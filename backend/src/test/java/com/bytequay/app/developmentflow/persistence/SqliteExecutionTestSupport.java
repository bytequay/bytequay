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
package com.bytequay.app.developmentflow.persistence;

import com.bytequay.app.developmentflow.execution.CapacityManager;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.testing.MigratedSqliteDatabase;
import com.bytequay.app.testing.V2TaskSeed;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Set;

public final class SqliteExecutionTestSupport
{
    private SqliteExecutionTestSupport() {}

    public static Database database(Path file)
    {
        String url = "jdbc:sqlite:" + file
                + "?foreign_keys=ON&journal_mode=WAL&busy_timeout=30000";
        MigratedSqliteDatabase.migrate(url);
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        return new Database(url, dataSource, new JdbcTemplate(dataSource));
    }

    static SQLiteDataSource dataSource(String url)
    {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        return dataSource;
    }

    public static void seedTrunk(
            Database database, String workspaceId, String trunkId)
    {
        JdbcTemplate jdbc = database.jdbc();
        jdbc.update("""
                INSERT OR IGNORE INTO workspaces(
                    id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
                VALUES (?, ?, '', 0, 1, 1)
                """, workspaceId, workspaceId);
        jdbc.update("""
                INSERT INTO threads(
                    id, kind, provider, title, status, model,
                    cost_usd_milli, tokens_in, tokens_out,
                    created_at_ms, updated_at_ms, workspace_id, flow,
                    parallel_slots, turn_version, lifecycle_state,
                    aggregate_version)
                VALUES (?, 'CLI_AGENT', 'claude-code', ?, 'IDLE',
                    'claude-sonnet-4.6', 0, 0, 0, 1, 1, ?, 'build', 2,
                    'V2', 'ACTIVE', 1)
                """, trunkId, trunkId, workspaceId);
        V2TaskSeed.prepareWorkspaces(jdbc);
        jdbc.update("""
                INSERT INTO task_policy_revision(
                    id, trunk_id, revision, source, created_by, created_at_ms)
                VALUES (?, ?, 1, 'TRUNK', 'test', 1)
                """, policyId(trunkId), trunkId);
    }

    public static void seedTask(
            Database database,
            String trunkId,
            String taskId,
            int sequence)
    {
        JdbcTemplate jdbc = database.jdbc();
        String assignmentId = "assignment-" + taskId;
        String authorizationId = "authorization-" + taskId;
        V2TaskSeed.insertAuthorized(jdbc, assignmentId, transaction ->
                transaction.update("""
                        INSERT INTO task_assignment(
                            id, trunk_id, kind, planning_base_sha, plan_seed,
                            prompt, created_by, created_at_ms,
                            creation_authorization_id)
                        VALUES (?, ?, 'NEW_FROM_TRUNK', 'base', 'seed',
                            'prompt', 'test', 1, ?)
                        """, assignmentId, trunkId, authorizationId));
        V2TaskSeed.insertCreated(jdbc, taskId, transaction ->
                transaction.update("""
                        INSERT INTO tasks(
                            id, thread_id, seq, status, phase, created_at_ms,
                            workflow_version, lifecycle_state, assignment_id,
                            policy_revision_id, creation_receipt_id, name,
                            task_type, opening_prompt, origin)
                        VALUES (?, ?, ?, 'IDLE', 'PLANNING', 1, 'V2',
                            'PROVISIONING', ?, ?, ?, ?, 'DEVELOP', 'prompt',
                            'user')
                        """, taskId, trunkId, sequence, assignmentId,
                        policyId(trunkId), "creation-receipt-" + taskId,
                        "Test task " + assignmentId));
    }

    public static void seedStage(
            Database database,
            String taskId,
            String stageId)
    {
        JdbcTemplate jdbc = database.jdbc();
        jdbc.update("""
                INSERT INTO stage(
                    id, task_id, kind, generation, version, checkpoint, opened_at_ms)
                VALUES (?, ?, 'LOCAL_DEVELOPMENT', 1, 0, 'IMPLEMENTING', 2)
                """, stageId, taskId);
        jdbc.update("""
                INSERT INTO task_current_stage(task_id, stage_id, stage_generation)
                VALUES (?, ?, 1)
                """, taskId, stageId);
        jdbc.update("""
                UPDATE tasks SET lifecycle_state = 'ACTIVE', aggregate_version = 1
                WHERE id = ?
                """, taskId);
    }

    static DispatchTicket requestedTaskTicket(
            String id,
            String operationId,
            String workspaceId,
            String trunkId,
            String taskId,
            Instant createdAt,
            CapacityManager.CapacityLane lane,
            boolean exclusive,
            boolean writer)
    {
        return requestedTaskTicket(
                id, operationId, workspaceId, trunkId, taskId, createdAt,
                lane, exclusive, writer, DispatchTicket.AsyncFamily.VALIDATION);
    }

    static DispatchTicket requestedAgentTaskTicket(
            String id,
            String operationId,
            String workspaceId,
            String trunkId,
            String taskId,
            Instant createdAt,
            CapacityManager.CapacityLane lane,
            boolean exclusive,
            boolean writer)
    {
        return requestedTaskTicket(
                id, operationId, workspaceId, trunkId, taskId, createdAt,
                lane, exclusive, writer, DispatchTicket.AsyncFamily.AGENT_TURN);
    }

    private static DispatchTicket requestedTaskTicket(
            String id,
            String operationId,
            String workspaceId,
            String trunkId,
            String taskId,
            Instant createdAt,
            CapacityManager.CapacityLane lane,
            boolean exclusive,
            boolean writer,
            DispatchTicket.AsyncFamily asyncFamily)
    {
        CapacityManager.CapacityRequest capacity = new CapacityManager.CapacityRequest(
                operationId,
                CapacityManager.WorkflowSource.V2,
                Set.of(lane),
                new CapacityManager.CapacityScope(
                        workspaceId, trunkId, taskId, 1L),
                false,
                exclusive,
                writer);
        return DispatchTicket.requested(
                id,
                new DispatchTicket.DispatchEnvelope(
                        "TEST_OPERATION",
                        asyncFamily,
                        new DispatchTicket.OwnerReference(
                                DispatchTicket.OwnerKind.TASK, taskId, "task-result"),
                        new DispatchTicket.OperationFence(
                                1L, null, null, operationId, 1,
                                "fingerprint-" + operationId,
                                "head-" + operationId,
                                "base-" + operationId),
                        capacity),
                createdAt);
    }

    static DispatchTicket requestedTrunkControlTicket(
            String id,
            String operationId,
            String workspaceId,
            String trunkId,
            Instant createdAt)
    {
        CapacityManager.CapacityRequest capacity = new CapacityManager.CapacityRequest(
                operationId,
                CapacityManager.WorkflowSource.V2,
                Set.of(CapacityManager.CapacityLane.CLI),
                new CapacityManager.CapacityScope(workspaceId, trunkId, null, null),
                true,
                false,
                false);
        return DispatchTicket.requested(
                id,
                new DispatchTicket.DispatchEnvelope(
                        "TRUNK_CONTROL",
                        DispatchTicket.AsyncFamily.AGENT_TURN,
                        new DispatchTicket.OwnerReference(
                                DispatchTicket.OwnerKind.TRUNK, trunkId, "trunk-result"),
                        new DispatchTicket.OperationFence(
                                null, null, null, operationId, 1,
                                null, null, null),
                        capacity),
                createdAt);
    }

    public static void insertTicket(Database database, DispatchTicket ticket)
    {
        database.jdbc().update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO dispatch_ticket(
                        id, version, operation_id, operation_kind, async_family,
                        owner_kind, owner_id, callback_route, lane_mask,
                        trunk_control, exclusive_task, writer_required,
                        workspace_id, trunk_id, task_id, task_epoch, stage_id,
                        stage_generation, attempt, expected_code_fingerprint,
                        expected_head_sha, expected_base_sha, status, claim_purpose,
                        claim_owner, capacity_lease_id, claim_expires_at_ms,
                        next_attempt_at_ms, cancel_requested_at_ms,
                        infrastructure_attempts, started_at_ms,
                        pending_result_outcome, pending_result_payload,
                        pending_result_evidence, pending_result_error,
                        delivery_acceptance, delivery_evidence, created_at_ms,
                        completed_at_ms, last_error, pending_result_task_epoch,
                        pending_result_stage_id, pending_result_stage_generation,
                        pending_result_operation_id, pending_result_attempt,
                        pending_result_expected_code_fingerprint,
                        pending_result_expected_head_sha,
                        pending_result_expected_base_sha)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """);
            bindTicket(statement, ticket);
            return statement;
        });
    }

    private static void bindTicket(PreparedStatement statement, DispatchTicket ticket)
            throws SQLException
    {
        DispatchTicket.DispatchEnvelope envelope = ticket.envelope();
        CapacityManager.CapacityRequest capacity = envelope.capacityRequest();
        DispatchTicket.OperationFence fence = envelope.fence();
        DispatchTicket.DispatchResult pending = ticket.pendingResult();
        DispatchTicket.OperationFence rawFence = pending == null ? null : pending.fence();
        DispatchTicket.DeliveryReceipt receipt = ticket.deliveryReceipt();
        int index = 1;
        statement.setString(index++, ticket.id());
        statement.setLong(index++, ticket.version());
        statement.setString(index++, fence.operationId());
        statement.setString(index++, envelope.operationKind());
        statement.setString(index++, envelope.family().name());
        statement.setString(index++, envelope.owner().kind().name());
        statement.setString(index++, envelope.owner().id());
        statement.setString(index++, envelope.owner().callbackRoute());
        statement.setInt(index++, CapacityManager.CapacityLane.toMask(capacity.lanes()));
        statement.setInt(index++, bool(capacity.trunkControl()));
        statement.setInt(index++, bool(capacity.exclusiveTask()));
        statement.setInt(index++, bool(capacity.writerRequired()));
        statement.setString(index++, capacity.scope().workspaceId());
        statement.setString(index++, capacity.scope().trunkId());
        statement.setString(index++, capacity.scope().taskId());
        setLong(statement, index++, fence.taskEpoch());
        statement.setString(index++, fence.stageId());
        setLong(statement, index++, fence.stageGeneration());
        statement.setInt(index++, fence.attempt());
        statement.setString(index++, fence.expectedCodeFingerprint());
        statement.setString(index++, fence.expectedHeadSha());
        statement.setString(index++, fence.expectedBaseSha());
        statement.setString(index++, ticket.state().name());
        statement.setString(index++, name(ticket.claimPurpose()));
        statement.setString(index++, ticket.claimOwner());
        statement.setString(index++, ticket.capacityLeaseId());
        setInstant(statement, index++, ticket.claimExpiresAt());
        setInstant(statement, index++, ticket.nextAttemptAt());
        setInstant(statement, index++, ticket.cancelRequestedAt());
        statement.setInt(index++, ticket.infrastructureAttempts());
        setInstant(statement, index++, ticket.startedAt());
        statement.setString(index++, pending == null ? null : pending.outcome().name());
        statement.setString(index++, pending == null ? null : pending.payloadJson());
        statement.setString(index++, pending == null ? null : pending.evidenceJson());
        statement.setString(index++, pending == null ? null : pending.error());
        statement.setString(index++, receipt == null ? null : receipt.acceptance().name());
        statement.setString(index++, receipt == null ? null : receipt.evidenceJson());
        statement.setLong(index++, ticket.createdAt().toEpochMilli());
        setInstant(statement, index++, ticket.completedAt());
        statement.setString(index++, ticket.lastError());
        setLong(statement, index++, rawFence == null ? null : rawFence.taskEpoch());
        statement.setString(index++, rawFence == null ? null : rawFence.stageId());
        setLong(statement, index++, rawFence == null ? null : rawFence.stageGeneration());
        statement.setString(index++, rawFence == null ? null : rawFence.operationId());
        setLong(statement, index++, rawFence == null ? null : (long) rawFence.attempt());
        statement.setString(index++, rawFence == null
                ? null : rawFence.expectedCodeFingerprint());
        statement.setString(index++, rawFence == null ? null : rawFence.expectedHeadSha());
        statement.setString(index, rawFence == null ? null : rawFence.expectedBaseSha());
    }

    private static String policyId(String trunkId)
    {
        return "policy-" + trunkId;
    }

    private static void setLong(PreparedStatement statement, int index, Long value)
            throws SQLException
    {
        if (value == null) {
            statement.setObject(index, null);
        }
        else {
            statement.setLong(index, value);
        }
    }

    private static void setInstant(
            PreparedStatement statement, int index, Instant value)
            throws SQLException
    {
        setLong(statement, index, value == null ? null : value.toEpochMilli());
    }

    private static int bool(boolean value)
    {
        return value ? 1 : 0;
    }

    private static String name(Enum<?> value)
    {
        return value == null ? null : value.name();
    }

    public record Database(
            String url, SQLiteDataSource dataSource, JdbcTemplate jdbc) {}
}
