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
import com.bytequay.app.repository.github.GitHubRepoClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepositoryIssuePage;
import com.bytequay.core.domain.Workspace;
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

import static com.bytequay.app.utils.StringInputUtil.requireNotBlank;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Service
public final class DefaultWorkspaceIssueService
        implements WorkspaceIssueService
{
    private static final Logger log = LoggerFactory.getLogger(DefaultWorkspaceIssueService.class);
    private static final int MAX_SYNC_PAGES = 50;

    private final WorkspaceStore workspaces;
    private final RepositoryIssueStore issues;
    private final GitHubRepoClient gitHub;
    private final GitHubPullRequestReadClient issueReads;
    private final GitHubPullRequestWriteClient issueWrites;
    private final CredentialService credentials;
    private final Clock clock;
    private final Striped<Lock> locks = Striped.lock(64);

    public DefaultWorkspaceIssueService(
            WorkspaceStore workspaces,
            RepositoryIssueStore issues,
            GitHubRepoClient gitHub,
            GitHubPullRequestReadClient issueReads,
            GitHubPullRequestWriteClient issueWrites,
            CredentialService credentials,
            Clock clock)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.issues = requireNonNull(issues, "issues is null");
        this.gitHub = requireNonNull(gitHub, "gitHub is null");
        this.issueReads = requireNonNull(issueReads, "issueReads is null");
        this.issueWrites = requireNonNull(issueWrites, "issueWrites is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    public RepositoryIssuePage list(String workspaceId, int page, int pageSize)
    {
        Workspace workspace = requireWorkspace(workspaceId);
        checkArgument(page >= 0, "page is negative");
        checkArgument(pageSize > 0 && pageSize <= 100, "pageSize must be between 1 and 100");
        return issues.findPage(
                workspace.monitoredRepositoryId(),
                workspace.monitoredRepository(),
                page,
                pageSize);
    }

    @Override
    public void synchronize(String workspaceId)
    {
        Workspace workspace = requireWorkspace(workspaceId);
        Lock lock = locks.get(workspace.monitoredRepositoryId());
        lock.lock();
        try {
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
                    throw new IllegalStateException("GitHub issue sync exceeded 5000 rows");
                }
            }
            issues.completeSync(
                    workspace.monitoredRepositoryId(),
                    workspace.monitoredRepository(),
                    syncMarker,
                    clock.instant());
        }
        catch (RuntimeException failure) {
            String reference = UUID.randomUUID().toString();
            log.warn("Workspace {} issue synchronization failed [{}]: {}",
                    workspaceId, reference, failure.getMessage());
            throw WorkspaceException.issueSyncFailed(reference);
        }
        finally {
            lock.unlock();
        }
    }

    @Override
    public List<PrTimelineEvent> timeline(String workspaceId, int issueNumber)
    {
        Workspace workspace = requireWorkspace(workspaceId);
        PullRequestRef issue = issueRef(workspace, issueNumber);
        String token = credentials.resolveGitHubToken();
        return java.util.stream.Stream.concat(
                        issueReads.fetchPrTimeline(token, issue, null).stream()
                                .filter(event -> !"commented".equals(event.event())),
                        issueReads.fetchPrIssueComments(token, issue, null).stream())
                .sorted(Comparator.comparing(PrTimelineEvent::timestamp, Comparator.nullsLast(Comparator.naturalOrder())))
                .collect(toImmutableList());
    }

    @Override
    public PrTimelineEvent comment(String workspaceId, int issueNumber, String body)
    {
        requireNotBlank(body, "comment body must not be blank");
        Workspace workspace = requireWorkspace(workspaceId);
        return issueWrites.createIssueComment(
                credentials.resolveGitHubToken(), issueRef(workspace, issueNumber), body.trim());
    }

    @Override
    public IssueTypeOptions issueType(String workspaceId, int issueNumber)
    {
        Workspace workspace = requireWorkspace(workspaceId);
        String token = credentials.resolveGitHubToken();
        return new IssueTypeOptions(
                gitHub.fetchIssueType(token, workspace.monitoredRepository(), issueNumber),
                gitHub.fetchIssueTypes(token, workspace.monitoredRepository()));
    }

    @Override
    public IssueTypeOptions updateIssueType(String workspaceId, int issueNumber, String type)
    {
        Workspace workspace = requireWorkspace(workspaceId);
        String normalized = type;
        if (normalized != null) {
            requireNotBlank(normalized, "issue type must not be blank");
            normalized = normalized.trim();
        }
        String token = credentials.resolveGitHubToken();
        String current = issueWrites.updateIssueType(token, issueRef(workspace, issueNumber), normalized);
        return new IssueTypeOptions(current, gitHub.fetchIssueTypes(token, workspace.monitoredRepository()));
    }

    @Override
    public IssueMetadata updateMetadata(
            String workspaceId,
            int issueNumber,
            List<String> assignees,
            List<String> labels)
    {
        Workspace workspace = requireWorkspace(workspaceId);
        List<String> normalizedAssignees = normalizeNames(assignees, "assignees");
        List<String> normalizedLabels = normalizeNames(labels, "labels");
        GitHubPullRequestWriteClient.IssueMetadata updated = issueWrites.updateIssueMetadata(
                credentials.resolveGitHubToken(),
                issueRef(workspace, issueNumber),
                normalizedAssignees,
                normalizedLabels);
        issues.updateMetadata(
                workspace.monitoredRepositoryId(),
                issueNumber,
                updated.assignees(),
                updated.labels(),
                updated.updatedAt() == null ? clock.instant() : updated.updatedAt());
        return new IssueMetadata(updated.assignees(), updated.labels());
    }

    private static List<String> normalizeNames(List<String> values, String name)
    {
        requireNonNull(values, name + " is null");
        return values.stream()
                .map(value -> {
                    requireNotBlank(value, name + " must not contain blanks");
                    return value.trim();
                })
                .distinct()
                .collect(toImmutableList());
    }

    private static PullRequestRef issueRef(Workspace workspace, int issueNumber)
    {
        checkArgument(issueNumber > 0, "issueNumber must be positive");
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
