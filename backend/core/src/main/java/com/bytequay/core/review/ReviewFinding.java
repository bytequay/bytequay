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
 * One finding with its stable identity, its frozen location, and the local decisions made
 * about it. Agent revisions move {@code revision}; a user resolution is a state the agent cannot undo.
 *
 * @param anchor the diff lines the finding is about, or empty for a finding about the whole pull
 *        request, such as a commit message or the description, which has no lines
 * @param validatedHeadSha the head at which the agent last supported this claim with evidence;
 *        mapping its anchor to a later head does not advance that verification
 * @param outdated whether the finding needs reassessment or a new anchor for a later review
 */
public record ReviewFinding(
        String id,
        String reviewId,
        String runId,
        int ordinal,
        String title,
        String body,
        Optional<String> severity,
        Optional<ReviewAnchor> anchor,
        List<ReviewEvidence> evidence,
        int revision,
        State state,
        Optional<String> stateReason,
        String validatedHeadSha,
        boolean outdated,
        long version,
        Instant createdAt,
        Instant updatedAt,
        List<MemoryCitation> memories,
        Origin origin)
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
        memories = ImmutableList.copyOf(memories);
        requireNonNull(origin, "origin is null");
    }

    /** The label shown on cards, derived from the review-local ordinal. */
    public String label()
    {
        return "F-" + ordinal;
    }

    /** The memories the reviewer cited when it saved this revision. */
    public ReviewFinding withMemories(List<MemoryCitation> cited)
    {
        return new ReviewFinding(id, reviewId, runId, ordinal, title, body, severity, anchor, evidence, revision, state, stateReason, validatedHeadSha, outdated,
                version, createdAt, updatedAt, cited, origin);
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
                updatedAt,
                memories,
                origin);
    }

    /** An evidence-backed agent revision at the current reviewed scope clears pending reassessment. */
    public ReviewFinding revised(
            String updatedTitle,
            String updatedBody,
            Optional<String> updatedSeverity,
            Optional<ReviewAnchor> updatedAnchor,
            List<ReviewEvidence> updatedEvidence,
            String verifiedHeadSha,
            Instant now)
    {
        return new ReviewFinding(
                id,
                reviewId,
                runId,
                ordinal,
                updatedTitle,
                updatedBody,
                updatedSeverity,
                updatedAnchor,
                updatedEvidence,
                revision + 1,
                state,
                stateReason,
                verifiedHeadSha,
                false,
                version,
                createdAt,
                now,
                memories,
                origin);
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
                now,
                memories,
                origin);
    }

    /** A new source scope requires another conclusion even when the location maps exactly. */
    public ReviewFinding pendingReassessment(Instant now)
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
                true,
                version,
                createdAt,
                now,
                memories,
                origin);
    }

    public enum Origin
    {
        AUTOMATIC,
        DISCUSSION
    }

    public enum State
    {
        OPEN,
        /** Resolved locally by the user; cannot be newly promoted, but existing user drafts are independent. */
        RESOLVED,
        /** Withdrawn by the agent with an explanation; cannot be newly promoted. */
        WITHDRAWN
    }

    /**
     * A repository memory the finding cites, as the reviewer received it, so the card shows the rule even after it changes.
     *
     * @param line the memory's text and source, as delivered
     */
    public record MemoryCitation(String id, String line)
    {
        public MemoryCitation
        {
            requireNonNull(id, "id is null");
            requireNonNull(line, "line is null");
        }
    }
}
