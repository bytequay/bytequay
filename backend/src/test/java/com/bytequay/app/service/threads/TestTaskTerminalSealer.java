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
package com.bytequay.app.service.threads;

import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageState;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.repository.LocalReviewSubmissionStore;
import com.bytequay.app.repository.RoundGateStore;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskPushStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.review.ReviewRoundService;
import com.bytequay.app.service.runs.AgentRunService;
import com.bytequay.app.service.stage.StageStateMachine;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestTaskTerminalSealer
{
    private static final String TASK_ID = "t1.k1";

    private final StageStore stageStore = mock(StageStore.class);
    private final StageStateMachine stageMachine = mock(StageStateMachine.class);
    private final ReviewRoundService reviewRounds = mock(ReviewRoundService.class);
    private final AgentRunService agentRuns = mock(AgentRunService.class);
    private final LocalReviewSubmissionStore submissions = mock(LocalReviewSubmissionStore.class);
    private final TaskPushStore pushes = mock(TaskPushStore.class);
    private final RoundGateStore roundGates = mock(RoundGateStore.class);
    private final PlatformTransactionManager transactionManager = new TestTransactionManager();
    private final TaskCommandExecutor commands = new TaskCommandExecutor(transactionManager);
    private final TaskStore tasks = mock(TaskStore.class);
    private final TaskTerminalSealer sealer =
            new TaskTerminalSealer(
                    stageStore, stageMachine, reviewRounds, agentRuns, submissions, pushes,
                    roundGates, commands, tasks);

    @Test
    void closesTheOpenRoundAndEveryStillOpenStage()
    {
        StageInstance open = stage(StageState.OPEN);
        StageInstance closed = stage(StageState.CLOSED);
        when(agentRuns.liveRunsByTask(TASK_ID)).thenReturn(List.of());
        when(stageStore.findStagesByTask(TASK_ID)).thenReturn(List.of(open, closed));

        sealer.seal(TASK_ID, "pr_merged");

        verify(reviewRounds).closeOpenRoundsInCommand(TASK_ID, "pr_merged");
        verify(stageMachine).closeInCommand(TASK_ID, open.id(), "pr_merged");
        verify(stageMachine, never()).closeInCommand(TASK_ID, closed.id(), "pr_merged");
        verify(submissions).cancelOpenForTask(eq(TASK_ID), eq("pr_merged"), any());
        verify(pushes).sealActive(eq(TASK_ID), eq("pr_merged"), any());
        verify(roundGates).sealActive(eq(TASK_ID), eq("pr_merged"), any());
    }

    @Test
    void cancelsAnyLiveRunLeftAfterClosingRounds()
    {
        AgentRun run = new AgentRun(
                "run-1", TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_REMOTE,
                null, null, "stage-1", AgentRun.STATUS_RUNNING, 0, null, null, null,
                Instant.parse("2026-07-08T00:00:00Z"), null);
        when(agentRuns.liveRunsByTask(TASK_ID)).thenReturn(List.of(run));
        when(stageStore.findStagesByTask(TASK_ID)).thenReturn(List.of());

        sealer.seal(TASK_ID, "pr_merged");

        verify(agentRuns).transitionInCommand(
                TASK_ID, "run-1", AgentRun.STATUS_CANCELLED, "pr_merged");
    }

    @Test
    void synchronousTerminalEventUsesTheExistingTaskCommand()
    {
        when(agentRuns.liveRunsByTask(TASK_ID)).thenReturn(List.of());
        when(stageStore.findStagesByTask(TASK_ID)).thenReturn(List.of());

        commands.executeVoid(TASK_ID, () -> sealer.onTerminalSealing(
                new TaskTerminalSealingEvent(TASK_ID, "task_cancelled")));

        verify(reviewRounds).closeOpenRoundsInCommand(TASK_ID, "task_cancelled");
        verify(submissions).cancelOpenForTask(eq(TASK_ID), eq("task_cancelled"), any());
    }

    @Test
    void legacyTerminalSealerNeverClaimsAV2Task()
    {
        when(tasks.isV2Task(TASK_ID)).thenReturn(true);

        sealer.seal(TASK_ID, "task_cancelled");

        verify(reviewRounds, never()).closeOpenRoundsInCommand(any(), any());
        verify(stageStore, never()).findStagesByTask(any());
    }

    private static StageInstance stage(StageState state)
    {
        return new StageInstance(
                UUID.randomUUID(), TASK_ID, StageType.DEVELOPMENT_STAGE, state,
                Instant.parse("2026-07-08T00:00:00Z"), null, null, null);
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
