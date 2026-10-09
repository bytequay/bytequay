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

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * Whether the tests a change adds or alters notice the change being undone, as one investigation observed it.
 *
 * @param headEvidenceRef the captured run of those tests on the unmodified head
 * @param undoneEvidenceRef the captured run of the same tests with the change undone
 * @param candidate the hypothesis recorded for the tests that kept passing
 */
public record ReviewTestCheck(Outcome outcome, String explanation, Optional<String> headEvidenceRef, Optional<String> undoneEvidenceRef, Optional<Integer> candidate)
{
    public ReviewTestCheck
    {
        requireNonNull(outcome, "outcome is null");
        checkArgument(!requireNonNull(explanation, "explanation is null").isBlank(), "explanation is blank");
        requireNonNull(headEvidenceRef, "headEvidenceRef is null");
        requireNonNull(undoneEvidenceRef, "undoneEvidenceRef is null");
        requireNonNull(candidate, "candidate is null");
        checkArgument(!outcome.ran() || (headEvidenceRef.isPresent() && undoneEvidenceRef.isPresent()), "a test check that ran cites both runs");
        checkArgument((outcome == Outcome.DOES_NOT_PROTECT) == candidate.isPresent(), "only tests that do not protect the change name a candidate");
    }

    public enum Outcome
    {
        /** Every selected test fails once the change is undone. */
        PROTECTS,
        /** At least one selected test still passes once the change is undone. */
        DOES_NOT_PROTECT,
        NOT_APPLICABLE,
        BLOCKED;

        /** Both runs happened, so the outcome is an observation rather than the reason there is none. */
        public boolean ran()
        {
            return this == PROTECTS || this == DOES_NOT_PROTECT;
        }
    }
}
