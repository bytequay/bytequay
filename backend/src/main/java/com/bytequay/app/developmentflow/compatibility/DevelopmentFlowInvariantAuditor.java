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
package com.bytequay.app.developmentflow.compatibility;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

import static java.util.Objects.requireNonNull;

/** Read-only V2 invariants and historical-shape checks; never a command owner. */
@Component
public final class DevelopmentFlowInvariantAuditor
{
    private final JdbcTemplate jdbc;

    public DevelopmentFlowInvariantAuditor(JdbcTemplate jdbc)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
    }

    public Audit audit()
    {
        List<Finding> findings = new ArrayList<>();
        add(findings, "V2_ACTIVE_WITHOUT_OPEN_STAGE", "TASK", """
                SELECT COUNT(*) FROM tasks task
                LEFT JOIN task_current_stage current ON current.task_id = task.id
                LEFT JOIN stage owner ON owner.id = current.stage_id
                WHERE task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'ACTIVE'
                  AND (current.task_id IS NULL OR owner.completed_at_ms IS NOT NULL)
                """, "An ACTIVE V2 Task must own one open current Stage");
        add(findings, "V2_CURRENT_STAGE_FENCE_MISMATCH", "TASK", """
                SELECT COUNT(*) FROM task_current_stage current
                JOIN tasks task ON task.id = current.task_id
                LEFT JOIN stage owner
                  ON owner.id = current.stage_id
                 AND owner.task_id = current.task_id
                 AND owner.generation = current.stage_generation
                WHERE task.workflow_version = 'V2'
                  AND (owner.id IS NULL OR owner.completed_at_ms IS NOT NULL)
                """, "The Task current-stage pointer must match one open Stage generation");
        add(findings, "V2_LIVE_TICKET_STALE_TASK_EPOCH", "DISPATCH", """
                SELECT COUNT(*) FROM dispatch_ticket ticket
                JOIN tasks task ON task.id = ticket.task_id
                WHERE task.workflow_version = 'V2'
                  AND ticket.status IN ('REQUESTED','RETRY_WAIT','RECONCILE_WAIT',
                      'RESULT_PENDING','CLAIMED','RUNNING','DELIVERING')
                  AND ticket.task_epoch <> task.epoch
                """, "Live DispatchTickets must carry the current immutable Task epoch");
        add(findings, "V2_LIVE_STAGE_TICKET_STALE_OWNER", "DISPATCH", """
                SELECT COUNT(*) FROM dispatch_ticket ticket
                JOIN tasks task ON task.id = ticket.task_id
                LEFT JOIN stage owner ON owner.id = ticket.stage_id
                WHERE task.workflow_version = 'V2'
                  AND ticket.owner_kind IN ('STAGE','STAGE_TURN')
                  AND ticket.status IN ('REQUESTED','RETRY_WAIT','RECONCILE_WAIT',
                      'RESULT_PENDING','CLAIMED','RUNNING','DELIVERING')
                  AND (owner.id IS NULL OR owner.task_id <> ticket.task_id
                    OR owner.generation <> ticket.stage_generation
                    OR owner.completed_at_ms IS NOT NULL)
                """, "A live Stage-owned ticket must match its exact open owner generation");
        add(findings, "V2_LIVE_TURN_STALE_OWNER", "TURN", """
                SELECT
                    (SELECT COUNT(*) FROM task_turn turn
                     JOIN tasks task ON task.id = turn.task_id
                     WHERE task.workflow_version = 'V2'
                       AND turn.status IN ('REQUESTED','QUEUED','CLAIMED','RUNNING')
                       AND (turn.task_epoch <> task.epoch
                         OR task.lifecycle_state IN ('COMPLETED','CANCELED','REMOTE_CLOSED')
                         OR (turn.trigger_stage_id IS NOT NULL AND NOT EXISTS (
                             SELECT 1 FROM stage owner
                             JOIN task_current_stage current ON current.stage_id = owner.id
                             WHERE owner.id = turn.trigger_stage_id
                               AND owner.task_id = turn.task_id
                               AND owner.generation = turn.trigger_stage_generation
                               AND owner.completed_at_ms IS NULL
                               AND current.task_id = turn.task_id
                               AND current.stage_generation = owner.generation))))
                  + (SELECT COUNT(*) FROM stage_turn turn
                     JOIN stage owner ON owner.id = turn.stage_id
                     JOIN tasks task ON task.id = owner.task_id
                     LEFT JOIN task_current_stage current
                       ON current.task_id = task.id AND current.stage_id = owner.id
                     WHERE task.workflow_version = 'V2'
                       AND turn.status IN ('REQUESTED','QUEUED','CLAIMED','RUNNING')
                       AND (turn.task_epoch <> task.epoch
                         OR turn.stage_generation <> owner.generation
                         OR owner.completed_at_ms IS NOT NULL
                         OR current.stage_id IS NULL
                         OR current.stage_generation <> owner.generation
                         OR task.lifecycle_state IN ('COMPLETED','CANCELED','REMOTE_CLOSED')))
                """, "Every live typed Turn must retain its exact Task epoch and current owner");
        add(findings, "V2_LIVE_TURN_WITHOUT_TICKET", "TURN", """
                SELECT
                    (SELECT COUNT(*) FROM task_turn turn
                     JOIN tasks task ON task.id = turn.task_id
                     WHERE task.workflow_version = 'V2'
                       AND turn.status IN ('REQUESTED','QUEUED','CLAIMED','RUNNING')
                       AND NOT EXISTS (
                           SELECT 1 FROM dispatch_ticket ticket
                           WHERE ticket.owner_kind = 'TASK_TURN'
                             AND ticket.owner_id = turn.id
                             AND ticket.operation_id = turn.operation_id
                             AND ticket.task_id = turn.task_id
                             AND ticket.task_epoch = turn.task_epoch
                             AND ticket.status NOT IN ('SUCCEEDED','FAILED','CANCELED')))
                  + (SELECT COUNT(*) FROM stage_turn turn
                     JOIN stage owner ON owner.id = turn.stage_id
                     JOIN tasks task ON task.id = owner.task_id
                     WHERE task.workflow_version = 'V2'
                       AND turn.status IN ('REQUESTED','QUEUED','CLAIMED','RUNNING')
                       AND NOT EXISTS (
                           SELECT 1 FROM dispatch_ticket ticket
                           WHERE ticket.owner_kind = 'STAGE_TURN'
                             AND ticket.owner_id = turn.id
                             AND ticket.operation_id = turn.operation_id
                             AND ticket.stage_id = turn.stage_id
                             AND ticket.stage_generation = turn.stage_generation
                             AND ticket.task_epoch = turn.task_epoch
                             AND ticket.status NOT IN ('SUCCEEDED','FAILED','CANCELED')))
                  + (SELECT COUNT(*) FROM thread_turn turn
                     JOIN threads trunk ON trunk.id = turn.trunk_id
                     WHERE trunk.turn_version = 'V2'
                       AND turn.status IN ('REQUESTED','QUEUED','CLAIMED','RUNNING')
                       AND NOT EXISTS (
                           SELECT 1 FROM dispatch_ticket ticket
                           WHERE ticket.owner_kind = 'THREAD_TURN'
                             AND ticket.owner_id = turn.id
                             AND ticket.operation_id = turn.operation_id
                             AND ticket.trunk_id = turn.trunk_id
                             AND ticket.status NOT IN ('SUCCEEDED','FAILED','CANCELED')))
                  + (SELECT COUNT(*) FROM review_assignment_turn turn
                     WHERE turn.status IN ('REQUESTED','QUEUED','CLAIMED','RUNNING')
                       AND NOT EXISTS (
                           SELECT 1 FROM dispatch_ticket ticket
                           WHERE ticket.owner_kind = 'REVIEW_ASSIGNMENT_TURN'
                             AND ticket.owner_id = turn.id
                             AND ticket.operation_id = turn.operation_id
                             AND ticket.status NOT IN ('SUCCEEDED','FAILED','CANCELED')))
                """, "A live typed Turn must have one live DispatchTicket for the same operation");
        add(findings, "V2_LIVE_TICKET_WITHOUT_TURN", "DISPATCH", """
                SELECT COUNT(*) FROM dispatch_ticket ticket
                WHERE ticket.status NOT IN ('SUCCEEDED','FAILED','CANCELED')
                  AND ticket.owner_kind IN (
                      'THREAD_TURN','TASK_TURN','STAGE_TURN','REVIEW_ASSIGNMENT_TURN')
                  AND NOT EXISTS (
                    SELECT 1 FROM (
                        SELECT id, operation_id, 'THREAD_TURN' AS kind, status FROM thread_turn
                        UNION ALL
                        SELECT id, operation_id, 'TASK_TURN', status FROM task_turn
                        UNION ALL
                        SELECT id, operation_id, 'STAGE_TURN', status FROM stage_turn
                        UNION ALL
                        SELECT id, operation_id, 'REVIEW_ASSIGNMENT_TURN', status
                        FROM review_assignment_turn
                    ) turn
                    WHERE turn.id = ticket.owner_id
                      AND turn.operation_id = ticket.operation_id
                      AND turn.kind = ticket.owner_kind
                      AND turn.status IN ('REQUESTED','QUEUED','CLAIMED','RUNNING'))
                """, "A live Turn-owned DispatchTicket must resolve to one live typed Turn");
        add(findings, "V2_LIVE_LEASE_STALE_TASK_EPOCH", "CAPACITY", """
                SELECT COUNT(*) FROM capacity_lease lease
                JOIN tasks task ON task.id = lease.task_id
                WHERE lease.workflow_source = 'V2' AND lease.released_at_ms IS NULL
                  AND lease.task_epoch <> task.epoch
                """, "Live capacity cannot survive a Task epoch advance");
        add(findings, "V2_CLAIM_WITHOUT_LIVE_CAPACITY", "CAPACITY", """
                SELECT COUNT(*) FROM dispatch_ticket ticket
                LEFT JOIN capacity_lease lease
                  ON lease.id = ticket.capacity_lease_id
                 AND lease.ticket_id = ticket.id
                 AND lease.operation_id = ticket.operation_id
                 AND lease.workflow_source = 'V2'
                 AND lease.lane_mask = ticket.lane_mask
                 AND lease.workspace_id IS ticket.workspace_id
                 AND lease.trunk_id IS ticket.trunk_id
                 AND lease.task_id IS ticket.task_id
                 AND lease.task_epoch IS ticket.task_epoch
                 AND lease.released_at_ms IS NULL
                WHERE ticket.status IN ('CLAIMED','RUNNING','DELIVERING')
                  AND lease.id IS NULL
                """, "A claimed V2 DispatchTicket must retain its exact live CapacityLease");
        add(findings, "V2_LIVE_CAPACITY_WITHOUT_CLAIM", "CAPACITY", """
                SELECT COUNT(*) FROM capacity_lease lease
                LEFT JOIN dispatch_ticket ticket
                  ON ticket.id = lease.ticket_id
                 AND ticket.operation_id = lease.operation_id
                 AND ticket.capacity_lease_id = lease.id
                 AND ticket.status IN ('CLAIMED','RUNNING','DELIVERING')
                WHERE lease.workflow_source = 'V2'
                  AND lease.released_at_ms IS NULL
                  AND ticket.id IS NULL
                """, "A live V2 CapacityLease must belong to its currently claimed ticket");
        add(findings, "V2_WORKSPACE_REPOSITORY_NOT_QUIESCENT", "WORKSPACE", """
                SELECT COUNT(*)
                FROM workspaces workspace
                WHERE (workspace.detached_at_ms IS NOT NULL
                    OR EXISTS (
                        SELECT 1 FROM workspace_creation creation
                        WHERE creation.workspace_id = workspace.id
                          AND creation.operation_kind = 'reclone'
                          AND creation.state IN (
                              'queued', 'forking', 'cloning', 'syncing')))
                  AND (EXISTS (
                        SELECT 1
                        FROM tasks task
                        JOIN threads trunk ON trunk.id = task.thread_id
                        WHERE trunk.workspace_id = workspace.id
                          AND task.workflow_version = 'V2'
                          AND task.lifecycle_state NOT IN (
                              'COMPLETED', 'CANCELED', 'REMOTE_CLOSED'))
                    OR EXISTS (
                        SELECT 1 FROM dispatch_ticket ticket
                        WHERE ticket.workspace_id = workspace.id
                          AND ticket.status NOT IN (
                              'SUCCEEDED', 'FAILED', 'CANCELED')))
                """, "A detached or re-cloning Workspace cannot retain active V2 owners");
        add(findings, "V2_WRITER_WITHOUT_WORKTREE_FENCE", "WRITER", """
                SELECT COUNT(*) FROM capacity_lease capacity
                WHERE capacity.workflow_source = 'V2'
                  AND capacity.writer_required = 1
                  AND capacity.released_at_ms IS NULL
                  AND NOT EXISTS (
                      SELECT 1 FROM worktree_leases writer
                      WHERE writer.workflow_version = 'V2'
                        AND writer.task_id = capacity.task_id
                        AND writer.task_epoch = capacity.task_epoch
                        AND writer.operation_id = capacity.operation_id
                        AND writer.fencing_token = capacity.fencing_token
                        AND writer.lease_owner = capacity.holder
                        AND writer.expires_at_ms IS NOT NULL
                        AND writer.expires_at_ms <= capacity.expires_at_ms)
                """, "Every live writer capacity must own the exact fenced WorktreeLease");
        add(findings, "V2_WORKTREE_WITHOUT_WRITER_CAPACITY", "WRITER", """
                SELECT COUNT(*) FROM worktree_leases writer
                JOIN tasks task ON task.id = writer.task_id
                WHERE writer.workflow_version = 'V2'
                  AND (writer.expires_at_ms IS NULL
                    OR writer.task_epoch <> task.epoch
                    OR NOT EXISTS (
                        SELECT 1 FROM capacity_lease capacity
                        WHERE capacity.workflow_source = 'V2'
                          AND capacity.writer_required = 1
                          AND capacity.released_at_ms IS NULL
                          AND capacity.task_id = writer.task_id
                          AND capacity.task_epoch = writer.task_epoch
                          AND capacity.operation_id = writer.operation_id
                          AND capacity.fencing_token = writer.fencing_token
                          AND capacity.holder = writer.lease_owner
                          AND writer.expires_at_ms <= capacity.expires_at_ms))
                """, "Every V2 WorktreeLease must retain its current writer-capacity fence");
        add(findings, "V2_TERMINAL_WITHOUT_OUTCOME", "TASK", """
                SELECT COUNT(*) FROM tasks task
                LEFT JOIN task_outcome outcome ON outcome.task_id = task.id
                WHERE task.workflow_version = 'V2'
                  AND task.lifecycle_state IN ('COMPLETED','CANCELED','REMOTE_CLOSED')
                  AND outcome.id IS NULL
                """, "Every terminal V2 Task must have exactly one durable TaskOutcome");
        add(findings, "V2_OUTCOME_TERMINAL_MISMATCH", "TASK", """
                SELECT COUNT(*) FROM task_outcome outcome
                JOIN tasks task ON task.id = outcome.task_id
                WHERE task.workflow_version = 'V2'
                  AND (task.lifecycle_state NOT IN ('COMPLETED','CANCELED','REMOTE_CLOSED')
                    OR task.lifecycle_state <> outcome.terminal_reason
                    OR task.epoch <> outcome.task_epoch
                    OR task.thread_id <> outcome.trunk_id)
                """, "TaskOutcome must match the terminal Task route, epoch, and reason");
        add(findings, "V2_TERMINAL_HAS_LIVE_OWNERS", "TASK", """
                SELECT COUNT(*) FROM tasks task
                WHERE task.workflow_version = 'V2'
                  AND task.lifecycle_state IN ('COMPLETED','CANCELED','REMOTE_CLOSED')
                  AND (EXISTS (SELECT 1 FROM task_current_stage current
                               WHERE current.task_id = task.id)
                    OR EXISTS (SELECT 1 FROM stage owner
                               WHERE owner.task_id = task.id
                                 AND owner.completed_at_ms IS NULL)
                    OR EXISTS (SELECT 1 FROM task_blocker blocker
                               WHERE blocker.task_id = task.id AND blocker.status = 'OPEN')
                    OR EXISTS (SELECT 1 FROM task_turn turn
                               WHERE turn.task_id = task.id
                                 AND turn.status IN ('REQUESTED','QUEUED','CLAIMED','RUNNING'))
                    OR EXISTS (SELECT 1 FROM stage_turn turn
                               JOIN stage owner ON owner.id = turn.stage_id
                               WHERE owner.task_id = task.id
                                 AND turn.status IN ('REQUESTED','QUEUED','CLAIMED','RUNNING'))
                    OR EXISTS (SELECT 1 FROM dispatch_ticket ticket
                               WHERE ticket.task_id = task.id
                                 AND ticket.status NOT IN ('SUCCEEDED','FAILED','CANCELED'))
                    OR EXISTS (SELECT 1 FROM capacity_lease lease
                               WHERE lease.workflow_source = 'V2'
                                 AND lease.task_id = task.id
                                 AND lease.released_at_ms IS NULL)
                    OR EXISTS (SELECT 1 FROM worktree_leases writer
                               WHERE writer.workflow_version = 'V2'
                                 AND writer.task_id = task.id))
                """, "A terminal Task cannot retain live owners, work, blockers, or leases");
        add(findings, "V2_COMMITTED_CLEANUP_WITHOUT_OUTCOME", "CLEANUP", """
                SELECT COUNT(*) FROM cleanup_operation cleanup
                JOIN dispatch_ticket ticket ON ticket.id = cleanup.dispatch_ticket_id
                LEFT JOIN task_outcome outcome ON outcome.cleanup_operation_id = cleanup.id
                WHERE cleanup.status = 'COMPLETED'
                  AND ticket.status = 'SUCCEEDED'
                  AND ticket.delivery_acceptance = 'ACCEPTED'
                  AND outcome.id IS NULL
                """, "Committed Cleanup delivery must eventually finalize its TaskOutcome");
        add(findings, "V2_LEGACY_OWNER_ROWS", "MIGRATION", """
                SELECT
                    (SELECT COUNT(*) FROM task_stage legacy
                     JOIN tasks task ON task.id = legacy.task_id
                     WHERE task.workflow_version = 'V2')
                  + (SELECT COUNT(*) FROM agent_run legacy
                     JOIN tasks task ON task.id = legacy.task_id
                     WHERE task.workflow_version = 'V2')
                  + (SELECT COUNT(*) FROM thread_turns legacy
                     JOIN tasks task ON task.id = legacy.task_id
                     WHERE task.workflow_version = 'V2')
                  + (SELECT COUNT(*) FROM thread_turns legacy
                     JOIN threads trunk ON trunk.id = legacy.thread_id
                     WHERE legacy.scope = 'TRUNK' AND legacy.task_id IS NULL
                       AND trunk.turn_version = 'V2')
                  + (SELECT COUNT(*) FROM task_phase_event legacy
                     JOIN tasks task ON task.id = legacy.task_id
                     WHERE task.workflow_version = 'V2')
                  + (SELECT COUNT(*) FROM task_status_event legacy
                     JOIN tasks task ON task.id = legacy.task_id
                     WHERE task.workflow_version = 'V2')
                """, "V2 Tasks cannot acquire legacy lifecycle, Stage, Turn, or AgentRun owners");
        return new Audit(findings.isEmpty(), List.copyOf(findings));
    }

    public DrainStatus legacyDrainStatus()
    {
        int nonterminalTasks = count("""
                SELECT COUNT(*) FROM tasks
                WHERE workflow_version = 'LEGACY'
                  AND status NOT IN ('COMPLETED','REMOTE_CLOSED','CANCELED')
                """);
        int liveTurns = count("""
                SELECT COUNT(*) FROM thread_turns turn
                LEFT JOIN tasks task ON task.id = turn.task_id
                WHERE COALESCE(task.workflow_version, 'LEGACY') = 'LEGACY'
                  AND turn.status IN ('QUEUED','RUNNING')
                """);
        int liveRuns = count("""
                SELECT COUNT(*) FROM agent_run run
                LEFT JOIN tasks task ON task.id = run.task_id
                WHERE COALESCE(task.workflow_version, 'LEGACY') = 'LEGACY'
                  AND (run.task_id IS NOT NULL OR NOT EXISTS (
                      SELECT 1 FROM review_round review
                      JOIN review_session session ON session.id = review.session_id
                      JOIN tasks owner ON owner.id = session.owner_task_id
                      WHERE (review.id = run.review_round_id
                          OR review.agent_run_id = run.id)
                        AND owner.workflow_version = 'V2'))
                  AND run.status IN ('queued','running','paused','awaiting_gate')
                """);
        int liveValidationClaims = count("""
                SELECT COUNT(*) FROM validation_pass
                WHERE workflow_version = 'LEGACY'
                  AND ended_at_ms IS NULL
                  AND superseded_at_ms IS NULL
                """);
        int liveEffects = count("""
                SELECT
                    (SELECT COUNT(*) FROM task_push_authorization authorization
                     JOIN tasks task ON task.id = authorization.task_id
                     WHERE task.workflow_version = 'LEGACY'
                       AND authorization.consumed_at_ms IS NULL)
                  + (SELECT COUNT(*) FROM round_gate_authorization authorization
                     JOIN tasks task ON task.id = authorization.task_id
                     WHERE task.workflow_version = 'LEGACY'
                       AND authorization.consumed_at_ms IS NULL)
                """);
        return new DrainStatus(
                nonterminalTasks == 0 && liveTurns == 0
                        && liveRuns == 0 && liveValidationClaims == 0
                        && liveEffects == 0,
                nonterminalTasks, liveTurns, liveRuns,
                liveValidationClaims, liveEffects);
    }

    private void add(
            List<Finding> findings,
            String code,
            String scope,
            String sql,
            String detail)
    {
        int count = count(sql);
        if (count > 0) {
            findings.add(new Finding(code, scope, count, detail));
        }
    }

    private int count(String sql, Object... arguments)
    {
        Integer value = jdbc.queryForObject(sql, Integer.class, arguments);
        return value == null ? 0 : value;
    }

    public record Audit(boolean healthy, List<Finding> findings) {}

    public record Finding(String code, String scope, int count, String detail) {}

    public record DrainStatus(
            boolean drained,
            int nonterminalTasks,
            int liveTurns,
            int liveRuns,
            int liveValidationClaims,
            int liveEffects) {}
}
