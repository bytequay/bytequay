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

import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRComment;
import com.bytequay.app.domain.PRTimelineEntry;
import com.bytequay.app.domain.ReviewComment;
import com.bytequay.app.domain.ReviewCommentSource;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.localpr.PRPublishService;
import com.bytequay.app.service.localpr.PRService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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
    private PRService prService;
    private PRPublishService publish;
    private TaskStore taskStore;
    private ReviewCommentServiceImpl service;

    @BeforeEach
    void setUp()
    {
        stageStore = mock(StageStore.class);
        reviewRounds = mock(ReviewRoundService.class);
        prService = mock(PRService.class);
        publish = mock(PRPublishService.class);
        taskStore = mock(TaskStore.class);
        service = new ReviewCommentServiceImpl(stageStore, reviewRounds, prService, publish, taskStore);
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
        verify(prService, never()).resolveComment(anyString());
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
        verify(prService, never()).resolveComment(anyString());
    }

    @Test
    void submitReviewPublishesTheSelectedCommentsToGitHub()
    {
        PRComment resolved = prComment("resolved-id", true);
        PRComment open = prComment("open-id", false);
        PR remotePr = TASK_PR.withRemote("acme/widget", 42, "https://github.com/acme/widget/pull/42", NOW);
        when(prService.findByTask("task-1")).thenReturn(Optional.of(remotePr));
        when(prService.comments("pr-1")).thenReturn(List.of(resolved, open));
        when(publish.publishReview("pr-1", "COMMENT", List.of(), List.of("open-id"), ""))
                .thenReturn(remotePr);

        ReviewCommentService.SubmitResult result = service.submitReview("task-1", null, null);

        assertThat(result.submitted()).isEqualTo(1);
        assertThat(result.turnId()).isNull();
        verify(publish).publishReview("pr-1", "COMMENT", List.of(), List.of("open-id"), "");
    }

    @Test
    void submitReviewIsANoOpWithNoUnresolvedCommentsAndNoBody()
    {
        when(prService.findByTask("task-1")).thenReturn(Optional.of(TASK_PR));
        when(prService.comments("pr-1")).thenReturn(List.of(prComment("resolved-id", true)));

        ReviewCommentService.SubmitResult result = service.submitReview("task-1", "", null);

        assertThat(result.submitted()).isZero();
        assertThat(result.turnId()).isNull();
        verify(publish, never()).publishReview(any(), any(), any(), any(), any());
    }

    @Test
    void submitReviewApprovesARemotePr()
    {
        PR remotePr = TASK_PR.withRemote("acme/widget", 42, "https://github.com/acme/widget/pull/42", NOW);
        when(prService.findByTask("task-1")).thenReturn(Optional.of(remotePr));
        when(prService.comments("pr-1")).thenReturn(List.of());
        when(publish.publishReview("pr-1", "APPROVE", List.of(), List.of(), "")).thenReturn(remotePr);

        ReviewCommentService.SubmitResult result = service.submitReview("task-1", "", "APPROVE");

        assertThat(result.submitted()).isZero();
        assertThat(result.turnId()).isNull();
        verify(publish).publishReview("pr-1", "APPROVE", List.of(), List.of(), "");
    }

    @Test
    void submitReviewPublishesTheBodyAndVerdict()
    {
        PR remotePr = TASK_PR.withRemote("acme/widget", 42, "https://github.com/acme/widget/pull/42", NOW);
        when(prService.findByTask("task-1")).thenReturn(Optional.of(remotePr));
        when(prService.comments("pr-1")).thenReturn(List.of(prComment("resolved-id", true)));
        when(publish.publishReview("pr-1", "APPROVE", List.of(), List.of(), "Looks great overall."))
                .thenReturn(remotePr);

        ReviewCommentService.SubmitResult result = service.submitReview("task-1", "Looks great overall.", "APPROVE");

        assertThat(result.turnId()).isNull();
        verify(publish).publishReview("pr-1", "APPROVE", List.of(), List.of(), "Looks great overall.");
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
