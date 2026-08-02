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
package com.bytequay.app.repository.sqlite.migration;

import com.bytequay.app.testing.MigratedSqliteDatabase;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestDevelopmentFlowTaskCreationProtocolMigration
{
    @TempDir
    private Path tempDir;

    @Test
    void createsZeroTaskAndParallelSiblingTasksThroughExactTrunkAuthorizations()
            throws Exception
    {
        String url = migrated("siblings.db");
        try (Connection connection = connect(url)) {
            seedDirectTrunk(connection, "workspace-1", "trunk-1", "acme/widget");
            execute(connection, policySql("policy-1", "trunk-1", 1));

            createNewTask(connection, new NewTask(
                    "task-1", 1, "create-command-1", "trunk-1", "workspace-1",
                    "policy-1", "acme/widget", "base-1"));
            createNewTask(connection, new NewTask(
                    "task-2", 2, "create-command-2", "trunk-1", "workspace-1",
                    "policy-1", "acme/widget", null));

            assertThat(number(connection, """
                    SELECT COUNT(*) FROM tasks
                    WHERE thread_id = 'trunk-1' AND lifecycle_state = 'PROVISIONING'
                      AND epoch = 1 AND aggregate_version = 0
                    """)).isEqualTo(2);
            assertThat(text(connection, """
                    SELECT group_concat(seq || ':' || creation_receipt_id, ',')
                    FROM (SELECT seq, creation_receipt_id FROM tasks
                          WHERE thread_id = 'trunk-1' ORDER BY seq)
                    """)).isEqualTo("1:creation-receipt-task-1,2:creation-receipt-task-2");
            assertThat(text(connection, """
                    SELECT group_concat(base_source || ':' || ifnull(expected_base_sha, 'fresh'), ',')
                    FROM (SELECT base_source, expected_base_sha
                          FROM provision_task_operation ORDER BY task_id)
                    """)).isEqualTo("PLANNING_SNAPSHOT:base-1,FRESH_REMOTE_BASE:fresh");
            assertThat(number(connection, """
                    SELECT aggregate_version FROM threads WHERE id = 'trunk-1'
                    """)).isEqualTo(2);
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM trunk_transition
                    WHERE trunk_id = 'trunk-1' AND cause = 'AUTHORIZE_TASK_CREATION'
                    """)).isEqualTo(2);
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM dispatch_ticket
                    WHERE operation_kind = 'PROVISION_TASK' AND lane_mask = 16
                    """)).isEqualTo(2);
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM task_current_stage
                    WHERE task_id IN ('task-1', 'task-2')
                    """)).isZero();
            assertThat(number(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"))
                    .isZero();
        }

        try (Connection connection = connect(url)) {
            assertThat(number(connection, "SELECT COUNT(*) FROM task_creation_receipt"))
                    .isEqualTo(2);
            assertThat(number(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"))
                    .isZero();
        }
    }

    @Test
    void duplicateCommandsAndIncompleteOrWrongLaneBundlesRollBack()
            throws Exception
    {
        String url = migrated("creation-conflicts.db");
        try (Connection connection = connect(url)) {
            seedDirectTrunk(connection, "workspace-1", "trunk-1", "acme/widget");
            execute(connection, policySql("policy-1", "trunk-1", 1));
            NewTask first = new NewTask(
                    "task-1", 1, "same-command", "trunk-1", "workspace-1",
                    "policy-1", "acme/widget", "base-1");
            createNewTask(connection, first);

            assertThatThrownBy(() -> createNewTask(connection, new NewTask(
                    "task-2", 2, "same-command", "trunk-1", "workspace-1",
                    "policy-1", "acme/widget", null)))
                    .isInstanceOf(SQLException.class);
            assertThat(number(connection, "SELECT COUNT(*) FROM tasks")).isOne();
            assertThat(number(connection, "SELECT COUNT(*) FROM task_assignment")).isOne();
            assertThat(number(connection, """
                    SELECT aggregate_version FROM threads WHERE id = 'trunk-1'
                    """)).isOne();

            NewTask incomplete = new NewTask(
                    "incomplete-task", 2, "incomplete-command", "trunk-1",
                    "workspace-1", "policy-1", "acme/widget", null);
            Authorization incompleteAuthorization = authorizeNewTask(connection, incomplete);
            connection.setAutoCommit(false);
            try {
                execute(connection, taskSql(
                        incomplete.taskId(), incomplete.seq(), incomplete.trunkId(),
                        incompleteAuthorization.assignmentId(), incomplete.policyId(),
                        "missing-receipt"));
                assertThatThrownBy(connection::commit).isInstanceOf(SQLException.class);
                connection.rollback();
            }
            finally {
                connection.setAutoCommit(true);
            }
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM tasks WHERE id = 'incomplete-task'
                    """)).isZero();
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM trunk_task_creation_authorization
                    WHERE id = 'authorization-incomplete-task'
                    """)).isOne();

            NewTask wrongLane = new NewTask(
                    "wrong-lane-task", 2, "wrong-lane-command", "trunk-1",
                    "workspace-1", "policy-1", "acme/widget", null);
            Authorization wrongLaneAuthorization = authorizeNewTask(connection, wrongLane);
            assertThatThrownBy(() -> createAuthorizedTask(
                    connection, wrongLane, wrongLaneAuthorization, 17))
                    .isInstanceOf(SQLException.class);
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM tasks WHERE id = 'wrong-lane-task'
                    """)).isZero();
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM provision_task_operation
                    WHERE task_id = 'wrong-lane-task'
                    """)).isZero();
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM dispatch_ticket
                    WHERE task_id = 'wrong-lane-task'
                    """)).isZero();
            assertThat(number(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"))
                    .isZero();
        }
    }

    @Test
    void existingPullRequestsFreezeDirectAndForkBaseAndHeadProvenance()
            throws Exception
    {
        String url = migrated("existing-pr.db");
        try (Connection connection = connect(url)) {
            seedDirectTrunk(connection, "workspace-direct", "trunk-direct", "acme/direct");
            execute(connection, policySql("policy-direct", "trunk-direct", 1));
            createExistingPrTask(connection, new ExistingPrTask(
                    "direct-task", "direct-command", "trunk-direct", "workspace-direct",
                    "policy-direct", "acme/direct", "acme/direct", null,
                    "main", "feature/direct", "base-direct", "head-direct", 41));

            seedDirectTrunk(connection, "workspace-upstream", "trunk-upstream", "acme/widget");
            seedDirectTrunk(connection, "workspace-fork", "trunk-fork", "jack/widget");
            execute(connection, """
                    INSERT INTO workspace_relation(
                        workspace_id, upstream_workspace_id,
                        created_at_ms, updated_at_ms)
                    VALUES ('workspace-fork', 'workspace-upstream', 2, 2)
                    """);
            execute(connection, policySql("policy-fork", "trunk-fork", 1));
            createExistingPrTask(connection, new ExistingPrTask(
                    "fork-task", "fork-command", "trunk-fork", "workspace-fork",
                    "policy-fork", "acme/widget", "jack/widget", "acme/widget",
                    "main", "feature/fork", "base-fork", "head-fork", 42));

            assertThat(text(connection, """
                    SELECT repository_route || '|' || base_repository_id || '|'
                        || head_repository_id || '|' || head_ref || '|' || remote_head_sha
                    FROM task_assignment WHERE id = 'assignment-fork-task'
                    """)).isEqualTo(
                    "FORK|acme/widget|jack/widget|feature/fork|head-fork");
            assertThat(text(connection, """
                    SELECT base_source || '|' || repository_id || '|'
                        || upstream_repository_id || '|' || base_repository_id || '|'
                        || base_ref || '|' || assignment_base_sha || '|'
                        || assignment_head_sha
                    FROM task_creation_context WHERE task_id = 'fork-task'
                    """)).isEqualTo(
                    "EXISTING_PR_HEAD|jack/widget|acme/widget|acme/widget|main|base-fork|head-fork");
            assertThat(text(connection, """
                    SELECT expected_base_sha || '|' || expected_remote_head_sha
                    FROM provision_task_operation WHERE task_id = 'fork-task'
                    """)).isEqualTo("base-fork|head-fork");
            assertFails(connection, existingAssignmentSql(
                    "wrong-fork", "trunk-fork", "authorization-wrong-fork",
                    "DIRECT", "acme/widget", "jack/widget", "main",
                    "feature/wrong", "base-x", "head-x", 43));
        }
    }

    @Test
    void reviewFindingsFreezeDirectAndForkReviewedPullRequestsAndRejectFreshBase()
            throws Exception
    {
        String url = migrated("review-findings.db");
        try (Connection connection = connect(url)) {
            seedDirectTrunk(connection, "workspace-direct", "trunk-direct", "acme/direct");
            execute(connection, policySql("policy-direct", "trunk-direct", 1));
            createReviewFindingsTask(connection, new ExistingPrTask(
                    "review-direct-task", "review-direct-command", "trunk-direct",
                    "workspace-direct", "policy-direct", "acme/direct", "acme/direct",
                    null, "main", "feature/direct", "base-direct", "reviewed-direct", 51),
                    "review-session-direct");

            seedDirectTrunk(connection, "workspace-upstream", "trunk-upstream", "acme/widget");
            seedDirectTrunk(connection, "workspace-fork", "trunk-fork", "jack/widget");
            execute(connection, """
                    INSERT INTO workspace_relation(
                        workspace_id, upstream_workspace_id,
                        created_at_ms, updated_at_ms)
                    VALUES ('workspace-fork', 'workspace-upstream', 2, 2)
                    """);
            execute(connection, policySql("policy-fork", "trunk-fork", 1));
            createReviewFindingsTask(connection, new ExistingPrTask(
                    "review-fork-task", "review-fork-command", "trunk-fork",
                    "workspace-fork", "policy-fork", "acme/widget", "jack/widget",
                    "acme/widget", "main", "feature/fork", "base-fork", "reviewed-fork", 52),
                    "review-session-fork");

            assertThat(text(connection, """
                    SELECT source_id || '|' || pr_number || '|' || repository_route || '|'
                        || base_repository_id || '|' || head_repository_id || '|'
                        || remote_base_sha || '|' || remote_head_sha
                    FROM task_assignment
                    WHERE id = 'assignment-review-direct-task'
                    """)).isEqualTo(
                    "review-session-direct|51|DIRECT|acme/direct|acme/direct|base-direct|reviewed-direct");
            assertThat(text(connection, """
                    SELECT source_review_id || '|' || finding_id
                    FROM task_assignment_review_finding
                    WHERE assignment_id = 'assignment-review-direct-task'
                    """)).isEqualTo(
                    "review-session-direct|finding-review-direct-task");
            assertThat(text(connection, """
                    SELECT provenance || '|' || base_source || '|'
                        || upstream_repository_id || '|' || base_repository_id || '|'
                        || assignment_base_sha || '|' || assignment_head_sha
                    FROM task_creation_context
                    WHERE task_id = 'review-fork-task'
                    """)).isEqualTo(
                    "REVIEW_SESSION|EXISTING_PR_HEAD|acme/widget|acme/widget|base-fork|reviewed-fork");
            assertThat(text(connection, """
                    SELECT expected_base_sha || '|' || expected_remote_head_sha
                    FROM provision_task_operation
                    WHERE task_id = 'review-fork-task'
                    """)).isEqualTo("base-fork|reviewed-fork");

            Authorization freshReview = new Authorization(
                    "authorization-review-fresh", "assignment-review-fresh",
                    "review-fresh-command", "trunk-direct", "workspace-direct",
                    "policy-direct", "REVIEW_SESSION", "FRESH_REMOTE_BASE",
                    "acme/direct", null, "acme/direct", "acme/direct", "main",
                    null, null, null);
            assertThatThrownBy(() -> authorizeAssignment(
                    connection,
                    reviewAssignmentSql(
                            "assignment-review-fresh", "trunk-direct",
                            "authorization-review-fresh", "review-session-fresh", "DIRECT",
                            "acme/direct", "acme/direct", "main", "feature/fresh",
                            "base-fresh", "reviewed-fresh", 53),
                    reviewFindingSql(
                            "assignment-review-fresh", "review-session-fresh",
                            "finding-review-fresh"),
                    freshReview))
                    .isInstanceOf(SQLException.class);
            assertThat(number(connection, """
                    SELECT COUNT(*) FROM task_assignment
                    WHERE id = 'assignment-review-fresh'
                    """)).isZero();
        }
    }

    @Test
    void assignmentVariantsAndPolicyRevisionsRejectAmbiguousIdentity()
            throws Exception
    {
        String url = migrated("assignment-variants.db");
        try (Connection connection = connect(url)) {
            seedDirectTrunk(connection, "workspace-1", "trunk-1", "acme/widget");
            execute(connection, policySql("policy-1", "trunk-1", 1));
            assertFails(connection, policySql("policy-3", "trunk-1", 3));
            execute(connection, policySql("policy-2", "trunk-1", 2));
            assertFails(connection, """
                    UPDATE task_policy_revision SET max_brain_rounds = 99
                    WHERE id = 'policy-2'
                    """);

            authorizeNewTask(connection, new NewTask(
                    "new", 1, "authorize-new", "trunk-1", "workspace-1",
                    "policy-2", "acme/widget", null));
            authorizeExistingPrTask(connection, new ExistingPrTask(
                    "existing", "authorize-existing", "trunk-1", "workspace-1",
                    "policy-2", "acme/widget", "acme/widget", null,
                    "main", "feature", "base-1", "head-1", 42));

            authorizeReviewFindingsTask(connection, new ExistingPrTask(
                    "review", "authorize-review", "trunk-1", "workspace-1",
                    "policy-2", "acme/widget", "acme/widget", null,
                    "main", "feature/review", "base-review", "head-review", 43),
                    "review-1");
            authorizeSourceAssignment(connection,
                    "issue", "ISSUE", "issue-42",
                    "ISSUE_MONITOR", "authorize-issue", "policy-2");
            authorizeAutomationAssignment(connection, "policy-2");
            authorizeSourceAssignment(connection,
                    "quality", "QUALITY_SCAN", "scan-7",
                    "QUALITY_SCAN", "authorize-quality", "policy-2");

            assertThat(number(connection, "SELECT COUNT(*) FROM task_assignment"))
                    .isEqualTo(6);
            assertThat(number(connection, "SELECT COUNT(*) FROM trunk_task_creation_authorization"))
                    .isEqualTo(6);
            assertFails(connection, """
                    INSERT INTO task_assignment(
                        id, trunk_id, kind, source_id, prompt,
                        created_by, created_at_ms, creation_authorization_id)
                    VALUES ('ambiguous-issue', 'trunk-1', 'ISSUE', 'issue-99',
                        'extra prompt', 'user', 2, 'authorization-ambiguous')
                    """);
            assertFails(connection, """
                    UPDATE task_assignment_review_finding
                    SET finding_revision = 3
                    WHERE assignment_id = 'assignment-review'
                    """);
            assertFails(connection, """
                    DELETE FROM task_assignment_review_finding
                    WHERE assignment_id = 'assignment-review'
                    """);
        }
    }

    @Test
    void freshBaseResultKeepsRequestFenceNullAndDeliversDiscoveredSubject()
            throws Exception
    {
        String url = migrated("fresh-base-result.db");
        try (Connection connection = connect(url)) {
            seedDirectTrunk(connection, "workspace-1", "trunk-1", "acme/widget");
            execute(connection, policySql("policy-1", "trunk-1", 1));
            NewTask task = new NewTask(
                    "task-1", 1, "create-command", "trunk-1", "workspace-1",
                    "policy-1", "acme/widget", null);
            createNewTask(connection, task);

            assertThat(text(connection, """
                    SELECT base_source || '|' || ifnull(expected_base_sha, 'null') || '|'
                        || ifnull(expected_remote_head_sha, 'null')
                    FROM provision_task_operation WHERE task_id = 'task-1'
                    """)).isEqualTo("FRESH_REMOTE_BASE|null|null");
            execute(connection, """
                    UPDATE provision_task_operation SET status = 'DISPATCHED'
                    WHERE id = 'provision-task-1'
                    """);
            connection.setAutoCommit(false);
            try {
                execute(connection, """
                        UPDATE dispatch_ticket
                        SET version = version + 1, status = 'RESULT_PENDING',
                            pending_result_outcome = 'SUCCEEDED',
                            pending_result_payload = 'stale result fence',
                            pending_result_evidence = 'git-adapter-evidence',
                            pending_result_task_epoch = 1,
                            pending_result_operation_id = 'different-operation',
                            pending_result_attempt = 1
                        WHERE id = 'provision-ticket-task-1'
                        """);
                assertFails(connection, """
                        UPDATE provision_task_operation
                        SET status = 'ACCEPTED', result_base_sha = 'resolved-base',
                            result_head_sha = 'resolved-base',
                            result_code_fingerprint = 'fp-1',
                            result_evidence = 'git-adapter-evidence',
                            completed_at_ms = 10
                        WHERE id = 'provision-task-1'
                        """);
                connection.rollback();
            }
            finally {
                connection.setAutoCommit(true);
            }
            execute(connection, """
                    UPDATE dispatch_ticket
                    SET version = version + 1, status = 'RESULT_PENDING',
                        pending_result_outcome = 'SUCCEEDED',
                        pending_result_payload = 'resolved fresh remote base',
                        pending_result_evidence = 'git-adapter-evidence',
                        pending_result_task_epoch = 1,
                        pending_result_operation_id = 'provision-operation-task-1',
                        pending_result_attempt = 1
                    WHERE id = 'provision-ticket-task-1'
                    """);
            assertFails(connection, """
                    UPDATE provision_task_operation
                    SET status = 'ACCEPTED', result_base_sha = 'resolved-base',
                        result_head_sha = 'different-head',
                        result_code_fingerprint = 'fp-1',
                        result_evidence = 'git-adapter-evidence',
                        completed_at_ms = 10
                    WHERE id = 'provision-task-1'
                    """);
            assertFails(connection, """
                    UPDATE provision_task_operation
                    SET status = 'ACCEPTED', result_base_sha = 'resolved-base',
                        result_head_sha = 'resolved-base',
                        result_code_fingerprint = 'fp-1', completed_at_ms = 10
                    WHERE id = 'provision-task-1'
                    """);
            assertFails(connection, """
                    UPDATE provision_task_operation
                    SET status = 'ACCEPTED', result_base_sha = 'resolved-base',
                        result_head_sha = 'resolved-base',
                        result_code_fingerprint = '   ',
                        result_evidence = 'git-adapter-evidence',
                        completed_at_ms = 10
                    WHERE id = 'provision-task-1'
                    """);
            execute(connection, """
                    UPDATE provision_task_operation
                    SET status = 'ACCEPTED', result_base_sha = 'resolved-base',
                        result_head_sha = 'resolved-base',
                        result_code_fingerprint = 'fp-1',
                        result_evidence = 'git-adapter-evidence',
                        completed_at_ms = 10
                    WHERE id = 'provision-task-1'
                    """);
            execute(connection, """
                    INSERT INTO task_code_identity(
                        task_id, provision_operation_id, repository_id,
                        publish_repository_id, branch_name, worktree_path,
                        base_sha, local_head_sha, code_fingerprint,
                        created_at_ms, updated_at_ms)
                    VALUES ('task-1', 'provision-task-1', 'acme/widget',
                        'acme/widget', 'dev/task-1', '/tmp/task-1',
                        'resolved-base', 'resolved-base', 'fp-1', 10, 10)
                    """);

            execute(connection, """
                    INSERT INTO stage(
                        id, task_id, kind, generation, version, checkpoint, opened_at_ms)
                    VALUES ('plan-stage-1', 'task-1', 'PLAN', 1, 0, 'DRAFTING', 11)
                    """);
            execute(connection, """
                    INSERT INTO task_current_stage(task_id, stage_id, stage_generation)
                    VALUES ('task-1', 'plan-stage-1', 1)
                    """);
            execute(connection, """
                    UPDATE tasks
                    SET lifecycle_state = 'ACTIVE', aggregate_version = 1
                    WHERE id = 'task-1'
                    """);
            execute(connection, """
                    INSERT INTO plan_stage(stage_id, task_id, generation, opened_for_epoch)
                    VALUES ('plan-stage-1', 'task-1', 1, 1)
                    """);
            execute(connection, """
                    INSERT INTO task_transition(
                        id, task_id, command_id, epoch, from_state, to_state,
                        aggregate_version, cause, actor, occurred_at_ms)
                    VALUES ('accept-transition-1', 'task-1', 'accept-command', 1,
                        'PROVISIONING', 'ACTIVE', 1, 'ACCEPT_PROVISIONING',
                        'result-delivery', 11)
                    """);
            execute(connection, """
                    INSERT INTO task_command_receipt(
                        id, task_id, command_id, cause, actor, disposition,
                        expected_task_epoch, expected_task_version,
                        subject_task_epoch, subject_stage_generation,
                        subject_operation_id, subject_attempt,
                        subject_expected_code_fingerprint,
                        subject_expected_head_sha, subject_expected_base_sha,
                        proof_id, next_stage_id, next_stage_kind,
                        next_stage_generation, returned_trunk_id,
                        returned_lifecycle, returned_epoch, returned_version,
                        returned_current_stage_id, recorded_at_ms)
                    VALUES ('accept-receipt-1', 'task-1', 'accept-command',
                        'ACCEPT_PROVISIONING', 'result-delivery', 'APPLIED',
                        1, 0, 1, 0, 'provision-operation-task-1', 1,
                        'fp-1', 'resolved-base', 'resolved-base',
                        'provision-operation-task-1', 'plan-stage-1', 'PLAN', 1,
                        'trunk-1', 'ACTIVE', 1, 1, 'plan-stage-1', 11)
                    """);
        }

        try (Connection connection = connect(url)) {
            assertThat(text(connection, """
                    SELECT ifnull(ticket.expected_base_sha, 'null') || '|'
                        || ifnull(ticket.expected_head_sha, 'null') || '|'
                        || receipt.subject_expected_base_sha || '|'
                        || receipt.subject_expected_head_sha || '|'
                        || receipt.subject_expected_code_fingerprint
                    FROM dispatch_ticket ticket
                    JOIN task_command_receipt receipt ON receipt.task_id = ticket.task_id
                    WHERE ticket.id = 'provision-ticket-task-1'
                      AND receipt.id = 'accept-receipt-1'
                    """)).isEqualTo("null|null|resolved-base|resolved-base|fp-1");
            assertThat(text(connection, """
                    SELECT result_evidence FROM provision_task_operation
                    WHERE id = 'provision-task-1'
                    """)).isEqualTo("git-adapter-evidence");
            assertThat(number(connection, "SELECT COUNT(*) FROM pragma_foreign_key_check"))
                    .isZero();
        }
    }

    private String migrated(String file)
    {
        String url = url(file);
        migrate(url);
        return url;
    }

    private String url(String file)
    {
        return "jdbc:sqlite:" + tempDir.resolve(file) + "?foreign_keys=ON";
    }

    private static void migrate(String url)
    {
        MigratedSqliteDatabase.migrate(url);
    }

    private static Connection connect(String url)
            throws SQLException
    {
        Connection connection = DriverManager.getConnection(url);
        execute(connection, "PRAGMA foreign_keys = ON");
        return connection;
    }

    private static void seedDirectTrunk(
            Connection connection, String workspaceId, String trunkId, String repository)
            throws SQLException
    {
        execute(connection, """
                INSERT INTO workspaces(
                    id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
                VALUES ('%1$s', '%1$s', '', 0, 1, 1)
                """.formatted(workspaceId));
        execute(connection, """
                INSERT INTO workspace_repos(
                    workspace_id, repo_full_name, default_base_branch,
                    auto_fix_enabled, added_at_ms)
                VALUES ('%s', '%s', 'main', 0, 1)
                """.formatted(workspaceId, repository));
        execute(connection, """
                INSERT INTO threads(
                    id, kind, provider, title, status, model, cost_usd_milli,
                    tokens_in, tokens_out, created_at_ms, updated_at_ms,
                    workspace_id, flow, parallel_slots, turn_version,
                    lifecycle_state)
                VALUES ('%1$s', 'CLI_AGENT', 'claude-code', '%1$s', 'IDLE',
                    'claude-sonnet-4.6', 0, 0, 0, 1, 1, '%2$s',
                    'build', 4, 'V2', 'IDLE')
                """.formatted(trunkId, workspaceId));
    }

    private static String policySql(String id, String trunkId, int revision)
    {
        return """
                INSERT INTO task_policy_revision(
                    id, trunk_id, revision, source, auto_approve,
                    created_by, created_at_ms)
                VALUES ('%s', '%s', %s, 'TRUNK', 0, 'user', 2)
                """.formatted(id, trunkId, revision);
    }

    private static String newAssignmentSql(
            String id, String trunkId, String authorizationId, String baseSha)
    {
        return """
                INSERT INTO task_assignment(
                    id, trunk_id, kind, planning_base_sha, plan_seed, prompt,
                    created_by, created_at_ms, creation_authorization_id)
                VALUES ('%s', '%s', 'NEW_FROM_TRUNK', %s,
                    'seed for %s', 'build %s', 'user', 3, '%s')
                """.formatted(
                id, trunkId, sql(baseSha), id, id, authorizationId);
    }

    private static String existingAssignmentSql(
            String id,
            String trunkId,
            String authorizationId,
            String route,
            String baseRepository,
            String headRepository,
            String baseRef,
            String headRef,
            String baseSha,
            String headSha,
            int prNumber)
    {
        return """
                INSERT INTO task_assignment(
                    id, trunk_id, kind, repository_id, pr_number,
                    remote_head_sha, base_repository_id, head_repository_id,
                    base_ref, head_ref, remote_base_sha, repository_route,
                    created_by, created_at_ms, creation_authorization_id)
                VALUES ('%s', '%s', 'EXISTING_OWN_PR', '%s', %s, '%s',
                    '%s', '%s', '%s', '%s', '%s', '%s', 'user', 3, '%s')
                """.formatted(
                id, trunkId, headRepository, prNumber, headSha,
                baseRepository, headRepository, baseRef, headRef, baseSha, route,
                authorizationId);
    }

    private static String reviewAssignmentSql(
            String id,
            String trunkId,
            String authorizationId,
            String sourceReviewId,
            String route,
            String baseRepository,
            String headRepository,
            String baseRef,
            String headRef,
            String baseSha,
            String headSha,
            int prNumber)
    {
        return """
                INSERT INTO task_assignment(
                    id, trunk_id, kind, source_id, repository_id, pr_number,
                    remote_head_sha, selected_findings_json, base_repository_id,
                    head_repository_id, base_ref, head_ref, remote_base_sha,
                    repository_route, created_by, created_at_ms,
                    creation_authorization_id)
                VALUES ('%s', '%s', 'REVIEW_FINDINGS', '%s', '%s', %s, '%s', '[]',
                    '%s', '%s', '%s', '%s', '%s', '%s', 'user', 3, '%s')
                """.formatted(
                id, trunkId, sourceReviewId, headRepository, prNumber, headSha,
                baseRepository, headRepository, baseRef, headRef, baseSha, route,
                authorizationId);
    }

    private static String reviewFindingSql(
            String assignmentId, String sourceReviewId, String findingId)
    {
        return """
                INSERT INTO task_assignment_review_finding(
                    assignment_id, position, source_review_id, finding_id,
                    finding_revision, content_digest)
                VALUES ('%s', 1, '%s', '%s', 2, 'digest-%s')
                """.formatted(assignmentId, sourceReviewId, findingId, findingId);
    }

    private static String taskSql(
            String taskId,
            int seq,
            String trunkId,
            String assignmentId,
            String policyId,
            String receiptId)
    {
        return """
                INSERT INTO tasks(
                    id, thread_id, seq, status, phase, created_at_ms,
                    workflow_version, epoch, aggregate_version, lifecycle_state,
                    assignment_id, policy_revision_id, creation_receipt_id,
                    name, task_type, origin)
                VALUES ('%s', '%s', %s, 'PENDING', 'PLANNING', 4,
                    'V2', 1, 0, 'PROVISIONING', '%s', '%s', '%s',
                    '%s', 'DEVELOP', 'user')
                """.formatted(
                taskId, trunkId, seq, assignmentId, policyId, receiptId, taskId);
    }

    private static void createNewTask(Connection connection, NewTask task)
            throws SQLException
    {
        Authorization authorization = authorizeNewTask(connection, task);
        createAuthorizedTask(connection, task, authorization, 16);
    }

    private static Authorization authorizeNewTask(Connection connection, NewTask task)
            throws SQLException
    {
        String assignmentId = "assignment-" + task.taskId();
        String authorizationId = "authorization-" + task.taskId();
        String provenance = task.planningBaseSha() == null ? "DIRECT_USER" : "AGENT_HANDOFF";
        String baseSource = task.planningBaseSha() == null
                ? "FRESH_REMOTE_BASE"
                : "PLANNING_SNAPSHOT";
        Authorization authorization = new Authorization(
                authorizationId, assignmentId, task.commandId(), task.trunkId(),
                task.workspaceId(), task.policyId(), provenance, baseSource,
                task.repository(), null, task.repository(), task.repository(), "main",
                task.planningBaseSha(), null, null);
        authorizeAssignment(connection,
                newAssignmentSql(
                        assignmentId, task.trunkId(), authorizationId,
                        task.planningBaseSha()),
                null,
                authorization);
        return authorization;
    }

    private static void createExistingPrTask(Connection connection, ExistingPrTask task)
            throws SQLException
    {
        Authorization authorization = authorizeExistingPrTask(connection, task);
        NewTask common = new NewTask(
                task.taskId(), 1, task.commandId(), task.trunkId(), task.workspaceId(),
                task.policyId(), task.headRepository(), null);
        createAuthorizedTask(connection, common, authorization, 16);
    }

    private static Authorization authorizeExistingPrTask(
            Connection connection, ExistingPrTask task)
            throws SQLException
    {
        String assignmentId = "assignment-" + task.taskId();
        String authorizationId = "authorization-" + task.taskId();
        String route = task.upstreamRepository() == null ? "DIRECT" : "FORK";
        Authorization authorization = new Authorization(
                authorizationId, assignmentId, task.commandId(), task.trunkId(),
                task.workspaceId(), task.policyId(), "DIRECT_USER", "EXISTING_PR_HEAD",
                task.headRepository(), task.upstreamRepository(), task.headRepository(),
                task.baseRepository(), task.baseRef(), null, task.baseSha(), task.headSha());
        authorizeAssignment(connection,
                existingAssignmentSql(
                        assignmentId, task.trunkId(), authorizationId, route,
                        task.baseRepository(), task.headRepository(), task.baseRef(),
                        task.headRef(), task.baseSha(), task.headSha(), task.prNumber()),
                null,
                authorization);
        return authorization;
    }

    private static void createReviewFindingsTask(
            Connection connection, ExistingPrTask task, String sourceReviewId)
            throws SQLException
    {
        Authorization authorization = authorizeReviewFindingsTask(
                connection, task, sourceReviewId);
        NewTask common = new NewTask(
                task.taskId(), 1, task.commandId(), task.trunkId(), task.workspaceId(),
                task.policyId(), task.headRepository(), null);
        createAuthorizedTask(connection, common, authorization, 16);
    }

    private static Authorization authorizeReviewFindingsTask(
            Connection connection, ExistingPrTask task, String sourceReviewId)
            throws SQLException
    {
        String assignmentId = "assignment-" + task.taskId();
        String authorizationId = "authorization-" + task.taskId();
        String route = task.upstreamRepository() == null ? "DIRECT" : "FORK";
        Authorization authorization = new Authorization(
                authorizationId, assignmentId, task.commandId(), task.trunkId(),
                task.workspaceId(), task.policyId(), "REVIEW_SESSION", "EXISTING_PR_HEAD",
                task.headRepository(), task.upstreamRepository(), task.headRepository(),
                task.baseRepository(), task.baseRef(), null, task.baseSha(), task.headSha());
        authorizeAssignment(connection,
                reviewAssignmentSql(
                        assignmentId, task.trunkId(), authorizationId, sourceReviewId, route,
                        task.baseRepository(), task.headRepository(), task.baseRef(),
                        task.headRef(), task.baseSha(), task.headSha(), task.prNumber()),
                reviewFindingSql(
                        assignmentId, sourceReviewId, "finding-" + task.taskId()),
                authorization);
        return authorization;
    }

    private static void authorizeSourceAssignment(
            Connection connection,
            String suffix,
            String kind,
            String sourceId,
            String provenance,
            String commandId,
            String policyId)
            throws SQLException
    {
        String assignmentId = "assignment-" + suffix;
        String authorizationId = "authorization-" + suffix;
        String assignmentSql = """
                INSERT INTO task_assignment(
                    id, trunk_id, kind, source_id, created_by, created_at_ms,
                    creation_authorization_id)
                VALUES ('%s', 'trunk-1', '%s', '%s', 'user', 3, '%s')
                """.formatted(assignmentId, kind, sourceId, authorizationId);
        Authorization authorization = new Authorization(
                authorizationId, assignmentId, commandId, "trunk-1", "workspace-1",
                policyId, provenance, "FRESH_REMOTE_BASE", "acme/widget", null,
                "acme/widget", "acme/widget", "main", null, null, null);
        authorizeAssignment(connection, assignmentSql, null, authorization);
    }

    private static void authorizeAutomationAssignment(Connection connection, String policyId)
            throws SQLException
    {
        Authorization authorization = new Authorization(
                "authorization-automation", "assignment-automation",
                "authorize-automation", "trunk-1", "workspace-1", policyId,
                "AUTOMATION", "FRESH_REMOTE_BASE", "acme/widget", null,
                "acme/widget", "acme/widget", "main", null, null, null);
        authorizeAssignment(connection, """
                INSERT INTO task_assignment(
                    id, trunk_id, kind, producer, reason, created_by,
                    created_at_ms, creation_authorization_id)
                VALUES ('assignment-automation', 'trunk-1', 'AUTOMATION',
                    'nightly', 'dependency refresh', 'system', 3,
                    'authorization-automation')
                """, null, authorization);
    }

    private static void authorizeAssignment(
            Connection connection,
            String assignmentSql,
            String findingSql,
            Authorization authorization)
            throws SQLException
    {
        connection.setAutoCommit(false);
        try {
            execute(connection, assignmentSql);
            if (findingSql != null) {
                execute(connection, findingSql);
            }
            long expectedVersion = number(connection, """
                    SELECT aggregate_version FROM threads WHERE id = '%s'
                    """.formatted(authorization.trunkId()));
            String lifecycle = text(connection, """
                    SELECT lifecycle_state FROM threads WHERE id = '%s'
                    """.formatted(authorization.trunkId()));
            int updated = update(connection, """
                    UPDATE threads SET aggregate_version = aggregate_version + 1
                    WHERE id = '%s' AND aggregate_version = %s
                    """.formatted(authorization.trunkId(), expectedVersion));
            if (updated != 1) {
                throw new SQLException("stale Trunk authorization CAS");
            }
            execute(connection, """
                    INSERT INTO trunk_transition(
                        id, trunk_id, command_id, from_state, to_state,
                        aggregate_version, cause, actor, occurred_at_ms)
                    VALUES ('authorization-transition-%s', '%s', '%s', '%s', '%s',
                        %s, 'AUTHORIZE_TASK_CREATION', 'user', 4)
                    """.formatted(
                    authorization.id(), authorization.trunkId(), authorization.commandId(),
                    lifecycle, lifecycle, expectedVersion + 1));
            execute(connection, """
                    INSERT INTO trunk_task_creation_authorization(
                        id, trunk_id, workspace_id, command_id, actor, disposition,
                        expected_trunk_version, returned_trunk_version,
                        returned_lifecycle, assignment_id, policy_revision_id,
                        provenance, repository_id, upstream_repository_id,
                        publish_repository_id, base_source, base_repository_id,
                        base_ref, planning_base_sha, assignment_base_sha,
                        assignment_head_sha, engine_snapshot, work_model_snapshot,
                        recorded_at_ms, task_name, task_type, task_origin)
                    VALUES ('%s', '%s', '%s', '%s', 'user', 'AUTHORIZED', %s, %s,
                        '%s', '%s', '%s', '%s', '%s', %s, '%s', '%s', '%s',
                        '%s', %s, %s, %s, 'engine-v1', 'work-model-v1', 4,
                        '%s', 'DEVELOP', 'user')
                    """.formatted(
                    authorization.id(), authorization.trunkId(), authorization.workspaceId(),
                    authorization.commandId(), expectedVersion, expectedVersion + 1,
                    lifecycle, authorization.assignmentId(), authorization.policyId(),
                    authorization.provenance(), authorization.repository(),
                    sql(authorization.upstreamRepository()), authorization.publishRepository(),
                    authorization.baseSource(), authorization.baseRepository(),
                    authorization.baseRef(), sql(authorization.planningBaseSha()),
                    sql(authorization.assignmentBaseSha()),
                    sql(authorization.assignmentHeadSha()),
                    authorization.assignmentId().replaceFirst("^assignment-", "")));
            connection.commit();
        }
        catch (SQLException failure) {
            connection.rollback();
            throw failure;
        }
        finally {
            connection.setAutoCommit(true);
        }
    }

    private static void createAuthorizedTask(
            Connection connection,
            NewTask task,
            Authorization authorization,
            int laneMask)
            throws SQLException
    {
        String receiptId = "creation-receipt-" + task.taskId();
        String brainId = "brain-" + task.taskId();
        String provisionId = "provision-" + task.taskId();
        String operationId = "provision-operation-" + task.taskId();
        String ticketId = "provision-ticket-" + task.taskId();
        String branch = "dev/" + task.taskId();
        String path = "/tmp/" + task.taskId();
        String expectedBase = switch (authorization.baseSource()) {
            case "PLANNING_SNAPSHOT" -> authorization.planningBaseSha();
            case "EXISTING_PR_HEAD" -> authorization.assignmentBaseSha();
            default -> null;
        };
        String expectedHead = authorization.baseSource().equals("EXISTING_PR_HEAD")
                ? authorization.assignmentHeadSha()
                : null;

        connection.setAutoCommit(false);
        try {
            execute(connection, taskSql(
                    task.taskId(), task.seq(), task.trunkId(), authorization.assignmentId(),
                    task.policyId(), receiptId));
            execute(connection, """
                    INSERT INTO task_creation_context(
                        task_id, assignment_id, policy_revision_id, authorization_id,
                        provenance, repository_id, upstream_repository_id,
                        publish_repository_id, base_source, base_repository_id,
                        base_ref, planning_base_sha, assignment_base_sha,
                        assignment_head_sha, engine_snapshot,
                        work_model_snapshot, created_at_ms,
                        task_name, task_type, task_origin)
                    VALUES ('%s', '%s', '%s', '%s', '%s', '%s', %s, '%s',
                        '%s', '%s', '%s', %s, %s, %s,
                        'engine-v1', 'work-model-v1', 4,
                        '%s', 'DEVELOP', 'user')
                    """.formatted(
                    task.taskId(), authorization.assignmentId(), task.policyId(),
                    authorization.id(), authorization.provenance(),
                    authorization.repository(), sql(authorization.upstreamRepository()),
                    authorization.publishRepository(), authorization.baseSource(),
                    authorization.baseRepository(), authorization.baseRef(),
                    sql(authorization.planningBaseSha()),
                    sql(authorization.assignmentBaseSha()),
                    sql(authorization.assignmentHeadSha()), task.taskId()));
            execute(connection, """
                    INSERT INTO task_brain(
                        id, task_id, provider, model, engine_snapshot, created_at_ms)
                    VALUES ('%s', '%s', 'openai', 'review-model', 'engine-v1', 4)
                    """.formatted(brainId, task.taskId()));
            execute(connection, """
                    INSERT INTO task_provision_target(
                        task_id, repository_id, publish_repository_id,
                        branch_name, worktree_path, created_at_ms)
                    VALUES ('%s', '%s', '%s', '%s', '%s', 4)
                    """.formatted(
                    task.taskId(), authorization.repository(),
                    authorization.publishRepository(), branch, path));
            execute(connection, """
                    INSERT INTO provision_task_operation(
                        id, task_id, task_epoch, assignment_id, operation_id,
                        semantic_attempt, repository_id, expected_base_sha,
                        expected_remote_head_sha, requested_branch_name,
                        requested_worktree_path, status, created_at_ms,
                        base_source, base_repository_id, base_ref)
                    VALUES ('%s', '%s', 1, '%s', '%s', 1, '%s', %s, %s,
                        '%s', '%s', 'REQUESTED', 4, '%s', '%s', '%s')
                    """.formatted(
                    provisionId, task.taskId(), authorization.assignmentId(), operationId,
                    authorization.repository(), sql(expectedBase), sql(expectedHead),
                    branch, path, authorization.baseSource(),
                    authorization.baseRepository(), authorization.baseRef()));
            execute(connection, """
                    INSERT INTO dispatch_ticket(
                        id, operation_id, operation_kind, async_family,
                        owner_kind, owner_id, callback_route, lane_mask,
                        exclusive_task, writer_required, workspace_id, trunk_id,
                        task_id, task_epoch, attempt, expected_head_sha,
                        expected_base_sha, status, created_at_ms)
                    VALUES ('%s', '%s', 'PROVISION_TASK', 'LOCAL_GIT', 'TASK',
                        '%s', 'TASK_PROVISION_RESULT', %s, 1, 1, '%s', '%s',
                        '%s', 1, 1, %s, %s, 'REQUESTED', 4)
                    """.formatted(
                    ticketId, operationId, task.taskId(), laneMask, task.workspaceId(),
                    task.trunkId(), task.taskId(), sql(expectedHead), sql(expectedBase)));
            execute(connection, """
                    INSERT INTO task_transition(
                        id, task_id, command_id, epoch, from_state, to_state,
                        aggregate_version, cause, actor, occurred_at_ms)
                    VALUES ('creation-transition-%s', '%s', '%s', 1, NULL,
                        'PROVISIONING', 0, 'CREATE_TASK', 'user', 4)
                    """.formatted(task.taskId(), task.taskId(), task.commandId()));
            execute(connection, """
                    INSERT INTO task_creation_receipt(
                        id, trunk_id, workspace_id, command_id, actor,
                        authorization_id, task_id, task_seq, task_epoch,
                        task_version, returned_lifecycle, assignment_id,
                        policy_revision_id, task_brain_id, provision_operation_id,
                        dispatch_ticket_id, operation_id, semantic_attempt,
                        requested_branch_name, requested_worktree_path, recorded_at_ms)
                    VALUES ('%s', '%s', '%s', '%s', 'user', '%s', '%s', %s, 1, 0,
                        'PROVISIONING', '%s', '%s', '%s', '%s', '%s', '%s', 1,
                        '%s', '%s', 4)
                    """.formatted(
                    receiptId, task.trunkId(), task.workspaceId(), task.commandId(),
                    authorization.id(), task.taskId(), task.seq(),
                    authorization.assignmentId(), task.policyId(), brainId,
                    provisionId, ticketId, operationId, branch, path));
            connection.commit();
        }
        catch (SQLException failure) {
            connection.rollback();
            throw failure;
        }
        finally {
            connection.setAutoCommit(true);
        }
    }

    private static String sql(String value)
    {
        return value == null ? "NULL" : "'" + value + "'";
    }

    private static void assertFails(Connection connection, String sql)
    {
        assertThatThrownBy(() -> execute(connection, sql)).isInstanceOf(SQLException.class);
    }

    private static void execute(Connection connection, String sql)
            throws SQLException
    {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static int update(Connection connection, String sql)
            throws SQLException
    {
        try (var statement = connection.createStatement()) {
            return statement.executeUpdate(sql);
        }
    }

    private static long number(Connection connection, String sql)
            throws SQLException
    {
        try (var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            return result.getLong(1);
        }
    }

    private static String text(Connection connection, String sql)
            throws SQLException
    {
        try (var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            return result.getString(1);
        }
    }

    private record NewTask(
            String taskId,
            int seq,
            String commandId,
            String trunkId,
            String workspaceId,
            String policyId,
            String repository,
            String planningBaseSha) {}

    private record ExistingPrTask(
            String taskId,
            String commandId,
            String trunkId,
            String workspaceId,
            String policyId,
            String baseRepository,
            String headRepository,
            String upstreamRepository,
            String baseRef,
            String headRef,
            String baseSha,
            String headSha,
            int prNumber) {}

    private record Authorization(
            String id,
            String assignmentId,
            String commandId,
            String trunkId,
            String workspaceId,
            String policyId,
            String provenance,
            String baseSource,
            String repository,
            String upstreamRepository,
            String publishRepository,
            String baseRepository,
            String baseRef,
            String planningBaseSha,
            String assignmentBaseSha,
            String assignmentHeadSha) {}
}
