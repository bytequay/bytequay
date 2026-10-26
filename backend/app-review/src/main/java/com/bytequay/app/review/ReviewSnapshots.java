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
package com.bytequay.app.review;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.WorktreeService;
import com.bytequay.app.agent.runtime.WorkflowAgentTasks;
import com.bytequay.app.git.command.GitCommandRunner;
import com.bytequay.app.service.pr.PullRequestRefreshService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.review.ReviewCommit;
import com.bytequay.core.review.ReviewConflictException;
import com.bytequay.core.review.ReviewRun;
import com.google.common.collect.ImmutableList;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** Freezes review input from pull requests, development working copies, and replay revisions. */
final class ReviewSnapshots
{
    static final List<String> GIT_OPERATION_PATHS = ImmutableList.of(
            "MERGE_HEAD", "CHERRY_PICK_HEAD", "REVERT_HEAD", "REBASE_HEAD", "BISECT_LOG", "BISECT_START", "rebase-merge", "rebase-apply");

    private final GitCommandRunner git;
    private final WorktreeDiffReader browse;
    private final WorktreeService worktrees;
    private final PullRequestService pullRequests;
    private final PullRequestRefreshService refresh;

    ReviewSnapshots(GitCommandRunner git, WorktreeDiffReader browse, WorktreeService worktrees, PullRequestService pullRequests, PullRequestRefreshService refresh)
    {
        this.git = requireNonNull(git, "git is null");
        this.browse = requireNonNull(browse, "browse is null");
        this.worktrees = requireNonNull(worktrees, "worktrees is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.refresh = requireNonNull(refresh, "refresh is null");
    }

    /**
     * Freezes the pull request at its current remote head: the exact head and base are fetched
     * through the pull request's own refs, so a fork head needs no agent-supplied remote, and the
     * publication diff is the one GitHub reports for that head.
     */
    Snapshot pullRequest(WorkspaceRepositoryContext workspace, String repository, int number)
    {
        PullRequestDetail detail = pullRequests.getPullRequestDetail(repository, number);
        String headSha = pullRequests.findHeadSha(repository, number)
                .orElseThrow(() -> new ReviewConflictException("The pull request head is unknown; refresh the pull request"));
        String baseRef = Optional.ofNullable(detail.baseRef()).orElseThrow(() -> new ReviewConflictException("The pull request base branch is unknown"));
        String remote = workspace.selectedRepository().equals(workspace.monitoredRepository()) ? "origin" : workspace.sourceRemote();
        Path repositoryPath = workspace.repository();
        String headRef = "refs/bytequay/review/" + number;
        GitCommandRunner.Result fetched = git.run(repositoryPath, ImmutableList.of(
                "fetch",
                "--no-tags",
                "--no-recurse-submodules",
                "--no-write-fetch-head",
                remote,
                "+refs/pull/" + number + "/head:" + headRef,
                "+refs/heads/" + baseRef + ":refs/remotes/" + remote + "/" + baseRef));
        if (!fetched.successful()) {
            throw new ReviewConflictException("Cannot fetch pull request " + repository + "#" + number + ": " + fetched.output().strip());
        }
        String fetchedHead = resolve(repositoryPath, headRef);
        if (!fetchedHead.equals(headSha)) {
            // The head moved between GitHub's answer and the fetch; the fetched commit is the newer truth.
            refresh.refreshAllDetail(repository, number);
            String refreshed = pullRequests.findHeadSha(repository, number).orElse(fetchedHead);
            if (!refreshed.equals(fetchedHead)) {
                throw new ReviewConflictException("The pull request head moved while the review was starting; try again");
            }
            headSha = fetchedHead;
        }
        String baseSha = resolve(repositoryPath, "refs/remotes/" + remote + "/" + baseRef);
        String mergeBase = resolve(repositoryPath, headSha, baseSha);
        GitCommandRunner.Result log = git.run(repositoryPath, "log", "--reverse", "--format=%H%x1f%an%x1f%s", mergeBase + ".." + headSha);
        if (log.truncated()) {
            throw new ReviewConflictException("Cannot list the pull request commits: Git output was truncated");
        }
        if (!log.successful()) {
            throw new ReviewConflictException("Cannot list the pull request commits: " + log.output().strip());
        }
        List<ReviewCommit> commits = log.output().lines()
                .filter(line -> !line.isBlank())
                .map(line -> line.split("\\u001f", 3))
                .map(parts -> new ReviewCommit(parts[0], parts.length > 2 ? parts[2] : "", parts.length > 1 ? parts[1] : ""))
                .collect(toImmutableList());
        List<DiffFile> diff = pullRequests.getValidatedPullRequestDiffFiles(repository, number, headSha);
        return new Snapshot(OptionalLong.of(detail.id()), headSha, Optional.empty(), baseRef, baseSha, mergeBase, commits, diff);
    }

    Path sourceCheckout(AgentTask source)
    {
        if (source.worktreeRemovedAt().isPresent()) {
            throw new ReviewConflictException("The development worktree is no longer available");
        }
        TaskWorktree worktree = source.taskWorktree().orElseThrow(() -> new ReviewConflictException("The development task has no worktree"));
        return worktrees.ownedCheckout(worktree);
    }

    Snapshot local(AgentTask source, WorkspaceRepositoryContext workspace, OptionalInt number)
    {
        WorkflowAgentTasks.requireSelectedRepository(WorkflowAgentTasks.repositoryIdentity(workspace.selectedRepository()), source.repository(), ReviewConflictException::new);
        Path checkout = sourceCheckout(source);
        TaskWorktree worktree = source.taskWorktree().orElseThrow();
        List<Path> operationPaths = git.gitPaths(checkout, GIT_OPERATION_PATHS);
        if (operationPaths.size() != GIT_OPERATION_PATHS.size() || operationPaths.stream().anyMatch(path -> !Files.notExists(path, LinkOption.NOFOLLOW_LINKS))) {
            throw new ReviewConflictException("Finish the development worktree's open Git operation before requesting review");
        }
        String head = browse.require(checkout, "rev-parse", "--verify", "HEAD^{commit}").strip();
        String baseRef = worktree.startCommit();
        String base = worktree.startCommit();
        OptionalLong pullRequestId = OptionalLong.empty();
        Optional<PullRequestDetail> associated = sourceDetail(workspace, number);
        if (associated.isPresent()) {
            PullRequestDetail detail = associated.orElseThrow();
            baseRef = Optional.ofNullable(detail.baseRef()).orElseThrow(() -> new ReviewConflictException("The pull request base branch is unknown"));
            String remote = workspace.selectedRepository().equals(workspace.monitoredRepository()) ? "origin" : workspace.sourceRemote();
            base = browse.require(checkout, "rev-parse", "--verify", "refs/remotes/" + remote + "/" + baseRef + "^{commit}").strip();
            pullRequestId = OptionalLong.of(detail.id());
        }
        String mergeBase = browse.require(checkout, "merge-base", head, base).strip();
        WorktreeDiffReader.History history = browse.history(checkout, mergeBase, head);
        String tree = browse.snapshotTree(checkout);
        Optional<String> localTree = tree.equals(browse.require(checkout, "rev-parse", "HEAD^{tree}").strip()) ? Optional.empty() : Optional.of(tree);
        WorktreeDiffReader.Diff diff = browse.diff(checkout, mergeBase, Optional.of(tree));
        if (history.truncated() || diff.truncated()) {
            throw new ReviewConflictException("The local change set exceeds the review snapshot limit; narrow the changes before reviewing");
        }
        List<ReviewCommit> commits = history.commits().reversed().stream()
                .map(commit -> new ReviewCommit(commit.sha(), commit.message(), commit.author())).collect(toImmutableList());
        return new Snapshot(pullRequestId, head, localTree, baseRef, base, mergeBase, commits, diff.files());
    }

    Optional<PullRequestDetail> sourceDetail(WorkspaceRepositoryContext workspace, OptionalInt number)
    {
        if (number.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(pullRequests.findPullRequestDetail(workspace.monitoredRepository().fullName(), number.getAsInt())
                .orElseThrow(() -> new ReviewConflictException("Refresh the associated pull request before reviewing its local branch")));
    }

    /** A replay's scope, read from the workspace repository: the commits and diff from where its pull request branched to the past head. */
    Snapshot replay(Path repository, String headSha, String baseRef, String mergeBaseSha)
    {
        WorktreeDiffReader.History history = browse.history(repository, mergeBaseSha, headSha);
        WorktreeDiffReader.Diff diff = browse.diff(repository, mergeBaseSha, Optional.of(headSha));
        if (history.truncated() || diff.truncated()) {
            throw new ReviewConflictException("The replayed change exceeds the review snapshot limit");
        }
        List<ReviewCommit> commits = history.commits().reversed().stream()
                .map(commit -> new ReviewCommit(commit.sha(), commit.message(), commit.author())).collect(toImmutableList());
        return new Snapshot(OptionalLong.empty(), headSha, Optional.empty(), baseRef, mergeBaseSha, mergeBaseSha, commits, diff.files());
    }

    /** A replay never moves: a retry reviews the scope its first run froze. */
    static Snapshot frozen(ReviewRun run)
    {
        return new Snapshot(OptionalLong.empty(), run.headSha(), run.worktreeTreeSha(), run.baseRef(), run.baseSha(), run.mergeBaseSha(), run.commits(), run.diff());
    }

    String resolve(Path repository, String... arguments)
    {
        GitCommandRunner.Result result = arguments.length == 1
                ? git.run(repository, "rev-parse", "--verify", arguments[0] + "^{commit}")
                : git.run(repository, "merge-base", arguments[0], arguments[1]);
        if (!result.successful() || result.truncated() || result.output().isBlank()) {
            throw new ReviewConflictException("Cannot resolve " + String.join(" ", arguments) + " in the workspace repository");
        }
        return result.output().strip();
    }

    record Snapshot(OptionalLong pullRequestId, String headSha, Optional<String> worktreeTreeSha, String baseRef, String baseSha, String mergeBaseSha,
            List<ReviewCommit> commits, List<DiffFile> diff)
    {
        Snapshot
        {
            requireNonNull(pullRequestId, "pullRequestId is null");
            requireNonNull(headSha, "headSha is null");
            requireNonNull(worktreeTreeSha, "worktreeTreeSha is null");
            requireNonNull(baseRef, "baseRef is null");
            requireNonNull(baseSha, "baseSha is null");
            requireNonNull(mergeBaseSha, "mergeBaseSha is null");
            commits = ImmutableList.copyOf(commits);
            diff = ImmutableList.copyOf(diff);
        }
    }
}
