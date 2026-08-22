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

import com.bytequay.app.repository.github.CreatedPullRequest;
import com.bytequay.app.repository.github.GitHubAccountClient;
import com.bytequay.app.repository.github.GitHubPullRequestWriteClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.workspace.WorkspaceBranchPusher.Push;
import com.bytequay.app.workspace.WorkspacePullRequestService.PullRequestCommand;
import com.bytequay.app.workspace.WorkspacePullRequestService.PullRequestResult;
import com.bytequay.app.workspace.WorkspacePullRequestService.PushCommand;
import com.bytequay.core.cherrypick.CherryPickExecution;
import com.bytequay.core.cherrypick.CherryPickExecution.FinishReason;
import com.bytequay.core.cherrypick.CherryPickExecution.PullRequestDraft;
import com.bytequay.core.cherrypick.CherryPickExecution.Range;
import com.bytequay.core.cherrypick.CherryPickExecutionId;
import com.bytequay.core.cherrypick.CherryPickExecutionStore;
import com.bytequay.core.cherrypick.CherryPickItem;
import com.bytequay.core.cherrypick.CherryPickItem.Disposition;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.UserRepo;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.domain.Workspace.CodeState;
import com.bytequay.core.domain.Workspace.MetadataState;
import com.bytequay.core.domain.Workspace.Topology;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestDefaultWorkspacePullRequestService
{
    private static final Instant NOW = Instant.parse("2026-08-22T00:00:00Z");
    private static final String SHA = "1111111111111111111111111111111111111111";
    private static final String PARENT_SHA = "2222222222222222222222222222222222222222";
    private static final String WORK_BRANCH = "bump-1-2-ab";

    @TempDir
    private Path temporaryDirectory;

    @Test
    void directDeliveryPushesOriginAndCreatesPullRequestInSelectedRepository()
    {
        Fixture fixture = fixture(directWorkspace(), directRepository(true));

        fixture.service().push(new PushCommand("workspace", fixture.executionId(), true));
        PullRequestResult result = fixture.service().createPullRequest(
                new PullRequestCommand("workspace", fixture.executionId(), true));
        PullRequestResult repeated = fixture.service().createPullRequest(
                new PullRequestCommand("workspace", fixture.executionId(), true));

        verify(fixture.git()).pushBranch(fixture.workspace(), temporaryDirectory, WORK_BRANCH);
        verify(fixture.pullRequests(), times(1)).createPullRequest(
                "token", RepoRef.of("owner", "project"), WORK_BRANCH, "main", "PR title", "PR body");
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

        verify(fixture.git()).pushBranch(fixture.workspace(), temporaryDirectory, WORK_BRANCH);
        verify(fixture.pullRequests()).createPullRequest(
                "token",
                RepoRef.of("upstream", "project"),
                "contributor:" + WORK_BRANCH,
                "main",
                "PR title",
                "PR body");
        assertThat(result.baseRepository()).isEqualTo(RepoRef.of("upstream", "project"));
        assertThat(result.head()).isEqualTo("contributor:" + WORK_BRANCH);
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

        verify(fixture.git(), times(1)).pushBranch(fixture.workspace(), temporaryDirectory, WORK_BRANCH);
        verify(fixture.pullRequests(), never()).createPullRequest(any(), any(), any(), any(), any(), any());
    }

    private Fixture fixture(Workspace workspace, UserRepo selected)
    {
        WorkspaceStore workspaceStore = mock(WorkspaceStore.class);
        when(workspaceStore.findById("workspace")).thenReturn(Optional.of(workspace));
        when(workspaceStore.findById("upstream-workspace")).thenReturn(Optional.of(upstreamWorkspace()));
        CherryPickExecution initial = completedExecution(workspace);
        AtomicReference<CherryPickExecution> current = new AtomicReference<>(initial);
        CherryPickExecutionStore executionStore = mock(CherryPickExecutionStore.class);
        when(executionStore.find(initial.id())).thenAnswer(ignored -> Optional.of(current.get()));
        when(executionStore.save(any())).thenAnswer(invocation -> {
            CherryPickExecution saved = invocation.getArgument(0);
            CherryPickExecution versioned = saved.withVersion(saved.version() + 1);
            current.set(versioned);
            return versioned;
        });
        GitHubAccountClient repositories = mock(GitHubAccountClient.class);
        when(repositories.fetchRepository("token", workspace.selectedRepositoryId()))
                .thenReturn(Optional.of(selected));
        GitHubPullRequestWriteClient pullRequests = mock(GitHubPullRequestWriteClient.class);
        when(pullRequests.createPullRequest(any(), any(), any(), any(), any(), any()))
                .thenReturn(new CreatedPullRequest(
                        900,
                        12,
                        "https://github.com/" + workspace.monitoredRepository().fullName() + "/pull/12"));
        CredentialService credentials = mock(CredentialService.class);
        when(credentials.resolveGitHubToken()).thenReturn("token");
        WorkspaceBranchPusher git = mock(WorkspaceBranchPusher.class);
        when(git.pushBranch(workspace, temporaryDirectory, WORK_BRANCH))
                .thenReturn(new Push("origin", WORK_BRANCH, SHA));
        DefaultWorkspacePullRequestService service = new DefaultWorkspacePullRequestService(
                workspaceStore,
                executionStore,
                repositories,
                pullRequests,
                credentials,
                git,
                Clock.fixed(NOW, ZoneOffset.UTC));
        return new Fixture(service, workspace, initial.id(), git, pullRequests);
    }

    private CherryPickExecution completedExecution(Workspace workspace)
    {
        CherryPickItem item = CherryPickItem.planned(
                1, SHA, PARENT_SHA, "Already present", Optional.empty(), Disposition.ALREADY_ON_TARGET);
        return CherryPickExecution.create(
                        CherryPickExecutionId.random(),
                        "idempotency-key",
                        new CherryPickExecution.Workspace(
                                Optional.of("workspace"),
                                workspace.monitoredRepository(),
                                workspace.upstreamWorkspaceId().isPresent() ? "upstream" : "origin",
                                workspace.upstreamWorkspaceId().isPresent() ? "upstream" : "origin",
                                "main",
                                "bytequay-base",
                                WORK_BRANCH,
                                temporaryDirectory,
                                "lane"),
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
                Optional.of("upstream-workspace"),
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
            Optional<String> upstreamWorkspaceId,
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
                upstreamWorkspaceId,
                upstreamId,
                upstream,
                monitoredId,
                monitored,
                topology,
                "main",
                originUrl,
                upstreamWorkspaceId.map(ignored -> "main"),
                upstreamUrl,
                temporaryDirectory.resolve("repository").toString(),
                CodeState.READY,
                MetadataState.READY,
                Optional.of(NOW),
                Optional.of(NOW),
                Optional.empty(),
                Optional.empty(),
                0,
                NOW,
                NOW);
    }

    private Workspace upstreamWorkspace()
    {
        return workspace(
                "upstream-workspace",
                300,
                RepoRef.of("upstream", "project"),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                300,
                RepoRef.of("upstream", "project"),
                Topology.DIRECT,
                "https://github.com/upstream/project.git",
                Optional.empty());
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

    private record Fixture(
            DefaultWorkspacePullRequestService service,
            Workspace workspace,
            CherryPickExecutionId executionId,
            WorkspaceBranchPusher git,
            GitHubPullRequestWriteClient pullRequests)
    {
        private Fixture
        {
            requireNonNull(service, "service is null");
            requireNonNull(workspace, "workspace is null");
            requireNonNull(executionId, "executionId is null");
            requireNonNull(git, "git is null");
            requireNonNull(pullRequests, "pullRequests is null");
        }
    }
}
