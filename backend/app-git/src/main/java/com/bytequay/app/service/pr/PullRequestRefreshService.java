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
import com.bytequay.app.workspace.WorkspaceOperationLocks;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.DiffFileContent;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.PullRequestDetailRefresh;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.store.WorkspaceStore;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static com.bytequay.app.utils.Locks.withLock;
import static java.util.Objects.requireNonNull;

/** Coordinates scheduled and user-requested pull-request refreshes. */
@Service
public class PullRequestRefreshService
{
    private static final Duration DASHBOARD_FRESHNESS = Duration.ofMinutes(6);

    private final PullRequestService pullRequests;
    private final CredentialService credentials;
    private final WorkspaceStore workspaces;
    private final ApplicationEventPublisher events;
    private final Lock refreshLock = new ReentrantLock();
    private volatile DashboardSnapshot dashboardSnapshot;

    public PullRequestRefreshService(PullRequestService pullRequests, CredentialService credentials, WorkspaceStore workspaces, ApplicationEventPublisher events)
    {
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.events = requireNonNull(events, "events is null");
    }

    /** Tells an open review what the refresh established, so it can follow a moved head. */
    private void published(PullRequestRead route, long pullRequestId)
    {
        events.publishEvent(new PullRequestDetailRefreshed(pullRequestId, route.repository().fullPath(), route.number(), pullRequests.findHeadSha(route)));
    }

    /** Conditionally refreshes one pull request and returns only the data changed by that refresh. */
    public PullRequestDetailRefresh refreshDetail(String repo, int number)
    {
        return refreshDetail(pullRequests.prepareRead(repo, number));
    }

    public PullRequestDetailRefresh refreshDetail(PullRequestRead route)
    {
        PullRequestService.DetailRefresh refresh = pullRequests.refreshPullRequestDetailFromGitHub(route);
        published(route, refresh.detail().id());
        return refresh.full() ? PullRequestDetailRefresh.full(refresh.detail()) : PullRequestDetailRefresh.status(refresh.detail());
    }

    public PullRequestDetailRefresh refreshChecks(String repo, int number)
    {
        return refreshChecks(pullRequests.prepareRead(repo, number));
    }

    public PullRequestDetailRefresh refreshChecks(PullRequestRead route)
    {
        PullRequestService.DetailRefresh refresh = pullRequests.refreshPullRequestChecksFromGitHub(route);
        published(route, refresh.detail().id());
        return refresh.full() ? PullRequestDetailRefresh.full(refresh.detail()) : PullRequestDetailRefresh.status(refresh.detail());
    }

    public PullRequestDetailRefresh refreshOpenedDetail(String repo, int number)
    {
        return refreshOpenedDetail(pullRequests.prepareRead(repo, number));
    }

    public PullRequestDetailRefresh refreshOpenedDetail(PullRequestRead route)
    {
        PullRequestService.DetailRefresh refresh = pullRequests.refreshOpenedPullRequestDetailFromGitHub(route);
        published(route, refresh.detail().id());
        // Another view may have refreshed the cache while this view was hidden.
        return PullRequestDetailRefresh.full(refresh.detail());
    }

    public void warmRecentDetails(int limit)
    {
        pullRequests.warmRecentDetails(limit);
    }

    /** Refreshes one pull request as the background warm pass does, then publishes what is stored, a head the pass moved included. */
    public void warmDetail(String workspaceId, String repo, int number)
    {
        PullRequestRead route = pullRequests.prepareRead(workspaceId, repo, number);
        PullRequestService.DetailRefresh refresh = pullRequests.refreshPullRequestDetailFromGitHub(route, Duration.ofMinutes(10));
        published(route, refresh.detail().id());
    }

    /** Bypasses conditional requests and rebuilds the complete local pull-request snapshot. */
    public PullRequestDetail refreshAllDetail(String repo, int number)
    {
        return refreshAllDetail(pullRequests.prepareRead(repo, number));
    }

    public PullRequestDetail refreshAllDetail(PullRequestRead route)
    {
        PullRequestDetail detail = pullRequests.refreshAllPullRequestDetailFromGitHub(route);
        published(route, detail.id());
        return detail;
    }

    /** Refreshes one diff snapshot and returns the newly persisted files. */
    public List<DiffFile> refreshDiffFiles(String repo, int number, String baseSha, String headSha)
    {
        return refreshDiffFiles(pullRequests.prepareRead(repo, number), baseSha, headSha);
    }

    public List<DiffFile> refreshDiffFiles(PullRequestRead route, String baseSha, String headSha)
    {
        pullRequests.refreshPullRequestDiffFromGitHub(route, baseSha, headSha);
        return pullRequests.getPullRequestDiffFiles(route, baseSha, headSha);
    }

    /** Refreshes one file body and returns the newly persisted content. */
    public DiffFileContent refreshDiffFileContent(String repo, int number, String path, String baseSha, String headSha)
    {
        return refreshDiffFileContent(pullRequests.prepareRead(repo, number), path, baseSha, headSha);
    }

    public DiffFileContent refreshDiffFileContent(PullRequestRead route, String path, String baseSha, String headSha)
    {
        pullRequests.refreshPullRequestDiffFileContentFromGitHub(route, path, baseSha, headSha);
        return pullRequests.getPullRequestDiffFileContent(route, path, baseSha, headSha);
    }

    /** Reuses a recent or in-flight dashboard refresh instead of queuing the same GitHub scan again. */
    public void refreshDashboard()
    {
        GitHubCredential credential = credentials.resolveGitHubCredential();
        if (!dashboardIsFresh(credential)) {
            refresh(this::refreshDashboardIfStale);
            events.publishEvent(new PullRequestsRefreshed());
        }
    }

    /** The scheduler already enforces its interval; a completed-scan cache must not skip the next due scan. */
    public void refreshScheduledDashboard()
    {
        refresh(() -> {
            GitHubCredential credential = credentials.resolveGitHubCredential();
            pullRequests.syncFromGitHub();
            if (credentials.resolveGitHubCredential() == credential) {
                dashboardSnapshot = new DashboardSnapshot(credential, Instant.now());
            }
        });
        events.publishEvent(new PullRequestsRefreshed());
    }

    /** Refreshes discovered rows and the bounded warm-detail set for each repository. */
    public void refreshDashboardRows()
    {
        if (!refreshLock.tryLock()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "A pull request refresh is already in progress; try again shortly");
        }
        try {
            pullRequests.syncFromGitHub();
        }
        finally {
            refreshLock.unlock();
        }
        workspaces.findAll().forEach(workspace -> refreshWorkspace(workspace.id()));
        events.publishEvent(new PullRequestsRefreshed());
    }

    private void refreshDashboardIfStale()
    {
        GitHubCredential credential = credentials.resolveGitHubCredential();
        if (dashboardIsFresh(credential)) {
            return;
        }
        pullRequests.syncFromGitHub();
        // A credential change during the scan must leave the new account stale.
        if (credentials.resolveGitHubCredential() == credential) {
            dashboardSnapshot = new DashboardSnapshot(credential, Instant.now());
        }
    }

    /** Refreshes one repository before returning. */
    public void refreshRepository(RepoRef repository)
    {
        requireNonNull(repository, "repository is null");
        refresh(() -> pullRequests.syncRepository(repository));
    }

    /** Captures current routing locally; repository-scoped cache refreshes do not own workspace files or lifecycle. */
    public void refreshWorkspace(String workspaceId)
    {
        boolean watched = WorkspaceOperationLocks.withLock(workspaceId, () -> workspaces.isWatched(workspaceId) && workspaces.findById(workspaceId).isPresent());
        if (watched) {
            refresh(() -> pullRequests.syncWorkspace(workspaceId));
        }
    }

    private void refresh(Runnable action)
    {
        withLock(refreshLock, () -> {
            action.run();
            return null;
        });
    }

    private boolean dashboardIsFresh(GitHubCredential credential)
    {
        DashboardSnapshot snapshot = dashboardSnapshot;
        return snapshot != null
                // CredentialService replaces the cached record when the token generation changes.
                && snapshot.credential() == credential
                && snapshot.refreshedAt().isAfter(Instant.now().minus(DASHBOARD_FRESHNESS));
    }

    private record DashboardSnapshot(GitHubCredential credential, Instant refreshedAt) {}
}
