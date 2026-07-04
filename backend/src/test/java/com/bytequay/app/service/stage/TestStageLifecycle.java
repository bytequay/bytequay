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
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.threads.PlanKickoffRequested;
import com.bytequay.app.service.threads.TaskCreatedEvent;
import com.bytequay.app.service.threads.TaskPhaseMachine;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Drives a Task through its phase walk against the real
 * {@link TaskPhaseMachine} and asserts {@link StageLifecycle} keeps the
 * stage timeline in step: a PlanStage open at creation, the DevelopmentStage
 * opening only once the plan is approved, a CiFixingStage opening at the
 * first push, and a CleanupStage opening at COMPLETED — each boundary
 * closing the prior stage.
 */
@SpringBootTest
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class TestStageLifecycle
{
    @Autowired
    private TaskPhaseMachine machine;
    @Autowired
    private PlanStageService planStageService;
    @Autowired
    private StageStore stageStore;
    @Autowired
    private TaskStore taskStore;
    @Autowired
    private ThreadStore threadStore;
    @Autowired
    private ApplicationEventPublisher events;
    @Autowired
    private ObjectMapper mapper;

    @Test
    void taskCreatedEventOpensPlanStageOnce()
    {
        String taskId = seedTask();
        assertThat(stageStore.findActiveStage(taskId)).isEmpty();

        events.publishEvent(new TaskCreatedEvent(taskId));
        assertActive(taskId, StageType.PLAN_STAGE);

        // Idempotent — a re-fired creation event adds no second stage.
        events.publishEvent(new TaskCreatedEvent(taskId));
        assertThat(stagesOfType(taskId, StageType.PLAN_STAGE)).hasSize(1);
        assertThat(stageStore.findStagesByTask(taskId)).hasSize(1);
    }

    @Test
    void developmentStageCannotOpenWithoutAnApprovedPlan()
    {
        String taskId = seedTask();
        events.publishEvent(new TaskCreatedEvent(taskId));
        assertActive(taskId, StageType.PLAN_STAGE);

        // Entering IMPLEMENTING would open the DevelopmentStage; with no
        // PLAN_APPROVED event the guard rejects it (and rolls the move back).
        assertThatThrownBy(() ->
                machine.transition(taskId, TaskPhase.IMPLEMENTING, "skip_planning", Actor.AGENT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("DevelopmentStage cannot open without an approved PlanStage");
        assertActive(taskId, StageType.PLAN_STAGE);
        assertThat(taskStore.findTaskById(taskId).orElseThrow().phase()).isEqualTo(TaskPhase.PLANNING);
    }

    @Test
    void approvingThePlanClosesItAndOpensTheDevelopmentStage()
    {
        String taskId = seedTask();
        events.publishEvent(new TaskCreatedEvent(taskId));

        planStageService.approve(taskId, "rev-1");

        assertActive(taskId, StageType.DEVELOPMENT_STAGE);
        assertThat(only(taskId, StageType.PLAN_STAGE).state()).isEqualTo(StageState.CLOSED);
        assertThat(taskStore.findTaskById(taskId).orElseThrow().phase())
                .isEqualTo(TaskPhase.IMPLEMENTING);
    }

    @Test
    void trunkPlanSeedsAPlanRecordedEventWithSourceTrunk()
    {
        String taskId = seedTask();
        events.publishEvent(new TaskCreatedEvent(taskId));

        ObjectNode trunk = mapper.createObjectNode();
        trunk.put("status", "suggested");
        trunk.put("source", "overwritten-to-trunk");
        // Call the seed listener directly so we don't also fire the brain's
        // planning turn (that path is covered by the brain-service test).
        planStageService.onPlanKickoff(new PlanKickoffRequested(taskId, "fix it", trunk));

        StageInstance plan = only(taskId, StageType.PLAN_STAGE);
        List<StageEvent> recorded = stageStore.findEventsByStage(plan.id()).stream()
                .filter(e -> e.eventType() == StageEventType.PLAN_RECORDED).toList();
        assertThat(recorded).hasSize(1);
        assertThat(recorded.get(0).payloadJson())
                .contains("\"source\":\"trunk\"")
                .contains("\"status\":\"suggested\"");
    }

    @Test
    void phaseWalkOpensAndClosesStagesAtTheirBoundaries()
    {
        String taskId = seedTask();
        // A brand-new task plans first; approval opens the DevelopmentStage.
        events.publishEvent(new TaskCreatedEvent(taskId));
        planStageService.approve(taskId, "rev-1");
        assertActive(taskId, StageType.DEVELOPMENT_STAGE);

        // The dev phases that follow keep the DevelopmentStage open.
        machine.transition(taskId, TaskPhase.VALIDATING, "ready_for_checks", Actor.AGENT);
        assertActive(taskId, StageType.DEVELOPMENT_STAGE);

        machine.transition(taskId, TaskPhase.INTERNAL_REVIEW, "validation_passed", Actor.AGENT);
        assertActive(taskId, StageType.DEVELOPMENT_STAGE);

        machine.transition(taskId, TaskPhase.AWAITING_PUSH, "approved", Actor.HUMAN);
        assertActive(taskId, StageType.DEVELOPMENT_STAGE);
        assertThat(stagesOfType(taskId, StageType.DEVELOPMENT_STAGE)).hasSize(1);

        // First push is an unmapped idle wait — DevelopmentStage stays
        // active. A red check no longer opens a CiFixing stage via the
        // phase transition; a ci_fix AgentRun opens its own backing stage
        // directly instead (see AgentRunService), bypassing this lifecycle.
        machine.transition(taskId, TaskPhase.PUSHED_AWAITING_CI, "human_push", Actor.HUMAN);
        assertActive(taskId, StageType.DEVELOPMENT_STAGE);

        // PR merges: DevelopmentStage closes, and the terminal Cleanup stage
        // opens then immediately closes (it's a marker, not live work) — so a
        // finished task leaves nothing "running".
        machine.transition(taskId, TaskPhase.COMPLETED, "merged", Actor.HUMAN);
        assertThat(stageStore.findActiveStage(taskId)).isEmpty();
        assertThat(only(taskId, StageType.DEVELOPMENT_STAGE).state()).isEqualTo(StageState.CLOSED);
        assertThat(only(taskId, StageType.CLEANUP_STAGE).state()).isEqualTo(StageState.CLOSED);

        List<StageInstance> stages = stageStore.findStagesByTask(taskId);
        assertThat(stages).extracting(StageInstance::type).containsExactlyInAnyOrder(
                StageType.PLAN_STAGE, StageType.DEVELOPMENT_STAGE, StageType.CLEANUP_STAGE);
        // Every stage closed — a finished task has nothing running.
        assertThat(stages).filteredOn(s -> s.state() == StageState.CLOSED).hasSize(3);
    }

    @Test
    void reviewMonitorStageArmsAndStaysAcrossOverlapPhases()
    {
        String taskId = seedTask();
        events.publishEvent(new TaskCreatedEvent(taskId));
        planStageService.approve(taskId, "rev-1");
        machine.transition(taskId, TaskPhase.VALIDATING, "ready", Actor.AGENT);
        machine.transition(taskId, TaskPhase.INTERNAL_REVIEW, "validated", Actor.AGENT);
        machine.transition(taskId, TaskPhase.AWAITING_PUSH, "approved", Actor.HUMAN);
        machine.transition(taskId, TaskPhase.PUSHED_AWAITING_CI, "push", Actor.HUMAN);
        assertActive(taskId, StageType.DEVELOPMENT_STAGE);

        // PR out for remote review → review-monitor arms, development closes.
        machine.transition(taskId, TaskPhase.AWAITING_REMOTE_REVIEW, "ci_green", Actor.WEBHOOK);
        assertActive(taskId, StageType.REVIEW_MONITOR_STAGE);
        assertThat(only(taskId, StageType.DEVELOPMENT_STAGE).state()).isEqualTo(StageState.CLOSED);

        // Addressing comments and the awaiting_update_push gate both stay in
        // the review-monitor stage.
        machine.transition(taskId, TaskPhase.ADDRESSING_COMMENTS, "new_comment", Actor.WEBHOOK);
        assertActive(taskId, StageType.REVIEW_MONITOR_STAGE);
        machine.transition(taskId, TaskPhase.AGENT_RE_REVIEW, "re_review", Actor.AGENT);
        machine.transition(taskId, TaskPhase.AWAITING_UPDATE_PUSH, "approved", Actor.HUMAN);
        assertActive(taskId, StageType.REVIEW_MONITOR_STAGE);
        assertThat(stagesOfType(taskId, StageType.REVIEW_MONITOR_STAGE)).hasSize(1);
    }

    @Test
    void crossCuttingPhaseKeepsTheCurrentStage()
    {
        String taskId = seedTask();
        events.publishEvent(new TaskCreatedEvent(taskId));
        planStageService.approve(taskId, "rev-1");
        machine.transition(taskId, TaskPhase.VALIDATING, "ready", Actor.AGENT);
        StageInstance dev = only(taskId, StageType.DEVELOPMENT_STAGE);

        // NEEDS_ATTENTION is cross-cutting — it must not churn the timeline.
        machine.transition(taskId, TaskPhase.NEEDS_ATTENTION, "stuck", Actor.HUMAN);

        assertThat(stageStore.findActiveStage(taskId).map(StageInstance::id)).hasValue(dev.id());
        // The closed PlanStage and the open DevelopmentStage — NEEDS_ATTENTION
        // added no third stage.
        assertThat(stageStore.findStagesByTask(taskId)).hasSize(2);
    }

    private void assertActive(String taskId, StageType type)
    {
        StageInstance active = stageStore.findActiveStage(taskId).orElseThrow();
        assertThat(active.type()).isEqualTo(type);
        assertThat(active.state()).isEqualTo(StageState.OPEN);
    }

    private List<StageInstance> stagesOfType(String taskId, StageType type)
    {
        return stageStore.findStagesByTask(taskId).stream()
                .filter(s -> s.type() == type)
                .toList();
    }

    private StageInstance only(String taskId, StageType type)
    {
        List<StageInstance> matches = stagesOfType(taskId, type);
        assertThat(matches).hasSize(1);
        return matches.get(0);
    }

    private String seedTask()
    {
        Instant now = Instant.parse("2026-06-20T09:00:00Z");
        Thread thread = new Thread(
                UUID.randomUUID().toString(), ThreadKind.CLI_AGENT, "claude-code",
                null, "Lifecycle test", ThreadStatus.RUNNING, "claude-sonnet-4.6",
                0L, 0L, 0L, now, now, null, null, ThreadFlow.BUILD, "ws-default", null, null);
        threadStore.saveThread(thread);

        String taskId = UUID.randomUUID().toString();
        taskStore.saveTask(new Task(
                taskId, thread.id(), 1L, TaskStatus.RUNNING, "feature", null, "main", "/tmp",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, now, null, null, null, null, null));
        return taskId;
    }
}
