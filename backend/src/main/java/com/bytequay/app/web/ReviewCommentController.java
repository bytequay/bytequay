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
package com.bytequay.app.web;

import com.bytequay.app.beans.review.AddReviewCommentRequest;
import com.bytequay.app.beans.review.ReviewCommentDto;
import com.bytequay.app.beans.review.SubmitReviewResponse;
import com.bytequay.app.service.review.ReviewCommentService;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

import static java.util.Objects.requireNonNull;

/**
 * REST surface for local pre-push review comments on a Task's diff. The
 * user leaves inline comments before the branch is pushed
 * ({@code POST /api/tasks/{taskId}/review-comments}), the diff page lists
 * them, resolve/reopen flip a single comment, and
 * {@code POST /api/tasks/{taskId}/submit-review} hands the unresolved set to
 * the task's dev agent as a steering turn.
 */
@RestController
public class ReviewCommentController
{
    private final ReviewCommentService reviewComments;

    public ReviewCommentController(ReviewCommentService reviewComments)
    {
        this.reviewComments = requireNonNull(reviewComments, "reviewComments is null");
    }

    @PostMapping("/api/tasks/{taskId}/review-comments")
    public ReviewCommentDto add(@PathVariable String taskId, @RequestBody AddReviewCommentRequest body)
    {
        if (body == null) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "request body is required");
        }
        int line = body.line() == null ? 0 : body.line();
        return ReviewCommentDto.from(reviewComments.add(taskId, body.file(), line, body.body()));
    }

    @GetMapping("/api/tasks/{taskId}/review-comments")
    public List<ReviewCommentDto> list(@PathVariable String taskId)
    {
        return reviewComments.list(taskId).stream().map(ReviewCommentDto::from).toList();
    }

    @PostMapping("/api/review-comments/{id}/resolve")
    public void resolve(@PathVariable String id)
    {
        reviewComments.resolve(parseId(id));
    }

    @PostMapping("/api/review-comments/{id}/reopen")
    public void reopen(@PathVariable String id)
    {
        reviewComments.reopen(parseId(id));
    }

    @PostMapping("/api/tasks/{taskId}/submit-review")
    public SubmitReviewResponse submitReview(@PathVariable String taskId)
    {
        ReviewCommentService.SubmitResult result = reviewComments.submitReview(taskId);
        return new SubmitReviewResponse(result.submitted(), result.turnId());
    }

    private static UUID parseId(String id)
    {
        try {
            return UUID.fromString(id);
        }
        catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(400), "invalid comment id: " + id);
        }
    }
}
