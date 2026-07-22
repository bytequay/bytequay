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
import com.bytequay.app.repository.ReviewRoundStore;
import com.bytequay.app.repository.StageStore;
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

class TestReviewRoundToolHandlers
{
    private final StageStore stageStore = mock(StageStore.class);
    private final ReviewRoundStore roundStore = mock(ReviewRoundStore.class);
    private final ReviewRoundToolHandlers handlers = new ReviewRoundToolHandlers(stageStore, roundStore);

    @Test
    void draftsOnlyForACommentInTheCallingRunsLiveRound()
    {
        UUID roundId = UUID.randomUUID();
        ReviewComment comment = comment(roundId, "task-1");
        ReviewRound round = round(roundId, "task-1", "run-1");
        when(stageStore.findReviewCommentById(comment.id())).thenReturn(Optional.of(comment));
        when(roundStore.findLiveByTask("task-1")).thenReturn(Optional.of(round));

        ToolOutcome outcome = handlers.recordRoundReply(
                new ReviewRoundToolHandlers.RecordRoundReplyArgs(comment.id().toString(), "Fixed"),
                new ToolCall("thread-1", null, AgentRole.TASK,
                        "task-1", "stage-1", "run-1"));

        assertThat(((ToolOutcome.Completed) outcome).isError()).isFalse();
        verify(stageStore).saveReviewComment(any());
    }

    @Test
    void rejectsACommentFromAnotherTaskOrRound()
    {
        UUID roundId = UUID.randomUUID();
        ReviewComment comment = comment(roundId, "task-2");
        when(stageStore.findReviewCommentById(comment.id())).thenReturn(Optional.of(comment));

        ToolOutcome outcome = handlers.recordRoundReply(
                new ReviewRoundToolHandlers.RecordRoundReplyArgs(comment.id().toString(), "Fixed"),
                new ToolCall("thread-1", null, AgentRole.TASK,
                        "task-1", "stage-1", "run-1"));

        assertThat(((ToolOutcome.Completed) outcome).isError()).isTrue();
        verify(stageStore, never()).saveReviewComment(any());
    }

    private static ReviewComment comment(UUID roundId, String taskId)
    {
        return new ReviewComment(
                UUID.randomUUID(), taskId, "Foo.java", 10, "Please fix", Instant.EPOCH,
                ReviewCommentSource.REMOTE_REVIEWER, null, false, 123L, roundId,
                null, null, "RIGHT", null, null);
    }

    private static ReviewRound round(UUID roundId, String taskId, String runId)
    {
        return new ReviewRound(
                roundId.toString(), taskId, 1, List.of("@reviewer"),
                ReviewRound.STATUS_ADDRESSING, ReviewRound.ReviewRoundStats.empty(),
                runId, Instant.EPOCH, null, null, ReviewRound.ORIGIN_EXTERNAL,
                null, 0, ReviewRound.DEFAULT_BRAIN_BUDGET);
    }
}
