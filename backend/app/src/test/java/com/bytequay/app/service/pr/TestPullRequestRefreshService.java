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
package com.bytequay.app.service.pr;

import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.domain.Workspace.CodeState;
import com.bytequay.core.domain.Workspace.MetadataState;
import com.bytequay.core.domain.Workspace.Topology;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestPullRequestRefreshService
{
    @Test
    void refreshesGlobalAndDistinctWorkspaceRepositories()
    {
        PullRequestService pullRequests = mock(PullRequestService.class);
        WorkspaceStore workspaces = mock(WorkspaceStore.class);
        RepoRef shared = RepoRef.of("upstream", "shared");
        RepoRef other = RepoRef.of("owner", "other");
        when(workspaces.findAll()).thenReturn(ImmutableList.of(
                workspace("first", 1, shared), workspace("second", 2, shared), workspace("other", 3, other)));
        AtomicReference<Runnable> submitted = new AtomicReference<>();
        PullRequestRefreshService service = service(pullRequests, workspaces, submitted::set);

        service.requestRefresh();
        submitted.get().run();

        InOrder order = inOrder(pullRequests);
        order.verify(pullRequests).syncFromGitHub();
        order.verify(pullRequests).syncRepository(shared);
        order.verify(pullRequests).syncRepository(other);
        verify(pullRequests, times(1)).syncRepository(shared);
    }

    @Test
    void repositoryRequestDuringRefreshRunsAfterTheActiveBatch()
            throws Exception
    {
        PullRequestService pullRequests = mock(PullRequestService.class);
        WorkspaceStore workspaces = mock(WorkspaceStore.class);
        when(workspaces.findAll()).thenReturn(ImmutableList.of());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch repositoryRefreshed = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(pullRequests).syncFromGitHub();
        RepoRef repository = RepoRef.of("owner", "repo");
        doAnswer(invocation -> {
            repositoryRefreshed.countDown();
            return null;
        }).when(pullRequests).syncRepository(repository);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            PullRequestRefreshService service = service(pullRequests, workspaces, executor);
            service.requestRefresh();
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            service.requestRefresh(repository);
            release.countDown();
            assertThat(repositoryRefreshed.await(5, TimeUnit.SECONDS)).isTrue();
        }

        verify(pullRequests, times(1)).syncFromGitHub();
        verify(pullRequests).syncRepository(repository);
    }

    @Test
    void repositoryRefreshIsQueued()
    {
        PullRequestService pullRequests = mock(PullRequestService.class);
        AtomicReference<Runnable> submitted = new AtomicReference<>();
        PullRequestRefreshService service = service(pullRequests, mock(WorkspaceStore.class), submitted::set);
        RepoRef repository = RepoRef.of("owner", "repo");

        service.requestRefresh(repository);

        verify(pullRequests, never()).syncRepository(repository);
        submitted.get().run();
        verify(pullRequests).syncRepository(repository);
    }

    private static PullRequestRefreshService service(
            PullRequestService pullRequests,
            WorkspaceStore workspaces,
            Executor executor)
    {
        CredentialService credentials = mock(CredentialService.class);
        when(credentials.isGitHubAccountConfigured()).thenReturn(true);
        return new PullRequestRefreshService(pullRequests, credentials, workspaces, executor);
    }

    private static Workspace workspace(String id, long repositoryId, RepoRef repository)
    {
        Instant now = Instant.parse("2026-08-22T00:00:00Z");
        return new Workspace(
                id,
                id,
                repositoryId,
                repository,
                Optional.empty(),
                Optional.empty(),
                repositoryId,
                repository,
                Topology.DIRECT,
                "main",
                "https://github.com/" + repository.fullName() + ".git",
                Optional.empty(),
                Optional.empty(),
                "/tmp/bytequay-pr-refresh/" + id,
                CodeState.READY,
                MetadataState.READY,
                Optional.of(now),
                Optional.of(now),
                Optional.empty(),
                Optional.empty(),
                0,
                now,
                now);
    }
}
