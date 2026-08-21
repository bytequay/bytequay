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
package com.bytequay.app.scheduler;

import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.pr.PullRequestService;
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
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestPullRequestSyncJob
{
    @Test
    void scheduledSyncRefreshesGlobalAndDistinctWorkspaceRepositories()
    {
        PullRequestService pullRequests = mock(PullRequestService.class);
        CredentialService credentials = configuredCredentials();
        WorkspaceStore workspaces = mock(WorkspaceStore.class);
        RepoRef shared = RepoRef.of("upstream", "shared");
        RepoRef other = RepoRef.of("owner", "other");
        when(workspaces.findAll()).thenReturn(ImmutableList.of(
                workspace("first", 1, shared), workspace("second", 2, shared), workspace("other", 3, other)));
        PullRequestSyncJob job = new PullRequestSyncJob(pullRequests, credentials, workspaces, Runnable::run);

        job.tick();

        InOrder order = inOrder(pullRequests);
        order.verify(pullRequests).syncFromGitHub();
        order.verify(pullRequests).syncRepository(shared);
        order.verify(pullRequests).syncRepository(other);
        verify(pullRequests, times(1)).syncRepository(shared);
    }

    @Test
    void manualRepositorySyncUsesThePeriodicOverlapGuard()
            throws Exception
    {
        PullRequestService pullRequests = mock(PullRequestService.class);
        CredentialService credentials = configuredCredentials();
        WorkspaceStore workspaces = mock(WorkspaceStore.class);
        when(workspaces.findAll()).thenReturn(ImmutableList.of());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(pullRequests).syncFromGitHub();
        PullRequestSyncJob job = new PullRequestSyncJob(pullRequests, credentials, workspaces, Runnable::run);
        RepoRef repository = RepoRef.of("owner", "repo");

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var scheduled = executor.submit(job::tick);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            job.synchronizeRepository(repository);
            release.countDown();
            scheduled.get();
        }

        verify(pullRequests, times(1)).syncFromGitHub();
        verify(pullRequests, never()).syncRepository(repository);
    }

    private static CredentialService configuredCredentials()
    {
        CredentialService credentials = mock(CredentialService.class);
        when(credentials.isGitHubAccountConfigured()).thenReturn(true);
        return credentials;
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
                "/tmp/bytequay-pr-sync/" + id,
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
