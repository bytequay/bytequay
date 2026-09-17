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

import com.bytequay.core.context.ContextContribution;
import com.bytequay.core.context.ContextDelivery;
import com.bytequay.core.context.ContextHistoryRead;
import com.bytequay.core.context.ContextLimits;
import com.bytequay.core.context.ContextSourceRead;
import com.bytequay.core.context.ReviewContextSnapshot;
import com.bytequay.core.domain.DiffFile;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

/**
 * One attempt to review one frozen head. The diff is frozen with the run so anchors, snapshots and
 * context reads keep resolving after the pull request cache moves on.
 *
 * @param turnIds the review-request turns ByteQuay started for this run, in order; the transcript
 *        labels their input as ByteQuay's, not the user's
 * @param operations replay results by assignment, tool and operation key, committed with the write they name
 * @param evidence retained source excerpts by reference; preparation alone does not establish provider delivery
 * @param context prepared repository context; absence means no snapshot was captured, including historical runs
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
        Optional<ReviewContextSnapshot> context,
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
        requireNonNull(context, "context is null");
        Set<String> operationKeys = operations.keySet();
        checkArgument(context.stream().flatMap(snapshot -> snapshot.contributions().stream())
                .allMatch(contribution -> operationKeys.containsAll(contribution.preparationRefs())), "Context references missing preparation receipts");
        Set<String> evidenceIds = evidence.keySet();
        checkArgument(context.stream().allMatch(snapshot -> evidenceIds.containsAll(snapshot.evidenceRefs())), "Context references missing run evidence");
        for (ContextSourceRead read : context.stream().flatMap(snapshot -> snapshot.sourceReads().stream()).toList()) {
            if (read.evidenceRef().isPresent()) {
                ReviewEvidence item = evidence.get(read.evidenceRef().orElseThrow());
                checkArgument(read.evidenceRef().orElseThrow().equals(item.ref()), "Source read evidence reference differs");
                checkArgument(!item.excerpt().isBlank() && ContextLimits.bytes(item.excerpt()) == read.utf8Bytes(), "Source read excerpt byte count or content differs");
                if (read instanceof ContextHistoryRead history) {
                    history.checkExcerpt(item.excerpt());
                }
            }
        }
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

    /**
     * Captures prepared context once and merges its retained evidence atomically in the returned run.
     *
     * @param snapshot immutable selection and packet, without a provider-delivery claim
     * @param capturedEvidence new or identical evidence records referenced by the snapshot
     * @param now time of local capture
     * @return updated run, or this run when an identical capture is replayed
     * @throws IllegalStateException when context or existing evidence would be replaced
     * @throws IllegalArgumentException when a snapshot source does not resolve in the resulting evidence
     */
    public ReviewRun withContext(ReviewContextSnapshot snapshot, Map<String, ReviewEvidence> capturedEvidence, Instant now)
    {
        requireNonNull(snapshot, "snapshot is null");
        requireNonNull(now, "now is null");
        checkState(context.isEmpty() || context.orElseThrow().equals(snapshot), "Review context is already captured");
        Map<String, ReviewEvidence> merged = new HashMap<>(evidence);
        ImmutableMap.copyOf(capturedEvidence).forEach((ref, item) -> {
            checkState(!merged.containsKey(ref) || merged.get(ref).equals(item), "Run evidence cannot be replaced: %s", ref);
            checkArgument(ref.equals(item.ref()), "Evidence key does not match its reference");
            merged.putIfAbsent(ref, item);
        });
        if (context.isPresent() && merged.equals(evidence)) {
            return this;
        }
        return new ReviewRun(
                id, reviewId, ordinal, scope, baseRef, baseSha, mergeBaseSha, headSha, priorHeadSha, commits, diff,
                turnIds, state, summary, coverage, investigation, steps, completionRepairUsed, operations, merged, Optional.of(snapshot),
                outcome, version, createdAt, now, endedAt);
    }

    public ReviewRun withCompletionRepairUsed(Instant now)
    {
        return copy(turnIds, state, summary, coverage, investigation, steps, true, operations, evidence, outcome, version, now, endedAt);
    }

    /** Retains the brief with the sealed investigation in the caller's existing atomic run update. */
    public ReviewRun withContextContribution(ContextContribution contribution, Instant now)
    {
        requireNonNull(contribution, "contribution is null");
        requireNonNull(now, "now is null");
        checkState(context.isPresent(), "Review context is absent");
        checkArgument(steps.stream().anyMatch(step -> step.number() == contribution.investigationStep() && step.investigation().isPresent()),
                "Context contribution requires its sealed investigation");
        ReviewContextSnapshot snapshot = context.orElseThrow().withContribution(contribution);
        if (snapshot.equals(context.orElseThrow())) {
            return this;
        }
        return new ReviewRun(id, reviewId, ordinal, scope, baseRef, baseSha, mergeBaseSha, headSha, priorHeadSha, commits, diff,
                turnIds, state, summary, coverage, investigation, steps, completionRepairUsed, operations, evidence, Optional.of(snapshot),
                outcome, version, createdAt, now, endedAt);
    }

    /** Records the exact packet bound to an accepted turn before provider submission. */
    public ReviewRun withContextDelivery(ContextDelivery delivery, Instant now)
    {
        requireNonNull(now, "now is null");
        checkState(context.isPresent(), "Review context is absent");
        ReviewContextSnapshot snapshot = context.orElseThrow().withDelivery(delivery);
        if (snapshot.equals(context.orElseThrow())) {
            return this;
        }
        return new ReviewRun(id, reviewId, ordinal, scope, baseRef, baseSha, mergeBaseSha, headSha, priorHeadSha, commits, diff,
                turnIds, state, summary, coverage, investigation, steps, completionRepairUsed, operations, evidence, Optional.of(snapshot),
                outcome, version, createdAt, now, endedAt);
    }

    /**
     * Retains an application-observed source receipt and its optional exact returned excerpt in one run value.
     *
     * @param read bounded receipt; successful blank reads have no evidence reference
     * @param capturedEvidence returned nonblank excerpt, with the exact reference and UTF-8 byte count recorded by the receipt
     * @param now local retention time; the receipt retains the source's original read time
     * @return updated run, or this run for an identical retry; frozen bootstrap and selection remain unchanged
     * @throws IllegalStateException when context is absent/disabled or existing receipt/evidence would be replaced
     * @throws IllegalArgumentException when evidence identity, byte count, or retention bounds differ
     */
    public ReviewRun withContextSourceRead(ContextSourceRead read, Optional<ReviewEvidence> capturedEvidence, Instant now)
    {
        requireNonNull(read, "read is null");
        requireNonNull(capturedEvidence, "capturedEvidence is null");
        requireNonNull(now, "now is null");
        checkState(context.isPresent(), "Review context is absent");
        checkArgument(read.evidenceRef().equals(capturedEvidence.map(ReviewEvidence::ref)), "Source read evidence reference differs");
        capturedEvidence.ifPresent(item -> checkArgument(ContextLimits.bytes(item.excerpt()) == read.utf8Bytes(), "Source read excerpt byte count differs"));
        ReviewContextSnapshot snapshot = context.orElseThrow().withSourceRead(read);
        Map<String, ReviewEvidence> merged = new HashMap<>(evidence);
        capturedEvidence.ifPresent(item -> {
            checkState(!merged.containsKey(item.ref()) || merged.get(item.ref()).equals(item), "Run evidence cannot be replaced: %s", item.ref());
            merged.putIfAbsent(item.ref(), item);
        });
        if (snapshot.equals(context.orElseThrow()) && merged.equals(evidence)) {
            return this;
        }
        return new ReviewRun(id, reviewId, ordinal, scope, baseRef, baseSha, mergeBaseSha, headSha, priorHeadSha, commits, diff,
                turnIds, state, summary, coverage, investigation, steps, completionRepairUsed, operations, merged, Optional.of(snapshot),
                outcome, version, createdAt, now, endedAt);
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
                nextTurnIds, nextState, nextSummary, nextCoverage, nextInvestigation, nextSteps, nextCompletionRepairUsed, nextOperations, nextEvidence, context,
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
