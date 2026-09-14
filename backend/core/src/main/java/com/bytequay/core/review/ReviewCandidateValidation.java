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

import static com.bytequay.core.review.ReviewCandidateValidation.Outcome.UNRESOLVED;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** One assessment of an immutable investigation, including a concrete next check when evidence is missing. */
public record ReviewCandidateValidation(
        int candidate,
        Outcome outcome,
        String conclusion,
        List<String> evidenceRefs,
        String counterevidence,
        Optional<String> findingId,
        Optional<Integer> findingRevision,
        Optional<ReviewEvidenceRequest> request)
{
    public ReviewCandidateValidation
    {
        checkArgument(candidate > 0, "candidate is not positive");
        outcome = outcome == null ? UNRESOLVED : outcome;
        requireNonNull(conclusion, "conclusion is null");
        evidenceRefs = evidenceRefs == null ? ImmutableList.of() : ImmutableList.copyOf(evidenceRefs);
        counterevidence = counterevidence == null ? "" : counterevidence;
        requireNonNull(findingId, "findingId is null");
        requireNonNull(findingRevision, "findingRevision is null");
        checkArgument(findingRevision.map(value -> value > 0).orElse(true), "findingRevision is not positive");
        requireNonNull(request, "request is null");
    }

    public enum Outcome
    {
        SUPPORTED,
        REJECTED,
        NEEDS_EVIDENCE,
        UNRESOLVED
    }
}
