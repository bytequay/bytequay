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
package com.bytequay.app.workspace;

import com.bytequay.app.ci.CiAutofixService;
import com.bytequay.app.repository.github.CreatedPullRequest;
import com.bytequay.app.repository.github.GitHubAccountClient;
import com.bytequay.app.repository.github.GitHubPullRequestIdentity;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.app.repository.github.GitHubPullRequestWriteClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.workspace.WorkspacePullRequestService.PullRequestCommand;
import com.bytequay.app.workspace.WorkspacePullRequestService.PullRequestResult;
import com.bytequay.app.workspace.WorkspacePullRequestService.PushCommand;
import com.bytequay.core.backport.BackportChain;
import com.bytequay.core.backport.BackportChainStore;
import com.bytequay.core.cherrypick.CherryPickExecution;
import com.bytequay.core.cherrypick.CherryPickExecution.FinishReason;
import com.bytequay.core.cherrypick.CherryPickExecution.PullRequestDraft;
import com.bytequay.core.cherrypick.CherryPickExecution.Range;
import com.bytequay.core.cherrypick.CherryPickExecutionId;
import com.bytequay.core.cherrypick.CherryPickExecutionStore;
import com.bytequay.core.cherrypick.CherryPickItem;
import com.bytequay.core.cherrypick.CherryPickItem.Disposition;
import com.bytequay.core.ci.CiAutofixStore;
import com.bytequay.core.domain.FetchedPullRequest;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.UserRepo;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.domain.Workspace.Topology;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestWorkspacePullRequestService
{
    private static final Instant NOW = Instant.parse("2026-08-22T00:00:00Z");
    private static final String SHA = "1111111111111111111111111111111111111111";
    private static final String PARENT_SHA = "2222222222222222222222222222222222222222";
    private static final String WORK_BRANCH = "bump-1-2-ab";

    @TempDir
    private Path temporaryDirectory;

    @Test
    void rejectsDependencyDeliveryWithoutHidingStatusOrDiscardingWork()
    {
        Fixture fixture = fixture(directWorkspace(), directRepository(true));
        doThrow(new IllegalArgumentException("Dependency transfer blocked")).when(fixture.relationships())
                .requireTransferAllowed(org.mockito.ArgumentMatchers.<Optional<RepoRef>>any(), any());
        assertThatThrownBy(() -> fixture.service().push(new PushCommand("workspace", fixture.executionId(), true)))
                .hasMessage("Dependency transfer blocked");
        assertThatThrownBy(() -> fixture.service().createPullRequest(new PullRequestCommand("workspace", fixture.executionId(), true)))
                .hasMessage("Dependency transfer blocked");
        fixture.service().getStatus("workspace", fixture.executionId());
        verifyNoInteractions(fixture.git(), fixture.pullRequests());
    }

    @Test
    void directDeliveryPushesOriginAndCreatesPullRequestInSelectedRepository()
    {
        Fixture fixture = fixture(directWorkspace(), directRepository(true));

        fixture.service().push(new PushCommand("workspace", fixture.executionId(), true));
        PullRequestResult result = fixture.service().createPullRequest(
                new PullRequestCommand("workspace", fixture.executionId(), true));
        PullRequestResult repeated = fixture.service().createPullRequest(
                new PullRequestCommand("workspace", fixture.executionId(), true));

        verify(fixture.git()).pushBranch(fixture.workspace(), WORK_BRANCH, PARENT_SHA);
        verify(fixture.pullRequests(), times(1)).createPullRequest(
                "token", RepoRef.of("owner", "project"), WORK_BRANCH, "main", "PR title", "PR body", false);
        verify(fixture.pullRequestCatalog(), times(2))
                .observeWorkspacePullRequest(RepoRef.of("owner", "project"), 12);
        verify(fixture.ciAutofix(), times(2)).start(argThat(command ->
                command.workspaceId().equals("workspace")
                        && command.pullRequestNumber() == 12
                        && command.laneId().toString().equals("lane")));
        assertThat(result).isEqualTo(repeated);
        assertThat(result.providerId()).isEqualTo(900);
        assertThat(result.url()).isEqualTo("https://github.com/owner/project/pull/12");
    }

    @Test
    void forkDeliveryPushesForkOriginAndTargetsUpstream()
    {
        Fixture fixture = fixture(forkWorkspace(), forkRepository(true));

        fixture.service().push(new PushCommand("workspace", fixture.executionId(), true));
        PullRequestResult result = fixture.service().createPullRequest(
                new PullRequestCommand("workspace", fixture.executionId(), true));

        verify(fixture.git()).pushBranch(fixture.workspace(), WORK_BRANCH, PARENT_SHA);
        verify(fixture.pullRequests()).createPullRequest(
                "token",
                RepoRef.of("upstream", "project"),
                "contributor:" + WORK_BRANCH,
                "main",
                "PR title",
                "PR body",
                false);
        verify(fixture.pullRequestCatalog())
                .observeWorkspacePullRequest(RepoRef.of("upstream", "project"), 12);
        verify(fixture.ciAutofix()).start(any());
        assertThat(result.baseRepository()).isEqualTo(RepoRef.of("upstream", "project"));
        assertThat(result.head()).isEqualTo("contributor:" + WORK_BRANCH);
    }

    @Test
    void forkDeliveryKeepsTheConfirmedReleaseBranchInsteadOfTheCurrentDefault()
    {
        Fixture fixture = fixture(forkWorkspace(), forkRepository(true), "release/478");
        assertThat(fixture.workspace().upstreamDefaultBranch()).contains("main");
        assertThat(fixture.service().getStatus("workspace", fixture.executionId()).baseBranch()).isEqualTo("release/478");

        assertThat(fixture.service().push(new PushCommand("workspace", fixture.executionId(), true)).remote()).isEqualTo("origin");
        PullRequestResult result = fixture.service().createPullRequest(new PullRequestCommand("workspace", fixture.executionId(), true));

        verify(fixture.git()).pushBranch(fixture.workspace(), WORK_BRANCH, PARENT_SHA);
        verify(fixture.pullRequests()).createPullRequest(
                "token", RepoRef.of("upstream", "project"), "contributor:" + WORK_BRANCH, "release/478", "PR title", "PR body", false);
        assertThat(result.baseRepository()).isEqualTo(RepoRef.of("upstream", "project"));
        assertThat(result.baseBranch()).isEqualTo("release/478");
        assertThat(fixture.service().getStatus("workspace", fixture.executionId()).baseBranch()).isEqualTo("release/478");
    }

    @Test
    void retriesLocalActivationWithoutCreatingASecondRemotePullRequest()
    {
        Fixture fixture = fixture(directWorkspace(), directRepository(true));
        fixture.service().push(new PushCommand("workspace", fixture.executionId(), true));
        doThrow(new RuntimeException("projection failed"))
                .doNothing()
                .when(fixture.pullRequestCatalog())
                .observeWorkspacePullRequest(RepoRef.of("owner", "project"), 12);

        assertThatThrownBy(() -> fixture.service().createPullRequest(
                new PullRequestCommand("workspace", fixture.executionId(), true)))
                .hasMessageContaining("projection failed");
        PullRequestResult retried = fixture.service().createPullRequest(
                new PullRequestCommand("workspace", fixture.executionId(), true));

        assertThat(retried.number()).isEqualTo(12);
        verify(fixture.pullRequests(), times(1)).createPullRequest(
                any(), any(), any(), any(), any(), any(), anyBoolean());
        verify(fixture.ciAutofix(), times(1)).start(any());
    }

    @Test
    void readOnlyRepositoryDisablesAndRejectsPushWithoutWrites()
    {
        Fixture fixture = fixture(forkWorkspace(), forkRepository(false));

        assertThat(fixture.service().getStatus("workspace", fixture.executionId()).pushWritable()).isFalse();
        assertThatThrownBy(() -> fixture.service().push(
                new PushCommand("workspace", fixture.executionId(), true)))
                .isInstanceOfSatisfying(WorkspaceException.class,
                        failure -> assertThat(failure.code()).isEqualTo("WORKSPACE_PUSH_NOT_WRITABLE"));

        verifyNoInteractions(fixture.git(), fixture.pullRequests());
    }

    @Test
    void eachWriteRequiresItsOwnConfirmationAndRepeatedPushIsIdempotent()
    {
        Fixture fixture = fixture(directWorkspace(), directRepository(true));

        assertThatThrownBy(() -> fixture.service().push(
                new PushCommand("workspace", fixture.executionId(), false)))
                .isInstanceOfSatisfying(WorkspaceException.class,
                        failure -> assertThat(failure.code()).isEqualTo("DELIVERY_CONFIRMATION_REQUIRED"));
        fixture.service().push(new PushCommand("workspace", fixture.executionId(), true));
        fixture.service().push(new PushCommand("workspace", fixture.executionId(), true));
        assertThatThrownBy(() -> fixture.service().createPullRequest(
                new PullRequestCommand("workspace", fixture.executionId(), false)))
                .isInstanceOfSatisfying(WorkspaceException.class,
                        failure -> assertThat(failure.code()).isEqualTo("DELIVERY_CONFIRMATION_REQUIRED"));

        verify(fixture.git(), times(1)).pushBranch(fixture.workspace(), WORK_BRANCH, PARENT_SHA);
        verify(fixture.pullRequests(), never()).createPullRequest(any(), any(), any(), any(), any(), any(), anyBoolean());
    }

    @Test
    void missingCodeTimestampTriggersSyncBeforeStatus()
    {
        Workspace unsynced = directWorkspace().withCodeSyncAt(Optional.empty(), NOW);
        Fixture fixture = fixture(unsynced, directRepository(true));

        assertThat(fixture.service().getStatus("workspace", fixture.executionId()).pushWritable()).isTrue();

        verify(fixture.provisioning()).synchronizeCode("workspace");
    }

    private Fixture fixture(Workspace workspace, UserRepo selected)
    {
        return fixture(workspace, selected, "main");
    }

    @Test
    void chainPublicationUsesSavedAuthorityAndStopDuringPushPreventsPrCreation()
    {
        Fixture fixture = fixture(directWorkspace(), directRepository(true));
        var chain = chain(fixture);
        AtomicReference<BackportChain> saved = new AtomicReference<>(chain);
        when(fixture.chains().find(chain.id())).thenAnswer(_ -> Optional.of(saved.get()));
        org.mockito.Mockito.doAnswer(_ -> {
            saved.set(chain.withState(BackportChain.State.STOPPING, Optional.of("Stop"), NOW));
            return null;
        }).when(fixture.git()).pushBranch(any(), any(), any());
        assertThatThrownBy(() -> fixture.service().deliverChain(chain.id(), 0, false)).hasMessageContaining("stopped or blocked");
        verify(fixture.pullRequests(), never()).createPullRequest(any(), any(), any(), any(), any(), any(), anyBoolean());
        verifyNoInteractions(fixture.ciAutofix());
    }

    @Test
    void uncertainPublicationAdoptsExactRemoteProofWithoutWritingAgain()
    {
        Fixture fixture = fixture(directWorkspace(), directRepository(true));
        var chain = chain(fixture);
        var repo = fixture.workspace().monitoredRepository();
        when(fixture.git().remoteBranchSha(any(), any())).thenReturn(Optional.of(PARENT_SHA));
        when(fixture.reads().fetchPullRequestIdentities(any(), any(), any(), any())).thenReturn(ImmutableList.of(
                new GitHubPullRequestIdentity(PullRequestRef.of(repo.owner(), repo.repo(), 12), "https://github.com/owner/project/pull/12",
                        repo, WORK_BRANCH, PARENT_SHA, repo, "main", SHA, GitHubPullRequestIdentity.State.OPEN, false, "owner")));
        when(fixture.reads().fetchPullRequestsByHead(any(), any(), any())).thenReturn(ImmutableList.of(
                new FetchedPullRequest(900L, repo.fullName(), 12, "Backport", "owner", "https://github.com/owner/project/pull/12", NOW, NOW,
                        PullRequest.Origin.AUTHORED, ImmutableList.of(), ImmutableMap.of(), false, "open", null, null, ImmutableList.of(), true, "clean", WORK_BRANCH)));
        assertThat(fixture.service().deliverChain(chain.id(), 0, true).number()).isEqualTo(12);
        assertThat(fixture.service().getStatus("workspace", fixture.executionId()).pullRequest()).isPresent();
        verify(fixture.git(), never()).pushBranch(any(), any(), any());
        verifyNoInteractions(fixture.pullRequests());
        verify(fixture.ciAutofix()).start(any());
    }

    @Test
    void stopDuringPrCreationKeepsItsRecordAndRecoverySkipsCiAfterMerge()
    {
        Fixture fixture = fixture(directWorkspace(), directRepository(true));
        var chain = chain(fixture);
        var saved = new AtomicReference<>(chain);
        when(fixture.chains().find(chain.id())).thenAnswer(_ -> Optional.of(saved.get()));
        when(fixture.pullRequests().createPullRequest(any(), any(), any(), any(), any(), any(), anyBoolean())).thenAnswer(_ -> {
            saved.set(chain.withState(BackportChain.State.STOPPING, Optional.of("Stop"), NOW));
            return new CreatedPullRequest(900, 12, "https://github.com/owner/project/pull/12");
        });
        assertThatThrownBy(() -> fixture.service().deliverChain(chain.id(), 0, false)).hasMessageContaining("stopped or blocked");
        verifyNoInteractions(fixture.ciAutofix());
        saved.set(chain);
        var repo = fixture.workspace().monitoredRepository();
        when(fixture.reads().fetchPullRequestIdentity(any(), any())).thenReturn(new GitHubPullRequestIdentity(
                PullRequestRef.of(repo.owner(), repo.repo(), 12), "https://github.com/owner/project/pull/12",
                repo, WORK_BRANCH, PARENT_SHA, repo, "main", SHA, GitHubPullRequestIdentity.State.MERGED, false, "owner"));
        assertThat(fixture.service().deliverChain(chain.id(), 0, true).number()).isEqualTo(12);
        verify(fixture.pullRequests()).createPullRequest(any(), any(), any(), any(), any(), any(), anyBoolean());
        verifyNoInteractions(fixture.ciAutofix());
    }

    @Test
    void uncertainMissingOrChangedRemoteNeverRetriesPushOrPr()
    {
        Fixture fixture = fixture(directWorkspace(), directRepository(true));
        var chain = chain(fixture);
        when(fixture.git().remoteBranchSha(any(), any())).thenReturn(Optional.empty(), Optional.of(SHA));
        assertThatThrownBy(() -> fixture.service().deliverChain(chain.id(), 0, true)).hasMessageContaining("Remote result unknown");
        assertThatThrownBy(() -> fixture.service().deliverChain(chain.id(), 0, true)).hasMessageContaining("Remote result unknown");
        verify(fixture.git(), never()).pushBranch(any(), any(), any());
        verifyNoInteractions(fixture.pullRequests(), fixture.reads(), fixture.ciAutofix());
    }

    private BackportChain chain(Fixture fixture)
    {
        Workspace source = workspace("source", 500, RepoRef.of("source", "project"), Optional.empty(), Optional.empty(),
                500, RepoRef.of("source", "project"), Topology.DIRECT, "https://github.com/source/project.git", Optional.empty());
        when(fixture.workspaceStore().findById("source")).thenReturn(Optional.of(source));
        when(fixture.relationships().list("workspace")).thenReturn(ImmutableList.of(
                new WorkspaceRelationships.Relationship("source", WorkspaceRelationships.Kind.FORK, "Fork", false)));
        var chain = new BackportChain("fixture-chain", "workspace", "request", Optional.of("source-task"), "source-branch", BackportChain.Route.of(source), Optional.empty(), false,
                ImmutableList.of(new BackportChain.Acceptance("key", "turn", ImmutableList.of("workspace"), ImmutableList.of(-1))),
                ImmutableList.of(new BackportChain.Hop(-1, BackportChain.Route.of(fixture.workspace()), "lane", Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(fixture.executionId().toString()), true, Optional.empty(), false, false)),
                BackportChain.State.ACTIVE, Optional.empty(), 0, NOW, NOW);
        when(fixture.chains().find(chain.id())).thenReturn(Optional.of(chain));
        return chain;
    }

    private Fixture fixture(Workspace workspace, UserRepo selected, String targetBranch)
    {
        WorkspaceStore workspaceStore = mock(WorkspaceStore.class);
        AtomicReference<Workspace> storedWorkspace = new AtomicReference<>(workspace);
        when(workspaceStore.findById("workspace")).thenAnswer(ignored -> Optional.of(storedWorkspace.get()));
        CherryPickExecution initial = completedExecution(workspace, targetBranch);
        AtomicReference<CherryPickExecution> current = new AtomicReference<>(initial);
        CherryPickExecutionStore executionStore = mock(CherryPickExecutionStore.class);
        when(executionStore.find(initial.id())).thenAnswer(ignored -> Optional.of(current.get()));
        when(executionStore.save(any())).thenAnswer(invocation -> {
            CherryPickExecution saved = invocation.getArgument(0);
            CherryPickExecution versioned = saved.withVersion(saved.version() + 1);
            current.set(versioned);
            return versioned;
        });
        WorkspaceProvisioningService provisioning = mock(WorkspaceProvisioningService.class);
        when(provisioning.synchronizeCode("workspace")).thenAnswer(ignored -> {
            Workspace synced = storedWorkspace.get().withCodeSyncAt(Optional.of(NOW), NOW);
            storedWorkspace.set(synced);
            return WorkspaceCodeSyncResult.success(synced, ImmutableSet.of());
        });
        GitHubAccountClient repositories = mock(GitHubAccountClient.class);
        when(repositories.fetchRepository("token", workspace.selectedRepositoryId()))
                .thenReturn(Optional.of(selected));
        when(repositories.fetchRepository("token", 300L)).thenReturn(Optional.of(upstreamRepository()));
        GitHubPullRequestWriteClient pullRequests = mock(GitHubPullRequestWriteClient.class);
        when(pullRequests.createPullRequest(any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new CreatedPullRequest(
                        900,
                        12,
                        "https://github.com/" + workspace.monitoredRepository().fullName() + "/pull/12"));
        CredentialService credentials = mock(CredentialService.class);
        when(credentials.resolveGitHubToken()).thenReturn("token");
        ManagedWorkspaceGit git = mock(ManagedWorkspaceGit.class);
        PullRequestService pullRequestCatalog = mock(PullRequestService.class);
        CiAutofixService ciAutofix = mock(CiAutofixService.class);
        CiAutofixStore ciAutofixStore = mock(CiAutofixStore.class);
        WorkspaceRelationships relationships = mock(WorkspaceRelationships.class);
        BackportChainStore chains = mock(BackportChainStore.class);
        GitHubPullRequestReadClient reads = mock(GitHubPullRequestReadClient.class);
        when(credentials.resolveGitHubToken("workspace")).thenReturn("token");
        WorkspacePullRequestService service = new WorkspacePullRequestService(
                workspaceStore,
                executionStore,
                provisioning,
                repositories,
                pullRequests,
                credentials,
                git,
                pullRequestCatalog,
                ciAutofix,
                ciAutofixStore,
                Clock.fixed(NOW, ZoneOffset.UTC),
                relationships, chains, reads);
        return new Fixture(
                service,
                workspace,
                initial.id(),
                provisioning,
                git,
                pullRequests,
                pullRequestCatalog,
                ciAutofix, relationships, chains, reads, workspaceStore);
    }

    private CherryPickExecution completedExecution(Workspace workspace, String targetBranch)
    {
        CherryPickItem item = CherryPickItem.planned(
                1, SHA, PARENT_SHA, "Already present", Optional.empty(), Disposition.ALREADY_ON_TARGET);
        return CherryPickExecution.create(
                        CherryPickExecutionId.random(),
                        "backport:fixture-chain:0",
                        new CherryPickExecution.Workspace(
                                "workspace",
                                workspace.monitoredRepository(),
                                workspace.selectedRepository(),
                                workspace.upstreamRepositoryId().isPresent() ? "upstream" : "origin",
                                workspace.upstreamRepositoryId().isPresent() ? "upstream" : "origin",
                                targetBranch,
                                "bytequay-base",
                                WORK_BRANCH,
                                temporaryDirectory,
                                "lane", Optional.empty(), Optional.empty(), Optional.empty()),
                        Optional.of(new PullRequestDraft("PR title", "PR body")),
                        new Range(PARENT_SHA, SHA, PARENT_SHA),
                        ImmutableList.of(item),
                        NOW)
                .finish(FinishReason.COMPLETED, "Done", NOW);
    }

    private Workspace directWorkspace()
    {
        return workspace(
                "workspace",
                100,
                RepoRef.of("owner", "project"),
                Optional.empty(),
                Optional.empty(),
                100,
                RepoRef.of("owner", "project"),
                Topology.DIRECT,
                "https://github.com/owner/project.git",
                Optional.empty());
    }

    private Workspace forkWorkspace()
    {
        return workspace(
                "workspace",
                200,
                RepoRef.of("contributor", "project"),
                Optional.of(300L),
                Optional.of(RepoRef.of("upstream", "project")),
                300,
                RepoRef.of("upstream", "project"),
                Topology.FORK,
                "https://github.com/contributor/project.git",
                Optional.of("https://github.com/upstream/project.git"));
    }

    private Workspace workspace(
            String id,
            long selectedId,
            RepoRef selected,
            Optional<Long> upstreamId,
            Optional<RepoRef> upstream,
            long monitoredId,
            RepoRef monitored,
            Topology topology,
            String originUrl,
            Optional<String> upstreamUrl)
    {
        return new Workspace(
                id,
                "project",
                selectedId,
                selected,
                upstreamId,
                upstream,
                monitoredId,
                monitored,
                topology,
                "main",
                originUrl,
                upstreamId.map(ignored -> "main"),
                upstreamUrl,
                temporaryDirectory.resolve("repository").toString(),
                Optional.of(NOW),
                Optional.of(NOW),
                0,
                NOW,
                NOW);
    }

    private static UserRepo directRepository(boolean writable)
    {
        return new UserRepo(
                100,
                "owner",
                "project",
                "owner/project",
                Optional.empty(),
                false,
                "public",
                writable,
                "main",
                "https://github.com/owner/project.git",
                Optional.empty());
    }

    private static UserRepo forkRepository(boolean writable)
    {
        return new UserRepo(
                200,
                "contributor",
                "project",
                "contributor/project",
                Optional.empty(),
                true,
                "public",
                writable,
                "main",
                "https://github.com/contributor/project.git",
                Optional.of(new UserRepo.Upstream(
                        300,
                        "upstream",
                        "project",
                        "main",
                        "https://github.com/upstream/project.git")));
    }

    private static UserRepo upstreamRepository()
    {
        return new UserRepo(
                300,
                "upstream",
                "project",
                "upstream/project",
                Optional.empty(),
                false,
                "public",
                false,
                "main",
                "https://github.com/upstream/project.git",
                Optional.empty());
    }

    private record Fixture(
            WorkspacePullRequestService service,
            Workspace workspace,
            CherryPickExecutionId executionId,
            WorkspaceProvisioningService provisioning,
            ManagedWorkspaceGit git,
            GitHubPullRequestWriteClient pullRequests,
            PullRequestService pullRequestCatalog,
            CiAutofixService ciAutofix,
            WorkspaceRelationships relationships, BackportChainStore chains, GitHubPullRequestReadClient reads, WorkspaceStore workspaceStore)
    {
        private Fixture
        {
            requireNonNull(service, "service is null");
            requireNonNull(workspace, "workspace is null");
            requireNonNull(executionId, "executionId is null");
            requireNonNull(provisioning, "provisioning is null");
            requireNonNull(git, "git is null");
            requireNonNull(pullRequests, "pullRequests is null");
            requireNonNull(pullRequestCatalog, "pullRequestCatalog is null");
            requireNonNull(ciAutofix, "ciAutofix is null");
        }
    }
}
