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

import com.bytequay.app.domain.ReviewComment;
import com.bytequay.app.domain.ReviewCommentSource;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageState;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.service.stage.StageSteeringService;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import static com.google.common.base.Strings.nullToEmpty;
import static java.util.Objects.requireNonNull;

@Service
public class ReviewCommentServiceImpl
        implements ReviewCommentService
{
    private final StageStore stageStore;
    private final StageSteeringService steering;
    private final ReviewRoundService reviewRounds;

    public ReviewCommentServiceImpl(
            StageStore stageStore, StageSteeringService steering, ReviewRoundService reviewRounds)
    {
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.steering = requireNonNull(steering, "steering is null");
        this.reviewRounds = requireNonNull(reviewRounds, "reviewRounds is null");
    }

    @Override
    public ReviewComment add(String taskId, String file, int line, String body)
    {
        String taskIdValue = nullToEmpty(taskId).strip();
        String fileValue = nullToEmpty(file).strip();
        String bodyValue = nullToEmpty(body).strip();
        if (taskIdValue.isEmpty()) {
            throw status(400, "taskId is required");
        }
        if (fileValue.isEmpty()) {
            throw status(400, "file is required");
        }
        if (line <= 0) {
            throw status(400, "line must be positive");
        }
        if (bodyValue.isEmpty()) {
            throw status(400, "body is required");
        }
        ReviewComment comment = new ReviewComment(
                UUID.randomUUID(),
                taskIdValue,
                fileValue,
                line,
                bodyValue,
                Instant.now(),
                ReviewCommentSource.LOCAL_USER,
                null,
                false,
                null,
                null,
                null,
                null);
        return stageStore.saveReviewComment(comment);
    }

    @Override
    public List<ReviewComment> list(String taskId)
    {
        return stageStore.findCommentsByTask(nullToEmpty(taskId).strip());
    }

    @Override
    public void resolve(UUID commentId)
    {
        requireNonNull(commentId, "commentId is null");
        stageStore.setReviewCommentResolved(commentId, true);
        recomputeOwningRoundStats(commentId);
    }

    @Override
    public void reopen(UUID commentId)
    {
        requireNonNull(commentId, "commentId is null");
        stageStore.setReviewCommentResolved(commentId, false);
        recomputeOwningRoundStats(commentId);
    }

    /** A resolve/reopen on a round-attached comment (the remote-reviewer
     *  batch a review_round is addressing) moves it between the round's
     *  open/fixed/replied buckets — keep the round's stored stats in step. */
    private void recomputeOwningRoundStats(UUID commentId)
    {
        stageStore.findReviewCommentById(commentId)
                .map(ReviewComment::roundId)
                .filter(Objects::nonNull)
                .ifPresent(roundId -> reviewRounds.recomputeStats(roundId.toString()));
    }

    @Override
    public SubmitResult submitReview(String taskId, String body, String verdict)
    {
        String taskIdValue = nullToEmpty(taskId).strip();
        if (taskIdValue.isEmpty()) {
            throw status(400, "taskId is required");
        }
        String bodyValue = nullToEmpty(body).strip();
        List<ReviewComment> unresolved = stageStore.findCommentsBySource(taskIdValue, ReviewCommentSource.LOCAL_USER)
                .stream()
                .filter(c -> !c.resolved())
                .toList();
        if (unresolved.isEmpty() && bodyValue.isEmpty()) {
            return new SubmitResult(0, null);
        }
        UUID devStageId = activeDevelopmentStage(taskIdValue)
                .orElseThrow(() -> status(422, "no active development stage for task " + taskIdValue))
                .id();
        String text = formatTurn(bodyValue, verdict, unresolved);
        StageSteeringService.SteerResult result = steering.steer(devStageId, text);
        return new SubmitResult(unresolved.size(), result.turnId());
    }

    /** The latest OPEN/ACTIVE {@code DEVELOPMENT_STAGE} for the task — the
     *  one whose dev agent is still implementing and so can act on the
     *  comments before the branch is pushed. */
    private Optional<StageInstance> activeDevelopmentStage(String taskId)
    {
        StageInstance found = null;
        for (StageInstance stage : stageStore.findStagesByTask(taskId)) {
            if (stage.type() == StageType.DEVELOPMENT_STAGE
                    && (stage.state() == StageState.OPEN || stage.state() == StageState.ACTIVE)) {
                // findStagesByTask is oldest-first; keep walking so we land
                // on the most recent open dev stage.
                found = stage;
            }
        }
        return Optional.ofNullable(found);
    }

    private static String formatTurn(String body, String verdict, List<ReviewComment> comments)
    {
        StringBuilder sb = new StringBuilder();
        String verdictValue = nullToEmpty(verdict).strip();
        if (!verdictValue.isEmpty()) {
            sb.append("Review verdict: ").append(verdictLabel(verdictValue)).append('\n');
        }
        if (!body.isEmpty()) {
            sb.append(body).append('\n');
        }
        if (!comments.isEmpty()) {
            if (sb.length() > 0) {
                sb.append('\n');
            }
            sb.append("Address these review comments before shipping:\n");
            for (ReviewComment c : comments) {
                sb.append("- ").append(c.id()).append(" `")
                        .append(c.file()).append(':').append(c.line()).append("` - ")
                        .append(c.body().strip()).append('\n');
            }
            sb.append("\nMark each one resolved with the resolve_review_comment tool "
                    + "once you've addressed it in the code.\n");
        }
        return sb.toString();
    }

    /** Maps raw verdict strings to agent-facing labels. */
    private static String verdictLabel(String verdict)
    {
        return switch (verdict) {
            case "APPROVE" -> "Approve";
            case "REQUEST_CHANGES" -> "Request changes";
            default -> "Comment";
        };
    }

    private static ResponseStatusException status(int code, String message)
    {
        return new ResponseStatusException(HttpStatusCode.valueOf(code), message);
    }
}
