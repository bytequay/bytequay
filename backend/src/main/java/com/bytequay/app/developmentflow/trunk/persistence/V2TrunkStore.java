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
package com.bytequay.app.developmentflow.trunk.persistence;

import com.bytequay.app.developmentflow.CommandRejectedException;
import com.bytequay.app.developmentflow.CommandResult;
import com.bytequay.app.developmentflow.task.creation.TaskAssignment;
import com.bytequay.app.developmentflow.task.creation.TaskCreationInput;
import com.bytequay.app.developmentflow.trunk.TrunkLifecycle;
import com.bytequay.app.developmentflow.trunk.TrunkManager;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static com.bytequay.app.developmentflow.CommandRejectedException.Reason.CONCURRENT_UPDATE;
import static java.util.Objects.requireNonNull;

/** Spring-transaction-bound persistence for the Trunk aggregate. */
@Component
final class V2TrunkStore
        implements TrunkManager.Store
{
    private final JdbcTemplate jdbc;

    V2TrunkStore(JdbcTemplate jdbc)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
    }

    @Override
    public Optional<TrunkManager.State> findById(String trunkId)
    {
        return jdbc.query("""
                SELECT id, lifecycle_state, aggregate_version
                FROM threads
                WHERE id = ? AND turn_version = 'V2'
                """,
                (rs, row) -> new TrunkManager.State(
                        rs.getString("id"),
                        TrunkLifecycle.valueOf(rs.getString("lifecycle_state")),
                        rs.getLong("aggregate_version")),
                trunkId).stream().findFirst();
    }

    @Override
    public Optional<TrunkManager.CommandReceipt> findCommandResult(
            String trunkId, String commandId)
    {
        return jdbc.query("""
                SELECT cause, actor, disposition, expected_version,
                       returned_lifecycle, returned_version
                FROM trunk_command_receipt
                WHERE trunk_id = ? AND command_id = ?
                """,
                (rs, row) -> new TrunkManager.CommandReceipt(
                        new TrunkManager.State(
                                trunkId,
                                TrunkLifecycle.valueOf(rs.getString("returned_lifecycle")),
                                rs.getLong("returned_version")),
                        rs.getString("cause"),
                        rs.getString("actor"),
                        rs.getLong("expected_version"),
                        CommandResult.Disposition.valueOf(rs.getString("disposition"))),
                trunkId, commandId).stream().findFirst();
    }

    @Override
    public Optional<TrunkManager.TaskCreationAuthorizationReceipt>
            findTaskCreationAuthorization(String trunkId, String commandId)
    {
        if (!taskCreationProtocolExists()) {
            return Optional.empty();
        }
        return jdbc.query("""
                SELECT actor, expected_trunk_version, returned_trunk_version,
                       returned_lifecycle, assignment_id, id, policy_revision_id
                FROM trunk_task_creation_authorization
                WHERE trunk_id = ? AND command_id = ?
                """,
                (rs, row) -> new TrunkManager.TaskCreationAuthorizationReceipt(
                        new TrunkManager.State(
                                trunkId,
                                TrunkLifecycle.valueOf(
                                        rs.getString("returned_lifecycle")),
                                rs.getLong("returned_trunk_version")),
                        rs.getString("actor"),
                        rs.getLong("expected_trunk_version"),
                        rs.getString("assignment_id"),
                        rs.getString("id"),
                        rs.getString("policy_revision_id")),
                trunkId, commandId).stream().findFirst();
    }

    private boolean taskCreationProtocolExists()
    {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sqlite_master
                WHERE type = 'table'
                  AND name = 'trunk_task_creation_authorization'
                """, Integer.class);
        return count != null && count == 1;
    }

    @Override
    public Optional<TrunkManager.PlanningBaseRequestReceipt>
            findPlanningBaseRequest(String trunkId, String commandId)
    {
        if (!planningRuntimeExists()) {
            return Optional.empty();
        }
        return jdbc.query("""
                SELECT operation.actor, operation.id, operation.operation_id,
                       operation.dispatch_ticket_id,
                       operation.launch_command_id,
                       operation.reserved_thread_turn_id,
                       receipt.expected_trunk_version,
                       receipt.returned_trunk_version,
                       receipt.returned_lifecycle, receipt.recorded_at_ms
                FROM planning_base_refresh_operation operation
                JOIN trunk_planning_base_request_receipt receipt
                  ON receipt.planning_operation_id = operation.id
                WHERE receipt.trunk_id = ? AND receipt.command_id = ?
                """, (rs, row) -> new TrunkManager.PlanningBaseRequestReceipt(
                        new TrunkManager.State(
                                trunkId,
                                TrunkLifecycle.valueOf(
                                        rs.getString("returned_lifecycle")),
                                rs.getLong("returned_trunk_version")),
                        commandId, rs.getString("actor"),
                        rs.getLong("expected_trunk_version"),
                        rs.getString("id"), rs.getString("operation_id"),
                        rs.getString("dispatch_ticket_id"),
                        rs.getString("launch_command_id"),
                        rs.getString("reserved_thread_turn_id"),
                        Instant.ofEpochMilli(rs.getLong("recorded_at_ms"))),
                trunkId, commandId).stream().findFirst();
    }

    @Override
    public boolean matchesPlanningBaseRequest(
            TrunkManager.PlanningBaseCommand command)
    {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT operation.*, receipt.expected_trunk_version
                FROM planning_base_refresh_operation operation
                JOIN trunk_planning_base_request_receipt receipt
                  ON receipt.planning_operation_id = operation.id
                WHERE operation.trunk_id = ? AND operation.command_id = ?
                """, command.trunkId(), command.commandId());
        if (rows.size() != 1) {
            return false;
        }
        Map<String, Object> row = rows.getFirst();
        return same(row, "workspace_id", command.workspaceId())
                && same(row, "actor", command.actor())
                && same(row, "id", command.planningOperationId())
                && same(row, "operation_id", command.operationId())
                && same(row, "dispatch_ticket_id", command.dispatchTicketId())
                && same(row, "launch_command_id", command.launchCommandId())
                && same(row, "reserved_thread_turn_id",
                command.reservedThreadTurnId())
                && same(row, "repository_root", command.repositoryRoot())
                && same(row, "previous_base_sha", command.previousBaseSha())
                && same(row, "launch_intent", command.launchIntent())
                && same(row, "launch_intent_digest", command.launchIntentDigest())
                && number(row, "semantic_attempt") == 1
                && number(row, "expected_trunk_version")
                == command.expectedTrunkVersion();
    }

    @Override
    public TrunkManager.PlanningBaseRequestReceipt requestPlanningBaseRefresh(
            TrunkManager.PlanningBaseCommand command,
            TrunkManager.State expected,
            TrunkManager.State updated)
    {
        requireTransaction();
        if (!expected.id().equals(command.trunkId())
                || !expected.id().equals(updated.id())
                || expected.version() != command.expectedTrunkVersion()
                || updated.version() != expected.version() + 1
                || updated.lifecycle() != TrunkLifecycle.ACTIVE) {
            throw new IllegalArgumentException(
                    "Planning-base request Trunk fence is inconsistent");
        }
        long now = command.requestedAt().toEpochMilli();
        jdbc.update("""
                INSERT INTO planning_base_refresh_operation(
                    id, trunk_id, workspace_id, command_id, actor,
                    operation_id, dispatch_ticket_id, launch_command_id,
                    reserved_thread_turn_id, semantic_attempt,
                    repository_root, previous_base_sha,
                    launch_intent, launch_intent_digest, status,
                    requested_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 1, ?, ?, ?, ?, 'REQUESTED', ?)
                """, command.planningOperationId(), command.trunkId(),
                command.workspaceId(), command.commandId(), command.actor(),
                command.operationId(), command.dispatchTicketId(),
                command.launchCommandId(), command.reservedThreadTurnId(),
                command.repositoryRoot(),
                command.previousBaseSha(), command.launchIntent(),
                command.launchIntentDigest(), now);
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, task_id, task_epoch,
                    stage_id, stage_generation, attempt, expected_base_sha,
                    status, next_attempt_at_ms, created_at_ms)
                VALUES (?, ?, 'REFRESH_PLANNING_BASE', 'LOCAL_GIT',
                    'TRUNK', ?, 'PLANNING_BASE_REFRESH_RESULT', 16,
                    1, 0, 0, ?, ?, NULL, NULL, NULL, NULL, 1, ?,
                    'REQUESTED', ?, ?)
                """, command.dispatchTicketId(), command.operationId(),
                command.trunkId(), command.workspaceId(), command.trunkId(),
                command.previousBaseSha(), now, now);
        updateTrunk(expected, updated, "Planning-base request");
        jdbc.update("""
                INSERT INTO trunk_transition(
                    id, trunk_id, command_id, from_state, to_state,
                    aggregate_version, cause, actor, occurred_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, 'REQUEST_PLANNING_BASE_REFRESH', ?, ?)
                """, id(), command.trunkId(), command.commandId(),
                expected.lifecycle().name(), updated.lifecycle().name(),
                updated.version(), command.actor(), now);
        jdbc.update("""
                INSERT INTO trunk_planning_base_request_receipt(
                    id, trunk_id, command_id, planning_operation_id,
                    expected_trunk_version, returned_trunk_version,
                    returned_lifecycle, recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, id(), command.trunkId(), command.commandId(),
                command.planningOperationId(), expected.version(),
                updated.version(), updated.lifecycle().name(), now);
        return new TrunkManager.PlanningBaseRequestReceipt(
                updated, command.commandId(), command.actor(), expected.version(),
                command.planningOperationId(), command.operationId(),
                command.dispatchTicketId(), command.launchCommandId(),
                command.reservedThreadTurnId(),
                command.requestedAt());
    }

    @Override
    public Optional<String> findPlanningBaseTrunk(String operationId)
    {
        if (!planningRuntimeExists()) {
            return Optional.empty();
        }
        return jdbc.query("""
                SELECT operation.trunk_id
                FROM planning_base_refresh_operation operation
                JOIN threads trunk ON trunk.id = operation.trunk_id
                WHERE operation.operation_id = ? AND trunk.turn_version = 'V2'
                """, (rs, row) -> rs.getString("trunk_id"), operationId)
                .stream().findFirst();
    }

    @Override
    public Optional<TrunkManager.PlanningBaseResultReceipt>
            findPlanningBaseResult(String operationId)
    {
        if (!planningRuntimeExists()) {
            return Optional.empty();
        }
        return jdbc.query("""
                SELECT receipt.trunk_id, receipt.command_id,
                       receipt.planning_operation_id, receipt.operation_id,
                       receipt.raw_outcome, receipt.raw_result_digest,
                       receipt.acceptance, operation.status,
                       operation.result_base_sha,
                       receipt.returned_trunk_version,
                       receipt.returned_lifecycle, receipt.recorded_at_ms
                FROM trunk_planning_base_result_receipt receipt
                JOIN planning_base_refresh_operation operation
                  ON operation.id = receipt.planning_operation_id
                WHERE receipt.operation_id = ?
                """, (rs, row) -> new TrunkManager.PlanningBaseResultReceipt(
                        new TrunkManager.State(
                                rs.getString("trunk_id"),
                                TrunkLifecycle.valueOf(
                                        rs.getString("returned_lifecycle")),
                                rs.getLong("returned_trunk_version")),
                        rs.getString("command_id"),
                        rs.getString("planning_operation_id"),
                        rs.getString("operation_id"),
                        rs.getString("raw_outcome"),
                        rs.getString("raw_result_digest"),
                        rs.getString("acceptance"), rs.getString("status"),
                        rs.getString("result_base_sha"),
                        Instant.ofEpochMilli(rs.getLong("recorded_at_ms"))),
                operationId).stream().findFirst();
    }

    @Override
    public TrunkManager.PlanningBaseResultContext requirePlanningBaseResult(
            String operationId)
    {
        return jdbc.query("""
                SELECT trunk.id AS trunk_id, trunk.lifecycle_state,
                       trunk.aggregate_version, operation.id,
                       operation.operation_id, operation.semantic_attempt,
                       operation.repository_root, operation.previous_base_sha,
                       (EXISTS (
                           SELECT 1 FROM thread_turn turn
                           WHERE turn.trunk_id = operation.trunk_id
                             AND turn.status IN (
                               'REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING'))
                       OR EXISTS (
                           SELECT 1
                           FROM planning_base_refresh_operation other
                           WHERE other.trunk_id = operation.trunk_id
                             AND other.id <> operation.id
                             AND (other.status = 'REQUESTED'
                               OR (other.status = 'SUCCEEDED'
                                 AND other.launch_disposition = 'PENDING'))))
                           AS other_live_work
                FROM planning_base_refresh_operation operation
                JOIN threads trunk ON trunk.id = operation.trunk_id
                WHERE operation.operation_id = ?
                  AND operation.status = 'REQUESTED'
                  AND trunk.turn_version = 'V2'
                """, (rs, row) -> new TrunkManager.PlanningBaseResultContext(
                        new TrunkManager.State(
                                rs.getString("trunk_id"),
                                TrunkLifecycle.valueOf(
                                        rs.getString("lifecycle_state")),
                                rs.getLong("aggregate_version")),
                        rs.getString("id"), rs.getString("operation_id"),
                        rs.getInt("semantic_attempt"),
                        rs.getString("repository_root"),
                        rs.getString("previous_base_sha"),
                        rs.getBoolean("other_live_work")),
                operationId).stream().findFirst().orElseThrow(() ->
                new IllegalStateException(
                        "Planning-base Operation is not requestable: " + operationId));
    }

    @Override
    public TrunkManager.PlanningBaseResultReceipt acceptPlanningBaseResult(
            TrunkManager.PlanningBaseResultFact fact,
            TrunkManager.PlanningBaseResultContext context,
            TrunkManager.State updated,
            String acceptance,
            String operationStatus)
    {
        requireTransaction();
        TrunkManager.State expected = context.trunkState();
        long now = fact.finishedAt().toEpochMilli();
        boolean succeeded = "SUCCEEDED".equals(operationStatus);
        int changed = jdbc.update("""
                UPDATE planning_base_refresh_operation
                SET status = ?, raw_outcome = ?, raw_result_digest = ?,
                    result_worktree_path = ?, result_base_ref = ?,
                    result_base_sha = ?, error_message = ?, completed_at_ms = ?
                WHERE id = ? AND operation_id = ? AND status = 'REQUESTED'
                  AND semantic_attempt = ?
                  AND previous_base_sha IS ?
                """, operationStatus, fact.rawOutcome(), fact.rawResultDigest(),
                succeeded ? fact.worktreePath() : null,
                succeeded ? fact.baseRef() : null,
                succeeded ? fact.baseSha() : null,
                fact.error(),
                now, context.planningOperationId(), fact.operationId(),
                fact.attempt(), fact.expectedPreviousBaseSha());
        if (changed != 1) {
            throw concurrent("Planning-base result changed before acceptance");
        }
        if ("SUCCEEDED".equals(operationStatus)) {
            jdbc.update("""
                    UPDATE threads
                    SET planning_repo_root = ?, planning_base_sha = ?
                    WHERE id = ? AND turn_version = 'V2'
                    """, fact.repositoryRoot(), fact.baseSha(), expected.id());
        }
        updateTrunk(expected, updated, "Planning-base result");
        String commandId = fact.commandId();
        jdbc.update("""
                INSERT INTO trunk_transition(
                    id, trunk_id, command_id, from_state, to_state,
                    aggregate_version, cause, actor, occurred_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, 'ACCEPT_PLANNING_BASE_REFRESH', ?, ?)
                """, id(), expected.id(), commandId,
                expected.lifecycle().name(), updated.lifecycle().name(),
                updated.version(), fact.actor(), now);
        jdbc.update("""
                INSERT INTO trunk_planning_base_result_receipt(
                    id, trunk_id, command_id, planning_operation_id,
                    operation_id, raw_outcome, raw_result_digest,
                    acceptance, returned_trunk_version,
                    returned_lifecycle, recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, id(), expected.id(), commandId,
                context.planningOperationId(), fact.operationId(),
                fact.rawOutcome(), fact.rawResultDigest(), acceptance,
                updated.version(), updated.lifecycle().name(), now);
        return new TrunkManager.PlanningBaseResultReceipt(
                updated, commandId, context.planningOperationId(),
                fact.operationId(), fact.rawOutcome(), fact.rawResultDigest(),
                acceptance, operationStatus,
                succeeded ? fact.baseSha() : null, fact.finishedAt());
    }

    @Override
    public boolean isPlanningBaseCommandUsed(String trunkId, String commandId)
    {
        if (!planningRuntimeExists()) {
            return false;
        }
        Integer used = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM planning_base_refresh_operation
                    WHERE trunk_id = ? AND command_id = ?
                    UNION ALL
                    SELECT 1 FROM trunk_planning_base_result_receipt
                    WHERE trunk_id = ? AND command_id = ?)
                """, Integer.class,
                trunkId, commandId, trunkId, commandId);
        return used != null && used == 1;
    }

    @Override
    public boolean isPlanningLaunchPending(
            String planningOperationId, String trunkId, String turnId)
    {
        requireTransaction();
        Integer pending = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM planning_base_refresh_operation operation
                    JOIN dispatch_ticket ticket
                      ON ticket.id = operation.dispatch_ticket_id
                    WHERE operation.id = ? AND operation.trunk_id = ?
                      AND operation.reserved_thread_turn_id = ?
                      AND operation.status = 'SUCCEEDED'
                      AND operation.launch_disposition = 'PENDING'
                      AND ticket.status = 'SUCCEEDED'
                      AND ticket.delivery_acceptance = 'ACCEPTED')
                """, Integer.class, planningOperationId, trunkId, turnId);
        return pending != null && pending == 1;
    }

    @Override
    public void markPlanningLaunch(
            String planningOperationId, String turnId, Instant launchedAt)
    {
        requireTransaction();
        int changed = jdbc.update("""
                UPDATE planning_base_refresh_operation
                SET launch_disposition = 'LAUNCHED',
                    launched_thread_turn_id = ?, launched_at_ms = ?
                WHERE id = ? AND status = 'SUCCEEDED'
                  AND launch_disposition = 'PENDING'
                  AND reserved_thread_turn_id = ?
                  AND EXISTS (
                    SELECT 1 FROM thread_turn turn
                    WHERE turn.id = ?
                      AND turn.trunk_id = planning_base_refresh_operation.trunk_id
                      AND turn.planning_operation_id = planning_base_refresh_operation.id)
                """, turnId, launchedAt.toEpochMilli(), planningOperationId,
                turnId, turnId);
        if (changed != 1) {
            throw concurrent("Planning launch disposition changed before commit");
        }
    }

    @Override
    public int suppressPlanningLaunches(
            String trunkId, String reason, Instant suppressedAt)
    {
        requireTransaction();
        return jdbc.update("""
                UPDATE planning_base_refresh_operation
                SET launch_disposition = 'SUPPRESSED',
                    launch_disposition_reason = ?, launched_at_ms = ?
                WHERE trunk_id = ? AND status IN ('REQUESTED', 'SUCCEEDED')
                  AND launch_disposition = 'PENDING'
                  AND NOT EXISTS (
                    SELECT 1 FROM thread_turn turn
                    WHERE turn.id = planning_base_refresh_operation.reserved_thread_turn_id)
                """, reason, suppressedAt.toEpochMilli(), trunkId);
    }

    private boolean planningRuntimeExists()
    {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sqlite_master
                WHERE type = 'table' AND name = 'planning_base_refresh_operation'
                """, Integer.class);
        return count != null && count == 1;
    }

    @Override
    public Optional<String> findTaskOutcomeTrunk(
            String taskOutcomeId, String deliveryKey)
    {
        if (!taskOutcomeRuntimeExists()) {
            return Optional.empty();
        }
        return jdbc.query("""
                SELECT inbox.trunk_id
                FROM trunk_outcome_inbox inbox
                JOIN task_outcome outcome ON outcome.id = inbox.task_outcome_id
                JOIN threads trunk ON trunk.id = inbox.trunk_id
                WHERE inbox.task_outcome_id = ? AND inbox.delivery_key = ?
                  AND outcome.trunk_id = inbox.trunk_id
                  AND trunk.turn_version = 'V2'
                """, (rs, row) -> rs.getString("trunk_id"),
                taskOutcomeId, deliveryKey).stream().findFirst();
    }

    @Override
    public Optional<String> findTaskOutcomeTrunk(String taskOutcomeId)
    {
        if (!taskOutcomeRuntimeExists()) {
            return Optional.empty();
        }
        return jdbc.query("""
                SELECT outcome.trunk_id
                FROM task_outcome outcome
                JOIN trunk_outcome_inbox inbox
                  ON inbox.task_outcome_id = outcome.id
                JOIN threads trunk ON trunk.id = outcome.trunk_id
                WHERE outcome.id = ?
                  AND inbox.trunk_id = outcome.trunk_id
                  AND inbox.status = 'DELIVERED'
                  AND trunk.turn_version = 'V2'
                """, (rs, row) -> rs.getString("trunk_id"), taskOutcomeId)
                .stream().findFirst();
    }

    @Override
    public Optional<TrunkManager.TaskOutcomeReceipt> findTaskOutcomeReceipt(
            String taskOutcomeId, String deliveryKey)
    {
        if (!taskOutcomeRuntimeExists()) {
            return Optional.empty();
        }
        return jdbc.query("""
                SELECT inbox.trunk_id, inbox.task_id,
                       inbox.fallback_summary_text, inbox.delivered_at_ms,
                       inbox.returned_trunk_version,
                       transition.to_state, transition.actor
                FROM trunk_outcome_inbox inbox
                JOIN trunk_transition transition
                  ON transition.trunk_id = inbox.trunk_id
                 AND transition.command_id = inbox.delivery_key
                 AND transition.aggregate_version = inbox.returned_trunk_version
                WHERE inbox.task_outcome_id = ? AND inbox.delivery_key = ?
                  AND inbox.status = 'DELIVERED'
                  AND transition.cause = 'ACCEPT_TASK_OUTCOME'
                """, (rs, row) -> new TrunkManager.TaskOutcomeReceipt(
                        new TrunkManager.State(
                                rs.getString("trunk_id"),
                                TrunkLifecycle.valueOf(rs.getString("to_state")),
                                rs.getLong("returned_trunk_version")),
                        taskOutcomeId, rs.getString("task_id"), deliveryKey,
                        rs.getString("actor"),
                        rs.getString("fallback_summary_text"),
                        Instant.ofEpochMilli(rs.getLong("delivered_at_ms"))),
                taskOutcomeId, deliveryKey).stream().findFirst();
    }

    @Override
    public TrunkManager.TaskOutcomeContext requireTaskOutcome(
            String taskOutcomeId, String deliveryKey)
    {
        return jdbc.query("""
                SELECT trunk.id AS trunk_id, trunk.lifecycle_state,
                       trunk.aggregate_version, outcome.task_id,
                       outcome.task_epoch, inbox.delivery_key,
                       inbox.fallback_summary_text, inbox.created_at_ms
                FROM trunk_outcome_inbox inbox
                JOIN task_outcome outcome ON outcome.id = inbox.task_outcome_id
                JOIN tasks task ON task.id = outcome.task_id
                JOIN threads trunk ON trunk.id = outcome.trunk_id
                WHERE outcome.id = ? AND inbox.delivery_key = ?
                  AND inbox.task_outcome_id = outcome.id
                  AND inbox.trunk_id = outcome.trunk_id
                  AND inbox.task_id = outcome.task_id
                  AND inbox.status = 'PENDING'
                  AND inbox.fallback_summary_text IS NOT NULL
                  AND task.workflow_version = 'V2'
                  AND task.epoch = outcome.task_epoch
                  AND task.lifecycle_state IN (
                    'COMPLETED', 'CANCELED', 'REMOTE_CLOSED')
                  AND trunk.turn_version = 'V2'
                """, (rs, row) -> new TrunkManager.TaskOutcomeContext(
                        new TrunkManager.State(
                                rs.getString("trunk_id"),
                                TrunkLifecycle.valueOf(
                                        rs.getString("lifecycle_state")),
                                rs.getLong("aggregate_version")),
                        taskOutcomeId, rs.getString("task_id"),
                        rs.getLong("task_epoch"), rs.getString("delivery_key"),
                        rs.getString("fallback_summary_text"),
                        Instant.ofEpochMilli(rs.getLong("created_at_ms"))),
                taskOutcomeId, deliveryKey).stream().findFirst().orElseThrow(() ->
                new IllegalStateException(
                        "TaskOutcome inbox item is not pending: " + taskOutcomeId));
    }

    @Override
    public TrunkManager.TaskOutcomeReceipt acceptTaskOutcome(
            TrunkManager.TaskOutcomeFact fact,
            TrunkManager.TaskOutcomeContext context,
            TrunkManager.State updated)
    {
        requireTransaction();
        TrunkManager.State expected = context.trunkState();
        if (!expected.id().equals(updated.id())
                || updated.lifecycle() != expected.lifecycle()
                || updated.version() != expected.version() + 1
                || !context.taskOutcomeId().equals(fact.taskOutcomeId())
                || !context.deliveryKey().equals(fact.deliveryKey())) {
            throw new IllegalArgumentException(
                    "TaskOutcome Trunk receipt fence is inconsistent");
        }
        updateTrunk(expected, updated, "TaskOutcome delivery");
        long now = fact.receivedAt().toEpochMilli();
        jdbc.update("""
                INSERT INTO trunk_transition(
                    id, trunk_id, command_id, from_state, to_state,
                    aggregate_version, cause, actor, occurred_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, 'ACCEPT_TASK_OUTCOME', ?, ?)
                """, id(), expected.id(), fact.deliveryKey(),
                expected.lifecycle().name(), updated.lifecycle().name(),
                updated.version(), fact.actor(), now);
        int changed = jdbc.update("""
                UPDATE trunk_outcome_inbox
                SET status = 'DELIVERED', delivered_at_ms = ?,
                    delivery_evidence = ?, returned_trunk_version = ?
                WHERE task_outcome_id = ? AND delivery_key = ?
                  AND trunk_id = ? AND task_id = ? AND status = 'PENDING'
                  AND returned_trunk_version IS NULL
                """, now, "TaskOutcome accepted: " + fact.taskOutcomeId(),
                updated.version(), fact.taskOutcomeId(), fact.deliveryKey(),
                expected.id(), context.taskId());
        if (changed != 1) {
            throw concurrent(
                    "TaskOutcome inbox changed before Trunk acceptance");
        }
        return new TrunkManager.TaskOutcomeReceipt(
                updated, fact.taskOutcomeId(), context.taskId(),
                fact.deliveryKey(), fact.actor(),
                context.fallbackSummaryText(), fact.receivedAt());
    }

    @Override
    public boolean isTaskOutcomeCommandUsed(String trunkId, String commandId)
    {
        if (!taskOutcomeRuntimeExists()) {
            return false;
        }
        Integer used = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM trunk_outcome_inbox
                    WHERE trunk_id = ? AND delivery_key = ?)
                """, Integer.class, trunkId, commandId);
        return used != null && used == 1;
    }

    @Override
    public TrunkManager.TaskOutcomeSummaryState requireTaskOutcomeSummary(
            String taskOutcomeId)
    {
        return jdbc.query("""
                SELECT outcome.trunk_id, outcome.summary_state,
                       (SELECT summary.task_turn_id
                        FROM task_outcome_summary_operation summary
                        WHERE summary.task_outcome_id = outcome.id
                          AND (summary.id = outcome.summary_operation_id
                            OR (outcome.summary_state = 'FALLBACK'
                              AND summary.status = 'SUCCEEDED'))
                        ORDER BY summary.semantic_attempt DESC LIMIT 1)
                          AS summary_task_turn_id,
                       outcome.summary_operation_id, outcome.summary_text,
                       outcome.summary_digest, outcome.summary_updated_at_ms
                FROM task_outcome outcome
                JOIN trunk_outcome_inbox inbox
                  ON inbox.task_outcome_id = outcome.id
                JOIN threads trunk ON trunk.id = outcome.trunk_id
                WHERE outcome.id = ?
                  AND inbox.trunk_id = outcome.trunk_id
                  AND inbox.status = 'DELIVERED'
                  AND trunk.turn_version = 'V2'
                """, (rs, row) -> new TrunkManager.TaskOutcomeSummaryState(
                        taskOutcomeId, rs.getString("trunk_id"),
                        rs.getString("summary_state"),
                        rs.getString("summary_task_turn_id"),
                        rs.getString("summary_operation_id"),
                        rs.getString("summary_text"),
                        rs.getString("summary_digest"),
                        rs.getObject("summary_updated_at_ms") == null ? null
                                : Instant.ofEpochMilli(
                                rs.getLong("summary_updated_at_ms"))),
                taskOutcomeId).stream().findFirst().orElseThrow(() ->
                new IllegalStateException(
                        "Delivered TaskOutcome is missing: " + taskOutcomeId));
    }

    @Override
    public TrunkManager.TaskOutcomeSummaryState enrichTaskOutcomeSummary(
            TrunkManager.TaskOutcomeSummaryFact fact,
            TrunkManager.TaskOutcomeSummaryState current)
    {
        requireTransaction();
        if (!fact.taskOutcomeId().equals(current.taskOutcomeId())
                || !fact.turnId().equals(current.summaryTaskTurnId())
                || !"FALLBACK".equals(current.summaryState())) {
            throw new IllegalArgumentException(
                    "TaskOutcome summary result fence is inconsistent");
        }
        int changed = jdbc.update("""
                UPDATE task_outcome
                SET summary_state = 'BRAIN_GENERATED', summary_text = ?,
                    summary_digest = ?, summary_operation_id = ?,
                    summary_updated_at_ms = ?
                WHERE id = ? AND trunk_id = ?
                  AND summary_state = 'FALLBACK'
                  AND EXISTS (
                    SELECT 1 FROM task_outcome_summary_operation summary
                    WHERE summary.task_outcome_id = task_outcome.id
                      AND summary.task_turn_id = ?
                      AND summary.id = ?
                      AND summary.status = 'SUCCEEDED')
                """, fact.summaryText(), fact.summaryDigest(),
                fact.operationId(), fact.finishedAt().toEpochMilli(),
                fact.taskOutcomeId(), current.trunkId(), fact.turnId(),
                fact.operationId());
        if (changed != 1) {
            throw concurrent(
                    "TaskOutcome changed before summary enrichment");
        }
        return new TrunkManager.TaskOutcomeSummaryState(
                current.taskOutcomeId(), current.trunkId(),
                "BRAIN_GENERATED", fact.turnId(), fact.operationId(),
                fact.summaryText(), fact.summaryDigest(), fact.finishedAt());
    }

    private boolean taskOutcomeRuntimeExists()
    {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM pragma_table_info('trunk_outcome_inbox')
                WHERE name = 'fallback_summary_text'
                """, Integer.class);
        return count != null && count == 1;
    }

    @Override
    public Optional<TrunkManager.ThreadTurnRequestReceipt> findThreadTurnRequest(
            String trunkId, String commandId)
    {
        return jdbc.query("""
                SELECT actor, expected_trunk_version, returned_trunk_version,
                       returned_lifecycle, turn_id, operation_id,
                       dispatch_ticket_id, purpose, delivery_lane,
                       launch_input_digest, user_message_digest, recorded_at_ms
                FROM trunk_thread_turn_request_receipt
                WHERE trunk_id = ? AND command_id = ?
                """,
                (rs, row) -> new TrunkManager.ThreadTurnRequestReceipt(
                        new TrunkManager.State(
                                trunkId,
                                TrunkLifecycle.valueOf(
                                        rs.getString("returned_lifecycle")),
                                rs.getLong("returned_trunk_version")),
                        commandId, rs.getString("actor"),
                        rs.getLong("expected_trunk_version"),
                        rs.getString("turn_id"),
                        rs.getString("operation_id"),
                        rs.getString("dispatch_ticket_id"),
                        rs.getString("purpose"),
                        rs.getString("delivery_lane"),
                        rs.getString("launch_input_digest"),
                        rs.getString("user_message_digest"),
                        Instant.ofEpochMilli(
                                rs.getLong("recorded_at_ms"))),
                trunkId, commandId).stream().findFirst();
    }

    @Override
    public boolean matchesThreadTurnRequest(
            TrunkManager.ThreadTurnCommand command)
    {
        String sql = planningRuntimeExists() ? """
                SELECT receipt.actor, receipt.expected_trunk_version,
                       receipt.returned_trunk_version,
                       receipt.turn_id, receipt.operation_id,
                       receipt.dispatch_ticket_id, receipt.purpose,
                       receipt.delivery_lane, receipt.launch_input_digest,
                       receipt.user_message_digest, receipt.recorded_at_ms,
                       turn.launch_input, turn.planning_operation_id,
                       turn.expected_base_sha,
                       message.id AS user_message_id,
                       message.body AS user_message,
                       ticket.workspace_id, ticket.lane_mask
                FROM trunk_thread_turn_request_receipt receipt
                JOIN thread_turn turn ON turn.id = receipt.turn_id
                JOIN thread_message message
                  ON message.turn_id = turn.id AND message.seq = 1
                JOIN dispatch_ticket ticket
                  ON ticket.id = receipt.dispatch_ticket_id
                WHERE receipt.trunk_id = ? AND receipt.command_id = ?
                """ : """
                SELECT receipt.actor, receipt.expected_trunk_version,
                       receipt.returned_trunk_version,
                       receipt.turn_id, receipt.operation_id,
                       receipt.dispatch_ticket_id, receipt.purpose,
                       receipt.delivery_lane, receipt.launch_input_digest,
                       receipt.user_message_digest, receipt.recorded_at_ms,
                       turn.launch_input, NULL AS planning_operation_id,
                       NULL AS expected_base_sha,
                       message.id AS user_message_id,
                       message.body AS user_message,
                       ticket.workspace_id, ticket.lane_mask
                FROM trunk_thread_turn_request_receipt receipt
                JOIN thread_turn turn ON turn.id = receipt.turn_id
                JOIN thread_message message
                  ON message.turn_id = turn.id AND message.seq = 1
                JOIN dispatch_ticket ticket
                  ON ticket.id = receipt.dispatch_ticket_id
                WHERE receipt.trunk_id = ? AND receipt.command_id = ?
                """;
        List<Map<String, Object>> rows = jdbc.queryForList(
                sql, command.trunkId(), command.commandId());
        if (rows.size() != 1) {
            return false;
        }
        Map<String, Object> row = rows.getFirst();
        return same(row, "actor", command.actor())
                && number(row, "expected_trunk_version")
                        == command.expectedTrunkVersion()
                && number(row, "returned_trunk_version")
                        == command.expectedTrunkVersion() + 1
                && same(row, "turn_id", command.turnId())
                && same(row, "operation_id", command.operationId())
                && same(row, "dispatch_ticket_id", command.ticketId())
                && same(row, "purpose", command.purpose())
                && same(row, "delivery_lane", command.deliveryLane())
                && same(row, "launch_input_digest", command.launchInputDigest())
                && same(row, "user_message_digest", command.userMessageDigest())
                && same(row, "launch_input", command.launchInput())
                && same(row, "planning_operation_id", command.planningOperationId())
                && same(row, "expected_base_sha", command.expectedBaseSha())
                && same(row, "user_message_id", command.userMessageId())
                && same(row, "user_message", command.userMessage())
                && same(row, "workspace_id", command.workspaceId())
                && number(row, "lane_mask") == command.laneMask();
    }

    @Override
    public TrunkManager.ThreadTurnRequestReceipt requestThreadTurn(
            TrunkManager.ThreadTurnCommand command,
            TrunkManager.State expected,
            TrunkManager.State updated)
    {
        requireTransaction();
        if (!expected.id().equals(command.trunkId())
                || !expected.id().equals(updated.id())
                || expected.version() != command.expectedTrunkVersion()
                || updated.version() != expected.version() + 1
                || updated.lifecycle() != TrunkLifecycle.ACTIVE) {
            throw new IllegalArgumentException(
                    "ThreadTurn request Trunk fence is inconsistent");
        }
        long now = command.requestedAt().toEpochMilli();
        if (planningRuntimeExists()) {
            jdbc.update("""
                    INSERT INTO thread_turn(
                        id, trunk_id, purpose, status, operation_id, attempt,
                        delivery_lane, launch_input, requested_at_ms,
                        planning_operation_id, expected_base_sha)
                    VALUES (?, ?, ?, 'REQUESTED', ?, 1, ?, ?, ?, ?, ?)
                    """,
                    command.turnId(), command.trunkId(), command.purpose(),
                    command.operationId(), command.deliveryLane(),
                    command.launchInput(), now, command.planningOperationId(),
                    command.expectedBaseSha());
        }
        else {
            if (command.planningOperationId() != null) {
                throw new IllegalStateException(
                        "Planning-base runtime schema is unavailable");
            }
            jdbc.update("""
                    INSERT INTO thread_turn(
                        id, trunk_id, purpose, status, operation_id, attempt,
                        delivery_lane, launch_input, requested_at_ms)
                    VALUES (?, ?, ?, 'REQUESTED', ?, 1, ?, ?, ?)
                    """,
                    command.turnId(), command.trunkId(), command.purpose(),
                    command.operationId(), command.deliveryLane(),
                    command.launchInput(), now);
        }
        jdbc.update("""
                INSERT INTO thread_message(
                    id, turn_id, seq, role, body, created_at_ms)
                VALUES (?, ?, 1, 'user', ?, ?)
                """,
                command.userMessageId(), command.turnId(),
                command.userMessage(), now);
        jdbc.update("""
                INSERT INTO dispatch_ticket(
                    id, operation_id, operation_kind, async_family,
                    owner_kind, owner_id, callback_route, lane_mask,
                    trunk_control, exclusive_task, writer_required,
                    workspace_id, trunk_id, task_id, task_epoch,
                    stage_id, stage_generation, attempt, expected_base_sha, status,
                    next_attempt_at_ms, created_at_ms)
                VALUES (?, ?, 'EXECUTE_THREAD_TURN', 'AGENT_TURN',
                    'THREAD_TURN', ?, 'THREAD_TURN_RESULT', ?,
                    1, 0, 0, ?, ?, NULL, NULL, NULL, NULL, 1, ?,
                    'REQUESTED', ?, ?)
                """,
                command.ticketId(), command.operationId(), command.turnId(),
                command.laneMask(), command.workspaceId(), command.trunkId(),
                command.expectedBaseSha(), now, now);

        updateTrunk(expected, updated, "ThreadTurn request");
        jdbc.update("""
                INSERT INTO trunk_transition(
                    id, trunk_id, command_id, from_state, to_state,
                    aggregate_version, cause, actor, occurred_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, 'REQUEST_THREAD_TURN', ?, ?)
                """,
                id(), command.trunkId(), command.commandId(),
                expected.lifecycle().name(), updated.lifecycle().name(),
                updated.version(), command.actor(), now);
        jdbc.update("""
                INSERT INTO trunk_thread_turn_request_receipt(
                    id, trunk_id, command_id, actor,
                    expected_trunk_version, returned_trunk_version,
                    returned_lifecycle, turn_id, operation_id,
                    dispatch_ticket_id, purpose, delivery_lane,
                    launch_input_digest, user_message_digest, recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id(), command.trunkId(), command.commandId(), command.actor(),
                expected.version(), updated.version(), updated.lifecycle().name(),
                command.turnId(), command.operationId(), command.ticketId(),
                command.purpose(), command.deliveryLane(),
                command.launchInputDigest(), command.userMessageDigest(), now);
        return new TrunkManager.ThreadTurnRequestReceipt(
                updated, command.commandId(), command.actor(), expected.version(),
                command.turnId(), command.operationId(), command.ticketId(),
                command.purpose(), command.deliveryLane(),
                command.launchInputDigest(), command.userMessageDigest(),
                command.requestedAt());
    }

    @Override
    public Optional<String> findThreadTurnTrunk(
            String turnId, String operationId)
    {
        return jdbc.query("""
                SELECT turn.trunk_id
                FROM thread_turn turn
                JOIN threads trunk ON trunk.id = turn.trunk_id
                WHERE turn.id = ? AND turn.operation_id = ?
                  AND trunk.turn_version = 'V2'
                """, (rs, row) -> rs.getString("trunk_id"),
                turnId, operationId).stream().findFirst();
    }

    @Override
    public Optional<TrunkManager.ThreadTurnResultReceipt> findThreadTurnResult(
            String turnId, String operationId)
    {
        return jdbc.query("""
                SELECT receipt.trunk_id, receipt.command_id, receipt.actor,
                       receipt.attempt, receipt.raw_result_digest,
                       receipt.raw_outcome, receipt.acceptance,
                       receipt.terminal_status, receipt.assistant_message_id,
                       receipt.returned_trunk_version,
                       receipt.returned_lifecycle, receipt.recorded_at_ms
                FROM trunk_thread_turn_result_receipt receipt
                WHERE receipt.turn_id = ? AND receipt.operation_id = ?
                """,
                (rs, row) -> new TrunkManager.ThreadTurnResultReceipt(
                        new TrunkManager.State(
                                rs.getString("trunk_id"),
                                TrunkLifecycle.valueOf(
                                        rs.getString("returned_lifecycle")),
                                rs.getLong("returned_trunk_version")),
                        rs.getString("command_id"), rs.getString("actor"),
                        turnId, operationId, rs.getInt("attempt"),
                        rs.getString("raw_result_digest"),
                        rs.getString("raw_outcome"),
                        rs.getString("acceptance"),
                        rs.getString("terminal_status"),
                        rs.getString("assistant_message_id"),
                        Instant.ofEpochMilli(
                                rs.getLong("recorded_at_ms"))),
                turnId, operationId).stream().findFirst();
    }

    @Override
    public boolean isThreadTurnCommandUsed(String trunkId, String commandId)
    {
        if (!threadTurnRuntimeExists()) {
            return false;
        }
        Integer used = jdbc.queryForObject("""
                SELECT EXISTS (
                    SELECT 1 FROM trunk_thread_turn_request_receipt
                    WHERE trunk_id = ? AND command_id = ?
                    UNION ALL
                    SELECT 1 FROM trunk_thread_turn_result_receipt
                    WHERE trunk_id = ? AND command_id = ?)
                """, Integer.class,
                trunkId, commandId, trunkId, commandId);
        return used != null && used == 1;
    }

    private boolean threadTurnRuntimeExists()
    {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sqlite_master
                WHERE type = 'table'
                  AND name = 'trunk_thread_turn_request_receipt'
                """, Integer.class);
        return count != null && count == 1;
    }

    @Override
    public TrunkManager.ThreadTurnResultContext requireThreadTurnResult(
            String turnId, String operationId)
    {
        String planningWork = planningRuntimeExists() ? """
                       OR EXISTS (
                           SELECT 1
                           FROM planning_base_refresh_operation planning
                           WHERE planning.trunk_id = turn.trunk_id
                             AND (planning.status = 'REQUESTED'
                               OR (planning.status = 'SUCCEEDED'
                                 AND planning.launch_disposition = 'PENDING')))
                """ : "";
        return jdbc.query("""
                SELECT trunk.id AS trunk_id, trunk.lifecycle_state,
                       trunk.aggregate_version, turn.status, turn.operation_id,
                       turn.attempt,
                       (EXISTS (
                           SELECT 1 FROM thread_turn other
                           WHERE other.trunk_id = turn.trunk_id
                             AND other.id <> turn.id
                             AND other.status IN (
                                 'REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING'))
                       %s) AS other_live_work
                FROM thread_turn turn
                JOIN threads trunk ON trunk.id = turn.trunk_id
                WHERE turn.id = ? AND turn.operation_id = ?
                  AND trunk.turn_version = 'V2'
                """.formatted(planningWork),
                (rs, row) -> new TrunkManager.ThreadTurnResultContext(
                        new TrunkManager.State(
                                rs.getString("trunk_id"),
                                TrunkLifecycle.valueOf(
                                        rs.getString("lifecycle_state")),
                                rs.getLong("aggregate_version")),
                        rs.getString("status"),
                        rs.getString("operation_id"),
                        rs.getInt("attempt"),
                        rs.getBoolean("other_live_work")),
                turnId, operationId).stream().findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "ThreadTurn delivery owner disappeared: " + turnId));
    }

    @Override
    public TrunkManager.ThreadTurnResultReceipt acceptThreadTurnResult(
            TrunkManager.ThreadTurnResultFact fact,
            TrunkManager.ThreadTurnResultContext context,
            TrunkManager.State updated,
            String acceptance,
            String terminalStatus)
    {
        requireTransaction();
        TrunkManager.State expected = context.trunkState();
        if (!expected.id().equals(updated.id())
                || updated.version() != expected.version() + 1
                || !context.operationId().equals(fact.operationId())
                || context.attempt() != fact.attempt()) {
            throw new IllegalArgumentException(
                    "ThreadTurn result Trunk fence is inconsistent");
        }
        int turnChanged = jdbc.update("""
                UPDATE thread_turn
                SET status = ?, finished_at_ms = ?, error_message = ?
                WHERE id = ? AND operation_id = ? AND attempt = ?
                  AND status = ?
                """,
                terminalStatus, fact.finishedAt().toEpochMilli(), fact.error(),
                fact.turnId(), fact.operationId(), fact.attempt(),
                context.turnStatus());
        if (turnChanged != 1) {
            throw concurrent(
                    "ThreadTurn changed before result acceptance: " + fact.turnId());
        }

        String assistantMessageId = null;
        if ("ACCEPTED".equals(acceptance)
                && "SUCCEEDED".equals(terminalStatus)
                && fact.finalText() != null) {
            assistantMessageId = id();
            jdbc.update("""
                    INSERT INTO thread_message(
                        id, turn_id, seq, role, body, created_at_ms)
                    VALUES (?, ?, 2, 'assistant', ?, ?)
                    """,
                    assistantMessageId, fact.turnId(), fact.finalText(),
                    fact.finishedAt().toEpochMilli());
        }

        updateTrunk(expected, updated, "ThreadTurn result");
        jdbc.update("""
                INSERT INTO trunk_transition(
                    id, trunk_id, command_id, from_state, to_state,
                    aggregate_version, cause, actor, occurred_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, 'ACCEPT_THREAD_TURN_RESULT', ?, ?)
                """,
                id(), expected.id(), fact.commandId(),
                expected.lifecycle().name(), updated.lifecycle().name(),
                updated.version(), fact.actor(), fact.finishedAt().toEpochMilli());
        jdbc.update("""
                INSERT INTO trunk_thread_turn_result_receipt(
                    id, trunk_id, command_id, actor, turn_id, operation_id,
                    attempt, raw_result_digest, raw_outcome, acceptance,
                    terminal_status, assistant_message_id,
                    returned_trunk_version, returned_lifecycle, recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id(), expected.id(), fact.commandId(), fact.actor(),
                fact.turnId(), fact.operationId(), fact.attempt(),
                fact.rawResultDigest(), fact.rawOutcome(), acceptance,
                terminalStatus, assistantMessageId, updated.version(),
                updated.lifecycle().name(), fact.finishedAt().toEpochMilli());
        return new TrunkManager.ThreadTurnResultReceipt(
                updated, fact.commandId(), fact.actor(), fact.turnId(),
                fact.operationId(), fact.attempt(), fact.rawResultDigest(),
                fact.rawOutcome(), acceptance, terminalStatus,
                assistantMessageId, fact.finishedAt());
    }

    @Override
    public boolean matchesTaskCreationAuthorization(
            TrunkManager.TaskCreationCommand command)
    {
        TaskCreationInput input = command.input();
        TaskAssignment assignment = input.assignment();
        List<Map<String, Object>> rows = jdbc.queryForList("""
                SELECT authorization.workspace_id AS workspace_id,
                       authorization.actor AS actor,
                       authorization.expected_trunk_version AS expected_version,
                       authorization.returned_trunk_version AS returned_version,
                       authorization.returned_lifecycle AS returned_lifecycle,
                       authorization.provenance AS provenance,
                       authorization.repository_id AS repository_id,
                       authorization.upstream_repository_id AS upstream_repository_id,
                       authorization.publish_repository_id AS publish_repository_id,
                       authorization.base_source AS base_source,
                       authorization.base_repository_id AS base_repository_id,
                       authorization.base_ref AS base_ref,
                       authorization.planning_base_sha AS authorized_planning_base_sha,
                       authorization.assignment_base_sha AS assignment_base_sha,
                       authorization.assignment_head_sha AS assignment_head_sha,
                       authorization.engine_snapshot AS engine_snapshot,
                       authorization.work_model_snapshot AS work_model_snapshot,
                       authorization.task_name AS task_name,
                       authorization.task_type AS task_type,
                       authorization.linked_issue_number AS linked_issue_number,
                       authorization.opening_prompt AS opening_prompt,
                       authorization.task_origin AS task_origin,
                       authorization.recorded_at_ms AS authorization_recorded_at_ms,
                       assignment.kind AS assignment_kind,
                       assignment.source_id AS source_id,
                       assignment.repository_id AS assignment_repository_id,
                       assignment.pr_number AS pr_number,
                       assignment.remote_head_sha AS remote_head_sha,
                       assignment.planning_base_sha AS assignment_planning_base_sha,
                       assignment.plan_seed AS plan_seed,
                       assignment.prompt AS prompt,
                       assignment.producer AS producer,
                       assignment.reason AS reason,
                       assignment.selected_findings_json AS selected_findings_json,
                       assignment.created_by AS assignment_created_by,
                       assignment.created_at_ms AS assignment_created_at_ms,
                       assignment.base_repository_id AS assignment_base_repository_id,
                       assignment.head_repository_id AS assignment_head_repository_id,
                       assignment.base_ref AS assignment_base_ref,
                       assignment.head_ref AS assignment_head_ref,
                       assignment.remote_base_sha AS remote_base_sha,
                       assignment.repository_route AS repository_route,
                       policy.revision AS policy_revision,
                       policy.source AS policy_source,
                       policy.auto_approve AS auto_approve,
                       policy.auto_merge AS auto_merge,
                       policy.min_approvals AS min_approvals,
                       policy.max_brain_rounds AS max_brain_rounds,
                       policy.max_ci_fix_pushes AS max_ci_fix_pushes,
                       policy.require_remote_branch_cleanup
                           AS require_remote_branch_cleanup,
                       policy.permission_policy_ref AS permission_policy_ref,
                       policy.created_by AS policy_created_by,
                       policy.created_at_ms AS policy_created_at_ms
                FROM trunk_task_creation_authorization authorization
                JOIN task_assignment assignment
                  ON assignment.id = authorization.assignment_id
                JOIN task_policy_revision policy
                  ON policy.id = authorization.policy_revision_id
                WHERE authorization.trunk_id = ?
                  AND authorization.command_id = ?
                  AND authorization.assignment_id = ?
                  AND authorization.id = ?
                  AND authorization.policy_revision_id = ?
                """,
                assignment.identity().trunkId(), command.commandId(),
                assignment.identity().id(),
                assignment.identity().creationAuthorizationId(), input.policy().id());
        if (rows.size() != 1 || !matchesCommon(rows.getFirst(), command)) {
            return false;
        }
        if (assignment instanceof TaskAssignment.ReviewFindings review) {
            List<TaskAssignment.ReviewFindingRef> findings = jdbc.query("""
                    SELECT source_review_id, finding_id, finding_revision,
                           content_digest
                    FROM task_assignment_review_finding
                    WHERE assignment_id = ? ORDER BY position
                    """,
                    (rs, row) -> new TaskAssignment.ReviewFindingRef(
                            rs.getString("source_review_id"),
                            rs.getString("finding_id"),
                            rs.getInt("finding_revision"),
                            rs.getString("content_digest")),
                    assignment.identity().id());
            return findings.equals(review.findings());
        }
        return jdbc.queryForObject("""
                SELECT COUNT(*) FROM task_assignment_review_finding
                WHERE assignment_id = ?
                """, Integer.class, assignment.identity().id()) == 0;
    }

    @Override
    public TrunkManager.State authorizeTaskCreation(
            TrunkManager.TaskCreationCommand command,
            TrunkManager.State expected,
            TrunkManager.State updated)
    {
        requireTransaction();
        TaskCreationInput input = command.input();
        TaskAssignment assignment = input.assignment();
        if (!expected.id().equals(assignment.identity().trunkId())
                || !expected.id().equals(updated.id())
                || expected.lifecycle() != updated.lifecycle()
                || expected.version() != command.expectedTrunkVersion()
                || updated.version() != expected.version() + 1) {
            throw new IllegalArgumentException("Task creation Trunk fence is inconsistent");
        }

        ensurePolicy(input.policy());
        insertAssignment(assignment);
        if (assignment instanceof TaskAssignment.ReviewFindings review) {
            int position = 1;
            for (TaskAssignment.ReviewFindingRef finding : review.findings()) {
                jdbc.update("""
                        INSERT INTO task_assignment_review_finding(
                            assignment_id, position, source_review_id, finding_id,
                            finding_revision, content_digest)
                        VALUES (?, ?, ?, ?, ?, ?)
                        """,
                        assignment.identity().id(), position++,
                        finding.sourceReviewId(), finding.findingId(),
                        finding.findingRevision(), finding.contentDigest());
            }
        }

        int changed = jdbc.update("""
                UPDATE threads
                SET aggregate_version = ?
                WHERE id = ? AND turn_version = 'V2'
                  AND lifecycle_state = ? AND aggregate_version = ?
                """,
                updated.version(), expected.id(), expected.lifecycle().name(),
                expected.version());
        if (changed != 1) {
            throw concurrent("Trunk changed before Task authorization: " + expected.id());
        }

        long recordedAt = input.createdAt().toEpochMilli();
        jdbc.update("""
                INSERT INTO trunk_transition(
                    id, trunk_id, command_id, from_state, to_state,
                    aggregate_version, cause, actor, occurred_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, 'AUTHORIZE_TASK_CREATION', ?, ?)
                """,
                id(), expected.id(), command.commandId(), expected.lifecycle().name(),
                updated.lifecycle().name(), updated.version(), command.actor(), recordedAt);

        TaskCreationInput.CreationBase base = input.base();
        TaskAssignment.RepositoryRouting repositories = base.repositories();
        jdbc.update("""
                INSERT INTO trunk_task_creation_authorization(
                    id, trunk_id, workspace_id, command_id, actor, disposition,
                    expected_trunk_version, returned_trunk_version,
                    returned_lifecycle, assignment_id, policy_revision_id,
                    provenance, repository_id, upstream_repository_id,
                    publish_repository_id, base_source, base_repository_id,
                    base_ref, planning_base_sha, assignment_base_sha,
                    assignment_head_sha, engine_snapshot, work_model_snapshot,
                    task_name, task_type, linked_issue_number, opening_prompt,
                    task_origin,
                    recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, 'AUTHORIZED', ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                assignment.identity().creationAuthorizationId(), expected.id(),
                input.workspaceId(), command.commandId(), command.actor(),
                expected.version(), updated.version(), updated.lifecycle().name(),
                assignment.identity().id(), input.policy().id(),
                input.provenance().name(), repositories.repositoryId(),
                repositories.upstreamRepositoryId().orElse(null),
                repositories.publishRepositoryId(), input.base().source().name(),
                repositories.baseRepositoryId(), base.baseRef(), planningBase(input),
                assignmentBase(input), assignmentHead(input),
                input.engine().canonicalValue(),
                input.workModel().value(), input.presentation().name(),
                input.presentation().taskType(),
                input.presentation().linkedIssueNumber(),
                input.presentation().openingPrompt(),
                input.presentation().origin(), recordedAt);
        return updated;
    }

    @Override
    public TrunkManager.State commit(
            String commandId,
            String cause,
            String actor,
            long expectedVersion,
            TrunkManager.State expected,
            TrunkManager.State updated)
    {
        requireTransaction();
        if (!expected.id().equals(updated.id())
                || expected.version() != expectedVersion
                || updated.version() != expectedVersion + 1) {
            throw new IllegalArgumentException("Trunk commit fence is inconsistent");
        }
        int changed = jdbc.update("""
                UPDATE threads
                SET lifecycle_state = ?, aggregate_version = ?
                WHERE id = ?
                  AND turn_version = 'V2'
                  AND lifecycle_state = ?
                  AND aggregate_version = ?
                """,
                updated.lifecycle().name(), updated.version(), expected.id(),
                expected.lifecycle().name(), expectedVersion);
        if (changed != 1) {
            throw concurrent("Trunk changed before commit: " + expected.id());
        }

        long now = System.currentTimeMillis();
        jdbc.update("""
                INSERT INTO trunk_transition(
                    id, trunk_id, command_id, from_state, to_state,
                    aggregate_version, cause, actor, occurred_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                id(), expected.id(), commandId, expected.lifecycle().name(),
                updated.lifecycle().name(), updated.version(), cause, actor, now);
        jdbc.update("""
                INSERT INTO trunk_command_receipt(
                    id, trunk_id, command_id, cause, actor, disposition,
                    expected_version, returned_lifecycle, returned_version, recorded_at_ms)
                VALUES (?, ?, ?, ?, ?, 'APPLIED', ?, ?, ?, ?)
                """,
                id(), expected.id(), commandId, cause, actor, expectedVersion,
                updated.lifecycle().name(), updated.version(), now);
        return updated;
    }

    private void updateTrunk(
            TrunkManager.State expected,
            TrunkManager.State updated,
            String action)
    {
        int changed = jdbc.update("""
                UPDATE threads
                SET lifecycle_state = ?, aggregate_version = ?
                WHERE id = ? AND turn_version = 'V2'
                  AND lifecycle_state = ? AND aggregate_version = ?
                """,
                updated.lifecycle().name(), updated.version(), expected.id(),
                expected.lifecycle().name(), expected.version());
        if (changed != 1) {
            throw concurrent(
                    "Trunk changed before " + action + ": " + expected.id());
        }
    }

    private static void requireTransaction()
    {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Trunk writes require the command transaction");
        }
    }

    private static CommandRejectedException concurrent(String message)
    {
        return new CommandRejectedException(CONCURRENT_UPDATE, message);
    }

    private static String id()
    {
        return UUID.randomUUID().toString();
    }

    private void ensurePolicy(TaskCreationInput.TaskPolicy policy)
    {
        Integer exists = jdbc.queryForObject("""
                SELECT COUNT(*) FROM task_policy_revision WHERE id = ?
                """, Integer.class, policy.id());
        if (exists != null && exists == 1) {
            if (!policyMatches(policy)) {
                throw new DataIntegrityViolationException(
                        "Task policy id already describes another revision");
            }
            return;
        }
        jdbc.update("""
                INSERT INTO task_policy_revision(
                    id, trunk_id, revision, source, auto_approve, auto_merge,
                    min_approvals, max_brain_rounds, max_ci_fix_pushes,
                    require_remote_branch_cleanup, permission_policy_ref,
                    created_by, created_at_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                policy.id(), policy.trunkId(), policy.revision(), policy.source(),
                policy.autoApprove(), policy.autoMerge(), policy.minApprovals(),
                policy.maxBrainRounds(), policy.maxCiFixPushes(),
                policy.requireRemoteBranchCleanup(),
                policy.permissionPolicyRef().orElse(null), policy.createdBy(),
                policy.createdAt().toEpochMilli());
    }

    private boolean policyMatches(TaskCreationInput.TaskPolicy policy)
    {
        Integer matches = jdbc.queryForObject("""
                SELECT COUNT(*) FROM task_policy_revision
                WHERE id = ? AND trunk_id = ? AND revision = ? AND source = ?
                  AND auto_approve = ? AND auto_merge = ? AND min_approvals = ?
                  AND max_brain_rounds = ? AND max_ci_fix_pushes = ?
                  AND require_remote_branch_cleanup = ?
                  AND permission_policy_ref IS ? AND created_by = ?
                  AND created_at_ms = ?
                """, Integer.class,
                policy.id(), policy.trunkId(), policy.revision(), policy.source(),
                policy.autoApprove(), policy.autoMerge(), policy.minApprovals(),
                policy.maxBrainRounds(), policy.maxCiFixPushes(),
                policy.requireRemoteBranchCleanup(),
                policy.permissionPolicyRef().orElse(null), policy.createdBy(),
                policy.createdAt().toEpochMilli());
        return matches != null && matches == 1;
    }

    private void insertAssignment(TaskAssignment assignment)
    {
        TaskAssignment.PullRequestRef pullRequest = pullRequest(assignment);
        TaskAssignment.RepositoryRouting route = pullRequest == null
                ? null
                : pullRequest.repositories();
        jdbc.update("""
                INSERT INTO task_assignment(
                    id, trunk_id, kind, source_id, repository_id, pr_number,
                    remote_head_sha, planning_base_sha, plan_seed, prompt,
                    producer, reason, selected_findings_json, created_by,
                    created_at_ms, base_repository_id, head_repository_id,
                    base_ref, head_ref, remote_base_sha, repository_route,
                    creation_authorization_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?)
                """,
                assignment.identity().id(), assignment.identity().trunkId(),
                assignment.kind().name(), sourceId(assignment),
                route == null ? null : route.repositoryId(),
                pullRequest == null ? null : pullRequest.number(),
                pullRequest == null ? null : pullRequest.remoteHeadSha(),
                assignment instanceof TaskAssignment.NewFromTrunk newTask
                        && newTask.origin() instanceof TaskAssignment.AgentHandoff origin
                        ? origin.planningBaseSha()
                        : null,
                assignment instanceof TaskAssignment.NewFromTrunk newTask
                        ? newTask.planSeed()
                        : null,
                assignment instanceof TaskAssignment.NewFromTrunk newTask
                        ? newTask.prompt()
                        : null,
                assignment instanceof TaskAssignment.Automation automation
                        ? automation.producer()
                        : null,
                assignment instanceof TaskAssignment.Automation automation
                        ? automation.reason()
                        : null,
                assignment instanceof TaskAssignment.ReviewFindings ? "[]" : null,
                assignment.identity().createdBy(),
                assignment.identity().createdAt().toEpochMilli(),
                route == null ? null : route.baseRepositoryId(),
                route == null ? null : route.publishRepositoryId(),
                pullRequest == null ? null : pullRequest.baseRef(),
                pullRequest == null ? null : pullRequest.headRef(),
                pullRequest == null ? null : pullRequest.remoteBaseSha(),
                route == null ? null : route.route().name(),
                assignment.identity().creationAuthorizationId());
    }

    private static boolean matchesCommon(
            Map<String, Object> row, TrunkManager.TaskCreationCommand command)
    {
        TaskCreationInput input = command.input();
        TaskAssignment assignment = input.assignment();
        TaskCreationInput.TaskPolicy policy = input.policy();
        TaskCreationInput.CreationBase base = input.base();
        TaskAssignment.RepositoryRouting repositories = base.repositories();
        TaskAssignment.PullRequestRef pullRequest = pullRequest(assignment);
        return same(row, "workspace_id", input.workspaceId())
                && same(row, "actor", command.actor())
                && number(row, "expected_version") == command.expectedTrunkVersion()
                && number(row, "returned_version") == command.expectedTrunkVersion() + 1
                && same(row, "provenance", input.provenance().name())
                && same(row, "repository_id", repositories.repositoryId())
                && same(row, "upstream_repository_id",
                        repositories.upstreamRepositoryId().orElse(null))
                && same(row, "publish_repository_id",
                        repositories.publishRepositoryId())
                && same(row, "base_source", base.source().name())
                && same(row, "base_repository_id", repositories.baseRepositoryId())
                && same(row, "base_ref", base.baseRef())
                && same(row, "authorized_planning_base_sha", planningBase(input))
                && same(row, "assignment_base_sha", assignmentBase(input))
                && same(row, "assignment_head_sha", assignmentHead(input))
                && same(row, "engine_snapshot", input.engine().canonicalValue())
                && same(row, "work_model_snapshot", input.workModel().value())
                && same(row, "task_name", input.presentation().name())
                && same(row, "task_type", input.presentation().taskType())
                && Objects.equals(
                        nullableNumber(row, "linked_issue_number"),
                        input.presentation().linkedIssueNumber() == null
                                ? null
                                : input.presentation().linkedIssueNumber().longValue())
                && same(row, "opening_prompt", input.presentation().openingPrompt())
                && same(row, "task_origin", input.presentation().origin())
                && number(row, "authorization_recorded_at_ms")
                        == input.createdAt().toEpochMilli()
                && same(row, "assignment_kind", assignment.kind().name())
                && same(row, "source_id", sourceId(assignment))
                && same(row, "assignment_repository_id",
                        pullRequest == null ? null
                                : pullRequest.repositories().repositoryId())
                && Objects.equals(
                        nullableNumber(row, "pr_number"),
                        pullRequest == null ? null : (long) pullRequest.number())
                && same(row, "remote_head_sha",
                        pullRequest == null ? null : pullRequest.remoteHeadSha())
                && same(row, "assignment_planning_base_sha",
                        assignment instanceof TaskAssignment.NewFromTrunk newTask
                                && newTask.origin()
                                        instanceof TaskAssignment.AgentHandoff origin
                                ? origin.planningBaseSha()
                                : null)
                && same(row, "plan_seed",
                        assignment instanceof TaskAssignment.NewFromTrunk newTask
                                ? newTask.planSeed()
                                : null)
                && same(row, "prompt",
                        assignment instanceof TaskAssignment.NewFromTrunk newTask
                                ? newTask.prompt()
                                : null)
                && same(row, "producer",
                        assignment instanceof TaskAssignment.Automation automation
                                ? automation.producer()
                                : null)
                && same(row, "reason",
                        assignment instanceof TaskAssignment.Automation automation
                                ? automation.reason()
                                : null)
                && same(row, "selected_findings_json",
                        assignment instanceof TaskAssignment.ReviewFindings ? "[]" : null)
                && same(row, "assignment_created_by", assignment.identity().createdBy())
                && number(row, "assignment_created_at_ms")
                        == assignment.identity().createdAt().toEpochMilli()
                && same(row, "assignment_base_repository_id",
                        pullRequest == null ? null
                                : pullRequest.repositories().baseRepositoryId())
                && same(row, "assignment_head_repository_id",
                        pullRequest == null ? null
                                : pullRequest.repositories().publishRepositoryId())
                && same(row, "assignment_base_ref",
                        pullRequest == null ? null : pullRequest.baseRef())
                && same(row, "assignment_head_ref",
                        pullRequest == null ? null : pullRequest.headRef())
                && same(row, "remote_base_sha",
                        pullRequest == null ? null : pullRequest.remoteBaseSha())
                && same(row, "repository_route",
                        pullRequest == null ? null
                                : pullRequest.repositories().route().name())
                && number(row, "policy_revision") == policy.revision()
                && same(row, "policy_source", policy.source())
                && number(row, "auto_approve") == (policy.autoApprove() ? 1 : 0)
                && number(row, "auto_merge") == (policy.autoMerge() ? 1 : 0)
                && number(row, "min_approvals") == policy.minApprovals()
                && number(row, "max_brain_rounds") == policy.maxBrainRounds()
                && number(row, "max_ci_fix_pushes") == policy.maxCiFixPushes()
                && number(row, "require_remote_branch_cleanup")
                        == (policy.requireRemoteBranchCleanup() ? 1 : 0)
                && same(row, "permission_policy_ref",
                        policy.permissionPolicyRef().orElse(null))
                && same(row, "policy_created_by", policy.createdBy())
                && number(row, "policy_created_at_ms")
                        == policy.createdAt().toEpochMilli();
    }

    private static String sourceId(TaskAssignment assignment)
    {
        return switch (assignment) {
            case TaskAssignment.ReviewFindings review -> review.sourceReviewId();
            case TaskAssignment.Issue issue -> issue.issueIdentity();
            case TaskAssignment.QualityScan quality -> quality.evidenceIdentity();
            default -> null;
        };
    }

    private static TaskAssignment.PullRequestRef pullRequest(TaskAssignment assignment)
    {
        return switch (assignment) {
            case TaskAssignment.ExistingOwnPr existing -> existing.pullRequest();
            case TaskAssignment.ReviewFindings review -> review.pullRequest();
            default -> null;
        };
    }

    private static String planningBase(TaskCreationInput input)
    {
        return input.base() instanceof TaskCreationInput.PlanningSnapshot planning
                ? planning.baseSha()
                : null;
    }

    private static String assignmentBase(TaskCreationInput input)
    {
        return input.base() instanceof TaskCreationInput.ExistingPrHead existing
                ? existing.pullRequest().remoteBaseSha()
                : null;
    }

    private static String assignmentHead(TaskCreationInput input)
    {
        return input.base() instanceof TaskCreationInput.ExistingPrHead existing
                ? existing.pullRequest().remoteHeadSha()
                : null;
    }

    private static boolean same(Map<String, Object> row, String name, Object expected)
    {
        return Objects.equals(row.get(name), expected);
    }

    private static long number(Map<String, Object> row, String name)
    {
        return ((Number) row.get(name)).longValue();
    }

    private static Long nullableNumber(Map<String, Object> row, String name)
    {
        Object value = row.get(name);
        return value == null ? null : ((Number) value).longValue();
    }
}
