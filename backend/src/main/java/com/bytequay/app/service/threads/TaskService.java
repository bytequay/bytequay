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

import com.bytequay.app.domain.CreatePullRequestCommand;
import com.bytequay.app.domain.ListPullRequestsQuery;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskFile;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.workspaces.WorkspaceService;
import com.bytequay.app.web.PatResolver;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * Service-layer surface for the work-unit Task — the branch + worktree
 * + PR row that belongs to a Thread. ThreadService still owns the
 * conversation lifecycle (create, send, pause, …); TaskService owns
 * the per-task lifecycle reads plus "ship & continue", which closes
 * out the current task and starts the next one.
 */
@Service
public class TaskService
{
    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    private final ThreadStore threadStore;
    private final TaskStore taskStore;
    private final WatchedRepoStore watchedRepoStore;
    private final WorktreeService worktreeService;
    private final GitRunner git;
    private final PullRequestRepository pullRequestRepository;
    private final PatResolver patResolver;
    private final ThreadRegistry registry;
    private final WorkspaceService workspaceService;
    private final NotificationService notificationService;
    private final ObjectMapper mapper;

    public TaskService(
            ThreadStore threadStore,
            TaskStore taskStore,
            WatchedRepoStore watchedRepoStore,
            WorktreeService worktreeService,
            GitRunner git,
            PullRequestRepository pullRequestRepository,
            PatResolver patResolver,
            ThreadRegistry registry,
            WorkspaceService workspaceService,
            NotificationService notificationService,
            ObjectMapper mapper)
    {
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.watchedRepoStore = requireNonNull(watchedRepoStore, "watchedRepoStore is null");
        this.worktreeService = requireNonNull(worktreeService, "worktreeService is null");
        this.git = requireNonNull(git, "git is null");
        this.pullRequestRepository = requireNonNull(pullRequestRepository, "pullRequestRepository is null");
        this.patResolver = requireNonNull(patResolver, "patResolver is null");
        this.registry = requireNonNull(registry, "registry is null");
        this.workspaceService = requireNonNull(workspaceService, "workspaceService is null");
        this.notificationService = requireNonNull(notificationService, "notificationService is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    /** All tasks for a thread, ordered by seq ascending. 404 if the
     *  thread doesn't exist (so callers can't probe arbitrary ids). */
    public List<Task> listTasksForThread(String threadId)
    {
        requireThread(threadId);
        return taskStore.listTasksByThread(threadId);
    }

    /** Latest non-terminal task for the thread, or empty if the
     *  thread is in the 0-Task state (brainstorm / Q&A). */
    public Optional<Task> findActiveTask(String threadId)
    {
        requireThread(threadId);
        return taskStore.findActiveTaskForThread(threadId);
    }

    /** Single task lookup. 404s if the task is missing OR if it
     *  belongs to a different thread than the URL implies. */
    public Task requireTask(String threadId, String taskId)
    {
        Task task = taskStore.findTaskById(taskId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "no task: " + taskId));
        if (!task.threadId().equals(threadId)) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(404), "task " + taskId + " is not on thread " + threadId);
        }
        return task;
    }

    /** Per-(task, path) file rollup the agent has produced. Powers the
     *  "Files touched" sidebar at task scope. */
    public List<TaskFile> listFiles(String threadId, String taskId)
    {
        requireTask(threadId, taskId);
        return taskStore.listFiles(taskId);
    }

    /**
     * Ship & continue — close the current task and start the next one
     * inside the same thread. The full flow is the user-triggered
     * "I'm done with this piece, roll onto the next" action:
     *
     * <ol>
     *   <li>Auto-stage + commit any uncommitted changes in the
     *       worktree with a default message (the agent doesn't always
     *       commit as it goes).</li>
     *   <li>{@code git push} the branch (sets upstream on first push).</li>
     *   <li>Open a PR via the per-repo GitHub PAT, targeting the
     *       repo's default branch. If a PR already exists for the
     *       branch we keep going without one — the caller can attach
     *       the number later.</li>
     *   <li>Mark the current task COMPLETED and record its PR number.</li>
     *   <li>Cut a new worktree at {@code <repo>/.worktrees/<new-task-id>/}
     *       from either {@code main} (independent next task) or the
     *       current branch (stacked).</li>
     *   <li>Persist the new task row at seq+1, status PENDING.</li>
     *   <li>Stop the current CLI subprocess and evict the in-memory
     *       agent so the next user turn spawns fresh in the new
     *       worktree with {@code --resume <thread-agent-id>}.</li>
     * </ol>
     *
     * <p>Per CLAUDE.md, opening the PR is the user's explicit action
     * (the ship button), which is why this method calls GitHub.
     */
    @Transactional
    public Task shipAndContinue(String threadId, String taskId, ShipRequest request)
    {
        return shipOrParkAndStartNext(threadId, taskId, request, ParkMode.SHIP);
    }

    /** Rename a task. Trims the supplied label; an empty string clears
     *  the override so the auto-derived humanised branch name takes over
     *  again. Returns the updated row so the caller can refresh its UI
     *  without an extra fetch. */
    @Transactional
    public Task renameTask(String threadId, String taskId, String newName)
    {
        Task current = requireTask(threadId, taskId);
        String trimmed = newName == null ? null : newName.trim();
        String stored = (trimmed == null || trimmed.isEmpty()) ? null : trimmed;
        Task next = new Task(
                current.id(), current.threadId(), current.seq(), current.status(),
                current.branchName(), current.worktreePath(), current.baseBranch(),
                current.workingDir(),
                current.processPid(), current.logPath(),
                current.prNumber(), current.prState(), current.ciState(),
                current.taskType(), current.linkedPrNumber(), current.linkedIssueNumber(),
                current.costUsdMilli(), current.tokensIn(), current.tokensOut(),
                current.agentSessionId(),
                current.createdAt(), current.endedAt(), current.errorMessage(),
                stored);
        taskStore.saveTask(next);
        return next;
    }

    /**
     * Next → park & advance. Same flow as {@link #shipAndContinue} but
     * parks the current task at {@code AWAITING_REVIEW} (not closed)
     * with its worktree preserved, so jump-back keeps the branch +
     * worktree + agent session id alive. Per the workspace/thread/task
     * design's "Next / Ship / jump-back" section, Next is the common
     * day-to-day move; Ship is the terminal move when the PR merges.
     */
    @Transactional
    public Task parkAndStartNext(String threadId, String taskId, ShipRequest request)
    {
        return shipOrParkAndStartNext(threadId, taskId, request, ParkMode.NEXT);
    }

    private Task shipOrParkAndStartNext(
            String threadId, String taskId, ShipRequest request, ParkMode mode)
    {
        requireNonNull(request, "request is null");
        requireNonNull(mode, "mode is null");
        Thread thread = requireThread(threadId);
        Task current = requireTask(threadId, taskId);
        Task active = taskStore.findActiveTaskForThread(threadId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(409),
                        "thread " + threadId + " has no active task"));
        if (!active.id().equals(taskId)) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "task " + taskId + " is not the active task for thread " + threadId);
        }
        if (current.workingDir() == null || current.workingDir().isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "task " + taskId + " has no working dir; nothing to ship");
        }
        if (current.worktreePath() == null || current.worktreePath().isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "task " + taskId + " has no worktree; nothing to ship");
        }
        if (current.branchName() == null || current.branchName().isBlank()) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "task " + taskId + " has no branch name; nothing to ship");
        }

        Path workingDir = Path.of(current.workingDir());
        Path worktreePath = Path.of(current.worktreePath());
        WatchedRepo watched = resolveRepo(workingDir);
        RepoRef repoRef = new RepoRef(watched.owner(), watched.repo());
        String repoFullName = watched.owner() + "/" + watched.repo();

        try {
            // 1. Commit any uncommitted changes the agent left behind.
            if (git.hasUncommittedChanges(worktreePath)) {
                git.stageAll(worktreePath);
                git.commit(worktreePath, "ByteQuay checkpoint via ship & continue");
            }

            // 2. Push to the user's fork (or upstream, depending on
            //    remote setup). push() sets upstream on first run.
            git.push(worktreePath);

            // 3. Open a PR (or accept that one already exists). The PR
            //    targets the per-repo merge-target from the workspace
            //    (e.g. upstream/master for a fork) when set, else the
            //    local clone's default branch.
            Integer prNumber = current.prNumber();
            if (prNumber == null) {
                String pat = patResolver.resolve(repoFullName);
                String prBase = resolveMergeTarget(repoFullName, workingDir);
                try {
                    PullRequest pr = pullRequestRepository.createPullRequest(
                            pat, repoRef,
                            CreatePullRequestCommand.of(current.branchName(), prBase, thread.title()));
                    prNumber = pr.number();
                }
                catch (RuntimeException e) {
                    // Most commonly: a PR is already open for this branch
                    // (GitHub returns 422). Re-fetch the open PR by head
                    // ref so the task still picks up a pr_number instead
                    // of needing the user to attach it manually.
                    log.info("PR create failed for {} branch {}: {} — looking up existing",
                            repoFullName, current.branchName(), e.getMessage());
                    prNumber = findExistingPrNumber(pat, repoRef, current.branchName())
                            .orElse(null);
                }
            }

            // 4. Park the current task. SHIP marks it terminal and
            //    nulls worktreePath ahead of the worktree reap in step 8;
            //    NEXT keeps the row alive at AWAITING_REVIEW with the
            //    worktree preserved so jump-back doesn't have to re-cut
            //    a worktree from origin/<branch> on a wake.
            Instant now = Instant.now();
            TaskStatus parkedStatus = mode == ParkMode.SHIP
                    ? TaskStatus.COMPLETED
                    : TaskStatus.AWAITING_REVIEW;
            String parkedWorktreePath = mode == ParkMode.SHIP
                    ? null
                    : current.worktreePath();
            Instant parkedEndedAt = mode == ParkMode.SHIP ? now : null;
            taskStore.saveTask(new Task(
                    current.id(), current.threadId(), current.seq(), parkedStatus,
                    current.branchName(),
                    parkedWorktreePath,
                    current.baseBranch(),
                    current.workingDir(),
                    /* processPid */ null,
                    current.logPath(),
                    prNumber, current.prState(), current.ciState(),
                    current.taskType(),
                    /* linkedPrNumber */ prNumber != null ? prNumber : current.linkedPrNumber(),
                    current.linkedIssueNumber(),
                    current.costUsdMilli(), current.tokensIn(), current.tokensOut(),
                    current.agentSessionId(),
                    current.createdAt(), parkedEndedAt, current.errorMessage(), current.name()));

            // 5. Resolve next base + cut a new worktree. MAIN mode
            //    uses the same per-repo merge-target as the PR base;
            //    STACKED chains on the current branch instead.
            String nextBase = request.baseMode() == BaseMode.STACKED
                    ? current.branchName()
                    : resolveMergeTarget(repoFullName, workingDir);
            long nextSeq = current.seq() + 1;
            String nextTaskId = UUID.randomUUID().toString();
            String nextTitle = request.nextTitle() != null && !request.nextTitle().isBlank()
                    ? request.nextTitle().trim()
                    : "task " + nextSeq;
            Optional<WorktreeService.WorktreeHandle> nextHandle =
                    worktreeService.create(workingDir, nextTaskId, nextTitle);

            // 6. Persist the new task at seq+1, PENDING.
            Task next = new Task(
                    nextTaskId, threadId, nextSeq, TaskStatus.PENDING,
                    nextHandle.map(WorktreeService.WorktreeHandle::branchName).orElse(null),
                    nextHandle.map(h -> h.worktreePath().toString()).orElse(null),
                    nextBase,
                    current.workingDir(),
                    /* processPid */ null, /* logPath */ null,
                    /* prNumber */ null, /* prState */ null, /* ciState */ null,
                    current.taskType(),
                    /* linkedPrNumber */ null, /* linkedIssueNumber */ null,
                    /* costUsdMilli */ 0L, /* tokensIn */ 0L, /* tokensOut */ 0L,
                    /* agentSessionId — captured on the new task's first turn */ null,
                    now, /* endedAt */ null, /* errorMessage */ null,
                    /* name */ null);
            taskStore.saveTask(next);

            // 7. Drop the in-memory agent for this thread. The next
            //    user turn will spawn a fresh ThreadAgent that reads
            //    the active task (= the new one) and uses its worktree
            //    as cwd while resuming the conversation via
            //    --resume <thread-agent-id>.
            registry.find(threadId).ifPresent(agent -> {
                try {
                    agent.stop();
                }
                catch (RuntimeException e) {
                    log.warn("agent stop on ship-and-continue threw for {}: {}",
                            threadId, e.getMessage());
                }
            });
            registry.evict(threadId);

            // Drop an informational notification so the bell / center
            // shows "Task N shipped → PR #M, next task started" the
            // next time the user looks. Best-effort: a failure here
            // shouldn't roll back the (already-completed) ship.
            try {
                Map<String, Object> payloadMap = new LinkedHashMap<>();
                payloadMap.put("shippedTaskId", current.id());
                payloadMap.put("shippedSeq", current.seq());
                payloadMap.put("prNumber", prNumber);
                payloadMap.put("repoFullName", repoFullName);
                payloadMap.put("branchName", current.branchName());
                payloadMap.put("nextTaskId", nextTaskId);
                payloadMap.put("nextSeq", nextSeq);
                payloadMap.put("nextTitle", nextTitle);
                String payload = mapper.writeValueAsString(payloadMap);
                notificationService.notifyAutoFixDone(threadId, current.id(), payload);
            }
            catch (JsonProcessingException | RuntimeException e) {
                log.warn("notification emit on ship-and-continue threw for thread {}: {}",
                        threadId, e.getMessage());
            }

            // 8. SHIP-only: reap the shipped task's worktree + local
            //    branch. The PR is on the remote; the local refs are an
            //    inert cache from this point on. Done last and best-
            //    effort — if the remove fails (concurrent rm -rf, locked
            //    file) we've still completed the ship; the directory is
            //    a disk leak the operator can clean up by hand or a
            //    future orphan-sweep can pick up. NEXT preserves the
            //    worktree so jump-back lands back in it without a re-cut.
            if (mode == ParkMode.SHIP) {
                worktreeService.remove(workingDir, worktreePath.toString(), current.branchName());
            }

            return next;
        }
        catch (IOException e) {
            throw new RuntimeException("Ship and continue failed for task " + taskId, e);
        }
        catch (InterruptedException e) {
            java.lang.Thread.currentThread().interrupt();
            throw new RuntimeException("Ship and continue interrupted for task " + taskId, e);
        }
    }

    /**
     * Picks the merge-target branch for the given repo. Resolution
     * order: per-(workspace, repo) override on workspace_repos →
     * local clone's default branch → "main".
     */
    private String resolveMergeTarget(String repoFullName, Path workingDir)
            throws IOException, InterruptedException
    {
        Optional<String> override = workspaceService.findDefaultBaseBranch(
                WorkspaceService.DEFAULT_WORKSPACE_ID, repoFullName);
        if (override.isPresent()) {
            return override.get();
        }
        return git.defaultBranch(workingDir).orElse("main");
    }

    /**
     * Look up an already-open PR by head branch when create returns
     * 422. GitHub's list-PRs API takes a {@code head=<owner>:<branch>}
     * filter; we ask for the single newest match. Best-effort — a
     * second failure here just leaves pr_number null and the user
     * attaches the PR manually.
     */
    private Optional<Integer> findExistingPrNumber(String pat, RepoRef repo, String branchName)
    {
        try {
            ListPullRequestsQuery query = new ListPullRequestsQuery(
                    "open",
                    Optional.of(repo.owner() + ":" + branchName),
                    Optional.empty(),
                    "created", "desc", 1, 1);
            List<PullRequest> hits = pullRequestRepository.listPullRequests(pat, repo, query);
            return hits.stream().findFirst().map(PullRequest::number);
        }
        catch (RuntimeException e) {
            log.warn("Lookup of existing PR for {} branch {} failed: {}",
                    repo.owner() + "/" + repo.repo(), branchName, e.getMessage());
            return Optional.empty();
        }
    }

    private WatchedRepo resolveRepo(Path workingDir)
    {
        for (WatchedRepo r : watchedRepoStore.findAll()) {
            if (r.localClonePath() != null
                    && !r.localClonePath().isBlank()
                    && Path.of(r.localClonePath()).equals(workingDir)) {
                return r;
            }
        }
        throw new ResponseStatusException(
                HttpStatusCode.valueOf(404),
                "No watched repo found for working dir " + workingDir);
    }

    private Thread requireThread(String threadId)
    {
        return threadStore.findThreadById(threadId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "no thread: " + threadId));
    }

    /** Whether the next task's branch is cut from {@code main} (an
     *  independent piece of work) or from the current task's branch
     *  (a stacked PR that depends on the previous one). */
    public enum BaseMode
    {
        MAIN,
        STACKED,
    }

    /** How to leave the current task when the user advances. Ship is
     *  terminal — the task closes, worktree reaps, the row is sealed.
     *  Next parks the task at AWAITING_REVIEW with the worktree alive
     *  so jump-back can resume the same conversation without re-cutting
     *  the worktree. */
    private enum ParkMode
    {
        SHIP,
        NEXT,
    }

    /** Request body for {@code POST /api/threads/{id}/tasks/{id}/ship}. */
    public record ShipRequest(String nextTitle, BaseMode baseMode)
    {
        public ShipRequest
        {
            if (baseMode == null) {
                baseMode = BaseMode.MAIN;
            }
        }
    }
}
