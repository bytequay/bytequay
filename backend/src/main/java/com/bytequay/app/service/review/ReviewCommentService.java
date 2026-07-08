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

import java.util.List;
import java.util.UUID;

/**
 * Local pre-push review comments on a Task's diff. The user leaves inline
 * comments before the branch is pushed; submitting them steers the task's
 * dev agent (a turn on its dev thread) to address each one in the code, and
 * the agent marks them resolved as it goes (the {@code resolve_review_comment}
 * tool). All comments here are {@code LOCAL_USER}-sourced.
 */
public interface ReviewCommentService
{
    /** The handle {@link #submitReview} returns: how many unresolved
     *  local comments were bundled into the steering turn, and the
     *  enqueued turn id (null when there was nothing to submit). */
    record SubmitResult(int submitted, String turnId) {}

    /** Add a new {@code LOCAL_USER} review comment on {@code taskId}'s diff
     *  at {@code file}:{@code line}; returns the persisted row. */
    ReviewComment add(String taskId, String file, int line, String body);

    /** Every review comment on the task, oldest-first, for the diff page. */
    List<ReviewComment> list(String taskId);

    /** Mark a comment resolved. No-op when the id is unknown. */
    void resolve(UUID commentId);

    /** Re-open a resolved comment. No-op when the id is unknown. */
    void reopen(UUID commentId);

    /**
     * Bundle the task's unresolved {@code LOCAL_USER} comments — plus an
     * optional top-level {@code body} and {@code verdict} label ({@code
     * COMMENT}/{@code APPROVE}/{@code REQUEST_CHANGES}) — into a markdown
     * turn and steer the task's active development stage's dev agent to
     * address them. No-op (0 submitted, null turn) when there are no
     * unresolved local comments and {@code body} is blank.
     */
    SubmitResult submitReview(String taskId, String body, String verdict);
}
