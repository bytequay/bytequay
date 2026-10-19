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
package com.bytequay.app.backport;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.app.agent.RelatedWorkspaceWork;
import com.bytequay.app.backport.BackportChainService.Request;
import com.bytequay.app.cherrypick.CherryPickExecutionService;
import com.bytequay.app.cherrypick.CherryPickPreview;
import com.bytequay.app.cherrypick.CherryPickPreviewService;
import com.bytequay.app.cherrypick.MergedPullRequestResolver;
import com.bytequay.app.cherrypick.PreviewCherryPickRangeCommand;
import com.bytequay.app.cherrypick.StartCherryPickExecutionCommand;
import com.bytequay.app.ci.CiAutofixService;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.repository.github.GitHubPullRequestIdentity;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.settings.AgentModelSetting;
import com.bytequay.app.service.settings.AgentModelType;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.app.workspace.WorkspacePullRequestService;
import com.bytequay.app.workspace.WorkspaceRelationships;
import com.bytequay.app.workspace.WorkspaceRemoteSourceService;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.backport.BackportChain;
import com.bytequay.core.backport.BackportChain.State;
import com.bytequay.core.backport.BackportChainStore;
import com.bytequay.core.cherrypick.CherryPickExecution;
import com.bytequay.core.cherrypick.CherryPickExecution.FinishReason;
import com.bytequay.core.cherrypick.CherryPickExecution.Range;
import com.bytequay.core.cherrypick.CherryPickExecutionId;
import com.bytequay.core.cherrypick.CherryPickExecutionStore;
import com.bytequay.core.cherrypick.CherryPickItem;
import com.bytequay.core.cherrypick.MergedPullRequestRange;
import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.ci.CiAutofixRunId;
import com.bytequay.core.ci.CiAutofixStore;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Preconditions.checkState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestBackportChainService
{
    private static final Instant NOW = Instant.EPOCH;
    private static final String BASE = "a".repeat(40);
    private static final String P4_SHA = "b".repeat(40);
    private static final String P3_SHA = "c".repeat(40);
    private static final String TARGET = "d".repeat(40);
    private final BackportChainStore chains = mock(BackportChainStore.class);
    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final AgentTaskStore tasks = mock(AgentTaskStore.class);
    private final AgentTurnStore turns = mock(AgentTurnStore.class);
    private final RelatedWorkspaceWork related = mock(RelatedWorkspaceWork.class);
    private final WorkspaceRelationships relationships = mock(WorkspaceRelationships.class);
    private final WorkspaceRepositoryService repositories = mock(WorkspaceRepositoryService.class);
    private final WorkspaceRemoteSourceService remotes = mock(WorkspaceRemoteSourceService.class);
    private final ModelSettingsService models = mock(ModelSettingsService.class);
    private final GitHubPullRequestReadClient prs = mock(GitHubPullRequestReadClient.class);
    private final CredentialService credentials = mock(CredentialService.class);
    private final GitCommandRunner git = mock(GitCommandRunner.class);
    private final MergedPullRequestResolver landed = mock(MergedPullRequestResolver.class);
    private final CherryPickPreviewService previews = mock(CherryPickPreviewService.class);
    private final CherryPickExecutionService executions = mock(CherryPickExecutionService.class);
    private final CherryPickExecutionStore executionStore = mock(CherryPickExecutionStore.class);
    private final WorkspacePullRequestService delivery = mock(WorkspacePullRequestService.class);
    private final CiAutofixStore ciStore = mock(CiAutofixStore.class);
    private final CiAutofixService ci = mock(CiAutofixService.class);
    private final Map<String, BackportChain> saved = new ConcurrentHashMap<>();
    private final Map<String, CherryPickExecution> children = new ConcurrentHashMap<>();
    private final Map<String, Workspace> workspaceData = new ConcurrentHashMap<>();
    private final Map<Integer, GitHubPullRequestIdentity> remote = new ConcurrentHashMap<>();
    private AgentTask source;
    private AgentTurn turn;

    @BeforeEach
    void setUp()
    {
        var id = TaskId.random();
        source = AgentTask.created(id, true, "Fix Trino", TitleSource.USER, AgentLaneId.CODEX_CLI,
                new TaskWorktree(id, "p4", new RepositoryIdentity("test", "p4"), Path.of("/tmp", id.toString()), "bytequay/task/" + id, BASE),
                new AgentTaskDefaults(Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO), NOW);
        turn = AgentTurn.accepted(TurnId.random(), id, "human", new AgentTurnSettings(Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO,
                Duration.ofMinutes(5), ImmutableList.of()), NOW);
        when(related.eligible(source.id().toString())).thenReturn(Optional.of(source));
        when(tasks.find(source.id())).thenReturn(Optional.of(source));
        when(turns.findActive(source.id())).thenReturn(Optional.of(turn));
        when(chains.find(anyString())).thenAnswer(call -> Optional.ofNullable(saved.get(call.getArgument(0))));
        when(chains.list()).thenAnswer(_ -> ImmutableList.copyOf(saved.values()));
        when(chains.createOrGet(any())).thenAnswer(call -> {
            BackportChain value = call.getArgument(0);
            return saved.computeIfAbsent(value.id(), _ -> value);
        });
        when(chains.save(any())).thenAnswer(call -> {
            BackportChain value = call.getArgument(0);
            return saved.compute(value.id(), (_, old) -> {
                checkState(old.version() == value.version(), "version changed");
                return value.withVersion(value.version() + 1);
            });
        });
        for (int number = 2; number <= 4; number++) {
            var workspace = workspace(number);
            workspaceData.put(workspace.id(), workspace);
            when(relationships.list(workspace.id())).thenReturn(ImmutableList.of(
                    new WorkspaceRelationships.Relationship("p" + (number + 1), WorkspaceRelationships.Kind.FORK, "Fork", false)));
            when(repositories.requireReadyRepository(workspace.id())).thenReturn(new WorkspaceRepositoryContext(workspace.id(), Path.of("/tmp", workspace.id()),
                    number, workspace.selectedRepository(), OptionalLong.empty(), workspace.monitoredRepository(), "origin", "origin", "main", ImmutableList.of()));
        }
        when(workspaces.findById(anyString())).thenAnswer(call -> Optional.ofNullable(workspaceData.get(call.getArgument(0))));
        when(workspaces.isWatched(anyString())).thenAnswer(call -> workspaceData.containsKey(call.getArgument(0)));
        when(workspaces.findAllRetained()).thenAnswer(_ -> ImmutableList.copyOf(workspaceData.values()));
        when(models.agentModel(eq(AgentModelType.DEVELOPMENT), any())).thenReturn(new AgentModelSetting(
                AgentModelType.DEVELOPMENT, true, "codex", "Codex", "model", "Model", Optional.of("high")));
        when(credentials.resolveGitHubToken(anyString())).thenReturn("fixture-token");
        remote.put(4, identity(4, GitHubPullRequestIdentity.State.OPEN, source.taskWorktree().orElseThrow().generatedBranch()));
        when(prs.fetchPullRequestIdentities(anyString(), any(), any(), anyString())).thenAnswer(_ -> ImmutableList.of(remote.get(4)));
        when(prs.fetchPullRequestIdentity(anyString(), any())).thenAnswer(call -> remote.get(((PullRequestRef) call.getArgument(1)).number()));
        when(landed.resolve(anyString(), anyString(), any())).thenAnswer(call -> {
            PullRequestRef pr = call.getArgument(2);
            String sha = pr.number() == 4 ? P4_SHA : P3_SHA;
            return new MergedPullRequestRange(pr, pr.number(), "main", NOW, sha, ImmutableList.of(sha), BASE, ImmutableList.of(sha));
        });
        when(executionStore.findByIdempotencyKey(anyString())).thenAnswer(call -> Optional.ofNullable(children.get(call.getArgument(0))));
        when(remotes.list(anyString())).thenReturn(ImmutableList.of(
                new WorkspaceRemoteSourceService.WorkspaceRemote("p4", workspace(4).originCloneUrl(), WorkspaceRemoteSourceService.WorkspaceRemote.Role.CUSTOM),
                new WorkspaceRemoteSourceService.WorkspaceRemote("p3", workspace(3).originCloneUrl(), WorkspaceRemoteSourceService.WorkspaceRemote.Role.CUSTOM)));
        when(previews.preview(any(), any())).thenAnswer(call -> {
            PreviewCherryPickRangeCommand request = call.getArgument(0);
            return new CherryPickPreview("preview:" + request.workspaceId(), request.workspaceId(), workspaceData.get(request.workspaceId()).monitoredRepository(),
                    RepoRef.of("test", request.sourceRemote()), request.sourceRemote(), request.targetRemote(), request.targetBranch(), "origin-main",
                    request.sourceBaseRef(), request.sourceTipRef(), BASE,
                    ImmutableList.of(commit(request.sourceTipRef())), ImmutableList.of(), NOW, NOW.plusSeconds(300));
        });
        when(executions.start(any(), anyString(), any(), any())).thenAnswer(call -> {
            StartCherryPickExecutionCommand command = call.getArgument(0);
            String workspace = call.getArgument(1);
            var child = execution(command.idempotencyKey(), workspace, command.expectedSourceTipSha(), false);
            children.put(command.idempotencyKey(), child);
            return child;
        });
        when(delivery.deliverChain(anyString(), anyInt(), any(Boolean.class))).thenAnswer(call -> {
            BackportChain chain = saved.get(call.getArgument(0));
            int index = call.getArgument(1);
            assertThat(chain.hops().get(index).publicationAttempted()).isTrue();
            var hop = chain.hops().get(index);
            int number = hop.route().repositoryId() == 3 ? 3 : 2;
            var execution = children.get(chain.executionKey(index));
            remote.put(number, identity(number, GitHubPullRequestIdentity.State.OPEN, execution.workspace().workBranch()));
            return new WorkspacePullRequestService.PullRequestResult(number, number, "https://github.com/test/p" + number + "/pull/" + number,
                    hop.route().repository(), "main", execution.workspace().workBranch());
        });
    }

    @Test
    void discoveryIdentifiesTheCurrentWorkspaceWithoutStartingWork()
    {
        var catalogue = ImmutableList.of(new WorkspaceRelationships.BackportWorkspace("p4", "OSS", "test/p4", ImmutableList.of()));
        when(relationships.backportWorkspaces()).thenReturn(catalogue);
        var discovery = service().discover(source.id().toString(), turn.id());
        assertThat(discovery.currentWorkspaceId()).isEqualTo("p4");
        assertThat(discovery.workspaces()).isEqualTo(catalogue);
        assertThat(saved).isEmpty();
        verify(executions, never()).start(any(), anyString(), any(), any());
    }

    @Test
    void existingContributorPrStartsSiblingBackportsWithoutWaitingForEachOther()
    {
        when(relationships.list("p2")).thenReturn(ImmutableList.of(new WorkspaceRelationships.Relationship("p4", WorkspaceRelationships.Kind.FORK, "Direct fork", true)));
        var pr = identity(4, GitHubPullRequestIdentity.State.OPEN, "contributor-fix");
        remote.put(4, new GitHubPullRequestIdentity(pr.pullRequest(), pr.url(), RepoRef.of("contributor", "fork"), pr.headBranch(),
                pr.headSha(), pr.baseRepository(), pr.baseBranch(), pr.baseSha(), pr.state(), false, "contributor"));
        var service = service();
        var request = new Request("parallel", Optional.empty(), Optional.empty(), ImmutableList.of("p3", "p2"),
                Optional.of(new BackportChainService.SourcePullRequest("p4", 4)), Optional.of(ImmutableList.of(-1, -1)));
        var chain = service.save(source.id().toString(), turn.id(), request);
        assertThat(chain.sourceTaskId()).isEmpty();
        assertThat(chain.sourcePullRequest()).contains(pr.pullRequest());
        service.advance(chain.id());
        assertThat(children).isEmpty();
        merge(4);
        for (int tick = 0; tick < 8; tick++) {
            service.advance(chain.id());
        }
        assertThat(children).hasSize(2);
        assertThat(current(chain).hops()).allSatisfy(hop -> {
            assertThat(hop.landedSource().orElseThrow().mergeSha()).isEqualTo(P4_SHA);
            assertThat(hop.completed()).isFalse();
            assertThat(hop.pullRequest()).isPresent();
        });
        assertThat(service.save(source.id().toString(), turn.id(), request)).isEqualTo(current(chain));
        assertThatThrownBy(() -> service.save(source.id().toString(), turn.id(),
                new Request("parallel", Optional.empty(), Optional.empty(),
                request.destinations(), request.sourcePullRequest(), Optional.empty()))).hasMessageContaining("saved request changed");
        merge(2);
        service.advance(chain.id());
        assertThat(current(chain).hops().get(1).completed()).isTrue();
        assertThat(current(chain).state()).isEqualTo(State.ACTIVE);
        merge(3);
        service.advance(chain.id());
        service.advance(chain.id());
        assertThat(current(chain).state()).isEqualTo(State.COMPLETED);
        verify(executions, times(2)).start(any(), anyString(), any(), any());
    }

    @Test
    void rejectsForwardDependenciesAndChangedSourcePrBeforeDispatch()
    {
        var service = service();
        var external = Optional.of(new BackportChainService.SourcePullRequest("p4", 4));
        assertThatThrownBy(() -> service.save(source.id().toString(), turn.id(),
                new Request("cycle", Optional.empty(), Optional.empty(),
                ImmutableList.of("p3", "p2"), external, Optional.of(ImmutableList.of(1, 0))))).hasMessageContaining("earlier hop");
        assertThat(saved).isEmpty();
        var request = new Request("existing", Optional.empty(), Optional.empty(), ImmutableList.of("p3", "p2"), external, Optional.empty());
        var chain = service.save(source.id().toString(), turn.id(), request);
        assertThatThrownBy(() -> service.save(source.id().toString(), turn.id(),
                new Request("existing", Optional.empty(), Optional.empty(),
                request.destinations(), Optional.of(new BackportChainService.SourcePullRequest("p4", 99)), Optional.empty())))
                .hasMessageContaining("source PR changed");
        when(relationships.list("p3")).thenReturn(ImmutableList.of());
        service.advance(chain.id());
        assertThat(current(chain).state()).isEqualTo(State.BLOCKED);
        verify(executions, never()).start(any(), anyString(), any(), any());
    }

    @ParameterizedTest
    @CsvSource({"false", "true"})
    void waitsForEveryMergeAndUsesP3LandedChangesForP2(boolean alreadyMerged)
    {
        var service = service();
        if (alreadyMerged) {
            merge(4);
        }
        var chain = save(service, "p3", "p2");
        service.advance(chain.id()); // binds the source's actual PR
        if (!alreadyMerged) {
            service.advance(chain.id());
            assertThat(children).isEmpty();
            merge(4);
        }
        service.advance(chain.id()); // saves landed source
        service.advance(chain.id()); // starts child
        service.advance(chain.id()); // publishes
        for (int tick = 0; tick < 3; tick++) {
            service.advance(chain.id());
        }
        assertThat(children).hasSize(1);
        merge(3);
        service.advance(chain.id());
        service.advance(chain.id()); // resolves the P3 merge afresh
        assertThat(current(chain).hops().get(1).landedSource().orElseThrow().mergeSha()).isEqualTo(P3_SHA);
        service.advance(chain.id());
        service.advance(chain.id());
        assertThat(current(chain).state()).isEqualTo(State.ACTIVE);
        merge(2);
        service.advance(chain.id());
        service.advance(chain.id());
        assertThat(current(chain).state()).isEqualTo(State.COMPLETED);
        assertThat(children.values()).anySatisfy(child -> {
            assertThat(child.workspace().workspaceId()).isEqualTo("p2");
            assertThat(child.range().sourceTipSha()).isEqualTo(P3_SHA);
        });
        verify(delivery, times(2)).deliverChain(anyString(), anyInt(), eq(false));
    }

    @ParameterizedTest
    @CsvSource({"p3,false", "p3,true", "p2,false", "p2,true"})
    void directRoutesCanBeRequestedBeforePublicationOrAfterMerge(String destination, boolean alreadyMerged)
    {
        when(relationships.list(destination)).thenReturn(ImmutableList.of(new WorkspaceRelationships.Relationship("p4", WorkspaceRelationships.Kind.FORK, "Direct fork", false)));
        var service = service();
        if (alreadyMerged) {
            merge(4);
        }
        else {
            when(prs.fetchPullRequestIdentities(anyString(), any(), any(), anyString())).thenReturn(ImmutableList.of());
        }
        var chain = save(service, destination);
        service.advance(chain.id());
        if (!alreadyMerged) {
            assertThat(current(chain).sourcePullRequest()).isEmpty();
            assertThat(children).isEmpty();
            merge(4);
            when(prs.fetchPullRequestIdentities(anyString(), any(), any(), anyString())).thenReturn(ImmutableList.of(remote.get(4)));
        }
        for (int tick = 0; tick < 5; tick++) {
            service.advance(chain.id());
        }
        assertThat(current(chain).state()).isEqualTo(State.ACTIVE);
        assertThat(current(chain).hops().getFirst().pullRequest()).isPresent();
        merge(destination.equals("p3") ? 3 : 2);
        service.advance(chain.id());
        service.advance(chain.id());
        assertThat(current(chain).state()).isEqualTo(State.COMPLETED);
        assertThat(children).hasSize(1);
        verify(delivery).deliverChain(chain.id(), 0, false);
    }

    @Test
    void p2UsesOnlyItsLinkedP4FixAndNeverItsInvestigationOrOwnPr()
    {
        var id = TaskId.random();
        var owner = AgentTask.created(id, true, "Private request", TitleSource.USER, AgentLaneId.CODEX_CLI,
                new TaskWorktree(id, "p2", new RepositoryIdentity("test", "p2"), Path.of("/tmp", id.toString()), "bytequay/task/" + id, BASE),
                source.defaults(), NOW);
        var accepted = AgentTurn.accepted(TurnId.random(), id, "user", turn.settings().orElseThrow(), NOW);
        when(related.eligible(id.toString())).thenReturn(Optional.of(owner));
        when(turns.findActive(id)).thenReturn(Optional.of(accepted));
        when(related.list("p2")).thenReturn(ImmutableList.of(linked(owner, accepted, "INVESTIGATE")));
        var service = service();
        var request = new Request("linked-fix", Optional.of(source.id().toString()), Optional.empty(), ImmutableList.of("p3", "p2"), Optional.empty(), Optional.empty());
        assertThatThrownBy(() -> service.save(id.toString(), accepted.id(), request)).hasMessageContaining("linked fix");
        assertThat(saved).isEmpty();
        when(related.list("p2")).thenReturn(ImmutableList.of(linked(owner, accepted, "FIX")));
        var chain = service.save(id.toString(), accepted.id(), request);
        assertThat(chain.workspaceId()).isEqualTo("p2");
        assertThat(chain.source().workspaceId()).isEqualTo("p4");
        assertThat(chain.sourceBranch()).isEqualTo(source.taskWorktree().orElseThrow().generatedBranch());
        service.advance(chain.id());
        assertThat(current(chain).sourcePullRequest()).contains(remote.get(4).pullRequest());
        assertThat(service.list("p4")).isEmpty();
        verify(relationships).requireRelated("p2", "p4");
        verify(relationships, never()).requireRelated("p4", "p2");
        merge(4);
        for (int tick = 0; tick < 3; tick++) {
            service.advance(chain.id());
        }
        assertThat(children).hasSize(1);
        merge(3);
        for (int tick = 0; tick < 4; tick++) {
            service.advance(chain.id());
        }
        assertThat(current(chain).hops().get(1).landedSource().orElseThrow().mergeSha()).isEqualTo(P3_SHA);
        merge(2);
        service.advance(chain.id());
        service.advance(chain.id());
        assertThat(current(chain).state()).isEqualTo(State.COMPLETED);
        assertThat(children).hasSize(2);
    }

    private RelatedWorkspaceWork.View linked(AgentTask owner, AgentTurn accepted, String purpose)
    {
        return new RelatedWorkspaceWork.View("p2", "test/p2", owner.id().toString(), owner.title(), accepted.id().toString(), source.id().toString(),
                "p4", source.title(), "test/p4", "FORK", purpose, "Check upstream", "main", Optional.of(P4_SHA), "FINISHED",
                Optional.empty(), Optional.of("Finding and reproduction"), Optional.empty(), NOW, false);
    }

    @Test
    void frozenRouteReplayAppendAndRootAuthority()
    {
        var service = service();
        var chain = save(service, "test/p3");
        assertThat(save(service, "test/p3")).isEqualTo(chain);
        assertThatThrownBy(() -> save(service, "p2")).hasMessageContaining("saved request changed");
        var appended = service.save(source.id().toString(), turn.id(),
                new Request("append", Optional.of(source.id().toString()), Optional.of(chain.id()), ImmutableList.of("p2"), Optional.empty(), Optional.empty()));
        assertThat(appended.hops()).hasSize(2);
        assertThat(appended.hops().getFirst()).isEqualTo(chain.hops().getFirst());
        assertThatThrownBy(() -> service.save(source.id().toString(), TurnId.random(),
                new Request("wrong", Optional.of(source.id().toString()), Optional.empty(), ImmutableList.of("p3"), Optional.empty(), Optional.empty())))
                .hasMessageContaining("no longer active");
        when(relationships.list("p3")).thenReturn(ImmutableList.of(new WorkspaceRelationships.Relationship("p4", WorkspaceRelationships.Kind.DEPENDENCY, "Library", false)));
        assertThatThrownBy(() -> service.save(source.id().toString(), turn.id(),
                new Request("dependency", Optional.of(source.id().toString()), Optional.empty(), ImmutableList.of("p3"), Optional.empty(), Optional.empty())))
                .hasMessageContaining("Fork");
    }

    @Test
    void adoptsPersistedChildAfterLostStartResponseAndRestart()
    {
        var service = service();
        var chain = save(service, "p3");
        merge(4);
        service.advance(chain.id());
        service.advance(chain.id());
        doAnswer(call -> {
            StartCherryPickExecutionCommand command = call.getArgument(0);
            children.put(command.idempotencyKey(), execution(command.idempotencyKey(), "p3", P4_SHA, false));
            throw new IllegalStateException("response lost after child insertion");
        }).when(executions).start(any(), anyString(), any(), any());
        service.advance(chain.id());
        assertThat(current(chain).state()).isEqualTo(State.BLOCKED);
        var restarted = service();
        restarted.resume("p4", chain.id());
        restarted.advance(chain.id());
        assertThat(current(chain).hops().getFirst().executionId()).isPresent();
        verify(executions).start(any(), anyString(), any(), any());
    }

    @Test
    void uncertainPublicationNeverReplaysItsWrite()
    {
        var service = service();
        var chain = ready(service, "p3");
        doThrow(new IllegalStateException("lost PR response")).when(delivery).deliverChain(chain.id(), 0, false);
        service.advance(chain.id());
        assertThat(current(chain).state()).isEqualTo(State.BLOCKED);
        var restarted = service();
        restarted.resume("p4", chain.id());
        restarted.advance(chain.id());
        verify(delivery).deliverChain(chain.id(), 0, true);
        verify(delivery).deliverChain(chain.id(), 0, false);
    }

    @Test
    void removedRelationshipPreservesChildrenAndBlocksPublication()
    {
        var service = service();
        var chain = ready(service, "p3");
        when(relationships.list("p3")).thenReturn(ImmutableList.of());
        service.advance(chain.id());
        assertThat(current(chain).state()).isEqualTo(State.BLOCKED);
        assertThat(children).hasSize(1);
        verify(delivery, never()).deliverChain(anyString(), anyInt(), any(Boolean.class));
        assertThatThrownBy(() -> service.resume("p4", chain.id())).hasMessageContaining("Fork");
    }

    @Test
    void concurrentTicksAndStopDoNotDispatchAfterRevocation()
            throws Exception
    {
        var service = service();
        var chain = ready(service, "p3");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        doAnswer(_ -> {
            entered.countDown();
            checkState(finish.await(5, TimeUnit.SECONDS), "test did not release delivery");
            throw new IllegalStateException("publication result unknown");
        }).when(delivery).deliverChain(chain.id(), 0, false);
        var advancing = CompletableFuture.runAsync(() -> service.advance(chain.id()));
        assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
        service.advance(chain.id()); // concurrent monitor tick must not duplicate dispatch
        var stopping = CompletableFuture.supplyAsync(() -> service.stop("p4", chain.id()));
        finish.countDown();
        advancing.get(5, TimeUnit.SECONDS);
        assertThat(stopping.get(5, TimeUnit.SECONDS).state()).isEqualTo(State.STOPPING);
        service.advance(chain.id());
        service.advance(chain.id());
        assertThat(current(chain).state()).isEqualTo(State.STOPPED);
        verify(delivery).deliverChain(chain.id(), 0, false);
    }

    @Test
    void finalNoOpCreatesNoPrAndIntermediateNoOpNeedsDestinationSelection()
    {
        var service = service();
        var chain = ready(service, "p3");
        children.put(chain.executionKey(0), execution(chain.executionKey(0), "p3", P4_SHA, true));
        // Preserve the same child identity for a legitimate no-op result.
        var child = children.get(chain.executionKey(0));
        saved.put(chain.id(), current(chain).withHop(0, current(chain).hops().getFirst().withExecution(child.id().toString()), NOW));
        service.advance(chain.id());
        service.advance(chain.id());
        assertThat(current(chain).state()).isEqualTo(State.COMPLETED);
        assertThat(current(chain).hops().getFirst().noOp()).isTrue();
        verify(delivery, never()).deliverChain(anyString(), anyInt(), any(Boolean.class));
        var appended = service.save(source.id().toString(), turn.id(),
                new Request("after-noop", Optional.of(source.id().toString()), Optional.of(chain.id()), ImmutableList.of("p2"), Optional.empty(), Optional.empty()));
        service.advance(appended.id());
        assertThat(current(chain).state()).isEqualTo(State.BLOCKED);
        assertThat(current(chain).reason()).get().asString().contains("Select landed commits");
    }

    @Test
    void resumeDoesNotReviveStoppedCiOrPublishAnotherPr()
    {
        var service = service();
        var chain = ready(service, "p3");
        service.advance(chain.id());
        var repo = RepoRef.of("test", "p3");
        var stopped = CiAutofixRun.create(CiAutofixRunId.random(), "cherry-pick-delivery-ci:" + children.get(chain.executionKey(0)).id(),
                new CiAutofixRun.Scope(PullRequestRef.of("test", "p3", 3), repo, "p3", repo, "test", "backport-p3", "main", "codex", Optional.empty()),
                TARGET, TARGET, BASE, new CiAutofixRun.Authorization(NOW), NOW)
                .finish(CiAutofixRun.FinishReason.CANCELLED, "CI cancelled; inspect retained work", NOW);
        when(ciStore.findByIdempotencyKey(anyString())).thenReturn(Optional.of(stopped));
        service.advance(chain.id());
        assertThat(current(chain).state()).isEqualTo(State.BLOCKED);
        service.resume("p4", chain.id());
        service.advance(chain.id());
        assertThat(current(chain).state()).isEqualTo(State.BLOCKED);
        assertThat(current(chain).reason()).contains("CI cancelled; inspect retained work");
        verify(delivery).deliverChain(chain.id(), 0, false);
        verify(executions).start(any(), anyString(), any(), any());
        org.mockito.Mockito.verifyNoInteractions(ci);
    }

    @Test
    void explicitLandedSelectionRequiresCurrentVersionAndResume()
    {
        var service = service();
        var chain = save(service, "p3");
        merge(4);
        doThrow(new IllegalStateException("Select commits for ambiguous history")).when(landed).resolve(anyString(), anyString(), any());
        service.advance(chain.id());
        service.advance(chain.id());
        assertThat(current(chain).sourceMerged()).isTrue();
        var request = new BackportChainService.SelectSource(current(chain).version(), 0, P4_SHA, P4_SHA);
        when(prs.fetchMergedPullRequest(anyString(), any())).thenReturn(new GitHubPullRequestReadClient.MergedPullRequest(remote.get(4), 4, NOW, P4_SHA, ImmutableList.of(P4_SHA)));
        when(previews.preview(any())).thenReturn(new CherryPickPreview("manual", "p3", workspace(3).monitoredRepository(), RepoRef.of("test", "p4"),
                "p4", "origin", "main", "origin-main", BASE, P4_SHA, BASE, ImmutableList.of(commit(P4_SHA)), ImmutableList.of(), NOW, NOW.plusSeconds(300)));
        when(git.run(any(), eq("merge-base"), eq("--is-ancestor"), eq(P4_SHA), anyString())).thenReturn(new GitCommandRunner.Result(1, "", false));
        assertThatThrownBy(() -> service.selectSource("p4", chain.id(), request)).hasMessageContaining("not landed");
        when(git.run(any(), eq("merge-base"), eq("--is-ancestor"), eq(P4_SHA), anyString())).thenReturn(new GitCommandRunner.Result(0, "", false));
        var selected = service.selectSource("p4", chain.id(), request);
        assertThat(selected.state()).isEqualTo(State.BLOCKED);
        assertThat(children).isEmpty();
        assertThat(selected.hops().getFirst().selection().orElseThrow().commits()).containsExactly(P4_SHA);
        assertThatThrownBy(() -> service.selectSource("p4", chain.id(), request)).hasMessageContaining("reload");
        service.resume("p4", chain.id());
        service.advance(chain.id());
        assertThat(children).hasSize(1);
    }

    @Test
    void closedSourceAndUnavailableDestinationBlockWithoutStartingWork()
    {
        var service = service();
        var chain = save(service, "p3");
        remote.put(4, identity(4, GitHubPullRequestIdentity.State.CLOSED, chain.sourceBranch()));
        service.advance(chain.id());
        assertThat(current(chain).reason()).get().asString().contains("closed without merging");
        assertThat(children).isEmpty();
        workspaceData.remove("p3");
        assertThatThrownBy(() -> service.resume("p4", chain.id())).hasMessageContaining("unavailable");
    }

    private BackportChain ready(BackportChainService service, String... destinations)
    {
        var chain = save(service, destinations);
        merge(4);
        service.advance(chain.id());
        service.advance(chain.id());
        service.advance(chain.id());
        return chain;
    }

    private BackportChain save(BackportChainService service, String... destinations)
    {
        return service.save(source.id().toString(), turn.id(),
                new Request("route", Optional.of(source.id().toString()), Optional.empty(), ImmutableList.copyOf(destinations), Optional.empty(), Optional.empty()));
    }

    private BackportChain current(BackportChain chain)
    {
        return saved.get(chain.id());
    }

    private void merge(int number)
    {
        var pr = remote.get(number);
        remote.put(number, new GitHubPullRequestIdentity(pr.pullRequest(), pr.url(), pr.headRepository(), pr.headBranch(), pr.headSha(),
                pr.baseRepository(), pr.baseBranch(), pr.baseSha(), GitHubPullRequestIdentity.State.MERGED, pr.draft(), pr.author()));
    }

    private BackportChainService service()
    {
        return new BackportChainService(chains, workspaces, tasks, turns, related, relationships, repositories, remotes, models, prs,
                credentials, landed, previews, executions, executionStore, delivery, ciStore, ci, Clock.fixed(NOW, ZoneOffset.UTC), git);
    }

    private static Workspace workspace(int number)
    {
        var repo = RepoRef.of("test", "p" + number);
        return new Workspace("p" + number, "Project " + number, number, repo, Optional.empty(), Optional.empty(), number, repo, Workspace.Topology.DIRECT,
                "main", "https://github.com/" + repo.fullName() + ".git", Optional.empty(), Optional.empty(), "/tmp/p" + number,
                Optional.of(NOW), Optional.empty(), 0, NOW, NOW);
    }

    private static GitHubPullRequestIdentity identity(int number, GitHubPullRequestIdentity.State state, String branch)
    {
        var repo = RepoRef.of("test", "p" + number);
        return new GitHubPullRequestIdentity(PullRequestRef.of("test", "p" + number, number), "https://github.com/" + repo.fullName() + "/pull/" + number,
                repo, branch, TARGET, repo, "main", BASE, state, false, "test");
    }

    private static com.bytequay.app.cherrypick.CherryPickCommitPreview commit(String sha)
    {
        return new com.bytequay.app.cherrypick.CherryPickCommitPreview(1, sha, BASE, "Fix", "test", NOW, ImmutableList.of("file"),
                false, Optional.empty(), false, com.bytequay.app.cherrypick.ConflictPreview.clean(), Optional.empty());
    }

    private static CherryPickExecution execution(String key, String workspace, String sourceSha, boolean empty)
    {
        var repo = RepoRef.of("test", workspace);
        var item = CherryPickItem.planned(1, sourceSha, BASE, "Fix", Optional.empty(), CherryPickItem.Disposition.TO_CHERRY_PICK);
        var result = CherryPickExecution.create(CherryPickExecutionId.random(), key,
                new CherryPickExecution.Workspace(workspace, repo, repo, sourceSha.equals(P4_SHA) ? "p4" : "p3", "origin", "main", "origin-main",
                        "backport-" + workspace, Path.of("/tmp", workspace, "work"), "codex",
                        Optional.of(RepoRef.of("test", sourceSha.equals(P4_SHA) ? "p4" : "p3")), Optional.of("model"), Optional.of("high")),
                Optional.of(new CherryPickExecution.PullRequestDraft("Backport test/" + (sourceSha.equals(P4_SHA) ? "p4#4" : "p3#3"),
                        "Backport of test/" + (sourceSha.equals(P4_SHA) ? "p4#4" : "p3#3") + "\n\nLanded source: " + sourceSha)),
                new Range(BASE, sourceSha, BASE), ImmutableList.of(item), NOW);
        result = empty ? result.recordEmptySkipped(sourceSha, NOW) : result.recordApplied(sourceSha, TARGET, NOW);
        return result.finish(FinishReason.COMPLETED, empty ? "Already present" : "Complete", NOW);
    }
}
