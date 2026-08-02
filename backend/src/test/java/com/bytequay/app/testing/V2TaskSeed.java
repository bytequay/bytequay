/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.bytequay.app.testing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Objects;
import java.util.function.Consumer;

/**
 * Completes a hand-seeded V2 task so the current schema accepts it.
 *
 * <p>A task assignment no longer stands on its own: it has to point at a trunk
 * authorization whose owner graph — trunk, workspace, policy revision and workspace
 * repository — matches exactly, and triggers check all of it on insert. Tests used to
 * get this for free by seeding at an older schema version and letting the migration
 * chain fill the newer columns in; with a squashed baseline there is no chain to do
 * that, so the seed has to be right the first time.
 *
 * <p>Call {@link #insertAuthorized} with the assignment insert. The assignment and
 * authorization reference each other through deferred foreign keys, so they must be
 * committed together with the Trunk transition that authorizes creation.
 */
public final class V2TaskSeed
{
    private static final String DEFAULT_WORK_MODEL_SNAPSHOT =
            "{\"kind\":\"API\",\"agentOrProvider\":\"openai\","
                    + "\"model\":\"review-model\",\"account\":null,"
                    + "\"reasoningEffort\":null}";

    /**
     * The owner graph is checked against the trunk workspace's repository, which most
     * fixtures never had a reason to seed before. One repository per workspace and one
     * workspace per repository are both unique, so this tolerates already being set.
     */
    private static final String SEED_PRIMARY_REPOSITORY = """
            INSERT OR IGNORE INTO workspace_repos(
                workspace_id, repo_full_name, default_base_branch, added_at_ms)
            SELECT workspace.id, 'acme/widget', 'main', 1 FROM workspaces workspace
            """;

    /**
     * Only one workspace can hold a given repository, so any further workspace gets one
     * named after itself rather than silently going without.
     */
    private static final String SEED_REMAINING_REPOSITORIES = """
            INSERT OR IGNORE INTO workspace_repos(
                workspace_id, repo_full_name, default_base_branch, added_at_ms)
            SELECT workspace.id, 'acme/' || workspace.id, 'main', 1
            FROM workspaces workspace
            WHERE NOT EXISTS (
                SELECT 1 FROM workspace_repos existing
                WHERE existing.workspace_id = workspace.id)
            """;

    private static final String ADVANCE_TRUNK = """
            UPDATE threads
            SET aggregate_version = aggregate_version + 1
            WHERE id = (SELECT trunk_id FROM task_assignment WHERE id = '%1$s')
            """;

    private static final String RECORD_TRANSITION = """
            INSERT INTO trunk_transition(
                id, trunk_id, command_id, from_state, to_state,
                aggregate_version, cause, actor, occurred_at_ms)
            SELECT 'transition-' || assignment.id, trunk.id,
                'command-' || assignment.id, trunk.lifecycle_state,
                trunk.lifecycle_state, trunk.aggregate_version,
                'AUTHORIZE_TASK_CREATION', 'test', assignment.created_at_ms
            FROM task_assignment assignment
            JOIN threads trunk ON trunk.id = assignment.trunk_id
            WHERE assignment.id = '%1$s'
            """;

    /**
     * The authorization mirrors the assignment it belongs to. Base source has to agree
     * with the shape the assignment was seeded in, because a table CHECK pins which of
     * base_ref / planning_base_sha / the assignment shas may be set for each one.
     */
    private static final String AUTHORIZE = """
            INSERT INTO trunk_task_creation_authorization(
                id, trunk_id, workspace_id, command_id, actor, disposition,
                expected_trunk_version, returned_trunk_version, returned_lifecycle,
                assignment_id, policy_revision_id, provenance,
                repository_id, upstream_repository_id, publish_repository_id,
                base_source, base_repository_id, base_ref, planning_base_sha,
                assignment_base_sha, assignment_head_sha,
                engine_snapshot, work_model_snapshot, recorded_at_ms,
                task_name, task_type, opening_prompt, task_origin)
            SELECT
                assignment.creation_authorization_id,
                trunk.id,
                trunk.workspace_id,
                'command-' || assignment.id,
                'test',
                'AUTHORIZED',
                trunk.aggregate_version - 1,
                trunk.aggregate_version,
                trunk.lifecycle_state,
                assignment.id,
                policy.id,
                CASE assignment.kind
                    WHEN 'NEW_FROM_TRUNK' THEN CASE
                        WHEN assignment.planning_base_sha IS NULL
                            THEN 'DIRECT_USER' ELSE 'AGENT_HANDOFF' END
                    WHEN 'EXISTING_OWN_PR' THEN 'DIRECT_USER'
                    WHEN 'REVIEW_FINDINGS' THEN 'REVIEW_SESSION'
                    WHEN 'ISSUE' THEN 'ISSUE_MONITOR'
                    WHEN 'AUTOMATION' THEN 'AUTOMATION'
                    ELSE 'QUALITY_SCAN'
                END,
                COALESCE(assignment.repository_id, repository.repo_full_name),
                CASE WHEN assignment.repository_route = 'FORK'
                    THEN assignment.base_repository_id END,
                COALESCE(assignment.head_repository_id,
                    repository.repo_full_name),
                CASE
                    WHEN assignment.kind <> 'NEW_FROM_TRUNK' THEN 'EXISTING_PR_HEAD'
                    WHEN assignment.planning_base_sha IS NOT NULL
                        THEN 'PLANNING_SNAPSHOT'
                    ELSE 'FRESH_REMOTE_BASE'
                END,
                COALESCE(assignment.base_repository_id,
                    repository.repo_full_name),
                CASE WHEN assignment.kind = 'NEW_FROM_TRUNK'
                    THEN repository.default_base_branch ELSE assignment.base_ref END,
                CASE WHEN assignment.kind = 'NEW_FROM_TRUNK'
                    THEN assignment.planning_base_sha END,
                CASE WHEN assignment.kind <> 'NEW_FROM_TRUNK'
                    THEN assignment.remote_base_sha END,
                CASE WHEN assignment.kind <> 'NEW_FROM_TRUNK'
                    THEN assignment.remote_head_sha END,
                'engine',
                json('%2$s'),
                assignment.created_at_ms,
                'Test task ' || assignment.id,
                'DEVELOP',
                assignment.prompt,
                'user'
            FROM task_assignment assignment
            JOIN threads trunk ON trunk.id = assignment.trunk_id
            JOIN workspace_repos repository
              ON repository.workspace_id = trunk.workspace_id
            JOIN task_policy_revision policy ON policy.trunk_id = trunk.id
            WHERE assignment.id = '%1$s'
              AND policy.revision = (
                  SELECT MAX(newer.revision) FROM task_policy_revision newer
                  WHERE newer.trunk_id = trunk.id)
            """;

    private V2TaskSeed() {}

    /**
     * Gives every seeded workspace a repository. Must run before the assignment insert:
     * the assignment trigger itself demands a live V2 trunk workspace with a repository,
     * so doing it as part of authorizing afterwards is already too late.
     */
    public static void prepareWorkspaces(Connection connection)
            throws SQLException
    {
        execute(connection, SEED_PRIMARY_REPOSITORY);
        execute(connection, SEED_REMAINING_REPOSITORIES);
    }

    public static void prepareWorkspaces(JdbcTemplate jdbc)
    {
        jdbc.update(SEED_PRIMARY_REPOSITORY);
        jdbc.update(SEED_REMAINING_REPOSITORIES);
    }

    /**
     * Inserts the assignment and its exact Trunk authorization in one transaction.
     * The callback may also insert assignment-owned evidence, such as frozen review
     * findings, before the authorization trigger validates it.
     */
    public static void insertAuthorized(
            JdbcTemplate jdbc,
            String assignmentId,
            Consumer<JdbcTemplate> insert)
    {
        insertAuthorized(jdbc, assignmentId, DEFAULT_WORK_MODEL_SNAPSHOT, insert);
    }

    public static void insertAuthorized(
            JdbcTemplate jdbc,
            String assignmentId,
            String workModelSnapshot,
            Consumer<JdbcTemplate> insert)
    {
        Objects.requireNonNull(jdbc, "jdbc is null");
        Objects.requireNonNull(assignmentId, "assignmentId is null");
        Objects.requireNonNull(workModelSnapshot, "workModelSnapshot is null");
        Objects.requireNonNull(insert, "insert is null");
        var dataSource = Objects.requireNonNull(
                jdbc.getDataSource(), "JdbcTemplate DataSource is null");
        new TransactionTemplate(new DataSourceTransactionManager(dataSource))
                .executeWithoutResult(ignored -> {
                    insert.accept(jdbc);
                    authorize(jdbc, assignmentId, workModelSnapshot);
                });
    }

    /**
     * Commits a hand-seeded Task together with the deferred creation receipt cycle.
     * The callback inserts the Task row; missing canonical creation support rows are
     * derived from its frozen authorization before the transaction commits.
     */
    public static void insertCreated(
            JdbcTemplate jdbc,
            String taskId,
            Consumer<JdbcTemplate> insert)
    {
        Objects.requireNonNull(jdbc, "jdbc is null");
        Objects.requireNonNull(taskId, "taskId is null");
        Objects.requireNonNull(insert, "insert is null");
        var dataSource = Objects.requireNonNull(
                jdbc.getDataSource(), "JdbcTemplate DataSource is null");
        new TransactionTemplate(new DataSourceTransactionManager(dataSource))
                .executeWithoutResult(ignored -> {
                    insert.accept(jdbc);
                    seedCreationGraph(jdbc, taskId);
                });
    }

    /** Completes the canonical provisioning operation created by {@link #insertCreated}. */
    public static void completeProvisioning(
            JdbcTemplate jdbc,
            String taskId,
            String resultBaseSha,
            String resultHeadSha,
            String codeFingerprint,
            String evidence,
            long completedAtMs)
    {
        String provisionId = "provision-" + taskId;
        String ticketId = "provision-ticket-" + taskId;
        requireOne(jdbc.update("""
                UPDATE provision_task_operation
                SET status = 'DISPATCHED'
                WHERE id = ?
                """, provisionId), "Task provisioning operation is missing");
        requireOne(jdbc.update("""
                UPDATE dispatch_ticket
                SET version = version + 1, status = 'RESULT_PENDING',
                    pending_result_outcome = 'SUCCEEDED',
                    pending_result_payload = 'provisioned',
                    pending_result_evidence = ?,
                    pending_result_task_epoch = task_epoch,
                    pending_result_operation_id = operation_id,
                    pending_result_attempt = attempt,
                    pending_result_expected_code_fingerprint =
                        expected_code_fingerprint,
                    pending_result_expected_head_sha = expected_head_sha,
                    pending_result_expected_base_sha = expected_base_sha
                WHERE id = ?
                """, evidence, ticketId), "Task provisioning ticket is missing");
        requireOne(jdbc.update("""
                UPDATE provision_task_operation
                SET status = 'ACCEPTED', result_base_sha = ?, result_head_sha = ?,
                    result_code_fingerprint = ?, result_evidence = ?,
                    completed_at_ms = ?
                WHERE id = ?
                """, resultBaseSha, resultHeadSha, codeFingerprint, evidence,
                completedAtMs, provisionId),
                "Task provisioning operation is missing");
        requireOne(jdbc.update("""
                INSERT INTO task_code_identity(
                    task_id, provision_operation_id, repository_id,
                    upstream_repository_id, publish_repository_id,
                    branch_name, worktree_path, base_sha, local_head_sha,
                    code_fingerprint, created_at_ms, updated_at_ms)
                SELECT operation.task_id, operation.id, operation.repository_id,
                    context.upstream_repository_id,
                    context.publish_repository_id,
                    operation.requested_branch_name,
                    operation.requested_worktree_path,
                    operation.result_base_sha, operation.result_head_sha,
                    operation.result_code_fingerprint, ?, ?
                FROM provision_task_operation operation
                JOIN task_creation_context context
                  ON context.task_id = operation.task_id
                WHERE operation.id = ?
                  AND NOT EXISTS (
                      SELECT 1 FROM task_code_identity existing
                      WHERE existing.task_id = operation.task_id)
                """, completedAtMs, completedAtMs, provisionId),
                "Task code identity is missing");
        requireOne(jdbc.update("""
                UPDATE dispatch_ticket
                SET version = version + 1, status = 'SUCCEEDED',
                    pending_result_outcome = NULL,
                    pending_result_payload = NULL,
                    pending_result_evidence = NULL,
                    pending_result_error = NULL,
                    pending_result_task_epoch = NULL,
                    pending_result_stage_id = NULL,
                    pending_result_stage_generation = NULL,
                    pending_result_operation_id = NULL,
                    pending_result_attempt = NULL,
                    pending_result_expected_code_fingerprint = NULL,
                    pending_result_expected_head_sha = NULL,
                    pending_result_expected_base_sha = NULL,
                    delivery_acceptance = 'ACCEPTED', delivery_evidence = ?,
                    completed_at_ms = ?
                WHERE id = ?
                """, evidence, completedAtMs, ticketId),
                "Task provisioning ticket is missing");
    }

    public static void authorize(Connection connection, String assignmentId)
            throws SQLException
    {
        execute(connection, ADVANCE_TRUNK.formatted(assignmentId));
        execute(connection, RECORD_TRANSITION.formatted(assignmentId));
        execute(connection, AUTHORIZE.formatted(
                assignmentId, DEFAULT_WORK_MODEL_SNAPSHOT));
    }

    private static void authorize(
            JdbcTemplate jdbc, String assignmentId, String workModelSnapshot)
    {
        requireOne(jdbc.update(ADVANCE_TRUNK.formatted(assignmentId)),
                "Task assignment Trunk is missing");
        requireOne(jdbc.update(RECORD_TRANSITION.formatted(assignmentId)),
                "Task assignment Trunk transition is missing");
        requireOne(jdbc.update(AUTHORIZE.formatted(
                        assignmentId, workModelSnapshot.replace("'", "''"))),
                "Task assignment authorization is missing");
    }

    private static void seedCreationGraph(JdbcTemplate jdbc, String taskId)
    {
        jdbc.update("""
                INSERT INTO task_creation_context(
                    task_id, assignment_id, policy_revision_id, authorization_id,
                    provenance, repository_id, upstream_repository_id,
                    publish_repository_id, base_source, base_repository_id,
                    base_ref, planning_base_sha, assignment_base_sha,
                    assignment_head_sha, engine_snapshot, work_model_snapshot,
                    created_at_ms, task_name, task_type, linked_issue_number,
                    opening_prompt, task_origin)
                SELECT task.id, task.assignment_id, task.policy_revision_id,
                    authorization.id, authorization.provenance,
                    authorization.repository_id,
                    authorization.upstream_repository_id,
                    authorization.publish_repository_id,
                    authorization.base_source,
                    authorization.base_repository_id, authorization.base_ref,
                    authorization.planning_base_sha,
                    authorization.assignment_base_sha,
                    authorization.assignment_head_sha,
                    authorization.engine_snapshot,
                    authorization.work_model_snapshot, task.created_at_ms,
                    authorization.task_name, authorization.task_type,
                    authorization.linked_issue_number,
                    authorization.opening_prompt, authorization.task_origin
                FROM tasks task
                JOIN task_assignment assignment ON assignment.id = task.assignment_id
                JOIN trunk_task_creation_authorization authorization
                  ON authorization.id = assignment.creation_authorization_id
                WHERE task.id = ?
                  AND NOT EXISTS (
                      SELECT 1 FROM task_creation_context existing
                      WHERE existing.task_id = task.id)
                """, taskId);
        jdbc.update("""
                INSERT INTO task_brain(
                    id, task_id, provider, model, engine_snapshot, created_at_ms)
                SELECT 'brain-' || task.id, task.id, 'openai', 'review-model',
                    context.engine_snapshot, task.created_at_ms
                FROM tasks task
                JOIN task_creation_context context ON context.task_id = task.id
                WHERE task.id = ?
                  AND NOT EXISTS (
                      SELECT 1 FROM task_brain existing
                      WHERE existing.task_id = task.id)
                """, taskId);
        jdbc.update("""
                INSERT INTO task_provision_target(
                    task_id, repository_id, publish_repository_id,
                    branch_name, worktree_path, created_at_ms)
                SELECT task.id, context.repository_id,
                    context.publish_repository_id, 'dev/' || task.id,
                    '/tmp/' || task.id, task.created_at_ms
                FROM tasks task
                JOIN task_creation_context context ON context.task_id = task.id
                WHERE task.id = ?
                  AND NOT EXISTS (
                      SELECT 1 FROM task_provision_target existing
                      WHERE existing.task_id = task.id)
                """, taskId);
        jdbc.update("""
                INSERT INTO provision_task_operation(
                    id, task_id, task_epoch, assignment_id, operation_id,
                    semantic_attempt, repository_id, expected_base_sha,
                    expected_remote_head_sha, requested_branch_name,
                    requested_worktree_path, status, created_at_ms,
                    base_source, base_repository_id, base_ref)
                SELECT 'provision-' || task.id, task.id, task.epoch,
                    task.assignment_id, 'provision-operation-' || task.id, 1,
                    target.repository_id,
                    CASE context.base_source
                        WHEN 'PLANNING_SNAPSHOT' THEN context.planning_base_sha
                        WHEN 'EXISTING_PR_HEAD' THEN context.assignment_base_sha END,
                    CASE context.base_source WHEN 'EXISTING_PR_HEAD'
                        THEN context.assignment_head_sha END,
                    target.branch_name, target.worktree_path, 'REQUESTED',
                    task.created_at_ms, context.base_source,
                    context.base_repository_id, context.base_ref
                FROM tasks task
                JOIN task_creation_context context ON context.task_id = task.id
                JOIN task_provision_target target ON target.task_id = task.id
                WHERE task.id = ?
                  AND NOT EXISTS (
                      SELECT 1 FROM provision_task_operation existing
                      WHERE existing.task_id = task.id)
                """, taskId);
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    exclusive_task, writer_required, workspace_id, trunk_id,
                    task_id, task_epoch, attempt, expected_head_sha,
                    expected_base_sha, status, created_at_ms)
                SELECT 'provision-ticket-' || task.id, operation.operation_id,
                    'PROVISION_TASK', 'LOCAL_GIT', 'TASK', task.id,
                    'TASK_PROVISION_RESULT', CASE
                        WHEN context.base_source = 'EXISTING_PR_HEAD'
                          AND context.assignment_base_sha IS NULL
                          AND context.assignment_head_sha IS NULL THEN 48
                        ELSE 16 END,
                    1, 1, trunk.workspace_id, task.thread_id, task.id,
                    task.epoch, operation.semantic_attempt,
                    operation.expected_remote_head_sha,
                    operation.expected_base_sha, 'REQUESTED', task.created_at_ms
                FROM tasks task
                JOIN threads trunk ON trunk.id = task.thread_id
                JOIN task_creation_context context ON context.task_id = task.id
                JOIN provision_task_operation operation
                  ON operation.task_id = task.id
                WHERE task.id = ?
                  AND NOT EXISTS (
                      SELECT 1 FROM dispatch_ticket existing
                      WHERE existing.operation_id = operation.operation_id)
                """, taskId);
        jdbc.update("""
                INSERT INTO task_transition(
                    id, task_id, command_id, epoch, from_state, to_state,
                    aggregate_version, cause, actor, occurred_at_ms)
                SELECT 'creation-transition-' || task.id, task.id,
                    authorization.command_id, task.epoch, NULL, 'PROVISIONING',
                    task.aggregate_version, 'CREATE_TASK', authorization.actor,
                    task.created_at_ms
                FROM tasks task
                JOIN task_assignment assignment ON assignment.id = task.assignment_id
                JOIN trunk_task_creation_authorization authorization
                  ON authorization.id = assignment.creation_authorization_id
                WHERE task.id = ?
                  AND NOT EXISTS (
                      SELECT 1 FROM task_transition existing
                      WHERE existing.task_id = task.id
                        AND existing.aggregate_version = task.aggregate_version)
                """, taskId);
        requireOne(jdbc.update("""
                INSERT INTO task_creation_receipt(
                    id, trunk_id, workspace_id, command_id, actor,
                    authorization_id, task_id, task_seq, task_epoch,
                    task_version, returned_lifecycle, assignment_id,
                    policy_revision_id, task_brain_id, provision_operation_id,
                    dispatch_ticket_id, operation_id, semantic_attempt,
                    requested_branch_name, requested_worktree_path, recorded_at_ms)
                SELECT task.creation_receipt_id, task.thread_id,
                    trunk.workspace_id, authorization.command_id,
                    authorization.actor, authorization.id, task.id, task.seq,
                    task.epoch, task.aggregate_version, task.lifecycle_state,
                    task.assignment_id, task.policy_revision_id, brain.id,
                    operation.id, ticket.id, operation.operation_id,
                    operation.semantic_attempt, target.branch_name,
                    target.worktree_path, task.created_at_ms
                FROM tasks task
                JOIN threads trunk ON trunk.id = task.thread_id
                JOIN task_assignment assignment ON assignment.id = task.assignment_id
                JOIN trunk_task_creation_authorization authorization
                  ON authorization.id = assignment.creation_authorization_id
                JOIN task_brain brain ON brain.task_id = task.id
                JOIN task_provision_target target ON target.task_id = task.id
                JOIN provision_task_operation operation
                  ON operation.task_id = task.id
                JOIN dispatch_ticket ticket
                  ON ticket.operation_id = operation.operation_id
                WHERE task.id = ?
                """, taskId), "Task creation receipt is missing");
    }

    private static void requireOne(int updated, String message)
    {
        if (updated != 1) {
            throw new IllegalStateException(message);
        }
    }

    private static void execute(Connection connection, String sql)
            throws SQLException
    {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(sql);
        }
    }
}
