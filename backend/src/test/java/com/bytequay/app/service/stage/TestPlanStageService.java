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
package com.bytequay.app.service.stage;

import com.bytequay.app.domain.Actor;
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
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ThreadTurnStatus;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.repository.ValidationPassStore;
import com.bytequay.app.service.brain.BrainServiceImpl;
import com.bytequay.app.service.threads.AgentScheduler;
import com.bytequay.app.service.threads.PlanKickoffRequested;
import com.bytequay.app.service.threads.TaskTurnFinishedEvent;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The PlanStage REST surface: approve (with its validations), replan, and the
 * follow-up note status flip.
 */
@SpringBootTest
class TestPlanStageService
{
    @Autowired
    private PlanStageService planStageService;
    @Autowired
    private StageStore stageStore;
    @Autowired
    private TaskStore taskStore;
    @Autowired
    private ThreadStore threadStore;
    @Autowired
    private ThreadTurnStore turnStore;
    @Autowired
    private ValidationPassStore validationStore;
    @Autowired
    private BrainServiceImpl brainService;
    /** Mocked so the approval / replan kickoff enqueues a turn without the
     *  real scheduler dispatching an agent on a background thread (which would
     *  race this test's own SQLite writes). */
    @MockitoBean
    private AgentScheduler scheduler;

    @Test
    void approveFinalizedPlanClosesItAndOpensDevelopment()
    {
        String taskId = seedTask();
        StageInstance plan = stageStore.openStage(taskId, StageType.PLAN_STAGE, null);
        recordPlan(plan, taskId, "finalized", "rev-1");

        PlanStageService.ApproveResult result = planStageService.approveByStage(plan.id());

        assertThat(result.devStageId()).isNotBlank();
        assertThat(result.redirectUrl()).contains(result.devStageId());
        StageInstance dev = stageStore.findActiveStage(taskId).orElseThrow();
        assertThat(dev.type()).isEqualTo(StageType.DEVELOPMENT_STAGE);
        assertThat(stageStore.findStageById(plan.id()).orElseThrow().state())
                .isEqualTo(StageState.CLOSED);
        // PLAN_APPROVED carries the approved revision id.
        assertThat(stageStore.findEventsByStage(plan.id()))
                .anySatisfy(e -> {
                    assertThat(e.eventType()).isEqualTo(StageEventType.PLAN_APPROVED);
                    assertThat(e.payloadJson()).contains("rev-1");
                });
        assertThat(taskStore.findTaskById(taskId).orElseThrow().phase())
                .isEqualTo(TaskPhase.IMPLEMENTING);
    }

    @Test
    void devKickoffIsDurablyEnqueuedInsideTheApprovalCommand()
    {
        String taskId = seedTask();
        StageInstance plan = stageStore.openStage(taskId, StageType.PLAN_STAGE, null);
        recordPlan(plan, taskId, "finalized", "rev-1");
        AtomicBoolean kickedOff = new AtomicBoolean();
        doAnswer(ignored -> {
            assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            assertThat(taskStore.findTaskById(taskId).orElseThrow().phase())
                    .isEqualTo(TaskPhase.IMPLEMENTING);
            assertThat(stageStore.findStageById(plan.id()).orElseThrow().state())
                    .isEqualTo(StageState.CLOSED);
            kickedOff.set(true);
            return "turn-1";
        }).when(scheduler).enqueueTaskTurnOnce(
                any(), any(), any(), eq(taskId), any(), any(), any(), any());

        planStageService.approveByStage(plan.id());

        assertThat(kickedOff).isTrue();
    }

    @Test
    void failedPhaseTransitionRollsBackTheWholeApproval()
    {
        String taskId = seedTask();
        StageInstance plan = stageStore.openStage(taskId, StageType.PLAN_STAGE, null);
        recordPlan(plan, taskId, "finalized", "rev-1");
        taskStore.updatePhase(taskId, TaskPhase.COMPLETED);

        assertThatThrownBy(() -> planStageService.approveByStage(plan.id()))
                .isInstanceOf(ResponseStatusException.class);

        assertThat(stageStore.findStageById(plan.id()).orElseThrow().state())
                .isEqualTo(StageState.OPEN);
        assertThat(stageStore.findEventsByStage(plan.id()))
                .noneMatch(event -> event.eventType() == StageEventType.PLAN_APPROVED);
        verify(scheduler, never()).enqueueTaskTurn(any(), any(), any(), any());
    }

    @Test
    void automationApprovalIsAuditedAsSchedulerPolicy()
    {
        String taskId = seedTask(
                Task.TYPE_WORKSPACE_ISSUE_TRIAGE, 17, Task.ORIGIN_ISSUE_MONITOR);
        StageInstance plan = stageStore.openStage(taskId, StageType.PLAN_STAGE, null);
        JsonNode expected = recordPlan(plan, taskId, "finalized", "rev-auto");

        planStageService.approveByAutomation(plan.id(), expected);

        assertThat(stageStore.findEventsByStage(plan.id()))
                .filteredOn(event -> event.eventType() == StageEventType.PLAN_APPROVED)
                .singleElement()
                .satisfies(event -> assertThat(event.payloadJson())
                        .contains("workspace-issue-intake"));
        assertThat(taskStore.listPhaseEvents(taskId))
                .filteredOn(event -> event.toPhase() == TaskPhase.IMPLEMENTING)
                .singleElement()
                .satisfies(event -> assertThat(event.actor()).isEqualTo(Actor.SCHEDULER));
    }

    @Test
    void automationApprovalRejectsWrongProvenanceAndChangedRevision()
    {
        String manualTask = seedTask();
        StageInstance manualPlan = stageStore.openStage(
                manualTask, StageType.PLAN_STAGE, null);
        JsonNode safe = recordPlan(manualPlan, manualTask, "finalized", "rev-manual");
        assertThatThrownBy(() -> planStageService.approveByAutomation(manualPlan.id(), safe))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("restricted to issue-intake");

        String intakeTask = seedTask(
                Task.TYPE_WORKSPACE_ISSUE_TRIAGE, 18, Task.ORIGIN_ISSUE_MONITOR);
        StageInstance intakePlan = stageStore.openStage(
                intakeTask, StageType.PLAN_STAGE, null);
        JsonNode recorded = recordPlan(intakePlan, intakeTask, "finalized", "rev-intake");
        JsonNode stale = recorded.deepCopy();
        ((ObjectNode) stale).put("goal", "stale revision");
        assertThatThrownBy(() -> planStageService.approveByAutomation(intakePlan.id(), stale))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("plan changed");
    }

    @Test
    void approveRejectsWhenNoPlanRecorded()
    {
        String taskId = seedTask();
        StageInstance plan = stageStore.openStage(taskId, StageType.PLAN_STAGE, null);

        assertThatThrownBy(() -> planStageService.approveByStage(plan.id()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("no plan");
    }

    @Test
    void approveRejectsWhenLatestPlanNotFinalized()
    {
        String taskId = seedTask();
        StageInstance plan = stageStore.openStage(taskId, StageType.PLAN_STAGE, null);
        recordPlan(plan, taskId, "suggested", "rev-1");

        assertThatThrownBy(() -> planStageService.approveByStage(plan.id()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not finalized");
    }

    @Test
    void approveRejectsUntilTheLatestPlanCompletesMandatorySelfReview()
    {
        String taskId = seedTask();
        StageInstance plan = stageStore.openStage(taskId, StageType.PLAN_STAGE, null);
        recordUnreviewedPlan(plan, taskId, "finalized", "rev-1");

        assertThatThrownBy(() -> planStageService.approveByStage(plan.id()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("self-review");
    }

    @Test
    void approveRejectsAClosedStage()
    {
        String taskId = seedTask();
        StageInstance plan = stageStore.openStage(taskId, StageType.PLAN_STAGE, null);
        recordPlan(plan, taskId, "finalized", "rev-1");
        stageStore.closeStage(plan.id(), "done");

        assertThatThrownBy(() -> planStageService.approveByStage(plan.id()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("already closed");
    }

    @Test
    void replanReopensAPlanStageAfterApproval()
    {
        String taskId = seedTask();
        StageInstance plan = stageStore.openStage(taskId, StageType.PLAN_STAGE, null);
        recordPlan(plan, taskId, "finalized", "rev-1");
        planStageService.approveByStage(plan.id());

        PlanStageService.ReplanResult result = planStageService.replan(taskId);
        if (result.preparing()) {
            result = planStageService.completeRequestedReplan(taskId);
        }

        assertThat(result.planStageId()).isNotBlank();
        StageInstance reopened = stageStore.findActiveStage(taskId).orElseThrow();
        assertThat(reopened.type()).isEqualTo(StageType.PLAN_STAGE);
        assertThat(reopened.id().toString()).isEqualTo(result.planStageId());
        assertThat(reopened.id()).isNotEqualTo(plan.id());
    }

    @Test
    void replanWaitsForLiveValidationBeforeOpeningPlanning()
    {
        String taskId = seedTask();
        StageInstance plan = stageStore.openStage(taskId, StageType.PLAN_STAGE, null);
        recordPlan(plan, taskId, "finalized", "rev-1");
        planStageService.approveByStage(plan.id());
        String claimKey = "replan:" + UUID.randomUUID();
        validationStore.insertClaim(
                claimKey, taskId, "dev-round", null, "fp-1", null, null, Instant.now());
        validationStore.acquireOwner(
                claimKey, "owner-1", "executor-1",
                Instant.now().plusSeconds(120), Instant.now());

        PlanStageService.ReplanResult pending = planStageService.replan(taskId);

        assertThat(pending.preparing()).isTrue();
        assertThat(pending.planStageId()).isNull();
        assertThat(taskStore.findTaskById(taskId).orElseThrow().phase())
                .isEqualTo(TaskPhase.NEEDS_ATTENTION);
        assertThat(validationStore.findByClaimKey(claimKey).orElseThrow().cancelRequestedAt())
                .isNotNull();

        validationStore.markSuperseded(claimKey, Instant.now());
        PlanStageService.ReplanResult completed = planStageService.completeRequestedReplan(taskId);

        assertThat(completed.preparing()).isFalse();
        assertThat(completed.planStageId()).isNotBlank();
        assertThat(completed.planStageId()).isNotEqualTo(plan.id().toString());
        assertThat(taskStore.findTaskById(taskId).orElseThrow().phase())
                .isEqualTo(TaskPhase.PLANNING);
    }

    @Test
    void replanRejectsWhenNoApprovedPlanYet()
    {
        String taskId = seedTask();
        stageStore.openStage(taskId, StageType.PLAN_STAGE, null);

        // A PlanStage is open but none has been approved/closed.
        assertThatThrownBy(() -> planStageService.replan(taskId))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void resolveFollowupFlipsTheNoteStatus()
    {
        String taskId = seedTask();
        StageInstance plan = stageStore.openStage(taskId, StageType.PLAN_STAGE, null);
        Map<String, Object> note = new LinkedHashMap<>();
        note.put("note", "the retry default looks wrong");
        note.put("sourceStageId", "dev-1");
        StageEvent followup = stageStore.recordEvent(
                plan.id(), taskId, StageEventType.PLAN_FOLLOWUP_NOTED, note);

        planStageService.resolveFollowup(followup.id(), "addressed");

        assertThat(stageStore.findEventById(followup.id()).orElseThrow().payloadJson())
                .contains("\"status\":\"addressed\"");
    }

    @Test
    void resolveFollowupRejectsBadStatusAndUnknownEvent()
    {
        String taskId = seedTask();
        StageInstance plan = stageStore.openStage(taskId, StageType.PLAN_STAGE, null);
        StageEvent followup = stageStore.recordEvent(
                plan.id(), taskId, StageEventType.PLAN_FOLLOWUP_NOTED, Map.of("note", "x"));

        assertThatThrownBy(() -> planStageService.resolveFollowup(followup.id(), "nonsense"))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> planStageService.resolveFollowup(UUID.randomUUID(), "addressed"))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void planKickoffTurnFinishingWithoutAPlanNudgesTheBrain()
    {
        String taskId = seedTask();
        // Create the brain thread (the kickoff enqueue is mocked away).
        brainService.onPlanKickoff(new PlanKickoffRequested(taskId, "do the thing", null));
        stageStore.openStage(taskId, StageType.PLAN_STAGE, null);
        String brainId = threadStore.findBrainThreadByTask(taskId).orElseThrow().id();
        String turnId = saveKickoffTurn(taskId, brainId, "plan-kickoff");

        planStageService.onTurnFinished(new TaskTurnFinishedEvent(taskId, turnId, false));

        verify(scheduler).enqueueTurn(any(), contains("ended without recording a plan"), any());
    }

    @Test
    void planKickoffTurnFinishingWithARecordedPlanDoesNotNudge()
    {
        String taskId = seedTask();
        StageInstance plan = stageStore.openStage(taskId, StageType.PLAN_STAGE, null);
        recordPlan(plan, taskId, "finalized", "rev-1");
        String devThread = taskStore.findTaskById(taskId).orElseThrow().threadId();
        String turnId = saveKickoffTurn(taskId, devThread, "plan-kickoff");

        planStageService.onTurnFinished(new TaskTurnFinishedEvent(taskId, turnId, false));

        verify(scheduler, never()).enqueueTurn(any(), contains("ended without recording"), any());
    }

    @Test
    void aNonKickoffTurnFinishingNeverNudges()
    {
        String taskId = seedTask();
        stageStore.openStage(taskId, StageType.PLAN_STAGE, null);
        String devThread = taskStore.findTaskById(taskId).orElseThrow().threadId();
        String turnId = saveKickoffTurn(taskId, devThread, "monitor");

        planStageService.onTurnFinished(new TaskTurnFinishedEvent(taskId, turnId, false));

        verify(scheduler, never()).enqueueTurn(any(), eq("plan-followup"), any());
        verify(scheduler, never()).enqueueTurn(any(), contains("ended without recording"), any());
    }

    @Test
    void devKickoffTurnFinishingNeverCreatesALegacyShipGate()
    {
        String taskId = seedTask();
        StageInstance plan = stageStore.openStage(taskId, StageType.PLAN_STAGE, null);
        recordPlan(plan, taskId, "finalized", "rev-1");
        // Approve → task IMPLEMENTING, DevelopmentStage open, dev kickoff enqueued.
        planStageService.approveByStage(plan.id());
        String devThread = taskStore.findTaskById(taskId).orElseThrow().threadId();
        String turnId = saveKickoffTurn(taskId, devThread, "plan-approved");

        planStageService.onTurnFinished(new TaskTurnFinishedEvent(taskId, turnId, false));

        verify(scheduler, never()).enqueueTaskTurn(any(), contains("ship_task"), any(), any());
    }

    @Test
    void devKickoffRendersStringFormSteps()
    {
        String taskId = seedTask();
        StageInstance plan = stageStore.openStage(taskId, StageType.PLAN_STAGE, null);
        // Steps as plain strings, not {ordinal, action} objects — the plan card
        // renders these, so the dev kickoff must too, not blank "0." lines.
        Map<String, Object> intent = new LinkedHashMap<>();
        intent.put("summary", "land it");
        intent.put("steps", List.of("Add the migration and stores", "Wire the reader"));
        intent.put("pushStrategy", "await_approval");
        stageStore.recordEvent(plan.id(), taskId, StageEventType.PLAN_RECORDED, Map.of(
                "id", "rev-1", "status", "finalized", "intent", intent,
                "signals", Map.of("riskLevel", "low", "estimatedComplexity", "small")));
        stageStore.recordEvent(
                plan.id(), taskId, StageEventType.PLAN_SELF_REVIEWED,
                Map.of("verdict", "approved"));

        planStageService.approveByStage(plan.id());

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(scheduler).enqueueTaskTurnOnce(
                any(), any(), prompt.capture(), any(), any(), any(), any(), any());
        assertThat(prompt.getValue())
                .contains("1. Add the migration and stores")
                .contains("2. Wire the reader")
                .doesNotContain("0. ");
    }

    @Test
    void automatedDevKickoffAlsoNeverCreatesALegacyShipGate()
    {
        String taskId = seedTask();
        StageInstance plan = stageStore.openStage(taskId, StageType.PLAN_STAGE, null);
        recordPlan(plan, taskId, "finalized", "rev-automation-kickoff");
        planStageService.approveByStage(plan.id());
        String devThread = taskStore.findTaskById(taskId).orElseThrow().threadId();
        String turnId = saveKickoffTurn(taskId, devThread, "automation-plan-approved");

        planStageService.onTurnFinished(new TaskTurnFinishedEvent(taskId, turnId, false));

        verify(scheduler, never()).enqueueTaskTurn(any(), contains("ship_task"), any(), any());
    }

    @Test
    void aFailedDevKickoffTurnDoesNotNudgeToShip()
    {
        String taskId = seedTask();
        StageInstance plan = stageStore.openStage(taskId, StageType.PLAN_STAGE, null);
        recordPlan(plan, taskId, "finalized", "rev-1");
        planStageService.approveByStage(plan.id());
        String devThread = taskStore.findTaskById(taskId).orElseThrow().threadId();
        String turnId = saveKickoffTurn(taskId, devThread, "plan-approved", "claude-code exited 1");

        planStageService.onTurnFinished(new TaskTurnFinishedEvent(taskId, turnId, true));

        verify(scheduler, never()).enqueueTaskTurn(any(), contains("ship_task"), any(), any());
    }

    @Test
    void theShipNudgeTurnItselfDoesNotReNudge()
    {
        String taskId = seedTask();
        StageInstance plan = stageStore.openStage(taskId, StageType.PLAN_STAGE, null);
        recordPlan(plan, taskId, "finalized", "rev-1");
        planStageService.approveByStage(plan.id());
        String devThread = taskStore.findTaskById(taskId).orElseThrow().threadId();
        // A turn that is itself the ship nudge must not chain another nudge.
        String turnId = saveKickoffTurn(taskId, devThread, "ship-nudge");

        planStageService.onTurnFinished(new TaskTurnFinishedEvent(taskId, turnId, false));

        verify(scheduler, never()).enqueueTaskTurn(any(), contains("ship_task"), any(), any());
    }

    @Test
    void aFailedPlanningTurnSurfacesTheErrorOnThePlanStage()
    {
        String taskId = seedTask();
        StageInstance plan = stageStore.openStage(taskId, StageType.PLAN_STAGE, null);
        String devThread = taskStore.findTaskById(taskId).orElseThrow().threadId();
        String turnId = saveKickoffTurn(taskId, devThread, "plan-kickoff",
                "claude-code exited with code 1");

        planStageService.onTurnFinished(new TaskTurnFinishedEvent(taskId, turnId, true));

        StageEvent failed = stageStore.findEventsByStage(plan.id()).stream()
                .filter(e -> e.eventType() == StageEventType.PLAN_FAILED)
                .findFirst().orElseThrow();
        assertThat(failed.payloadJson()).contains("claude-code exited with code 1");
        // A failure must not also enqueue a nudge turn.
        verify(scheduler, never()).enqueueTurn(any(), contains("record_plan"), any());
    }

    private String saveKickoffTurn(String taskId, String threadId, String source)
    {
        return saveKickoffTurn(taskId, threadId, source, null);
    }

    private String saveKickoffTurn(String taskId, String threadId, String source, String errorMessage)
    {
        String turnId = UUID.randomUUID().toString();
        Instant now = Instant.parse("2026-06-20T09:30:00Z");
        turnStore.saveTurn(new ThreadTurn(
                turnId, threadId, taskId, ThreadResourceLane.CLI, ThreadTurnStatus.QUEUED,
                "plan", now, now, now, now, errorMessage, TurnInitiator.unattended(source)));
        return turnId;
    }

    private JsonNode recordPlan(StageInstance plan, String taskId, String status, String revId)
    {
        JsonNode recorded = recordUnreviewedPlan(plan, taskId, status, revId);
        stageStore.recordEvent(
                plan.id(), taskId, StageEventType.PLAN_SELF_REVIEWED,
                Map.of("verdict", "approved"));
        return recorded;
    }

    private JsonNode recordUnreviewedPlan(
            StageInstance plan, String taskId, String status, String revId)
    {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", revId);
        payload.put("status", status);
        Map<String, Object> intent = new LinkedHashMap<>();
        intent.put("summary", "bump the retry default");
        intent.put("steps", List.of(Map.of("ordinal", 1, "action", "Change it")));
        intent.put("pushStrategy", "await_approval");
        payload.put("intent", intent);
        payload.put("signals", Map.of(
                "confidence", "high",
                "riskLevel", "low",
                "estimatedComplexity", "small"));
        stageStore.recordEvent(plan.id(), taskId, StageEventType.PLAN_RECORDED, payload);
        return new ObjectMapper().valueToTree(payload);
    }

    private String seedTask()
    {
        return seedTask("DEVELOP", null, Task.ORIGIN_USER);
    }

    private String seedTask(String taskType, Integer issueNumber, String origin)
    {
        Instant now = Instant.parse("2026-06-20T09:00:00Z");
        Thread thread = new Thread(
                UUID.randomUUID().toString(), ThreadKind.CLI_AGENT, "claude-code",
                null, "Plan stage test", ThreadStatus.RUNNING, "claude-sonnet-4-6",
                0L, 0L, 0L, now, now, null, null, ThreadFlow.BUILD, "ws-default", null, null);
        threadStore.saveThread(thread);
        String taskId = UUID.randomUUID().toString();
        taskStore.saveTask(new Task(
                taskId, thread.id(), 1L, TaskStatus.RUNNING, "feature", null, "main", "/tmp",
                null, null, null, null, null, taskType, null, issueNumber,
                0L, 0L, 0L, null, now, null, null, null, null, null, origin));
        return taskId;
    }
}
