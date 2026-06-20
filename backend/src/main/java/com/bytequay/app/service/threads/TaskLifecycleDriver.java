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
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.repository.TaskReviewMarkerStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.stage.ReadyToMergeService;
import com.bytequay.app.service.stage.RemoteCommentIngestor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.EnumSet;
import java.util.LinkedHashMap;
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
            ReadyToMergeService readyToMerge)
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

    /** Visible for the unit test: fetch the task's PR fresh and move its
     *  phase to match. */
    void reconcileTask(Task task)
    {
        String ref = task.linkedPrRef();
        int hash = ref.lastIndexOf('#');
        if (hash <= 0 || hash == ref.length() - 1) {
            return;
        }
        String repo = ref.substring(0, hash);
        int number;
        try {
            number = Integer.parseInt(ref.substring(hash + 1).trim());
        }
        catch (NumberFormatException e) {
            return;
        }
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
            // CI is green on a still-draft shipped PR. Un-drafting (marking
            // it ready for review) is autonomous per the post-ship loop:
            // record the AWAITING_READY gate, flip the PR ready, and let the
            // next observe land it at AWAITING_REMOTE_REVIEW. Idempotent —
            // guarded on detail.draft(), so an already-ready PR never
            // re-fires the mutation.
            phaseMachine.observe(task.id(), TaskPhase.AWAITING_READY, "ci_green_on_draft");
            pullRequests.setPullRequestDraft(repo, number, false);
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
        String prompt = buildReviewAnalysisPrompt(repo, number, detail);
        try {
            scheduler.enqueueTurn(thread, prompt, TurnInitiator.unattended("address-comments-analysis"));
            log.info("address-comments: analysis turn queued for task {} on {} #{}",
                    task.id(), repo, number);
        }
        catch (RuntimeException e) {
            log.warn("address-comments analysis enqueue failed for task {}: {}",
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
    private static String buildReviewAnalysisPrompt(String repo, int number, PullRequestDetail detail)
    {
        StringBuilder out = new StringBuilder();
        out.append("New review comments arrived on ").append(repo).append(" #").append(number)
                .append(". Do NOT change code or reply on the PR yet.\n\n")
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

    /** Drive a task to terminal COMPLETED from an observed remote merge /
     *  close: flip the runtime status (the phase machine owns the phase
     *  axis) and, on a real merge, reap the now-dead worktree + branch. */
    private void completeRemotelyTerminal(Task task, boolean merged)
    {
        if (task.status() != TaskStatus.COMPLETED) {
            taskStore.completeTask(task.id(), Instant.now());
        }
        phaseMachine.observe(
                task.id(), TaskPhase.COMPLETED, merged ? "pr_merged_observed" : "pr_closed_observed");
        // Reap only on a real merge — a closed-unmerged PR may still carry
        // local commits the user hasn't landed, so deleting its branch
        // would lose work.
        if (merged) {
            worktrees.reap(task);
        }
    }
}
