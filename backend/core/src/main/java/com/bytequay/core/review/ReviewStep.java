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

import java.util.Optional;

import static com.bytequay.core.review.ReviewStep.Kind.INVESTIGATE;
import static com.bytequay.core.review.ReviewStep.Kind.VALIDATE;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** One persisted assignment; its accepted turn is identified by the deterministic key. */
public record ReviewStep(
        int number,
        Kind kind,
        boolean repair,
        Optional<ReviewInvestigation> investigation,
        Optional<ReviewValidation> validation)
{
    public ReviewStep
    {
        checkArgument(number > 0, "number is not positive");
        requireNonNull(kind, "kind is null");
        requireNonNull(investigation, "investigation is null");
        requireNonNull(validation, "validation is null");
        checkArgument(kind != VALIDATE || investigation.isEmpty(), "validation cannot submit investigation");
        checkArgument(kind == VALIDATE || validation.isEmpty(), "investigation cannot submit validation");
    }

    public static ReviewStep initial()
    {
        return new ReviewStep(1, INVESTIGATE, false, Optional.empty(), Optional.empty());
    }

    public ReviewStep next(Kind nextKind)
    {
        return new ReviewStep(number + 1, nextKind, false, Optional.empty(), Optional.empty());
    }

    public ReviewStep forRepair()
    {
        checkArgument(!repair, "step repair is already used");
        return new ReviewStep(number, kind, true, investigation, validation);
    }

    public ReviewStep withInvestigation(ReviewInvestigation report)
    {
        checkArgument(!sealed(), "step is already sealed");
        return new ReviewStep(number, kind, repair, Optional.of(report), validation);
    }

    public ReviewStep withValidation(ReviewValidation report)
    {
        checkArgument(!sealed(), "step is already sealed");
        return new ReviewStep(number, kind, repair, investigation, Optional.of(report));
    }

    public boolean sealed()
    {
        return investigation.isPresent() || validation.isPresent();
    }

    public String key(String runId)
    {
        return "review-step:" + runId + ":" + number + (repair ? ":repair" : "");
    }

    public enum Kind
    {
        INVESTIGATE,
        CHECK_NITS,
        COLLECT_EVIDENCE,
        VALIDATE
    }
}
