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
package com.bytequay.app.service.tools;

import com.bytequay.app.domain.ReviewComment;
import com.bytequay.app.domain.ReviewCommentSource;
import com.bytequay.app.domain.ReviewRound;
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.repository.ReviewRoundStore;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.service.review.ReviewCommentService;
import com.bytequay.app.service.tools.ReviewCommentToolHandlers.ResolveReviewCommentArgs;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestReviewCommentToolHandlers
{
    private final ReviewCommentService reviewComments = mock(ReviewCommentService.class);
    private final StageStore stageStore = mock(StageStore.class);
    private final ReviewRoundStore roundStore = mock(ReviewRoundStore.class);
    private final ReviewCommentToolHandlers handlers = new ReviewCommentToolHandlers(
            reviewComments, stageStore, roundStore);

    private final ToolCall call = new ToolCall(ThreadScope.STAGE,
            "thread-1", null, AgentRole.TASK, "task-1", "stage-1", "run-1");

    @Test
    void resolvesAComment()
    {
        UUID id = UUID.randomUUID();
        UUID roundId = UUID.randomUUID();
        when(stageStore.findReviewCommentById(id)).thenReturn(Optional.of(comment(id, "task-1", roundId)));
        when(roundStore.findLiveByTask("task-1")).thenReturn(Optional.of(round(roundId)));

        ToolOutcome outcome = handlers.resolveReviewComment(
                new ResolveReviewCommentArgs(id.toString()), call);

        assertThat(((ToolOutcome.Completed) outcome).isError()).isFalse();
        verify(reviewComments).resolve(id);
    }

    @Test
    void rejectsAnUnknownCommentId()
    {
        UUID id = UUID.randomUUID();

        ToolOutcome outcome = handlers.resolveReviewComment(
                new ResolveReviewCommentArgs(id.toString()), call);

        assertThat(((ToolOutcome.Completed) outcome).isError()).isTrue();
        verify(reviewComments, never()).resolve(any());
    }

    @Test
    void rejectsACommentFromAnotherTask()
    {
        UUID id = UUID.randomUUID();
        when(stageStore.findReviewCommentById(id))
                .thenReturn(Optional.of(comment(id, "task-2", UUID.randomUUID())));

        ToolOutcome outcome = handlers.resolveReviewComment(
                new ResolveReviewCommentArgs(id.toString()), call);

        assertThat(((ToolOutcome.Completed) outcome).isError()).isTrue();
        verify(reviewComments, never()).resolve(any());
    }

    @Test
    void rejectsAMalformedId()
    {
        ToolOutcome outcome = handlers.resolveReviewComment(
                new ResolveReviewCommentArgs("not-a-uuid"), call);

        assertThat(((ToolOutcome.Completed) outcome).isError()).isTrue();
        verify(reviewComments, never()).resolve(any());
    }

    private static ReviewComment comment(UUID id, String taskId, UUID roundId)
    {
        return new ReviewComment(
                id, taskId, "src/Foo.java", 12, "nit", Instant.parse("2026-07-05T12:00:00Z"),
                ReviewCommentSource.REMOTE_REVIEWER,
                "https://github.com/acme/widgets/pull/42#discussion_r1",
                false, 1L, roundId, null, null, "RIGHT", null, null);
    }

    private static ReviewRound round(UUID roundId)
    {
        return new ReviewRound(
                roundId.toString(), "task-1", 1, List.of(), ReviewRound.STATUS_ADDRESSING,
                ReviewRound.ReviewRoundStats.empty(), "run-1", Instant.parse("2026-07-05T12:00:00Z"),
                null, null, ReviewRound.ORIGIN_EXTERNAL, null, 0, ReviewRound.DEFAULT_BRAIN_BUDGET);
    }
}
