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

import com.bytequay.app.domain.NotificationKind;
import com.bytequay.app.domain.NotificationStatus;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.PullRequestDetail.CiStatus;
import com.bytequay.app.domain.ReviewRound;
import com.bytequay.app.domain.StageEventType;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.ReviewRoundStore;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.threads.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.mock.mockito.MockitoTestExecutionListener;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The ready-to-merge notification: fires once on the ready state via the
 * atomic sentinel CAS (so two monitor sweeps don't double-notify) and
 * auto-resets when a condition breaks.
 */
@SpringBootTest
@TestExecutionListeners(
        listeners = {
            DependencyInjectionTestExecutionListener.class,
            MockitoTestExecutionListener.class,
        },
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class TestReadyToMerge
{
    @Autowired
    private ReadyToMergeService readyToMerge;
    @Autowired
    private TaskStore taskStore;
    @Autowired
    private StageStore stageStore;
    @Autowired
    private ReviewRoundStore reviewRounds;
    @Autowired
    private ThreadStore threadStore;
    @Autowired
    private NotificationService notifications;
    @MockBean
    private PullRequestService pullRequests;

    @Test
    void firesOnceOnReadyAndDedupsTheSecondSweep()
    {
        String threadId = seedThread();
        String taskId = seedTask(threadId);
        StageInstance stage = stageStore.openStage(taskId, StageType.CI_FIXING_STAGE, null);
        Task task = taskStore.findTaskById(taskId).orElseThrow();

        readyToMerge.evaluate(task, readyDetail());
        assertThat(taskStore.mergeNotificationSentAt(taskId)).isPresent();
        assertThat(readyToMergeNotifications(threadId)).isEqualTo(1);
        assertThat(stageStore.findEventsByStage(stage.id()))
                .anyMatch(e -> e.eventType() == StageEventType.NOTIFY_FIRED);

        // A second sweep over the same ready state must not re-notify.
        readyToMerge.evaluate(task, readyDetail());
        assertThat(readyToMergeNotifications(threadId)).isEqualTo(1);
    }

    @Test
    void doesNotFireWhenThePrIsNotMergeable()
    {
        String threadId = seedThread();
        String taskId = seedTask(threadId);
        Task task = taskStore.findTaskById(taskId).orElseThrow();

        // CI green, no reviewer blocking, no unresolved comments — but GitHub
        // reports a conflict, so the merge gate must stay closed.
        PullRequestDetail conflicted = detail(CiStatus.PASSING, 0, 0, false, "open");
        when(conflicted.mergeable()).thenReturn(false);

        readyToMerge.evaluate(task, conflicted);
        assertThat(taskStore.mergeNotificationSentAt(taskId)).isEmpty();
        assertThat(readyToMergeNotifications(threadId)).isEqualTo(0);
    }

    @Test
    void firesWhenMergeabilityIsStillUnknown()
    {
        String threadId = seedThread();
        String taskId = seedTask(threadId);
        stageStore.openStage(taskId, StageType.CI_FIXING_STAGE, null);
        Task task = taskStore.findTaskById(taskId).orElseThrow();

        // GitHub hasn't finished recomputing mergeability after the push, so it
        // reports null. That is not a conflict — the gate must still fire.
        PullRequestDetail unknownMergeable = detail(CiStatus.PASSING, 0, 0, false, "open");
        when(unknownMergeable.mergeable()).thenReturn(null);

        readyToMerge.evaluate(task, unknownMergeable);
        assertThat(taskStore.mergeNotificationSentAt(taskId)).isPresent();
        assertThat(readyToMergeNotifications(threadId)).isEqualTo(1);
    }

    @Test
    void parksAReviewerNudgeWhenTheUserCannotMerge()
    {
        String threadId = seedThread();
        String taskId = seedTask(threadId);
        stageStore.openStage(taskId, StageType.REVIEW_MONITOR_STAGE, null);
        Task task = taskStore.findTaskById(taskId).orElseThrow();

        // Merge-ready, but no push access to the upstream repo. Instead of the
        // merge gate the user gets an approvable comment nudging the reviewers.
        PullRequestDetail noAccess = detail(CiStatus.PASSING, 0, 0, false, "open");
        when(noAccess.viewerCanWrite()).thenReturn(false);
        when(noAccess.requestedReviewers()).thenReturn(List.of("octocat"));

        readyToMerge.evaluate(task, noAccess);

        assertThat(taskStore.mergeNotificationSentAt(taskId)).isPresent();
        assertThat(readyToMergeNotifications(threadId)).isZero();       // not a merge_pr gate
        long nudges = notifications.listForThread(threadId).stream()
                .filter(n -> n.kind() == NotificationKind.AWAITING_REVIEW)
                .filter(n -> n.payloadJson().contains("\"action\":\"post_comment\""))
                .filter(n -> n.payloadJson().contains("@octocat"))
                .count();
        assertThat(nudges).isEqualTo(1);
    }

    @Test
    void holdsTheGateUntilTheMinimumWritePermissionApprovalsLand()
    {
        String threadId = seedThread();
        String taskId = seedTask(threadId);
        stageStore.openStage(taskId, StageType.REVIEW_MONITOR_STAGE, null);
        taskStore.setMinApprovals(taskId, 2);        // needs 2 write-permission approvals
        Task task = taskStore.findTaskById(taskId).orElseThrow();

        // Only one write-permission approval so far — not merge-ready.
        PullRequestDetail oneApproval = detail(CiStatus.PASSING, 0, 0, false, "open");
        when(oneApproval.writeApprovalCount()).thenReturn(1);
        readyToMerge.evaluate(task, oneApproval);
        assertThat(taskStore.mergeNotificationSentAt(taskId)).isEmpty();
        assertThat(readyToMergeNotifications(threadId)).isZero();

        // The second write-permission approval lands — the merge gate fires.
        PullRequestDetail twoApprovals = detail(CiStatus.PASSING, 0, 0, false, "open");
        when(twoApprovals.writeApprovalCount()).thenReturn(2);
        readyToMerge.evaluate(task, twoApprovals);
        assertThat(taskStore.mergeNotificationSentAt(taskId)).isPresent();
        assertThat(readyToMergeNotifications(threadId)).isEqualTo(1);
    }

    @Test
    void doesNotFireWhileThePrIsStillADraft()
    {
        String threadId = seedThread();
        String taskId = seedTask(threadId);
        Task task = taskStore.findTaskById(taskId).orElseThrow();

        // CI green + mergeable, but the PR is shipped-as-draft. Firing here
        // would arm the sentinel and suppress the real gate once it's marked
        // ready for review, so the draft must not gate.
        PullRequestDetail draft = detail(CiStatus.PASSING, 0, 0, false, "open");
        when(draft.draft()).thenReturn(true);

        readyToMerge.evaluate(task, draft);
        assertThat(taskStore.mergeNotificationSentAt(taskId)).isEmpty();
        assertThat(readyToMergeNotifications(threadId)).isEqualTo(0);

        // Once it's out for review (no longer draft), the gate fires.
        readyToMerge.evaluate(task, readyDetail());
        assertThat(taskStore.mergeNotificationSentAt(taskId)).isPresent();
        assertThat(readyToMergeNotifications(threadId)).isEqualTo(1);
    }

    @Test
    void doesNotFireWhileAReviewRoundIsLive()
    {
        String threadId = seedThread();
        String taskId = seedTask(threadId);
        Task task = taskStore.findTaskById(taskId).orElseThrow();
        reviewRounds.insert(new ReviewRound(
                UUID.randomUUID().toString(), taskId, 1, List.of("@octocat"),
                ReviewRound.STATUS_ADDRESSING, ReviewRound.ReviewRoundStats.empty(),
                null, Instant.now(), null, null, ReviewRound.ORIGIN_EXTERNAL,
                null, 0, ReviewRound.DEFAULT_BRAIN_BUDGET));

        readyToMerge.evaluate(task, readyDetail());

        assertThat(taskStore.mergeNotificationSentAt(taskId)).isEmpty();
        assertThat(readyToMergeNotifications(threadId)).isZero();
    }

    @Test
    void doesNotFireForAnUnresolvedLiveGitHubThread()
    {
        String threadId = seedThread();
        String taskId = seedTask(threadId);
        Task task = taskStore.findTaskById(taskId).orElseThrow();
        PullRequestDetail detail = readyDetail();
        PullRequestDetail.ReviewThread unresolved = mock(PullRequestDetail.ReviewThread.class);
        when(unresolved.resolved()).thenReturn(false);
        when(detail.reviewThreads()).thenReturn(List.of(unresolved));

        readyToMerge.evaluate(task, detail);

        assertThat(taskStore.mergeNotificationSentAt(taskId)).isEmpty();
        assertThat(readyToMergeNotifications(threadId)).isZero();
    }

    @Test
    void autoResetsWhenAConditionBreaks()
    {
        String threadId = seedThread();
        String taskId = seedTask(threadId);
        Task task = taskStore.findTaskById(taskId).orElseThrow();

        readyToMerge.evaluate(task, readyDetail());
        assertThat(taskStore.mergeNotificationSentAt(taskId)).isPresent();

        // CI goes red — the armed sentinel clears.
        readyToMerge.evaluate(task, detail(CiStatus.FAILING, 0, 0, false, "open"));
        assertThat(taskStore.mergeNotificationSentAt(taskId)).isEmpty();
        assertThat(notifications.listForThread(threadId)).anyMatch(notification ->
                notification.payloadJson().contains("\"action\":\"merge_pr\"")
                        && notification.status() == NotificationStatus.RESOLVED);
    }

    @Test
    void autoReEnqueuesWhenAuthorizedAndTheQueueBounced()
    {
        String threadId = seedThread();
        String taskId = seedTask(threadId);
        stageStore.openStage(taskId, StageType.REVIEW_MONITOR_STAGE, null);
        taskStore.authorizeMerge(taskId, Instant.now());        // standing consent
        when(pullRequests.enqueueForMerge(anyString(), anyInt())).thenReturn(true);
        Task task = taskStore.findTaskById(taskId).orElseThrow();

        // Ready + authorized + not in the queue (bounced) → silent re-enqueue,
        // not a fresh approval gate.
        readyToMerge.evaluate(task, readyDetail());

        assertThat(taskStore.mergeQueueRetries(taskId)).isEqualTo(1);
        assertThat(readyToMergeNotifications(threadId)).isZero();
    }

    @Test
    void escalatesToNeedsAttentionAndNotifiesOnceRetriesAreExhausted()
    {
        String threadId = seedThread();
        String taskId = seedTask(threadId);
        stageStore.openStage(taskId, StageType.REVIEW_MONITOR_STAGE, null);
        taskStore.updatePhase(taskId, TaskPhase.AWAITING_REMOTE_REVIEW);
        taskStore.authorizeMerge(taskId, Instant.now());
        taskStore.setMergeQueueRetries(taskId, 2);              // already exhausted
        Task task = taskStore.findTaskById(taskId).orElseThrow();

        readyToMerge.evaluate(task, readyDetail());

        assertThat(taskStore.isMergeAuthorized(taskId)).isFalse();
        assertThat(taskStore.findTaskById(taskId).orElseThrow().phase())
                .isEqualTo(TaskPhase.NEEDS_ATTENTION);
        assertThat(needsAttentionNotifications(threadId)).isEqualTo(1);
    }

    private long needsAttentionNotifications(String threadId)
    {
        return notifications.listForThread(threadId).stream()
                .filter(n -> n.kind() == NotificationKind.NEEDS_ATTENTION)
                .filter(n -> n.payloadJson().contains("merge_queue_failed"))
                .count();
    }

    /** The ready-to-merge gate is now a parked {@code merge_pr} proposal —
     *  an AWAITING_REVIEW notification the user one-click approves to merge —
     *  rather than the old informational READY_TO_MERGE notice. */
    private long readyToMergeNotifications(String threadId)
    {
        return notifications.listForThread(threadId).stream()
                .filter(n -> n.kind() == NotificationKind.AWAITING_REVIEW)
                .filter(n -> n.payloadJson().contains("\"action\":\"merge_pr\""))
                .count();
    }

    private static PullRequestDetail readyDetail()
    {
        return detail(CiStatus.PASSING, 0, 0, false, "open");
    }

    private static PullRequestDetail detail(
            CiStatus ci, int changesRequested, int pending, boolean merged, String state)
    {
        PullRequestDetail detail = mock(PullRequestDetail.class);
        when(detail.ciStatus()).thenReturn(ci);
        when(detail.changesRequestedCount()).thenReturn(changesRequested);
        when(detail.pendingReviewerCount()).thenReturn(pending);
        when(detail.merged()).thenReturn(merged);
        when(detail.state()).thenReturn(state);
        // Ready by default is a non-draft PR; the draft path stubs this true.
        lenient().when(detail.draft()).thenReturn(false);
        // Default to push access → the one-click merge gate; the no-privilege
        // path stubs this false and supplies reviewers to nudge.
        lenient().when(detail.viewerCanWrite()).thenReturn(true);
        lenient().when(detail.requestedReviewers()).thenReturn(List.of());
        lenient().when(detail.approvalCount()).thenReturn(0);
        lenient().when(detail.writeApprovalCount()).thenReturn(0);
        lenient().when(detail.reviewThreads()).thenReturn(List.of());
        // Mergeable by default; the CI-failing paths short-circuit before the
        // mergeable check, so keep it lenient.
        lenient().when(detail.mergeable()).thenReturn(true);
        lenient().when(detail.repo()).thenReturn("octo/repo");
        lenient().when(detail.number()).thenReturn(7);
        return detail;
    }

    private String seedThread()
    {
        Instant now = Instant.parse("2026-06-20T09:00:00Z");
        Thread thread = new Thread(
                UUID.randomUUID().toString(), ThreadKind.CLI_AGENT, "claude-code",
                null, "Ready test", ThreadStatus.RUNNING, "claude-sonnet-4.6",
                0L, 0L, 0L, now, now, null, null, ThreadFlow.BUILD, "ws-default", null, null);
        threadStore.saveThread(thread);
        return thread.id();
    }

    private String seedTask(String threadId)
    {
        Instant now = Instant.parse("2026-06-20T09:00:00Z");
        String taskId = UUID.randomUUID().toString();
        taskStore.saveTask(new Task(
                taskId, threadId, 1L, TaskStatus.RUNNING, "feature", null, "main", "/tmp",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, now, null, null, null, null, null));
        return taskId;
    }
}
