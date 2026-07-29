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
import com.bytequay.app.domain.Task;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestReviewCommentService
{
    private static final Instant NOW = Instant.parse("2026-06-24T09:00:00Z");
    private static final PR TASK_PR = PR.create(
            "pr-v2", "task-v2", "dev/task-v2", "main", "Task", "", NOW);

    private StageStore stageStore;
    private PRService prService;
    private TaskStore taskStore;
    private V2LocalReviewControl typed;
    private ReviewCommentServiceImpl service;

    @BeforeEach
    void setUp()
    {
        stageStore = mock(StageStore.class);
        prService = mock(PRService.class);
        taskStore = mock(TaskStore.class);
        typed = mock(V2LocalReviewControl.class);
        service = new ReviewCommentServiceImpl(stageStore, prService, taskStore);
        service.setV2LocalReview(typed);
    }

    @Test
    void v2AddRoutesOnlyToTypedLocalReview()
    {
        Task task = mock(Task.class);
        PRComment saved = comment("comment-v2");
        when(taskStore.findTaskById("task-v2")).thenReturn(Optional.of(task));
        when(taskStore.isV2Task("task-v2")).thenReturn(true);
        when(prService.findByTask("task-v2")).thenReturn(Optional.of(TASK_PR));
        when(typed.addComment(
                TASK_PR, PRComment.ORIGIN_LOCAL, PRComment.SCOPE_FILE_LINE,
                "src/Foo.java", 42, "RIGHT", null, null,
                PRTimelineEntry.ACTOR_USER, "fix this", null))
                .thenReturn(saved);

        assertThat(service.add(
                "task-v2", "src/Foo.java", 42, "RIGHT", null, null, "fix this"))
                .isSameAs(saved);

        verify(prService, never()).addComment(
                any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
    }

    @Test
    void v2SubmissionRoutesOnlyToTypedLocalReview()
    {
        when(taskStore.findTaskById("task-v2")).thenReturn(Optional.of(mock(Task.class)));
        when(taskStore.isV2Task("task-v2")).thenReturn(true);
        when(typed.submit(
                "task-v2", "Please revise", "REQUEST_CHANGES", List.of("c-1")))
                .thenReturn(new V2LocalReviewControl.Submission(1, "turn-v2"));

        ReviewCommentService.SubmitResult result = service.submitReview(
                "task-v2", " Please revise ", "REQUEST_CHANGES", List.of("c-1"));

        assertThat(result.submitted()).isEqualTo(1);
        assertThat(result.turnId()).isEqualTo("turn-v2");
        verify(prService, never()).recordLocalReviewSubmission(
                any(), any(), any(), any(), any());
    }

    @Test
    void legacyTaskReviewMutationsAreRejected()
    {
        when(taskStore.findTaskById("task-legacy"))
                .thenReturn(Optional.of(mock(Task.class)));

        assertThatThrownBy(() -> service.add(
                "task-legacy", "src/Foo.java", 1, null, null, null, "fix"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("historical");
        assertThatThrownBy(() -> service.submitReview(
                "task-legacy", "fix", "REQUEST_CHANGES", List.of()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("historical");

        verify(typed, never()).submit(anyString(), anyString(), any(), any());
        verify(prService, never()).createForTask(
                anyString(), anyString(), anyString(), anyString(), anyString());
    }

    @Test
    void legacyRoundCommentCannotBeResolvedOrReopened()
    {
        UUID id = UUID.randomUUID();
        ReviewComment legacy = mock(ReviewComment.class);
        when(legacy.taskId()).thenReturn("task-legacy");
        when(stageStore.findReviewCommentById(id)).thenReturn(Optional.of(legacy));

        assertThatThrownBy(() -> service.resolve(id))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("read-only");
        assertThatThrownBy(() -> service.reopen(id))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("read-only");

        verify(stageStore, never()).setReviewCommentResolved(any(), anyBoolean());
        verify(prService, never()).resolveComment(anyString());
        verify(prService, never()).reopenComment(anyString());
    }

    @Test
    void v2ResolveAndReopenRouteOnlyToTypedLocalReview()
    {
        UUID id = UUID.randomUUID();
        when(typed.ownsComment(id.toString())).thenReturn(true);

        service.resolve(id);
        service.reopen(id);

        verify(typed).resolveComment(id.toString());
        verify(typed).reopenComment(id.toString());
        verify(prService, never()).resolveComment(anyString());
        verify(prService, never()).reopenComment(anyString());
    }

    @Test
    void tasklessOrImportedCommentStillUsesGenericPrStorage()
    {
        UUID id = UUID.randomUUID();
        when(stageStore.findReviewCommentById(id)).thenReturn(Optional.empty());

        service.resolve(id);
        service.reopen(id);

        verify(prService).resolveComment(id.toString());
        verify(prService).reopenComment(id.toString());
    }

    @Test
    void historicalCommentsRemainReadable()
    {
        PRComment inline = comment("inline");
        PRComment summary = new PRComment(
                "summary", "pr-v2", PRComment.ORIGIN_LOCAL, PRComment.SCOPE_PR,
                null, null, PRTimelineEntry.ACTOR_USER, "summary", NOW,
                null, null, null, null, null, "RIGHT", null, null);
        when(prService.findByTask("task-v2")).thenReturn(Optional.of(TASK_PR));
        when(prService.comments("pr-v2")).thenReturn(List.of(summary, inline));

        assertThat(service.list("task-v2")).containsExactly(inline);
    }

    private static PRComment comment(String id)
    {
        return new PRComment(
                id, "pr-v2", PRComment.ORIGIN_LOCAL, PRComment.SCOPE_FILE_LINE,
                "src/Foo.java", 7, PRTimelineEntry.ACTOR_USER, "rename it",
                NOW, null, null, null, null, null, "RIGHT", null, null);
    }
}
