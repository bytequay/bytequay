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
package com.bytequay.app.service.threads;

import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.ReviewPass;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskPhaseGroup;
import com.bytequay.app.repository.AppSettingsStore;
import com.bytequay.app.repository.ReviewStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.pr.PullRequestService;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Enforces the task ↔ PR linking rules server-side:
 * <ul>
 *   <li>Authorship gates the affordance: you may open a <em>dev task</em>
 *       only on your own PR; you may <em>review</em> only someone else's.</li>
 *   <li>Task ↔ PR is 1:1 active: a PR has at most one non-COMPLETED task.</li>
 * </ul>
 * The UI mirrors these, but the server is the source of truth.
 */
@Service
public class PrTaskLinkService
{
    private final PullRequestService pullRequests;
    private final TaskStore taskStore;
    private final AppSettingsStore appSettings;
    private final WatchedRepoStore watchedRepos;
    private final ReviewStore reviewStore;

    public PrTaskLinkService(
            PullRequestService pullRequests,
            TaskStore taskStore,
            AppSettingsStore appSettings,
            WatchedRepoStore watchedRepos,
            ReviewStore reviewStore)
    {
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.appSettings = requireNonNull(appSettings, "appSettings is null");
        this.watchedRepos = requireNonNull(watchedRepos, "watchedRepos is null");
        this.reviewStore = requireNonNull(reviewStore, "reviewStore is null");
    }

    /** The connected GitHub user's login, or "" when not connected. */
    public String viewerLogin()
    {
        return appSettings.get(AppSettingsStore.Key.GITHUB_LOGIN).orElse("");
    }

    /**
     * Guard the "Assign review" entry: a review may only target someone
     * else's PR. Reviewing your own PR is the dev-task lifecycle's job.
     */
    public void assertCanReview(String repoFullName, int prNumber)
    {
        if (isViewer(authorOf(repoFullName, prNumber))) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(422),
                    "cannot_review_own_pr · use the dev task lifecycle");
        }
    }

    /**
     * Guard the "Create dev task" entry: only on your own PR, and only
     * when no active task already owns it. Returns the {@code owner/repo#n}
     * ref the caller links onto the new task.
     */
    public String assertCanCreateDevTask(String repoFullName, int prNumber)
    {
        if (!isViewer(authorOf(repoFullName, prNumber))) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(422),
                    "cannot_create_task_for_others_pr");
        }
        String prRef = repoFullName + "#" + prNumber;
        taskStore.findActiveTaskByPrRef(prRef).ifPresent(active -> {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "pr already has an active task: active_task_id=" + active.id());
        });
        return prRef;
    }

    /**
     * Worktree-driven variant for the task-create path, which knows the
     * task's {@code workingDir} (clone path) + a linked PR number rather
     * than a repo full name. Resolves the repo from the worktree; returns
     * the {@code owner/repo#n} ref to link, or empty when the worktree
     * doesn't map to a watched repo (nothing to gate or link against).
     */
    public Optional<String> assertCanCreateDevTaskForWorktree(String workingDir, int prNumber)
    {
        return repoFullNameFor(workingDir)
                .map(repoFullName -> assertCanCreateDevTask(repoFullName, prNumber));
    }

    private Optional<String> repoFullNameFor(String workingDir)
    {
        if (workingDir == null || workingDir.isBlank()) {
            return Optional.empty();
        }
        Path needle = Path.of(workingDir);
        return watchedRepos.findAll().stream()
                .filter(r -> r.localClonePath() != null && !r.localClonePath().isBlank()
                        && Path.of(r.localClonePath()).equals(needle))
                .findFirst()
                .map(r -> r.owner() + "/" + r.repo());
    }

    /**
     * The tasks linked to a PR: the single active one (if any) plus the
     * completed/cancelled audit log. Backs the PR detail page's
     * linked-task chip + history.
     */
    public LinkedTasks linkedTasksFor(String repoFullName, int prNumber)
    {
        String prRef = repoFullName + "#" + prNumber;
        TaskRef active = null;
        List<String> completed = new ArrayList<>();
        for (Task task : taskStore.findTasksByPrRef(prRef)) {
            if (task.phase() == TaskPhase.COMPLETED) {
                completed.add(task.id());
            }
            else {
                active = new TaskRef(task.id(), taskTitle(task), TaskPhaseGroup.of(task.phase()).name());
            }
        }
        ReviewPassRef activeReview = reviewStore.findActivePrReview(repoFullName, prNumber)
                .map(PrTaskLinkService::toReviewPassRef)
                .orElse(null);
        return new LinkedTasks(active, List.copyOf(completed), activeReview);
    }

    /** Display title for a task chip: the user rename, else the branch,
     *  else "Task N". */
    private static String taskTitle(Task task)
    {
        if (task.name() != null && !task.name().isBlank()) {
            return task.name();
        }
        if (task.branchName() != null && !task.branchName().isBlank()) {
            return task.branchName();
        }
        return "Task " + task.seq();
    }

    private static ReviewPassRef toReviewPassRef(ReviewPass pass)
    {
        return new ReviewPassRef(
                pass.id(),
                pass.phase().name(),
                pass.hostKind().name(),
                pass.round(),
                pass.roundCap(),
                pass.costUsdMilli(),
                pass.costCapMilli());
    }

    /** PR → tasks + active review view. {@code linkedActiveReviewRef} is
     *  populated only for THREAD-hosted (standalone) reviews. */
    public record LinkedTasks(
            TaskRef linkedActiveTask,
            List<String> linkedCompletedTaskIds,
            ReviewPassRef linkedActiveReviewRef) {}

    /** Compact view of the active linked task for a PR-row chip. */
    public record TaskRef(String id, String title, String phaseGroup) {}

    /** Compact view of an active review pass for a PR/task chip. */
    public record ReviewPassRef(
            String passId,
            String phase,
            String hostKind,
            int round,
            int roundCap,
            long costSpentMilli,
            long costCapMilli) {}

    private String authorOf(String repoFullName, int prNumber)
    {
        PullRequest pr = pullRequests.lookupPullRequest(repoFullName, prNumber);
        return pr == null ? "" : pr.author();
    }

    private boolean isViewer(String login)
    {
        String viewer = viewerLogin();
        return login != null && !login.isBlank()
                && !viewer.isBlank() && login.equalsIgnoreCase(viewer);
    }
}
