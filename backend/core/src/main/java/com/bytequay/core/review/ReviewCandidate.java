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

/** An append-only discovery hypothesis; its origin records the assigned parent turn that saved it. */
public record ReviewCandidate(
        int number,
        ReviewPerspectiveAssessment.Perspective perspective,
        String responsibility,
        String hypothesis,
        List<String> evidenceRefs,
        String nextCheck,
        Optional<String> carriedFindingId,
        int originStep,
        String originTurnId,
        Optional<String> attributedSource)
{
    public ReviewCandidate
    {
        checkArgument(number > 0, "number is not positive");
        requireNonNull(perspective, "perspective is null");
        checkArgument(!requireNonNull(responsibility, "responsibility is null").isBlank(), "responsibility is blank");
        checkArgument(!requireNonNull(hypothesis, "hypothesis is null").isBlank(), "hypothesis is blank");
        evidenceRefs = ImmutableList.copyOf(evidenceRefs);
        checkArgument(!requireNonNull(nextCheck, "nextCheck is null").isBlank(), "nextCheck is blank");
        requireNonNull(carriedFindingId, "carriedFindingId is null");
        checkArgument(originStep > 0, "originStep is not positive");
        checkArgument(!requireNonNull(originTurnId, "originTurnId is null").isBlank(), "originTurnId is blank");
        requireNonNull(attributedSource, "attributedSource is null");
    }
}
