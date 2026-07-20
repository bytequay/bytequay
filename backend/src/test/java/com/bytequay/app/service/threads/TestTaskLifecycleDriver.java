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
import com.bytequay.app.domain.PullRequestDetail.CiStatus;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestTaskLifecycleDriver
{
    private final TaskStore taskStore = mock(TaskStore.class);
    private final PullRequestService pullRequests = mock(PullRequestService.class);
    private final TaskPhaseMachine phaseMachine = mock(TaskPhaseMachine.class);
    private final WorktreeService worktrees = mock(WorktreeService.class);
    private final ThreadStore threadStore = mock(ThreadStore.class);
    private final ThreadTurnScheduler scheduler = mock(ThreadTurnScheduler.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final RemoteCommentIngestor commentIngestor = mock(RemoteCommentIngestor.class);
    private final ReadyToMergeService readyToMerge = mock(ReadyToMergeService.class);
    private final ReviewRoundService reviewRounds = mock(ReviewRoundService.class);
    private final ThreadRegistry registry = mock(ThreadRegistry.class);
    private final StageStore stageStore = mock(StageStore.class);
    private final PRService prService = mock(PRService.class);
    private final TaskTerminalSealer sealer = mock(TaskTerminalSealer.class);
    private final TaskLifecycleDriver driver =
            new TaskLifecycleDriver(taskStore, pullRequests, phaseMachine, worktrees,
                    threadStore, scheduler, notifications, mapper,
                    commentIngestor, readyToMerge, reviewRounds, registry, stageStore, prService, sealer);

    @TempDir
    private Path tempDir;

    @Test
    void sweepReapsTerminalTaskWhoseWorktreeStillExists()
            throws Exception
    {
        Path orphan = Files.createDirectory(tempDir.resolve("orphan-wt"));
        Task canceled = task(null, TaskPhase.COMPLETED)
                .withStatus(TaskStatus.CANCELED)
                .withWorktreePath(orphan.toString());
        when(taskStore.listByStatus(eq(TaskStatus.CANCELED), anyInt())).thenReturn(List.of(canceled));
        when(taskStore.listByStatus(eq(TaskStatus.COMPLETED), anyInt())).thenReturn(List.of());

        driver.sweepOrphanedWorktrees();

        verify(worktrees).reap(canceled);
    }

    @Test
    void sweepSkipsTerminalTaskWhoseWorktreeIsAlreadyGone()
    {
        Task canceled = task(null, TaskPhase.COMPLETED)
                .withStatus(TaskStatus.CANCELED)
                .withWorktreePath(tempDir.resolve("already-reaped").toString());
        when(taskStore.listByStatus(eq(TaskStatus.CANCELED), anyInt())).thenReturn(List.of(canceled));
        when(taskStore.listByStatus(eq(TaskStatus.COMPLETED), anyInt())).thenReturn(List.of());

        driver.sweepOrphanedWorktrees();

        verify(worktrees, never()).reap(any());
    }

    @Test
    void greenDraftOffersTheMarkReadyGateOnceInsteadOfAutoUnDrafting()
    {
        Task task = task("trinodb/trino#29897", TaskPhase.PUSHED_AWAITING_CI);
        PullRequestDetail greenDraft = detail(CiStatus.PASSING, true);
        when(pullRequests.refreshPullRequestDetail("trinodb/trino", 29897)).thenReturn(greenDraft);
        when(taskStore.markReadyGateSentIfUnset(eq("t1.k2"), any())).thenReturn(true);

        driver.reconcileTask(task);

        // Green draft → record the AWAITING_READY gate and park a ONE-TIME
        // mark-ready approval (a mark_ready proposal). It must NOT un-draft
        // autonomously — the user approves marking it ready for review.
        verify(phaseMachine).observe("t1.k2", TaskPhase.AWAITING_READY, "ci_green_on_draft");
        verify(notifications).notifyAwaitingReview(eq("t1"), eq("t1.k2"), contains("mark_ready"));
        verify(pullRequests, never()).setPullRequestDraft(any(), anyInt(), eq(false));
    }

    @Test
    void greenDraftDoesNotReParkTheMarkReadyGateOnceSent()
    {
        Task task = task("trinodb/trino#29897", TaskPhase.PUSHED_AWAITING_CI);
        PullRequestDetail greenDraft = detail(CiStatus.PASSING, true);
        when(pullRequests.refreshPullRequestDetail("trinodb/trino", 29897)).thenReturn(greenDraft);
        // Sentinel already set (a prior sweep offered the gate) → not the winner.
        when(taskStore.markReadyGateSentIfUnset(eq("t1.k2"), any())).thenReturn(false);

        driver.reconcileTask(task);

        verify(notifications, never()).notifyAwaitingReview(any(), any(), anyString());
    }

    @Test
    void greenReadyPrAdvancesToRemoteReviewWithoutReUnDrafting()
    {
        Task task = task("trinodb/trino#29897", TaskPhase.AWAITING_READY);
        PullRequestDetail greenReady = detail(CiStatus.PASSING, false);
        when(pullRequests.refreshPullRequestDetail("trinodb/trino", 29897)).thenReturn(greenReady);

        driver.reconcileTask(task);

        // Already ready (not draft) — the un-draft mutation must not re-fire;
        // the phase simply advances onto the remote-review spine.
        verify(phaseMachine).observe("t1.k2", TaskPhase.AWAITING_REMOTE_REVIEW, "pr_state_observed");
        verify(pullRequests, never()).setPullRequestDraft(any(), anyInt(), eq(false));
    }

    @Test
    void completesAndReapsWhenItsPrMergedOnTheRemote()
    {
        Task task = task("trinodb/trino#29897", TaskPhase.AWAITING_REMOTE_REVIEW);
        PullRequestDetail merged = mock(PullRequestDetail.class);
        when(merged.merged()).thenReturn(true);
        when(pullRequests.refreshPullRequestDetail("trinodb/trino", 29897)).thenReturn(merged);

        driver.reconcileTask(task);

        // A remote merge fired no in-app merge event, so the driver itself
        // must finish the task: flip the runtime status, drain the phase
        // off the spine, and reap the now-dead worktree + branch.
        verify(taskStore).completeTask(eq("t1.k2"), any());
        verify(phaseMachine).observe("t1.k2", TaskPhase.COMPLETED, "pr_merged_observed");
        verify(worktrees).reap(task);
        // The PR merged, so the remote head branch is deleted too.
        verify(worktrees).deleteRemoteBranch(task);
        // A still-open review round (e.g. mid-"Addressing") must not keep
        // rendering as live now that the task itself is terminal.
        verify(sealer).seal("t1.k2", "pr_merged");
    }

    @Test
    void closedUnmergedPrLandsAtRemoteClosedAndReaps()
    {
        Task task = task("trinodb/trino#29897", TaskPhase.AWAITING_REMOTE_REVIEW);
        PullRequestDetail closed = mock(PullRequestDetail.class);
        when(closed.merged()).thenReturn(false);
        when(closed.state()).thenReturn("closed");
        when(pullRequests.refreshPullRequestDetail("trinodb/trino", 29897)).thenReturn(closed);

        driver.reconcileTask(task);

        // A closed-unmerged PR is terminal too: land at the distinct
        // REMOTE_CLOSED status (not a merge-COMPLETED) and clean up resources
        // anyway — the work never landed, so the branch is dead weight.
        verify(taskStore).remoteCloseTask(eq("t1.k2"), any());
        verify(taskStore, never()).completeTask(any(), any());
        verify(phaseMachine).observe("t1.k2", TaskPhase.COMPLETED, "pr_closed_observed");
        verify(worktrees).reap(task);
        // A close is not a merge — leave the remote branch (the PR may reopen).
        verify(worktrees, never()).deleteRemoteBranch(any());
        verify(sealer).seal("t1.k2", "pr_closed");
    }

    @Test
    void scansOnlyTheRemoteSpineAndSkipsTasksWithNoLinkedPr()
    {
        // A spine task that never linked a PR has nothing to fetch.
        Task noRef = task(null, TaskPhase.PUSHED_AWAITING_CI);
        when(taskStore.listByPhases(any(), anyInt())).thenReturn(List.of(noRef));

        driver.reconcile();

        // The phase narrowing happens in SQL — the driver asks the store
        // only for the remote-spine phases, not the whole linked-PR set.
        verify(taskStore).listByPhases(eq(TaskLifecycleDriver.REMOTE_SPINE), anyInt());
        verify(pullRequests, never()).refreshPullRequestDetail(any(), anyInt());
    }

    @Test
    void readyPrAtRemoteReviewHandsOffToTheRoundService()
    {
        Task task = task("trinodb/trino#29897", TaskPhase.AWAITING_REMOTE_REVIEW);
        PullRequestDetail ready = detail(CiStatus.PASSING, /* draft */ false);
        when(pullRequests.refreshPullRequestDetail("trinodb/trino", 29897)).thenReturn(ready);

        driver.reconcileTask(task);

        // The phase stays at AWAITING_REMOTE_REVIEW (a no-op observe) and
        // batching/round-launching is entirely ReviewRoundService's job now
        // — the driver no longer inspects review threads itself.
        verify(phaseMachine).observe("t1.k2", TaskPhase.AWAITING_REMOTE_REVIEW, "pr_state_observed");
        verify(reviewRounds).reconcile(task);
    }

    @Test
    void doesNotHandOffToTheRoundServiceOutsideRemoteReview()
    {
        Task task = task("trinodb/trino#29897", TaskPhase.PUSHED_AWAITING_CI);
        PullRequestDetail pending = detail(CiStatus.PENDING, false);
        when(pullRequests.refreshPullRequestDetail("trinodb/trino", 29897)).thenReturn(pending);

        driver.reconcileTask(task);

        verify(pullRequests).refreshPullRequestDetail("trinodb/trino", 29897);
        verify(pullRequests, never()).getPullRequestDetail(any(), anyInt());
        verify(taskStore).updateCiState("t1.k2", "PENDING");
        verify(phaseMachine).observe("t1.k2", TaskPhase.PUSHED_AWAITING_CI, "pr_state_observed");
        verify(reviewRounds, never()).reconcile(any());
    }

    @Test
    void scansOnlyTheLocalSpine()
    {
        when(taskStore.listByPhases(any(), anyInt())).thenReturn(List.of());

        driver.reconcileLocalComments();

        verify(taskStore).listByPhases(eq(TaskLifecycleDriver.LOCAL_SPINE), anyInt());
    }

    @Test
    void newLocalCommentAtAwaitingPushStartsTheLocalAddressLoop()
    {
        Task task = task(null, TaskPhase.AWAITING_PUSH);
        Instant commentAt = Instant.parse("2026-07-01T09:00:00Z");
        PR pr = pr("pr1", PR.STATUS_LOCAL_OPEN, null);
        when(prService.findByTask("t1.k2")).thenReturn(Optional.of(pr));
        when(prService.comments("pr1")).thenReturn(
                List.of(localComment("cm1", "you", "please fix this", commentAt, null, null)));
        Thread thread = mock(Thread.class);
        when(thread.id()).thenReturn("t1");
        when(thread.status()).thenReturn(ThreadStatus.IDLE);
        when(threadStore.findThreadById("t1")).thenReturn(Optional.of(thread));

        driver.reconcileLocalTask(task);

        verify(phaseMachine).observe("t1.k2", TaskPhase.ADDRESSING_LOCAL_COMMENTS, "new_local_comments");
        verify(prService).markLocalAddressed("pr1", commentAt);
        ArgumentCaptor<TurnInitiator> initiator = ArgumentCaptor.forClass(TurnInitiator.class);
        verify(scheduler).enqueueTaskTurn(eq(thread), anyString(), eq("t1.k2"), any(), initiator.capture());
        assertThat(initiator.getValue().attended()).isFalse();
        assertThat(initiator.getValue().source()).isEqualTo("address-local-comments");
    }

    @Test
    void alreadyMarkedLocalCommentsDoNotReTriggerFromAwaitingPush()
    {
        Task task = task(null, TaskPhase.AWAITING_PUSH);
        Instant commentAt = Instant.parse("2026-07-01T09:00:00Z");
        // The marker already covers this comment — surfaced (and presumably
        // enqueued) on a prior sweep.
        PR pr = pr("pr1", PR.STATUS_LOCAL_OPEN, commentAt);
        when(prService.findByTask("t1.k2")).thenReturn(Optional.of(pr));
        when(prService.comments("pr1")).thenReturn(
                List.of(localComment("cm1", "you", "please fix this", commentAt, null, null)));

        driver.reconcileLocalTask(task);

        verify(phaseMachine, never())
                .observe(anyString(), eq(TaskPhase.ADDRESSING_LOCAL_COMMENTS), anyString());
        verify(scheduler, never()).enqueueTaskTurn(any(), anyString(), anyString(), any(), any());
    }

    @Test
    void addressingLocalCommentsReturnsToAwaitingPushOnceEverythingIsResolved()
    {
        Task task = task(null, TaskPhase.ADDRESSING_LOCAL_COMMENTS);
        Instant commentAt = Instant.parse("2026-07-01T09:00:00Z");
        PR pr = pr("pr1", PR.STATUS_LOCAL_OPEN, commentAt);
        when(prService.findByTask("t1.k2")).thenReturn(Optional.of(pr));
        // Resolved — nothing left to address.
        when(prService.comments("pr1")).thenReturn(
                List.of(localComment("cm1", "you", "please fix this", commentAt, commentAt, null)));

        driver.reconcileLocalTask(task);

        verify(phaseMachine).observe("t1.k2", TaskPhase.AWAITING_PUSH, "local_comments_addressed");
        verify(scheduler, never()).enqueueTaskTurn(any(), anyString(), anyString(), any(), any());
    }

    @Test
    void addressingLocalCommentsRetriesWhenStillOpen()
    {
        Task task = task(null, TaskPhase.ADDRESSING_LOCAL_COMMENTS);
        Instant commentAt = Instant.parse("2026-07-01T09:00:00Z");
        PR pr = pr("pr1", PR.STATUS_LOCAL_OPEN, commentAt);
        when(prService.findByTask("t1.k2")).thenReturn(Optional.of(pr));
        when(prService.comments("pr1")).thenReturn(
                List.of(localComment("cm1", "you", "still open", commentAt, null, null)));
        Thread thread = mock(Thread.class);
        when(thread.id()).thenReturn("t1");
        when(thread.status()).thenReturn(ThreadStatus.IDLE);
        when(threadStore.findThreadById("t1")).thenReturn(Optional.of(thread));

        driver.reconcileLocalTask(task);

        // Retries the turn rather than declaring the round done.
        verify(phaseMachine, never()).observe("t1.k2", TaskPhase.AWAITING_PUSH, "local_comments_addressed");
        verify(scheduler).enqueueTaskTurn(eq(thread), anyString(), eq("t1.k2"), any(), any());
    }

    @Test
    void agentsOwnCommentDoesNotTriggerTheLocalAddressLoop()
    {
        Task task = task(null, TaskPhase.AWAITING_PUSH);
        Instant commentAt = Instant.parse("2026-07-01T09:00:00Z");
        PR pr = pr("pr1", PR.STATUS_LOCAL_OPEN, null);
        when(prService.findByTask("t1.k2")).thenReturn(Optional.of(pr));
        when(prService.comments("pr1")).thenReturn(List.of(
                localComment("cm1", PRTimelineEntry.ACTOR_AGENT, "reply", commentAt, null, null)));

        driver.reconcileLocalTask(task);

        verify(phaseMachine, never())
                .observe(anyString(), eq(TaskPhase.ADDRESSING_LOCAL_COMMENTS), anyString());
    }

    @Test
    void localReconcileSkipsATaskWithNoPr()
    {
        Task task = task(null, TaskPhase.AWAITING_PUSH);
        when(prService.findByTask("t1.k2")).thenReturn(Optional.empty());

        driver.reconcileLocalTask(task);

        verify(phaseMachine, never()).observe(anyString(), any(), anyString());
    }

    private static PR pr(String id, String status, Instant localAddressedThroughAt)
    {
        Instant now = Instant.parse("2026-06-01T00:00:00Z");
        return new PR(id, "t1.k2", "dev/x", "main", "T", "", status, now,
                null, null, null, null, null, localAddressedThroughAt,
                PR.ORIGIN_TASK, null, null, null, null, null);
    }

    private static PRComment localComment(
            String id, String author, String body, Instant createdAt, Instant resolvedAt, Instant dismissedAt)
    {
        return new PRComment(id, "pr1", PRComment.ORIGIN_LOCAL, PRComment.SCOPE_PR,
                null, null, author, body, createdAt, resolvedAt, dismissedAt, null, null, null,
                "RIGHT", null, null);
    }

    private static PullRequestDetail detail(CiStatus ci, boolean draft)
    {
        PullRequestDetail d = mock(PullRequestDetail.class);
        when(d.ciStatus()).thenReturn(ci);
        when(d.draft()).thenReturn(draft);
        return d;
    }

    private static Task task(String linkedPrRef, TaskPhase phase)
    {
        Instant now = Instant.ofEpochMilli(1_700_000_000_000L);
        return new Task("t1.k2", "t1", 2L, TaskStatus.IN_REVIEW, "dev/x", "/wt", "main", "/clone",
                null, null, null, null, null, "DEVELOP", null, null, 0L, 0L, 0L, null,
                now, null, null, null, null, null, null, phase, null, 0, linkedPrRef);
    }
}
