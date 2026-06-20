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

import com.bytequay.app.beans.stage.TaskBrainViewData;
import com.bytequay.app.domain.Actor;
import com.bytequay.app.domain.StageEventType;
import com.bytequay.app.domain.StageInstance;
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
import com.bytequay.app.service.threads.TaskAutoPushEvent;
import com.bytequay.app.service.threads.TaskPhaseMachine;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The brain endpoint surfaces live values computed from the stage-event
 * stream: autonomous-push totals, the current budget slice, the
 * budget-exhaustion approval card, the ready-to-merge state, and the
 * matching brain-feed rows.
 */
@SpringBootTest
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class TestStageBrain
{
    @Autowired
    private StageService stageService;
    @Autowired
    private TaskPhaseMachine machine;
    @Autowired
    private StageStore stageStore;
    @Autowired
    private TaskStore taskStore;
    @Autowired
    private ThreadStore threadStore;
    @Autowired
    private ApplicationEventPublisher events;

    @Test
    void brainReflectsBudgetExhaustionAndReadyState()
    {
        String taskId = seedTask();
        taskStore.linkPullRequest(taskId, 7, "open");
        StageInstance ciFixing = openCiFixing(taskId);

        // Exhaust the budget, then arm + fire the ready-to-merge signal.
        for (int i = 0; i < StageBudgetService.DEFAULT_AUTO_PUSH_BUDGET; i++) {
            events.publishEvent(new TaskAutoPushEvent(taskId));
        }
        taskStore.markMergeNotificationSentIfUnset(taskId, Instant.parse("2026-06-20T12:00:00Z"));
        stageStore.recordEvent(ciFixing.id(), taskId, StageEventType.NOTIFY_FIRED,
                Map.of("reason", "ready_to_merge"));

        TaskBrainViewData brain = stageService.getBrain(taskId);

        assertThat(brain.aggregate().pushes()).isEqualTo(StageBudgetService.DEFAULT_AUTO_PUSH_BUDGET);
        assertThat(brain.aggregate().autoPushBudget()).isNotNull();
        assertThat(brain.aggregate().autoPushBudget().used())
                .isEqualTo(StageBudgetService.DEFAULT_AUTO_PUSH_BUDGET);

        assertThat(brain.rightRail().approval()).isNotNull();
        assertThat(brain.rightRail().approval().reasonShort()).contains("5/5");
        assertThat(brain.rightRail().approval().primaryAction().href())
                .isEqualTo("/api/stages/" + ciFixing.id() + "/budget/extend");
        assertThat(brain.rightRail().linkedPr()).isNotNull();
        assertThat(brain.rightRail().linkedPr().mergeable()).isTrue();

        assertThat(brain.brainFeed()).anyMatch(r -> r.type().equals("NEEDS_ATTENTION"));
        assertThat(brain.brainFeed()).anyMatch(r -> r.type().equals("NOTIFY_READY_FOR_MERGE"));
    }

    private StageInstance openCiFixing(String taskId)
    {
        machine.transition(taskId, TaskPhase.VALIDATING, "ready", Actor.AGENT);
        machine.transition(taskId, TaskPhase.INTERNAL_REVIEW, "validated", Actor.AGENT);
        machine.transition(taskId, TaskPhase.AWAITING_PUSH, "approved", Actor.HUMAN);
        machine.transition(taskId, TaskPhase.PUSHED_AWAITING_CI, "human_push", Actor.HUMAN);
        StageInstance active = stageStore.findActiveStage(taskId).orElseThrow();
        assertThat(active.type()).isEqualTo(StageType.CI_FIXING_STAGE);
        return active;
    }

    private String seedTask()
    {
        Instant now = Instant.parse("2026-06-20T09:00:00Z");
        Thread thread = new Thread(
                UUID.randomUUID().toString(), ThreadKind.CLI_AGENT, "claude-code",
                null, "Brain test", ThreadStatus.RUNNING, "claude-sonnet-4.6",
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
