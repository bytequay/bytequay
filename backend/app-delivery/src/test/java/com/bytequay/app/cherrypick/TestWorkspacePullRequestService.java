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

import com.bytequay.app.ci.CiAutofixService;
import com.bytequay.app.service.hosting.HostingReadSession;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.workspace.ManagedWorkspaceGit;
import com.bytequay.app.workspace.WorkspaceCodeSyncResult;
import com.bytequay.app.workspace.WorkspaceException;
import com.bytequay.app.workspace.WorkspaceHostingRead;
import com.bytequay.app.workspace.WorkspaceHostingService;
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
import com.bytequay.core.domain.PullRequestIdentity;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.domain.Workspace.Topology;
import com.bytequay.core.hosting.HostingAccess;
import com.bytequay.core.hosting.HostingInstance;
import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.hosting.HostingTarget;
import com.bytequay.core.hosting.PullRequestContentReader;
import com.bytequay.core.hosting.PullRequestWriter;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static com.bytequay.app.service.hosting.HostingIntegrationFixtures.integrations;
import static com.bytequay.app.service.hosting.HostingIntegrationFixtures.pullRequests;
import static com.bytequay.core.hosting.HostingServer.GITHUB_COM;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
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
        Fixture fixture = fixture(directWorkspace(), true);
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
        Fixture fixture = fixture(directWorkspace(), true);
        BackportChain chain = chain(fixture);

        int result = fixture.service().deliverChain(chain.id(), 0, false);
        int repeated = fixture.service().deliverChain(chain.id(), 0, false);

        verify(fixture.git()).pushBranch(fixture.workspace(), WORK_BRANCH, PARENT_SHA);
        verify(fixture.pullRequests(), times(1)).create(
                argThat(target -> target.repository().fullPath().equals("owner/project")), eq(RepoRef.of("owner", "project")), eq(WORK_BRANCH), eq("main"),
                eq("PR title"), eq("PR body"), eq(false));
        verify(fixture.pullRequestCatalog(), times(2))
                .observeWorkspacePullRequest("workspace", 12);
        verify(fixture.ciAutofix(), times(2)).start(argThat(command ->
                command.workspaceId().equals("workspace")
                        && command.pullRequestNumber() == 12
                        && command.laneId().toString().equals("lane")),
                argThat(identity -> identity.baseBranch().equals("main") && identity.headSha().equals(PARENT_SHA)));
        assertThat(result).isEqualTo(repeated).isEqualTo(12);
        assertThat(fixture.executions().find(fixture.executionId()).orElseThrow().delivery().orElseThrow().pullRequestUrl()).contains("https://github.com/owner/project/pull/12");
    }

    @Test
    void forkDeliveryPushesForkOriginAndTargetsUpstream()
    {
        Fixture fixture = fixture(forkWorkspace(), true);
        BackportChain chain = chain(fixture);

        assertThat(fixture.service().deliverChain(chain.id(), 0, false)).isEqualTo(12);

        verify(fixture.git()).pushBranch(fixture.workspace(), WORK_BRANCH, PARENT_SHA);
        verify(fixture.pullRequests()).create(
                argThat(target -> target.repository().fullPath().equals("upstream/project")), eq(RepoRef.of("contributor", "project")), eq(WORK_BRANCH), eq("main"),
                eq("PR title"), eq("PR body"), eq(false));
        verify(fixture.pullRequestCatalog())
                .observeWorkspacePullRequest("workspace", 12);
        verify(fixture.ciAutofix()).start(any(), any());
    }

    @Test
    void forkDeliveryKeepsTheConfirmedReleaseBranchInsteadOfTheCurrentDefault()
    {
        Fixture fixture = fixture(forkWorkspace(), true, "release/478");
        BackportChain chain = chain(fixture);
        assertThat(fixture.workspace().upstreamDefaultBranch()).contains("main");

        assertThat(fixture.service().deliverChain(chain.id(), 0, false)).isEqualTo(12);

        verify(fixture.git()).pushBranch(fixture.workspace(), WORK_BRANCH, PARENT_SHA);
        verify(fixture.pullRequests()).create(
                argThat(target -> target.repository().fullPath().equals("upstream/project")), eq(RepoRef.of("contributor", "project")), eq(WORK_BRANCH), eq("release/478"),
                eq("PR title"), eq("PR body"), eq(false));
    }

    @Test
    void retriesLocalActivationWithoutCreatingASecondRemotePullRequest()
    {
        Fixture fixture = fixture(directWorkspace(), true);
        BackportChain chain = chain(fixture);
        doThrow(new RuntimeException("projection failed"))
                .doNothing()
                .when(fixture.pullRequestCatalog())
                .observeWorkspacePullRequest("workspace", 12);

        assertThatThrownBy(() -> fixture.service().deliverChain(chain.id(), 0, false))
                .hasMessageContaining("projection failed");
        assertThat(fixture.service().deliverChain(chain.id(), 0, false)).isEqualTo(12);

        verify(fixture.pullRequests(), times(1)).create(any(), any(), any(), any(), any(), any(), anyBoolean());
        verify(fixture.ciAutofix(), times(1)).start(any(), any());
    }

    @Test
    void readOnlyRepositoryRejectsPushWithoutWrites()
    {
        Fixture fixture = fixture(forkWorkspace(), false);
        BackportChain chain = chain(fixture);

        assertThatThrownBy(() -> fixture.service().deliverChain(chain.id(), 0, false))
                .isInstanceOfSatisfying(WorkspaceException.class,
                        failure -> assertThat(failure.code()).isEqualTo("WORKSPACE_PUSH_NOT_WRITABLE"));

        verifyNoInteractions(fixture.git(), fixture.pullRequests());
    }

    @Test
    void publicationRequiresSavedChainAuthority()
    {
        Fixture fixture = fixture(directWorkspace(), true);

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
        Fixture fixture = fixture(directWorkspace(), true);
        BackportChain chain = chain(fixture);
        CherryPickExecution execution = fixture.executions().find(fixture.executionId()).orElseThrow();
        fixture.executions().save(execution.recordPush("origin", WORK_BRANCH, PARENT_SHA, NOW));

        assertThat(fixture.service().deliverChain(chain.id(), 0, false)).isEqualTo(12);

        verify(fixture.git(), never()).pushBranch(any(), any(), any());
        verify(fixture.pullRequests()).create(any(), eq(RepoRef.of("owner", "project")), eq(WORK_BRANCH), eq("main"), eq("PR title"), eq("PR body"), eq(false));
        verify(fixture.ciAutofix()).start(any(), any());
    }

    @Test
    void missingCodeTimestampTriggersSyncBeforePush()
    {
        Workspace unsynced = directWorkspace().withCodeSyncAt(Optional.empty(), NOW);
        Fixture fixture = fixture(unsynced, true);
        BackportChain chain = chain(fixture);

        fixture.service().deliverChain(chain.id(), 0, false);

        verify(fixture.provisioning()).synchronizeCode("workspace");
    }

    private Fixture fixture(Workspace workspace, boolean writable)
    {
        return fixture(workspace, writable, "main");
    }

    @Test
    void chainPublicationUsesSavedAuthorityAndStopDuringPushPreventsPrCreation()
    {
        Fixture fixture = fixture(directWorkspace(), true);
        var chain = chain(fixture);
        AtomicReference<BackportChain> saved = new AtomicReference<>(chain);
        when(fixture.chains().find(chain.id())).thenAnswer(_ -> Optional.of(saved.get()));
        org.mockito.Mockito.doAnswer(_ -> {
            saved.set(chain.withState(BackportChain.State.STOPPING, Optional.of("Stop"), NOW));
            return null;
        }).when(fixture.git()).pushBranch(any(), any(), any());
        assertThatThrownBy(() -> fixture.service().deliverChain(chain.id(), 0, false)).hasMessageContaining("stopped or blocked");
        verify(fixture.pullRequests(), never()).create(any(), any(), any(), any(), any(), any(), anyBoolean());
        verifyNoInteractions(fixture.ciAutofix());
    }

    @Test
    void uncertainPublicationAdoptsExactRemoteProofWithoutWritingAgain()
    {
        Fixture fixture = fixture(directWorkspace(), true);
        var chain = chain(fixture);
        var repo = fixture.workspace().monitoredRepository();
        when(fixture.git().remoteBranchSha(any(), any())).thenReturn(Optional.of(PARENT_SHA));
        when(fixture.reads().identitiesByHead(any(), any(), any())).thenReturn(ImmutableList.of(
                new PullRequestIdentity(PullRequestRef.of(repo.owner(), repo.repo(), 12), "https://github.com/owner/project/pull/12",
                        repo, WORK_BRANCH, PARENT_SHA, repo, "main", SHA, PullRequestIdentity.State.OPEN, false, "owner")));
        assertThat(fixture.service().deliverChain(chain.id(), 0, true)).isEqualTo(12);
        verify(fixture.git(), never()).pushBranch(any(), any(), any());
        verifyNoInteractions(fixture.pullRequests());
        verify(fixture.ciAutofix()).start(any(), any());
    }

    @Test
    void stopDuringPrCreationKeepsItsRecordAndRecoverySkipsCiAfterMerge()
    {
        Fixture fixture = fixture(directWorkspace(), true);
        var chain = chain(fixture);
        var saved = new AtomicReference<>(chain);
        when(fixture.chains().find(chain.id())).thenAnswer(_ -> Optional.of(saved.get()));
        when(fixture.pullRequests().create(any(), any(), any(), any(), any(), any(), anyBoolean())).thenAnswer(_ -> {
            saved.set(chain.withState(BackportChain.State.STOPPING, Optional.of("Stop"), NOW));
            return new CreatedPullRequest(900, 12, "https://github.com/owner/project/pull/12");
        });
        assertThatThrownBy(() -> fixture.service().deliverChain(chain.id(), 0, false)).hasMessageContaining("stopped or blocked");
        verifyNoInteractions(fixture.ciAutofix());
        saved.set(chain);
        var repo = fixture.workspace().monitoredRepository();
        when(fixture.reads().identity(any(), anyInt())).thenReturn(new PullRequestIdentity(
                PullRequestRef.of(repo.owner(), repo.repo(), 12), "https://github.com/owner/project/pull/12",
                repo, WORK_BRANCH, PARENT_SHA, repo, "main", SHA, PullRequestIdentity.State.MERGED, false, "owner"));
        assertThat(fixture.service().deliverChain(chain.id(), 0, true)).isEqualTo(12);
        verify(fixture.pullRequests()).create(any(), any(), any(), any(), any(), any(), anyBoolean());
        verifyNoInteractions(fixture.ciAutofix());
    }

    @Test
    void uncertainMissingOrChangedRemoteNeverRetriesPushOrPr()
    {
        Fixture fixture = fixture(directWorkspace(), true);
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
        Fixture fixture = fixture(directWorkspace(), true, "release");
        CherryPickExecution execution = publishing(fixture);
        when(fixture.pullRequests().create(any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenThrow(new IllegalStateException("Lost publish response"));
        assertThatThrownBy(() -> fixture.service().publish(execution.id())).hasMessage("Lost publish response");
        RepoRef repository = fixture.workspace().selectedRepository();
        when(fixture.reads().identitiesByHead(any(), any(), any())).thenReturn(ImmutableList.of(new PullRequestIdentity(
                PullRequestRef.of(repository.owner(), repository.repo(), 12), "https://github.com/owner/project/pull/12",
                change.equals("head-repository") ? RepoRef.of("another", "project") : repository,
                change.equals("head-branch") ? "another-branch" : WORK_BRANCH,
                change.equals("head") ? SHA : PARENT_SHA,
                change.equals("base-repository") ? RepoRef.of("another", "project") : repository,
                change.equals("base") ? "main" : "release", SHA, PullRequestIdentity.State.OPEN, false, "owner")));

        assertThatThrownBy(() -> fixture.service().publish(execution.id()))
                .hasMessageContaining(change.equals("head") ? "published cherry-pick head" : "retargeted");

        assertThat(fixture.executions().find(execution.id()).orElseThrow().delivery().orElseThrow().pullRequestNumber()).isEmpty();
        verify(fixture.pullRequests(), times(1)).create(any(), any(), any(), any(), any(), any(), anyBoolean());
        verify(fixture.git(), times(1)).pushBranch(any(), any(), any());
        verifyNoInteractions(fixture.ciAutofix(), fixture.pullRequestCatalog());
    }

    @Test
    void lostPublishResponseRecoversTheAcceptedPullRequestAndStartsCi()
    {
        Fixture fixture = fixture(directWorkspace(), true, "release");
        CherryPickExecution execution = publishing(fixture);
        when(fixture.pullRequests().create(any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenThrow(new IllegalStateException("Lost publish response"));
        assertThatThrownBy(() -> fixture.service().publish(execution.id())).hasMessage("Lost publish response");
        RepoRef repository = fixture.workspace().selectedRepository();
        when(fixture.reads().identitiesByHead(any(), any(), any())).thenReturn(ImmutableList.of(new PullRequestIdentity(
                PullRequestRef.of(repository.owner(), repository.repo(), 12), "https://github.com/owner/project/pull/12",
                repository, WORK_BRANCH, PARENT_SHA, repository, "release", SHA, PullRequestIdentity.State.OPEN, false, "owner")));

        CherryPickExecution recovered = fixture.service().publish(execution.id());
        fixture.service().startCi(recovered, 1);

        assertThat(recovered.delivery().orElseThrow().pullRequestNumber()).contains(12);
        verify(fixture.pullRequests(), times(1)).create(any(), any(), any(), any(), any(), any(), anyBoolean());
        verify(fixture.git(), times(1)).pushBranch(any(), any(), any());
        verify(fixture.ciAutofix()).start(argThat(command -> command.pullRequestNumber() == 12),
                argThat(identity -> identity.baseBranch().equals("release") && identity.headSha().equals(PARENT_SHA)));
    }

    @Test
    void retargetingABoundPullRequestBlocksDeliveryAndPreservesItsBranch()
    {
        Fixture fixture = fixture(directWorkspace(), true, "release");
        CherryPickExecution execution = fixture.executions().save(publishing(fixture)
                .recordPush("origin", WORK_BRANCH, PARENT_SHA, NOW).recordPullRequest(12, "https://github.com/owner/project/pull/12", NOW));
        RepoRef repository = fixture.workspace().selectedRepository();
        when(fixture.reads().identity(any(), anyInt())).thenReturn(new PullRequestIdentity(
                PullRequestRef.of(repository.owner(), repository.repo(), 12), "https://github.com/owner/project/pull/12",
                repository, WORK_BRANCH, PARENT_SHA, repository, "main", SHA, PullRequestIdentity.State.MERGED, false, "owner"));

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
        Fixture fixture = fixture(directWorkspace(), true, "release");
        CherryPickExecution execution = fixture.executions().save(publishing(fixture)
                .recordPush("origin", WORK_BRANCH, PARENT_SHA, NOW).recordPullRequest(12, "https://github.com/owner/project/pull/12", NOW));
        RepoRef repository = fixture.workspace().selectedRepository();
        when(fixture.reads().identity(any(), anyInt())).thenReturn(new PullRequestIdentity(
                PullRequestRef.of(repository.owner(), repository.repo(), 12), "https://github.com/owner/project/pull/12",
                repository, WORK_BRANCH, SHA, repository, "release", SHA, PullRequestIdentity.State.OPEN, false, "owner"));

        fixture.service().startCi(execution, 1);

        verify(fixture.ciAutofix()).start(any(), argThat(identity -> identity.headSha().equals(PARENT_SHA) && identity.baseBranch().equals("release")));
    }

    @Test
    void acceptedMergedPullRequestStillAllowsBranchCleanup()
    {
        Fixture fixture = fixture(directWorkspace(), true, "release");
        CherryPickExecution execution = fixture.executions().save(publishing(fixture)
                .recordPush("origin", WORK_BRANCH, PARENT_SHA, NOW).recordPullRequest(12, "https://github.com/owner/project/pull/12", NOW));
        RepoRef repository = fixture.workspace().selectedRepository();
        when(fixture.reads().identity(any(), anyInt())).thenReturn(new PullRequestIdentity(
                PullRequestRef.of(repository.owner(), repository.repo(), 12), "https://github.com/owner/project/pull/12",
                repository, WORK_BRANCH, PARENT_SHA, repository, "release", SHA, PullRequestIdentity.State.MERGED, false, "owner"));
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

    private Fixture fixture(Workspace workspace, boolean writable, String targetBranch)
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
        HostingAccess access = new HostingAccess(new HostingInstance(1, GITHUB_COM.provider(), GITHUB_COM.webBaseUrl(), GITHUB_COM.apiBaseUrl()), "token");
        WorkspaceHostingRead read = new WorkspaceHostingRead(new HostingReadSession(2, "workspace", access),
                repository(3, workspace.monitoredRepositoryId(), workspace.monitoredRepository()), "contributor");
        HostingTarget pushed = new HostingTarget(access, repository(4, workspace.selectedRepositoryId(), workspace.selectedRepository()));
        WorkspaceHostingService hosting = mock(WorkspaceHostingService.class);
        when(hosting.captureRead("workspace")).thenReturn(read);
        when(hosting.pushTarget(eq("workspace"), any())).thenReturn(pushed);
        PullRequestWriter pullRequests = mock(PullRequestWriter.class);
        when(pullRequests.create(any(), any(), any(), any(), any(), any(), anyBoolean()))
                .thenReturn(new CreatedPullRequest(900, 12, "https://github.com/" + workspace.monitoredRepository().fullName() + "/pull/12"));
        PullRequestContentReader reads = mock(PullRequestContentReader.class);
        when(reads.viewerCanWrite(pushed)).thenReturn(writable);
        when(reads.identity(any(), anyInt())).thenReturn(new PullRequestIdentity(
                PullRequestRef.of(workspace.monitoredRepository().owner(), workspace.monitoredRepository().repo(), 12),
                "https://github.com/" + workspace.monitoredRepository().fullName() + "/pull/12",
                workspace.selectedRepository(), WORK_BRANCH, PARENT_SHA, workspace.monitoredRepository(), targetBranch, SHA,
                PullRequestIdentity.State.OPEN, false, workspace.selectedRepository().owner()));
        ManagedWorkspaceGit git = mock(ManagedWorkspaceGit.class);
        PullRequestService pullRequestCatalog = mock(PullRequestService.class);
        CiAutofixService ciAutofix = mock(CiAutofixService.class);
        WorkspaceRelationships relationships = mock(WorkspaceRelationships.class);
        BackportChainStore chains = mock(BackportChainStore.class);
        WorkspacePullRequestService service = new WorkspacePullRequestService(
                workspaceStore,
                executionStore,
                provisioning,
                hosting,
                integrations(pullRequests(GITHUB_COM.provider(), reads, pullRequests)),
                git,
                pullRequestCatalog,
                ciAutofix,
                Clock.fixed(NOW, ZoneOffset.UTC),
                relationships, chains);
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
                NOW,
                GITHUB_COM);
    }

    private static HostingRepository repository(long id, long remoteId, RepoRef path)
    {
        return new HostingRepository(id, 1, Long.toString(remoteId), path.fullName(), URI.create("https://github.com/" + path.fullName()),
                URI.create("https://github.com/" + path.fullName() + ".git"), Optional.empty(), Optional.of("main"));
    }

    private record Fixture(
            WorkspacePullRequestService service,
            Workspace workspace,
            CherryPickExecutionId executionId,
            WorkspaceProvisioningService provisioning,
            ManagedWorkspaceGit git,
            PullRequestWriter pullRequests,
            PullRequestService pullRequestCatalog,
            CiAutofixService ciAutofix,
            WorkspaceRelationships relationships, BackportChainStore chains, PullRequestContentReader reads, WorkspaceStore workspaceStore, CherryPickExecutionStore executions)
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
