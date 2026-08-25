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

import com.bytequay.app.repository.github.GitHubPullRequestWriteClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.RepositoryIssueDetail;
import com.bytequay.core.domain.RepositoryIssuePage;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.RepositoryIssueDetailStore;
import com.bytequay.core.store.RepositoryIssueStore;
import com.bytequay.core.store.WorkspaceStore;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import static com.bytequay.app.utils.StringInputUtil.requireNotBlank;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Service
public final class DefaultWorkspaceIssueService
        implements WorkspaceIssueService
{
    private final WorkspaceStore workspaces;
    private final RepositoryIssueStore issues;
    private final RepositoryIssueDetailStore details;
    private final GitHubPullRequestWriteClient issueWrites;
    private final CredentialService credentials;
    private final Clock clock;

    public DefaultWorkspaceIssueService(
            WorkspaceStore workspaces,
            RepositoryIssueStore issues,
            RepositoryIssueDetailStore details,
            GitHubPullRequestWriteClient issueWrites,
            CredentialService credentials,
            Clock clock)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.issues = requireNonNull(issues, "issues is null");
        this.details = requireNonNull(details, "details is null");
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
    public Optional<RepositoryIssue> find(String workspaceId, int issueNumber)
    {
        Workspace workspace = requireWorkspace(workspaceId);
        checkArgument(issueNumber > 0, "issueNumber must be positive");
        return issues.find(workspace.monitoredRepositoryId(), issueNumber);
    }

    @Override
    public RepositoryIssueDetail detail(String workspaceId, int issueNumber)
    {
        Workspace workspace = requireWorkspace(workspaceId);
        checkArgument(issueNumber > 0, "issueNumber must be positive");
        return details.find(workspace.monitoredRepositoryId(), issueNumber)
                .orElseGet(() -> RepositoryIssueDetail.empty(workspace.monitoredRepositoryId(), issueNumber));
    }

    @Override
    public PrTimelineEvent comment(String workspaceId, int issueNumber, String body)
    {
        requireNotBlank(body, "comment body must not be blank");
        Workspace workspace = requireWorkspace(workspaceId);
        PrTimelineEvent created = issueWrites.createIssueComment(
                credentials.resolveGitHubToken(), issueRef(workspace, issueNumber), body.trim());
        RepositoryIssueDetail current = details.find(workspace.monitoredRepositoryId(), issueNumber)
                .orElseGet(() -> RepositoryIssueDetail.empty(workspace.monitoredRepositoryId(), issueNumber));
        details.save(current.append(created));
        return created;
    }

    @Override
    public void close(String workspaceId, int issueNumber)
    {
        Workspace workspace = requireWorkspace(workspaceId);
        issueWrites.closeIssue(credentials.resolveGitHubToken(), issueRef(workspace, issueNumber));
        issues.delete(workspace.monitoredRepositoryId(), issueNumber);
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
        String current = issueWrites.updateIssueType(
                credentials.resolveGitHubToken(), issueRef(workspace, issueNumber), normalized);
        RepositoryIssueDetail detail = details.find(workspace.monitoredRepositoryId(), issueNumber)
                .orElseGet(() -> RepositoryIssueDetail.empty(workspace.monitoredRepositoryId(), issueNumber))
                .withType(current);
        details.save(detail);
        return new IssueTypeOptions(current, detail.availableTypes());
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
