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
package com.bytequay.app.service.localpr;

import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRCommit;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.PullRequestDetail.ActivityItem;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.review.BrainReviewService;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coverage for materialising a task's local PR from git: it creates the row,
 * appends unseen branch commits oldest-first (deduping ones already recorded),
 * and flips to {@code local-open} once the task is awaiting review.
 */
class TestPRSyncService
{
    private static final Instant NOW = Instant.parse("2026-07-01T00:00:00Z");

    private final PRService prService = mock(PRService.class);
    private final TaskStore taskStore = mock(TaskStore.class);
    private final GitRunner git = mock(GitRunner.class);
    private final BrainReviewService brainReview = mock(BrainReviewService.class);
    private final PullRequestService pullRequests = mock(PullRequestService.class);
    private final PRPublishService prPublish = mock(PRPublishService.class);
    private final PRSyncService service =
            new PRSyncService(prService, taskStore, git, brainReview, pullRequests, prPublish);

    private Task task(TaskPhase phase)
    {
        return task(phase, null);
    }

    private Task task(TaskPhase phase, String linkedPrRef)
    {
        return new Task(
                "task1", "thread-1", 1L, TaskStatus.RUNNING,
                "feature/x", "/tmp/wt/feature-x", "main", "/tmp/repo",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, NOW, null, null, "T", null, null, null, phase, null, 0, linkedPrRef);
    }

    private PR draftPr()
    {
        return PR.create("pr1", "task1", "feature/x", "main", "T", "", NOW);
    }

    private PR pushedPr()
    {
        return draftPr().withRemote(42, "https://github.com/acme/widget/pull/42", NOW)
                .withStatus(PR.STATUS_REMOTE_DRAFTED, NOW);
    }

    private static ActivityItem commented(long githubId, String actor, String body)
    {
        return new ActivityItem(actor, "commented", NOW, body, null, null, null, null, null, "MEMBER", githubId, null);
    }

    private static ActivityItem reviewed(long githubId, String actor, String state)
    {
        return reviewed(githubId, actor, state, null);
    }

    private static ActivityItem reviewed(long githubId, String actor, String state, String body)
    {
        return new ActivityItem(actor, "reviewed", NOW, body, state, null, null, null, null, "MEMBER", githubId, null);
    }

    private static PullRequestDetail detailWithActivity(List<ActivityItem> activity)
    {
        PullRequestDetail detail = mock(PullRequestDetail.class);
        when(detail.recentActivity()).thenReturn(activity);
        return detail;
    }

    private static GitRunner.CommitEntry commit(String shortSha, String subject)
    {
        return new GitRunner.CommitEntry(
                shortSha + "full", shortSha, "you", "you@example.com", "2026-07-01T00:00:00Z", subject);
    }

    /** Stub every commit's numstat to a fixed +10 −2 delta. */
    private void stubDelta()
            throws Exception
    {
        when(git.commitFiles(any(), any()))
                .thenReturn(List.of(new GitRunner.CommitFileChange("f.java", "M", 10, 2)));
    }

    @Test
    void createsThePrAndRecordsBranchCommitsOldestFirst()
            throws Exception
    {
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(task(TaskPhase.IMPLEMENTING)));
        when(prService.createForTask("task1", "feature/x", "main", "T", "")).thenReturn(draftPr());
        when(prService.commits("pr1")).thenReturn(List.of());
        when(prService.findById("pr1")).thenReturn(Optional.of(draftPr()));
        when(git.resolveCommitBase(any(), eq("main"))).thenReturn("main");
        // git log is newest-first.
        when(git.listCommitsAhead(any(), eq("main"), eq(200)))
                .thenReturn(List.of(commit("ccc", "third"), commit("bbb", "second"), commit("aaa", "first")));
        stubDelta();

        service.syncFromTask("task1");

        // Recorded oldest-first: aaa, bbb, ccc — each with its summed numstat delta.
        var order = inOrder(prService);
        order.verify(prService).recordCommit(eq("pr1"), eq("aaa"), eq("first"), eq(10), eq(2), any());
        order.verify(prService).recordCommit(eq("pr1"), eq("bbb"), eq("second"), eq(10), eq(2), any());
        order.verify(prService).recordCommit(eq("pr1"), eq("ccc"), eq("third"), eq(10), eq(2), any());
    }

    @Test
    void listsCommitsAgainstTheResolvedBaseNotTheRawConfiguredName()
            throws Exception
    {
        // A worktree whose local "main" ref never fast-forwarded while
        // "origin/main" moved on (e.g. another parallel task's work merged
        // upstream) resolves to the tighter origin/main-based merge-base —
        // excluding a commit that's already reachable from origin/main even
        // though it'd show as "ahead" of the stale local main.
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(task(TaskPhase.IMPLEMENTING)));
        when(prService.createForTask("task1", "feature/x", "main", "T", "")).thenReturn(draftPr());
        when(prService.commits("pr1")).thenReturn(List.of());
        when(prService.findById("pr1")).thenReturn(Optional.of(draftPr()));
        when(git.resolveCommitBase(any(), eq("main"))).thenReturn("origin/main");
        when(git.listCommitsAhead(any(), eq("origin/main"), eq(200)))
                .thenReturn(List.of(commit("f121bf0", "the task's own work")));
        stubDelta();

        service.syncFromTask("task1");

        verify(prService).recordCommit(eq("pr1"), eq("f121bf0"), eq("the task's own work"), eq(10), eq(2), any());
        // Never asked git for the raw "main" name — only the resolved base.
        verify(git, never()).listCommitsAhead(any(), eq("main"), anyInt());
    }

    @Test
    void skipsCommitsAlreadyRecorded()
            throws Exception
    {
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(task(TaskPhase.IMPLEMENTING)));
        when(prService.createForTask(any(), any(), any(), any(), any())).thenReturn(draftPr());
        when(prService.findById("pr1")).thenReturn(Optional.of(draftPr()));
        when(prService.commits("pr1")).thenReturn(List.of(new PRCommit(
                "id-aaa", "pr1", "aaa", "first", 0, 0, NOW, null)));
        when(git.resolveCommitBase(any(), eq("main"))).thenReturn("main");
        when(git.listCommitsAhead(any(), eq("main"), eq(200)))
                .thenReturn(List.of(commit("bbb", "second"), commit("aaa", "first")));
        stubDelta();

        service.syncFromTask("task1");

        verify(prService).recordCommit(eq("pr1"), eq("bbb"), eq("second"), eq(10), eq(2), any());
        verify(prService, never()).recordCommit(eq("pr1"), eq("aaa"), any(), anyInt(), anyInt(), any());
    }

    @Test
    void returnsEmptyWhenTheTaskHasNoBranch()
    {
        Task noBranch = new Task(
                "task1", "thread-1", 1L, TaskStatus.RUNNING,
                null, null, "main", "/tmp/repo",
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, NOW, null, null, null, null, null, null, TaskPhase.QUEUED, null, 0, null);
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(noBranch));

        assertThat(service.syncFromTask("task1")).isEmpty();
        verify(prService, never()).createForTask(any(), any(), any(), any(), any());
    }

    @Test
    void flipsToLocalOpenOnceAwaitingReview()
            throws Exception
    {
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(task(TaskPhase.AWAITING_PUSH)));
        when(prService.createForTask(any(), any(), any(), any(), any())).thenReturn(draftPr());
        when(prService.commits("pr1")).thenReturn(List.of());
        when(git.resolveCommitBase(any(), any())).thenReturn("main");
        when(git.listCommitsAhead(any(), any(), anyInt())).thenReturn(List.of());
        when(prService.findById("pr1")).thenReturn(Optional.of(draftPr()));

        service.syncFromTask("task1");

        verify(brainReview).reviewBeforeLocalOpen(eq("pr1"), any());
    }

    @Test
    void syncsRemoteCommentsAndReviewsOntoTheTimelineOncePushed()
            throws Exception
    {
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(task(TaskPhase.PUSHED_AWAITING_CI)));
        when(prService.createForTask(any(), any(), any(), any(), any())).thenReturn(pushedPr());
        when(prService.commits("pr1")).thenReturn(List.of());
        when(git.resolveCommitBase(any(), any())).thenReturn("main");
        when(git.listCommitsAhead(any(), any(), anyInt())).thenReturn(List.of());
        when(prService.findById("pr1")).thenReturn(Optional.of(pushedPr()));
        when(git.remoteSlug(Path.of("/tmp/repo"), "origin"))
                .thenReturn(Optional.of(new RepoRef("acme", "widget")));
        PullRequestDetail detail = detailWithActivity(List.of(
                commented(5001L, "octocat", "Can you also handle nulls?"),
                reviewed(9001L, "reviewer1", "APPROVED", "Nice cleanup, LGTM.")));
        when(pullRequests.getPullRequestDetail("acme/widget", 42)).thenReturn(detail);
        when(prService.hasRemoteEvent(eq("pr1"), anyLong())).thenReturn(false);

        service.syncFromTask("task1");

        verify(prService).addRemoteComment("pr1", "@octocat", "Can you also handle nulls?", NOW, 5001L);
        verify(prService).recordRemoteReview("pr1", "@reviewer1", "APPROVED", "Nice cleanup, LGTM.", NOW, 9001L);
    }

    @Test
    void skipsAlreadySyncedRemoteEvents()
            throws Exception
    {
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(task(TaskPhase.PUSHED_AWAITING_CI)));
        when(prService.createForTask(any(), any(), any(), any(), any())).thenReturn(pushedPr());
        when(prService.commits("pr1")).thenReturn(List.of());
        when(git.resolveCommitBase(any(), any())).thenReturn("main");
        when(git.listCommitsAhead(any(), any(), anyInt())).thenReturn(List.of());
        when(prService.findById("pr1")).thenReturn(Optional.of(pushedPr()));
        when(git.remoteSlug(Path.of("/tmp/repo"), "origin"))
                .thenReturn(Optional.of(new RepoRef("acme", "widget")));
        PullRequestDetail detail = detailWithActivity(List.of(commented(5001L, "octocat", "already seen")));
        when(pullRequests.getPullRequestDetail("acme/widget", 42)).thenReturn(detail);
        when(prService.hasRemoteEvent("pr1", 5001L)).thenReturn(true);

        service.syncFromTask("task1");

        verify(prService, never()).addRemoteComment(any(), any(), any(), any(), anyLong());
    }

    @Test
    void doesNotSyncRemoteTimelineBeforeThePrIsPushed()
            throws Exception
    {
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(task(TaskPhase.IMPLEMENTING)));
        when(prService.createForTask(any(), any(), any(), any(), any())).thenReturn(draftPr());
        when(prService.commits("pr1")).thenReturn(List.of());
        when(git.resolveCommitBase(any(), any())).thenReturn("main");
        when(git.listCommitsAhead(any(), any(), anyInt())).thenReturn(List.of());
        when(prService.findById("pr1")).thenReturn(Optional.of(draftPr()));

        service.syncFromTask("task1");

        verify(pullRequests, never()).getPullRequestDetail(any(), anyInt());
    }

    @Test
    void healsAPrRowStuckBehindAnAlreadyOpenRemotePr()
            throws Exception
    {
        // A task whose PR opened before this sync existed (or through a path
        // that missed recording it) — the task itself already knows about
        // the remote PR (linkedPrRef), but the PR row never advanced
        // past local-drafted. This must self-heal on the next PR-bundle
        // fetch instead of requiring a fresh push to notice.
        when(taskStore.findTaskById("task1"))
                .thenReturn(Optional.of(task(TaskPhase.PUSHED_AWAITING_CI, "acme/widget#32")));
        when(prService.createForTask(any(), any(), any(), any(), any())).thenReturn(draftPr());
        when(prService.commits("pr1")).thenReturn(List.of());
        when(git.resolveCommitBase(any(), any())).thenReturn("main");
        when(git.listCommitsAhead(any(), any(), anyInt())).thenReturn(List.of());
        when(prService.findById("pr1")).thenReturn(Optional.of(draftPr()));

        service.syncFromTask("task1");

        verify(prPublish).onPushedElsewhere(
                new PrPushedEvent("task1", 32, "https://github.com/acme/widget/pull/32"));
    }

    @Test
    void doesNotAttemptToHealATaskWithNoLinkedRemotePr()
            throws Exception
    {
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(task(TaskPhase.IMPLEMENTING)));
        when(prService.createForTask(any(), any(), any(), any(), any())).thenReturn(draftPr());
        when(prService.commits("pr1")).thenReturn(List.of());
        when(git.resolveCommitBase(any(), any())).thenReturn("main");
        when(git.listCommitsAhead(any(), any(), anyInt())).thenReturn(List.of());
        when(prService.findById("pr1")).thenReturn(Optional.of(draftPr()));

        service.syncFromTask("task1");

        verify(prPublish, never()).onPushedElsewhere(any());
    }

    @Test
    void doesNotFlipWhileStillImplementing()
            throws Exception
    {
        when(taskStore.findTaskById("task1")).thenReturn(Optional.of(task(TaskPhase.IMPLEMENTING)));
        when(prService.createForTask(any(), any(), any(), any(), any())).thenReturn(draftPr());
        when(prService.commits("pr1")).thenReturn(List.of());
        when(git.resolveCommitBase(any(), any())).thenReturn("main");
        when(git.listCommitsAhead(any(), any(), anyInt())).thenReturn(List.of());
        when(prService.findById("pr1")).thenReturn(Optional.of(draftPr()));

        service.syncFromTask("task1");

        verify(brainReview, never()).reviewBeforeLocalOpen(any(), any());
    }
}
