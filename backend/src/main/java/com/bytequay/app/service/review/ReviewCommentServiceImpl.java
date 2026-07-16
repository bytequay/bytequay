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
import com.bytequay.app.domain.Task;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.localpr.PRPublishService;
import com.bytequay.app.service.localpr.PRService;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.google.common.base.Strings.nullToEmpty;
import static java.util.Objects.requireNonNull;

@Service
public class ReviewCommentServiceImpl
        implements ReviewCommentService
{
    private final StageStore stageStore;
    private final ReviewRoundService reviewRounds;
    private final PRService prService;
    private final PRPublishService publish;
    private final TaskStore taskStore;

    public ReviewCommentServiceImpl(
            StageStore stageStore,
            ReviewRoundService reviewRounds,
            PRService prService,
            PRPublishService publish,
            TaskStore taskStore)
    {
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.reviewRounds = requireNonNull(reviewRounds, "reviewRounds is null");
        this.prService = requireNonNull(prService, "prService is null");
        this.publish = requireNonNull(publish, "publish is null");
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
        PR pr = prService.findByTask(taskIdValue)
                .orElseThrow(() -> status(409, "task " + taskIdValue + " has no pull request to review"));
        List<PRComment> unresolved = prService.comments(pr.id()).stream()
                .filter(ReviewCommentServiceImpl::isOpenUserComment)
                .toList();
        String event = switch (nullToEmpty(verdict).strip()) {
            case "APPROVE" -> "APPROVE";
            case "REQUEST_CHANGES" -> "REQUEST_CHANGES";
            default -> "COMMENT";
        };
        if (unresolved.isEmpty() && bodyValue.isEmpty() && !"APPROVE".equals(event)) {
            return new SubmitResult(0, null);
        }
        publish.publishReview(pr.id(), event, List.of(),
                unresolved.stream().map(PRComment::id).toList(), bodyValue);
        return new SubmitResult(unresolved.size(), null);
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

    private static ResponseStatusException status(int code, String message)
    {
        return new ResponseStatusException(HttpStatusCode.valueOf(code), message);
    }
}
