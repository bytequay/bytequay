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

import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.ReviewRound;
import com.bytequay.app.domain.StageEvent;
import com.bytequay.app.domain.StageEventType;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageState;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.repository.ReviewRoundStore;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.runs.AgentRunService;
import com.bytequay.app.service.threads.NotificationService;
import com.bytequay.app.service.threads.TaskTurnFinishedEvent;
import com.bytequay.app.service.threads.ThreadTurnScheduler;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
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
    private final NotificationService notifications;
    private final ObjectMapper mapper;
    private final Clock clock;

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
            NotificationService notifications,
            ObjectMapper mapper)
    {
        this(taskStore, stageStore, roundStore, agentRuns, threadStore, scheduler, turnStore, prService,
                notifications, mapper, Clock.systemUTC());
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
            NotificationService notifications,
            ObjectMapper mapper,
            Clock clock)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.roundStore = requireNonNull(roundStore, "roundStore is null");
        this.agentRuns = requireNonNull(agentRuns, "agentRuns is null");
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.scheduler = requireNonNull(scheduler, "scheduler is null");
        this.turnStore = requireNonNull(turnStore, "turnStore is null");
        this.prService = requireNonNull(prService, "prService is null");
        this.notifications = requireNonNull(notifications, "notifications is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.clock = requireNonNull(clock, "clock is null");
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
        enqueueReviewTurn(task, run.stageId());
        log.info("brain-review: opened dev-end round {} for task {} (PR {})", round.id(), task.id(), prId);
    }

    @Override
    @Transactional
    public void reviewBeforeRoundGate(ReviewRound round, Task task)
    {
        ReviewRound triaging = round.withStatus(ReviewRound.STATUS_TRIAGING).withIterationBumped();
        roundStore.save(triaging);
        AgentRun run = round.runId() == null ? null : agentRuns.findById(round.runId()).orElse(null);
        if (run == null) {
            armGate(triaging); // no run to review against — arm as before rather than stall.
            return;
        }
        enqueueReviewTurn(task, run.stageId());
        log.info("brain-review: verification pass started for round {} (task {})", round.id(), task.id());
    }

    @Override
    @Transactional
    public void recordVerdict(String taskId, String stageId, String scope, String verdict)
    {
        if ("plan".equals(scope)) {
            stageStore.recordEvent(
                    UUID.fromString(stageId), taskId, StageEventType.PLAN_SELF_REVIEWED,
                    Map.of("verdict", verdict));
            return;
        }
        Optional<ReviewRound> live = roundStore.findLiveByTask(taskId)
                .filter(r -> matchesRunStage(r, stageId));
        if (live.isEmpty()) {
            log.warn("brain-review: record_review_verdict scope={} for task {} matched no live round",
                    scope, taskId);
            return;
        }
        ReviewRound updated = live.get().withBrainVerdict(verdict);
        roundStore.save(updated);
        prService.recordBrainReview(taskId, scope, verdict, updated.iteration());
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
    public void onTurnFinished(TaskTurnFinishedEvent event)
    {
        ThreadTurn turn = turnStore.findTurnById(event.turnId()).orElse(null);
        if (turn == null || turn.stageId() == null) {
            return;
        }
        advancePlanSelfReview(event.taskId(), turn);
        advanceRoundLoop(event.taskId(), turn);
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
        boolean alreadyReviewed = events.stream()
                .anyMatch(e -> e.eventType() == StageEventType.PLAN_SELF_REVIEWED);
        boolean isSelfReviewTurn = SOURCE_PLAN_SELF_REVIEW.equals(turn.initiator().source());
        if (isSelfReviewTurn) {
            // The self-review turn itself just finished — proceed to the
            // review bar / auto-approve regardless of whether it recorded a
            // verdict (a forgotten record_review_verdict call must not wedge
            // planning open forever).
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
        Optional<Thread> brainThread = threadStore.findBrainThreadByTask(taskId);
        if (brainThread.isEmpty()) {
            return;
        }
        try {
            scheduler.enqueueTaskTurn(
                    brainThread.get(), PLAN_SELF_REVIEW_PROMPT, taskId, stageId.toString(),
                    TurnInitiator.unattended(SOURCE_PLAN_SELF_REVIEW));
            log.info("brain-review: plan self-review turn enqueued for task {}", taskId);
        }
        catch (RuntimeException e) {
            log.warn("brain-review: plan self-review enqueue failed for task {}: {}", taskId, e.getMessage());
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
                .filter(r -> matchesRunStage(r, turn.stageId()));
        if (liveOpt.isEmpty()) {
            return;
        }
        ReviewRound round = liveOpt.get();
        Task task = taskStore.findTaskById(taskId).orElse(null);
        if (task == null) {
            return;
        }
        if (ReviewRound.STATUS_TRIAGING.equals(round.status())) {
            advanceAfterReviewTurn(round, task);
        }
        else if (ReviewRound.STATUS_ADDRESSING.equals(round.status()) && round.iteration() > 0) {
            advanceAfterFixTurn(round, task);
        }
    }

    /** A review turn just finished — its verdict (if any) is already
     *  persisted via {@code record_review_verdict}. Decide: conclude
     *  (approved or budget spent) or loop into another fix turn. */
    private void advanceAfterReviewTurn(ReviewRound round, Task task)
    {
        String verdict = round.brainVerdict();
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
        Optional<Thread> taskThread = threadStore.findThreadById(task.threadId());
        if (taskThread.isEmpty() || taskThread.get().status() != ThreadStatus.IDLE) {
            return; // thread busy; the next turn-finished event on it re-drives this.
        }
        try {
            scheduler.enqueueTaskTurn(
                    taskThread.get(), BRAIN_FIX_PROMPT, task.id(), run.stageId(),
                    TurnInitiator.unattended(SOURCE_BRAIN_FIX));
            roundStore.save(round.withStatus(ReviewRound.STATUS_ADDRESSING));
        }
        catch (RuntimeException e) {
            log.warn("brain-review: fix-turn enqueue failed for round {}: {}", round.id(), e.getMessage());
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
        enqueueReviewTurn(task, run.stageId());
        roundStore.save(round.withStatus(ReviewRound.STATUS_TRIAGING).withIterationBumped());
    }

    private void enqueueReviewTurn(Task task, String stageId)
    {
        Optional<Thread> brainThread = threadStore.findBrainThreadByTask(task.id());
        if (brainThread.isEmpty()) {
            return;
        }
        try {
            scheduler.enqueueTaskTurn(
                    brainThread.get(), BRAIN_REVIEW_PROMPT, task.id(), stageId,
                    TurnInitiator.unattended(SOURCE_BRAIN_REVIEW));
        }
        catch (RuntimeException e) {
            log.warn("brain-review: review-turn enqueue failed for task {}: {}", task.id(), e.getMessage());
        }
    }

    private void conclude(ReviewRound round, Task task, boolean approved)
    {
        ReviewRound closed = round.withStatus(ReviewRound.ORIGIN_BRAIN.equals(round.origin())
                ? ReviewRound.STATUS_CLOSED : ReviewRound.STATUS_AWAITING_GATE);
        if (ReviewRound.ORIGIN_EXTERNAL.equals(round.origin())) {
            closed = closed.withGatedAt(now());
        }
        roundStore.save(closed);
        if (round.runId() != null) {
            agentRuns.transition(round.runId(), AgentRun.STATUS_SUCCEEDED, "brain_review_concluded");
        }
        if (!approved) {
            notifications.notifyNeedsAttention(task.threadId(), task.id(),
                    "{\"reason\":\"brain review budget exhausted\",\"roundId\":\"" + round.id() + "\"}");
        }
        if (ReviewRound.ORIGIN_BRAIN.equals(round.origin())) {
            prService.findByTask(task.id()).ifPresent(pr -> prService.requestUserReview(pr.id(), "brain"));
        }
        log.info("brain-review: round {} concluded ({}), approved={}", round.id(), round.origin(), approved);
    }

    private void armGate(ReviewRound round)
    {
        if (round.runId() != null) {
            agentRuns.transition(round.runId(), AgentRun.STATUS_AWAITING_GATE, "drafts_ready");
        }
        roundStore.save(round.withStatus(ReviewRound.STATUS_AWAITING_GATE).withGatedAt(now()));
    }

    private Instant now()
    {
        return Instant.now(clock);
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
            + "Address each: make the fix and commit it, or reply via record_round_reply if it's a "
            + "question, then resolve_pr_comment. Do not push.";
}
