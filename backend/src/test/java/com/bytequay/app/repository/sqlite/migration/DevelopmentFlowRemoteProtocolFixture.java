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
import com.fasterxml.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class DevelopmentFlowRemoteProtocolFixture
{
    private DevelopmentFlowRemoteProtocolFixture() {}

    static void migrate(String url)
    {
        MigratedSqliteDatabase.migrate(url);
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
                    flow, parallel_slots, turn_version, lifecycle_state,
                    aggregate_version)
                VALUES ('trunk-1', 'CLI_AGENT', 'claude-code', 'Trunk', 'IDLE',
                    'claude-sonnet-4.6', 0, 0, 0, 1, 1, 'workspace-1',
                    'build', 2, 'V2', 'ACTIVE', 1)
                """);
        execute(connection, """
                INSERT INTO workspace_repos(
                    workspace_id, repo_full_name, default_base_branch, added_at_ms)
                VALUES ('workspace-1', 'acme/widget', 'main', 1)
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

    /**
     * Records the trunk-side authorization every task assignment now hangs off. The
     * owner graph is checked by trigger on insert, so trunk, policy, assignment and
     * workspace repository all have to line up with what the fixture seeded above.
     */
    static void seedCreationAuthorization(Connection connection, int number)
            throws SQLException
    {
        long expectedVersion = number(connection, """
                SELECT aggregate_version FROM threads WHERE id = 'trunk-1'
                """);
        execute(connection, """
                UPDATE threads SET aggregate_version = %1$s
                WHERE id = 'trunk-1' AND aggregate_version = %2$s
                """.formatted(expectedVersion + 1, expectedVersion));
        execute(connection, """
                INSERT INTO trunk_transition(
                    id, trunk_id, command_id, from_state, to_state,
                    aggregate_version, cause, actor, occurred_at_ms)
                VALUES ('authorization-transition-%1$s', 'trunk-1',
                    'command-%1$s', 'ACTIVE', 'ACTIVE', %2$s,
                    'AUTHORIZE_TASK_CREATION', 'user', %1$s + 1)
                """.formatted(number, expectedVersion + 1));
        execute(connection, """
                INSERT INTO trunk_task_creation_authorization(
                    id, trunk_id, workspace_id, command_id, actor, disposition,
                    expected_trunk_version, returned_trunk_version,
                    returned_lifecycle, assignment_id, policy_revision_id,
                    provenance, repository_id, publish_repository_id,
                    base_source, base_repository_id, base_ref, planning_base_sha,
                    engine_snapshot, work_model_snapshot, recorded_at_ms,
                    task_name, task_type, opening_prompt, task_origin)
                VALUES ('authorization-%1$s', 'trunk-1', 'workspace-1',
                    'command-%1$s', 'user', 'AUTHORIZED', %2$s, %3$s, 'ACTIVE',
                    'assignment-%1$s', 'policy-%1$s', 'AGENT_HANDOFF',
                    'acme/widget', 'acme/widget', 'PLANNING_SNAPSHOT',
                    'acme/widget', 'main', 'base-%1$s', 'engine-%1$s',
                    '{"kind":"API","agentOrProvider":"openai",'
                    || '"model":"review-model","account":null,'
                    || '"reasoningEffort":null}', %1$s + 1,
                    'Task %1$s', 'DEVELOP', 'build %1$s', 'user')
                """.formatted(number, expectedVersion, expectedVersion + 1));
    }

    static void seedLocalDevelopmentTask(Connection connection, int number)
            throws SQLException
    {
        boolean ownsTransaction = connection.getAutoCommit();
        if (ownsTransaction) {
            connection.setAutoCommit(false);
        }
        try {
            execute(connection, """
                INSERT INTO task_assignment(
                    id, trunk_id, kind, planning_base_sha, plan_seed, prompt,
                    created_by, created_at_ms, creation_authorization_id)
                VALUES ('assignment-%1$s', 'trunk-1', 'NEW_FROM_TRUNK',
                    'base-%1$s', 'seed-%1$s', 'build %1$s', 'user', %1$s + 1,
                    'authorization-%1$s')
                    """.formatted(number));
            execute(connection, """
                INSERT INTO task_policy_revision(
                    id, trunk_id, revision, source, auto_approve,
                    max_ci_fix_pushes, require_remote_branch_cleanup,
                    created_by, created_at_ms)
                VALUES ('policy-%1$s', 'trunk-1', %1$s, 'TRUNK', 1, 2, 0,
                    'user', %1$s + 1)
                    """.formatted(number));
            seedCreationAuthorization(connection, number);
            execute(connection, """
                INSERT INTO tasks(
                    id, thread_id, seq, status, phase, created_at_ms,
                    workflow_version, epoch, aggregate_version, lifecycle_state,
                    assignment_id, policy_revision_id, creation_receipt_id,
                    name, task_type, opening_prompt, origin)
                VALUES ('task-%1$s', 'trunk-1', %1$s, 'PENDING', 'PLANNING',
                    %1$s + 1, 'V2', 1, 0, 'PROVISIONING', 'assignment-%1$s',
                    'policy-%1$s', 'creation-receipt-%1$s', 'Task %1$s',
                    'DEVELOP', 'build %1$s', 'user')
                    """.formatted(number));
            execute(connection, """
                INSERT INTO task_creation_context(
                    task_id, assignment_id, policy_revision_id, authorization_id,
                    provenance, repository_id, publish_repository_id, base_source,
                    base_repository_id, base_ref, planning_base_sha,
                    engine_snapshot, work_model_snapshot, created_at_ms,
                    task_name, task_type, opening_prompt, task_origin)
                VALUES ('task-%1$s', 'assignment-%1$s', 'policy-%1$s',
                    'authorization-%1$s', 'AGENT_HANDOFF', 'acme/widget',
                    'acme/widget', 'PLANNING_SNAPSHOT', 'acme/widget', 'main',
                    'base-%1$s', 'engine-%1$s',
                    '{"kind":"API","agentOrProvider":"openai",'
                    || '"model":"review-model","account":null,'
                    || '"reasoningEffort":null}', 3, 'Task %1$s', 'DEVELOP',
                    'build %1$s', 'user')
                    """.formatted(number));
            execute(connection, """
                INSERT INTO task_brain(
                    id, task_id, provider, model, engine_snapshot, created_at_ms)
                VALUES ('brain-%1$s', 'task-%1$s', 'openai', 'review-model',
                    'engine-%1$s', 3)
                    """.formatted(number));
            execute(connection, """
                INSERT INTO task_provision_target(
                    task_id, repository_id, publish_repository_id,
                    branch_name, worktree_path, created_at_ms)
                VALUES ('task-%1$s', 'acme/widget', 'acme/widget',
                    'dev/task-%1$s', '/tmp/task-%1$s', 4)
                    """.formatted(number));
            execute(connection, """
                INSERT INTO provision_task_operation(
                    id, task_id, task_epoch, assignment_id, operation_id,
                    semantic_attempt, repository_id, expected_base_sha,
                    requested_branch_name, requested_worktree_path,
                    status, created_at_ms, base_source, base_repository_id,
                    base_ref)
                VALUES ('provision-%1$s', 'task-%1$s', 1, 'assignment-%1$s',
                    'provision-operation-%1$s', 1, 'acme/widget', 'base-%1$s',
                    'dev/task-%1$s', '/tmp/task-%1$s', 'REQUESTED', 4,
                    'PLANNING_SNAPSHOT', 'acme/widget', 'main')
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
                INSERT INTO task_transition(
                    id, task_id, command_id, epoch, from_state, to_state,
                    aggregate_version, cause, actor, occurred_at_ms)
                VALUES ('creation-transition-%1$s', 'task-%1$s', 'command-%1$s',
                    1, NULL, 'PROVISIONING', 0, 'CREATE_TASK', 'user', 4)
                    """.formatted(number));
            execute(connection, """
                INSERT INTO task_creation_receipt(
                    id, trunk_id, workspace_id, command_id, actor,
                    authorization_id, task_id, task_seq, task_epoch,
                    task_version, returned_lifecycle, assignment_id,
                    policy_revision_id, task_brain_id, provision_operation_id,
                    dispatch_ticket_id, operation_id, semantic_attempt,
                    requested_branch_name, requested_worktree_path, recorded_at_ms)
                VALUES ('creation-receipt-%1$s', 'trunk-1', 'workspace-1',
                    'command-%1$s', 'user', 'authorization-%1$s', 'task-%1$s',
                    %1$s, 1, 0, 'PROVISIONING', 'assignment-%1$s', 'policy-%1$s',
                    'brain-%1$s', 'provision-%1$s', 'provision-ticket-%1$s',
                    'provision-operation-%1$s', 1, 'dev/task-%1$s',
                    '/tmp/task-%1$s', 4)
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
                    result_evidence = 'local branch exists',
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
                VALUES ('plan-stage-%1$s', 'task-%1$s', 'PLAN', 1, 0,
                    'DRAFTING', 6)
                    """.formatted(number));
            execute(connection, """
                INSERT INTO task_current_stage(task_id, stage_id, stage_generation)
                VALUES ('task-%1$s', 'plan-stage-%1$s', 1)
                    """.formatted(number));
            execute(connection, """
                UPDATE tasks SET lifecycle_state = 'ACTIVE', aggregate_version = 1
                WHERE id = 'task-%s'
                    """.formatted(number));
            execute(connection, """
                INSERT INTO plan_stage(
                    stage_id, task_id, generation, opened_for_epoch)
                VALUES ('plan-stage-%1$s', 'task-%1$s', 1, 1)
                    """.formatted(number));
            execute(connection, """
                INSERT INTO task_transition(
                    id, task_id, command_id, epoch, from_state, to_state,
                    aggregate_version, cause, actor, occurred_at_ms)
                VALUES ('provision-transition-%1$s', 'task-%1$s',
                    'accept-provision-%1$s', 1, 'PROVISIONING', 'ACTIVE', 1,
                    'ACCEPT_PROVISIONING', 'result-delivery', 6)
                    """.formatted(number));
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
                VALUES ('provision-receipt-%1$s', 'task-%1$s',
                    'accept-provision-%1$s', 'ACCEPT_PROVISIONING',
                    'result-delivery', 'APPLIED', 1, 0, 1, 0,
                    'provision-operation-%1$s', 1, 'initial-fingerprint-%1$s',
                    'base-%1$s', 'base-%1$s', 'provision-operation-%1$s',
                    'plan-stage-%1$s', 'PLAN', 1, 'trunk-1', 'ACTIVE', 1, 1,
                    'plan-stage-%1$s', 6)
                    """.formatted(number));
            execute(connection, """
                UPDATE stage
                SET version = 1, checkpoint = 'COMPLETED', completed_at_ms = 6,
                    end_reason = 'NORMAL'
                WHERE id = 'plan-stage-%s'
                    """.formatted(number));
            execute(connection, """
                INSERT INTO stage(
                    id, task_id, kind, generation, version, checkpoint, opened_at_ms)
                VALUES ('local-stage-%1$s', 'task-%1$s', 'LOCAL_DEVELOPMENT',
                    1, 0, 'LOCAL_REVIEW', 6)
                    """.formatted(number));
            execute(connection, """
                UPDATE task_current_stage
                SET stage_id = 'local-stage-%1$s', stage_generation = 1
                WHERE task_id = 'task-%1$s'
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
                    requested_at_ms)
                VALUES ('development-turn-%1$s', 'local-stage-%1$s', 1,
                    'IMPLEMENT_LOCAL_PLAN', 'QUEUED',
                    'development-turn-operation-%1$s', 1, 1,
                    'initial-fingerprint-%1$s', 'base-%1$s', 'base-%1$s', 'API',
                    '{"schemaVersion":1,"transport":"API",'
                    || '"provider":"openai","model":"review-model",'
                    || '"workingDirectory":"/tmp/task-%1$s"}', 6)
                    """.formatted(number));
            execute(connection, """
                INSERT INTO local_stage_turn_request(
                    id, command_id, stage_turn_id, task_id,
                    local_development_stage_id, task_epoch, stage_generation,
                    kind, queue_mode, prompt_digest, requested_by,
                    requested_at_ms)
                VALUES ('development-request-%1$s', 'development-command-%1$s',
                    'development-turn-%1$s', 'task-%1$s', 'local-stage-%1$s',
                    1, 1, 'IMPLEMENTATION', 'IMMEDIATE', '%2$s', 'fixture', 6)
                    """.formatted(number, "a".repeat(64)));
            execute(connection, """
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    exclusive_task, writer_required, workspace_id, trunk_id,
                    task_id, task_epoch, stage_id, stage_generation, attempt,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, created_at_ms)
                VALUES ('development-ticket-%1$s',
                    'development-turn-operation-%1$s', 'EXECUTE_STAGE_TURN',
                    'AGENT_TURN', 'STAGE_TURN', 'development-turn-%1$s',
                    'STAGE_TURN_RESULT', 2, 1, 1, 'workspace-1', 'trunk-1',
                    'task-%1$s', 1, 'local-stage-%1$s', 1, 1,
                    'initial-fingerprint-%1$s', 'base-%1$s', 'base-%1$s',
                    'REQUESTED', 6)
                    """.formatted(number));
            seedSuccessfulDevelopmentExecution(connection, number);
            execute(connection, """
                INSERT INTO dev_report(
                    id, task_id, summary, created_at_ms, workflow_version,
                    local_development_stage_id, task_epoch, stage_generation,
                    stage_turn_id, revision, code_fingerprint, head_sha, base_sha,
                    source_code_fingerprint, source_head_sha, source_base_sha,
                    implemented_intent, commit_summary, file_summary,
                    validation_summary, known_risks, unresolved_concerns, context_refs)
                VALUES ('report-%1$s', 'task-%1$s', 'implemented feature', 7, 'V2',
                    'local-stage-%1$s', 1, 1, 'development-turn-%1$s', 1,
                    'fingerprint-%1$s', 'head-%1$s', 'base-%1$s',
                    'initial-fingerprint-%1$s', 'base-%1$s', 'base-%1$s', 'intent',
                    'one commit', 'two files', 'tests pending', 'none', 'none',
                    'turn:development-turn-%1$s')
                    """.formatted(number));
            if (ownsTransaction) {
                connection.commit();
            }
        }
        catch (Throwable failure) {
            if (ownsTransaction) {
                connection.rollback();
            }
            throw failure;
        }
        finally {
            if (ownsTransaction) {
                connection.setAutoCommit(true);
            }
        }
    }

    private static void seedSuccessfulDevelopmentExecution(
            Connection connection, int number)
            throws SQLException
    {
        ObjectMapper json = new ObjectMapper();
        var output = json.createObjectNode();
        output.put("codeFingerprint", "fingerprint-" + number);
        output.put("headSha", "head-" + number);
        output.put("baseSha", "base-" + number);
        output.put("clean", true);
        output.put("mergeBaseSha", "base-" + number);
        output.put("sourceTreeSha", "source-tree-" + number);
        output.put("resultTreeSha", "result-tree-" + number);
        output.put("sourceHeadMergeBaseSha", "base-" + number);
        output.put("candidateParentSha", "base-" + number);
        output.put("branchName", "dev/task-" + number);

        var payload = json.createObjectNode();
        payload.put("schemaVersion", 1);
        payload.put("turnId", "development-turn-" + number);
        payload.put("ownerKind", "STAGE_TURN");
        payload.put("purpose", "IMPLEMENT_LOCAL_PLAN");
        payload.put("transport", "API");
        payload.put("provider", "openai");
        payload.put("finalText", "implemented feature");
        payload.put("inputTokens", 1);
        payload.put("outputTokens", 1);
        payload.put("costUsdMilli", 0);
        payload.put("disposition", "PROVIDER_SUCCEEDED");
        payload.set("outputCodeSubject", output);

        var writerFence = json.createObjectNode();
        writerFence.put("worktreePath", "/tmp/task-" + number);
        writerFence.put("taskId", "task-" + number);
        writerFence.put("operationId", "development-turn-operation-" + number);
        writerFence.put("taskEpoch", 1);
        writerFence.put("fencingToken", 1);
        var evidence = json.createObjectNode();
        evidence.put("schemaVersion", 1);
        evidence.put("disposition", "PROVIDER_SUCCEEDED");
        evidence.set("writerFence", writerFence);
        evidence.set("outputCodeSubject", output);

        var fence = json.createObjectNode();
        fence.put("taskEpoch", 1);
        fence.put("stageId", "local-stage-" + number);
        fence.put("stageGeneration", 1);
        fence.put("operationId", "development-turn-operation-" + number);
        fence.put("attempt", 1);
        fence.put("expectedCodeFingerprint", "initial-fingerprint-" + number);
        fence.put("expectedHeadSha", "base-" + number);
        fence.put("expectedBaseSha", "base-" + number);
        var raw = json.createObjectNode();
        raw.set("fence", fence);
        raw.put("outcome", "SUCCEEDED");
        raw.put("payloadJson", payload.toString());
        raw.put("evidenceJson", evidence.toString());
        raw.putNull("error");

        try (var statement = connection.prepareStatement("""
                UPDATE dispatch_ticket
                SET version = 1, status = 'RESULT_PENDING',
                    infrastructure_attempts = 1, started_at_ms = 6,
                    pending_result_outcome = 'SUCCEEDED',
                    pending_result_payload = ?, pending_result_evidence = ?,
                    pending_result_task_epoch = 1,
                    pending_result_stage_id = 'local-stage-%1$s',
                    pending_result_stage_generation = 1,
                    pending_result_operation_id =
                        'development-turn-operation-%1$s',
                    pending_result_attempt = 1,
                    pending_result_expected_code_fingerprint =
                        'initial-fingerprint-%1$s',
                    pending_result_expected_head_sha = 'base-%1$s',
                    pending_result_expected_base_sha = 'base-%1$s'
                WHERE id = 'development-ticket-%1$s'
                """.formatted(number))) {
            statement.setString(1, payload.toString());
            statement.setString(2, evidence.toString());
            statement.executeUpdate();
        }
        try (var statement = connection.prepareStatement("""
                INSERT INTO agent_execution(
                    id, ticket_id, infrastructure_attempt, provider,
                    status, started_at_ms, finished_at_ms, raw_result)
                VALUES ('development-execution-%1$s',
                    'development-ticket-%1$s', 1, 'openai',
                    'SUCCEEDED', 6, 7, ?)
                """.formatted(number))) {
            statement.setString(1, raw.toString());
            statement.executeUpdate();
        }
        execute(connection, """
                UPDATE stage_turn
                SET status = 'SUCCEEDED', started_at_ms = 6, finished_at_ms = 7
                WHERE id = 'development-turn-%s'
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
                INSERT INTO local_stage_turn_delivery_receipt(
                    stage_turn_id, operation_id, raw_outcome,
                    raw_result_digest, acceptance, dev_report_id,
                    validation_operation_id, recorded_at_ms)
                VALUES ('development-turn-%1$s',
                    'development-turn-operation-%1$s', 'SUCCEEDED', '%2$s',
                    'ACCEPTED', 'report-%1$s', 'validation-operation-%1$s', 7)
                """.formatted(number, "d".repeat(64)));
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
                    branch_name, head_ref, base_branch, require_clean_worktree,
                    minimum_commits_ahead, require_branch_match,
                    require_base_match, require_publish_permission, pr_title,
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
            execute(connection, """
                    UPDATE tasks SET aggregate_version = 2
                    WHERE id = 'task-%1$s' AND aggregate_version = 1
                    """.formatted(number));
            execute(connection, """
                    INSERT INTO task_transition(
                        id, task_id, command_id, epoch, from_state, to_state,
                        aggregate_version, cause, actor, occurred_at_ms)
                    VALUES ('remote-transition-%1$s', 'task-%1$s',
                        'open-remote-%1$s', 1, 'ACTIVE', 'ACTIVE', 2,
                        'OPEN_REMOTE_DEVELOPMENT', 'result-delivery', 52)
                    """.formatted(number));
            execute(connection, """
                    INSERT INTO task_command_receipt(
                        id, task_id, command_id, cause, actor, disposition,
                        expected_task_epoch, expected_task_version,
                        subject_task_epoch, subject_stage_id,
                        subject_stage_generation, subject_operation_id,
                        subject_attempt, subject_expected_code_fingerprint,
                        subject_expected_head_sha, subject_expected_base_sha,
                        next_stage_id, next_stage_kind, next_stage_generation,
                        returned_trunk_id, returned_lifecycle, returned_epoch,
                        returned_version, returned_current_stage_id, recorded_at_ms)
                    VALUES ('remote-receipt-%1$s', 'task-%1$s',
                        'open-remote-%1$s', 'OPEN_REMOTE_DEVELOPMENT',
                        'result-delivery', 'APPLIED', 1, 1, 1,
                        'local-stage-%1$s', 1, 'publish-operation-id-%1$s', 1,
                        'fingerprint-%1$s', 'head-%1$s', 'base-%1$s',
                        'remote-stage-%1$s', 'REMOTE_DEVELOPMENT', 1,
                        'trunk-1', 'ACTIVE', 1, 2, 'remote-stage-%1$s', 52)
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

    static void insertRevisionBackedCurrentSubject(Connection connection)
            throws SQLException
    {
        execute(connection, """
                INSERT INTO remote_ci_policy_revision(
                    id, task_id, remote_pr_binding_id, revision, source,
                    none_outcome, missing_outcome, queued_outcome,
                    pending_outcome, neutral_outcome, skipped_outcome,
                    canceled_outcome, created_by, created_at_ms)
                VALUES ('revision-ci-policy', 'task-1', 'binding-1', 2,
                    'REPOSITORY', 'WAITING', 'FAILED', 'WAITING', 'WAITING',
                    'WAITING', 'FAILED', 'FAILED', 'test', 75)
                """);
        execute(connection, """
                INSERT INTO remote_ci_evaluation(
                    id, remote_development_stage_id, remote_pr_snapshot_id,
                    ci_policy_revision_id, task_id, task_epoch,
                    stage_generation, head_sha, base_sha, normalized_status,
                    policy_outcome, check_count, missing_required_count,
                    evidence, evaluated_at_ms)
                VALUES ('revision-ci-evaluation', 'remote-stage-1',
                    'snapshot-1-1', 'revision-ci-policy', 'task-1', 1, 1,
                    'head-1', 'base-1', 'FAILED', 'FAILED', 1, 0,
                    'prior failure', 76)
                """);
        execute(connection, """
                INSERT INTO ci_repair_episode(
                    id, remote_development_stage_id, task_id, task_epoch,
                    stage_generation, remote_pr_binding_id,
                    failed_ci_evaluation_id, subject_head_sha, subject_base_sha,
                    classification, status, rerun_limit, fix_attempt_limit,
                    delivery_retry_limit, push_limit, opened_at_ms)
                VALUES ('revision-episode', 'remote-stage-1', 'task-1', 1, 1,
                    'binding-1', 'revision-ci-evaluation', 'head-1', 'base-1',
                    'TASK_DETERMINISTIC', 'OPEN', 0, 3, 2, 3, 77)
                """);
        execute(connection, """
                INSERT INTO ci_repair_turn_freshness_v319(
                    id, ci_repair_episode_id, intent_kind, intent_id,
                    semantic_attempt, execution_attempt,
                    predecessor_snapshot_id,
                    predecessor_observation_revision,
                    accepted_snapshot_id, accepted_observation_revision,
                    accepted_ci_evaluation_id, remote_head_sha,
                    authoritative_base_sha, code_fingerprint, code_head_sha,
                    code_base_sha, prepublish_branch_sync_episode_id,
                    authorized_at_ms)
                VALUES ('revision-freshness', 'revision-episode',
                    'OBSERVED_FAILURE', 'revision-ci-evaluation', 1, 1,
                    NULL, NULL, 'snapshot-1-1', 1,
                    'revision-ci-evaluation', 'head-1', 'base-1',
                    'fingerprint-1', 'head-1', 'base-1', NULL, 78)
                """);
        execute(connection, """
                INSERT INTO stage_turn(
                    id, stage_id, stage_generation, purpose, status,
                    operation_id, attempt, task_epoch,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, delivery_lane, launch_input,
                    requested_at_ms)
                VALUES ('revision-stage-turn', 'remote-stage-1', 1,
                    'REMOTE_CI_REPAIR', 'QUEUED', 'revision-operation', 1, 1,
                    'fingerprint-1', 'head-1', 'base-1', 'API',
                    '{"transport":"API","provider":"openai",'
                    || '"model":"model","workingDirectory":"/tmp/task-1"}',
                    79)
                """);
        execute(connection, """
                INSERT INTO ci_repair_operation(
                    id, ci_repair_episode_id, remote_development_stage_id,
                    task_id, task_epoch, stage_generation, kind, operation_id,
                    semantic_attempt, stage_turn_id,
                    expected_code_fingerprint, expected_head_sha,
                    expected_base_sha, status, requested_at_ms)
                VALUES ('revision-operation-row', 'revision-episode',
                    'remote-stage-1', 'task-1', 1, 1, 'FIX_STAGE_TURN',
                    'revision-operation', 1, 'revision-stage-turn',
                    'fingerprint-1', 'head-1', 'base-1', 'REQUESTED', 79)
                """);
        execute(connection, """
                UPDATE ci_repair_operation
                SET status = 'SUCCEEDED',
                    result_code_fingerprint = 'fingerprint-1',
                    result_head_sha = 'head-1', result_evidence = 'prior repair',
                    completed_at_ms = 80
                WHERE id = 'revision-operation-row'
                """);
        execute(connection, """
                UPDATE stage_turn
                SET status = 'SUCCEEDED', started_at_ms = 79,
                    finished_at_ms = 80
                WHERE id = 'revision-stage-turn'
                """);
        execute(connection, """
                INSERT INTO remote_worktree_subject(
                    id, task_id, task_epoch, remote_development_stage_id,
                    stage_generation, revision, source_kind,
                    source_operation_id, code_fingerprint, head_sha, base_sha,
                    recorded_at_ms)
                VALUES ('revision-worktree', 'task-1', 1, 'remote-stage-1', 1,
                    1, 'CI_STAGE_TURN', 'revision-operation', 'fingerprint-1',
                    'head-1', 'base-1', 80)
                """);
        execute(connection, """
                UPDATE ci_repair_episode
                SET status = 'STOPPED', completed_at_ms = 81,
                    stop_reason = 'prior repair fixture completed'
                WHERE id = 'revision-episode'
                """);
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
                    'NONE', max(60 + %2$s, (SELECT subject_changed_at_ms + 1
                        FROM remote_development_stage
                        WHERE stage_id = 'remote-stage-%1$s')))
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
                    '%7$s', %9$s, %10$s, %11$s, %12$s,
                    max(60 + %2$s, (SELECT subject_changed_at_ms + 1
                        FROM remote_development_stage
                        WHERE stage_id = 'remote-stage-%1$s')), '%8$s')
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
                    subject_changed_at_ms = (SELECT observed_at_ms
                        FROM remote_pr_snapshot
                        WHERE id = 'snapshot-%1$s-%2$s')
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
                    max(70 + %2$s, (SELECT observed_at_ms
                        FROM remote_pr_snapshot
                        WHERE id = 'snapshot-%1$s-%2$s')))
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
                    max(71 + %2$s, (SELECT observed_at_ms + 1
                        FROM remote_pr_snapshot
                        WHERE id = 'snapshot-%1$s-%2$s')))
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
                    'SUCCESS', max(70 + %2$s, (SELECT observed_at_ms
                        FROM remote_pr_snapshot
                        WHERE id = 'snapshot-%1$s-%2$s')))
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
                    max(71 + %2$s, (SELECT observed_at_ms + 1
                        FROM remote_pr_snapshot
                        WHERE id = 'snapshot-%1$s-%2$s')))
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
