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
import com.bytequay.app.domain.PullRequestDetail.CiStatus;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ThreadTurnStatus;
import com.bytequay.app.domain.TurnInitiator;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
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
    private final ThreadTurnStore turnStore = mock(ThreadTurnStore.class);
    private final ThreadTurnScheduler scheduler = mock(ThreadTurnScheduler.class);
    private final NotificationService notifications = mock(NotificationService.class);
    private final RemoteCommentIngestor commentIngestor = mock(RemoteCommentIngestor.class);
    private final ReadyToMergeService readyToMerge = mock(ReadyToMergeService.class);
    private final ReviewRoundService reviewRounds = mock(ReviewRoundService.class);
    private final ThreadRegistry registry = mock(ThreadRegistry.class);
    private final StageStore stageStore = mock(StageStore.class);
    private final PRService prService = mock(PRService.class);
    private final TaskTerminalSealer sealer = mock(TaskTerminalSealer.class);
    private final ValidationPassService validation = mock(ValidationPassService.class);
    private final BrainReviewService brainReview = mock(BrainReviewService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final TaskLifecycleDriver driver =
            new TaskLifecycleDriver(taskStore, pullRequests, phaseMachine, worktrees,
                    threadStore, turnStore, scheduler, notifications,
                    commentIngestor, readyToMerge, reviewRounds, registry, stageStore, prService, sealer,
                    validation, brainReview, events);

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
    void greenDraftIsMarkedReadyAutomaticallyAndEntersRemoteReview()
    {
        Task task = task("trinodb/trino#29897", TaskPhase.PUSHED_AWAITING_CI);
        when(taskStore.findTaskById("t1.k2")).thenReturn(Optional.of(task));
        PullRequestDetail greenDraft = detail(CiStatus.PASSING, true);
        when(pullRequests.refreshPullRequestDetail("trinodb/trino", 29897)).thenReturn(greenDraft);
        PR remoteDraft = pr("pr1", PR.STATUS_REMOTE_DRAFTED, null);
        when(prService.findByTask("t1.k2")).thenReturn(Optional.of(remoteDraft));

        driver.reconcileTask(task);

        verify(pullRequests).setPullRequestDraft("trinodb/trino", 29897, false);
        verify(notifications).supersedeAwaitingReviewForTask("t1", "t1.k2");
        verify(prService).transition("pr1", PR.STATUS_REMOTE_OPEN, PRTimelineEntry.ACTOR_AGENT);
        verify(phaseMachine).observe(
                "t1.k2", TaskPhase.AWAITING_REMOTE_REVIEW, "ci_green_marked_ready");
        verify(reviewRounds, never()).reconcile(task);
    }

    @Test
    void greenDraftDoesNotAdvancePastAnInFlightLegacyGateResolution()
    {
        Task task = task("trinodb/trino#29897", TaskPhase.PUSHED_AWAITING_CI);
        when(taskStore.findTaskById("t1.k2")).thenReturn(Optional.of(task));
        PullRequestDetail greenDraft = detail(CiStatus.PASSING, true);
        when(pullRequests.refreshPullRequestDetail("trinodb/trino", 29897)).thenReturn(greenDraft);
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "resolution is already in progress"))
                .when(notifications).supersedeAwaitingReviewForTask("t1", "t1.k2");

        assertThatThrownBy(() -> driver.reconcileTask(task))
                .hasMessageContaining("resolution is already in progress");

        verify(pullRequests, never()).setPullRequestDraft("trinodb/trino", 29897, false);
        verify(prService, never()).transition(anyString(), anyString(), anyString());
        verify(phaseMachine, never()).observe(
                "t1.k2", TaskPhase.AWAITING_REMOTE_REVIEW, "ci_green_marked_ready");
    }

    @Test
    void aPreviouslyAwaitingReadyDraftAlsoAutoUndrafts()
    {
        Task task = task("trinodb/trino#29897", TaskPhase.AWAITING_READY)
                .withStatus(TaskStatus.AWAITING_REVIEW);
        when(taskStore.findTaskById("t1.k2")).thenReturn(Optional.of(task));
        PullRequestDetail greenDraft = detail(CiStatus.PASSING, true);
        when(pullRequests.refreshPullRequestDetail("trinodb/trino", 29897)).thenReturn(greenDraft);

        driver.reconcileTask(task);

        verify(pullRequests).setPullRequestDraft("trinodb/trino", 29897, false);
        ArgumentCaptor<Task> taskCaptor = ArgumentCaptor.forClass(Task.class);
        verify(taskStore).saveTask(taskCaptor.capture());
        assertThat(taskCaptor.getValue().status()).isEqualTo(TaskStatus.IN_REVIEW);
        verify(phaseMachine).observe(
                "t1.k2", TaskPhase.AWAITING_REMOTE_REVIEW, "ci_green_marked_ready");
    }

    @Test
    void greenReadyPrAdvancesToRemoteReviewWithoutReUnDrafting()
    {
        Task task = task("trinodb/trino#29897", TaskPhase.AWAITING_READY);
        when(taskStore.findTaskById("t1.k2")).thenReturn(Optional.of(task));
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
        // No merge event fired on this path, so the driver itself must clear
        // the task's open notifications (publish gates, budget-cap "needs you")
        // — otherwise a stale card lingers in the overview panel after merge.
        verify(notifications).dismissOpenForTask("t1", "t1.k2");
    }

    @Test
    void notificationCleanupFailureDoesNotBlockTerminalResourceCleanup()
    {
        Task task = task("trinodb/trino#29897", TaskPhase.AWAITING_REMOTE_REVIEW);
        PullRequestDetail merged = mock(PullRequestDetail.class);
        when(merged.merged()).thenReturn(true);
        when(pullRequests.refreshPullRequestDetail("trinodb/trino", 29897)).thenReturn(merged);
        doThrow(new RuntimeException("notification store unavailable"))
                .when(notifications).dismissOpenForTask("t1", "t1.k2");

        driver.reconcileTask(task);

        verify(worktrees).reap(task);
        verify(worktrees).deleteRemoteBranch(task);
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
        when(taskStore.findTaskById("t1.k2")).thenReturn(Optional.of(task));
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
    void mergeQueueParkIsNotOverwrittenByObservedRemoteReview()
    {
        Task task = task("trinodb/trino#29897", TaskPhase.AWAITING_REMOTE_REVIEW);
        Task parked = task("trinodb/trino#29897", TaskPhase.NEEDS_ATTENTION);
        PullRequestDetail ready = detail(CiStatus.PASSING, false);
        when(pullRequests.refreshPullRequestDetail("trinodb/trino", 29897)).thenReturn(ready);
        when(taskStore.findTaskById("t1.k2"))
                .thenReturn(Optional.of(task), Optional.of(parked));

        driver.reconcileTask(task);

        verify(readyToMerge).evaluate(task, ready);
        verify(phaseMachine, never()).observe(anyString(), any(), anyString());
        verify(reviewRounds, never()).reconcile(any());
    }

    @Test
    void staleRemoteSpineRowCannotMutateAParkedTask()
    {
        Task stale = task("trinodb/trino#29897", TaskPhase.PUSHED_AWAITING_CI);
        Task parked = stale.withStatus(TaskStatus.NEEDS_ATTENTION);
        PullRequestDetail greenDraft = detail(CiStatus.PASSING, true);
        when(pullRequests.refreshPullRequestDetail("trinodb/trino", 29897)).thenReturn(greenDraft);
        when(taskStore.findTaskById("t1.k2")).thenReturn(Optional.of(parked));

        driver.reconcileTask(stale);

        verify(readyToMerge, never()).evaluate(any(), any());
        verify(pullRequests, never()).setPullRequestDraft(any(), anyInt(), eq(false));
        verify(phaseMachine, never()).observe(anyString(), any(), anyString());
    }

    @Test
    void pausedRemoteReviewCannotLaunchANewReviewRound()
    {
        Task stale = task("trinodb/trino#29897", TaskPhase.AWAITING_REMOTE_REVIEW);
        Task paused = stale.withStatus(TaskStatus.PAUSED);
        PullRequestDetail ready = detail(CiStatus.PASSING, false);
        when(pullRequests.refreshPullRequestDetail("trinodb/trino", 29897)).thenReturn(ready);
        when(taskStore.findTaskById("t1.k2")).thenReturn(Optional.of(paused));

        driver.reconcileTask(stale);

        verify(readyToMerge, never()).evaluate(any(), any());
        verify(phaseMachine, never()).observe(anyString(), any(), anyString());
        verify(reviewRounds, never()).reconcile(any());
    }

    @Test
    void archivedRemoteReviewCannotLaunchANewReviewRound()
    {
        Task stale = task("trinodb/trino#29897", TaskPhase.AWAITING_REMOTE_REVIEW);
        Task archived = stale.withStatus(TaskStatus.ARCHIVED);
        PullRequestDetail ready = detail(CiStatus.PASSING, false);
        when(pullRequests.refreshPullRequestDetail("trinodb/trino", 29897)).thenReturn(ready);
        when(taskStore.findTaskById("t1.k2")).thenReturn(Optional.of(archived));

        driver.reconcileTask(stale);

        verify(readyToMerge, never()).evaluate(any(), any());
        verify(phaseMachine, never()).observe(anyString(), any(), anyString());
        verify(reviewRounds, never()).reconcile(any());
    }

    @Test
    void doesNotHandOffToTheRoundServiceOutsideRemoteReview()
    {
        Task task = task("trinodb/trino#29897", TaskPhase.PUSHED_AWAITING_CI);
        when(taskStore.findTaskById("t1.k2")).thenReturn(Optional.of(task));
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
    void dirtyPullRequestPublishesTheReactiveBranchGuardEvent()
    {
        Task task = task("trinodb/trino#29897", TaskPhase.AWAITING_REMOTE_REVIEW);
        PullRequestDetail dirty = mock(PullRequestDetail.class);
        when(dirty.mergeable()).thenReturn(false);
        when(dirty.mergeableState()).thenReturn("dirty");
        when(pullRequests.refreshPullRequestDetail("trinodb/trino", 29897)).thenReturn(dirty);

        driver.reconcileTask(task);

        verify(events).publishEvent(new PullRequestDirtyDetectedEvent("t1.k2"));
    }

    @Test
    void cleanPullRequestNeverPublishesTheReactiveBranchGuardEvent()
    {
        Task task = task("trinodb/trino#29897", TaskPhase.AWAITING_REMOTE_REVIEW);
        PullRequestDetail clean = mock(PullRequestDetail.class);
        when(clean.mergeable()).thenReturn(true);
        when(clean.mergeableState()).thenReturn("clean");
        when(pullRequests.refreshPullRequestDetail("trinodb/trino", 29897)).thenReturn(clean);

        driver.reconcileTask(task);

        verify(events, never()).publishEvent(any());
    }

    @Test
    void unknownMergeableStateDoesNotPublishTheReactiveBranchGuardEvent()
    {
        Task task = task("trinodb/trino#29897", TaskPhase.AWAITING_REMOTE_REVIEW);
        PullRequestDetail unknown = mock(PullRequestDetail.class);
        when(unknown.mergeable()).thenReturn(null);
        when(unknown.mergeableState()).thenReturn("unknown");
        when(pullRequests.refreshPullRequestDetail("trinodb/trino", 29897)).thenReturn(unknown);

        driver.reconcileTask(task);

        verify(events, never()).publishEvent(any());
    }

    @Test
    void scansOnlyTheLocalSpine()
    {
        when(taskStore.listByPhases(any(), anyInt())).thenReturn(List.of());

        driver.reconcileLocalComments();

        verify(taskStore).listByPhases(eq(TaskLifecycleDriver.LOCAL_SPINE), anyInt());
    }

    @Test
    void submittedLocalCommentAtAwaitingPushStartsTheLocalAddressLoop()
    {
        Task task = task(null, TaskPhase.AWAITING_PUSH);
        Instant commentAt = Instant.parse("2026-07-01T09:00:00Z");
        Instant submittedAt = commentAt.plusSeconds(30);
        PR pr = pr("pr1", PR.STATUS_LOCAL_OPEN, null);
        when(prService.findByTask("t1.k2")).thenReturn(Optional.of(pr));
        when(prService.comments("pr1")).thenReturn(
                List.of(localComment("cm1", "you", "please fix this", commentAt, null, null)));
        when(prService.localReviewSubmissions("pr1")).thenReturn(
                List.of(submission(submittedAt, "cm1")));
        when(taskStore.findTaskById("t1.k2")).thenReturn(Optional.of(task));
        Thread thread = mock(Thread.class);
        when(thread.id()).thenReturn("t1");
        when(thread.status()).thenReturn(ThreadStatus.IDLE);
        when(threadStore.findThreadById("t1")).thenReturn(Optional.of(thread));

        driver.reconcileLocalTask(task);

        verify(phaseMachine).transition(
                "t1.k2", TaskPhase.ADDRESSING_LOCAL_COMMENTS, "new_local_comments", Actor.AGENT);
        verify(prService).markLocalAddressed("pr1", submittedAt);
        ArgumentCaptor<TurnInitiator> initiator = ArgumentCaptor.forClass(TurnInitiator.class);
        verify(scheduler).enqueueTaskTurn(eq(thread), anyString(), eq("t1.k2"), any(), initiator.capture());
        assertThat(initiator.getValue().attended()).isFalse();
        assertThat(initiator.getValue().source()).isEqualTo("address-local-comments");
    }

    @Test
    void pendingLocalCommentDoesNotDispatchUntilSubmitted()
    {
        Task task = task(null, TaskPhase.AWAITING_PUSH);
        PR pr = pr("pr1", PR.STATUS_LOCAL_OPEN, null);
        when(prService.findByTask("t1.k2")).thenReturn(Optional.of(pr));
        when(prService.comments("pr1")).thenReturn(List.of(localComment(
                "cm1", "you", "still drafting", Instant.parse("2026-07-01T09:00:00Z"), null, null)));
        when(prService.localReviewSubmissions("pr1")).thenReturn(List.of());

        driver.reconcileLocalTask(task);

        verify(phaseMachine, never()).transition(
                eq("t1.k2"), eq(TaskPhase.ADDRESSING_LOCAL_COMMENTS), anyString(), any());
        verify(scheduler, never()).enqueueTaskTurn(any(), anyString(), anyString(), any(), any());
    }

    @Test
    void invalidatedHistoricalSubmissionDoesNotDispatchAgain()
    {
        Task task = task(null, TaskPhase.AWAITING_PUSH);
        Instant submittedAt = Instant.parse("2026-07-01T09:00:30Z");
        PR pr = pr("pr1", PR.STATUS_LOCAL_OPEN, null);
        when(prService.findByTask("t1.k2")).thenReturn(Optional.of(pr));
        when(prService.comments("pr1")).thenReturn(List.of(localComment(
                "cm1", "you", "clarification pending resubmit",
                submittedAt.minusSeconds(30), null, null)));
        when(prService.localReviewSubmissions("pr1")).thenReturn(
                List.of(submission(submittedAt)));

        driver.reconcileLocalTask(task);

        verify(phaseMachine, never()).transition(
                eq("t1.k2"), eq(TaskPhase.ADDRESSING_LOCAL_COMMENTS), anyString(), any());
        verify(scheduler, never()).enqueueTaskTurn(any(), anyString(), anyString(), any(), any());
    }

    @Test
    void submissionEventDispatchesDevelopmentImmediately()
    {
        Task task = task(null, TaskPhase.AWAITING_PUSH);
        Instant commentAt = Instant.parse("2026-07-01T09:00:00Z");
        Instant submittedAt = commentAt.plusSeconds(30);
        PR pr = pr("pr1", PR.STATUS_LOCAL_OPEN, null);
        when(taskStore.findTaskById("t1.k2")).thenReturn(Optional.of(task));
        when(prService.findByTask("t1.k2")).thenReturn(Optional.of(pr));
        when(prService.comments("pr1")).thenReturn(
                List.of(localComment("cm1", "you", "please fix this", commentAt, null, null)));
        when(prService.localReviewSubmissions("pr1")).thenReturn(
                List.of(submission(submittedAt, "cm1")));
        Thread thread = mock(Thread.class);
        when(thread.id()).thenReturn("t1");
        when(thread.status()).thenReturn(ThreadStatus.IDLE);
        when(threadStore.findThreadById("t1")).thenReturn(Optional.of(thread));

        driver.onLocalReviewSubmitted(new LocalReviewSubmittedEvent("t1.k2", "pr1"));

        verify(scheduler).enqueueTaskTurn(eq(thread), anyString(), eq("t1.k2"), any(), any());
    }

    @Test
    void submissionTimeRatherThanDraftCreationTimeDrivesTheMarker()
    {
        Task task = task(null, TaskPhase.AWAITING_PUSH);
        Instant commentAt = Instant.parse("2026-07-01T09:00:00Z");
        Instant oldMarker = commentAt.plusSeconds(10);
        Instant submittedAt = commentAt.plusSeconds(20);
        PR pr = pr("pr1", PR.STATUS_LOCAL_OPEN, oldMarker);
        when(prService.findByTask("t1.k2")).thenReturn(Optional.of(pr));
        when(prService.comments("pr1")).thenReturn(
                List.of(localComment("cm1", "you", "please fix this", commentAt, null, null)));
        when(prService.localReviewSubmissions("pr1")).thenReturn(
                List.of(submission(submittedAt, "cm1")));
        when(taskStore.findTaskById("t1.k2")).thenReturn(Optional.of(task));
        Thread thread = mock(Thread.class);
        when(thread.id()).thenReturn("t1");
        when(thread.status()).thenReturn(ThreadStatus.IDLE);
        when(threadStore.findThreadById("t1")).thenReturn(Optional.of(thread));

        driver.reconcileLocalTask(task);

        verify(prService).markLocalAddressed("pr1", submittedAt);
        verify(scheduler).enqueueTaskTurn(eq(thread), anyString(), eq("t1.k2"), any(), any());
    }

    @Test
    void explicitlyResubmittedRootDispatchesItsCurrentReplies()
    {
        Task task = task(null, TaskPhase.AWAITING_PUSH);
        Instant commentAt = Instant.parse("2026-07-01T09:00:00Z");
        Instant firstSubmission = commentAt.plusSeconds(10);
        Instant secondSubmission = commentAt.plusSeconds(30);
        PR pr = pr("pr1", PR.STATUS_LOCAL_OPEN, firstSubmission);
        PRComment root = localComment(
                "cm1", PRTimelineEntry.ACTOR_USER, "Please fix this", commentAt, null, null);
        PRComment reply = new PRComment(
                "reply-1", "pr1", PRComment.ORIGIN_LOCAL, PRComment.SCOPE_PR,
                null, null, PRTimelineEntry.ACTOR_USER, "One more constraint",
                commentAt.plusSeconds(20), null, null, null, "cm1", null,
                "RIGHT", null, null);
        when(prService.findByTask("t1.k2")).thenReturn(Optional.of(pr));
        when(prService.comments("pr1")).thenReturn(List.of(root, reply));
        when(prService.localReviewSubmissions("pr1")).thenReturn(List.of(
                submission(firstSubmission, "cm1"),
                submission(secondSubmission, "cm1")));
        when(taskStore.findTaskById("t1.k2")).thenReturn(Optional.of(task));
        Thread thread = mock(Thread.class);
        when(thread.id()).thenReturn("t1");
        when(thread.status()).thenReturn(ThreadStatus.IDLE);
        when(threadStore.findThreadById("t1")).thenReturn(Optional.of(thread));
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);

        driver.reconcileLocalTask(task);

        verify(prService).markLocalAddressed("pr1", secondSubmission);
        verify(scheduler).enqueueTaskTurn(eq(thread), prompt.capture(), eq("t1.k2"), any(), any());
        assertThat(prompt.getValue())
                .contains("@you: Please fix this")
                .contains("Reply @you: One more constraint");
    }

    @Test
    void submittedAgentFindingIsDispatchedToDevelopment()
    {
        Task task = task(null, TaskPhase.AWAITING_PUSH);
        Instant submittedAt = Instant.parse("2026-07-01T09:00:30Z");
        PR pr = pr("pr1", PR.STATUS_LOCAL_OPEN, null);
        PRComment finding = new PRComment(
                "finding-comment", "pr1", PRComment.ORIGIN_LOCAL, PRComment.SCOPE_FILE_LINE,
                "src/Foo.java", 9, "agent", "Possible null dereference", submittedAt.minusSeconds(30),
                null, null, null, null, null, "RIGHT", null, null, "finding-1");
        when(prService.findByTask("t1.k2")).thenReturn(Optional.of(pr));
        when(prService.comments("pr1")).thenReturn(List.of(finding));
        when(prService.localReviewSubmissions("pr1")).thenReturn(
                List.of(submission(submittedAt, "finding-comment")));
        when(taskStore.findTaskById("t1.k2")).thenReturn(Optional.of(task));
        Thread thread = mock(Thread.class);
        when(thread.id()).thenReturn("t1");
        when(thread.status()).thenReturn(ThreadStatus.IDLE);
        when(threadStore.findThreadById("t1")).thenReturn(Optional.of(thread));
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);

        driver.reconcileLocalTask(task);

        verify(scheduler).enqueueTaskTurn(eq(thread), prompt.capture(), eq("t1.k2"), any(), any());
        assertThat(prompt.getValue()).contains("finding-comment", "src/Foo.java:9", "Possible null dereference");
    }

    @Test
    void staleLocalCommentSnapshotCannotRegressARemoteTask()
    {
        Task stale = task(null, TaskPhase.AWAITING_PUSH);
        Task pushed = task("trinodb/trino#29897", TaskPhase.PUSHED_AWAITING_CI);
        Instant commentAt = Instant.parse("2026-07-01T09:00:00Z");
        PR pr = pr("pr1", PR.STATUS_LOCAL_OPEN, null);
        when(prService.findByTask("t1.k2")).thenReturn(Optional.of(pr));
        when(prService.comments("pr1")).thenReturn(
                List.of(localComment("cm1", "you", "please fix this", commentAt, null, null)));
        when(prService.localReviewSubmissions("pr1")).thenReturn(
                List.of(submission(commentAt, "cm1")));
        when(taskStore.findTaskById("t1.k2")).thenReturn(Optional.of(pushed));

        driver.reconcileLocalTask(stale);

        verify(phaseMachine, never()).transition(
                eq("t1.k2"), eq(TaskPhase.ADDRESSING_LOCAL_COMMENTS), anyString(), any());
        verify(prService, never()).markLocalAddressed(anyString(), any());
        verify(scheduler, never()).enqueueTaskTurn(any(), anyString(), anyString(), any(), any());
    }

    @Test
    void staleLocalCommentSnapshotCannotDispatchAfterTheTaskWasPaused()
    {
        Task stale = task(null, TaskPhase.ADDRESSING_LOCAL_COMMENTS);
        Task paused = stale.withStatus(TaskStatus.PAUSED);
        Instant commentAt = Instant.parse("2026-07-01T09:00:00Z");
        PR pr = pr("pr1", PR.STATUS_LOCAL_OPEN, commentAt);
        when(prService.findByTask("t1.k2")).thenReturn(Optional.of(pr));
        when(prService.comments("pr1")).thenReturn(
                List.of(localComment("cm1", "you", "still open", commentAt, null, null)));
        when(prService.localReviewSubmissions("pr1")).thenReturn(
                List.of(submission(commentAt, "cm1")));
        when(taskStore.findTaskById("t1.k2")).thenReturn(Optional.of(paused));

        driver.reconcileLocalTask(stale);

        verify(phaseMachine, never()).transition(anyString(), any(), anyString(), any());
        verify(prService, never()).markLocalAddressed(anyString(), any());
        verify(scheduler, never()).enqueueTaskTurn(any(), anyString(), anyString(), any(), any());
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
        when(prService.localReviewSubmissions("pr1")).thenReturn(
                List.of(submission(commentAt, "cm1")));

        driver.reconcileLocalTask(task);

        verify(phaseMachine, never())
                .observe(anyString(), eq(TaskPhase.ADDRESSING_LOCAL_COMMENTS), anyString());
        verify(scheduler, never()).enqueueTaskTurn(any(), anyString(), anyString(), any(), any());
    }

    @Test
    void addressingLocalCommentsStartsFreshBrainReviewOnceEverythingIsResolved()
    {
        Task task = task(null, TaskPhase.ADDRESSING_LOCAL_COMMENTS);
        Instant commentAt = Instant.parse("2026-07-01T09:00:00Z");
        PR pr = pr("pr1", PR.STATUS_LOCAL_OPEN, commentAt);
        when(prService.findByTask("t1.k2")).thenReturn(Optional.of(pr));
        // Resolved — nothing left to address.
        when(prService.comments("pr1")).thenReturn(
                List.of(localComment("cm1", "you", "please fix this", commentAt, commentAt, null)));
        when(taskStore.findTaskById("t1.k2")).thenReturn(Optional.of(task));
        Thread thread = mock(Thread.class);
        when(thread.status()).thenReturn(ThreadStatus.IDLE);
        when(threadStore.findThreadById("t1")).thenReturn(Optional.of(thread));
        when(validation.run("t1.k2")).thenReturn(new ValidationPassResult(true, 0, List.of()));

        driver.reconcileLocalTask(task);

        verify(phaseMachine).transition(
                "t1.k2", TaskPhase.INTERNAL_REVIEW, "local_comments_validated",
                Actor.AGENT);
        verify(brainReview).reviewAfterLocalComments("pr1");
        verify(scheduler, never()).enqueueTaskTurn(any(), anyString(), anyString(), any(), any());
    }

    @Test
    void initialInternalReviewDoesNotStartAPostAddressingBrainRound()
    {
        Task task = task(null, TaskPhase.INTERNAL_REVIEW);
        when(prService.findByTask("t1.k2"))
                .thenReturn(Optional.of(pr("pr1", PR.STATUS_LOCAL_OPEN, null)));

        driver.reconcileLocalTask(task);

        verify(brainReview, never()).reviewAfterLocalComments(anyString());
    }

    @Test
    void addressingLocalCommentsDoesNotReopenLocalReviewWhenValidationFails()
    {
        Task task = task(null, TaskPhase.ADDRESSING_LOCAL_COMMENTS);
        Instant commentAt = Instant.parse("2026-07-01T09:00:00Z");
        when(prService.findByTask("t1.k2"))
                .thenReturn(Optional.of(pr("pr1", PR.STATUS_LOCAL_OPEN, commentAt)));
        when(prService.comments("pr1")).thenReturn(List.of());
        when(taskStore.findTaskById("t1.k2")).thenReturn(Optional.of(task));
        Thread thread = mock(Thread.class);
        when(thread.status()).thenReturn(ThreadStatus.IDLE);
        when(threadStore.findThreadById("t1")).thenReturn(Optional.of(thread));
        when(validation.run("t1.k2")).thenReturn(new ValidationPassResult(false, 3, List.of()));

        driver.reconcileLocalTask(task);

        verify(validation).run("t1.k2");
        verify(phaseMachine).transition(
                "t1.k2", TaskPhase.NEEDS_ATTENTION, "local_comments_validation_failed", Actor.AGENT);
        verify(phaseMachine, never()).transition(
                eq("t1.k2"), eq(TaskPhase.AWAITING_PUSH), anyString(), any());
    }

    @Test
    void addressingLocalCommentsWaitsForTheFixTurnBeforeValidating()
    {
        Task task = task(null, TaskPhase.ADDRESSING_LOCAL_COMMENTS);
        when(prService.findByTask("t1.k2"))
                .thenReturn(Optional.of(pr("pr1", PR.STATUS_LOCAL_OPEN, Instant.now())));
        when(prService.comments("pr1")).thenReturn(List.of());
        when(taskStore.findTaskById("t1.k2")).thenReturn(Optional.of(task));
        Thread thread = mock(Thread.class);
        when(thread.status()).thenReturn(ThreadStatus.RUNNING);
        when(threadStore.findThreadById("t1")).thenReturn(Optional.of(thread));

        driver.reconcileLocalTask(task);

        verify(validation, never()).run(anyString());
        verify(phaseMachine, never()).transition(anyString(), any(), anyString(), any());
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
        when(prService.localReviewSubmissions("pr1")).thenReturn(
                List.of(submission(commentAt, "cm1")));
        when(taskStore.findTaskById("t1.k2")).thenReturn(Optional.of(task));
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
    void queuedAddressingTurnPreventsADuplicateEnqueue()
    {
        Task task = task(null, TaskPhase.ADDRESSING_LOCAL_COMMENTS);
        Instant submittedAt = Instant.parse("2026-07-01T09:00:00Z");
        when(prService.findByTask("t1.k2"))
                .thenReturn(Optional.of(pr("pr1", PR.STATUS_LOCAL_OPEN, submittedAt)));
        when(prService.comments("pr1")).thenReturn(List.of(
                localComment("cm1", "you", "still open", submittedAt, null, null)));
        when(prService.localReviewSubmissions("pr1")).thenReturn(
                List.of(submission(submittedAt, "cm1")));
        when(taskStore.findTaskById("t1.k2")).thenReturn(Optional.of(task));
        Thread thread = mock(Thread.class);
        when(thread.status()).thenReturn(ThreadStatus.IDLE);
        when(threadStore.findThreadById("t1")).thenReturn(Optional.of(thread));
        ThreadTurn queued = addressingTurn("t1.k2");
        when(turnStore.listTurnsByTaskIdAndStatus(
                eq("t1"), eq(ThreadTurnStatus.QUEUED), anyInt()))
                .thenReturn(List.of(queued));

        driver.reconcileLocalTask(task);

        verify(scheduler, never()).enqueueTaskTurn(any(), anyString(), anyString(), any(), any());
        verify(prService, never()).markLocalAddressed(anyString(), any());
    }

    @Test
    void queuedAddressingTurnPreventsValidationAndBrainReview()
    {
        Task task = task(null, TaskPhase.ADDRESSING_LOCAL_COMMENTS);
        Instant submittedAt = Instant.parse("2026-07-01T09:00:00Z");
        when(prService.findByTask("t1.k2"))
                .thenReturn(Optional.of(pr("pr1", PR.STATUS_LOCAL_OPEN, submittedAt)));
        when(prService.comments("pr1")).thenReturn(List.of());
        when(taskStore.findTaskById("t1.k2")).thenReturn(Optional.of(task));
        Thread thread = mock(Thread.class);
        when(thread.status()).thenReturn(ThreadStatus.IDLE);
        when(threadStore.findThreadById("t1")).thenReturn(Optional.of(thread));
        ThreadTurn queued = addressingTurn("t1.k2");
        when(turnStore.listTurnsByTaskIdAndStatus(
                eq("t1"), eq(ThreadTurnStatus.QUEUED), anyInt()))
                .thenReturn(List.of(queued));

        driver.reconcileLocalTask(task);

        verify(validation, never()).run(anyString());
        verify(brainReview, never()).reviewAfterLocalComments(anyString());
        verify(phaseMachine, never()).transition(anyString(), any(), anyString(), any());
    }

    @Test
    void addressingDoesNotPullAnOlderUnsubmittedDraftIntoARealBatch()
    {
        Task task = task(null, TaskPhase.ADDRESSING_LOCAL_COMMENTS);
        Instant draftAt = Instant.parse("2026-07-01T08:59:00Z");
        Instant submittedAt = Instant.parse("2026-07-01T09:00:00Z");
        PR pr = pr("pr1", PR.STATUS_LOCAL_OPEN, submittedAt);
        when(prService.findByTask("t1.k2")).thenReturn(Optional.of(pr));
        when(prService.comments("pr1")).thenReturn(List.of(
                localComment("draft", "you", "do not send yet", draftAt, null, null),
                localComment("selected", "you", "please fix this", draftAt, null, null)));
        when(prService.localReviewSubmissions("pr1")).thenReturn(
                List.of(submission(submittedAt, "selected")));
        when(taskStore.findTaskById("t1.k2")).thenReturn(Optional.of(task));
        Thread thread = mock(Thread.class);
        when(thread.id()).thenReturn("t1");
        when(thread.status()).thenReturn(ThreadStatus.IDLE);
        when(threadStore.findThreadById("t1")).thenReturn(Optional.of(thread));
        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);

        driver.reconcileLocalTask(task);

        verify(scheduler).enqueueTaskTurn(eq(thread), prompt.capture(), eq("t1.k2"), any(), any());
        assertThat(prompt.getValue())
                .contains("[id: selected]", "please fix this")
                .doesNotContain("[id: draft]", "do not send yet");
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
    void brainFindingsStayOwnedByTheBoundedBrainReviewLoop()
    {
        Task task = task(null, TaskPhase.AWAITING_PUSH);
        Instant commentAt = Instant.parse("2026-07-01T09:00:00Z");
        PR pr = pr("pr1", PR.STATUS_LOCAL_OPEN, null);
        when(prService.findByTask("t1.k2")).thenReturn(Optional.of(pr));
        when(prService.comments("pr1")).thenReturn(List.of(
                localComment("cm1", PRTimelineEntry.ACTOR_BRAIN, "still unresolved", commentAt, null, null)));

        driver.reconcileLocalTask(task);

        verify(phaseMachine, never())
                .observe(anyString(), eq(TaskPhase.ADDRESSING_LOCAL_COMMENTS), anyString());
        verify(scheduler, never()).enqueueTaskTurn(any(), anyString(), anyString(), any(), any());
    }

    @Test
    void needsAttentionTaskNeverRetriesLocalCommentAddressing()
    {
        Task task = task(null, TaskPhase.ADDRESSING_LOCAL_COMMENTS)
                .withStatus(TaskStatus.NEEDS_ATTENTION);

        driver.reconcileLocalTask(task);

        verify(prService, never()).findByTask(anyString());
        verify(scheduler, never()).enqueueTaskTurn(any(), anyString(), anyString(), any(), any());
    }

    @Test
    void legacyAwaitingReviewStatusDoesNotBlockCanonicalLocalAddressing()
    {
        Task task = task(null, TaskPhase.ADDRESSING_LOCAL_COMMENTS)
                .withStatus(TaskStatus.AWAITING_REVIEW);
        Instant commentAt = Instant.parse("2026-07-01T09:00:00Z");
        when(prService.findByTask("t1.k2"))
                .thenReturn(Optional.of(pr("pr1", PR.STATUS_LOCAL_OPEN, commentAt)));
        when(prService.comments("pr1")).thenReturn(
                List.of(localComment("cm1", "you", "still open", commentAt, null, null)));
        when(taskStore.findTaskById("t1.k2")).thenReturn(Optional.of(task));
        Thread thread = mock(Thread.class);
        when(thread.id()).thenReturn("t1");
        when(thread.status()).thenReturn(ThreadStatus.IDLE);
        when(threadStore.findThreadById("t1")).thenReturn(Optional.of(thread));

        driver.reconcileLocalTask(task);

        verify(scheduler).enqueueTaskTurn(eq(thread), anyString(), eq("t1.k2"), any(), any());
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

    private static PRService.LocalReviewSubmission submission(Instant submittedAt, String... commentIds)
    {
        return new PRService.LocalReviewSubmission(submittedAt, List.of(commentIds), "", "COMMENT", null);
    }

    private static ThreadTurn addressingTurn(String taskId)
    {
        ThreadTurn turn = mock(ThreadTurn.class);
        when(turn.taskId()).thenReturn(taskId);
        when(turn.initiator()).thenReturn(TurnInitiator.unattended("address-local-comments"));
        return turn;
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
