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
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.localpr.PRService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.google.common.base.Strings.nullToEmpty;
import static java.util.Objects.requireNonNull;

/** Public Local Review boundary. V2 state is mutated only by its typed owner. */
@Service
public class ReviewCommentServiceImpl
{
    public record SubmitResult(int submitted, String turnId) {}

    private final StageStore stageStore;
    private final PRService prService;
    private final TaskStore taskStore;
    private V2LocalReviewControl v2LocalReview;

    public ReviewCommentServiceImpl(
            StageStore stageStore,
            PRService prService,
            TaskStore taskStore)
    {
        this.stageStore = requireNonNull(stageStore, "stageStore is null");
        this.prService = requireNonNull(prService, "prService is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
    }

    @Autowired(required = false)
    void setV2LocalReview(V2LocalReviewControl v2LocalReview)
    {
        this.v2LocalReview = requireNonNull(v2LocalReview, "v2LocalReview is null");
    }

    public SubmitResult submitReview(String taskId, String body, String verdict)
    {
        return submitReview(taskId, body, verdict, null);
    }
    public PRComment add(
            String taskId, String file, int line, String side,
            Integer startLine, String startSide, String body)
    {
        String taskIdValue = required(taskId, "taskId");
        String fileValue = required(file, "file");
        String bodyValue = required(body, "body");
        if (line <= 0) {
            throw status(400, "line must be positive");
        }
        requireV2Task(taskIdValue);
        V2LocalReviewControl localReview = requireV2LocalReview();
        PR pr = prService.findByTask(taskIdValue)
                .orElseThrow(() -> status(
                        409, "task " + taskIdValue + " has no Local Review pull request"));
        return localReview.addComment(
                pr,
                PRComment.ORIGIN_LOCAL,
                PRComment.SCOPE_FILE_LINE,
                fileValue,
                line,
                side,
                startLine,
                startSide,
                PRTimelineEntry.ACTOR_USER,
                bodyValue,
                null);
    }

    /** Historical comments remain readable after the hard cutover. */
    public List<PRComment> list(String taskId)
    {
        return prService.findByTask(nullToEmpty(taskId).strip())
                .map(pr -> prService.comments(pr.id()).stream()
                        .filter(c -> PRComment.SCOPE_FILE_LINE.equals(c.scope()))
                        .toList())
                .orElse(List.of());
    }
    public void resolve(UUID commentId)
    {
        requireNonNull(commentId, "commentId is null");
        String id = commentId.toString();
        if (v2LocalReview != null && v2LocalReview.ownsComment(id)) {
            v2LocalReview.resolveComment(id);
            return;
        }
        rejectLegacyReviewComment(commentId);
        try {
            // Taskless and imported PR comments are not lifecycle state and
            // continue to use the generic PR store.
            prService.resolveComment(id);
        }
        catch (IllegalArgumentException ignored) {
            // Resolving a stale/unknown id remains idempotent for callers.
        }
    }
    public void reopen(UUID commentId)
    {
        requireNonNull(commentId, "commentId is null");
        String id = commentId.toString();
        if (v2LocalReview != null && v2LocalReview.ownsComment(id)) {
            v2LocalReview.reopenComment(id);
            return;
        }
        rejectLegacyReviewComment(commentId);
        try {
            prService.reopenComment(id);
        }
        catch (IllegalArgumentException ignored) {
            // See resolve(UUID).
        }
    }
    public SubmitResult submitReview(
            String taskId, String body, String verdict, List<String> commentIds)
    {
        String taskIdValue = required(taskId, "taskId");
        requireV2Task(taskIdValue);
        V2LocalReviewControl.Submission submitted = requireV2LocalReview().submit(
                taskIdValue, nullToEmpty(body).strip(), verdict, commentIds);
        return new SubmitResult(submitted.submitted(), submitted.turnId());
    }

    private void requireV2Task(String taskId)
    {
        if (taskStore.findTaskById(taskId).isEmpty()) {
            throw status(404, "no task " + taskId);
        }
        if (!taskStore.isV2Task(taskId)) {
            throw legacyReviewMutationRetired(taskId);
        }
    }

    private V2LocalReviewControl requireV2LocalReview()
    {
        if (v2LocalReview == null) {
            throw status(503, "V2 Local Review is not configured");
        }
        return v2LocalReview;
    }

    private void rejectLegacyReviewComment(UUID commentId)
    {
        Optional<ReviewComment> comment = stageStore.findReviewCommentById(commentId);
        if (comment.isPresent()) {
            throw legacyReviewMutationRetired(comment.orElseThrow().taskId());
        }
    }

    private static ResponseStatusException legacyReviewMutationRetired(String taskId)
    {
        return status(409, "LEGACY task " + taskId
                + " is historical and its review state is read-only");
    }

    private static String required(String value, String name)
    {
        String normalized = nullToEmpty(value).strip();
        if (normalized.isEmpty()) {
            throw status(400, name + " is required");
        }
        return normalized;
    }

    private static ResponseStatusException status(int code, String message)
    {
        return new ResponseStatusException(HttpStatusCode.valueOf(code), message);
    }
}
