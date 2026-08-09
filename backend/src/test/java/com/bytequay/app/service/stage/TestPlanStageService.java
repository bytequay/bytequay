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
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.service.threads.TaskTurnFinishedEvent;
import com.bytequay.app.service.threads.ThreadTurnScheduler;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
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
    /** Mocked so the approval / replan kickoff enqueues a turn without the
     *  real scheduler dispatching an agent on a background thread (which would
     *  race this test's own SQLite writes). */
    @MockitoBean
    private ThreadTurnScheduler scheduler;

    @Test
    void legacyPlanApprovalFailsClosedAndRollsBackTheWholeCommand()
    {
        String taskId = seedTask();
        StageInstance plan = stageStore.openStage(taskId, StageType.PLAN_STAGE, null);
        recordPlan(plan, taskId, "finalized", "rev-1");

        assertThatThrownBy(() -> planStageService.approveByStage(plan.id()))
                .isInstanceOf(UnsupportedOperationException.class)
                .hasMessageContaining("TaskPhaseMachine is retired");

        assertThat(stageStore.findStageById(plan.id()).orElseThrow().state())
                .isEqualTo(StageState.OPEN);
        assertThat(stageStore.findEventsByStage(plan.id()))
                .noneMatch(event -> event.eventType() == StageEventType.PLAN_APPROVED);
        assertThat(taskStore.findTaskById(taskId).orElseThrow().phase())
                .isEqualTo(TaskPhase.PLANNING);
        verify(scheduler, never()).enqueueStageTurnOnce(
                any(), any(), any(), any(), any(), any(), any(), any());
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
    void approveRejectsAChangesRequestedOrStaleSelfReview()
    {
        String changesTask = seedTask();
        StageInstance changesPlan = stageStore.openStage(
                changesTask, StageType.PLAN_STAGE, null);
        recordUnreviewedPlan(changesPlan, changesTask, "finalized", "rev-changes");
        stageStore.recordEvent(
                changesPlan.id(), changesTask, StageEventType.PLAN_SELF_REVIEWED,
                Map.of("verdict", "changes_requested", "reviewedRevisionId", "rev-changes"));

        assertThatThrownBy(() -> planStageService.approveByStage(changesPlan.id()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("has not approved the latest revision");

        String staleTask = seedTask();
        StageInstance stalePlan = stageStore.openStage(staleTask, StageType.PLAN_STAGE, null);
        recordUnreviewedPlan(stalePlan, staleTask, "finalized", "rev-current");
        stageStore.recordEvent(
                stalePlan.id(), staleTask, StageEventType.PLAN_SELF_REVIEWED,
                Map.of("verdict", "approved", "reviewedRevisionId", "rev-old"));

        assertThatThrownBy(() -> planStageService.approveByStage(stalePlan.id()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("has not approved the latest revision");
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
        stageStore.openStage(taskId, StageType.PLAN_STAGE, null);
        String brainId = seedBrainThread(taskId);
        String turnId = saveKickoffTurn(taskId, brainId, "plan-kickoff");

        planStageService.onTurnFinished(new TaskTurnFinishedEvent(taskId, turnId, false));

        verify(scheduler).enqueueStageTurn(
                any(), contains("ended without recording a plan"), eq(taskId), any(), any(), any(), any());
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

        verify(scheduler, never()).enqueueStageTurn(
                any(), contains("ended without recording"), any(), any(), any(), any(), any());
    }

    @Test
    void aNonKickoffTurnFinishingNeverNudges()
    {
        String taskId = seedTask();
        stageStore.openStage(taskId, StageType.PLAN_STAGE, null);
        String devThread = taskStore.findTaskById(taskId).orElseThrow().threadId();
        String turnId = saveKickoffTurn(taskId, devThread, "monitor");

        planStageService.onTurnFinished(new TaskTurnFinishedEvent(taskId, turnId, false));

        verify(scheduler, never()).enqueueStageTurn(
                any(), eq("plan-followup"), any(), any(), any(), any(), any());
        verify(scheduler, never()).enqueueStageTurn(
                any(), contains("ended without recording"), any(), any(), any(), any(), any());
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
        verify(scheduler, never()).enqueueStageTurn(
                any(), contains("record_plan"), any(), any(), any(), any(), any());
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
                "plan", now, now, now, now, errorMessage, TurnInitiator.unattended(source),
                null, ThreadScope.TASK));
        return turnId;
    }

    private JsonNode recordPlan(StageInstance plan, String taskId, String status, String revId)
    {
        JsonNode recorded = recordUnreviewedPlan(plan, taskId, status, revId);
        stageStore.recordEvent(
                plan.id(), taskId, StageEventType.PLAN_SELF_REVIEWED,
                Map.of("verdict", "approved", "reviewedRevisionId", revId));
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

    private String seedBrainThread(String taskId)
    {
        Instant now = Instant.parse("2026-06-20T09:15:00Z");
        Thread brain = new Thread(
                UUID.randomUUID().toString(), ThreadKind.BRAIN_AGENT, "claude-code",
                null, "Brain · " + taskId, ThreadStatus.IDLE, "claude-sonnet-4-6",
                0L, 0L, 0L, now, now, null, null, ThreadFlow.BUILD, "ws-default",
                null, null, 1, taskId);
        threadStore.saveThread(brain);
        return brain.id();
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
