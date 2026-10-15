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
import com.bytequay.agent.core.impl.AgentRuntimeRegistry;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.core.ci.CiAutofixConflictException;
import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.ci.CiAutofixRunId;
import com.bytequay.core.ci.CiAutofixStore;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.OptionalLong;

import static com.bytequay.agent.core.AgentEventSource.root;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestDevelopmentCiOwnership
{
    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");
    private static final RepoRef BASE = RepoRef.of("upstream", "project");
    private static final RepoRef FORK = RepoRef.of("author", "project");
    private static final String HEAD = "a".repeat(40);

    private final CiAutofixStore store = mock(CiAutofixStore.class);
    private final AgentTaskStore tasks = mock(AgentTaskStore.class);
    private final AgentTurnStore turns = mock(AgentTurnStore.class);
    private final AgentEventService events = mock(AgentEventService.class);
    private final AgentRuntimeRegistry runtimes = new AgentRuntimeRegistry(mock(AgentTaskStore.class), ignored -> {});
    private final DevelopmentCiOwnership ownership = new DevelopmentCiOwnership(store, tasks, turns, events, runtimes, Clock.fixed(NOW, ZoneOffset.UTC));

    @ParameterizedTest
    @ValueSource(strings = {"author", "another-author"})
    void acceptsOnlyParentFromExactHeadRepository(String owner)
    {
        AgentTask parent = task(RepoRef.of(owner, "project")).withDevelopmentContinuation(true, NOW);
        TurnId source = reserveParent(parent);
        CiAutofixRun run = run(parent, FORK, Optional.of(parent.id().toString()));

        if (owner.equals(FORK.owner())) {
            ownership.insert(run, Optional.of(source));
            verify(store).insert(run);
        }
        else {
            assertThatThrownBy(() -> ownership.insert(run, Optional.of(source)))
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

        assertThatThrownBy(() -> ownership.insert(run(source, FORK, Optional.empty()), Optional.empty()))
                .isInstanceOf(CiAutofixConflictException.class).hasMessageContaining("still using this pull request branch");
        verify(store, never()).insert(any());
    }

    @Test
    void doesNotMatchBasePrToForkSourceInSamePhysicalClone()
    {
        AgentTask source = task(FORK).withDevelopmentContinuation(true, NOW);
        when(tasks.listByWorkspace("workspace")).thenReturn(ImmutableList.of(source));
        CiAutofixRun run = run(source, BASE, Optional.empty());

        ownership.insert(run, Optional.empty());

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
        return CiAutofixRun.create(CiAutofixRunId.random(), "fork-ci",
                new CiAutofixRun.Scope(PullRequestRef.of(BASE.owner(), BASE.repo(), 1), headRepository, "workspace", FORK, "author",
                        source.taskWorktree().orElseThrow().generatedBranch(), "main", AgentLaneId.CLAUDE_CLI.toString(), parent),
                HEAD, HEAD, "b".repeat(40), new CiAutofixRun.Authorization(true, true, true, 3, NOW, NOW.plus(Duration.ofHours(8))), NOW);
    }

    private TurnId reserveParent(AgentTask parent)
    {
        TurnId id = TurnId.random();
        AgentTurn turn = AgentTurn.accepted(id, parent.id(), id.toString(), new AgentTurnSettings(
                Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO, Duration.ofMinutes(10), ImmutableList.of()), NOW);
        when(tasks.find(parent.id())).thenReturn(Optional.of(parent));
        when(turns.find(id)).thenReturn(Optional.of(turn));
        when(turns.list(parent.id())).thenReturn(ImmutableList.of(turn));
        AgentEventEnvelope input = new AgentEventEnvelope(AgentEventEnvelope.CURRENT_SCHEMA_VERSION, EventId.random(), 1, parent.id(), Optional.of(id), root(), NOW,
                new AgentEventPayload.UserMessage("Fix the requested code", ImmutableList.of(), AgentEventPayload.DeliveryKind.TURN));
        when(events.readHistory(eq(parent.id()), any(), any())).thenReturn(new AgentHistoryPage(ImmutableList.of(input), OptionalLong.empty(), OptionalLong.empty(), 1));
        synchronized (runtimes.taskLock(parent.id())) {
            ReflectionTestUtils.invokeMethod(runtimes, "reserveDevelopmentOwner", parent.id(), id);
        }
        return id;
    }
}
