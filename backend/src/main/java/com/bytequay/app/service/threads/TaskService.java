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
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadStatus;
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
import com.bytequay.app.service.localpr.PrPushedEvent;
import com.bytequay.app.service.pr.PullRequestMergedEvent;
import com.bytequay.app.service.workspaces.WorkspaceService;
import com.bytequay.app.service.workspaces.WorkspaceShipEvent;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
    private final TaskPhaseMachine taskPhaseMachine;
    private final TaskTerminalSealer sealer;

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
            TaskPhaseMachine taskPhaseMachine,
            TaskTerminalSealer sealer)
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
        this.taskPhaseMachine = requireNonNull(taskPhaseMachine, "taskPhaseMachine is null");
        this.sealer = requireNonNull(sealer, "sealer is null");
    }

    /** All tasks for a thread, ordered by seq ascending. 404 if the
     *  thread doesn't exist (so callers can't probe arbitrary ids). */
    public List<Task> listTasksForThread(String threadId)
    {
        requireThread(threadId);
        return taskStore.listTasksByThread(threadId);
    }

    /**
     * Append to (or replace) a task's opening-prompt accumulator — the
     * text the agent reads as its first-turn input when it starts. Editable
     * only while the task is in {@link TaskPhase#QUEUED}; a write to a task
     * in any other phase is rejected (422), since the plan seals once the
     * task starts.
     */
    @Transactional
    public Task updateOpeningPrompt(String taskId, String text, boolean append)
    {
        Task task = taskStore.findTaskById(taskId)
                .orElseThrow(() -> new ResponseStatusException(
                        HttpStatusCode.valueOf(404), "no task: " + taskId));
        if (task.phase() != TaskPhase.QUEUED) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(422),
                    "opening prompt is editable only while the task is QUEUED (phase="
                            + task.phase() + ")");
        }
        String incoming = text == null ? "" : text;
        String existing = task.openingPrompt();
        String next = append && existing != null && !existing.isBlank()
                ? existing + "\n" + incoming
                : incoming;
        taskStore.setOpeningPrompt(taskId, next);
        return taskStore.findTaskById(taskId).orElse(task);
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
    @Transactional
    public Task shipAndContinue(String threadId, String taskId, ShipRequest request)
    {
        return shipOrParkAndStartNext(threadId, taskId, request, ParkMode.SHIP, false);
    }

    /** Execute a human-approved parked ship proposal without first
     *  reopening the task as active work. */
    @Transactional
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
    @Transactional
    public Task parkAndStartNext(String threadId, String taskId, ShipRequest request)
    {
        return shipOrParkAndStartNext(threadId, taskId, request, ParkMode.NEXT, false);
    }

    /** Execute a human-approved parked Next proposal. The current task
     *  remains reviewable while the created sibling becomes active. */
    @Transactional
    public Task startNextFromApprovedParkedTask(
            String threadId, String taskId, ShipRequest request)
    {
        return shipOrParkAndStartNext(threadId, taskId, request, ParkMode.NEXT, true);
    }

    private Task shipOrParkAndStartNext(
            String threadId, String taskId, ShipRequest request, ParkMode mode, boolean approvedParked)
    {
        requireNonNull(request, "request is null");
        requireNonNull(mode, "mode is null");
        Thread thread = requireThread(threadId);
        Task current = requireTask(threadId, taskId);
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
                String prBase = resolveMergeTarget(repoFullName, workingDir);
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

            // 4. Park the current task. SHIP marks it IN_REVIEW — the
            //    work is pushed and a PR is open, but the task is only
            //    COMPLETED once that PR merges (see onPullRequestMerged).
            //    NEXT keeps the row alive at AWAITING_REVIEW. Both modes
            //    preserve the worktree: SHIP runs the post-ship PR loop
            //    (CI fix / addressing comments push more commits to the
            //    branch), and NEXT's jump-back doesn't have to re-cut a
            //    worktree from origin/<branch> on a wake. The reconciler
            //    reaps a shipped task's worktree only when its PR merges.
            TaskStatus parkedStatus = mode == ParkMode.SHIP
                    ? TaskStatus.IN_REVIEW
                    : TaskStatus.AWAITING_REVIEW;
            // Both modes keep the worktree now: SHIP enters the PR loop (CI
            // fix / addressing comments push more commits), and the reconciler
            // reaps it only when the PR actually merges.
            String parkedWorktreePath = current.worktreePath();
            Integer parkedLinkedPrNumber = prNumber != null ? prNumber : current.linkedPrNumber();
            Task parked = current
                    .withStatus(parkedStatus)
                    .withWorktreePath(parkedWorktreePath)
                    .withProcessPid(null)
                    .withPrNumber(prNumber)
                    .withLinkedPrNumber(parkedLinkedPrNumber)
                    // endedAt stays null for a shipped task too — it isn't
                    // finished until its PR merges, at which point completion
                    // stamps endedAt.
                    .withEndedAt(null);
            taskStore.saveTask(parked);
            // Ship pushed + opened the PR directly, so fast-forward the phase
            // to match that observed reality: the task is no longer
            // "implementing" — it's shipped and waiting on the PR to merge, so
            // the flow stepper should read "Remote review", not "Implement".
            // It only reaches COMPLETED when the PR actually merges
            // (completeTasksForMergedPr). NEXT keeps its own parked flow.
            if (mode == ParkMode.SHIP && prNumber != null) {
                // Link the PR so the lifecycle reconciler can monitor it, and
                // fast-forward to "pushed, awaiting CI" — the draft PR's checks
                // are starting. The reconciler drives it from here (CI fix →
                // mark-ready → remote review → merge).
                taskStore.linkTaskToPr(current.id(), repoFullName + "#" + prNumber);
                taskPhaseMachine.observe(
                        current.id(), TaskPhase.PUSHED_AWAITING_CI, "shipped_draft_pr_open");
                // Ship pushed + opened the PR directly (not through a push/
                // open_pr gate), so the PR row otherwise never learns
                // about it and keeps offering "ready to push" forever.
                eventPublisher.publishEvent(new PrPushedEvent(
                        current.id(), repoFullName, prNumber, "https://github.com/" + repoFullName + "/pull/" + prNumber));
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
     * and only flips rows that are still {@code IN_REVIEW}. A bookkeeping
     * failure here never propagates back to the merge call.
     */
    @EventListener
    public void onPullRequestMerged(PullRequestMergedEvent event)
    {
        completeTasksForMergedPr(event.repoFullName(), event.prNumber());
    }

    /**
     * Advance any shipped task that owns {@code prNumber} in {@code
     * repoFullName} from IN_REVIEW to COMPLETED. Called by the dashboard
     * merge (via {@link PullRequestMergedEvent}) and directly by an
     * approved {@code merge_pr} proposal. Best-effort and idempotent:
     * narrows by repo (PR numbers aren't globally unique) and only flips
     * rows still at IN_REVIEW; a failure never propagates to the merge.
     */
    public void completeTasksForMergedPr(String repoFullName, int prNumber)
    {
        try {
            for (Task task : taskStore.findByLinkedPrNumber(prNumber)) {
                if (task.status() != TaskStatus.IN_REVIEW) {
                    continue;
                }
                if (!repoMatches(task, repoFullName)) {
                    continue;
                }
                taskStore.completeTask(task.id(), Instant.now());
                // Record the merged PR state so the task/stage surfaces show
                // the PR as merged, not open, after it lands.
                taskStore.linkPullRequest(task.id(), prNumber, "merged");
                // Drive the dev-lifecycle phase to its terminal COMPLETED
                // through the machine (not just the runtime status) so the
                // phase audit + transition event fire.
                if (task.phase() != TaskPhase.COMPLETED) {
                    try {
                        taskPhaseMachine.transition(
                                task.id(), TaskPhase.COMPLETED, "pr_merged", Actor.WEBHOOK);
                    }
                    catch (RuntimeException e) {
                        log.warn("phase -> COMPLETED for task {} failed: {}",
                                task.id(), e.getMessage());
                    }
                }
                // The PR landed, so the local worktree + branch are dead
                // weight — reap them. Best-effort; a task already shipped
                // (worktree nulled) is skipped.
                notificationService.dismissOpenForTask(task.threadId(), task.id());
                worktreeService.reap(task);
                // The PR merged, so its head branch is dead — delete the remote
                // copy too (what GitHub's auto-delete-head-branch setting does).
                worktreeService.deleteRemoteBranch(task);
            }
        }
        catch (RuntimeException e) {
            log.warn("completing tasks for merged PR {} #{} failed: {}",
                    repoFullName, prNumber, e.getMessage());
        }
    }

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
        Task task = taskStore.findTaskById(taskId)
                .orElseThrow(() -> new IllegalArgumentException("no task " + taskId));
        // Interrupt the subprocess(es) and wait for them to actually exit
        // before we reap the worktree. interrupt() only sends destroy()
        // (SIGTERM); without the wait, `git worktree remove` can race a
        // still-live process that's mid-tool-call inside the worktree we're
        // deleting. A task can have several per-stage agents in flight, so
        // hit each of this task's stage agents.
        List<String> stageKeys = stageKeysForTask(taskId);
        for (ThreadAgent agent : registry.findStages(stageKeys)) {
            agent.interrupt();
            awaitAgentStopped(agent);
        }
        registry.evictStages(threadId, stageKeys);
        taskStore.cancelTask(taskId, Instant.now());
        // Drive the phase terminal so the lifecycle reconciler stops polling
        // the task and the queue scheduler frees its slot.
        if (task.phase() != TaskPhase.COMPLETED) {
            taskStore.updatePhase(taskId, TaskPhase.COMPLETED);
        }
        // Seal any still-open review round or stage (Plan / Dev / CI-fix …);
        // otherwise the rail and stage pages keep reporting work as live
        // after the task itself is CANCELED.
        sealer.seal(taskId, "task_canceled");
        // Drop any still-open publish gate (push / ship / merge): the worktree
        // is about to be reaped, so an un-dismissed gate would be approvable
        // against nothing and resolve into a silent no-op.
        notificationService.dismissOpenForTask(threadId, taskId);
        worktreeService.reap(task);
        return taskStore.findTaskById(taskId).orElse(task);
    }

    /** The set of registry stage keys a task's per-stage agents are keyed
     *  by: each of its stage ids plus the task id itself (the key for a
     *  task-level agent that ran with no stage). Lets the cancel / pause /
     *  ship paths reach exactly this task's agents without disturbing the
     *  thread's other concurrent tasks. */
    private List<String> stageKeysForTask(String taskId)
    {
        List<String> keys = new ArrayList<>();
        keys.add(taskId);
        for (StageInstance stage : stageStore.findStagesByTask(taskId)) {
            keys.add(stage.id().toString());
        }
        return keys;
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
     * with its worktree, branch, and agent session intact. Unlike ship
     * (publish) or cancel (throw away + reap), pause preserves the work — the
     * thread treats a PAUSED task as not-active, freeing the trunk to plan or
     * run another task, and {@link #resumeTask} revives it later. Idempotent.
     */
    @Transactional
    public Task pauseTask(String threadId, String taskId)
    {
        Task task = requireTask(threadId, taskId);
        if (task.status() == TaskStatus.PAUSED) {
            return task;
        }
        if (!isPausable(task.status())) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "task " + taskId + " cannot be paused from status " + task.status());
        }
        // Stop this task's live stage agent(s) and drop them so the task
        // frees its lease — the thread's other tasks keep running. The
        // worktree is NOT reaped (cancel does that); pause keeps it for
        // resume.
        List<String> stageKeys = stageKeysForTask(taskId);
        registry.findStages(stageKeys).forEach(ThreadAgent::interrupt);
        registry.evictStages(threadId, stageKeys);
        Task paused = task.withStatus(TaskStatus.PAUSED).withProcessPid(null);
        taskStore.saveTask(paused);
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
        return paused;
    }

    /**
     * Revive a {@link TaskStatus#PAUSED} task back to {@link TaskStatus#IDLE}
     * so the thread runs it again (the next turn re-spawns the agent in its
     * worktree via {@code --resume}). A thread may run several tasks at once,
     * so reviving a paused task doesn't require the others to be idle.
     */
    @Transactional
    public Task resumeTask(String threadId, String taskId)
    {
        Task task = requireTask(threadId, taskId);
        if (task.status() != TaskStatus.PAUSED) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(409),
                    "task " + taskId + " is not paused");
        }
        Task resumed = task.withStatus(TaskStatus.IDLE);
        taskStore.saveTask(resumed);
        return resumed;
    }

    /** A task can be paused only while it's live, non-terminal work — not
     *  once shipped (IN_REVIEW), already parked terminally, or done. */
    private static boolean isPausable(TaskStatus status)
    {
        return switch (status) {
            case PENDING, RUNNING, AWAITING, IDLE,
                    AWAITING_REVIEW, IN_REVIEW, NEEDS_ATTENTION -> true;
            default -> false;
        };
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
