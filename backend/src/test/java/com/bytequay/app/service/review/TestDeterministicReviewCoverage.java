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
package com.bytequay.app.service.review;

import com.bytequay.app.domain.InvestigationReviewData.FindingEvidenceRow;
import com.bytequay.app.domain.InvestigationReviewData.FindingRow;
import com.bytequay.app.domain.InvestigationReviewData.HypothesisRow;
import com.bytequay.app.domain.InvestigationReviewData.InvestigationStepRow;
import com.bytequay.app.domain.InvestigationReviewData.ObservationRow;
import com.bytequay.app.domain.InvestigationReviewData.ReviewObjectiveRow;
import com.bytequay.app.service.review.DeterministicReviewCoverage.CoverageReport;
import com.bytequay.app.service.review.DeterministicReviewCoverage.FailureClassResult;
import com.bytequay.app.service.review.DeterministicReviewCoverage.SweepResult;
import com.bytequay.app.service.review.InvestigationReviewContext.Snapshot;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

class TestDeterministicReviewCoverage
{
    @Test
    void runsBoundedSweepsAndClassifiesApplicableFailureClasses()
    {
        CoverageReport report = DeterministicReviewCoverage.analyze("""
                diff --git a/src/A.java b/src/A.java
                @@ -1,4 +1,5 @@ public class A {
                - public boolean allowed(String token) { return token != null; }
                + public boolean allowed(String token) { return token != null && authorize(token); }
                + private boolean authorize(String token) { return permissions.contains(token); }
                diff --git a/src/B.java b/src/B.java
                @@ -8,2 +8,2 @@ public class B {
                - queue.add(oldValue);
                + queue.add(newValue);
                """);

        Map<String, SweepResult> sweeps = report.sweeps().stream()
                .collect(Collectors.toMap(SweepResult::name, Function.identity()));
        assertThat(sweeps.keySet()).containsExactlyInAnyOrder(
                "line-scan", "removed-behavior", "cross-file-trace",
                "language-pitfall", "extraction-correctness");
        assertThat(sweeps.get("line-scan").inspectedUnits()).isEqualTo(2);
        assertThat(sweeps.get("removed-behavior").inspectedUnits()).isEqualTo(2);
        assertThat(report.sweeps()).allSatisfy(sweep ->
                assertThat(sweep.candidates()).hasSizeLessThanOrEqualTo(
                        DeterministicReviewCoverage.MAX_SWEEP_CANDIDATES));

        Map<String, FailureClassResult> classes = report.failureClasses().stream()
                .collect(Collectors.toMap(FailureClassResult::id, Function.identity()));
        assertThat(classes.get("logic-boundary").applicable()).isTrue();
        assertThat(classes.get("removed-behavior").applicable()).isTrue();
        assertThat(classes.get("interface-contract").applicable()).isTrue();
        assertThat(classes.get("concurrency").applicable()).isTrue();
        assertThat(classes.get("security").applicable()).isTrue();
        assertThat(report.promptContext()).contains("Failure-class dispositions owed");
    }

    @Test
    void emitsNoPaddingForAChangeWithNoSweepCandidates()
    {
        CoverageReport report = DeterministicReviewCoverage.analyze("""
                diff --git a/README.md b/README.md
                @@ -1 +1 @@
                -old words
                +new words
                """);

        SweepResult pitfalls = report.sweeps().stream()
                .filter(sweep -> sweep.name().equals("language-pitfall"))
                .findFirst().orElseThrow();
        assertThat(pitfalls.candidates()).isEmpty();
        assertThat(pitfalls.preview()).contains("No candidates emitted; no padding.");
    }

    @Test
    void validationNeedsSupportingEvidenceButNotSelfRefutationEvidence()
    {
        FindingEvidenceRow supports = evidence("SUPPORTS");
        FindingEvidenceRow refutes = evidence("REFUTES");

        assertThat(InvestigationReviewService.hasRequiredEvidence(List.of(supports))).isTrue();
        assertThat(InvestigationReviewService.hasRequiredEvidence(List.of(supports, refutes))).isTrue();
        assertThat(InvestigationReviewService.hasRequiredEvidence(List.of(refutes))).isFalse();
        assertThat(InvestigationReviewService.hasRequiredEvidence(List.of())).isFalse();
    }

    @Test
    void findingsNeedAChangedRightSideRangeCoveredBySupportingEvidence()
    {
        Snapshot snapshot = new Snapshot(null, "base", "head", """
                diff --git a/src/A.java b/src/A.java
                @@ -10,3 +10,4 @@
                 before();
                -oldCall();
                +newCall();
                +guard();
                 after();
                """, List.of(), null);
        FindingRow finding = new FindingRow(
                "finding", "review", "round", "objective", "hypothesis", "hard-invariant",
                "src/A.java", 11, 12, "The guard is incomplete", 3, "SUPPORTED", "unknown",
                "Handle the failure", "candidate", "head");
        ObservationRow covering = new ObservationRow(
                "observation", "step", "source", "head", "src/A.java", 10, 13,
                null, null, null, null, "digest", "preview");

        assertThat(InvestigationReviewService.rightSideRange(
                snapshot, finding.path(), finding.startLine(), finding.endLine())).isTrue();
        assertThat(InvestigationReviewService.rightSideRange(snapshot, "src/A.java", 11, 14)).isFalse();
        assertThat(InvestigationReviewService.rightSideRange(snapshot, "src/A.java", 12, 11)).isFalse();
        assertThat(InvestigationReviewService.hasAnchoredSupportingEvidence(
                finding, List.of(evidence("SUPPORTS")), Map.of("observation", covering))).isTrue();
        assertThat(InvestigationReviewService.hasAnchoredSupportingEvidence(
                finding, List.of(evidence("REFUTES")), Map.of("observation", covering))).isFalse();
    }

    @Test
    void untouchedApplicableObjectivesAreReportedAsBudgetGaps()
    {
        ReviewObjectiveRow objective = new ReviewObjectiveRow(
                "objective", "round", "criterion", "Check errors", "failure-class",
                "applicable", "pending");

        assertThat(InvestigationReviewService.objectiveResolution(
                objective, List.of(), List.of(), List.of()))
                .isEqualTo("not-covered-budget");

        HypothesisRow hypothesis = new HypothesisRow(
                "hypothesis", "assignment", objective.id(), "Errors may be swallowed",
                "reviewer", "active", "TENTATIVE");
        InvestigationStepRow step = new InvestigationStepRow(
                "step", "assignment", hypothesis.id(), "read_file",
                JsonNodeFactory.instance.objectNode(), "inspect catch path", true, 0, "completed");
        assertThat(InvestigationReviewService.objectiveResolution(
                objective, List.of(), List.of(hypothesis), List.of(step)))
                .isEqualTo("investigated-clean");
    }

    private static FindingEvidenceRow evidence(String relation)
    {
        return new FindingEvidenceRow(
                "finding", "observation", relation, "proposition", "E1", "reason",
                "DIRECT_ONLY", JsonNodeFactory.instance.objectNode());
    }
}
