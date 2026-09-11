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
package com.bytequay.app.agent;

import com.bytequay.agent.core.AgentMetrics;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.core.DevelopmentTurnCompletion;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.WorktreeService;
import com.bytequay.agent.core.WorktreeSnapshot;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.DevelopmentResultReport;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Optional;
import java.util.concurrent.RejectedExecutionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestDevelopmentFollowupCoordinator
{
    private static final Instant NOW = Instant.EPOCH;
    private final AgentTurnService turns = mock(AgentTurnService.class);
    private final WorktreeService worktrees = mock(WorktreeService.class);
    private final ArrayDeque<Runnable> pending = new ArrayDeque<>();
    private final DevelopmentFollowupCoordinator coordinator = new DevelopmentFollowupCoordinator(turns, worktrees, pending::add);
    private final TaskId taskId = TaskId.random();
    private final TaskWorktree worktree = new TaskWorktree(taskId, "workspace", new RepositoryIdentity("owner", "repo"),
            Path.of("/tmp", taskId.toString()), "bytequay/task/" + taskId, "a".repeat(40));
    private final AgentTask task = AgentTask.created(taskId, true, "Develop", TitleSource.USER, AgentLaneId.CODEX_CLI, worktree,
            AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO).withDevelopmentAutomation(DevelopmentAutomation.DEFAULT), NOW);

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void questionSettlesBeforeInspectingDirtyOrCommittedWork(boolean dirty)
    {
        DevelopmentTurnCompletion completion = completion(false);
        when(worktrees.inspect(worktree)).thenReturn(new WorktreeSnapshot(worktree, "b".repeat(40), dirty, false));
        accept(completion);

        pending.remove().run();

        verify(turns).finishDevelopmentContinuation(taskId, completion.turn().id());
        verify(turns, never()).handoffDevelopmentContinuationToQueue(taskId, completion.turn().id());
        verifyNoInteractions(worktrees);
    }

    @Test
    void answerRechecksEarlierPendingWorkWithoutRequiringNewEdits()
    {
        DevelopmentTurnCompletion completion = completion(true);
        when(worktrees.inspect(worktree)).thenReturn(new WorktreeSnapshot(worktree, "b".repeat(40), true, false));
        accept(completion);
        assertThat(pending).hasSize(1);
        verifyNoInteractions(worktrees);

        pending.remove().run();

        verify(worktrees).inspect(worktree);
        verify(turns).finishDevelopmentContinuation(taskId, completion.turn().id());
    }

    @Test
    void acceptedQueuedWorkTakesPrecedenceOverGitInspection()
    {
        DevelopmentTurnCompletion completion = completion(true);
        when(turns.handoffDevelopmentContinuationToQueue(taskId, completion.turn().id())).thenReturn(true);
        accept(completion);

        pending.remove().run();

        verifyNoInteractions(worktrees);
        verify(turns, never()).finishDevelopmentContinuation(taskId, completion.turn().id());
    }

    @Test
    void missingReportFailsInsteadOfAssumingReadiness()
    {
        AgentTurn turn = runningTurn().finish(TurnState.COMPLETED, AgentMetrics.empty(), Optional.empty(), NOW);
        DevelopmentTurnCompletion completion = new DevelopmentTurnCompletion(task, turn, true, Optional.empty());
        accept(completion);

        pending.remove().run();

        verify(turns).failDevelopmentContinuation(eq(taskId), eq(turn.id()), contains("did not report a valid development result"));
        verifyNoInteractions(worktrees);
    }

    @Test
    void cancellationWinsWhenTheProviderStillReportsSuccess()
    {
        AgentTurn turn = runningTurn().withDevelopmentResult(new DevelopmentResultReport(true, Optional.empty(), Optional.empty()))
                .requestCancellation(NOW).finish(TurnState.COMPLETED, AgentMetrics.empty(), Optional.empty(), NOW);
        DevelopmentTurnCompletion completion = new DevelopmentTurnCompletion(task, turn, true, Optional.empty());
        accept(completion);

        pending.remove().run();

        verify(turns).failDevelopmentContinuation(eq(taskId), eq(turn.id()), contains("Development was stopped"));
        verifyNoInteractions(worktrees);
    }

    @Test
    void readyReportCannotHideAProviderFailure()
    {
        AgentTurn turn = runningTurn().withDevelopmentResult(new DevelopmentResultReport(true, Optional.empty(), Optional.empty()))
                .finish(TurnState.FAILED, AgentMetrics.empty(), Optional.of("provider failed"), NOW);
        DevelopmentTurnCompletion completion = new DevelopmentTurnCompletion(task, turn, true, Optional.empty());
        accept(completion);

        pending.remove().run();

        verify(turns).failDevelopmentContinuation(eq(taskId), eq(turn.id()), contains("did not complete successfully"));
        verifyNoInteractions(worktrees);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void requiresBothQuiescenceAndSuccessfulCleanup(boolean quiescent)
    {
        DevelopmentTurnCompletion ready = completion(true);
        DevelopmentTurnCompletion completion = new DevelopmentTurnCompletion(task, ready.turn(), quiescent,
                quiescent ? Optional.of("cleanup failed") : Optional.empty());
        accept(completion);

        pending.remove().run();

        verify(turns).failDevelopmentContinuation(eq(taskId), eq(ready.turn().id()), contains("Automation failed"));
        verifyNoInteractions(worktrees);
    }

    @Test
    void revokedOwnershipStopsAnAlreadyScheduledCallback()
    {
        DevelopmentTurnCompletion completion = completion(true);
        accept(completion);
        when(turns.ownsDevelopmentContinuation(taskId, completion.turn().id())).thenReturn(false);

        pending.remove().run();

        verifyNoInteractions(worktrees);
        verify(turns, never()).finishDevelopmentContinuation(taskId, completion.turn().id());
        verify(turns, never()).failDevelopmentContinuation(eq(taskId), eq(completion.turn().id()), anyString());
    }

    @Test
    void stoppingDuringInspectionCannotSettleAnotherOwnersWork()
    {
        DevelopmentTurnCompletion completion = completion(true);
        accept(completion);
        when(worktrees.inspect(worktree)).thenAnswer(ignored -> {
            when(turns.ownsDevelopmentContinuation(taskId, completion.turn().id())).thenReturn(false);
            return new WorktreeSnapshot(worktree, worktree.startCommit(), false, false);
        });

        pending.remove().run();

        verify(turns, never()).finishDevelopmentContinuation(taskId, completion.turn().id());
    }

    @Test
    void openGitOperationFailsWithoutRetryingInspection()
    {
        DevelopmentTurnCompletion completion = completion(true);
        when(worktrees.inspect(worktree)).thenReturn(new WorktreeSnapshot(worktree, worktree.startCommit(), false, true));
        accept(completion);

        pending.remove().run();

        verify(turns).failDevelopmentContinuation(eq(taskId), eq(completion.turn().id()), contains("Git operation"));
        verify(worktrees).inspect(worktree);
        assertThat(pending).isEmpty();
    }

    @Test
    void dispatchFailureReleasesTheReservedContinuationThroughFailureHandling()
    {
        DevelopmentTurnCompletion completion = completion(true);
        when(turns.ownsDevelopmentContinuation(taskId, completion.turn().id())).thenReturn(true);
        var rejected = new DevelopmentFollowupCoordinator(turns, worktrees, ignored -> {
            throw new RejectedExecutionException("executor stopped");
        });

        rejected.completed(completion);

        verify(turns).failDevelopmentContinuation(eq(taskId), eq(completion.turn().id()), contains("Could not start"));
    }

    private void accept(DevelopmentTurnCompletion completion)
    {
        when(turns.ownsDevelopmentContinuation(taskId, completion.turn().id())).thenReturn(true);
        coordinator.completed(completion);
    }

    private DevelopmentTurnCompletion completion(boolean ready)
    {
        AgentTurn turn = runningTurn().withDevelopmentResult(new DevelopmentResultReport(ready, Optional.of("Choose the next implementation"), Optional.empty()))
                .finish(TurnState.COMPLETED, AgentMetrics.empty(), Optional.empty(), NOW);
        return new DevelopmentTurnCompletion(task, turn, true, Optional.empty());
    }

    private AgentTurn runningTurn()
    {
        var settings = new AgentTurnSettings(Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO, Duration.ofMinutes(5),
                ImmutableList.of(), Optional.empty(), Optional.of(DevelopmentAutomation.DEFAULT));
        return AgentTurn.accepted(TurnId.random(), taskId, "user-request", settings, NOW)
                .markDeliveryStarted(NOW).running(new ProviderTurnRef("native-turn"), Optional.empty());
    }
}
