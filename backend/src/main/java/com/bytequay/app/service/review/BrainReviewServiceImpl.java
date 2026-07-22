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
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ThreadTurnStatus;
import com.bytequay.app.domain.TurnInitiator;
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
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.joining;

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
    private static final int MAX_OPERATIONAL_TURN_FAILURES = 2;

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
        Optional<ReviewRound> existing = roundStore.findByTask(task.id()).stream()
                .filter(r -> ReviewRound.ORIGIN_BRAIN.equals(r.origin()))
                .findFirst();
        if (existing.isEmpty()) {
            openBrainRound(task, prId);
            return pr; // still local-drafted — the loop concludes this later.
        }
        ReviewRound round = existing.get();
        if (round.isLive()) {
            return pr; // review already in flight; flip happens on conclusion.
        }
        // Already reviewed (approved or escalated) in an earlier call — perform
        // the deferred flip now.
        return prService.requestUserReview(prId, actor);
    }

    private void openBrainRound(Task task, String prId)
    {
        Optional<StageInstance> dev = stageStore.findStagesByTask(task.id()).stream()
                .filter(s -> s.type() == StageType.DEVELOPMENT_STAGE)
                .findFirst();
        String parentStageId = dev.map(s -> s.id().toString()).orElse(null);
        AgentRun run = agentRuns.open(
                task.id(), AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_LOCAL,
                parentStageId, StageType.REVIEW_ROUND_STAGE, /* budget */ null);
        ReviewRound round = new ReviewRound(
                UUID.randomUUID().toString(), task.id(), roundStore.nextIndex(task.id()), List.of(),
                ReviewRound.STATUS_TRIAGING, ReviewRound.ReviewRoundStats.empty(),
                run.id(), now(), /* gatedAt */ null, /* postedAt */ null,
                ReviewRound.ORIGIN_BRAIN, /* brainVerdict */ null, /* iteration */ 1,
                ReviewRound.DEFAULT_BRAIN_BUDGET);
        roundStore.save(round);
        prService.recordBrainReviewStarted(task.id(), "dev", round.iteration(), round.id());
        if (!enqueueReviewTurn(task, run, round.iteration())) {
            parkBrainRound(task, round, "brain_review_thread_missing");
        }
        log.info("brain-review: opened dev-end round {} for task {} (PR {})", round.id(), task.id(), prId);
    }

    @Override
    @Transactional
    public void reviewBeforeRoundGate(ReviewRound round, Task task)
    {
        if (task.status() == TaskStatus.NEEDS_ATTENTION || task.phase() == TaskPhase.NEEDS_ATTENTION) {
            return;
        }
        if (!validateExternalFixes(task)) {
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
                    taskId, scope, verdict, /* iteration */ 1, /* roundId */ null, /* body */ null);
            return;
        }
        Optional<ReviewRound> live = roundStore.findLiveByTask(taskId)
                .filter(r -> matchesRunScope(r, stageId, agentRunId));
        if (live.isEmpty()) {
            log.warn("brain-review: record_review_verdict scope={} for task {} matched no live round",
                    scope, taskId);
            return;
        }
        ReviewRound updated = live.get().withBrainVerdict(verdict);
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
            return;
        }
        advancePlanSelfReview(event.taskId(), turn);
        advanceRoundLoop(event.taskId(), turn);
    }

    private void handleFailedPlanSelfReview(String taskId, ThreadTurn turn)
    {
        if (turn.initiator() == null
                || !SOURCE_PLAN_SELF_REVIEW.equals(turn.initiator().source())) {
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
        boolean priorAttempt = turnStore.listTurnsByTaskId(turn.threadId(), 50).stream()
                .filter(candidate -> !candidate.id().equals(turn.id()))
                .filter(candidate -> stageId.toString().equals(candidate.stageId()))
                .filter(candidate -> !candidate.createdAt().isBefore(latestPlanAt))
                .anyMatch(BrainReviewServiceImpl::isPlanSelfReviewTurn);
        if (!priorAttempt) {
            if (!enqueuePlanSelfReview(taskId, stageId)) {
                parkFailedPlanReview(taskId, stageId);
            }
            return;
        }
        parkFailedPlanReview(taskId, stageId);
    }

    private boolean enqueuePlanSelfReview(String taskId, UUID stageId)
    {
        Optional<Thread> brain = threadStore.findBrainThreadByTask(taskId);
        if (brain.isEmpty()) {
            return false;
        }
        try {
            scheduler.enqueueTaskTurn(
                    brain.get(), PLAN_SELF_REVIEW_PROMPT, taskId, stageId.toString(),
                    TurnInitiator.unattended(SOURCE_PLAN_SELF_REVIEW));
            return true;
        }
        catch (RuntimeException e) {
            log.warn("brain-review: plan self-review enqueue failed for task {}: {}", taskId, e.getMessage());
            return false;
        }
    }

    private static boolean isPlanSelfReviewTurn(ThreadTurn turn)
    {
        return turn.initiator() != null
                && SOURCE_PLAN_SELF_REVIEW.equals(turn.initiator().source());
    }

    private void parkFailedPlanReview(String taskId, UUID stageId)
    {
        Task task = taskStore.findTaskById(taskId).orElse(null);
        if (task == null || task.phase() == TaskPhase.COMPLETED) {
            return;
        }
        stageStore.recordEvent(
                stageId, taskId, StageEventType.PLAN_FAILED,
                Map.of("error", "The mandatory Brain plan self-review failed twice."));
        if (task.phase() != TaskPhase.NEEDS_ATTENTION) {
            phaseMachine.transition(
                    taskId, TaskPhase.NEEDS_ATTENTION, "plan_self_review_failed", Actor.AGENT);
        }
        if (task.status() != TaskStatus.NEEDS_ATTENTION) {
            taskStore.saveTask(task.withStatus(TaskStatus.NEEDS_ATTENTION));
        }
        notifications.notifyNeedsAttention(
                task.threadId(), taskId, "{\"reason\":\"plan self-review failed twice\"}");
    }

    /** Recover a self-review whose completion event was missed, retry one
     *  failed/cancelled turn, and otherwise preserve the mandatory checkpoint. */
    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
    @Transactional
    public synchronized void reconcilePlanSelfReviews()
    {
        for (Task task : taskStore.listByPhases(List.of(TaskPhase.PLANNING), 100)) {
            if (task.status() == TaskStatus.NEEDS_ATTENTION) {
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
            List<ThreadTurn> attempts = turnStore.listTurnsByTaskId(brain.get().id(), 50).stream()
                    .filter(candidate -> plan.id().toString().equals(candidate.stageId()))
                    .filter(BrainReviewServiceImpl::isPlanSelfReviewTurn)
                    .filter(candidate -> !candidate.createdAt().isBefore(latestPlan.get().eventAt()))
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
        Optional<ReviewRound> liveOpt = roundStore.findLiveByTask(taskId)
                .filter(r -> matchesRunTurn(r, turn));
        if (liveOpt.isEmpty()) {
            return;
        }
        ReviewRound round = liveOpt.get();
        Task task = taskStore.findTaskById(taskId).orElse(null);
        if (task == null) {
            return;
        }
        String source = turn.initiator() == null ? "" : turn.initiator().source();
        if (ReviewRound.STATUS_TRIAGING.equals(round.status()) && SOURCE_BRAIN_REVIEW.equals(source)) {
            advanceAfterReviewTurn(round, task, turn);
        }
        else if (ReviewRound.STATUS_ADDRESSING.equals(round.status()) && round.iteration() > 0
                && SOURCE_BRAIN_FIX.equals(source)) {
            advanceAfterFixTurn(round, task);
        }
    }

    /** A review turn just finished — its verdict (if any) is already
     *  persisted via {@code record_review_verdict}. Decide: conclude
     *  (approved or budget spent) or loop into another fix turn. */
    private void advanceAfterReviewTurn(ReviewRound round, Task task, ThreadTurn turn)
    {
        String verdict = round.brainVerdict();
        String scope = ReviewRound.ORIGIN_BRAIN.equals(round.origin()) ? "dev" : "round";
        // The orchestrator owns the finished audit row so the final review
        // text survives even when the agent omitted its verdict tool call or
        // its MCP connection failed.
        prService.recordBrainReview(
                task.id(), scope, verdict, round.iteration(), round.id(), turn == null ? null : reviewBody(turn));
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

    /** Preserve the reviewer's written conclusion even when its MCP tools
     *  were unavailable and no structured comments/verdict could be saved. */
    private String reviewBody(ThreadTurn turn)
    {
        List<ThreadMessage> messages = threadStore.listStageMessages(turn.stageId());
        if (messages.isEmpty()) {
            messages = threadStore.listMessages(turn.threadId()).stream()
                    .filter(message -> turn.stageId().equals(message.stageId()))
                    .toList();
        }
        String body = messages.stream()
                .filter(message -> "assistant".equals(message.role()) && "text".equals(message.type()))
                .filter(message -> turn.taskId().equals(message.taskId()))
                .filter(message -> turn.startedAt() == null || !message.ts().isBefore(turn.startedAt()))
                .filter(message -> turn.finishedAt() == null || !message.ts().isAfter(turn.finishedAt()))
                .map(this::messageText)
                .filter(text -> !text.isBlank())
                .collect(joining("\n\n"));
        return body.isBlank() ? null : body;
    }

    private String messageText(ThreadMessage message)
    {
        try {
            return mapper.readTree(message.contentJson()).path("text").asText("");
        }
        catch (JsonProcessingException | RuntimeException e) {
            return "";
        }
    }

    /** A fix turn just finished addressing the brain's comments — review it
     *  again. */
    private void advanceAfterFixTurn(ReviewRound round, Task task)
    {
        AgentRun run = round.runId() == null ? null : agentRuns.findById(round.runId()).orElse(null);
        if (run == null) {
            conclude(round, task, false);
            return;
        }
        if (ReviewRound.ORIGIN_BRAIN.equals(round.origin())) {
            if (!validateLocalBrainFixes(task)) {
                return;
            }
        }
        else if (!validateExternalFixes(task)) {
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

    private boolean validateLocalBrainFixes(Task task)
    {
        if (task.phase() != TaskPhase.INTERNAL_REVIEW) {
            return false;
        }
        ValidationPassResult result = validation.run(task.id());
        if (!result.passed()) {
            phaseMachine.transition(
                    task.id(), TaskPhase.NEEDS_ATTENTION, "brain_fixes_validation_failed", Actor.AGENT);
        }
        return result.passed();
    }

    private boolean validateExternalFixes(Task task)
    {
        ValidationPassResult result = validation.run(task.id());
        if (!result.passed()) {
            phaseMachine.transition(
                    task.id(), TaskPhase.NEEDS_ATTENTION, "review_fixes_validation_failed", Actor.AGENT);
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
    public synchronized void reconcileStalledRounds()
    {
        for (ReviewRound candidate : roundStore.findAllLive()) {
            ReviewRound round = roundStore.findById(candidate.id()).orElse(candidate);
            if (!round.isLive()) {
                continue;
            }
            Task task = taskStore.findTaskById(round.taskId()).orElse(null);
            if (task == null || task.status() == TaskStatus.NEEDS_ATTENTION
                    || task.phase() == TaskPhase.NEEDS_ATTENTION) {
                continue;
            }
            AgentRun run = round.runId() == null ? null : agentRuns.findById(round.runId()).orElse(null);
            if (run == null) {
                parkBrainRound(task, round, "brain_review_run_missing");
                continue;
            }
            if (ReviewRound.STATUS_TRIAGING.equals(round.status())) {
                reconcileReviewTurn(round, task, run);
            }
            else if (ReviewRound.STATUS_ADDRESSING.equals(round.status()) && round.iteration() > 0) {
                reconcileFixTurn(round, task, run);
            }
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
            advanceAfterReviewTurn(round, task, turn);
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
                .filter(turn -> turn.status() == ThreadTurnStatus.FAILED
                        || turn.status() == ThreadTurnStatus.CANCELLED)
                .count();
    }

    private void parkBrainRound(Task task, ReviewRound round, String reason)
    {
        if (task.phase() == TaskPhase.COMPLETED
                || task.status() == TaskStatus.COMPLETED
                || task.status() == TaskStatus.REMOTE_CLOSED
                || task.status() == TaskStatus.CANCELED
                || task.status() == TaskStatus.ERRORED
                || task.phase() == TaskPhase.NEEDS_ATTENTION
                || task.status() == TaskStatus.NEEDS_ATTENTION) {
            return;
        }
        phaseMachine.transition(task.id(), TaskPhase.NEEDS_ATTENTION, reason, Actor.AGENT);
        notifications.notifyNeedsAttention(task.threadId(), task.id(),
                "{\"reason\":\"" + reason + "\",\"roundId\":\"" + round.id() + "\"}");
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
                    TurnInitiator.unattended(SOURCE_BRAIN_FIX), run.id());
            if (!ReviewRound.STATUS_ADDRESSING.equals(round.status())) {
                roundStore.save(round.withStatus(ReviewRound.STATUS_ADDRESSING));
                prService.recordBrainReviewAddressing(task.id(), scope, round.iteration(), round.id());
            }
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
                    TurnInitiator.unattended(SOURCE_BRAIN_REVIEW), run.id());
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
        ReviewRound.ReviewRoundStats stats = round.stats() == null
                ? ReviewRound.ReviewRoundStats.empty()
                : round.stats();
        boolean brainOrigin = ReviewRound.ORIGIN_BRAIN.equals(round.origin());
        int openBrainFindings = brainOrigin
                ? prService.findByTask(task.id())
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
            notifications.notifyNeedsAttention(task.threadId(), task.id(),
                    "{\"reason\":\"brain review budget exhausted\",\"roundId\":\"" + round.id() + "\"}");
        }
        if (ReviewRound.ORIGIN_BRAIN.equals(round.origin())) {
            prService.findByTask(task.id()).ifPresent(pr -> {
                prService.requestUserReview(pr.id(), "brain");
                // Let auto_merge push automatically instead of waiting on the
                // Local Review page's manual button — the PR just reached
                // local-open, exactly the moment that button would appear.
                events.publishEvent(new LocalReviewClearedEvent(task.id(), pr.id(), approved));
            });
        }
        log.info("brain-review: round {} concluded ({}), approved={}", round.id(), round.origin(), approved);
    }

    private Instant now()
    {
        return Instant.now(clock);
    }

    private String brainFixPrompt(Task task)
    {
        StringBuilder out = new StringBuilder(BRAIN_FIX_PROMPT)
                .append("\n\nOpen brain comments:\n");
        List<PRComment> comments = prService.findByTask(task.id())
                .map(pr -> prService.comments(pr.id()).stream()
                        .filter(BrainReviewServiceImpl::isOpenBrainComment)
                        .toList())
                .orElse(List.of());
        if (comments.isEmpty()) {
            out.append("- No open brain comments are currently recorded. Re-read the diff and continue if needed.\n");
            return out.toString();
        }
        int i = 1;
        for (PRComment comment : comments) {
            out.append(i++).append(". [id: ").append(comment.id()).append("] ");
            if (comment.filePath() != null) {
                out.append(comment.filePath());
                if (comment.lineNumber() != null) {
                    out.append(':').append(comment.lineNumber());
                }
                out.append(' ');
            }
            out.append(comment.body() == null ? "" : comment.body().strip()).append('\n');
        }
        return out.toString();
    }

    private static boolean isOpenBrainComment(PRComment comment)
    {
        return PRTimelineEntry.ACTOR_BRAIN.equals(comment.author())
                && comment.resolvedAt() == null
                && comment.dismissedAt() == null;
    }

    private static final String PLAN_SELF_REVIEW_PROMPT =
            "Critique your own plan adversarially — wrong decomposition, a missing constraint, a "
            + "simpler alternative, understated risk. If you find something worth fixing, call "
            + "record_plan again with the revision (finalized). Either way, when you're done, call "
            + "record_review_verdict(scope='plan', verdict='approved'|'changes_requested'). Exactly "
            + "one pass — do not loop.";

    private static final String BRAIN_REVIEW_PROMPT =
            "Adversarially review the current diff before it goes to the user (or before this round's "
            + "gate arms). Use read_dev_report / read_dev_conversation / read_diff_summary to see what "
            + "changed and why. Leave any concerns with record_pr_comment (they stay local — never "
            + "posted to GitHub). When done, call record_review_verdict(scope, "
            + "verdict='approved'|'changes_requested').";

    private static final String BRAIN_FIX_PROMPT =
            "The brain left review comments on this diff (local only — see the PR's open comments). "
            + "For each comment, decide deliberately: make the fix and commit it, reply via "
            + "record_pr_comment with parent_comment_id if it's a question or explanation, or "
            + "push back if you disagree by replying with your reasoning. Then call "
            + "resolve_pr_comment with resolution='addressed' for handled comments or "
            + "resolution='dismissed' for justified pushback. Do not push.";
}
