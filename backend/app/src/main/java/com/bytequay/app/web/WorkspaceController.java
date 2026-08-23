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
package com.bytequay.app.web;

import com.bytequay.app.scheduler.PullRequestSyncJob;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.workspace.WatchRepositoryCommand;
import com.bytequay.app.workspace.WatchRepositoryPreview;
import com.bytequay.app.workspace.WorkspaceDetails;
import com.bytequay.app.workspace.WorkspaceIssueService;
import com.bytequay.app.workspace.WorkspaceMetadataService;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.app.workspace.WorkspaceSummary;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PullRequestPage;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryContributor;
import com.bytequay.core.domain.RepositoryIssuePage;
import com.bytequay.core.domain.RepositoryMetadataSnapshot;
import com.google.common.collect.ImmutableMap;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController
{
    private final WorkspaceRepositoryService workspaces;
    private final PullRequestService pullRequests;
    private final PullRequestSyncJob pullRequestSync;
    private final WorkspaceMetadataService metadata;
    private final WorkspaceIssueService issues;

    public WorkspaceController(
            WorkspaceRepositoryService workspaces,
            PullRequestService pullRequests,
            PullRequestSyncJob pullRequestSync,
            WorkspaceMetadataService metadata,
            WorkspaceIssueService issues)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.pullRequestSync = requireNonNull(pullRequestSync, "pullRequestSync is null");
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.issues = requireNonNull(issues, "issues is null");
    }

    @PostMapping
    public WorkspaceSummary watch(@RequestBody WatchRequest request)
    {
        requireNonNull(request, "body is required");
        return WorkspaceSummary.of(workspaces.watch(new WatchRepositoryCommand(
                request.selectedRepositoryId(),
                request.previewFingerprint(),
                Optional.ofNullable(request.name()))));
    }

    @PostMapping("/watch-preview")
    public WatchRepositoryPreview previewWatch(@RequestBody WatchPreviewRequest request)
    {
        requireNonNull(request, "body is required");
        return workspaces.previewWatch(request.selectedRepositoryId());
    }

    @GetMapping
    public List<WorkspaceSummary> list()
    {
        return workspaces.listWorkspaces();
    }

    @GetMapping("/{workspaceId}")
    public WorkspaceDetails get(@PathVariable String workspaceId)
    {
        return workspaces.getWorkspace(workspaceId);
    }

    @GetMapping("/{workspaceId}/pull-requests")
    public PullRequestPage pullRequests(
            @PathVariable String workspaceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size)
    {
        WorkspaceSummary workspace = workspaces.getWorkspace(workspaceId).summary();
        return pullRequests.listPullRequests(RepoRef.of(
                workspace.monitoredOwner(), workspace.monitoredRepo()), page, size);
    }

    @PostMapping("/{workspaceId}/pull-requests/sync")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void syncPullRequests(@PathVariable String workspaceId)
    {
        WorkspaceSummary workspace = workspaces.getWorkspace(workspaceId).summary();
        RepoRef repository = RepoRef.of(workspace.monitoredOwner(), workspace.monitoredRepo());
        pullRequestSync.requestRepositorySync(repository);
    }

    @GetMapping("/{workspaceId}/metadata")
    public RepositoryMetadataSnapshot metadata(@PathVariable String workspaceId)
    {
        return metadata.getSnapshot(workspaceId);
    }

    @GetMapping("/{workspaceId}/contributors")
    public List<RepositoryContributor> contributors(@PathVariable String workspaceId)
    {
        return metadata.getSnapshot(workspaceId).contributors();
    }

    @GetMapping("/{workspaceId}/issues")
    public RepositoryIssuePage issues(
            @PathVariable String workspaceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size)
    {
        return issues.list(workspaceId, page, size);
    }

    @PostMapping("/{workspaceId}/issues/sync")
    public RepositoryIssuePage syncIssues(@PathVariable String workspaceId)
    {
        issues.synchronize(workspaceId);
        return issues.list(workspaceId, 0, 50);
    }

    @GetMapping("/{workspaceId}/issues/{issueNumber}/timeline")
    public List<PrTimelineEvent> issueTimeline(@PathVariable String workspaceId, @PathVariable int issueNumber)
    {
        return issues.timeline(workspaceId, issueNumber);
    }

    @PostMapping("/{workspaceId}/issues/{issueNumber}/comments")
    public PrTimelineEvent commentOnIssue(
            @PathVariable String workspaceId,
            @PathVariable int issueNumber,
            @RequestBody IssueCommentRequest request)
    {
        requireNonNull(request, "body is required");
        return issues.comment(workspaceId, issueNumber, request.body());
    }

    @GetMapping("/{workspaceId}/issues/{issueNumber}/type")
    public WorkspaceIssueService.IssueTypeOptions issueType(
            @PathVariable String workspaceId,
            @PathVariable int issueNumber)
    {
        return issues.issueType(workspaceId, issueNumber);
    }

    @PatchMapping("/{workspaceId}/issues/{issueNumber}/type")
    public WorkspaceIssueService.IssueTypeOptions updateIssueType(
            @PathVariable String workspaceId,
            @PathVariable int issueNumber,
            @RequestBody IssueTypeRequest request)
    {
        requireNonNull(request, "body is required");
        return issues.updateIssueType(workspaceId, issueNumber, request.type());
    }

    @PatchMapping("/{workspaceId}/issues/{issueNumber}/metadata")
    public WorkspaceIssueService.IssueMetadata updateIssueMetadata(
            @PathVariable String workspaceId,
            @PathVariable int issueNumber,
            @RequestBody IssueMetadataRequest request)
    {
        requireNonNull(request, "body is required");
        return issues.updateMetadata(workspaceId, issueNumber, request.assignees(), request.labels());
    }

    @PostMapping("/{workspaceId}/sync")
    public WorkspaceSummary sync(@PathVariable String workspaceId)
    {
        return WorkspaceSummary.of(workspaces.requestSync(workspaceId));
    }

    @PutMapping("/{workspaceId}/upstream")
    public WorkspaceSummary setUpstream(@PathVariable String workspaceId, @RequestBody UpstreamRequest request)
    {
        requireNonNull(request, "body is required");
        return WorkspaceSummary.of(workspaces.setUpstream(
                workspaceId,
                Optional.ofNullable(request.upstreamWorkspaceId())));
    }

    @DeleteMapping("/{workspaceId}")
    public Map<String, String> unwatch(@PathVariable String workspaceId)
    {
        workspaces.unwatch(workspaceId);
        return ImmutableMap.of("result", "removed");
    }

    public record WatchRequest(long selectedRepositoryId, String previewFingerprint, String name) {}

    public record WatchPreviewRequest(long selectedRepositoryId) {}

    public record IssueCommentRequest(String body) {}

    public record IssueTypeRequest(String type) {}

    public record IssueMetadataRequest(List<String> assignees, List<String> labels) {}

    public record UpstreamRequest(String upstreamWorkspaceId) {}
}
