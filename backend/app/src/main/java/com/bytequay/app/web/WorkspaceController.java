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

import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.pr.PullRequestRefreshService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.app.service.settings.ModelSettingsService.MainModel;
import com.bytequay.app.service.settings.ModelSettingsService.WorkspaceAgentModel;
import com.bytequay.app.workspace.WatchRepositoryCommand;
import com.bytequay.app.workspace.WatchRepositoryPreview;
import com.bytequay.app.workspace.WorkspaceDetails;
import com.bytequay.app.workspace.WorkspaceIssueRefreshService;
import com.bytequay.app.workspace.WorkspaceIssueService;
import com.bytequay.app.workspace.WorkspaceMetadataService;
import com.bytequay.app.workspace.WorkspaceRemoteSourceService;
import com.bytequay.app.workspace.WorkspaceRemoteSourceService.WorkspaceRemote;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.app.workspace.WorkspaceSummary;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PullRequestPage;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryContributor;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.RepositoryIssueDetail;
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
import org.springframework.web.server.ResponseStatusException;

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
    private final PullRequestRefreshService pullRequestRefresh;
    private final WorkspaceMetadataService metadata;
    private final WorkspaceIssueService issues;
    private final WorkspaceIssueRefreshService issueRefresh;
    private final WorkspaceRemoteSourceService remoteSources;
    private final ModelSettingsService models;
    private final CredentialService credentials;

    public WorkspaceController(
            WorkspaceRepositoryService workspaces,
            PullRequestService pullRequests,
            PullRequestRefreshService pullRequestRefresh,
            WorkspaceMetadataService metadata,
            WorkspaceIssueService issues,
            WorkspaceIssueRefreshService issueRefresh,
            WorkspaceRemoteSourceService remoteSources,
            ModelSettingsService models,
            CredentialService credentials)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.pullRequestRefresh = requireNonNull(pullRequestRefresh, "pullRequestRefresh is null");
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.issues = requireNonNull(issues, "issues is null");
        this.issueRefresh = requireNonNull(issueRefresh, "issueRefresh is null");
        this.remoteSources = requireNonNull(remoteSources, "remoteSources is null");
        this.models = requireNonNull(models, "models is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
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
        pullRequestRefresh.requestRefresh(repository);
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

    @GetMapping("/{workspaceId}/issues/{issueNumber}")
    public RepositoryIssue issue(@PathVariable String workspaceId, @PathVariable int issueNumber)
    {
        return issues.find(workspaceId, issueNumber)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Issue is not available locally"));
    }

    @PostMapping("/{workspaceId}/issues/refresh")
    public RepositoryIssuePage refreshIssues(@PathVariable String workspaceId)
    {
        return issueRefresh.refreshIssues(workspaceId);
    }

    @GetMapping("/{workspaceId}/issues/{issueNumber}/detail")
    public RepositoryIssueDetail issueDetail(
            @PathVariable String workspaceId,
            @PathVariable int issueNumber)
    {
        return issues.detail(workspaceId, issueNumber);
    }

    @PostMapping("/{workspaceId}/issues/{issueNumber}/detail/refresh")
    public RepositoryIssueDetail refreshIssueDetail(
            @PathVariable String workspaceId,
            @PathVariable int issueNumber)
    {
        return issueRefresh.refreshDetail(workspaceId, issueNumber);
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
                Optional.ofNullable(request.upstreamRepositoryId())));
    }

    @DeleteMapping("/{workspaceId}")
    public Map<String, String> unwatch(@PathVariable String workspaceId)
    {
        workspaces.unwatch(workspaceId);
        return ImmutableMap.of("result", "removed");
    }

    @PatchMapping("/{workspaceId}")
    public WorkspaceSummary rename(@PathVariable String workspaceId, @RequestBody RenameRequest request)
    {
        requireNonNull(request, "body is required");
        return WorkspaceSummary.of(workspaces.rename(workspaceId, request.name()));
    }

    @GetMapping("/{workspaceId}/settings")
    public WorkspaceSettingsResponse settings(@PathVariable String workspaceId)
    {
        return new WorkspaceSettingsResponse(
                remoteSources.list(workspaceId),
                models.workspaceOverride(workspaceId).orElse(null),
                models.mainModel(),
                credentials.workspaceGitHubToken(workspaceId).orElse(null));
    }

    @PostMapping("/{workspaceId}/remotes")
    public List<WorkspaceRemote> addRemote(@PathVariable String workspaceId, @RequestBody RemoteRequest request)
    {
        requireNonNull(request, "body is required");
        return remoteSources.add(workspaceId, request.name(), request.url());
    }

    @DeleteMapping("/{workspaceId}/remotes/{name}")
    public List<WorkspaceRemote> removeRemote(@PathVariable String workspaceId, @PathVariable String name)
    {
        return remoteSources.remove(workspaceId, name);
    }

    @PutMapping("/{workspaceId}/agent-model")
    public WorkspaceAgentModel setAgentModel(
            @PathVariable String workspaceId,
            @RequestBody AgentModelRequest request)
    {
        requireNonNull(request, "body is required");
        workspaces.getWorkspace(workspaceId);
        return models.setWorkspaceOverride(
                workspaceId,
                request.configId(),
                request.model(),
                Optional.ofNullable(request.effort()));
    }

    @DeleteMapping("/{workspaceId}/agent-model")
    public void clearAgentModel(@PathVariable String workspaceId)
    {
        models.clearWorkspaceOverride(workspaceId);
    }

    @PutMapping("/{workspaceId}/github-token")
    public Map<String, String> saveGitHubToken(
            @PathVariable String workspaceId,
            @RequestBody TokenRequest request)
    {
        requireNonNull(request, "body is required");
        workspaces.getWorkspace(workspaceId);
        return ImmutableMap.of("login", credentials.saveWorkspaceGitHubToken(workspaceId, request.value()));
    }

    @DeleteMapping("/{workspaceId}/github-token")
    public void deleteGitHubToken(@PathVariable String workspaceId)
    {
        credentials.deleteWorkspaceGitHubToken(workspaceId);
    }

    /** Full workspace token for main-process clipboard copy; the renderer never displays it. */
    @GetMapping("/{workspaceId}/github-token/value")
    public Map<String, String> gitHubTokenValue(@PathVariable String workspaceId)
    {
        return ImmutableMap.of("value", credentials.workspaceGitHubTokenValue(workspaceId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatus.NOT_FOUND, "No workspace token is stored.")));
    }

    public record WatchRequest(long selectedRepositoryId, String previewFingerprint, String name) {}

    public record WatchPreviewRequest(long selectedRepositoryId) {}

    public record IssueCommentRequest(String body) {}

    public record IssueTypeRequest(String type) {}

    public record IssueMetadataRequest(List<String> assignees, List<String> labels) {}

    public record UpstreamRequest(Long upstreamRepositoryId) {}

    public record RenameRequest(String name) {}

    public record RemoteRequest(String name, String url) {}

    public record AgentModelRequest(String configId, String model, String effort) {}

    public record TokenRequest(String value) {}

    public record WorkspaceSettingsResponse(
            List<WorkspaceRemote> remotes,
            WorkspaceAgentModel agentModelOverride,
            MainModel mainModel,
            CredentialService.WorkspaceToken githubToken) {}
}
