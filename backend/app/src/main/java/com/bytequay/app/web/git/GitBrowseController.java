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
package com.bytequay.app.web.git;

import com.bytequay.app.cherrypick.CherryPickPullRequestLookup;
import com.bytequay.app.cherrypick.PullRequestAssociation;
import com.bytequay.app.git.GitBrowseService;
import com.bytequay.app.git.GitBrowseService.Commit;
import com.bytequay.app.git.GitBrowseService.DiffFile;
import com.bytequay.app.git.GitBrowseService.FileContent;
import com.bytequay.app.git.GitBrowseService.RepoOverview;
import com.bytequay.app.git.GitBrowseService.WorktreeStatus;
import com.bytequay.app.repository.github.GitHubRepoClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.domain.RepoRef;
import com.google.common.collect.ImmutableMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * Local checkout browsing for the Commits tab. The backend resolves every
 * checkout from a durable workspace ID; the renderer cannot select a path.
 */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}/git")
public class GitBrowseController
{
    private final WorkspaceRepositoryService workspaces;
    private final GitBrowseService service;
    private final GitHubRepoClient gitHub;
    private final CredentialService credentials;
    private final CherryPickPullRequestLookup pullRequests;

    public GitBrowseController(
            WorkspaceRepositoryService workspaces,
            GitBrowseService service,
            GitHubRepoClient gitHub,
            CredentialService credentials,
            CherryPickPullRequestLookup pullRequests)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.service = requireNonNull(service, "service is null");
        this.gitHub = requireNonNull(gitHub, "gitHub is null");
        this.credentials = requireNonNull(credentials, "credentials is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
    }

    @GetMapping("/overview")
    public RepoOverview overview(@PathVariable String workspaceId)
    {
        return service.overview(context(workspaceId).repository());
    }

    @GetMapping("/branches")
    public List<String> remoteBranches(@PathVariable String workspaceId, @RequestParam String remote)
    {
        WorkspaceRepositoryContext context = context(workspaceId);
        return service.remoteBranches(context.repository(), allowedRemote(context, remote));
    }

    @GetMapping("/commits")
    public List<GitCommitResponse> commits(
            @PathVariable String workspaceId,
            @RequestParam String ref,
            @RequestParam(defaultValue = "200") int limit)
    {
        WorkspaceRepositoryContext context = context(workspaceId);
        String validatedRef = revision(ref);
        List<Commit> commits = service.log(context.repository(), validatedRef, limit);
        RepoRef repository = context.selectedRepository();
        String githubRef = validatedRef;
        String sourcePrefix = context.sourceRemote() + "/";
        if (validatedRef.startsWith(sourcePrefix)) {
            repository = context.monitoredRepository();
            githubRef = validatedRef.substring(sourcePrefix.length());
        }
        Map<String, String> avatars = credentials.isGitHubAccountConfigured()
                ? gitHub.fetchCommitAvatars(credentials.resolveGitHubToken(), repository, githubRef, limit)
                : ImmutableMap.of();
        Map<String, PullRequestAssociation> associations = pullRequests.lookUp(
                repository,
                commits.stream().map(Commit::sha).collect(toImmutableList()),
                githubRef,
                new ArrayList<>());
        return commits.stream()
                .map(commit -> response(commit, avatars, associations))
                .collect(toImmutableList());
    }

    private static GitCommitResponse response(
            Commit commit,
            Map<String, String> avatars,
            Map<String, PullRequestAssociation> associations)
    {
        PullRequestAssociation association = associations.get(commit.sha());
        return new GitCommitResponse(
                commit.sha(),
                commit.title(),
                commit.body(),
                commit.author(),
                Optional.ofNullable(avatars.get(commit.sha())),
                commit.authoredAt(),
                Optional.ofNullable(association).map(PullRequestAssociation::number).or(() -> commit.pullRequestNumber()),
                Optional.ofNullable(association).map(PullRequestAssociation::title).filter(title -> !title.isBlank()));
    }

    @GetMapping("/diff")
    public List<DiffFile> diff(
            @PathVariable String workspaceId,
            @RequestParam String base,
            @RequestParam String head)
    {
        return service.diff(context(workspaceId).repository(), revision(base), revision(head));
    }

    @GetMapping("/diff-content")
    public FileContent diffFileContent(
            @PathVariable String workspaceId,
            @RequestParam String base,
            @RequestParam String head,
            @RequestParam String path)
    {
        return service.diffFileContent(
                context(workspaceId).repository(), revision(base), revision(head), path);
    }

    @GetMapping("/status")
    public WorktreeStatus status(@PathVariable String workspaceId)
    {
        return service.status(context(workspaceId).repository());
    }

    @PostMapping("/fetch")
    public Map<String, Boolean> fetch(@PathVariable String workspaceId, @RequestBody GitFetchRequest request)
    {
        WorkspaceRepositoryContext context = context(workspaceId);
        checkArgument(request.remotes() != null, "remotes is null");
        List<String> remotes = request.remotes().stream()
                .map(remote -> allowedRemote(context, remote))
                .distinct()
                .collect(toImmutableList());
        service.fetch(context.repository(), remotes);
        return ImmutableMap.of("fetched", true);
    }

    @PostMapping("/commit")
    public Map<String, String> commit(@PathVariable String workspaceId, @RequestBody GitCommitRequest request)
    {
        return ImmutableMap.of("sha", service.commit(
                context(workspaceId).repository(),
                request.summary(),
                request.description() == null ? "" : request.description()));
    }

    @PostMapping("/reword")
    public Map<String, String> reword(@PathVariable String workspaceId, @RequestBody GitRewordRequest request)
    {
        return ImmutableMap.of("sha", service.reword(
                context(workspaceId).repository(),
                revision(request.branch()),
                revision(request.sha()),
                request.message()));
    }

    @PostMapping("/squash")
    public Map<String, String> squash(@PathVariable String workspaceId, @RequestBody GitSquashRequest request)
    {
        return ImmutableMap.of("sha", service.squash(
                context(workspaceId).repository(),
                revision(request.branch()),
                revision(request.baseSha()),
                revision(request.tipSha()),
                request.message()));
    }

    private WorkspaceRepositoryContext context(String workspaceId)
    {
        return workspaces.requireReadyRepository(workspaceId);
    }

    private static String allowedRemote(WorkspaceRepositoryContext context, String remote)
    {
        checkArgument(
                remote != null && (remote.equals(context.sourceRemote()) || remote.equals(context.targetRemote())),
                "remote is not configured for this workspace");
        return remote;
    }

    private static String revision(String value)
    {
        checkArgument(value != null && !value.isBlank(), "revision is blank");
        checkArgument(!value.startsWith("-"), "revision must not be a Git option");
        checkArgument(value.length() <= 512, "revision is too long");
        return value;
    }

    public record GitFetchRequest(List<String> remotes) {}

    public record GitCommitRequest(String summary, String description) {}

    public record GitRewordRequest(String branch, String sha, String message) {}

    public record GitSquashRequest(String branch, String baseSha, String tipSha, String message) {}
}
