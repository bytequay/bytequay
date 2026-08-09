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
import com.bytequay.app.domain.LocalReviewSubmission;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRComment;
import com.bytequay.app.domain.PRTimelineEntry;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ThreadTurnStatus;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.domain.TurnLiveness;
import com.bytequay.app.repository.LocalReviewBrainHandoffStore;
import com.bytequay.app.repository.LocalReviewSubmissionStore;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.service.checks.LocalReviewValidationFinishedEvent;
import com.bytequay.app.service.checks.ValidationClaimService;
import com.bytequay.app.service.localpr.LocalReviewSubmittedEvent;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.review.BrainReviewServiceImpl;
import com.bytequay.app.service.review.ReviewRoundServiceImpl;
import com.bytequay.app.service.stage.ReadyToMergeService;
import com.bytequay.app.service.stage.RemoteCommentIngestor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * Drives the post-push spine of the dev-task lifecycle from the linked
 * PR's live state. Periodically, for each task waiting on its PR, it
 * fetches that PR <em>directly</em> by {@code owner/repo#n} (the task's
 * {@code linkedPrRef}) and moves the phase to match its CI / draft state.
 *
 * <p>Going straight to the PR — rather than reading the dashboard sync's
 * cached {@code ci_status} — is deliberate: a task's PR may not be in the
 * dashboard search set at all (e.g. a freshly-opened fork PR GitHub
 * search hasn't indexed yet), in which case the cached status is never
 * filled and the task would hang at PUSHED_AWAITING_CI forever. The
 * direct fetch is ETag-cached (an unchanged PR costs a 304), bounded to
 * the handful of tasks on the remote spine, and stops the moment a task
 * leaves it.
 *
 * <p>Merge → COMPLETED stays on the PR-merged event path; this driver
 * only places the task on the CI / ready / review spine.
 */
public class TaskLifecycleDriver
{
    private static final Logger log = LoggerFactory.getLogger(TaskLifecycleDriver.class);

    /** Cap on tasks scanned per sweep. The scan is already narrowed to the
     *  remote spine in SQL, so this bounds only in-flight tasks — a set
     *  that's tiny in practice and never approaches this ceiling. */
    private static final int SCAN_LIMIT = 200;
    private static final int TURN_SCAN_LIMIT = 100;
    private static final String SOURCE_ADDRESS_LOCAL_COMMENTS = "address-local-comments";
    private static final String LOCAL_COMMENT_TARGET_PREFIX = "Target comment id: ";

    /** Bounded per-batch agent attempts before the loop hands the review
     *  back to the human. */
    private static final int LOCAL_REVIEW_ATTEMPT_BOUND = 3;

    /** Bounded Brain handoff deliveries before parking visibly. */
    private static final int HANDOFF_DELIVERY_BOUND = 3;

    /** Phases that are waiting on the PR's remote state, so the linked PR
     *  is worth polling. A task outside these isn't waiting on CI/review,
     *  so we don't fetch its PR. */
    static final Set<TaskPhase> REMOTE_SPINE = EnumSet.of(
            TaskPhase.PUSHED_AWAITING_CI,
            TaskPhase.AWAITING_READY,
            TaskPhase.AWAITING_REMOTE_REVIEW);

    /** Phases the local-comments loop watches: the wait state, the addressing
     *  state, and the post-addressing Brain review recovery state. */
    static final Set<TaskPhase> LOCAL_SPINE = EnumSet.of(
            TaskPhase.AWAITING_PUSH,
            TaskPhase.ADDRESSING_LOCAL_COMMENTS,
            TaskPhase.INTERNAL_REVIEW);

    private final TaskStore taskStore;
    private final PullRequestService pullRequests;
    private final TaskPhaseMachine phaseMachine;
    private final WorktreeService worktrees;
    private final ThreadStore threadStore;
    private final ThreadTurnStore turnStore;
    private final ThreadTurnScheduler scheduler;
    private final NotificationService notifications;
    private final RemoteCommentIngestor commentIngestor;
    private final ReadyToMergeService readyToMerge;
    private final ReviewRoundServiceImpl reviewRounds;
    private final ThreadRegistry registry;
    private final StageStore stageStore;
    private final PRService prService;
    private final LocalReviewSubmissionStore submissions;
    private final LocalReviewBrainHandoffStore handoffs;
    private final ValidationClaimService claimedValidation;
    private final ObjectMapper mapper;
    private final BrainReviewServiceImpl brainReview;
    private final ApplicationEventPublisher events;

    public TaskLifecycleDriver(
            TaskStore taskStore,
            PullRequestService pullRequests,
            TaskPhaseMachine phaseMachine,
            WorktreeService worktrees,
            ThreadStore threadStore,
            ThreadTurnStore turnStore,
            ThreadTurnScheduler scheduler,
            NotificationService notifications,
            RemoteCommentIngestor commentIngestor,
            ReadyToMergeService readyToMerge,
            ReviewRoundServiceImpl reviewRounds,
            ThreadRegistry registry,
            StageStore stageStore,
            PRService prService,
            BrainReviewServiceImpl brainReview,
            LocalReviewSubmissionStore submissions,
            LocalReviewBrainHandoffStore handoffs,
            ValidationClaimService claimedValidation,
            ObjectMapper mapper,
            ApplicationEventPublisher events)
    {
        this.submissions = requireNonNull(submissions, "submissions is null");
        this.handoffs = requireNonNull(handoffs, "handoffs is null");
        this.claimedValidation = requireNonNull(claimedValidation, "claimedValidation is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.phaseMachine = requireNonNull(phaseMachine, "phaseMachine is null");
        this.worktrees = requireNonNull(worktrees, "worktrees is null");
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.turnStore = requireNonNull(turnStore, "turnStore is null");
        this.scheduler = requireNonNull(scheduler, "scheduler is null");
        this.notifications = requireNonNull(notifications, "notifications is null");
        this.commentIngestor = requireNonNull(commentIngestor, "commentIngestor is null");
        this.readyToMerge = requireNonNull(readyToMerge, "readyToMerge is null");
        this.reviewRounds = requireNonNull(reviewRounds, "reviewRounds is null");
        this.registry = requireNonNull(registry, "registry is null");
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.prService = requireNonNull(prService, "prService is null");
        this.brainReview = requireNonNull(brainReview, "brainReview is null");
        this.events = requireNonNull(events, "events is null");
    }

    public void reconcile()
    {
        for (Task task : taskStore.listByPhases(REMOTE_SPINE, SCAN_LIMIT)) {
            if (task.linkedPrRef() == null) {
                continue;
            }
            try {
                reconcileTask(task);
            }
            catch (RuntimeException e) {
                log.warn("lifecycle reconcile for task {} (PR {}) failed: {}",
                        task.id(), task.linkedPrRef(), e.getMessage());
            }
        }
    }

    /** Local twin of {@link #reconcile()}: watches a task's local PR for
     *  explicitly submitted review batches, dispatches Development, then
     *  starts a fresh Brain pass before returning to the push gate. */
    public void reconcileLocalComments()
    {
        for (Task task : taskStore.listByPhases(LOCAL_SPINE, SCAN_LIMIT)) {
            try {
                reconcileLocalTask(task);
            }
            catch (RuntimeException e) {
                log.warn("local-comments reconcile for task {} failed: {}", task.id(), e.getMessage());
            }
        }
    }

    /** Fast path for explicit submission; the scheduled sweep above remains
     *  the recovery path if the task thread was busy or enqueue failed. */
    public void onLocalReviewSubmitted(LocalReviewSubmittedEvent event)
    {
        TaskCommandExecutor.dispatchAfterCommit(() -> handleLocalReviewSubmitted(event));
    }

    private void handleLocalReviewSubmitted(LocalReviewSubmittedEvent event)
    {
        if (taskStore.isV2Task(event.taskId())) {
            return;
        }
        taskStore.findTaskById(event.taskId()).ifPresent(task -> {
            if (task.phase() == TaskPhase.INTERNAL_REVIEW) {
                // The submission is durable. Let the active Brain pass finish;
                // onInternalReviewCompleted hands it off after that transition
                // commits. The scheduled sweep still recovers a stalled pass.
                return;
            }
            try {
                reconcileLocalTask(task);
            }
            catch (RuntimeException e) {
                log.warn("immediate local-review dispatch for task {} failed: {}",
                        task.id(), e.getMessage());
            }
        });
    }

    /** A local comment submitted during Brain review is already durable but
     *  cannot move the phase until that pass releases INTERNAL_REVIEW. Re-read
     *  it immediately after the committed handoff instead of waiting a sweep. */
    public void onInternalReviewCompleted(TaskPhaseTransitionedEvent event)
    {
        TaskCommandExecutor.dispatchAfterCommit(() -> handleInternalReviewCompleted(event));
    }

    private void handleInternalReviewCompleted(TaskPhaseTransitionedEvent event)
    {
        if (event.from() != TaskPhase.INTERNAL_REVIEW || event.to() != TaskPhase.AWAITING_PUSH) {
            return;
        }
        taskStore.findTaskById(event.taskId()).ifPresent(task -> {
            try {
                reconcileLocalTask(task);
            }
            catch (RuntimeException e) {
                log.warn("post-review local-comment handoff for task {} failed: {}",
                        task.id(), e.getMessage());
            }
        });
    }

    /** Fast path after a local-addressing turn. A closed target advances
     *  the queue; a failure — or a completed turn whose root is still
     *  open — records a bounded durable failure on its owning batch so
     *  the retry gets a fresh kick key and cannot loop forever. */
    public void onLocalAddressTurnFinished(TaskTurnFinishedEvent event)
    {
        ThreadTurn turn = turnStore.findTurnById(event.turnId()).orElse(null);
        if (turn == null || !event.taskId().equals(turn.taskId())
                || turn.initiator() == null
                || !SOURCE_ADDRESS_LOCAL_COMMENTS.equals(turn.initiator().source())) {
            return;
        }
        try {
            if (event.failed()
                    || turn.status() == ThreadTurnStatus.FAILED
                    || turn.status() == ThreadTurnStatus.CANCELLED) {
                recordLocalAddressFailure(turn, "turn_failed");
            }
            else if (turn.status() != ThreadTurnStatus.COMPLETED) {
                return; // still settling — the sweep re-reads durable state
            }
            else if (!localAddressTargetClosed(turn)) {
                recordLocalAddressFailure(turn, "target_not_closed");
            }
            else {
                taskStore.findTaskById(event.taskId()).ifPresent(this::reconcileLocalTask);
            }
        }
        catch (RuntimeException e) {
            log.warn("post-turn local-review reconcile for task {} failed: {}",
                    event.taskId(), e.getMessage());
        }
    }

    private void recordLocalAddressFailure(ThreadTurn turn, String reason)
    {
        String targetId = localAddressTargetId(turn);
        if (targetId == null) {
            return;
        }
        boolean retry = TaskPhaseMachine.withTaskLock(turn.taskId(), () -> {
            LocalReviewSubmission owner = submissions.listOpenByTask(turn.taskId()).stream()
                    .filter(submission -> rootIds(submission).contains(targetId))
                    .findFirst()
                    .orElse(null);
            if (owner == null) {
                return false;
            }
            submissions.incrementFailures(owner.id());
            if (owner.failures() + 1 >= LOCAL_REVIEW_ATTEMPT_BOUND) {
                phaseMachine.parkOperational(
                        turn.taskId(), Actor.AGENT, "local_review_attempts_exhausted");
                return false;
            }
            log.info("local-review target {} {} (attempt {}); re-driving",
                    targetId, reason, owner.failures() + 1);
            return true;
        });
        if (retry) {
            taskStore.findTaskById(turn.taskId()).ifPresent(this::reconcileLocalTask);
        }
    }

    private static String localAddressTargetId(ThreadTurn turn)
    {
        return turn.input() == null ? null : turn.input().lines()
                .findFirst()
                .filter(line -> line.startsWith(LOCAL_COMMENT_TARGET_PREFIX))
                .map(line -> line.substring(LOCAL_COMMENT_TARGET_PREFIX.length()).strip())
                .filter(id -> !id.isEmpty())
                .orElse(null);
    }

    private boolean localAddressTargetClosed(ThreadTurn turn)
    {
        String targetId = localAddressTargetId(turn);
        if (targetId == null) {
            return false;
        }
        return prService.findByTask(turn.taskId())
                .map(pr -> prService.comments(pr.id()).stream()
                        .filter(comment -> targetId.equals(comment.id()))
                        .filter(comment -> PRComment.ORIGIN_LOCAL.equals(comment.origin()))
                        .filter(comment -> comment.parentCommentId() == null)
                        .anyMatch(comment -> comment.resolvedAt() != null || comment.dismissedAt() != null))
                .orElse(false);
    }

    /** Terminal states whose worktree should already be gone — if one still
     *  has a worktree on disk, an earlier reap failed (or raced) and we retry. */
    private static final List<TaskStatus> REAPABLE_TERMINAL =
            List.of(TaskStatus.CANCELED, TaskStatus.COMPLETED, TaskStatus.REMOTE_CLOSED);
    private static final int ORPHAN_SWEEP_LIMIT = 200;

    /**
     * Backstop for the close/merge reap: reap is best-effort and swallows
     * failures, so a worktree can be orphaned if its removal failed or raced a
     * still-live subprocess. This sweep finds terminal (canceled/completed)
     * tasks whose worktree directory is still present and reaps it again. A
     * task whose worktree is already gone is skipped — a single {@code stat}.
     */
    public void sweepOrphanedWorktrees()
    {
        for (TaskStatus status : REAPABLE_TERMINAL) {
            for (Task task : taskStore.listByStatus(status, ORPHAN_SWEEP_LIMIT)) {
                if (taskStore.isV2Task(task.id())) {
                    continue;
                }
                String worktreePath = task.worktreePath();
                if (worktreePath == null || worktreePath.isBlank()) {
                    continue;
                }
                if (!Files.isDirectory(Path.of(worktreePath))) {
                    continue;
                }
                log.info("Reaping orphaned worktree for {} task {}: {}",
                        status, task.id(), worktreePath);
                try {
                    worktrees.reap(task);
                }
                catch (RuntimeException e) {
                    log.warn("orphan worktree reap for task {} failed: {}",
                            task.id(), e.getMessage());
                }
            }
        }
    }

    /** Visible for the unit test: fetch the task's PR fresh and move its
     *  phase to match. */
    void reconcileTask(Task task)
    {
        if (taskStore.isV2Task(task.id())) {
            return;
        }
        Optional<PullRequestRef> parsed = PullRequestRef.parse(task.linkedPrRef());
        if (parsed.isEmpty()) {
            return;
        }
        String repo = parsed.get().repoRef().fullName();
        int number = parsed.get().number();
        PullRequestDetail detail = pullRequests.refreshPullRequestDetail(repo, number);
        // Persist the live CI status so the live-plan rail's CI validation
        // node reflects it — task.ciState otherwise never gets written and
        // stays permanently "unknown" even once the PR is actually green.
        if (detail.ciStatus() != null) {
            taskStore.updateCiState(task.id(), detail.ciStatus().name());
        }
        // React within this 60s sweep rather than waiting for BranchGuardJob's
        // own much slower scheduled tick: GitHub already reports this task's
        // own PR as conflicted with its base branch, so kick the same
        // rebase/agent-fix machinery now.
        if (Boolean.FALSE.equals(detail.mergeable()) && "dirty".equalsIgnoreCase(detail.mergeableState())) {
            events.publishEvent(new PullRequestDirtyDetectedEvent(task.id()));
        }
        // Mirror any new remote review comments into the unified
        // review_comment table (idempotent) before acting on the phase.
        String currentLogin = prService.findByTask(task.id())
                .map(PR::author)
                .filter(author -> !author.isBlank())
                .orElse(null);
        if (currentLogin == null) {
            currentLogin = pullRequests.resolveCurrentRepoLogin(repo);
        }
        commentIngestor.ingest(task.id(), repo, number, detail, currentLogin);
        reconcileObservedTask(task, repo, number, detail);
    }

    private void reconcileObservedTask(Task task, String repo, int number, PullRequestDetail detail)
    {
        if (detail.merged() || "closed".equalsIgnoreCase(detail.state())) {
            // Clear merge-gate state even when a parked task finishes remotely.
            readyToMerge.evaluate(task, detail);
            // The PR reached a terminal state on the remote (merged or
            // closed) without our in-app merge action — so
            // PullRequestMergedEvent never fired and the completion path in
            // TaskService never ran. observe() alone only advances the
            // phase axis, leaving the runtime status stuck (e.g. IN_REVIEW)
            // and the worktree on disk. Finish the job here.
            completeRemotelyTerminal(task, detail.merged());
            return;
        }
        task = taskStore.findTaskById(task.id()).orElse(null);
        if (isParkedOrTerminal(task)) {
            return;
        }
        // Fire / auto-reset the ready-to-merge notification off the same detail.
        // It may itself park the task after exhausting merge-queue retries.
        readyToMerge.evaluate(task, detail);
        task = taskStore.findTaskById(task.id()).orElse(null);
        if (isParkedOrTerminal(task)) {
            return;
        }
        if (detail.ciStatus() != PullRequestDetail.CiStatus.PASSING
                && detail.ciStatus() != PullRequestDetail.CiStatus.NONE) {
            return; // Red/pending/unknown CI records facts but never rewinds the phase.
        }
        if (task.phase() == TaskPhase.PUSHED_AWAITING_CI) {
            phaseMachine.observeRemoteCiGreen(
                    task.id(), detail.draft(), "remote_ci_green");
            task = taskStore.findTaskById(task.id()).orElse(task);
        }
        if (task.phase() == TaskPhase.AWAITING_READY && detail.draft()) {
            // Local Review already authorised publishing. Green CI is the
            // automatic draft -> ready checkpoint, not a second human gate.
            Task readyTask = task;
            TaskExternalEffectGate.withEffectGate(task.id(), () -> {
                Task current = taskStore.findTaskById(readyTask.id()).orElse(null);
                if (isParkedOrTerminal(current)
                        || current.phase() != TaskPhase.AWAITING_READY) {
                    return null;
                }
                notifications.supersedeAwaitingReviewForTask(
                        current.threadId(), current.id());
                pullRequests.setPullRequestDraft(repo, number, false);
                prService.findByTask(current.id())
                        .filter(pr -> PR.STATUS_REMOTE_DRAFTED.equals(pr.status()))
                        .ifPresent(pr -> prService.transition(
                                pr.id(), PR.STATUS_REMOTE_OPEN, PRTimelineEntry.ACTOR_AGENT));
                phaseMachine.observeReady(current.id(), "ci_green_marked_ready");
                return null;
            });
            task = taskStore.findTaskById(task.id()).orElse(task);
        }
        else if (task.phase() == TaskPhase.AWAITING_READY && !detail.draft()) {
            phaseMachine.observeReady(task.id(), "remote_ready_observed");
            task = taskStore.findTaskById(task.id()).orElse(task);
        }
        if (task.phase() == TaskPhase.AWAITING_REMOTE_REVIEW) {
            // The phase no longer moves for a new batch of reviewer comments
            // — a review_round AgentRun triages and addresses it beside this
            // phase. reviewRounds reads directly from the review_comment
            // rows commentIngestor just mirrored in, above.
            reviewRounds.reconcile(task);
        }
    }

    private static boolean isParkedOrTerminal(Task task)
    {
        if (task == null || task.phase() == TaskPhase.NEEDS_ATTENTION
                || task.phase() == TaskPhase.COMPLETED) {
            return true;
        }
        return switch (task.status()) {
            case PAUSED, NEEDS_ATTENTION, COMPLETED, REMOTE_CLOSED,
                    ERRORED, CANCELED, ARCHIVED -> true;
            default -> false;
        };
    }

    /** Visible for the unit test: drive submitted local comments through
     *  Development and back through Brain review. */
    void reconcileLocalTask(Task task)
    {
        if (task != null && taskStore.isV2Task(task.id())) {
            return;
        }
        if (isParkedOrTerminal(task)) {
            return; // A real human gate owns the task; never churn agent turns behind it.
        }
        Optional<PR> prOpt = prService.findByTask(task.id());
        if (prOpt.isEmpty()) {
            return;
        }
        PR pr = prOpt.get();
        if (!PR.STATUS_LOCAL_OPEN.equals(pr.status())) {
            return; // nothing to review yet, or already promoted/terminal.
        }
        if (task.phase() == TaskPhase.INTERNAL_REVIEW) {
            // Deliver any owed Brain handoff. The legacy marker call covers
            // pre-handoff in-flight tasks; both entries are idempotent.
            if (!deliverBrainHandoffs(task) && pr.localAddressedThroughAt() != null) {
                brainReview.reviewAfterLocalComments(pr.id());
            }
            return;
        }
        driveLocalReviewQueue(task.id(), pr.id());
    }

    /**
     * The deterministic admission driver for durable submitted-review
     * batches: reload under the task lock and always select the lowest
     * open {@code (submission_seq, root order)} target regardless of
     * which event woke it, admitting exactly one keyed one-root turn.
     * With every submitted root closed it instead claims the
     * roots-closed validation bound to the exact watermark + root set.
     */
    void driveLocalReviewQueue(String taskId, String prId)
    {
        if (taskStore.isV2Task(taskId)) {
            return;
        }
        Runnable rootsClosed = TaskPhaseMachine.withTaskLock(taskId, () -> {
            Task current = taskStore.findTaskById(taskId).orElse(null);
            if (isParkedOrTerminal(current)
                    || (current.phase() != TaskPhase.AWAITING_PUSH
                            && current.phase() != TaskPhase.ADDRESSING_LOCAL_COMMENTS)) {
                return null;
            }
            List<LocalReviewSubmission> open = submissions.listOpenByTask(taskId);
            if (open.isEmpty()) {
                return null;
            }
            PR pr = prService.findById(prId).orElse(null);
            if (pr == null || !PR.STATUS_LOCAL_OPEN.equals(pr.status())) {
                return null;
            }
            List<PRComment> comments = prService.comments(prId);
            Thread thread = threadStore.findThreadById(current.threadId()).orElse(null);
            if (thread == null) {
                log.warn("local-review queue: thread {} not found (task {})",
                        current.threadId(), taskId);
                return null;
            }
            if (thread.status() != ThreadStatus.IDLE || hasPendingLocalAddressTurn(current)) {
                return null; // busy — retried when the live work completes
            }
            NextTarget next = nextOpenTarget(open, comments);
            if (next == null) {
                if (current.phase() != TaskPhase.ADDRESSING_LOCAL_COMMENTS) {
                    return null; // nothing owed from the push gate
                }
                // Claim outside the lock: CI never runs inside a command.
                long throughSeq = open.getLast().submissionSeq();
                String digest = rootSetDigest(open);
                return (Runnable) () ->
                        claimedValidation.claimAndRunLocalReview(taskId, throughSeq, digest);
            }
            if (current.phase() != TaskPhase.ADDRESSING_LOCAL_COMMENTS) {
                try {
                    phaseMachine.transition(
                            taskId, TaskPhase.ADDRESSING_LOCAL_COMMENTS,
                            "new_local_comments", Actor.AGENT);
                }
                catch (ResponseStatusException e) {
                    if (e.getStatusCode().value() == 409) {
                        return null; // Push won the race; do not mark or enqueue.
                    }
                    throw e;
                }
            }
            String stageId = stageStore.findLiveStageByType(taskId, StageType.DEVELOPMENT_STAGE)
                    .map(s -> s.id().toString())
                    .orElseThrow(() -> new IllegalStateException(
                            "task " + taskId + " has no live DevelopmentStage"));
            String prompt = buildLocalAddressingPrompt(pr, next.root(), comments);
            // Attempt rides the durable failure counter, so a retry never
            // reuses a terminal turn's kick key.
            String kickKey = "local-review:" + next.submission().id() + ":" + next.root().id()
                    + ":" + next.submission().failures();
            try {
                scheduler.enqueueStageTurnOnce(
                        kickKey, thread, prompt, taskId, stageId,
                        TurnInitiator.unattended(SOURCE_ADDRESS_LOCAL_COMMENTS), null,
                        TurnLiveness.CODE);
                if (next.submission().activatedAt() == null) {
                    submissions.bindRun(next.submission().id(), null, Instant.now());
                }
                prService.markLocalAddressed(prId, newestSubmittedAt(open));
            }
            catch (RuntimeException e) {
                log.warn("local-review admission failed for task {}: {}", taskId, e.getMessage());
            }
            return null;
        });
        if (rootsClosed != null) {
            rootsClosed.run();
        }
    }

    /** The lowest open (submission_seq, root order) target, or null when
     *  every submitted root of every open batch is closed. */
    private NextTarget nextOpenTarget(List<LocalReviewSubmission> open, List<PRComment> comments)
    {
        Map<String, PRComment> byId = new LinkedHashMap<>();
        for (PRComment comment : comments) {
            byId.put(comment.id(), comment);
        }
        for (LocalReviewSubmission submission : open) {
            for (String rootId : rootIds(submission)) {
                PRComment root = byId.get(rootId);
                if (root != null && isOpenSubmittedRoot(root)) {
                    return new NextTarget(submission, root);
                }
            }
        }
        return null;
    }

    private List<String> rootIds(LocalReviewSubmission submission)
    {
        try {
            return mapper.readValue(
                    submission.rootIdsJson(),
                    mapper.getTypeFactory().constructCollectionType(List.class, String.class));
        }
        catch (Exception e) {
            log.warn("submission {} has unreadable root ids: {}", submission.id(), e.getMessage());
            return List.of();
        }
    }

    /** Digest of the exact covered root set, order-independent. */
    private String rootSetDigest(List<LocalReviewSubmission> open)
    {
        List<String> ids = new ArrayList<>();
        for (LocalReviewSubmission submission : open) {
            ids.addAll(rootIds(submission));
        }
        ids.sort(String::compareTo);
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(
                    digest.digest(String.join("\n", ids).getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static Instant newestSubmittedAt(List<LocalReviewSubmission> open)
    {
        return open.stream()
                .map(LocalReviewSubmission::submittedThroughAt)
                .max(Instant::compareTo)
                .orElseThrow();
    }

    /**
     * The roots-closed validation finished. Accept it only while the
     * exact submission watermark + root set are still current and every
     * covered root is still closed: green stamps the covered batches
     * completed, advances to INTERNAL_REVIEW, and commits the owed
     * Brain handoff marker in the same command; red parks.
     */
    public void onLocalReviewValidationFinished(LocalReviewValidationFinishedEvent event)
    {
        try {
            acceptLocalReviewValidation(event);
        }
        catch (RuntimeException e) {
            log.warn("local-review validation acceptance for task {} failed: {}",
                    event.taskId(), e.getMessage());
        }
    }

    private void acceptLocalReviewValidation(LocalReviewValidationFinishedEvent event)
    {
        if (taskStore.isV2Task(event.taskId())) {
            return;
        }
        boolean deliver = TaskPhaseMachine.withTaskLock(event.taskId(), () -> {
            Task current = taskStore.findTaskById(event.taskId()).orElse(null);
            if (isParkedOrTerminal(current)
                    || current.phase() != TaskPhase.ADDRESSING_LOCAL_COMMENTS) {
                return false;
            }
            List<LocalReviewSubmission> open = submissions.listOpenByTask(event.taskId());
            if (open.isEmpty()
                    || open.getLast().submissionSeq() != event.throughSequence()
                    || !rootSetDigest(open).equals(event.rootSetDigest())) {
                return false; // superseded — a fresh claim owns the newer set
            }
            PR pr = prService.findByTask(event.taskId()).orElse(null);
            if (pr == null
                    || nextOpenTarget(open, prService.comments(pr.id())) != null) {
                return false; // a covered root reopened — the queue re-drives
            }
            if (!event.passed()) {
                phaseMachine.parkOperational(
                        event.taskId(), Actor.AGENT, "local_comments_validation_failed");
                return false;
            }
            Instant now = Instant.now();
            for (LocalReviewSubmission submission : open) {
                submissions.markCompleted(submission.id(), now);
            }
            phaseMachine.transition(
                    event.taskId(), TaskPhase.INTERNAL_REVIEW,
                    "local_comments_validated", Actor.AGENT);
            handoffs.insert(event.claimKey(), event.taskId(), event.throughSequence(),
                    event.codeFingerprint(), now);
            return true;
        });
        if (deliver) {
            taskStore.findTaskById(event.taskId()).ifPresent(this::deliverBrainHandoffs);
        }
    }

    /**
     * P5a's compatibility adapter: the durable handoff marker stays until
     * the existing, idempotent Brain entry succeeds. A failed delivery
     * increments its bounded counter and parks at the bound.
     */
    private boolean deliverBrainHandoffs(Task task)
    {
        List<LocalReviewBrainHandoffStore.Handoff> owed = handoffs.listUnconsumedByTask(task.id());
        if (owed.isEmpty()) {
            return false;
        }
        Optional<PR> pr = prService.findByTask(task.id());
        if (pr.isEmpty()) {
            return true;
        }
        for (LocalReviewBrainHandoffStore.Handoff handoff : owed) {
            try {
                brainReview.reviewAfterLocalComments(pr.get().id());
                handoffs.markConsumed(handoff.validationClaimKey(), Instant.now());
            }
            catch (RuntimeException e) {
                handoffs.incrementDeliveryFailures(handoff.validationClaimKey());
                log.warn("brain handoff delivery for task {} failed: {}",
                        task.id(), e.getMessage());
                if (handoff.deliveryFailures() + 1 >= HANDOFF_DELIVERY_BOUND) {
                    phaseMachine.parkOperational(task.id(), Actor.AGENT, "brain_handoff_failed");
                }
            }
        }
        return true;
    }

    private record NextTarget(LocalReviewSubmission submission, PRComment root) {}

    private static boolean isOpenSubmittedRoot(PRComment comment)
    {
        return PRComment.ORIGIN_LOCAL.equals(comment.origin())
                && (PRTimelineEntry.ACTOR_USER.equals(comment.author())
                        || "agent".equals(comment.author()) && comment.findingId() != null)
                && comment.parentCommentId() == null
                && comment.resolvedAt() == null
                && comment.dismissedAt() == null;
    }

    private boolean hasPendingLocalAddressTurn(Task task)
    {
        for (ThreadTurnStatus status : List.of(ThreadTurnStatus.QUEUED, ThreadTurnStatus.RUNNING)) {
            boolean found = turnStore.listTurnsByTaskIdAndStatus(task.threadId(), status, TURN_SCAN_LIMIT).stream()
                    .anyMatch(turn -> task.id().equals(turn.taskId())
                            && turn.initiator() != null
                            && SOURCE_ADDRESS_LOCAL_COMMENTS.equals(turn.initiator().source()));
            if (found) {
                return true;
            }
        }
        return false;
    }

    /** The local-addressing turn's prompt: the next still-open comment, and a
     *  direct instruction to fix/reply/dismiss it now — the local twin
     *  of {@link #buildReviewAnalysisPrompt}, minus the "stop and wait" step
     *  (decision: local addressing is autonomous; the unpushed branch is the
     *  safety net, not a human gate). */
    private static String buildLocalAddressingPrompt(
            PR pr, PRComment comment, List<PRComment> threadComments)
    {
        StringBuilder out = new StringBuilder(LOCAL_COMMENT_TARGET_PREFIX)
                .append(comment.id()).append("\n\n");
        out.append("New comments arrived on your local PR \"").append(pr.title()).append("\". ")
                .append("Unlike remote review comments, address these directly now — the branch "
                        + "hasn't been pushed yet, so there's nothing to gate on.\n\n")
                .append("Address exactly this one open comment. Fully finish and resolve it before "
                        + "the lifecycle sends the next comment.\n")
                .append("  1. Inspect its stored anchor, the relevant current code, and the current diff.\n")
                .append("  2. For a code concern: make the fix, run a focused check for that concern, "
                        + "then inspect the current diff again to verify the concern is addressed. "
                        + "Commit it, call record_pr_commit, then call "
                        + "resolve_pr_comment(comment_id, resolution='addressed', "
                        + "reply='<concise description of the verified fix>').\n")
                .append("  3. For a question or concern needing no code change: call "
                        + "resolve_pr_comment(comment_id, resolution='addressed', "
                        + "reply='<concise answer>'). Do not call record_pr_comment first; "
                        + "resolve_pr_comment posts the reply.\n")
                .append("  4. If you disagree and no action is needed: reply explaining why via "
                        + "record_pr_comment, then resolve_pr_comment(comment_id, "
                        + "resolution='dismissed').\n\n")
                .append("After resolving it, do not push; finish this turn. The lifecycle will send "
                        + "the next open comment, or, once all are closed, run final whole-change "
                        + "validation and start a fresh Brain review.\n\n")
                .append("Open comment:\n\n")
                .append("[id: ").append(comment.id()).append("]\n")
                .append("Anchor: ");
        if (comment.filePath() != null) {
            out.append(comment.filePath());
            if (comment.lineNumber() != null) {
                out.append("; line=").append(comment.lineNumber());
            }
            if (comment.side() != null) {
                out.append("; side=").append(comment.side());
            }
            if (comment.startLine() != null) {
                out.append("; start_line=").append(comment.startLine());
            }
            if (comment.startSide() != null) {
                out.append("; start_side=").append(comment.startSide());
            }
        }
        else {
            out.append("PR-level");
        }
        out.append('\n')
                .append('@').append(comment.author()).append(": ")
                .append(comment.body() == null ? "" : comment.body().strip())
                .append('\n');
        for (PRComment reply : threadComments) {
            if (comment.id().equals(reply.parentCommentId())) {
                out.append("Reply @").append(reply.author()).append(": ")
                        .append(reply.body() == null ? "" : reply.body().strip())
                        .append('\n');
            }
        }
        return out.toString();
    }

    /** Drive a task to a terminal state from an observed remote merge /
     *  close. A merge lands at COMPLETED; a close-without-merge lands at the
     *  distinct REMOTE_CLOSED. Either way it is terminal, so we tear the
     *  task's resources down the same way: interrupt + evict the running
     *  Task agent, seal the stages, then reap the worktree + branch.
     *  A closed PR cleans up just like a merged one — the work didn't land,
     *  so the branch is dead weight. */
    private void completeRemotelyTerminal(Task task, boolean merged)
    {
        // Durable terminal intent first: status + phase + audit commit in
        // one locked step, and only then does runtime teardown run — each
        // step idempotent, retried by the orphan sweep. The effect gate
        // serializes this against pause/submission commands on the task.
        if (!isTerminal(task.status())) {
            TaskExternalEffectGate.withEffectGate(task.id(), () -> {
                phaseMachine.finishTerminal(
                        task.id(),
                        merged ? TaskStatus.COMPLETED : TaskStatus.REMOTE_CLOSED,
                        Actor.WEBHOOK,
                        merged ? "pr_merged_observed" : "pr_closed_observed");
                return null;
            });
        }
        // Record the terminal PR state so the task/stage surfaces stop showing
        // a merged/closed PR as "open" once the remote PR resolves (incl. via
        // the merge queue, where no in-app merge action ran).
        if (task.prNumber() != null) {
            taskStore.linkPullRequest(task.id(), task.prNumber(), merged ? "merged" : "closed");
        }
        // Interrupt + evict the still-running Task agent BEFORE the reap,
        // so a live subprocess isn't yanked out from under a worktree it's
        // mid-tool-call inside. Best-effort: the orphan sweep retries the reap.
        evictTaskRuntime(task);
        // finishTerminal already sealed every durable child in the terminal
        // transaction; only external runtime cleanup remains here.
        // Clear the task's still-open publish gates and "needs you" prompts
        // (e.g. a budget-cap pause) — this reconciler path runs when the merge
        // event never fired, so unlike TaskService.completeTasksForMergedPr it
        // otherwise leaves a stale card lingering in the overview panel.
        try {
            notifications.dismissOpenForTask(task.threadId(), task.id());
        }
        catch (RuntimeException e) {
            log.warn("notification cleanup for terminal task {} failed: {}",
                    task.id(), e.getMessage());
        }
        worktrees.reap(task);
        // A merged PR's head branch is dead weight — delete the remote copy
        // too (mirrors GitHub's auto-delete-head-branch). Skip on a plain
        // close: the PR may be reopened, and closing already leaves the branch.
        if (merged) {
            worktrees.deleteRemoteBranch(task);
        }
    }

    private static boolean isTerminal(TaskStatus status)
    {
        return status == TaskStatus.COMPLETED
                || status == TaskStatus.REMOTE_CLOSED
                || status == TaskStatus.CANCELED;
    }

    /** Interrupt + evict every runtime owned by the Task. */
    private void evictTaskRuntime(Task task)
    {
        registry.findTaskAgents(List.of(task.id())).forEach(Agent::interrupt);
        registry.evictTaskAgent(task.threadId(), task.id());
    }
}
