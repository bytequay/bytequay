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

import com.bytequay.app.domain.PRComment;

import java.util.List;
import java.util.UUID;

/**
 * Local pre-push review comments on a Task's PR diff. The user leaves inline
 * comments before the branch is pushed; submitting them steers the task's
 * dev agent (a turn on its dev thread) to address each one in the code, and
 * the agent marks them resolved as it goes. Local comments are stored on the
 * PR aggregate ({@link PRComment}) so the task diff and local PR screens share
 * one comment source.
 */
public interface ReviewCommentService
{
    /** The handle {@link #submitReview} returns: how many unresolved
     *  local comments were bundled into the steering turn, and the
     *  enqueued turn id (null when there was nothing to submit). */
    record SubmitResult(int submitted, String turnId) {}

    /** Add a new user-authored local PR comment on {@code taskId}'s diff at
     *  {@code file}:{@code line}; returns the persisted PR comment row.
     *  {@code side} is {@code LEFT}/{@code RIGHT} (null defaults to RIGHT);
     *  {@code startLine}/{@code startSide} are set only for a multi-line
     *  range. */
    PRComment add(
            String taskId, String file, int line, String side, Integer startLine, String startSide, String body);

    /** Every inline local PR comment on the task, oldest-first, for the diff page. */
    List<PRComment> list(String taskId);

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
