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
package com.bytequay.app.cherrypick;

import com.bytequay.app.cherrypick.WorkspacePullRequestService.PullRequestResult;
import com.bytequay.app.ci.CiAutofixService;
import com.bytequay.app.repository.github.GitHubAccountClient;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.app.repository.github.GitHubPullRequestWriteClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.workspace.ManagedWorkspaceGit;
import com.bytequay.app.workspace.WorkspaceCodeSyncResult;
import com.bytequay.app.workspace.WorkspaceException;
import com.bytequay.app.workspace.WorkspaceProvisioningService;
import com.bytequay.app.workspace.WorkspaceRelationships;
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
import com.bytequay.core.cherrypick.CherryPickLifecycle.Stage;
import com.bytequay.core.domain.CreatedPullRequest;
import com.bytequay.core.domain.FetchedPullRequest;
import com.bytequay.core.domain.GitHubPullRequestIdentity;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
    void rejectsDependencyDeliveryWithoutDiscardingWork()
    {
        Fixture fixture = fixture(directWorkspace(), directRepository(true));
        BackportChain chain = chain(fixture);
        doThrow(new IllegalArgumentException("Dependency transfer blocked")).when(fixture.relationships())
                .requireTransferAllowed(org.mockito.ArgumentMatchers.<Optional<RepoRef>>any(), any());
        assertThatThrownBy(() -> fixture.service().deliverChain(chain.id(), 0, false))
                .hasMessage("Dependency transfer blocked");
        verifyNoInteractions(fixture.git(), fixture.pullRequests());
    }

    @Test
    void directDeliveryPushesOriginAndCreatesPullRequestInSelectedRepository()
    {
        Fixture fixture = fixture(directWorkspace(), directRepository(true));
        BackportChain chain = chain(fixture);

        PullRequestResult result = fixture.service().deliverChain(chain.id(), 0, false);
        PullRequestResult repeated = fixture.service().deliverChain(chain.id(), 0, false);

        verify(fixture.git()).pushBranch(fixture.workspace(), WORK_BRANCH, PARENT_SHA);
        verify(fixture.pullRequests(), times(1)).createPullRequest(
                "token", RepoRef.of("owner", "project"), WORK_BRANCH, "main", "PR title", "PR body", false);
        verify(fixture.pullRequestCatalog(), times(2))
                .observeWorkspacePullRequest("workspace", 12);
        verify(fixture.ciAutofix(), times(2)).start(argThat(command ->
                command.workspaceId().equals("workspace")
                        && command.pullRequestNumber() == 12
                        && command.laneId().toString().equals("lane")),
                argThat(identity -> identity.baseBranch().equals("main") && identity.headSha().equals(PARENT_SHA)));
        assertThat(result).isEqualTo(repeated);
        assertThat(result.providerId()).isEqualTo(900);
        assertThat(result.url()).isEqualTo("https://github.com/owner/project/pull/12");
    }

    @Test
    void forkDeliveryPushesForkOriginAndTargetsUpstream()
    {
        Fixture fixture = fixture(forkWorkspace(), forkRepository(true));
        BackportChain chain = chain(fixture);

        PullRequestResult result = fixture.service().deliverChain(chain.id(), 0, false);

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
                .observeWorkspacePullRequest("workspace", 12);
        verify(fixture.ciAutofix()).start(any(), any());
        assertThat(result.baseRepository()).isEqualTo(RepoRef.of("upstream", "project"));
        assertThat(result.head()).isEqualTo("contributor:" + WORK_BRANCH);
    }

    @Test
    void forkDeliveryKeepsTheConfirmedReleaseBranchInsteadOfTheCurrentDefault()
    {
        Fixture fixture = fixture(forkWorkspace(), forkRepository(true), "release/478");
        BackportChain chain = chain(fixture);
        assertThat(fixture.workspace().upstreamDefaultBranch()).contains("main");

        PullRequestResult result = fixture.service().deliverChain(chain.id(), 0, false);

        verify(fixture.git()).pushBranch(fixture.workspace(), WORK_BRANCH, PARENT_SHA);
        verify(fixture.pullRequests()).createPullRequest(
                "token", RepoRef.of("upstream", "project"), "contributor:" + WORK_BRANCH, "release/478", "PR title", "PR body", false);
        assertThat(result.baseRepository()).isEqualTo(RepoRef.of("upstream", "project"));
        assertThat(result.baseBranch()).isEqualTo("release/478");
    }

    @Test
    void retriesLocalActivationWithoutCreatingASecondRemotePullRequest()
    {
        Fixture fixture = fixture(directWorkspace(), directRepository(true));
        BackportChain chain = chain(fixture);
        doThrow(new RuntimeException("projection failed"))
                .doNothing()
                .when(fixture.pullRequestCatalog())
                .observeWorkspacePullRequest("workspace", 12);

        assertThatThrownBy(() -> fixture.service().deliverChain(chain.id(), 0, false))
                .hasMessageContaining("projection failed");
        PullRequestResult retried = fixture.service().deliverChain(chain.id(), 0, false);

        assertThat(retried.number()).isEqualTo(12);
        verify(fixture.pullRequests(), times(1)).createPullRequest(
                any(), any(), any(), any(), any(), any(), anyBoolean());
        verify(fixture.ciAutofix(), times(1)).start(any(), any());
    }

    @Test
    void readOnlyRepositoryRejectsPushWithoutWrites()
    {
        Fixture fixture = fixture(forkWorkspace(), forkRepository(false));
        BackportChain chain = chain(fixture);

        assertThatThrownBy(() -> fixture.service().deliverChain(chain.id(), 0, false))
                .isInstanceOfSatisfying(WorkspaceException.class,
                        failure -> assertThat(failure.code()).isEqualTo("WORKSPACE_PUSH_NOT_WRITABLE"));

        verifyNoInteractions(fixture.git(), fixture.pullRequests());
    }

    @Test
    void publicationRequiresSavedChainAuthority()
    {
        Fixture fixture = fixture(directWorkspace(), directRepository(true));

        assertThatThrownBy(() -> fixture.service().deliverChain("fixture-chain", 0, false))
                .hasMessage("Chain authority is unavailable");
        BackportChain chain = chain(fixture);
        when(fixture.chains().find(chain.id())).thenReturn(Optional.of(chain.withState(BackportChain.State.STOPPED, Optional.of("Stop"), NOW)));
        assertThatThrownBy(() -> fixture.service().deliverChain(chain.id(), 0, false)).hasMessageContaining("stopped or blocked");

        verifyNoInteractions(fixture.git(), fixture.pullRequests(), fixture.ciAutofix());
    }

    @Test
    void createsPullRequestWithoutRepeatingSavedPush()
    {
        Fixture fixture = fixture(directWorkspace(), directRepository(true));
        BackportChain chain = chain(fixture);
        CherryPickExecution execution = fixture.executions().find(fixture.executionId()).orElseThrow();
        fixture.executions().save(execution.recordPush("origin", WORK_BRANCH, PARENT_SHA, NOW));

        assertThat(fixture.service().deliverChain(chain.id(), 0, false).number()).isEqualTo(12);

        verify(fixture.git(), never()).pushBranch(any(), any(), any());
        verify(fixture.pullRequests()).createPullRequest(
                "token", RepoRef.of("owner", "project"), WORK_BRANCH, "main", "PR title", "PR body", false);
        verify(fixture.ciAutofix()).start(any(), any());
    }

    @Test
    void missingCodeTimestampTriggersSyncBeforePush()
    {
        Workspace unsynced = directWorkspace().withCodeSyncAt(Optional.empty(), NOW);
        Fixture fixture = fixture(unsynced, directRepository(true));
        BackportChain chain = chain(fixture);

        fixture.service().deliverChain(chain.id(), 0, false);

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
        verify(fixture.git(), never()).pushBranch(any(), any(), any());
        verifyNoInteractions(fixture.pullRequests());
        verify(fixture.ciAutofix()).start(any(), any());
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

    @ParameterizedTest
    @ValueSource(strings = {"base", "head", "head-repository", "head-branch", "base-repository"})
    void lostPublishResponseDoesNotAdoptARetargetedOrChangedPullRequest(String change)
    {
        Fixture fixture = fixture(directWorkspace(), directRepository(true), "release");
        CherryPickExecution execution = publishing(fixture);
        when(fixture.pullRequests().createPullRequest(any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenThrow(new IllegalStateException("Lost publish response"));
        assertThatThrownBy(() -> fixture.service().publish(execution.id())).hasMessage("Lost publish response");
        when(fixture.reads().fetchPullRequestsByHead(any(), any(), any())).thenReturn(ImmutableList.of(publishedRow(fixture)));
        RepoRef repository = fixture.workspace().selectedRepository();
        when(fixture.reads().fetchPullRequestIdentity(any(), any())).thenReturn(new GitHubPullRequestIdentity(
                PullRequestRef.of(repository.owner(), repository.repo(), 12), "https://github.com/owner/project/pull/12",
                change.equals("head-repository") ? RepoRef.of("another", "project") : repository,
                change.equals("head-branch") ? "another-branch" : WORK_BRANCH,
                change.equals("head") ? SHA : PARENT_SHA,
                change.equals("base-repository") ? RepoRef.of("another", "project") : repository,
                change.equals("base") ? "main" : "release", SHA, GitHubPullRequestIdentity.State.OPEN, false, "owner"));

        assertThatThrownBy(() -> fixture.service().publish(execution.id()))
                .hasMessageContaining(change.equals("head") ? "published cherry-pick head" : "retargeted");

        assertThat(fixture.executions().find(execution.id()).orElseThrow().delivery().orElseThrow().pullRequestId()).isEmpty();
        verify(fixture.pullRequests(), times(1)).createPullRequest(any(), any(), any(), any(), any(), any(), anyBoolean());
        verify(fixture.git(), times(1)).pushBranch(any(), any(), any());
        verifyNoInteractions(fixture.ciAutofix(), fixture.pullRequestCatalog());
    }

    @Test
    void lostPublishResponseRecoversTheAcceptedPullRequestAndStartsCi()
    {
        Fixture fixture = fixture(directWorkspace(), directRepository(true), "release");
        CherryPickExecution execution = publishing(fixture);
        when(fixture.pullRequests().createPullRequest(any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenThrow(new IllegalStateException("Lost publish response"));
        assertThatThrownBy(() -> fixture.service().publish(execution.id())).hasMessage("Lost publish response");
        when(fixture.reads().fetchPullRequestsByHead(any(), any(), any())).thenReturn(ImmutableList.of(publishedRow(fixture)));

        CherryPickExecution recovered = fixture.service().publish(execution.id());
        fixture.service().startCi(recovered, 1);

        assertThat(recovered.delivery().orElseThrow().pullRequestNumber()).contains(12);
        verify(fixture.pullRequests(), times(1)).createPullRequest(any(), any(), any(), any(), any(), any(), anyBoolean());
        verify(fixture.git(), times(1)).pushBranch(any(), any(), any());
        verify(fixture.ciAutofix()).start(argThat(command -> command.pullRequestNumber() == 12),
                argThat(identity -> identity.baseBranch().equals("release") && identity.headSha().equals(PARENT_SHA)));
    }

    @Test
    void retargetingABoundPullRequestBlocksDeliveryAndPreservesItsBranch()
    {
        Fixture fixture = fixture(directWorkspace(), directRepository(true), "release");
        CherryPickExecution execution = fixture.executions().save(publishing(fixture)
                .recordPush("origin", WORK_BRANCH, PARENT_SHA, NOW).recordPullRequest(900, 12, "https://github.com/owner/project/pull/12", NOW));
        RepoRef repository = fixture.workspace().selectedRepository();
        when(fixture.reads().fetchPullRequestIdentity(any(), any())).thenReturn(new GitHubPullRequestIdentity(
                PullRequestRef.of(repository.owner(), repository.repo(), 12), "https://github.com/owner/project/pull/12",
                repository, WORK_BRANCH, PARENT_SHA, repository, "main", SHA, GitHubPullRequestIdentity.State.MERGED, false, "owner"));

        assertThatThrownBy(() -> fixture.service().pullRequest(execution)).hasMessageContaining("retargeted");
        assertThatThrownBy(() -> fixture.service().startCi(execution, 1)).hasMessageContaining("retargeted");
        assertThatThrownBy(() -> fixture.service().markReady(execution)).hasMessageContaining("retargeted");
        assertThatThrownBy(() -> fixture.service().replaceBranch(execution, SHA, PARENT_SHA)).hasMessageContaining("retargeted");
        assertThat(fixture.service().withdraw(execution, false)).singleElement().asString().contains("branch was preserved", "retargeted");
        assertThat(fixture.service().withdraw(execution, true)).singleElement().asString().contains("branch was preserved", "retargeted");
        verifyNoInteractions(fixture.git(), fixture.pullRequests(), fixture.ciAutofix());
    }

    @Test
    void retryingCiStartKeepsItsAcceptedHeadAfterCiAlreadyPushed()
    {
        Fixture fixture = fixture(directWorkspace(), directRepository(true), "release");
        CherryPickExecution execution = fixture.executions().save(publishing(fixture)
                .recordPush("origin", WORK_BRANCH, PARENT_SHA, NOW).recordPullRequest(900, 12, "https://github.com/owner/project/pull/12", NOW));
        RepoRef repository = fixture.workspace().selectedRepository();
        when(fixture.reads().fetchPullRequestIdentity(any(), any())).thenReturn(new GitHubPullRequestIdentity(
                PullRequestRef.of(repository.owner(), repository.repo(), 12), "https://github.com/owner/project/pull/12",
                repository, WORK_BRANCH, SHA, repository, "release", SHA, GitHubPullRequestIdentity.State.OPEN, false, "owner"));

        fixture.service().startCi(execution, 1);

        verify(fixture.ciAutofix()).start(any(), argThat(identity -> identity.headSha().equals(PARENT_SHA) && identity.baseBranch().equals("release")));
    }

    @Test
    void acceptedMergedPullRequestStillAllowsBranchCleanup()
    {
        Fixture fixture = fixture(directWorkspace(), directRepository(true), "release");
        CherryPickExecution execution = fixture.executions().save(publishing(fixture)
                .recordPush("origin", WORK_BRANCH, PARENT_SHA, NOW).recordPullRequest(900, 12, "https://github.com/owner/project/pull/12", NOW));
        RepoRef repository = fixture.workspace().selectedRepository();
        when(fixture.reads().fetchPullRequestIdentity(any(), any())).thenReturn(new GitHubPullRequestIdentity(
                PullRequestRef.of(repository.owner(), repository.repo(), 12), "https://github.com/owner/project/pull/12",
                repository, WORK_BRANCH, PARENT_SHA, repository, "release", SHA, GitHubPullRequestIdentity.State.MERGED, false, "owner"));
        when(fixture.git().remoteBranchSha(any(), any())).thenReturn(Optional.of(PARENT_SHA));

        assertThat(fixture.service().withdraw(execution, false)).isEmpty();

        verify(fixture.git()).deleteBranch(fixture.workspace(), WORK_BRANCH, PARENT_SHA);
        verifyNoInteractions(fixture.pullRequests());
    }

    private CherryPickExecution publishing(Fixture fixture)
    {
        CherryPickExecution completed = fixture.executions().find(fixture.executionId()).orElseThrow();
        return fixture.executions().save(CherryPickExecution.create(completed.id(), completed.startIdempotencyKey(), completed.workspace(), completed.pullRequestDraft(),
                completed.range(), completed.items(), true, NOW).withLifecycle(lifecycle -> lifecycle.inStage(Stage.PUBLISH), NOW));
    }

    private static FetchedPullRequest publishedRow(Fixture fixture)
    {
        return new FetchedPullRequest(900L, fixture.workspace().monitoredRepository().fullName(), 12, "Backport", "owner", "https://github.com/owner/project/pull/12", NOW, NOW,
                PullRequest.Origin.AUTHORED, ImmutableList.of(), ImmutableMap.of(), false, "open", null, null, ImmutableList.of(), true, "clean", WORK_BRANCH);
    }

    private BackportChain chain(Fixture fixture)
    {
        Workspace source = workspace("source", 500, RepoRef.of("source", "project"), Optional.empty(), Optional.empty(),
                500, RepoRef.of("source", "project"), Topology.DIRECT, "https://github.com/source/project.git", Optional.empty());
        when(fixture.workspaceStore().findById("source")).thenReturn(Optional.of(source));
        when(fixture.relationships().list("workspace")).thenReturn(ImmutableList.of(
                new WorkspaceRelationships.Relationship("source", WorkspaceRelationships.Kind.FORK, "Fork", false)));
        var chain = new BackportChain("fixture-chain", "workspace", Optional.of("request"), Optional.of("source-task"), "source-branch", BackportChain.Route.of(source),
                Optional.empty(),
                false,
                ImmutableList.of(new BackportChain.Acceptance("key", Optional.of("turn"), ImmutableList.of("workspace"), ImmutableList.of(-1))),
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
        WorkspaceRelationships relationships = mock(WorkspaceRelationships.class);
        BackportChainStore chains = mock(BackportChainStore.class);
        GitHubPullRequestReadClient reads = mock(GitHubPullRequestReadClient.class);
        when(reads.fetchPullRequestIdentity(any(), any())).thenReturn(new GitHubPullRequestIdentity(
                PullRequestRef.of(workspace.monitoredRepository().owner(), workspace.monitoredRepository().repo(), 12),
                "https://github.com/" + workspace.monitoredRepository().fullName() + "/pull/12",
                workspace.selectedRepository(), WORK_BRANCH, PARENT_SHA, workspace.monitoredRepository(), targetBranch, SHA,
                GitHubPullRequestIdentity.State.OPEN, false, workspace.selectedRepository().owner()));
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
                ciAutofix, relationships, chains, reads, workspaceStore, executionStore);
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
                        false,
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
            WorkspaceRelationships relationships, BackportChainStore chains, GitHubPullRequestReadClient reads, WorkspaceStore workspaceStore, CherryPickExecutionStore executions)
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
