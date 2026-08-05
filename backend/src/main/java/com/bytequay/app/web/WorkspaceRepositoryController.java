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

import com.bytequay.app.beans.backlog.BacklogItemDto;
import com.bytequay.app.domain.BacklogItem;
import com.bytequay.app.domain.InvestigationReviewData;
import com.bytequay.app.domain.IssueDetail;
import com.bytequay.app.domain.LocalBranch;
import com.bytequay.app.domain.LocalCommit;
import com.bytequay.app.domain.LocalCommitDetail;
import com.bytequay.app.domain.LocalCommitFile;
import com.bytequay.app.domain.LocalFileDiff;
import com.bytequay.app.domain.LocalRepoStatus;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestCommit;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.RepoIssue;
import com.bytequay.app.domain.RepoMeta;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.RepoService;
import com.bytequay.app.service.backlog.BacklogService;
import com.bytequay.app.service.local.HistoryRewriter;
import com.bytequay.app.service.local.LocalRepoService;
import com.bytequay.app.service.localpr.PRSyncService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.review.InvestigationReviewService;
import com.bytequay.app.service.workspaces.ReviewTrunkLifecycleService;
import com.bytequay.app.service.workspaces.UpstreamCherryPickService;
import com.bytequay.app.service.workspaces.WorkspaceCherryPickService;
import com.bytequay.app.service.workspaces.WorkspaceIssueService;
import com.bytequay.app.service.workspaces.WorkspaceRelationService;
import com.bytequay.app.service.workspaces.WorkspaceRepositoryResolver;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.time.Instant;
import java.util.List;

import static java.util.Objects.requireNonNull;

/** Renderer-facing one-repository workspace façades. */
@RestController
@RequestMapping("/api/workspaces/{workspaceId}")
public class WorkspaceRepositoryController
{
    private final WorkspaceRepositoryResolver resolver;
    private final RepoService repos;
    private final PullRequestService pullRequests;
    private final PRSyncService prSync;
    private final InvestigationReviewService reviews;
    private final LocalRepoService local;
    private final WorkspaceIssueService issues;
    private final BacklogService backlog;
    private final ReviewTrunkLifecycleService reviewTrunks;
    private final WorkspaceCherryPickService cherryPicks;
    private final WorkspaceRelationService relations;
    private final UpstreamCherryPickService upstreamCherryPicks;
    private final TaskStore tasks;
    private final ThreadStore trunks;

    public WorkspaceRepositoryController(
            WorkspaceRepositoryResolver resolver,
            RepoService repos,
            PullRequestService pullRequests,
            PRSyncService prSync,
            InvestigationReviewService reviews,
            LocalRepoService local,
            WorkspaceIssueService issues,
            BacklogService backlog,
            ReviewTrunkLifecycleService reviewTrunks,
            WorkspaceCherryPickService cherryPicks,
            WorkspaceRelationService relations,
            UpstreamCherryPickService upstreamCherryPicks,
            TaskStore tasks,
            ThreadStore trunks)
    {
        this.resolver = requireNonNull(resolver, "resolver is null");
        this.repos = requireNonNull(repos, "repos is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.prSync = requireNonNull(prSync, "prSync is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
        this.local = requireNonNull(local, "local is null");
        this.issues = requireNonNull(issues, "issues is null");
        this.backlog = requireNonNull(backlog, "backlog is null");
        this.reviewTrunks = requireNonNull(reviewTrunks, "reviewTrunks is null");
        this.cherryPicks = requireNonNull(cherryPicks, "cherryPicks is null");
        this.relations = requireNonNull(relations, "relations is null");
        this.upstreamCherryPicks = requireNonNull(
                upstreamCherryPicks, "upstreamCherryPicks is null");
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.trunks = requireNonNull(trunks, "trunks is null");
    }

    @GetMapping("/repository")
    public RepositoryDto repository(@PathVariable String workspaceId)
    {
        WorkspaceRepositoryResolver.RepositoryIdentity repo =
                resolver.resolve(workspaceId);
        LocalRepoStatus status = local.listAll().stream()
                .filter(row -> row.owner().equalsIgnoreCase(repo.owner())
                        && row.repo().equalsIgnoreCase(repo.repo()))
                .findFirst()
                .orElse(LocalRepoStatus.unmapped(repo.owner(), repo.repo()));
        return new RepositoryDto(repo.fullName(), repo.owner(), repo.repo(),
                repo.defaultBaseBranch(), status);
    }

    @GetMapping("/pull-requests")
    public List<PullRequest> pullRequests(@PathVariable String workspaceId)
    {
        WorkspaceRepositoryResolver.RepositoryIdentity repo =
                resolver.resolve(workspaceId);
        List<PullRequest> result =
                repos.getRepoPullRequests(repo.owner(), repo.repo());
        result.forEach(pr -> reviewTrunks.reconcile(workspaceId, pr));
        return result;
    }

    @GetMapping("/pull-requests/{number}")
    public PullRequest pullRequest(
            @PathVariable String workspaceId,
            @PathVariable int number)
    {
        WorkspaceRepositoryResolver.RepositoryIdentity repo =
                resolver.resolve(workspaceId);
        PullRequest result =
                repos.getRepoPullRequest(repo.owner(), repo.repo(), number);
        reviewTrunks.reconcile(workspaceId, result);
        return result;
    }

    @GetMapping("/pull-requests/{number}/detail")
    public PullRequestDetail pullRequestDetail(
            @PathVariable String workspaceId,
            @PathVariable int number)
    {
        WorkspaceRepositoryResolver.RepositoryIdentity repo =
                resolver.resolve(workspaceId);
        return pullRequests.getPullRequestDetail(repo.fullName(), number);
    }

    @GetMapping("/pull-requests/{number}/commits")
    public List<PullRequestCommit> pullRequestCommits(
            @PathVariable String workspaceId,
            @PathVariable int number)
    {
        WorkspaceRepositoryResolver.RepositoryIdentity repo =
                resolver.resolve(workspaceId);
        return pullRequests.getPullRequestCommits(repo.fullName(), number);
    }

    @PostMapping("/pull-requests/{number}/review")
    public ReviewStartDto review(
            @PathVariable String workspaceId,
            @PathVariable int number)
    {
        WorkspaceRepositoryResolver.RepositoryIdentity repo =
                resolver.resolve(workspaceId);
        PR pr = prSync.syncExternalPR(repo.fullName(), number)
                .orElseThrow(() -> new IllegalStateException(
                        "pull request is unavailable: " + repo.fullName() + "#" + number));
        boolean existing = reviews.findByPr(pr.id()).isPresent();
        InvestigationReviewService.StartOptions options =
                new InvestigationReviewService.StartOptions(
                        null, null, workspaceId);
        InvestigationReviewData review = reviews.start(
                pr.id(), options);
        boolean hasLiveRound = review.rounds().stream()
                .anyMatch(round -> "QUEUED".equals(round.status())
                        || "RUNNING".equals(round.status()));
        if (existing && !hasLiveRound) {
            review = reviews.createRound(
                    review.review().id(),
                    "re-review",
                    List.of(),
                    options);
        }
        InvestigationReviewData.ReviewRoundRow round = review.rounds().stream()
                .reduce((first, second) -> second)
                .orElse(null);
        return new ReviewStartDto(
                review.review().id(),
                review.review().ownerThreadId(),
                round == null ? null : round.id(),
                round == null ? review.review().status() : round.status());
    }

    @GetMapping("/issues")
    public List<RepoIssue> issues(
            @PathVariable String workspaceId,
            @RequestParam(defaultValue = "open") String state)
    {
        WorkspaceRepositoryResolver.RepositoryIdentity repo =
                resolver.resolve(workspaceId);
        return repos.getRepoIssues(repo.owner(), repo.repo(), state);
    }

    @GetMapping("/issues/{number}")
    public IssueDetail issue(
            @PathVariable String workspaceId,
            @PathVariable int number)
    {
        return issues.readFresh(workspaceId, number);
    }

    @PostMapping("/issues/{number}/comments")
    public IssueDetail.Comment comment(
            @PathVariable String workspaceId,
            @PathVariable int number,
            @RequestBody CommentBody body)
    {
        WorkspaceRepositoryResolver.RepositoryIdentity repo =
                resolver.resolve(workspaceId);
        return repos.createIssueComment(
                repo.owner(), repo.repo(), number, body.body());
    }

    @PatchMapping("/issues/{number}")
    public IssueDetail setIssueState(
            @PathVariable String workspaceId,
            @PathVariable int number,
            @RequestBody IssueStateBody body)
    {
        WorkspaceRepositoryResolver.RepositoryIdentity repo =
                resolver.resolve(workspaceId);
        return repos.setIssueState(
                repo.owner(), repo.repo(), number, body.state());
    }

    @PostMapping("/issues/{number}/start")
    public WorkspaceIssueService.StartIssueResult startIssue(
            @PathVariable String workspaceId,
            @PathVariable int number,
            @RequestBody(required = false) StartIssueBody body)
    {
        return issues.start(
                workspaceId, number, body == null ? null : body.trunkId());
    }

    @GetMapping("/issues/{number}/trunks")
    public List<String> issueTrunks(
            @PathVariable String workspaceId,
            @PathVariable int number)
    {
        return issues.linkedTrunks(workspaceId, number);
    }

    @PostMapping("/issues/{number}/backlog")
    public BacklogItemDto addIssueToBacklog(
            @PathVariable String workspaceId,
            @PathVariable int number,
            @RequestBody(required = false) StartIssueBody body)
    {
        IssueDetail issue = issues.readFresh(workspaceId, number);
        String trunkId = issues.linkToTrunk(
                workspaceId, number, body == null ? null : body.trunkId());
        return BacklogItemDto.from(backlog.createForWorkspace(
                workspaceId,
                trunkId,
                issue.title(),
                firstParagraph(issue.body(), issue.title()),
                issue.body(),
                null,
                List.of("issue"),
                "medium",
                List.of(new BacklogItem.Link("issue", String.valueOf(number)))));
    }

    private static String firstParagraph(String body, String fallback)
    {
        if (body == null || body.isBlank()) {
            return fallback;
        }
        String stripped = body.strip();
        int paragraph = stripped.indexOf("\n\n");
        return paragraph < 0 ? stripped : stripped.substring(0, paragraph).strip();
    }

    @GetMapping("/branches")
    public List<BranchDto> branches(@PathVariable String workspaceId)
    {
        WorkspaceRepositoryResolver.RepositoryIdentity repo =
                resolver.resolve(workspaceId);
        return interrupted(() -> local.listBranches(
                repo.owner(), repo.repo()).stream()
                .map(this::branchDto)
                .toList());
    }

    /**
     * The fork's {@code upstream/*} refs, so the Commits tab can browse
     * and cherry-pick from the upstream repo without checking anything
     * out. Empty remote for a direct clone.
     */
    @GetMapping("/branches/upstream")
    public LocalRepoService.UpstreamRefs upstreamBranches(@PathVariable String workspaceId)
    {
        WorkspaceRepositoryResolver.RepositoryIdentity repo =
                resolver.resolve(workspaceId);
        return interrupted(() -> local.upstreamRefs(repo.owner(), repo.repo()));
    }

    @GetMapping("/branches/comparison")
    public LocalRepoService.BranchComparison compareBranch(
            @PathVariable String workspaceId,
            @RequestParam String branch,
            @RequestParam(required = false) String base)
    {
        WorkspaceRepositoryResolver.RepositoryIdentity repo =
                resolver.resolve(workspaceId);
        return interrupted(() -> local.compareBranches(
                repo.owner(), repo.repo(), branch, base));
    }

    @DeleteMapping("/branches")
    public List<String> deleteBranches(
            @PathVariable String workspaceId,
            @RequestBody DeleteBranchesBody body)
    {
        requireNonNull(body, "body is null");
        WorkspaceRepositoryResolver.RepositoryIdentity repo =
                resolver.resolve(workspaceId);
        return interrupted(() -> local.deleteBranches(
                repo.owner(), repo.repo(), body.names(), body.deleteRemote()));
    }

    @GetMapping("/commits")
    public List<LocalCommit> commits(
            @PathVariable String workspaceId,
            @RequestParam(required = false) String revision,
            @RequestParam(defaultValue = "100") int limit)
    {
        WorkspaceRepositoryResolver.RepositoryIdentity repo =
                resolver.resolve(workspaceId);
        return interrupted(() -> local.listCommits(
                repo.owner(), repo.repo(), revision,
                Math.min(Math.max(limit, 1), 500)));
    }

    @GetMapping("/relation")
    public ResponseEntity<WorkspaceRelationService.WorkspaceRelationDto> relation(
            @PathVariable String workspaceId)
    {
        return relations.find(workspaceId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PutMapping("/relation")
    public WorkspaceRelationService.WorkspaceRelationDto linkRelation(
            @PathVariable String workspaceId,
            @RequestBody WorkspaceRelationService.RelationUpdate body)
    {
        return relations.link(workspaceId, body);
    }

    @DeleteMapping("/relation")
    public ResponseEntity<Void> unlinkRelation(@PathVariable String workspaceId)
    {
        relations.unlink(workspaceId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/relation/fetch")
    public WorkspaceRelationService.WorkspaceRelationDto fetchRelation(
            @PathVariable String workspaceId)
    {
        return interrupted(() -> relations.fetch(workspaceId));
    }

    @GetMapping("/relation/candidates")
    public List<WorkspaceRelationService.RelationCandidateDto> relationCandidates(
            @PathVariable String workspaceId)
    {
        return relations.candidates(workspaceId);
    }

    @GetMapping("/upstream/commits")
    public WorkspaceRelationService.UpstreamCommitsDto upstreamCommits(
            @PathVariable String workspaceId,
            @RequestParam(required = false) String revision,
            @RequestParam(defaultValue = "100") int limit)
    {
        return interrupted(() -> relations.commits(workspaceId, revision, limit));
    }

    /** Dry run: what a cherry-pick would apply and what it would skip, and why. */
    @PostMapping("/upstream/cherry-picks/preview")
    public UpstreamCherryPickService.CherryPickPlan previewUpstreamCherryPick(
            @PathVariable String workspaceId,
            @RequestBody UpstreamCherryPickService.PreviewRequest body)
    {
        return interrupted(() -> upstreamCherryPicks.preview(workspaceId, body));
    }

    @PostMapping("/upstream/cherry-picks")
    public ResponseEntity<UpstreamCherryPickService.UpstreamCherryPickJobDto>
            startUpstreamCherryPick(
                    @PathVariable String workspaceId,
                    @RequestBody UpstreamCherryPickService.StartRequest body)
    {
        UpstreamCherryPickService.UpstreamCherryPickJobDto job =
                interrupted(() -> upstreamCherryPicks.enqueue(workspaceId, body));
        return ResponseEntity.accepted().body(job);
    }

    @GetMapping("/upstream/cherry-picks")
    public List<UpstreamCherryPickService.UpstreamCherryPickJobDto>
            upstreamCherryPicks(
                    @PathVariable String workspaceId,
                    @RequestParam(defaultValue = "20") int limit)
    {
        return upstreamCherryPicks.list(workspaceId, limit);
    }

    @GetMapping("/upstream/cherry-picks/{jobId}")
    public UpstreamCherryPickService.UpstreamCherryPickJobDto upstreamCherryPick(
            @PathVariable String workspaceId,
            @PathVariable String jobId)
    {
        return upstreamCherryPicks.require(workspaceId, jobId);
    }

    @PostMapping("/upstream/cherry-picks/{jobId}/resume")
    public ResponseEntity<UpstreamCherryPickService.UpstreamCherryPickJobDto>
            resumeUpstreamCherryPick(
                    @PathVariable String workspaceId,
                    @PathVariable String jobId)
    {
        UpstreamCherryPickService.UpstreamCherryPickJobDto job =
                interrupted(() -> upstreamCherryPicks.resume(workspaceId, jobId));
        return ResponseEntity.accepted().body(job);
    }

    @PostMapping("/upstream/cherry-picks/{jobId}/retry")
    public ResponseEntity<UpstreamCherryPickService.UpstreamCherryPickJobDto>
            retryUpstreamCherryPick(
                    @PathVariable String workspaceId,
                    @PathVariable String jobId)
    {
        return ResponseEntity.accepted().body(
                upstreamCherryPicks.retry(workspaceId, jobId));
    }

    /**
     * The commit list the history editor works on — same rows as
     * {@code /commits} plus bodies, line counts, and a pushed flag.
     * Literal path, so it wins over {@code /commits/{sha}}.
     */
    @GetMapping("/commits/rewritable")
    public LocalRepoService.RewritableHistory rewritableHistory(
            @PathVariable String workspaceId,
            @RequestParam(required = false) String revision,
            @RequestParam(defaultValue = "100") int limit,
            @RequestParam(defaultValue = "0") int skip)
    {
        WorkspaceRepositoryResolver.RepositoryIdentity repo =
                resolver.resolve(workspaceId);
        return interrupted(() -> local.rewritableHistory(
                repo.owner(), repo.repo(), revision,
                Math.min(Math.max(limit, 1), 500),
                Math.max(skip, 0)));
    }

    /**
     * Applies the editor's staged reorder/squash/reword queue in one
     * rebase. A conflict rolls the branch back and comes out as a 409 so
     * the UI can keep its pending queue and let the user retry.
     */
    @PostMapping("/commits/rewrite")
    public RewriteResultDto rewriteHistory(
            @PathVariable String workspaceId,
            @RequestBody HistoryRewriter.RewritePlan body)
    {
        requireNonNull(body, "body is null");
        WorkspaceRepositoryResolver.RepositoryIdentity repo =
                resolver.resolve(workspaceId);
        try {
            HistoryRewriter.RewriteResult result = interrupted(() -> local.rewriteHistory(
                    repo.owner(), repo.repo(), body));
            return new RewriteResultDto(result.headSha(), result.pushed(), result.pushError());
        }
        catch (HistoryRewriter.RewriteFailedException failed) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, failed.getMessage(), failed);
        }
    }

    public record RewriteResultDto(String headSha, boolean pushed, String pushError) {}

    /**
     * Everything changed but not yet committed — staged, unstaged, and
     * untracked in one list. Powers the Commits page's uncommitted tab.
     */
    @GetMapping("/working-tree/files")
    public List<LocalCommitFile> workingTreeFiles(@PathVariable String workspaceId)
    {
        WorkspaceRepositoryResolver.RepositoryIdentity repo =
                resolver.resolve(workspaceId);
        return interrupted(() -> local.workingTreeFiles(repo.owner(), repo.repo()));
    }

    /** One working-tree file's diff against HEAD. */
    @GetMapping("/working-tree/diff")
    public LocalFileDiff workingTreeFileDiff(
            @PathVariable String workspaceId,
            @RequestParam String path)
    {
        WorkspaceRepositoryResolver.RepositoryIdentity repo =
                resolver.resolve(workspaceId);
        return interrupted(() -> local.workingTreeFileDiff(repo.owner(), repo.repo(), path));
    }

    /**
     * One file's diff for a commit or a span of them. {@code base} is
     * the exclusive start ({@code <sha>^} for a single commit), so the
     * editor uses the same call for its single- and multi-select panes.
     */
    @GetMapping("/commits/diff")
    public LocalFileDiff commitRangeFileDiff(
            @PathVariable String workspaceId,
            @RequestParam String base,
            @RequestParam String head,
            @RequestParam String path)
    {
        WorkspaceRepositoryResolver.RepositoryIdentity repo =
                resolver.resolve(workspaceId);
        return interrupted(() -> local.rangeFileDiff(
                repo.owner(), repo.repo(), base, head, path));
    }

    @GetMapping("/commits/{sha}")
    public LocalCommitDetail commit(
            @PathVariable String workspaceId,
            @PathVariable String sha)
    {
        WorkspaceRepositoryResolver.RepositoryIdentity repo =
                resolver.resolve(workspaceId);
        return interrupted(() -> local.commitDetail(
                repo.owner(), repo.repo(), sha));
    }

    @GetMapping("/commits/{sha}/files")
    public List<LocalCommitFile> commitFiles(
            @PathVariable String workspaceId,
            @PathVariable String sha)
    {
        WorkspaceRepositoryResolver.RepositoryIdentity repo =
                resolver.resolve(workspaceId);
        return interrupted(() -> local.commitFiles(
                repo.owner(), repo.repo(), sha));
    }

    @PostMapping("/commits/cherry-pick")
    public WorkspaceCherryPickService.CherryPickResult cherryPick(
            @PathVariable String workspaceId,
            @RequestBody CherryPickBody body)
    {
        requireNonNull(body, "body is null");
        return interrupted(() -> cherryPicks.cherryPick(
                workspaceId,
                body.sourceBranch(),
                body.targetBranch(),
                body.shas()));
    }

    /** Undoes a conflicted cherry-pick and removes its retained worktree. */
    @PostMapping("/commits/cherry-pick/{operationId}/abort")
    public WorkspaceCherryPickService.CherryPickResult abortCherryPick(
            @PathVariable String workspaceId,
            @PathVariable String operationId)
    {
        return interrupted(() -> cherryPicks.abort(workspaceId, operationId));
    }

    @PostMapping("/refresh")
    public LocalRepoStatus refresh(@PathVariable String workspaceId)
    {
        WorkspaceRepositoryResolver.RepositoryIdentity repo =
                resolver.resolve(workspaceId);
        return interrupted(() -> local.fetch(repo.owner(), repo.repo()));
    }

    @GetMapping("/repository/meta")
    public RepoMeta meta(@PathVariable String workspaceId)
    {
        WorkspaceRepositoryResolver.RepositoryIdentity repo =
                resolver.resolve(workspaceId);
        return repos.getRepoMeta(repo.owner(), repo.repo());
    }

    private static <T> T interrupted(Interruptible<T> operation)
    {
        try {
            return operation.run();
        }
        catch (InterruptedException e) {
            java.lang.Thread.currentThread().interrupt();
            throw new IllegalStateException("git operation interrupted", e);
        }
        catch (IOException e) {
            throw new IllegalStateException(e.getMessage(), e);
        }
    }

    private interface Interruptible<T>
    {
        T run()
                throws IOException, InterruptedException;
    }

    public record RepositoryDto(
            String fullName,
            String owner,
            String repo,
            String defaultBaseBranch,
            LocalRepoStatus local) {}

    private BranchDto branchDto(LocalBranch branch)
    {
        Task task = tasks.findTaskByBranch(branch.name()).orElse(null);
        Thread trunk = task == null
                ? null
                : trunks.findThreadById(task.threadId()).orElse(null);
        return new BranchDto(
                branch.name(),
                branch.isCurrent(),
                branch.lastCommitAt(),
                branch.hasUpstream(),
                branch.ahead(),
                branch.behind(),
                branch.linkedPrNumber(),
                branch.cleanupReason(),
                branch.commitCount(),
                branch.rebasePreview(),
                branch.remoteOnly(),
                task == null ? null : task.id(),
                task == null ? null : task.name(),
                trunk == null ? null : trunk.id(),
                trunk == null ? null : trunk.title());
    }

    public record BranchDto(
            String name,
            boolean isCurrent,
            Instant lastCommitAt,
            boolean hasUpstream,
            Integer ahead,
            Integer behind,
            Integer linkedPrNumber,
            LocalBranch.CleanupReason cleanupReason,
            Integer commitCount,
            LocalBranch.RebasePreview rebasePreview,
            boolean remoteOnly,
            String taskId,
            String taskTitle,
            String trunkId,
            String trunkTitle) {}

    public record CommentBody(String body) {}

    public record IssueStateBody(String state) {}

    public record StartIssueBody(String trunkId) {}

    public record CherryPickBody(
            String sourceBranch,
            String targetBranch,
            List<String> shas) {}

    public record DeleteBranchesBody(
            List<String> names,
            boolean deleteRemote) {}

    public record ReviewStartDto(
            String reviewId,
            String trunkId,
            String roundId,
            String status) {}
}
