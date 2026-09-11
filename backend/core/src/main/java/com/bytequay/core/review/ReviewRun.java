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

import com.bytequay.core.domain.DiffFile;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * One attempt to review one frozen head. The diff is frozen with the run so anchors, snapshots and
 * context reads keep resolving after the pull request cache moves on.
 *
 * @param turnIds the review-request turns ByteQuay started for this run, in order; the transcript
 *        labels their input as ByteQuay's, not the user's
 * @param operations replay results by {@code tool:operationKey}, committed with the write they name
 * @param evidence the excerpts context reads handed the agent, by returned reference, so a finding can cite them
 */
public record ReviewRun(
        String id,
        String reviewId,
        int ordinal,
        Scope scope,
        String baseRef,
        String baseSha,
        String mergeBaseSha,
        String headSha,
        Optional<String> priorHeadSha,
        List<ReviewCommit> commits,
        List<DiffFile> diff,
        List<String> turnIds,
        State state,
        Optional<ReviewSummary> summary,
        Optional<ReviewCoverage> coverage,
        boolean completionRepairUsed,
        Map<String, ReviewOperation> operations,
        Map<String, ReviewEvidence> evidence,
        Optional<String> outcome,
        long version,
        Instant createdAt,
        Instant updatedAt,
        Optional<Instant> endedAt)
{
    public ReviewRun
    {
        requireNonNull(id, "id is null");
        requireNonNull(reviewId, "reviewId is null");
        checkArgument(ordinal > 0, "ordinal is not positive");
        requireNonNull(scope, "scope is null");
        requireNonNull(baseRef, "baseRef is null");
        requireNonNull(baseSha, "baseSha is null");
        requireNonNull(mergeBaseSha, "mergeBaseSha is null");
        requireNonNull(headSha, "headSha is null");
        requireNonNull(priorHeadSha, "priorHeadSha is null");
        commits = ImmutableList.copyOf(commits);
        diff = ImmutableList.copyOf(diff);
        turnIds = ImmutableList.copyOf(turnIds);
        requireNonNull(state, "state is null");
        requireNonNull(summary, "summary is null");
        requireNonNull(coverage, "coverage is null");
        operations = ImmutableMap.copyOf(operations);
        evidence = ImmutableMap.copyOf(evidence);
        requireNonNull(outcome, "outcome is null");
        checkArgument(version >= 0, "version is negative");
        requireNonNull(createdAt, "createdAt is null");
        requireNonNull(updatedAt, "updatedAt is null");
        requireNonNull(endedAt, "endedAt is null");
        checkArgument(state.terminal() == endedAt.isPresent(), "endedAt and state disagree");
    }

    public boolean active()
    {
        return !state.terminal();
    }

    public ReviewRun withVersion(long updatedVersion)
    {
        return copy(turnIds, state, summary, coverage, completionRepairUsed, operations, evidence, outcome, updatedVersion, updatedAt, endedAt);
    }

    public ReviewRun withTurn(String turnId, Instant now)
    {
        List<String> nextTurnIds = ImmutableList.<String>builder().addAll(turnIds).add(turnId).build();
        return copy(nextTurnIds, state, summary, coverage, completionRepairUsed, operations, evidence, outcome, version, now, endedAt);
    }

    public ReviewRun withSummary(ReviewSummary updatedSummary, Instant now)
    {
        return copy(turnIds, state, Optional.of(updatedSummary), coverage, completionRepairUsed, operations, evidence, outcome, version, now, endedAt);
    }

    public ReviewRun withCoverage(ReviewCoverage updatedCoverage, Instant now)
    {
        return copy(turnIds, state, summary, Optional.of(updatedCoverage), completionRepairUsed, operations, evidence, outcome, version, now, endedAt);
    }

    public ReviewRun withOperation(String key, ReviewOperation operation, Instant now)
    {
        Map<String, ReviewOperation> nextOperations = ImmutableMap.<String, ReviewOperation>builder().putAll(operations).put(key, operation).buildOrThrow();
        return copy(turnIds, state, summary, coverage, completionRepairUsed, nextOperations, evidence, outcome, version, now, endedAt);
    }

    public ReviewRun withEvidence(String ref, ReviewEvidence item, Instant now)
    {
        Map<String, ReviewEvidence> nextEvidence = ImmutableMap.<String, ReviewEvidence>builder().putAll(evidence).put(ref, item).buildOrThrow();
        return copy(turnIds, state, summary, coverage, completionRepairUsed, operations, nextEvidence, outcome, version, now, endedAt);
    }

    public ReviewRun withCompletionRepairUsed(Instant now)
    {
        return copy(turnIds, state, summary, coverage, true, operations, evidence, outcome, version, now, endedAt);
    }

    public ReviewRun ended(State terminalState, Optional<String> terminalOutcome, Instant now)
    {
        checkArgument(terminalState.terminal(), "state is not terminal");
        return copy(turnIds, terminalState, summary, coverage, completionRepairUsed, operations, evidence, terminalOutcome, version, now, Optional.of(now));
    }

    /** Carries everything frozen with the head over unchanged; only the progress of the run varies. */
    private ReviewRun copy(
            List<String> nextTurnIds,
            State nextState,
            Optional<ReviewSummary> nextSummary,
            Optional<ReviewCoverage> nextCoverage,
            boolean nextCompletionRepairUsed,
            Map<String, ReviewOperation> nextOperations,
            Map<String, ReviewEvidence> nextEvidence,
            Optional<String> nextOutcome,
            long nextVersion,
            Instant now,
            Optional<Instant> nextEndedAt)
    {
        return new ReviewRun(
                id, reviewId, ordinal, scope, baseRef, baseSha, mergeBaseSha, headSha, priorHeadSha, commits, diff,
                nextTurnIds, nextState, nextSummary, nextCoverage, nextCompletionRepairUsed, nextOperations, nextEvidence,
                nextOutcome, nextVersion, createdAt, now, nextEndedAt);
    }

    public enum Scope
    {
        FULL,
        INCREMENTAL
    }

    public enum State
    {
        RUNNING,
        /** Valid summary and coverage were recorded and every contributing turn settled successfully. */
        ACCEPTED,
        /** Turns settled without a valid report, or the report was refused and the repair budget is spent. */
        INCOMPLETE,
        /** The user stopped the run; what was saved stays provisional. */
        STOPPED,
        FAILED;

        public boolean terminal()
        {
            return this != RUNNING;
        }
    }
}
