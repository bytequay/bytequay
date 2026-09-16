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
package com.bytequay.app.review;

import com.bytequay.agent.core.AgentMetrics;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.AgentUnitOfWork;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.app.context.RepositoryContextPreparation;
import com.bytequay.app.context.RepositoryContextService;
import com.bytequay.app.workspace.WorkspaceOperationLocks;
import com.bytequay.core.context.ActiveContextPreparation;
import com.bytequay.core.context.ContextInputFingerprint;
import com.bytequay.core.context.ContextScope;
import com.bytequay.core.context.RepositoryNotebook;
import com.bytequay.core.review.AgentReview;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.locks.ReentrantLock;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestReviewContextPreparationCleanup
{
    private static final String WORKSPACE = "workspace";
    private static final TaskId TASK = TaskId.valueOf("11111111-1111-4111-8111-111111111111");
    private static final TurnId TURN = TurnId.valueOf("22222222-2222-4222-8222-222222222222");
    private static final String TURN_KEY = "review-step:run:1";
    private static final Instant NOW = Instant.EPOCH;
    private static final AgentReview REVIEW = new AgentReview("review", WORKSPACE, "owner/repository", 1, OptionalLong.empty(), TASK.toString(),
            AgentReview.State.OPEN, true, Optional.empty(), Optional.empty(), 0, NOW, NOW, Optional.empty());
    private static final AgentTurnSettings SETTINGS = new AgentTurnSettings(Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO,
            Duration.ofMinutes(10), ImmutableList.of());
    private static final ActiveContextPreparation ACTIVE = new ActiveContextPreparation("attempt", "request", "sha256:" + "a".repeat(64),
            TASK.toString(), TURN.toString(), TURN_KEY, REVIEW.id(), "run", 0,
            ImmutableList.of(ContextInputFingerprint.create("project", ImmutableMap.of("README.md", "b".repeat(40)), ImmutableList.of())));

    private final RepositoryContextService notebooks = mock(RepositoryContextService.class);
    private final RepositoryContextPreparation preparation = mock(RepositoryContextPreparation.class);
    private final AgentTaskStore tasks = mock(AgentTaskStore.class);
    private final AgentTurnStore turns = mock(AgentTurnStore.class);
    private final ReviewContextPreparationCleanup cleanup = new ReviewContextPreparationCleanup(notebooks, preparation, tasks, turns, new AgentUnitOfWork()
    {
        @Override
        public <T> T execute(Work<T> work)
        {
            assertThat(((ReentrantLock) WorkspaceOperationLocks.get(WORKSPACE)).isHeldByCurrentThread()).isTrue();
            return work.run();
        }
    });

    @BeforeEach
    void activePreparation()
    {
        when(notebooks.getNotebook(WORKSPACE)).thenReturn(new RepositoryNotebook(1, 0, true, ImmutableList.of(), ImmutableList.of(ContextScope.project()),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), Optional.of(ACTIVE)));
        when(tasks.find(TASK)).thenReturn(Optional.of(task()));
    }

    @ParameterizedTest
    @EnumSource(value = TurnState.class, names = {"COMPLETED", "CANCELLED", "FAILED", "INTERRUPTED"})
    void settledOwnerIsFailedWithoutStartingAnotherTask(TurnState state)
    {
        AgentTurn settled = turn().finish(state, AgentMetrics.empty(), state == TurnState.COMPLETED ? Optional.empty() : Optional.of("Stopped"), NOW);
        when(turns.find(TURN)).thenReturn(Optional.of(settled));

        cleanup.reconcile(REVIEW);

        verify(preparation).fail(eq(WORKSPACE), eq(ACTIVE), anyString());
    }

    @Test
    void interruptedOwnerRemainsFencedUntilRecoveryProvesItsRuntimeStopped()
    {
        when(turns.find(TURN)).thenReturn(Optional.of(turn().finish(TurnState.INTERRUPTED, AgentMetrics.empty(), Optional.of("Restarted"), NOW)));
        when(tasks.find(TASK)).thenReturn(Optional.of(task().claimWorktreeMutation(WorktreeMutationClaim.recovery("launch", NOW), NOW)));

        cleanup.reconcile(REVIEW);
        verifyNoInteractions(preparation, turns);

        when(tasks.find(TASK)).thenReturn(Optional.of(task()));
        cleanup.reconcile(REVIEW);
        verify(preparation).fail(eq(WORKSPACE), eq(ACTIVE), anyString());
    }

    @Test
    void cancellationRequestAloneDoesNotReleaseThePreparationOwner()
    {
        when(turns.find(TURN)).thenReturn(Optional.of(turn().requestCancellation(NOW)));

        cleanup.reconcile(REVIEW);

        verifyNoInteractions(preparation);
    }

    @Test
    void unrelatedOrMissingExecutionCannotClearOwnership()
    {
        when(turns.find(TURN)).thenReturn(Optional.of(AgentTurn.accepted(TURN, TASK, "review-step:another-run:1", SETTINGS, NOW)
                .finish(TurnState.FAILED, AgentMetrics.empty(), Optional.of("Stopped"), NOW)));
        cleanup.reconcile(REVIEW);
        when(turns.find(TURN)).thenReturn(Optional.empty());
        cleanup.reconcile(REVIEW);
        when(notebooks.getNotebook(WORKSPACE)).thenReturn(RepositoryNotebook.empty());
        cleanup.reconcile(REVIEW);

        verifyNoInteractions(preparation);
    }

    private static AgentTask task()
    {
        return AgentTask.created(TASK, Optional.of(WORKSPACE), false, "Review", new AgentLaneId("codex"), new RepositoryIdentity("owner", "repository"),
                Path.of("/tmp/context-review-worktree"), new AgentTaskDefaults(Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO, Optional.empty()), NOW);
    }

    private static AgentTurn turn()
    {
        return AgentTurn.accepted(TURN, TASK, TURN_KEY, SETTINGS, NOW).markDeliveryStarted(NOW).running(new ProviderTurnRef("provider-turn"), Optional.empty());
    }
}
