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
import lombok.AccessLevel;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * One finding with its stable identity, its frozen location, and the local decisions made
 * about it. Agent revisions move {@code revision}; a user resolution is a state no agent can undo.
 *
 * @param anchor the diff lines the finding is about, or empty for a finding about the whole pull
 *        request, such as a commit message or the description, which has no lines
 * @param validatedHeadSha the head at which the agent last supported this claim with evidence;
 *        mapping its anchor to a later head does not advance that verification
 * @param outdated whether the finding needs reassessment or a new anchor for a later review
 * @param resolvedByTaskId the development task whose agent resolved the finding after fixing it;
 *        empty when the user resolved it and while it is not resolved
 */
@Builder(builderClassName = "Draft", builderMethodName = "", toBuilder = true, access = AccessLevel.PRIVATE)
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
        Origin origin,
        Optional<String> resolvedByTaskId)
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
        requireNonNull(resolvedByTaskId, "resolvedByTaskId is null");
        checkArgument(resolvedByTaskId.isEmpty() || state == State.RESOLVED, "Only a resolved finding names the development task that resolved it");
    }

    /** A finding no development agent resolved. */
    public ReviewFinding(
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
        this(id, reviewId, runId, ordinal, title, body, severity, anchor, evidence, revision, state, stateReason, validatedHeadSha, outdated, version, createdAt, updatedAt,
                memories, origin, Optional.empty());
    }

    /** The label shown on cards, derived from the review-local ordinal. */
    public String label()
    {
        return "F-" + ordinal;
    }

    /** The memories the reviewer cited when it saved this revision. */
    public ReviewFinding withMemories(List<MemoryCitation> cited)
    {
        return toBuilder().memories(cited).build();
    }

    public ReviewFinding withVersion(long updatedVersion)
    {
        return toBuilder().version(updatedVersion).build();
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
        return toBuilder()
                .title(updatedTitle)
                .body(updatedBody)
                .severity(updatedSeverity)
                .anchor(updatedAnchor)
                .evidence(updatedEvidence)
                .revision(revision + 1)
                .validatedHeadSha(verifiedHeadSha)
                .outdated(false)
                .updatedAt(now)
                .build();
    }

    /** A state the user or the reviewer sets, so the finding names no development task afterwards. */
    public ReviewFinding withState(State updatedState, Optional<String> reason, Instant now)
    {
        return toBuilder().state(updatedState).stateReason(reason).updatedAt(now).resolvedByTaskId(Optional.empty()).build();
    }

    /** Resolved by the agent of the development task that fixed it; the task's own reply on the finding says what changed. */
    public ReviewFinding resolvedByDeveloper(String developerTaskId, Instant now)
    {
        return toBuilder().state(State.RESOLVED).stateReason(Optional.empty()).updatedAt(now).resolvedByTaskId(Optional.of(developerTaskId)).build();
    }

    /** A new source scope requires another conclusion even when the location maps exactly. */
    public ReviewFinding pendingReassessment(Instant now)
    {
        return toBuilder().outdated(true).updatedAt(now).build();
    }

    /**
     * Moves the finding to a head that carries the identical change: the same conclusion at the same revision, located
     * where its lines sit in that head's diff. Nothing was reassessed, so nothing else about the finding changes.
     */
    public ReviewFinding carried(Optional<ReviewAnchor> carriedAnchor, String headSha, Instant now)
    {
        return toBuilder().anchor(carriedAnchor).validatedHeadSha(headSha).outdated(false).updatedAt(now).build();
    }

    public enum Origin
    {
        AUTOMATIC,
        DISCUSSION
    }

    public enum State
    {
        OPEN,
        /** Resolved locally by the user, confirmed publication, or a development agent's fix; cannot be newly promoted, but existing user drafts remain sendable. */
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
