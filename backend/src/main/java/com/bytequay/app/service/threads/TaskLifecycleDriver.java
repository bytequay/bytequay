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
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRComment;
import com.bytequay.app.domain.PRTimelineEntry;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.StageInstance;
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
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.ThreadTurnStore;
import com.bytequay.app.service.checks.ValidationPassResult;
import com.bytequay.app.service.checks.ValidationPassService;
import com.bytequay.app.service.localpr.LocalReviewSubmittedEvent;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.review.BrainReviewService;
import com.bytequay.app.service.review.ReviewRoundService;
import com.bytequay.app.service.stage.ReadyToMergeService;
import com.bytequay.app.service.stage.RemoteCommentIngestor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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
@Component
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
    private final ReviewRoundService reviewRounds;
    private final ThreadRegistry registry;
    private final StageStore stageStore;
    private final PRService prService;
    private final TaskTerminalSealer sealer;
    private final ValidationPassService validation;
    private final BrainReviewService brainReview;
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
            ReviewRoundService reviewRounds,
            ThreadRegistry registry,
            StageStore stageStore,
            PRService prService,
            TaskTerminalSealer sealer,
            ValidationPassService validation,
            BrainReviewService brainReview,
            ApplicationEventPublisher events)
    {
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
        this.sealer = requireNonNull(sealer, "sealer is null");
        this.validation = requireNonNull(validation, "validation is null");
        this.brainReview = requireNonNull(brainReview, "brainReview is null");
        this.events = requireNonNull(events, "events is null");
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 90_000)
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
    @Scheduled(fixedDelay = 60_000, initialDelay = 100_000)
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
    @EventListener
    public void onLocalReviewSubmitted(LocalReviewSubmittedEvent event)
    {
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
    @TransactionalEventListener(
            phase = TransactionPhase.AFTER_COMMIT,
            fallbackExecution = true)
    public void onInternalReviewCompleted(TaskPhaseTransitionedEvent event)
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

    /** Fast path after a successful local-addressing turn. Reconcile only
     *  turns durably identified as ours; failed turns wait for the scheduled
     *  recovery sweep so a persistent failure cannot spin an immediate loop. */
    @EventListener
    public void onLocalAddressTurnFinished(TaskTurnFinishedEvent event)
    {
        if (event.failed()) {
            return;
        }
        turnStore.findTurnById(event.turnId())
                .filter(turn -> turn.status() == ThreadTurnStatus.COMPLETED)
                .filter(turn -> event.taskId().equals(turn.taskId()))
                .filter(turn -> turn.initiator() != null
                        && SOURCE_ADDRESS_LOCAL_COMMENTS.equals(turn.initiator().source()))
                .filter(this::localAddressTargetClosed)
                .flatMap(turn -> taskStore.findTaskById(event.taskId()))
                .ifPresent(task -> {
                    try {
                        reconcileLocalTask(task);
                    }
                    catch (RuntimeException e) {
                        log.warn("post-turn local-review reconcile for task {} failed: {}",
                                task.id(), e.getMessage());
                    }
                });
    }

    private boolean localAddressTargetClosed(ThreadTurn turn)
    {
        String targetId = turn.input() == null ? null : turn.input().lines()
                .findFirst()
                .filter(line -> line.startsWith(LOCAL_COMMENT_TARGET_PREFIX))
                .map(line -> line.substring(LOCAL_COMMENT_TARGET_PREFIX.length()).strip())
                .filter(id -> !id.isEmpty())
                .orElse(null);
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
    @Scheduled(fixedDelay = 600_000, initialDelay = 120_000)
    public void sweepOrphanedWorktrees()
    {
        for (TaskStatus status : REAPABLE_TERMINAL) {
            for (Task task : taskStore.listByStatus(status, ORPHAN_SWEEP_LIMIT)) {
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
        TaskPhaseMachine.withTaskLock(task.id(), () -> {
            reconcileObservedTask(task, repo, number, detail);
            return null;
        });
    }

    private void reconcileObservedTask(Task task, String repo, int number, PullRequestDetail detail)
    {
        Optional<TaskPhase> target = TaskLifecyclePhases.observedPhaseFromDetail(detail);
        if (target.isEmpty()) {
            return;
        }
        TaskPhase phase = target.get();
        if (phase == TaskPhase.COMPLETED) {
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
        if (phase == TaskPhase.AWAITING_READY && detail.draft()) {
            // Local Review already authorised publishing. Green CI is the
            // automatic draft -> ready checkpoint, not a second human gate.
            notifications.supersedeAwaitingReviewForTask(task.threadId(), task.id());
            if (task.status() == TaskStatus.AWAITING_REVIEW) {
                taskStore.saveTask(task.withStatus(TaskStatus.IN_REVIEW));
            }
            pullRequests.setPullRequestDraft(repo, number, false);
            prService.findByTask(task.id())
                    .filter(pr -> PR.STATUS_REMOTE_DRAFTED.equals(pr.status()))
                    .ifPresent(pr -> prService.transition(
                            pr.id(), PR.STATUS_REMOTE_OPEN, PRTimelineEntry.ACTOR_AGENT));
            phaseMachine.observe(task.id(), TaskPhase.AWAITING_REMOTE_REVIEW, "ci_green_marked_ready");
            return;
        }
        phaseMachine.observe(task.id(), phase, "pr_state_observed");
        if (phase == TaskPhase.AWAITING_REMOTE_REVIEW) {
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
            // INTERNAL_REVIEW is also used by the initial Brain pass. Only a
            // local-review marker proves this is the post-addressing pass.
            if (pr.localAddressedThroughAt() != null) {
                brainReview.reviewAfterLocalComments(pr.id());
            }
            return;
        }
        List<PRComment> comments = prService.comments(pr.id());
        List<PRService.LocalReviewSubmission> submissions = prService.localReviewSubmissions(pr.id());
        Set<String> submittedIds = submissions.stream()
                .flatMap(submission -> submission.commentIds().stream())
                .collect(Collectors.toCollection(LinkedHashSet::new));
        List<PRComment> submittedOpen = comments.stream()
                .filter(TaskLifecycleDriver::isOpenSubmittedRoot)
                .filter(comment -> submittedIds.contains(comment.id())
                        || isLegacySubmitted(task, pr, submissions, comment))
                .toList();
        if (task.phase() == TaskPhase.ADDRESSING_LOCAL_COMMENTS) {
            if (!submittedOpen.isEmpty()) {
                // Still (or newly) has open threads — keep the loop going once
                // the current turn (if any) goes idle.
                startAddressLocalComments(
                        task, pr, submittedOpen, comments,
                        newestSubmissionAt(pr, submissions, submittedOpen));
            }
            else {
                boolean startBrain = TaskPhaseMachine.withTaskLock(task.id(), () -> {
                    Task current = taskStore.findTaskById(task.id()).orElse(null);
                    if (isParkedOrTerminal(current)
                            || current.phase() != TaskPhase.ADDRESSING_LOCAL_COMMENTS) {
                        return false;
                    }
                    Thread thread = threadStore.findThreadById(current.threadId()).orElse(null);
                    if (thread == null || thread.status() != ThreadStatus.IDLE
                            || hasPendingLocalAddressTurn(current)) {
                        return false;
                    }
                    ValidationPassResult result = validation.run(current.id());
                    if (!result.passed()) {
                        phaseMachine.transition(
                                current.id(), TaskPhase.NEEDS_ATTENTION,
                                "local_comments_validation_failed", Actor.AGENT);
                        return false;
                    }
                    phaseMachine.transition(
                            current.id(), TaskPhase.INTERNAL_REVIEW,
                            "local_comments_validated", Actor.AGENT);
                    return true;
                });
                if (startBrain) {
                    brainReview.reviewAfterLocalComments(pr.id());
                }
            }
            return;
        }
        // task.phase() == AWAITING_PUSH: only an explicitly submitted review
        // event past the marker dispatches Development. Open draft comments
        // remain pending until the user submits them.
        Instant marker = pr.localAddressedThroughAt();
        List<PRService.LocalReviewSubmission> fresh = submissions.stream()
                .filter(submission -> marker == null || submission.submittedAt().isAfter(marker))
                .toList();
        Set<String> freshIds = fresh.stream()
                .flatMap(submission -> submission.commentIds().stream())
                .collect(Collectors.toSet());
        List<PRComment> freshOpen = submittedOpen.stream()
                .filter(comment -> freshIds.contains(comment.id()))
                .toList();
        if (!freshOpen.isEmpty()) {
            Instant newest = fresh.stream()
                    .map(PRService.LocalReviewSubmission::submittedAt)
                    .max(Instant::compareTo)
                    .orElseThrow();
            startAddressLocalComments(task, pr, submittedOpen, comments, newest);
        }
    }

    private static boolean isOpenSubmittedRoot(PRComment comment)
    {
        return PRComment.ORIGIN_LOCAL.equals(comment.origin())
                && (PRTimelineEntry.ACTOR_USER.equals(comment.author())
                        || "agent".equals(comment.author()) && comment.findingId() != null)
                && comment.parentCommentId() == null
                && comment.resolvedAt() == null
                && comment.dismissedAt() == null;
    }

    /** Keep an already-running pre-submission-model batch alive across an
     *  upgrade without turning newer drafts into submitted comments. */
    private static boolean isLegacySubmitted(
            Task task, PR pr, List<PRService.LocalReviewSubmission> submissions, PRComment comment)
    {
        if (task.phase() != TaskPhase.ADDRESSING_LOCAL_COMMENTS || !submissions.isEmpty()) {
            return false;
        }
        Instant marker = pr.localAddressedThroughAt();
        return marker == null || !comment.createdAt().isAfter(marker);
    }

    private static Instant newestSubmissionAt(
            PR pr, List<PRService.LocalReviewSubmission> submissions, List<PRComment> comments)
    {
        return submissions.stream()
                .map(PRService.LocalReviewSubmission::submittedAt)
                .max(Instant::compareTo)
                .orElseGet(() -> Optional.ofNullable(pr.localAddressedThroughAt())
                        .orElseGet(() -> comments.stream()
                                .map(PRComment::createdAt)
                                .max(Instant::compareTo)
                                .orElseThrow()));
    }

    /**
     * Move the task onto (or keep it on) the local-addressing phase and, if
     * its thread is idle, enqueue a turn that fixes/replies to every open
     * local PR comment directly — no analysis-then-wait step like the remote
     * loop: the branch hasn't been pushed yet, so there's nothing to gate.
     */
    private void startAddressLocalComments(
            Task task, PR pr, List<PRComment> roots, List<PRComment> threadComments, Instant submittedAt)
    {
        TaskPhaseMachine.withTaskLock(task.id(), () -> {
            // Re-read under the same per-task lock Local Review promotion
            // holds through its remote side effects. Exactly one path wins:
            // either this strict edge blocks Push, or Push completes and this
            // stale sweep leaves the now-remote task alone.
            Task current = taskStore.findTaskById(task.id()).orElse(null);
            if (isParkedOrTerminal(current)) {
                return null;
            }
            if (current.phase() != TaskPhase.ADDRESSING_LOCAL_COMMENTS) {
                if (current.phase() != TaskPhase.AWAITING_PUSH) {
                    return null;
                }
                try {
                    phaseMachine.transition(
                            task.id(), TaskPhase.ADDRESSING_LOCAL_COMMENTS,
                            "new_local_comments", Actor.AGENT);
                }
                catch (ResponseStatusException e) {
                    if (e.getStatusCode().value() == 409) {
                        return null; // Push won the race; do not mark or enqueue.
                    }
                    throw e;
                }
            }
            Optional<Thread> threadOpt = threadStore.findThreadById(current.threadId());
            if (threadOpt.isEmpty()) {
                log.warn("address-local-comments: thread {} not found (task {})",
                        current.threadId(), current.id());
                return null;
            }
            Thread thread = threadOpt.get();
            if (thread.status() != ThreadStatus.IDLE || hasPendingLocalAddressTurn(current)) {
                // Busy or durably queued — retried after that turn completes;
                // never stack a duplicate while scheduler capacity is pending.
                log.info("address-local-comments: task {} already has active work; retrying next sweep",
                        current.id());
                return null;
            }
            String prompt = buildLocalAddressingPrompt(pr, roots.get(0), threadComments);
            try {
                String stageId = stageStore.findLiveStageByType(current.id(), StageType.DEVELOPMENT_STAGE)
                        .map(s -> s.id().toString())
                        .orElse(null);
                scheduler.enqueueTaskTurn(
                        thread, prompt, current.id(), stageId,
                        TurnInitiator.unattended(SOURCE_ADDRESS_LOCAL_COMMENTS), null, TurnLiveness.CODE);
                // This marker is the revision actually carried by the queued
                // turn. A newer resubmission that arrives while the turn is
                // busy stays beyond it and cannot be closed by stale work.
                prService.markLocalAddressed(pr.id(), submittedAt);
                log.info("address-local-comments: turn queued for task {}", current.id());
            }
            catch (RuntimeException e) {
                log.warn("address-local-comments enqueue failed for task {}: {}", current.id(), e.getMessage());
            }
            return null;
        });
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
     *  task's resources down the same way: interrupt + evict any running
     *  per-stage agents, seal the stages, then reap the worktree + branch.
     *  A closed PR cleans up just like a merged one — the work didn't land,
     *  so the branch is dead weight. */
    private void completeRemotelyTerminal(Task task, boolean merged)
    {
        // Durable terminal intent first: status + phase + audit commit in
        // one locked step, and only then does runtime teardown run — each
        // step idempotent, retried by the orphan sweep.
        if (!isTerminal(task.status())) {
            phaseMachine.finishTerminal(
                    task.id(),
                    merged ? TaskStatus.COMPLETED : TaskStatus.REMOTE_CLOSED,
                    Actor.WEBHOOK,
                    merged ? "pr_merged_observed" : "pr_closed_observed");
        }
        // Record the terminal PR state so the task/stage surfaces stop showing
        // a merged/closed PR as "open" once the remote PR resolves (incl. via
        // the merge queue, where no in-app merge action ran).
        if (task.prNumber() != null) {
            taskStore.linkPullRequest(task.id(), task.prNumber(), merged ? "merged" : "closed");
        }
        // Interrupt + evict any still-running per-stage agents BEFORE the reap,
        // so a live subprocess isn't yanked out from under a worktree it's
        // mid-tool-call inside. Best-effort: the orphan sweep retries the reap.
        evictRunningStages(task);
        // Seal any still-open review round or stage so neither keeps
        // rendering as live once the task itself is terminal.
        sealer.seal(task.id(), merged ? "pr_merged" : "pr_closed");
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

    /** Interrupt + evict every per-stage agent of a task (its stage ids plus
     *  the task id itself, the key for a task-level agent that ran with no
     *  stage), so no subprocess survives the task's terminal teardown. */
    private void evictRunningStages(Task task)
    {
        List<String> stageKeys = new ArrayList<>();
        for (StageInstance stage : stageStore.findStagesByTask(task.id())) {
            stageKeys.add(stage.id().toString());
        }
        stageKeys.add(task.id());
        registry.findStages(stageKeys).forEach(ThreadAgent::interrupt);
        registry.evictStages(task.threadId(), stageKeys);
    }
}
