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
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
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
        List<ScrubberDash> scrubber = new ArrayList<>(feed.size());
        for (int index = 0; index < feed.size(); index++) {
            BrainFeedRow event = feed.get(index);
            scrubber.add(new ScrubberDash(
                    event.id(), event.ts(), index == feed.size() - 1));
        }

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
                        0, 0, aggregate.turns(), 0, 0,
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
                        null),
                new TaskBrainViewData.Scrubbers(List.copyOf(scrubber), List.of()),
                List.of(),
                branchGuards.project(task.id()),
                null,
                devPhases(row));
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
                       context.base_ref, context.repository_id,
                       CASE WHEN json_valid(context.work_model_snapshot)
                            THEN json_extract(context.work_model_snapshot, '$.kind')
                            ELSE NULL END AS agent_runtime,
                       brain.model AS agent_model,
                       binding.remote_pr_number,
                       binding.bound_at_ms,
                       COALESCE(snapshot.pr_state, pr.status) AS pr_state,
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
                LEFT JOIN task_brain brain ON brain.task_id = task.id
                LEFT JOIN pr ON pr.task_id = task.id AND pr.origin = 'task'
                LEFT JOIN remote_pr_binding binding ON binding.task_id = task.id
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
                    COALESCE((SELECT SUM(execution.cost_usd_milli)
                        FROM agent_execution execution
                        JOIN dispatch_ticket ticket ON ticket.id = execution.ticket_id
                        WHERE ticket.task_id = ?), 0) AS cost_usd_milli
                """, (rs, ignored) -> new BrainAggregate(
                        rs.getInt("pushes"), rs.getLong("active_time_ms"),
                        rs.getInt("turns"), rs.getLong("cost_usd_milli")),
                taskId, taskId, taskId, taskId, taskId, taskId, taskId);
    }

    private List<BrainFeedRow> brainFeed(String taskId)
    {
        return jdbc.query("""
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
                }, taskId);
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
            int pushes, long activeTimeMs, int turns, long costUsdMilli) {}

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
