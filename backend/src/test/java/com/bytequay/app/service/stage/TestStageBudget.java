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

import com.bytequay.app.domain.StageEventType;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.threads.TaskAutoPushEvent;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The per-stage auto-push budget: a fresh ci-fixing stage starts with the
 * default allowance, each autonomous push spends one, and the exhausting
 * push flags the stage and writes the audit event.
 */
@SpringBootTest
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class TestStageBudget
{
    @Autowired
    private StageBudgetService budgetService;
    @Autowired
    private StageStore stageStore;
    @Autowired
    private TaskStore taskStore;
    @Autowired
    private ThreadStore threadStore;
    @Autowired
    private ApplicationEventPublisher events;

    @Test
    void autonomousPushesDrainTheBudgetAndExhaustionFlagsTheStage()
    {
        String taskId = seedTask();
        StageInstance ciFixing = openCiFixing(taskId);
        assertThat(ciFixing.type()).isEqualTo(StageType.CI_FIXING_STAGE);
        StageMetrics fresh = budgetService.readMetrics(ciFixing.id());
        assertThat(fresh.autoPushBudget().limit()).isEqualTo(StageBudgetService.DEFAULT_AUTO_PUSH_BUDGET);
        assertThat(fresh.autoPushBudget().remaining()).isEqualTo(StageBudgetService.DEFAULT_AUTO_PUSH_BUDGET);

        // Four autonomous pushes: budget drains but doesn't exhaust.
        for (int i = 0; i < 4; i++) {
            events.publishEvent(new TaskAutoPushEvent(taskId));
        }
        StageMetrics drained = budgetService.readMetrics(ciFixing.id());
        assertThat(drained.autoPushBudget().used()).isEqualTo(4);
        assertThat(drained.autoPushBudget().remaining()).isEqualTo(1);
        assertThat(drained.budgetExhausted()).isFalse();
        assertThat(stageStore.findEventsByStage(ciFixing.id()))
                .noneMatch(e -> e.eventType() == StageEventType.BUDGET_EXHAUSTED);

        // The fifth exhausts it: flag set, audit event written.
        events.publishEvent(new TaskAutoPushEvent(taskId));
        StageMetrics exhausted = budgetService.readMetrics(ciFixing.id());
        assertThat(exhausted.autoPushBudget().remaining()).isZero();
        assertThat(exhausted.budgetExhausted()).isTrue();
        assertThat(stageStore.findEventsByStage(ciFixing.id()))
                .anyMatch(e -> e.eventType() == StageEventType.BUDGET_EXHAUSTED);
    }

    @Test
    void extendBudgetRequiresExhaustionThenBumpsAndResumes()
    {
        String taskId = seedTask();
        StageInstance ciFixing = openCiFixing(taskId);

        // Not exhausted yet → 422.
        assertThatThrownBy(() -> budgetService.extendBudget(ciFixing.id(), 5))
                .isInstanceOf(ResponseStatusException.class);

        drain(taskId, StageBudgetService.DEFAULT_AUTO_PUSH_BUDGET);
        StageMetrics after = budgetService.extendBudget(ciFixing.id(), 5);

        assertThat(after.autoPushBudget().limit()).isEqualTo(10);
        assertThat(after.autoPushBudget().remaining()).isEqualTo(5);
        assertThat(after.budgetExhausted()).isFalse();
        assertThat(taskStore.consecutiveAutoPushes(taskId)).isZero();
        assertThat(stageStore.findEventsByStage(ciFixing.id()))
                .anyMatch(e -> e.eventType() == StageEventType.BUDGET_EXHAUSTED_DECISION);
    }

    @Test
    void fallbackToReviewFlipsTheGateClosed()
    {
        String taskId = seedTask();
        StageInstance ciFixing = openCiFixing(taskId);
        drain(taskId, StageBudgetService.DEFAULT_AUTO_PUSH_BUDGET);

        StageMetrics after = budgetService.fallbackToReview(ciFixing.id());

        assertThat(after.internalReviewEnabled()).isTrue();
        assertThat(after.budgetExhausted()).isFalse();
        assertThat(stageStore.findEventsByStage(ciFixing.id()))
                .anyMatch(e -> e.eventType() == StageEventType.BUDGET_EXHAUSTED_DECISION);
    }

    @Test
    void reviewMonitorStageHasNoBudget()
    {
        String taskId = seedTask();
        StageInstance reviewMonitor = stageStore.openStage(taskId, StageType.REVIEW_MONITOR_STAGE, null);
        budgetService.onStageOpened(reviewMonitor);

        StageMetrics metrics = budgetService.readMetrics(reviewMonitor.id());
        assertThat(metrics.autoPushBudget()).isNull();
        assertThat(metrics.internalReviewEnabled()).isTrue();
    }

    /** Open a ci-fixing stage with its budget seeded — a {@code ci_fix}
     *  {@link com.bytequay.app.domain.AgentRun} opens one directly (it no
     *  longer rides a phase transition), so the test does the same. */
    private StageInstance openCiFixing(String taskId)
    {
        StageInstance stage = stageStore.openStage(taskId, StageType.CI_FIXING_STAGE, null);
        budgetService.onStageOpened(stage);
        return stage;
    }

    private void drain(String taskId, int pushes)
    {
        for (int i = 0; i < pushes; i++) {
            events.publishEvent(new TaskAutoPushEvent(taskId));
        }
    }

    private String seedTask()
    {
        Instant now = Instant.parse("2026-06-20T09:00:00Z");
        Thread thread = new Thread(
                UUID.randomUUID().toString(), ThreadKind.CLI_AGENT, "claude-code",
                null, "Budget test", ThreadStatus.RUNNING, "claude-sonnet-4.6",
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
