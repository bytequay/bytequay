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
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.pr.PullRequestService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
    private final TaskLifecycleDriver driver =
            new TaskLifecycleDriver(taskStore, pullRequests, phaseMachine, worktrees);

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
