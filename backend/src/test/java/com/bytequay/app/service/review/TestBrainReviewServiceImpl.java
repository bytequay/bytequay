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
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadMessage;
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
import com.bytequay.app.service.checks.ValidationPassResult;
import com.bytequay.app.service.checks.ValidationPassService;
import com.bytequay.app.service.localpr.LocalReviewClearedEvent;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.runs.AgentRunService;
import com.bytequay.app.service.threads.NotificationService;
import com.bytequay.app.service.threads.TaskPhaseMachine;
import com.bytequay.app.service.threads.TaskTurnFinishedEvent;
import com.bytequay.app.service.threads.ThreadTurnScheduler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
    private final ValidationPassService validation = mock(ValidationPassService.class);
    private final TaskPhaseMachine phaseMachine = mock(TaskPhaseMachine.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final BrainReviewServiceImpl service = new BrainReviewServiceImpl(
            taskStore, stageStore, roundStore, agentRuns, threadStore, scheduler, turnStore, prService,
            validation, phaseMachine, notifications, mapper, Clock.fixed(NOW, ZoneOffset.UTC), events);

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

        verify(stageStore).recordEvent(
                eq(PLAN_STAGE_ID), eq(TASK_ID), eq(StageEventType.PLAN_SELF_REVIEWED),
                eq(Map.of("verdict", "completed_without_verdict")));
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
    void failedPlanSelfReviewRetriesOnce()
    {
        ThreadTurn failed = turn(
                PLAN_STAGE_ID.toString(), TurnInitiator.unattended("brain-plan-self-review"));
        when(turnStore.findTurnById("failed-review")).thenReturn(Optional.of(failed));
        when(stageStore.findStageById(PLAN_STAGE_ID)).thenReturn(Optional.of(planStage(StageState.ACTIVE)));
        when(turnStore.listTurnsByTaskId("thread-1", 50)).thenReturn(List.of(failed));
        when(threadStore.findBrainThreadByTask(TASK_ID)).thenReturn(Optional.of(brainThread()));

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "failed-review", true));

        verify(scheduler).enqueueTaskTurn(
                any(), anyString(), eq(TASK_ID), eq(PLAN_STAGE_ID.toString()),
                argThat(initiator -> "brain-plan-self-review".equals(initiator.source())));
        verify(phaseMachine, never()).transition(eq(TASK_ID), eq(TaskPhase.NEEDS_ATTENTION), any(), any());
    }

    @Test
    void secondFailedPlanSelfReviewParksInsteadOfSkippingTheCheckpoint()
    {
        ThreadTurn failed = turn(
                PLAN_STAGE_ID.toString(), TurnInitiator.unattended("brain-plan-self-review"));
        ThreadTurn prior = runTurn(
                PLAN_STAGE_ID.toString(), "brain-plan-self-review", ThreadTurnStatus.FAILED, null);
        when(turnStore.findTurnById("failed-review")).thenReturn(Optional.of(failed));
        when(stageStore.findStageById(PLAN_STAGE_ID)).thenReturn(Optional.of(planStage(StageState.ACTIVE)));
        when(turnStore.listTurnsByTaskId("thread-1", 50)).thenReturn(List.of(failed, prior));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(taskAt(TaskPhase.PLANNING)));

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "failed-review", true));

        verify(phaseMachine).transition(
                TASK_ID, TaskPhase.NEEDS_ATTENTION, "plan_self_review_failed", Actor.AGENT);
        verify(taskStore).saveTask(argThat(task -> task.status() == TaskStatus.NEEDS_ATTENTION));
        verify(notifications).notifyNeedsAttention(eq("thread-1"), eq(TASK_ID), anyString());
        verify(scheduler, never()).enqueueTaskTurn(any(), any(), any(), any(), any());
    }

    @Test
    void reconcilerRecordsTheCheckpointWhenACompletionEventWasMissed()
    {
        Task planning = taskAt(TaskPhase.PLANNING);
        when(taskStore.listByPhases(List.of(TaskPhase.PLANNING), 100)).thenReturn(List.of(planning));
        when(stageStore.findActiveStage(TASK_ID)).thenReturn(Optional.of(planStage(StageState.ACTIVE)));
        when(stageStore.findEventsByStage(PLAN_STAGE_ID))
                .thenReturn(List.of(planRecordedEvent("finalized")));
        when(threadStore.findBrainThreadByTask(TASK_ID)).thenReturn(Optional.of(brainThread()));
        when(turnStore.listTurnsByTaskId("brain-1", 50)).thenReturn(List.of(
                turn(PLAN_STAGE_ID.toString(), TurnInitiator.unattended("brain-plan-self-review"))));

        service.reconcilePlanSelfReviews();

        verify(stageStore).recordEvent(
                eq(PLAN_STAGE_ID), eq(TASK_ID), eq(StageEventType.PLAN_SELF_REVIEWED),
                eq(Map.of("verdict", "completed_without_verdict")));
        verify(scheduler, never()).enqueueTaskTurn(any(), any(), any(), any(), any());
    }

    @Test
    void revisedFinalizedPlanRequiresANewSelfReviewTurn()
    {
        Task planning = taskAt(TaskPhase.PLANNING);
        StageEvent first = planRecordedEvent("finalized");
        StageEvent reviewed = new StageEvent(
                UUID.randomUUID(), PLAN_STAGE_ID, TASK_ID, StageEventType.PLAN_SELF_REVIEWED,
                NOW.minusSeconds(2), "{\"verdict\":\"approved\"}");
        StageEvent revised = new StageEvent(
                UUID.randomUUID(), PLAN_STAGE_ID, TASK_ID, StageEventType.PLAN_RECORDED,
                NOW, first.payloadJson());
        ThreadTurn oldReview = new ThreadTurn(
                "old-review", "brain-1", TASK_ID, ThreadResourceLane.CLI,
                ThreadTurnStatus.COMPLETED, "review", NOW.minusSeconds(3), NOW.minusSeconds(2),
                NOW.minusSeconds(3), NOW.minusSeconds(2), null,
                TurnInitiator.unattended("brain-plan-self-review"),
                PLAN_STAGE_ID.toString(), ThreadScope.STAGE);
        when(taskStore.listByPhases(List.of(TaskPhase.PLANNING), 100)).thenReturn(List.of(planning));
        when(stageStore.findActiveStage(TASK_ID)).thenReturn(Optional.of(planStage(StageState.ACTIVE)));
        when(stageStore.findEventsByStage(PLAN_STAGE_ID)).thenReturn(List.of(first, reviewed, revised));
        when(threadStore.findBrainThreadByTask(TASK_ID)).thenReturn(Optional.of(brainThread()));
        when(turnStore.listTurnsByTaskId("brain-1", 50)).thenReturn(List.of(oldReview));

        service.reconcilePlanSelfReviews();

        verify(scheduler).enqueueTaskTurn(
                any(), anyString(), eq(TASK_ID), eq(PLAN_STAGE_ID.toString()),
                argThat(initiator -> "brain-plan-self-review".equals(initiator.source())));
        verify(stageStore, never()).recordEvent(
                eq(PLAN_STAGE_ID), eq(TASK_ID), eq(StageEventType.PLAN_SELF_REVIEWED), any());
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
        verify(prService).recordBrainReview(TASK_ID, "plan", ReviewRound.VERDICT_APPROVED, 1, null, null);
    }

    @Test
    void recordVerdictForDevScopePersistsItOnTheRound()
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
        verify(prService, never()).recordBrainReview(any(), any(), any(), anyInt(), any(), any());
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
    void legacyPublishGateIsRetiredBeforeABrainFixTurnIsScheduled()
    {
        ReviewRound triaging = brainRound(ReviewRound.STATUS_TRIAGING).withBrainVerdict(
                ReviewRound.VERDICT_CHANGES_REQUESTED);
        when(turnStore.findTurnById("legacy-review")).thenReturn(Optional.of(runTurn(
                "run-stage", "brain-review", ThreadTurnStatus.COMPLETED, triaging.runId())));
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(triaging));
        Task legacy = taskAt(TaskPhase.AWAITING_PUSH).withStatus(TaskStatus.AWAITING_REVIEW);
        Task recovered = taskAt(TaskPhase.INTERNAL_REVIEW).withStatus(TaskStatus.IDLE);
        when(taskStore.findTaskById(TASK_ID))
                .thenReturn(Optional.of(legacy), Optional.of(recovered));
        AgentRun run = new AgentRun(
                triaging.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_LOCAL, null, null,
                "run-stage", AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(agentRuns.findById(triaging.runId())).thenReturn(Optional.of(run));
        when(threadStore.findThreadById(recovered.threadId())).thenReturn(Optional.of(idleTaskThread()));

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "legacy-review", false));

        verify(notifications).supersedeAwaitingReviewForTask(legacy.threadId(), TASK_ID);
        verify(taskStore).saveTask(argThat(task -> task.status() == TaskStatus.IDLE));
        verify(phaseMachine).observe(TASK_ID, TaskPhase.INTERNAL_REVIEW, "brain_review_resumed");
        verify(taskStore, times(2)).findTaskById(TASK_ID);
        verify(scheduler).enqueueTaskTurn(
                any(), anyString(), eq(TASK_ID), eq("run-stage"),
                argThat(i -> "brain-review-fix".equals(i.source())), eq(triaging.runId()));
    }

    @Test
    void legacyPublishGateBeingResolvedKeepsTheBrainFixParked()
    {
        ReviewRound triaging = brainRound(ReviewRound.STATUS_TRIAGING).withBrainVerdict(
                ReviewRound.VERDICT_CHANGES_REQUESTED);
        when(turnStore.findTurnById("resolving-review")).thenReturn(Optional.of(runTurn(
                "run-stage", "brain-review", ThreadTurnStatus.COMPLETED, triaging.runId())));
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(triaging));
        Task legacy = taskAt(TaskPhase.AWAITING_PUSH).withStatus(TaskStatus.AWAITING_REVIEW);
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(legacy));
        AgentRun run = new AgentRun(
                triaging.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_LOCAL, null, null,
                "run-stage", AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(agentRuns.findById(triaging.runId())).thenReturn(Optional.of(run));
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "resolution in progress"))
                .when(notifications).supersedeAwaitingReviewForTask(legacy.threadId(), TASK_ID);

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "resolving-review", false));

        verify(taskStore, never()).saveTask(any());
        verify(phaseMachine, never()).observe(anyString(), any(), anyString());
        verify(threadStore, never()).findThreadById(anyString());
        verify(scheduler, never()).enqueueTaskTurn(any(), anyString(), anyString(), anyString(), any(), anyString());
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
        when(threadStore.listStageMessages("run-stage")).thenReturn(List.of(reviewMessage(
                "The final reviewer response survives an MCP disconnect.")));

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "turn-6", false));

        verify(prService).recordBrainReview(
                TASK_ID, "dev", ReviewRound.VERDICT_APPROVED, 0, triaging.id(),
                "The final reviewer response survives an MCP disconnect.");
        verify(roundStore).save(argThat(r -> ReviewRound.STATUS_CLOSED.equals(r.status())));
        verify(agentRuns).transition(triaging.runId(), AgentRun.STATUS_SUCCEEDED, "brain_review_concluded");
        verify(prService).requestUserReview("pr1", "brain");
        verify(notifications, never()).notifyNeedsAttention(any(), any(), any());
        // auto_merge's push trigger listens for this instead of the manual
        // Local Review button.
        verify(events).publishEvent(new LocalReviewClearedEvent(TASK_ID, "pr1", true));
    }

    @Test
    void anApprovedExternalVerificationWaitsAtTheRoundGate()
    {
        ReviewRound triaging = round(ReviewRound.STATUS_TRIAGING)
                .withIterationBumped()
                .withBrainVerdict(ReviewRound.VERDICT_APPROVED);
        ThreadTurn reviewTurn = runTurn(
                "run-stage", "brain-review", ThreadTurnStatus.COMPLETED, triaging.runId(),
                "prompt\n\n[brain-review-iteration:1]");
        when(turnStore.findTurnById("external-review")).thenReturn(Optional.of(reviewTurn));
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(triaging));
        Task task = taskAt(TaskPhase.AWAITING_REMOTE_REVIEW);
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task));
        AgentRun run = new AgentRun(
                triaging.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_REMOTE, null, null,
                "run-stage", AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(agentRuns.findById(triaging.runId())).thenReturn(Optional.of(run));

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "external-review", false));

        verify(roundStore).save(argThat(r -> ReviewRound.STATUS_AWAITING_GATE.equals(r.status())
                && NOW.equals(r.gatedAt())));
        verify(agentRuns).transition(triaging.runId(), AgentRun.STATUS_AWAITING_GATE, "drafts_ready");
        verify(agentRuns, never()).transition(triaging.runId(), AgentRun.STATUS_SUCCEEDED, "brain_review_concluded");
        verify(prService, never()).requestUserReview(anyString(), anyString());
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
        when(prService.comments("pr1")).thenReturn(List.of(brainComment(
                "c-open", PRComment.SCOPE_FILE_LINE, "src/Foo.java", 42, "Still unresolved")));

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "turn-7", false));

        assertThat(lastIteration.brainBudgetExhausted()).isTrue();
        verify(notifications).notifyNeedsAttention(eq(task().threadId()), eq(TASK_ID), anyString());
        verify(roundStore).save(argThat(r -> ReviewRound.STATUS_CLOSED.equals(r.status())
                && r.stats().open() == 1));
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
        Task task = taskAt(TaskPhase.AWAITING_REMOTE_REVIEW);
        AgentRun run = new AgentRun(
                addressed.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_REMOTE, null, null,
                "run-stage", AgentRun.STATUS_RUNNING, 1, null, null, null, NOW, null);
        when(agentRuns.findById(addressed.runId())).thenReturn(Optional.of(run));
        when(threadStore.findBrainThreadByTask(TASK_ID)).thenReturn(Optional.of(brainThread()));
        when(validation.run(TASK_ID)).thenReturn(new ValidationPassResult(true, 0, List.of()));

        service.reviewBeforeRoundGate(addressed, task);

        verify(roundStore, times(1)).save(argThat(r -> ReviewRound.STATUS_TRIAGING.equals(r.status())));
        verify(agentRuns, never()).transition(anyString(), eq(AgentRun.STATUS_AWAITING_GATE), anyString());
        verify(scheduler).enqueueTaskTurn(
                any(), anyString(), eq(TASK_ID), eq("run-stage"),
                argThat(i -> "brain-review".equals(i.source())), anyString());
    }

    @Test
    void reviewBeforeRoundGateStopsAndParksWhenValidationFails()
    {
        ReviewRound addressed = round(ReviewRound.STATUS_ADDRESSING);
        Task task = taskAt(TaskPhase.AWAITING_REMOTE_REVIEW);
        when(validation.run(TASK_ID)).thenReturn(new ValidationPassResult(false, 3, List.of()));

        service.reviewBeforeRoundGate(addressed, task);

        verify(phaseMachine).transition(
                TASK_ID, TaskPhase.NEEDS_ATTENTION, "review_fixes_validation_failed",
                Actor.AGENT);
        verify(roundStore, never()).save(any());
        verify(scheduler, never()).enqueueTaskTurn(any(), anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void reviewBeforeRoundGateParksInsteadOfFailingOpenWhenItsRunIsMissing()
    {
        ReviewRound addressed = round(ReviewRound.STATUS_ADDRESSING);
        Task task = taskAt(TaskPhase.AWAITING_REMOTE_REVIEW);
        when(validation.run(TASK_ID)).thenReturn(new ValidationPassResult(true, 0, List.of()));

        service.reviewBeforeRoundGate(addressed, task);

        verify(phaseMachine).transition(
                TASK_ID, TaskPhase.NEEDS_ATTENTION, "brain_review_run_missing", Actor.AGENT);
        verify(notifications).notifyNeedsAttention(eq(task.threadId()), eq(TASK_ID),
                argThat(payload -> payload.contains(addressed.id())));
        verify(roundStore, never()).save(any());
        verify(agentRuns, never()).transition(anyString(), eq(AgentRun.STATUS_AWAITING_GATE), anyString());
        verify(scheduler, never()).enqueueTaskTurn(any(), anyString(), anyString(), anyString(), any(), anyString());
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
        when(turnStore.listTurnsByAgentRunId(triaging.runId(), 100)).thenReturn(List.of(
                runTurn("run-stage", "brain-review", ThreadTurnStatus.COMPLETED, triaging.runId())));

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
        when(turnStore.listTurnsByAgentRunId(triaging.runId(), 100)).thenReturn(List.of(
                runTurn("run-stage", "brain-review", ThreadTurnStatus.COMPLETED, triaging.runId())));

        service.reconcileStalledRounds();

        verify(scheduler, never()).enqueueTaskTurn(any(), anyString(), anyString(), anyString(), any());
        verify(roundStore, never()).save(any());
    }

    @Test
    void reconcileStalledRoundsAdvancesAnAddressingRoundOnceItsThreadIsIdle()
    {
        ReviewRound addressing = brainRound(ReviewRound.STATUS_ADDRESSING)
                .withIterationBumped()
                .withBrainVerdict(ReviewRound.VERDICT_CHANGES_REQUESTED);
        when(roundStore.findAllLive()).thenReturn(List.of(addressing));
        Task task = taskAt(TaskPhase.INTERNAL_REVIEW);
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task));
        AgentRun run = new AgentRun(
                addressing.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_LOCAL, null, null,
                "run-stage", AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(agentRuns.findById(addressing.runId())).thenReturn(Optional.of(run));
        when(threadStore.findThreadById(task.threadId())).thenReturn(Optional.of(idleTaskThread()));
        when(threadStore.findBrainThreadByTask(TASK_ID)).thenReturn(Optional.of(brainThread()));
        when(validation.run(TASK_ID)).thenReturn(new ValidationPassResult(true, 0, List.of()));
        when(turnStore.listTurnsByAgentRunId(addressing.runId(), 100)).thenReturn(List.of(
                runTurn("run-stage", "brain-review-fix", ThreadTurnStatus.COMPLETED, addressing.runId())));

        service.reconcileStalledRounds();

        verify(scheduler).enqueueTaskTurn(
                any(), anyString(), eq(TASK_ID), eq("run-stage"),
                argThat(i -> "brain-review".equals(i.source())), anyString());
        verify(roundStore).save(argThat(
                r -> ReviewRound.STATUS_TRIAGING.equals(r.status())
                        && r.iteration() == 2
                        && r.brainVerdict() == null));
        verify(phaseMachine, never()).transition(anyString(), any(), anyString(), any());
    }

    @Test
    void completedFixDoesNotAdvanceOrSpendAnIterationWithoutABrainThread()
    {
        ReviewRound addressing = brainRound(ReviewRound.STATUS_ADDRESSING).withIterationBumped();
        when(roundStore.findAllLive()).thenReturn(List.of(addressing));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(taskAt(TaskPhase.INTERNAL_REVIEW)));
        AgentRun run = new AgentRun(
                addressing.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_LOCAL, null, null,
                "run-stage", AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(agentRuns.findById(addressing.runId())).thenReturn(Optional.of(run));
        when(validation.run(TASK_ID)).thenReturn(new ValidationPassResult(true, 0, List.of()));
        when(turnStore.listTurnsByAgentRunId(addressing.runId(), 100)).thenReturn(List.of(
                runTurn("run-stage", "brain-review-fix", ThreadTurnStatus.COMPLETED, addressing.runId())));

        service.reconcileStalledRounds();

        verify(scheduler, never()).enqueueTaskTurn(any(), anyString(), anyString(), anyString(), any(), anyString());
        verify(roundStore, never()).save(argThat(r -> ReviewRound.STATUS_TRIAGING.equals(r.status())));
        verify(prService, never()).recordBrainReviewStarted(anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void completedFixDoesNotAdvanceOrSpendAnIterationWhenReviewEnqueueFails()
    {
        ReviewRound addressing = brainRound(ReviewRound.STATUS_ADDRESSING).withIterationBumped();
        when(roundStore.findAllLive()).thenReturn(List.of(addressing));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(taskAt(TaskPhase.INTERNAL_REVIEW)));
        AgentRun run = new AgentRun(
                addressing.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_LOCAL, null, null,
                "run-stage", AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(agentRuns.findById(addressing.runId())).thenReturn(Optional.of(run));
        when(validation.run(TASK_ID)).thenReturn(new ValidationPassResult(true, 0, List.of()));
        when(threadStore.findBrainThreadByTask(TASK_ID)).thenReturn(Optional.of(brainThread()));
        when(turnStore.listTurnsByAgentRunId(addressing.runId(), 100)).thenReturn(List.of(
                runTurn("run-stage", "brain-review-fix", ThreadTurnStatus.COMPLETED, addressing.runId())));
        when(scheduler.enqueueTaskTurn(any(), anyString(), anyString(), anyString(), any(), anyString()))
                .thenThrow(new IllegalStateException("queue unavailable"));

        service.reconcileStalledRounds();

        verify(roundStore, never()).save(argThat(r -> ReviewRound.STATUS_TRIAGING.equals(r.status())));
        verify(prService, never()).recordBrainReviewStarted(anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void reconcileDoesNotReuseACompletedReviewFromAnOlderIteration()
    {
        ReviewRound secondReview = brainRound(ReviewRound.STATUS_TRIAGING)
                .withIterationBumped()
                .withIterationBumped();
        when(roundStore.findAllLive()).thenReturn(List.of(secondReview));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task()));
        AgentRun run = new AgentRun(
                secondReview.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_LOCAL, null, null,
                "run-stage", AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(agentRuns.findById(secondReview.runId())).thenReturn(Optional.of(run));
        when(threadStore.findBrainThreadByTask(TASK_ID)).thenReturn(Optional.of(brainThread()));
        when(turnStore.listTurnsByAgentRunId(secondReview.runId(), 100)).thenReturn(List.of(
                runTurn("run-stage", "brain-review", ThreadTurnStatus.COMPLETED, secondReview.runId())));

        service.reconcileStalledRounds();

        verify(scheduler).enqueueTaskTurn(
                any(), anyString(), eq(TASK_ID), eq("run-stage"),
                argThat(i -> "brain-review".equals(i.source())), eq(secondReview.runId()));
        verify(scheduler, never()).enqueueTaskTurn(
                any(), anyString(), anyString(), anyString(),
                argThat(i -> "brain-review-fix".equals(i.source())), anyString());
        verify(roundStore, never()).save(argThat(r -> ReviewRound.STATUS_ADDRESSING.equals(r.status())));
    }

    @Test
    void reconcileAdvancesTheMarkedReviewForTheCurrentIteration()
    {
        ReviewRound secondReview = brainRound(ReviewRound.STATUS_TRIAGING)
                .withIterationBumped()
                .withIterationBumped()
                .withBrainVerdict(ReviewRound.VERDICT_CHANGES_REQUESTED);
        when(roundStore.findAllLive()).thenReturn(List.of(secondReview));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task()));
        AgentRun run = new AgentRun(
                secondReview.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_LOCAL, null, null,
                "run-stage", AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(agentRuns.findById(secondReview.runId())).thenReturn(Optional.of(run));
        when(threadStore.findThreadById(task().threadId())).thenReturn(Optional.of(idleTaskThread()));
        when(turnStore.listTurnsByAgentRunId(secondReview.runId(), 100)).thenReturn(List.of(
                runTurn("run-stage", "brain-review", ThreadTurnStatus.COMPLETED, secondReview.runId(),
                        "prompt\n\n[brain-review-iteration:2]"),
                runTurn("run-stage", "brain-review", ThreadTurnStatus.COMPLETED, secondReview.runId())));

        service.reconcileStalledRounds();

        verify(scheduler).enqueueTaskTurn(
                any(), anyString(), eq(TASK_ID), eq("run-stage"),
                argThat(i -> "brain-review-fix".equals(i.source())), eq(secondReview.runId()));
        verify(roundStore).save(argThat(r -> ReviewRound.STATUS_ADDRESSING.equals(r.status())));
    }

    @Test
    void reconcileStalledRoundsDoesNotReReviewFailedBrainFixes()
    {
        ReviewRound addressing = brainRound(ReviewRound.STATUS_ADDRESSING).withIterationBumped();
        when(roundStore.findAllLive()).thenReturn(List.of(addressing));
        Task task = taskAt(TaskPhase.INTERNAL_REVIEW);
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task));
        AgentRun run = new AgentRun(
                addressing.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_LOCAL, null, null,
                "run-stage", AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(agentRuns.findById(addressing.runId())).thenReturn(Optional.of(run));
        when(threadStore.findThreadById(task.threadId())).thenReturn(Optional.of(idleTaskThread()));
        when(validation.run(TASK_ID)).thenReturn(new ValidationPassResult(false, 3, List.of()));
        when(turnStore.listTurnsByAgentRunId(addressing.runId(), 100)).thenReturn(List.of(
                runTurn("run-stage", "brain-review-fix", ThreadTurnStatus.COMPLETED, addressing.runId())));

        service.reconcileStalledRounds();

        verify(phaseMachine).transition(
                TASK_ID, TaskPhase.NEEDS_ATTENTION, "brain_fixes_validation_failed", Actor.AGENT);
        verify(scheduler, never()).enqueueTaskTurn(any(), anyString(), anyString(), anyString(), any(), anyString());
        verify(roundStore, never()).save(argThat(r -> ReviewRound.STATUS_TRIAGING.equals(r.status())));
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
        AgentRun run = new AgentRun(
                addressing.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_LOCAL, null, null,
                "run-stage", AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(agentRuns.findById(addressing.runId())).thenReturn(Optional.of(run));
        when(turnStore.listTurnsByAgentRunId(addressing.runId(), 100)).thenReturn(List.of(
                runTurn("run-stage", "brain-review-fix", ThreadTurnStatus.RUNNING, addressing.runId())));

        service.reconcileStalledRounds();

        verify(scheduler, never()).enqueueTaskTurn(any(), anyString(), anyString(), anyString(), any());
        verify(roundStore, never()).save(any());
    }

    @Test
    void reconcileStalledRoundsDoesNotInferAQueuedExternalBrainTurnCompleted()
    {
        ReviewRound external = round(ReviewRound.STATUS_TRIAGING);
        when(roundStore.findAllLive()).thenReturn(List.of(external));
        Task task = taskAt(TaskPhase.AWAITING_REMOTE_REVIEW);
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task));
        AgentRun run = new AgentRun(
                external.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_REMOTE, null, null,
                "run-stage", AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(agentRuns.findById(external.runId())).thenReturn(Optional.of(run));
        when(turnStore.listTurnsByAgentRunId(external.runId(), 100)).thenReturn(List.of(
                runTurn("run-stage", "brain-review", ThreadTurnStatus.QUEUED, external.runId())));

        service.reconcileStalledRounds();

        verify(roundStore, never()).save(any());
        verify(scheduler, never()).enqueueTaskTurn(any(), anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void failedBrainTurnEventNeverAdvancesTheRound()
    {
        ReviewRound triaging = brainRound(ReviewRound.STATUS_TRIAGING);
        ThreadTurn failed = runTurn(
                "run-stage", "brain-review", ThreadTurnStatus.FAILED, triaging.runId());
        when(turnStore.findTurnById("failed-turn")).thenReturn(Optional.of(failed));
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(triaging));

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "failed-turn", true));

        verify(taskStore, never()).findTaskById(TASK_ID);
        verify(roundStore, never()).save(any());
    }

    @Test
    void secondFailedBrainReviewTurnParksInsteadOfRetryingForever()
    {
        ReviewRound triaging = brainRound(ReviewRound.STATUS_TRIAGING);
        Task task = task();
        when(roundStore.findAllLive()).thenReturn(List.of(triaging));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task));
        AgentRun run = new AgentRun(
                triaging.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND,
                AgentRun.SOURCE_LOCAL, null, null, "run-stage",
                AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(agentRuns.findById(triaging.runId())).thenReturn(Optional.of(run));
        when(turnStore.listTurnsByAgentRunId(triaging.runId(), 100)).thenReturn(List.of(
                runTurn("run-stage", "brain-review", ThreadTurnStatus.FAILED, triaging.runId()),
                runTurn("run-stage", "brain-review", ThreadTurnStatus.CANCELLED, triaging.runId())));

        service.reconcileStalledRounds();

        verify(phaseMachine).transition(
                TASK_ID, TaskPhase.NEEDS_ATTENTION, "brain_review_turn_failed", Actor.AGENT);
        verify(notifications).notifyNeedsAttention(eq(task.threadId()), eq(TASK_ID),
                argThat(payload -> payload.contains(triaging.id())));
        verify(scheduler, never()).enqueueTaskTurn(any(), anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void duplicateReviewCompletionCannotAdvanceTheFollowingFixIteration()
    {
        ReviewRound triaging = brainRound(ReviewRound.STATUS_TRIAGING)
                .withBrainVerdict(ReviewRound.VERDICT_CHANGES_REQUESTED);
        ReviewRound addressing = triaging.withStatus(ReviewRound.STATUS_ADDRESSING);
        ThreadTurn reviewTurn = runTurn(
                "run-stage", "brain-review", ThreadTurnStatus.COMPLETED, triaging.runId());
        when(turnStore.findTurnById("review-turn")).thenReturn(Optional.of(reviewTurn));
        when(roundStore.findLiveByTask(TASK_ID))
                .thenReturn(Optional.of(triaging), Optional.of(addressing));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task()));
        AgentRun run = new AgentRun(
                triaging.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_LOCAL, null, null,
                "run-stage", AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(agentRuns.findById(triaging.runId())).thenReturn(Optional.of(run));
        when(threadStore.findThreadById(task().threadId())).thenReturn(Optional.of(idleTaskThread()));

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "review-turn", false));
        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "review-turn", false));

        verify(scheduler, times(1)).enqueueTaskTurn(
                any(), anyString(), eq(TASK_ID), eq("run-stage"),
                argThat(i -> "brain-review-fix".equals(i.source())), eq(triaging.runId()));
        verify(roundStore, times(1)).save(argThat(r -> ReviewRound.STATUS_ADDRESSING.equals(r.status())));
        verify(validation, never()).run(TASK_ID);
    }

    // ── fixtures ─────────────────────────────────────────────────────────

    private static Task task()
    {
        return taskAt(TaskPhase.INTERNAL_REVIEW);
    }

    private static Task taskAt(TaskPhase phase)
    {
        return new Task(
                TASK_ID, "thread-1", 1L, TaskStatus.RUNNING, "dev/x", "/tmp/wt", "main", "/tmp/clone",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, NOW, null, null, null, null, null,
                null, phase, null, 0, null);
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

    private static ThreadTurn runTurn(
            String stageId, String source, ThreadTurnStatus status, String runId)
    {
        return runTurn(stageId, source, status, runId, "prompt");
    }

    private static ThreadTurn runTurn(
            String stageId, String source, ThreadTurnStatus status, String runId, String input)
    {
        return new ThreadTurn(
                "failed-turn", "thread-1", TASK_ID, ThreadResourceLane.CLI,
                status, input, NOW, NOW, NOW,
                status == ThreadTurnStatus.QUEUED || status == ThreadTurnStatus.RUNNING ? null : NOW,
                status == ThreadTurnStatus.FAILED ? "failed" : null,
                TurnInitiator.unattended(source), stageId, ThreadScope.STAGE, runId);
    }

    private static ThreadMessage reviewMessage(String body)
    {
        return new ThreadMessage(
                "message-1", "thread-1", TASK_ID, 1L, "assistant", "text",
                "{\"text\":\"" + body + "\"}", null, null, null, null, NOW,
                "run-stage", ThreadScope.STAGE);
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
