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
import com.bytequay.app.service.credentials.CredentialService.GitHubCredential;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.PullRequestDetailRefresh;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.UserProfile;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestPullRequestRefreshService
{
    @Test
    void directDetailRefreshReturnsTheRefreshedResultWithoutRereading()
    {
        PullRequestService pullRequests = mock(PullRequestService.class);
        PullRequestRefreshService service = service(pullRequests);
        when(pullRequests.refreshPullRequestDetailFromGitHub("owner/repo", 7))
                .thenReturn(new PullRequestService.DetailRefresh(false, detail()));

        PullRequestDetailRefresh result = service.refreshDetail("owner/repo", 7);

        assertThat(result.full()).isFalse();
        assertThat(result.status().ciStatus()).isEqualTo(PullRequestDetail.CiStatus.PASSING);

        verify(pullRequests).refreshPullRequestDetailFromGitHub("owner/repo", 7);
    }

    @Test
    void directDiffRefreshPersistsBeforeReading()
    {
        PullRequestService pullRequests = mock(PullRequestService.class);
        DiffFile file = new DiffFile("src/App.java", "modified", 1, 1, "patch");
        when(pullRequests.getPullRequestDiffFiles("owner/repo", 7, "base", "head"))
                .thenReturn(ImmutableList.of(file));
        PullRequestRefreshService service = service(pullRequests);

        assertThat(service.refreshDiffFiles("owner/repo", 7, "base", "head"))
                .containsExactly(file);

        InOrder order = inOrder(pullRequests);
        order.verify(pullRequests).refreshPullRequestDiffFromGitHub(
                "owner/repo", 7, "base", "head");
        order.verify(pullRequests).getPullRequestDiffFiles(
                "owner/repo", 7, "base", "head");
    }

    @Test
    void refreshesTheDashboardBeforeReturning()
    {
        PullRequestService pullRequests = mock(PullRequestService.class);
        PullRequestRefreshService service = service(pullRequests);

        service.refreshDashboard();

        verify(pullRequests).syncFromGitHub();
    }

    @Test
    void reusesARecentDashboardRefresh()
    {
        PullRequestService pullRequests = mock(PullRequestService.class);
        PullRequestRefreshService service = service(pullRequests);

        service.refreshDashboard();
        service.refreshDashboard();

        verify(pullRequests).syncFromGitHub();
    }

    @Test
    void coalescesConcurrentDashboardRefreshes()
            throws Exception
    {
        PullRequestService pullRequests = mock(PullRequestService.class);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(pullRequests).syncFromGitHub();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            PullRequestRefreshService service = service(pullRequests);
            var first = executor.submit(service::refreshDashboard);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var duplicate = executor.submit(service::refreshDashboard);
            assertThatThrownBy(() -> duplicate.get(100, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            duplicate.get(5, TimeUnit.SECONDS);
        }

        verify(pullRequests).syncFromGitHub();
    }

    @Test
    void refreshesAgainAfterCredentialChanges()
    {
        PullRequestService pullRequests = mock(PullRequestService.class);
        CredentialService credentials = mock(CredentialService.class);
        GitHubCredential firstCredential = credential("token-a", "alice");
        GitHubCredential secondCredential = credential("token-b", "bob");
        AtomicReference<GitHubCredential> current = new AtomicReference<>(firstCredential);
        when(credentials.resolveGitHubCredential()).thenAnswer(invocation -> current.get());
        PullRequestRefreshService service = new PullRequestRefreshService(pullRequests, credentials);

        service.refreshDashboard();
        current.set(secondCredential);
        service.refreshDashboard();

        verify(pullRequests, times(2)).syncFromGitHub();
    }

    @Test
    void credentialChangeDuringRefreshRunsAgainAfterCurrentRefresh()
            throws Exception
    {
        PullRequestService pullRequests = mock(PullRequestService.class);
        CredentialService credentials = mock(CredentialService.class);
        GitHubCredential firstCredential = credential("token-a", "alice");
        GitHubCredential secondCredential = credential("token-b", "bob");
        AtomicReference<GitHubCredential> current = new AtomicReference<>(firstCredential);
        when(credentials.resolveGitHubCredential()).thenAnswer(invocation -> current.get());
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(pullRequests).syncFromGitHub();
        PullRequestRefreshService service = new PullRequestRefreshService(pullRequests, credentials);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(service::refreshDashboard);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            current.set(secondCredential);
            var second = executor.submit(service::refreshDashboard);
            assertThatThrownBy(() -> second.get(100, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            release.countDown();
            first.get(5, TimeUnit.SECONDS);
            second.get(5, TimeUnit.SECONDS);
        }

        verify(pullRequests, times(2)).syncFromGitHub();
    }

    @Test
    void recentDashboardRefreshDoesNotWaitBehindRepositoryRefresh()
            throws Exception
    {
        PullRequestService pullRequests = mock(PullRequestService.class);
        RepoRef repository = RepoRef.of("owner", "repo");
        CountDownLatch repositoryEntered = new CountDownLatch(1);
        CountDownLatch releaseRepository = new CountDownLatch(1);
        doAnswer(invocation -> {
            repositoryEntered.countDown();
            assertThat(releaseRepository.await(5, TimeUnit.SECONDS)).isTrue();
            return null;
        }).when(pullRequests).syncRepository(repository);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            PullRequestRefreshService service = service(pullRequests);
            service.refreshDashboard();
            var repositoryRefresh = executor.submit(() -> service.refreshRepository(repository));
            assertThat(repositoryEntered.await(5, TimeUnit.SECONDS)).isTrue();
            executor.submit(service::refreshDashboard).get(1, TimeUnit.SECONDS);
            releaseRepository.countDown();
            repositoryRefresh.get(5, TimeUnit.SECONDS);
        }

        verify(pullRequests).syncFromGitHub();
        verify(pullRequests).syncRepository(repository);
    }

    @Test
    void refreshesOneRepositoryBeforeReturning()
    {
        PullRequestService pullRequests = mock(PullRequestService.class);
        PullRequestRefreshService service = service(pullRequests);
        RepoRef repository = RepoRef.of("owner", "repo");

        service.refreshRepository(repository);

        verify(pullRequests).syncRepository(repository);
    }

    @Test
    void failedDashboardRefreshCanBeRetried()
    {
        PullRequestService pullRequests = mock(PullRequestService.class);
        doThrow(new IllegalStateException("provider unavailable"))
                .doNothing()
                .when(pullRequests).syncFromGitHub();
        PullRequestRefreshService service = service(pullRequests);

        assertThatThrownBy(service::refreshDashboard)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("provider unavailable");
        service.refreshDashboard();

        verify(pullRequests, times(2)).syncFromGitHub();
    }

    @Test
    void serializesDashboardAndRepositoryRefreshes()
            throws Exception
    {
        PullRequestService pullRequests = mock(PullRequestService.class);
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
            PullRequestRefreshService service = service(pullRequests);
            var dashboard = executor.submit(service::refreshDashboard);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            var repositoryRefresh = executor.submit(() -> service.refreshRepository(repository));
            assertThat(repositoryRefreshed.await(100, TimeUnit.MILLISECONDS)).isFalse();
            release.countDown();
            dashboard.get(5, TimeUnit.SECONDS);
            repositoryRefresh.get(5, TimeUnit.SECONDS);
            assertThat(repositoryRefreshed.await(5, TimeUnit.SECONDS)).isTrue();
        }

        verify(pullRequests).syncFromGitHub();
        verify(pullRequests).syncRepository(repository);
    }

    private static PullRequestDetail detail()
    {
        return new PullRequestDetail(
                12L,
                "owner/repo",
                7,
                "Title",
                "https://github.test/owner/repo/pull/7",
                "open",
                false,
                true,
                "clean",
                "alice",
                null,
                null,
                null,
                null,
                "main",
                "feature",
                "Body",
                ImmutableList.of(),
                ImmutableList.of(),
                null,
                ImmutableList.of(),
                1,
                1,
                1,
                0,
                PullRequestDetail.CiStatus.PASSING,
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                true);
    }

    private static PullRequestRefreshService service(PullRequestService pullRequests)
    {
        return new PullRequestRefreshService(
                pullRequests, credentials(credential("token-a", "alice")));
    }

    private static CredentialService credentials(GitHubCredential credential)
    {
        CredentialService credentials = mock(CredentialService.class);
        when(credentials.resolveGitHubCredential()).thenReturn(credential);
        return credentials;
    }

    private static GitHubCredential credential(String token, String login)
    {
        return new GitHubCredential(
                token,
                new UserProfile(login, login, "", "", 0, 0, 0, "", "", "", ""));
    }
}
