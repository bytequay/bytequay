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
import com.bytequay.app.web.PatResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
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

    public TaskService(
            ThreadStore threadStore,
            TaskStore taskStore,
            WatchedRepoStore watchedRepoStore,
            WorktreeService worktreeService,
            GitRunner git,
            PullRequestRepository pullRequestRepository,
            PatResolver patResolver,
            ThreadRegistry registry)
    {
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.watchedRepoStore = requireNonNull(watchedRepoStore, "watchedRepoStore is null");
        this.worktreeService = requireNonNull(worktreeService, "worktreeService is null");
        this.git = requireNonNull(git, "git is null");
        this.pullRequestRepository = requireNonNull(pullRequestRepository, "pullRequestRepository is null");
        this.patResolver = requireNonNull(patResolver, "patResolver is null");
        this.registry = requireNonNull(registry, "registry is null");
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
        requireNonNull(request, "request is null");
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

            // 3. Open a PR (or accept that one already exists).
            Integer prNumber = current.prNumber();
            if (prNumber == null) {
                String pat = patResolver.resolve(repoFullName);
                String prBase = git.defaultBranch(workingDir).orElse("main");
                try {
                    PullRequest pr = pullRequestRepository.createPullRequest(
                            pat, repoRef,
                            CreatePullRequestCommand.of(current.branchName(), prBase, thread.title()));
                    prNumber = pr.number();
                }
                catch (RuntimeException e) {
                    // Most commonly: a PR is already open for this branch.
                    // Keep going without a PR number; the user can attach
                    // the existing PR through the regular linking UI.
                    log.warn("PR create failed for {} branch {}: {}",
                            repoFullName, current.branchName(), e.getMessage());
                }
            }

            // 4. Close the current task.
            Instant now = Instant.now();
            taskStore.saveTask(new Task(
                    current.id(), current.threadId(), current.seq(), TaskStatus.COMPLETED,
                    current.branchName(), current.worktreePath(), current.baseBranch(),
                    current.workingDir(),
                    /* processPid */ null,
                    current.logPath(),
                    prNumber, current.prState(), current.ciState(),
                    current.taskType(),
                    /* linkedPrNumber */ prNumber != null ? prNumber : current.linkedPrNumber(),
                    current.linkedIssueNumber(),
                    current.costUsdMilli(), current.tokensIn(), current.tokensOut(),
                    current.firstMsgSeq(), current.lastMsgSeq(),
                    current.createdAt(), now, current.errorMessage()));

            // 5. Resolve next base + cut a new worktree.
            String nextBase = request.baseMode() == BaseMode.STACKED
                    ? current.branchName()
                    : git.defaultBranch(workingDir).orElse("main");
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
                    /* firstMsgSeq */ null, /* lastMsgSeq */ null,
                    now, /* endedAt */ null, /* errorMessage */ null);
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
