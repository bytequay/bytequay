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
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.review.ReviewRoundService;
import com.bytequay.app.service.stage.ReadyToMergeService;
import com.bytequay.app.service.stage.RemoteCommentIngestor;
import com.bytequay.app.service.tools.ParkedProposal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
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
@Component
public class TaskLifecycleDriver
{
    private static final Logger log = LoggerFactory.getLogger(TaskLifecycleDriver.class);

    /** Cap on tasks scanned per sweep. The scan is already narrowed to the
     *  remote spine in SQL, so this bounds only in-flight tasks — a set
     *  that's tiny in practice and never approaches this ceiling. */
    private static final int SCAN_LIMIT = 200;

    /** Phases that are waiting on the PR's remote state, so the linked PR
     *  is worth polling. A task outside these isn't waiting on CI/review,
     *  so we don't fetch its PR. */
    static final Set<TaskPhase> REMOTE_SPINE = EnumSet.of(
            TaskPhase.PUSHED_AWAITING_CI,
            TaskPhase.AWAITING_READY,
            TaskPhase.AWAITING_REMOTE_REVIEW);

    /** Phases the local-comments loop watches: the wait state itself, plus
     *  the addressing state (so a finished or still-in-progress round is
     *  re-checked every sweep). */
    static final Set<TaskPhase> LOCAL_SPINE = EnumSet.of(
            TaskPhase.AWAITING_PUSH,
            TaskPhase.ADDRESSING_LOCAL_COMMENTS);

    private final TaskStore taskStore;
    private final PullRequestService pullRequests;
    private final TaskPhaseMachine phaseMachine;
    private final WorktreeService worktrees;
    private final ThreadStore threadStore;
    private final ThreadTurnScheduler scheduler;
    private final NotificationService notifications;
    private final ObjectMapper mapper;
    private final RemoteCommentIngestor commentIngestor;
    private final ReadyToMergeService readyToMerge;
    private final ReviewRoundService reviewRounds;
    private final ThreadRegistry registry;
    private final StageStore stageStore;
    private final PRService prService;
    private final TaskTerminalSealer sealer;

    public TaskLifecycleDriver(
            TaskStore taskStore,
            PullRequestService pullRequests,
            TaskPhaseMachine phaseMachine,
            WorktreeService worktrees,
            ThreadStore threadStore,
            ThreadTurnScheduler scheduler,
            NotificationService notifications,
            ObjectMapper mapper,
            RemoteCommentIngestor commentIngestor,
            ReadyToMergeService readyToMerge,
            ReviewRoundService reviewRounds,
            ThreadRegistry registry,
            StageStore stageStore,
            PRService prService,
            TaskTerminalSealer sealer)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.phaseMachine = requireNonNull(phaseMachine, "phaseMachine is null");
        this.worktrees = requireNonNull(worktrees, "worktrees is null");
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.scheduler = requireNonNull(scheduler, "scheduler is null");
        this.notifications = requireNonNull(notifications, "notifications is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.commentIngestor = requireNonNull(commentIngestor, "commentIngestor is null");
        this.readyToMerge = requireNonNull(readyToMerge, "readyToMerge is null");
        this.reviewRounds = requireNonNull(reviewRounds, "reviewRounds is null");
        this.registry = requireNonNull(registry, "registry is null");
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.prService = requireNonNull(prService, "prService is null");
        this.sealer = requireNonNull(sealer, "sealer is null");
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

    /** Local twin of {@link #reconcile()}: watches a task's local PR for new
     *  review comments while it holds at {@link TaskPhase#AWAITING_PUSH},
     *  addresses them autonomously (no gate — the unpushed branch is the
     *  safety buffer), and returns it to the wait state. A local-only
     *  concern, so — unlike the remote spine — this never touches the PR /
     *  GitHub at all. */
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
        PullRequestDetail detail = pullRequests.getPullRequestDetail(repo, number);
        // Persist the live CI status so the live-plan rail's CI validation
        // node reflects it — task.ciState otherwise never gets written and
        // stays permanently "unknown" even once the PR is actually green.
        if (detail.ciStatus() != null) {
            taskStore.updateCiState(task.id(), detail.ciStatus().name());
        }
        // Mirror any new remote review comments into the unified
        // review_comment table (idempotent) before acting on the phase.
        commentIngestor.ingest(task.id(), repo, number, detail);
        // Fire / auto-reset the ready-to-merge notification off the same detail.
        readyToMerge.evaluate(task, detail);
        Optional<TaskPhase> target = TaskLifecyclePhases.observedPhaseFromDetail(detail);
        if (target.isEmpty()) {
            return;
        }
        TaskPhase phase = target.get();
        if (phase == TaskPhase.COMPLETED) {
            // The PR reached a terminal state on the remote (merged or
            // closed) without our in-app merge action — so
            // PullRequestMergedEvent never fired and the completion path in
            // TaskService never ran. observe() alone only advances the
            // phase axis, leaving the runtime status stuck (e.g. IN_REVIEW)
            // and the worktree on disk. Finish the job here.
            completeRemotelyTerminal(task, detail.merged());
            return;
        }
        if (phase == TaskPhase.AWAITING_READY && detail.draft()) {
            // CI is green on a still-draft shipped PR. Rather than un-drafting
            // autonomously, offer a ONE-TIME "mark ready for review (+ request
            // reviewers)" approval gate — the user decides when it goes out.
            // The sentinel guarantees it's offered once even if dismissed; an
            // already-ready PR never reaches here (guarded on detail.draft()).
            phaseMachine.observe(task.id(), TaskPhase.AWAITING_READY, "ci_green_on_draft");
            if (taskStore.markReadyGateSentIfUnset(task.id(), Instant.now())) {
                parkMarkReadyGate(task, repo, number);
            }
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

    /** Visible for the unit test: check one task's local PR for unaddressed
     *  comments and drive the {@code AWAITING_PUSH ⇄ ADDRESSING_LOCAL_COMMENTS}
     *  loop. */
    void reconcileLocalTask(Task task)
    {
        Optional<PR> prOpt = prService.findByTask(task.id());
        if (prOpt.isEmpty()) {
            return;
        }
        PR pr = prOpt.get();
        if (!PR.STATUS_LOCAL_OPEN.equals(pr.status())) {
            return; // nothing to review yet, or already promoted/terminal.
        }
        List<PRComment> comments = prService.comments(pr.id());
        boolean anyUnaddressed = comments.stream().anyMatch(TaskLifecycleDriver::isUnaddressedLocalComment);
        if (task.phase() == TaskPhase.ADDRESSING_LOCAL_COMMENTS) {
            if (anyUnaddressed) {
                // Still (or newly) has open threads — keep the loop going once
                // the current turn (if any) goes idle.
                startAddressLocalComments(task, pr, comments);
            }
            else {
                phaseMachine.observe(task.id(), TaskPhase.AWAITING_PUSH, "local_comments_addressed");
            }
            return;
        }
        // task.phase() == AWAITING_PUSH: only a genuinely new comment (past
        // the marker) re-triggers the loop — an already-known open thread
        // means the last round's turn never got to it (thread was busy) and
        // is retried from the ADDRESSING_LOCAL_COMMENTS branch above instead,
        // not re-entered here every sweep.
        Instant marker = pr.localAddressedThroughAt();
        boolean hasNew = comments.stream()
                .filter(TaskLifecycleDriver::isUnaddressedLocalComment)
                .anyMatch(c -> marker == null || c.createdAt().isAfter(marker));
        if (hasNew) {
            startAddressLocalComments(task, pr, comments);
        }
    }

    /** A comment that still needs the agent's attention: not yet resolved or
     *  dismissed, and not the agent's own comment/reply. */
    private static boolean isUnaddressedLocalComment(PRComment comment)
    {
        return comment.resolvedAt() == null && comment.dismissedAt() == null
                && !PRTimelineEntry.ACTOR_AGENT.equals(comment.author());
    }

    /**
     * Move the task onto (or keep it on) the local-addressing phase and, if
     * its thread is idle, enqueue a turn that fixes/replies to every open
     * local PR comment directly — no analysis-then-wait step like the remote
     * loop: the branch hasn't been pushed yet, so there's nothing to gate.
     */
    private void startAddressLocalComments(Task task, PR pr, List<PRComment> comments)
    {
        Instant newest = comments.stream()
                .filter(TaskLifecycleDriver::isUnaddressedLocalComment)
                .map(PRComment::createdAt)
                .max(Instant::compareTo)
                .orElse(null);
        if (newest == null) {
            return; // resolved between the caller's check and here.
        }
        phaseMachine.observe(task.id(), TaskPhase.ADDRESSING_LOCAL_COMMENTS, "new_local_comments");
        // Advance the marker up front, same reasoning as the remote loop: a
        // later sweep shouldn't treat this same batch as newly arrived.
        prService.markLocalAddressed(pr.id(), newest);
        Optional<Thread> threadOpt = threadStore.findThreadById(task.threadId());
        if (threadOpt.isEmpty()) {
            log.warn("address-local-comments: thread {} not found (task {})", task.threadId(), task.id());
            return;
        }
        Thread thread = threadOpt.get();
        if (thread.status() != ThreadStatus.IDLE) {
            // Busy thread — retried next sweep from the ADDRESSING_LOCAL_COMMENTS
            // branch of reconcileLocalTask; don't stack a turn on a running one.
            log.info("address-local-comments: thread {} is {} (task {}); retrying next sweep",
                    thread.id(), thread.status(), task.id());
            return;
        }
        String prompt = buildLocalAddressingPrompt(pr, comments);
        try {
            String stageId = stageStore.findLiveStageByType(task.id(), StageType.DEVELOPMENT_STAGE)
                    .map(s -> s.id().toString())
                    .orElse(null);
            scheduler.enqueueTaskTurn(
                    thread, prompt, task.id(), stageId,
                    TurnInitiator.unattended("address-local-comments"));
            log.info("address-local-comments: turn queued for task {}", task.id());
        }
        catch (RuntimeException e) {
            log.warn("address-local-comments enqueue failed for task {}: {}", task.id(), e.getMessage());
        }
    }

    /** The local-addressing turn's prompt: every still-open comment, and a
     *  direct instruction to fix/reply/dismiss each one now — the local twin
     *  of {@link #buildReviewAnalysisPrompt}, minus the "stop and wait" step
     *  (decision: local addressing is autonomous; the unpushed branch is the
     *  safety net, not a human gate). */
    private static String buildLocalAddressingPrompt(PR pr, List<PRComment> comments)
    {
        StringBuilder out = new StringBuilder();
        out.append("New comments arrived on your local PR \"").append(pr.title()).append("\". ")
                .append("Unlike remote review comments, address these directly now — the branch "
                        + "hasn't been pushed yet, so there's nothing to gate on.\n\n")
                .append("For EACH open comment below:\n")
                .append("  1. If it asks for a code change: make the fix, commit it, call "
                        + "record_pr_commit, then resolve_pr_comment(comment_id, "
                        + "resolution='addressed').\n")
                .append("  2. If it's a question or needs no code change: reply with "
                        + "record_pr_comment (parent_comment_id set to the comment's id), then "
                        + "resolve_pr_comment(comment_id, resolution='addressed').\n")
                .append("  3. If you disagree and no action is needed: reply explaining why via "
                        + "record_pr_comment, then resolve_pr_comment(comment_id, "
                        + "resolution='dismissed').\n\n")
                .append("Open comments:\n");
        int i = 1;
        for (PRComment comment : comments) {
            if (!isUnaddressedLocalComment(comment)) {
                continue;
            }
            out.append('\n').append(i++).append(". [id: ").append(comment.id()).append("] ");
            if (comment.filePath() != null) {
                out.append(comment.filePath());
                if (comment.lineNumber() != null) {
                    out.append(':').append(comment.lineNumber());
                }
            }
            out.append('\n')
                    .append("   @").append(comment.author()).append(": ")
                    .append(comment.body() == null ? "" : comment.body().strip())
                    .append('\n');
        }
        return out.toString();
    }

    /**
     * Park the one-time mark-ready gate as an AWAITING_REVIEW notification
     * carrying a {@code mark_ready} proposal. Created directly (not via
     * {@code ParkedProposalService.park}) so the shipped task stays IN_REVIEW
     * on the remote spine — the gate is a prompt to mark ready, not a status
     * change. Approving it flips the PR ready and requests any reviewers.
     */
    private void parkMarkReadyGate(Task task, String repo, int number)
    {
        int slash = repo.indexOf('/');
        if (slash <= 0) {
            return;
        }
        ParkedProposal proposal = new ParkedProposal.MarkReady(
                new ParkedProposal.PrRef(repo.substring(0, slash), repo.substring(slash + 1), number));
        try {
            notifications.notifyAwaitingReview(
                    task.threadId(), task.id(), mapper.writeValueAsString(proposal));
        }
        catch (JsonProcessingException e) {
            log.warn("Failed to write mark-ready gate payload for task {}: {}",
                    task.id(), e.getMessage());
        }
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
        // Interrupt + evict any still-running per-stage agents BEFORE the reap,
        // so a live subprocess isn't yanked out from under a worktree it's
        // mid-tool-call inside. Best-effort: the orphan sweep retries the reap.
        evictRunningStages(task);
        if (!isTerminal(task.status())) {
            if (merged) {
                taskStore.completeTask(task.id(), Instant.now());
            }
            else {
                taskStore.remoteCloseTask(task.id(), Instant.now());
            }
        }
        // Record the terminal PR state so the task/stage surfaces stop showing
        // a merged/closed PR as "open" once the remote PR resolves (incl. via
        // the merge queue, where no in-app merge action ran).
        if (task.prNumber() != null) {
            taskStore.linkPullRequest(task.id(), task.prNumber(), merged ? "merged" : "closed");
        }
        phaseMachine.observe(
                task.id(), TaskPhase.COMPLETED, merged ? "pr_merged_observed" : "pr_closed_observed");
        // Seal any still-open review round or stage so neither keeps
        // rendering as live once the task itself is terminal.
        sealer.seal(task.id(), merged ? "pr_merged" : "pr_closed");
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
