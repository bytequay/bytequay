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
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadResourceLane;
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ThreadTurnStatus;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.repository.ReviewRoundStore;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.service.localpr.LocalReviewClearedEvent;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.runs.AgentRunService;
import com.bytequay.app.service.threads.NotificationService;
import com.bytequay.app.service.threads.TaskTurnFinishedEvent;
import com.bytequay.app.service.threads.ThreadTurnScheduler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coverage for brain-driven adversarial review (plan-rail-runs.md R20-R24):
 * the plan self-review's one-shot trigger + deferred auto-approve (R20), and
 * the dev-end / round-gate review-fix-review loop with budget escalation
 * (R21-R23).
 */
class TestBrainReviewServiceImpl
{
    private static final Instant NOW = Instant.parse("2026-07-06T12:00:00Z");
    private static final String TASK_ID = "t1.k1";
    private static final UUID PLAN_STAGE_ID = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private final TaskStore taskStore = mock(TaskStore.class);
    private final StageStore stageStore = mock(StageStore.class);
    private final ReviewRoundStore roundStore = mock(ReviewRoundStore.class);
    private final AgentRunService agentRuns = mock(AgentRunService.class);
    private final ThreadStore threadStore = mock(ThreadStore.class);
    private final ThreadTurnScheduler scheduler = mock(ThreadTurnScheduler.class);
    private final ThreadTurnStore turnStore = mock(ThreadTurnStore.class);
    private final PRService prService = mock(PRService.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final BrainReviewServiceImpl service = new BrainReviewServiceImpl(
            taskStore, stageStore, roundStore, agentRuns, threadStore, scheduler, turnStore, prService,
            notifications, mapper, Clock.fixed(NOW, ZoneOffset.UTC), events);

    // ── R20: plan self-review ────────────────────────────────────────────

    @Test
    void enqueuesTheSelfReviewTurnOnceAFinalizedPlanLandsWithNoPriorReview()
    {
        when(turnStore.findTurnById("turn-1"))
                .thenReturn(Optional.of(turn(PLAN_STAGE_ID.toString(), TurnInitiator.unattended("user"))));
        when(stageStore.findStageById(PLAN_STAGE_ID)).thenReturn(Optional.of(planStage(StageState.ACTIVE)));
        when(stageStore.findEventsByStage(PLAN_STAGE_ID))
                .thenReturn(List.of(planRecordedEvent("finalized")));
        Thread brainThread = brainThread();
        when(threadStore.findBrainThreadByTask(TASK_ID)).thenReturn(Optional.of(brainThread));

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "turn-1", false));

        verify(scheduler).enqueueTaskTurn(
                eq(brainThread), anyString(), eq(TASK_ID), eq(PLAN_STAGE_ID.toString()),
                argThat(i -> "brain-plan-self-review".equals(i.source())));
    }

    @Test
    void doesNotEnqueueForADraftPlan()
    {
        when(turnStore.findTurnById("turn-1"))
                .thenReturn(Optional.of(turn(PLAN_STAGE_ID.toString(), TurnInitiator.unattended("user"))));
        when(stageStore.findStageById(PLAN_STAGE_ID)).thenReturn(Optional.of(planStage(StageState.ACTIVE)));
        when(stageStore.findEventsByStage(PLAN_STAGE_ID)).thenReturn(List.of(planRecordedEvent("suggested")));

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "turn-1", false));

        verify(scheduler, never()).enqueueTaskTurn(any(), any(), any(), any(), any());
    }

    @Test
    void neverTriggersASecondSelfReviewOnceOneHasHappened()
    {
        StageEvent reviewed = new StageEvent(
                UUID.randomUUID(), PLAN_STAGE_ID, TASK_ID, StageEventType.PLAN_SELF_REVIEWED, NOW, "{}");
        when(turnStore.findTurnById("turn-2"))
                .thenReturn(Optional.of(turn(PLAN_STAGE_ID.toString(), TurnInitiator.unattended("user"))));
        when(stageStore.findStageById(PLAN_STAGE_ID)).thenReturn(Optional.of(planStage(StageState.ACTIVE)));
        when(stageStore.findEventsByStage(PLAN_STAGE_ID))
                .thenReturn(List.of(planRecordedEvent("finalized"), reviewed));

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "turn-2", false));

        verify(scheduler, never()).enqueueTaskTurn(any(), any(), any(), any(), any());
    }

    @Test
    void theSelfReviewTurnFinishingTurnsAutoApproveOnForALowRiskLowEffortPlan()
    {
        when(turnStore.findTurnById("turn-3")).thenReturn(Optional.of(
                turn(PLAN_STAGE_ID.toString(), TurnInitiator.unattended("brain-plan-self-review"))));
        when(stageStore.findStageById(PLAN_STAGE_ID)).thenReturn(Optional.of(planStage(StageState.ACTIVE)));
        when(stageStore.findEventsByStage(PLAN_STAGE_ID))
                .thenReturn(List.of(planRecordedEvent("finalized", "low", "trivial")));

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "turn-3", false));

        verify(taskStore).setAutoApprove(TASK_ID, true);
        // The self-review turn's own completion must not re-enqueue itself.
        verify(scheduler, never()).enqueueTaskTurn(any(), any(), any(), any(), any());
    }

    @Test
    void theSelfReviewTurnFinishingLeavesAutoApproveOffForAHighRiskPlan()
    {
        when(turnStore.findTurnById("turn-4")).thenReturn(Optional.of(
                turn(PLAN_STAGE_ID.toString(), TurnInitiator.unattended("brain-plan-self-review"))));
        when(stageStore.findStageById(PLAN_STAGE_ID)).thenReturn(Optional.of(planStage(StageState.ACTIVE)));
        when(stageStore.findEventsByStage(PLAN_STAGE_ID))
                .thenReturn(List.of(planRecordedEvent("finalized", "high", "large")));

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "turn-4", false));

        verify(taskStore, never()).setAutoApprove(anyString(), any(Boolean.class));
    }

    @Test
    void recordVerdictForPlanScopeWritesTheSelfReviewedMarkerAndTheTimelineEvent()
    {
        service.recordVerdict(TASK_ID, PLAN_STAGE_ID.toString(), "plan", ReviewRound.VERDICT_APPROVED);

        verify(stageStore).recordEvent(
                eq(PLAN_STAGE_ID), eq(TASK_ID), eq(StageEventType.PLAN_SELF_REVIEWED),
                eq(Map.of("verdict", ReviewRound.VERDICT_APPROVED)));
        // Exactly one pass (R20), so iteration is always 1. A no-op when the
        // plan predates the local PR (the usual case) — PRServiceImpl backs
        // that with its own backfill, verified separately in PRServiceImpl's
        // own test.
        verify(prService).recordBrainReview(TASK_ID, "plan", ReviewRound.VERDICT_APPROVED, 1);
    }

    @Test
    void recordVerdictForDevScopePersistsItOnTheRoundAndWritesTheTimelineEvent()
    {
        // iteration is already 1 here — it's bumped when the review turn is
        // enqueued, not by this call.
        ReviewRound triaging = brainRound(ReviewRound.STATUS_TRIAGING).withIterationBumped();
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(triaging));
        AgentRun run = new AgentRun(
                triaging.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_LOCAL, null, null,
                "run-stage", AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(agentRuns.findById(triaging.runId())).thenReturn(Optional.of(run));

        service.recordVerdict(TASK_ID, "run-stage", "dev", ReviewRound.VERDICT_CHANGES_REQUESTED);

        verify(roundStore).save(argThat(r ->
                ReviewRound.VERDICT_CHANGES_REQUESTED.equals(r.brainVerdict()) && r.iteration() == 1));
        verify(prService).recordBrainReview(TASK_ID, "dev", ReviewRound.VERDICT_CHANGES_REQUESTED, 1);
    }

    // ── R21-R23: code lock-point review loop ─────────────────────────────

    @Test
    void devEndLockPointOpensABrainRoundAndLeavesThePrUnflipped()
    {
        PR drafted = PR.create("pr1", TASK_ID, "feature/x", "main", "x", "", NOW);
        when(prService.findById("pr1")).thenReturn(Optional.of(drafted));
        Task task = task();
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task));
        when(roundStore.findByTask(TASK_ID)).thenReturn(List.of());
        when(roundStore.nextIndex(TASK_ID)).thenReturn(1);
        AgentRun run = new AgentRun(
                "run1", TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_LOCAL, null, null,
                "stage1", AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(agentRuns.open(eq(TASK_ID), eq(AgentRun.KIND_REVIEW_ROUND), eq(AgentRun.SOURCE_LOCAL),
                any(), eq(StageType.REVIEW_ROUND_STAGE), any())).thenReturn(run);
        when(threadStore.findBrainThreadByTask(TASK_ID)).thenReturn(Optional.of(brainThread()));

        PR result = service.reviewBeforeLocalOpen("pr1", "claude-code");

        assertThat(result.status()).isEqualTo(PR.STATUS_LOCAL_DRAFTED);
        verify(roundStore).save(argThat(r ->
                ReviewRound.ORIGIN_BRAIN.equals(r.origin()) && ReviewRound.STATUS_TRIAGING.equals(r.status())));
        verify(prService, never()).requestUserReview(any(), any());
    }

    @Test
    void devEndLockPointLeavesThePrUnflippedWhileTheRoundIsStillLive()
    {
        PR drafted = PR.create("pr1", TASK_ID, "feature/x", "main", "x", "", NOW);
        when(prService.findById("pr1")).thenReturn(Optional.of(drafted));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task()));
        when(roundStore.findByTask(TASK_ID)).thenReturn(List.of(brainRound(ReviewRound.STATUS_TRIAGING)));

        PR result = service.reviewBeforeLocalOpen("pr1", "claude-code");

        assertThat(result.status()).isEqualTo(PR.STATUS_LOCAL_DRAFTED);
        verify(agentRuns, never()).open(any(), any(), any(), any(), any(), any());
        verify(prService, never()).requestUserReview(any(), any());
    }

    @Test
    void devEndLockPointFlipsOnceItsBrainRoundHasAlreadyConcluded()
    {
        PR drafted = PR.create("pr1", TASK_ID, "feature/x", "main", "x", "", NOW);
        PR open = drafted.withStatus(PR.STATUS_LOCAL_OPEN, NOW);
        when(prService.findById("pr1")).thenReturn(Optional.of(drafted));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task()));
        when(roundStore.findByTask(TASK_ID)).thenReturn(List.of(brainRound(ReviewRound.STATUS_CLOSED)));
        when(prService.requestUserReview("pr1", "claude-code")).thenReturn(open);

        PR result = service.reviewBeforeLocalOpen("pr1", "claude-code");

        assertThat(result.status()).isEqualTo(PR.STATUS_LOCAL_OPEN);
        verify(prService).requestUserReview("pr1", "claude-code");
    }

    @Test
    void aChangesRequestedVerdictWithBudgetRemainingEnqueuesAFixTurn()
    {
        ReviewRound triaging = brainRound(ReviewRound.STATUS_TRIAGING).withBrainVerdict(
                ReviewRound.VERDICT_CHANGES_REQUESTED);
        when(turnStore.findTurnById("turn-5")).thenReturn(Optional.of(turn("run-stage", TurnInitiator.unattended(
                "brain-review"))));
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(triaging));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task()));
        AgentRun run = new AgentRun(
                triaging.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_LOCAL, null, null,
                "run-stage", AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(agentRuns.findById(triaging.runId())).thenReturn(Optional.of(run));
        Thread taskThread = idleTaskThread();
        when(threadStore.findThreadById(task().threadId())).thenReturn(Optional.of(taskThread));
        PR drafted = PR.create("pr1", TASK_ID, "feature/x", "main", "x", "", NOW);
        when(prService.findByTask(TASK_ID)).thenReturn(Optional.of(drafted));
        when(prService.comments("pr1")).thenReturn(List.of(brainComment(
                "c1", PRComment.SCOPE_FILE_LINE, "src/Foo.java", 42, "Guard the null branch")));

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "turn-5", false));

        verify(scheduler).enqueueTaskTurn(
                eq(taskThread),
                argThat(prompt -> prompt.contains("[id: c1]")
                        && prompt.contains("src/Foo.java:42")
                          && prompt.contains("Guard the null branch")
                          && prompt.contains("parent_comment_id")
                          && prompt.contains("push back if you disagree")
                          && prompt.contains("resolution='dismissed'")
                          && !prompt.contains("record_round_reply")),
                eq(TASK_ID), eq("run-stage"),
                argThat(i -> "brain-review-fix".equals(i.source())), anyString());
        verify(roundStore).save(argThat(r -> ReviewRound.STATUS_ADDRESSING.equals(r.status())));
    }

    @Test
    void anApprovedVerdictConcludesABrainOriginRoundAndFlipsLocalOpen()
    {
        ReviewRound triaging = brainRound(ReviewRound.STATUS_TRIAGING).withBrainVerdict(
                ReviewRound.VERDICT_APPROVED);
        when(turnStore.findTurnById("turn-6")).thenReturn(Optional.of(turn("run-stage", TurnInitiator.unattended(
                "brain-review"))));
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(triaging));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task()));
        AgentRun run = new AgentRun(
                triaging.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_LOCAL, null, null,
                "run-stage", AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(agentRuns.findById(triaging.runId())).thenReturn(Optional.of(run));
        PR drafted = PR.create("pr1", TASK_ID, "feature/x", "main", "x", "", NOW);
        when(prService.findByTask(TASK_ID)).thenReturn(Optional.of(drafted));

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "turn-6", false));

        verify(roundStore).save(argThat(r -> ReviewRound.STATUS_CLOSED.equals(r.status())));
        verify(agentRuns).transition(triaging.runId(), AgentRun.STATUS_SUCCEEDED, "brain_review_concluded");
        verify(prService).requestUserReview("pr1", "brain");
        verify(notifications, never()).notifyNeedsAttention(any(), any(), any());
        // auto_merge's push trigger listens for this instead of the manual
        // Local Review button.
        verify(events).publishEvent(new LocalReviewClearedEvent(TASK_ID, "pr1", true));
    }

    @Test
    void budgetExhaustionEscalatesAndStillConcludes()
    {
        ReviewRound lastIteration = brainRound(ReviewRound.STATUS_TRIAGING)
                .withIterationBumped()
                .withIterationBumped()
                .withIterationBumped() // iteration now == budget (3)
                .withBrainVerdict(ReviewRound.VERDICT_CHANGES_REQUESTED);
        when(turnStore.findTurnById("turn-7")).thenReturn(Optional.of(turn("run-stage", TurnInitiator.unattended(
                "brain-review"))));
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(lastIteration));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task()));
        AgentRun run = new AgentRun(
                lastIteration.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_LOCAL, null, null,
                "run-stage", AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(agentRuns.findById(lastIteration.runId())).thenReturn(Optional.of(run));
        PR drafted = PR.create("pr1", TASK_ID, "feature/x", "main", "x", "", NOW);
        when(prService.findByTask(TASK_ID)).thenReturn(Optional.of(drafted));

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "turn-7", false));

        assertThat(lastIteration.brainBudgetExhausted()).isTrue();
        verify(notifications).notifyNeedsAttention(eq(task().threadId()), eq(TASK_ID), anyString());
        verify(prService).requestUserReview("pr1", "brain");
        // approved=false on an escalation — auto_merge must not push unreviewed
        // concerns straight to remote.
        verify(events).publishEvent(new LocalReviewClearedEvent(TASK_ID, "pr1", false));
    }

    @Test
    void aReviewLoopThatNeverRecordsAVerdictStillConcludesOnceBudgetIsExhausted()
    {
        // Regression: iteration must bump on every scheduled review turn, not
        // just when record_review_verdict is called — otherwise a brain agent
        // that never calls it wedges the round on STATUS_RUNNING forever.
        ReviewRound neverVerdicted = brainRound(ReviewRound.STATUS_TRIAGING)
                .withIterationBumped()
                .withIterationBumped()
                .withIterationBumped(); // iteration == budget (3), brainVerdict still null
        when(turnStore.findTurnById("turn-8")).thenReturn(Optional.of(turn("run-stage", TurnInitiator.unattended(
                "brain-review"))));
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(neverVerdicted));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task()));
        AgentRun run = new AgentRun(
                neverVerdicted.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_LOCAL, null, null,
                "run-stage", AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(agentRuns.findById(neverVerdicted.runId())).thenReturn(Optional.of(run));
        PR drafted = PR.create("pr1", TASK_ID, "feature/x", "main", "x", "", NOW);
        when(prService.findByTask(TASK_ID)).thenReturn(Optional.of(drafted));

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "turn-8", false));

        assertThat(neverVerdicted.brainVerdict()).isNull();
        verify(roundStore).save(argThat(r -> ReviewRound.STATUS_CLOSED.equals(r.status())));
        verify(agentRuns).transition(neverVerdicted.runId(), AgentRun.STATUS_SUCCEEDED, "brain_review_concluded");
        verify(notifications).notifyNeedsAttention(eq(task().threadId()), eq(TASK_ID), anyString());
    }

    @Test
    void reviewBeforeRoundGateStartsTheVerificationPassInsteadOfArmingTheGateDirectly()
    {
        ReviewRound addressed = round(ReviewRound.STATUS_ADDRESSING);
        Task task = task();
        AgentRun run = new AgentRun(
                addressed.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_REMOTE, null, null,
                "run-stage", AgentRun.STATUS_RUNNING, 1, null, null, null, NOW, null);
        when(agentRuns.findById(addressed.runId())).thenReturn(Optional.of(run));
        when(threadStore.findBrainThreadByTask(TASK_ID)).thenReturn(Optional.of(brainThread()));

        service.reviewBeforeRoundGate(addressed, task);

        verify(roundStore, times(1)).save(argThat(r -> ReviewRound.STATUS_TRIAGING.equals(r.status())));
        verify(agentRuns, never()).transition(anyString(), eq(AgentRun.STATUS_AWAITING_GATE), anyString());
        verify(scheduler).enqueueTaskTurn(
                any(), anyString(), eq(TASK_ID), eq("run-stage"),
                argThat(i -> "brain-review".equals(i.source())), anyString());
    }

    // ── reconcileStalledRounds: the intra-thread-multi-tasking backstop ───

    @Test
    void reconcileStalledRoundsAdvancesATriagingRoundOnceItsThreadIsIdle()
    {
        ReviewRound triaging = brainRound(ReviewRound.STATUS_TRIAGING);
        when(roundStore.findAllLive()).thenReturn(List.of(triaging));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task()));
        AgentRun run = new AgentRun(
                triaging.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_LOCAL, null, null,
                "run-stage", AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(agentRuns.findById(triaging.runId())).thenReturn(Optional.of(run));
        when(threadStore.findThreadById(task().threadId())).thenReturn(Optional.of(idleTaskThread()));

        service.reconcileStalledRounds();

        verify(scheduler).enqueueTaskTurn(
                any(), anyString(), eq(TASK_ID), eq("run-stage"),
                argThat(i -> "brain-review-fix".equals(i.source())), anyString());
        verify(roundStore).save(argThat(r -> ReviewRound.STATUS_ADDRESSING.equals(r.status())));
    }

    @Test
    void reconcileStalledRoundsSkipsATriagingRoundWhoseThreadIsStillBusy()
    {
        ReviewRound triaging = brainRound(ReviewRound.STATUS_TRIAGING);
        when(roundStore.findAllLive()).thenReturn(List.of(triaging));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task()));
        AgentRun run = new AgentRun(
                triaging.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_LOCAL, null, null,
                "run-stage", AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(agentRuns.findById(triaging.runId())).thenReturn(Optional.of(run));
        when(threadStore.findThreadById(task().threadId())).thenReturn(Optional.of(busyTaskThread()));

        service.reconcileStalledRounds();

        verify(scheduler, never()).enqueueTaskTurn(any(), anyString(), anyString(), anyString(), any());
        verify(roundStore, never()).save(any());
    }

    @Test
    void reconcileStalledRoundsAdvancesAnAddressingRoundOnceItsThreadIsIdle()
    {
        ReviewRound addressing = brainRound(ReviewRound.STATUS_ADDRESSING).withIterationBumped();
        when(roundStore.findAllLive()).thenReturn(List.of(addressing));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task()));
        AgentRun run = new AgentRun(
                addressing.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_LOCAL, null, null,
                "run-stage", AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(agentRuns.findById(addressing.runId())).thenReturn(Optional.of(run));
        when(threadStore.findThreadById(task().threadId())).thenReturn(Optional.of(idleTaskThread()));
        when(threadStore.findBrainThreadByTask(TASK_ID)).thenReturn(Optional.of(brainThread()));

        service.reconcileStalledRounds();

        verify(scheduler).enqueueTaskTurn(
                any(), anyString(), eq(TASK_ID), eq("run-stage"),
                argThat(i -> "brain-review".equals(i.source())), anyString());
        verify(roundStore).save(argThat(
                r -> ReviewRound.STATUS_TRIAGING.equals(r.status()) && r.iteration() == 2));
    }

    @Test
    void reconcileStalledRoundsNeverTouchesAnAddressingRoundWhoseFixTurnIsStillRunning()
    {
        // The exact bug this backstop must not introduce: re-driving a round
        // whose fix turn hasn't actually finished (advanceAfterFixTurn has no
        // idle check of its own — the sweep is the only guard).
        ReviewRound addressing = brainRound(ReviewRound.STATUS_ADDRESSING).withIterationBumped();
        when(roundStore.findAllLive()).thenReturn(List.of(addressing));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task()));
        when(threadStore.findThreadById(task().threadId())).thenReturn(Optional.of(busyTaskThread()));

        service.reconcileStalledRounds();

        verify(agentRuns, never()).findById(any());
        verify(scheduler, never()).enqueueTaskTurn(any(), anyString(), anyString(), anyString(), any());
        verify(roundStore, never()).save(any());
    }

    @Test
    void reconcileStalledRoundsIgnoresExternalOriginRounds()
    {
        ReviewRound external = round(ReviewRound.STATUS_TRIAGING);
        when(roundStore.findAllLive()).thenReturn(List.of(external));

        service.reconcileStalledRounds();

        verify(taskStore, never()).findTaskById(any());
    }

    // ── fixtures ─────────────────────────────────────────────────────────

    private static Task task()
    {
        return new Task(
                TASK_ID, "thread-1", 1L, TaskStatus.RUNNING, "dev/x", "/tmp/wt", "main", "/tmp/clone",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, NOW, null, null, null, null, null,
                null, TaskPhase.AWAITING_PUSH, null, 0, null);
    }

    private static StageInstance planStage(StageState state)
    {
        return new StageInstance(PLAN_STAGE_ID, TASK_ID, StageType.PLAN_STAGE, state, NOW, null, null);
    }

    private static StageEvent planRecordedEvent(String status)
    {
        return planRecordedEvent(status, "low", "trivial");
    }

    private static StageEvent planRecordedEvent(String status, String riskLevel, String estimatedComplexity)
    {
        ObjectNode payload = new ObjectMapper().createObjectNode();
        payload.put("status", status);
        payload.set("signals", new ObjectMapper().createObjectNode()
                .put("riskLevel", riskLevel)
                .put("estimatedComplexity", estimatedComplexity));
        return new StageEvent(UUID.randomUUID(), PLAN_STAGE_ID, TASK_ID, StageEventType.PLAN_RECORDED, NOW,
                payload.toString());
    }

    private static Thread brainThread()
    {
        return new Thread(
                "brain-1", ThreadKind.BRAIN_AGENT, "claude-code", null, "Brain",
                ThreadStatus.IDLE, "claude-sonnet-4.6", 0L, 0L, 0L, NOW, NOW,
                null, null, ThreadFlow.BUILD, "ws-default", null, null);
    }

    private static Thread idleTaskThread()
    {
        return new Thread(
                "thread-1", ThreadKind.CLI_AGENT, "claude-code", null, "Task",
                ThreadStatus.IDLE, "claude-sonnet-4.6", 0L, 0L, 0L, NOW, NOW,
                null, null, ThreadFlow.BUILD, "ws-default", null, null);
    }

    /** A thread with another turn genuinely still running on it — the
     *  intra-thread-multi-tasking case reconcileStalledRounds must not
     *  disturb. */
    private static Thread busyTaskThread()
    {
        return new Thread(
                "thread-1", ThreadKind.CLI_AGENT, "claude-code", null, "Task",
                ThreadStatus.RUNNING, "claude-sonnet-4.6", 0L, 0L, 0L, NOW, NOW,
                null, null, ThreadFlow.BUILD, "ws-default", null, null);
    }

    private static ThreadTurn turn(String stageId, TurnInitiator initiator)
    {
        return new ThreadTurn(
                "turn-x", "thread-1", TASK_ID, ThreadResourceLane.CLI,
                ThreadTurnStatus.COMPLETED, "prompt", NOW, NOW, NOW, NOW,
                null, initiator, stageId, ThreadScope.TASK);
    }

    private static ReviewRound round(String status)
    {
        boolean gated = ReviewRound.STATUS_AWAITING_GATE.equals(status) || ReviewRound.STATUS_POSTED.equals(status);
        return new ReviewRound(
                UUID.randomUUID().toString(), TASK_ID, 1, List.of(), status,
                ReviewRound.ReviewRoundStats.empty(), "run-stage-run", NOW.minusSeconds(60),
                gated ? NOW : null, null,
                ReviewRound.ORIGIN_EXTERNAL, null, 0, ReviewRound.DEFAULT_BRAIN_BUDGET);
    }

    private static ReviewRound brainRound(String status)
    {
        return new ReviewRound(
                UUID.randomUUID().toString(), TASK_ID, 1, List.of(), status,
                ReviewRound.ReviewRoundStats.empty(), "run-id-1", NOW.minusSeconds(60),
                null, null,
                ReviewRound.ORIGIN_BRAIN, null, 0, ReviewRound.DEFAULT_BRAIN_BUDGET);
    }

    private static PRComment brainComment(
            String id, String scope, String filePath, Integer lineNumber, String body)
    {
        return new PRComment(
                id, "pr1", PRComment.ORIGIN_LOCAL, scope, filePath, lineNumber,
                PRTimelineEntry.ACTOR_BRAIN, body, NOW, /* resolvedAt */ null,
                /* dismissedAt */ null, /* strippedOnPushAt */ null,
                /* parentCommentId */ null, /* publishedAt */ null,
                "RIGHT", /* startLine */ null, /* startSide */ null);
    }
}
