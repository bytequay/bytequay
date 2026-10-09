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
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** The reviewer's change model, assessed scope, and hypotheses; candidate numbers are one-based list positions. */
public record ReviewInvestigation(
        String changeMap,
        List<String> reviewedFiles,
        List<String> contextEvidenceRefs,
        List<ReviewCandidate> candidates,
        List<ReviewCandidateValidation> validations,
        List<ReviewEvidenceAnswer> answers,
        Optional<ReviewVerification> verification,
        List<ReviewPerspectiveAssessment> perspectives,
        Optional<ReviewTestCheck> testCheck)
{
    public ReviewInvestigation
    {
        requireNonNull(verification, "verification is null");
        requireNonNull(testCheck, "testCheck is null");
        requireNonNull(changeMap, "changeMap is null");
        reviewedFiles = ImmutableList.copyOf(reviewedFiles);
        contextEvidenceRefs = ImmutableList.copyOf(contextEvidenceRefs);
        candidates = ImmutableList.copyOf(candidates);
        validations = ImmutableList.copyOf(validations);
        answers = ImmutableList.copyOf(answers);
        perspectives = ImmutableList.copyOf(perspectives);
        checkArgument(perspectives.stream().map(ReviewPerspectiveAssessment::perspective).distinct().count() == perspectives.size(), "perspectives are duplicated");
        for (int index = 0; index < candidates.size(); index++) {
            checkArgument(candidates.get(index).number() == index + 1, "candidate numbers must match their one-based positions");
        }
    }

    public boolean completePerspectives()
    {
        return perspectives.size() == ReviewPerspectiveAssessment.Perspective.values().length
                && perspectives.stream().noneMatch(value -> value.status() == ReviewPerspectiveAssessment.Status.BLOCKED);
    }

    public ReviewInvestigation withValidations(List<ReviewCandidateValidation> conclusions)
    {
        return new ReviewInvestigation(changeMap, reviewedFiles, contextEvidenceRefs, candidates, conclusions, answers, verification, perspectives, testCheck);
    }
}
