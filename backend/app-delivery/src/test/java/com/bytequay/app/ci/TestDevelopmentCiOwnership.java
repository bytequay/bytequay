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
package com.bytequay.app.ci;

import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.AgentHistoryPage;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.core.impl.AgentRuntimeRegistry;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.core.ci.CiAutofixConflictException;
import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.ci.CiAutofixRunId;
import com.bytequay.core.ci.CiAutofixStore;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.PullRequestTask;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.PullRequestTaskStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockMakers;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalLong;

import static com.bytequay.agent.core.AgentEventSource.root;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestDevelopmentCiOwnership
{
    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");
    private static final RepoRef BASE = RepoRef.of("upstream", "project");
    private static final RepoRef FORK = RepoRef.of("author", "project");
    private static final String HEAD = "a".repeat(40);

    private final CiAutofixStore store = mock(CiAutofixStore.class);
    private final AgentTaskStore tasks = mock(AgentTaskStore.class);
    private final AgentTurnStore turns = mock(AgentTurnStore.class);
    private final PullRequestTaskStore assignments = mock(PullRequestTaskStore.class);
    private final PullRequestStore pullRequests = mock(PullRequestStore.class);
    private final AgentEventService events = mock(AgentEventService.class);
    private final AgentRuntimeRegistry runtimes = new AgentRuntimeRegistry(mock(AgentTaskStore.class), ignored -> {}, ignored -> false, true);
    private final DevelopmentCiOwnership ownership = new DevelopmentCiOwnership(store, tasks, turns, assignments, pullRequests, events, runtimes);

    @Test
    void acceptsAssignedParentWithSeparateLocalBranch()
    {
        AgentTask parent = task(FORK).withDevelopmentContinuation(true, NOW);
        openParent(parent);
        assign(parent);
        CiAutofixRun run = assignedRun(Optional.of(parent.id().toString()));

        ownership.insert(run);

        verify(store).insert(run);
        assertThat(ownership.authorized(run)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"task", "workspace", "repo", "number", "headRepository", "missing"})
    void rejectsAssignedParentOutsideExactPullRequest(String mismatch)
    {
        AgentTask parent = task(mismatch.equals("headRepository") ? BASE : FORK).withDevelopmentContinuation(true, NOW);
        openParent(parent);
        if (!mismatch.equals("missing")) {
            when(assignments.findByTaskId(parent.id().toString())).thenReturn(Optional.of(new PullRequestTask(
                    mismatch.equals("task") ? TaskId.random().toString() : parent.id().toString(),
                    mismatch.equals("workspace") ? "other-workspace" : "workspace",
                    mismatch.equals("repo") ? "other/project" : BASE.fullName(), mismatch.equals("number") ? 2 : 1, "Assigned PR")));
        }

        assertThatThrownBy(() -> ownership.insert(assignedRun(Optional.of(parent.id().toString()))))
                .isInstanceOf(CiAutofixConflictException.class).hasMessageContaining("does not belong to the development parent");
        verify(store, never()).insert(any());
    }

    @Test
    void rejectsStandaloneAttachmentWhileAssignedSourceIsBusy()
    {
        AgentTask source = task(FORK).withDevelopmentContinuation(true, NOW);
        assign(source);
        when(tasks.listByWorkspace("workspace")).thenReturn(ImmutableList.of(source));
        when(tasks.find(source.id())).thenReturn(Optional.of(source));

        assertThatThrownBy(() -> ownership.insert(assignedRun(Optional.empty())))
                .isInstanceOf(CiAutofixConflictException.class).hasMessageContaining("still using this pull request branch");
        verify(store, never()).insert(any());
    }

    @Test
    void checksAdmissionAgainstAssignedRemoteBranch()
    {
        AgentTask source = task(FORK);
        assign(source);
        CiAutofixRun run = assignedRun(Optional.empty());
        PullRequest pr = mock(PullRequest.class, withSettings().mockMaker(MockMakers.INLINE));
        when(pr.headRef()).thenReturn(run.scope().headRef());
        when(pullRequests.findIdByRepoAndNumber(BASE.fullName(), 1)).thenReturn(Optional.of(1L));
        when(pullRequests.findById(1)).thenReturn(Optional.of(pr));
        when(store.findByActiveBranch(FORK.fullName(), run.scope().headRef())).thenReturn(Optional.of(run));

        assertThatThrownBy(() -> ownership.requireSourceAdmission(source)).isInstanceOf(AgentConflictException.class);
        verify(store).findByActiveBranch(FORK.fullName(), run.scope().headRef());
    }

    @Test
    void watchingParentRunAdmitsSourceWorkButARepairStillWritingBlocksIt()
    {
        AgentTask source = task(FORK);
        assign(source);
        CiAutofixRun run = assignedRun(Optional.of(source.id().toString()));
        when(store.listByParentTaskId(source.id().toString())).thenReturn(ImmutableList.of(run));
        when(store.findByActiveBranch(FORK.fullName(), source.taskWorktree().orElseThrow().generatedBranch())).thenReturn(Optional.of(run));

        ownership.requireSourceAdmission(source);

        when(store.listByParentTaskId(source.id().toString())).thenReturn(ImmutableList.of(run.linkUserTurn(TurnId.random().toString(), NOW)));
        assertThatThrownBy(() -> ownership.requireSourceAdmission(source)).isInstanceOf(AgentConflictException.class).hasMessageContaining("still repairing");
    }

    @Test
    void authorityFollowsTheOpenParentUntilRevoked()
    {
        AgentTask parent = task(FORK);
        when(tasks.find(parent.id())).thenReturn(Optional.of(parent));
        CiAutofixRun run = run(parent, FORK, Optional.of(parent.id().toString()));
        CiAutofixRun sibling = run(parent, FORK, Optional.of(parent.id().toString()));
        assertThat(ownership.authorized(run)).isTrue();

        ownership.revoke(run.id());
        ownership.drain(run.id());
        assertThat(ownership.authorized(run)).isFalse();
        assertThat(ownership.authorized(sibling)).isTrue();

        when(tasks.find(parent.id())).thenReturn(Optional.of(parent.withArchived(true, NOW).fail("Stopped", NOW, NOW.plusSeconds(60))));
        assertThat(ownership.authorized(sibling)).isFalse();
        assertThatThrownBy(() -> ownership.require(sibling)).isInstanceOf(CiAutofixConflictException.class);
        assertThat(ownership.authorized(run(parent, FORK, Optional.empty()))).isTrue();
    }

    @Test
    void parentIsBusyWhileItsTurnOrAutomationOwnsTheBranchButNotForALocalRun()
    {
        AgentTask parent = task(FORK);
        CiAutofixRun run = run(parent, FORK, Optional.of(parent.id().toString()));
        when(tasks.find(parent.id())).thenReturn(Optional.of(parent));
        assertThat(ownership.parentBusy(run)).isFalse();

        when(tasks.find(parent.id())).thenReturn(Optional.of(parent.claimWorktreeMutation(WorktreeMutationClaim.localRun("tears", NOW), NOW)));
        assertThat(ownership.parentBusy(run)).isFalse();

        when(tasks.find(parent.id())).thenReturn(Optional.of(parent.claimWorktreeMutation(WorktreeMutationClaim.turn(TurnId.random(), NOW), NOW)));
        assertThat(ownership.parentBusy(run)).isTrue();

        when(tasks.find(parent.id())).thenReturn(Optional.of(parent.withDevelopmentContinuation(true, NOW)));
        assertThat(ownership.parentBusy(run)).isTrue();
        assertThat(ownership.parentBusy(run(parent, FORK, Optional.empty()))).isFalse();
    }

    @Test
    void repairInstructionsCarryEveryAcceptedUserInputOfTheParent()
    {
        AgentTask parent = task(FORK);
        openParent(parent);
        AgentTask child = task(FORK);
        when(store.findByAgentTaskId(child.id().toString())).thenReturn(Optional.of(run(parent, FORK, Optional.of(parent.id().toString()))));

        assertThat(ownership.instructions(child)).contains("Development request scope for this CI task", "--- Accepted user input ---\nFix the requested code\n");
    }

    @ParameterizedTest
    @ValueSource(strings = {"author", "another-author"})
    void acceptsOnlyParentFromExactHeadRepository(String owner)
    {
        AgentTask parent = task(RepoRef.of(owner, "project")).withDevelopmentContinuation(true, NOW);
        openParent(parent);
        CiAutofixRun run = run(parent, FORK, Optional.of(parent.id().toString()));

        if (owner.equals(FORK.owner())) {
            ownership.insert(run);
            verify(store).insert(run);
        }
        else {
            assertThatThrownBy(() -> ownership.insert(run))
                    .isInstanceOf(CiAutofixConflictException.class).hasMessageContaining("does not belong to the development parent");
            verify(store, never()).insert(any());
        }
    }

    @Test
    void rejectsStandaloneAttachmentWhileForkSourceIsBusy()
    {
        AgentTask source = task(FORK).withDevelopmentContinuation(true, NOW);
        when(tasks.listByWorkspace("workspace")).thenReturn(ImmutableList.of(source));
        when(tasks.find(source.id())).thenReturn(Optional.of(source));

        assertThatThrownBy(() -> ownership.insert(run(source, FORK, Optional.empty())))
                .isInstanceOf(CiAutofixConflictException.class).hasMessageContaining("still using this pull request branch");
        verify(store, never()).insert(any());
    }

    @Test
    void doesNotMatchBasePrToForkSourceInSamePhysicalClone()
    {
        AgentTask source = task(FORK).withDevelopmentContinuation(true, NOW);
        when(tasks.listByWorkspace("workspace")).thenReturn(ImmutableList.of(source));
        CiAutofixRun run = run(source, BASE, Optional.empty());

        ownership.insert(run);

        verify(store).insert(run);
        verify(tasks, never()).find(any());
    }

    @Test
    void checksAdmissionAgainstSourceForkBranch()
    {
        AgentTask source = task(FORK);
        CiAutofixRun run = run(source, FORK, Optional.empty());
        when(store.findByActiveBranch(FORK.fullName(), run.scope().headRef())).thenReturn(Optional.of(run));

        assertThatThrownBy(() -> ownership.requireSourceAdmission(source)).isInstanceOf(AgentConflictException.class);
        verify(store).findByActiveBranch(FORK.fullName(), run.scope().headRef());
    }

    private static AgentTask task(RepoRef repository)
    {
        TaskId id = TaskId.random();
        TaskWorktree worktree = new TaskWorktree(id, "workspace", new RepositoryIdentity(repository.owner(), repository.repo()),
                Path.of("/tmp/ci-ownership").resolve(id.toString()), "bytequay/task/" + id, HEAD);
        return AgentTask.created(id, true, "Development", TitleSource.USER, AgentLaneId.CLAUDE_CLI, worktree, AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), NOW);
    }

    private static CiAutofixRun run(AgentTask source, RepoRef headRepository, Optional<String> parent)
    {
        return run(headRepository, parent, source.taskWorktree().orElseThrow().generatedBranch());
    }

    private static CiAutofixRun assignedRun(Optional<String> parent)
    {
        return run(FORK, parent, "existing-pr-branch");
    }

    private static CiAutofixRun run(RepoRef headRepository, Optional<String> parent, String headBranch)
    {
        return CiAutofixRun.create(CiAutofixRunId.random(), "fork-ci",
                new CiAutofixRun.Scope(PullRequestRef.of(BASE.owner(), BASE.repo(), 1), headRepository, "workspace", FORK, "author",
                        headBranch, "main", AgentLaneId.CLAUDE_CLI.toString(), parent),
                HEAD, HEAD, "b".repeat(40), new CiAutofixRun.Authorization(NOW), NOW);
    }

    private void assign(AgentTask source)
    {
        when(assignments.findByTaskId(source.id().toString())).thenReturn(Optional.of(new PullRequestTask(source.id().toString(), "workspace", BASE.fullName(), 1, "Assigned PR")));
    }

    private void openParent(AgentTask parent)
    {
        TurnId id = TurnId.random();
        AgentTurn turn = AgentTurn.accepted(id, parent.id(), id.toString(), new AgentTurnSettings(
                Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO, Duration.ofMinutes(10), ImmutableList.of(), Optional.empty(), Optional.empty()), NOW);
        when(tasks.find(parent.id())).thenReturn(Optional.of(parent));
        when(turns.find(id)).thenReturn(Optional.of(turn));
        when(turns.list(parent.id())).thenReturn(ImmutableList.of(turn));
        AgentEventEnvelope input = new AgentEventEnvelope(AgentEventEnvelope.CURRENT_SCHEMA_VERSION, EventId.random(), 1, parent.id(), Optional.of(id), root(), NOW,
                new AgentEventPayload.UserMessage("Fix the requested code", ImmutableList.of(), AgentEventPayload.DeliveryKind.TURN));
        when(events.readHistory(eq(parent.id()), any(), any())).thenReturn(new AgentHistoryPage(ImmutableList.of(input), OptionalLong.empty(), OptionalLong.empty(), 1));
    }
}
