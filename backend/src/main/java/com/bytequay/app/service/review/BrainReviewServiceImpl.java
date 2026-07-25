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
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ThreadTurnStatus;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.domain.TurnLiveness;
import com.bytequay.app.repository.ReviewRoundStore;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.service.checks.ValidationPassResult;
import com.bytequay.app.service.checks.ValidationPassService;
import com.bytequay.app.service.localpr.LocalReviewClearedEvent;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.runs.AgentRunService;
import com.bytequay.app.service.threads.NotificationService;
import com.bytequay.app.service.threads.TaskPhaseMachine;
import com.bytequay.app.service.threads.TaskTurnBudgetPausedEvent;
import com.bytequay.app.service.threads.TaskTurnFinishedEvent;
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
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
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
    private final TaskPhaseMachine phaseMachine;
    private final NotificationService notifications;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    @Autowired
    public BrainReviewServiceImpl(
            TaskStore taskStore,
            StageStore stageStore,
            ReviewRoundStore roundStore,
            AgentRunService agentRuns,
            ThreadStore threadStore,
            ThreadTurnScheduler scheduler,
            ThreadTurnStore turnStore,
            PRService prService,
            @Lazy ValidationPassService validation,
            TaskPhaseMachine phaseMachine,
            NotificationService notifications,
            ObjectMapper mapper,
            ApplicationEventPublisher events)
    {
        this(taskStore, stageStore, roundStore, agentRuns, threadStore, scheduler, turnStore, prService,
                validation, phaseMachine, notifications, mapper, Clock.systemUTC(), events);
    }

    BrainReviewServiceImpl(
            TaskStore taskStore,
            StageStore stageStore,
            ReviewRoundStore roundStore,
            AgentRunService agentRuns,
            ThreadStore threadStore,
            ThreadTurnScheduler scheduler,
            ThreadTurnStore turnStore,
            PRService prService,
            ValidationPassService validation,
            TaskPhaseMachine phaseMachine,
            NotificationService notifications,
            ObjectMapper mapper,
            Clock clock,
            ApplicationEventPublisher events)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.roundStore = requireNonNull(roundStore, "roundStore is null");
        this.agentRuns = requireNonNull(agentRuns, "agentRuns is null");
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.scheduler = requireNonNull(scheduler, "scheduler is null");
        this.turnStore = requireNonNull(turnStore, "turnStore is null");
        this.prService = requireNonNull(prService, "prService is null");
        this.validation = requireNonNull(validation, "validation is null");
        this.phaseMachine = requireNonNull(phaseMachine, "phaseMachine is null");
        this.notifications = requireNonNull(notifications, "notifications is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.clock = requireNonNull(clock, "clock is null");
        this.events = requireNonNull(events, "events is null");
    }

    @Override
    @Transactional
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
        if (taskRuntimeStopped(task)) {
            return pr;
        }
        return TaskPhaseMachine.withTaskLock(task.id(), () -> {
            PR current = prService.findById(prId).orElse(pr);
            Task currentTask = taskStore.findTaskById(task.id()).orElse(task);
            if (!PR.STATUS_LOCAL_DRAFTED.equals(current.status())
                    || taskRuntimeStopped(currentTask)) {
                return current;
            }
            Optional<ReviewRound> existing = roundStore.findByTask(currentTask.id()).stream()
                    .filter(r -> ReviewRound.ORIGIN_BRAIN.equals(r.origin()))
                    .findFirst();
            if (existing.isEmpty()) {
                openBrainRound(currentTask, prId);
                return current; // still local-drafted — the loop concludes this later.
            }
            ReviewRound round = existing.get();
            if (round.isLive()) {
                return current; // review already in flight; flip happens on conclusion.
            }
            if (ReviewRound.STATUS_PAUSED.equals(round.status())) {
                return current; // operational failure; only task-scoped Resume may retry it.
            }
            // Already reviewed (approved or escalated) in an earlier call — perform
            // the deferred flip now.
            return prService.requestUserReview(prId, actor);
        });
    }

    @Override
    @Transactional
    public void reviewAfterLocalComments(String prId)
    {
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
        TaskPhaseMachine.withTaskLock(task.id(), () -> {
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
                openBrainRound(currentTask, prId);
            }
            return null;
        });
    }

    @Override
    @Transactional
    public boolean ownsParkedResume(String taskId)
    {
        List<ReviewRound> rounds = roundStore.findByTask(taskId);
        if (rounds.stream().anyMatch(round -> ReviewRound.STATUS_PAUSED.equals(round.status()))) {
            return true;
        }
        Task task = taskStore.findTaskById(taskId).orElse(null);
        if (task != null && task.status() == TaskStatus.PAUSED
                && (rounds.stream().anyMatch(ReviewRound::isLive)
                        || planSelfReviewPending(task))) {
            return true;
        }
        String parkReason = latestNeedsAttentionReason(taskId).orElse("");
        if (rounds.stream().anyMatch(ReviewRound::isLive)
                && isReviewCoordinatorReason(parkReason)) {
            return true;
        }
        return task != null
                && (task.phase() == TaskPhase.NEEDS_ATTENTION
                        || task.status() == TaskStatus.NEEDS_ATTENTION)
                && planReviewWasLatestPark(taskId)
                && stageStore.findActiveStage(taskId)
                        .filter(stage -> stage.type() == StageType.PLAN_STAGE)
                        .filter(stage -> stage.state() != StageState.CLOSED)
                        .isPresent();
    }

    @Override
    @Transactional
    public boolean pauseActiveReview(String taskId, String reason)
    {
        return TaskPhaseMachine.withTaskLock(taskId, () -> {
            ReviewRound round = roundStore.findLiveByTask(taskId).orElse(null);
            if (round != null) {
                pauseCoordinatorRound(round, reason);
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
    @Transactional
    public boolean resumeParkedReview(String taskId)
    {
        return TaskPhaseMachine.withTaskLock(taskId, () -> {
            Task task = taskStore.findTaskById(taskId).orElse(null);
            if (task == null) {
                return false;
            }
            if (task.phase() == TaskPhase.PLANNING
                    && (planReviewWasLatestPark(taskId) || planSelfReviewPending(task))) {
                return resumePlanSelfReview(task);
            }

            ReviewRound round = resumableRound(taskId).orElse(null);
            if (round == null) {
                if (task.phase() != TaskPhase.INTERNAL_REVIEW) {
                    return false;
                }
                PR pr = prService.findByTask(taskId).orElse(null);
                if (pr == null || (!PR.STATUS_LOCAL_DRAFTED.equals(pr.status())
                        && !PR.STATUS_LOCAL_OPEN.equals(pr.status()))) {
                    return false;
                }
                openBrainRound(task, pr.id());
                return taskStore.findTaskById(taskId)
                        .map(current -> !taskRuntimeStopped(current))
                        .orElse(false);
            }
            boolean brainOrigin = ReviewRound.ORIGIN_BRAIN.equals(round.origin());
            String scope = brainOrigin ? "dev" : "round";
            TaskPhase expectedPhase = brainOrigin
                    ? TaskPhase.INTERNAL_REVIEW : TaskPhase.AWAITING_REMOTE_REVIEW;
            if (task.phase() != expectedPhase) {
                return false;
            }

            // Repair a legacy half-parked row before opening the replacement
            // run. New parking already leaves the round PAUSED and its run
            // terminal, so this branch is only for older inconsistent data.
            if (round.isLive()) {
                stopRoundRuntime(task, round, "review_restarted_after_park");
            }
            AgentRun run = openResumedReviewRun(task, round);
            if (run == null) {
                parkBrainRound(task, round, "brain_review_resume_run_missing");
                return false;
            }
            boolean addressing = hasOpenBrainRoots(task);
            ReviewRound resumed = round.withRunId(run.id()).withBrainVerdict(null);
            roundStore.save(resumed);
            if (!addressing) {
                boolean valid = brainOrigin
                        ? validateLocalBrainFixes(resumed, task)
                        : validateExternalFixes(resumed, task);
                if (!valid) {
                    return false;
                }
                resumed = resumed.withStatus(ReviewRound.STATUS_TRIAGING).withIterationBumped();
            }
            else {
                resumed = resumed.withStatus(ReviewRound.STATUS_ADDRESSING);
            }
            roundStore.save(resumed);
            boolean enqueued;
            if (addressing) {
                enqueued = enqueueFixTurn(resumed, task, run, scope);
            }
            else {
                prService.recordBrainReviewStarted(task.id(), scope, resumed.iteration(), resumed.id());
                enqueued = enqueueReviewTurn(task, run, resumed.iteration());
            }
            if (!enqueued) {
                parkBrainRound(task, resumed, "brain_review_resume_failed");
                return false;
            }
            return true;
        });
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
        return stageStore.findActiveStage(task.id())
                .filter(stage -> stage.type() == StageType.PLAN_STAGE)
                .filter(stage -> stage.state() != StageState.CLOSED)
                .map(stage -> stageStore.findEventsByStage(stage.id()))
                .filter(events -> latestPlanEvent(events).filter(this::isFinalized).isPresent())
                .filter(events -> !latestPlanWasSelfReviewed(events))
                .isPresent();
    }

    private void pauseCoordinatorRound(ReviewRound round, String reason)
    {
        roundStore.save(round.withStatus(ReviewRound.STATUS_PAUSED));
        if (round.runId() != null) {
            agentRuns.findById(round.runId())
                    .filter(AgentRun::isLive)
                    .ifPresent(run -> agentRuns.pause(run.id(), reason));
        }
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
                .ifPresent(run -> agentRuns.pause(run.id(), reason));
    }

    private boolean resumePlanSelfReview(Task task)
    {
        StageInstance plan = stageStore.findActiveStage(task.id())
                .filter(stage -> stage.type() == StageType.PLAN_STAGE)
                .filter(stage -> stage.state() != StageState.CLOSED)
                .orElse(null);
        if (plan == null) {
            return false;
        }
        List<StageEvent> stageEvents = stageStore.findEventsByStage(plan.id());
        Optional<StageEvent> latestPlan = latestPlanEvent(stageEvents);
        if (latestPlan.isEmpty() || !isFinalized(latestPlan.get())
                || latestPlanWasSelfReviewed(stageEvents)) {
            return false;
        }
        String replacementRunId = replacementPlanRun(task.id(), plan.id())
                .map(AgentRun::id)
                .orElse(null);
        if (enqueuePlanSelfReview(task.id(), plan.id(), replacementRunId)) {
            return true;
        }
        parkFailedPlanReview(task.id(), plan.id());
        return false;
    }

    private Optional<AgentRun> replacementPlanRun(String taskId, UUID stageId)
    {
        return latestPlanSelfReviewTurn(taskId, stageId)
                .map(ThreadTurn::agentRunId)
                .filter(runId -> runId != null && !runId.isBlank())
                .flatMap(agentRuns::findById)
                .filter(run -> AgentRun.STATUS_PAUSED.equals(run.status()))
                .map(run -> agentRuns.restart(run.id()));
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

    private void openBrainRound(Task task, String prId)
    {
        AgentRun run = openReviewRun(task);
        boolean adoptOpenFindings = hasOpenBrainRoots(task);
        ReviewRound round = new ReviewRound(
                UUID.randomUUID().toString(), task.id(), roundStore.nextIndex(task.id()), List.of(),
                ReviewRound.STATUS_TRIAGING, ReviewRound.ReviewRoundStats.empty(),
                run.id(), now(), /* gatedAt */ null, /* postedAt */ null,
                ReviewRound.ORIGIN_BRAIN, /* brainVerdict */ null,
                adoptOpenFindings ? 0 : 1,
                ReviewRound.DEFAULT_BRAIN_BUDGET);
        roundStore.save(round);
        boolean enqueued;
        if (adoptOpenFindings) {
            enqueued = enqueueFixTurn(round, task, run, "dev");
        }
        else {
            prService.recordBrainReviewStarted(task.id(), "dev", round.iteration(), round.id());
            enqueued = enqueueReviewTurn(task, run, round.iteration());
        }
        if (!enqueued) {
            parkBrainRound(task, round, "brain_review_thread_missing");
        }
        log.info("brain-review: opened dev-end round {} for task {} (PR {})", round.id(), task.id(), prId);
    }

    private AgentRun openReviewRun(Task task)
    {
        String parentStageId = stageStore.findStagesByTask(task.id()).stream()
                .filter(s -> s.type() == StageType.DEVELOPMENT_STAGE)
                .findFirst()
                .map(s -> s.id().toString())
                .orElse(null);
        AgentRun run = agentRuns.open(
                task.id(), AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_LOCAL,
                parentStageId, StageType.REVIEW_ROUND_STAGE, /* budget */ null);
        threadStore.findBrainThreadByTask(task.id())
                .filter(thread -> thread.workspaceId() != null && !thread.workspaceId().isBlank())
                .ifPresent(thread -> agentRuns.attachOwnership(
                        run.id(), thread.workspaceId(), thread.id(), thread.provider(), thread.model(),
                        BRAIN_REVIEW_PROMPT));
        return run;
    }

    private AgentRun openResumedReviewRun(Task task, ReviewRound round)
    {
        AgentRun prior = round.runId() == null ? null : agentRuns.findById(round.runId()).orElse(null);
        if (prior != null && AgentRun.STATUS_PAUSED.equals(prior.status())) {
            return agentRuns.restart(prior.id());
        }
        if (ReviewRound.ORIGIN_BRAIN.equals(round.origin())) {
            return openReviewRun(task);
        }
        String stageId = prior == null ? null : prior.stageId();
        if (stageId != null && !stageId.isBlank()) {
            try {
                StageInstance priorStage = stageStore.findStageById(UUID.fromString(stageId)).orElse(null);
                if (priorStage == null) {
                    stageId = null;
                }
                else if (priorStage.state() == StageState.CLOSED) {
                    stageId = stageStore.reopenStage(priorStage.id()).id().toString();
                }
            }
            catch (IllegalArgumentException e) {
                stageId = null;
            }
        }
        if (stageId == null || stageId.isBlank()) {
            StageInstance remote = stageStore.findStageByType(task.id(), StageType.REMOTE_DEVELOPMENT_STAGE)
                    .orElse(null);
            if (remote == null) {
                return null;
            }
            if (remote.state() == StageState.CLOSED) {
                remote = stageStore.reopenStage(remote.id());
            }
            stageId = remote.id().toString();
        }
        return agentRuns.openInStage(
                task.id(), AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_REMOTE,
                stageId, /* budget */ null);
    }

    @Override
    @Transactional
    public void reviewBeforeRoundGate(ReviewRound round, Task task)
    {
        if (taskRuntimeStopped(task)) {
            return;
        }
        if (!validateExternalFixes(round, task)) {
            return;
        }
        AgentRun run = round.runId() == null ? null : agentRuns.findById(round.runId()).orElse(null);
        if (run == null) {
            parkBrainRound(task, round, "brain_review_run_missing");
            return;
        }
        ReviewRound triaging = round.withStatus(ReviewRound.STATUS_TRIAGING).withIterationBumped();
        roundStore.save(triaging);
        prService.recordBrainReviewStarted(task.id(), "round", triaging.iteration(), triaging.id());
        if (!enqueueReviewTurn(task, run, triaging.iteration())) {
            parkBrainRound(task, triaging, "brain_review_thread_missing");
        }
        log.info("brain-review: verification pass started for round {} (task {})", round.id(), task.id());
    }

    @Override
    @Transactional
    public void recordVerdict(String taskId, String stageId, String agentRunId, String scope, String verdict)
    {
        if ("plan".equals(scope)) {
            stageStore.recordEvent(
                    UUID.fromString(stageId), taskId, StageEventType.PLAN_SELF_REVIEWED,
                    Map.of("verdict", verdict));
            // Exactly one pass (R20), so iteration is always 1 — mirrors
            // PRServiceImpl.backfillPlanSelfReview's hardcoded value for the
            // same event, reached instead when the review finishes before
            // the local PR exists (the usual case).
            prService.recordBrainReview(
                    taskId, scope, verdict, /* iteration */ 1, /* roundId */ null);
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
        ReviewRound updated = current.withBrainVerdict(effectiveVerdict);
        roundStore.save(updated);
    }

    @Override
    public boolean isBudgetExhaustedEscalation(String taskId)
    {
        return roundStore.findByTask(taskId).stream()
                .anyMatch(round -> ReviewRound.ORIGIN_BRAIN.equals(round.origin())
                        && ReviewRound.STATUS_CLOSED.equals(round.status())
                        && ReviewRound.VERDICT_CHANGES_REQUESTED.equals(round.brainVerdict())
                        && round.brainBudgetExhausted());
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
    @EventListener
    @Transactional
    public synchronized void onTurnFinished(TaskTurnFinishedEvent event)
    {
        ThreadTurn turn = turnStore.findTurnById(event.turnId()).orElse(null);
        if (turn == null || turn.stageId() == null) {
            return;
        }
        if (event.failed() || turn.status() != ThreadTurnStatus.COMPLETED) {
            handleFailedPlanSelfReview(event.taskId(), turn);
            reconcileFailedRoundTurn(event.taskId(), turn);
            return;
        }
        advancePlanSelfReview(event.taskId(), turn);
        advanceRoundLoop(event.taskId(), turn);
    }

    @EventListener
    @Transactional
    public void onTurnBudgetPaused(TaskTurnBudgetPausedEvent event)
    {
        ThreadTurn turn = turnStore.findTurnById(event.turnId()).orElse(null);
        if (turn == null || turn.initiator() == null) {
            return;
        }
        String source = turn.initiator().source();
        TaskPhaseMachine.withTaskLock(event.taskId(), () -> {
            Task task = taskStore.findTaskById(event.taskId()).orElse(null);
            if (SOURCE_PLAN_SELF_REVIEW.equals(source)) {
                if (taskRuntimeStopped(task)) {
                    return null;
                }
                if (planSelfReviewPending(task)) {
                    pauseRun(turn.agentRunId(), PLAN_BUDGET_PAUSED);
                    phaseMachine.pause(task.id(), Actor.AGENT, PLAN_BUDGET_PAUSED);
                    taskStore.updateRuntimeFailure(task.id(), null, PLAN_BUDGET_PAUSED);
                }
                return null;
            }
            if (!SOURCE_BRAIN_REVIEW.equals(source) && !SOURCE_BRAIN_FIX.equals(source)) {
                return null;
            }
            boolean userAlreadyPaused = task != null && task.status() == TaskStatus.PAUSED;
            if (!userAlreadyPaused && taskRuntimeStopped(task)) {
                return null;
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
                return null;
            }
            String scope = ReviewRound.ORIGIN_BRAIN.equals(round.origin()) ? "dev" : "round";
            String reason;
            if (SOURCE_BRAIN_REVIEW.equals(source)) {
                reason = REVIEW_BUDGET_PAUSED;
                String verdict = effectiveBrainVerdict(round, task.id(), round.brainVerdict());
                if (!Objects.equals(verdict, round.brainVerdict())) {
                    round = round.withBrainVerdict(verdict);
                    roundStore.save(round);
                }
                prService.recordBrainReview(
                        task.id(), scope, verdict, round.iteration(),
                        round.id());
            }
            else {
                reason = FIX_BUDGET_PAUSED;
                prService.recordBrainReviewFailed(
                        task.id(), scope, round.iteration(), round.id(), reason, round.runId());
            }
            if (userAlreadyPaused) {
                return null;
            }
            pauseCoordinatorRound(round, reason);
            phaseMachine.pause(task.id(), Actor.AGENT, reason);
            taskStore.updateRuntimeFailure(task.id(), null, reason);
            return null;
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

    private void handleFailedPlanSelfReview(String taskId, ThreadTurn turn)
    {
        if (turn.initiator() == null
                || !SOURCE_PLAN_SELF_REVIEW.equals(turn.initiator().source())) {
            return;
        }
        if (taskStore.findTaskById(taskId)
                .map(BrainReviewServiceImpl::taskRuntimeStopped)
                .orElse(true)) {
            return;
        }
        UUID stageId;
        try {
            stageId = UUID.fromString(turn.stageId());
        }
        catch (IllegalArgumentException e) {
            return;
        }
        StageInstance plan = stageStore.findStageById(stageId)
                .filter(stage -> stage.type() == StageType.PLAN_STAGE)
                .filter(stage -> stage.state() != StageState.CLOSED)
                .orElse(null);
        if (plan == null) {
            return;
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
            if (!enqueuePlanSelfReview(taskId, stageId)) {
                parkFailedPlanReview(taskId, stageId);
            }
            return;
        }
        parkFailedPlanReview(taskId, stageId);
    }

    private void reconcileFailedRoundTurn(String taskId, ThreadTurn turn)
    {
        String source = turn.initiator() == null ? "" : turn.initiator().source();
        if (!SOURCE_BRAIN_REVIEW.equals(source) && !SOURCE_BRAIN_FIX.equals(source)) {
            return;
        }
        TaskPhaseMachine.withTaskLock(taskId, () -> {
            roundStore.findLiveByTask(taskId)
                    .filter(round -> matchesRunTurn(round, turn))
                    .ifPresent(this::reconcileStalledRound);
            return null;
        });
    }

    private boolean enqueuePlanSelfReview(String taskId, UUID stageId)
    {
        return enqueuePlanSelfReview(taskId, stageId, null);
    }

    private boolean enqueuePlanSelfReview(String taskId, UUID stageId, String agentRunId)
    {
        Optional<Thread> brain = threadStore.findBrainThreadByTask(taskId);
        if (brain.isEmpty()) {
            return false;
        }
        try {
            TurnInitiator initiator = TurnInitiator.unattended(SOURCE_PLAN_SELF_REVIEW);
            if (agentRunId == null) {
                scheduler.enqueueTaskTurn(
                        brain.get(), PLAN_SELF_REVIEW_PROMPT, taskId, stageId.toString(),
                        initiator, null, TurnLiveness.NARRATION);
            }
            else {
                scheduler.enqueueTaskTurn(
                        brain.get(), PLAN_SELF_REVIEW_PROMPT, taskId, stageId.toString(),
                        initiator, agentRunId, TurnLiveness.NARRATION);
            }
        }
        catch (RuntimeException e) {
            log.warn("brain-review: plan self-review enqueue failed for task {}: {}", taskId, e.getMessage());
            return false;
        }
        stageStore.recordEvent(
                stageId, taskId, StageEventType.PLAN_SELF_REVIEW_STARTED,
                Map.of("iteration", 1));
        prService.recordBrainReviewStarted(taskId, "plan", 1, null);
        return true;
    }

    private static boolean isPlanSelfReviewTurn(ThreadTurn turn)
    {
        return turn.initiator() != null
                && SOURCE_PLAN_SELF_REVIEW.equals(turn.initiator().source());
    }

    private void parkFailedPlanReview(String taskId, UUID stageId)
    {
        Task task = taskStore.findTaskById(taskId).orElse(null);
        if (taskRuntimeStopped(task)) {
            return;
        }
        stageStore.recordEvent(
                stageId, taskId, StageEventType.PLAN_FAILED,
                Map.of("error", "The mandatory Brain plan self-review failed twice."));
        phaseMachine.parkOperational(taskId, Actor.AGENT, "plan_self_review_failed");
        notifyNeedsAttention(
                task.threadId(), taskId, "{\"reason\":\"plan self-review failed twice\"}");
    }

    /** Recover a self-review whose completion event was missed, retry one
     *  failed/cancelled turn, and otherwise preserve the mandatory checkpoint. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    @Transactional
    public synchronized void reconcilePlanSelfReviews()
    {
        for (Task task : taskStore.listByPhases(List.of(TaskPhase.PLANNING), 100)) {
            if (taskRuntimeStopped(task)) {
                continue;
            }
            StageInstance plan = stageStore.findActiveStage(task.id())
                    .filter(stage -> stage.type() == StageType.PLAN_STAGE)
                    .orElse(null);
            if (plan == null) {
                continue;
            }
            List<StageEvent> events = stageStore.findEventsByStage(plan.id());
            boolean reviewed = latestPlanWasSelfReviewed(events);
            Optional<StageEvent> latestPlan = latestPlanEvent(events);
            if (reviewed || latestPlan.isEmpty() || !isFinalized(latestPlan.get())) {
                continue;
            }
            Optional<Thread> brain = threadStore.findBrainThreadByTask(task.id());
            if (brain.isEmpty()) {
                parkFailedPlanReview(task.id(), plan.id());
                continue;
            }
            Instant attemptResetAt = planAttemptResetAt(task.id(), latestPlan.get().eventAt());
            List<ThreadTurn> attempts = turnStore.listTurnsByTaskId(brain.get().id(), 50).stream()
                    .filter(candidate -> plan.id().toString().equals(candidate.stageId()))
                    .filter(BrainReviewServiceImpl::isPlanSelfReviewTurn)
                    .filter(candidate -> !candidate.createdAt().isBefore(attemptResetAt))
                    .toList();
            if (attempts.stream().anyMatch(candidate ->
                    candidate.status() == ThreadTurnStatus.QUEUED
                            || candidate.status() == ThreadTurnStatus.RUNNING)) {
                continue;
            }
            if (attempts.stream().anyMatch(candidate -> candidate.status() == ThreadTurnStatus.COMPLETED)) {
                stageStore.recordEvent(
                        plan.id(), task.id(), StageEventType.PLAN_SELF_REVIEWED,
                        Map.of("verdict", "completed_without_verdict"));
                runAutoApproveCheck(task.id(), events);
                continue;
            }
            long failures = attempts.stream().filter(candidate ->
                    candidate.status() == ThreadTurnStatus.FAILED
                            || candidate.status() == ThreadTurnStatus.CANCELLED).count();
            if (failures >= 2 || !enqueuePlanSelfReview(task.id(), plan.id())) {
                parkFailedPlanReview(task.id(), plan.id());
            }
        }
    }

    private void advancePlanSelfReview(String taskId, ThreadTurn turn)
    {
        UUID stageId;
        try {
            stageId = UUID.fromString(turn.stageId());
        }
        catch (IllegalArgumentException e) {
            return;
        }
        Optional<StageInstance> planStage = stageStore.findStageById(stageId)
                .filter(s -> s.type() == StageType.PLAN_STAGE)
                .filter(s -> s.state() != StageState.CLOSED);
        if (planStage.isEmpty()) {
            return;
        }
        if (taskStore.findTaskById(taskId)
                .map(BrainReviewServiceImpl::taskRuntimeStopped)
                .orElse(true)) {
            return;
        }
        List<StageEvent> events = stageStore.findEventsByStage(stageId);
        boolean alreadyReviewed = latestPlanWasSelfReviewed(events);
        boolean isSelfReviewTurn = SOURCE_PLAN_SELF_REVIEW.equals(turn.initiator().source());
        if (isSelfReviewTurn) {
            // The self-review turn itself just finished — proceed to the
            // review bar / auto-approve regardless of whether it recorded a
            // verdict (a forgotten record_review_verdict call must not wedge
            // planning open forever).
            if (!alreadyReviewed) {
                stageStore.recordEvent(
                        stageId, taskId, StageEventType.PLAN_SELF_REVIEWED,
                        Map.of("verdict", "completed_without_verdict"));
            }
            runAutoApproveCheck(taskId, events);
            return;
        }
        if (alreadyReviewed) {
            return; // R20: exactly one round, ever, per PlanStage.
        }
        Optional<StageEvent> latestPlan = events.stream()
                .filter(e -> e.eventType() == StageEventType.PLAN_RECORDED)
                .reduce((first, second) -> second);
        if (latestPlan.isEmpty() || !isFinalized(latestPlan.get())) {
            return;
        }
        if (enqueuePlanSelfReview(taskId, stageId)) {
            log.info("brain-review: plan self-review turn enqueued for task {}", taskId);
        }
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

    private static boolean latestPlanWasSelfReviewed(List<StageEvent> events)
    {
        boolean reviewed = false;
        for (StageEvent event : events) {
            if (event.eventType() == StageEventType.PLAN_RECORDED) {
                reviewed = false;
            }
            else if (event.eventType() == StageEventType.PLAN_SELF_REVIEWED) {
                reviewed = true;
            }
        }
        return reviewed;
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

    private void advanceRoundLoop(String taskId, ThreadTurn turn)
    {
        TaskPhaseMachine.withTaskLock(taskId, () -> {
            Optional<ReviewRound> liveOpt = roundStore.findLiveByTask(taskId)
                    .filter(r -> matchesRunTurn(r, turn));
            if (liveOpt.isEmpty()) {
                return null;
            }
            ReviewRound round = liveOpt.get();
            Task task = taskStore.findTaskById(taskId).orElse(null);
            if (taskRuntimeStopped(task)) {
                return null;
            }
            String source = turn.initiator() == null ? "" : turn.initiator().source();
            if (ReviewRound.STATUS_TRIAGING.equals(round.status()) && SOURCE_BRAIN_REVIEW.equals(source)) {
                advanceAfterReviewTurn(round, task);
            }
            else if (ReviewRound.STATUS_ADDRESSING.equals(round.status())
                    && SOURCE_BRAIN_FIX.equals(source)) {
                advanceAfterFixTurn(round, task);
            }
            return null;
        });
    }

    /** A review turn just finished — its verdict (if any) is already
     *  persisted via {@code record_review_verdict}. Decide: conclude
     *  (approved or budget spent) or loop into another fix turn. */
    private void advanceAfterReviewTurn(ReviewRound round, Task task)
    {
        if (taskRuntimeStopped(task)) {
            return;
        }
        String verdict = effectiveBrainVerdict(round, task.id(), round.brainVerdict());
        if (!Objects.equals(verdict, round.brainVerdict())) {
            round = round.withBrainVerdict(verdict);
            roundStore.save(round);
        }
        String scope = ReviewRound.ORIGIN_BRAIN.equals(round.origin()) ? "dev" : "round";
        // The orchestrator owns the finished audit row, including when the
        // agent omitted its verdict tool call or its MCP connection failed.
        prService.recordBrainReview(
                task.id(), scope, verdict, round.iteration(), round.id());
        if (verdict == null) {
            parkBrainRound(task, round, "brain_review_verdict_missing");
            return;
        }
        boolean approved = ReviewRound.VERDICT_APPROVED.equals(verdict);
        if (approved || round.brainBudgetExhausted()) {
            conclude(round, task, approved);
            return;
        }
        AgentRun run = round.runId() == null ? null : agentRuns.findById(round.runId()).orElse(null);
        if (run == null) {
            conclude(round, task, false);
            return;
        }
        task = recoverLegacyBrainGate(round, task);
        if (task == null) {
            return;
        }
        if (threadStore.findThreadById(task.threadId())
                .filter(thread -> thread.status() == ThreadStatus.IDLE)
                .isEmpty()) {
            // Thread busy; reconcileStalledRounds() re-checks once it's idle
            // rather than counting on a same-task turn to finish here — with
            // intra-thread multi-tasking a DIFFERENT task's turn can be what
            // finishes on this thread next, and that turn's onTurnFinished is
            // scoped to its own task, never this round.
            return;
        }
        enqueueFixTurn(round, task, run, scope);
    }

    /**
     * Early builds opened the user publish gate before the dev-end Brain
     * review finished. That leaves an unpushed Brain round unable to edit:
     * the task says AWAITING_PUSH/AWAITING_REVIEW and the park guard rejects
     * every fix. Retire that stale proposal and restore the canonical private
     * review state before a fix turn is scheduled. A gate already being
     * resolved is deliberately left alone so two owners cannot race it.
     */
    private Task recoverLegacyBrainGate(ReviewRound round, Task task)
    {
        if (!ReviewRound.ORIGIN_BRAIN.equals(round.origin())
                || task.pushedAt() != null
                || task.phase() != TaskPhase.AWAITING_PUSH) {
            return task;
        }
        try {
            notifications.supersedeAwaitingReviewForTask(task.threadId(), task.id());
        }
        catch (ResponseStatusException e) {
            log.warn("brain-review: task {} has a publish gate being resolved; fix turn remains parked",
                    task.id());
            return null;
        }
        if (task.status() == TaskStatus.AWAITING_REVIEW) {
            taskStore.saveTask(task.withStatus(TaskStatus.IDLE));
        }
        phaseMachine.observe(task.id(), TaskPhase.INTERNAL_REVIEW, "brain_review_resumed");
        return taskStore.findTaskById(task.id()).orElse(null);
    }

    /** A fix turn just finished addressing the brain's comments — review it
     *  again. */
    private void advanceAfterFixTurn(ReviewRound round, Task task)
    {
        if (taskRuntimeStopped(task)) {
            return;
        }
        AgentRun run = round.runId() == null ? null : agentRuns.findById(round.runId()).orElse(null);
        if (run == null) {
            conclude(round, task, false);
            return;
        }
        if (ReviewRound.ORIGIN_BRAIN.equals(round.origin())) {
            if (hasOpenBrainRoots(task)) {
                long completedFixes = attemptsSinceResume(
                        round, task, SOURCE_BRAIN_FIX, Set.of(ThreadTurnStatus.COMPLETED));
                if (completedFixes >= MAX_OPERATIONAL_TURN_FAILURES) {
                    parkBrainRound(task, round, "brain_findings_unresolved");
                    return;
                }
                Optional<Thread> owner = threadStore.findThreadById(task.threadId());
                if (owner.isEmpty()) {
                    parkBrainRound(task, round, "brain_fix_thread_missing");
                    return;
                }
                if (owner.get().status() == ThreadStatus.IDLE
                        && !enqueueFixTurn(round, task, run, "dev")) {
                    parkBrainRound(task, round, "brain_fix_enqueue_failed");
                }
                return;
            }
            if (!validateLocalBrainFixes(round, task)) {
                return;
            }
        }
        else if (!validateExternalFixes(round, task)) {
            return;
        }
        ReviewRound reviewing = round.withStatus(ReviewRound.STATUS_TRIAGING).withIterationBumped();
        if (!enqueueReviewTurn(task, run, reviewing.iteration())) {
            parkBrainRound(task, round, "brain_review_thread_missing");
            return;
        }
        roundStore.save(reviewing);
        String scope = ReviewRound.ORIGIN_BRAIN.equals(round.origin()) ? "dev" : "round";
        prService.recordBrainReviewStarted(task.id(), scope, reviewing.iteration(), reviewing.id());
    }

    private boolean validateLocalBrainFixes(ReviewRound round, Task task)
    {
        if (task.phase() != TaskPhase.INTERNAL_REVIEW) {
            return false;
        }
        ValidationPassResult result = validation.run(task.id());
        if (!result.passed()) {
            parkBrainRound(task, round, "brain_fixes_validation_failed");
        }
        return result.passed();
    }

    private boolean validateExternalFixes(ReviewRound round, Task task)
    {
        ValidationPassResult result = validation.run(task.id());
        if (!result.passed()) {
            parkBrainRound(task, round, "review_fixes_validation_failed");
        }
        return result.passed();
    }

    /**
     * Backstop for the review-fix-review loop. {@link #advanceAfterReviewTurn}
     * skips re-driving a round when its task's thread is busy, on the
     * assumption that "the next turn-finished event on it re-drives this" —
     * true when one thread ran exactly one task, false since intra-thread
     * multi-tasking landed: a DIFFERENT task's turn finishing on a shared
     * thread fires {@link #onTurnFinished} scoped to ITS OWN task only, and
     * never rechecks a sibling task's round left waiting on that same thread.
     * This sweep re-checks the persisted turn state for every live round. It
     * never infers completion from thread idleness: queued/running turns are
     * left alone, completed turns are advanced, and missing/failed turns are
     * retried without spending another review iteration.
     */
    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    @Transactional
    public void reconcileStalledRounds()
    {
        for (ReviewRound candidate : roundStore.findAllLive()) {
            TaskPhaseMachine.withTaskLock(candidate.taskId(), () -> {
                reconcileStalledRound(candidate);
                return null;
            });
        }
    }

    private void reconcileStalledRound(ReviewRound candidate)
    {
        ReviewRound round = roundStore.findById(candidate.id()).orElse(candidate);
        if (!round.isLive()) {
            return;
        }
        Task task = taskStore.findTaskById(round.taskId()).orElse(null);
        if (taskRuntimeStopped(task)) {
            return;
        }
        AgentRun run = round.runId() == null ? null : agentRuns.findById(round.runId()).orElse(null);
        if (run == null) {
            parkBrainRound(task, round, "brain_review_run_missing");
            return;
        }
        if (ReviewRound.STATUS_TRIAGING.equals(round.status())) {
            reconcileReviewTurn(round, task, run);
        }
        else if (ReviewRound.STATUS_ADDRESSING.equals(round.status())) {
            reconcileFixTurn(round, task, run);
        }
    }

    private void reconcileReviewTurn(ReviewRound round, Task task, AgentRun run)
    {
        ThreadTurn turn = latestRunTurn(round, SOURCE_BRAIN_REVIEW).orElse(null);
        if (turn == null) {
            if (!enqueueReviewTurn(task, run, round.iteration())) {
                parkBrainRound(task, round, "brain_review_thread_missing");
            }
        }
        else if (turn.status() == ThreadTurnStatus.FAILED
                || turn.status() == ThreadTurnStatus.CANCELLED) {
            if (failedAttemptsSinceResume(round, task, SOURCE_BRAIN_REVIEW)
                    >= MAX_OPERATIONAL_TURN_FAILURES) {
                parkBrainRound(task, round, "brain_review_turn_failed");
            }
            else if (!enqueueReviewTurn(task, run, round.iteration())) {
                parkBrainRound(task, round, "brain_review_thread_missing");
            }
        }
        else if (turn.status() == ThreadTurnStatus.COMPLETED) {
            advanceAfterReviewTurn(round, task);
        }
    }

    private void reconcileFixTurn(ReviewRound round, Task task, AgentRun run)
    {
        ThreadTurn turn = latestRunTurn(round, SOURCE_BRAIN_FIX).orElse(null);
        if (turn == null || turn.status() == ThreadTurnStatus.FAILED
                || turn.status() == ThreadTurnStatus.CANCELLED) {
            Optional<Thread> owner = threadStore.findThreadById(task.threadId());
            if (owner.isEmpty()) {
                parkBrainRound(task, round, "brain_fix_thread_missing");
                return;
            }
            if (owner.get().status() != ThreadStatus.IDLE) {
                return;
            }
            if (turn != null && failedAttemptsSinceResume(round, task, SOURCE_BRAIN_FIX)
                    >= MAX_OPERATIONAL_TURN_FAILURES) {
                parkBrainRound(task, round, "brain_fix_turn_failed");
                return;
            }
            String scope = ReviewRound.ORIGIN_BRAIN.equals(round.origin()) ? "dev" : "round";
            if (!enqueueFixTurn(round, task, run, scope)) {
                parkBrainRound(task, round, "brain_fix_enqueue_failed");
            }
        }
        else if (turn.status() == ThreadTurnStatus.COMPLETED) {
            advanceAfterFixTurn(round, task);
        }
    }

    private Optional<ThreadTurn> latestRunTurn(ReviewRound round, String source)
    {
        if (round.runId() == null) {
            return Optional.empty();
        }
        List<ThreadTurn> turns = turnStore.listTurnsByAgentRunId(round.runId(), 100).stream()
                .filter(turn -> matchesRunTurn(round, turn))
                .filter(turn -> turn.initiator() != null && source.equals(turn.initiator().source()))
                .toList();
        if (SOURCE_BRAIN_REVIEW.equals(source)) {
            Optional<ThreadTurn> current = turns.stream()
                    .filter(turn -> turn.input() != null
                            && turn.input().contains(reviewIterationMarker(round.iteration())))
                    .findFirst();
            if (current.isPresent()) {
                return current;
            }
            // Iteration markers predate neither old rows nor the first pass.
            // For a later iteration, an unmarked completed row is ambiguous
            // and must be retried rather than treated as current.
            return round.iteration() <= 1 ? turns.stream().findFirst() : Optional.empty();
        }
        Optional<ThreadTurn> current = turns.stream()
                .filter(turn -> turn.input() != null
                        && turn.input().contains(fixIterationMarker(round.iteration())))
                .findFirst();
        if (current.isPresent()) {
            return current;
        }
        return round.iteration() <= 1 ? turns.stream().findFirst() : Optional.empty();
    }

    private long failedAttemptsSinceResume(ReviewRound round, Task task, String source)
    {
        return attemptsSinceResume(
                round, task, source,
                Set.of(ThreadTurnStatus.FAILED, ThreadTurnStatus.CANCELLED));
    }

    private long attemptsSinceResume(
            ReviewRound round, Task task, String source, Set<ThreadTurnStatus> statuses)
    {
        Instant resetAt = taskStore.listPhaseEvents(task.id()).stream()
                .filter(event -> "user_resumed_task".equals(event.reason()))
                .map(event -> event.transitionedAt())
                .max(Instant::compareTo)
                .orElse(Instant.MIN);
        String marker = SOURCE_BRAIN_REVIEW.equals(source)
                ? reviewIterationMarker(round.iteration())
                : fixIterationMarker(round.iteration());
        List<ThreadTurn> attempts = turnStore.listTurnsByAgentRunId(round.runId(), 100).stream()
                .filter(turn -> matchesRunTurn(round, turn))
                .filter(turn -> turn.initiator() != null && source.equals(turn.initiator().source()))
                .filter(turn -> !turn.createdAt().isBefore(resetAt))
                .toList();
        boolean hasMarkedAttempts = attempts.stream()
                .anyMatch(turn -> turn.input() != null && turn.input().contains(marker));
        return attempts.stream()
                .filter(turn -> hasMarkedAttempts
                        ? turn.input() != null && turn.input().contains(marker)
                        : round.iteration() <= 1)
                .filter(turn -> statuses.contains(turn.status()))
                .count();
    }

    private void parkBrainRound(Task task, ReviewRound round, String reason)
    {
        if (taskRuntimeStopped(task)) {
            return;
        }
        stopRoundRuntime(task, round, reason);
        taskStore.saveTask(task.withErrorMessage(reason));
        phaseMachine.transition(task.id(), TaskPhase.NEEDS_ATTENTION, reason, Actor.AGENT);
        notifyNeedsAttention(task.threadId(), task.id(),
                "{\"reason\":\"" + reason + "\",\"roundId\":\"" + round.id() + "\"}");
    }

    private void stopRoundRuntime(Task task, ReviewRound round, String reason)
    {
        roundStore.save(round.withStatus(ReviewRound.STATUS_PAUSED));
        if (round.runId() != null) {
            scheduler.cancelSessionTurns(round.runId());
            agentRuns.findById(round.runId())
                    .filter(AgentRun::isLive)
                    .ifPresent(run -> agentRuns.transition(
                            run.id(), AgentRun.STATUS_FAILED, reason));
        }
        String scope = ReviewRound.ORIGIN_BRAIN.equals(round.origin()) ? "dev" : "round";
        prService.recordBrainReviewFailed(
                task.id(), scope, round.iteration(), round.id(), reason, round.runId());
    }

    private boolean enqueueFixTurn(ReviewRound round, Task task, AgentRun run, String scope)
    {
        Optional<Thread> taskThread = threadStore.findThreadById(task.threadId())
                .filter(thread -> thread.status() == ThreadStatus.IDLE);
        if (taskThread.isEmpty()) {
            return false;
        }
        try {
            scheduler.enqueueTaskTurn(
                    taskThread.get(), brainFixPrompt(task) + "\n\n" + fixIterationMarker(round.iteration()),
                    task.id(), run.stageId(),
                    TurnInitiator.unattended(SOURCE_BRAIN_FIX), run.id(), TurnLiveness.CODE);
            if (!ReviewRound.STATUS_ADDRESSING.equals(round.status())) {
                roundStore.save(round.withStatus(ReviewRound.STATUS_ADDRESSING));
            }
            prService.recordBrainReviewAddressing(
                    task.id(), scope, round.iteration(), round.id(), run.id());
            return true;
        }
        catch (RuntimeException e) {
            log.warn("brain-review: fix-turn enqueue failed for round {}: {}", round.id(), e.getMessage());
            return false;
        }
    }

    private boolean enqueueReviewTurn(Task task, AgentRun run, int iteration)
    {
        Optional<Thread> brainThread = threadStore.findBrainThreadByTask(task.id());
        if (brainThread.isEmpty()) {
            return false;
        }
        try {
            scheduler.enqueueTaskTurn(
                    brainThread.get(), BRAIN_REVIEW_PROMPT + "\n\n" + reviewIterationMarker(iteration),
                    task.id(), run.stageId(),
                    TurnInitiator.unattended(SOURCE_BRAIN_REVIEW), run.id(), TurnLiveness.NARRATION);
            return true;
        }
        catch (RuntimeException e) {
            log.warn("brain-review: review-turn enqueue failed for task {}: {}", task.id(), e.getMessage());
            return false;
        }
    }

    private static String reviewIterationMarker(int iteration)
    {
        return "[brain-review-iteration:" + iteration + "]";
    }

    private static String fixIterationMarker(int iteration)
    {
        return "[brain-fix-iteration:" + iteration + "]";
    }

    private void conclude(ReviewRound round, Task task, boolean approved)
    {
        Task currentTask = taskStore.findTaskById(task.id()).orElse(task);
        if (taskRuntimeStopped(currentTask)) {
            return;
        }
        ReviewRound.ReviewRoundStats stats = round.stats() == null
                ? ReviewRound.ReviewRoundStats.empty()
                : round.stats();
        boolean brainOrigin = ReviewRound.ORIGIN_BRAIN.equals(round.origin());
        int openBrainFindings = brainOrigin
                ? prService.findByTask(currentTask.id())
                        .map(pr -> (int) prService.comments(pr.id()).stream()
                                .filter(comment -> PRTimelineEntry.ACTOR_BRAIN.equals(comment.author()))
                                .filter(comment -> comment.parentCommentId() == null)
                                .filter(comment -> comment.resolvedAt() == null && comment.dismissedAt() == null)
                                .count())
                        .orElse(0)
                : stats.open();
        ReviewRound closed = round.withStats(new ReviewRound.ReviewRoundStats(
                        stats.fixed(), stats.replied(), stats.pushedBack(), openBrainFindings))
                .withStatus(brainOrigin
                ? ReviewRound.STATUS_CLOSED : ReviewRound.STATUS_AWAITING_GATE);
        if (ReviewRound.ORIGIN_EXTERNAL.equals(round.origin())) {
            closed = closed.withGatedAt(now());
        }
        roundStore.save(closed);
        if (round.runId() != null) {
            if (brainOrigin) {
                agentRuns.transition(round.runId(), AgentRun.STATUS_SUCCEEDED, "brain_review_concluded");
            }
            else {
                agentRuns.transition(round.runId(), AgentRun.STATUS_AWAITING_GATE, "drafts_ready");
            }
        }
        if (!approved) {
            notifyNeedsAttention(currentTask.threadId(), currentTask.id(),
                    "{\"reason\":\"brain review budget exhausted\",\"roundId\":\"" + round.id() + "\"}");
        }
        if (ReviewRound.ORIGIN_BRAIN.equals(round.origin())) {
            prService.findByTask(currentTask.id()).ifPresent(pr -> {
                if (PR.STATUS_LOCAL_DRAFTED.equals(pr.status())) {
                    prService.requestUserReview(pr.id(), "brain");
                    // Let auto_merge push automatically instead of waiting on the
                    // Local Review page's manual button — the PR just reached
                    // local-open, exactly the moment that button would appear.
                    events.publishEvent(new LocalReviewClearedEvent(currentTask.id(), pr.id(), approved));
                }
                else if (PR.STATUS_LOCAL_OPEN.equals(pr.status())
                        && currentTask.phase() == TaskPhase.INTERNAL_REVIEW) {
                    phaseMachine.transition(
                            currentTask.id(), TaskPhase.AWAITING_PUSH,
                            "local_review_reverified", Actor.AGENT);
                }
            });
        }
        log.info("brain-review: round {} concluded ({}), approved={}", round.id(), round.origin(), approved);
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

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void deliverNeedsAttention(NeedsAttentionNotice notice)
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

    private static final String PLAN_SELF_REVIEW_PROMPT =
            "Critique your own plan adversarially — wrong decomposition, a missing constraint, a "
            + "simpler alternative, understated risk. If you find something worth fixing, call "
            + "record_plan again with the revision (finalized). Either way, when you're done, call "
            + "record_review_verdict(scope='plan', verdict='approved'|'changes_requested'). Exactly "
            + "one pass — do not loop.";

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
