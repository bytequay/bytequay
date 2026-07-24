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
import com.bytequay.app.domain.TaskPhaseEvent;
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
import com.bytequay.app.service.threads.TaskTurnBudgetPausedEvent;
import com.bytequay.app.service.threads.TaskTurnFinishedEvent;
import com.bytequay.app.service.threads.ThreadTurnScheduler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(taskAt(TaskPhase.PLANNING)));
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
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(taskAt(TaskPhase.PLANNING)));
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
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(taskAt(TaskPhase.PLANNING)));
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
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(taskAt(TaskPhase.PLANNING)));
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
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(taskAt(TaskPhase.PLANNING)));
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
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(taskAt(TaskPhase.PLANNING)));
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
    void resumedPlanSelfReviewIgnoresFailuresFromBeforeTheUserResume()
    {
        Instant resumedAt = NOW.plusSeconds(1);
        ThreadTurn current = new ThreadTurn(
                "resumed-review", "thread-1", TASK_ID, ThreadResourceLane.CLI,
                ThreadTurnStatus.FAILED, "review", NOW.plusSeconds(2), NOW.plusSeconds(2),
                NOW.plusSeconds(2), NOW.plusSeconds(3), "failed",
                TurnInitiator.unattended("brain-plan-self-review"),
                PLAN_STAGE_ID.toString(), ThreadScope.STAGE, null);
        ThreadTurn oldOne = runTurn(
                PLAN_STAGE_ID.toString(), "brain-plan-self-review", ThreadTurnStatus.FAILED, null);
        ThreadTurn oldTwo = runTurn(
                PLAN_STAGE_ID.toString(), "brain-plan-self-review", ThreadTurnStatus.CANCELLED, null);
        when(turnStore.findTurnById(current.id())).thenReturn(Optional.of(current));
        when(stageStore.findStageById(PLAN_STAGE_ID)).thenReturn(Optional.of(planStage(StageState.ACTIVE)));
        when(stageStore.findEventsByStage(PLAN_STAGE_ID))
                .thenReturn(List.of(planRecordedEvent("finalized")));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(taskAt(TaskPhase.PLANNING)));
        when(taskStore.listPhaseEvents(TASK_ID)).thenReturn(List.of(new TaskPhaseEvent(
                1L, TASK_ID, TaskPhase.NEEDS_ATTENTION, TaskPhase.PLANNING,
                resumedAt, "user_resumed_task", Actor.HUMAN)));
        when(turnStore.listTurnsByTaskId("thread-1", 50))
                .thenReturn(List.of(oldOne, oldTwo, current));
        when(threadStore.findBrainThreadByTask(TASK_ID)).thenReturn(Optional.of(brainThread()));

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, current.id(), true));

        verify(scheduler).enqueueTaskTurn(
                any(), anyString(), eq(TASK_ID), eq(PLAN_STAGE_ID.toString()),
                argThat(initiator -> "brain-plan-self-review".equals(initiator.source())));
        verify(phaseMachine, never()).transition(
                eq(TASK_ID), eq(TaskPhase.NEEDS_ATTENTION), anyString(), any());
    }

    @Test
    void taskResumeImmediatelyRequeuesTheParkedPlanReview()
    {
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(taskAt(TaskPhase.PLANNING)));
        when(taskStore.listPhaseEvents(TASK_ID)).thenReturn(List.of(new TaskPhaseEvent(
                1L, TASK_ID, TaskPhase.PLANNING, TaskPhase.NEEDS_ATTENTION,
                NOW, "plan_self_review_failed", Actor.AGENT)));
        when(stageStore.findActiveStage(TASK_ID)).thenReturn(Optional.of(planStage(StageState.ACTIVE)));
        when(stageStore.findEventsByStage(PLAN_STAGE_ID))
                .thenReturn(List.of(planRecordedEvent("finalized")));
        when(threadStore.findBrainThreadByTask(TASK_ID)).thenReturn(Optional.of(brainThread()));

        assertThat(service.resumeParkedReview(TASK_ID)).isTrue();

        verify(scheduler).enqueueTaskTurn(
                any(), anyString(), eq(TASK_ID), eq(PLAN_STAGE_ID.toString()),
                argThat(initiator -> "brain-plan-self-review".equals(initiator.source())));
    }

    @Test
    void pausedPlanSelfReviewIsOwnedAndImmediatelyRestartedOnTaskResume()
    {
        Task pausedTask = taskAt(TaskPhase.PLANNING).withStatus(TaskStatus.PAUSED);
        ThreadTurn cancelled = new ThreadTurn(
                "paused-plan-turn", "brain-1", TASK_ID, ThreadResourceLane.CLI,
                ThreadTurnStatus.CANCELLED, "review", NOW, NOW, NOW, NOW,
                "cancelled by task pause", TurnInitiator.unattended("brain-plan-self-review"),
                PLAN_STAGE_ID.toString(), ThreadScope.STAGE, "paused-plan-run");
        AgentRun running = new AgentRun(
                "paused-plan-run", TASK_ID, "plan", AgentRun.SOURCE_SCHEDULED,
                PLAN_STAGE_ID.toString(), null, PLAN_STAGE_ID.toString(),
                AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        AgentRun pausedRun = running.paused("user_paused_task");
        AgentRun replacement = new AgentRun(
                "replacement-plan-run", TASK_ID, "plan", AgentRun.SOURCE_SCHEDULED,
                PLAN_STAGE_ID.toString(), null, PLAN_STAGE_ID.toString(),
                AgentRun.STATUS_QUEUED, 0, null, null, null, NOW, null);
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(pausedTask));
        when(stageStore.findActiveStage(TASK_ID)).thenReturn(Optional.of(planStage(StageState.ACTIVE)));
        when(stageStore.findEventsByStage(PLAN_STAGE_ID))
                .thenReturn(List.of(planRecordedEvent("finalized")));
        when(threadStore.findBrainThreadByTask(TASK_ID)).thenReturn(Optional.of(brainThread()));
        when(turnStore.listTurnsByTaskId("brain-1", 50)).thenReturn(List.of(cancelled));
        when(agentRuns.findById(running.id()))
                .thenReturn(Optional.of(running), Optional.of(pausedRun));
        when(agentRuns.restart(pausedRun.id())).thenReturn(replacement);

        assertThat(service.pauseActiveReview(TASK_ID, "user_paused_task")).isTrue();
        assertThat(service.ownsParkedResume(TASK_ID)).isTrue();
        assertThat(service.resumeParkedReview(TASK_ID)).isTrue();

        verify(agentRuns).pause(running.id(), "user_paused_task");
        verify(agentRuns).restart(pausedRun.id());
        verify(scheduler).enqueueTaskTurn(
                any(), anyString(), eq(TASK_ID), eq(PLAN_STAGE_ID.toString()),
                argThat(initiator -> "brain-plan-self-review".equals(initiator.source())),
                eq(replacement.id()));
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
                ReviewRound.ORIGIN_BRAIN.equals(r.origin())
                        && ReviewRound.STATUS_TRIAGING.equals(r.status())
                        && r.budget() == 5));
        verify(agentRuns).attachOwnership(
                eq("run1"), eq("ws-default"), eq("brain-1"), eq("claude-code"),
                eq("claude-sonnet-4.6"), anyString());
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
    void devEndLockPointLeavesThePrUnflippedWhileTheRoundIsParked()
    {
        PR drafted = PR.create("pr1", TASK_ID, "feature/x", "main", "x", "", NOW);
        when(prService.findById("pr1")).thenReturn(Optional.of(drafted));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task()));
        when(roundStore.findByTask(TASK_ID))
                .thenReturn(List.of(brainRound(ReviewRound.STATUS_PAUSED)));

        PR result = service.reviewBeforeLocalOpen("pr1", "claude-code");

        assertThat(result.status()).isEqualTo(PR.STATUS_LOCAL_DRAFTED);
        verify(prService, never()).requestUserReview(any(), any());
    }

    @Test
    void taskPauseProjectsTheCoordinatorRoundAndRunAsPausedWithoutAFailureEvent()
    {
        ReviewRound live = brainRound(ReviewRound.STATUS_TRIAGING);
        AgentRun run = new AgentRun(
                live.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND,
                AgentRun.SOURCE_LOCAL, null, null, "run-stage",
                AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(live));
        when(agentRuns.findById(live.runId())).thenReturn(Optional.of(run));

        assertThat(service.pauseActiveReview(TASK_ID, "user_paused_task")).isTrue();

        verify(roundStore).save(argThat(round -> ReviewRound.STATUS_PAUSED.equals(round.status())));
        verify(agentRuns).pause(live.runId(), "user_paused_task");
        verify(prService, never()).recordBrainReviewFailed(
                anyString(), anyString(), anyInt(), anyString(), anyString(), anyString());
    }

    @Test
    void budgetPausedReviewPreservesItsBodyThenParksTaskRoundAndRun()
    {
        ReviewRound live = brainRound(ReviewRound.STATUS_TRIAGING)
                .withIterationBumped()
                .withBrainVerdict(ReviewRound.VERDICT_APPROVED);
        ThreadTurn turn = runTurnWithId(
                "budget-review", "run-stage", "brain-review",
                ThreadTurnStatus.COMPLETED, live.runId());
        AgentRun pausedRun = new AgentRun(
                live.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND,
                AgentRun.SOURCE_LOCAL, null, null, "run-stage",
                AgentRun.STATUS_PAUSED, 0, null, null, null, NOW, null);
        when(turnStore.findTurnById(turn.id())).thenReturn(Optional.of(turn));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task()));
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(live));
        when(agentRuns.findById(live.runId())).thenReturn(Optional.of(pausedRun));
        when(threadStore.listStageMessages("run-stage"))
                .thenReturn(List.of(reviewMessage("The change is sound.")));

        service.onTurnBudgetPaused(new TaskTurnBudgetPausedEvent(TASK_ID, turn.id()));

        verify(prService).recordBrainReview(
                TASK_ID, "dev", ReviewRound.VERDICT_APPROVED,
                live.iteration(), live.id(), "The change is sound.");
        verify(prService, never()).recordBrainReviewFailed(
                anyString(), anyString(), anyInt(), anyString(), anyString(), anyString());
        verify(roundStore).save(argThat(round -> ReviewRound.STATUS_PAUSED.equals(round.status())));
        verify(agentRuns).pause(live.runId(), "brain_review_budget_paused");
        verify(taskStore).saveTask(argThat(saved -> saved.status() == TaskStatus.PAUSED
                && "brain_review_budget_paused".equals(saved.errorMessage())));
    }

    @Test
    void budgetPausedReviewStillRecordsItsCompletedBodyAfterUserPauseWonTheRace()
    {
        ReviewRound paused = brainRound(ReviewRound.STATUS_PAUSED)
                .withIterationBumped()
                .withBrainVerdict(ReviewRound.VERDICT_APPROVED);
        ThreadTurn turn = runTurnWithId(
                "paused-budget-review", "run-stage", "brain-review",
                ThreadTurnStatus.COMPLETED, paused.runId());
        when(turnStore.findTurnById(turn.id())).thenReturn(Optional.of(turn));
        when(taskStore.findTaskById(TASK_ID))
                .thenReturn(Optional.of(task().withStatus(TaskStatus.PAUSED)));
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.empty());
        when(roundStore.findByTask(TASK_ID)).thenReturn(List.of(paused));
        when(threadStore.listStageMessages("run-stage"))
                .thenReturn(List.of(reviewMessage("The completed review survives the pause race.")));

        service.onTurnBudgetPaused(new TaskTurnBudgetPausedEvent(TASK_ID, turn.id()));

        verify(prService).recordBrainReview(
                TASK_ID, "dev", ReviewRound.VERDICT_APPROVED,
                paused.iteration(), paused.id(), "The completed review survives the pause race.");
        verify(roundStore, never()).save(any());
        verify(taskStore, never()).saveTask(any());
        verify(agentRuns, never()).pause(anyString(), anyString());
    }

    @Test
    void budgetPausedFixRecordsTheExplicitFailureTrailThenParks()
    {
        ReviewRound live = brainRound(ReviewRound.STATUS_ADDRESSING).withIterationBumped();
        ThreadTurn turn = runTurnWithId(
                "budget-fix", "run-stage", "brain-review-fix",
                ThreadTurnStatus.COMPLETED, live.runId());
        AgentRun pausedRun = new AgentRun(
                live.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND,
                AgentRun.SOURCE_LOCAL, null, null, "run-stage",
                AgentRun.STATUS_PAUSED, 0, null, null, null, NOW, null);
        when(turnStore.findTurnById(turn.id())).thenReturn(Optional.of(turn));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task()));
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(live));
        when(agentRuns.findById(live.runId())).thenReturn(Optional.of(pausedRun));

        service.onTurnBudgetPaused(new TaskTurnBudgetPausedEvent(TASK_ID, turn.id()));

        verify(prService).recordBrainReviewFailed(
                TASK_ID, "dev", live.iteration(), live.id(),
                "brain_fix_budget_paused", live.runId());
        verify(prService, never()).recordBrainReview(
                anyString(), anyString(), any(), anyInt(), anyString(), any());
        verify(roundStore).save(argThat(round -> ReviewRound.STATUS_PAUSED.equals(round.status())));
        verify(agentRuns).pause(live.runId(), "brain_fix_budget_paused");
        verify(taskStore).saveTask(argThat(saved -> saved.status() == TaskStatus.PAUSED
                && "brain_fix_budget_paused".equals(saved.errorMessage())));
    }

    @Test
    void resumeParkedReviewOpensAReplacementRunAndRetriesTheBrain()
    {
        ReviewRound parked = brainRound(ReviewRound.STATUS_PAUSED);
        Task task = taskAt(TaskPhase.INTERNAL_REVIEW);
        PR drafted = PR.create("pr1", TASK_ID, "feature/x", "main", "x", "", NOW);
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task));
        when(roundStore.findByTask(TASK_ID)).thenReturn(List.of(parked));
        when(prService.findByTask(TASK_ID)).thenReturn(Optional.of(drafted));
        when(prService.comments("pr1")).thenReturn(List.of());
        AgentRun replacement = new AgentRun(
                "replacement-run", TASK_ID, AgentRun.KIND_REVIEW_ROUND,
                AgentRun.SOURCE_LOCAL, null, null, "replacement-stage",
                AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(agentRuns.open(eq(TASK_ID), eq(AgentRun.KIND_REVIEW_ROUND),
                eq(AgentRun.SOURCE_LOCAL), any(), eq(StageType.REVIEW_ROUND_STAGE), any()))
                .thenReturn(replacement);
        when(threadStore.findBrainThreadByTask(TASK_ID)).thenReturn(Optional.of(brainThread()));
        when(validation.run(TASK_ID)).thenReturn(new ValidationPassResult(true, 0, List.of()));

        assertThat(service.resumeParkedReview(TASK_ID)).isTrue();

        verify(roundStore).save(argThat(round -> round.id().equals(parked.id())
                && round.runId().equals(replacement.id())
                && ReviewRound.STATUS_TRIAGING.equals(round.status())
                && round.iteration() == parked.iteration() + 1));
        verify(scheduler).enqueueTaskTurn(
                any(), anyString(), eq(TASK_ID), eq("replacement-stage"),
                argThat(initiator -> "brain-review".equals(initiator.source())),
                eq("replacement-run"));
        verify(validation).run(TASK_ID);
    }

    @Test
    void resumeOfABudgetPausedReviewRestartsItsRunAndClearsTheStaleVerdict()
    {
        ReviewRound parked = brainRound(ReviewRound.STATUS_PAUSED)
                .withIterationBumped()
                .withBrainVerdict(ReviewRound.VERDICT_APPROVED);
        Task task = taskAt(TaskPhase.INTERNAL_REVIEW);
        PR drafted = PR.create("pr1", TASK_ID, "feature/x", "main", "x", "", NOW);
        AgentRun prior = new AgentRun(
                parked.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND,
                AgentRun.SOURCE_LOCAL, null, null, "paused-stage",
                AgentRun.STATUS_PAUSED, 0, null, null, null, NOW, null);
        AgentRun replacement = new AgentRun(
                "replacement-budget-run", TASK_ID, AgentRun.KIND_REVIEW_ROUND,
                AgentRun.SOURCE_LOCAL, null, null, "paused-stage",
                AgentRun.STATUS_QUEUED, 0, null, null, null, NOW, null);
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task));
        when(roundStore.findByTask(TASK_ID)).thenReturn(List.of(parked));
        when(prService.findByTask(TASK_ID)).thenReturn(Optional.of(drafted));
        when(prService.comments("pr1")).thenReturn(List.of());
        when(agentRuns.findById(prior.id())).thenReturn(Optional.of(prior));
        when(agentRuns.restart(prior.id())).thenReturn(replacement);
        when(threadStore.findBrainThreadByTask(TASK_ID)).thenReturn(Optional.of(brainThread()));
        when(validation.run(TASK_ID)).thenReturn(new ValidationPassResult(true, 0, List.of()));

        assertThat(service.resumeParkedReview(TASK_ID)).isTrue();

        verify(agentRuns).restart(prior.id());
        verify(roundStore, times(2)).save(argThat(round -> round.id().equals(parked.id())
                && replacement.id().equals(round.runId())
                && round.brainVerdict() == null));
        verify(scheduler).enqueueTaskTurn(
                any(), anyString(), eq(TASK_ID), eq(replacement.stageId()),
                argThat(initiator -> "brain-review".equals(initiator.source())),
                eq(replacement.id()));
    }

    @Test
    void resumeParkedReviewRerunsFailedValidationBeforeAnotherBrainPass()
    {
        ReviewRound parked = brainRound(ReviewRound.STATUS_PAUSED);
        Task task = taskAt(TaskPhase.INTERNAL_REVIEW);
        PR drafted = PR.create("pr1", TASK_ID, "feature/x", "main", "x", "", NOW);
        AgentRun replacement = new AgentRun(
                "replacement-run", TASK_ID, AgentRun.KIND_REVIEW_ROUND,
                AgentRun.SOURCE_LOCAL, null, null, "replacement-stage",
                AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task));
        when(roundStore.findByTask(TASK_ID)).thenReturn(List.of(parked));
        when(prService.findByTask(TASK_ID)).thenReturn(Optional.of(drafted));
        when(prService.comments("pr1")).thenReturn(List.of());
        when(agentRuns.open(eq(TASK_ID), eq(AgentRun.KIND_REVIEW_ROUND),
                eq(AgentRun.SOURCE_LOCAL), any(), eq(StageType.REVIEW_ROUND_STAGE), any()))
                .thenReturn(replacement);
        when(agentRuns.findById(replacement.id())).thenReturn(Optional.of(replacement));
        when(validation.run(TASK_ID)).thenReturn(new ValidationPassResult(false, 0, List.of()));

        assertThat(service.resumeParkedReview(TASK_ID)).isFalse();

        verify(validation).run(TASK_ID);
        verify(roundStore, times(2)).save(argThat(round -> ReviewRound.STATUS_PAUSED.equals(round.status())
                && replacement.id().equals(round.runId())));
        verify(prService).recordBrainReviewFailed(
                TASK_ID, "dev", parked.iteration(), parked.id(),
                "brain_fixes_validation_failed", replacement.id());
        verify(scheduler, never()).enqueueTaskTurn(
                any(), anyString(), anyString(), anyString(), any(), anyString());
    }

    @Test
    void resumeParkedExternalRoundRevalidatesAndRestartsRoundScopedVerification()
    {
        String remoteStageId = "00000000-0000-0000-0000-0000000000b1";
        ReviewRound parked = round(ReviewRound.STATUS_PAUSED);
        Task task = taskAt(TaskPhase.AWAITING_REMOTE_REVIEW);
        AgentRun prior = new AgentRun(
                parked.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND,
                AgentRun.SOURCE_REMOTE, remoteStageId, null, remoteStageId,
                AgentRun.STATUS_FAILED, 0, null, null, null, NOW, NOW);
        AgentRun replacement = new AgentRun(
                "replacement-remote-run", TASK_ID, AgentRun.KIND_REVIEW_ROUND,
                AgentRun.SOURCE_REMOTE, remoteStageId, null, remoteStageId,
                AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        StageInstance remoteStage = new StageInstance(
                UUID.fromString(remoteStageId), TASK_ID, StageType.REMOTE_DEVELOPMENT_STAGE,
                StageState.ACTIVE, NOW, null, null);
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task));
        when(roundStore.findByTask(TASK_ID)).thenReturn(List.of(parked));
        when(agentRuns.findById(parked.runId())).thenReturn(Optional.of(prior));
        when(stageStore.findStageById(remoteStage.id())).thenReturn(Optional.of(remoteStage));
        when(agentRuns.openInStage(
                TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_REMOTE, remoteStageId, null))
                .thenReturn(replacement);
        when(threadStore.findBrainThreadByTask(TASK_ID)).thenReturn(Optional.of(brainThread()));
        when(validation.run(TASK_ID)).thenReturn(new ValidationPassResult(true, 0, List.of()));

        assertThat(service.resumeParkedReview(TASK_ID)).isTrue();

        verify(validation).run(TASK_ID);
        verify(prService).recordBrainReviewStarted(
                TASK_ID, "round", parked.iteration() + 1, parked.id());
        verify(scheduler).enqueueTaskTurn(
                any(), anyString(), eq(TASK_ID), eq(remoteStageId),
                argThat(initiator -> "brain-review".equals(initiator.source())),
                eq(replacement.id()));
    }

    @Test
    void resumedAddressingUsesTheReplacementRunAsANewTimelineAttempt()
    {
        ReviewRound parked = brainRound(ReviewRound.STATUS_PAUSED).withIterationBumped();
        Task task = taskAt(TaskPhase.INTERNAL_REVIEW);
        PR drafted = PR.create("pr1", TASK_ID, "feature/x", "main", "x", "", NOW);
        AgentRun replacement = new AgentRun(
                "replacement-fix-run", TASK_ID, AgentRun.KIND_REVIEW_ROUND,
                AgentRun.SOURCE_LOCAL, null, null, "replacement-stage",
                AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task));
        when(roundStore.findByTask(TASK_ID)).thenReturn(List.of(parked));
        when(prService.findByTask(TASK_ID)).thenReturn(Optional.of(drafted));
        when(prService.comments("pr1")).thenReturn(List.of(brainComment(
                "open-root", PRComment.SCOPE_FILE_LINE, "src/Foo.java", 42, "still open")));
        when(agentRuns.open(eq(TASK_ID), eq(AgentRun.KIND_REVIEW_ROUND),
                eq(AgentRun.SOURCE_LOCAL), any(), eq(StageType.REVIEW_ROUND_STAGE), any()))
                .thenReturn(replacement);
        when(threadStore.findThreadById(task.threadId())).thenReturn(Optional.of(idleTaskThread()));

        assertThat(service.resumeParkedReview(TASK_ID)).isTrue();

        verify(validation, never()).run(TASK_ID);
        verify(prService).recordBrainReviewAddressing(
                TASK_ID, "dev", parked.iteration(), parked.id(), replacement.id());
        verify(scheduler).enqueueTaskTurn(
                any(), anyString(), eq(TASK_ID), eq(replacement.stageId()),
                argThat(initiator -> "brain-review-fix".equals(initiator.source())),
                eq(replacement.id()));
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
    void localUserFixesOpenAFreshBrainRoundOnTheAlreadyOpenPr()
    {
        PR open = PR.create("pr1", TASK_ID, "feature/x", "main", "x", "", NOW)
                .withStatus(PR.STATUS_LOCAL_OPEN, NOW);
        when(prService.findById("pr1")).thenReturn(Optional.of(open));
        Task task = taskAt(TaskPhase.INTERNAL_REVIEW);
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task));
        when(roundStore.findByTask(TASK_ID)).thenReturn(List.of(brainRound(ReviewRound.STATUS_CLOSED)));
        when(roundStore.nextIndex(TASK_ID)).thenReturn(2);
        AgentRun run = new AgentRun(
                "run2", TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_LOCAL, null, null,
                "stage2", AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(agentRuns.open(eq(TASK_ID), eq(AgentRun.KIND_REVIEW_ROUND), eq(AgentRun.SOURCE_LOCAL),
                any(), eq(StageType.REVIEW_ROUND_STAGE), any())).thenReturn(run);
        when(threadStore.findBrainThreadByTask(TASK_ID)).thenReturn(Optional.of(brainThread()));

        service.reviewAfterLocalComments("pr1");

        verify(roundStore).save(argThat(round -> round.idx() == 2
                && ReviewRound.ORIGIN_BRAIN.equals(round.origin())
                && ReviewRound.STATUS_TRIAGING.equals(round.status())
                && "run2".equals(round.runId())));
        verify(scheduler).enqueueTaskTurn(
                any(), anyString(), eq(TASK_ID), eq("stage2"),
                argThat(initiator -> "brain-review".equals(initiator.source())), eq("run2"));
        verify(prService, never()).requestUserReview(any(), any());
    }

    @Test
    void freshBrainRoundAdoptsAnyOlderOpenBrainRootsBeforeReviewingAgain()
    {
        PR open = PR.create("pr1", TASK_ID, "feature/x", "main", "x", "", NOW)
                .withStatus(PR.STATUS_LOCAL_OPEN, NOW);
        when(prService.findById("pr1")).thenReturn(Optional.of(open));
        when(prService.findByTask(TASK_ID)).thenReturn(Optional.of(open));
        when(prService.comments("pr1")).thenReturn(List.of(brainComment(
                "older-root", PRComment.SCOPE_FILE_LINE, "src/Foo.java", 12, "Still open")));
        Task task = taskAt(TaskPhase.INTERNAL_REVIEW);
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task));
        when(roundStore.findByTask(TASK_ID)).thenReturn(List.of(brainRound(ReviewRound.STATUS_CLOSED)));
        when(roundStore.nextIndex(TASK_ID)).thenReturn(2);
        AgentRun run = new AgentRun(
                "run2", TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_LOCAL, null, null,
                "stage2", AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(agentRuns.open(eq(TASK_ID), eq(AgentRun.KIND_REVIEW_ROUND), eq(AgentRun.SOURCE_LOCAL),
                any(), eq(StageType.REVIEW_ROUND_STAGE), any())).thenReturn(run);
        when(threadStore.findThreadById(task.threadId())).thenReturn(Optional.of(idleTaskThread()));

        service.reviewAfterLocalComments("pr1");

        verify(scheduler).enqueueTaskTurn(
                any(), argThat(prompt -> prompt.contains("[id: older-root]")), eq(TASK_ID), eq("stage2"),
                argThat(initiator -> "brain-review-fix".equals(initiator.source())), eq("run2"));
        verify(roundStore).save(argThat(round -> round.idx() == 2
                && round.iteration() == 0
                && ReviewRound.STATUS_ADDRESSING.equals(round.status())));
        verify(prService, never()).recordBrainReviewStarted(anyString(), anyString(), anyInt(), anyString());
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
        PRComment root = brainComment(
                "c1", PRComment.SCOPE_FILE_LINE, "src/Foo.java", 42, "Guard the null branch");
        PRComment clarification = new PRComment(
                "c2", "pr1", PRComment.ORIGIN_LOCAL, PRComment.SCOPE_FILE_LINE,
                "src/Foo.java", 42, PRTimelineEntry.ACTOR_USER,
                "Please preserve the original exception too.", NOW,
                null, null, null, root.id(), null, "RIGHT", null, null);
        when(prService.comments("pr1")).thenReturn(List.of(root, clarification));

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "turn-5", false));

        verify(scheduler).enqueueTaskTurn(
                eq(taskThread),
                argThat(prompt -> prompt.contains("[id: c1]")
                          && prompt.contains("src/Foo.java:42")
                          && prompt.contains("Guard the null branch")
                          && prompt.contains("Reply @you: Please preserve the original exception too.")
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
    void approvedVerdictCannotBypassAnOpenBrainRoot()
    {
        ReviewRound triaging = brainRound(ReviewRound.STATUS_TRIAGING).withBrainVerdict(
                ReviewRound.VERDICT_APPROVED);
        when(turnStore.findTurnById("approved-with-root")).thenReturn(Optional.of(turn(
                "run-stage", TurnInitiator.unattended("brain-review"))));
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(triaging));
        Task task = taskAt(TaskPhase.INTERNAL_REVIEW);
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task));
        AgentRun run = new AgentRun(
                triaging.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_LOCAL, null, null,
                "run-stage", AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(agentRuns.findById(triaging.runId())).thenReturn(Optional.of(run));
        when(threadStore.findThreadById(task.threadId())).thenReturn(Optional.of(idleTaskThread()));
        PR open = PR.create("pr1", TASK_ID, "feature/x", "main", "x", "", NOW)
                .withStatus(PR.STATUS_LOCAL_OPEN, NOW);
        when(prService.findByTask(TASK_ID)).thenReturn(Optional.of(open));
        when(prService.comments("pr1")).thenReturn(List.of(brainComment(
                "still-open", PRComment.SCOPE_FILE_LINE, "src/Foo.java", 10, "Fix this first")));

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "approved-with-root", false));

        verify(scheduler).enqueueTaskTurn(
                any(), argThat(prompt -> prompt.contains("[id: still-open]")), eq(TASK_ID), eq("run-stage"),
                argThat(initiator -> "brain-review-fix".equals(initiator.source())), eq(triaging.runId()));
        verify(roundStore, never()).save(argThat(round -> ReviewRound.STATUS_CLOSED.equals(round.status())));
        verify(phaseMachine, never()).transition(
                eq(TASK_ID), eq(TaskPhase.AWAITING_PUSH), anyString(), any());
        verify(prService, never()).requestUserReview(anyString(), anyString());
    }

    @Test
    void approvedReReviewOfAnOpenLocalPrReturnsToThePushGateWithoutFlippingItAgain()
    {
        ReviewRound triaging = brainRound(ReviewRound.STATUS_TRIAGING).withBrainVerdict(
                ReviewRound.VERDICT_APPROVED);
        when(turnStore.findTurnById("local-rereview")).thenReturn(Optional.of(turn(
                "run-stage", TurnInitiator.unattended("brain-review"))));
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(triaging));
        Task task = taskAt(TaskPhase.INTERNAL_REVIEW);
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task));
        AgentRun run = new AgentRun(
                triaging.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_LOCAL, null, null,
                "run-stage", AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(agentRuns.findById(triaging.runId())).thenReturn(Optional.of(run));
        PR open = PR.create("pr1", TASK_ID, "feature/x", "main", "x", "", NOW)
                .withStatus(PR.STATUS_LOCAL_OPEN, NOW);
        when(prService.findByTask(TASK_ID)).thenReturn(Optional.of(open));

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "local-rereview", false));

        verify(phaseMachine).transition(
                TASK_ID, TaskPhase.AWAITING_PUSH, "local_review_reverified", Actor.AGENT);
        verify(prService, never()).requestUserReview(anyString(), anyString());
        verify(events, never()).publishEvent(any(LocalReviewClearedEvent.class));
    }

    @Test
    void completedBrainFixRetriesDevelopmentWhileABrainRootRemainsOpen()
    {
        ReviewRound addressing = brainRound(ReviewRound.STATUS_ADDRESSING).withIterationBumped();
        ThreadTurn completed = runTurn(
                "run-stage", "brain-review-fix", ThreadTurnStatus.COMPLETED, addressing.runId(),
                "prompt\n\n[brain-fix-iteration:1]");
        when(turnStore.findTurnById("brain-fix-open")).thenReturn(Optional.of(completed));
        when(turnStore.listTurnsByAgentRunId(addressing.runId(), 100)).thenReturn(List.of(completed));
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(addressing));
        Task task = taskAt(TaskPhase.INTERNAL_REVIEW);
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task));
        AgentRun run = new AgentRun(
                addressing.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_LOCAL, null, null,
                "run-stage", AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(agentRuns.findById(addressing.runId())).thenReturn(Optional.of(run));
        when(threadStore.findThreadById(task.threadId())).thenReturn(Optional.of(idleTaskThread()));
        PR open = PR.create("pr1", TASK_ID, "feature/x", "main", "x", "", NOW)
                .withStatus(PR.STATUS_LOCAL_OPEN, NOW);
        when(prService.findByTask(TASK_ID)).thenReturn(Optional.of(open));
        when(prService.comments("pr1")).thenReturn(List.of(brainComment(
                "still-open", PRComment.SCOPE_FILE_LINE, "src/Foo.java", 10, "Fix this first")));

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "brain-fix-open", false));

        verify(validation, never()).run(anyString());
        verify(scheduler).enqueueTaskTurn(
                any(), argThat(prompt -> prompt.contains("[id: still-open]")), eq(TASK_ID), eq("run-stage"),
                argThat(initiator -> "brain-review-fix".equals(initiator.source())), eq(addressing.runId()));
        verify(roundStore, never()).save(argThat(round -> ReviewRound.STATUS_TRIAGING.equals(round.status())));
    }

    @Test
    void repeatedCompletedFixesThatLeaveBrainRootsOpenParkForTheUser()
    {
        ReviewRound addressing = brainRound(ReviewRound.STATUS_ADDRESSING).withIterationBumped();
        ThreadTurn completed = runTurn(
                "run-stage", "brain-review-fix", ThreadTurnStatus.COMPLETED, addressing.runId(),
                "prompt\n\n[brain-fix-iteration:1]");
        when(turnStore.findTurnById("brain-fix-open")).thenReturn(Optional.of(completed));
        when(turnStore.listTurnsByAgentRunId(addressing.runId(), 100)).thenReturn(List.of(
                completed,
                runTurn("run-stage", "brain-review-fix", ThreadTurnStatus.COMPLETED, addressing.runId(),
                        "retry 2\n\n[brain-fix-iteration:1]"),
                runTurn("run-stage", "brain-review-fix", ThreadTurnStatus.COMPLETED, addressing.runId(),
                        "retry 1\n\n[brain-fix-iteration:1]")));
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(addressing));
        Task task = taskAt(TaskPhase.INTERNAL_REVIEW);
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task));
        AgentRun run = new AgentRun(
                addressing.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_LOCAL, null, null,
                "run-stage", AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(agentRuns.findById(addressing.runId())).thenReturn(Optional.of(run));
        PR open = PR.create("pr1", TASK_ID, "feature/x", "main", "x", "", NOW)
                .withStatus(PR.STATUS_LOCAL_OPEN, NOW);
        when(prService.findByTask(TASK_ID)).thenReturn(Optional.of(open));
        when(prService.comments("pr1")).thenReturn(List.of(brainComment(
                "still-open", PRComment.SCOPE_FILE_LINE, "src/Foo.java", 10, "Fix this first")));

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "brain-fix-open", false));

        verify(phaseMachine).transition(
                TASK_ID, TaskPhase.NEEDS_ATTENTION, "brain_findings_unresolved", Actor.AGENT);
        verify(scheduler, never()).enqueueTaskTurn(
                any(), anyString(), anyString(), anyString(), any(), anyString());
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
                .withIterationBumped()
                .withIterationBumped()
                .withIterationBumped() // iteration now == budget (5)
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
    void parkingDefersNeedsAttentionUntilAfterItsDurableStateCanCommit()
    {
        ReviewRound triaging = brainRound(ReviewRound.STATUS_TRIAGING).withIterationBumped();
        ThreadTurn turn = runTurnWithId(
                "missing-verdict", "run-stage", "brain-review",
                ThreadTurnStatus.COMPLETED, triaging.runId());
        when(turnStore.findTurnById(turn.id())).thenReturn(Optional.of(turn));
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(triaging));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task()));
        AgentRun run = new AgentRun(
                triaging.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND,
                AgentRun.SOURCE_LOCAL, null, null, "run-stage",
                AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(agentRuns.findById(triaging.runId())).thenReturn(Optional.of(run));

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, turn.id(), false));
        }
        finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(roundStore).save(argThat(saved -> ReviewRound.STATUS_PAUSED.equals(saved.status())));
        verify(prService).recordBrainReviewFailed(
                TASK_ID, "dev", triaging.iteration(), triaging.id(),
                "brain_review_verdict_missing", triaging.runId());
        verify(events).publishEvent((Object) argThat(
                (Object event) -> event instanceof BrainReviewServiceImpl.NeedsAttentionNotice));
        verify(notifications, never()).notifyNeedsAttention(anyString(), anyString(), anyString());
    }

    @Test
    void conclusionDefersNeedsAttentionUntilAfterItsDurableStateCanCommit()
    {
        ReviewRound exhausted = brainRound(ReviewRound.STATUS_TRIAGING)
                .withIterationBumped()
                .withIterationBumped()
                .withIterationBumped()
                .withIterationBumped()
                .withIterationBumped()
                .withBrainVerdict(ReviewRound.VERDICT_CHANGES_REQUESTED);
        ThreadTurn turn = runTurnWithId(
                "budget-exhausted", "run-stage", "brain-review",
                ThreadTurnStatus.COMPLETED, exhausted.runId());
        when(turnStore.findTurnById(turn.id())).thenReturn(Optional.of(turn));
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(exhausted));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task()));
        AgentRun run = new AgentRun(
                exhausted.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND,
                AgentRun.SOURCE_LOCAL, null, null, "run-stage",
                AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(agentRuns.findById(exhausted.runId())).thenReturn(Optional.of(run));
        PR drafted = PR.create("pr1", TASK_ID, "feature/x", "main", "x", "", NOW);
        when(prService.findByTask(TASK_ID)).thenReturn(Optional.of(drafted));
        when(prService.comments("pr1")).thenReturn(List.of());

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, turn.id(), false));
        }
        finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(roundStore).save(argThat(saved -> ReviewRound.STATUS_CLOSED.equals(saved.status())));
        verify(agentRuns).transition(exhausted.runId(), AgentRun.STATUS_SUCCEEDED, "brain_review_concluded");
        verify(events).publishEvent((Object) argThat(
                (Object event) -> event instanceof BrainReviewServiceImpl.NeedsAttentionNotice));
        verify(notifications, never()).notifyNeedsAttention(anyString(), anyString(), anyString());
    }

    @Test
    void isolatedNeedsAttentionFailureIsBestEffort()
    {
        BrainReviewServiceImpl.NeedsAttentionNotice notice =
                new BrainReviewServiceImpl.NeedsAttentionNotice("thread-1", TASK_ID, "{}");
        doThrow(new IllegalStateException("notification store unavailable"))
                .when(notifications)
                .notifyNeedsAttentionInNewTransaction("thread-1", TASK_ID, "{}");

        assertThatCode(() -> service.deliverNeedsAttention(notice)).doesNotThrowAnyException();
    }

    @Test
    void aReviewLoopThatNeverRecordsAVerdictParksWithAnExplicitFailure()
    {
        // Regression: iteration must bump on every scheduled review turn, not
        // just when record_review_verdict is called — otherwise a brain agent
        // that never calls it wedges the round on STATUS_RUNNING forever.
        ReviewRound neverVerdicted = brainRound(ReviewRound.STATUS_TRIAGING)
                .withIterationBumped()
                .withIterationBumped()
                .withIterationBumped()
                .withIterationBumped()
                .withIterationBumped(); // iteration == budget (5), brainVerdict still null
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
        verify(roundStore).save(argThat(r -> ReviewRound.STATUS_PAUSED.equals(r.status())));
        verify(agentRuns).transition(
                neverVerdicted.runId(), AgentRun.STATUS_FAILED, "brain_review_verdict_missing");
        verify(prService).recordBrainReviewFailed(
                TASK_ID, "dev", neverVerdicted.iteration(), neverVerdicted.id(),
                "brain_review_verdict_missing", neverVerdicted.runId());
    }

    @Test
    void legacyUnattributedBrainResultIsRecoveredByStageAndTurnWindowThenParked()
    {
        ReviewRound triaging = brainRound(ReviewRound.STATUS_TRIAGING).withIterationBumped();
        ThreadTurn completed = runTurn(
                "run-stage", "brain-review", ThreadTurnStatus.COMPLETED, triaging.runId());
        ThreadMessage legacyResult = new ThreadMessage(
                "legacy-result", completed.threadId(), null, 1L, "assistant", "text",
                "{\"text\":\"Verdict I would record: changes_requested; delete the orphaned CSS.\"}",
                null, null, null, null, NOW, "run-stage", ThreadScope.STAGE);
        Task task = taskAt(TaskPhase.INTERNAL_REVIEW);
        AgentRun run = new AgentRun(
                triaging.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND,
                AgentRun.SOURCE_LOCAL, null, null, "run-stage",
                AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(turnStore.findTurnById("ghost-result")).thenReturn(Optional.of(completed));
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(triaging));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task));
        when(threadStore.listStageMessages("run-stage")).thenReturn(List.of(legacyResult));
        when(agentRuns.findById(triaging.runId())).thenReturn(Optional.of(run));

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "ghost-result", false));

        verify(prService).recordBrainReview(
                eq(TASK_ID), eq("dev"), eq(null), eq(triaging.iteration()), eq(triaging.id()),
                argThat(body -> body != null && body.contains("delete the orphaned CSS")));
        verify(prService).recordBrainReviewFailed(
                TASK_ID, "dev", triaging.iteration(), triaging.id(),
                "brain_review_verdict_missing", triaging.runId());
        verify(roundStore).save(argThat(round -> ReviewRound.STATUS_PAUSED.equals(round.status())));
        verify(scheduler, never()).enqueueTaskTurn(
                any(), anyString(), anyString(), anyString(), any(), anyString());
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
        verify(roundStore).save(argThat(saved -> ReviewRound.STATUS_PAUSED.equals(saved.status())));
        verify(prService).recordBrainReviewFailed(
                TASK_ID, "round", addressed.iteration(), addressed.id(),
                "review_fixes_validation_failed", addressed.runId());
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
        verify(roundStore).save(argThat(saved -> ReviewRound.STATUS_PAUSED.equals(saved.status())));
        verify(prService).recordBrainReviewFailed(
                TASK_ID, "round", addressed.iteration(), addressed.id(),
                "brain_review_run_missing", addressed.runId());
        verify(agentRuns, never()).transition(anyString(), eq(AgentRun.STATUS_AWAITING_GATE), anyString());
        verify(scheduler, never()).enqueueTaskTurn(any(), anyString(), anyString(), anyString(), any(), anyString());
    }

    // ── reconcileStalledRounds: the intra-thread-multi-tasking backstop ───

    @Test
    void reconcileStalledRoundsAdvancesATriagingRoundOnceItsThreadIsIdle()
    {
        ReviewRound triaging = brainRound(ReviewRound.STATUS_TRIAGING)
                .withBrainVerdict(ReviewRound.VERDICT_CHANGES_REQUESTED);
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
    void reconcileStalledRoundsLeavesAUserPausedTaskAlone()
    {
        ReviewRound triaging = brainRound(ReviewRound.STATUS_TRIAGING)
                .withBrainVerdict(ReviewRound.VERDICT_CHANGES_REQUESTED);
        when(roundStore.findAllLive()).thenReturn(List.of(triaging));
        when(taskStore.findTaskById(TASK_ID))
                .thenReturn(Optional.of(taskAt(TaskPhase.INTERNAL_REVIEW).withStatus(TaskStatus.PAUSED)));

        service.reconcileStalledRounds();

        verify(agentRuns, never()).findById(anyString());
        verify(scheduler, never()).enqueueTaskTurn(
                any(), anyString(), anyString(), anyString(), any(), anyString());
        verify(roundStore, never()).save(any());
    }

    @Test
    void reconcileStalledRoundsSkipsATriagingRoundWhoseThreadIsStillBusy()
    {
        ReviewRound triaging = brainRound(ReviewRound.STATUS_TRIAGING)
                .withBrainVerdict(ReviewRound.VERDICT_CHANGES_REQUESTED);
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
    void adoptedOpenFindingsAdvanceFromTheirZeroIterationFixIntoTheFirstReview()
    {
        ReviewRound addressing = brainRound(ReviewRound.STATUS_ADDRESSING);
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
                        && r.iteration() == 1
                        && r.brainVerdict() == null));
        verify(prService).recordBrainReviewStarted(TASK_ID, "dev", 1, addressing.id());
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
    void firstFailedBrainReviewTurnRetriesImmediately()
    {
        ReviewRound triaging = brainRound(ReviewRound.STATUS_TRIAGING);
        ThreadTurn failed = runTurn(
                "run-stage", "brain-review", ThreadTurnStatus.FAILED, triaging.runId());
        when(turnStore.findTurnById("failed-turn")).thenReturn(Optional.of(failed));
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(triaging));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task()));
        AgentRun run = new AgentRun(
                triaging.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND,
                AgentRun.SOURCE_LOCAL, null, null, "run-stage",
                AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(agentRuns.findById(triaging.runId())).thenReturn(Optional.of(run));
        when(turnStore.listTurnsByAgentRunId(triaging.runId(), 100)).thenReturn(List.of(failed));
        when(threadStore.findBrainThreadByTask(TASK_ID)).thenReturn(Optional.of(brainThread()));

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "failed-turn", true));

        verify(scheduler).enqueueTaskTurn(
                any(), anyString(), eq(TASK_ID), eq("run-stage"),
                argThat(initiator -> "brain-review".equals(initiator.source())), eq(triaging.runId()));
        verify(roundStore, never()).save(any());
        verify(phaseMachine, never()).transition(anyString(), any(), anyString(), any());
    }

    @Test
    void firstFailedBrainFixTurnRetriesImmediately()
    {
        ReviewRound addressing = brainRound(ReviewRound.STATUS_ADDRESSING).withIterationBumped();
        ThreadTurn failed = runTurn(
                "run-stage", "brain-review-fix", ThreadTurnStatus.FAILED, addressing.runId());
        when(turnStore.findTurnById("failed-turn")).thenReturn(Optional.of(failed));
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(addressing));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task()));
        AgentRun run = new AgentRun(
                addressing.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND,
                AgentRun.SOURCE_LOCAL, null, null, "run-stage",
                AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(agentRuns.findById(addressing.runId())).thenReturn(Optional.of(run));
        when(turnStore.listTurnsByAgentRunId(addressing.runId(), 100)).thenReturn(List.of(failed));
        when(threadStore.findThreadById(task().threadId())).thenReturn(Optional.of(idleTaskThread()));

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "failed-turn", true));

        verify(scheduler).enqueueTaskTurn(
                any(), anyString(), eq(TASK_ID), eq("run-stage"),
                argThat(initiator -> "brain-review-fix".equals(initiator.source())), eq(addressing.runId()));
        verify(prService).recordBrainReviewAddressing(
                TASK_ID, "dev", addressing.iteration(), addressing.id(), addressing.runId());
        verify(phaseMachine, never()).transition(anyString(), any(), anyString(), any());
    }

    @Test
    void failedBrainTurnEventDoesNotRestartADormantTask()
    {
        ReviewRound triaging = brainRound(ReviewRound.STATUS_TRIAGING);
        ThreadTurn cancelled = runTurn(
                "run-stage", "brain-review", ThreadTurnStatus.CANCELLED, triaging.runId());
        when(turnStore.findTurnById("failed-turn")).thenReturn(Optional.of(cancelled));
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(triaging));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(
                Optional.of(task().withStatus(TaskStatus.PAUSED)),
                Optional.of(task().withStatus(TaskStatus.CANCELED)),
                Optional.of(task().withStatus(TaskStatus.ARCHIVED)),
                Optional.of(task().withStatus(TaskStatus.ERRORED)));

        for (int attempt = 0; attempt < 4; attempt++) {
            service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "failed-turn", true));
        }

        verify(agentRuns, never()).findById(anyString());
        verify(scheduler, never()).enqueueTaskTurn(
                any(), anyString(), anyString(), anyString(), any(), anyString());
        verify(roundStore, never()).save(any());
    }

    @Test
    void secondFailedBrainReviewTurnImmediatelyParksAndRecordsTheTimeline()
    {
        ReviewRound triaging = brainRound(ReviewRound.STATUS_TRIAGING);
        Task task = task();
        ThreadTurn firstFailure = runTurnWithId(
                "failed-turn-1", "run-stage", "brain-review",
                ThreadTurnStatus.FAILED, triaging.runId());
        ThreadTurn secondFailure = runTurnWithId(
                "failed-turn-2", "run-stage", "brain-review",
                ThreadTurnStatus.FAILED, triaging.runId());
        when(turnStore.findTurnById(secondFailure.id())).thenReturn(Optional.of(secondFailure));
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(triaging));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task));
        AgentRun run = new AgentRun(
                triaging.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND,
                AgentRun.SOURCE_LOCAL, null, null, "run-stage",
                AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(agentRuns.findById(triaging.runId())).thenReturn(Optional.of(run));
        when(turnStore.listTurnsByAgentRunId(triaging.runId(), 100)).thenReturn(List.of(
                secondFailure, firstFailure));

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, secondFailure.id(), true));

        verify(phaseMachine).transition(
                TASK_ID, TaskPhase.NEEDS_ATTENTION, "brain_review_turn_failed", Actor.AGENT);
        verify(notifications).notifyNeedsAttention(eq(task.threadId()), eq(TASK_ID),
                argThat(payload -> payload.contains(triaging.id())));
        verify(scheduler).cancelSessionTurns(triaging.runId());
        verify(agentRuns).transition(
                triaging.runId(), AgentRun.STATUS_FAILED, "brain_review_turn_failed");
        verify(roundStore).save(argThat(saved -> ReviewRound.STATUS_PAUSED.equals(saved.status())));
        verify(taskStore).saveTask(argThat(saved ->
                "brain_review_turn_failed".equals(saved.errorMessage())));
        verify(prService).recordBrainReviewFailed(
                TASK_ID, "dev", triaging.iteration(), triaging.id(),
                "brain_review_turn_failed", triaging.runId());
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
        return runTurnWithId("failed-turn", stageId, source, status, runId, input);
    }

    private static ThreadTurn runTurnWithId(
            String id, String stageId, String source, ThreadTurnStatus status, String runId)
    {
        return runTurnWithId(id, stageId, source, status, runId, "prompt");
    }

    private static ThreadTurn runTurnWithId(
            String id, String stageId, String source, ThreadTurnStatus status, String runId, String input)
    {
        return new ThreadTurn(
                id, "thread-1", TASK_ID, ThreadResourceLane.CLI,
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
