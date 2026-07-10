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
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageState;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.localpr.PRService;
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
    private final PRService prService;
    private final TaskStore taskStore;

    public ReviewCommentServiceImpl(
            StageStore stageStore,
            StageSteeringService steering,
            ReviewRoundService reviewRounds,
            PRService prService,
            TaskStore taskStore)
    {
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.steering = requireNonNull(steering, "steering is null");
        this.reviewRounds = requireNonNull(reviewRounds, "reviewRounds is null");
        this.prService = requireNonNull(prService, "prService is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
    }

    @Override
    public PRComment add(
            String taskId, String file, int line, String side, Integer startLine, String startSide, String body)
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
        PR pr = localPrForTask(taskIdValue);
        return prService.addComment(
                pr.id(),
                PRComment.ORIGIN_LOCAL,
                PRComment.SCOPE_FILE_LINE,
                fileValue,
                line,
                side,
                startLine,
                startSide,
                PRTimelineEntry.ACTOR_USER,
                bodyValue,
                /* parentCommentId */ null);
    }

    @Override
    public List<PRComment> list(String taskId)
    {
        return prService.findByTask(nullToEmpty(taskId).strip())
                .map(pr -> prService.comments(pr.id()).stream()
                        .filter(c -> PRComment.SCOPE_FILE_LINE.equals(c.scope()))
                        .toList())
                .orElse(List.of());
    }

    @Override
    public void resolve(UUID commentId)
    {
        requireNonNull(commentId, "commentId is null");
        if (resolveLegacyReviewComment(commentId, true)) {
            return;
        }
        try {
            prService.resolveComment(commentId.toString());
        }
        catch (IllegalArgumentException ignored) {
            // Preserve the legacy review_comment behaviour: resolving an
            // unknown id is a no-op, so stale UI/tool calls do not fail a turn.
        }
    }

    @Override
    public void reopen(UUID commentId)
    {
        requireNonNull(commentId, "commentId is null");
        if (resolveLegacyReviewComment(commentId, false)) {
            return;
        }
        try {
            prService.reopenComment(commentId.toString());
        }
        catch (IllegalArgumentException ignored) {
            // See resolve(UUID).
        }
    }

    /** A resolve/reopen on a legacy round-attached comment (the remote-reviewer
     *  batch a review_round is addressing) moves it between the round's
     *  open/fixed/replied buckets — keep the round's stored stats in step. */
    private boolean resolveLegacyReviewComment(UUID commentId, boolean resolved)
    {
        Optional<ReviewComment> existing = stageStore.findReviewCommentById(commentId);
        if (existing.isEmpty()) {
            return false;
        }
        stageStore.setReviewCommentResolved(commentId, resolved);
        Optional.ofNullable(existing.get().roundId())
                .ifPresent(roundId -> reviewRounds.recomputeStats(roundId.toString()));
        return true;
    }

    @Override
    public SubmitResult submitReview(String taskId, String body, String verdict)
    {
        String taskIdValue = nullToEmpty(taskId).strip();
        if (taskIdValue.isEmpty()) {
            throw status(400, "taskId is required");
        }
        String bodyValue = nullToEmpty(body).strip();
        PR pr = prService.findByTask(taskIdValue).orElse(null);
        List<PRComment> unresolved = pr == null ? List.of() : prService.comments(pr.id()).stream()
                .filter(ReviewCommentServiceImpl::isOpenUserComment)
                .toList();
        if (unresolved.isEmpty() && bodyValue.isEmpty()) {
            return new SubmitResult(0, null);
        }
        UUID devStageId = activeDevelopmentStage(taskIdValue)
                .orElseThrow(() -> status(422, "no active development stage for task " + taskIdValue))
                .id();
        String text = formatTurn(bodyValue, verdict, unresolved);
        StageSteeringService.SteerResult result = steering.steer(devStageId, text, null);
        newestCreatedAt(unresolved).ifPresent(through -> prService.markLocalAddressed(pr.id(), through));
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

    private PR localPrForTask(String taskId)
    {
        Optional<PR> existing = prService.findByTask(taskId);
        if (existing.isPresent()) {
            return existing.get();
        }
        Task task = taskStore.findTaskById(taskId)
                .orElseThrow(() -> status(404, "no task " + taskId));
        if (task.branchName() == null || task.branchName().isBlank()) {
            throw status(422, "task " + taskId + " has no branch yet");
        }
        String base = task.baseBranch() == null || task.baseBranch().isBlank() ? "main" : task.baseBranch();
        String title = task.name() == null || task.name().isBlank() ? task.branchName() : task.name();
        return prService.createForTask(task.id(), task.branchName(), base, title, "");
    }

    private static boolean isOpenUserComment(PRComment comment)
    {
        return PRComment.ORIGIN_LOCAL.equals(comment.origin())
                && PRTimelineEntry.ACTOR_USER.equals(comment.author())
                && comment.parentCommentId() == null
                && comment.resolvedAt() == null
                && comment.dismissedAt() == null;
    }

    private static Optional<Instant> newestCreatedAt(List<PRComment> comments)
    {
        return comments.stream()
                .map(PRComment::createdAt)
                .filter(Objects::nonNull)
                .max(Instant::compareTo);
    }

    private static String formatTurn(String body, String verdict, List<PRComment> comments)
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
            for (PRComment c : comments) {
                sb.append("- ").append(c.id()).append(" `")
                        .append(c.filePath()).append(':').append(c.lineNumber()).append("` - ")
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
