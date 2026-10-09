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

import java.util.List;

import static com.bytequay.core.review.ReviewCandidateValidation.Outcome.NEEDS_EVIDENCE;
import static com.bytequay.core.review.ReviewCandidateValidation.Outcome.UNRESOLVED;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * Assessments refer to the exact sealed investigation step that the validator received.
 *
 * @param threadVerdicts conclusions on the pull-request threads the user started
 */
public record ReviewValidation(
        int investigationStep,
        List<ReviewCandidateValidation> candidates,
        ReviewCoverage coverage,
        List<ReviewCandidate> newCandidates,
        List<String> selectedFindingIds,
        String selectionExplanation,
        List<ReviewThreadVerdict> threadVerdicts)
{
    public ReviewValidation
    {
        checkArgument(investigationStep > 0, "investigationStep is not positive");
        candidates = ImmutableList.copyOf(candidates);
        checkArgument(candidates.stream().map(ReviewCandidateValidation::candidate).distinct().count() == candidates.size(), "candidate assessments are duplicated");
        requireNonNull(coverage, "coverage is null");
        newCandidates = ImmutableList.copyOf(newCandidates);
        selectedFindingIds = ImmutableList.copyOf(selectedFindingIds);
        checkArgument(selectedFindingIds.size() <= 10 && selectedFindingIds.stream().distinct().count() == selectedFindingIds.size(),
                "selectedFindingIds must contain at most ten unique findings");
        checkArgument(!requireNonNull(selectionExplanation, "selectionExplanation is null").isBlank(), "selectionExplanation is blank");
        // Absent from validations stored before thread verdicts existed.
        threadVerdicts = threadVerdicts == null ? ImmutableList.of() : ImmutableList.copyOf(threadVerdicts);
        checkArgument(threadVerdicts.stream().map(ReviewThreadVerdict::threadId).distinct().count() == threadVerdicts.size(), "thread verdicts are duplicated");
        checkArgument(!coverage.complete() || candidates.stream().noneMatch(value -> value.outcome() == NEEDS_EVIDENCE || value.outcome() == UNRESOLVED),
                "complete coverage cannot contain unresolved candidates");
    }

    public boolean needsEvidence()
    {
        return candidates.stream().anyMatch(candidate -> candidate.outcome() == NEEDS_EVIDENCE);
    }
}
