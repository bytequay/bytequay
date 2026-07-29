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
package com.bytequay.app.service.review;

import com.bytequay.app.domain.Actor;
import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRComment;
import com.bytequay.app.domain.PRTimelineEntry;
import com.bytequay.app.domain.ReviewRound;
import com.bytequay.app.domain.StageEvent;
import com.bytequay.app.domain.StageEventType;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageState;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ThreadTurnStatus;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.domain.TurnLiveness;
import com.bytequay.app.repository.ReviewRoundStore;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.service.checks.ValidationClaimService;
import com.bytequay.app.service.checks.ValidationPassService;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.runs.AgentRunService;
import com.bytequay.app.service.stage.StageStateMachine;
import com.bytequay.app.service.threads.NotificationService;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.bytequay.app.service.threads.TaskPhaseMachine;
import com.bytequay.app.service.threads.TaskTurnBudgetPausedEvent;
import com.bytequay.app.service.threads.TaskTurnFinishedEvent;
import com.bytequay.app.service.threads.TaskTurnStatusChanged;
import com.bytequay.app.service.threads.ThreadTurnScheduler;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * Brain-driven adversarial review (plan-rail-runs.md R20-R24). Two
 * independent lock points, both system-triggered off {@link
 * TaskTurnFinishedEvent} (never reliant on the agent remembering, per
 * C8.3's philosophy):
 *
 * <p><b>Plan self-review (R20)</b> — after a finalized plan is recorded on
 * an open PlanStage and no {@link StageEventType#PLAN_SELF_REVIEWED} event
 * exists yet, one turn runs on the task's brain thread: critique the plan,
 * optionally revise it via {@code record_plan}, then call {@code
 * record_review_verdict(scope=plan)}. Exactly one round — no loop. Once it
 * (or a prior attempt) has run, the low-risk/low-effort auto-approve check
 * evaluates the plan as it now stands — moved here from {@code
 * PlanToolHandlers.recordPlan} so it evaluates the POST-self-review plan.
 *
 * <p><b>Code lock-point review (R21-R23)</b> — reuses {@link ReviewRound}
 * with {@code origin=brain} for the dev-end lock point ({@link
 * #reviewBeforeLocalOpen}), or a brain verification sub-pass tacked onto an
 * {@code origin=external} round before its gate arms ({@link
 * #reviewBeforeRoundGate}). Both drive the same review-fix-review loop:
 * a review turn on the brain thread (status {@code triaging}) leaves
 * {@code pr_comment} rows and a verdict; if {@code
 * changes_requested} and budget remains, a fix turn on the task's own
 * thread (status {@code addressing}) addresses them, then the loop
 * reviews again — until {@code approved} or the budget's spent (R23),
 * at which point it concludes (and always concludes — {@code iteration}
 * is bumped when each review turn is scheduled, not when the verdict tool
 * is called, so a turn that never calls {@code record_review_verdict}
 * still counts against the budget instead of leaving the round stuck
 * running forever).
 */
@Service
public class BrainReviewServiceImpl
        implements BrainReviewService
{
    private static final Logger log = LoggerFactory.getLogger(BrainReviewServiceImpl.class);

    static final String SOURCE_PLAN_SELF_REVIEW = "brain-plan-self-review";
    static final String SOURCE_BRAIN_REVIEW = "brain-review";
    static final String SOURCE_BRAIN_FIX = "brain-review-fix";
    private static final String PLAN_BUDGET_PAUSED = "plan_self_review_budget_paused";
    private static final String REVIEW_BUDGET_PAUSED = "brain_review_budget_paused";
    private static final String FIX_BUDGET_PAUSED = "brain_fix_budget_paused";
    private static final int MAX_OPERATIONAL_TURN_FAILURES = 2;

    record NeedsAttentionNotice(String threadId, String taskId, String payloadJson) {}

    record RoundRuntimeStopRequested(String taskId, String agentRunId) {}

    private enum WorkKind
    {
        PLAN_REVIEW,
        ROUND
    }

    private record DeferredWork(
            WorkKind kind,
            String taskId,
            String roundId,
            String runId,
            UUID stageId)
    {
        private static DeferredWork plan(String taskId, UUID stageId, String runId)
        {
            return new DeferredWork(
                    WorkKind.PLAN_REVIEW, taskId, null, runId, stageId);
        }

        private static DeferredWork round(Task task, ReviewRound round, AgentRun run)
        {
            return new DeferredWork(
                    WorkKind.ROUND, task.id(), round.id(), run.id(), null);
        }
    }

    private record ResumeResult(boolean resumed, DeferredWork work) {}

    /** Mirrors {@code PlanToolHandlers}' auto-approve heuristic — moved here
     *  so it evaluates the plan AFTER the mandatory self-review (R20). */
    private static final Set<String> LOW_EFFORT_COMPLEXITY = Set.of("trivial", "small");

    private final TaskStore taskStore;
    private final StageStore stageStore;
    private final ReviewRoundStore roundStore;
    private final AgentRunService agentRuns;
    private final ThreadStore threadStore;
    private final ThreadTurnScheduler scheduler;
    private final ThreadTurnStore turnStore;
    private final PRService prService;
    private final ValidationPassService validation;
    private final ValidationClaimService claimedValidation;
    private final ReviewRoundStateMachine roundMachine;
    private final TaskPhaseMachine phaseMachine;
    private final NotificationService notifications;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ApplicationEventPublisher events;
    private final TaskCommandExecutor commands;

    @Autowired
    public BrainReviewServiceImpl(
            TaskStore taskStore,
            StageStore stageStore,
            StageStateMachine stages,
            ReviewRoundStore roundStore,
            AgentRunService agentRuns,
            ThreadStore threadStore,
            ThreadTurnScheduler scheduler,
            ThreadTurnStore turnStore,
            PRService prService,
            @Lazy ValidationPassService validation,
            @Lazy ValidationClaimService claimedValidation,
            ReviewRoundStateMachine roundMachine,
            TaskPhaseMachine phaseMachine,
            NotificationService notifications,
            ObjectMapper mapper,
            ApplicationEventPublisher events,
            TaskCommandExecutor commands)
    {
        this(taskStore, stageStore, stages, roundStore, agentRuns, threadStore, scheduler, turnStore, prService,
                validation, claimedValidation, roundMachine, phaseMachine, notifications,
                mapper, Clock.systemUTC(), events, commands);
    }

    BrainReviewServiceImpl(
            TaskStore taskStore,
            StageStore stageStore,
            StageStateMachine stages,
            ReviewRoundStore roundStore,
            AgentRunService agentRuns,
            ThreadStore threadStore,
            ThreadTurnScheduler scheduler,
            ThreadTurnStore turnStore,
            PRService prService,
            ValidationPassService validation,
            ValidationClaimService claimedValidation,
            ReviewRoundStateMachine roundMachine,
            TaskPhaseMachine phaseMachine,
            NotificationService notifications,
            ObjectMapper mapper,
            Clock clock,
            ApplicationEventPublisher events,
            TaskCommandExecutor commands)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        requireNonNull(stages, "stages is null");
        this.roundStore = requireNonNull(roundStore, "roundStore is null");
        this.agentRuns = requireNonNull(agentRuns, "agentRuns is null");
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.scheduler = requireNonNull(scheduler, "scheduler is null");
        this.turnStore = requireNonNull(turnStore, "turnStore is null");
        this.prService = requireNonNull(prService, "prService is null");
        this.validation = requireNonNull(validation, "validation is null");
        this.claimedValidation = requireNonNull(claimedValidation, "claimedValidation is null");
        this.roundMachine = requireNonNull(roundMachine, "roundMachine is null");
        this.phaseMachine = requireNonNull(phaseMachine, "phaseMachine is null");
        this.notifications = requireNonNull(notifications, "notifications is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.clock = requireNonNull(clock, "clock is null");
        this.events = requireNonNull(events, "events is null");
        this.commands = requireNonNull(commands, "commands is null");
    }

    @Override
    public PR reviewBeforeLocalOpen(String prId, String actor)
    {
        PR pr = prService.findById(prId)
                .orElseThrow(() -> new IllegalArgumentException("unknown local PR: " + prId));
        if (!PR.STATUS_LOCAL_DRAFTED.equals(pr.status())) {
            // Already past this lock point (or not applicable) — nothing to gate.
            return pr;
        }
        Task task = taskStore.findTaskById(pr.taskId()).orElse(null);
        if (task == null) {
            return prService.requestUserReview(prId, actor);
        }
        String workflowVersion = taskStore.findWorkflowVersion(task.id())
                .orElse("LEGACY");
        if ("V2".equals(workflowVersion)) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "V2 Task review is owned by the typed Local Review runtime");
        }
        throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "LEGACY Task review is read-only; use a typed V2 Task control");
    }

    @Override
    public void reviewAfterLocalComments(String prId)
    {
        rejectLegacyMutation();
        PR pr = prService.findById(prId)
                .orElseThrow(() -> new IllegalArgumentException("unknown local PR: " + prId));
        if (!PR.STATUS_LOCAL_OPEN.equals(pr.status())) {
            return;
        }
        Task task = taskStore.findTaskById(pr.taskId()).orElse(null);
        if (taskRuntimeStopped(task)
                || task.phase() != TaskPhase.INTERNAL_REVIEW) {
            return;
        }
        DeferredWork work = commands.execute(task.id(), () -> {
            PR currentPr = prService.findById(prId).orElse(pr);
            Task currentTask = taskStore.findTaskById(task.id()).orElse(task);
            if (!PR.STATUS_LOCAL_OPEN.equals(currentPr.status())
                    || taskRuntimeStopped(currentTask)
                    || currentTask.phase() != TaskPhase.INTERNAL_REVIEW) {
                return null;
            }
            boolean live = roundStore.findByTask(task.id()).stream()
                    .anyMatch(round -> ReviewRound.ORIGIN_BRAIN.equals(round.origin()) && round.isLive());
            if (!live) {
                return openBrainRoundInCommand(currentTask, prId);
            }
            return null;
        });
        runDeferred(work);
    }

    @Override
    @Transactional
    public boolean ownsParkedResume(String taskId)
    {
        return false;
    }

    @Override
    public boolean pauseActiveReview(String taskId, String reason)
    {
        rejectLegacyMutation();
        return commands.execute(taskId, () -> {
            ReviewRound round = roundStore.findLiveByTask(taskId).orElse(null);
            if (round != null) {
                roundMachine.parkInCommand(taskId, round.id(), reason);
                return true;
            }
            Task task = taskStore.findTaskById(taskId).orElse(null);
            if (!planSelfReviewPending(task)) {
                return false;
            }
            pausePlanRun(task, reason);
            return true;
        });
    }

    @Override
    public boolean resumeParkedReview(String taskId)
    {
        rejectLegacyMutation();
        Boolean validationPassed = validationForResume(taskId);
        ResumeResult result = commands.execute(taskId,
                () -> resumeParkedReviewInCommand(taskId, validationPassed));
        return result.resumed() && runDeferred(result.work());
    }

    private Boolean validationForResume(String taskId)
    {
        Task task = taskStore.findTaskById(taskId).orElse(null);
        ReviewRound round = resumableRound(taskId).orElse(null);
        if (task == null || round == null || hasOpenBrainRoots(task)) {
            return null;
        }
        boolean brainOrigin = ReviewRound.ORIGIN_BRAIN.equals(round.origin());
        TaskPhase expected = brainOrigin
                ? TaskPhase.INTERNAL_REVIEW : TaskPhase.AWAITING_REMOTE_REVIEW;
        if (task.phase() != expected) {
            return null;
        }
        return validation.run(taskId).passed();
    }

    private ResumeResult resumeParkedReviewInCommand(String taskId, Boolean validationPassed)
    {
        Task task = taskStore.findTaskById(taskId).orElse(null);
        if (task == null) {
            return new ResumeResult(false, null);
        }
        if (task.phase() == TaskPhase.PLANNING
                && (planReviewWasLatestPark(taskId) || planSelfReviewPending(task))) {
            DeferredWork work = resumePlanSelfReviewInCommand(task);
            return new ResumeResult(work != null, work);
        }

        ReviewRound round = resumableRound(taskId).orElse(null);
        if (round == null) {
            if (task.phase() != TaskPhase.INTERNAL_REVIEW) {
                return new ResumeResult(false, null);
            }
            PR pr = prService.findByTask(taskId).orElse(null);
            if (pr == null || (!PR.STATUS_LOCAL_DRAFTED.equals(pr.status())
                    && !PR.STATUS_LOCAL_OPEN.equals(pr.status()))) {
                return new ResumeResult(false, null);
            }
            DeferredWork work = openBrainRoundInCommand(task, pr.id());
            boolean resumed = taskStore.findTaskById(taskId)
                    .map(current -> !taskRuntimeStopped(current))
                    .orElse(false);
            return new ResumeResult(resumed, work);
        }
        boolean brainOrigin = ReviewRound.ORIGIN_BRAIN.equals(round.origin());
        TaskPhase expectedPhase = brainOrigin
                ? TaskPhase.INTERNAL_REVIEW : TaskPhase.AWAITING_REMOTE_REVIEW;
        if (task.phase() != expectedPhase) {
            return new ResumeResult(false, null);
        }

        boolean addressing = hasOpenBrainRoots(task);
        if (!addressing && !Boolean.TRUE.equals(validationPassed)) {
            parkBrainRoundInCommand(task, round,
                    brainOrigin ? "brain_fixes_validation_failed" : "review_fixes_validation_failed");
            return new ResumeResult(false, null);
        }

        if (round.status() != ReviewRound.STATUS_PAUSED || round.pausedFrom() == null) {
            return new ResumeResult(false, null);
        }
        ReviewRound resumed = roundMachine.resumeInCommand(
                taskId, round.id(), "review_resumed");
        AgentRun run = resumed.runId() == null
                ? null : agentRuns.findById(resumed.runId()).orElse(null);
        if (run == null) {
            return new ResumeResult(false, null);
        }
        DeferredWork work;
        if (resumed.status() == ReviewRound.STATUS_TRIAGING) {
            work = DeferredWork.round(task, resumed, run);
        }
        else if (resumed.status() == ReviewRound.STATUS_ADDRESSING) {
            work = DeferredWork.round(task, resumed, run);
        }
        else {
            work = null;
        }
        return new ResumeResult(true, work);
    }

    private Optional<ReviewRound> resumableRound(String taskId)
    {
        return roundStore.findByTask(taskId).stream()
                .filter(candidate -> ReviewRound.STATUS_PAUSED.equals(candidate.status())
                        || candidate.isLive())
                .findFirst();
    }

    private boolean planReviewWasLatestPark(String taskId)
    {
        return taskStore.listPhaseEvents(taskId).stream()
                .filter(event -> event.toPhase() == TaskPhase.NEEDS_ATTENTION)
                .max((left, right) -> left.transitionedAt().compareTo(right.transitionedAt()))
                .map(event -> event.fromPhase() == TaskPhase.PLANNING
                        && "plan_self_review_failed".equals(event.reason()))
                .orElse(false);
    }

    private Optional<String> latestNeedsAttentionReason(String taskId)
    {
        return taskStore.listPhaseEvents(taskId).stream()
                .filter(event -> event.toPhase() == TaskPhase.NEEDS_ATTENTION)
                .max((left, right) -> left.transitionedAt().compareTo(right.transitionedAt()))
                .map(event -> event.reason() == null ? "" : event.reason());
    }

    private static boolean isReviewCoordinatorReason(String reason)
    {
        return reason.startsWith("brain_") || reason.startsWith("review_fixes_");
    }

    private static boolean taskRuntimeStopped(Task task)
    {
        if (task == null || task.phase() == TaskPhase.NEEDS_ATTENTION
                || task.phase() == TaskPhase.COMPLETED) {
            return true;
        }
        return switch (task.status()) {
            case PAUSED, NEEDS_ATTENTION, COMPLETED, REMOTE_CLOSED,
                    ERRORED, CANCELED, ARCHIVED -> true;
            default -> false;
        };
    }

    private boolean planSelfReviewPending(Task task)
    {
        if (task == null || task.phase() != TaskPhase.PLANNING) {
            return false;
        }
        return planSelfReviewOwed(task.id());
    }

    private boolean planSelfReviewOwed(String taskId)
    {
        return stageStore.findActiveStage(taskId)
                .filter(stage -> stage.type() == StageType.PLAN_STAGE)
                .filter(stage -> stage.state() != StageState.CLOSED)
                .map(stage -> stageStore.findEventsByStage(stage.id()))
                .filter(events -> latestPlanEvent(events).filter(this::isFinalized).isPresent())
                .filter(events -> !latestPlanWasSelfReviewed(events))
                .isPresent();
    }

    private void pauseCoordinatorRound(ReviewRound round, String reason)
    {
        roundMachine.park(round.id(), reason);
    }

    private void pauseCoordinatorRoundInCommand(String taskId, ReviewRound round, String reason)
    {
        roundMachine.parkInCommand(taskId, round.id(), reason);
    }

    private void pausePlanRun(Task task, String reason)
    {
        StageInstance plan = stageStore.findActiveStage(task.id()).orElse(null);
        ThreadTurn turn = latestPlanSelfReviewTurn(task.id(), plan == null ? null : plan.id()).orElse(null);
        if (turn == null || turn.agentRunId() == null) {
            return;
        }
        agentRuns.findById(turn.agentRunId())
                .filter(AgentRun::isLive)
                .ifPresent(run -> agentRuns.pauseInCommand(task.id(), run.id(), reason));
    }

    private DeferredWork resumePlanSelfReviewInCommand(Task task)
    {
        StageInstance plan = stageStore.findActiveStage(task.id())
                .filter(stage -> stage.type() == StageType.PLAN_STAGE)
                .filter(stage -> stage.state() != StageState.CLOSED)
                .orElse(null);
        if (plan == null) {
            return null;
        }
        List<StageEvent> stageEvents = stageStore.findEventsByStage(plan.id());
        Optional<StageEvent> latestPlan = latestPlanEvent(stageEvents);
        if (latestPlan.isEmpty() || !isFinalized(latestPlan.get())
                || latestPlanWasSelfReviewed(stageEvents)) {
            return null;
        }
        String replacementRunId = replacementPlanRunInCommand(task.id(), plan.id())
                .map(AgentRun::id)
                .orElse(null);
        return DeferredWork.plan(task.id(), plan.id(), replacementRunId);
    }

    private Optional<AgentRun> replacementPlanRunInCommand(String taskId, UUID stageId)
    {
        return latestPlanSelfReviewTurn(taskId, stageId)
                .map(ThreadTurn::agentRunId)
                .filter(runId -> runId != null && !runId.isBlank())
                .flatMap(agentRuns::findById)
                .filter(run -> AgentRun.STATUS_PAUSED.equals(run.status()))
                .map(run -> agentRuns.restartInCommand(taskId, run.id()));
    }

    private Optional<ThreadTurn> latestPlanSelfReviewTurn(String taskId, UUID stageId)
    {
        if (stageId == null) {
            return Optional.empty();
        }
        return threadStore.findBrainThreadByTask(taskId).stream()
                .flatMap(thread -> turnStore.listTurnsByTaskId(thread.id(), 50).stream())
                .filter(turn -> stageId.toString().equals(turn.stageId()))
                .filter(BrainReviewServiceImpl::isPlanSelfReviewTurn)
                .findFirst();
    }

    private DeferredWork openBrainRoundInCommand(Task task, String prId)
    {
        boolean adoptOpenFindings = hasOpenBrainRoots(task);
        ReviewRound round = roundMachine.openBrainInCommand(
                task.id(), prId, null, adoptOpenFindings);
        AgentRun run = agentRuns.findById(round.runId())
                .orElseThrow(() -> new IllegalStateException(
                        "new review round has no owning run: " + round.id()));
        threadStore.findBrainThreadByTask(task.id())
                .filter(thread -> thread.workspaceId() != null && !thread.workspaceId().isBlank())
                .ifPresent(thread -> agentRuns.attachOwnership(
                        run.id(), thread.workspaceId(), thread.id(), thread.provider(), thread.model(),
                        BRAIN_REVIEW_PROMPT));
        if (adoptOpenFindings) {
            return DeferredWork.round(task, round, run);
        }
        log.info("brain-review: opened dev-end round {} for task {} (PR {})", round.id(), task.id(), prId);
        return DeferredWork.round(task, round, run);
    }

    @Override
    public void reviewBeforeRoundGate(ReviewRound round, Task task)
    {
        rejectLegacyMutation();
        if (taskStore.isV2Task(task.id())) {
            log.warn("brain-review: ignored legacy round {} for V2 task {}",
                    round.id(), task.id());
            return;
        }
        if (taskRuntimeStopped(task)) {
            return;
        }
        latestOwnedTurn(round, "review-round")
                .filter(turn -> turn.status() == ThreadTurnStatus.COMPLETED)
                .ifPresentOrElse(
                        turn -> claimedValidation.claimAndRunReviewRound(
                                task.id(), round.id(), turn.id()),
                        () -> driveRound(round.id()));
    }

    @Override
    public void recordVerdict(String taskId, String stageId, String agentRunId, String scope, String verdict)
    {
        rejectLegacyMutation();
        if ("plan".equals(scope)) {
            commands.executeVoid(taskId, () -> {
                if (!ReviewRound.VERDICT_APPROVED.equals(verdict)
                        && !ReviewRound.VERDICT_CHANGES_REQUESTED.equals(verdict)) {
                    throw new IllegalArgumentException("invalid plan review verdict: " + verdict);
                }
                UUID planStageId = UUID.fromString(stageId);
                StageInstance planStage = stageStore.findStageById(planStageId)
                        .filter(stage -> taskId.equals(stage.taskId()))
                        .filter(stage -> stage.type() == StageType.PLAN_STAGE)
                        .filter(stage -> stage.state() != StageState.CLOSED)
                        .orElseThrow(() -> new IllegalStateException(
                                "no open PlanStage " + stageId + " for task " + taskId));
                StageEvent latestPlan = latestPlanEvent(stageStore.findEventsByStage(planStage.id()))
                        .filter(this::isFinalized)
                        .orElseThrow(() -> new IllegalStateException(
                                "no finalized plan to review for task " + taskId));
                String revisionId = requiredRevisionId(latestPlan);
                stageStore.recordEvent(
                        planStageId, taskId, StageEventType.PLAN_SELF_REVIEWED,
                        Map.of("verdict", verdict, "reviewedRevisionId", revisionId));
                // Exactly one pass (R20), so iteration is always 1.
                prService.recordBrainReview(
                        taskId, scope, verdict, /* iteration */ 1, /* roundId */ null);
            });
            return;
        }
        Optional<ReviewRound> live = roundStore.findLiveByTask(taskId)
                .filter(r -> matchesRunScope(r, stageId, agentRunId));
        if (live.isEmpty()) {
            log.warn("brain-review: record_review_verdict scope={} for task {} matched no live round",
                    scope, taskId);
            return;
        }
        ReviewRound current = live.get();
        String effectiveVerdict = effectiveBrainVerdict(current, taskId, verdict);
        roundMachine.recordVerdict(
                current.id(), currentRoundAttemptId(current, SOURCE_BRAIN_REVIEW), effectiveVerdict);
    }

    @Override
    public boolean isBudgetExhaustedEscalation(String taskId)
    {
        return false;
    }

    private boolean matchesRunStage(ReviewRound round, String stageId)
    {
        if (round.runId() == null) {
            return false;
        }
        return agentRuns.findById(round.runId())
                .map(r -> stageId.equals(r.stageId()))
                .orElse(false);
    }

    private boolean matchesRunScope(ReviewRound round, String stageId, String agentRunId)
    {
        if (round.runId() == null) {
            return false;
        }
        if (agentRunId != null && !agentRunId.isBlank()) {
            return round.runId().equals(agentRunId);
        }
        return matchesRunStage(round, stageId);
    }

    private boolean matchesRunTurn(ReviewRound round, ThreadTurn turn)
    {
        if (round.runId() == null || turn == null) {
            return false;
        }
        if (round.runId().equals(turn.agentRunId())) {
            return true;
        }
        String source = turn.initiator().source();
        return (SOURCE_BRAIN_REVIEW.equals(source) || SOURCE_BRAIN_FIX.equals(source))
                && turn.stageId() != null
                && matchesRunStage(round, turn.stageId());
    }

    /**
     * The one listener that advances every brain-review loop, matched
     * purely by which live thing the finished turn's stage id belongs to
     * — a PlanStage (R20) or a live round's backing run (R21). {@code
     * ReviewRoundServiceImpl.onTurnFinished} only ever hands a round to
     * {@link #reviewBeforeRoundGate} once (iteration 0, no verdict yet);
     * every subsequent turn on that round's stage is ours.
     */
    public void onTurnFinished(TaskTurnFinishedEvent event)
    {
        rejectLegacyMutation();
        TaskCommandExecutor.dispatchAfterCommit(() -> handleTurnFinished(event));
    }

    private void handleTurnFinished(TaskTurnFinishedEvent event)
    {
        ThreadTurn finished = turnStore.findTurnById(event.turnId()).orElse(null);
        if (coordinatorOwns(finished)) {
            handleRoundTurnFinished(event.taskId(), finished);
            return;
        }
        List<DeferredWork> work = commands.execute(event.taskId(), () -> {
            ThreadTurn turn = turnStore.findTurnById(event.turnId()).orElse(null);
            if (turn == null || turn.stageId() == null) {
                return List.of();
            }
            List<DeferredWork> pending = new ArrayList<>(2);
            if (event.failed() || turn.status() != ThreadTurnStatus.COMPLETED) {
                addIfPresent(pending, handleFailedPlanSelfReviewInCommand(event.taskId(), turn));
                return List.copyOf(pending);
            }
            addIfPresent(pending, advancePlanSelfReviewInCommand(event.taskId(), turn));
            return List.copyOf(pending);
        });
        for (DeferredWork pending : work) {
            runDeferred(pending);
        }
    }

    private static boolean coordinatorOwns(ThreadTurn turn)
    {
        if (turn == null || turn.initiator() == null || turn.agentRunId() == null) {
            return false;
        }
        return Set.of("review-round", SOURCE_BRAIN_REVIEW, SOURCE_BRAIN_FIX)
                .contains(turn.initiator().source());
    }

    private void handleRoundTurnFinished(String taskId, ThreadTurn turn)
    {
        ReviewRoundStateMachine.OwnedTurnEnded ended = commands.execute(taskId, () -> {
            ReviewRound round = roundStore.findLiveByTask(taskId)
                    .filter(candidate -> Objects.equals(candidate.runId(), turn.agentRunId()))
                    .orElse(null);
            if (round == null) {
                return null;
            }
            recordRoundAttemptStarted(round, turn);
            ReviewRoundStateMachine.OwnedTurnEnded result =
                    roundMachine.recordOwnedTurnEndedInCommand(
                            taskId, round.id(), turn.id());
            if (result.action() != ReviewRoundStateMachine.OwnedTurnAction.NONE
                    && (turn.status() == ThreadTurnStatus.FAILED
                            || turn.status() == ThreadTurnStatus.CANCELLED)) {
                String scope = ReviewRound.ORIGIN_BRAIN.equals(round.origin()) ? "dev" : "round";
                prService.recordBrainReviewFailed(
                        taskId, scope, round.iteration(), round.id(),
                        turn.status() == ThreadTurnStatus.CANCELLED
                                ? "review_turn_cancelled" : "review_turn_failed",
                        turn.id());
            }
            return result;
        });
        dispatchOwnedTurnEnded(ended);
    }

    private void concludeFinishedReview(ReviewRound round, String attemptId)
    {
        commands.executeVoid(round.taskId(), () -> {
            ReviewRound current = roundStore.findById(round.id()).orElse(null);
            Task task = taskStore.findTaskById(round.taskId()).orElse(null);
            if (current == null || task == null || current.status() != ReviewRound.STATUS_TRIAGING) {
                return;
            }
            String scope = ReviewRound.ORIGIN_BRAIN.equals(current.origin()) ? "dev" : "round";
            String verdict = effectiveBrainVerdict(
                    current, current.taskId(), current.brainVerdict());
            if (verdict == null) {
                parkBrainRoundInCommand(task, current, "brain_review_verdict_missing");
                return;
            }
            if (!Objects.equals(verdict, current.brainVerdict())) {
                current = roundMachine.recordVerdictInCommand(
                        current.taskId(), current.id(), attemptId, verdict);
            }
            prService.recordBrainReview(
                    current.taskId(), scope, verdict, current.iteration(), current.id(), attemptId);
            roundMachine.concludeBrainInCommand(
                    current.taskId(), current.id(), attemptId);
        });
    }

    public void onRoundOpened(ReviewRoundOpenedEvent event)
    {
        rejectLegacyMutation();
        TaskCommandExecutor.dispatchAfterCommit(() -> driveRound(event.roundId()));
    }

    public void onRoundTransitioned(ReviewRoundTransitionedEvent event)
    {
        rejectLegacyMutation();
        TaskCommandExecutor.dispatchAfterCommit(() -> driveRound(event.roundId()));
    }

    public void onRoundTurnStatusChanged(TaskTurnStatusChanged event)
    {
        rejectLegacyMutation();
        if (event.status() != ThreadTurnStatus.RUNNING) {
            return;
        }
        commands.executeVoid(event.taskId(), () -> {
            ThreadTurn turn = turnStore.findTurnById(event.turnId()).orElse(null);
            if (!coordinatorOwns(turn)) {
                return;
            }
            roundStore.findLiveByTask(event.taskId())
                    .filter(candidate -> Objects.equals(candidate.runId(), turn.agentRunId()))
                    .ifPresent(round -> recordRoundAttemptStarted(round, turn));
        });
    }

    public void onRoundValidationFinished(ReviewRoundValidationFinishedEvent event)
    {
        rejectLegacyMutation();
        TaskCommandExecutor.dispatchAfterCommit(() -> finishRoundValidation(event));
    }

    private void finishRoundValidation(ReviewRoundValidationFinishedEvent event)
    {
        if (!event.passed()) {
            parkRound(event.roundId(), "review_fixes_validation_failed");
            return;
        }
        try {
            roundMachine.finishAddressing(
                    event.roundId(), event.attemptId(), event.claimKey());
        }
        catch (ResponseStatusException e) {
            log.debug("review-round: stale validation {} ignored: {}",
                    event.claimKey(), e.getReason());
        }
    }

    public void onRoundGateValidationFinished(ReviewRoundGateValidationFinishedEvent event)
    {
        rejectLegacyMutation();
        TaskCommandExecutor.dispatchAfterCommit(() -> finishRoundGateValidation(event));
    }

    private void finishRoundGateValidation(ReviewRoundGateValidationFinishedEvent event)
    {
        try {
            if (!event.passed()) {
                parkRound(event.roundId(), "review_gate_validation_failed");
                return;
            }
            roundMachine.acceptGateValidation(event.roundId(), event.claimKey());
            driveRound(event.roundId());
        }
        catch (ResponseStatusException e) {
            log.debug("review-round: stale gate validation {} ignored: {}",
                    event.claimKey(), e.getReason());
        }
    }

    /** Reload-and-drive entry point shared by transition listeners and the sweep. */
    public void driveRound(String roundId)
    {
        rejectLegacyMutation();
        ReviewRound snapshot = roundStore.findById(roundId).orElse(null);
        if (snapshot == null) {
            return;
        }
        if (claimedValidation.claimAndRunGateRevalidation(roundId)) {
            return;
        }
        try {
            ReviewRoundStateMachine.OwnedTurnEnded ended = commands.execute(
                    snapshot.taskId(), () -> driveRoundInCommand(snapshot.taskId(), roundId));
            dispatchOwnedTurnEnded(ended);
        }
        catch (RuntimeException e) {
            log.warn("review-round: durable kick failed for round {}: {}",
                    roundId, e.getMessage());
            ReviewRound failed = roundMachine.recordDeliveryFailure(
                    roundId, snapshot.kickAttempt(), "review_turn_enqueue_failed");
            if (failed.status() != ReviewRound.STATUS_PAUSED
                    && failed.enqueueFailures() < MAX_OPERATIONAL_TURN_FAILURES) {
                driveRound(roundId);
            }
        }
    }

    private ReviewRoundStateMachine.OwnedTurnEnded driveRoundInCommand(
            String taskId, String roundId)
    {
        TaskCommandExecutor.requireCurrent(taskId);
        ReviewRound round = roundStore.findById(roundId).orElse(null);
        Task task = taskStore.findTaskById(taskId).orElse(null);
        if (round == null || taskRuntimeStopped(task)
                || (round.status() != ReviewRound.STATUS_TRIAGING
                        && round.status() != ReviewRound.STATUS_ADDRESSING)) {
            return null;
        }
        AgentRun run = round.runId() == null
                ? null : agentRuns.findById(round.runId()).orElse(null);
        if (run == null) {
            throw new IllegalStateException("review round has no owning run: " + roundId);
        }
        String source = owedSource(round);
        if (AgentRun.STATUS_RUNNING.equals(run.status())) {
            return currentOwnedTurn(round, source)
                    .map(turn -> {
                        recordRoundAttemptStarted(round, turn);
                        return turn;
                    })
                    .filter(BrainReviewServiceImpl::terminal)
                    .map(turn -> roundMachine.recordOwnedTurnEndedInCommand(
                            taskId, roundId, turn.id()))
                    .orElse(null);
        }
        if (!AgentRun.STATUS_QUEUED.equals(run.status())) {
            return null;
        }
        Thread thread = ReviewRound.STATUS_TRIAGING.equals(round.status())
                ? threadStore.findBrainThreadByTask(taskId)
                        .orElseThrow(() -> new IllegalStateException(
                                "brain review thread missing for task " + taskId))
                : threadStore.findThreadById(task.threadId())
                        .orElseThrow(() -> new IllegalStateException(
                                "review owner thread missing for task " + taskId));
        String turnId = scheduler.enqueueStageTurnOnce(
                ReviewRoundStateMachine.kickKey(round, source),
                thread, owedPrompt(round, task, source),
                taskId, run.stageId(), TurnInitiator.unattended(source), run.id(),
                ReviewRound.STATUS_TRIAGING.equals(round.status())
                        ? TurnLiveness.NARRATION : TurnLiveness.CODE);
        roundMachine.recordKickAdmittedInCommand(
                taskId, roundId, round.status(), round.kickAttempt());
        if (turnId == null) {
            return null; // test doubles may not return a durable id
        }
        if (round.status() == ReviewRound.STATUS_ADDRESSING) {
            pointAddressingReplacementInCommand(round, source, turnId);
        }
        return turnStore.findTurnById(turnId)
                .map(turn -> {
                    if (turn.status() == ThreadTurnStatus.RUNNING || terminal(turn)) {
                        recordRoundAttemptStarted(round, turn);
                    }
                    return turn;
                })
                .filter(BrainReviewServiceImpl::terminal)
                .map(turn -> roundMachine.recordOwnedTurnEndedInCommand(
                        taskId, roundId, turn.id()))
                .orElse(null);
    }

    /** A failed/cancelled code turn keeps the task's liveness pointer by
     * design. The coordinator retry owns the exact CAS to its newly keyed
     * replacement; without it the scheduler correctly defers the follower
     * forever. This runs in the same task command as turn insertion. */
    private void pointAddressingReplacementInCommand(
            ReviewRound round, String source, String replacementTurnId)
    {
        TaskCommandExecutor.requireCurrent(round.taskId());
        String currentId = taskStore.currentLivenessTurnId(round.taskId()).orElse(null);
        if (currentId == null || currentId.equals(replacementTurnId)) {
            return;
        }
        if (turnStore.findTurnIdByKickKey(ReviewRoundStateMachine.kickKey(round, source))
                .filter(replacementTurnId::equals)
                .isEmpty()) {
            throw new IllegalStateException(
                    "review replacement is not the current durable kick: " + replacementTurnId);
        }
        ThreadTurn failed = turnStore.findTurnById(currentId).orElse(null);
        boolean terminalFailure = failed != null
                && round.taskId().equals(failed.taskId())
                && failed.initiator() != null
                && source.equals(failed.initiator().source())
                && (failed.status() == ThreadTurnStatus.FAILED
                        || failed.status() == ThreadTurnStatus.CANCELLED);
        if (!terminalFailure || !isPriorRoundKick(round, source, currentId)) {
            throw new IllegalStateException(
                    "review replacement cannot take unrelated liveness pointer " + currentId);
        }
        if (!taskStore.setCurrentLivenessTurnIdIf(
                round.taskId(), currentId, replacementTurnId)
                && !replacementTurnId.equals(
                        taskStore.currentLivenessTurnId(round.taskId()).orElse(null))) {
            throw new IllegalStateException(
                    "review liveness pointer changed while admitting " + replacementTurnId);
        }
    }

    private boolean isPriorRoundKick(ReviewRound round, String source, String turnId)
    {
        for (int attempt = round.kickAttempt() - 1; attempt >= 0; attempt--) {
            if (turnStore.findTurnIdByKickKey(
                    ReviewRoundStateMachine.kickKey(round, source, attempt))
                    .filter(turnId::equals)
                    .isPresent()) {
                return true;
            }
        }
        return false;
    }

    private void dispatchOwnedTurnEnded(ReviewRoundStateMachine.OwnedTurnEnded ended)
    {
        if (ended == null) {
            return;
        }
        switch (ended.action()) {
            case CONCLUDE -> concludeFinishedReview(ended.round(), ended.turn().id());
            case VALIDATE -> claimedValidation.claimAndRunReviewRound(
                    ended.round().taskId(), ended.round().id(), ended.turn().id());
            case RETRY -> driveRound(ended.round().id());
            case PAUSED, NONE -> {
                // The owning command already persisted the terminal checkpoint.
            }
        }
    }

    private Optional<ThreadTurn> latestOwnedTurn(ReviewRound round, String source)
    {
        return turnStore.listTurnsByAgentRunId(round.runId(), 100).stream()
                .filter(turn -> turn.initiator() != null
                        && source.equals(turn.initiator().source()))
                .findFirst();
    }

    private Optional<ThreadTurn> currentOwnedTurn(ReviewRound round, String source)
    {
        return turnStore.findTurnIdByKickKey(ReviewRoundStateMachine.kickKey(round, source))
                .flatMap(turnStore::findTurnById);
    }

    private void recordRoundAttemptStarted(ReviewRound round, ThreadTurn turn)
    {
        if (turn.initiator() == null || turn.startedAt() == null) {
            return;
        }
        String source = turn.initiator().source();
        if (turnStore.findTurnIdByKickKey(ReviewRoundStateMachine.kickKey(round, source))
                .filter(turn.id()::equals)
                .isEmpty()) {
            return;
        }
        String scope = ReviewRound.ORIGIN_BRAIN.equals(round.origin()) ? "dev" : "round";
        if (SOURCE_BRAIN_REVIEW.equals(source)) {
            prService.recordBrainReviewStarted(
                    round.taskId(), scope, round.iteration(), round.id(), turn.id());
        }
        else if ("review-round".equals(source) || SOURCE_BRAIN_FIX.equals(source)) {
            prService.recordBrainReviewAddressing(
                    round.taskId(), scope, round.iteration(), round.id(), turn.id());
        }
    }

    private static boolean terminal(ThreadTurn turn)
    {
        return turn.status() == ThreadTurnStatus.COMPLETED
                || turn.status() == ThreadTurnStatus.FAILED
                || turn.status() == ThreadTurnStatus.CANCELLED;
    }

    private static String owedSource(ReviewRound round)
    {
        if (round.status() == ReviewRound.STATUS_TRIAGING) {
            return SOURCE_BRAIN_REVIEW;
        }
        return ReviewRound.ORIGIN_EXTERNAL.equals(round.origin()) && round.iteration() == 0
                ? "review-round" : SOURCE_BRAIN_FIX;
    }

    private String owedPrompt(ReviewRound round, Task task, String source)
    {
        if (SOURCE_BRAIN_REVIEW.equals(source)) {
            return BRAIN_REVIEW_PROMPT + "\n\n" + reviewIterationMarker(round.iteration());
        }
        if (SOURCE_BRAIN_FIX.equals(source)) {
            return brainFixPrompt(task) + "\n\n" + fixIterationMarker(round.iteration());
        }
        StringBuilder prompt = new StringBuilder(
                "Address every review comment below. Make and commit required code changes; "
                        + "draft replies with record_round_reply, resolve each handled comment, "
                        + "and do not push or post to GitHub.\n");
        for (var comment : stageStore.findCommentsByRound(UUID.fromString(round.id()))) {
            prompt.append("\n[id: ").append(comment.id()).append("] ")
                    .append(comment.file() == null ? "general" : comment.file() + ':' + comment.line())
                    .append("\n").append(comment.body() == null ? "" : comment.body().strip())
                    .append('\n');
        }
        return prompt.toString();
    }

    private static void addIfPresent(List<DeferredWork> work, DeferredWork candidate)
    {
        if (candidate != null) {
            work.add(candidate);
        }
    }

    public void onTurnBudgetPaused(TaskTurnBudgetPausedEvent event)
    {
        rejectLegacyMutation();
        TaskCommandExecutor.dispatchAfterCommit(() -> handleTurnBudgetPaused(event));
    }

    private void handleTurnBudgetPaused(TaskTurnBudgetPausedEvent event)
    {
        ThreadTurn turn = turnStore.findTurnById(event.turnId()).orElse(null);
        if (turn == null || turn.initiator() == null) {
            return;
        }
        String source = turn.initiator().source();
        commands.executeVoid(event.taskId(), () -> {
            Task task = taskStore.findTaskById(event.taskId()).orElse(null);
            if (SOURCE_PLAN_SELF_REVIEW.equals(source)) {
                if (taskRuntimeStopped(task)) {
                    return;
                }
                if (planSelfReviewPending(task)) {
                    pauseRunInCommand(task.id(), turn.agentRunId(), PLAN_BUDGET_PAUSED);
                    phaseMachine.pauseInCommand(task.id(), Actor.AGENT, PLAN_BUDGET_PAUSED);
                    taskStore.updateRuntimeFailure(task.id(), null, PLAN_BUDGET_PAUSED);
                }
                return;
            }
            if (!SOURCE_BRAIN_REVIEW.equals(source) && !SOURCE_BRAIN_FIX.equals(source)) {
                return;
            }
            boolean userAlreadyPaused = task != null && task.status() == TaskStatus.PAUSED;
            if (!userAlreadyPaused && taskRuntimeStopped(task)) {
                return;
            }
            ReviewRound round = roundStore.findLiveByTask(event.taskId())
                    .filter(candidate -> matchesRunTurn(candidate, turn))
                    .orElseGet(() -> userAlreadyPaused
                            ? roundStore.findByTask(event.taskId()).stream()
                                    .filter(candidate -> ReviewRound.STATUS_PAUSED.equals(candidate.status()))
                                    .filter(candidate -> matchesRunTurn(candidate, turn))
                                    .findFirst()
                                    .orElse(null)
                            : null);
            if (round == null) {
                return;
            }
            String scope = ReviewRound.ORIGIN_BRAIN.equals(round.origin()) ? "dev" : "round";
            String reason;
            if (SOURCE_BRAIN_REVIEW.equals(source)) {
                reason = REVIEW_BUDGET_PAUSED;
                String verdict = effectiveBrainVerdict(round, task.id(), round.brainVerdict());
                if (verdict != null && !Objects.equals(verdict, round.brainVerdict())) {
                    round = roundMachine.recordVerdictInCommand(
                            task.id(), round.id(), turn.id(), verdict);
                }
                prService.recordBrainReview(
                        task.id(), scope, verdict, round.iteration(),
                        round.id(), turn.id());
            }
            else {
                reason = FIX_BUDGET_PAUSED;
                prService.recordBrainReviewFailed(
                        task.id(), scope, round.iteration(), round.id(), reason, turn.id());
            }
            if (userAlreadyPaused) {
                return;
            }
            pauseCoordinatorRoundInCommand(task.id(), round, reason);
            phaseMachine.pauseInCommand(task.id(), Actor.AGENT, reason);
            taskStore.updateRuntimeFailure(task.id(), null, reason);
        });
    }

    private void pauseRun(String runId, String reason)
    {
        if (runId == null || runId.isBlank()) {
            return;
        }
        agentRuns.findById(runId)
                .filter(AgentRun::isLive)
                .ifPresent(run -> agentRuns.pause(run.id(), reason));
    }

    private void pauseRunInCommand(String taskId, String runId, String reason)
    {
        if (runId == null || runId.isBlank()) {
            return;
        }
        agentRuns.findById(runId)
                .filter(AgentRun::isLive)
                .ifPresent(run -> agentRuns.pauseInCommand(taskId, run.id(), reason));
    }

    private DeferredWork handleFailedPlanSelfReviewInCommand(String taskId, ThreadTurn turn)
    {
        if (turn.initiator() == null
                || !SOURCE_PLAN_SELF_REVIEW.equals(turn.initiator().source())) {
            return null;
        }
        if (taskStore.findTaskById(taskId)
                .map(BrainReviewServiceImpl::taskRuntimeStopped)
                .orElse(true)) {
            return null;
        }
        UUID stageId;
        try {
            stageId = UUID.fromString(turn.stageId());
        }
        catch (IllegalArgumentException e) {
            return null;
        }
        StageInstance plan = stageStore.findStageById(stageId)
                .filter(stage -> stage.type() == StageType.PLAN_STAGE)
                .filter(stage -> stage.state() != StageState.CLOSED)
                .orElse(null);
        if (plan == null) {
            return null;
        }
        Instant latestPlanAt = latestPlanEvent(stageStore.findEventsByStage(stageId))
                .map(StageEvent::eventAt)
                .orElse(Instant.MIN);
        Instant attemptResetAt = planAttemptResetAt(taskId, latestPlanAt);
        boolean priorAttempt = turnStore.listTurnsByTaskId(turn.threadId(), 50).stream()
                .filter(candidate -> !candidate.id().equals(turn.id()))
                .filter(candidate -> stageId.toString().equals(candidate.stageId()))
                .filter(candidate -> !candidate.createdAt().isBefore(attemptResetAt))
                .anyMatch(BrainReviewServiceImpl::isPlanSelfReviewTurn);
        if (!priorAttempt) {
            return DeferredWork.plan(taskId, stageId, null);
        }
        parkFailedPlanReviewInCommand(taskId, stageId);
        return null;
    }

    private boolean enqueuePlanSelfReviewRuntime(String taskId, UUID stageId, String agentRunId)
    {
        Optional<Thread> brain = threadStore.findBrainThreadByTask(taskId);
        Optional<String> prompt = planSelfReviewPrompt(taskId, stageId);
        if (brain.isEmpty() || prompt.isEmpty()) {
            return false;
        }
        try {
            TurnInitiator initiator = TurnInitiator.unattended(SOURCE_PLAN_SELF_REVIEW);
            if (agentRunId == null) {
                scheduler.enqueueStageTurn(
                        brain.get(), prompt.orElseThrow(), taskId, stageId.toString(),
                        initiator, null, TurnLiveness.NARRATION);
            }
            else {
                scheduler.enqueueStageTurn(
                        brain.get(), prompt.orElseThrow(), taskId, stageId.toString(),
                        initiator, agentRunId, TurnLiveness.NARRATION);
            }
        }
        catch (RuntimeException e) {
            log.warn("brain-review: plan self-review enqueue failed for task {}: {}", taskId, e.getMessage());
            return false;
        }
        return true;
    }

    private Optional<String> planSelfReviewPrompt(String taskId, UUID stageId)
    {
        return latestPlanEvent(stageStore.findEventsByStage(stageId))
                .filter(this::isFinalized)
                .map(event -> {
                    JsonNode plan = planJson(event);
                    String revisionId = requiredRevisionId(event);
                    return PLAN_SELF_REVIEW_PROMPT.formatted(
                            taskId, revisionId, plan.toPrettyString());
                });
    }

    private static boolean isPlanSelfReviewTurn(ThreadTurn turn)
    {
        return turn.initiator() != null
                && SOURCE_PLAN_SELF_REVIEW.equals(turn.initiator().source());
    }

    private void parkFailedPlanReviewInCommand(String taskId, UUID stageId)
    {
        Task task = taskStore.findTaskById(taskId).orElse(null);
        if (taskRuntimeStopped(task)) {
            return;
        }
        stageStore.recordEvent(
                stageId, taskId, StageEventType.PLAN_FAILED,
                Map.of("error", "The mandatory Brain plan self-review failed twice."));
        phaseMachine.parkOperationalInCommand(taskId, Actor.AGENT, "plan_self_review_failed");
        notifyNeedsAttention(
                task.threadId(), taskId, "{\"reason\":\"plan self-review failed twice\"}");
    }

    /** Recover a self-review whose completion event was missed, retry one
     *  failed/cancelled/unverdicted turn, and otherwise preserve the mandatory checkpoint. */
    public void reconcilePlanSelfReviews()
    {
        rejectLegacyMutation();
        List<String> taskIds = taskStore.listByPhases(List.of(TaskPhase.PLANNING), 100).stream()
                .map(Task::id)
                .toList();
        for (String taskId : taskIds) {
            DeferredWork work = commands.execute(
                    taskId, () -> reconcilePlanSelfReviewInCommand(taskId));
            runDeferred(work);
        }
    }

    private DeferredWork reconcilePlanSelfReviewInCommand(String taskId)
    {
        Task task = taskStore.findTaskById(taskId).orElse(null);
        if (taskRuntimeStopped(task) || task.phase() != TaskPhase.PLANNING) {
            return null;
        }
        StageInstance plan = stageStore.findActiveStage(task.id())
                .filter(stage -> stage.type() == StageType.PLAN_STAGE)
                .orElse(null);
        if (plan == null) {
            return null;
        }
        List<StageEvent> stageEvents = stageStore.findEventsByStage(plan.id());
        Optional<StageEvent> latestPlan = latestPlanEvent(stageEvents);
        if (latestPlanWasSelfReviewed(stageEvents)
                || latestPlan.isEmpty() || !isFinalized(latestPlan.orElseThrow())) {
            return null;
        }
        Optional<Thread> brain = threadStore.findBrainThreadByTask(task.id());
        if (brain.isEmpty()) {
            parkFailedPlanReviewInCommand(task.id(), plan.id());
            return null;
        }
        Instant attemptResetAt = planAttemptResetAt(
                task.id(), latestPlan.orElseThrow().eventAt());
        List<ThreadTurn> attempts = turnStore.listTurnsByTaskId(brain.orElseThrow().id(), 50).stream()
                .filter(candidate -> plan.id().toString().equals(candidate.stageId()))
                .filter(BrainReviewServiceImpl::isPlanSelfReviewTurn)
                .filter(candidate -> !candidate.createdAt().isBefore(attemptResetAt))
                .toList();
        if (attempts.stream().anyMatch(candidate ->
                candidate.status() == ThreadTurnStatus.QUEUED
                        || candidate.status() == ThreadTurnStatus.RUNNING)) {
            return null;
        }
        long failedAttempts = attempts.stream().filter(candidate ->
                candidate.status() == ThreadTurnStatus.COMPLETED
                        || candidate.status() == ThreadTurnStatus.FAILED
                        || candidate.status() == ThreadTurnStatus.CANCELLED).count();
        if (failedAttempts >= MAX_OPERATIONAL_TURN_FAILURES) {
            parkFailedPlanReviewInCommand(task.id(), plan.id());
            return null;
        }
        return DeferredWork.plan(task.id(), plan.id(), null);
    }

    private DeferredWork advancePlanSelfReviewInCommand(String taskId, ThreadTurn turn)
    {
        UUID stageId;
        try {
            stageId = UUID.fromString(turn.stageId());
        }
        catch (IllegalArgumentException e) {
            return null;
        }
        Optional<StageInstance> planStage = stageStore.findStageById(stageId)
                .filter(s -> s.type() == StageType.PLAN_STAGE)
                .filter(s -> s.state() != StageState.CLOSED);
        if (planStage.isEmpty()) {
            return null;
        }
        if (taskStore.findTaskById(taskId)
                .map(BrainReviewServiceImpl::taskRuntimeStopped)
                .orElse(true)) {
            return null;
        }
        List<StageEvent> events = stageStore.findEventsByStage(stageId);
        boolean alreadyReviewed = latestPlanWasSelfReviewed(events);
        boolean isSelfReviewTurn = SOURCE_PLAN_SELF_REVIEW.equals(turn.initiator().source());
        if (isSelfReviewTurn) {
            if (!alreadyReviewed) {
                return handleFailedPlanSelfReviewInCommand(taskId, turn);
            }
            if (latestPlanWasApproved(events)) {
                runAutoApproveCheck(taskId, events);
            }
            return null;
        }
        if (alreadyReviewed) {
            return null; // R20: exactly one round, ever, per PlanStage.
        }
        Optional<StageEvent> latestPlan = events.stream()
                .filter(e -> e.eventType() == StageEventType.PLAN_RECORDED)
                .reduce((first, second) -> second);
        if (latestPlan.isEmpty() || !isFinalized(latestPlan.get())) {
            return null;
        }
        return DeferredWork.plan(taskId, stageId, null);
    }

    private void runAutoApproveCheck(String taskId, List<StageEvent> events)
    {
        Optional<StageEvent> latestPlan = events.stream()
                .filter(e -> e.eventType() == StageEventType.PLAN_RECORDED)
                .reduce((first, second) -> second);
        latestPlan.map(this::planJson).ifPresent(plan -> {
            if (!"finalized".equals(plan.path("status").asText(""))) {
                return;
            }
            JsonNode signals = plan.path("signals");
            boolean lowRisk = "low".equals(signals.path("riskLevel").asText(""));
            boolean lowEffort = LOW_EFFORT_COMPLEXITY.contains(signals.path("estimatedComplexity").asText(""));
            if (lowRisk && lowEffort) {
                taskStore.setAutoApprove(taskId, true);
            }
        });
    }

    private boolean isFinalized(StageEvent planEvent)
    {
        return "finalized".equals(planJson(planEvent).path("status").asText(""));
    }

    private boolean latestPlanWasSelfReviewed(List<StageEvent> events)
    {
        return latestPlanReviewVerdict(events).isPresent();
    }

    private boolean latestPlanWasApproved(List<StageEvent> events)
    {
        return latestPlanReviewVerdict(events)
                .filter(ReviewRound.VERDICT_APPROVED::equals)
                .isPresent();
    }

    private Optional<String> latestPlanReviewVerdict(List<StageEvent> events)
    {
        String revisionId = null;
        String verdict = null;
        for (StageEvent event : events) {
            if (event.eventType() == StageEventType.PLAN_RECORDED) {
                revisionId = planJson(event).path("id").asText(null);
                verdict = null;
            }
            else if (event.eventType() == StageEventType.PLAN_SELF_REVIEWED
                    && revisionId != null) {
                JsonNode review = planJson(event);
                String candidate = review.path("verdict").asText(null);
                verdict = null;
                if (revisionId.equals(review.path("reviewedRevisionId").asText(null))
                        && (ReviewRound.VERDICT_APPROVED.equals(candidate)
                                || ReviewRound.VERDICT_CHANGES_REQUESTED.equals(candidate))) {
                    verdict = candidate;
                }
            }
        }
        return Optional.ofNullable(verdict);
    }

    private static Optional<StageEvent> latestPlanEvent(List<StageEvent> events)
    {
        return events.stream()
                .filter(event -> event.eventType() == StageEventType.PLAN_RECORDED)
                .reduce((first, second) -> second);
    }

    private Instant planAttemptResetAt(String taskId, Instant latestPlanAt)
    {
        return taskStore.listPhaseEvents(taskId).stream()
                .filter(event -> "user_resumed_task".equals(event.reason()))
                .map(event -> event.transitionedAt())
                .filter(resumedAt -> resumedAt.isAfter(latestPlanAt))
                .max(Instant::compareTo)
                .orElse(latestPlanAt);
    }

    private JsonNode planJson(StageEvent planEvent)
    {
        if (planEvent.payloadJson() == null) {
            return MissingNode.getInstance();
        }
        try {
            return mapper.readTree(planEvent.payloadJson());
        }
        catch (JsonProcessingException e) {
            log.warn("brain-review: unparseable plan payload for stage event {}: {}",
                    planEvent.id(), e.getMessage());
            return MissingNode.getInstance();
        }
    }

    private String requiredRevisionId(StageEvent planEvent)
    {
        String revisionId = planJson(planEvent).path("id").asText(null);
        if (revisionId == null || revisionId.isBlank()) {
            throw new IllegalStateException(
                    "plan event " + planEvent.id() + " has no revision id");
        }
        return revisionId;
    }

    /** Backstop for missed transition notifications. Each candidate enters
     * the same reload-and-drive path as the normal event listener. */
    public void reconcileStalledRounds()
    {
        rejectLegacyMutation();
        roundStore.findAllLive().forEach(round -> driveRound(round.id()));
    }

    private void parkBrainRoundInCommand(Task task, ReviewRound round, String reason)
    {
        if (taskRuntimeStopped(task)) {
            return;
        }
        stopRoundRuntimeInCommand(task, round, reason);
        taskStore.updateRuntimeFailure(task.id(), null, reason);
        phaseMachine.parkOperationalInCommand(task.id(), Actor.AGENT, reason);
        notifyNeedsAttention(task.threadId(), task.id(),
                "{\"reason\":\"" + reason + "\",\"roundId\":\"" + round.id() + "\"}");
    }

    private void parkRound(String roundId, String reason)
    {
        ReviewRound snapshot = roundStore.findById(roundId).orElse(null);
        if (snapshot == null) {
            return;
        }
        commands.executeVoid(snapshot.taskId(), () -> {
            ReviewRound round = roundStore.findById(roundId).orElse(null);
            Task task = taskStore.findTaskById(snapshot.taskId()).orElse(null);
            if (round != null && task != null) {
                parkBrainRoundInCommand(task, round, reason);
            }
        });
    }

    private void stopRoundRuntimeInCommand(Task task, ReviewRound round, String reason)
    {
        String attemptId = turnStore.findTurnIdByKickKey(
                        ReviewRoundStateMachine.kickKey(round, owedSource(round)))
                .orElse(null);
        ReviewRound parked = roundMachine.parkInCommand(task.id(), round.id(), reason);
        if (round.runId() != null) {
            events.publishEvent(new RoundRuntimeStopRequested(task.id(), round.runId()));
        }
        String scope = ReviewRound.ORIGIN_BRAIN.equals(round.origin()) ? "dev" : "round";
        prService.recordBrainReviewFailed(
                task.id(), scope, parked.iteration(), parked.id(), reason, attemptId);
    }

    public void stopRoundRuntimeAfterCommit(RoundRuntimeStopRequested request)
    {
        rejectLegacyMutation();
        TaskCommandExecutor.dispatchAfterCommit(() -> stopRoundRuntime(request));
    }

    private void stopRoundRuntime(RoundRuntimeStopRequested request)
    {
        try {
            scheduler.cancelSessionTurns(request.agentRunId());
        }
        catch (RuntimeException e) {
            log.warn("brain-review: stopping runtime {} for task {} failed: {}",
                    request.agentRunId(), request.taskId(), e.getMessage());
        }
    }

    private boolean runDeferred(DeferredWork work)
    {
        if (work == null) {
            return true;
        }
        return switch (work.kind()) {
            case PLAN_REVIEW -> runDeferredPlanReview(work);
            case ROUND -> runDeferredRoundTurn(work);
        };
    }

    private boolean runDeferredPlanReview(DeferredWork work)
    {
        boolean enqueued = enqueuePlanSelfReviewRuntime(
                work.taskId(), work.stageId(), work.runId());
        commands.executeVoid(work.taskId(), () -> {
            Task task = taskStore.findTaskById(work.taskId()).orElse(null);
            StageInstance stage = stageStore.findStageById(work.stageId()).orElse(null);
            if (taskRuntimeStopped(task) || stage == null
                    || stage.state() == StageState.CLOSED
                    || stage.type() != StageType.PLAN_STAGE) {
                return;
            }
            if (!enqueued) {
                parkFailedPlanReviewInCommand(work.taskId(), work.stageId());
                return;
            }
            stageStore.recordEvent(
                    work.stageId(), work.taskId(), StageEventType.PLAN_SELF_REVIEW_STARTED,
                    Map.of("iteration", 1));
            prService.recordBrainReviewStarted(work.taskId(), "plan", 1, null);
        });
        if (enqueued) {
            log.info("brain-review: plan self-review turn enqueued for task {}", work.taskId());
        }
        return enqueued;
    }

    private boolean runDeferredRoundTurn(DeferredWork work)
    {
        driveRound(work.roundId());
        return true;
    }

    private static String reviewIterationMarker(int iteration)
    {
        return "[brain-review-iteration:" + iteration + "]";
    }

    private static String fixIterationMarker(int iteration)
    {
        return "[brain-fix-iteration:" + iteration + "]";
    }

    private String currentRoundAttemptId(ReviewRound round, String source)
    {
        return turnStore.findTurnIdByKickKey(ReviewRoundStateMachine.kickKey(round, source))
                .orElseThrow(() -> new IllegalStateException(
                        "review round " + round.id() + " has no durable " + source + " kick"));
    }

    private void notifyNeedsAttention(String threadId, String taskId, String payloadJson)
    {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            try {
                notifications.notifyNeedsAttention(threadId, taskId, payloadJson);
            }
            catch (RuntimeException e) {
                log.warn("brain-review: needs-attention notification failed for task {}: {}",
                        taskId, e.getMessage());
            }
            return;
        }
        try {
            events.publishEvent(new NeedsAttentionNotice(threadId, taskId, payloadJson));
        }
        catch (RuntimeException e) {
            log.warn("brain-review: needs-attention notification could not be scheduled for task {}: {}",
                    taskId, e.getMessage());
        }
    }

    public void deliverNeedsAttention(NeedsAttentionNotice notice)
    {
        rejectLegacyMutation();
        TaskCommandExecutor.dispatchAfterCommit(() -> notifyNeedsAttention(notice));
    }

    private static void rejectLegacyMutation()
    {
        throw new ResponseStatusException(
                HttpStatus.CONFLICT,
                "LEGACY Brain review is read-only; use a typed V2 review control");
    }

    private void notifyNeedsAttention(NeedsAttentionNotice notice)
    {
        try {
            notifications.notifyNeedsAttentionInNewTransaction(
                    notice.threadId(), notice.taskId(), notice.payloadJson());
        }
        catch (RuntimeException e) {
            log.warn("brain-review: needs-attention notification failed for task {}: {}",
                    notice.taskId(), e.getMessage());
        }
    }

    private Instant now()
    {
        return Instant.now(clock);
    }

    private String brainFixPrompt(Task task)
    {
        StringBuilder out = new StringBuilder(BRAIN_FIX_PROMPT)
                .append("\n\nOpen brain comments:\n");
        List<PRComment> threadComments = prService.findByTask(task.id())
                .map(pr -> prService.comments(pr.id()))
                .orElse(List.of());
        List<PRComment> roots = threadComments.stream()
                .filter(BrainReviewServiceImpl::isOpenBrainComment)
                .toList();
        if (roots.isEmpty()) {
            out.append("- No open brain comments are currently recorded. Re-read the diff and continue if needed.\n");
            return out.toString();
        }
        int i = 1;
        for (PRComment comment : roots) {
            out.append(i++).append(". [id: ").append(comment.id()).append("] ");
            if (comment.filePath() != null) {
                out.append(comment.filePath());
                if (comment.lineNumber() != null) {
                    out.append(':').append(comment.lineNumber());
                }
                out.append(' ');
            }
            out.append(comment.body() == null ? "" : comment.body().strip()).append('\n');
            for (PRComment reply : threadComments) {
                if (comment.id().equals(reply.parentCommentId())) {
                    out.append("   Reply @").append(reply.author()).append(": ")
                            .append(reply.body() == null ? "" : reply.body().strip())
                            .append('\n');
                }
            }
        }
        return out.toString();
    }

    private static boolean isOpenBrainComment(PRComment comment)
    {
        return PRTimelineEntry.ACTOR_BRAIN.equals(comment.author())
                && comment.parentCommentId() == null
                && comment.resolvedAt() == null
                && comment.dismissedAt() == null;
    }

    private boolean hasOpenBrainRoots(Task task)
    {
        return hasOpenBrainRoots(task.id());
    }

    private boolean hasOpenBrainRoots(String taskId)
    {
        return prService.findByTask(taskId)
                .map(pr -> prService.comments(pr.id()).stream()
                        .anyMatch(BrainReviewServiceImpl::isOpenBrainComment))
                .orElse(false);
    }

    private String effectiveBrainVerdict(ReviewRound round, String taskId, String verdict)
    {
        if (ReviewRound.ORIGIN_BRAIN.equals(round.origin())
                && ReviewRound.VERDICT_APPROVED.equals(verdict)
                && hasOpenBrainRoots(taskId)) {
            log.warn("brain-review: round {} reported approved with open findings; recording changes requested",
                    round.id());
            return ReviewRound.VERDICT_CHANGES_REQUESTED;
        }
        return verdict;
    }

    private static final String PLAN_SELF_REVIEW_PROMPT = """
            Adversarially review the exact finalized plan below for task `%s`, revision `%s`.
            The embedded PlanResult is authoritative; do not use a placeholder task id such as `current`.

            <plan_json>
            %s
            </plan_json>

            Check for wrong decomposition, a missing constraint, a simpler alternative, and understated risk.
            If the plan needs correction, call record_plan with task_id=`%1$s` and a finalized revision, then
            evaluate that revision. Call record_review_verdict(scope='plan', verdict='approved') only when the
            resulting latest plan has no remaining concern; otherwise use verdict='changes_requested'. You must
            record a verdict. Exactly one pass — do not loop.
            """;

    private static final String BRAIN_REVIEW_PROMPT = """
            Adversarially review the current diff before it goes to the user (or before this round's gate arms).
            Use read_dev_report / read_dev_conversation / read_diff_summary to inspect what changed and why.

            Produce only durable review artifacts; do not narrate your process, announce what you will inspect,
            praise the implementation, or write a review summary. If there are no concerns, leave no comment,
            call record_review_verdict with verdict='approved', and stop. Otherwise:
            - Call record_pr_comment exactly once per distinct concern.
            - Use scope='file-line' with the precise file and line whenever the concern is positionable.
            - Keep each body concise: state only the problem and its evidence or impact.
            - Do not include implementation steps, remediation advice, future cleanup, nits, or non-blocking notes.
            Then call record_review_verdict with verdict='changes_requested' and stop.
            Comments stay local and are never posted to GitHub.
            """;

    private static final String BRAIN_FIX_PROMPT =
            "The brain left review comments on this diff (local only — see the PR's open comments). "
            + "For each comment, decide deliberately: make the fix and commit it, then call "
            + "resolve_pr_comment with resolution='addressed' and its required reply describing "
            + "the fix; answer a question directly through that same reply; or, if you disagree, "
            + "reply via record_pr_comment with parent_comment_id and then call "
            + "resolve_pr_comment with resolution='dismissed'. Do not push.";
}
