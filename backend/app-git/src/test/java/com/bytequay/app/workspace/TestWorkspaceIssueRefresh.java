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

import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.app.repository.github.GitHubPullRequestWriteClient;
import com.bytequay.app.repository.github.GitHubRateLimitException;
import com.bytequay.app.repository.github.GitHubRepoClient;
import com.bytequay.app.service.hosting.GitHubIssueClient;
import com.bytequay.app.service.recent.RecentTouchRecorder;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryIssueDetail;
import com.bytequay.core.domain.RepositoryIssuePage;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.domain.Workspace.Topology;
import com.bytequay.core.store.AppSettingsStore;
import com.bytequay.core.store.RepositoryIssueDetailStore;
import com.bytequay.core.store.RepositoryIssueStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.bytequay.app.workspace.WorkspaceIssueTestData.NOW;
import static com.bytequay.app.workspace.WorkspaceIssueTestData.event;
import static com.bytequay.app.workspace.WorkspaceIssueTestData.issue;
import static com.bytequay.app.workspace.WorkspaceIssueTestData.workspace;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestWorkspaceIssueRefresh
{
    private static final RepoRef DIRECT = RepoRef.of("owner", "project");
    private static final RepoRef UPSTREAM = RepoRef.of("upstream", "project");

    private final Map<String, Workspace> workspaces = new HashMap<>();
    private final AppSettingsStore settings = mock(AppSettingsStore.class);
    private final WorkspaceStore workspaceStore = mock(WorkspaceStore.class);
    private final RepositoryIssueStore issueStore = mock(RepositoryIssueStore.class);
    private final RepositoryIssueDetailStore detailStore = mock(RepositoryIssueDetailStore.class);
    private final GitHubRepoClient gitHub = mock(GitHubRepoClient.class);
    private final GitHubPullRequestReadClient issueReads = mock(GitHubPullRequestReadClient.class);
    private final WorkspaceHostingService hosting = mock(WorkspaceHostingService.class);
    private WorkspaceIssueService service;

    @BeforeEach
    void setUp()
    {
        when(workspaceStore.findAll()).thenAnswer(invocation -> ImmutableList.copyOf(workspaces.values()));
        when(workspaceStore.findById(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(workspaces.get(invocation.getArgument(0))));
        when(workspaceStore.findHostingRepositoryId(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(workspaces.get(invocation.getArgument(0))).map(Workspace::monitoredRepositoryId));
        when(hosting.captureRead(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> WorkspaceIssueTestData.route(workspaces.get(invocation.getArgument(0))));
        service = new WorkspaceIssueService(workspaceStore, issueStore, detailStore, hosting,
                ImmutableList.of(new GitHubIssueClient(gitHub, issueReads, mock(GitHubPullRequestWriteClient.class))),
                mock(RecentTouchRecorder.class), Clock.fixed(NOW, ZoneOffset.UTC), settings);
    }

    @Test
    void deletionCannotAcquireWorkspaceWhileIssueRefreshWritesItsCache()
            throws Exception
    {
        workspaces.put("fork", workspace("fork", 300, UPSTREAM, Topology.FORK));
        CountDownLatch fetching = new CountDownLatch(1);
        CountDownLatch finishFetch = new CountDownLatch(1);
        when(gitHub.fetchOpenIssuesPage("token", UPSTREAM, 1)).thenAnswer(invocation -> {
            fetching.countDown();
            assertThat(finishFetch.await(5, TimeUnit.SECONDS)).isTrue();
            return new GitHubRepoClient.IssuePage(ImmutableList.of(issue(1)), false);
        });
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var refresh = executor.submit(() -> service.refreshIssues("fork"));
            try {
                assertThat(fetching.await(5, TimeUnit.SECONDS)).isTrue();
                var lock = WorkspaceOperationLocks.get("fork");
                boolean acquired = lock.tryLock();
                if (acquired) {
                    lock.unlock();
                }
                assertThat(acquired).isFalse();
            }
            finally {
                finishFetch.countDown();
            }
            refresh.get(5, TimeUnit.SECONDS);
        }
        verify(issueStore).completeSync(org.mockito.ArgumentMatchers.eq(300L), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(NOW));
    }

    @Test
    void refreshesTheMonitoredRepositoryAndReturnsThePersistedPage()
    {
        Workspace fork = workspace("fork", 300, UPSTREAM, Topology.FORK);
        RepositoryIssuePage persisted = new RepositoryIssuePage(300, UPSTREAM, ImmutableList.of(issue(1)), 0, 50, 1, false, Optional.of(NOW));
        workspaces.put("fork", fork);
        when(gitHub.fetchOpenIssuesPage("token", UPSTREAM, 1))
                .thenReturn(new GitHubRepoClient.IssuePage(ImmutableList.of(issue(1)), false));
        when(issueStore.findPage(300, UPSTREAM, 0, 50)).thenReturn(persisted);

        assertThat(service.refreshIssues("fork")).isSameAs(persisted);
        verify(issueStore).saveSyncPage(org.mockito.ArgumentMatchers.eq(300L), org.mockito.ArgumentMatchers.eq(ImmutableList.of(issue(1))));
        verify(issueStore).completeSync(org.mockito.ArgumentMatchers.eq(300L), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq(NOW));
    }

    @Test
    void runsAFullSyncOnlyWhenTheStoredIssuesAreOlderThanAsked()
    {
        workspaces.put("direct", workspace("direct", 100, DIRECT, Topology.DIRECT));
        when(gitHub.fetchOpenIssuesPage("token", DIRECT, 1)).thenReturn(new GitHubRepoClient.IssuePage(ImmutableList.of(issue(1), issue(2)), false));
        when(issueStore.findPage(100, DIRECT, 0, 1))
                .thenReturn(new RepositoryIssuePage(100, DIRECT, ImmutableList.of(), 0, 1, 0, false, Optional.of(NOW.minus(Duration.ofHours(3)))));

        service.refreshIssuesOlderThan("direct", Duration.ofHours(4));
        verifyNoInteractions(gitHub);

        when(issueStore.findPage(100, DIRECT, 0, 1))
                .thenReturn(new RepositoryIssuePage(100, DIRECT, ImmutableList.of(), 0, 1, 0, false, Optional.of(NOW.minus(Duration.ofHours(5)))));
        service.refreshIssuesOlderThan("direct", Duration.ofHours(4));
        verify(issueStore).completeSync(100L, ImmutableSet.of(issue(1).id(), issue(2).id()), NOW);
    }

    @Test
    void writesEachGitHubPageBeforeRequestingTheNextOne()
    {
        workspaces.put("direct", workspace("direct", 100, DIRECT, Topology.DIRECT));
        when(gitHub.fetchOpenIssuesPage("token", DIRECT, 1))
                .thenReturn(new GitHubRepoClient.IssuePage(ImmutableList.of(issue(1)), true));
        when(gitHub.fetchOpenIssuesPage("token", DIRECT, 2))
                .thenReturn(new GitHubRepoClient.IssuePage(ImmutableList.of(issue(2)), false));

        service.refreshIssues("direct");

        var order = inOrder(gitHub, issueStore);
        order.verify(gitHub).fetchOpenIssuesPage("token", DIRECT, 1);
        order.verify(issueStore).saveSyncPage(org.mockito.ArgumentMatchers.eq(100L), org.mockito.ArgumentMatchers.eq(ImmutableList.of(issue(1))));
        order.verify(gitHub).fetchOpenIssuesPage("token", DIRECT, 2);
        order.verify(issueStore).saveSyncPage(org.mockito.ArgumentMatchers.eq(100L), org.mockito.ArgumentMatchers.eq(ImmutableList.of(issue(2))));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void providerFailureLeavesThePreviousCacheIntact(boolean detail)
    {
        workspaces.put("direct", workspace("direct", 100, DIRECT, Topology.DIRECT));
        when(gitHub.fetchOpenIssuesPage("token", DIRECT, 1))
                .thenThrow(new IllegalStateException("provider down"));
        when(issueReads.fetchPrTimeline("token", PullRequestRef.of("owner", "project", 7)))
                .thenThrow(new IllegalStateException("provider down"));

        assertThatThrownBy(() -> {
            if (detail) {
                service.refreshDetail("direct", 7);
            }
            else {
                service.refreshIssues("direct");
            }
        })
                .isInstanceOfSatisfying(WorkspaceException.class, failure -> assertThat(failure.code()).isEqualTo("ISSUE_SYNC_FAILED"));
        verify(issueStore, never()).completeSync(org.mockito.ArgumentMatchers.anyLong(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
        verify(detailStore, never()).save(any());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void preservesRateLimitFailures(boolean detail)
    {
        workspaces.put("direct", workspace("direct", 100, DIRECT, Topology.DIRECT));
        GitHubRateLimitException rateLimit = new GitHubRateLimitException("core", NOW.plusSeconds(60).getEpochSecond());
        when(gitHub.fetchOpenIssuesPage("token", DIRECT, 1)).thenThrow(rateLimit);
        when(issueReads.fetchPrTimeline("token", PullRequestRef.of("owner", "project", 7))).thenThrow(rateLimit);

        assertThatThrownBy(() -> {
            if (detail) {
                service.refreshDetail("direct", 7);
            }
            else {
                service.refreshIssues("direct");
            }
        }).isSameAs(rateLimit);
    }

    @Test
    void refreshesIssueActivityAndTypeBeforeRereadingTheSnapshot()
    {
        workspaces.put("direct", workspace("direct", 100, DIRECT, Topology.DIRECT));
        PullRequestRef ref = PullRequestRef.of("owner", "project", 7);
        PrTimelineEvent assigned = event(1, "assigned", NOW.minusSeconds(20));
        PrTimelineEvent duplicateComment = event(2, "commented", NOW.minusSeconds(10));
        PrTimelineEvent comment = event(2, "commented", NOW.minusSeconds(10));
        RepositoryIssueDetail expected = new RepositoryIssueDetail(100, 7, ImmutableList.of(assigned, comment), "Task", ImmutableList.of("Task", "Bug"), Optional.of(NOW));
        when(issueReads.fetchPrTimeline("token", ref))
                .thenReturn(ImmutableList.of(assigned, duplicateComment));
        when(issueReads.fetchPrIssueComments("token", ref)).thenReturn(ImmutableList.of(comment));
        when(gitHub.fetchIssueType("token", DIRECT, 7)).thenReturn("Task");
        when(gitHub.fetchIssueTypes("token", DIRECT)).thenReturn(ImmutableList.of("Task", "Bug"));
        when(detailStore.find(100, 7)).thenReturn(Optional.of(expected));

        assertThat(service.refreshDetail("direct", 7)).isSameAs(expected);
        verify(detailStore).save(expected);
    }

    @Test
    void notificationUpdatesOnlyTheWatchedRepositoryOnceWithoutReadingFullDetails()
    {
        Workspace first = workspace("first", 300, UPSTREAM, Topology.FORK);
        Workspace second = workspace("second", 300, UPSTREAM, Topology.FORK);
        workspaces.put(first.id(), first);
        workspaces.put(second.id(), second);
        workspaces.put("other", workspace("other", 100, DIRECT, Topology.DIRECT));
        when(gitHub.fetchIssue("token", UPSTREAM, 7)).thenReturn(Optional.of(issue(7)));

        service.refreshNotifiedIssue(UPSTREAM, 7);

        verify(gitHub, times(1)).fetchIssue("token", UPSTREAM, 7);
        verify(issueStore).saveSyncPage(eq(300L), eq(ImmutableList.of(issue(7))));
        verify(issueStore, never()).saveSyncPage(eq(100L), any());
        verifyNoInteractions(issueReads, detailStore, settings);
    }

    @Test
    void notifiedClosureRemovesTheCardAndUnwatchedNotificationsDoNotFetch()
    {
        workspaces.put("direct", workspace("direct", 100, DIRECT, Topology.DIRECT));
        when(gitHub.fetchIssue("token", DIRECT, 7)).thenReturn(Optional.empty());

        service.refreshNotifiedIssue(UPSTREAM, 7);
        verifyNoInteractions(gitHub);
        service.refreshNotifiedIssue(DIRECT, 7);

        verify(issueStore).delete(100, 7);
        verify(issueStore, never()).completeSync(anyLong(), any(), any());
        verifyNoInteractions(issueReads, detailStore);
    }

    @Test
    void recentChangesApplyPagesBeforeAdvancingCheckpointWithoutPruningOtherOpenCards()
    {
        workspaces.put("direct", workspace("direct", 100, DIRECT, Topology.DIRECT));
        String key = "hosting.issues.checkpoint.1:connected-account.100";
        Instant previous = NOW.minusSeconds(600);
        Instant since = previous.minusSeconds(300);
        when(settings.get(key)).thenReturn(Optional.of(previous.toString()));
        when(gitHub.fetchChangedIssuesPage("token", DIRECT, since, 1))
                .thenReturn(new GitHubRepoClient.ChangedIssuePage(ImmutableList.of(issue(7)), ImmutableList.of(8), true));
        when(gitHub.fetchChangedIssuesPage("token", DIRECT, since, 2))
                .thenReturn(new GitHubRepoClient.ChangedIssuePage(ImmutableList.of(issue(9)), ImmutableList.of(), false));

        service.refreshRecentIssues();

        var order = inOrder(gitHub, issueStore, settings);
        order.verify(settings).get(key);
        order.verify(gitHub).fetchChangedIssuesPage("token", DIRECT, since, 1);
        order.verify(issueStore).saveSyncPage(eq(100L), eq(ImmutableList.of(issue(7))));
        order.verify(issueStore).delete(100, 8);
        order.verify(gitHub).fetchChangedIssuesPage("token", DIRECT, since, 2);
        order.verify(issueStore).saveSyncPage(eq(100L), eq(ImmutableList.of(issue(9))));
        order.verify(settings).put(key, NOW.toString());
        verify(issueStore, never()).completeSync(anyLong(), any(), any());
        verifyNoInteractions(issueReads, detailStore);
    }

    @Test
    void firstRecentPassUsesOneHourAndFailedPageKeepsCheckpointWhileOtherRepositoriesContinue()
    {
        Workspace direct = workspace("direct", 100, DIRECT, Topology.DIRECT);
        Workspace upstream = workspace("upstream", 300, UPSTREAM, Topology.FORK);
        workspaces.put(direct.id(), direct);
        workspaces.put(upstream.id(), upstream);
        when(workspaceStore.findAll()).thenReturn(ImmutableList.of(direct, upstream));
        Instant since = NOW.minusSeconds(3600);
        when(gitHub.fetchChangedIssuesPage("token", DIRECT, since, 1))
                .thenReturn(new GitHubRepoClient.ChangedIssuePage(ImmutableList.of(issue(7)), ImmutableList.of(), true));
        when(gitHub.fetchChangedIssuesPage("token", DIRECT, since, 2)).thenThrow(new IllegalStateException("temporary failure"));
        when(gitHub.fetchChangedIssuesPage("token", UPSTREAM, since, 1))
                .thenReturn(new GitHubRepoClient.ChangedIssuePage(ImmutableList.of(issue(9)), ImmutableList.of(), false));

        service.refreshRecentIssues();

        verify(issueStore).saveSyncPage(eq(100L), eq(ImmutableList.of(issue(7))));
        verify(settings, never()).put(eq("hosting.issues.checkpoint.1:connected-account.100"), anyString());
        verify(settings).put("hosting.issues.checkpoint.1:connected-account.300", NOW.toString());
    }

    @Test
    void recentPassSkipsFreshCheckpointsAndSharesOnePassAcrossWatchedWorkspaces()
    {
        Workspace first = workspace("first", 100, DIRECT, Topology.DIRECT);
        Workspace second = workspace("second", 100, DIRECT, Topology.FORK);
        workspaces.put(first.id(), first);
        workspaces.put(second.id(), second);
        when(settings.get("hosting.issues.checkpoint.1:connected-account.100")).thenReturn(Optional.of(NOW.minusSeconds(119).toString()));

        service.refreshRecentIssues();

        verify(settings, times(1)).get("hosting.issues.checkpoint.1:connected-account.100");
        verifyNoInteractions(gitHub, issueStore);
    }

    @Test
    void futureCheckpointFallsBackToTheRecentHourAfterAClockCorrection()
    {
        workspaces.put("direct", workspace("direct", 100, DIRECT, Topology.DIRECT));
        String key = "hosting.issues.checkpoint.1:connected-account.100";
        when(settings.get(key)).thenReturn(Optional.of(NOW.plusSeconds(86400).toString()));
        when(gitHub.fetchChangedIssuesPage("token", DIRECT, NOW.minusSeconds(3600), 1))
                .thenReturn(new GitHubRepoClient.ChangedIssuePage(ImmutableList.of(), ImmutableList.of(), false));

        service.refreshRecentIssues();

        verify(gitHub).fetchChangedIssuesPage("token", DIRECT, NOW.minusSeconds(3600), 1);
        verify(settings).put(key, NOW.toString());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("repositoryFailures")
    void authenticationOrRateLimitFailureStopsTheRepositoryLoopWithoutAdvancingCheckpoint(String reason, RuntimeException failure)
    {
        Workspace direct = workspace("direct", 100, DIRECT, Topology.DIRECT);
        Workspace upstream = workspace("upstream", 300, UPSTREAM, Topology.FORK);
        workspaces.put(direct.id(), direct);
        workspaces.put(upstream.id(), upstream);
        when(workspaceStore.findAll()).thenReturn(ImmutableList.of(direct, upstream));
        when(gitHub.fetchChangedIssuesPage("token", DIRECT, NOW.minusSeconds(3600), 1)).thenThrow(failure);

        assertThatThrownBy(service::refreshRecentIssues).as(reason).isSameAs(failure);

        verify(gitHub, never()).fetchChangedIssuesPage(eq("token"), eq(UPSTREAM), any(), org.mockito.ArgumentMatchers.anyInt());
        verify(settings, never()).put(anyString(), anyString());
    }

    private static Stream<Arguments> repositoryFailures()
    {
        return Stream.of(
                Arguments.of("authentication failure", new ResponseStatusException(HttpStatus.UNAUTHORIZED)),
                Arguments.of("rate limit", new GitHubRateLimitException("core", NOW.plusSeconds(60).getEpochSecond())));
    }
}
