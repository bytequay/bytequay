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

import com.bytequay.app.domain.Actor;
import com.bytequay.app.domain.CreatePullRequestCommand;
import com.bytequay.app.domain.ListPullRequestsQuery;
import com.bytequay.app.domain.NotificationKind;
import com.bytequay.app.domain.NotificationStatus;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskPhaseEvent;
import com.bytequay.app.domain.TaskRecoveryRequest;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.TurnLiveness;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.local.UncheckedGitException;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.localpr.PrPushedEvent;
import com.bytequay.app.service.localpr.TaskPushSaga;
import com.bytequay.app.service.pr.PullRequestClosedEvent;
import com.bytequay.app.service.pr.PullRequestMergedEvent;
import com.bytequay.app.service.review.BrainReviewService;
import com.bytequay.app.service.review.RoundGateSaga;
import com.bytequay.app.service.workspaces.WorkspaceService;
import com.bytequay.app.service.workspaces.WorkspaceShipEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import static java.util.Objects.requireNonNull;

/**
 * Service-layer surface for the work-unit Task — the branch + worktree
 * + PR row that belongs to a Thread. ThreadService still owns the
 * conversation lifecycle (create, send, pause, …); TaskService owns
 * the per-task lifecycle reads plus "ship", which parks the current
 * task at IN_REVIEW with a draft PR open and hands it to the post-ship
 * PR loop (no successor is cut — the trunk does that).
 */
@Service
public class TaskService
{
    private static final Logger log = LoggerFactory.getLogger(TaskService.class);

    /** Longest we block a cancel waiting for the interrupted subprocess to die
     *  before reaping anyway. This bounds the agent winding down: interrupt()
     *  sends destroy() (SIGTERM) and the CLI exits at its next tool boundary,
     *  so the wait covers an in-flight tool call finishing — not the start of a
     *  fresh one. destroy() is graceful; a few hundred ms is the norm, and reap
     *  is --force + best-effort so a timeout is no worse than the pre-wait
     *  behaviour. */
    private static final Duration AGENT_STOP_TIMEOUT = Duration.ofSeconds(3);

    private final ThreadStore threadStore;
    private final TaskStore taskStore;
    private final StageStore stageStore;
    private final WatchedRepoStore watchedRepoStore;
    private final WorktreeService worktreeService;
    private final GitRunner git;
    private final PullRequestRepository pullRequestRepository;
    private final PatResolver patResolver;
    private final ThreadRegistry registry;
    private final WorkspaceService workspaceService;
    private final NotificationService notificationService;
    private final ObjectMapper mapper;
    private final ApplicationEventPublisher eventPublisher;
    private final TaskCommandExecutor commands;
    private final TaskPhaseMachine taskPhaseMachine;
    private final TaskTerminalSealer sealer;
    private final PRService prService;
    private final TaskPushSaga pushSaga;
    private final RoundGateSaga roundGateSaga;
    private final BrainReviewService brainReview;
    private final ThreadTurnScheduler scheduler;
    private final TaskRuntimeStopReconciler stopReconciler;
    private final Executor pauseTeardownExecutor;
    /** Generation token for the one committed Pause teardown still allowed to
     *  touch a task's runtime. Resume/Cancel remove it under the task lock. */
    private final ConcurrentHashMap<String, Object> pauseTeardownTokens = new ConcurrentHashMap<>();

    @Autowired
    public TaskService(
            ThreadStore threadStore,
            TaskStore taskStore,
            StageStore stageStore,
            WatchedRepoStore watchedRepoStore,
            WorktreeService worktreeService,
            GitRunner git,
            PullRequestRepository pullRequestRepository,
            PatResolver patResolver,
            ThreadRegistry registry,
            WorkspaceService workspaceService,
            NotificationService notificationService,
            ObjectMapper mapper,
            ApplicationEventPublisher eventPublisher,
            TaskCommandExecutor commands,
            TaskPhaseMachine taskPhaseMachine,
            TaskTerminalSealer sealer,
            PRService prService,
            TaskPushSaga pushSaga,
            RoundGateSaga roundGateSaga,
            BrainReviewService brainReview,
            ThreadTurnScheduler scheduler,
            TaskRuntimeStopReconciler stopReconciler)
    {
        this(threadStore, taskStore, stageStore, watchedRepoStore, worktreeService,
                git, pullRequestRepository, patResolver, registry, workspaceService,
                notificationService, mapper, eventPublisher, commands, taskPhaseMachine, sealer,
                prService, pushSaga, roundGateSaga, brainReview, scheduler, stopReconciler,
                action -> java.lang.Thread.startVirtualThread(action));
    }

    TaskService(
            ThreadStore threadStore,
            TaskStore taskStore,
            StageStore stageStore,
            WatchedRepoStore watchedRepoStore,
            WorktreeService worktreeService,
            GitRunner git,
            PullRequestRepository pullRequestRepository,
            PatResolver patResolver,
            ThreadRegistry registry,
            WorkspaceService workspaceService,
            NotificationService notificationService,
            ObjectMapper mapper,
            ApplicationEventPublisher eventPublisher,
            TaskCommandExecutor commands,
            TaskPhaseMachine taskPhaseMachine,
            TaskTerminalSealer sealer,
            PRService prService,
            TaskPushSaga pushSaga,
            RoundGateSaga roundGateSaga,
            BrainReviewService brainReview,
            ThreadTurnScheduler scheduler,
            TaskRuntimeStopReconciler stopReconciler,
            Executor pauseTeardownExecutor)
    {
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.watchedRepoStore = requireNonNull(watchedRepoStore, "watchedRepoStore is null");
        this.worktreeService = requireNonNull(worktreeService, "worktreeService is null");
        this.git = requireNonNull(git, "git is null");
        this.pullRequestRepository = requireNonNull(pullRequestRepository, "pullRequestRepository is null");
        this.patResolver = requireNonNull(patResolver, "patResolver is null");
        this.registry = requireNonNull(registry, "registry is null");
        this.workspaceService = requireNonNull(workspaceService, "workspaceService is null");
        this.notificationService = requireNonNull(notificationService, "notificationService is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.eventPublisher = requireNonNull(eventPublisher, "eventPublisher is null");
        this.commands = requireNonNull(commands, "commands is null");
        this.taskPhaseMachine = requireNonNull(taskPhaseMachine, "taskPhaseMachine is null");
        this.sealer = requireNonNull(sealer, "sealer is null");
        this.prService = requireNonNull(prService, "prService is null");
        this.pushSaga = requireNonNull(pushSaga, "pushSaga is null");
        this.roundGateSaga = requireNonNull(roundGateSaga, "roundGateSaga is null");
        this.brainReview = requireNonNull(brainReview, "brainReview is null");
        this.scheduler = requireNonNull(scheduler, "scheduler is null");
        this.stopReconciler = requireNonNull(stopReconciler, "stopReconciler is null");
        this.pauseTeardownExecutor = requireNonNull(
                pauseTeardownExecutor, "pauseTeardownExecutor is null");
    }

    /** All tasks for a thread, ordered by seq ascending. 404 if the
     *  thread doesn't exist (so callers can't probe arbitrary ids). */
    public List<Task> listTasksForThread(String threadId)
    {
        requireThread(threadId);
        return taskStore.listTasksByThread(threadId);
    }

    /** Read the task's auto-approve mode. */
    public boolean isAutoApprove(String threadId, String taskId)
    {
        requireTask(threadId, taskId);
        return taskStore.isAutoApprove(taskId);
    }

    /** Read the task's minimum-approvals gate (write-permission approvals a
     *  shipped PR needs before it counts as merge-ready). */
    public int getMinApprovals(String threadId, String taskId)
    {
        requireTask(threadId, taskId);
        return taskStore.minApprovals(taskId);
    }

    /** Set the task's minimum-approvals gate. Clamped to the 0..2 range the
     *  plan-card selector offers. */
    public int setMinApprovals(String threadId, String taskId, int minApprovals)
    {
        requireTask(threadId, taskId);
        int clamped = Math.clamp(minApprovals, 0, 2);
        taskStore.setMinApprovals(taskId, clamped);
        return taskStore.minApprovals(taskId);
    }

    /** Flip the task's auto-approve mode, returning the stored value. While
     *  on, the task's parked publish gates + tool prompts auto-approve — the
     *  final PR merge stays manually gated. */
    public boolean setAutoApprove(String threadId, String taskId, boolean enabled)
    {
        requireTask(threadId, taskId);
        taskStore.setAutoApprove(taskId, enabled);
        // Turning it on clears any gate already parked, not just future ones —
        // AutoApproveGateListener sweeps the task's parked non-merge gates.
        if (enabled) {
            eventPublisher.publishEvent(new AutoApproveEnabledEvent(threadId, taskId));
        }
        return taskStore.isAutoApprove(taskId);
    }

    /** Read the task's auto-merge mode. */
    public boolean isAutoMerge(String threadId, String taskId)
    {
        requireTask(threadId, taskId);
        return taskStore.isAutoMerge(taskId);
    }

    /** Flip the task's auto-merge mode. Enabling it also turns on
     *  auto-approve (merge can't happen before every earlier gate has
     *  cleared anyway). The plan-card UI surfaces risk/effort as a hint for
     *  whether auto-merge is a good idea, but doesn't block the user from
     *  turning it on regardless — this is their call, not a system gate. */
    public boolean setAutoMerge(String threadId, String taskId, boolean enabled)
    {
        requireTask(threadId, taskId);
        taskStore.setAutoMerge(taskId, enabled);
        if (enabled) {
            taskStore.setAutoApprove(taskId, true);
            eventPublisher.publishEvent(new AutoApproveEnabledEvent(threadId, taskId));
        }
        return taskStore.isAutoMerge(taskId);
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

    /**
     * Ship — publish the current task and hand it to the post-ship PR
     * loop. The user-triggered "I'm done with this piece" action:
     *
     * <ol>
     *   <li>Auto-stage + commit any uncommitted changes in the
     *       worktree with a default message (the agent doesn't always
     *       commit as it goes).</li>
     *   <li>{@code git push} the branch (sets upstream on first push).</li>
     *   <li>Open a draft PR via the per-repo GitHub PAT, targeting the
     *       repo's merge-target. If a PR already exists for the branch
     *       we re-fetch its number instead of failing.</li>
     *   <li>Park the current task at IN_REVIEW (not COMPLETED — that
     *       waits for the PR to merge), keep its worktree, and link the
     *       PR so the lifecycle reconciler drives CI-fix → mark-ready →
     *       merge.</li>
     * </ol>
     *
     * <p>No successor is ever cut and the agent keeps running: the
     * shipped task stays IN_REVIEW and its agent runs the post-ship loop
     * (CI auto-fix, addressing review comments) on the same worktree.
     * The trunk cuts the next task when the user wants it.
     *
     * <p>Per CLAUDE.md, opening the PR is the user's explicit action
     * (the ship button), which is why this method calls GitHub.
     */
    public Task shipAndContinue(String threadId, String taskId, ShipRequest request)
    {
        return shipOrParkAndStartNext(threadId, taskId, request, ParkMode.SHIP, false);
    }

    /** Execute a human-approved parked ship proposal without first
     *  reopening the task as active work. */
    public Task shipApprovedParkedTask(String threadId, String taskId, ShipRequest request)
    {
        return shipOrParkAndStartNext(threadId, taskId, request, ParkMode.SHIP, true);
    }

    /**
     * Set (or clear) the task's override on the work-model cascade.
     * Passing {@code null} clears the override so the resolver falls
     * back to the thread pick. The most-specific scope on the
     * cascade — pinning it here turns this single task off the
     * thread default.
     */
    @Transactional
    public Task setWorkModel(String threadId, String taskId, WorkModel workModel)
    {
        Task current = requireTask(threadId, taskId);
        Task next = current.withWorkModel(workModel);
        taskStore.saveTask(next);
        return next;
    }

    /** Task-scoped agent selection seals when task execution first emits a
     *  stage message. A legacy task-level CLI session also seals it. */
    public boolean isWorkModelAgentLocked(String threadId, String taskId)
    {
        Task task = requireTask(threadId, taskId);
        return (task.agentSessionId() != null && !task.agentSessionId().isBlank())
                || !threadStore.listStageMessagesByTask(taskId).isEmpty();
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
        Task next = current.withName(stored);
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
    public Task parkAndStartNext(String threadId, String taskId, ShipRequest request)
    {
        return shipOrParkAndStartNext(threadId, taskId, request, ParkMode.NEXT, false);
    }

    /** Execute a human-approved parked Next proposal. The current task
     *  remains reviewable while the created sibling becomes active. */
    public Task startNextFromApprovedParkedTask(
            String threadId, String taskId, ShipRequest request)
    {
        return shipOrParkAndStartNext(threadId, taskId, request, ParkMode.NEXT, true);
    }

    private Task shipOrParkAndStartNext(
            String threadId, String taskId, ShipRequest request, ParkMode mode, boolean approvedParked)
    {
        return TaskExternalEffectGate.withEffectGate(taskId, () ->
                shipOrParkAndStartNextLocked(threadId, taskId, request, mode, approvedParked));
    }

    private Task shipOrParkAndStartNextLocked(
            String threadId, String taskId, ShipRequest request, ParkMode mode, boolean approvedParked)
    {
        requireNonNull(request, "request is null");
        requireNonNull(mode, "mode is null");
        Thread thread = requireThread(threadId);
        Task current = requireTask(threadId, taskId);
        prService.findByTask(current.id())
                .filter(pr -> PR.ORIGIN_TASK.equals(pr.origin()))
                .ifPresent(pr -> {
                    throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                            "task " + taskId + " is managed by the Local Review lifecycle (PR status="
                                    + pr.status() + "); use its Approve & ship gate");
                });
        if (approvedParked) {
            if (current.status() != TaskStatus.AWAITING_REVIEW) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                        "task " + taskId + " is no longer awaiting approval");
            }
        }
        else if (!taskStore.activeTasksForThread(threadId).stream()
                .anyMatch(t -> t.id().equals(taskId))) {
            // Ship / Next act on a live task; the task must be among the
            // thread's active set (a thread may have several at once).
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "task " + taskId + " is not active for thread " + threadId);
        }
        TaskPreconditions.requireShippable(current);

        Path workingDir = Path.of(current.workingDir());
        Path worktreePath = Path.of(current.worktreePath());
        WatchedRepo watched = resolveRepo(workingDir);
        RepoRef repoRef = new RepoRef(watched.owner(), watched.repo());
        String repoFullName = watched.fullName();

        try {
            // 1-2. Commit any uncommitted work (minus our per-worktree hook
            //      dir, which is ByteQuay infra and must never land in the
            //      user's branch / PR), then push to origin. Both are
            //      pre-remote-mutation: if the push fails the branch never
            //      reaches the remote, so we tag it PublishPushFailedException
            //      and the approve gate releases for a clean retry instead of
            //      pinning RESOLVING as an ambiguous advance failure.
            try {
                if (git.hasUncommittedChanges(worktreePath)) {
                    git.stageAll(worktreePath, List.of(WorktreeService.HOOK_DIR_REL));
                    git.commit(worktreePath, "ByteQuay checkpoint via ship & continue");
                }
                git.push(worktreePath);
            }
            catch (IOException | InterruptedException e) {
                if (e instanceof InterruptedException) {
                    java.lang.Thread.currentThread().interrupt();
                }
                throw new PublishPushFailedException(
                        "push failed for task " + taskId + ": " + e.getMessage(), e);
            }

            // 3. Open a PR (or accept that one already exists). The PR
            //    targets the per-repo merge-target from the workspace
            //    (e.g. upstream/master for a fork) when set, else the
            //    local clone's default branch.
            Integer prNumber = current.prNumber();
            if (prNumber == null) {
                String pat = patResolver.resolve(repoFullName);
                String prBase = resolveMergeTarget(thread.workspaceId(), repoFullName, workingDir);
                // Cross-fork PRs need an owner-qualified head: when the
                // clone's origin is a fork of the target repo, GitHub wants
                // <fork-owner>:<branch>; a same-repo PR uses the bare branch.
                String prHead = crossForkHead(workingDir, watched.owner(), current.branchName());
                try {
                    // Ship opens the PR as a DRAFT and keeps the worktree alive:
                    // the post-ship loop (CI auto-fix, addressing review
                    // comments) pushes more commits to this branch, and the
                    // lifecycle reconciler marks the PR ready once CI is green.
                    // Ship opens a draft PR with the agent-proposed (or
                    // user-edited) title/body when supplied; a blank title
                    // falls back to the thread title, a blank body opens with
                    // none. Next keeps the legacy thread-title shape.
                    String shipTitle = request.prTitle() == null || request.prTitle().isBlank()
                            ? thread.title()
                            : request.prTitle();
                    CreatePullRequestCommand command = mode == ParkMode.SHIP
                            ? CreatePullRequestCommand.draft(
                                    prHead, prBase, shipTitle, request.prBody())
                            : CreatePullRequestCommand.of(prHead, prBase, thread.title());
                    PullRequest pr = pullRequestRepository.createPullRequest(pat, repoRef, command);
                    prNumber = pr.number();
                }
                catch (RuntimeException e) {
                    // Most commonly: a PR is already open for this branch
                    // (GitHub returns 422). Re-fetch the open PR by head
                    // ref so the task still picks up a pr_number instead
                    // of needing the user to attach it manually.
                    log.info("PR create failed for {} branch {}: {} — looking up existing",
                            repoFullName, current.branchName(), e.getMessage());
                    String headFilter = prHead.contains(":")
                            ? prHead
                            : repoRef.owner() + ":" + current.branchName();
                    prNumber = findExistingPrNumber(pat, repoRef, headFilter)
                            .orElse(null);
                }
            }

            // 4. Commit the local handoff in one short task command. Git and
            //    GitHub have already completed, so no network call runs in the
            //    transaction/task stripe. SHIP enters the remote spine; NEXT
            //    remains a local human-review park.
            Integer openedPrNumber = prNumber;
            Task parked = commands.execute(current.id(), () -> finalizeShipInCommand(
                    current.id(), mode, openedPrNumber, repoFullName));
            if (mode == ParkMode.SHIP && openedPrNumber != null) {
                // Ship pushed + opened the PR directly (not through a push/
                // open_pr gate), so the PR row otherwise never learns
                // about it and keeps offering "ready to push" forever.
                eventPublisher.publishEvent(new PrPushedEvent(
                        current.id(), repoFullName, openedPrNumber,
                        "https://github.com/" + repoFullName + "/pull/" + openedPrNumber));
            }

            // No successor is ever cut: ship / next finish the current task,
            // and the trunk's create_task is the only way to start more work
            // (a thread may run several tasks concurrently). The shipped /
            // parked task keeps its worktree — SHIP runs the post-ship PR loop
            // (CI fix / addressing comments push more commits), and the
            // lifecycle reconciler reaps the worktree only when the PR merges.

            // Drop an informational notification so the bell / center
            // shows "Task N shipped → PR #M" the next time the user looks.
            // Best-effort: a failure here shouldn't roll back the
            // (already-completed) ship.
            try {
                Map<String, Object> payloadMap = new LinkedHashMap<>();
                payloadMap.put("shippedTaskId", current.id());
                payloadMap.put("shippedSeq", current.seq());
                payloadMap.put("prNumber", prNumber);
                payloadMap.put("repoFullName", repoFullName);
                payloadMap.put("branchName", current.branchName());
                String payload = mapper.writeValueAsString(payloadMap);
                notificationService.notifyAutoFixDone(threadId, current.id(), payload);
            }
            catch (JsonProcessingException | RuntimeException e) {
                log.warn("notification emit on ship-and-continue threw for thread {}: {}",
                        threadId, e.getMessage());
            }

            // Phase B: tell the memory subsystem a unit of work just
            // landed. ShipEventMemoryTrigger listens, dedups within
            // a 5-minute window, and runs the workspace distiller in
            // the background so memory catches up as work ships.
            String workspaceId = thread.workspaceId();
            if (workspaceId != null && !workspaceId.isBlank()) {
                eventPublisher.publishEvent(new WorkspaceShipEvent(workspaceId, threadId, taskId));
            }
            return parked;
        }
        catch (IOException e) {
            throw new UncheckedGitException("Ship and continue failed for task " + taskId, e);
        }
        catch (InterruptedException e) {
            java.lang.Thread.currentThread().interrupt();
            throw new RuntimeException("Ship and continue interrupted for task " + taskId, e);
        }
    }

    private Task finalizeShipInCommand(
            String taskId, ParkMode mode, Integer prNumber, String repoFullName)
    {
        Task task = taskStore.findTaskById(taskId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "no task: " + taskId));
        if (task.status().isDone()
                || task.status() == TaskStatus.PAUSED
                || task.status() == TaskStatus.NEEDS_ATTENTION
                || task.status() == TaskStatus.ERRORED
                || task.status() == TaskStatus.ARCHIVED) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "task " + taskId + " stopped while publishing");
        }
        taskStore.clearProcessPid(taskId);
        Task parked = task.withProcessPid(null).withEndedAt(null);
        if (prNumber != null) {
            taskStore.linkPullRequest(taskId, prNumber,
                    mode == ParkMode.SHIP ? "draft" : "open");
            parked = parked.withPrNumber(prNumber).withLinkedPrNumber(prNumber);
        }
        if (mode == ParkMode.SHIP) {
            if (prNumber == null) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(502),
                        "GitHub did not return or expose the opened pull request");
            }
            taskStore.markPushed(taskId, Instant.now());
            taskStore.linkTaskToPr(taskId, repoFullName + "#" + prNumber);
            taskPhaseMachine.observeRemoteOpenedInCommand(taskId, "shipped_draft_pr_open");
            return parked.withStatus(TaskStatus.IN_REVIEW)
                    .withPhase(TaskPhase.PUSHED_AWAITING_CI);
        }
        if (task.status() != TaskStatus.AWAITING_REVIEW) {
            if (!taskStore.updateStatusIf(
                    taskId, task.status(), TaskStatus.AWAITING_REVIEW)) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                        "task " + taskId + " changed while parking for review");
            }
            taskStore.appendStatusEvent(
                    taskId, task.status(), TaskStatus.AWAITING_REVIEW,
                    Actor.HUMAN, "task_parked_for_review", Instant.now());
        }
        return parked.withStatus(TaskStatus.AWAITING_REVIEW);
    }

    /**
     * Picks the merge-target branch for the given repo. Resolution
     * order: per-(workspace, repo) override on workspace_repos →
     * local clone's default branch → "main".
     */
    private String resolveMergeTarget(String workspaceId, String repoFullName, Path workingDir)
            throws IOException, InterruptedException
    {
        if (workspaceId != null && !workspaceId.isBlank()) {
            Optional<String> override = workspaceService.findDefaultBaseBranch(
                    workspaceId, repoFullName);
            if (override.isPresent()) {
                return override.get();
            }
        }
        // Fork-aware: the upstream's default branch for a fork-based clone
        // (so a trinodb/trino fork targets master, not the fork's HEAD),
        // else the local clone's default. Same resolver the worktree base
        // uses, so branch-from and PR-base agree.
        return worktreeService.resolveBaseBranchName(workingDir);
    }

    /**
     * Look up an already-open PR by head ref when create returns 422.
     * GitHub's list-PRs API takes a {@code head=<owner>:<branch>} filter
     * — {@code headFilter} is already in that owner-qualified form (the
     * fork owner for a cross-fork PR, else the target owner), so a fork's
     * existing PR is found under its real head. We ask for the single
     * newest match. Best-effort — a second failure here just leaves
     * pr_number null and the user attaches the PR manually.
     */
    private Optional<Integer> findExistingPrNumber(String pat, RepoRef repo, String headFilter)
    {
        try {
            ListPullRequestsQuery query = new ListPullRequestsQuery(
                    "open",
                    Optional.of(headFilter),
                    Optional.empty(),
                    "created", "desc", 1, 1);
            List<PullRequest> hits = pullRequestRepository.listPullRequests(pat, repo, query);
            return hits.stream().findFirst().map(PullRequest::number);
        }
        catch (RuntimeException e) {
            log.warn("Lookup of existing PR for {} head {} failed: {}",
                    repo.fullName(), headFilter, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * PR head ref for a branch in {@code workingDir}'s clone against a PR
     * target owned by {@code targetOwner}. When the clone's {@code origin}
     * is a fork of the target (its owner differs from {@code
     * targetOwner}), GitHub needs the owner-qualified {@code
     * <fork-owner>:<branch>}; a same-repo PR uses the bare branch. Falls
     * back to the bare branch if the origin owner can't be read.
     */
    private String crossForkHead(Path workingDir, String targetOwner, String branch)
    {
        try {
            Optional<String> forkOwner = git.remoteOwner(workingDir, "origin");
            if (forkOwner.isPresent() && !forkOwner.get().equalsIgnoreCase(targetOwner)) {
                return forkOwner.get() + ":" + branch;
            }
        }
        catch (IOException | RuntimeException e) {
            log.warn("Resolving origin owner for cross-fork head in {} failed: {}",
                    workingDir, e.getMessage());
        }
        catch (InterruptedException e) {
            java.lang.Thread.currentThread().interrupt();
        }
        return branch;
    }

    private WatchedRepo resolveRepo(Path workingDir)
    {
        return watchedRepoStore.findAll().stream()
                .filter(r -> r.localClonePath() != null
                        && !r.localClonePath().isBlank()
                        && Path.of(r.localClonePath()).equals(workingDir))
                .findFirst()
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404),
                        "No watched repo found for working dir " + workingDir));
    }

    /**
     * Advance a shipped task to COMPLETED once its PR merges. Fired
     * (in-process) by both merge paths — the dashboard merge button and
     * an approved {@code merge_pr} proposal. A shipped task sits at
     * {@link TaskStatus#IN_REVIEW} until this lands; before then nothing
     * reports it as "done", matching the user's mental model that
     * completion means merged, not merely "PR opened".
     *
     * <p>Best-effort and idempotent: matches the merged PR to tasks by
     * linked PR number, narrows to the right repo via {@code workingDir},
     * and only flips rows that are not already terminal. A bookkeeping
     * failure here never propagates back to the merge call.
     */
    @TransactionalEventListener(fallbackExecution = true)
    public void onPullRequestMerged(PullRequestMergedEvent event)
    {
        completeTasksForMergedPr(event.repoFullName(), event.prNumber());
    }

    @TransactionalEventListener(fallbackExecution = true)
    public void onPullRequestClosed(PullRequestClosedEvent event)
    {
        closeTasksForRemotePr(event.repoFullName(), event.prNumber());
    }

    /**
     * Advance any shipped task that owns {@code prNumber} in {@code
     * repoFullName} from IN_REVIEW to COMPLETED. Called by the dashboard
     * merge (via {@link PullRequestMergedEvent}) and directly by an
     * approved {@code merge_pr} proposal. Best-effort and idempotent:
     * narrows by repo (PR numbers aren't globally unique) and only flips
     * rows that are not already terminal; a failure never propagates to the merge.
     */
    public void completeTasksForMergedPr(String repoFullName, int prNumber)
    {
        finishTasksForRemotePr(repoFullName, prNumber, true);
    }

    /** Immediately seal a task whose PR the user closed without merging. */
    public void closeTasksForRemotePr(String repoFullName, int prNumber)
    {
        finishTasksForRemotePr(repoFullName, prNumber, false);
    }

    private void finishTasksForRemotePr(String repoFullName, int prNumber, boolean merged)
    {
        try {
            for (Task candidate : taskStore.findByLinkedPrNumber(prNumber)) {
                TaskExternalEffectGate.withEffectGate(candidate.id(), () -> {
                    RemoteTerminalResult result = commands.execute(candidate.id(),
                            () -> finishOneForRemotePrInCommand(
                                    candidate, repoFullName, prNumber, merged));
                    if (result != null) {
                        finishRemoteTerminalRuntime(result);
                    }
                    return null;
                });
            }
        }
        catch (RuntimeException e) {
            log.warn("completing tasks for merged PR {} #{} failed: {}",
                    repoFullName, prNumber, e.getMessage());
        }
    }

    private RemoteTerminalResult finishOneForRemotePrInCommand(
            Task candidate, String repoFullName, int prNumber, boolean merged)
    {
        // Re-read only after acquiring the same command boundary used by every
        // task-owned push. A merge can therefore seal/reap only
        // after an in-flight push has committed and left its critical section.
        Task task = taskStore.findTaskById(candidate.id()).orElse(candidate);
        if (task.status().isDone()
                || !repoMatches(task, repoFullName)) {
            return null;
        }
        String reason = merged ? "pr_merged" : "pr_closed";
        taskPhaseMachine.finishTerminalInCommand(
                task.id(),
                merged ? TaskStatus.COMPLETED : TaskStatus.REMOTE_CLOSED,
                Actor.WEBHOOK,
                reason);
        taskStore.linkPullRequest(task.id(), prNumber, merged ? "merged" : "closed");
        return new RemoteTerminalResult(task);
    }

    private void finishRemoteTerminalRuntime(RemoteTerminalResult result)
    {
        Task task = result.task();
        // finishTerminalInCommand already sealed every durable child in the
        // terminal transaction; only external runtime cleanup remains here.
        stopReconciler.reconcileStoppedTask(task.id());
        notificationService.dismissOpenForTask(task.threadId(), task.id());
        worktreeService.reap(task);
        worktreeService.deleteRemoteBranch(task);
    }

    private record RemoteTerminalResult(Task task) {}

    /**
     * Record the user's standing consent to merge the PR after they approve
     * an "Approve &amp; merge" gate that enqueues into the merge queue. With
     * consent on file, the lifecycle re-enqueues automatically if the queue
     * bounces the PR, instead of re-prompting for approval each time.
     */
    public void authorizeMergeForPr(String repoFullName, int prNumber)
    {
        try {
            for (Task task : taskStore.findByLinkedPrNumber(prNumber)) {
                if (task.status() == TaskStatus.IN_REVIEW && repoMatches(task, repoFullName)) {
                    taskStore.authorizeMerge(task.id(), Instant.now());
                }
            }
        }
        catch (RuntimeException e) {
            log.warn("recording merge consent for PR {} #{} failed: {}",
                    repoFullName, prNumber, e.getMessage());
        }
    }

    /**
     * Close a task the user is done with: stop the agent, seal it
     * CANCELED, and reap its worktree + branch. The explicit "throw this
     * away" action — distinct from ship (publish) and from a clean
     * completion. Interrupting first lets the CLI subprocess exit at the
     * next tool boundary so it stops touching the worktree we then reap.
     */
    public Task cancelTask(String threadId, String taskId)
    {
        return TaskExternalEffectGate.withEffectGate(taskId, () -> {
            Task task = commands.execute(taskId, () -> cancelTaskInCommand(taskId));
            return finishCancelRuntime(threadId, task);
        });
    }

    private Task cancelTaskInCommand(String taskId)
    {
        Task task = taskStore.findTaskById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("no task " + taskId));
        // Durable terminal intent first: CANCELED status + COMPLETED phase +
        // both audit rows commit before any runtime teardown, so a crash
        // mid-cancel leaves a task the reconcilers finish tearing down
        // rather than a live-looking task with a half-dead runtime.
        taskPhaseMachine.finishTerminalInCommand(
                taskId, TaskStatus.CANCELED, Actor.HUMAN, "task_cancelled");
        return task;
    }

    private Task finishCancelRuntime(String threadId, Task task)
    {
        String taskId = task.id();
        // A committed Pause callback may still be waiting for its virtual
        // thread. Cancel owns the task now, so make that callback stale before
        // touching durable turns or the runtime.
        pauseTeardownTokens.remove(taskId);
        // Stop durable queued/running work before tearing down the runtime or
        // worktree. This runs only after the terminal command committed. The
        // scheduler scopes by exact task_id, so siblings keep running.
        scheduler.cancelTaskTurns(taskId);
        // Interrupt the subprocess(es) and wait for them to actually exit
        // before we reap the worktree. interrupt() only sends destroy()
        // (SIGTERM); without the wait, `git worktree remove` can race a
        // still-live process that's mid-tool-call inside the worktree we're
        // deleting. Include the Task's development and Brain runtimes.
        for (ThreadAgent agent : registry.findTaskAgents(List.of(taskId))) {
            agent.interrupt();
            awaitAgentStopped(agent);
        }
        registry.evictTaskAgent(threadId, taskId);
        // finishTerminalInCommand already sealed every durable child before
        // this post-commit runtime teardown began.
        // Drop any still-open publish gate (push / ship / merge): the worktree
        // is about to be reaped, so an un-dismissed gate would be approvable
        // against nothing and resolve into a silent no-op.
        notificationService.dismissOpenForTask(threadId, taskId);
        worktreeService.reap(task);
        return taskStore.findTaskById(taskId).orElse(task);
    }

    /** Block until the agent leaves RUNNING (its turn thread transitions to
     *  IDLE once the destroyed subprocess exits) or the timeout elapses. */
    private void awaitAgentStopped(ThreadAgent agent)
    {
        // Fully qualified: this file imports the domain Thread, which shadows
        // java.lang.Thread.
        long deadlineNanos = System.nanoTime() + AGENT_STOP_TIMEOUT.toNanos();
        while (agent.status() == ThreadStatus.RUNNING && System.nanoTime() < deadlineNanos) {
            try {
                java.lang.Thread.sleep(50);
            }
            catch (InterruptedException e) {
                java.lang.Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /**
     * Set a task aside: stop its agent and park it at {@link TaskStatus#PAUSED}
     * with its worktree and branch intact. Unlike ship
     * (publish) or cancel (throw away + reap), pause preserves the work — the
     * thread treats a PAUSED task as not-active, freeing the trunk to plan or
     * run another task, and {@link #resumeTask} recreates its runtime later.
     * Idempotent.
     */
    public Task pauseTask(String threadId, String taskId)
    {
        return TaskExternalEffectGate.withEffectGate(taskId, () -> pauseTaskCommand(threadId, taskId));
    }

    private Task pauseTaskCommand(String threadId, String taskId)
    {
        Object teardownToken = new Object();
        PauseResult result;
        try {
            result = commands.execute(taskId, () -> pauseTaskInCommand(
                    threadId, taskId, teardownToken));
        }
        catch (RuntimeException e) {
            pauseTeardownTokens.remove(taskId, teardownToken);
            throw e;
        }
        if (!result.changed()) {
            return result.task();
        }

        // These owners act only after PAUSED is committed. Each is
        // idempotent, so a crash between them is repaired by the stop sweep.
        try {
            brainReview.pauseActiveReview(taskId, "user_paused_task");
        }
        catch (RuntimeException e) {
            log.warn("pausing active Brain review for task {} threw: {}", taskId, e.getMessage());
        }
        clearParkedReviewNotifications(threadId, taskId);
        pauseTeardownExecutor.execute(() -> stopPausedTaskRuntime(taskId, teardownToken));
        return result.task();
    }

    private PauseResult pauseTaskInCommand(
            String threadId, String taskId, Object teardownToken)
    {
        Task task = requireTask(threadId, taskId);
        if (task.status() == TaskStatus.PAUSED) {
            return new PauseResult(task, false);
        }
        // The worktree is NOT reaped (cancel does that); pause keeps it for
        // resume. Runtime teardown starts only after this command commits.
        Task paused = taskPhaseMachine.pauseInCommand(
                taskId, Actor.HUMAN, "user_paused_task");
        pauseTeardownTokens.put(taskId, teardownToken);
        return new PauseResult(paused, true);
    }

    private void clearParkedReviewNotifications(String threadId, String taskId)
    {
        // Clear any parked approve/discard notification for this task — a
        // paused task is set aside, so it must not keep showing "needs your
        // approval". Best-effort: a notification failure mustn't fail the pause.
        try {
            notificationService.listForThread(threadId).stream()
                    .filter(n -> taskId.equals(n.taskId())
                            && n.kind() == NotificationKind.AWAITING_REVIEW
                            && n.status() == NotificationStatus.UNREAD)
                    .forEach(n -> notificationService.markRead(n.id()));
        }
        catch (RuntimeException e) {
            log.warn("clearing parked notifications on pause of {} threw: {}", taskId, e.getMessage());
        }
    }

    private record PauseResult(Task task, boolean changed) {}

    private void stopPausedTaskRuntime(String taskId, Object teardownToken)
    {
        TaskPhaseMachine.withTaskLock(taskId, () -> {
            if (pauseTeardownTokens.get(taskId) != teardownToken) {
                return null;
            }
            try {
                stopReconciler.reconcileStoppedTask(taskId);
            }
            finally {
                pauseTeardownTokens.remove(taskId, teardownToken);
            }
            return null;
        });
    }

    /** Compatibility wrapper for the nested task URL. */
    public Task resumeTask(String threadId, String taskId)
    {
        return TaskExternalEffectGate.withEffectGate(
                taskId, () -> resumeTaskCommand(taskId, threadId, false));
    }

    /**
     * Revive an exact task runtime. This owns task-scoped resume; thread
     * resume is trunk-only.
     */
    public Task resumeTask(String taskId)
    {
        return TaskExternalEffectGate.withEffectGate(
                taskId, () -> resumeTaskCommand(taskId, null, false));
    }

    /** Explicitly restart a CI lifecycle that parked after exhausting its
     *  autonomous attempts. Ordinary Resume deliberately refuses this state
     *  because recovery causes a remote GitHub Actions rerun. */
    public Task retryFailedCi(String threadId, String taskId)
    {
        return TaskExternalEffectGate.withEffectGate(
                taskId, () -> resumeTaskCommand(taskId, threadId, true));
    }

    private Task resumeTaskCommand(
            String taskId, String expectedThreadId, boolean retryingCi)
    {
        Optional<TaskRecoveryRequest> request = taskStore.recoveryRequest(taskId);
        boolean externalRequest = request
                .filter(value -> TaskRecoveryRequest.KIND_EXTERNAL_SAGA.equals(value.kind()))
                .isPresent();
        ExternalSagaRecovery externalRecovery;
        try {
            externalRecovery = prepareExternalSagaRecovery(taskId);
        }
        catch (ResponseStatusException e) {
            if (externalRequest && e.getStatusCode().value() == 409) {
                return rejectExternalSagaRecovery(
                        taskId, request.orElseThrow(), "external_saga_revalidation_failed");
            }
            throw e;
        }
        if (externalRequest && !externalRecovery.planned()) {
            return rejectExternalSagaRecovery(
                    taskId, request.orElseThrow(), "external_saga_authorization_missing");
        }
        ResumeCommandResult result = commands.execute(taskId,
                () -> routeResumeInCommand(
                        taskId, expectedThreadId, externalRecovery, retryingCi));
        if (result.invalidatePauseToken()) {
            pauseTeardownTokens.remove(taskId);
        }
        return switch (result.route()) {
            case REQUESTED_RESUME -> finishRequestedResume(taskId, result.task());
            case REQUESTED_RECOVERY -> finishRequestedRecovery(taskId, result.task());
            case START_RUNTIME -> {
                resumeRuntime(result.runtime());
                yield result.task();
            }
            case COMPLETE -> result.task();
        };
    }

    private ResumeCommandResult routeResumeInCommand(
            String taskId,
            String expectedThreadId,
            ExternalSagaRecovery externalRecovery,
            boolean retryingCi)
    {
        Task routed = taskStore.findTaskById(taskId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "no task: " + taskId));
        if (expectedThreadId != null && !routed.threadId().equals(expectedThreadId)) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(404),
                    "task " + taskId + " is not on thread " + expectedThreadId);
        }
        if (routed.status() == TaskStatus.PAUSED && routed.phase() != TaskPhase.NEEDS_ATTENTION) {
            taskPhaseMachine.requestResumeInCommand(taskId);
            return ResumeCommandResult.requested(routed, ResumeRoute.REQUESTED_RESUME);
        }
        if (routed.status() == TaskStatus.NEEDS_ATTENTION
                || routed.phase() == TaskPhase.NEEDS_ATTENTION) {
            boolean ciRetryRequired = requiresExplicitCiRetry(routed);
            if (ciRetryRequired != retryingCi) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(409), ciRetryRequired
                        ? "task " + taskId + " requires the explicit Retry CI action"
                        : "task " + taskId + " is not parked for exhausted CI attempts");
            }
            if (retryingCi) {
                taskPhaseMachine.requestRecoveryInCommand(
                        taskId, TaskRecoveryRequest.KIND_CI_RETRY);
            }
            else if (externalRecovery.active()) {
                if (!externalRecovery.planned()) {
                    throw new ResponseStatusException(
                            HttpStatusCode.valueOf(409),
                            "task " + taskId + " has no valid external-saga recovery plan");
                }
                taskPhaseMachine.requestRecoveryInCommand(
                        taskId, TaskRecoveryRequest.KIND_EXTERNAL_SAGA,
                        recoveryPayload(externalRecovery));
            }
            else {
                taskPhaseMachine.requestRecoveryInCommand(
                        taskId, TaskRecoveryRequest.KIND_NORMAL);
            }
            return ResumeCommandResult.requested(routed, ResumeRoute.REQUESTED_RECOVERY);
        }
        if (retryingCi) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "task " + taskId + " is not parked for exhausted CI attempts");
        }
        if (routed.status() == TaskStatus.ERRORED) {
            return retryErroredTaskInCommand(taskId);
        }
        if (routed.status() == TaskStatus.ARCHIVED) {
            return reviveArchivedTaskInCommand(taskId);
        }
        return resumeIdleRuntimeInCommand(taskId);
    }

    private ExternalSagaRecovery prepareExternalSagaRecovery(String taskId)
    {
        ExternalSagaRecovery active = activeExternalSaga(taskId);
        if (active.kind() == ExternalSagaKind.TASK_PUSH) {
            return active.withPushPlan(pushSaga.prepareRecovery(
                    taskId, TaskPushSaga.DEFAULT_RECOVERY_ALLOWANCE).orElse(null));
        }
        if (active.kind() == ExternalSagaKind.ROUND_GATE) {
            return active.withRoundGatePlan(roundGateSaga.prepareRecovery(
                    taskId, RoundGateSaga.DEFAULT_RECOVERY_ALLOWANCE).orElse(null));
        }
        return active;
    }

    private ExternalSagaRecovery verifyExternalSagaRecovery(String taskId)
    {
        ExternalSagaRecovery active = activeExternalSaga(taskId);
        if (active.kind() == ExternalSagaKind.TASK_PUSH) {
            return active.withPushPlan(
                    pushSaga.verifyRecoveryRequest(taskId).orElse(null));
        }
        if (active.kind() == ExternalSagaKind.ROUND_GATE) {
            return active.withRoundGatePlan(
                    roundGateSaga.verifyRecoveryRequest(taskId).orElse(null));
        }
        return active;
    }

    private ExternalSagaRecovery activeExternalSaga(String taskId)
    {
        Optional<String> pushToken = pushSaga.activeToken(taskId);
        Optional<String> roundGateToken = roundGateSaga.activeToken(taskId);
        if (pushToken.isPresent() && roundGateToken.isPresent()) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(409),
                    "task " + taskId + " has conflicting active external sagas");
        }
        if (pushToken.isPresent()) {
            return new ExternalSagaRecovery(
                    ExternalSagaKind.TASK_PUSH, pushToken.orElseThrow(), null, null);
        }
        if (roundGateToken.isPresent()) {
            return new ExternalSagaRecovery(
                    ExternalSagaKind.ROUND_GATE, roundGateToken.orElseThrow(), null, null);
        }
        return ExternalSagaRecovery.none();
    }

    private String recoveryPayload(ExternalSagaRecovery recovery)
    {
        if (recovery.kind() == ExternalSagaKind.TASK_PUSH) {
            return pushSaga.recoveryPayload(recovery.pushPlan());
        }
        if (recovery.kind() == ExternalSagaKind.ROUND_GATE) {
            return roundGateSaga.recoveryPayload(recovery.roundGatePlan());
        }
        throw new IllegalArgumentException("external saga is not active");
    }

    /**
     * Resume for a cleanly paused task is a durable request plus a
     * teardown barrier, not one leap: the request survives while the
     * pre-pause runtime winds down, and only the proven-stopped barrier
     * command leaves PAUSED. The inline reconcile makes the common case
     * (teardown finished long ago) complete within this request.
     */
    private Task finishRequestedResume(String taskId, Task requested)
    {
        stopReconciler.reconcileStoppedTask(taskId);
        if (!stopReconciler.runtimeStopped(taskId)) {
            // Still winding down; the stop reconciler's sweep completes
            // this request once the barrier is proven.
            return taskStore.findTaskById(taskId).orElse(requested);
        }
        return completeRequestedResume(taskId);
    }

    /**
     * The resume barrier's completion command — invoked inline when the
     * pre-pause runtime is already proven gone, or by
     * {@link TaskRuntimeStopReconciler}'s sweep once it is. Invalidates
     * the pause teardown token, leaves PAUSED via the machine's derived
     * safe status, and re-drives the owning coordinator or stage runtime.
     */
    public Task completeRequestedResume(String taskId)
    {
        return TaskExternalEffectGate.withEffectGate(
                taskId, () -> completeRequestedResumeLocked(taskId));
    }

    private Task completeRequestedResumeLocked(String taskId)
    {
        ResumeCompletion result = commands.execute(taskId,
                () -> completeRequestedResumeInCommand(taskId));
        pauseTeardownTokens.remove(taskId);
        if (!result.changed()) {
            return result.task();
        }
        if (result.brainOwnsResume()) {
            return finishBrainReviewResume(taskId, result.task());
        }
        if (result.runtime() != null) {
            resumeRuntime(result.runtime());
        }
        if (result.pushToken() != null) {
            pushSaga.drive(result.pushToken());
            return taskStore.findTaskById(taskId).orElse(result.task());
        }
        if (result.roundGateToken() != null) {
            roundGateSaga.drive(result.roundGateToken());
            return taskStore.findTaskById(taskId).orElse(result.task());
        }
        return result.task();
    }

    private ResumeCompletion completeRequestedResumeInCommand(String taskId)
    {
        Task task = taskStore.findTaskById(taskId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "no task: " + taskId));
        if (task.status() != TaskStatus.PAUSED) {
            return ResumeCompletion.unchanged(task);
        }
        boolean brainOwnsResume = brainReview.ownsParkedResume(taskId);
        Task resumed = taskPhaseMachine.completeResumeInCommand(
                taskId, Actor.HUMAN, "user_resumed_task");
        ExternalSagaRecovery externalSaga = activeExternalSaga(taskId);
        if ((externalSaga.kind() == ExternalSagaKind.TASK_PUSH
                && resumed.phase() != TaskPhase.AWAITING_PUSH)
                || (externalSaga.kind() == ExternalSagaKind.ROUND_GATE
                        && resumed.phase() != TaskPhase.AWAITING_REMOTE_REVIEW)) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(409),
                    "active external saga does not match the paused task checkpoint");
        }
        Thread resumedThread = reviveOwningThreadInCommand(task);
        Optional<StageInstance> activeStage = stageStore.findActiveStage(taskId);
        RuntimeResume runtime = null;
        if (!brainOwnsResume
                && resumed.phase() != TaskPhase.VALIDATING
                && !externalSaga.active()) {
            runtime = prepareRuntimeResumeInCommand(resumedThread, resumed, activeStage);
        }
        return new ResumeCompletion(
                resumed, true, brainOwnsResume, runtime,
                externalSaga.kind() == ExternalSagaKind.TASK_PUSH
                        ? externalSaga.activeToken() : null,
                externalSaga.kind() == ExternalSagaKind.ROUND_GATE
                        ? externalSaga.activeToken() : null);
    }

    private Task finishBrainReviewResume(String taskId, Task resumed)
    {
        if (brainReview.resumeParkedReview(taskId)) {
            return resumed;
        }
        // The coordinator re-parks failed validation/enqueue attempts with a
        // fresh durable reason. Return that state instead of hiding its trail.
        Task current = taskStore.findTaskById(taskId).orElse(resumed);
        if (current.phase() == TaskPhase.NEEDS_ATTENTION
                || current.status() == TaskStatus.NEEDS_ATTENTION) {
            return current;
        }
        throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                "task " + taskId + " has no resumable Brain review");
    }

    /**
     * Recovery from NEEDS_ATTENTION mirrors the paused shape: a durable
     * request, the teardown barrier, then the completion command that
     * restores the checkpointed phase.
     */
    private Task finishRequestedRecovery(String taskId, Task requested)
    {
        stopReconciler.reconcileStoppedTask(taskId);
        if (!stopReconciler.runtimeStopped(taskId)) {
            // Still winding down; the stop reconciler's sweep completes
            // this request once the barrier is proven.
            return taskStore.findTaskById(taskId).orElse(requested);
        }
        return completeRequestedRecovery(taskId);
    }

    /**
     * The recovery barrier's completion command — invoked inline when
     * the pre-park runtime is already proven gone, or by
     * {@link TaskRuntimeStopReconciler}'s sweep once it is. Restores the
     * checkpointed phase (server-derived fallback for legacy rows) and
     * re-drives the owning coordinator or stage runtime.
     */
    public Task completeRequestedRecovery(String taskId)
    {
        return TaskExternalEffectGate.withEffectGate(
                taskId, () -> completeRequestedRecoveryLocked(taskId));
    }

    private Task completeRequestedRecoveryLocked(String taskId)
    {
        Optional<TaskRecoveryRequest> request = taskStore.recoveryRequest(taskId);
        boolean externalRequest = request
                .filter(value -> TaskRecoveryRequest.KIND_EXTERNAL_SAGA.equals(value.kind()))
                .isPresent();
        ExternalSagaRecovery externalRecovery;
        try {
            externalRecovery = verifyExternalSagaRecovery(taskId);
        }
        catch (ResponseStatusException e) {
            if (externalRequest && e.getStatusCode().value() == 409) {
                return rejectExternalSagaRecovery(
                        taskId, request.orElseThrow(), "external_saga_revalidation_failed");
            }
            throw e;
        }
        if (externalRequest && !externalRecovery.planned()) {
            return rejectExternalSagaRecovery(
                    taskId, request.orElseThrow(), "external_saga_authorization_missing");
        }
        RecoveryCompletion result = commands.execute(taskId,
                () -> completeRequestedRecoveryInCommand(
                        taskId, externalRecovery));
        if (result.legacyRestart()) {
            Task restarted = restartLegacyLocalInRecovery(result.original());
            pauseTeardownTokens.remove(taskId);
            return restarted;
        }
        pauseTeardownTokens.remove(taskId);
        if (!result.changed()) {
            return result.task();
        }
        Task recovered = result.task();
        if (result.brainOwnsResume()) {
            recovered = finishBrainReviewResume(taskId, recovered);
            if (recovered.phase() == TaskPhase.NEEDS_ATTENTION
                    || recovered.status() == TaskStatus.NEEDS_ATTENTION) {
                return recovered;
            }
        }
        else if (result.runtime() != null) {
            resumeRuntime(result.runtime());
        }
        if (result.pushToken() != null) {
            pushSaga.drive(result.pushToken());
            recovered = taskStore.findTaskById(taskId).orElse(recovered);
        }
        else if (result.roundGateToken() != null) {
            roundGateSaga.drive(result.roundGateToken());
            recovered = taskStore.findTaskById(taskId).orElse(recovered);
        }
        clearNeedsAttentionNotifications(result.original());
        return recovered;
    }

    private Task rejectExternalSagaRecovery(
            String taskId, TaskRecoveryRequest request, String reason)
    {
        commands.execute(taskId, () -> {
            taskPhaseMachine.rejectRecoveryRequestInCommand(
                    taskId, request.id(), reason);
            return null;
        });
        log.warn("rejected stale external-saga recovery {} for task {}: {}",
                request.id(), taskId, reason);
        return taskStore.findTaskById(taskId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "no task: " + taskId));
    }

    private RecoveryCompletion completeRequestedRecoveryInCommand(
            String taskId, ExternalSagaRecovery externalRecovery)
    {
        Task task = taskStore.findTaskById(taskId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "no task: " + taskId));
        if (task.status() != TaskStatus.NEEDS_ATTENTION
                && task.phase() != TaskPhase.NEEDS_ATTENTION) {
            return RecoveryCompletion.unchanged(task);
        }
        Optional<TaskRecoveryRequest> request = taskStore.recoveryRequest(taskId);
        boolean externalSaga = request
                .filter(value -> TaskRecoveryRequest.KIND_EXTERNAL_SAGA.equals(value.kind()))
                .isPresent();
        boolean retryingCi = request
                .filter(value -> TaskRecoveryRequest.KIND_CI_RETRY.equals(value.kind()))
                .isPresent();
        Optional<TaskPhase> evidence = evidenceBasedRecoveryPhase(task);
        if (taskStore.recoveryPhase(taskId).isEmpty() && evidence.isEmpty()) {
            // Ambiguous migrated row: seal its obsolete local owners before a
            // separate recovery command restarts planning.
            return RecoveryCompletion.legacy(task);
        }
        if (externalSaga) {
            if (!externalRecovery.planned()) {
                throw new ResponseStatusException(
                        HttpStatusCode.valueOf(409),
                        "task " + taskId + " has no valid external-saga recovery plan");
            }
            if (externalRecovery.kind() == ExternalSagaKind.TASK_PUSH) {
                pushSaga.resumeExternalSagaInCommand(externalRecovery.pushPlan());
            }
            else {
                roundGateSaga.resumeExternalSagaInCommand(
                        externalRecovery.roundGatePlan());
            }
        }
        else if (externalRecovery.active()) {
            throw new ResponseStatusException(
                    HttpStatusCode.valueOf(409),
                    "task " + taskId + " requires exact external-saga recovery");
        }
        boolean brainOwnsResume = !externalSaga && brainReview.ownsParkedResume(taskId);
        // The fallback matters only for legacy rows without a machine
        // checkpoint; with one, completeRecovery restores it instead.
        TaskPhase fallback = evidence.orElse(TaskPhase.IMPLEMENTING);
        Task recovered = externalSaga
                ? taskPhaseMachine.completeExternalSagaRecoveryInCommand(
                        taskId, Actor.HUMAN, "external_saga_recovered", fallback)
                : taskPhaseMachine.completeRecoveryInCommand(
                        taskId, Actor.HUMAN,
                        retryingCi ? "user_retried_ci" : "user_resumed_task",
                        fallback);
        Thread resumedThread = reviveOwningThreadInCommand(task);
        Optional<StageInstance> activeStage = stageStore.findActiveStage(taskId);
        RuntimeResume runtime = null;
        if (!externalSaga
                && !brainOwnsResume
                && shouldResumeRuntime(recovered.phase(), activeStage)) {
            runtime = prepareRuntimeResumeInCommand(resumedThread, recovered, activeStage);
        }
        return new RecoveryCompletion(
                recovered, task, true, false, brainOwnsResume, runtime,
                externalRecovery.kind() == ExternalSagaKind.TASK_PUSH
                        ? externalRecovery.token() : null,
                externalRecovery.kind() == ExternalSagaKind.ROUND_GATE
                        ? externalRecovery.token() : null);
    }

    /**
     * Retry is the only exit from a pointer-backed ERRORED task: the
     * machine re-verifies the exact failed turn is still the liveness
     * authority and moves ERRORED → IDLE, then this command inserts the
     * keyed replacement turn and hands it the pointer. An insert failure
     * rolls the whole command back, leaving ERRORED intact.
     */
    private ResumeCommandResult retryErroredTaskInCommand(String taskId)
    {
        String failedTurnId = taskStore.currentLivenessTurnId(taskId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(409), "task " + taskId + " has no current failure"));
        ThreadTurn failed = taskPhaseMachine.retryErroredInCommand(taskId, failedTurnId);
        Task retried = taskStore.findTaskById(taskId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "no task: " + taskId));
        Thread resumedThread = reviveOwningThreadInCommand(retried);
        // Attempt is always 1 per failed id: a failed retry becomes the
        // new failure identity, so the next retry keys off the new turn.
        String kickKey = "task-retry:" + failedTurnId + ":1";
        String replacementId = switch (failed.scope()) {
            case TASK -> scheduler.enqueueTaskTurnOnce(
                    kickKey, resumedThread, failed.input(), failed.requireTaskId(),
                    failed.initiator(), failed.agentRunId(), TurnLiveness.CODE);
            case STAGE -> scheduler.enqueueStageTurnOnce(
                    kickKey, resumedThread, failed.input(), failed.requireTaskId(),
                    failed.requireStageId(), failed.initiator(), failed.agentRunId(),
                    TurnLiveness.CODE);
            case TRUNK -> throw new IllegalStateException(
                    "task " + taskId + " points at a trunk-scoped failed turn");
        };
        taskStore.setCurrentLivenessTurnIdIf(taskId, failedTurnId, replacementId);
        return ResumeCommandResult.complete(
                retried.withStatus(TaskStatus.IDLE).withEndedAt(null).withErrorMessage(null));
    }

    private ResumeCommandResult reviveArchivedTaskInCommand(String taskId)
    {
        Task revived = taskPhaseMachine.reviveArchivedInCommand(taskId);
        Thread resumedThread = reviveOwningThreadInCommand(revived);
        Optional<StageInstance> activeStage = stageStore.findActiveStage(taskId);
        if (revived.phase() != TaskPhase.VALIDATING) {
            RuntimeResume runtime = prepareRuntimeResumeInCommand(
                    resumedThread, revived, activeStage);
            return ResumeCommandResult.runtime(revived, runtime, false);
        }
        return ResumeCommandResult.complete(revived);
    }

    private ResumeCommandResult resumeIdleRuntimeInCommand(String taskId)
    {
        Task resumed = taskPhaseMachine.resumeIdleRuntimeInCommand(taskId);
        Thread resumedThread = reviveOwningThreadInCommand(resumed);
        Optional<StageInstance> activeStage = stageStore.findActiveStage(taskId);
        if (resumed.phase() != TaskPhase.VALIDATING) {
            RuntimeResume runtime = prepareRuntimeResumeInCommand(
                    resumedThread, resumed, activeStage);
            return ResumeCommandResult.runtime(resumed, runtime, true);
        }
        return ResumeCommandResult.complete(resumed, true);
    }

    private Thread reviveOwningThreadInCommand(Task task)
    {
        Thread thread = threadStore.findThreadById(task.threadId())
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "no thread: " + task.threadId()));
        Thread resumed = revivedThread(thread);
        if (resumed != thread) {
            threadStore.saveThread(resumed);
        }
        return resumed;
    }

    private Optional<String> latestNeedsAttentionReason(Task task)
    {
        if (task.phase() != TaskPhase.NEEDS_ATTENTION
                && task.status() != TaskStatus.NEEDS_ATTENTION) {
            return Optional.empty();
        }
        return taskStore.listPhaseEvents(task.id()).stream()
                .filter(event -> event.toPhase() == TaskPhase.NEEDS_ATTENTION)
                .max((left, right) -> left.transitionedAt().compareTo(right.transitionedAt()))
                .map(TaskPhaseEvent::reason);
    }

    private boolean requiresExplicitCiRetry(Task task)
    {
        return latestNeedsAttentionReason(task)
                .map(reason -> "ci_fix_attempts_exhausted".equals(reason)
                        || "ci_fix_no_changes".equals(reason))
                .orElse(false);
    }

    /**
     * The server-derived recovery phase for a legacy row with no machine
     * checkpoint, from durable evidence only: the last real phase event
     * before the park, else the linked PR's authoritative state. Empty
     * when neither exists — those rows restart planning instead of
     * guessing.
     */
    private Optional<TaskPhase> evidenceBasedRecoveryPhase(Task task)
    {
        Optional<PR> pr = prService.findByTask(task.id());
        if (pr.isPresent()) {
            String status = pr.get().status();
            if (PR.STATUS_MERGED.equals(status) || PR.STATUS_CLOSED.equals(status)) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                        "task " + task.id() + " has a terminal pull request");
            }
        }

        TaskPhase blockedFrom = task.phase();
        if (blockedFrom == TaskPhase.NEEDS_ATTENTION) {
            List<TaskPhaseEvent> phaseEvents = taskStore.listPhaseEvents(task.id());
            for (int i = phaseEvents.size() - 1; i >= 0; i--) {
                if (phaseEvents.get(i).toPhase() == TaskPhase.NEEDS_ATTENTION) {
                    blockedFrom = phaseEvents.get(i).fromPhase();
                    break;
                }
            }
        }
        if (blockedFrom != null && blockedFrom != TaskPhase.NEEDS_ATTENTION) {
            return Optional.of(switch (blockedFrom) {
                case PLANNING -> blockedFrom;
                case AWAITING_PUSH -> TaskPhase.AWAITING_PUSH;
                case PUSHED_AWAITING_CI, AWAITING_READY -> TaskPhase.PUSHED_AWAITING_CI;
                case AWAITING_REMOTE_REVIEW -> TaskPhase.AWAITING_REMOTE_REVIEW;
                case IMPLEMENTING, NEEDS_ATTENTION -> TaskPhase.IMPLEMENTING;
                case VALIDATING -> TaskPhase.VALIDATING;
                case INTERNAL_REVIEW, ADDRESSING_LOCAL_COMMENTS -> blockedFrom;
                case COMPLETED -> throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                        "task " + task.id() + " is complete");
            });
        }
        return pr.map(linked -> switch (linked.status()) {
            case PR.STATUS_REMOTE_DRAFTED -> TaskPhase.PUSHED_AWAITING_CI;
            case PR.STATUS_REMOTE_OPEN -> TaskPhase.AWAITING_REMOTE_REVIEW;
            case PR.STATUS_LOCAL_OPEN -> TaskPhase.AWAITING_PUSH;
            default -> TaskPhase.IMPLEMENTING;
        });
    }

    /**
     * The safe restart for an ambiguous legacy park (both axes
     * NEEDS_ATTENTION, no checkpoint, no phase-event or PR evidence):
     * seal every local owner, clear the stale liveness pointer, re-enter
     * PLANNING + IDLE through the recovery command, and arm a fresh plan
     * kickoff. The phase-transition listener reopens the PlanStage.
     */
    private Task restartLegacyLocalInRecovery(Task task)
    {
        // The terminal sealer owns its own lifecycle command. Run it before
        // reopening Planning, then use a fresh task command for the durable
        // recovery state and liveness pointer.
        sealer.seal(task.id(), "legacy_local_restarted");
        Task restarted = commands.execute(task.id(), () -> {
            taskStore.currentLivenessTurnId(task.id()).ifPresent(
                    current -> taskStore.setCurrentLivenessTurnIdIf(task.id(), current, null));
            return taskPhaseMachine.completeRecoveryInCommand(
                    task.id(), Actor.HUMAN, "legacy_local_restarted", TaskPhase.PLANNING);
        });
        eventPublisher.publishEvent(new PlanKickoffRequested(
                task.id(), task.openingPrompt(), /* trunkPlan */ null));
        clearNeedsAttentionNotifications(task);
        return restarted;
    }

    private static boolean shouldResumeRuntime(
            TaskPhase phase, Optional<StageInstance> activeStage)
    {
        if (phase == TaskPhase.VALIDATING) {
            return false;
        }
        if (activeStage.isPresent()) {
            return true;
        }
        return switch (phase) {
            case IMPLEMENTING, INTERNAL_REVIEW, ADDRESSING_LOCAL_COMMENTS -> true;
            default -> false;
        };
    }

    private void clearNeedsAttentionNotifications(Task task)
    {
        try {
            notificationService.listForThread(task.threadId()).stream()
                    .filter(n -> task.id().equals(n.taskId())
                            && n.kind() == NotificationKind.NEEDS_ATTENTION
                            && n.status() == NotificationStatus.UNREAD)
                    .forEach(n -> notificationService.markRead(n.id()));
        }
        catch (RuntimeException e) {
            log.warn("clearing needs-attention notifications on recovery of {} threw: {}",
                    task.id(), e.getMessage());
        }
    }

    private static Thread revivedThread(Thread thread)
    {
        return switch (thread.status()) {
            case ERRORED, COMPLETED, ARCHIVED -> new Thread(
                    thread.id(), thread.kind(), thread.provider(), thread.agentSessionId(),
                    thread.title(), ThreadStatus.IDLE, thread.model(),
                    thread.costUsdMilli(), thread.tokensIn(), thread.tokensOut(),
                    thread.createdAt(), Instant.now(),
                    /* endedAt */ null, /* errorMessage */ null,
                    thread.flow(), thread.workspaceId(), thread.workModel(),
                    thread.parentReviewPassId(), thread.parallelSlots(), thread.parentTaskId());
            default -> thread;
        };
    }

    private RuntimeResume prepareRuntimeResumeInCommand(
            Thread parentThread, Task task, Optional<StageInstance> activeStage)
    {
        if (activeStage.map(StageInstance::type).filter(StageType.PLAN_STAGE::equals).isPresent()) {
            Thread brainThread = threadStore.findBrainThreadByTask(task.id())
                    .orElseThrow(() -> new ResponseStatusException(
                            HttpStatusCode.valueOf(409), "no task brain thread for task: " + task.id()));
            Thread resumedBrain = revivedThread(brainThread);
            if (resumedBrain != brainThread) {
                threadStore.saveThread(resumedBrain);
            }
            return new RuntimeResume(resumedBrain, task, null, true);
        }
        String stageId = activeStage.map(stage -> stage.id().toString()).orElse(null);
        return new RuntimeResume(parentThread, task, stageId, false);
    }

    private void resumeRuntime(RuntimeResume runtime)
    {
        if (runtime.brain()) {
            registry.getOrCreateTaskBrainAgent(runtime.thread()).resume();
            return;
        }
        registry.getOrCreateTaskAgent(
                runtime.thread(), runtime.task(), runtime.stageId()).resume();
    }

    private enum ResumeRoute
    {
        REQUESTED_RESUME,
        REQUESTED_RECOVERY,
        START_RUNTIME,
        COMPLETE,
    }

    private record RuntimeResume(
            Thread thread, Task task, String stageId, boolean brain) {}

    private record ResumeCommandResult(
            Task task, ResumeRoute route, RuntimeResume runtime, boolean invalidatePauseToken)
    {
        private static ResumeCommandResult requested(Task task, ResumeRoute route)
        {
            return new ResumeCommandResult(task, route, null, false);
        }

        private static ResumeCommandResult runtime(
                Task task, RuntimeResume runtime, boolean invalidatePauseToken)
        {
            return new ResumeCommandResult(
                    task, ResumeRoute.START_RUNTIME, runtime, invalidatePauseToken);
        }

        private static ResumeCommandResult complete(Task task)
        {
            return complete(task, false);
        }

        private static ResumeCommandResult complete(Task task, boolean invalidatePauseToken)
        {
            return new ResumeCommandResult(
                    task, ResumeRoute.COMPLETE, null, invalidatePauseToken);
        }
    }

    private record ResumeCompletion(
            Task task,
            boolean changed,
            boolean brainOwnsResume,
            RuntimeResume runtime,
            String pushToken,
            String roundGateToken)
    {
        private static ResumeCompletion unchanged(Task task)
        {
            return new ResumeCompletion(task, false, false, null, null, null);
        }
    }

    private record RecoveryCompletion(
            Task task,
            Task original,
            boolean changed,
            boolean legacyRestart,
            boolean brainOwnsResume,
            RuntimeResume runtime,
            String pushToken,
            String roundGateToken)
    {
        private static RecoveryCompletion unchanged(Task task)
        {
            return new RecoveryCompletion(
                    task, task, false, false, false, null, null, null);
        }

        private static RecoveryCompletion legacy(Task task)
        {
            return new RecoveryCompletion(
                    task, task, false, true, false, null, null, null);
        }
    }

    private enum ExternalSagaKind
    {
        TASK_PUSH,
        ROUND_GATE,
    }

    private record ExternalSagaRecovery(
            ExternalSagaKind kind,
            String activeToken,
            TaskPushSaga.RecoveryPlan pushPlan,
            RoundGateSaga.RecoveryPlan roundGatePlan)
    {
        private static ExternalSagaRecovery none()
        {
            return new ExternalSagaRecovery(null, null, null, null);
        }

        private boolean active()
        {
            return kind != null;
        }

        private boolean planned()
        {
            return pushPlan != null || roundGatePlan != null;
        }

        private String token()
        {
            if (pushPlan != null) {
                return pushPlan.token();
            }
            return roundGatePlan == null ? null : roundGatePlan.token();
        }

        private ExternalSagaRecovery withPushPlan(TaskPushSaga.RecoveryPlan plan)
        {
            return new ExternalSagaRecovery(kind, activeToken, plan, null);
        }

        private ExternalSagaRecovery withRoundGatePlan(RoundGateSaga.RecoveryPlan plan)
        {
            return new ExternalSagaRecovery(kind, activeToken, null, plan);
        }
    }

    /** True when the task's working dir maps to {@code repoFullName}. A
     *  task whose repo can't be resolved (clone removed) is treated as a
     *  non-match rather than throwing. */
    private boolean repoMatches(Task task, String repoFullName)
    {
        if (task.workingDir() == null || task.workingDir().isBlank()) {
            return false;
        }
        try {
            WatchedRepo repo = resolveRepo(Path.of(task.workingDir()));
            return repo.fullName().equals(repoFullName);
        }
        catch (RuntimeException e) {
            return false;
        }
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

    /** Request body for {@code POST /api/threads/{id}/tasks/{id}/ship}.
     *  {@code prTitle} / {@code prBody} carry the agent-proposed (or
     *  user-edited) PR description for the draft PR a ship opens; both
     *  are optional — a blank {@code prTitle} falls back to the thread
     *  title and a blank {@code prBody} opens the PR with no body. */
    public record ShipRequest(String nextTitle, BaseMode baseMode, String prTitle, String prBody)
    {
        public ShipRequest
        {
            if (baseMode == null) {
                baseMode = BaseMode.MAIN;
            }
        }

        public ShipRequest(String nextTitle, BaseMode baseMode)
        {
            this(nextTitle, baseMode, null, null);
        }
    }
}
