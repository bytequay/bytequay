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
import com.bytequay.app.domain.StageState;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStageIteration;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadTurnEvent;
import com.bytequay.app.repository.IterationStore;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnEventStore;
import com.bytequay.app.service.threads.TaskTurnFinishedEvent;
import com.bytequay.app.service.threads.ThreadTurnScheduler;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The iteration lifecycle against the async-turn model, with collaborators
 * mocked (mirrors the driver tests) so the real scheduler never dispatches:
 * begin opens a row only for monitor stages, end solicits one follow-up
 * turn when no summary was recorded in-line, and a follow-up that still
 * doesn't record one falls back to a synthetic placeholder.
 */
class TestIterationService
{
    private static final String TASK_ID = "task-1";
    private static final String THREAD_ID = "thread-1";

    private final IterationStore iterationStore = mock(IterationStore.class);
    private final StageStore stageStore = mock(StageStore.class);
    private final TaskStore taskStore = mock(TaskStore.class);
    private final ThreadStore threadStore = mock(ThreadStore.class);
    private final ThreadTurnEventStore turnEventStore = mock(ThreadTurnEventStore.class);
    private final ThreadTurnScheduler scheduler = mock(ThreadTurnScheduler.class);

    private final IterationService service = new IterationService(
            iterationStore, stageStore, taskStore, threadStore, turnEventStore, scheduler);

    private final UUID stageId = UUID.randomUUID();

    @Test
    void beginOpensIterationForMonitorStageAndEmitsLoopStarted()
    {
        activeStage(StageType.CI_FIXING_STAGE);
        when(iterationStore.nextIterationNumber(stageId)).thenReturn(1);

        service.begin(TASK_ID, "turn-1", IterationService.TRIGGER_RED_CI);

        verify(iterationStore).save(any(TaskStageIteration.class));
        verify(stageStore).recordEvent(eq(stageId), eq(TASK_ID),
                eq(StageEventType.LOOP_ITERATION_STARTED), any());
    }

    @Test
    void beginNoOpsWhenActiveStageIsNotAMonitor()
    {
        activeStage(StageType.DEVELOPMENT_STAGE);

        service.begin(TASK_ID, "turn-x", IterationService.TRIGGER_RED_CI);

        verify(iterationStore, never()).save(any());
        verify(stageStore, never()).recordEvent(any(), anyString(), any(), any());
    }

    @Test
    void inlineSummaryClosesWithoutSolicitingFollowup()
    {
        TaskStageIteration iter = openIteration("turn-1")
                .withSummary("bumped retry default 3->5", Instant.now());
        when(iterationStore.findByTurnId("turn-1")).thenReturn(Optional.of(iter));
        task(TaskPhase.PUSHED_AWAITING_CI);

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "turn-1", false));

        // Closed with a reason, but no follow-up turn enqueued.
        verify(iterationStore).save(any(TaskStageIteration.class));
        verify(scheduler, never()).enqueueStageTurn(any(), anyString(), anyString(), any(), any(), any(), any());
    }

    @Test
    void missingSummarySolicitsExactlyOneFollowupTurn()
    {
        TaskStageIteration iter = openIteration("turn-1");
        when(iterationStore.findByTurnId("turn-1")).thenReturn(Optional.of(iter));
        task(TaskPhase.PUSHED_AWAITING_CI);
        Thread thread = thread();
        when(threadStore.findThreadById(THREAD_ID)).thenReturn(Optional.of(thread));
        when(scheduler.enqueueStageTurn(any(), anyString(), anyString(), any(), any(), any(), any())).thenReturn("followup-1");

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "turn-1", false));

        // Bound to the task id + the iteration's own stage so the summary turn
        // runs on the task's stage agent and writes to stage_messages.
        verify(scheduler).enqueueStageTurn(
                any(), anyString(), eq(TASK_ID), eq(stageId.toString()), any(), any(), any());
        // The follow-up turn id is recorded so its completion can be matched.
        verify(iterationStore).save(argThatHasSummaryRequestTurn());
    }

    @Test
    void followupWithoutToolCallWritesPlaceholderAndDualWrites()
    {
        TaskStageIteration awaiting = openIteration("turn-1")
                .withEnded(Instant.now(), "push_completed")
                .withSummaryRequestTurnId("followup-1");
        when(iterationStore.findByTurnId("followup-1")).thenReturn(Optional.empty());
        when(iterationStore.findBySummaryRequestTurnId("followup-1")).thenReturn(Optional.of(awaiting));
        when(iterationStore.findById(awaiting.id())).thenReturn(Optional.of(awaiting));
        task(TaskPhase.PUSHED_AWAITING_CI);

        service.onTurnFinished(new TaskTurnFinishedEvent(TASK_ID, "followup-1", false));

        // Placeholder dual-write: iteration row saved + an is_summary turn event.
        verify(iterationStore).save(any(TaskStageIteration.class));
        verify(turnEventStore).appendEvent(any(ThreadTurnEvent.class));
    }

    @Test
    void latestCiFixingSummariesReturnsTheNewestCiStagesSummariesInOrder()
    {
        UUID oldStage = UUID.randomUUID();
        UUID newStage = UUID.randomUUID();
        Instant t0 = Instant.parse("2026-06-01T00:00:00Z");
        when(stageStore.findStagesByTask(TASK_ID)).thenReturn(List.of(
                new StageInstance(oldStage, TASK_ID, StageType.CI_FIXING_STAGE,
                        StageState.CLOSED, t0, t0.plusSeconds(60), null),
                new StageInstance(newStage, TASK_ID, StageType.CI_FIXING_STAGE,
                        StageState.CLOSED, t0.plusSeconds(120), t0.plusSeconds(180), null),
                new StageInstance(UUID.randomUUID(), TASK_ID, StageType.DEVELOPMENT_STAGE,
                        StageState.CLOSED, t0.plusSeconds(30), t0.plusSeconds(40), null)));
        when(iterationStore.findByStage(newStage)).thenReturn(List.of(
                summarised(newStage, 1, "bumped retry 3->5"),
                summarised(newStage, 2, "fixed flaky test"),
                noSummary(newStage, 3)));

        List<String> summaries = service.latestCiFixingSummaries(TASK_ID);

        assertThat(summaries)
                .containsExactly("bumped retry 3->5", "fixed flaky test");
    }

    @Test
    void latestCiFixingSummariesIsEmptyWhenNoCiStageRan()
    {
        when(stageStore.findStagesByTask(TASK_ID)).thenReturn(List.of(
                new StageInstance(UUID.randomUUID(), TASK_ID, StageType.DEVELOPMENT_STAGE,
                        StageState.CLOSED, Instant.now(), Instant.now(), null)));

        assertThat(service.latestCiFixingSummaries(TASK_ID)).isEmpty();
    }

    // ── helpers ─────────────────────────────────────────────────────────

    private TaskStageIteration summarised(UUID stage, int n, String text)
    {
        return TaskStageIteration.opened(
                        UUID.randomUUID(), stage, TASK_ID, "turn-" + n, n,
                        IterationService.TRIGGER_RED_CI, Instant.now())
                .withSummary(text, Instant.now());
    }

    private TaskStageIteration noSummary(UUID stage, int n)
    {
        return TaskStageIteration.opened(
                UUID.randomUUID(), stage, TASK_ID, "turn-" + n, n,
                IterationService.TRIGGER_RED_CI, Instant.now());
    }

    private void activeStage(StageType type)
    {
        StageInstance stage = new StageInstance(
                stageId, TASK_ID, type, StageState.OPEN, Instant.now(), null, null);
        when(stageStore.findActiveStage(TASK_ID)).thenReturn(Optional.of(stage));
    }

    private TaskStageIteration openIteration(String turnId)
    {
        return TaskStageIteration.opened(
                UUID.randomUUID(), stageId, TASK_ID, turnId, 1,
                IterationService.TRIGGER_RED_CI, Instant.now());
    }

    private void task(TaskPhase phase)
    {
        Task task = mock(Task.class);
        when(task.phase()).thenReturn(phase);
        when(task.threadId()).thenReturn(THREAD_ID);
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task));
    }

    private Thread thread()
    {
        Thread thread = mock(Thread.class);
        when(thread.id()).thenReturn(THREAD_ID);
        return thread;
    }

    private static TaskStageIteration argThatHasSummaryRequestTurn()
    {
        return argThat(it -> it != null && "followup-1".equals(it.summaryRequestTurnId()));
    }
}
