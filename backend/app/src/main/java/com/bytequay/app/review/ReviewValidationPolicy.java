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
package com.bytequay.app.review;

import com.bytequay.core.review.ReviewEvidence;
import com.bytequay.core.review.ReviewFinding;
import com.bytequay.core.review.ReviewRun;

import java.util.List;
import java.util.Optional;

/** Evaluated against fresh findings both when a report is submitted and when its turn finishes. */
final class ReviewValidationPolicy
{
    private ReviewValidationPolicy() {}

    static Optional<FindingProblem> findingProblem(ReviewRun run, Optional<ReviewFinding> finding, Optional<Integer> revision, List<String> evidenceRefs)
    {
        if (finding.isEmpty() || !finding.orElseThrow().reviewId().equals(run.reviewId())) {
            return Optional.of(FindingProblem.MISSING_OR_FOREIGN);
        }
        ReviewFinding current = finding.orElseThrow();
        if (current.state() == ReviewFinding.State.WITHDRAWN || current.outdated() || !current.validatedHeadSha().equals(run.sourceRevision())) {
            return Optional.of(FindingProblem.NOT_CURRENT);
        }
        if (!revision.equals(Optional.of(current.revision()))) {
            return Optional.of(FindingProblem.CHANGED_REVISION);
        }
        if (!current.evidence().stream().map(ReviewEvidence::ref).allMatch(evidenceRefs::contains)) {
            return Optional.of(FindingProblem.MISSING_EVIDENCE);
        }
        return Optional.empty();
    }

    static boolean selectable(ReviewRun run, ReviewFinding finding)
    {
        return finding.reviewId().equals(run.reviewId()) && finding.runId().equals(run.id()) && finding.origin() == ReviewFinding.Origin.AUTOMATIC;
    }

    enum FindingProblem
    {
        MISSING_OR_FOREIGN,
        NOT_CURRENT,
        CHANGED_REVISION,
        MISSING_EVIDENCE
    }
}
