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
package com.bytequay.app.service.stage;

import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.PullRequestDetail.CiStatus;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.ReviewCommentSource;
import com.bytequay.app.domain.StageEventType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.threads.NotificationService;
import com.bytequay.app.service.threads.TaskPhaseMachine;
import com.bytequay.app.service.tools.ParkedProposal;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Fires the ready-to-merge notification once a shipped PR reaches the
 * merge-ready state — CI green, no unresolved remote comments, reviewers
 * approved — and auto-resets when any condition breaks. The first monitor
 * sweep to notice wins an atomic compare-and-set on the task's sentinel,
 * so the two monitor loops never both notify for the same ready state.
 */
@Component
public class ReadyToMergeService
{
    private static final Logger log = LoggerFactory.getLogger(ReadyToMergeService.class);

    /** Silent auto re-enqueues after a merge-queue bounce before escalating
     *  to CI fixing + a notification. */
    private static final int MAX_MERGE_QUEUE_RETRIES = 2;

    private final TaskStore taskStore;
    private final StageStore stageStore;
    private final NotificationService notifications;
    private final PullRequestService pullRequests;
    private final TaskPhaseMachine phaseMachine;
    private final ObjectMapper mapper;

    public ReadyToMergeService(
            TaskStore taskStore,
            StageStore stageStore,
            NotificationService notifications,
            PullRequestService pullRequests,
            TaskPhaseMachine phaseMachine,
            ObjectMapper mapper)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.notifications = requireNonNull(notifications, "notifications is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.phaseMachine = requireNonNull(phaseMachine, "phaseMachine is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    /** Re-evaluate the ready-to-merge state for a shipped task against its
     *  freshly-fetched PR detail, firing or auto-resetting the notification —
     *  or, once the user has approved, driving the merge automatically. */
    @Transactional
    public void evaluate(Task task, PullRequestDetail detail)
    {
        if (detail == null) {
            return;
        }

        // A terminal PR can't be "ready to merge"; clear every gate/marker.
        if (detail.merged() || isClosed(detail)) {
            taskStore.clearMergeNotificationSent(task.id());
            taskStore.clearMergeAuthorization(task.id());
            return;
        }

        boolean ready = detail.ciStatus() == CiStatus.PASSING
                && reviewersApproved(detail)
                && noUnresolvedRemoteComments(task.id());

        // Standing consent: once the user approved, keep the merge moving
        // automatically (re-enqueue after a queue bounce) instead of
        // re-prompting them every time.
        if (taskStore.isMergeAuthorized(task.id())) {
            evaluateAuthorized(task, detail, ready);
            return;
        }

        boolean armed = taskStore.mergeNotificationSentAt(task.id()).isPresent();
        if (ready && !armed) {
            if (taskStore.markMergeNotificationSentIfUnset(task.id(), Instant.now())) {
                parkMergeGate(task, detail);
            }
            else {
                recordOnActiveStage(task.id(), StageEventType.NOTIFY_SKIPPED,
                        Map.of("reason", "already_sent"));
            }
        }
        else if (!ready && armed) {
            taskStore.clearMergeNotificationSent(task.id());
            recordOnActiveStage(task.id(), StageEventType.NOTIFY_SKIPPED,
                    Map.of("reason", "conditions_broke"));
        }
    }

    /**
     * The merge is pre-approved (standing consent), so keep it moving without
     * re-prompting. While the PR sits in the queue, wait. If it bounces back
     * out still ready, silently re-enqueue up to {@link #MAX_MERGE_QUEUE_RETRIES}
     * times. When those run out, hand the task to CI fixing to investigate the
     * queue failure, drop consent (a fresh approval is required afterward), and
     * notify the user. A red CI head needs no special case here — the phase
     * machine already routes it to CI fixing, and consent still stands so the
     * PR auto-merges again once it's green.
     */
    private void evaluateAuthorized(Task task, PullRequestDetail detail, boolean ready)
    {
        if (isInQueue(detail)) {
            // Genuinely queued and progressing — reset the bounce counter.
            taskStore.setMergeQueueRetries(task.id(), 0);
            return;
        }
        if (!ready) {
            // CI red / changes requested / unresolved comments: wait. The
            // phase machine handles a red head; consent still stands.
            return;
        }
        ParkedProposal.PrRef pr = prRefFor(task, detail);
        if (pr == null) {
            return;
        }
        int retries = taskStore.mergeQueueRetries(task.id());
        if (retries < MAX_MERGE_QUEUE_RETRIES) {
            if (pullRequests.enqueueForMerge(pr.owner() + "/" + pr.repo(), pr.number())) {
                taskStore.setMergeQueueRetries(task.id(), retries + 1);
                log.info("Auto re-enqueued {}#{} after a merge-queue bounce (attempt {}/{})",
                        pr.owner() + "/" + pr.repo(), pr.number(), retries + 1, MAX_MERGE_QUEUE_RETRIES);
            }
            return;
        }
        // Retries exhausted with a green head — not a simple transient. Hand it
        // to CI fixing to investigate, require fresh approval afterward, notify.
        taskStore.clearMergeNotificationSent(task.id());
        taskStore.clearMergeAuthorization(task.id());
        phaseMachine.observe(task.id(), TaskPhase.CI_FIXING, "merge_queue_failed_repeatedly");
        notifyMergeQueueFailed(task, detail, pr);
    }

    /** True while the PR is sitting in the merge queue and not in a failed
     *  ({@code UNMERGEABLE}) entry state. */
    private static boolean isInQueue(PullRequestDetail detail)
    {
        String state = detail.mergeQueueState();
        return state != null && !state.isBlank() && !"UNMERGEABLE".equalsIgnoreCase(state);
    }

    private void notifyMergeQueueFailed(Task task, PullRequestDetail detail, ParkedProposal.PrRef pr)
    {
        notifications.notifyNeedsAttention(task.threadId(), task.id(), toJson(Map.of(
                "reason", "merge_queue_failed",
                "number", detail.number(),
                "pr", Map.of("owner", pr.owner(), "repo", pr.repo(), "number", pr.number()))));
        recordOnActiveStage(task.id(), StageEventType.NOTIFY_FIRED,
                Map.of("reason", "merge_queue_failed", "number", detail.number()));
    }

    /**
     * Park a {@code merge_pr} proposal so the user gets a one-click
     * "Approve &amp; merge" gate once the PR is merge-ready. Created as an
     * AWAITING_REVIEW notification directly (not via {@code
     * ParkedProposalService.park}) so the shipped task stays {@code IN_REVIEW}
     * on the remote spine the lifecycle driver monitors — the gate is a prompt
     * to merge, not a status change. The merge's own preflight + GitHub call
     * re-check mergeability at approval time, so a gate that goes stale (CI
     * breaks after it parks) can't merge a broken PR.
     */
    private void parkMergeGate(Task task, PullRequestDetail detail)
    {
        ParkedProposal.PrRef pr = prRefFor(task, detail);
        if (pr == null) {
            recordOnActiveStage(task.id(), StageEventType.NOTIFY_SKIPPED,
                    Map.of("reason", "no_pr_ref"));
            return;
        }
        // strategy null → squash (PublishService default).
        ParkedProposal proposal = new ParkedProposal.MergePr(null, pr);
        notifications.notifyAwaitingReview(task.threadId(), task.id(), toJson(proposal));
        recordOnActiveStage(task.id(), StageEventType.NOTIFY_FIRED,
                Map.of("reason", "ready_to_merge", "action", "merge_pr", "number", detail.number()));
    }

    /** Build the merge target from the task's linked PR ref
     *  ({@code owner/repo#number}), falling back to the PR detail. Null when
     *  neither yields a complete owner/repo/number. */
    private static ParkedProposal.PrRef prRefFor(Task task, PullRequestDetail detail)
    {
        Optional<PullRequestRef> parsed = PullRequestRef.parse(task.linkedPrRef());
        if (parsed.isPresent()) {
            PullRequestRef ref = parsed.get();
            return new ParkedProposal.PrRef(ref.owner(), ref.repo(), ref.number());
        }
        String repo = detail.repo();
        if (repo != null && repo.contains("/")) {
            int slash = repo.indexOf('/');
            return new ParkedProposal.PrRef(
                    repo.substring(0, slash), repo.substring(slash + 1), detail.number());
        }
        return null;
    }

    private boolean noUnresolvedRemoteComments(String taskId)
    {
        return stageStore.findUnresolvedComments(taskId).stream()
                .noneMatch(c -> c.source() == ReviewCommentSource.REMOTE_REVIEWER);
    }

    private void recordOnActiveStage(String taskId, StageEventType type, Map<String, Object> payload)
    {
        stageStore.findActiveStage(taskId).ifPresent(stage ->
                stageStore.recordEvent(stage.id(), taskId, type, payload));
    }

    private static boolean reviewersApproved(PullRequestDetail detail)
    {
        return detail.approvalCount() > 0
                && detail.changesRequestedCount() == 0
                && detail.pendingReviewerCount() == 0;
    }

    private static boolean isClosed(PullRequestDetail detail)
    {
        return detail.state() != null && "closed".equalsIgnoreCase(detail.state());
    }

    private String toJson(Object value)
    {
        try {
            return mapper.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            return "{}";
        }
    }
}
