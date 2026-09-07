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
package com.bytequay.core.review;

import java.time.Instant;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * One private discussion message on the summary or on a finding.
 *
 * @param findingId the finding discussed, or empty for the summary discussion
 * @param queuedInputId the queue row carrying a user question while the agent was busy; a question
 *        with a queue row and no turn is still waiting
 */
public record ReviewMessage(
        String id,
        String reviewId,
        Optional<String> findingId,
        Author author,
        String body,
        int targetRevision,
        String runId,
        Optional<String> turnId,
        Optional<String> queuedInputId,
        long version,
        Instant createdAt)
{
    public ReviewMessage
    {
        requireNonNull(id, "id is null");
        requireNonNull(reviewId, "reviewId is null");
        requireNonNull(findingId, "findingId is null");
        requireNonNull(author, "author is null");
        requireNonNull(body, "body is null");
        checkArgument(targetRevision > 0, "targetRevision is not positive");
        requireNonNull(runId, "runId is null");
        requireNonNull(turnId, "turnId is null");
        requireNonNull(queuedInputId, "queuedInputId is null");
        checkArgument(version >= 0, "version is negative");
        requireNonNull(createdAt, "createdAt is null");
    }

    public ReviewMessage withVersion(long updatedVersion)
    {
        return new ReviewMessage(id, reviewId, findingId, author, body, targetRevision, runId, turnId, queuedInputId, updatedVersion, createdAt);
    }

    public ReviewMessage withTurn(String startedTurnId)
    {
        return new ReviewMessage(id, reviewId, findingId, author, body, targetRevision, runId, Optional.of(startedTurnId), queuedInputId, version, createdAt);
    }

    public enum Author
    {
        USER,
        AGENT
    }
}
