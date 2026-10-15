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
import lombok.AccessLevel;
import lombok.Builder;

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
@Builder(builderClassName = "Draft", builderMethodName = "", toBuilder = true, access = AccessLevel.PRIVATE)
public record ReviewRun(
        String id,
        String reviewId,
        int ordinal,
        Scope scope,
        String baseRef,
        String baseSha,
        String mergeBaseSha,
        String headSha,
        Optional<String> worktreeTreeSha,
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
        requireNonNull(worktreeTreeSha, "worktreeTreeSha is null");
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

    /** Git tree for local edits, or the committed head for a clean review. */
    public String sourceRevision()
    {
        return worktreeTreeSha.orElse(headSha);
    }

    public boolean active()
    {
        return !state.terminal();
    }

    public boolean explanationOnly()
    {
        return !steps.isEmpty() && steps.getFirst().kind() == ReviewStep.Kind.EXPLAIN;
    }

    public ReviewRun withVersion(long updatedVersion)
    {
        return toBuilder().version(updatedVersion).build();
    }

    public ReviewRun withTurn(String turnId, Instant now)
    {
        List<String> nextTurnIds = ImmutableList.<String>builder().addAll(turnIds).add(turnId).build();
        return toBuilder().turnIds(nextTurnIds).updatedAt(now).build();
    }

    public ReviewRun withSummary(ReviewSummary updatedSummary, Instant now)
    {
        return toBuilder().summary(Optional.of(updatedSummary)).updatedAt(now).build();
    }

    public ReviewRun withCoverage(ReviewCoverage updatedCoverage, Instant now)
    {
        return toBuilder().coverage(Optional.of(updatedCoverage)).updatedAt(now).build();
    }

    public ReviewRun withInvestigation(ReviewInvestigation updatedInvestigation, Instant now)
    {
        return toBuilder().investigation(Optional.of(updatedInvestigation)).updatedAt(now).build();
    }

    /** The immutable report and its assignment number must always be read together. */
    public Optional<ReviewStep> latestSealedInvestigation()
    {
        return steps.stream().filter(step -> step.investigation().isPresent()).reduce((first, last) -> last);
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
        return toBuilder().coverage(Optional.empty()).steps(next).updatedAt(now).build();
    }

    public ReviewRun replaceCurrentStep(ReviewStep step, Instant now)
    {
        checkArgument(!steps.isEmpty() && step.number() == steps.size(), "only the current step can be replaced");
        List<ReviewStep> next = ImmutableList.<ReviewStep>builder().addAll(steps.subList(0, steps.size() - 1)).add(step).build();
        return toBuilder().steps(next).updatedAt(now).build();
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
        return toBuilder().operations(nextOperations).updatedAt(now).build();
    }

    public ReviewRun withEvidence(String ref, ReviewEvidence item, Instant now)
    {
        Map<String, ReviewEvidence> nextEvidence = ImmutableMap.<String, ReviewEvidence>builder().putAll(evidence).put(ref, item).buildOrThrow();
        return toBuilder().evidence(nextEvidence).updatedAt(now).build();
    }

    public ReviewRun withCompletionRepairUsed(Instant now)
    {
        return toBuilder().completionRepairUsed(true).updatedAt(now).build();
    }

    public ReviewRun ended(State terminalState, Optional<String> terminalOutcome, Instant now)
    {
        checkArgument(terminalState.terminal(), "state is not terminal");
        return toBuilder().state(terminalState).outcome(terminalOutcome).updatedAt(now).endedAt(Optional.of(now)).build();
    }

    public enum Scope
    {
        FULL,
        INCREMENTAL
    }

    public enum State
    {
        RUNNING,
        /** The assigned report was recorded and every contributing turn settled successfully. */
        ACCEPTED,
        /** Turns settled without a valid report, or the report was refused and the repair budget is spent. */
        INCOMPLETE,
        /** The user stopped the run; what was saved stays provisional. */
        STOPPED, FAILED;

        public boolean terminal()
        {
            return this != RUNNING;
        }
    }
}
