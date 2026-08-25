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
import com.bytequay.app.repository.github.GitHubRepoClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepositoryIssueDetail;
import com.bytequay.core.domain.RepositoryIssuePage;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.RepositoryIssueDetailStore;
import com.bytequay.core.store.RepositoryIssueStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.util.concurrent.Striped;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.locks.Lock;

import static com.bytequay.app.repository.github.GitHubRateLimitException.isRateLimit;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** Fetches issue projections from GitHub and persists them before returning local snapshots. */
@Service
public class WorkspaceIssueRefreshService
{
    private static final Logger log = LoggerFactory.getLogger(WorkspaceIssueRefreshService.class);
    private static final int MAX_SYNC_PAGES = 50;

    private final WorkspaceStore workspaces;
    private final RepositoryIssueStore issues;
    private final RepositoryIssueDetailStore details;
    private final GitHubRepoClient gitHub;
    private final GitHubPullRequestReadClient issueReads;
    private final CredentialService credentials;
    private final Clock clock;
    private final Striped<Lock> locks = Striped.lock(64);

    public WorkspaceIssueRefreshService(
            WorkspaceStore workspaces,
            RepositoryIssueStore issues,
            RepositoryIssueDetailStore details,
            GitHubRepoClient gitHub,
            GitHubPullRequestReadClient issueReads,
            CredentialService credentials,
            Clock clock)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.issues = requireNonNull(issues, "issues is null");
        this.details = requireNonNull(details, "details is null");
        this.gitHub = requireNonNull(gitHub, "gitHub is null");
        this.issueReads = requireNonNull(issueReads, "issueReads is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public RepositoryIssuePage refreshIssues(String workspaceId)
    {
        Workspace workspace = requireWorkspace(workspaceId);
        Lock lock = locks.get(workspace.monitoredRepositoryId());
        lock.lock();
        try {
            refreshIssueRows(workspace);
            return issues.findPage(
                    workspace.monitoredRepositoryId(), workspace.monitoredRepository(), 0, 50);
        }
        catch (RuntimeException failure) {
            throw refreshFailure(workspaceId, failure);
        }
        finally {
            lock.unlock();
        }
    }

    public RepositoryIssueDetail refreshDetail(String workspaceId, int issueNumber)
    {
        Workspace workspace = requireWorkspace(workspaceId);
        checkArgument(issueNumber > 0, "issueNumber must be positive");
        Lock lock = locks.get(workspace.monitoredRepositoryId() + ":" + issueNumber);
        lock.lock();
        try {
            String token = credentials.resolveGitHubToken();
            RepositoryIssueDetail detail = new RepositoryIssueDetail(
                    workspace.monitoredRepositoryId(),
                    issueNumber,
                    fetchTimeline(token, issueRef(workspace, issueNumber)),
                    gitHub.fetchIssueType(token, workspace.monitoredRepository(), issueNumber),
                    gitHub.fetchIssueTypes(token, workspace.monitoredRepository()),
                    java.util.Optional.of(clock.instant()));
            details.save(detail);
            return details.find(workspace.monitoredRepositoryId(), issueNumber)
                    .orElseThrow(() -> new IllegalStateException("Issue detail refresh was not persisted"));
        }
        catch (RuntimeException failure) {
            throw refreshFailure(workspaceId, failure);
        }
        finally {
            lock.unlock();
        }
    }

    private void refreshIssueRows(Workspace workspace)
    {
        String token = credentials.resolveGitHubToken();
        String syncMarker = UUID.randomUUID().toString();
        for (int page = 1; ; page++) {
            GitHubRepoClient.IssuePage fetched = gitHub.fetchOpenIssuesPage(
                    token, workspace.monitoredRepository(), page);
            issues.saveSyncPage(workspace.monitoredRepositoryId(), fetched.issues(), syncMarker);
            if (!fetched.hasNext()) {
                break;
            }
            if (page == MAX_SYNC_PAGES) {
                throw new IllegalStateException("GitHub issue refresh exceeded 5000 rows");
            }
        }
        issues.completeSync(
                workspace.monitoredRepositoryId(),
                workspace.monitoredRepository(),
                syncMarker,
                clock.instant());
    }

    private List<PrTimelineEvent> fetchTimeline(String token, PullRequestRef issue)
    {
        return java.util.stream.Stream.concat(
                        issueReads.fetchPrTimeline(token, issue, null).stream()
                                .filter(event -> !"commented".equals(event.event())),
                        issueReads.fetchPrIssueComments(token, issue, null).stream())
                .sorted(Comparator.comparing(
                        PrTimelineEvent::timestamp,
                        Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(toImmutableList());
    }

    private RuntimeException refreshFailure(String workspaceId, RuntimeException failure)
    {
        if (isRateLimit(failure)) {
            return failure;
        }
        String reference = UUID.randomUUID().toString();
        log.warn("Workspace {} issue refresh failed [{}]: {}", workspaceId, reference, failure.getMessage());
        return WorkspaceException.issueSyncFailed(reference);
    }

    private static PullRequestRef issueRef(Workspace workspace, int issueNumber)
    {
        return PullRequestRef.of(
                workspace.monitoredRepository().owner(), workspace.monitoredRepository().repo(), issueNumber);
    }

    private Workspace requireWorkspace(String workspaceId)
    {
        requireNonNull(workspaceId, "workspaceId is null");
        return workspaces.findById(workspaceId)
                .orElseThrow(() -> WorkspaceException.notFound(workspaceId));
    }
}
