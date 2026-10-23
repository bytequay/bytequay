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

import com.bytequay.core.review.ReviewCandidate;
import com.bytequay.core.review.ReviewCandidateValidation;
import com.bytequay.core.review.ReviewEvidence;
import com.bytequay.core.review.ReviewFinding;
import com.bytequay.core.review.ReviewInvestigation;
import com.bytequay.core.review.ReviewRun;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.google.common.collect.ImmutableSet.toImmutableSet;

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

    /** A hypothesis added during validation is settled only once its own evidence has been collected. */
    static boolean settlesNewCandidate(ReviewInvestigation report, int candidate, ReviewCandidateValidation.Outcome outcome)
    {
        return candidate > report.candidates().size() && outcome != ReviewCandidateValidation.Outcome.NEEDS_EVIDENCE;
    }

    /** A hypothesis carried from an earlier finding can only be supported as that same finding. */
    static boolean changesCarriedFinding(ReviewInvestigation report, int candidate, Optional<String> findingId)
    {
        return candidate <= report.candidates().size()
                && report.candidates().get(candidate - 1).carriedFindingId().filter(carried -> !findingId.equals(Optional.of(carried))).isPresent();
    }

    /** A selected finding is an automatic finding of this run that the report supports at its current revision. */
    static boolean supportedSelection(ReviewRun run, ReviewFinding finding, List<ReviewCandidateValidation> validations)
    {
        return selectable(run, finding) && validations.stream().anyMatch(candidate -> candidate.outcome() == ReviewCandidateValidation.Outcome.SUPPORTED
                && candidate.findingId().equals(Optional.of(finding.id())) && candidate.findingRevision().equals(Optional.of(finding.revision())));
    }

    /** A complete report carries every finding still open from an earlier run into a hypothesis. */
    static boolean carriesOpenFindings(ReviewRun run, List<ReviewCandidate> candidates, List<ReviewFinding> findings)
    {
        Set<String> carried = candidates.stream().flatMap(candidate -> candidate.carriedFindingId().stream()).collect(toImmutableSet());
        return findings.stream()
                .filter(finding -> !finding.runId().equals(run.id()) && finding.state() == ReviewFinding.State.OPEN)
                .allMatch(finding -> carried.contains(finding.id()));
    }

    /** A complete report supports every open finding and every automatic finding this run has not withdrawn. */
    static boolean supportsOpenFindings(ReviewRun run, Set<String> supported, List<ReviewFinding> findings)
    {
        return findings.stream()
                .filter(finding -> finding.state() == ReviewFinding.State.OPEN
                        || (finding.runId().equals(run.id()) && finding.origin() == ReviewFinding.Origin.AUTOMATIC && finding.state() != ReviewFinding.State.WITHDRAWN))
                .allMatch(finding -> supported.contains(finding.id()));
    }

    enum FindingProblem
    {
        MISSING_OR_FOREIGN,
        NOT_CURRENT,
        CHANGED_REVISION,
        MISSING_EVIDENCE
    }
}
