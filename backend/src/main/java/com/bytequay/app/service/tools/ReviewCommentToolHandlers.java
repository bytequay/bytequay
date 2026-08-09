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
import com.bytequay.app.domain.ReviewRound;
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.repository.sqlite.SqliteReviewRoundStore;
import com.bytequay.app.repository.sqlite.SqliteStageStore;
import com.bytequay.app.service.review.ReviewCommentServiceImpl;
import org.springframework.stereotype.Component;

import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * The {@code resolve_review_comment} agent tool. After the user submits
 * their local pre-push review comments, the dev agent addresses each one in
 * the code and calls this tool with the comment's id to mark it resolved, so
 * the user's diff page shows progress comment-by-comment.
 */
@Component
public class ReviewCommentToolHandlers
{
    private final ReviewCommentServiceImpl reviewComments;
    private final SqliteStageStore stageStore;
    private final SqliteReviewRoundStore roundStore;

    public ReviewCommentToolHandlers(
            ReviewCommentServiceImpl reviewComments,
            SqliteStageStore stageStore,
            SqliteReviewRoundStore roundStore)
    {
        this.reviewComments = requireNonNull(reviewComments, "reviewComments is null");
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.roundStore = requireNonNull(roundStore, "roundStore is null");
    }

    /** Args record for {@code resolve_review_comment}. */
    public record ResolveReviewCommentArgs(
            @ToolParam(description = "Id of the review comment you've addressed, as given to "
                    + "you in the review-comments turn.", required = true, wireName = "comment_id")
            String commentId) {}

    @AgentTool(
            name = "resolve_review_comment",
            description = "Mark a user review comment resolved once you've addressed it in the code.",
            security = SecurityType.TASK_MANAGE,
            gating = Gating.AUTO,
            roles = AgentRole.TASK)
    public ToolOutcome resolveReviewComment(ResolveReviewCommentArgs args, ToolCall call)
    {
        String raw = args == null ? null : args.commentId();
        UUID commentId;
        try {
            commentId = UUID.fromString(raw);
        }
        catch (IllegalArgumentException | NullPointerException e) {
            return ToolOutcome.Completed.error("Invalid comment_id: " + raw);
        }
        ReviewComment remoteComment = stageStore.findReviewCommentById(commentId).orElse(null);
        if (remoteComment == null) {
            return ToolOutcome.Completed.error("No review comment: " + commentId);
        }
        if (!belongsToActiveRound(call, remoteComment)) {
            return ToolOutcome.Completed.error(
                    "comment does not belong to this task's active review round");
        }
        reviewComments.resolve(commentId);
        return ToolOutcome.Completed.ok("Review comment " + commentId + " marked resolved.");
    }

    private boolean belongsToActiveRound(ToolCall call, ReviewComment comment)
    {
        if (call.scope() == ThreadScope.TRUNK || !call.requireTaskId().equals(comment.taskId())
                || comment.roundId() == null) {
            return false;
        }
        return roundStore.findLiveByTask(call.requireTaskId())
                .filter(round -> round.id().equals(comment.roundId().toString()))
                .filter(round -> ReviewRound.STATUS_ADDRESSING.equals(round.status()))
                .filter(round -> call.agentRunId() == null
                        || call.agentRunId().equals(round.runId()))
                .isPresent();
    }
}
