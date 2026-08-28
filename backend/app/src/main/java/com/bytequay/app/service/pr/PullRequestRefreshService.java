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
import com.bytequay.core.domain.DiffFileContent;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.PullRequestDetailRefresh;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.WorkspaceStore;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static java.util.Objects.requireNonNull;

/** Coordinates scheduled and user-requested pull-request refreshes. */
@Service
public class PullRequestRefreshService
{
    private static final Duration DASHBOARD_FRESHNESS = Duration.ofMinutes(6);

    private final PullRequestService pullRequests;
    private final CredentialService credentials;
    private final WorkspaceStore workspaces;
    private final Lock refreshLock = new ReentrantLock();
    private volatile DashboardSnapshot dashboardSnapshot;

    public PullRequestRefreshService(
            PullRequestService pullRequests,
            CredentialService credentials,
            WorkspaceStore workspaces)
    {
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
    }

    /** Conditionally refreshes one pull request and returns only the data changed by that refresh. */
    public PullRequestDetailRefresh refreshDetail(String repo, int number)
    {
        PullRequestService.DetailRefresh refresh = pullRequests.refreshPullRequestDetailFromGitHub(repo, number);
        return refresh.full() ? PullRequestDetailRefresh.full(refresh.detail()) : PullRequestDetailRefresh.status(refresh.detail());
    }

    /** Bypasses conditional requests and rebuilds the complete local pull-request snapshot. */
    public PullRequestDetail refreshAllDetail(String repo, int number)
    {
        return pullRequests.refreshAllPullRequestDetailFromGitHub(repo, number);
    }

    /** Refreshes one diff snapshot and returns the newly persisted files. */
    public List<DiffFile> refreshDiffFiles(
            String repo,
            int number,
            String baseSha,
            String headSha)
    {
        pullRequests.refreshPullRequestDiffFromGitHub(repo, number, baseSha, headSha);
        return pullRequests.getPullRequestDiffFiles(repo, number, baseSha, headSha);
    }

    /** Refreshes one file body and returns the newly persisted content. */
    public DiffFileContent refreshDiffFileContent(
            String repo,
            int number,
            String path,
            String baseSha,
            String headSha)
    {
        pullRequests.refreshPullRequestDiffFileContentFromGitHub(
                repo, number, path, baseSha, headSha);
        return pullRequests.getPullRequestDiffFileContent(
                repo, number, path, baseSha, headSha);
    }

    /** Reuses a recent or in-flight dashboard refresh instead of queuing the same GitHub scan again. */
    public void refreshDashboard()
    {
        GitHubCredential credential = credentials.resolveGitHubCredential();
        if (!dashboardIsFresh(credential)) {
            refresh(this::refreshDashboardIfStale);
        }
    }

    /** Uses the existing list scans without fetching pull-request detail or diffs. */
    public void refreshDashboardRows()
    {
        if (!refreshLock.tryLock()) {
            throw new ResponseStatusException(
                    HttpStatus.CONFLICT,
                    "A pull request refresh is already in progress; try again shortly");
        }
        try {
            pullRequests.syncFromGitHub(false);
            workspaces.findAll().stream()
                    .map(Workspace::monitoredRepository)
                    .distinct()
                    .forEach(pullRequests::syncRepository);
        }
        finally {
            refreshLock.unlock();
        }
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

    private void refresh(Runnable action)
    {
        refreshLock.lock();
        try {
            action.run();
        }
        finally {
            refreshLock.unlock();
        }
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
