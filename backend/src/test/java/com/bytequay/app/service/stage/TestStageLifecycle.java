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
import com.bytequay.app.service.threads.TaskPhaseMachine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drives a Task through its phase walk against the real
 * {@link TaskPhaseMachine} and asserts {@link StageLifecycle} keeps the
 * stage timeline in step: one DevelopmentStage open across the dev phases,
 * a CiFixingStage opening at the first push, and a CleanupStage opening at
 * COMPLETED — each boundary closing the prior stage.
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
    private StageStore stageStore;
    @Autowired
    private TaskStore taskStore;
    @Autowired
    private ThreadStore threadStore;

    @Test
    void phaseWalkOpensAndClosesStagesAtTheirBoundaries()
    {
        String taskId = seedTask();
        // A brand-new task has no stage until it takes its first transition.
        assertThat(stageStore.findActiveStage(taskId)).isEmpty();

        // The first transition opens the DevelopmentStage; the dev phases
        // that follow keep it open.
        machine.transition(taskId, TaskPhase.VALIDATING, "ready_for_checks", Actor.AGENT);
        assertActive(taskId, StageType.DEVELOPMENT_STAGE);

        machine.transition(taskId, TaskPhase.INTERNAL_REVIEW, "validation_passed", Actor.AGENT);
        assertActive(taskId, StageType.DEVELOPMENT_STAGE);

        machine.transition(taskId, TaskPhase.AWAITING_PUSH, "approved", Actor.HUMAN);
        assertActive(taskId, StageType.DEVELOPMENT_STAGE);
        assertThat(stagesOfType(taskId, StageType.DEVELOPMENT_STAGE)).hasSize(1);

        // First push crosses into the CiFixing era: dev closes, CiFixing opens.
        machine.transition(taskId, TaskPhase.PUSHED_AWAITING_CI, "human_push", Actor.HUMAN);
        assertActive(taskId, StageType.CI_FIXING_STAGE);
        assertThat(only(taskId, StageType.DEVELOPMENT_STAGE).state()).isEqualTo(StageState.CLOSED);

        // PR closes: CiFixing closes, Cleanup opens (terminal).
        machine.transition(taskId, TaskPhase.COMPLETED, "merged", Actor.HUMAN);
        assertActive(taskId, StageType.CLEANUP_STAGE);
        assertThat(only(taskId, StageType.CI_FIXING_STAGE).state()).isEqualTo(StageState.CLOSED);

        List<StageInstance> stages = stageStore.findStagesByTask(taskId);
        assertThat(stages).extracting(StageInstance::type).containsExactlyInAnyOrder(
                StageType.DEVELOPMENT_STAGE, StageType.CI_FIXING_STAGE, StageType.CLEANUP_STAGE);
        // Two stages closed, one (cleanup) still open.
        assertThat(stages).filteredOn(s -> s.state() == StageState.CLOSED).hasSize(2);
    }

    @Test
    void crossCuttingPhaseKeepsTheCurrentStage()
    {
        String taskId = seedTask();
        machine.transition(taskId, TaskPhase.VALIDATING, "ready", Actor.AGENT);
        StageInstance dev = only(taskId, StageType.DEVELOPMENT_STAGE);

        // NEEDS_ATTENTION is cross-cutting — it must not churn the timeline.
        machine.transition(taskId, TaskPhase.NEEDS_ATTENTION, "stuck", Actor.HUMAN);

        assertThat(stageStore.findActiveStage(taskId).map(StageInstance::id)).hasValue(dev.id());
        assertThat(stageStore.findStagesByTask(taskId)).hasSize(1);
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
