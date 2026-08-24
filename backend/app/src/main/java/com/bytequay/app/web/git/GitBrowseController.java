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

import com.bytequay.app.git.CommitHistoryRef;
import com.bytequay.app.git.CommitHistoryRef.Source;
import com.bytequay.app.git.CommitHistoryService;
import com.bytequay.app.git.CommitHistorySnapshot;
import com.bytequay.app.git.GitBrowseService;
import com.bytequay.app.git.GitBrowseService.DiffFile;
import com.bytequay.app.git.GitBrowseService.FileContent;
import com.bytequay.app.git.GitBrowseService.RepoOverview;
import com.bytequay.app.git.GitBrowseService.WorktreeStatus;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.google.common.collect.ImmutableMap;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

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
    private final CommitHistoryService commits;

    public GitBrowseController(
            WorkspaceRepositoryService workspaces,
            GitBrowseService service,
            CommitHistoryService commits)
    {
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.service = requireNonNull(service, "service is null");
        this.commits = requireNonNull(commits, "commits is null");
    }

    @GetMapping("/overview")
    public RepoOverview overview(@PathVariable String workspaceId)
    {
        return service.overview(readContext(workspaceId).repository());
    }

    @GetMapping("/branches")
    public List<String> remoteBranches(@PathVariable String workspaceId, @RequestParam String remote)
    {
        WorkspaceRepositoryContext context = readContext(workspaceId);
        return service.remoteBranches(context.repository(), allowedRemote(context, remote));
    }

    @GetMapping("/commits")
    public CommitHistorySnapshot commits(
            @PathVariable String workspaceId,
            @RequestParam Source source,
            @RequestParam String branch,
            @RequestParam(required = false) String remote,
            @RequestParam(defaultValue = "200") int limit)
    {
        return commits.listHistory(
                workspaceId,
                new CommitHistoryRef(source, branch, Optional.ofNullable(remote)),
                limit);
    }

    @GetMapping("/diff")
    public List<DiffFile> diff(
            @PathVariable String workspaceId,
            @RequestParam String base,
            @RequestParam String head)
    {
        return service.diff(readContext(workspaceId).repository(), revision(base), revision(head));
    }

    @GetMapping("/diff-content")
    public FileContent diffFileContent(
            @PathVariable String workspaceId,
            @RequestParam String base,
            @RequestParam String head,
            @RequestParam String path)
    {
        return service.diffFileContent(
                readContext(workspaceId).repository(), revision(base), revision(head), path);
    }

    @GetMapping("/status")
    public WorktreeStatus status(@PathVariable String workspaceId)
    {
        return service.status(readContext(workspaceId).repository());
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

    /**
     * Reads work while a synchronized repository re-syncs (git supports
     * concurrent readers during a fetch); the commits endpoint already
     * behaves this way, and a stricter gate here made the diff pane error
     * while the commit list loaded.
     */
    private WorkspaceRepositoryContext readContext(String workspaceId)
    {
        return workspaces.requireReadableRepository(workspaceId);
    }

    /** Mutations and explicit fetches still require a fully READY repository. */
    private WorkspaceRepositoryContext context(String workspaceId)
    {
        return workspaces.requireReadyRepository(workspaceId);
    }

    private static String allowedRemote(WorkspaceRepositoryContext context, String remote)
    {
        checkArgument(
                remote != null && context.knownRemote(remote),
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
