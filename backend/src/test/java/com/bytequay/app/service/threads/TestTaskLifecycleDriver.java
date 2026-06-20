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
import com.bytequay.app.domain.PullRequestDetail.CiStatus;
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
import com.bytequay.app.service.stage.RemoteCommentIngestor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
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
    private final TaskReviewMarkerStore reviewMarkers = mock(TaskReviewMarkerStore.class);
    private final ThreadStore threadStore = mock(ThreadStore.class);
    private final ThreadTurnScheduler scheduler = mock(ThreadTurnScheduler.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final RemoteCommentIngestor commentIngestor = mock(RemoteCommentIngestor.class);
    private final TaskLifecycleDriver driver =
            new TaskLifecycleDriver(taskStore, pullRequests, phaseMachine, worktrees,
                    reviewMarkers, threadStore, scheduler, notifications, mapper, commentIngestor);

    @Test
    void greenDraftRecordsTheReadyGateAndAutonomouslyUnDraftsThePr()
    {
        Task task = task("trinodb/trino#29897", TaskPhase.PUSHED_AWAITING_CI);
        PullRequestDetail greenDraft = detail(CiStatus.PASSING, true);
        when(pullRequests.getPullRequestDetail("trinodb/trino", 29897)).thenReturn(greenDraft);

        driver.reconcileTask(task);

        // Went straight to the PR by number — not the cached summary — saw a
        // green draft, recorded the AWAITING_READY gate, and un-drafted the PR
        // (marked it ready for review) so the next sweep lands it at remote
        // review. Un-drafting on green is autonomous per the post-ship loop.
        verify(pullRequests).getPullRequestDetail("trinodb/trino", 29897);
        verify(phaseMachine).observe("t1.k2", TaskPhase.AWAITING_READY, "ci_green_on_draft");
        verify(pullRequests).setPullRequestDraft("trinodb/trino", 29897, false);
    }

    @Test
    void greenReadyPrAdvancesToRemoteReviewWithoutReUnDrafting()
    {
        Task task = task("trinodb/trino#29897", TaskPhase.AWAITING_READY);
        PullRequestDetail greenReady = detail(CiStatus.PASSING, false);
        when(pullRequests.getPullRequestDetail("trinodb/trino", 29897)).thenReturn(greenReady);

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
        when(pullRequests.getPullRequestDetail("trinodb/trino", 29897)).thenReturn(merged);

        driver.reconcileTask(task);

        // A remote merge fired no in-app merge event, so the driver itself
        // must finish the task: flip the runtime status, drain the phase
        // off the spine, and reap the now-dead worktree + branch.
        verify(taskStore).completeTask(eq("t1.k2"), any());
        verify(phaseMachine).observe("t1.k2", TaskPhase.COMPLETED, "pr_merged_observed");
        verify(worktrees).reap(task);
    }

    @Test
    void completesButDoesNotReapAClosedUnmergedPr()
    {
        Task task = task("trinodb/trino#29897", TaskPhase.AWAITING_REMOTE_REVIEW);
        PullRequestDetail closed = mock(PullRequestDetail.class);
        when(closed.merged()).thenReturn(false);
        when(closed.state()).thenReturn("closed");
        when(pullRequests.getPullRequestDetail("trinodb/trino", 29897)).thenReturn(closed);

        driver.reconcileTask(task);

        // A closed-unmerged PR is terminal too — complete the task — but
        // its branch may still hold unlanded local commits, so leave the
        // worktree alone rather than delete the work.
        verify(taskStore).completeTask(eq("t1.k2"), any());
        verify(phaseMachine).observe("t1.k2", TaskPhase.COMPLETED, "pr_closed_observed");
        verify(worktrees, never()).reap(any());
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
        verify(pullRequests, never()).getPullRequestDetail(any(), anyInt());
    }

    @Test
    void newReviewCommentsOnAReadyPrStartTheAddressLoop()
    {
        Task task = task("trinodb/trino#29897", TaskPhase.AWAITING_REMOTE_REVIEW);
        Instant commentAt = Instant.parse("2026-06-01T09:00:00Z");
        PullRequestDetail readyWithComments = detailWithThreads(
                CiStatus.PASSING, /* draft */ false,
                List.of(unresolvedThread("Foo.java", 10, "reviewer", "please fix this", commentAt)));
        when(pullRequests.getPullRequestDetail("trinodb/trino", 29897)).thenReturn(readyWithComments);
        Thread thread = mock(Thread.class);
        when(thread.id()).thenReturn("t1");
        when(thread.status()).thenReturn(ThreadStatus.IDLE);
        when(threadStore.findThreadById("t1")).thenReturn(Optional.of(thread));
        when(scheduler.enqueueTurn(any(), anyString(), any())).thenReturn("turn-id");

        driver.reconcileTask(task);

        // Fresh reviewer comments on a ready PR divert the task onto the
        // address-comments spine, advance the marker so they don't re-fire,
        // alert the user, and queue an analysis turn that presents a plan and
        // waits for approval.
        verify(phaseMachine).observe("t1.k2", TaskPhase.ADDRESSING_COMMENTS, "new_review_comments");
        verify(reviewMarkers).mark("t1.k2", commentAt);
        verify(notifications).notifyNeedsAttention(eq("t1"), eq("t1.k2"), anyString());
        ArgumentCaptor<TurnInitiator> initiator = ArgumentCaptor.forClass(TurnInitiator.class);
        verify(scheduler).enqueueTurn(eq(thread), anyString(), initiator.capture());
        assertThat(initiator.getValue().attended()).isFalse();
        assertThat(initiator.getValue().source()).isEqualTo("address-comments-analysis");
        // It does not also plain-advance to remote review — it diverted.
        verify(phaseMachine, never())
                .observe("t1.k2", TaskPhase.AWAITING_REMOTE_REVIEW, "pr_state_observed");
    }

    @Test
    void alreadyAddressedCommentsDoNotReTriggerTheAddressLoop()
    {
        Task task = task("trinodb/trino#29897", TaskPhase.AWAITING_REMOTE_REVIEW);
        Instant commentAt = Instant.parse("2026-06-01T09:00:00Z");
        PullRequestDetail readyWithComments = detailWithThreads(
                CiStatus.PASSING, false,
                List.of(unresolvedThread("Foo.java", 10, "reviewer", "please fix this", commentAt)));
        when(pullRequests.getPullRequestDetail("trinodb/trino", 29897)).thenReturn(readyWithComments);
        // The marker already covers this comment — we surfaced it last round.
        when(reviewMarkers.find("t1.k2")).thenReturn(Optional.of(commentAt));

        driver.reconcileTask(task);

        // No diversion: the PR just sits at remote review, no new analysis turn.
        verify(phaseMachine).observe("t1.k2", TaskPhase.AWAITING_REMOTE_REVIEW, "pr_state_observed");
        verify(phaseMachine, never())
                .observe(anyString(), eq(TaskPhase.ADDRESSING_COMMENTS), anyString());
        verify(scheduler, never()).enqueueTurn(any(), anyString(), any());
    }

    private static PullRequestDetail detail(CiStatus ci, boolean draft)
    {
        PullRequestDetail d = mock(PullRequestDetail.class);
        when(d.ciStatus()).thenReturn(ci);
        when(d.draft()).thenReturn(draft);
        return d;
    }

    private static PullRequestDetail detailWithThreads(
            CiStatus ci, boolean draft, List<ReviewThread> threads)
    {
        PullRequestDetail d = mock(PullRequestDetail.class);
        when(d.ciStatus()).thenReturn(ci);
        when(d.draft()).thenReturn(draft);
        when(d.reviewThreads()).thenReturn(threads);
        return d;
    }

    private static ReviewThread unresolvedThread(
            String file, int line, String author, String body, Instant at)
    {
        ReviewMessage msg = new ReviewMessage(1L, author, body, at, null, null, "COLLABORATOR");
        return new ReviewThread(1L, file, line, "RIGHT", null, List.of(msg),
                /* resolved */ false, /* outdated */ false, null, null, null, null);
    }

    private static Task task(String linkedPrRef, TaskPhase phase)
    {
        Instant now = Instant.ofEpochMilli(1_700_000_000_000L);
        return new Task("t1.k2", "t1", 2L, TaskStatus.IN_REVIEW, "dev/x", "/wt", "main", "/clone",
                null, null, null, null, null, "DEVELOP", null, null, 0L, 0L, 0L, null,
                now, null, null, null, null, null, null, phase, null, 0, linkedPrRef);
    }
}
