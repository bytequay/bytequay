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
package com.bytequay.app.service.localpr;

import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRCommit;
import com.bytequay.app.domain.PRTimelineEntry;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.PullRequestDetail.ActivityItem;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.review.BrainReviewService;
import com.google.common.collect.ImmutableSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Materialises a task's local PR from its real git state so the PR view has
 * something to show without waiting for an agent to call the {@code record_pr_*}
 * tools. Idempotent: creates the row on first sight, then appends any branch
 * commits it hasn't recorded yet, and flips {@code local-drafted → local-open}
 * once the task's phase says development is done and it's awaiting review/push.
 *
 * <p>ponytail: read-side sync from git (git log on each PR-bundle fetch) rather
 * than event-sourced from the agent. Cheap for one task; the agent-driven path
 * ({@code record_pr_*}) can supersede it once stage prompts drive those tools.
 */
@Service
public class PRSyncService
{
    private static final Logger log = LoggerFactory.getLogger(PRSyncService.class);
    private static final int COMMIT_LIMIT = 200;
    private static final String DEFAULT_BASE = "main";

    /** Passive-sync calls (e.g. a PR-bundle fetch on pane load) probe GitHub
     *  at most this often — matches {@link PullRequestService}'s own
     *  detail-page polling maxAge. An explicit user-triggered refresh
     *  ({@code POST /api/prs/{id}/sync}) passes {@code 0} to always probe. */
    private static final int DEFAULT_MAX_AGE_SECONDS = 20;

    /** Phases at which dev is finished and the PR is awaiting the user's review. */
    private static final Set<TaskPhase> READY_FOR_REVIEW = ImmutableSet.of(
            TaskPhase.INTERNAL_REVIEW, TaskPhase.AWAITING_PUSH, TaskPhase.ADDRESSING_LOCAL_COMMENTS);

    private final PRService prService;
    private final TaskStore taskStore;
    private final GitRunner git;
    private final BrainReviewService brainReview;
    private final PullRequestService pullRequests;
    private final PRPublishService prPublish;

    public PRSyncService(
            PRService prService, TaskStore taskStore, GitRunner git, BrainReviewService brainReview,
            PullRequestService pullRequests, PRPublishService prPublish)
    {
        this.prService = requireNonNull(prService, "prService is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.git = requireNonNull(git, "git is null");
        this.brainReview = requireNonNull(brainReview, "brainReview is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.prPublish = requireNonNull(prPublish, "prPublish is null");
    }

    /** Ensure the task's local PR exists and reflects the branch's commits
     *  and (once pushed) the remote PR's comments/reviews. Returns empty
     *  when the task has no branch yet (nothing to show). */
    public Optional<PR> syncFromTask(String taskId)
    {
        Task task = taskStore.findTaskById(taskId).orElse(null);
        if (task == null || task.branchName() == null || task.branchName().isBlank()) {
            return Optional.empty();
        }
        String base = task.baseBranch() == null || task.baseBranch().isBlank() ? DEFAULT_BASE : task.baseBranch();
        String title = task.name() != null && !task.name().isBlank() ? task.name() : task.branchName();
        PR pr = prService.createForTask(taskId, task.branchName(), base, title, "");
        return syncPR(pr.id(), DEFAULT_MAX_AGE_SECONDS);
    }

    /** Canonical id-based refresh for either origin — the {@code POST
     *  /api/prs/{id}/sync} entry point and the target of a future dashboard
     *  {@code syncList}. Task-origin PRs also pick up their branch's local
     *  commits; both origins pick up the remote PR's comments/reviews once
     *  a {@code remotePrNumber} exists. Returns empty only when the PR
     *  itself doesn't exist. */
    public Optional<PR> syncPR(String prId)
    {
        return syncPR(prId, DEFAULT_MAX_AGE_SECONDS);
    }

    /**
     * @param maxAgeSeconds forwarded to {@link PullRequestService#refreshPullRequestDetail}
     *  — {@code 0} always probes GitHub, otherwise a probe within the last
     *  {@code maxAgeSeconds} is skipped (see {@link #DEFAULT_MAX_AGE_SECONDS}).
     */
    public Optional<PR> syncPR(String prId, int maxAgeSeconds)
    {
        PR pr = prService.findById(prId).orElse(null);
        if (pr == null) {
            return Optional.empty();
        }
        if (PR.ORIGIN_EXTERNAL.equals(pr.origin())) {
            if (pr.repo() != null && pr.remotePrNumber() != null) {
                syncRemoteTimeline(pr, pr.repo(), maxAgeSeconds);
            }
            return prService.findById(prId);
        }

        Task task = taskStore.findTaskById(pr.taskId()).orElse(null);
        if (task == null) {
            return Optional.of(pr);
        }
        String base = task.baseBranch() == null || task.baseBranch().isBlank() ? DEFAULT_BASE : task.baseBranch();
        syncCommits(pr, task, base);
        maybeFlipToOpen(pr.id(), task);
        pr = healIfAlreadyPushedRemotely(pr, task);
        PR healed = pr;
        if (healed.remotePrNumber() != null) {
            resolveGitRemoteSlug(task).ifPresent(
                    repo -> syncRemoteTimeline(healed, repo.owner() + "/" + repo.repo(), maxAgeSeconds));
        }
        return prService.findById(healed.id());
    }

    private Optional<RepoRef> resolveGitRemoteSlug(Task task)
    {
        try {
            return git.remoteSlug(Path.of(task.workingDir()), "origin");
        }
        catch (IOException e) {
            log.info("resolving origin remote for task {} failed: {}", task.id(), e.getMessage());
            return Optional.empty();
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Optional.empty();
        }
    }

    /** Self-heals a row stuck at {@code local-drafted}/{@code local-open}
     *  when the task's PR is already open remotely — the same recovery
     *  {@link PRPublishService#onPushedElsewhere} performs for a push
     *  resolved via a gate, applied here too since a task pushed before that
     *  sync existed (or through a path that missed it) would otherwise never
     *  catch up. Runs on every PR-bundle fetch, so it's a one-time fix per
     *  task — once flipped, the status guard in {@code onPushedElsewhere}
     *  makes every later call a no-op. */
    private PR healIfAlreadyPushedRemotely(PR pr, Task task)
    {
        if (!PR.STATUS_LOCAL_DRAFTED.equals(pr.status()) && !PR.STATUS_LOCAL_OPEN.equals(pr.status())) {
            return pr;
        }
        Optional<PullRequestRef> ref = PullRequestRef.parse(task.linkedPrRef());
        if (ref.isEmpty()) {
            return pr;
        }
        prPublish.onPushedElsewhere(new PrPushedEvent(
                task.id(), ref.get().number(),
                "https://github.com/" + ref.get().owner() + "/" + ref.get().repo() + "/pull/" + ref.get().number()));
        return prService.findById(pr.id()).orElse(pr);
    }

    /** Mirror the remote PR's comments and reviews onto the unified timeline
     *  — the caller has already confirmed a {@code remotePrNumber} exists.
     *  Best-effort: a GitHub hiccup here must never break the PR view, so
     *  failures just log. Goes through {@link PullRequestService#refreshPullRequestDetail}
     *  rather than a raw fetch, so a repeat sync within {@code maxAgeSeconds}
     *  (or an unchanged ETag) skips the network round-trip. */
    private void syncRemoteTimeline(PR pr, String repoSlug, int maxAgeSeconds)
    {
        PullRequestDetail detail;
        try {
            detail = pullRequests.refreshPullRequestDetail(repoSlug, pr.remotePrNumber(), maxAgeSeconds);
        }
        catch (RuntimeException e) {
            log.info("fetching remote PR detail for PR {} failed: {}", pr.id(), e.getMessage());
            return;
        }
        if (detail.recentActivity() == null) {
            return;
        }
        for (ActivityItem item : detail.recentActivity()) {
            if (item.githubId() == null) {
                continue;
            }
            if ("commented".equals(item.eventType())) {
                syncIssueComment(pr, item);
            }
            else if ("reviewed".equals(item.eventType())) {
                syncReview(pr, item);
            }
        }
    }

    private void syncIssueComment(PR pr, ActivityItem item)
    {
        if (prService.hasRemoteEvent(pr.id(), item.githubId())) {
            return;
        }
        try {
            prService.addRemoteComment(
                    pr.id(), actorLabel(item.actor()), item.body() == null ? "" : item.body(),
                    item.timestamp() == null ? Instant.now() : item.timestamp(), item.githubId());
        }
        catch (RuntimeException e) {
            log.warn("syncing remote comment {} onto local PR {} failed: {}", item.githubId(), pr.id(),
                    e.getMessage());
        }
    }

    private void syncReview(PR pr, ActivityItem item)
    {
        if (prService.hasRemoteEvent(pr.id(), item.githubId())) {
            return;
        }
        try {
            prService.recordRemoteReview(
                    pr.id(), actorLabel(item.actor()), item.state(), item.body(),
                    item.timestamp() == null ? Instant.now() : item.timestamp(), item.githubId());
        }
        catch (RuntimeException e) {
            log.warn("syncing remote review {} onto local PR {} failed: {}", item.githubId(), pr.id(),
                    e.getMessage());
        }
    }

    private static String actorLabel(String githubLogin)
    {
        return githubLogin == null || githubLogin.isBlank() ? "unknown" : "@" + githubLogin;
    }

    private void syncCommits(PR pr, Task task, String base)
    {
        String cwd = task.worktreePath() != null && !task.worktreePath().isBlank()
                ? task.worktreePath() : task.workingDir();
        if (cwd == null || cwd.isBlank()) {
            return;
        }
        Set<String> known = new HashSet<>();
        for (PRCommit c : prService.commits(pr.id())) {
            known.add(c.sha());
        }
        try {
            Path dir = Path.of(cwd);
            // Resolve the real fork point rather than trusting the configured
            // base name verbatim — a stale local base ref (never fast-forwarded
            // while origin/<base> moved on, e.g. because another parallel
            // worktree merged work upstream) would otherwise sweep in commits
            // that already landed upstream as if they belonged to this branch
            // (see GitRunner.resolveCommitBase).
            String resolvedBase = git.resolveCommitBase(dir, base);
            List<GitRunner.CommitEntry> ahead = resolvedBase == null
                    ? List.of() : git.listCommitsAhead(dir, resolvedBase, COMMIT_LIMIT);
            // git log is newest-first; record oldest-first so the timeline reads
            // in the order the commits were authored.
            for (int i = ahead.size() - 1; i >= 0; i--) {
                GitRunner.CommitEntry c = ahead.get(i);
                if (known.contains(c.shortSha())) {
                    continue;
                }
                int[] delta = commitDelta(dir, c.sha());
                prService.recordCommit(
                        pr.id(), c.shortSha(), c.subject(), delta[0], delta[1], PRTimelineEntry.ACTOR_AGENT);
            }
        }
        catch (IOException e) {
            log.info("syncing commits for local PR {} failed: {}", pr.id(), e.getMessage());
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.info("syncing commits for local PR {} interrupted", pr.id());
        }
    }

    /** Summed additions/deletions for one commit ({@code [add, del]}); zeros on
     *  any git failure so a stat hiccup never blocks recording the commit. */
    private int[] commitDelta(Path dir, String sha)
    {
        try {
            int add = 0;
            int del = 0;
            for (GitRunner.CommitFileChange f : git.commitFiles(dir, sha)) {
                add += f.additions();
                del += f.deletions();
            }
            return new int[] {add, del};
        }
        catch (IOException e) {
            return new int[] {0, 0};
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new int[] {0, 0};
        }
    }

    private void maybeFlipToOpen(String prId, Task task)
    {
        if (task.phase() == null || !READY_FOR_REVIEW.contains(task.phase())) {
            return;
        }
        PR pr = prService.findById(prId).orElse(null);
        if (pr != null && pr.canTransitionTo(PR.STATUS_LOCAL_OPEN)) {
            brainReview.reviewBeforeLocalOpen(prId, PRTimelineEntry.ACTOR_AGENT);
        }
    }
}
