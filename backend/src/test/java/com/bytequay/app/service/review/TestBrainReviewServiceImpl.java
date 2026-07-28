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
import com.bytequay.app.domain.ReviewRoundState;
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
import com.bytequay.app.service.checks.ValidationClaimService;
import com.bytequay.app.service.checks.ValidationPassResult;
import com.bytequay.app.service.checks.ValidationPassService;
import com.bytequay.app.service.localpr.LocalReviewClearedEvent;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.runs.AgentRunService;
import com.bytequay.app.service.stage.StageStateMachine;
import com.bytequay.app.service.threads.NotificationService;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.bytequay.app.service.threads.TaskPhaseMachine;
import com.bytequay.app.service.threads.TaskTurnBudgetPausedEvent;
import com.bytequay.app.service.threads.TaskTurnFinishedEvent;
import com.bytequay.app.service.threads.ThreadTurnScheduler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
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
    private final StageStateMachine stages = mock(StageStateMachine.class);
    private final ReviewRoundStore roundStore = mock(ReviewRoundStore.class);
    private final AgentRunService agentRuns = mock(AgentRunService.class);
    private final ThreadStore threadStore = mock(ThreadStore.class);
    private final ThreadTurnScheduler scheduler = mock(ThreadTurnScheduler.class);
    private final ThreadTurnStore turnStore = mock(ThreadTurnStore.class);
    private final PRService prService = mock(PRService.class);
    private final ValidationPassService validation = mock(ValidationPassService.class);
    private final ValidationClaimService claimedValidation = mock(ValidationClaimService.class);
    private final ReviewRoundStateMachine roundMachine = mock(ReviewRoundStateMachine.class);
    private final TaskPhaseMachine phaseMachine = mock(TaskPhaseMachine.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final PlatformTransactionManager transactionManager = new TestTransactionManager();
    private final TaskCommandExecutor commands = new TaskCommandExecutor(transactionManager);
    private final BrainReviewServiceImpl service = new BrainReviewServiceImpl(
            taskStore, stageStore, stages, roundStore, agentRuns, threadStore, scheduler, turnStore, prService,
            validation, claimedValidation, roundMachine, phaseMachine, notifications,
            mapper, Clock.fixed(NOW, ZoneOffset.UTC), events, commands);

    // ── R20: plan self-review ────────────────────────────────────────────

    @Test
    void enqueuesTheSelfReviewTurnOnceAFinalizedPlanLandsWithNoPriorReview()
    {
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(taskAt(TaskPhase.PLANNING)));
        when(turnStore.findTurnById("turn-1"))
                .thenReturn(Optional.of(turn(PLAN_STAGE_ID.toString(), TurnInitiator.unattended("user"))));
        when(stageStore.findStageById(PLAN_STAGE_ID)).thenReturn(Optional.of(planStage(StageState.OPEN)));
        when(stageStore.findEventsByStage(PLAN_STAGE_ID))
                .thenReturn(List.of(planRecordedEvent("finalized")));
        Thread brainThread = brainThread();
        when(threadStore.findBrainThreadByTask(TASK_ID)).thenReturn(Optional.of(brainThread));

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "turn-1", false));

        verify(scheduler).enqueueStageTurn(
                eq(brainThread), argThat(prompt -> prompt.contains("task `" + TASK_ID + "`")
                        && prompt.contains("revision `plan-rev-1`")
                        && prompt.contains("\"goal\" : \"Fix the bug\"")
                        && prompt.contains("task_id=`" + TASK_ID + "`")
                        && !prompt.contains("task_id=`current`")),
                eq(TASK_ID), eq(PLAN_STAGE_ID.toString()),
                argThat(i -> "brain-plan-self-review".equals(i.source())), isNull(), any());
        verify(stageStore).recordEvent(
                PLAN_STAGE_ID, TASK_ID, StageEventType.PLAN_SELF_REVIEW_STARTED,
                Map.of("iteration", 1));
        verify(prService).recordBrainReviewStarted(TASK_ID, "plan", 1, null);
    }

    @Test
    void doesNotRecordSelfReviewStartedWhenSchedulerAdmissionFails()
    {
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(taskAt(TaskPhase.PLANNING)));
        when(turnStore.findTurnById("turn-1"))
                .thenReturn(Optional.of(turn(PLAN_STAGE_ID.toString(), TurnInitiator.unattended("user"))));
        when(stageStore.findStageById(PLAN_STAGE_ID)).thenReturn(Optional.of(planStage(StageState.OPEN)));
        when(stageStore.findEventsByStage(PLAN_STAGE_ID))
                .thenReturn(List.of(planRecordedEvent("finalized")));
        when(threadStore.findBrainThreadByTask(TASK_ID)).thenReturn(Optional.of(brainThread()));
        doThrow(new IllegalStateException("scheduler unavailable"))
                .when(scheduler).enqueueStageTurn(any(), anyString(), anyString(), anyString(), any(), any(), any());

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "turn-1", false));

        verify(stageStore, never()).recordEvent(
                eq(PLAN_STAGE_ID), eq(TASK_ID), eq(StageEventType.PLAN_SELF_REVIEW_STARTED), any());
        verify(prService, never()).recordBrainReviewStarted(anyString(), anyString(), anyInt(), any());
    }

    @Test
    void doesNotEnqueueForADraftPlan()
    {
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(taskAt(TaskPhase.PLANNING)));
        when(turnStore.findTurnById("turn-1"))
                .thenReturn(Optional.of(turn(PLAN_STAGE_ID.toString(), TurnInitiator.unattended("user"))));
        when(stageStore.findStageById(PLAN_STAGE_ID)).thenReturn(Optional.of(planStage(StageState.OPEN)));
        when(stageStore.findEventsByStage(PLAN_STAGE_ID)).thenReturn(List.of(planRecordedEvent("suggested")));

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "turn-1", false));

        verify(scheduler, never()).enqueueStageTurn(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void neverTriggersASecondSelfReviewOnceOneHasHappened()
    {
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(taskAt(TaskPhase.PLANNING)));
        when(turnStore.findTurnById("turn-2"))
                .thenReturn(Optional.of(turn(PLAN_STAGE_ID.toString(), TurnInitiator.unattended("user"))));
        when(stageStore.findStageById(PLAN_STAGE_ID)).thenReturn(Optional.of(planStage(StageState.OPEN)));
        when(stageStore.findEventsByStage(PLAN_STAGE_ID))
                .thenReturn(List.of(
                        planRecordedEvent("finalized"),
                        planReviewedEvent(ReviewRound.VERDICT_APPROVED, "plan-rev-1")));

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "turn-2", false));

        verify(scheduler, never()).enqueueStageTurn(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void approvedSelfReviewTurnsAutoApproveOnForALowRiskLowEffortPlan()
    {
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(taskAt(TaskPhase.PLANNING)));
        when(turnStore.findTurnById("turn-3")).thenReturn(Optional.of(
                turn(PLAN_STAGE_ID.toString(), TurnInitiator.unattended("brain-plan-self-review"))));
        when(stageStore.findStageById(PLAN_STAGE_ID)).thenReturn(Optional.of(planStage(StageState.OPEN)));
        when(stageStore.findEventsByStage(PLAN_STAGE_ID))
                .thenReturn(List.of(
                        planRecordedEvent("finalized", "low", "trivial"),
                        planReviewedEvent(ReviewRound.VERDICT_APPROVED, "plan-rev-1")));

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "turn-3", false));

        verify(stageStore, never()).recordEvent(
                eq(PLAN_STAGE_ID), eq(TASK_ID), eq(StageEventType.PLAN_SELF_REVIEWED), any());
        verify(taskStore).setAutoApprove(TASK_ID, true);
        // The self-review turn's own completion must not re-enqueue itself.
        verify(scheduler, never()).enqueueStageTurn(any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void approvedSelfReviewLeavesAutoApproveOffForAHighRiskPlan()
    {
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(taskAt(TaskPhase.PLANNING)));
        when(turnStore.findTurnById("turn-4")).thenReturn(Optional.of(
                turn(PLAN_STAGE_ID.toString(), TurnInitiator.unattended("brain-plan-self-review"))));
        when(stageStore.findStageById(PLAN_STAGE_ID)).thenReturn(Optional.of(planStage(StageState.OPEN)));
        when(stageStore.findEventsByStage(PLAN_STAGE_ID))
                .thenReturn(List.of(
                        planRecordedEvent("finalized", "high", "large"),
                        planReviewedEvent(ReviewRound.VERDICT_APPROVED, "plan-rev-1")));

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "turn-4", false));

        verify(taskStore, never()).setAutoApprove(anyString(), any(Boolean.class));
    }

    @Test
    void changesRequestedCompletesTheReviewWithoutApprovingOrRetryingTheSameRevision()
    {
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(taskAt(TaskPhase.PLANNING)));
        when(turnStore.findTurnById("changes-requested-review")).thenReturn(Optional.of(
                turn(PLAN_STAGE_ID.toString(), TurnInitiator.unattended("brain-plan-self-review"))));
        when(stageStore.findStageById(PLAN_STAGE_ID)).thenReturn(Optional.of(planStage(StageState.OPEN)));
        when(stageStore.findEventsByStage(PLAN_STAGE_ID))
                .thenReturn(List.of(
                        planRecordedEvent("finalized"),
                        planReviewedEvent(ReviewRound.VERDICT_CHANGES_REQUESTED, "plan-rev-1")));

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "changes-requested-review", false));

        verify(scheduler, never()).enqueueStageTurn(any(), any(), any(), any(), any(), any(), any());
        verify(taskStore, never()).setAutoApprove(anyString(), any(Boolean.class));
    }

    @Test
    void completedSelfReviewWithoutAVerdictRetriesInsteadOfUnlockingApproval()
    {
        ThreadTurn completed = turn(
                PLAN_STAGE_ID.toString(), TurnInitiator.unattended("brain-plan-self-review"));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(taskAt(TaskPhase.PLANNING)));
        when(turnStore.findTurnById("unverdicted-review")).thenReturn(Optional.of(completed));
        when(stageStore.findStageById(PLAN_STAGE_ID)).thenReturn(Optional.of(planStage(StageState.OPEN)));
        when(stageStore.findEventsByStage(PLAN_STAGE_ID))
                .thenReturn(List.of(planRecordedEvent("finalized")));
        when(turnStore.listTurnsByTaskId(completed.threadId(), 50)).thenReturn(List.of(completed));
        when(threadStore.findBrainThreadByTask(TASK_ID)).thenReturn(Optional.of(brainThread()));

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "unverdicted-review", false));

        verify(scheduler).enqueueStageTurn(
                any(), anyString(), eq(TASK_ID), eq(PLAN_STAGE_ID.toString()),
                argThat(initiator -> "brain-plan-self-review".equals(initiator.source())), isNull(), any());
        verify(stageStore, never()).recordEvent(
                eq(PLAN_STAGE_ID), eq(TASK_ID), eq(StageEventType.PLAN_SELF_REVIEWED), any());
        verify(taskStore, never()).setAutoApprove(anyString(), any(Boolean.class));
    }

    @Test
    void failedPlanSelfReviewRetriesOnce()
    {
        ThreadTurn failed = turn(
                PLAN_STAGE_ID.toString(), TurnInitiator.unattended("brain-plan-self-review"));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(taskAt(TaskPhase.PLANNING)));
        when(turnStore.findTurnById("failed-review")).thenReturn(Optional.of(failed));
        when(stageStore.findStageById(PLAN_STAGE_ID)).thenReturn(Optional.of(planStage(StageState.OPEN)));
        when(stageStore.findEventsByStage(PLAN_STAGE_ID))
                .thenReturn(List.of(planRecordedEvent("finalized")));
        when(turnStore.listTurnsByTaskId("thread-1", 50)).thenReturn(List.of(failed));
        when(threadStore.findBrainThreadByTask(TASK_ID)).thenReturn(Optional.of(brainThread()));

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "failed-review", true));

        verify(scheduler).enqueueStageTurn(
                any(), anyString(), eq(TASK_ID), eq(PLAN_STAGE_ID.toString()),
                argThat(initiator -> "brain-plan-self-review".equals(initiator.source())), isNull(), any());
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
        when(stageStore.findStageById(PLAN_STAGE_ID)).thenReturn(Optional.of(planStage(StageState.OPEN)));
        when(turnStore.listTurnsByTaskId("thread-1", 50)).thenReturn(List.of(failed, prior));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(taskAt(TaskPhase.PLANNING)));

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "failed-review", true));

        verify(phaseMachine).parkOperationalInCommand(
                TASK_ID, Actor.AGENT, "plan_self_review_failed");
        verify(events).publishEvent((Object) argThat(
                (Object event) -> event instanceof BrainReviewServiceImpl.NeedsAttentionNotice));
        verify(scheduler, never()).enqueueStageTurn(any(), any(), any(), any(), any(), any(), any());
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
        when(stageStore.findStageById(PLAN_STAGE_ID)).thenReturn(Optional.of(planStage(StageState.OPEN)));
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

        verify(scheduler).enqueueStageTurn(
                any(), anyString(), eq(TASK_ID), eq(PLAN_STAGE_ID.toString()),
                argThat(initiator -> "brain-plan-self-review".equals(initiator.source())), isNull(), any());
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
        when(stageStore.findActiveStage(TASK_ID)).thenReturn(Optional.of(planStage(StageState.OPEN)));
        when(stageStore.findEventsByStage(PLAN_STAGE_ID))
                .thenReturn(List.of(planRecordedEvent("finalized")));
        when(threadStore.findBrainThreadByTask(TASK_ID)).thenReturn(Optional.of(brainThread()));

        assertThat(service.resumeParkedReview(TASK_ID)).isTrue();

        verify(scheduler).enqueueStageTurn(
                any(), anyString(), eq(TASK_ID), eq(PLAN_STAGE_ID.toString()),
                argThat(initiator -> "brain-plan-self-review".equals(initiator.source())), isNull(), any());
    }

    @Test
    void schedulerConflictDuringPlanSelfReviewIsOwnedAndRequeuedOnResume()
    {
        Task parked = taskAt(TaskPhase.NEEDS_ATTENTION).withStatus(TaskStatus.NEEDS_ATTENTION);
        Task recovered = taskAt(TaskPhase.PLANNING);
        when(taskStore.findTaskById(TASK_ID))
                .thenReturn(Optional.of(parked), Optional.of(recovered));
        when(taskStore.listPhaseEvents(TASK_ID)).thenReturn(List.of(new TaskPhaseEvent(
                1L, TASK_ID, TaskPhase.PLANNING, TaskPhase.NEEDS_ATTENTION,
                NOW, "scheduler_turn_conflict", Actor.AGENT)));
        when(stageStore.findActiveStage(TASK_ID)).thenReturn(Optional.of(planStage(StageState.OPEN)));
        when(stageStore.findEventsByStage(PLAN_STAGE_ID))
                .thenReturn(List.of(planRecordedEvent("finalized")));
        when(threadStore.findBrainThreadByTask(TASK_ID)).thenReturn(Optional.of(brainThread()));

        assertThat(service.ownsParkedResume(TASK_ID)).isTrue();
        assertThat(service.resumeParkedReview(TASK_ID)).isTrue();

        verify(scheduler).enqueueStageTurn(
                any(), anyString(), eq(TASK_ID), eq(PLAN_STAGE_ID.toString()),
                argThat(initiator -> "brain-plan-self-review".equals(initiator.source())),
                isNull(), any());
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
        when(stageStore.findActiveStage(TASK_ID)).thenReturn(Optional.of(planStage(StageState.OPEN)));
        when(stageStore.findEventsByStage(PLAN_STAGE_ID))
                .thenReturn(List.of(planRecordedEvent("finalized")));
        when(threadStore.findBrainThreadByTask(TASK_ID)).thenReturn(Optional.of(brainThread()));
        when(turnStore.listTurnsByTaskId("brain-1", 50)).thenReturn(List.of(cancelled));
        when(agentRuns.findById(running.id()))
                .thenReturn(Optional.of(running), Optional.of(pausedRun));
        when(agentRuns.restartInCommand(TASK_ID, pausedRun.id())).thenReturn(replacement);

        assertThat(service.pauseActiveReview(TASK_ID, "user_paused_task")).isTrue();
        assertThat(service.ownsParkedResume(TASK_ID)).isTrue();
        assertThat(service.resumeParkedReview(TASK_ID)).isTrue();

        verify(agentRuns).pauseInCommand(TASK_ID, running.id(), "user_paused_task");
        verify(agentRuns).restartInCommand(TASK_ID, pausedRun.id());
        verify(scheduler).enqueueStageTurn(
                any(), anyString(), eq(TASK_ID), eq(PLAN_STAGE_ID.toString()),
                argThat(initiator -> "brain-plan-self-review".equals(initiator.source())),
                eq(replacement.id()), any());
    }

    @Test
    void reconcilerReReviewsAnUnboundLegacyCheckpoint()
    {
        Task planning = taskAt(TaskPhase.PLANNING);
        when(taskStore.listByPhases(List.of(TaskPhase.PLANNING), 100)).thenReturn(List.of(planning));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(planning));
        when(stageStore.findActiveStage(TASK_ID)).thenReturn(Optional.of(planStage(StageState.OPEN)));
        when(stageStore.findEventsByStage(PLAN_STAGE_ID))
                .thenReturn(List.of(
                        planRecordedEvent("finalized"),
                        planReviewedEvent(ReviewRound.VERDICT_APPROVED, null)));
        when(threadStore.findBrainThreadByTask(TASK_ID)).thenReturn(Optional.of(brainThread()));
        when(turnStore.listTurnsByTaskId("brain-1", 50)).thenReturn(List.of(
                turn(PLAN_STAGE_ID.toString(), TurnInitiator.unattended("brain-plan-self-review"))));

        service.reconcilePlanSelfReviews();

        verify(scheduler).enqueueStageTurn(
                any(), anyString(), eq(TASK_ID), eq(PLAN_STAGE_ID.toString()),
                argThat(initiator -> "brain-plan-self-review".equals(initiator.source())), isNull(), any());
        verify(stageStore, never()).recordEvent(
                eq(PLAN_STAGE_ID), eq(TASK_ID), eq(StageEventType.PLAN_SELF_REVIEWED), any());
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
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(planning));
        when(stageStore.findActiveStage(TASK_ID)).thenReturn(Optional.of(planStage(StageState.OPEN)));
        when(stageStore.findEventsByStage(PLAN_STAGE_ID)).thenReturn(List.of(first, reviewed, revised));
        when(threadStore.findBrainThreadByTask(TASK_ID)).thenReturn(Optional.of(brainThread()));
        when(turnStore.listTurnsByTaskId("brain-1", 50)).thenReturn(List.of(oldReview));

        service.reconcilePlanSelfReviews();

        verify(scheduler).enqueueStageTurn(
                any(), anyString(), eq(TASK_ID), eq(PLAN_STAGE_ID.toString()),
                argThat(initiator -> "brain-plan-self-review".equals(initiator.source())), isNull(), any());
        verify(stageStore, never()).recordEvent(
                eq(PLAN_STAGE_ID), eq(TASK_ID), eq(StageEventType.PLAN_SELF_REVIEWED), any());
    }

    @Test
    void recordVerdictForPlanScopeWritesTheSelfReviewedMarkerAndTheTimelineEvent()
    {
        when(stageStore.findStageById(PLAN_STAGE_ID))
                .thenReturn(Optional.of(planStage(StageState.OPEN)));
        when(stageStore.findEventsByStage(PLAN_STAGE_ID))
                .thenReturn(List.of(planRecordedEvent("finalized")));

        service.recordVerdict(TASK_ID, PLAN_STAGE_ID.toString(), "plan", ReviewRound.VERDICT_APPROVED);

        verify(stageStore).recordEvent(
                eq(PLAN_STAGE_ID), eq(TASK_ID), eq(StageEventType.PLAN_SELF_REVIEWED),
                eq(Map.of(
                        "verdict", ReviewRound.VERDICT_APPROVED,
                        "reviewedRevisionId", "plan-rev-1")));
        // Exactly one pass (R20), so iteration is always 1. A no-op when the
        // plan predates the local PR (the usual case) — PRServiceImpl backs
        // that with its own backfill, verified separately in PRServiceImpl's
        // own test.
        verify(prService).recordBrainReview(TASK_ID, "plan", ReviewRound.VERDICT_APPROVED, 1, null);
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
        when(turnStore.findTurnIdByKickKey(
                ReviewRoundStateMachine.kickKey(triaging, "brain-review")))
                .thenReturn(Optional.of("verdict-turn"));

        service.recordVerdict(TASK_ID, "run-stage", "dev", ReviewRound.VERDICT_CHANGES_REQUESTED);

        verify(roundMachine).recordVerdict(
                triaging.id(), "verdict-turn", ReviewRound.VERDICT_CHANGES_REQUESTED);
        verify(prService, never()).recordBrainReview(any(), any(), any(), anyInt(), any());
    }

    @Test
    void devVerdictForAnotherRunCannotReachTheLiveRound()
    {
        ReviewRound triaging = brainRound(ReviewRound.STATUS_TRIAGING).withIterationBumped();
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(triaging));

        service.recordVerdict(
                TASK_ID, "run-stage", "different-run", "dev", ReviewRound.VERDICT_APPROVED);

        verify(roundMachine, never()).recordVerdict(anyString(), anyString(), anyString());
        verify(agentRuns, never()).findById(anyString());
    }

    // ── R21-R23: code lock-point review loop ─────────────────────────────

    @Test
    void approvedDevVerdictWithAnOpenBrainRootPersistsChangesRequested()
    {
        ReviewRound triaging = brainRound(ReviewRound.STATUS_TRIAGING).withIterationBumped();
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(triaging));
        AgentRun run = new AgentRun(
                triaging.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_LOCAL, null, null,
                "run-stage", AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(agentRuns.findById(triaging.runId())).thenReturn(Optional.of(run));
        PR drafted = PR.create("pr1", TASK_ID, "feature/x", "main", "x", "", NOW);
        when(prService.findByTask(TASK_ID)).thenReturn(Optional.of(drafted));
        when(prService.comments(drafted.id())).thenReturn(List.of(brainComment(
                "c-open", PRComment.SCOPE_FILE_LINE, "src/Foo.java", 42,
                "Null input reaches value.length() before the documented fallback.")));
        when(turnStore.findTurnIdByKickKey(
                ReviewRoundStateMachine.kickKey(triaging, "brain-review")))
                .thenReturn(Optional.of("verdict-turn"));

        service.recordVerdict(TASK_ID, "run-stage", "dev", ReviewRound.VERDICT_APPROVED);

        verify(roundMachine).recordVerdict(
                triaging.id(), "verdict-turn", ReviewRound.VERDICT_CHANGES_REQUESTED);
    }

    @Test
    void devEndLockPointOpensABrainRoundAndLeavesThePrUnflipped()
    {
        PR drafted = PR.create("pr1", TASK_ID, "feature/x", "main", "x", "", NOW);
        when(prService.findById("pr1")).thenReturn(Optional.of(drafted));
        Task task = task();
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task));
        when(roundStore.findByTask(TASK_ID)).thenReturn(List.of());
        AgentRun run = new AgentRun(
                "run1", TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_LOCAL, null, null,
                "stage1", AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        ReviewRound opened = brainRound(ReviewRound.STATUS_TRIAGING).withRunId(run.id());
        when(roundMachine.openBrainInCommand(TASK_ID, "pr1", null, false)).thenReturn(opened);
        when(roundStore.findById(opened.id())).thenReturn(Optional.of(opened));
        when(agentRuns.findById(run.id())).thenReturn(Optional.of(run));
        when(threadStore.findBrainThreadByTask(TASK_ID)).thenReturn(Optional.of(brainThread()));

        PR result = service.reviewBeforeLocalOpen("pr1", "claude-code");

        assertThat(result.status()).isEqualTo(PR.STATUS_LOCAL_DRAFTED);
        verify(roundMachine).openBrainInCommand(TASK_ID, "pr1", null, false);
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
        verify(agentRuns, never()).openInCommand(any(), any(), any(), any(), any(), any());
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
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(live));
        assertThat(service.pauseActiveReview(TASK_ID, "user_paused_task")).isTrue();

        verify(roundMachine).parkInCommand(TASK_ID, live.id(), "user_paused_task");
        verify(agentRuns, never()).pause(anyString(), anyString());
        verify(prService, never()).recordBrainReviewFailed(
                anyString(), anyString(), anyInt(), anyString(), anyString(), anyString());
    }

    @Test
    void failedOwnedRoundTurnUsesTheTurnAsItsTimelineAttemptIdentity()
    {
        ReviewRound live = brainRound(ReviewRound.STATUS_TRIAGING).withIterationBumped();
        ThreadTurn failed = runTurnWithId(
                "failed-review-attempt", "run-stage", "brain-review",
                ThreadTurnStatus.FAILED, live.runId());
        String kickKey = ReviewRoundStateMachine.kickKey(live, "brain-review");
        when(turnStore.findTurnById(failed.id())).thenReturn(Optional.of(failed));
        when(turnStore.findTurnIdByKickKey(kickKey)).thenReturn(Optional.of(failed.id()));
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(live));
        when(roundMachine.recordOwnedTurnEndedInCommand(TASK_ID, live.id(), failed.id()))
                .thenReturn(new ReviewRoundStateMachine.OwnedTurnEnded(
                        live, failed, ReviewRoundStateMachine.OwnedTurnAction.PAUSED));

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, failed.id(), true));

        verify(prService).recordBrainReviewStarted(
                TASK_ID, "dev", live.iteration(), live.id(), failed.id());
        verify(prService).recordBrainReviewFailed(
                TASK_ID, "dev", live.iteration(), live.id(),
                "review_turn_failed", failed.id());
    }

    @Test
    void budgetPausedReviewRecordsItsVerdictWithoutReadingTranscriptThenParksTaskRoundAndRun()
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

        service.onTurnBudgetPaused(new TaskTurnBudgetPausedEvent(TASK_ID, turn.id()));

        verify(prService).recordBrainReview(
                TASK_ID, "dev", ReviewRound.VERDICT_APPROVED,
                live.iteration(), live.id(), turn.id());
        verify(threadStore, never()).listStageMessages(anyString());
        verify(threadStore, never()).listMessages(anyString());
        verify(prService, never()).recordBrainReviewFailed(
                anyString(), anyString(), anyInt(), anyString(), anyString(), anyString());
        verify(roundMachine).parkInCommand(
                TASK_ID, live.id(), "brain_review_budget_paused");
        verify(phaseMachine).pauseInCommand(
                TASK_ID, Actor.AGENT, "brain_review_budget_paused");
        verify(taskStore).updateRuntimeFailure(TASK_ID, null, "brain_review_budget_paused");
    }

    @Test
    void budgetPausedReviewStillRecordsItsVerdictAfterUserPauseWonTheRace()
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

        service.onTurnBudgetPaused(new TaskTurnBudgetPausedEvent(TASK_ID, turn.id()));

        verify(prService).recordBrainReview(
                TASK_ID, "dev", ReviewRound.VERDICT_APPROVED,
                paused.iteration(), paused.id(), turn.id());
        verify(threadStore, never()).listStageMessages(anyString());
        verify(threadStore, never()).listMessages(anyString());
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
                "brain_fix_budget_paused", turn.id());
        verify(prService, never()).recordBrainReview(
                anyString(), anyString(), any(), anyInt(), anyString());
        verify(roundMachine).parkInCommand(
                TASK_ID, live.id(), "brain_fix_budget_paused");
        verify(phaseMachine).pauseInCommand(
                TASK_ID, Actor.AGENT, "brain_fix_budget_paused");
        verify(taskStore).updateRuntimeFailure(TASK_ID, null, "brain_fix_budget_paused");
    }

    @Test
    void resumeParkedReviewOpensAReplacementRunAndRetriesTheBrain()
    {
        ReviewRound parked = pausedFrom(
                brainRound(ReviewRound.STATUS_PAUSED), ReviewRound.STATUS_TRIAGING);
        Task task = taskAt(TaskPhase.INTERNAL_REVIEW);
        PR drafted = PR.create("pr1", TASK_ID, "feature/x", "main", "x", "", NOW);
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task));
        when(roundStore.findByTask(TASK_ID)).thenReturn(List.of(parked));
        when(prService.findByTask(TASK_ID)).thenReturn(Optional.of(drafted));
        when(prService.comments("pr1")).thenReturn(List.of());
        AgentRun replacement = new AgentRun(
                "replacement-run", TASK_ID, AgentRun.KIND_REVIEW_ROUND,
                AgentRun.SOURCE_LOCAL, null, null, "replacement-stage",
                AgentRun.STATUS_QUEUED, 0, null, null, null, NOW, null);
        ReviewRound resumed = parked.withStatus(ReviewRound.STATUS_TRIAGING)
                .withRunId(replacement.id());
        when(roundMachine.resumeInCommand(TASK_ID, parked.id(), "review_resumed"))
                .thenReturn(resumed);
        when(roundStore.findById(parked.id())).thenReturn(Optional.of(resumed));
        when(agentRuns.findById(replacement.id())).thenReturn(Optional.of(replacement));
        when(threadStore.findBrainThreadByTask(TASK_ID)).thenReturn(Optional.of(brainThread()));
        when(validation.run(TASK_ID)).thenReturn(new ValidationPassResult(true, 0, List.of()));

        assertThat(service.resumeParkedReview(TASK_ID)).isTrue();

        verify(roundMachine).resumeInCommand(TASK_ID, parked.id(), "review_resumed");
        verify(scheduler).enqueueStageTurnOnce(
                eq(ReviewRoundStateMachine.kickKey(resumed, "brain-review")),
                any(), anyString(), eq(TASK_ID), eq("replacement-stage"),
                argThat(initiator -> "brain-review".equals(initiator.source())),
                eq("replacement-run"), any());
        verify(validation).run(TASK_ID);
    }

    @Test
    void resumeOfABudgetPausedReviewRestartsItsRunAndClearsTheStaleVerdict()
    {
        ReviewRound parked = pausedFrom(brainRound(ReviewRound.STATUS_PAUSED),
                ReviewRound.STATUS_TRIAGING)
                .withIterationBumped()
                .withBrainVerdict(ReviewRound.VERDICT_APPROVED);
        Task task = taskAt(TaskPhase.INTERNAL_REVIEW);
        PR drafted = PR.create("pr1", TASK_ID, "feature/x", "main", "x", "", NOW);
        AgentRun replacement = new AgentRun(
                "replacement-budget-run", TASK_ID, AgentRun.KIND_REVIEW_ROUND,
                AgentRun.SOURCE_LOCAL, null, null, "paused-stage",
                AgentRun.STATUS_QUEUED, 0, null, null, null, NOW, null);
        ReviewRound resumed = parked.withStatus(ReviewRound.STATUS_TRIAGING)
                .withRunId(replacement.id())
                .withBrainVerdict(null);
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task));
        when(roundStore.findByTask(TASK_ID)).thenReturn(List.of(parked));
        when(prService.findByTask(TASK_ID)).thenReturn(Optional.of(drafted));
        when(prService.comments("pr1")).thenReturn(List.of());
        when(roundMachine.resumeInCommand(TASK_ID, parked.id(), "review_resumed"))
                .thenReturn(resumed);
        when(roundStore.findById(parked.id())).thenReturn(Optional.of(resumed));
        when(agentRuns.findById(replacement.id())).thenReturn(Optional.of(replacement));
        when(threadStore.findBrainThreadByTask(TASK_ID)).thenReturn(Optional.of(brainThread()));
        when(validation.run(TASK_ID)).thenReturn(new ValidationPassResult(true, 0, List.of()));

        assertThat(service.resumeParkedReview(TASK_ID)).isTrue();

        verify(roundMachine).resumeInCommand(TASK_ID, parked.id(), "review_resumed");
        verify(scheduler).enqueueStageTurnOnce(
                eq(ReviewRoundStateMachine.kickKey(resumed, "brain-review")),
                any(), anyString(), eq(TASK_ID), eq(replacement.stageId()),
                argThat(initiator -> "brain-review".equals(initiator.source())),
                eq(replacement.id()), any());
    }

    @Test
    void resumeParkedReviewRerunsFailedValidationBeforeAnotherBrainPass()
    {
        ReviewRound parked = pausedFrom(
                brainRound(ReviewRound.STATUS_PAUSED), ReviewRound.STATUS_TRIAGING);
        Task task = taskAt(TaskPhase.INTERNAL_REVIEW);
        PR drafted = PR.create("pr1", TASK_ID, "feature/x", "main", "x", "", NOW);
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task));
        when(roundStore.findByTask(TASK_ID)).thenReturn(List.of(parked));
        when(prService.findByTask(TASK_ID)).thenReturn(Optional.of(drafted));
        when(prService.comments("pr1")).thenReturn(List.of());
        when(validation.run(TASK_ID)).thenReturn(new ValidationPassResult(false, 0, List.of()));
        when(roundMachine.parkInCommand(
                TASK_ID, parked.id(), "brain_fixes_validation_failed"))
                .thenReturn(parked);

        assertThat(service.resumeParkedReview(TASK_ID)).isFalse();

        verify(validation).run(TASK_ID);
        verify(roundMachine).parkInCommand(
                TASK_ID, parked.id(), "brain_fixes_validation_failed");
        verify(phaseMachine).parkOperationalInCommand(
                TASK_ID, Actor.AGENT, "brain_fixes_validation_failed");
        verify(prService).recordBrainReviewFailed(
                TASK_ID, "dev", parked.iteration(), parked.id(),
                "brain_fixes_validation_failed", null);
        verify(scheduler, never()).enqueueStageTurn(
                any(), anyString(), anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    void resumeParkedExternalRoundRevalidatesAndRestartsRoundScopedVerification()
    {
        String remoteStageId = "00000000-0000-0000-0000-0000000000b1";
        ReviewRound parked = pausedFrom(
                round(ReviewRound.STATUS_PAUSED), ReviewRound.STATUS_TRIAGING);
        Task task = taskAt(TaskPhase.AWAITING_REMOTE_REVIEW);
        AgentRun replacement = new AgentRun(
                "replacement-remote-run", TASK_ID, AgentRun.KIND_REVIEW_ROUND,
                AgentRun.SOURCE_REMOTE, remoteStageId, null, remoteStageId,
                AgentRun.STATUS_QUEUED, 0, null, null, null, NOW, null);
        ReviewRound resumed = parked.withStatus(ReviewRound.STATUS_TRIAGING)
                .withRunId(replacement.id());
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task));
        when(roundStore.findByTask(TASK_ID)).thenReturn(List.of(parked));
        when(roundMachine.resumeInCommand(TASK_ID, parked.id(), "review_resumed"))
                .thenReturn(resumed);
        when(roundStore.findById(parked.id())).thenReturn(Optional.of(resumed));
        when(agentRuns.findById(replacement.id())).thenReturn(Optional.of(replacement));
        when(threadStore.findBrainThreadByTask(TASK_ID)).thenReturn(Optional.of(brainThread()));
        when(validation.run(TASK_ID)).thenReturn(new ValidationPassResult(true, 0, List.of()));

        assertThat(service.resumeParkedReview(TASK_ID)).isTrue();

        verify(validation).run(TASK_ID);
        verify(roundMachine).resumeInCommand(TASK_ID, parked.id(), "review_resumed");
        verify(scheduler).enqueueStageTurnOnce(
                eq(ReviewRoundStateMachine.kickKey(resumed, "brain-review")),
                any(), anyString(), eq(TASK_ID), eq(remoteStageId),
                argThat(initiator -> "brain-review".equals(initiator.source())),
                eq(replacement.id()), any());
    }

    @Test
    void resumedAddressingUsesTheReplacementRunAsANewTimelineAttempt()
    {
        ReviewRound parked = pausedFrom(
                brainRound(ReviewRound.STATUS_PAUSED), ReviewRound.STATUS_ADDRESSING)
                .withIterationBumped();
        Task task = taskAt(TaskPhase.INTERNAL_REVIEW);
        PR drafted = PR.create("pr1", TASK_ID, "feature/x", "main", "x", "", NOW);
        AgentRun replacement = new AgentRun(
                "replacement-fix-run", TASK_ID, AgentRun.KIND_REVIEW_ROUND,
                AgentRun.SOURCE_LOCAL, null, null, "replacement-stage",
                AgentRun.STATUS_QUEUED, 0, null, null, null, NOW, null);
        ReviewRound resumed = parked.withStatus(ReviewRound.STATUS_ADDRESSING)
                .withRunId(replacement.id());
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task));
        when(roundStore.findByTask(TASK_ID)).thenReturn(List.of(parked));
        when(prService.findByTask(TASK_ID)).thenReturn(Optional.of(drafted));
        when(prService.comments("pr1")).thenReturn(List.of(brainComment(
                "open-root", PRComment.SCOPE_FILE_LINE, "src/Foo.java", 42, "still open")));
        when(roundMachine.resumeInCommand(TASK_ID, parked.id(), "review_resumed"))
                .thenReturn(resumed);
        when(roundStore.findById(parked.id())).thenReturn(Optional.of(resumed));
        when(agentRuns.findById(replacement.id())).thenReturn(Optional.of(replacement));
        when(threadStore.findThreadById(task.threadId())).thenReturn(Optional.of(idleTaskThread()));

        assertThat(service.resumeParkedReview(TASK_ID)).isTrue();

        verify(validation, never()).run(TASK_ID);
        verify(prService, never()).recordBrainReviewAddressing(
                anyString(), anyString(), anyInt(), anyString(), anyString());
        verify(scheduler).enqueueStageTurnOnce(
                eq(ReviewRoundStateMachine.kickKey(resumed, "brain-review-fix")),
                any(), anyString(), eq(TASK_ID), eq(replacement.stageId()),
                argThat(initiator -> "brain-review-fix".equals(initiator.source())),
                eq(replacement.id()), any());
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
        AgentRun run = new AgentRun(
                "run2", TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_LOCAL, null, null,
                "stage2", AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        ReviewRound opened = brainRound(ReviewRound.STATUS_TRIAGING).withRunId(run.id());
        when(roundMachine.openBrainInCommand(TASK_ID, "pr1", null, false)).thenReturn(opened);
        when(roundStore.findById(opened.id())).thenReturn(Optional.of(opened));
        when(agentRuns.findById(run.id())).thenReturn(Optional.of(run));
        when(threadStore.findBrainThreadByTask(TASK_ID)).thenReturn(Optional.of(brainThread()));

        service.reviewAfterLocalComments("pr1");

        verify(roundMachine).openBrainInCommand(TASK_ID, "pr1", null, false);
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
        AgentRun run = new AgentRun(
                "run2", TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_LOCAL, null, null,
                "stage2", AgentRun.STATUS_QUEUED, 0, null, null, null, NOW, null);
        ReviewRound opened = brainRound(ReviewRound.STATUS_ADDRESSING).withRunId(run.id());
        when(roundMachine.openBrainInCommand(TASK_ID, "pr1", null, true)).thenReturn(opened);
        when(roundStore.findById(opened.id())).thenReturn(Optional.of(opened));
        when(agentRuns.findById(run.id())).thenReturn(Optional.of(run));
        when(threadStore.findThreadById(task.threadId())).thenReturn(Optional.of(idleTaskThread()));

        service.reviewAfterLocalComments("pr1");

        verify(scheduler).enqueueStageTurnOnce(
                eq(ReviewRoundStateMachine.kickKey(opened, "brain-review-fix")),
                any(), argThat(prompt -> prompt.contains("[id: older-root]")), eq(TASK_ID), eq("stage2"),
                argThat(initiator -> "brain-review-fix".equals(initiator.source())), eq("run2"), any());
        verify(roundMachine).openBrainInCommand(TASK_ID, "pr1", null, true);
        verify(prService, never()).recordBrainReviewStarted(anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void aChangesRequestedVerdictWithBudgetRemainingEnqueuesAFixTurn()
    {
        ReviewRound triaging = brainRound(ReviewRound.STATUS_TRIAGING).withBrainVerdict(
                ReviewRound.VERDICT_CHANGES_REQUESTED);
        ReviewRound addressing = triaging.withStatus(ReviewRound.STATUS_ADDRESSING);
        ThreadTurn reviewTurn = runTurnWithId(
                "turn-5", "run-stage", "brain-review",
                ThreadTurnStatus.COMPLETED, triaging.runId());
        when(turnStore.findTurnById(reviewTurn.id())).thenReturn(Optional.of(reviewTurn));
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(triaging));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task()));
        AgentRun run = new AgentRun(
                triaging.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_LOCAL, null, null,
                "run-stage", AgentRun.STATUS_QUEUED, 0, null, null, null, NOW, null);
        when(agentRuns.findById(triaging.runId())).thenReturn(Optional.of(run));
        when(roundStore.findById(triaging.id())).thenReturn(
                Optional.of(triaging), Optional.of(addressing), Optional.of(addressing));
        ownedTurnEnds(triaging, reviewTurn, ReviewRoundStateMachine.OwnedTurnAction.CONCLUDE);
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
        service.driveRound(addressing.id());

        verify(roundMachine).concludeBrainInCommand(TASK_ID, triaging.id(), reviewTurn.id());
        verify(scheduler).enqueueStageTurnOnce(
                eq(ReviewRoundStateMachine.kickKey(addressing, "brain-review-fix")),
                eq(taskThread),
                argThat(prompt -> prompt.contains("[id: c1]")
                          && prompt.contains("src/Foo.java:42")
                          && prompt.contains("Guard the null branch")
                          && prompt.contains("Reply @you: Please preserve the original exception too.")
                          && prompt.contains("resolution='addressed' and its required reply")
                          && !prompt.contains("reply via record_pr_comment with parent_comment_id "
                                  + "if it's a question")
                          && prompt.contains("if you disagree")
                          && prompt.contains("resolution='dismissed'")
                          && !prompt.contains("record_round_reply")),
                eq(TASK_ID), eq("run-stage"),
                argThat(i -> "brain-review-fix".equals(i.source())), anyString(), any());
        verify(roundMachine).recordKickAdmittedInCommand(
                TASK_ID, addressing.id(), ReviewRound.STATUS_ADDRESSING,
                addressing.kickAttempt());
    }

    @Test
    void legacyPublishGateIsRetiredBeforeABrainFixTurnIsScheduled()
    {
        ReviewRound triaging = brainRound(ReviewRound.STATUS_TRIAGING).withBrainVerdict(
                ReviewRound.VERDICT_CHANGES_REQUESTED);
        ThreadTurn completed = runTurnWithId(
                "legacy-review", "run-stage", "brain-review",
                ThreadTurnStatus.COMPLETED, triaging.runId());
        when(turnStore.findTurnById(completed.id())).thenReturn(Optional.of(completed));
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(triaging));
        ownedTurnEnds(triaging, completed, ReviewRoundStateMachine.OwnedTurnAction.NONE);

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "legacy-review", false));

        verify(roundMachine).recordOwnedTurnEndedInCommand(
                TASK_ID, triaging.id(), completed.id());
        verify(notifications, never()).supersedeAwaitingReviewForTask(anyString(), anyString());
        verify(taskStore, never()).saveTask(any());
    }

    @Test
    void legacyPublishGateBeingResolvedKeepsTheBrainFixParked()
    {
        ReviewRound triaging = brainRound(ReviewRound.STATUS_TRIAGING).withBrainVerdict(
                ReviewRound.VERDICT_CHANGES_REQUESTED);
        ThreadTurn completed = runTurnWithId(
                "resolving-review", "run-stage", "brain-review",
                ThreadTurnStatus.COMPLETED, triaging.runId());
        when(turnStore.findTurnById(completed.id())).thenReturn(Optional.of(completed));
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(triaging));
        ownedTurnEnds(triaging, completed, ReviewRoundStateMachine.OwnedTurnAction.NONE);

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "resolving-review", false));

        verify(taskStore, never()).saveTask(any());
        verify(threadStore, never()).findThreadById(anyString());
        verify(scheduler, never()).enqueueStageTurnOnce(
                anyString(), any(), anyString(), anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    void anApprovedVerdictConcludesABrainOriginRoundAndFlipsLocalOpen()
    {
        ReviewRound triaging = brainRound(ReviewRound.STATUS_TRIAGING).withBrainVerdict(
                ReviewRound.VERDICT_APPROVED);
        ThreadTurn completed = runTurnWithId(
                "turn-6", "run-stage", "brain-review",
                ThreadTurnStatus.COMPLETED, triaging.runId());
        when(turnStore.findTurnById(completed.id())).thenReturn(Optional.of(completed));
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(triaging));
        when(roundStore.findById(triaging.id())).thenReturn(Optional.of(triaging));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task()));
        PR drafted = PR.create("pr1", TASK_ID, "feature/x", "main", "x", "", NOW);
        when(prService.findByTask(TASK_ID)).thenReturn(Optional.of(drafted));
        ownedTurnEnds(triaging, completed, ReviewRoundStateMachine.OwnedTurnAction.CONCLUDE);

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "turn-6", false));

        verify(prService).recordBrainReview(
                TASK_ID, "dev", ReviewRound.VERDICT_APPROVED, 0,
                triaging.id(), completed.id());
        verify(threadStore, never()).listStageMessages(anyString());
        verify(threadStore, never()).listMessages(anyString());
        verify(roundMachine).concludeBrainInCommand(
                TASK_ID, triaging.id(), completed.id());
        verify(prService, never()).requestUserReview("pr1", "brain");
        verify(notifications, never()).notifyNeedsAttention(any(), any(), any());
        // auto_merge's push trigger listens for this instead of the manual
        // Local Review button.
        verify(events, never()).publishEvent(any(LocalReviewClearedEvent.class));
    }

    @Test
    void approvedVerdictCannotBypassAnOpenBrainRoot()
    {
        ReviewRound triaging = brainRound(ReviewRound.STATUS_TRIAGING).withBrainVerdict(
                ReviewRound.VERDICT_APPROVED);
        ThreadTurn completed = runTurnWithId(
                "approved-with-root", "run-stage", "brain-review",
                ThreadTurnStatus.COMPLETED, triaging.runId());
        when(turnStore.findTurnById(completed.id())).thenReturn(Optional.of(completed));
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(triaging));
        when(roundStore.findById(triaging.id())).thenReturn(Optional.of(triaging));
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
        ReviewRound changesRequested = triaging.withBrainVerdict(
                ReviewRound.VERDICT_CHANGES_REQUESTED);
        when(roundMachine.recordVerdictInCommand(
                TASK_ID, triaging.id(), completed.id(),
                ReviewRound.VERDICT_CHANGES_REQUESTED))
                .thenReturn(changesRequested);
        ownedTurnEnds(triaging, completed, ReviewRoundStateMachine.OwnedTurnAction.CONCLUDE);

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "approved-with-root", false));

        verify(roundMachine).recordVerdictInCommand(
                TASK_ID, triaging.id(), completed.id(),
                ReviewRound.VERDICT_CHANGES_REQUESTED);
        verify(roundMachine).concludeBrainInCommand(
                TASK_ID, triaging.id(), completed.id());
        verify(prService, never()).requestUserReview(anyString(), anyString());
    }

    @Test
    void approvedReReviewOfAnOpenLocalPrReturnsToThePushGateWithoutFlippingItAgain()
    {
        ReviewRound triaging = brainRound(ReviewRound.STATUS_TRIAGING).withBrainVerdict(
                ReviewRound.VERDICT_APPROVED);
        ThreadTurn completed = runTurnWithId(
                "local-rereview", "run-stage", "brain-review",
                ThreadTurnStatus.COMPLETED, triaging.runId());
        when(turnStore.findTurnById(completed.id())).thenReturn(Optional.of(completed));
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(triaging));
        when(roundStore.findById(triaging.id())).thenReturn(Optional.of(triaging));
        Task task = taskAt(TaskPhase.INTERNAL_REVIEW);
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task));
        AgentRun run = new AgentRun(
                triaging.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_LOCAL, null, null,
                "run-stage", AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(agentRuns.findById(triaging.runId())).thenReturn(Optional.of(run));
        PR open = PR.create("pr1", TASK_ID, "feature/x", "main", "x", "", NOW)
                .withStatus(PR.STATUS_LOCAL_OPEN, NOW);
        when(prService.findByTask(TASK_ID)).thenReturn(Optional.of(open));
        ownedTurnEnds(triaging, completed, ReviewRoundStateMachine.OwnedTurnAction.CONCLUDE);

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "local-rereview", false));

        verify(roundMachine).concludeBrainInCommand(
                TASK_ID, triaging.id(), completed.id());
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
        ownedTurnEnds(addressing, completed, ReviewRoundStateMachine.OwnedTurnAction.VALIDATE);

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "brain-fix-open", false));

        verify(validation, never()).run(anyString());
        verify(claimedValidation).claimAndRunReviewRound(
                TASK_ID, addressing.id(), completed.id());
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
        ownedTurnEnds(addressing, completed, ReviewRoundStateMachine.OwnedTurnAction.PAUSED);

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "brain-fix-open", false));

        verify(roundMachine).recordOwnedTurnEndedInCommand(
                TASK_ID, addressing.id(), completed.id());
        verify(scheduler, never()).enqueueStageTurn(
                any(), anyString(), anyString(), anyString(), any(), anyString(), any());
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
        ownedTurnEnds(triaging, reviewTurn, ReviewRoundStateMachine.OwnedTurnAction.CONCLUDE);
        when(roundStore.findById(triaging.id())).thenReturn(Optional.of(triaging));

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "external-review", false));

        verify(roundMachine).concludeBrainInCommand(
                TASK_ID, triaging.id(), reviewTurn.id());
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
        ThreadTurn completed = runTurnWithId(
                "turn-7", "run-stage", "brain-review",
                ThreadTurnStatus.COMPLETED, lastIteration.runId());
        when(turnStore.findTurnById(completed.id())).thenReturn(Optional.of(completed));
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(lastIteration));
        when(roundStore.findById(lastIteration.id())).thenReturn(Optional.of(lastIteration));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task()));
        AgentRun run = new AgentRun(
                lastIteration.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_LOCAL, null, null,
                "run-stage", AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(agentRuns.findById(lastIteration.runId())).thenReturn(Optional.of(run));
        PR drafted = PR.create("pr1", TASK_ID, "feature/x", "main", "x", "", NOW);
        when(prService.findByTask(TASK_ID)).thenReturn(Optional.of(drafted));
        when(prService.comments("pr1")).thenReturn(List.of(brainComment(
                "c-open", PRComment.SCOPE_FILE_LINE, "src/Foo.java", 42, "Still unresolved")));
        ownedTurnEnds(lastIteration, completed, ReviewRoundStateMachine.OwnedTurnAction.CONCLUDE);

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "turn-7", false));

        assertThat(lastIteration.brainBudgetExhausted()).isTrue();
        verify(roundMachine).concludeBrainInCommand(
                TASK_ID, lastIteration.id(), completed.id());
        verify(notifications, never()).notifyNeedsAttention(anyString(), anyString(), anyString());
        // approved=false on an escalation — auto_merge must not push unreviewed
        // concerns straight to remote.
        verify(events, never()).publishEvent(any(LocalReviewClearedEvent.class));
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
        when(roundStore.findById(triaging.id())).thenReturn(Optional.of(triaging));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task()));
        ReviewRound parked = pausedFrom(triaging, ReviewRound.STATUS_TRIAGING);
        when(roundMachine.parkInCommand(
                TASK_ID, triaging.id(), "brain_review_verdict_missing"))
                .thenReturn(parked);
        when(turnStore.findTurnIdByKickKey(
                ReviewRoundStateMachine.kickKey(triaging, "brain-review")))
                .thenReturn(Optional.of(turn.id()));
        ownedTurnEnds(triaging, turn, ReviewRoundStateMachine.OwnedTurnAction.CONCLUDE);

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, turn.id(), false));
        }
        finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(roundMachine, timeout(1_000)).parkInCommand(
                TASK_ID, triaging.id(), "brain_review_verdict_missing");
        verify(prService, timeout(1_000)).recordBrainReviewFailed(
                TASK_ID, "dev", triaging.iteration(), triaging.id(),
                "brain_review_verdict_missing", turn.id());
        verify(events, timeout(1_000)).publishEvent((Object) argThat(
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
        when(roundStore.findById(exhausted.id())).thenReturn(Optional.of(exhausted));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task()));
        PR drafted = PR.create("pr1", TASK_ID, "feature/x", "main", "x", "", NOW);
        when(prService.findByTask(TASK_ID)).thenReturn(Optional.of(drafted));
        when(prService.comments("pr1")).thenReturn(List.of());
        ownedTurnEnds(exhausted, turn, ReviewRoundStateMachine.OwnedTurnAction.CONCLUDE);

        TransactionSynchronizationManager.initSynchronization();
        try {
            service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, turn.id(), false));
        }
        finally {
            TransactionSynchronizationManager.clearSynchronization();
        }

        verify(roundMachine, timeout(1_000)).concludeBrainInCommand(
                TASK_ID, exhausted.id(), turn.id());
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
        ThreadTurn completed = runTurnWithId(
                "turn-8", "run-stage", "brain-review",
                ThreadTurnStatus.COMPLETED, neverVerdicted.runId());
        when(turnStore.findTurnById(completed.id())).thenReturn(Optional.of(completed));
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(neverVerdicted));
        when(roundStore.findById(neverVerdicted.id())).thenReturn(Optional.of(neverVerdicted));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task()));
        ReviewRound parked = pausedFrom(neverVerdicted, ReviewRound.STATUS_TRIAGING);
        when(roundMachine.parkInCommand(
                TASK_ID, neverVerdicted.id(), "brain_review_verdict_missing"))
                .thenReturn(parked);
        when(turnStore.findTurnIdByKickKey(
                ReviewRoundStateMachine.kickKey(neverVerdicted, "brain-review")))
                .thenReturn(Optional.of(completed.id()));
        ownedTurnEnds(
                neverVerdicted, completed, ReviewRoundStateMachine.OwnedTurnAction.CONCLUDE);

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "turn-8", false));

        assertThat(neverVerdicted.brainVerdict()).isNull();
        verify(roundMachine).parkInCommand(
                TASK_ID, neverVerdicted.id(), "brain_review_verdict_missing");
        verify(prService).recordBrainReviewFailed(
                TASK_ID, "dev", neverVerdicted.iteration(), neverVerdicted.id(),
                "brain_review_verdict_missing", completed.id());
    }

    @Test
    void missingVerdictParksWithoutReadingTheTranscript()
    {
        ReviewRound triaging = brainRound(ReviewRound.STATUS_TRIAGING).withIterationBumped();
        ThreadTurn completed = runTurn(
                "run-stage", "brain-review", ThreadTurnStatus.COMPLETED, triaging.runId());
        Task task = taskAt(TaskPhase.INTERNAL_REVIEW);
        when(turnStore.findTurnById("ghost-result")).thenReturn(Optional.of(completed));
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(triaging));
        when(roundStore.findById(triaging.id())).thenReturn(Optional.of(triaging));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task));
        ReviewRound parked = pausedFrom(triaging, ReviewRound.STATUS_TRIAGING);
        when(roundMachine.parkInCommand(
                TASK_ID, triaging.id(), "brain_review_verdict_missing"))
                .thenReturn(parked);
        when(turnStore.findTurnIdByKickKey(
                ReviewRoundStateMachine.kickKey(triaging, "brain-review")))
                .thenReturn(Optional.of(completed.id()));
        ownedTurnEnds(triaging, completed, ReviewRoundStateMachine.OwnedTurnAction.CONCLUDE);

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "ghost-result", false));

        verify(prService, never()).recordBrainReview(
                anyString(), anyString(), any(), anyInt(), anyString());
        verify(threadStore, never()).listStageMessages(anyString());
        verify(threadStore, never()).listMessages(anyString());
        verify(prService).recordBrainReviewFailed(
                TASK_ID, "dev", triaging.iteration(), triaging.id(),
                "brain_review_verdict_missing", completed.id());
        verify(roundMachine).parkInCommand(
                TASK_ID, triaging.id(), "brain_review_verdict_missing");
        verify(scheduler, never()).enqueueStageTurn(
                any(), anyString(), anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    void reviewBeforeRoundGateStartsTheVerificationPassInsteadOfArmingTheGateDirectly()
    {
        ReviewRound addressed = round(ReviewRound.STATUS_ADDRESSING);
        Task task = taskAt(TaskPhase.AWAITING_REMOTE_REVIEW);
        ThreadTurn completed = runTurnWithId(
                "external-fix", "run-stage", "review-round",
                ThreadTurnStatus.COMPLETED, addressed.runId());
        when(turnStore.listTurnsByAgentRunId(addressed.runId(), 100))
                .thenReturn(List.of(completed));

        service.reviewBeforeRoundGate(addressed, task);

        verify(claimedValidation).claimAndRunReviewRound(
                TASK_ID, addressed.id(), completed.id());
        verify(roundMachine, never()).concludeBrain(anyString(), anyString());
    }

    @Test
    void reviewBeforeRoundGateStopsAndParksWhenValidationFails()
    {
        ReviewRound addressed = round(ReviewRound.STATUS_ADDRESSING);
        Task task = taskAt(TaskPhase.AWAITING_REMOTE_REVIEW);
        when(roundStore.findById(addressed.id())).thenReturn(Optional.of(addressed));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task));
        ReviewRound parked = pausedFrom(addressed, ReviewRound.STATUS_ADDRESSING);
        when(roundMachine.parkInCommand(
                TASK_ID, addressed.id(), "review_fixes_validation_failed"))
                .thenReturn(parked);

        service.onRoundValidationFinished(new ReviewRoundValidationFinishedEvent(
                TASK_ID, addressed.id(), "external-fix", "claim", "fp", false));

        verify(phaseMachine).parkOperationalInCommand(
                TASK_ID, Actor.AGENT, "review_fixes_validation_failed");
        verify(roundMachine).parkInCommand(
                TASK_ID, addressed.id(), "review_fixes_validation_failed");
        verify(prService).recordBrainReviewFailed(
                TASK_ID, "round", addressed.iteration(), addressed.id(),
                "review_fixes_validation_failed", null);
        verify(scheduler, never()).enqueueStageTurn(any(), anyString(), anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    void reviewBeforeRoundGateParksInsteadOfFailingOpenWhenItsRunIsMissing()
    {
        ReviewRound addressed = round(ReviewRound.STATUS_ADDRESSING);
        Task task = taskAt(TaskPhase.AWAITING_REMOTE_REVIEW);
        when(roundStore.findById(addressed.id())).thenReturn(Optional.of(addressed));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task));
        ReviewRound parked = pausedFrom(addressed, ReviewRound.STATUS_ADDRESSING);
        when(roundMachine.recordDeliveryFailure(
                addressed.id(), addressed.kickAttempt(), "review_turn_enqueue_failed"))
                .thenReturn(parked);

        service.driveRound(addressed.id());

        verify(roundMachine).recordDeliveryFailure(
                addressed.id(), addressed.kickAttempt(), "review_turn_enqueue_failed");
        verify(scheduler, never()).enqueueStageTurn(any(), anyString(), anyString(), anyString(), any(), anyString(), any());
    }

    // ── reconcileStalledRounds: the intra-thread-multi-tasking backstop ───

    @Test
    void reconcileStalledRoundsAdvancesATriagingRoundOnceItsThreadIsIdle()
    {
        ReviewRound triaging = brainRound(ReviewRound.STATUS_TRIAGING)
                .withBrainVerdict(ReviewRound.VERDICT_CHANGES_REQUESTED);
        when(roundStore.findAllLive()).thenReturn(List.of(triaging));
        when(roundStore.findById(triaging.id())).thenReturn(Optional.of(triaging));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task()));
        AgentRun run = new AgentRun(
                triaging.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_LOCAL, null, null,
                "run-stage", AgentRun.STATUS_QUEUED, 0, null, null, null, NOW, null);
        when(agentRuns.findById(triaging.runId())).thenReturn(Optional.of(run));
        when(threadStore.findBrainThreadByTask(TASK_ID)).thenReturn(Optional.of(brainThread()));

        service.reconcileStalledRounds();

        verify(scheduler).enqueueStageTurnOnce(
                eq(ReviewRoundStateMachine.kickKey(triaging, "brain-review")),
                any(), anyString(), eq(TASK_ID), eq("run-stage"),
                argThat(i -> "brain-review".equals(i.source())), anyString(), any());
        verify(roundMachine).recordKickAdmittedInCommand(
                TASK_ID, triaging.id(), ReviewRound.STATUS_TRIAGING,
                triaging.kickAttempt());
    }

    @Test
    void reconcileStalledRoundsLeavesAUserPausedTaskAlone()
    {
        ReviewRound triaging = brainRound(ReviewRound.STATUS_TRIAGING)
                .withBrainVerdict(ReviewRound.VERDICT_CHANGES_REQUESTED);
        when(roundStore.findAllLive()).thenReturn(List.of(triaging));
        when(roundStore.findById(triaging.id())).thenReturn(Optional.of(triaging));
        when(taskStore.findTaskById(TASK_ID))
                .thenReturn(Optional.of(taskAt(TaskPhase.INTERNAL_REVIEW).withStatus(TaskStatus.PAUSED)));

        service.reconcileStalledRounds();

        verify(agentRuns, never()).findById(anyString());
        verify(scheduler, never()).enqueueStageTurn(
                any(), anyString(), anyString(), anyString(), any(), anyString(), any());
        verify(roundMachine, never()).recordKickAdmittedInCommand(
                anyString(), anyString(), any(), anyInt());
    }

    @Test
    void reconcileStalledRoundsSkipsATriagingRoundWhoseThreadIsStillBusy()
    {
        ReviewRound triaging = brainRound(ReviewRound.STATUS_TRIAGING)
                .withBrainVerdict(ReviewRound.VERDICT_CHANGES_REQUESTED);
        when(roundStore.findAllLive()).thenReturn(List.of(triaging));
        when(roundStore.findById(triaging.id())).thenReturn(Optional.of(triaging));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task()));
        ThreadTurn runningTurn = runTurnWithId(
                "current-review", "run-stage", "brain-review",
                ThreadTurnStatus.RUNNING, triaging.runId());
        AgentRun run = new AgentRun(
                triaging.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_LOCAL, null, null,
                "run-stage", AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(agentRuns.findById(triaging.runId())).thenReturn(Optional.of(run));
        when(turnStore.findTurnIdByKickKey(
                ReviewRoundStateMachine.kickKey(triaging, "brain-review")))
                .thenReturn(Optional.of(runningTurn.id()));
        when(turnStore.findTurnById(runningTurn.id())).thenReturn(Optional.of(runningTurn));

        service.reconcileStalledRounds();

        verify(scheduler, never()).enqueueStageTurn(any(), anyString(), anyString(), anyString(), any(), any(), any());
        verify(roundMachine, never()).recordOwnedTurnEndedInCommand(
                anyString(), anyString(), anyString());
    }

    @Test
    void reconcileStalledRoundsAdvancesAnAddressingRoundOnceItsThreadIsIdle()
    {
        ReviewRound addressing = brainRound(ReviewRound.STATUS_ADDRESSING)
                .withIterationBumped()
                .withBrainVerdict(ReviewRound.VERDICT_CHANGES_REQUESTED);
        when(roundStore.findAllLive()).thenReturn(List.of(addressing));
        when(roundStore.findById(addressing.id())).thenReturn(Optional.of(addressing));
        Task task = taskAt(TaskPhase.INTERNAL_REVIEW);
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task));
        AgentRun run = new AgentRun(
                addressing.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_LOCAL, null, null,
                "run-stage", AgentRun.STATUS_QUEUED, 0, null, null, null, NOW, null);
        when(agentRuns.findById(addressing.runId())).thenReturn(Optional.of(run));
        when(threadStore.findThreadById(task.threadId())).thenReturn(Optional.of(idleTaskThread()));

        service.reconcileStalledRounds();

        verify(scheduler).enqueueStageTurnOnce(
                eq(ReviewRoundStateMachine.kickKey(addressing, "brain-review-fix")),
                any(), anyString(), eq(TASK_ID), eq("run-stage"),
                argThat(i -> "brain-review-fix".equals(i.source())), anyString(), any());
        verify(roundMachine).recordKickAdmittedInCommand(
                TASK_ID, addressing.id(), ReviewRound.STATUS_ADDRESSING,
                addressing.kickAttempt());
    }

    @Test
    void failedAddressingRetryTakesTheExactLivenessPointerBeforeDispatch()
    {
        ReviewRound addressing = withKickAttempt(
                brainRound(ReviewRound.STATUS_ADDRESSING), 1);
        Task task = taskAt(TaskPhase.INTERNAL_REVIEW);
        AgentRun run = new AgentRun(
                addressing.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND,
                AgentRun.SOURCE_LOCAL, null, null, "run-stage",
                AgentRun.STATUS_QUEUED, 0, null, null, null, NOW, null);
        ThreadTurn failed = runTurnWithId(
                "failed-fix", "run-stage", "brain-review-fix",
                ThreadTurnStatus.FAILED, addressing.runId());
        ThreadTurn replacement = runTurnWithId(
                "replacement-fix", "run-stage", "brain-review-fix",
                ThreadTurnStatus.QUEUED, addressing.runId());
        when(roundStore.findById(addressing.id())).thenReturn(Optional.of(addressing));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task));
        when(agentRuns.findById(addressing.runId())).thenReturn(Optional.of(run));
        when(threadStore.findThreadById(task.threadId())).thenReturn(Optional.of(idleTaskThread()));
        when(scheduler.enqueueStageTurnOnce(
                anyString(), any(), anyString(), anyString(), anyString(), any(), anyString(), any()))
                .thenReturn(replacement.id());
        when(taskStore.currentLivenessTurnId(TASK_ID)).thenReturn(Optional.of(failed.id()));
        when(turnStore.findTurnIdByKickKey(
                ReviewRoundStateMachine.kickKey(addressing, "brain-review-fix")))
                .thenReturn(Optional.of(replacement.id()));
        when(turnStore.findTurnIdByKickKey(
                ReviewRoundStateMachine.kickKey(addressing, "brain-review-fix", 0)))
                .thenReturn(Optional.of(failed.id()));
        when(turnStore.findTurnById(failed.id())).thenReturn(Optional.of(failed));
        when(turnStore.findTurnById(replacement.id())).thenReturn(Optional.of(replacement));
        when(taskStore.setCurrentLivenessTurnIdIf(
                TASK_ID, failed.id(), replacement.id())).thenReturn(true);

        service.driveRound(addressing.id());

        verify(taskStore).setCurrentLivenessTurnIdIf(
                TASK_ID, failed.id(), replacement.id());
    }

    @Test
    void adoptedOpenFindingsAdvanceFromTheirZeroIterationFixIntoTheFirstReview()
    {
        ReviewRound addressing = brainRound(ReviewRound.STATUS_ADDRESSING);
        when(roundStore.findAllLive()).thenReturn(List.of(addressing));
        when(roundStore.findById(addressing.id())).thenReturn(Optional.of(addressing));
        Task task = taskAt(TaskPhase.INTERNAL_REVIEW);
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task));
        AgentRun run = new AgentRun(
                addressing.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_LOCAL, null, null,
                "run-stage", AgentRun.STATUS_QUEUED, 0, null, null, null, NOW, null);
        when(agentRuns.findById(addressing.runId())).thenReturn(Optional.of(run));
        when(threadStore.findThreadById(task.threadId())).thenReturn(Optional.of(idleTaskThread()));

        service.reconcileStalledRounds();

        verify(scheduler).enqueueStageTurnOnce(
                eq(ReviewRoundStateMachine.kickKey(addressing, "brain-review-fix")),
                any(), anyString(), eq(TASK_ID), eq("run-stage"),
                argThat(i -> "brain-review-fix".equals(i.source())), anyString(), any());
        verify(prService, never()).recordBrainReviewStarted(
                anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void completedFixDoesNotAdvanceOrSpendAnIterationWithoutABrainThread()
    {
        ReviewRound addressing = brainRound(ReviewRound.STATUS_ADDRESSING).withIterationBumped();
        when(roundStore.findAllLive()).thenReturn(List.of(addressing));
        when(roundStore.findById(addressing.id())).thenReturn(Optional.of(addressing));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(taskAt(TaskPhase.INTERNAL_REVIEW)));
        AgentRun run = new AgentRun(
                addressing.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_LOCAL, null, null,
                "run-stage", AgentRun.STATUS_QUEUED, 0, null, null, null, NOW, null);
        when(agentRuns.findById(addressing.runId())).thenReturn(Optional.of(run));
        when(roundMachine.recordDeliveryFailure(
                addressing.id(), addressing.kickAttempt(), "review_turn_enqueue_failed"))
                .thenReturn(pausedFrom(addressing, ReviewRound.STATUS_ADDRESSING));

        service.reconcileStalledRounds();

        verify(scheduler, never()).enqueueStageTurn(any(), anyString(), anyString(), anyString(), any(), anyString(), any());
        verify(roundMachine).recordDeliveryFailure(
                addressing.id(), addressing.kickAttempt(), "review_turn_enqueue_failed");
        verify(prService, never()).recordBrainReviewStarted(anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void completedFixDoesNotAdvanceOrSpendAnIterationWhenReviewEnqueueFails()
    {
        ReviewRound addressing = brainRound(ReviewRound.STATUS_ADDRESSING).withIterationBumped();
        when(roundStore.findAllLive()).thenReturn(List.of(addressing));
        when(roundStore.findById(addressing.id())).thenReturn(Optional.of(addressing));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(taskAt(TaskPhase.INTERNAL_REVIEW)));
        AgentRun run = new AgentRun(
                addressing.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_LOCAL, null, null,
                "run-stage", AgentRun.STATUS_QUEUED, 0, null, null, null, NOW, null);
        when(agentRuns.findById(addressing.runId())).thenReturn(Optional.of(run));
        when(threadStore.findThreadById(task().threadId())).thenReturn(Optional.of(idleTaskThread()));
        when(scheduler.enqueueStageTurnOnce(
                anyString(), any(), anyString(), anyString(), anyString(), any(), anyString(), any()))
                .thenThrow(new IllegalStateException("queue unavailable"));
        when(roundMachine.recordDeliveryFailure(
                addressing.id(), addressing.kickAttempt(), "review_turn_enqueue_failed"))
                .thenReturn(pausedFrom(addressing, ReviewRound.STATUS_ADDRESSING));

        service.reconcileStalledRounds();

        verify(roundMachine).recordDeliveryFailure(
                addressing.id(), addressing.kickAttempt(), "review_turn_enqueue_failed");
        verify(prService, never()).recordBrainReviewStarted(anyString(), anyString(), anyInt(), anyString());
    }

    @Test
    void reconcileDoesNotReuseACompletedReviewFromAnOlderIteration()
    {
        ReviewRound secondReview = brainRound(ReviewRound.STATUS_TRIAGING)
                .withIterationBumped()
                .withIterationBumped();
        when(roundStore.findAllLive()).thenReturn(List.of(secondReview));
        when(roundStore.findById(secondReview.id())).thenReturn(Optional.of(secondReview));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task()));
        AgentRun run = new AgentRun(
                secondReview.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_LOCAL, null, null,
                "run-stage", AgentRun.STATUS_QUEUED, 0, null, null, null, NOW, null);
        when(agentRuns.findById(secondReview.runId())).thenReturn(Optional.of(run));
        when(threadStore.findBrainThreadByTask(TASK_ID)).thenReturn(Optional.of(brainThread()));
        when(turnStore.listTurnsByAgentRunId(secondReview.runId(), 100)).thenReturn(List.of(
                runTurn("run-stage", "brain-review", ThreadTurnStatus.COMPLETED, secondReview.runId())));

        service.reconcileStalledRounds();

        verify(scheduler).enqueueStageTurnOnce(
                eq(ReviewRoundStateMachine.kickKey(secondReview, "brain-review")),
                any(), anyString(), eq(TASK_ID), eq("run-stage"),
                argThat(i -> "brain-review".equals(i.source())), eq(secondReview.runId()), any());
        verify(scheduler, never()).enqueueStageTurnOnce(
                anyString(), any(), anyString(), anyString(), anyString(),
                argThat(i -> "brain-review-fix".equals(i.source())), anyString(), any());
    }

    @Test
    void reconcileAdvancesTheMarkedReviewForTheCurrentIteration()
    {
        ReviewRound secondReview = brainRound(ReviewRound.STATUS_TRIAGING)
                .withIterationBumped()
                .withIterationBumped()
                .withBrainVerdict(ReviewRound.VERDICT_CHANGES_REQUESTED);
        when(roundStore.findAllLive()).thenReturn(List.of(secondReview));
        when(roundStore.findById(secondReview.id())).thenReturn(Optional.of(secondReview));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task()));
        AgentRun run = new AgentRun(
                secondReview.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_LOCAL, null, null,
                "run-stage", AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(agentRuns.findById(secondReview.runId())).thenReturn(Optional.of(run));
        ThreadTurn current = runTurnWithId(
                "marked-review", "run-stage", "brain-review",
                ThreadTurnStatus.COMPLETED, secondReview.runId(),
                "prompt\n\n[brain-review-iteration:2]");
        when(turnStore.findTurnIdByKickKey(
                ReviewRoundStateMachine.kickKey(secondReview, "brain-review")))
                .thenReturn(Optional.of(current.id()));
        when(turnStore.findTurnById(current.id())).thenReturn(Optional.of(current));
        ownedTurnEnds(secondReview, current, ReviewRoundStateMachine.OwnedTurnAction.CONCLUDE);

        service.reconcileStalledRounds();

        verify(roundMachine).concludeBrainInCommand(
                TASK_ID, secondReview.id(), current.id());
    }

    @Test
    void reconcileStalledRoundsDoesNotReReviewFailedBrainFixes()
    {
        ReviewRound addressing = brainRound(ReviewRound.STATUS_ADDRESSING).withIterationBumped();
        when(roundStore.findAllLive()).thenReturn(List.of(addressing));
        when(roundStore.findById(addressing.id())).thenReturn(Optional.of(addressing));
        Task task = taskAt(TaskPhase.INTERNAL_REVIEW);
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task));
        AgentRun run = new AgentRun(
                addressing.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_LOCAL, null, null,
                "run-stage", AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(agentRuns.findById(addressing.runId())).thenReturn(Optional.of(run));
        ThreadTurn completed = runTurnWithId(
                "validated-fix", "run-stage", "brain-review-fix",
                ThreadTurnStatus.COMPLETED, addressing.runId());
        when(turnStore.findTurnIdByKickKey(
                ReviewRoundStateMachine.kickKey(addressing, "brain-review-fix")))
                .thenReturn(Optional.of(completed.id()));
        when(turnStore.findTurnById(completed.id())).thenReturn(Optional.of(completed));
        ownedTurnEnds(addressing, completed, ReviewRoundStateMachine.OwnedTurnAction.VALIDATE);

        service.reconcileStalledRounds();

        verify(claimedValidation).claimAndRunReviewRound(
                TASK_ID, addressing.id(), completed.id());
        verify(scheduler, never()).enqueueStageTurnOnce(
                anyString(), any(), anyString(), anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    void reconcileStalledRoundsNeverTouchesAnAddressingRoundWhoseFixTurnIsStillRunning()
    {
        // The exact bug this backstop must not introduce: re-driving a round
        // whose fix turn hasn't actually finished (advanceAfterFixTurn has no
        // idle check of its own — the sweep is the only guard).
        ReviewRound addressing = brainRound(ReviewRound.STATUS_ADDRESSING).withIterationBumped();
        when(roundStore.findAllLive()).thenReturn(List.of(addressing));
        when(roundStore.findById(addressing.id())).thenReturn(Optional.of(addressing));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task()));
        AgentRun run = new AgentRun(
                addressing.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_LOCAL, null, null,
                "run-stage", AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(agentRuns.findById(addressing.runId())).thenReturn(Optional.of(run));
        ThreadTurn running = runTurnWithId(
                "running-fix", "run-stage", "brain-review-fix",
                ThreadTurnStatus.RUNNING, addressing.runId());
        when(turnStore.findTurnIdByKickKey(
                ReviewRoundStateMachine.kickKey(addressing, "brain-review-fix")))
                .thenReturn(Optional.of(running.id()));
        when(turnStore.findTurnById(running.id())).thenReturn(Optional.of(running));

        service.reconcileStalledRounds();

        verify(scheduler, never()).enqueueStageTurn(any(), anyString(), anyString(), anyString(), any(), any(), any());
        verify(roundMachine, never()).recordOwnedTurnEndedInCommand(
                anyString(), anyString(), anyString());
    }

    @Test
    void reconcileStalledRoundsDoesNotInferAQueuedExternalBrainTurnCompleted()
    {
        ReviewRound external = round(ReviewRound.STATUS_TRIAGING);
        when(roundStore.findAllLive()).thenReturn(List.of(external));
        when(roundStore.findById(external.id())).thenReturn(Optional.of(external));
        Task task = taskAt(TaskPhase.AWAITING_REMOTE_REVIEW);
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task));
        AgentRun run = new AgentRun(
                external.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_REMOTE, null, null,
                "run-stage", AgentRun.STATUS_RUNNING, 0, null, null, null, NOW, null);
        when(agentRuns.findById(external.runId())).thenReturn(Optional.of(run));
        ThreadTurn queued = runTurnWithId(
                "queued-review", "run-stage", "brain-review",
                ThreadTurnStatus.QUEUED, external.runId());
        when(turnStore.findTurnIdByKickKey(
                ReviewRoundStateMachine.kickKey(external, "brain-review")))
                .thenReturn(Optional.of(queued.id()));
        when(turnStore.findTurnById(queued.id())).thenReturn(Optional.of(queued));

        service.reconcileStalledRounds();

        verify(roundMachine, never()).recordOwnedTurnEndedInCommand(
                anyString(), anyString(), anyString());
        verify(scheduler, never()).enqueueStageTurnOnce(
                anyString(), any(), anyString(), anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    void firstFailedBrainReviewTurnRetriesImmediately()
    {
        ReviewRound triaging = brainRound(ReviewRound.STATUS_TRIAGING);
        ReviewRound retry = withKickAttempt(triaging, triaging.kickAttempt() + 1);
        ThreadTurn failed = runTurn(
                "run-stage", "brain-review", ThreadTurnStatus.FAILED, triaging.runId());
        when(turnStore.findTurnById("failed-turn")).thenReturn(Optional.of(failed));
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(triaging));
        when(roundStore.findById(triaging.id())).thenReturn(Optional.of(retry));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task()));
        AgentRun run = new AgentRun(
                triaging.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND,
                AgentRun.SOURCE_LOCAL, null, null, "run-stage",
                AgentRun.STATUS_QUEUED, 0, null, null, null, NOW, null);
        when(agentRuns.findById(triaging.runId())).thenReturn(Optional.of(run));
        when(threadStore.findBrainThreadByTask(TASK_ID)).thenReturn(Optional.of(brainThread()));
        when(roundMachine.recordOwnedTurnEndedInCommand(
                TASK_ID, triaging.id(), failed.id()))
                .thenReturn(new ReviewRoundStateMachine.OwnedTurnEnded(
                        retry, failed, ReviewRoundStateMachine.OwnedTurnAction.RETRY));

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "failed-turn", true));

        verify(scheduler).enqueueStageTurnOnce(
                eq(ReviewRoundStateMachine.kickKey(retry, "brain-review")),
                any(), anyString(), eq(TASK_ID), eq("run-stage"),
                argThat(initiator -> "brain-review".equals(initiator.source())), eq(triaging.runId()), any());
        verify(prService).recordBrainReviewFailed(
                TASK_ID, "dev", triaging.iteration(), triaging.id(),
                "review_turn_failed", failed.id());
    }

    @Test
    void firstFailedBrainFixTurnRetriesImmediately()
    {
        ReviewRound addressing = brainRound(ReviewRound.STATUS_ADDRESSING).withIterationBumped();
        ReviewRound retry = withKickAttempt(addressing, addressing.kickAttempt() + 1);
        ThreadTurn failed = runTurn(
                "run-stage", "brain-review-fix", ThreadTurnStatus.FAILED, addressing.runId());
        when(turnStore.findTurnById("failed-turn")).thenReturn(Optional.of(failed));
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(addressing));
        when(roundStore.findById(addressing.id())).thenReturn(Optional.of(retry));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task()));
        AgentRun run = new AgentRun(
                addressing.runId(), TASK_ID, AgentRun.KIND_REVIEW_ROUND,
                AgentRun.SOURCE_LOCAL, null, null, "run-stage",
                AgentRun.STATUS_QUEUED, 0, null, null, null, NOW, null);
        when(agentRuns.findById(addressing.runId())).thenReturn(Optional.of(run));
        when(threadStore.findThreadById(task().threadId())).thenReturn(Optional.of(idleTaskThread()));
        when(roundMachine.recordOwnedTurnEndedInCommand(
                TASK_ID, addressing.id(), failed.id()))
                .thenReturn(new ReviewRoundStateMachine.OwnedTurnEnded(
                        retry, failed, ReviewRoundStateMachine.OwnedTurnAction.RETRY));

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "failed-turn", true));

        verify(scheduler).enqueueStageTurnOnce(
                eq(ReviewRoundStateMachine.kickKey(retry, "brain-review-fix")),
                any(), anyString(), eq(TASK_ID), eq("run-stage"),
                argThat(initiator -> "brain-review-fix".equals(initiator.source())), eq(addressing.runId()), any());
        verify(prService).recordBrainReviewFailed(
                TASK_ID, "dev", addressing.iteration(), addressing.id(),
                "review_turn_failed", failed.id());
    }

    @Test
    void failedBrainTurnEventDoesNotRestartADormantTask()
    {
        ReviewRound triaging = brainRound(ReviewRound.STATUS_TRIAGING);
        ThreadTurn cancelled = runTurn(
                "run-stage", "brain-review", ThreadTurnStatus.CANCELLED, triaging.runId());
        when(turnStore.findTurnById("failed-turn")).thenReturn(Optional.of(cancelled));
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(triaging));
        ownedTurnEnds(triaging, cancelled, ReviewRoundStateMachine.OwnedTurnAction.NONE);

        for (int attempt = 0; attempt < 4; attempt++) {
            service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "failed-turn", true));
        }

        verify(roundMachine, times(4)).recordOwnedTurnEndedInCommand(
                TASK_ID, triaging.id(), cancelled.id());
        verify(scheduler, never()).enqueueStageTurn(
                any(), anyString(), anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    void secondFailedBrainReviewTurnImmediatelyParksAndRecordsTheTimeline()
    {
        ReviewRound triaging = brainRound(ReviewRound.STATUS_TRIAGING);
        ThreadTurn secondFailure = runTurnWithId(
                "failed-turn-2", "run-stage", "brain-review",
                ThreadTurnStatus.FAILED, triaging.runId());
        when(turnStore.findTurnById(secondFailure.id())).thenReturn(Optional.of(secondFailure));
        when(roundStore.findLiveByTask(TASK_ID)).thenReturn(Optional.of(triaging));
        ownedTurnEnds(triaging, secondFailure, ReviewRoundStateMachine.OwnedTurnAction.PAUSED);

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, secondFailure.id(), true));

        verify(roundMachine).recordOwnedTurnEndedInCommand(
                TASK_ID, triaging.id(), secondFailure.id());
        verify(prService).recordBrainReviewFailed(
                TASK_ID, "dev", triaging.iteration(), triaging.id(),
                "review_turn_failed", secondFailure.id());
        verify(scheduler, never()).enqueueStageTurn(any(), anyString(), anyString(), anyString(), any(), anyString(), any());
    }

    @Test
    void duplicateReviewCompletionCannotAdvanceTheFollowingFixIteration()
    {
        ReviewRound triaging = brainRound(ReviewRound.STATUS_TRIAGING)
                .withBrainVerdict(ReviewRound.VERDICT_CHANGES_REQUESTED);
        ReviewRound addressing = triaging.withStatus(ReviewRound.STATUS_ADDRESSING);
        ThreadTurn reviewTurn = runTurnWithId(
                "review-turn", "run-stage", "brain-review",
                ThreadTurnStatus.COMPLETED, triaging.runId());
        when(turnStore.findTurnById("review-turn")).thenReturn(Optional.of(reviewTurn));
        when(roundStore.findLiveByTask(TASK_ID))
                .thenReturn(Optional.of(triaging), Optional.of(addressing));
        when(roundStore.findById(triaging.id())).thenReturn(Optional.of(triaging));
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task()));
        when(roundMachine.recordOwnedTurnEndedInCommand(
                TASK_ID, triaging.id(), reviewTurn.id()))
                .thenReturn(
                        new ReviewRoundStateMachine.OwnedTurnEnded(
                                triaging, reviewTurn,
                                ReviewRoundStateMachine.OwnedTurnAction.CONCLUDE),
                        new ReviewRoundStateMachine.OwnedTurnEnded(
                                addressing, reviewTurn,
                                ReviewRoundStateMachine.OwnedTurnAction.NONE));

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "review-turn", false));
        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "review-turn", false));

        verify(roundMachine, times(1)).concludeBrainInCommand(
                TASK_ID, triaging.id(), reviewTurn.id());
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
        payload.put("id", "plan-rev-1");
        payload.put("goal", "Fix the bug");
        payload.put("status", status);
        payload.set("signals", new ObjectMapper().createObjectNode()
                .put("riskLevel", riskLevel)
                .put("estimatedComplexity", estimatedComplexity));
        return new StageEvent(UUID.randomUUID(), PLAN_STAGE_ID, TASK_ID, StageEventType.PLAN_RECORDED, NOW,
                payload.toString());
    }

    private static StageEvent planReviewedEvent(String verdict, String reviewedRevisionId)
    {
        ObjectNode payload = new ObjectMapper().createObjectNode();
        payload.put("verdict", verdict);
        if (reviewedRevisionId != null) {
            payload.put("reviewedRevisionId", reviewedRevisionId);
        }
        return new StageEvent(
                UUID.randomUUID(), PLAN_STAGE_ID, TASK_ID, StageEventType.PLAN_SELF_REVIEWED,
                NOW.plusSeconds(1), payload.toString());
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

    private static ThreadTurn turn(String stageId, TurnInitiator initiator)
    {
        return new ThreadTurn(
                "turn-x", "thread-1", TASK_ID, ThreadResourceLane.CLI,
                ThreadTurnStatus.COMPLETED, "prompt", NOW, NOW, NOW, NOW,
                null, initiator, stageId, ThreadScope.STAGE);
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

    private static ReviewRound round(ReviewRoundState status)
    {
        boolean gated = ReviewRound.STATUS_AWAITING_GATE.equals(status) || ReviewRound.STATUS_POSTED.equals(status);
        return new ReviewRound(
                UUID.randomUUID().toString(), TASK_ID, 1, List.of(), status,
                ReviewRound.ReviewRoundStats.empty(), "run-stage-run", NOW.minusSeconds(60),
                gated ? NOW : null, null,
                ReviewRound.ORIGIN_EXTERNAL, null, 0, ReviewRound.DEFAULT_BRAIN_BUDGET);
    }

    private static ReviewRound brainRound(ReviewRoundState status)
    {
        return new ReviewRound(
                UUID.randomUUID().toString(), TASK_ID, 1, List.of(), status,
                ReviewRound.ReviewRoundStats.empty(), "run-id-1", NOW.minusSeconds(60),
                null, null,
                ReviewRound.ORIGIN_BRAIN, null, 0, ReviewRound.DEFAULT_BRAIN_BUDGET);
    }

    private static ReviewRound pausedFrom(ReviewRound round, ReviewRoundState pausedFrom)
    {
        return new ReviewRound(
                round.id(), round.taskId(), round.idx(), round.reviewers(),
                ReviewRound.STATUS_PAUSED, round.stats(), round.runId(), round.openedAt(),
                round.gatedAt(), round.postedAt(), round.origin(), round.brainVerdict(),
                round.iteration(), round.budget(), pausedFrom, round.codeFingerprint(),
                round.enqueueFailures(), round.kickAttempt(), round.gateRevision(),
                round.activeGateToken(), round.closedAt());
    }

    private static ReviewRound withKickAttempt(ReviewRound round, int kickAttempt)
    {
        return new ReviewRound(
                round.id(), round.taskId(), round.idx(), round.reviewers(), round.status(),
                round.stats(), round.runId(), round.openedAt(), round.gatedAt(),
                round.postedAt(), round.origin(), round.brainVerdict(), round.iteration(),
                round.budget(), round.pausedFrom(), round.codeFingerprint(),
                round.enqueueFailures(), kickAttempt, round.gateRevision(),
                round.activeGateToken(), round.closedAt());
    }

    private void ownedTurnEnds(
            ReviewRound round,
            ThreadTurn turn,
            ReviewRoundStateMachine.OwnedTurnAction action)
    {
        when(roundMachine.recordOwnedTurnEndedInCommand(
                TASK_ID, round.id(), turn.id()))
                .thenReturn(new ReviewRoundStateMachine.OwnedTurnEnded(
                        round, turn, action));
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

    private static final class TestTransactionManager
            extends AbstractPlatformTransactionManager
    {
        @Override
        protected Object doGetTransaction()
        {
            return new Object();
        }

        @Override
        protected void doBegin(Object transaction, TransactionDefinition definition) {}

        @Override
        protected void doCommit(DefaultTransactionStatus status) {}

        @Override
        protected void doRollback(DefaultTransactionStatus status) {}
    }
}
