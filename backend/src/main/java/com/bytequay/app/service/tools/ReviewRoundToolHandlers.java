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
import com.bytequay.app.repository.ReviewRoundStore;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.service.review.RoundGateSaga;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * The {@code record_round_reply} agent tool — a {@code review_round} run
 * drafts a reply to a remote review comment. Local-only: nothing posts to
 * GitHub until the round's gate approval reads {@code draft_reply_body} and
 * calls {@code PullRequestService.replyToReviewThread}.
 */
@Component
public class ReviewRoundToolHandlers
{
    private final StageStore stageStore;
    private final ReviewRoundStore roundStore;
    private final RoundGateSaga roundGate;

    public ReviewRoundToolHandlers(
            StageStore stageStore,
            ReviewRoundStore roundStore,
            RoundGateSaga roundGate)
    {
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.roundStore = requireNonNull(roundStore, "roundStore is null");
        this.roundGate = requireNonNull(roundGate, "roundGate is null");
    }

    /** Args record for {@code record_round_reply}. */
    public record RecordRoundReplyArgs(
            @ToolParam(description = "Id of the review comment you're replying to.",
                    required = true, wireName = "comment_id") String commentId,
            @ToolParam(description = "Markdown reply body. Held locally until the user approves "
                    + "the round.", required = true) String body) {}

    @AgentTool(
            name = "record_round_reply",
            description = "Draft a reply to a remote review comment as part of the current review "
                    + "round. Nothing posts to GitHub until the user approves the round's gate.",
            security = SecurityType.TASK_MANAGE,
            gating = Gating.AUTO,
            roles = AgentRole.TASK)
    public ToolOutcome recordRoundReply(RecordRoundReplyArgs args, ToolCall call)
    {
        String raw = args == null ? null : args.commentId();
        String body = args == null ? null : args.body();
        if (body == null || body.isBlank()) {
            return ToolOutcome.Completed.error("body is required");
        }
        UUID commentId;
        try {
            commentId = UUID.fromString(raw);
        }
        catch (IllegalArgumentException | NullPointerException e) {
            return ToolOutcome.Completed.error("Invalid comment_id: " + raw);
        }
        Optional<ReviewComment> existing = stageStore.findReviewCommentById(commentId);
        if (existing.isEmpty()) {
            return ToolOutcome.Completed.error("no review comment: " + commentId);
        }
        ReviewComment comment = existing.get();
        if (!belongsToActiveRound(call, comment)) {
            return ToolOutcome.Completed.error(
                    "comment does not belong to this task's active review round");
        }
        try {
            roundGate.editPayload(comment.taskId(), comment.roundId().toString(), (Runnable) () -> {
                ReviewComment current = stageStore.findReviewCommentById(commentId)
                        .orElseThrow(() -> conflict("review comment changed while drafting its reply"));
                if (!belongsToActiveRound(call, current)
                        || !comment.roundId().equals(current.roundId())) {
                    throw conflict("review comment changed while drafting its reply");
                }
                stageStore.saveReviewComment(new ReviewComment(
                        current.id(), current.taskId(), current.file(), current.line(), current.body(),
                        current.createdAt(), current.source(), current.remoteLink(), current.resolved(),
                        current.remoteCommentId(), current.roundId(), body, Instant.now(),
                        current.side(), current.startLine(), current.startSide()));
            });
        }
        catch (ResponseStatusException e) {
            return ToolOutcome.Completed.error(e.getReason() == null ? e.getMessage() : e.getReason());
        }
        return ToolOutcome.Completed.ok("Drafted reply on comment " + commentId);
    }

    private static ResponseStatusException conflict(String reason)
    {
        return new ResponseStatusException(HttpStatus.CONFLICT, reason);
    }

    private boolean belongsToActiveRound(ToolCall call, ReviewComment comment)
    {
        if (call.taskId() == null || !call.taskId().equals(comment.taskId())
                || comment.roundId() == null) {
            return false;
        }
        return roundStore.findLiveByTask(call.taskId())
                .filter(round -> round.id().equals(comment.roundId().toString()))
                .filter(round -> ReviewRound.STATUS_ADDRESSING.equals(round.status()))
                .filter(round -> call.agentRunId() == null
                        || call.agentRunId().equals(round.runId()))
                .isPresent();
    }
}
