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

import com.bytequay.app.beans.stage.ApprovalDto;
import com.bytequay.app.beans.stage.BrainFeedRow;
import com.bytequay.app.beans.stage.ContextWindowDto;
import com.bytequay.app.beans.stage.LinkedPrDto;
import com.bytequay.app.beans.stage.ScrubberDash;
import com.bytequay.app.beans.stage.StageDetailDto;
import com.bytequay.app.beans.stage.StageDto;
import com.bytequay.app.beans.stage.StageEventDto;
import com.bytequay.app.beans.stage.TaskBrainViewData;
import com.bytequay.app.beans.trace.LinkedActivePr;
import com.bytequay.app.developmentflow.stage.PlanRuntimeCoordinator;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/** Read-only LEGACY API compatibility projection for immutable V2 facts. */
@Component
public final class V2DevelopmentFlowProjection
{
    private static final int STAGE_EVENT_LIMIT = 50;
    private static final int CONTEXT_TOKEN_LIMIT = 200_000;
    private static final Set<String> TERMINAL_LIFECYCLES =
            Set.of("COMPLETED", "CANCELED", "REMOTE_CLOSED");
    private static final ObjectMapper JSON = new ObjectMapper();

    private final JdbcTemplate jdbc;
    private final V2BranchGuardProjection branchGuards;

    public V2DevelopmentFlowProjection(JdbcTemplate jdbc)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.branchGuards = new V2BranchGuardProjection(jdbc);
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
        Projection row = loadProjection(legacyShape.id());
        return project(legacyShape, row);
    }

    private Task project(Task legacyShape, Projection row)
    {
        return new Task(
                legacyShape.id(), legacyShape.threadId(), legacyShape.seq(),
                status(row),
                first(row.branchName(), legacyShape.branchName()),
                first(row.worktreePath(), legacyShape.worktreePath()),
                row.baseRef(),
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

    /**
     * Projects the current brain response exclusively from V2 aggregate and
     * evidence tables. Legacy-only slots stay conservative (empty/disabled)
     * instead of querying legacy owners or inventing an equivalent owner.
     */
    public TaskBrainViewData brain(Task legacyShape)
    {
        requireNonNull(legacyShape, "legacyShape is null");
        Projection row = loadProjection(legacyShape.id());
        Task task = project(legacyShape, row);
        List<StageDto> stages = stages(task.id());
        BrainAggregate aggregate = brainAggregate(task.id());
        List<BrainFeedRow> feed = brainFeed(task.id());
        List<BrainFeedRow> stageRows = feed.stream()
                .filter(event -> !"USER_MESSAGE".equals(event.type())
                        && !"BRAIN_AGENT_RESPONSE".equals(event.type()))
                .toList();
        List<BrainFeedRow> userRows = feed.stream()
                .filter(event -> "USER_MESSAGE".equals(event.type()))
                .toList();
        List<ScrubberDash> stageScrubber = scrubber(stageRows);
        List<ScrubberDash> userScrubber = scrubber(userRows);

        TaskBrainViewData.CostBreakdown costs = costBreakdown(
                task.id(), aggregate.costUsdMilli(), aggregate.pushes());
        TaskBrainViewData.AutoPushBudget budget = autoPushBudget(task.id());
        LinkedPrDto linkedPr = linkedPr(row);
        ApprovalDto approval = approval(task.id());
        boolean terminal = TERMINAL_LIFECYCLES.contains(row.lifecycle());
        boolean paused = !terminal && ("PAUSED".equals(row.lifecycle())
                || "ARCHIVED".equals(row.lifecycle()) || row.blockerCount() > 0);

        return new TaskBrainViewData(
                new TaskBrainViewData.BrainTask(
                        task.id(), first(task.name(), first(task.branchName(), "")),
                        task.seq(), first(task.branchName(), ""),
                        first(row.repositoryId(), ""), task.prNumber(),
                        isDraft(row.prState()), task.phase().name(),
                        statusLabel(task, row), first(row.agentRuntime(), "API"),
                        first(row.agentModel(), ""), paused, terminal),
                new TaskBrainViewData.Aggregate(
                        aggregate.pushes(), aggregate.activeTimeMs() / 1000,
                        0, 0, aggregate.turns(), aggregate.messages(), 0,
                        (int) Math.min(Integer.MAX_VALUE,
                                aggregate.costUsdMilli() / 10), budget),
                stages,
                List.of(),
                null,
                feed,
                new TaskBrainViewData.RightRail(
                        approval, linkedPr,
                        new ContextWindowDto(
                                Math.toIntExact(Math.min(Integer.MAX_VALUE,
                                        row.tokensIn() + row.tokensOut())),
                                CONTEXT_TOKEN_LIMIT,
                                contextBand(row.tokensIn() + row.tokensOut())),
                        List.of(),
                        false,
                        null,
                        costs,
                        planCard(task.id())),
                new TaskBrainViewData.Scrubbers(stageScrubber, userScrubber),
                List.of(),
                branchGuards.project(task.id()),
                null,
                devPhases(row),
                recovery(task.id()));
    }

    /** Immutable V2 facts consumed by the legacy-shaped trace presenter. */
    public TraceFacts traceFacts(Task legacyShape)
    {
        requireNonNull(legacyShape, "legacyShape is null");
        Projection row = loadProjection(legacyShape.id());
        Task task = project(legacyShape, row);
        List<PhaseFact> facts = jdbc.query("""
                SELECT source, kind, checkpoint, actor, reason, occurred_at_ms
                FROM (
                    SELECT 'STAGE' AS source, owner.kind AS kind,
                           transition.to_checkpoint AS checkpoint,
                           transition.actor AS actor, transition.cause AS reason,
                           transition.occurred_at_ms AS occurred_at_ms,
                           transition.id AS fact_id
                    FROM stage_transition transition
                    JOIN stage owner ON owner.id = transition.stage_id
                    WHERE owner.task_id = ?
                    UNION ALL
                    SELECT 'TASK', NULL, transition.to_state,
                           transition.actor, transition.cause,
                           transition.occurred_at_ms, transition.id
                    FROM task_transition transition
                    WHERE transition.task_id = ?
                    UNION ALL
                    SELECT 'BLOCKER', NULL, blocker.status,
                           'SYSTEM', blocker.blocker_type,
                           blocker.opened_at_ms, blocker.id
                    FROM task_blocker blocker
                    WHERE blocker.task_id = ?
                ) facts
                ORDER BY occurred_at_ms, fact_id
                """, (rs, ignored) -> phaseFact(rs),
                task.id(), task.id(), task.id()).stream()
                .flatMap(Optional::stream)
                .sorted(Comparator.comparing(PhaseFact::occurredAt))
                .toList();

        List<PhaseFact> canonical = new ArrayList<>();
        for (PhaseFact fact : facts) {
            if (canonical.isEmpty()
                    || canonical.get(canonical.size() - 1).phase() != fact.phase()) {
                canonical.add(fact);
            }
        }
        if (canonical.isEmpty()
                || canonical.get(canonical.size() - 1).phase() != task.phase()) {
            long at = row.ownerOpenedAtMs() == null
                    ? row.taskCreatedAtMs() : row.ownerOpenedAtMs();
            if (!canonical.isEmpty()) {
                at = Math.max(at, canonical.get(canonical.size() - 1)
                        .occurredAt().toEpochMilli());
            }
            canonical.add(new PhaseFact(
                    task.phase(), "SYSTEM", "current_v2_projection", instant(at)));
        }
        return new TraceFacts(task, List.copyOf(canonical), linkedActivePr(row));
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

    private Projection loadProjection(String taskId)
    {
        Projection row = jdbc.query("""
                SELECT task.id AS task_id, task.lifecycle_state,
                       task.created_at_ms AS task_created_at_ms,
                       current.stage_id AS current_stage_id,
                       current.stage_generation AS current_stage_generation,
                       owner.task_id AS owner_task_id,
                       owner.kind, owner.generation AS owner_generation,
                       owner.checkpoint,
                       owner.opened_at_ms AS owner_opened_at_ms,
                       owner.completed_at_ms AS owner_completed_at_ms,
                       code.branch_name, code.worktree_path,
                       CASE
                           WHEN context.base_source IS NULL
                               THEN NULLIF(task.base_branch, '')
                           WHEN context.base_source = 'EXISTING_PR_HEAD'
                               THEN COALESCE(
                                   NULLIF(context.base_ref, ''),
                                   CASE WHEN json_valid(provision.result_evidence)
                                       AND json_extract(
                                           provision.result_evidence, '$.schema')
                                           = 'PROVISION_TASK_V2'
                                       AND json_extract(
                                           provision.result_evidence, '$.baseSource')
                                           = 'EXISTING_PR_HEAD'
                                       THEN NULLIF(json_extract(
                                           provision.result_evidence,
                                           '$.pullRequest.baseRef'), '')
                                   END)
                           ELSE NULLIF(context.base_ref, '')
                       END AS base_ref,
                       context.repository_id,
                       CASE WHEN json_valid(context.work_model_snapshot)
                            THEN json_extract(context.work_model_snapshot, '$.kind')
                            ELSE NULL END AS agent_runtime,
                       brain.model AS agent_model,
                       binding.remote_pr_number,
                       binding.bound_at_ms,
                       CASE
                           WHEN snapshot.pr_state IN ('MERGED', 'CLOSED')
                               THEN snapshot.pr_state
                           WHEN cached_pr.merged_at IS NOT NULL THEN 'MERGED'
                           WHEN UPPER(cached_pr.state) = 'CLOSED' THEN 'CLOSED'
                           ELSE COALESCE(snapshot.pr_state, pr.status)
                       END AS pr_state,
                       snapshot.mergeability,
                       snapshot.effective_approval_count,
                       snapshot.changes_requested_count,
                       snapshot.requested_reviewer_count,
                       evaluation.normalized_status AS ci_state,
                       evaluation.policy_outcome AS ci_outcome,
                       outcome.recorded_at_ms,
                       (SELECT COUNT(*) FROM stage open_stage
                        WHERE open_stage.task_id = task.id
                          AND open_stage.completed_at_ms IS NULL) AS open_stage_count,
                       (SELECT COUNT(*) FROM task_current_stage pointer
                        WHERE pointer.task_id = task.id) AS current_stage_count,
                       (SELECT COUNT(*) FROM task_outcome terminal
                        WHERE terminal.task_id = task.id) AS outcome_count,
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
                LEFT JOIN provision_task_operation provision
                  ON provision.id = code.provision_operation_id
                 AND provision.status = 'ACCEPTED'
                LEFT JOIN task_brain brain ON brain.task_id = task.id
                LEFT JOIN pr ON pr.task_id = task.id AND pr.origin = 'task'
                LEFT JOIN remote_pr_binding binding ON binding.task_id = task.id
                LEFT JOIN pull_requests cached_pr
                  ON cached_pr.repo = context.repository_id
                 AND cached_pr.number = binding.remote_pr_number
                LEFT JOIN remote_development_stage remote ON remote.stage_id = (
                    SELECT candidate.stage_id
                    FROM remote_development_stage candidate
                    WHERE candidate.task_id = task.id
                    ORDER BY candidate.subject_changed_at_ms DESC,
                             candidate.stage_id DESC
                    LIMIT 1)
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
                """, V2DevelopmentFlowProjection::projection, taskId)
                .stream().findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "Task is not routed through V2: " + taskId));
        validateOwner(row, taskId);
        return row;
    }

    private static void validateOwner(Projection row, String taskId)
    {
        boolean pointerExact = row.currentStageId() == null
                || (taskId.equals(row.ownerTaskId())
                    && row.currentStageGeneration() != null
                    && row.currentStageGeneration().equals(row.ownerGeneration())
                    && row.ownerCompletedAtMs() == null);
        boolean ownerCardinalityExact = row.openStageCount() == row.currentStageCount()
                && row.openStageCount() <= 1;
        boolean activeHasOwner = !"ACTIVE".equals(row.lifecycle())
                || row.currentStageCount() == 1;
        boolean terminalExact = !TERMINAL_LIFECYCLES.contains(row.lifecycle())
                || (row.currentStageCount() == 0 && row.openStageCount() == 0
                    && row.outcomeCount() == 1);
        if (!pointerExact || !ownerCardinalityExact || !activeHasOwner || !terminalExact) {
            throw new IllegalStateException(
                    "Inconsistent V2 Task owner state: " + taskId);
        }
    }

    private BrainAggregate brainAggregate(String taskId)
    {
        return jdbc.queryForObject("""
                SELECT
                    (CASE WHEN EXISTS (
                        SELECT 1 FROM remote_pr_binding binding
                        WHERE binding.task_id = ?) THEN 1 ELSE 0 END
                     + COALESCE((SELECT SUM(repair.push_count)
                        FROM ci_repair_episode repair WHERE repair.task_id = ?), 0)
                     + (SELECT COUNT(*) FROM branch_sync_push_proof proof
                        JOIN branch_sync_episode episode
                          ON episode.id = proof.branch_sync_episode_id
                        WHERE episode.task_id = ?)) AS pushes,
                    COALESCE((SELECT SUM(MAX(0,
                        COALESCE(owner.completed_at_ms, CAST(strftime('%s','now') AS INTEGER) * 1000)
                        - owner.opened_at_ms))
                        FROM stage owner WHERE owner.task_id = ?), 0) AS active_time_ms,
                    ((SELECT COUNT(*) FROM task_turn turn WHERE turn.task_id = ?)
                     + (SELECT COUNT(*) FROM stage_turn turn
                        JOIN stage owner ON owner.id = turn.stage_id
                        WHERE owner.task_id = ?)) AS turns,
                    (SELECT COUNT(*) FROM task_message message
                     JOIN task_turn turn ON turn.id = message.turn_id
                     WHERE turn.task_id = ?
                       AND turn.purpose = 'TASK_BRAIN_CONVERSATION') AS messages,
                    COALESCE((SELECT SUM(execution.cost_usd_milli)
                        FROM agent_execution execution
                        JOIN dispatch_ticket ticket ON ticket.id = execution.ticket_id
                        WHERE ticket.task_id = ?), 0) AS cost_usd_milli
                """, (rs, ignored) -> new BrainAggregate(
                        rs.getInt("pushes"), rs.getLong("active_time_ms"),
                        rs.getInt("turns"), rs.getInt("messages"),
                        rs.getLong("cost_usd_milli")),
                taskId, taskId, taskId, taskId, taskId, taskId, taskId,
                taskId);
    }

    private List<BrainFeedRow> brainFeed(String taskId)
    {
        Map<String, List<String>> attachments = new HashMap<>();
        RowMapper<AttachmentRef> attachmentMapper = (result, row) ->
                new AttachmentRef(result.getString("turn_id"),
                        result.getString("content_ref"));
        List<AttachmentRef> attachmentRows = jdbc.query("""
                SELECT attachment.turn_id, attachment.content_ref
                FROM task_attachment attachment
                JOIN task_turn turn ON turn.id = attachment.turn_id
                WHERE turn.task_id = ?
                  AND turn.purpose = 'TASK_BRAIN_CONVERSATION'
                ORDER BY attachment.created_at_ms, attachment.id
                """, attachmentMapper, taskId);
        for (AttachmentRef attachment : attachmentRows) {
            attachments.computeIfAbsent(
                            attachment.turnId(), ignored -> new ArrayList<>())
                    .add(attachment.contentRef());
        }
        List<BrainFeedRow> rows = new ArrayList<>(jdbc.query("""
                SELECT transition.id, transition.stage_id, owner.kind,
                       transition.from_checkpoint, transition.to_checkpoint,
                       transition.cause, transition.actor,
                       transition.occurred_at_ms
                FROM stage_transition transition
                JOIN stage owner ON owner.id = transition.stage_id
                WHERE owner.task_id = ?
                ORDER BY transition.occurred_at_ms, transition.stage_version
                """, (rs, ignored) -> {
                    String from = rs.getString("from_checkpoint");
                    String to = rs.getString("to_checkpoint");
                    String type = from == null ? "STAGE_OPENED"
                            : "COMPLETED".equals(to) ? "STAGE_CLOSED"
                            : "STAGE_PROGRESS";
                    String body = legacyStageType(rs.getString("kind"))
                            .replace('_', ' ') + " · " + to.replace('_', ' ').toLowerCase(Locale.ROOT);
                    return new BrainFeedRow(
                            rs.getString("id"), null, type,
                            rs.getString("stage_id"), legacyStageType(rs.getString("kind")),
                            instant(rs.getLong("occurred_at_ms")).toString(),
                            body, null, List.of(), List.of(), null);
                }, taskId));
        rows.addAll(jdbc.query("""
                SELECT message.id, message.turn_id, message.role, message.body,
                       message.created_at_ms, turn.trigger_stage_id,
                       owner.kind,
                       ROW_NUMBER() OVER (
                           ORDER BY message.created_at_ms,
                                    turn.requested_at_ms,
                                    message.turn_id, message.seq) AS message_seq
                FROM task_message message
                JOIN task_turn turn ON turn.id = message.turn_id
                LEFT JOIN stage owner ON owner.id = turn.trigger_stage_id
                WHERE turn.task_id = ?
                  AND turn.purpose = 'TASK_BRAIN_CONVERSATION'
                ORDER BY message.created_at_ms, turn.requested_at_ms,
                         message.turn_id, message.seq
                """, (rs, ignored) -> new BrainFeedRow(
                        rs.getString("id"), rs.getLong("message_seq"),
                        "USER".equals(rs.getString("role"))
                                ? "USER_MESSAGE" : "BRAIN_AGENT_RESPONSE",
                        rs.getString("trigger_stage_id"),
                        rs.getString("kind") == null ? null
                                : legacyStageType(rs.getString("kind")),
                        instant(rs.getLong("created_at_ms")).toString(),
                        rs.getString("body"), null,
                        "USER".equals(rs.getString("role"))
                                ? List.copyOf(attachments.getOrDefault(
                                        rs.getString("turn_id"), List.of()))
                                : List.of(),
                        List.of(), null), taskId));
        rows.sort(Comparator.comparing(BrainFeedRow::ts)
                .thenComparing(BrainFeedRow::id));
        return List.copyOf(rows);
    }

    private static List<ScrubberDash> scrubber(List<BrainFeedRow> rows)
    {
        List<ScrubberDash> result = new ArrayList<>(rows.size());
        for (int index = 0; index < rows.size(); index++) {
            BrainFeedRow event = rows.get(index);
            result.add(new ScrubberDash(
                    event.id(), event.ts(), index == rows.size() - 1));
        }
        return List.copyOf(result);
    }

    private TaskBrainViewData.CostBreakdown costBreakdown(
            String taskId, long totalMilli, int pushes)
    {
        List<TaskBrainViewData.StageCost> perStage = jdbc.query("""
                SELECT owner.id, owner.kind,
                       COALESCE(SUM(execution.cost_usd_milli), 0) AS cost_milli
                FROM stage owner
                LEFT JOIN dispatch_ticket ticket ON ticket.stage_id = owner.id
                LEFT JOIN agent_execution execution ON execution.ticket_id = ticket.id
                WHERE owner.task_id = ?
                GROUP BY owner.id, owner.kind, owner.opened_at_ms
                HAVING COALESCE(SUM(execution.cost_usd_milli), 0) > 0
                ORDER BY owner.opened_at_ms
                """, (rs, ignored) -> new TaskBrainViewData.StageCost(
                        rs.getString("id"), legacyStageType(rs.getString("kind")),
                        rs.getLong("cost_milli") / 10), taskId);
        List<TaskBrainViewData.AgentCost> perAgent = jdbc.query("""
                SELECT COALESCE(execution.provider, 'agent') AS agent,
                       SUM(execution.cost_usd_milli) AS cost_milli
                FROM agent_execution execution
                JOIN dispatch_ticket ticket ON ticket.id = execution.ticket_id
                WHERE ticket.task_id = ?
                GROUP BY COALESCE(execution.provider, 'agent')
                HAVING SUM(execution.cost_usd_milli) > 0
                ORDER BY agent
                """, (rs, ignored) -> new TaskBrainViewData.AgentCost(
                        rs.getString("agent"), rs.getLong("cost_milli") / 10), taskId);
        long cents = totalMilli / 10;
        return new TaskBrainViewData.CostBreakdown(
                cents, perStage, perAgent, pushes == 0 ? null : cents / pushes);
    }

    private TaskBrainViewData.AutoPushBudget autoPushBudget(String taskId)
    {
        return jdbc.query("""
                SELECT push_count, push_limit
                FROM ci_repair_episode
                WHERE task_id = ?
                ORDER BY opened_at_ms DESC, id DESC
                LIMIT 1
                """, (rs, ignored) -> new TaskBrainViewData.AutoPushBudget(
                        rs.getInt("push_count"), rs.getInt("push_limit")), taskId)
                .stream().findFirst().orElse(null);
    }

    private ApprovalDto approval(String taskId)
    {
        return jdbc.query("""
                SELECT id, stage_id, blocker_type
                FROM task_blocker
                WHERE task_id = ? AND status = 'OPEN'
                ORDER BY opened_at_ms DESC, id DESC
                LIMIT 1
                """, (rs, ignored) -> new ApprovalDto(
                        "ask", rs.getString("stage_id"), "Task needs attention",
                        rs.getString("blocker_type").replace('_', ' ').toLowerCase(Locale.ROOT),
                        rs.getString("id"), null), taskId)
                .stream().findFirst().orElse(null);
    }

    /** Latest immutable V2 Plan adapted to the existing Markdown plan card. */
    private TaskBrainViewData.PlanCard planCard(String taskId)
    {
        PlanCardRow row = jdbc.query("""
                SELECT owner.id AS stage_id, owner.checkpoint,
                       revision.id AS revision_id, revision.revision,
                       revision.content, revision.source,
                       (SELECT COUNT(*) FROM plan_revision counted
                        WHERE counted.plan_stage_id = owner.id) AS revision_count,
                       review.status AS review_status,
                       review.verdict AS review_verdict,
                       approval.id AS approval_id
                FROM stage owner
                JOIN plan_stage plan ON plan.stage_id = owner.id
                LEFT JOIN plan_revision revision
                  ON revision.plan_stage_id = owner.id
                 AND NOT EXISTS (
                     SELECT 1 FROM plan_revision newer
                     WHERE newer.plan_stage_id = owner.id
                       AND newer.revision > revision.revision)
                LEFT JOIN plan_self_review review
                  ON review.plan_revision_id = revision.id
                 AND review.reviewed_digest = revision.content_digest
                LEFT JOIN plan_approval approval
                  ON approval.plan_revision_id = revision.id
                 AND approval.self_review_id = review.id
                WHERE owner.task_id = ? AND owner.kind = 'PLAN'
                ORDER BY owner.generation DESC, owner.opened_at_ms DESC, owner.id DESC
                LIMIT 1
                """, (rs, ignored) -> new PlanCardRow(
                        rs.getString("stage_id"), rs.getString("checkpoint"),
                        rs.getString("revision_id"), rs.getInt("revision"),
                        rs.getString("content"), rs.getString("source"),
                        rs.getInt("revision_count"),
                        rs.getString("review_status"),
                        rs.getString("review_verdict"),
                        rs.getString("approval_id")), taskId)
                .stream().findFirst().orElse(null);
        if (row == null) {
            return null;
        }

        MarkdownPlan content = planContent(first(row.content(), ""));
        String state = row.approvalId() != null ? "locked"
                : "SUCCEEDED".equals(row.reviewStatus())
                        && "CHANGES_REQUESTED".equals(row.reviewVerdict())
                        ? "revision_required"
                : "AWAITING_APPROVAL".equals(row.checkpoint())
                        && "SUCCEEDED".equals(row.reviewStatus())
                        && "APPROVED".equals(row.reviewVerdict())
                        ? "awaiting"
                : "draft";
        return new TaskBrainViewData.PlanCard(
                row.stageId(), state,
                row.revisionId() == null ? "suggested" : "finalized",
                planSource(row.source(), row.revision()),
                content.goal(), content.understanding(), content.action(),
                content.steps(), content.validation(), "await_approval",
                new TaskBrainViewData.PlanSignals(
                        content.risk(), content.effort(),
                        content.steps().size(), "", content.confidence()),
                row.revisionCount(), planFollowups(row.revisionId()),
                content.guardrails(), null);
    }

    private List<TaskBrainViewData.PlanFollowup> planFollowups(String revisionId)
    {
        if (revisionId == null) {
            return List.of();
        }
        return jdbc.query("""
                SELECT id, description, created_by, created_at_ms, status
                FROM plan_followup
                WHERE plan_revision_id = ? AND kind = 'FOLLOW_UP'
                ORDER BY created_at_ms, id
                """, (rs, ignored) -> new TaskBrainViewData.PlanFollowup(
                        rs.getString("id"), rs.getString("description"),
                        rs.getString("created_by"),
                        instant(rs.getLong("created_at_ms")).toString(),
                        switch (rs.getString("status")) {
                            case "RESOLVED" -> "addressed";
                            case "DEFERRED" -> "dismissed";
                            default -> "open";
                        }), revisionId);
    }

    private static MarkdownPlan planContent(String content)
    {
        try {
            JsonNode plan = JSON.readTree(content);
            if (plan != null && plan.isObject()) {
                return jsonPlan(plan);
            }
        }
        catch (JsonProcessingException ignored) {
            // Revisions recorded before the structured protocol used Markdown.
        }
        return markdownPlan(content);
    }

    private static MarkdownPlan jsonPlan(JsonNode plan)
    {
        JsonNode intent = plan.path("intent");
        String understanding = plan.path("understanding").path("summary")
                .asText("");
        String goal = firstNonBlank(plan.path("goal").asText(""), understanding);
        String action = intent.path("summary").asText("");
        List<String> expectedFiles = textList(
                intent.path("expectedFilesChanged"));
        JsonNode stepNodes = intent.path("steps");
        List<TaskBrainViewData.PlanStep> steps = new ArrayList<>();
        if (stepNodes.isArray()) {
            int index = 0;
            for (JsonNode step : stepNodes) {
                index++;
                String stepAction = step.isTextual()
                        ? step.asText("")
                        : firstNonBlank(
                                step.path("action").asText(""),
                                step.path("step").asText(""),
                                step.path("description").asText(""),
                                step.path("text").asText(""),
                                step.path("summary").asText(""));
                if (stepAction.isBlank()) {
                    continue;
                }
                stepAction = stepAction.replaceFirst("^\\s*\\d+[.)]\\s+", "");
                List<String> files = new ArrayList<>();
                String file = step.path("file").asText("").strip();
                if (!file.isBlank()) {
                    files.add(file);
                }
                textList(step.path("files")).stream()
                        .filter(candidate -> !files.contains(candidate))
                        .forEach(files::add);
                if (files.isEmpty() && stepNodes.size() == 1) {
                    files.addAll(expectedFiles);
                }
                String detail = firstNonBlank(
                        step.path("rationale").asText(""),
                        step.path("detail").asText(""));
                String risk = step.path("risk").asText("");
                steps.add(new TaskBrainViewData.PlanStep(
                        step.path("order").asInt(
                                step.path("ordinal").asInt(index)),
                        stepAction,
                        List.copyOf(files),
                        detail.isBlank() ? null : detail,
                        risk.isBlank() ? null : risk));
            }
        }
        JsonNode signals = plan.path("signals");
        return new MarkdownPlan(
                goal, understanding, action, List.copyOf(steps),
                text(plan.path("intent").path("validationStrategy")),
                textList(plan.path("outOfScope")),
                signals.path("riskLevel").asText(""),
                signals.path("estimatedComplexity").asText(""),
                signals.path("confidence").asText(""));
    }

    /**
     * The headings the V2 Plan prompt asks for; not a general Markdown parser.
     * Work written under any other heading is invisible on the plan card, which
     * is why the draft prompt states the heading contract to the brain.
     */
    static MarkdownPlan markdownPlan(String markdown)
    {
        String action = firstLine(section(markdown, "change"));
        String goal = firstNonBlank(
                firstItem(section(markdown, "goal")),
                firstItem(section(markdown, "plan")),
                firstItem(section(markdown, "objective")),
                action);
        List<String> authored = listItems(section(markdown, "steps"));
        List<TaskBrainViewData.PlanStep> steps = new ArrayList<>();
        for (String item : authored) {
            steps.add(new TaskBrainViewData.PlanStep(
                    steps.size() + 1, item, List.of(), null, null));
        }
        if (steps.isEmpty()) {
            // No steps section — keep the single synthetic step revisions
            // recorded before the contract rendered, so they still read as a
            // plan rather than an empty, unapprovable card.
            List<String> files = action.contains("/") && !action.contains(" ")
                    ? List.of(action.replaceFirst(":\\d+$", "")) : List.of();
            String step = action.isBlank() ? goal
                    : files.isEmpty() ? action : "Update " + action;
            if (!step.isBlank()) {
                steps.add(new TaskBrainViewData.PlanStep(
                        1, step, files, markdown.isBlank() ? null : markdown, null));
            }
        }
        // A pre-contract Markdown revision never reported signals; say so
        // rather than inventing them.
        return new MarkdownPlan(
                goal, goal, action, List.copyOf(steps),
                String.join("\n", contentLines(section(markdown, "validation"))),
                listItems(section(markdown, "scope guardrails")),
                "", "", "");
    }

    private static String text(JsonNode node)
    {
        return node.isTextual() ? node.asText("")
                : String.join("\n", textList(node));
    }

    private static List<String> textList(JsonNode node)
    {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        for (JsonNode value : node) {
            String text = value.asText("").strip();
            if (!text.isBlank()) {
                values.add(text);
            }
        }
        return List.copyOf(values);
    }

    private static String firstNonBlank(String... values)
    {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "";
    }

    private static String section(String markdown, String heading)
    {
        StringBuilder found = new StringBuilder();
        boolean selected = false;
        for (String line : markdown.split("\\R")) {
            String text = line.strip();
            if (text.startsWith("## ")) {
                if (selected) {
                    break;
                }
                // endsWith as well as startsWith: the brain qualifies its
                // headings ("## Execution steps", "## What will change") at
                // least as often as it writes them bare.
                String title = text.substring(3).toLowerCase(Locale.ROOT);
                selected = title.startsWith(heading) || title.endsWith(heading);
            }
            else if (selected) {
                found.append(line).append('\n');
            }
            else if ("plan".equals(heading) && text.matches("(?i)^#\\s+plan\\b.*")) {
                found.append(text.replaceFirst("(?i)^#\\s+plan\\b[\\s:\\p{Pd}]*", ""))
                        .append('\n');
            }
        }
        return found.toString();
    }

    private static String firstLine(String section)
    {
        List<String> lines = contentLines(section);
        return lines.isEmpty() ? "" : lines.getFirst();
    }

    private static String firstItem(String section)
    {
        List<String> items = listItems(section);
        return items.isEmpty() ? "" : items.getFirst();
    }

    /**
     * The list items of a section, one entry per authored item. Unlike
     * {@link #contentLines} this re-joins continuation lines: the brain hard-wraps
     * its Markdown, and a wrapped step or guardrail is one item, not two.
     */
    private static List<String> listItems(String section)
    {
        List<String> items = new ArrayList<>();
        boolean fenced = false;
        for (String line : section.split("\\R")) {
            String text = line.strip();
            if (text.startsWith("```")) {
                fenced = !fenced;
            }
            else if (!fenced && !text.isBlank()) {
                boolean marked = text.matches("^(\\d+[.)]|[-*])\\s+\\S.*");
                String item = text.replaceFirst("^(\\d+[.)]|[-*])\\s+", "")
                        .replace("**", "");
                if (marked || items.isEmpty()) {
                    items.add(item);
                }
                else {
                    items.set(items.size() - 1, items.getLast() + " " + item);
                }
            }
        }
        return List.copyOf(items);
    }

    private static List<String> contentLines(String section)
    {
        List<String> lines = new ArrayList<>();
        boolean fenced = false;
        for (String line : section.split("\\R")) {
            String text = line.strip();
            if (text.startsWith("```")) {
                fenced = !fenced;
            }
            else if (!fenced && !text.isBlank()) {
                text = text.replaceFirst("^[-*]\\s+", "").replace("**", "");
                if (text.length() > 1 && text.startsWith("`")
                        && text.endsWith("`")) {
                    text = text.substring(1, text.length() - 1);
                }
                lines.add(text);
            }
        }
        return List.copyOf(lines);
    }

    private static String planSource(String source, int revision)
    {
        return "AGENT".equals(source)
                ? revision > 1 ? "brain-revision" : "brain"
                : first(source, "").toLowerCase(Locale.ROOT);
    }

    private record PlanCardRow(
            String stageId, String checkpoint, String revisionId, int revision,
            String content, String source, int revisionCount,
            String reviewStatus, String reviewVerdict, String approvalId) {}

    record MarkdownPlan(
            String goal, String understanding, String action,
            List<TaskBrainViewData.PlanStep> steps,
            String validation, List<String> guardrails,
            /** The planner's own risk / effort / confidence, or blank when the
             *  revision predates the structured contract. Blank renders as
             *  unknown; it must never be filled with a plausible default,
             *  because these are pills a human reads to decide go / no-go. */
            String risk, String effort, String confidence) {}

    /**
     * Projects only the exact recoverable Plan-draft failure. The terminal
     * Plan receipt is the durable predecessor fence; the retry command
     * revalidates the same facts inside the Task command transaction.
     */
    private TaskBrainViewData.RecoveryAction recovery(String taskId)
    {
        List<RecoveryCandidate> candidates =
                tableAvailable("stage_plan_terminal_result")
                ? jdbc.query("""
                SELECT owner.id AS stage_id, blocker.id AS blocker_id,
                       blocker.subject_revision, failed.id AS failed_turn_id
                FROM tasks task
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN stage owner
                  ON owner.id = current.stage_id
                 AND owner.generation = current.stage_generation
                JOIN stage_plan_terminal_result terminal
                  ON terminal.stage_id = owner.id
                 AND terminal.returned_stage_version = owner.version
                 AND terminal.cause = 'PLAN_DRAFT_FAILED'
                JOIN task_turn failed
                  ON failed.id = terminal.proof_id
                 AND failed.operation_id = terminal.subject_operation_id
                JOIN task_blocker blocker
                  ON blocker.task_id = task.id
                 AND blocker.stage_id = owner.id
                 AND blocker.owner_kind = 'STAGE'
                 AND blocker.owner_id = owner.id
                 AND blocker.blocker_type = 'OPERATION_FAILED'
                 AND blocker.status = 'OPEN'
                 AND (blocker.subject_revision IS NULL
                      OR blocker.subject_revision = failed.id)
                WHERE task.id = ?
                  AND task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'ACTIVE'
                  AND current.stage_generation = owner.generation
                  AND owner.kind = 'PLAN'
                  AND owner.checkpoint = 'DRAFTING'
                  AND owner.completed_at_ms IS NULL
                  AND failed.task_id = task.id
                  AND failed.task_epoch = task.epoch
                  AND failed.trigger_stage_id = owner.id
                  AND failed.trigger_stage_generation = owner.generation
                  AND failed.purpose = 'PLAN_DRAFT'
                  AND failed.status = 'FAILED'
                  AND NOT EXISTS (
                      SELECT 1 FROM task_turn live
                      WHERE live.trigger_stage_id = owner.id
                        AND live.purpose = 'PLAN_DRAFT'
                        AND live.status IN (
                            'REQUESTED','QUEUED','CLAIMED','RUNNING'))
                """, (rs, ignored) -> new RecoveryCandidate(
                        new TaskBrainViewData.RecoveryAction(
                                "RETRY_PLAN_DRAFT", rs.getString("stage_id"),
                                rs.getString("blocker_id"),
                                rs.getString("failed_turn_id")),
                        rs.getString("subject_revision")), taskId)
                : List.of();
        List<TaskBrainViewData.RecoveryAction> actions = new ArrayList<>(
                candidates.stream()
                .filter(candidate -> candidate.subjectRevision() == null
                        ? candidate.action().blockerId().equals(
                                PlanRuntimeCoordinator.id(
                                        "plan-turn-blocker",
                                candidate.action().failedTurnId()))
                        : candidate.subjectRevision().equals(
                                candidate.action().failedTurnId()))
                .map(RecoveryCandidate::action)
                .toList());
        if (tableAvailable("development_brain_protocol_failure_v300")) {
            actions.addAll(jdbc.query("""
                SELECT failure.stage_id, failure.blocker_id,
                       failure.task_turn_id AS failed_turn_id
                FROM development_brain_protocol_failure_v300 failure
                JOIN tasks task ON task.id = failure.task_id
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN stage owner ON owner.id = current.stage_id
                JOIN task_blocker blocker ON blocker.id = failure.blocker_id
                JOIN brain_review_episode episode
                  ON episode.id = failure.brain_review_episode_id
                JOIN task_turn failed ON failed.id = failure.task_turn_id
                JOIN task_current_code_subject_v230 code
                  ON code.task_id = task.id
                WHERE failure.task_id = ?
                  AND task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'ACTIVE'
                  AND task.epoch = failure.task_epoch
                  AND current.stage_id = failure.stage_id
                  AND current.stage_generation = failure.stage_generation
                  AND owner.kind = 'LOCAL_DEVELOPMENT'
                  AND owner.generation = failure.stage_generation
                  AND owner.version = failure.stage_version
                  AND owner.checkpoint = 'BRAIN_REVIEW'
                  AND owner.completed_at_ms IS NULL
                  AND blocker.task_id = task.id
                  AND blocker.stage_id IS NULL
                  AND blocker.owner_kind = 'TASK'
                  AND blocker.owner_id = task.id
                  AND blocker.subject_revision = failed.id
                  AND blocker.blocker_type = 'OPERATION_FAILED'
                  AND blocker.status = 'OPEN'
                  AND episode.status = 'FAILED'
                  AND failed.status = 'FAILED'
                  AND failed.purpose = 'DEVELOPMENT_BRAIN_REVIEW'
                  AND code.code_fingerprint = failure.code_fingerprint
                  AND code.head_sha = failure.head_sha
                  AND code.base_sha = failure.base_sha
                  AND NOT EXISTS (
                      SELECT 1 FROM development_brain_retry_v300 retry
                      WHERE retry.failure_id = failure.id
                         OR retry.blocker_id = blocker.id
                         OR retry.predecessor_turn_id = failed.id)
                  AND (
                      NOT EXISTS (
                          SELECT 1
                          FROM development_brain_retry_v300 prior_retry
                          WHERE prior_retry.replacement_episode_id = episode.id
                             OR prior_retry.replacement_turn_id =
                                failure.owner_turn_id
                             OR prior_retry.replacement_operation_id =
                                failure.owner_operation_id)
                      OR EXISTS (
                          SELECT 1
                          FROM development_brain_retry_v300 prior_retry
                          JOIN dispatch_ticket source_ticket
                            ON source_ticket.owner_kind = 'TASK_TURN'
                           AND source_ticket.owner_id = failed.id
                           AND source_ticket.operation_id = failed.operation_id
                          JOIN agent_execution execution
                            ON execution.ticket_id = source_ticket.id
                          WHERE prior_retry.replacement_episode_id = episode.id
                            AND prior_retry.replacement_turn_id =
                                failure.owner_turn_id
                            AND prior_retry.replacement_operation_id =
                                failure.owner_operation_id
                            AND execution.status = 'SUCCEEDED'
                            AND execution.infrastructure_attempt =
                                source_ticket.infrastructure_attempts
                            AND execution.raw_result IS NOT NULL
                            AND length(trim(COALESCE(json_extract(
                                json_extract(execution.raw_result,
                                    '$.payloadJson'), '$.finalText'), ''),
                                char(9) || char(10) || char(13) || ' ')) > 0))
                  AND NOT EXISTS (
                      SELECT 1 FROM development_brain_result_repair_v311 repair
                      WHERE repair.source_failure_id = failure.id
                         OR repair.source_task_turn_id = failed.id)
                """, (rs, ignored) -> new TaskBrainViewData.RecoveryAction(
                        "RETRY_DEVELOPMENT_BRAIN_REVIEW",
                        rs.getString("stage_id"), rs.getString("blocker_id"),
                        rs.getString("failed_turn_id")), taskId));
        }
        if (tableAvailable("remote_repair_brain_failure_receipt_v309")) {
            actions.addAll(jdbc.query("""
                WITH failed_operation AS (
                    SELECT 'CI' AS family, operation.task_id,
                           operation.task_epoch,
                           operation.remote_development_stage_id AS stage_id,
                           operation.stage_generation,
                           operation.ci_repair_episode_id AS episode_id,
                           operation.task_turn_id AS failed_turn_id,
                           operation.operation_id,
                           operation.expected_code_fingerprint,
                           operation.expected_head_sha,
                           operation.expected_base_sha,
                           operation.status, operation.semantic_attempt,
                           operation.semantic_attempt AS execution_attempt,
                           episode.status AS episode_status,
                           COALESCE(episode.last_pushed_head_sha,
                                    episode.subject_head_sha) AS remote_head_sha,
                           episode.subject_base_sha AS remote_base_sha,
                           NULL AS branch_step_status,
                           0 AS branch_step_attempt
                    FROM ci_repair_operation operation
                    JOIN ci_repair_delivery_receipt delivery
                     ON delivery.ci_repair_operation_id = operation.id
                     AND delivery.operation_id = operation.operation_id
                     AND delivery.acceptance = 'ACCEPTED'
                     AND delivery.raw_outcome IN ('FAILED', 'CANCELED')
                    JOIN ci_repair_episode episode
                      ON episode.id = operation.ci_repair_episode_id
                     AND episode.remote_development_stage_id =
                         operation.remote_development_stage_id
                     AND episode.task_id = operation.task_id
                     AND episode.task_epoch = operation.task_epoch
                     AND episode.stage_generation = operation.stage_generation
                     AND episode.status = 'AWAITING_PUSH_CI'
                    JOIN remote_development_stage remote
                      ON remote.stage_id = operation.remote_development_stage_id
                     AND remote.task_id = operation.task_id
                     AND remote.generation = operation.stage_generation
                     AND remote.current_head_sha = COALESCE(
                         episode.last_pushed_head_sha, episode.subject_head_sha)
                     AND remote.current_base_sha = episode.subject_base_sha
                    WHERE operation.kind = 'BRAIN_REVIEW'
                      AND operation.status IN ('FAILED', 'CANCELED')
                    UNION ALL
                    SELECT 'BRANCH', operation.task_id,
                           operation.task_epoch,
                           operation.remote_development_stage_id,
                           operation.stage_generation,
                           operation.branch_sync_episode_id,
                           operation.task_turn_id,
                           operation.operation_id,
                           operation.expected_code_fingerprint,
                           operation.expected_head_sha,
                           operation.expected_base_sha,
                           operation.status, operation.semantic_attempt,
                           operation.semantic_attempt AS execution_attempt,
                           episode.status, episode.old_head_sha,
                           episode.observed_base_sha, step.status,
                           step.attempt_count
                    FROM branch_sync_dispatch_operation operation
                    JOIN branch_sync_delivery_receipt delivery
                     ON delivery.branch_sync_dispatch_operation_id = operation.id
                     AND delivery.operation_id = operation.operation_id
                     AND delivery.acceptance = 'ACCEPTED'
                     AND delivery.raw_outcome IN ('FAILED', 'CANCELED')
                    JOIN branch_sync_episode episode
                      ON episode.id = operation.branch_sync_episode_id
                     AND episode.remote_development_stage_id =
                         operation.remote_development_stage_id
                     AND episode.task_id = operation.task_id
                     AND episode.task_epoch = operation.task_epoch
                     AND episode.stage_generation = operation.stage_generation
                     AND episode.status = 'BRAIN_REVIEW'
                    JOIN branch_sync_effect_step step
                      ON step.id = operation.branch_sync_effect_step_id
                     AND step.branch_sync_episode_id = episode.id
                     AND step.kind = 'BRAIN_REVIEW'
                     AND step.status = 'FAILED'
                     AND step.attempt_count = operation.semantic_attempt
                    JOIN remote_development_stage remote
                      ON remote.stage_id = operation.remote_development_stage_id
                     AND remote.task_id = operation.task_id
                     AND remote.generation = operation.stage_generation
                     AND remote.current_head_sha = episode.old_head_sha
                     AND remote.current_base_sha = episode.observed_base_sha
                    WHERE operation.kind = 'BRAIN_REVIEW'
                      AND operation.status IN ('FAILED', 'CANCELED')
                    UNION ALL
                    SELECT 'CI', operation.task_id,
                           operation.task_epoch,
                           operation.remote_development_stage_id,
                           operation.stage_generation,
                           operation.ci_repair_episode_id,
                           operation.task_turn_id, operation.operation_id,
                           operation.expected_code_fingerprint,
                           operation.expected_head_sha,
                           operation.expected_base_sha, operation.status,
                           operation.semantic_attempt,
                           operation.execution_attempt, episode.status,
                           COALESCE(episode.last_pushed_head_sha,
                                    episode.subject_head_sha),
                           episode.subject_base_sha, NULL, 0
                    FROM remote_repair_brain_replacement_operation_v309 operation
                    JOIN remote_repair_brain_replacement_delivery_v309 delivery
                      ON delivery.replacement_operation_id = operation.id
                     AND delivery.operation_id = operation.operation_id
                     AND delivery.acceptance = 'ACCEPTED'
                     AND delivery.raw_outcome IN ('FAILED', 'CANCELED')
                    JOIN ci_repair_episode episode
                      ON episode.id = operation.ci_repair_episode_id
                     AND episode.remote_development_stage_id =
                         operation.remote_development_stage_id
                     AND episode.task_id = operation.task_id
                     AND episode.task_epoch = operation.task_epoch
                     AND episode.stage_generation = operation.stage_generation
                     AND episode.status = 'AWAITING_PUSH_CI'
                    JOIN remote_development_stage remote
                      ON remote.stage_id = operation.remote_development_stage_id
                     AND remote.task_id = operation.task_id
                     AND remote.generation = operation.stage_generation
                     AND remote.current_head_sha = COALESCE(
                         episode.last_pushed_head_sha, episode.subject_head_sha)
                     AND remote.current_base_sha = episode.subject_base_sha
                    WHERE operation.family = 'CI'
                      AND operation.status IN ('FAILED', 'CANCELED')
                    UNION ALL
                    SELECT 'BRANCH', operation.task_id,
                           operation.task_epoch,
                           operation.remote_development_stage_id,
                           operation.stage_generation,
                           operation.branch_sync_episode_id,
                           operation.task_turn_id, operation.operation_id,
                           operation.expected_code_fingerprint,
                           operation.expected_head_sha,
                           operation.expected_base_sha, operation.status,
                           operation.semantic_attempt,
                           operation.execution_attempt, episode.status,
                           episode.old_head_sha, episode.observed_base_sha,
                           step.status, step.attempt_count
                    FROM remote_repair_brain_replacement_operation_v309 operation
                    JOIN remote_repair_brain_replacement_delivery_v309 delivery
                      ON delivery.replacement_operation_id = operation.id
                     AND delivery.operation_id = operation.operation_id
                     AND delivery.acceptance = 'ACCEPTED'
                     AND delivery.raw_outcome IN ('FAILED', 'CANCELED')
                    JOIN branch_sync_episode episode
                      ON episode.id = operation.branch_sync_episode_id
                     AND episode.remote_development_stage_id =
                         operation.remote_development_stage_id
                     AND episode.task_id = operation.task_id
                     AND episode.task_epoch = operation.task_epoch
                     AND episode.stage_generation = operation.stage_generation
                     AND episode.status = 'BRAIN_REVIEW'
                    JOIN branch_sync_effect_step step
                      ON step.id = operation.branch_sync_effect_step_id
                     AND step.branch_sync_episode_id = episode.id
                     AND step.kind = 'BRAIN_REVIEW'
                     AND step.status = 'FAILED'
                     AND step.attempt_count = operation.semantic_attempt
                    JOIN remote_development_stage remote
                      ON remote.stage_id = operation.remote_development_stage_id
                     AND remote.task_id = operation.task_id
                     AND remote.generation = operation.stage_generation
                     AND remote.current_head_sha = episode.old_head_sha
                     AND remote.current_base_sha = episode.observed_base_sha
                    WHERE operation.family = 'BRANCH'
                      AND operation.status IN ('FAILED', 'CANCELED')
                )
                SELECT operation.stage_id, blocker.id AS blocker_id,
                       failed.id AS failed_turn_id
                FROM failed_operation operation
                JOIN tasks task ON task.id = operation.task_id
                JOIN task_applied_protocol_snapshot_v309 current_task
                  ON current_task.task_id = task.id
                 AND current_task.returned_version = (
                     SELECT MAX(latest.returned_version)
                     FROM task_applied_protocol_snapshot_v309 latest
                     WHERE latest.task_id = task.id
                       AND latest.returned_version <= task.aggregate_version)
                JOIN task_current_stage current ON current.task_id = task.id
                JOIN stage owner ON owner.id = current.stage_id
                JOIN remote_development_stage remote
                  ON remote.stage_id = operation.stage_id
                JOIN task_current_code_subject_v230 code
                  ON code.task_id = task.id
                JOIN task_turn failed
                  ON failed.id = operation.failed_turn_id
                 AND failed.operation_id = operation.operation_id
                JOIN task_blocker blocker
                  ON blocker.task_id = task.id
                 AND blocker.stage_id IS NULL
                 AND blocker.owner_kind = 'TASK'
                 AND blocker.owner_id = task.id
                 AND blocker.subject_revision = failed.id
                 AND blocker.blocker_type = 'REMOTE_REPAIR_BRAIN_FAILED'
                 AND blocker.status = 'OPEN'
                JOIN remote_repair_brain_failure_receipt_v309 failure
                  ON failure.family = operation.family
                 AND failure.task_id = task.id
                 AND failure.task_epoch = operation.task_epoch
                 AND failure.remote_development_stage_id = operation.stage_id
                 AND failure.stage_generation = operation.stage_generation
                 AND failure.task_turn_id = failed.id
                 AND failure.operation_id = operation.operation_id
                 AND failure.semantic_attempt = operation.semantic_attempt
                 AND failure.execution_attempt = operation.execution_attempt
                 AND failure.expected_code_fingerprint =
                     operation.expected_code_fingerprint
                 AND failure.expected_head_sha = operation.expected_head_sha
                 AND failure.expected_base_sha = operation.expected_base_sha
                 AND failure.blocker_id = blocker.id
                 AND failure.raw_outcome = operation.status
                JOIN task_brain_protocol_failure_receipt_v300 receipt
                  ON receipt.task_id = task.id
                 AND receipt.proof_id = blocker.id
                 AND receipt.subject_task_epoch = operation.task_epoch
                 AND receipt.subject_stage_id = operation.stage_id
                 AND receipt.subject_stage_generation =
                     operation.stage_generation
                 AND receipt.subject_operation_id = operation.operation_id
                 AND receipt.subject_attempt = operation.execution_attempt
                 AND receipt.subject_expected_code_fingerprint =
                     operation.expected_code_fingerprint
                 AND receipt.subject_expected_head_sha =
                     operation.expected_head_sha
                 AND receipt.subject_expected_base_sha =
                     operation.expected_base_sha
                 AND receipt.returned_trunk_id = task.thread_id
                 AND receipt.returned_lifecycle = 'ACTIVE'
                 AND receipt.returned_epoch = task.epoch
                 AND receipt.returned_version = failure.cleared_task_version
                 AND receipt.returned_current_stage_id = current.stage_id
                 AND receipt.returned_pending_operation_id IS NULL
                WHERE task.id = ?
                  AND operation.family = 'BRANCH'
                  AND task.workflow_version = 'V2'
                  AND task.lifecycle_state = 'ACTIVE'
                  AND task.epoch = operation.task_epoch
                  AND task.aggregate_version >= failure.cleared_task_version
                  AND current_task.returned_pending_operation_id IS NULL
                  AND current.stage_id = operation.stage_id
                  AND current.stage_generation = operation.stage_generation
                  AND owner.kind = 'REMOTE_DEVELOPMENT'
                  AND owner.generation = operation.stage_generation
                  AND owner.completed_at_ms IS NULL
                  AND remote.generation = operation.stage_generation
                  AND remote.current_head_sha = operation.remote_head_sha
                  AND remote.current_base_sha = operation.remote_base_sha
                  AND code.code_fingerprint IS
                      operation.expected_code_fingerprint
                  AND code.head_sha = operation.expected_head_sha
                  AND code.base_sha = operation.expected_base_sha
                  AND ((operation.family = 'CI'
                        AND operation.episode_status = 'AWAITING_PUSH_CI'
                        AND failed.purpose = 'REMOTE_CI_BRAIN_REVIEW')
                    OR (operation.family = 'BRANCH'
                        AND operation.episode_status = 'BRAIN_REVIEW'
                        AND operation.branch_step_status = 'FAILED'
                        AND operation.branch_step_attempt =
                            operation.semantic_attempt
                        AND failed.purpose = 'BRANCH_SYNC_BRAIN_REVIEW'))
                  AND failed.task_id = task.id
                  AND failed.task_epoch = task.epoch
                  AND failed.trigger_stage_id = operation.stage_id
                  AND failed.trigger_stage_generation =
                      operation.stage_generation
                  AND failed.attempt = operation.execution_attempt
                  AND failed.status IN ('FAILED', 'CANCELED')
                  AND failed.expected_code_fingerprint IS
                      operation.expected_code_fingerprint
                  AND failed.expected_head_sha = operation.expected_head_sha
                  AND failed.expected_base_sha = operation.expected_base_sha
                  AND NOT EXISTS (
                      SELECT 1
                      FROM remote_repair_brain_retry_command_v309 retry
                      WHERE retry.task_id = task.id
                        AND (retry.blocker_id = blocker.id
                             OR retry.failed_turn_id = failed.id))
                  AND NOT EXISTS (
                      SELECT 1 FROM task_turn live
                      WHERE live.task_id = task.id
                        AND live.trigger_stage_id = operation.stage_id
                        AND live.trigger_stage_generation =
                            operation.stage_generation
                        AND live.purpose IN (
                            'REMOTE_CI_BRAIN_REVIEW',
                            'BRANCH_SYNC_BRAIN_REVIEW')
                        AND live.status IN (
                            'REQUESTED','QUEUED','CLAIMED','RUNNING'))
                """, (rs, ignored) -> new TaskBrainViewData.RecoveryAction(
                        "RETRY_REMOTE_REPAIR_BRAIN_REVIEW",
                        rs.getString("stage_id"), rs.getString("blocker_id"),
                        rs.getString("failed_turn_id")), taskId));
        }
        return actions.size() == 1 ? actions.getFirst() : null;
    }

    private boolean tableAvailable(String table)
    {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*) FROM sqlite_master
                WHERE type = 'table' AND name = ?
                """, Integer.class, table);
        return count != null && count == 1;
    }

    private record RecoveryCandidate(
            TaskBrainViewData.RecoveryAction action, String subjectRevision) {}

    private List<TaskBrainViewData.DevPhase> devPhases(Projection row)
    {
        if (!"LOCAL_DEVELOPMENT".equals(row.stageKind())) {
            return List.of();
        }
        LocalFacts facts = jdbc.query("""
                SELECT
                    (SELECT status FROM validation_operation operation
                     WHERE operation.local_development_stage_id = ?
                     ORDER BY operation.requested_at_ms DESC, operation.id DESC
                     LIMIT 1) AS validation_status,
                    (SELECT evidence.passed FROM validation_evidence evidence
                     WHERE evidence.stage_id = ?
                     ORDER BY evidence.completed_at_ms DESC, evidence.id DESC
                     LIMIT 1) AS validation_passed,
                    (SELECT status FROM brain_review_episode review
                     WHERE review.local_development_stage_id = ?
                     ORDER BY review.requested_at_ms DESC, review.id DESC
                     LIMIT 1) AS review_status,
                    (SELECT verdict FROM brain_review_episode review
                     WHERE review.local_development_stage_id = ?
                     ORDER BY review.requested_at_ms DESC, review.id DESC
                     LIMIT 1) AS review_verdict,
                    (SELECT unresolved_finding_count FROM brain_review_episode review
                     WHERE review.local_development_stage_id = ?
                     ORDER BY review.requested_at_ms DESC, review.id DESC
                     LIMIT 1) AS unresolved_findings
                """, (rs, ignored) -> new LocalFacts(
                        rs.getString("validation_status"),
                        nullableInt(rs, "validation_passed"),
                        rs.getString("review_status"), rs.getString("review_verdict"),
                        nullableInt(rs, "unresolved_findings")),
                row.currentStageId(), row.currentStageId(), row.currentStageId(),
                row.currentStageId(), row.currentStageId()).stream()
                .findFirst().orElse(new LocalFacts(null, null, null, null, null));
        String checkpoint = row.checkpoint();
        boolean pastImplementing = !"IMPLEMENTING".equals(checkpoint);
        boolean validationRunning = "REQUESTED".equals(facts.validationStatus())
                || "DISPATCHED".equals(facts.validationStatus());
        boolean validationDone = facts.validationPassed() != null
                || List.of("BRAIN_REVIEW", "LOCAL_REVIEW", "PUBLISHING",
                        "ADDRESSING_LOCAL_FEEDBACK", "COMPLETED").contains(checkpoint);
        String reviewStatus;
        String reviewMeta;
        if ("REQUESTED".equals(facts.reviewStatus())
                || "REVIEWING".equals(facts.reviewStatus())) {
            reviewStatus = "running";
            reviewMeta = "brain reviewing";
        }
        else if ("APPROVED".equals(facts.reviewVerdict())) {
            reviewStatus = "done";
            reviewMeta = "brain approved";
        }
        else if (facts.reviewStatus() != null) {
            reviewStatus = "done";
            reviewMeta = "brain unresolved"
                    + (facts.unresolvedFindings() == null || facts.unresolvedFindings() == 0
                        ? "" : " · " + facts.unresolvedFindings());
        }
        else {
            reviewStatus = "BRAIN_REVIEW".equals(checkpoint) ? "running" : "future";
            reviewMeta = "future".equals(reviewStatus) ? "next" : "brain reviewing";
        }
        return List.of(
                new TaskBrainViewData.DevPhase(
                        "implementing", pastImplementing ? "done" : "running", null, null),
                new TaskBrainViewData.DevPhase(
                        "validation", validationDone ? "done"
                                : validationRunning || "VALIDATING".equals(checkpoint)
                                        ? "running" : "future",
                        facts.validationPassed() == null ? null
                                : facts.validationPassed() == 1 ? "passed" : "failed",
                        null),
                new TaskBrainViewData.DevPhase(
                        "brainReview", reviewStatus, reviewMeta, null));
    }

    private static LinkedPrDto linkedPr(Projection row)
    {
        if (row.remotePrNumber() == null) {
            return null;
        }
        return new LinkedPrDto(
                row.remotePrNumber(), first(row.branchName(), ""),
                legacyPrStatus(row.prState()), legacyCiStatus(ciState(row)), "",
                row.approvalCount(), row.approvalCount() + row.requestedReviewerCount(),
                switch (first(row.mergeability(), "UNKNOWN")) {
                    case "MERGEABLE" -> "none";
                    case "CONFLICTING" -> "has_conflicts";
                    default -> "unknown";
                },
                "MERGEABLE".equals(row.mergeability()));
    }

    private LinkedActivePr linkedActivePr(Projection row)
    {
        TaskPhase current = phase(row);
        if (row.remotePrNumber() == null
                || !Set.of(TaskPhase.PUSHED_AWAITING_CI, TaskPhase.AWAITING_READY,
                        TaskPhase.AWAITING_REMOTE_REVIEW).contains(current)) {
            return null;
        }
        List<String> reviewers = jdbc.queryForList("""
                SELECT DISTINCT inbox.requested_reviewer
                FROM remote_inbox_item inbox
                JOIN remote_development_stage remote
                  ON remote.stage_id = inbox.remote_development_stage_id
                WHERE inbox.task_id = ? AND inbox.kind = 'REQUESTED_REVIEW'
                  AND inbox.ignored = 0
                  AND (remote.accepted_snapshot_id IS NULL
                    OR inbox.remote_pr_snapshot_id = remote.accepted_snapshot_id)
                ORDER BY inbox.requested_reviewer
                """, String.class, row.taskId());
        return new LinkedActivePr(
                row.remotePrNumber(), first(ciState(row), "NONE"),
                isDraft(row.prState()), row.approvalCount(),
                row.changesRequestedCount(), row.requestedReviewerCount(), reviewers);
    }

    private static Optional<PhaseFact> phaseFact(ResultSet rs)
            throws SQLException
    {
        String source = rs.getString("source");
        String checkpoint = rs.getString("checkpoint");
        TaskPhase phase = switch (source) {
            case "BLOCKER" -> "OPEN".equals(checkpoint)
                    ? TaskPhase.NEEDS_ATTENTION : null;
            case "TASK" -> TERMINAL_LIFECYCLES.contains(checkpoint)
                    ? TaskPhase.COMPLETED : null;
            case "STAGE" -> phase(rs.getString("kind"), checkpoint);
            default -> throw new IllegalArgumentException("Unknown V2 trace fact: " + source);
        };
        return phase == null ? Optional.empty() : Optional.of(new PhaseFact(
                phase, rs.getString("actor"), rs.getString("reason"),
                instant(rs.getLong("occurred_at_ms"))));
    }

    private static TaskPhase phase(String kind, String checkpoint)
    {
        if (kind == null || checkpoint == null || "COMPLETED".equals(checkpoint)) {
            return "CLEANUP".equals(kind) && "COMPLETED".equals(checkpoint)
                    ? TaskPhase.COMPLETED : null;
        }
        if ("PLAN".equals(kind)) {
            return TaskPhase.PLANNING;
        }
        if ("REMOTE_DEVELOPMENT".equals(kind)) {
            return switch (checkpoint) {
                case "WAITING_CI", "ADDRESSING_REMOTE_FEEDBACK" ->
                        TaskPhase.PUSHED_AWAITING_CI;
                case "AWAITING_READY" -> TaskPhase.AWAITING_READY;
                default -> TaskPhase.AWAITING_REMOTE_REVIEW;
            };
        }
        if ("CLEANUP".equals(kind)) {
            return TaskPhase.COMPLETED;
        }
        return switch (checkpoint) {
            case "VALIDATING" -> TaskPhase.VALIDATING;
            case "BRAIN_REVIEW" -> TaskPhase.INTERNAL_REVIEW;
            case "LOCAL_REVIEW", "PUBLISHING" -> TaskPhase.AWAITING_PUSH;
            case "ADDRESSING_LOCAL_FEEDBACK" -> TaskPhase.ADDRESSING_LOCAL_COMMENTS;
            default -> TaskPhase.IMPLEMENTING;
        };
    }

    private static String statusLabel(Task task, Projection row)
    {
        if (TERMINAL_LIFECYCLES.contains(row.lifecycle())) {
            return "CANCELED".equals(row.lifecycle()) ? "CANCELLED"
                    : row.lifecycle().replace('_', ' ');
        }
        if (row.blockerCount() > 0) {
            return "NEEDS ATTENTION";
        }
        return task.phase().name().replace('_', ' ');
    }

    private static String contextBand(long tokens)
    {
        if (tokens >= CONTEXT_TOKEN_LIMIT * 9L / 10L) {
            return "danger";
        }
        if (tokens >= CONTEXT_TOKEN_LIMIT * 3L / 4L) {
            return "warn";
        }
        return "safe";
    }

    private static boolean isDraft(String state)
    {
        return state != null && state.toUpperCase(Locale.ROOT).contains("DRAFT");
    }

    private static String legacyPrStatus(String state)
    {
        if (state == null) {
            return "open";
        }
        return switch (state.toUpperCase(Locale.ROOT)) {
            case "DRAFT", "LOCAL-DRAFTED", "REMOTE-DRAFTED" -> "draft";
            case "MERGED" -> "merged";
            case "CLOSED" -> "closed";
            default -> "open";
        };
    }

    private static String legacyCiStatus(String state)
    {
        return switch (first(state, "UNKNOWN")) {
            case "PASSING" -> "green";
            case "FAILING" -> "failing";
            case "PENDING" -> "pending";
            default -> "unknown";
        };
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
                rs.getString("task_id"), rs.getString("lifecycle_state"),
                rs.getLong("task_created_at_ms"),
                rs.getString("current_stage_id"),
                nullableInt(rs, "current_stage_generation"),
                rs.getString("owner_task_id"), nullableInt(rs, "owner_generation"),
                nullableLong(rs, "owner_opened_at_ms"),
                nullableLong(rs, "owner_completed_at_ms"),
                rs.getString("kind"),
                rs.getString("checkpoint"), rs.getString("branch_name"),
                rs.getString("worktree_path"), rs.getString("base_ref"),
                rs.getString("repository_id"),
                rs.getString("agent_runtime"), rs.getString("agent_model"),
                nullableInt(rs, "remote_pr_number"),
                nullableLong(rs, "bound_at_ms"), rs.getString("pr_state"),
                rs.getString("mergeability"),
                rs.getInt("effective_approval_count"),
                rs.getInt("changes_requested_count"),
                rs.getInt("requested_reviewer_count"),
                rs.getString("ci_state"), rs.getString("ci_outcome"),
                nullableLong(rs, "recorded_at_ms"),
                rs.getInt("open_stage_count"), rs.getInt("current_stage_count"),
                rs.getInt("outcome_count"),
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
            String taskId,
            String lifecycle,
            long taskCreatedAtMs,
            String currentStageId,
            Integer currentStageGeneration,
            String ownerTaskId,
            Integer ownerGeneration,
            Long ownerOpenedAtMs,
            Long ownerCompletedAtMs,
            String stageKind,
            String checkpoint,
            String branchName,
            String worktreePath,
            String baseRef,
            String repositoryId,
            String agentRuntime,
            String agentModel,
            Integer remotePrNumber,
            Long boundAtMs,
            String prState,
            String mergeability,
            int approvalCount,
            int changesRequestedCount,
            int requestedReviewerCount,
            String ciState,
            String ciOutcome,
            Long recordedAtMs,
            int openStageCount,
            int currentStageCount,
            int outcomeCount,
            int blockerCount,
            int runningCount,
            int queuedCount,
            long costUsdMilli,
            long tokensIn,
            long tokensOut) {}

    private record BrainAggregate(
            int pushes, long activeTimeMs, int turns, int messages,
            long costUsdMilli) {}

    private record AttachmentRef(String turnId, String contentRef) {}

    private record LocalFacts(
            String validationStatus,
            Integer validationPassed,
            String reviewStatus,
            String reviewVerdict,
            Integer unresolvedFindings) {}

    public record PhaseFact(
            TaskPhase phase, String actor, String reason, Instant occurredAt) {}

    public record TraceFacts(
            Task task, List<PhaseFact> events, LinkedActivePr linkedActivePr) {}
}
