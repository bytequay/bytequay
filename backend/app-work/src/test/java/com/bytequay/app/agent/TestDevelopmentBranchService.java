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

import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.core.DevelopmentTurnOrigin;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.core.impl.AgentRuntimeRegistry;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentSession;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.core.domain.PullRequestTask;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.store.PullRequestTaskStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static com.bytequay.agent.core.AgentTaskFixtures.created;
import static com.bytequay.app.service.credentials.CredentialService.GITHUB_SERVER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestDevelopmentBranchService
{
    private static final Instant NOW = Instant.EPOCH;
    private static final String SUFFIX = "convert-parquet-time-millis-to-micro";
    private final AgentTaskStore tasks = mock(AgentTaskStore.class);
    private final AgentTurnStore turns = mock(AgentTurnStore.class);
    private final DevelopmentGitInspector git = mock(DevelopmentGitInspector.class);
    private final AgentReviewStore reviews = mock(AgentReviewStore.class);
    private final PullRequestTaskStore assignments = mock(PullRequestTaskStore.class);
    private final DevelopmentFollowupCoordinator followups = mock(DevelopmentFollowupCoordinator.class);
    private final DevelopmentBranchRenameJournal journal = mock(DevelopmentBranchRenameJournal.class);
    private final TaskId id = TaskId.random();
    private final TurnId turnId = TurnId.random();
    private final TaskWorktree worktree = new TaskWorktree(id, "workspace", new RepositoryIdentity("owner", "repo"),
            Path.of("/tmp", id.toString()), "contributor/bytequay/find-and-fix-issue", "a".repeat(40));
    private final AgentTask task = created(id, true, "Find an issue", TitleSource.AGENT, AgentLaneId.CODEX_CLI, worktree,
            AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO).withDevelopmentAutomation(DevelopmentAutomation.DEFAULT), NOW)
            .claimWorktreeMutation(WorktreeMutationClaim.turn(turnId, NOW), NOW);
    private final AgentTurn turn = AgentTurn.accepted(turnId, id, "request", new AgentTurnSettings(Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO,
            Duration.ofHours(1), ImmutableList.of(), Optional.empty(), Optional.of(DevelopmentAutomation.DEFAULT)), NOW)
            .markDeliveryStarted(NOW).running(new ProviderTurnRef("provider"), Optional.empty());
    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    private final AtomicReference<AgentTask> currentTask = new AtomicReference<>(task);
    private final AtomicReference<AgentTurn> currentTurn = new AtomicReference<>(turn);
    private final AgentRuntimeRegistry runtime = new AgentRuntimeRegistry(tasks, ignored -> {}, ignored -> false, true);
    private final DevelopmentBranchService service = new DevelopmentBranchService(tasks, turns, runtime, git, reviews, assignments,
            beans.getBeanProvider(DevelopmentFollowupCoordinator.class), journal, Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp()
    {
        beans.registerSingleton("followups", followups);
        when(tasks.require(id)).thenAnswer(ignored -> currentTask.get());
        when(tasks.find(id)).thenAnswer(ignored -> Optional.of(currentTask.get()));
        when(tasks.update(any())).thenAnswer(call -> {
            AgentTask updated = call.getArgument(0);
            currentTask.set(updated);
            return updated;
        });
        when(tasks.listByWorkspace("workspace")).thenAnswer(ignored -> ImmutableList.of(currentTask.get()));
        String base = worktree.startCommit();
        RepoRef repo = new RepoRef("owner", "repo");
        when(git.capture(any())).thenReturn(new DevelopmentBaseline(worktree,
                new DevelopmentPublicationRoute(GITHUB_SERVER, repo, repo, "https://github.com/owner/repo.git", "origin", "main", base,
                        worktree.generatedBranch(), Optional.empty()),
                new DevelopmentLocalFacts(worktree.generatedBranch(), base, "clean", 0, 0, false, false)));
        when(turns.findActive(id)).thenAnswer(ignored -> Optional.of(currentTurn.get()).filter(value -> value.state().active()));
        when(turns.list(id)).thenAnswer(ignored -> ImmutableList.of(currentTurn.get()));
    }

    @Test
    void renameAndJournalRecoveryRejectAnIdleProviderThatStillOwnsTheCheckout()
    {
        service.propose(id, SUFFIX);
        finishTurn(TurnState.COMPLETED);
        AgentSession retained = mock(AgentSession.class);
        when(retained.retained()).thenReturn(true);
        runtime.registerSession(id, retained);
        // Send only recovers committed rename intent; an optional proposal stays deferred while the process is warm.
        assertThat(service.recover(currentTask.get())).isEqualTo(currentTask.get());
        assertThatThrownBy(() -> service.prepare(currentTask.get())).isInstanceOf(AgentConflictException.class).hasMessageContaining("must stop");
        when(journal.read(id)).thenReturn(Optional.of(new DevelopmentBranchRename(worktree.generatedBranch(), "user/bytequay/renamed", worktree.startCommit())));
        assertThatThrownBy(() -> service.recover(currentTask.get())).isInstanceOf(AgentConflictException.class).hasMessageContaining("must stop");
        verifyNoInteractions(git);
        runtime.close();
        verify(retained).close();
    }

    @Test
    void defersTheRenameUntilProviderShutdownWithoutLosingTaskOrCheckoutState()
    {
        String branch = "contributor/bytequay/" + SUFFIX;
        AgentTask updated = task.releaseWorktreeMutation(task.worktreeMutationClaim().orElseThrow(), NOW).withBranch(branch, NOW);
        when(git.renameBranch(any(), eq(branch), any(), any())).thenAnswer(call -> {
            Consumer<DevelopmentBranchRename> intent = call.getArgument(2);
            intent.accept(new DevelopmentBranchRename(worktree.generatedBranch(), branch, worktree.startCommit()));
            Consumer<TaskWorktree> record = call.getArgument(3);
            record.accept(updated.taskWorktree().orElseThrow());
            return updated.taskWorktree().orElseThrow();
        });

        assertThat(service.rename(id, turnId, SUFFIX)).contains("Recorded proposed branch", branch, "Keep using the current branch", worktree.generatedBranch());
        verifyNoInteractions(git, followups, journal);
        finishTurn(TurnState.COMPLETED);
        assertThat(service.prepare(currentTask.get())).isEqualTo(updated);

        verify(tasks).update(updated);
        verify(journal).write(id, new DevelopmentBranchRename(worktree.generatedBranch(), branch, worktree.startCommit()));
        verify(journal).clear(id);
        verify(followups).branchRenamed(id, worktree, updated.taskWorktree().orElseThrow());
        assertThat(updated.worktreeMutationClaim()).isEmpty();
        assertThat(updated.worktree()).isEqualTo(task.worktree());
        assertThat(updated.taskWorktree().orElseThrow().startCommit()).isEqualTo(worktree.startCommit());
    }

    @Test
    void theAgentProposalWinsOverBackgroundNaming()
    {
        service.propose(id, "initial-helper-name");
        service.rename(id, turnId, SUFFIX);
        service.propose(id, "late-helper-name");
        finishTurn(TurnState.COMPLETED);
        when(git.renameBranch(any(), any(), any(), any())).thenReturn(worktree);

        service.prepare(currentTask.get());
        service.propose(id, "helper-finishes-after-the-agent-name");
        service.prepare(currentTask.get());

        verify(git).renameBranch(any(), eq("contributor/bytequay/" + SUFFIX), any(), any());
    }

    @Test
    void optionalNamingFailureBeforeIntentDoesNotBlockTheContinuation()
    {
        service.propose(id, SUFFIX);
        finishTurn(TurnState.COMPLETED);
        when(git.renameBranch(any(), any(), any(), any())).thenThrow(new IllegalStateException("Remote verification unavailable"));

        assertThat(service.prepare(currentTask.get())).isEqualTo(currentTask.get());
        assertThat(service.prepare(currentTask.get())).isEqualTo(currentTask.get());

        verify(git).renameBranch(any(), any(), any(), any());
        verifyNoInteractions(followups);
        verify(journal, never()).write(any(), any());
        verify(tasks, never()).update(any());
    }

    @Test
    void optionalNamingFailureAfterIntentStillRequiresRecovery()
    {
        service.propose(id, SUFFIX);
        finishTurn(TurnState.COMPLETED);
        DevelopmentBranchRename intent = new DevelopmentBranchRename(worktree.generatedBranch(), "contributor/bytequay/" + SUFFIX, worktree.startCommit());
        IllegalStateException failure = new IllegalStateException("Persistence result unknown");
        when(git.renameBranch(any(), any(), any(), any())).thenAnswer(call -> {
            Consumer<DevelopmentBranchRename> recordIntent = call.getArgument(2);
            recordIntent.accept(intent);
            when(journal.read(id)).thenReturn(Optional.of(intent));
            throw failure;
        });

        assertThatThrownBy(() -> service.prepare(currentTask.get())).isSameAs(failure);

        verify(journal).write(id, intent);
        verify(journal, never()).clear(any());
        verifyNoInteractions(followups);
    }

    @Test
    void anAgentNameThatCannotBeAppliedBeforeIntentKeepsTheBranchAndIsNotRetried()
    {
        service.rename(id, turnId, SUFFIX);
        finishTurn(TurnState.COMPLETED);
        when(git.renameBranch(any(), any(), any(), any())).thenThrow(new IllegalStateException("The proposed branch name is already in use"));

        assertThat(service.prepare(currentTask.get())).isEqualTo(currentTask.get());
        assertThat(service.prepare(currentTask.get())).isEqualTo(currentTask.get());

        verify(git).renameBranch(any(), any(), any(), any());
        verifyNoInteractions(followups);
        verify(journal, never()).write(any(), any());
        verify(tasks, never()).update(any());
        // The agent's choice still outranks a later background suggestion.
        service.propose(id, "background-name");
        assertThat(service.prepare(currentTask.get())).isEqualTo(currentTask.get());
        verify(git).renameBranch(any(), any(), any(), any());
    }

    @Test
    void aRetainedBorrowerKeepsItsBranchSnapshot()
    {
        service.propose(id, SUFFIX);
        finishTurn(TurnState.COMPLETED);
        AgentTask borrower = created(TaskId.random(), false, "Review", TitleSource.USER, AgentLaneId.CODEX_CLI, worktree,
                task.defaults(), NOW);
        when(tasks.listByWorkspace("workspace")).thenReturn(ImmutableList.of(currentTask.get(), borrower));

        assertThat(service.prepare(currentTask.get()).taskWorktree()).contains(worktree);

        verifyNoInteractions(git, followups);
    }

    @ParameterizedTest
    @EnumSource(value = TurnState.class, names = {"FAILED", "CANCELLED", "INTERRUPTED"})
    void unsuccessfulTurnsDoNotApplySuggestions(TurnState state)
    {
        service.propose(id, SUFFIX);
        finishTurn(state);

        service.prepare(currentTask.get());

        verifyNoInteractions(git, followups);
    }

    @Test
    void recoveryDoesNotApplyAPendingSuggestion()
    {
        service.propose(id, SUFFIX);
        finishTurn(TurnState.COMPLETED);

        assertThat(service.recover(currentTask.get()).taskWorktree()).contains(worktree);

        verifyNoInteractions(git, followups);
        verify(journal, never()).write(any(), any());
    }

    @Test
    void recoveryReturnsTheFreshTaskBeforeTheNextWriterCanUseIt()
    {
        finishTurn(TurnState.COMPLETED);
        String branch = "contributor/bytequay/" + SUFFIX;
        DevelopmentBranchRename intent = new DevelopmentBranchRename(worktree.generatedBranch(), branch, worktree.startCommit());
        TaskWorktree renamed = currentTask.get().withBranch(branch, NOW).taskWorktree().orElseThrow();
        when(journal.read(id)).thenReturn(Optional.of(intent));
        when(git.recoverRename(eq(worktree), eq(intent), any())).thenAnswer(call -> {
            Consumer<TaskWorktree> record = call.getArgument(2);
            record.accept(renamed);
            return renamed;
        });

        AgentTask recovered = service.recover(currentTask.get());

        assertThat(recovered.taskWorktree()).contains(renamed);
        assertThat(recovered).isEqualTo(currentTask.get());
        verify(journal).clear(id);
        verify(followups).branchRenamed(id, worktree, renamed);
    }

    @Test
    void preservesTheBranchForAnImportedPullRequest()
    {
        when(assignments.findByTaskId(id.toString())).thenReturn(Optional.of(new PullRequestTask(id.toString(), "workspace", "owner/repo", 7, "PR context")));
        assertThat(service.rename(id, turnId, SUFFIX)).contains("Keep the imported PR branch", worktree.generatedBranch());
        verifyNoInteractions(git, followups);
    }

    @ParameterizedTest
    @EnumSource(value = TurnState.class, names = {"COMPLETED", "FAILED", "CANCELLED", "INTERRUPTED"})
    void laterTurnsCannotRenameEvenWhenTheFirstTurnLeftTheInitialBranch(TurnState firstOutcome)
    {
        AgentTurn first = AgentTurn.accepted(TurnId.random(), id, "first", turn.settings().orElseThrow(), NOW.minusSeconds(2))
                .markDeliveryStarted(NOW.minusSeconds(2)).running(new ProviderTurnRef("previous"), Optional.empty())
                .finish(firstOutcome, turn.metrics(), firstOutcome == TurnState.COMPLETED ? Optional.empty() : Optional.of("Stopped"), NOW.minusSeconds(1));
        when(turns.list(id)).thenReturn(ImmutableList.of(first, turn));

        assertThatThrownBy(() -> service.rename(id, turnId, SUFFIX))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Branch naming is only available during the first turn");
        verifyNoInteractions(git, followups);
    }

    @ParameterizedTest
    @ValueSource(strings = {"stale", "idle", "generated", "claim", "cancelled", "invalid"})
    void refusesCallsOutsideTheActiveDevelopmentScope(String kind)
    {
        if (kind.equals("stale")) {
            when(turns.findActive(id)).thenReturn(Optional.of(AgentTurn.accepted(TurnId.random(), id, "next", turn.settings().orElseThrow(), NOW)));
        }
        if (kind.equals("idle")) {
            when(turns.findActive(id)).thenReturn(Optional.empty());
        }
        if (kind.equals("generated")) {
            AgentTurn generated = AgentTurn.accepted(turnId, id, "generated", turn.settings().orElseThrow(), NOW)
                    .withDevelopmentOrigin(new DevelopmentTurnOrigin(DevelopmentTurnOrigin.Kind.AUTO_COMMIT, TurnId.random()))
                    .markDeliveryStarted(NOW).running(new ProviderTurnRef("provider"), Optional.empty());
            when(turns.findActive(id)).thenReturn(Optional.of(generated));
        }
        if (kind.equals("cancelled")) {
            when(turns.findActive(id)).thenReturn(Optional.of(turn.requestCancellation(NOW)));
        }
        if (kind.equals("claim")) {
            when(tasks.require(id)).thenReturn(task.releaseWorktreeMutation(task.worktreeMutationClaim().orElseThrow(), NOW));
        }
        assertThatThrownBy(() -> service.rename(id, turnId, kind.equals("invalid") ? "../main" : SUFFIX)).isInstanceOf(RuntimeException.class);
        verifyNoInteractions(git, followups);
    }

    private void finishTurn(TurnState state)
    {
        currentTask.set(task.releaseWorktreeMutation(task.worktreeMutationClaim().orElseThrow(), NOW));
        currentTurn.set(turn.finish(state, turn.metrics(), state == TurnState.COMPLETED ? Optional.empty() : Optional.of("Stopped"), NOW));
    }
}
