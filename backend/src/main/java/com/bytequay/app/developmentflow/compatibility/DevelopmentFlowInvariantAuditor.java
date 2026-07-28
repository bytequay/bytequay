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

/** Read-only canary and drain checks. Findings are diagnostics, never commands. */
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
        add(findings, "V2_LIVE_LEASE_STALE_TASK_EPOCH", "CAPACITY", """
                SELECT COUNT(*) FROM capacity_lease lease
                JOIN tasks task ON task.id = lease.task_id
                WHERE lease.workflow_source = 'V2' AND lease.released_at_ms IS NULL
                  AND lease.task_epoch <> task.epoch
                """, "Live capacity cannot survive a Task epoch advance");
        add(findings, "V2_TERMINAL_WITHOUT_OUTCOME", "TASK", """
                SELECT COUNT(*) FROM tasks task
                LEFT JOIN task_outcome outcome ON outcome.task_id = task.id
                WHERE task.workflow_version = 'V2'
                  AND task.lifecycle_state IN ('COMPLETED','CANCELED','REMOTE_CLOSED')
                  AND outcome.id IS NULL
                """, "Every terminal V2 Task must have exactly one durable TaskOutcome");
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
                JOIN tasks task ON task.id = run.task_id
                WHERE task.workflow_version = 'LEGACY'
                  AND run.status IN ('running','awaiting_gate')
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
                        && liveRuns == 0 && liveEffects == 0,
                nonterminalTasks, liveTurns, liveRuns, liveEffects);
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
            int liveEffects) {}
}
