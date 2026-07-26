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

import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.domain.StageEventType;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageState;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.AgentRunStore;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestStageStateMachine
{
    private static final String TASK_ID = "task-1";
    private static final UUID STAGE_ID = UUID.fromString("00000000-0000-0000-0000-000000000101");

    private final StageStore stages = mock(StageStore.class);
    private final TaskStore tasks = mock(TaskStore.class);
    private final AgentRunStore runs = mock(AgentRunStore.class);
    private final StageBudgetService budgets = mock(StageBudgetService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final PlatformTransactionManager transactionManager = new TestTransactionManager();
    private final TaskCommandExecutor commands = new TaskCommandExecutor(transactionManager);
    private final StageStateMachine machine =
            new StageStateMachine(stages, tasks, runs, budgets, commands, events);

    @Test
    void phaseOwnerOpensOnceAndReusesTheOpenStage()
    {
        Task task = task(TaskStatus.RUNNING, TaskPhase.IMPLEMENTING);
        StageInstance open = stage(StageType.DEVELOPMENT_STAGE, StageState.OPEN);
        when(tasks.findTaskById(TASK_ID)).thenReturn(Optional.of(task));
        when(stages.findStageByType(TASK_ID, StageType.DEVELOPMENT_STAGE))
                .thenReturn(Optional.empty(), Optional.of(open));
        when(stages.openStage(TASK_ID, StageType.DEVELOPMENT_STAGE, null)).thenReturn(open);

        assertThat(machine.ensurePhaseOpen(TASK_ID, StageType.DEVELOPMENT_STAGE, null))
                .isEqualTo(open);
        assertThat(machine.ensurePhaseOpen(TASK_ID, StageType.DEVELOPMENT_STAGE, null))
                .isEqualTo(open);

        verify(stages).openStage(TASK_ID, StageType.DEVELOPMENT_STAGE, null);
    }

    @Test
    void freshPhaseChapterDoesNotReopenClosedHistory()
    {
        Task task = task(TaskStatus.IDLE, TaskPhase.PLANNING);
        StageInstance fresh = new StageInstance(
                UUID.fromString("00000000-0000-0000-0000-000000000202"),
                TASK_ID, StageType.PLAN_STAGE, StageState.OPEN,
                Instant.now(), null, null);
        when(tasks.findTaskById(TASK_ID)).thenReturn(Optional.of(task));
        when(stages.findActiveStage(TASK_ID)).thenReturn(Optional.empty());
        when(stages.openStage(TASK_ID, StageType.PLAN_STAGE, null)).thenReturn(fresh);

        StageInstance opened = commands.execute(TASK_ID,
                () -> machine.openFreshPhaseInCommand(
                        TASK_ID, StageType.PLAN_STAGE, null));

        assertThat(opened).isEqualTo(fresh);
        verify(stages).openStage(TASK_ID, StageType.PLAN_STAGE, null);
        verify(budgets).onStageOpened(fresh);
        verify(stages, never()).findStageByType(TASK_ID, StageType.PLAN_STAGE);
    }

    @Test
    void realCloseUsesCasWritesOneAuditAndPublishesOneEvictionSignal()
    {
        StageInstance open = stage(StageType.DEVELOPMENT_STAGE, StageState.OPEN);
        when(stages.findStageById(STAGE_ID)).thenReturn(Optional.of(open));
        when(stages.updateStateIf(eq(STAGE_ID), eq(StageState.OPEN),
                eq(StageState.CLOSED), any())).thenReturn(true);

        assertThat(machine.close(STAGE_ID, "phase_transition", Map.of("detail", "done")))
                .isTrue();

        verify(stages).recordEvent(STAGE_ID, TASK_ID, StageEventType.CLOSED,
                Map.of("reason", "phase_transition", "detail", "done"));
        verify(events).publishEvent(new StageClosedEvent(TASK_ID, STAGE_ID.toString()));
    }

    @Test
    void repeatedCloseIsAnIdempotentNoOp()
    {
        when(stages.findStageById(STAGE_ID))
                .thenReturn(Optional.of(stage(StageType.DEVELOPMENT_STAGE, StageState.CLOSED)));

        assertThat(machine.close(STAGE_ID, "again")).isFalse();

        verify(stages, never()).updateStateIf(any(), any(), any(), any());
        verify(events, never()).publishEvent(any());
    }

    @Test
    void stoppedTaskAndWrongPhaseCannotOpenOrReopen()
    {
        StageInstance closed = stage(StageType.DEVELOPMENT_STAGE, StageState.CLOSED);
        when(stages.findStageById(STAGE_ID)).thenReturn(Optional.of(closed));
        when(tasks.findTaskById(TASK_ID)).thenReturn(Optional.of(
                task(TaskStatus.PAUSED, TaskPhase.IMPLEMENTING)));

        assertThatThrownBy(() -> machine.reopenOwned(STAGE_ID))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("stopped");

        when(tasks.findTaskById(TASK_ID)).thenReturn(Optional.of(
                task(TaskStatus.RUNNING, TaskPhase.PLANNING)));
        assertThatThrownBy(() -> machine.ensurePhaseOpen(
                TASK_ID, StageType.DEVELOPMENT_STAGE, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("does not own");
        verify(stages, never()).updateStateIf(any(), any(), any(), any());
    }

    @Test
    void runContainerRequiresTheMatchingRunKind()
    {
        StageInstance open = stage(StageType.CI_FIXING_STAGE, StageState.OPEN);
        when(tasks.findTaskById(TASK_ID)).thenReturn(Optional.of(
                task(TaskStatus.RUNNING, TaskPhase.IMPLEMENTING)));
        when(stages.findStageByType(TASK_ID, StageType.CI_FIXING_STAGE))
                .thenReturn(Optional.empty());
        when(stages.openStage(TASK_ID, StageType.CI_FIXING_STAGE, null)).thenReturn(open);

        assertThat(machine.ensureRunOpen(
                TASK_ID, AgentRun.KIND_CI_FIX, StageType.CI_FIXING_STAGE, null))
                .isEqualTo(open);
        assertThatThrownBy(() -> machine.ensureRunOpen(
                TASK_ID, AgentRun.KIND_REVIEW_ROUND, StageType.CI_FIXING_STAGE, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("does not own");
    }

    @Test
    void publicCommandRejectsAnAmbientTransaction()
    {
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored ->
                assertThatThrownBy(() -> machine.ensurePhaseOpen(
                        TASK_ID, StageType.PLAN_STAGE, null))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("ambient transaction"));
    }

    private static StageInstance stage(StageType type, StageState state)
    {
        Instant now = Instant.parse("2026-07-25T10:00:00Z");
        return new StageInstance(
                STAGE_ID, TASK_ID, type, state, now,
                state == StageState.CLOSED ? now.plusSeconds(60) : null, null);
    }

    private static Task task(TaskStatus status, TaskPhase phase)
    {
        Instant now = Instant.parse("2026-07-25T10:00:00Z");
        return new Task(
                TASK_ID, "thread-1", 1L, status,
                "dev/test", "/tmp/worktree", "main", "/tmp/repo",
                null, null, null, null, null, "DEVELOP",
                null, null, 0L, 0L, 0L, null,
                now, null, null, null, null, null, null, phase, null, 0, null);
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
        protected void doBegin(Object transaction, TransactionDefinition definition)
        {
        }

        @Override
        protected void doCommit(DefaultTransactionStatus status)
        {
        }

        @Override
        protected void doRollback(DefaultTransactionStatus status)
        {
        }
    }
}
