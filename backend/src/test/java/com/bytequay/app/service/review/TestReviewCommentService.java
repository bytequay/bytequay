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
package com.bytequay.app.service.review;

import com.bytequay.app.developmentflow.stage.V2LocalReviewControl;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRComment;
import com.bytequay.app.domain.PRTimelineEntry;
import com.bytequay.app.domain.ReviewComment;
import com.bytequay.app.domain.ReviewCommentSource;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.threads.TaskPhaseMachine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestReviewCommentService
{
    private static final Instant NOW = Instant.parse("2026-06-24T09:00:00Z");
    private static final PR TASK_PR = PR.create("pr-1", "task-1", "dev/task-1", "main", "Task", "", NOW);

    private StageStore stageStore;
    private ReviewRoundService reviewRounds;
    private RoundGateSaga roundGate;
    private PRService prService;
    private TaskStore taskStore;
    private ReviewCommentServiceImpl service;

    @BeforeEach
    void setUp()
    {
        stageStore = mock(StageStore.class);
        reviewRounds = mock(ReviewRoundService.class);
        roundGate = mock(RoundGateSaga.class);
        prService = mock(PRService.class);
        taskStore = mock(TaskStore.class);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(2).run();
            return null;
        }).when(roundGate).editPayload(anyString(), anyString(), any(Runnable.class));
        service = new ReviewCommentServiceImpl(
                stageStore, reviewRounds, roundGate, prService, taskStore);
        Task reviewTask = mock(Task.class);
        when(reviewTask.phase()).thenReturn(TaskPhase.AWAITING_PUSH);
        when(reviewTask.status()).thenReturn(TaskStatus.IDLE);
        when(taskStore.findTaskById("task-1")).thenReturn(Optional.of(reviewTask));
    }

    @Test
    void addPersistsALocalUserCommentOnTheTaskPr()
    {
        when(taskStore.findTaskById("task-1")).thenReturn(Optional.of(task("task-1")));
        when(prService.findByTask("task-1")).thenReturn(Optional.empty());
        when(prService.createForTask("task-1", "dev/task-1", "main", "Task 1", "")).thenReturn(TASK_PR);
        PRComment saved = prComment("c1", false);
        when(prService.addComment(eq("pr-1"), anyString(), anyString(), anyString(), any(), any(), any(), any(),
                anyString(), anyString(), any())).thenReturn(saved);

        PRComment result = service.add("task-1", "src/Foo.java", 42, null, null, null, "fix this");

        assertThat(result).isSameAs(saved);
        verify(prService).addComment(
                "pr-1",
                PRComment.ORIGIN_LOCAL,
                PRComment.SCOPE_FILE_LINE,
                "src/Foo.java",
                42,
                null,
                null,
                null,
                PRTimelineEntry.ACTOR_USER,
                "fix this",
                null);
        verify(stageStore, never()).saveReviewComment(any());
    }

    @Test
    void v2SubmissionRoutesOnlyToTypedLocalReview()
    {
        V2LocalReviewControl typed = mock(V2LocalReviewControl.class);
        when(taskStore.isV2Task("task-v2")).thenReturn(true);
        when(typed.submit(
                "task-v2", "Please revise", "REQUEST_CHANGES", List.of("c-1")))
                .thenReturn(new V2LocalReviewControl.Submission(1, "turn-v2"));
        service.setV2LocalReview(typed);

        ReviewCommentService.SubmitResult result = service.submitReview(
                "task-v2", " Please revise ", "REQUEST_CHANGES", List.of("c-1"));

        assertThat(result.submitted()).isEqualTo(1);
        assertThat(result.turnId()).isEqualTo("turn-v2");
        verify(typed).submit(
                "task-v2", "Please revise", "REQUEST_CHANGES", List.of("c-1"));
        verify(prService, never()).recordLocalReviewSubmission(
                any(), any(), any(), any(), any());
        verify(prService, never()).findByTask(anyString());
    }

    @Test
    void addRejectsABlankBody()
    {
        assertThatThrownBy(() -> service.add("task-1", "src/Foo.java", 1, null, null, null, "  "))
                .isInstanceOf(ResponseStatusException.class);
        verify(prService, never()).addComment(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void addKeepsSideAndRangeOnThePrComment()
    {
        when(prService.findByTask("task-1")).thenReturn(Optional.of(TASK_PR));
        when(prService.addComment(eq("pr-1"), anyString(), anyString(), anyString(), any(), any(), any(), any(),
                anyString(), anyString(), any()))
                .thenReturn(prComment("c1", false));

        service.add("task-1", "src/Foo.java", 45, "LEFT", 40, "LEFT", "spans a few lines");

        verify(prService).addComment(
                "pr-1",
                PRComment.ORIGIN_LOCAL,
                PRComment.SCOPE_FILE_LINE,
                "src/Foo.java",
                45,
                "LEFT",
                40,
                "LEFT",
                PRTimelineEntry.ACTOR_USER,
                "spans a few lines",
                null);
    }

    @Test
    void listReturnsInlineCommentsForTheTaskPr()
    {
        PRComment inline = prComment("inline", false);
        PRComment prLevel = new PRComment(
                "pr-level", "pr-1", PRComment.ORIGIN_LOCAL, PRComment.SCOPE_PR,
                null, null, PRTimelineEntry.ACTOR_USER, "general", NOW, null, null,
                null, null, null, "RIGHT", null, null);
        when(prService.findByTask("task-1")).thenReturn(Optional.of(TASK_PR));
        when(prService.comments("pr-1")).thenReturn(List.of(prLevel, inline));

        assertThat(service.list("task-1")).containsExactly(inline);
    }

    @Test
    void resolveFlipsAPrCommentWhenItIsNotALegacyRoundComment()
    {
        UUID id = UUID.randomUUID();
        when(stageStore.findReviewCommentById(id)).thenReturn(Optional.empty());

        service.resolve(id);

        verify(prService).resolveComment(id.toString());
    }

    @Test
    void reopenFlipsAPrCommentBackWhenItIsNotALegacyRoundComment()
    {
        UUID id = UUID.randomUUID();
        when(stageStore.findReviewCommentById(id)).thenReturn(Optional.empty());

        service.reopen(id);

        verify(prService).reopenComment(id.toString());
    }

    @Test
    void resolvingARoundCommentRecomputesThatRoundsStats()
    {
        UUID id = UUID.randomUUID();
        UUID roundId = UUID.randomUUID();
        ReviewComment comment = new ReviewComment(
                id, "task-1", "src/Foo.java", 12, "nit", NOW, ReviewCommentSource.REMOTE_REVIEWER,
                "https://github.com/octo/repo/pull/7#discussion_r1", false, 1001L, roundId, null, null,
                "RIGHT", null, null);
        when(stageStore.findReviewCommentById(id)).thenReturn(Optional.of(comment));

        service.resolve(id);

        verify(stageStore).setReviewCommentResolved(id, true);
        verify(reviewRounds).recomputeStats(roundId.toString());
        verify(roundGate).editPayload(eq("task-1"), eq(roundId.toString()), any(Runnable.class));
        verify(prService, never()).resolveComment(anyString());
    }

    @Test
    void reopeningARoundCommentUsesTheSameGatePayloadEdit()
    {
        UUID id = UUID.randomUUID();
        UUID roundId = UUID.randomUUID();
        ReviewComment comment = new ReviewComment(
                id, "task-1", "src/Foo.java", 12, "nit", NOW, ReviewCommentSource.REMOTE_REVIEWER,
                "https://github.com/octo/repo/pull/7#discussion_r1", true, 1001L, roundId, null, null,
                "RIGHT", null, null);
        when(stageStore.findReviewCommentById(id)).thenReturn(Optional.of(comment));

        service.reopen(id);

        verify(stageStore).setReviewCommentResolved(id, false);
        verify(reviewRounds).recomputeStats(roundId.toString());
        verify(roundGate).editPayload(eq("task-1"), eq(roundId.toString()), any(Runnable.class));
        verify(prService, never()).reopenComment(anyString());
    }

    @Test
    void aRejectedRoundPayloadEditDoesNotResolveTheComment()
    {
        UUID id = UUID.randomUUID();
        UUID roundId = UUID.randomUUID();
        ReviewComment comment = new ReviewComment(
                id, "task-1", "src/Foo.java", 12, "nit", NOW, ReviewCommentSource.REMOTE_REVIEWER,
                "https://github.com/octo/repo/pull/7#discussion_r1", false, 1001L, roundId, null, null,
                "RIGHT", null, null);
        when(stageStore.findReviewCommentById(id)).thenReturn(Optional.of(comment));
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "round gate posting has started"))
                .when(roundGate).editPayload(eq("task-1"), eq(roundId.toString()), any(Runnable.class));

        assertThatThrownBy(() -> service.resolve(id))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("posting has started");

        verify(stageStore, never()).setReviewCommentResolved(any(), anyBoolean());
        verify(reviewRounds, never()).recomputeStats(anyString());
    }

    @Test
    void resolvingALegacyLocalCommentWithNoRoundNeverTouchesReviewRounds()
    {
        UUID id = UUID.randomUUID();
        ReviewComment comment = new ReviewComment(
                id, "task-1", "src/Foo.java", 12, "note", NOW, ReviewCommentSource.LOCAL_USER,
                null, false, null, /* roundId */ null, null, null, "RIGHT", null, null);
        when(stageStore.findReviewCommentById(id)).thenReturn(Optional.of(comment));

        service.resolve(id);

        verify(stageStore).setReviewCommentResolved(id, true);
        verify(reviewRounds, never()).recomputeStats(any());
        verify(roundGate, never()).editPayload(anyString(), anyString(), any(Runnable.class));
        verify(prService, never()).resolveComment(anyString());
    }

    @Test
    void submitReviewRecordsAPrivateLocalBatchForDevelopment()
    {
        PRComment resolved = prComment("resolved-id", true);
        PRComment open = prComment("open-id", false);
        PR localOpen = TASK_PR.withStatus(PR.STATUS_LOCAL_OPEN, NOW);
        when(prService.findByTask("task-1")).thenReturn(Optional.of(localOpen));
        when(prService.comments("pr-1")).thenReturn(List.of(resolved, open));

        ReviewCommentService.SubmitResult result = service.submitReview("task-1", null, null);

        assertThat(result.submitted()).isEqualTo(1);
        assertThat(result.turnId()).isNull();
        verify(prService).recordLocalReviewSubmission(
                "pr-1", List.of("open-id"), "", "COMMENT", null);
    }

    @Test
    void submitReviewIsANoOpWithNoUnresolvedCommentsAndNoBody()
    {
        when(prService.findByTask("task-1"))
                .thenReturn(Optional.of(TASK_PR.withStatus(PR.STATUS_LOCAL_OPEN, NOW)));
        when(prService.comments("pr-1")).thenReturn(List.of(prComment("resolved-id", true)));

        ReviewCommentService.SubmitResult result = service.submitReview("task-1", "", null);

        assertThat(result.submitted()).isZero();
        assertThat(result.turnId()).isNull();
        verify(prService, never()).recordLocalReviewSubmission(any(), any(), any(), any(), any());
    }

    @Test
    void submitReviewCanRecordApprovalWithoutComments()
    {
        PR localOpen = TASK_PR.withStatus(PR.STATUS_LOCAL_OPEN, NOW);
        when(prService.findByTask("task-1")).thenReturn(Optional.of(localOpen));
        when(prService.comments("pr-1")).thenReturn(List.of());

        ReviewCommentService.SubmitResult result = service.submitReview("task-1", "", "APPROVE");

        assertThat(result.submitted()).isZero();
        assertThat(result.turnId()).isNull();
        verify(prService).recordLocalReviewSubmission("pr-1", List.of(), "", "APPROVE", null);
    }

    @Test
    void submitReviewRejectsAnInvalidTaskPhase()
    {
        Task task = mock(Task.class);
        when(task.phase()).thenReturn(TaskPhase.PUSHED_AWAITING_CI);
        when(task.status()).thenReturn(TaskStatus.IDLE);
        when(taskStore.findTaskById("task-1")).thenReturn(Optional.of(task));
        when(prService.findByTask("task-1"))
                .thenReturn(Optional.of(TASK_PR.withStatus(PR.STATUS_LOCAL_OPEN, NOW)));

        assertThatThrownBy(() -> service.submitReview(
                "task-1", "", "APPROVE", List.of()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not accepting Local Review submissions");

        verify(prService, never()).recordLocalReviewSubmission(any(), any(), any(), any(), any());
    }

    @Test
    void submitReviewQueuesFeedbackThatArrivesDuringBrainReReview()
    {
        Task task = mock(Task.class);
        when(task.phase()).thenReturn(TaskPhase.INTERNAL_REVIEW);
        when(task.status()).thenReturn(TaskStatus.IDLE);
        when(taskStore.findTaskById("task-1")).thenReturn(Optional.of(task));
        when(prService.findByTask("task-1"))
                .thenReturn(Optional.of(TASK_PR.withStatus(PR.STATUS_LOCAL_OPEN, NOW)));
        when(prService.comments("pr-1")).thenReturn(List.of(prComment("open-id", false)));

        ReviewCommentService.SubmitResult result = service.submitReview(
                "task-1", "", "COMMENT", List.of("open-id"));

        assertThat(result.submitted()).isEqualTo(1);
        verify(prService).recordLocalReviewSubmission(
                "pr-1", List.of("open-id"), "", "COMMENT", null);
    }

    @Test
    void submitReviewRejectsANeedsAttentionTaskAtTheLocalPhase()
    {
        Task task = mock(Task.class);
        when(task.phase()).thenReturn(TaskPhase.AWAITING_PUSH);
        when(task.status()).thenReturn(TaskStatus.NEEDS_ATTENTION);
        when(taskStore.findTaskById("task-1")).thenReturn(Optional.of(task));
        when(prService.findByTask("task-1"))
                .thenReturn(Optional.of(TASK_PR.withStatus(PR.STATUS_LOCAL_OPEN, NOW)));

        assertThatThrownBy(() -> service.submitReview(
                "task-1", "", "APPROVE", List.of()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not accepting Local Review submissions");

        verify(prService, never()).recordLocalReviewSubmission(any(), any(), any(), any(), any());
    }

    @Test
    void submitReviewHoldsTheTaskLockThroughPersistence()
            throws Exception
    {
        PR localOpen = TASK_PR.withStatus(PR.STATUS_LOCAL_OPEN, NOW);
        when(prService.findByTask("task-1")).thenReturn(Optional.of(localOpen));
        CountDownLatch insideSelection = new CountDownLatch(1);
        CountDownLatch releaseSelection = new CountDownLatch(1);
        when(prService.comments("pr-1")).thenAnswer(ignored -> {
            insideSelection.countDown();
            if (!releaseSelection.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("timed out waiting to release selection");
            }
            return List.of(prComment("open", false));
        });
        CountDownLatch competitorStarted = new CountDownLatch(1);
        CountDownLatch competitorEntered = new CountDownLatch(1);

        CompletableFuture<ReviewCommentService.SubmitResult> submission = CompletableFuture.supplyAsync(
                () -> service.submitReview("task-1", "", "REQUEST_CHANGES"));
        assertThat(insideSelection.await(5, TimeUnit.SECONDS)).isTrue();
        CompletableFuture<Void> competingLifecycleAction = CompletableFuture.runAsync(() -> {
            competitorStarted.countDown();
            TaskPhaseMachine.withTaskLock("task-1", () -> {
                competitorEntered.countDown();
                return null;
            });
        });
        assertThat(competitorStarted.await(5, TimeUnit.SECONDS)).isTrue();
        try {
            assertThat(competitorEntered.await(250, TimeUnit.MILLISECONDS)).isFalse();
        }
        finally {
            releaseSelection.countDown();
        }

        assertThat(submission.get(5, TimeUnit.SECONDS).submitted()).isOne();
        competingLifecycleAction.get(5, TimeUnit.SECONDS);
        assertThat(competitorEntered.getCount()).isZero();
    }

    @Test
    void submitReviewRecordsTheBodyVerdictAndOnlySelectedPendingComments()
    {
        PR localOpen = TASK_PR.withStatus(PR.STATUS_LOCAL_OPEN, NOW);
        when(prService.findByTask("task-1")).thenReturn(Optional.of(localOpen));
        when(prService.comments("pr-1")).thenReturn(List.of(
                prComment("first", false), prComment("second", false)));
        when(prService.localReviewSubmissions("pr-1")).thenReturn(List.of(
                new PRService.LocalReviewSubmission(
                        NOW.minusSeconds(1), List.of("first"), "", "COMMENT", null)));
        PRComment summary = new PRComment(
                "summary", "pr-1", PRComment.ORIGIN_LOCAL, PRComment.SCOPE_PR,
                null, null, PRTimelineEntry.ACTOR_USER, "Please handle the selected concern.",
                NOW, null, null, null, null, null, "RIGHT", null, null);
        when(prService.addComment(eq("pr-1"), eq(PRComment.ORIGIN_LOCAL), eq(PRComment.SCOPE_PR),
                any(), any(), any(), any(), any(), eq(PRTimelineEntry.ACTOR_USER), anyString(), any()))
                .thenReturn(summary);

        ReviewCommentService.SubmitResult result = service.submitReview(
                "task-1", "Please handle the selected concern.", "REQUEST_CHANGES", List.of("second"));

        assertThat(result.submitted()).isEqualTo(2);
        assertThat(result.turnId()).isNull();
        verify(prService).recordLocalReviewSubmission(
                "pr-1", List.of("second", "summary"), "Please handle the selected concern.",
                "REQUEST_CHANGES", "summary");
    }

    @Test
    void submitReviewRejectsASelectedCommentOutsideTheOpenUserRoots()
    {
        PR localOpen = TASK_PR.withStatus(PR.STATUS_LOCAL_OPEN, NOW);
        when(prService.findByTask("task-1")).thenReturn(Optional.of(localOpen));
        when(prService.comments("pr-1")).thenReturn(List.of(prComment("open", false)));

        assertThatThrownBy(() -> service.submitReview(
                "task-1", "", "COMMENT", List.of("not-on-this-review")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("not an open actionable root");

        verify(prService, never()).recordLocalReviewSubmission(any(), any(), any(), any(), any());
    }

    @Test
    void explicitSelectionAcceptsAnOpenAgentFindingButDefaultSelectionDoesNot()
    {
        PR localOpen = TASK_PR.withStatus(PR.STATUS_LOCAL_OPEN, NOW);
        PRComment finding = new PRComment(
                "finding-comment", "pr-1", PRComment.ORIGIN_LOCAL, PRComment.SCOPE_FILE_LINE,
                "src/Foo.java", 9, "agent", "Possible null dereference", NOW,
                null, null, null, null, null, "RIGHT", null, null, "finding-1");
        when(prService.findByTask("task-1")).thenReturn(Optional.of(localOpen));
        when(prService.comments("pr-1")).thenReturn(List.of(finding));

        assertThat(service.submitReview("task-1", "", "COMMENT").submitted()).isZero();
        assertThat(service.submitReview(
                "task-1", "", "REQUEST_CHANGES", List.of("finding-comment")).submitted()).isOne();

        verify(prService).recordLocalReviewSubmission(
                "pr-1", List.of("finding-comment"), "", "REQUEST_CHANGES", null);
    }

    @Test
    void explicitSelectionRedispatchesAReopenedPreviouslySubmittedRoot()
    {
        PR localOpen = TASK_PR.withStatus(PR.STATUS_LOCAL_OPEN, NOW);
        PRComment reopened = prComment("reopened", false);
        when(prService.findByTask("task-1")).thenReturn(Optional.of(localOpen));
        when(prService.comments("pr-1")).thenReturn(List.of(reopened));
        when(prService.localReviewSubmissions("pr-1")).thenReturn(List.of(
                new PRService.LocalReviewSubmission(
                        NOW.minusSeconds(30), List.of("reopened"), "", "COMMENT", null)));

        assertThat(service.submitReview(
                "task-1", "", "REQUEST_CHANGES", List.of("reopened")).submitted()).isOne();

        verify(prService).recordLocalReviewSubmission(
                "pr-1", List.of("reopened"), "", "REQUEST_CHANGES", null);
    }

    @Test
    void implicitSelectionIncludesAnInvalidatedPreviouslySubmittedRoot()
    {
        PR localOpen = TASK_PR.withStatus(PR.STATUS_LOCAL_OPEN, NOW);
        PRComment invalidated = prComment("invalidated", false);
        when(prService.findByTask("task-1")).thenReturn(Optional.of(localOpen));
        when(prService.comments("pr-1")).thenReturn(List.of(invalidated));
        when(prService.localReviewSubmissions("pr-1")).thenReturn(List.of(
                new PRService.LocalReviewSubmission(
                        NOW.minusSeconds(30), List.of(), "", "COMMENT", null)));

        assertThat(service.submitReview("task-1", "", "REQUEST_CHANGES").submitted()).isOne();

        verify(prService).recordLocalReviewSubmission(
                "pr-1", List.of("invalidated"), "", "REQUEST_CHANGES", null);
    }

    private static PRComment prComment(String id, boolean resolved)
    {
        return new PRComment(id, "pr-1", PRComment.ORIGIN_LOCAL, PRComment.SCOPE_FILE_LINE,
                "src/Foo.java", 7, PRTimelineEntry.ACTOR_USER, "rename it",
                NOW, resolved ? NOW : null, null, null, null, null, "RIGHT", null, null);
    }

    private static Task task(String id)
    {
        return new Task(
                id, "thread-" + id, 1L, TaskStatus.RUNNING,
                "dev/" + id, "/tmp/wt/" + id, "main", "/tmp/repo",
                null, null, null, null, null,
                "DEVELOP", null, null,
                0L, 0L, 0L,
                /* agentSessionId */ null,
                NOW, null, null, "Task 1", null, null);
    }
}
