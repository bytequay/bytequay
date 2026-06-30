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

import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.PullRequestDetail.ReviewMessage;
import com.bytequay.app.domain.PullRequestDetail.ReviewThread;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageState;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskReviewMarkerStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.stage.IterationService;
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
            TaskPhase.CI_FIXING,
            TaskPhase.AWAITING_REMOTE_REVIEW,
            TaskPhase.AWAITING_UPDATE_PUSH);

    private final TaskStore taskStore;
    private final PullRequestService pullRequests;
    private final TaskPhaseMachine phaseMachine;
    private final WorktreeService worktrees;
    private final TaskReviewMarkerStore reviewMarkers;
    private final ThreadStore threadStore;
    private final ThreadTurnScheduler scheduler;
    private final NotificationService notifications;
    private final ObjectMapper mapper;
    private final RemoteCommentIngestor commentIngestor;
    private final ReadyToMergeService readyToMerge;
    private final IterationService iterationService;
    private final ThreadRegistry registry;
    private final StageStore stageStore;

    public TaskLifecycleDriver(
            TaskStore taskStore,
            PullRequestService pullRequests,
            TaskPhaseMachine phaseMachine,
            WorktreeService worktrees,
            TaskReviewMarkerStore reviewMarkers,
            ThreadStore threadStore,
            ThreadTurnScheduler scheduler,
            NotificationService notifications,
            ObjectMapper mapper,
            RemoteCommentIngestor commentIngestor,
            ReadyToMergeService readyToMerge,
            IterationService iterationService,
            ThreadRegistry registry,
            StageStore stageStore)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.phaseMachine = requireNonNull(phaseMachine, "phaseMachine is null");
        this.worktrees = requireNonNull(worktrees, "worktrees is null");
        this.reviewMarkers = requireNonNull(reviewMarkers, "reviewMarkers is null");
        this.threadStore = requireNonNull(threadStore, "threadStore is null");
        this.scheduler = requireNonNull(scheduler, "scheduler is null");
        this.notifications = requireNonNull(notifications, "notifications is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.commentIngestor = requireNonNull(commentIngestor, "commentIngestor is null");
        this.readyToMerge = requireNonNull(readyToMerge, "readyToMerge is null");
        this.iterationService = requireNonNull(iterationService, "iterationService is null");
        this.registry = requireNonNull(registry, "registry is null");
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
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
        if (phase == TaskPhase.AWAITING_REMOTE_REVIEW) {
            Optional<Instant> newest = TaskLifecyclePhases.newestUnaddressedReviewComment(
                    detail, reviewMarkers.find(task.id()).orElse(null));
            if (newest.isPresent()) {
                startAddressComments(task, repo, number, detail, newest.get());
                return;
            }
        }
        phaseMachine.observe(task.id(), phase, "pr_state_observed");
    }

    /**
     * A ready PR has a fresh round of unresolved reviewer comments. Move
     * the task onto the address-comments spine, tell the user, and kick off
     * an <em>analysis</em> turn that presents a per-comment plan and then
     * stops — the user reviews / discusses / edits it in the task chat and
     * tells the agent to proceed before anything is addressed (decision:
     * ask first; addressing is never autonomous). The user's "go ahead" is
     * just their next chat turn, which addresses the comments one by one
     * with the existing reply / push gate tools.
     */
    private void startAddressComments(
            Task task, String repo, int number, PullRequestDetail detail, Instant newest)
    {
        phaseMachine.observe(task.id(), TaskPhase.ADDRESSING_COMMENTS, "new_review_comments");
        // Advance the marker up front so a later sweep doesn't re-trigger on
        // the same comments while the analysis / user review is underway; a
        // genuinely newer round of comments still reads as new.
        reviewMarkers.mark(task.id(), newest);
        notifyNewComments(task, repo, number);
        Optional<Thread> threadOpt = threadStore.findThreadById(task.threadId());
        if (threadOpt.isEmpty()) {
            log.warn("address-comments: thread {} not found (task {})", task.threadId(), task.id());
            return;
        }
        Thread thread = threadOpt.get();
        if (thread.status() != ThreadStatus.IDLE) {
            // Busy thread — the notification already alerted the user, who
            // can open the task and start the analysis themselves. Don't
            // stack a turn on top of a running one.
            log.info("address-comments: thread {} is {} (task {}); notified only",
                    thread.id(), thread.status(), task.id());
            return;
        }
        String prompt = buildReviewAnalysisPrompt(
                repo, number, detail, iterationService.latestCiFixingSummaries(task.id()));
        try {
            // Bind the task id AND the review-monitor stage so this runs on the
            // task's own agent and its messages land in stage_messages, not the
            // thread slice. The stage is PAUSED while it waits on remote review,
            // which findActiveStage (OPEN/ACTIVE only) misses — so pin it
            // explicitly via findLiveStageByType.
            String stageId = stageStore.findLiveStageByType(task.id(), StageType.REVIEW_MONITOR_STAGE)
                    .map(s -> s.id().toString())
                    .orElse(null);
            String turnId = scheduler.enqueueTaskTurn(
                    thread, prompt, task.id(), stageId,
                    TurnInitiator.unattended("address-comments-analysis"));
            iterationService.begin(task.id(), turnId, IterationService.TRIGGER_NEW_COMMENTS);
            log.info("address-comments: analysis turn queued for task {} on {} #{}",
                    task.id(), repo, number);
        }
        catch (RuntimeException e) {
            log.warn("address-comments analysis enqueue failed for task {}: {}",
                    task.id(), e.getMessage());
        }
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

    /** Best-effort NEEDS_ATTENTION row so the bell flags the new comments
     *  even if the user isn't looking at the task chat. */
    private void notifyNewComments(Task task, String repo, int number)
    {
        try {
            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("reason", "New review comments");
            payload.put("repoFullName", repo);
            payload.put("prNumber", number);
            notifications.notifyNeedsAttention(
                    task.threadId(), task.id(), mapper.writeValueAsString(payload));
        }
        catch (JsonProcessingException e) {
            log.warn("Failed to write new-review-comments payload for task {}: {}",
                    task.id(), e.getMessage());
        }
    }

    /** The analysis turn's first prompt. Carries the unresolved review
     *  threads inline so the agent needn't re-fetch the PR, asks for a
     *  structured per-comment analysis, and tells it to STOP and wait for
     *  the user before addressing anything. */
    private static String buildReviewAnalysisPrompt(
            String repo, int number, PullRequestDetail detail, List<String> ciFixingSummaries)
    {
        StringBuilder out = new StringBuilder();
        out.append("New review comments arrived on ").append(repo).append(" #").append(number)
                .append(". Do NOT change code or reply on the PR yet.\n\n");
        // Seed the agent with what the CI-fixing stage just did, so it
        // doesn't re-derive context the prior stage already established.
        if (ciFixingSummaries != null && !ciFixingSummaries.isEmpty()) {
            out.append("Recent CI-fixing iterations on this task:\n");
            for (String summary : ciFixingSummaries) {
                out.append("  - ").append(summary).append('\n');
            }
            out.append('\n');
        }
        out
                .append("For EACH unresolved review thread below, write a short analysis:\n")
                .append("  1. Summary — what is the comment asking?\n")
                .append("  2. Problem — what is actually wrong, if anything?\n")
                .append("  3. Does it make sense? If you disagree, draft a respectful "
                        + "push-back reply.\n")
                .append("  4. If it needs a code change, give a concrete plan for the fix.\n\n")
                .append("Then STOP and wait. I'll review your analysis, discuss any of it, and "
                        + "tell you to proceed. Only after I confirm should you address them one "
                        + "by one — implement the fix or post the push-back, validate, push, and "
                        + "reply on the threads.\n\n")
                .append("Unresolved review threads:\n");
        int i = 1;
        for (ReviewThread thread : detail.reviewThreads()) {
            if (Boolean.TRUE.equals(thread.resolved())
                    || thread.messages() == null
                    || thread.messages().isEmpty()) {
                continue;
            }
            out.append('\n').append(i++).append(". ");
            if (thread.filePath() != null) {
                out.append(thread.filePath());
                if (thread.line() != null) {
                    out.append(':').append(thread.line());
                }
            }
            out.append('\n');
            for (ReviewMessage message : thread.messages()) {
                out.append("   @").append(message.author() == null ? "?" : message.author())
                        .append(": ")
                        .append(message.body() == null ? "" : message.body().strip())
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
        // Seal any still-open stage so the stage pages stop reporting live work
        // after the task itself is terminal.
        for (StageInstance stage : stageStore.findStagesByTask(task.id())) {
            if (stage.state() != StageState.CLOSED) {
                stageStore.closeStage(stage.id(), merged ? "pr_merged" : "pr_closed");
            }
        }
        worktrees.reap(task);
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
