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

import com.bytequay.app.domain.LocalPR;
import com.bytequay.app.domain.LocalPRCommit;
import com.bytequay.app.domain.LocalPRTimelineEvent;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.local.GitRunner;
import com.google.common.collect.ImmutableSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Path;
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
public class LocalPRSyncService
{
    private static final Logger log = LoggerFactory.getLogger(LocalPRSyncService.class);
    private static final int COMMIT_LIMIT = 200;
    private static final String DEFAULT_BASE = "main";

    /** Phases at which dev is finished and the PR is awaiting the user's review. */
    private static final Set<TaskPhase> READY_FOR_REVIEW =
            ImmutableSet.of(TaskPhase.INTERNAL_REVIEW, TaskPhase.AWAITING_PUSH);

    private final LocalPRService localPr;
    private final TaskStore taskStore;
    private final GitRunner git;

    public LocalPRSyncService(LocalPRService localPr, TaskStore taskStore, GitRunner git)
    {
        this.localPr = requireNonNull(localPr, "localPr is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.git = requireNonNull(git, "git is null");
    }

    /** Ensure the task's local PR exists and reflects the branch's commits.
     *  Returns empty when the task has no branch yet (nothing to show). */
    public Optional<LocalPR> syncFromTask(String taskId)
    {
        Task task = taskStore.findTaskById(taskId).orElse(null);
        if (task == null || task.branchName() == null || task.branchName().isBlank()) {
            return Optional.empty();
        }
        String base = task.baseBranch() == null || task.baseBranch().isBlank() ? DEFAULT_BASE : task.baseBranch();
        String title = task.name() != null && !task.name().isBlank() ? task.name() : task.branchName();
        LocalPR pr = localPr.createForTask(taskId, task.branchName(), base, title, "");

        syncCommits(pr, task, base);
        maybeFlipToOpen(pr.id(), task);
        return localPr.findById(pr.id());
    }

    private void syncCommits(LocalPR pr, Task task, String base)
    {
        String cwd = task.worktreePath() != null && !task.worktreePath().isBlank()
                ? task.worktreePath() : task.workingDir();
        if (cwd == null || cwd.isBlank()) {
            return;
        }
        Set<String> known = new HashSet<>();
        for (LocalPRCommit c : localPr.commits(pr.id())) {
            known.add(c.sha());
        }
        try {
            Path dir = Path.of(cwd);
            List<GitRunner.CommitEntry> ahead = git.listCommitsAhead(dir, base, COMMIT_LIMIT);
            // git log is newest-first; record oldest-first so the timeline reads
            // in the order the commits were authored.
            for (int i = ahead.size() - 1; i >= 0; i--) {
                GitRunner.CommitEntry c = ahead.get(i);
                if (known.contains(c.shortSha())) {
                    continue;
                }
                int[] delta = commitDelta(dir, c.sha());
                localPr.recordCommit(
                        pr.id(), c.shortSha(), c.subject(), delta[0], delta[1], LocalPRTimelineEvent.ACTOR_AGENT);
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
        LocalPR pr = localPr.findById(prId).orElse(null);
        if (pr != null && pr.canTransitionTo(LocalPR.STATUS_LOCAL_OPEN)) {
            localPr.requestUserReview(prId, LocalPRTimelineEvent.ACTOR_AGENT);
        }
    }
}
