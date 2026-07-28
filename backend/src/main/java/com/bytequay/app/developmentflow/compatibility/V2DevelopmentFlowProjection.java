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

import com.bytequay.app.beans.stage.StageDetailDto;
import com.bytequay.app.beans.stage.StageDto;
import com.bytequay.app.beans.stage.StageEventDto;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Read-only LEGACY API compatibility projection for immutable V2 facts. */
@Component
public final class V2DevelopmentFlowProjection
{
    private static final int STAGE_EVENT_LIMIT = 50;

    private final JdbcTemplate jdbc;

    public V2DevelopmentFlowProjection(JdbcTemplate jdbc)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
    }

    public boolean isV2Task(String taskId)
    {
        return count("""
                SELECT COUNT(*) FROM tasks
                WHERE id = ? AND workflow_version = 'V2'
                """, taskId) == 1;
    }

    public Task project(Task legacyShape)
    {
        requireNonNull(legacyShape, "legacyShape is null");
        Projection row = jdbc.query("""
                SELECT task.lifecycle_state, owner.kind, owner.checkpoint,
                       code.branch_name, code.worktree_path,
                       context.base_ref, context.repository_id,
                       binding.remote_pr_number,
                       binding.bound_at_ms,
                       COALESCE(snapshot.pr_state, pr.status) AS pr_state,
                       evaluation.normalized_status AS ci_state,
                       evaluation.policy_outcome AS ci_outcome,
                       outcome.recorded_at_ms,
                       (SELECT COUNT(*) FROM task_blocker blocker
                        WHERE blocker.task_id = task.id AND blocker.status = 'OPEN')
                           AS blocker_count,
                       (SELECT COUNT(*) FROM dispatch_ticket ticket
                        WHERE ticket.task_id = task.id AND ticket.task_epoch = task.epoch
                          AND ticket.status IN ('CLAIMED','RUNNING','DELIVERING'))
                           AS running_count,
                       (SELECT COUNT(*) FROM dispatch_ticket ticket
                        WHERE ticket.task_id = task.id AND ticket.task_epoch = task.epoch
                          AND ticket.status IN ('REQUESTED','RETRY_WAIT','RECONCILE_WAIT',
                                                'RESULT_PENDING'))
                           AS queued_count,
                       (SELECT COALESCE(SUM(execution.cost_usd_milli), 0)
                        FROM agent_execution execution
                        JOIN dispatch_ticket ticket ON ticket.id = execution.ticket_id
                        WHERE ticket.task_id = task.id) AS cost_usd_milli,
                       (SELECT COALESCE(SUM(execution.tokens_in), 0)
                        FROM agent_execution execution
                        JOIN dispatch_ticket ticket ON ticket.id = execution.ticket_id
                        WHERE ticket.task_id = task.id) AS tokens_in,
                       (SELECT COALESCE(SUM(execution.tokens_out), 0)
                        FROM agent_execution execution
                        JOIN dispatch_ticket ticket ON ticket.id = execution.ticket_id
                        WHERE ticket.task_id = task.id) AS tokens_out
                FROM tasks task
                LEFT JOIN task_current_stage current ON current.task_id = task.id
                LEFT JOIN stage owner ON owner.id = current.stage_id
                LEFT JOIN task_code_identity code ON code.task_id = task.id
                LEFT JOIN task_creation_context context ON context.task_id = task.id
                LEFT JOIN pr ON pr.task_id = task.id AND pr.origin = 'task'
                LEFT JOIN remote_pr_binding binding ON binding.task_id = task.id
                LEFT JOIN remote_development_stage remote
                  ON remote.stage_id = owner.id
                LEFT JOIN remote_pr_snapshot snapshot
                  ON snapshot.id = remote.accepted_snapshot_id
                LEFT JOIN remote_ci_evaluation evaluation ON evaluation.id = (
                    SELECT candidate.id FROM remote_ci_evaluation candidate
                    WHERE candidate.task_id = task.id
                      AND candidate.head_sha = snapshot.head_sha
                    ORDER BY candidate.evaluated_at_ms DESC, candidate.id DESC
                    LIMIT 1)
                LEFT JOIN task_outcome outcome ON outcome.task_id = task.id
                WHERE task.id = ? AND task.workflow_version = 'V2'
                """, V2DevelopmentFlowProjection::projection, legacyShape.id())
                .stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Task is not routed through V2: " + legacyShape.id()));

        return new Task(
                legacyShape.id(), legacyShape.threadId(), legacyShape.seq(),
                status(row),
                first(row.branchName(), legacyShape.branchName()),
                first(row.worktreePath(), legacyShape.worktreePath()),
                first(row.baseRef(), legacyShape.baseBranch()),
                repositoryRoot(row.worktreePath(), legacyShape.workingDir()),
                null, null,
                row.remotePrNumber(), prState(row.prState()), ciState(row),
                legacyShape.taskType(), row.remotePrNumber(),
                legacyShape.linkedIssueNumber(),
                row.costUsdMilli(), row.tokensIn(), row.tokensOut(), null,
                legacyShape.createdAt(), instant(row.recordedAtMs()), null,
                legacyShape.name(), legacyShape.roleSkill(), legacyShape.workModel(),
                instant(row.boundAtMs()),
                phase(row), legacyShape.agendaJson(), 0,
                row.remotePrNumber() == null || row.repositoryId() == null
                        ? null
                        : row.repositoryId() + "#" + row.remotePrNumber(),
                legacyShape.openingPrompt(), legacyShape.origin());
    }

    public List<StageDto> stages(String taskId)
    {
        return jdbc.query("""
                SELECT id, task_id, kind, generation, checkpoint,
                       opened_at_ms, completed_at_ms
                FROM stage
                WHERE task_id = ?
                ORDER BY opened_at_ms, generation, id
                """, V2DevelopmentFlowProjection::stage, taskId);
    }

    public List<StageDto> activeStages(String taskId)
    {
        return jdbc.query("""
                SELECT id, task_id, kind, generation, checkpoint,
                       opened_at_ms, completed_at_ms
                FROM stage
                WHERE task_id = ? AND completed_at_ms IS NULL
                ORDER BY opened_at_ms, generation, id
                """, V2DevelopmentFlowProjection::stage, taskId);
    }

    public Optional<StageDetailDto> stageDetail(String stageId)
    {
        Optional<StageDto> stage = jdbc.query("""
                SELECT id, task_id, kind, generation, checkpoint,
                       opened_at_ms, completed_at_ms
                FROM stage WHERE id = ?
                """, V2DevelopmentFlowProjection::stage, stageId)
                .stream().findFirst();
        return stage.map(value -> new StageDetailDto(value, jdbc.query("""
                SELECT transition.id, transition.stage_id, owner.task_id,
                       transition.command_id, transition.from_checkpoint,
                       transition.to_checkpoint, transition.cause,
                       transition.actor, transition.occurred_at_ms
                FROM stage_transition transition
                JOIN stage owner ON owner.id = transition.stage_id
                WHERE transition.stage_id = ?
                ORDER BY occurred_at_ms DESC, stage_version DESC
                LIMIT ?
                """, V2DevelopmentFlowProjection::stageEvent,
                stageId, STAGE_EVENT_LIMIT)));
    }

    private int count(String sql, Object... arguments)
    {
        Integer value = jdbc.queryForObject(sql, Integer.class, arguments);
        return value == null ? 0 : value;
    }

    private static Projection projection(ResultSet rs, int row)
            throws SQLException
    {
        return new Projection(
                rs.getString("lifecycle_state"), rs.getString("kind"),
                rs.getString("checkpoint"), rs.getString("branch_name"),
                rs.getString("worktree_path"), rs.getString("base_ref"),
                rs.getString("repository_id"),
                nullableInt(rs, "remote_pr_number"),
                nullableLong(rs, "bound_at_ms"), rs.getString("pr_state"),
                rs.getString("ci_state"), rs.getString("ci_outcome"),
                nullableLong(rs, "recorded_at_ms"),
                rs.getInt("blocker_count"), rs.getInt("running_count"),
                rs.getInt("queued_count"), rs.getLong("cost_usd_milli"),
                rs.getLong("tokens_in"), rs.getLong("tokens_out"));
    }

    private static StageDto stage(ResultSet rs, int row)
            throws SQLException
    {
        Long completed = nullableLong(rs, "completed_at_ms");
        return new StageDto(
                rs.getString("id"), rs.getString("task_id"),
                legacyStageType(rs.getString("kind")),
                completed == null ? "OPEN" : "CLOSED",
                instant(rs.getLong("opened_at_ms")).toString(),
                completed == null ? null : instant(completed).toString(),
                null,
                "{\"checkpoint\":\"" + json(rs.getString("checkpoint")) + "\"}",
                Math.max(0, rs.getInt("generation") - 1));
    }

    private static StageEventDto stageEvent(ResultSet rs, int row)
            throws SQLException
    {
        String from = rs.getString("from_checkpoint");
        String to = rs.getString("to_checkpoint");
        String event = from == null ? "OPENED"
                : "COMPLETED".equals(to) ? "CLOSED" : "OPERATION_COMPLETED";
        String payload = "{\"commandId\":\"" + json(rs.getString("command_id"))
                + "\",\"fromCheckpoint\":"
                + (from == null ? "null" : "\"" + json(from) + "\"")
                + ",\"toCheckpoint\":\"" + json(to)
                + "\",\"cause\":\"" + json(rs.getString("cause"))
                + "\",\"actor\":\"" + json(rs.getString("actor")) + "\"}";
        return new StageEventDto(
                rs.getString("id"), rs.getString("stage_id"),
                rs.getString("task_id"), event,
                instant(rs.getLong("occurred_at_ms")).toString(), payload);
    }

    private static TaskStatus status(Projection row)
    {
        return switch (row.lifecycle()) {
            case "COMPLETED" -> TaskStatus.COMPLETED;
            case "CANCELED" -> TaskStatus.CANCELED;
            case "REMOTE_CLOSED" -> TaskStatus.REMOTE_CLOSED;
            case "PAUSED" -> TaskStatus.PAUSED;
            case "ARCHIVED" -> TaskStatus.ARCHIVED;
            case "PAUSING", "RESUMING", "ARCHIVING", "CANCELING", "CLEANING" ->
                    TaskStatus.RUNNING;
            case "PROVISIONING" -> TaskStatus.PENDING;
            default -> {
                if (row.blockerCount() > 0) {
                    yield TaskStatus.NEEDS_ATTENTION;
                }
                if (row.runningCount() > 0) {
                    yield TaskStatus.RUNNING;
                }
                if (row.queuedCount() > 0) {
                    yield TaskStatus.PENDING;
                }
                if ("REMOTE_DEVELOPMENT".equals(row.stageKind())) {
                    yield TaskStatus.IN_REVIEW;
                }
                if ("LOCAL_REVIEW".equals(row.checkpoint())
                        || "PUBLISHING".equals(row.checkpoint())) {
                    yield TaskStatus.AWAITING_REVIEW;
                }
                yield TaskStatus.IDLE;
            }
        };
    }

    private static TaskPhase phase(Projection row)
    {
        if (row.lifecycle().equals("COMPLETED")
                || row.lifecycle().equals("CANCELED")
                || row.lifecycle().equals("REMOTE_CLOSED")
                || "CLEANUP".equals(row.stageKind())) {
            return TaskPhase.COMPLETED;
        }
        if (row.blockerCount() > 0) {
            return TaskPhase.NEEDS_ATTENTION;
        }
        if (row.stageKind() == null || "PLAN".equals(row.stageKind())) {
            return TaskPhase.PLANNING;
        }
        if ("REMOTE_DEVELOPMENT".equals(row.stageKind())) {
            return switch (row.checkpoint()) {
                case "WAITING_CI", "ADDRESSING_REMOTE_FEEDBACK" ->
                        TaskPhase.PUSHED_AWAITING_CI;
                case "AWAITING_READY" -> TaskPhase.AWAITING_READY;
                default -> TaskPhase.AWAITING_REMOTE_REVIEW;
            };
        }
        return switch (row.checkpoint()) {
            case "VALIDATING" -> TaskPhase.VALIDATING;
            case "BRAIN_REVIEW" -> TaskPhase.INTERNAL_REVIEW;
            case "LOCAL_REVIEW", "PUBLISHING" -> TaskPhase.AWAITING_PUSH;
            case "ADDRESSING_LOCAL_FEEDBACK" -> TaskPhase.ADDRESSING_LOCAL_COMMENTS;
            default -> TaskPhase.IMPLEMENTING;
        };
    }

    private static String ciState(Projection row)
    {
        if (row.ciState() == null) {
            return null;
        }
        if ("NONE".equals(row.ciState())) {
            return "NONE";
        }
        return switch (row.ciOutcome()) {
            case "ACCEPTED" -> "PASSING";
            case "FAILED" -> "FAILING";
            case "WAITING" -> "PENDING";
            case null -> "UNKNOWN";
            default -> throw new IllegalArgumentException(
                    "Unknown V2 CI policy outcome: " + row.ciOutcome());
        };
    }

    private static String prState(String state)
    {
        if (state == null) {
            return null;
        }
        return switch (state.toUpperCase(Locale.ROOT)) {
            case "DRAFT" -> "remote-drafted";
            case "OPEN" -> "remote-open";
            case "MERGED" -> "merged";
            case "CLOSED" -> "closed";
            case "LOCAL-DRAFTED", "LOCAL-OPEN", "REMOTE-DRAFTED", "REMOTE-OPEN" ->
                    state.toLowerCase(Locale.ROOT);
            default -> throw new IllegalArgumentException(
                    "Unknown V2 PR projection state: " + state);
        };
    }

    private static String legacyStageType(String kind)
    {
        return switch (kind) {
            case "PLAN" -> "PLAN_STAGE";
            case "LOCAL_DEVELOPMENT" -> "DEVELOPMENT_STAGE";
            case "REMOTE_DEVELOPMENT" -> "REMOTE_DEVELOPMENT_STAGE";
            case "CLEANUP" -> "CLEANUP_STAGE";
            default -> throw new IllegalArgumentException("Unknown V2 Stage kind: " + kind);
        };
    }

    private static String repositoryRoot(String worktreePath, String fallback)
    {
        if (worktreePath == null) {
            return fallback;
        }
        Path worktree = Path.of(worktreePath);
        Path worktrees = worktree.getParent();
        Path root = worktrees == null ? null : worktrees.getParent();
        return root == null ? fallback : root.toString();
    }

    private static String first(String value, String fallback)
    {
        return value == null ? fallback : value;
    }

    private static Instant instant(Long value)
    {
        return value == null ? null : Instant.ofEpochMilli(value);
    }

    private static Integer nullableInt(ResultSet rs, String column)
            throws SQLException
    {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static Long nullableLong(ResultSet rs, String column)
            throws SQLException
    {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static String json(String value)
    {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private record Projection(
            String lifecycle,
            String stageKind,
            String checkpoint,
            String branchName,
            String worktreePath,
            String baseRef,
            String repositoryId,
            Integer remotePrNumber,
            Long boundAtMs,
            String prState,
            String ciState,
            String ciOutcome,
            Long recordedAtMs,
            int blockerCount,
            int runningCount,
            int queuedCount,
            long costUsdMilli,
            long tokensIn,
            long tokensOut) {}
}
