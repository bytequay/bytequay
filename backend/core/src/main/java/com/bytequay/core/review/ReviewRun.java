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
 * One attempt to review one frozen head. The diff is frozen with the run so anchors and snapshots keep
 * resolving after the pull request cache moves on.
 *
 * @param turnIds the review-request turns ByteQuay started for this run, in order; the transcript
 *        labels their input as ByteQuay's, not the user's
 * @param operations replay results by assignment, tool and operation key, committed with the write they name
 * @param evidence retained source excerpts by reference
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
        Optional<ReviewInvestigation> investigation,
        List<ReviewStep> steps,
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
        requireNonNull(investigation, "investigation is null");
        steps = ImmutableList.copyOf(steps);
        for (int index = 0; index < steps.size(); index++) {
            checkArgument(steps.get(index).number() == index + 1, "step numbers must be consecutive");
        }
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
        return copy(turnIds, state, summary, coverage, investigation, steps, completionRepairUsed, operations, evidence, outcome, updatedVersion, updatedAt, endedAt);
    }

    public ReviewRun withTurn(String turnId, Instant now)
    {
        List<String> nextTurnIds = ImmutableList.<String>builder().addAll(turnIds).add(turnId).build();
        return copy(nextTurnIds, state, summary, coverage, investigation, steps, completionRepairUsed, operations, evidence, outcome, version, now, endedAt);
    }

    public ReviewRun withSummary(ReviewSummary updatedSummary, Instant now)
    {
        return copy(
                turnIds, state, Optional.of(updatedSummary), coverage, investigation, steps, completionRepairUsed,
                operations, evidence, outcome, version, now, endedAt);
    }

    public ReviewRun withCoverage(ReviewCoverage updatedCoverage, Instant now)
    {
        return copy(
                turnIds, state, summary, Optional.of(updatedCoverage), investigation, steps, completionRepairUsed,
                operations, evidence, outcome, version, now, endedAt);
    }

    public ReviewRun withInvestigation(ReviewInvestigation updatedInvestigation, Instant now)
    {
        return copy(
                turnIds, state, summary, coverage, Optional.of(updatedInvestigation), steps, completionRepairUsed,
                operations, evidence, outcome, version, now, endedAt);
    }

    public Optional<ReviewStep> currentStep()
    {
        return steps.isEmpty() ? Optional.empty() : Optional.of(steps.getLast());
    }

    public boolean validationStarted()
    {
        return currentStep().map(step -> step.kind() == ReviewStep.Kind.VALIDATE).orElse(false);
    }

    public ReviewRun withStep(ReviewStep step, Instant now)
    {
        checkArgument(step.number() == steps.size() + 1, "next step number must be consecutive");
        List<ReviewStep> next = ImmutableList.<ReviewStep>builder().addAll(steps).add(step).build();
        return copy(turnIds, state, summary, Optional.empty(), investigation, next, completionRepairUsed, operations, evidence, outcome, version, now, endedAt);
    }

    public ReviewRun replaceCurrentStep(ReviewStep step, Instant now)
    {
        checkArgument(!steps.isEmpty() && step.number() == steps.size(), "only the current step can be replaced");
        List<ReviewStep> next = ImmutableList.<ReviewStep>builder().addAll(steps.subList(0, steps.size() - 1)).add(step).build();
        return copy(turnIds, state, summary, coverage, investigation, next, completionRepairUsed, operations, evidence, outcome, version, now, endedAt);
    }

    public boolean hasCompleteSummary()
    {
        return summary.filter(value -> value.commits().size() == commits.size()
                && value.commits().stream().map(ReviewSummary.CommitExplanation::sha).distinct().count() == commits.size()
                && value.commits().stream().allMatch(explanation -> commits.stream().anyMatch(commit -> commit.sha().equals(explanation.sha())))).isPresent();
    }

    public ReviewRun withOperation(String key, ReviewOperation operation, Instant now)
    {
        Map<String, ReviewOperation> nextOperations = ImmutableMap.<String, ReviewOperation>builder().putAll(operations).put(key, operation).buildOrThrow();
        return copy(turnIds, state, summary, coverage, investigation, steps, completionRepairUsed, nextOperations, evidence, outcome, version, now, endedAt);
    }

    public ReviewRun withEvidence(String ref, ReviewEvidence item, Instant now)
    {
        Map<String, ReviewEvidence> nextEvidence = ImmutableMap.<String, ReviewEvidence>builder().putAll(evidence).put(ref, item).buildOrThrow();
        return copy(turnIds, state, summary, coverage, investigation, steps, completionRepairUsed, operations, nextEvidence, outcome, version, now, endedAt);
    }

    public ReviewRun withCompletionRepairUsed(Instant now)
    {
        return copy(turnIds, state, summary, coverage, investigation, steps, true, operations, evidence, outcome, version, now, endedAt);
    }

    public ReviewRun ended(State terminalState, Optional<String> terminalOutcome, Instant now)
    {
        checkArgument(terminalState.terminal(), "state is not terminal");
        return copy(
                turnIds, terminalState, summary, coverage, investigation, steps, completionRepairUsed,
                operations, evidence, terminalOutcome, version, now, Optional.of(now));
    }

    /** Carries everything frozen with the head over unchanged; only the progress of the run varies. */
    private ReviewRun copy(
            List<String> nextTurnIds,
            State nextState,
            Optional<ReviewSummary> nextSummary,
            Optional<ReviewCoverage> nextCoverage,
            Optional<ReviewInvestigation> nextInvestigation,
            List<ReviewStep> nextSteps,
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
                nextTurnIds, nextState, nextSummary, nextCoverage, nextInvestigation, nextSteps, nextCompletionRepairUsed, nextOperations, nextEvidence,
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
