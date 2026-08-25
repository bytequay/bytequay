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
import com.bytequay.core.domain.DiffFileContent;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.PullRequestDetailRefresh;
import com.bytequay.core.domain.RepoRef;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import static java.util.Objects.requireNonNull;

/** Coordinates scheduled and user-requested pull-request refreshes. */
@Service
public class PullRequestRefreshService
{
    private final PullRequestService pullRequests;
    private final Lock refreshLock = new ReentrantLock();

    public PullRequestRefreshService(PullRequestService pullRequests)
    {
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
    }

    /** Conditionally refreshes one pull request and returns only the data changed by that refresh. */
    public PullRequestDetailRefresh refreshDetail(String repo, int number)
    {
        boolean full = pullRequests.refreshPullRequestDetailFromGitHub(repo, number);
        PullRequestDetail detail = pullRequests.getPullRequestDetail(repo, number);
        return full ? PullRequestDetailRefresh.full(detail) : PullRequestDetailRefresh.status(detail);
    }

    public PullRequestDetailRefresh refreshDetail(long prId)
    {
        boolean full = pullRequests.refreshPullRequestDetailFromGitHub(prId);
        PullRequestDetail detail = pullRequests.getPullRequestDetail(prId);
        return full ? PullRequestDetailRefresh.full(detail) : PullRequestDetailRefresh.status(detail);
    }

    /** Bypasses conditional requests and rebuilds the complete local pull-request snapshot. */
    public PullRequestDetail refreshAllDetail(String repo, int number)
    {
        pullRequests.refreshAllPullRequestDetailFromGitHub(repo, number);
        return pullRequests.getPullRequestDetail(repo, number);
    }

    public PullRequestDetail refreshAllDetail(long prId)
    {
        pullRequests.refreshAllPullRequestDetailFromGitHub(prId);
        return pullRequests.getPullRequestDetail(prId);
    }

    /** Refreshes one diff snapshot and returns the newly persisted files. */
    public List<DiffFile> refreshDiffFiles(
            String repo,
            int number,
            String baseSha,
            String headSha)
    {
        pullRequests.refreshPullRequestDiffFromGitHub(repo, number, baseSha, headSha);
        return baseSha == null && headSha == null
                ? pullRequests.getPullRequestDiffFiles(repo, number)
                : pullRequests.getPullRequestDiffFiles(repo, number, baseSha, headSha);
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
        return baseSha == null && headSha == null
                ? pullRequests.getPullRequestDiffFileContent(repo, number, path)
                : pullRequests.getPullRequestDiffFileContent(
                        repo, number, path, baseSha, headSha);
    }

    /** Refreshes the global dashboard before returning. */
    public void refreshDashboard()
    {
        refresh(pullRequests::syncFromGitHub);
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
}
