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

import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * One inline finding with its stable identity, its frozen location, and the local decisions made
 * about it. Agent revisions move {@code revision}; a user resolution is a state the agent cannot undo.
 *
 * @param validatedHeadSha the head at which the anchor last mapped deterministically; a later head
 *        without a proved mapping leaves the finding outdated and unpublishable
 */
public record ReviewFinding(
        String id,
        String reviewId,
        String runId,
        int ordinal,
        String title,
        String body,
        Optional<String> severity,
        ReviewAnchor anchor,
        List<ReviewEvidence> evidence,
        int revision,
        State state,
        Optional<String> stateReason,
        String validatedHeadSha,
        boolean outdated,
        long version,
        Instant createdAt,
        Instant updatedAt)
{
    public ReviewFinding
    {
        requireNonNull(id, "id is null");
        requireNonNull(reviewId, "reviewId is null");
        requireNonNull(runId, "runId is null");
        checkArgument(ordinal > 0, "ordinal is not positive");
        requireNonNull(title, "title is null");
        requireNonNull(body, "body is null");
        requireNonNull(severity, "severity is null");
        requireNonNull(anchor, "anchor is null");
        evidence = ImmutableList.copyOf(evidence);
        checkArgument(revision > 0, "revision is not positive");
        requireNonNull(state, "state is null");
        requireNonNull(stateReason, "stateReason is null");
        requireNonNull(validatedHeadSha, "validatedHeadSha is null");
        checkArgument(version >= 0, "version is negative");
        requireNonNull(createdAt, "createdAt is null");
        requireNonNull(updatedAt, "updatedAt is null");
    }

    /** The label shown on cards, derived from the review-local ordinal. */
    public String label()
    {
        return "F-" + ordinal;
    }

    public ReviewFinding withVersion(long updatedVersion)
    {
        return new ReviewFinding(
                id,
                reviewId,
                runId,
                ordinal,
                title,
                body,
                severity,
                anchor,
                evidence,
                revision,
                state,
                stateReason,
                validatedHeadSha,
                outdated,
                updatedVersion,
                createdAt,
                updatedAt);
    }

    /** An agent revision: new content at the next revision, in the same place and state. */
    public ReviewFinding revised(String updatedTitle, String updatedBody, Optional<String> updatedSeverity, List<ReviewEvidence> updatedEvidence, Instant now)
    {
        return new ReviewFinding(
                id,
                reviewId,
                runId,
                ordinal,
                updatedTitle,
                updatedBody,
                updatedSeverity,
                anchor,
                updatedEvidence,
                revision + 1,
                state,
                stateReason,
                validatedHeadSha,
                outdated,
                version,
                createdAt,
                now);
    }

    public ReviewFinding withState(State updatedState, Optional<String> reason, Instant now)
    {
        return new ReviewFinding(
                id,
                reviewId,
                runId,
                ordinal,
                title,
                body,
                severity,
                anchor,
                evidence,
                revision,
                updatedState,
                reason,
                validatedHeadSha,
                outdated,
                version,
                createdAt,
                now);
    }

    public ReviewFinding withMapping(String headSha, boolean nowOutdated, Instant now)
    {
        return new ReviewFinding(
                id,
                reviewId,
                runId,
                ordinal,
                title,
                body,
                severity,
                anchor,
                evidence,
                revision,
                state,
                stateReason,
                headSha,
                nowOutdated,
                version,
                createdAt,
                now);
    }

    public enum State
    {
        OPEN,
        /** Resolved locally by the user; excluded from publication until explicitly reopened. */
        RESOLVED,
        /** Withdrawn by the agent with an explanation; never publishable again. */
        WITHDRAWN
    }
}
