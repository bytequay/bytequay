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

import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.PullRequestDetailRefresh;
import com.bytequay.core.domain.RepoRef;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestPullRequestRefreshService
{
    @Test
    void directDetailRefreshPersistsBeforeReading()
    {
        PullRequestService pullRequests = mock(PullRequestService.class);
        PullRequestRefreshService service = new PullRequestRefreshService(pullRequests);
        when(pullRequests.getPullRequestDetail("owner/repo", 7)).thenReturn(detail());

        PullRequestDetailRefresh result = service.refreshDetail("owner/repo", 7);

        assertThat(result.full()).isFalse();
        assertThat(result.status().ciStatus()).isEqualTo(PullRequestDetail.CiStatus.PASSING);

        InOrder order = inOrder(pullRequests);
        order.verify(pullRequests).refreshPullRequestDetailFromGitHub("owner/repo", 7);
        order.verify(pullRequests).getPullRequestDetail("owner/repo", 7);
    }

    @Test
    void idDetailRefreshPersistsBeforeReading()
    {
        PullRequestService pullRequests = mock(PullRequestService.class);
        PullRequestRefreshService service = new PullRequestRefreshService(pullRequests);
        when(pullRequests.refreshPullRequestDetailFromGitHub(12L)).thenReturn(true);
        when(pullRequests.getPullRequestDetail(12L)).thenReturn(detail());

        assertThat(service.refreshDetail(12L).full()).isTrue();

        InOrder order = inOrder(pullRequests);
        order.verify(pullRequests).refreshPullRequestDetailFromGitHub(12L);
        order.verify(pullRequests).getPullRequestDetail(12L);
    }

    @Test
    void manualDetailRefreshAlwaysUsesTheFullRefreshPath()
    {
        PullRequestService pullRequests = mock(PullRequestService.class);
        when(pullRequests.getPullRequestDetail(12L)).thenReturn(detail());
        PullRequestRefreshService service = new PullRequestRefreshService(pullRequests);

        assertThat(service.refreshAllDetail(12L)).isEqualTo(detail());

        InOrder order = inOrder(pullRequests);
        order.verify(pullRequests).refreshAllPullRequestDetailFromGitHub(12L);
        order.verify(pullRequests).getPullRequestDetail(12L);
    }

    @Test
    void directDiffRefreshPersistsBeforeReading()
    {
        PullRequestService pullRequests = mock(PullRequestService.class);
        DiffFile file = new DiffFile("src/App.java", "modified", 1, 1, "patch");
        when(pullRequests.getPullRequestDiffFiles("owner/repo", 7, "base", "head"))
                .thenReturn(ImmutableList.of(file));
        PullRequestRefreshService service = new PullRequestRefreshService(pullRequests);

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
        PullRequestRefreshService service = new PullRequestRefreshService(pullRequests);

        service.refreshDashboard();

        verify(pullRequests).syncFromGitHub();
    }

    @Test
    void refreshesOneRepositoryBeforeReturning()
    {
        PullRequestService pullRequests = mock(PullRequestService.class);
        PullRequestRefreshService service = new PullRequestRefreshService(pullRequests);
        RepoRef repository = RepoRef.of("owner", "repo");

        service.refreshRepository(repository);

        verify(pullRequests).syncRepository(repository);
    }

    @Test
    void refreshFailureIsReturnedToTheCaller()
    {
        PullRequestService pullRequests = mock(PullRequestService.class);
        doThrow(new IllegalStateException("provider unavailable")).when(pullRequests).syncFromGitHub();
        PullRequestRefreshService service = new PullRequestRefreshService(pullRequests);

        assertThatThrownBy(service::refreshDashboard)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("provider unavailable");
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
            PullRequestRefreshService service = new PullRequestRefreshService(pullRequests);
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
}
