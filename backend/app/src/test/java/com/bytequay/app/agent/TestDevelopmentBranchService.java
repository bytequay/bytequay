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
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
    private final TaskId id = TaskId.random();
    private final TurnId turnId = TurnId.random();
    private final TaskWorktree worktree = new TaskWorktree(id, "workspace", new RepositoryIdentity("owner", "repo"),
            Path.of("/tmp", id.toString()), "chenjian2664/bytequay/find-and-fix-issue", "a".repeat(40));
    private final AgentTask task = AgentTask.created(id, true, "Find an issue", TitleSource.AGENT, AgentLaneId.CODEX_CLI, worktree,
            AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO).withDevelopmentAutomation(DevelopmentAutomation.DEFAULT), NOW)
            .claimWorktreeMutation(WorktreeMutationClaim.turn(turnId, NOW), NOW);
    private final AgentTurn turn = AgentTurn.accepted(turnId, id, "request", new AgentTurnSettings(Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO,
            Duration.ofHours(1), ImmutableList.of(), Optional.empty(), Optional.of(DevelopmentAutomation.DEFAULT)), NOW)
            .markDeliveryStarted(NOW).running(new ProviderTurnRef("provider"), Optional.empty());
    private final DefaultListableBeanFactory beans = new DefaultListableBeanFactory();
    private final DevelopmentBranchService service = new DevelopmentBranchService(tasks, turns, new AgentRuntimeRegistry(ignored -> {}), git, reviews, assignments,
            beans.getBeanProvider(DevelopmentFollowupCoordinator.class), Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp()
    {
        beans.registerSingleton("followups", followups);
        when(tasks.require(id)).thenReturn(task);
        String base = worktree.startCommit();
        RepoRef repo = new RepoRef("owner", "repo");
        when(git.capture(task)).thenReturn(new DevelopmentBaseline(worktree,
                new DevelopmentPublicationRoute(repo, repo, "https://github.com/owner/repo.git", "origin", "main", base, worktree.generatedBranch()),
                new DevelopmentLocalFacts(worktree.generatedBranch(), base, base, "clean", 0, 0, 0, 0, 0, false, false)));
        when(turns.findActive(id)).thenReturn(Optional.of(turn));
        when(turns.list(id)).thenReturn(ImmutableList.of(turn));
    }

    @Test
    void recordsTheVerifiedNameWithoutLosingTaskOrCheckoutState()
    {
        String branch = "chenjian2664/bytequay/" + SUFFIX;
        AgentTask updated = task.withBranch(branch, NOW);
        when(git.renameBranch(any(), eq(branch), any())).thenAnswer(call -> {
            Consumer<TaskWorktree> record = call.getArgument(2);
            record.accept(updated.taskWorktree().orElseThrow());
            return updated.taskWorktree().orElseThrow();
        });

        assertThat(service.rename(id, turnId, SUFFIX)).contains(branch);

        verify(tasks).update(updated);
        verify(followups).branchRenamed(id, turnId, worktree, updated.taskWorktree().orElseThrow());
        assertThat(updated.worktreeMutationClaim()).isEqualTo(task.worktreeMutationClaim());
        assertThat(updated.worktree()).isEqualTo(task.worktree());
        assertThat(updated.taskWorktree().orElseThrow().startCommit()).isEqualTo(worktree.startCommit());
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
}
