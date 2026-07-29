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

import org.flywaydb.core.Flyway;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class DevelopmentFlowRemoteProtocolFixture
{
    private DevelopmentFlowRemoteProtocolFixture() {}

    static void migrate(String url, String target)
    {
        Flyway.configure().dataSource(url, "", "").target(target).load().migrate();
    }

    static Connection connect(String url)
            throws SQLException
    {
        Connection connection = DriverManager.getConnection(url);
        execute(connection, "PRAGMA foreign_keys = ON");
        return connection;
    }

    static void seedWorkspaceAndTrunk(Connection connection)
            throws SQLException
    {
        execute(connection, """
                INSERT INTO workspaces(
                    id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
                VALUES ('workspace-1', 'Workspace', '', 0, 1, 1)
                """);
        execute(connection, """
                INSERT INTO threads(
                    id, kind, provider, title, status, model, cost_usd_milli,
                    tokens_in, tokens_out, created_at_ms, updated_at_ms, workspace_id,
                    flow, parallel_slots, turn_version, lifecycle_state)
                VALUES ('trunk-1', 'CLI_AGENT', 'claude-code', 'Trunk', 'IDLE',
                    'claude-sonnet-4.6', 0, 0, 0, 1, 1, 'workspace-1',
                    'build', 2, 'V2', 'ACTIVE')
                """);
    }

    static void seedPublishedRemoteTask(Connection connection, int number)
            throws SQLException
    {
        seedLocalDevelopmentTask(connection, number);
        seedApprovedEvidence(connection, number);
        seedPublishSaga(connection, number);
        openRemoteStage(connection, number);
    }

    static void seedLocalDevelopmentTask(Connection connection, int number)
            throws SQLException
    {
        execute(connection, """
                INSERT INTO task_assignment(
                    id, trunk_id, kind, planning_base_sha, plan_seed, prompt,
                    created_by, created_at_ms)
                VALUES ('assignment-%1$s', 'trunk-1', 'NEW_FROM_TRUNK',
                    'base-%1$s', 'seed-%1$s', 'build %1$s', 'user', %1$s + 1)
                """.formatted(number));
        execute(connection, """
                INSERT INTO task_policy_revision(
                    id, trunk_id, revision, source, auto_approve,
                    max_ci_fix_pushes, require_remote_branch_cleanup,
                    created_by, created_at_ms)
                VALUES ('policy-%1$s', 'trunk-1', %1$s, 'TRUNK', 1, 2, 0,
                    'user', %1$s + 1)
                """.formatted(number));
        execute(connection, """
                INSERT INTO tasks(
                    id, thread_id, seq, status, phase, created_at_ms,
                    workflow_version, lifecycle_state, assignment_id,
                    policy_revision_id)
                VALUES ('task-%1$s', 'trunk-1', %1$s, 'IDLE', 'PLANNING',
                    %1$s + 1, 'V2', 'PROVISIONING', 'assignment-%1$s',
                    'policy-%1$s')
                """.formatted(number));
        execute(connection, """
                INSERT INTO task_creation_context(
                    task_id, assignment_id, policy_revision_id, provenance,
                    repository_id, publish_repository_id, planning_base_sha,
                    engine_snapshot, work_model_snapshot, created_at_ms)
                VALUES ('task-%1$s', 'assignment-%1$s', 'policy-%1$s',
                    'DIRECT_USER', 'acme/widget', 'acme/widget', 'base-%1$s',
                    'engine-%1$s',
                    '{"kind":"API","agentOrProvider":"openai",'
                    || '"model":"review-model","account":null,'
                    || '"reasoningEffort":null}', 3)
                """.formatted(number));
        execute(connection, """
                INSERT INTO task_brain(
                    id, task_id, provider, model, engine_snapshot, created_at_ms)
                VALUES ('brain-%1$s', 'task-%1$s', 'openai', 'review-model',
                    'engine-%1$s', 3)
                """.formatted(number));
        execute(connection, """
                INSERT INTO provision_task_operation(
                    id, task_id, task_epoch, assignment_id, operation_id,
                    semantic_attempt, repository_id, expected_base_sha,
                    requested_branch_name, requested_worktree_path,
                    status, created_at_ms)
                VALUES ('provision-%1$s', 'task-%1$s', 1, 'assignment-%1$s',
                    'provision-operation-%1$s', 1, 'acme/widget', 'base-%1$s',
                    'dev/task-%1$s', '/tmp/task-%1$s', 'REQUESTED', 4)
                """.formatted(number));
        execute(connection, """
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    exclusive_task, writer_required, workspace_id, trunk_id,
                    task_id, task_epoch, attempt, expected_base_sha,
                    status, created_at_ms)
                VALUES ('provision-ticket-%1$s', 'provision-operation-%1$s',
                    'PROVISION_TASK', 'LOCAL_GIT', 'TASK', 'task-%1$s',
                    'TASK_PROVISION_RESULT', 16, 1, 1, 'workspace-1', 'trunk-1',
                    'task-%1$s', 1, 1, 'base-%1$s', 'REQUESTED', 4)
                """.formatted(number));
        execute(connection, """
                UPDATE provision_task_operation SET status = 'DISPATCHED'
                WHERE id = 'provision-%s'
                """.formatted(number));
        execute(connection, """
                UPDATE dispatch_ticket
                SET version = 1, status = 'RESULT_PENDING',
                    pending_result_outcome = 'SUCCEEDED',
                    pending_result_payload = 'provisioned',
                    pending_result_evidence = 'local branch exists',
                    pending_result_task_epoch = 1,
                    pending_result_operation_id = 'provision-operation-%1$s',
                    pending_result_attempt = 1,
                    pending_result_expected_base_sha = 'base-%1$s'
                WHERE id = 'provision-ticket-%1$s'
                """.formatted(number));
        execute(connection, """
                UPDATE provision_task_operation
                SET status = 'ACCEPTED', result_base_sha = 'base-%1$s',
                    result_head_sha = 'base-%1$s',
                    result_code_fingerprint = 'initial-fingerprint-%1$s',
                    completed_at_ms = 5
                WHERE id = 'provision-%1$s'
                """.formatted(number));
        execute(connection, """
                INSERT INTO task_code_identity(
                    task_id, provision_operation_id, repository_id,
                    publish_repository_id, branch_name, worktree_path,
                    base_sha, local_head_sha, code_fingerprint,
                    created_at_ms, updated_at_ms)
                VALUES ('task-%1$s', 'provision-%1$s', 'acme/widget',
                    'acme/widget', 'dev/task-%1$s', '/tmp/task-%1$s',
                    'base-%1$s', 'base-%1$s', 'initial-fingerprint-%1$s', 5, 5)
                """.formatted(number));
        execute(connection, """
                INSERT INTO stage(
                    id, task_id, kind, generation, version, checkpoint, opened_at_ms)
                VALUES ('local-stage-%1$s', 'task-%1$s', 'LOCAL_DEVELOPMENT',
                    1, 0, 'LOCAL_REVIEW', 6)
                """.formatted(number));
        execute(connection, """
                INSERT INTO task_current_stage(task_id, stage_id, stage_generation)
                VALUES ('task-%1$s', 'local-stage-%1$s', 1)
                """.formatted(number));
        execute(connection, """
                UPDATE tasks SET lifecycle_state = 'ACTIVE', aggregate_version = 1
                WHERE id = 'task-%s'
                """.formatted(number));
        execute(connection, """
                INSERT INTO local_development_stage(
                    stage_id, task_id, generation, opened_for_epoch)
                VALUES ('local-stage-%1$s', 'task-%1$s', 1, 1)
                """.formatted(number));
        execute(connection, """
                INSERT INTO pr(
                    id, task_id, branch_name, base_branch, title, description,
                    status, created_at_ms, origin)
                VALUES ('pr-%1$s', 'task-%1$s', 'dev/task-%1$s', 'main',
                    'Implement feature %1$s', 'Description', 'local-open', 6, 'task')
                """.formatted(number));
        execute(connection, """
                INSERT INTO stage_turn(
                    id, stage_id, stage_generation, purpose, status,
                    operation_id, attempt, task_epoch,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms, started_at_ms, finished_at_ms)
                VALUES ('development-turn-%1$s', 'local-stage-%1$s', 1,
                    'IMPLEMENT_LOCAL_DEVELOPMENT', 'SUCCEEDED',
                    'development-turn-operation-%1$s', 1, 1,
                    'fingerprint-%1$s', 'head-%1$s', 'base-%1$s', 'CLI',
                    'implement', 6, 6, 7)
                """.formatted(number));
        execute(connection, """
                INSERT INTO dev_report(
                    id, task_id, summary, created_at_ms, workflow_version,
                    local_development_stage_id, task_epoch, stage_generation,
                    stage_turn_id, revision, code_fingerprint, head_sha, base_sha,
                    implemented_intent, commit_summary, file_summary,
                    validation_summary, known_risks, unresolved_concerns, context_refs)
                VALUES ('report-%1$s', 'task-%1$s', 'implemented feature', 7, 'V2',
                    'local-stage-%1$s', 1, 1, 'development-turn-%1$s', 1,
                    'fingerprint-%1$s', 'head-%1$s', 'base-%1$s', 'intent',
                    'one commit', 'two files', 'tests pending', 'none', 'none',
                    'turn:development-turn-%1$s')
                """.formatted(number));
    }

    static void seedApprovedEvidence(Connection connection, int number)
            throws SQLException
    {
        seedGreenValidationEvidence(connection, number);
        seedApprovedBrainEvidence(connection, number);
    }

    static void seedGreenValidationEvidence(Connection connection, int number)
            throws SQLException
    {
        execute(connection, """
                INSERT INTO validation_operation(
                    id, local_development_stage_id, task_id, task_epoch,
                    stage_generation, dev_report_id, operation_id,
                    semantic_attempt, code_fingerprint, expected_head_sha,
                    expected_base_sha, status, requested_at_ms)
                VALUES ('validation-operation-%1$s', 'local-stage-%1$s',
                    'task-%1$s', 1, 1, 'report-%1$s',
                    'validation-operation-id-%1$s', 1, 'fingerprint-%1$s',
                    'head-%1$s', 'base-%1$s', 'REQUESTED', 10)
                """.formatted(number));
        execute(connection, """
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    exclusive_task, writer_required, workspace_id, trunk_id,
                    task_id, task_epoch, stage_id, stage_generation, attempt,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, created_at_ms)
                VALUES ('validation-ticket-%1$s', 'validation-operation-id-%1$s',
                    'VALIDATE_LOCAL_DEVELOPMENT', 'VALIDATION', 'STAGE',
                    'local-stage-%1$s', 'STAGE_VALIDATION_RESULT', 4, 1, 0,
                    'workspace-1', 'trunk-1', 'task-%1$s', 1,
                    'local-stage-%1$s', 1, 1, 'fingerprint-%1$s', 'head-%1$s',
                    'base-%1$s', 'REQUESTED', 10)
                """.formatted(number));
        execute(connection, """
                UPDATE validation_operation SET status = 'DISPATCHED'
                WHERE id = 'validation-operation-%s'
                """.formatted(number));
        execute(connection, """
                INSERT INTO validation_pass(
                    task_id, started_at_ms, ended_at_ms, passed, fix_rounds,
                    failures_json, claim_key, code_fingerprint, workflow_version,
                    task_epoch, stage_id, stage_generation, operation_id,
                    semantic_attempt, expected_head_sha, expected_base_sha)
                VALUES ('task-%1$s', 10, 11, 1, 0, '[]',
                    'validation-claim-%1$s', 'fingerprint-%1$s', 'V2', 1,
                    'local-stage-%1$s', 1, 'validation-operation-id-%1$s', 1,
                    'head-%1$s', 'base-%1$s')
                """.formatted(number));
        execute(connection, """
                INSERT INTO validation_evidence(
                    id, validation_operation_id, validation_pass_id,
                    task_id, task_epoch, stage_id, stage_generation,
                    code_fingerprint, head_sha, base_sha, passed,
                    evidence, completed_at_ms)
                VALUES ('validation-evidence-%1$s', 'validation-operation-%1$s',
                    (SELECT id FROM validation_pass
                     WHERE claim_key = 'validation-claim-%1$s'),
                    'task-%1$s', 1, 'local-stage-%1$s', 1,
                    'fingerprint-%1$s', 'head-%1$s', 'base-%1$s', 1,
                    'all checks passed', 11)
                """.formatted(number));
        execute(connection, """
                UPDATE validation_operation
                SET status = 'COMPLETED', completed_at_ms = 11
                WHERE id = 'validation-operation-%s'
                """.formatted(number));
    }

    private static void seedApprovedBrainEvidence(Connection connection, int number)
            throws SQLException
    {
        execute(connection, """
                INSERT INTO task_turn(
                    id, task_id, purpose, status, operation_id, attempt,
                    task_epoch, trigger_stage_id, trigger_stage_generation,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input, requested_at_ms)
                VALUES ('brain-turn-%1$s', 'task-%1$s',
                    'DEVELOPMENT_BRAIN_REVIEW', 'REQUESTED',
                    'brain-turn-operation-%1$s', 1, 1, 'local-stage-%1$s', 1,
                    'fingerprint-%1$s', 'head-%1$s', 'base-%1$s', 'API',
                    'review report', 12)
                """.formatted(number));
        execute(connection, """
                INSERT INTO brain_review_episode(
                    id, task_brain_id, task_id, task_epoch,
                    local_development_stage_id, stage_generation, dev_report_id,
                    validation_evidence_id, task_turn_id, semantic_attempt,
                    code_fingerprint, expected_head_sha, expected_base_sha,
                    status, requested_at_ms)
                VALUES ('brain-episode-%1$s', 'brain-%1$s', 'task-%1$s', 1,
                    'local-stage-%1$s', 1, 'report-%1$s',
                    'validation-evidence-%1$s', 'brain-turn-%1$s', 1,
                    'fingerprint-%1$s', 'head-%1$s', 'base-%1$s',
                    'REQUESTED', 12)
                """.formatted(number));
        execute(connection, """
                UPDATE task_turn
                SET status = 'SUCCEEDED', started_at_ms = 12, finished_at_ms = 13
                WHERE id = 'brain-turn-%s'
                """.formatted(number));
        execute(connection, """
                UPDATE brain_review_episode
                SET status = 'SUCCEEDED', verdict = 'APPROVED',
                    verdict_summary = 'approved', completed_at_ms = 13
                WHERE id = 'brain-episode-%s'
                """.formatted(number));
    }

    private static void seedPublishSaga(Connection connection, int number)
            throws SQLException
    {
        execute(connection, """
                INSERT INTO promotion_manifest(
                    id, local_development_stage_id, task_id, task_epoch,
                    stage_generation, dev_report_id, pr_id, policy_revision_id,
                    revision, code_fingerprint, head_sha, base_sha, route,
                    base_repository_id, head_repository_id, publish_repository_id,
                    branch_name, head_ref, base_branch, worktree_clean, commits_ahead,
                    branch_verified, base_verified, permission_clear, pr_title,
                    pr_body, pr_content_revision, pr_content_digest, created_at_ms)
                VALUES ('manifest-%1$s', 'local-stage-%1$s', 'task-%1$s', 1, 1,
                    'report-%1$s', 'pr-%1$s', 'policy-%1$s', 1,
                    'fingerprint-%1$s', 'head-%1$s', 'base-%1$s', 'DIRECT',
                    'acme/widget', 'acme/widget', 'acme/widget', 'dev/task-%1$s',
                    'dev/task-%1$s', 'main', 1, 1, 1, 1, 1,
                    'Implement feature %1$s', 'Description', 1, 'pr-digest-%1$s', 30)
                """.formatted(number));
        execute(connection, """
                INSERT INTO publish_authorization(
                    id, local_development_stage_id, task_id, task_epoch,
                    stage_generation, manifest_id, dev_report_id,
                    validation_evidence_id, brain_review_episode_id,
                    pr_id, policy_revision_id, code_fingerprint, head_sha,
                    base_sha, route, base_repository_id, head_repository_id,
                    publish_repository_id, branch_name, head_ref, base_branch,
                    pr_content_revision, pr_content_digest, consent_kind,
                    consent_id, actor_id, brain_basis, authorized_operation_id,
                    authorized_attempt, created_at_ms)
                VALUES ('authorization-%1$s', 'local-stage-%1$s', 'task-%1$s',
                    1, 1, 'manifest-%1$s', 'report-%1$s',
                    'validation-evidence-%1$s', 'brain-episode-%1$s', 'pr-%1$s',
                    'policy-%1$s', 'fingerprint-%1$s', 'head-%1$s', 'base-%1$s',
                    'DIRECT', 'acme/widget', 'acme/widget', 'acme/widget',
                    'dev/task-%1$s', 'dev/task-%1$s', 'main', 1,
                    'pr-digest-%1$s', 'STANDING_TASK', 'policy-%1$s', 'policy',
                    'APPROVED', 'publish-operation-id-%1$s', 1, 31)
                """.formatted(number));
        execute(connection, """
                UPDATE stage SET version = 1, checkpoint = 'PUBLISHING'
                WHERE id = 'local-stage-%s'
                """.formatted(number));
        execute(connection, """
                INSERT INTO publish_operation(
                    id, publish_authorization_id, local_development_stage_id,
                    task_id, task_epoch, stage_generation, operation_id,
                    semantic_attempt, code_fingerprint, expected_head_sha,
                    expected_base_sha, status, requested_at_ms)
                VALUES ('publish-operation-%1$s', 'authorization-%1$s',
                    'local-stage-%1$s', 'task-%1$s', 1, 1,
                    'publish-operation-id-%1$s', 1, 'fingerprint-%1$s',
                    'head-%1$s', 'base-%1$s', 'REQUESTED', 32)
                """.formatted(number));
        String[] kinds = {
                "VERIFY_SUBJECT", "RECONCILE_BRANCH_BASE", "PUSH_BRANCH",
                "CREATE_OR_ADOPT_DRAFT_PR", "FETCH_REMOTE_DETAIL", "PROVE_REMOTE_HEAD"};
        for (int ordinal = 1; ordinal <= kinds.length; ordinal++) {
            execute(connection, """
                    INSERT INTO publish_effect_step(
                        id, publish_operation_id, ordinal, kind, idempotency_key,
                        status, attempt_limit)
                    VALUES ('publish-step-%1$s-%2$s', 'publish-operation-%1$s',
                        %2$s, '%3$s', 'publish-operation-%1$s:%3$s',
                        'REQUESTED', 3)
                    """.formatted(number, ordinal, kinds[ordinal - 1]));
        }
        execute(connection, """
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    exclusive_task, writer_required, workspace_id, trunk_id,
                    task_id, task_epoch, stage_id, stage_generation, attempt,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, created_at_ms)
                VALUES ('publish-ticket-%1$s', 'publish-operation-id-%1$s',
                    'PUBLISH_LOCAL_DEVELOPMENT', 'GITHUB_EFFECT', 'STAGE',
                    'local-stage-%1$s', 'STAGE_PUBLISH_RESULT', 48, 1, 1,
                    'workspace-1', 'trunk-1', 'task-%1$s', 1,
                    'local-stage-%1$s', 1, 1, 'fingerprint-%1$s', 'head-%1$s',
                    'base-%1$s', 'REQUESTED', 32)
                """.formatted(number));
        execute(connection, """
                UPDATE publish_operation SET status = 'DISPATCHED'
                WHERE id = 'publish-operation-%s'
                """.formatted(number));
        for (int ordinal = 1; ordinal <= kinds.length; ordinal++) {
            execute(connection, """
                    UPDATE publish_effect_step
                    SET status = 'CLAIMED', attempt_count = attempt_count + 1,
                        claim_mode = 'EXECUTE', claim_owner = 'worker',
                        claimed_at_ms = %2$s, lease_until_ms = %2$s + 100
                    WHERE publish_operation_id = 'publish-operation-%1$s'
                      AND ordinal = %2$s
                    """.formatted(number, ordinal));
            execute(connection, """
                    UPDATE publish_effect_step
                    SET status = 'SUCCEEDED', claim_mode = NULL, claim_owner = NULL,
                        claimed_at_ms = NULL, lease_until_ms = NULL,
                        evidence = 'complete', completed_at_ms = 40 + %2$s
                    WHERE publish_operation_id = 'publish-operation-%1$s'
                      AND ordinal = %2$s
                    """.formatted(number, ordinal));
        }
        execute(connection, """
                UPDATE dispatch_ticket
                SET version = 1, status = 'RESULT_PENDING',
                    pending_result_outcome = 'SUCCEEDED',
                    pending_result_payload = 'published',
                    pending_result_evidence = 'remote head fetched',
                    pending_result_task_epoch = 1,
                    pending_result_stage_id = 'local-stage-%1$s',
                    pending_result_stage_generation = 1,
                    pending_result_operation_id = 'publish-operation-id-%1$s',
                    pending_result_attempt = 1,
                    pending_result_expected_code_fingerprint = 'fingerprint-%1$s',
                    pending_result_expected_head_sha = 'head-%1$s',
                    pending_result_expected_base_sha = 'base-%1$s'
                WHERE id = 'publish-ticket-%1$s'
                """.formatted(number));
        execute(connection, """
                UPDATE publish_operation
                SET status = 'SUCCEEDED', remote_repository_id = 'acme/widget',
                    remote_pr_number = 40 + %1$s,
                    remote_pr_url = 'https://example/pr/%1$s',
                    remote_head_ref = 'dev/task-%1$s', remote_head_sha = 'head-%1$s',
                    remote_base_sha = 'base-%1$s', result_evidence = 'proof',
                    completed_at_ms = 50
                WHERE id = 'publish-operation-%1$s'
                """.formatted(number));
        execute(connection, """
                INSERT INTO remote_pr_binding(
                    id, task_id, pr_id, publish_operation_id,
                    publish_authorization_id, manifest_id, route,
                    base_repository_id, head_repository_id, remote_repository_id,
                    remote_pr_number, remote_pr_url, remote_head_ref,
                    remote_head_sha, remote_base_sha, evidence, bound_at_ms)
                VALUES ('binding-%1$s', 'task-%1$s', 'pr-%1$s',
                    'publish-operation-%1$s', 'authorization-%1$s',
                    'manifest-%1$s', 'DIRECT', 'acme/widget', 'acme/widget',
                    'acme/widget', 40 + %1$s, 'https://example/pr/%1$s',
                    'dev/task-%1$s', 'head-%1$s', 'base-%1$s', 'binding proof', 51)
                """.formatted(number));
        execute(connection, """
                UPDATE publish_authorization
                SET consumed_at_ms = 51, outcome = 'PUBLISHED'
                WHERE id = 'authorization-%s'
                """.formatted(number));
    }

    private static void openRemoteStage(Connection connection, int number)
            throws SQLException
    {
        connection.setAutoCommit(false);
        try {
            execute(connection, """
                    UPDATE stage
                    SET version = 2, checkpoint = 'COMPLETED',
                        completed_at_ms = 52, end_reason = 'NORMAL'
                    WHERE id = 'local-stage-%s'
                    """.formatted(number));
            execute(connection, """
                    INSERT INTO stage(
                        id, task_id, kind, generation, version, checkpoint,
                        opened_at_ms)
                    VALUES ('remote-stage-%1$s', 'task-%1$s',
                        'REMOTE_DEVELOPMENT', 1, 0, 'WAITING_CI', 52)
                    """.formatted(number));
            execute(connection, """
                    UPDATE task_current_stage
                    SET stage_id = 'remote-stage-%1$s', stage_generation = 1
                    WHERE task_id = 'task-%1$s'
                    """.formatted(number));
            connection.commit();
        }
        catch (Throwable failure) {
            connection.rollback();
            throw failure;
        }
        finally {
            connection.setAutoCommit(true);
        }
    }

    static void insertRemoteOwner(Connection connection, int number)
            throws SQLException
    {
        execute(connection, """
                INSERT INTO remote_development_stage(
                    stage_id, task_id, generation, remote_pr_binding_id,
                    current_head_sha, current_base_sha, subject_changed_at_ms)
                VALUES ('remote-stage-%1$s', 'task-%1$s', 1, 'binding-%1$s',
                    'head-%1$s', 'base-%1$s', 52)
                """.formatted(number));
    }

    static void insertSnapshot(
            Connection connection,
            int taskNumber,
            int revision,
            String head,
            String base,
            String state,
            String mergeability)
            throws SQLException
    {
        execute(connection, """
                INSERT INTO remote_pr_snapshot(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, remote_pr_binding_id, observation_revision,
                    observation_key, remote_repository_id, remote_pr_number,
                    head_sha, base_sha, pr_state, mergeability, merge_queue_state,
                    observed_at_ms)
                VALUES ('snapshot-%1$s-%2$s', 'remote-stage-%1$s', 'task-%1$s',
                    1, 1, 'binding-%1$s', %2$s, 'observation-%1$s-%2$s',
                    'acme/widget', 40 + %1$s, '%3$s', '%4$s', '%5$s', '%6$s',
                    'NONE', 60 + %2$s)
                """.formatted(taskNumber, revision, head, base, state, mergeability));
    }

    static void insertSnapshot(
            Connection connection,
            int taskNumber,
            int revision,
            String head,
            String base,
            String state,
            String mergeability,
            String queueState,
            String queueCapability,
            int writeApprovals,
            int changesRequested,
            int unresolvedThreads,
            int unresolvedComments)
            throws SQLException
    {
        execute(connection, """
                INSERT INTO remote_pr_snapshot(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, remote_pr_binding_id, observation_revision,
                    observation_key, remote_repository_id, remote_pr_number,
                    head_sha, base_sha, pr_state, mergeability, merge_queue_state,
                    write_approval_count, changes_requested_count,
                    unresolved_thread_count, unresolved_comment_count,
                    observed_at_ms, merge_queue_capability)
                VALUES ('snapshot-%1$s-%2$s', 'remote-stage-%1$s', 'task-%1$s',
                    1, 1, 'binding-%1$s', %2$s, 'observation-%1$s-%2$s',
                    'acme/widget', 40 + %1$s, '%3$s', '%4$s', '%5$s', '%6$s',
                    '%7$s', %9$s, %10$s, %11$s, %12$s, 60 + %2$s, '%8$s')
                """.formatted(taskNumber, revision, head, base, state, mergeability,
                queueState, queueCapability, writeApprovals, changesRequested,
                unresolvedThreads, unresolvedComments));
    }

    static void acceptSnapshot(Connection connection, int taskNumber, int revision,
            String head, String base)
            throws SQLException
    {
        execute(connection, """
                UPDATE remote_development_stage
                SET accepted_snapshot_id = 'snapshot-%1$s-%2$s',
                    accepted_observation_revision = %2$s,
                    current_head_sha = '%3$s', current_base_sha = '%4$s',
                    subject_changed_at_ms = 60 + %2$s
                WHERE stage_id = 'remote-stage-%1$s'
                """.formatted(taskNumber, revision, head, base));
    }

    static void insertCiPolicy(Connection connection, int taskNumber)
            throws SQLException
    {
        execute(connection, """
                INSERT INTO remote_ci_policy_revision(
                    id, task_id, remote_pr_binding_id, revision, source,
                    none_outcome, missing_outcome, queued_outcome, pending_outcome,
                    neutral_outcome, skipped_outcome, canceled_outcome,
                    created_by, created_at_ms)
                VALUES ('ci-policy-%1$s', 'task-%1$s', 'binding-%1$s', 1,
                    'REPOSITORY', 'WAITING', 'FAILED', 'WAITING', 'WAITING',
                    'WAITING', 'FAILED', 'FAILED', 'user', 55)
                """.formatted(taskNumber));
    }

    static void insertFailedCi(
            Connection connection, int taskNumber, int revision, String head, String base)
            throws SQLException
    {
        execute(connection, """
                INSERT INTO remote_ci_check_snapshot(
                    id, remote_pr_snapshot_id, check_kind, external_check_id,
                    check_name, normalized_state, provider_status,
                    provider_conclusion, observed_at_ms)
                VALUES ('check-%1$s-%2$s', 'snapshot-%1$s-%2$s', 'CHECK_RUN',
                    'check-%1$s-%2$s', 'build', 'FAILED', 'COMPLETED', 'FAILURE',
                    70 + %2$s)
                """.formatted(taskNumber, revision));
        execute(connection, """
                INSERT INTO remote_ci_evaluation(
                    id, remote_development_stage_id, remote_pr_snapshot_id,
                    ci_policy_revision_id, task_id, task_epoch, stage_generation,
                    head_sha, base_sha, normalized_status, policy_outcome,
                    check_count, missing_required_count, evidence, evaluated_at_ms)
                VALUES ('ci-evaluation-%1$s-%2$s', 'remote-stage-%1$s',
                    'snapshot-%1$s-%2$s', 'ci-policy-%1$s', 'task-%1$s', 1, 1,
                    '%3$s', '%4$s', 'FAILED', 'FAILED', 1, 0, 'build failed',
                    71 + %2$s)
                """.formatted(taskNumber, revision, head, base));
    }

    static void insertGreenCi(
            Connection connection, int taskNumber, int revision, String head, String base)
            throws SQLException
    {
        execute(connection, """
                INSERT INTO remote_ci_check_snapshot(
                    id, remote_pr_snapshot_id, check_kind, external_check_id,
                    check_name, normalized_state, provider_status,
                    provider_conclusion, observed_at_ms)
                VALUES ('green-check-%1$s-%2$s', 'snapshot-%1$s-%2$s', 'CHECK_RUN',
                    'green-check-%1$s-%2$s', 'build', 'PASSED', 'COMPLETED',
                    'SUCCESS', 70 + %2$s)
                """.formatted(taskNumber, revision));
        execute(connection, """
                INSERT INTO remote_ci_evaluation(
                    id, remote_development_stage_id, remote_pr_snapshot_id,
                    ci_policy_revision_id, task_id, task_epoch, stage_generation,
                    head_sha, base_sha, normalized_status, policy_outcome,
                    check_count, missing_required_count, evidence, evaluated_at_ms)
                VALUES ('green-ci-%1$s-%2$s', 'remote-stage-%1$s',
                    'snapshot-%1$s-%2$s', 'ci-policy-%1$s', 'task-%1$s', 1, 1,
                    '%3$s', '%4$s', 'PASSED', 'ACCEPTED', 1, 0, 'build passed',
                    71 + %2$s)
                """.formatted(taskNumber, revision, head, base));
    }

    static void seedLegacyTask(Connection connection)
            throws SQLException
    {
        execute(connection, """
                INSERT INTO workspaces(
                    id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
                VALUES ('legacy-workspace', 'Legacy', '', 0, 1, 1)
                """);
        execute(connection, """
                INSERT INTO threads(
                    id, kind, provider, title, status, model, cost_usd_milli,
                    tokens_in, tokens_out, created_at_ms, updated_at_ms, workspace_id,
                    flow, parallel_slots, turn_version)
                VALUES ('legacy-trunk', 'CLI_AGENT', 'claude-code', 'Legacy',
                    'IDLE', 'claude-sonnet-4.6', 0, 0, 0, 1, 1,
                    'legacy-workspace', 'build', 1, 'LEGACY')
                """);
        execute(connection, """
                INSERT INTO tasks(
                    id, thread_id, seq, status, phase, created_at_ms, workflow_version)
                VALUES ('legacy-task', 'legacy-trunk', 1, 'IDLE', 'PLANNING',
                    2, 'LEGACY')
                """);
    }

    static void execute(Connection connection, String sql)
            throws SQLException
    {
        try (var statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    static long number(Connection connection, String sql)
            throws SQLException
    {
        try (var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            return result.getLong(1);
        }
    }

    static String text(Connection connection, String sql)
            throws SQLException
    {
        try (var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            return result.getString(1);
        }
    }

    static void assertFails(Connection connection, String sql)
    {
        assertThatThrownBy(() -> execute(connection, sql))
                .isInstanceOf(SQLException.class);
    }
}
