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
package com.bytequay.core.discussion;

import java.time.Instant;
import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * One reviewer comment that Auto-Review fix handed to a task's agent, and what became of it. The agent's reply reaches
 * GitHub only after its fix is pushed. A post is marked started before it is sent, so a post whose outcome is unknown is
 * reported instead of repeated.
 *
 * @param requestKey the idempotency key of the input that asked the agent to fix this comment
 * @param rootCommentId the comment that opens the review thread, which replies and resolution address
 */
public record ReviewFixReply(
        String taskId,
        String repo,
        int number,
        long rootCommentId,
        long commentId,
        String requestKey,
        Instant requestedAt,
        Optional<String> reply,
        boolean fixed,
        Optional<Instant> postStartedAt,
        Optional<Instant> postedAt,
        Optional<Instant> resolvedAt,
        Optional<String> failure)
{
    public ReviewFixReply
    {
        checkText(taskId, "taskId");
        checkText(repo, "repo");
        checkArgument(number > 0 && rootCommentId > 0 && commentId > 0, "number and comment ids must be positive");
        checkText(requestKey, "requestKey");
        requireNonNull(requestedAt, "requestedAt is null");
        requireNonNull(reply, "reply is null");
        requireNonNull(postStartedAt, "postStartedAt is null");
        requireNonNull(postedAt, "postedAt is null");
        requireNonNull(resolvedAt, "resolvedAt is null");
        requireNonNull(failure, "failure is null");
    }

    public static ReviewFixReply requested(String taskId, String repo, int number, long rootCommentId, long commentId, String requestKey, Instant now)
    {
        return new ReviewFixReply(taskId, repo, number, rootCommentId, commentId, requestKey, now, Optional.empty(), false,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }

    public String id()
    {
        return taskId + ":" + commentId;
    }

    /** Asked of the agent and not yet answered, posted or given up. */
    public boolean waiting()
    {
        return postedAt.isEmpty() && failure.isEmpty();
    }

    public ReviewFixReply answered(String body, boolean fixed)
    {
        return new ReviewFixReply(taskId, repo, number, rootCommentId, commentId, requestKey, requestedAt, Optional.of(body), fixed,
                postStartedAt, postedAt, resolvedAt, failure);
    }

    public ReviewFixReply postStarted(Instant now)
    {
        return new ReviewFixReply(taskId, repo, number, rootCommentId, commentId, requestKey, requestedAt, reply, fixed,
                Optional.of(now), postedAt, resolvedAt, failure);
    }

    public ReviewFixReply posted(Instant now)
    {
        return new ReviewFixReply(taskId, repo, number, rootCommentId, commentId, requestKey, requestedAt, reply, fixed,
                postStartedAt, Optional.of(now), resolvedAt, failure);
    }

    public ReviewFixReply resolved(Instant now)
    {
        return new ReviewFixReply(taskId, repo, number, rootCommentId, commentId, requestKey, requestedAt, reply, fixed,
                postStartedAt, postedAt, Optional.of(now), failure);
    }

    public ReviewFixReply failed(String reason)
    {
        return new ReviewFixReply(taskId, repo, number, rootCommentId, commentId, requestKey, requestedAt, reply, fixed,
                postStartedAt, postedAt, resolvedAt, Optional.of(reason));
    }
}
