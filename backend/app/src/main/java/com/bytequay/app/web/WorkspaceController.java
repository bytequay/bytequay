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

import com.bytequay.app.agent.IssueDevelopmentService;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.pr.PullRequestRefreshService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.settings.AgentModelSetting;
import com.bytequay.app.service.settings.AgentModelType;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.app.workspace.WatchRepositoryCommand;
import com.bytequay.app.workspace.WatchRepositoryPreview;
import com.bytequay.app.workspace.WorkspaceCommitsSettingsService;
import com.bytequay.app.workspace.WorkspaceCommitsTarget;
import com.bytequay.app.workspace.WorkspaceDeletionService;
import com.bytequay.app.workspace.WorkspaceException;
import com.bytequay.app.workspace.WorkspaceIssueRefreshService;
import com.bytequay.app.workspace.WorkspaceIssueService;
import com.bytequay.app.workspace.WorkspaceMetadataService;
import com.bytequay.app.workspace.WorkspaceRelationships;
import com.bytequay.app.workspace.WorkspaceRelationships.Relationship;
import com.bytequay.app.workspace.WorkspaceRemoteSourceService;
import com.bytequay.app.workspace.WorkspaceRemoteSourceService.WorkspaceRemote;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.app.workspace.WorkspaceSummary;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PullRequestPage;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryIssueDetail;
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

import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Stream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@RestController
@RequestMapping("/api/workspaces")
public class WorkspaceController
{
    private final WorkspaceRepositoryService workspaces;
    private final WorkspaceDeletionService deletion;
    private final PullRequestService pullRequests;
    private final PullRequestRefreshService pullRequestRefresh;
    private final WorkspaceMetadataService metadata;
    private final WorkspaceIssueService issues;
    private final WorkspaceIssueRefreshService issueRefresh;
    private final WorkspaceRemoteSourceService remoteSources;
    private final ModelSettingsService models;
    private final CredentialService credentials;
    private final WorkspaceCommitsSettingsService commitsSettings;
    private final IssueDevelopmentService issueDevelopment;
    private final WorkspaceRelationships relationships;

    public WorkspaceController(
            WorkspaceRepositoryService workspaces,
            WorkspaceDeletionService deletion,
            PullRequestService pullRequests,
            PullRequestRefreshService pullRequestRefresh,
            WorkspaceMetadataService metadata,
            WorkspaceIssueService issues,
            WorkspaceIssueRefreshService issueRefresh,
            WorkspaceRemoteSourceService remoteSources,
            ModelSettingsService models,
            CredentialService credentials,
            WorkspaceCommitsSettingsService commitsSettings,
            IssueDevelopmentService issueDevelopment,
            WorkspaceRelationships relationships)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.deletion = requireNonNull(deletion, "deletion is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.pullRequestRefresh = requireNonNull(pullRequestRefresh, "pullRequestRefresh is null");
        this.metadata = requireNonNull(metadata, "metadata is null");
        this.issues = requireNonNull(issues, "issues is null");
        this.issueRefresh = requireNonNull(issueRefresh, "issueRefresh is null");
        this.remoteSources = requireNonNull(remoteSources, "remoteSources is null");
        this.models = requireNonNull(models, "models is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.commitsSettings = requireNonNull(commitsSettings, "commitsSettings is null");
        this.issueDevelopment = requireNonNull(issueDevelopment, "issueDevelopment is null");
        this.relationships = requireNonNull(relationships, "relationships is null");
    }

    @PostMapping
    public WorkspaceSummary watch(@RequestBody WatchRequest request)
    {
        return WorkspaceSummary.of(workspaces.watch(new WatchRepositoryCommand(
                request.selectedRepositoryId(),
                request.previewFingerprint(),
                Optional.ofNullable(request.name()),
                request.allowForkCreation())));
    }

    @PostMapping("/watch-preview")
    public WatchRepositoryPreview previewWatch(@RequestBody WatchPreviewRequest request)
    {
        return workspaces.previewWatch(request.selectedRepositoryId());
    }

    @GetMapping
    public List<WorkspaceSummary> list()
    {
        return workspaces.listWorkspaces();
    }

    @GetMapping("/retired")
    public List<WorkspaceSummary> retired()
    {
        return workspaces.listRetiredWorkspaces();
    }

    @GetMapping("/{workspaceId}")
    public WorkspaceSummary get(@PathVariable String workspaceId)
    {
        return workspaces.getWorkspace(workspaceId);
    }

    @GetMapping("/{workspaceId}/pull-requests")
    public PullRequestPage pullRequests(
            @PathVariable String workspaceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size)
    {
        WorkspaceSummary workspace = workspaces.getWorkspace(workspaceId);
        return pullRequests.listPullRequests(RepoRef.of(workspace.monitoredOwner(), workspace.monitoredRepo()), page, size, "");
    }

    @PostMapping("/{workspaceId}/pull-requests/sync")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void syncPullRequests(@PathVariable String workspaceId)
    {
        pullRequestRefresh.refreshWorkspace(workspaceId);
    }

    @GetMapping("/{workspaceId}/metadata")
    public RepositoryMetadataSnapshot metadata(@PathVariable String workspaceId)
    {
        return metadata.getSnapshot(workspaceId);
    }

    @GetMapping("/{workspaceId}/issues")
    public WorkspaceIssueHttpContract.Page issues(
            @PathVariable String workspaceId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size)
    {
        return WorkspaceIssueHttpContract.page(issues.list(workspaceId, page, size), issueDevelopment);
    }

    @GetMapping("/{workspaceId}/issues/{issueNumber}")
    public Optional<WorkspaceIssueHttpContract.Issue> issue(@PathVariable String workspaceId, @PathVariable int issueNumber)
    {
        return issues.find(workspaceId, issueNumber).map(issue -> WorkspaceIssueHttpContract.issue(issue, issueDevelopment));
    }

    @PostMapping("/{workspaceId}/issues/refresh")
    public WorkspaceIssueHttpContract.Page refreshIssues(@PathVariable String workspaceId)
    {
        return WorkspaceIssueHttpContract.page(issueRefresh.refreshIssues(workspaceId), issueDevelopment);
    }

    @GetMapping("/{workspaceId}/issues/attention")
    public WorkspaceIssueHttpContract.Page attentionIssues(@PathVariable String workspaceId)
    {
        return WorkspaceIssueHttpContract.page(issueDevelopment.attention(workspaceId), issueDevelopment);
    }

    @PatchMapping("/{workspaceId}/issues/{issueNumber}/epic")
    public IssueDevelopmentService.Metadata updateIssueEpic(
            @PathVariable String workspaceId,
            @PathVariable int issueNumber,
            @RequestBody IssueEpicRequest request)
    {
        return issueDevelopment.setEpic(workspaceId, issueNumber, request.isEpic());
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
        return issues.comment(workspaceId, issueNumber, request.body());
    }

    @PostMapping("/{workspaceId}/issues/{issueNumber}/close")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void closeIssue(@PathVariable String workspaceId, @PathVariable int issueNumber)
    {
        issues.close(workspaceId, issueNumber);
    }

    @PatchMapping("/{workspaceId}/issues/{issueNumber}/type")
    public WorkspaceIssueService.IssueTypeOptions updateIssueType(
            @PathVariable String workspaceId,
            @PathVariable int issueNumber,
            @RequestBody IssueTypeRequest request)
    {
        return issues.updateIssueType(workspaceId, issueNumber, request.type());
    }

    @PatchMapping("/{workspaceId}/issues/{issueNumber}/metadata")
    public WorkspaceIssueService.IssueMetadata updateIssueMetadata(
            @PathVariable String workspaceId,
            @PathVariable int issueNumber,
            @RequestBody IssueMetadataRequest request)
    {
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

    @DeleteMapping("/{workspaceId}/data")
    public void delete(@PathVariable String workspaceId, @RequestBody DeleteWorkspaceRequest request)
    {
        deletion.delete(workspaceId, request.confirmed(), request.repositoryName());
    }

    @PatchMapping("/{workspaceId}")
    public WorkspaceSummary rename(@PathVariable String workspaceId, @RequestBody RenameRequest request)
    {
        return WorkspaceSummary.of(workspaces.rename(workspaceId, request.name()));
    }

    @GetMapping("/{workspaceId}/settings")
    public WorkspaceSettingsResponse settings(@PathVariable String workspaceId)
    {
        return new WorkspaceSettingsResponse(
                remoteSources.list(workspaceId),
                Stream.of(AgentModelType.values())
                        .map(type -> models.agentModel(type, Optional.of(workspaceId)))
                        .collect(toImmutableList()),
                credentials.workspaceGitHubToken(workspaceId).orElse(null),
                commitsSettings.get(workspaceId));
    }

    @GetMapping("/{workspaceId}/relationships")
    public List<RelationshipResponse> relationships(@PathVariable String workspaceId)
    {
        return relationships.list(workspaceId).stream().map(link -> {
            String unavailable = null;
            try {
                relationships.requireRelated(workspaceId, link.workspaceId());
                var local = workspaces.requireLocalRepository(link.workspaceId());
                if (!Files.exists(local.repository().resolve(".git"))) {
                    unavailable = "Managed checkout missing at " + local.repository();
                }
            }
            catch (IllegalArgumentException | WorkspaceException failure) {
                unavailable = failure.getMessage();
            }
            return new RelationshipResponse(link.workspaceId(), link.kind(), link.guidance(), link.backportByDefault(), unavailable);
        }).collect(toImmutableList());
    }

    public record RelationshipResponse(String workspaceId, WorkspaceRelationships.Kind kind, String guidance, boolean backportByDefault, String unavailableReason) {}

    @PutMapping("/{workspaceId}/relationships")
    public List<Relationship> saveRelationship(@PathVariable String workspaceId, @RequestBody Relationship relationship)
    {
        return relationships.save(workspaceId, relationship);
    }

    @DeleteMapping("/{workspaceId}/relationships/{destinationId}")
    public List<Relationship> removeRelationship(@PathVariable String workspaceId, @PathVariable String destinationId)
    {
        return relationships.remove(workspaceId, destinationId);
    }

    @PutMapping("/{workspaceId}/commits-target")
    public WorkspaceCommitsTarget setCommitsTarget(@PathVariable String workspaceId, @RequestBody CommitsTargetRequest request)
    {
        return commitsSettings.save(workspaceId, request.branch());
    }

    @DeleteMapping("/{workspaceId}/commits-target")
    public WorkspaceCommitsTarget resetCommitsTarget(@PathVariable String workspaceId)
    {
        return commitsSettings.reset(workspaceId);
    }

    @PostMapping("/{workspaceId}/remotes")
    public List<WorkspaceRemote> addRemote(@PathVariable String workspaceId, @RequestBody RemoteRequest request)
    {
        return remoteSources.add(workspaceId, request.name(), request.url());
    }

    @DeleteMapping("/{workspaceId}/remotes/{name}")
    public List<WorkspaceRemote> removeRemote(@PathVariable String workspaceId, @PathVariable String name)
    {
        return remoteSources.remove(workspaceId, name);
    }

    @PutMapping("/{workspaceId}/agent-model/{type}")
    public AgentModelSetting setAgentModel(
            @PathVariable String workspaceId,
            @PathVariable AgentModelType type,
            @RequestBody AgentModelRequest request)
    {
        workspaces.getWorkspace(workspaceId);
        return models.setWorkspaceOverride(
                workspaceId,
                type,
                request.configId(),
                request.model(),
                Optional.ofNullable(request.effort()));
    }

    @DeleteMapping("/{workspaceId}/agent-model/{type}")
    public AgentModelSetting clearAgentModel(@PathVariable String workspaceId, @PathVariable AgentModelType type)
    {
        return models.clearWorkspaceOverride(workspaceId, type);
    }

    @PutMapping("/{workspaceId}/github-token")
    public Map<String, String> saveGitHubToken(
            @PathVariable String workspaceId,
            @RequestBody TokenRequest request)
    {
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

    public record DeleteWorkspaceRequest(boolean confirmed, String repositoryName) {}

    public record WatchRequest(long selectedRepositoryId, String previewFingerprint, String name, boolean allowForkCreation) {}

    public record WatchPreviewRequest(long selectedRepositoryId) {}

    public record IssueCommentRequest(String body) {}

    public record IssueTypeRequest(String type) {}

    public record IssueEpicRequest(boolean isEpic) {}

    public record IssueMetadataRequest(List<String> assignees, List<String> labels) {}

    public record UpstreamRequest(Long upstreamRepositoryId) {}

    public record RenameRequest(String name) {}

    public record RemoteRequest(String name, String url) {}

    public record AgentModelRequest(String configId, String model, String effort) {}

    public record CommitsTargetRequest(String branch) {}

    public record TokenRequest(String value) {}

    public record WorkspaceSettingsResponse(
            List<WorkspaceRemote> remotes,
            List<AgentModelSetting> agents,
            CredentialService.WorkspaceToken githubToken,
            WorkspaceCommitsTarget commitsTarget) {}
}
