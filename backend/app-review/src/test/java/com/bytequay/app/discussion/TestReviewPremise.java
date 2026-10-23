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
package com.bytequay.app.discussion;

import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.core.review.ReviewAnchor;
import com.bytequay.core.review.ReviewCandidateValidation;
import com.bytequay.core.review.ReviewCommit;
import com.bytequay.core.review.ReviewCoverage;
import com.bytequay.core.review.ReviewFinding;
import com.bytequay.core.review.ReviewInvestigation;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.review.ReviewStep;
import com.bytequay.core.review.ReviewSummary;
import com.bytequay.core.review.ReviewValidation;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static com.bytequay.app.review.ReviewTestData.discoveryCandidate;
import static com.bytequay.app.review.ReviewTestData.perspectives;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestReviewPremise
{
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    private static final String HEADER = "--- review so far ---\n\n";

    @Test
    void retainsTheFirstCompleteExplanationWithEveryFindingAndTheNewestCandidatesAtTheirLatestAssessment()
            throws Exception
    {
        ReviewRun older = run("run-1", 1, "a".repeat(40))
                .withSummary(new ReviewSummary(1, "The original purpose", ImmutableList.of(new ReviewSummary.CommitExplanation("a".repeat(40), "Introduces the cache.")),
                        "", Optional.empty(), ImmutableList.of()), NOW)
                .withInvestigation(investigation(ImmutableList.of("An older claim"), ImmutableList.of()), NOW);
        ReviewRun newer = run("run-2", 2, "b".repeat(40))
                .withSummary(new ReviewSummary(1, "Resolve owners by id", ImmutableList.of(new ReviewSummary.CommitExplanation("b".repeat(40), "Keys the cache by id.")),
                        "", Optional.empty(), ImmutableList.of()), NOW)
                .withInvestigation(investigation(
                        ImmutableList.of("@carol: the cache leaks entries", "The parser keeps state"),
                        ImmutableList.of(
                                validation(1, ReviewCandidateValidation.Outcome.UNRESOLVED, "Needs a trace"),
                                validation(2, ReviewCandidateValidation.Outcome.SUPPORTED, "It does; see F-1"),
                                validation(1, ReviewCandidateValidation.Outcome.REJECTED, "close() evicts every entry"))), NOW);
        ReviewFinding later = finding("f2", "A later finding", NOW.plusSeconds(60), Optional.empty(), false);
        ReviewFinding earlier = finding("f1", "Reset the parser", NOW, Optional.of(new ReviewAnchor("src/Parser.java", "RIGHT", 3, 5, ImmutableList.of())), true);

        JsonNode premise = parse(ReviewPremise.render(ImmutableList.of(seal(newer), seal(older)), ImmutableList.of(later, earlier)));

        assertThat(premise.path("explanationRunId").asText()).isEqualTo("run-1");
        assertThat(premise.path("explanationHead").asText()).isEqualTo("a".repeat(40));
        assertThat(premise.path("purpose").asText()).isEqualTo("The original purpose");
        assertThat(premise.path("commits").toString()).isEqualTo("[{\"sha\":\"" + "a".repeat(40) + "\",\"explanation\":\"Introduces the cache.\"}]");
        assertThat(premise.path("findings").toString()).isEqualTo("""
                [{"id":"f1","title":"Reset the parser","revision":1,"origin":"AUTOMATIC","selected":false,"severity":"major","state":"OPEN","outdated":true,"path":"src/Parser.java","range":"R3–R5","body":"Body of Reset the parser"},\
                {"id":"f2","title":"A later finding","revision":1,"origin":"AUTOMATIC","selected":false,"severity":"major","state":"OPEN","body":"Body of A later finding"}]""");
        assertThat(premise.path("candidates").toString()).isEqualTo("""
                [{"number":1,"perspective":"BEHAVIOR","claim":"@carol: the cache leaks entries","outcome":"REJECTED","conclusion":"close() evicts every entry"},\
                {"number":2,"perspective":"BEHAVIOR","claim":"The parser keeps state","outcome":"SUPPORTED","conclusion":"It does; see F-1"}]""");
    }

    @Test
    void skipsIncompleteExplanationsAndRetainsTheirOriginalScopeWhenLaterRunsHaveNoSummary()
            throws Exception
    {
        ReviewRun incomplete = run("run-1", 1, "a".repeat(40))
                .withSummary(new ReviewSummary(1, "Missing commit explanation", ImmutableList.of(), "", Optional.empty(), ImmutableList.of()), NOW);
        ReviewRun explained = run("run-2", 2, "b".repeat(40))
                .withSummary(new ReviewSummary(1, "The first complete explanation",
                        ImmutableList.of(new ReviewSummary.CommitExplanation("b".repeat(40), "Explains the retained commit.")), "", Optional.empty(), ImmutableList.of()), NOW)
                .ended(ReviewRun.State.FAILED, Optional.of("Findings investigation failed after saving the explanation"), NOW);
        ReviewRun current = run("run-3", 3, "c".repeat(40))
                .withInvestigation(investigation(ImmutableList.of("New code requires a separate ownership check"), ImmutableList.of()), NOW);

        JsonNode premise = parse(ReviewPremise.render(ImmutableList.of(seal(current), explained, incomplete), ImmutableList.of()));

        assertThat(premise.path("explanationRunId").asText()).isEqualTo("run-2");
        assertThat(premise.path("explanationHead").asText()).isEqualTo("b".repeat(40));
        assertThat(premise.path("purpose").asText()).isEqualTo("The first complete explanation");
        assertThat(premise.path("commits").get(0).path("sha").asText()).isEqualTo("b".repeat(40));
        assertThat(premise.path("candidates").get(0).path("claim").asText()).isEqualTo("New code requires a separate ownership check");
    }

    @Test
    void replacesTheExplanationOnlyAfterAnExplicitRefreshIsAccepted()
            throws Exception
    {
        ReviewRun original = run("original", 1, "a".repeat(40))
                .withSummary(new ReviewSummary(1, "Original intent", ImmutableList.of(new ReviewSummary.CommitExplanation("a".repeat(40), "Original commit")),
                        "", Optional.empty(), ImmutableList.of()), NOW);
        ReviewRun refresh = run("refresh", 2, "b".repeat(40)).withStep(ReviewStep.explanation(), NOW)
                .withSummary(new ReviewSummary(1, "Refreshed intent", ImmutableList.of(new ReviewSummary.CommitExplanation("b".repeat(40), "Updated commit")),
                        "", Optional.empty(), ImmutableList.of()), NOW);
        ReviewRun current = seal(run("findings", 3, "c".repeat(40))
                .withInvestigation(investigation(ImmutableList.of("Current candidate"), ImmutableList.of()), NOW));
        ReviewFinding finding = finding("f1", "Existing finding", NOW, Optional.empty(), false);

        for (ReviewRun pending : ImmutableList.of(refresh, refresh.ended(ReviewRun.State.FAILED, Optional.of("Refresh interrupted"), NOW))) {
            JsonNode premise = parse(ReviewPremise.render(ImmutableList.of(current, pending, original), ImmutableList.of(finding)));
            assertThat(premise.path("explanationRunId").asText()).isEqualTo("original");
            assertThat(premise.path("purpose").asText()).isEqualTo("Original intent");
        }

        ReviewRun accepted = refresh.ended(ReviewRun.State.ACCEPTED, Optional.empty(), NOW);
        ReviewRun next = run("next-refresh", 4, "d".repeat(40)).withStep(ReviewStep.explanation(), NOW)
                .withSummary(new ReviewSummary(1, "Newest intent", ImmutableList.of(new ReviewSummary.CommitExplanation("d".repeat(40), "Newest commit")),
                        "", Optional.empty(), ImmutableList.of()), NOW);
        JsonNode retained = parse(ReviewPremise.render(ImmutableList.of(next, current, original, accepted), ImmutableList.of(finding)));
        assertThat(retained.path("explanationRunId").asText()).isEqualTo("refresh");
        ReviewRun incomplete = next.withSummary(new ReviewSummary(2, "Incomplete refresh", ImmutableList.of(), "", Optional.empty(), ImmutableList.of()), NOW)
                .ended(ReviewRun.State.ACCEPTED, Optional.empty(), NOW);
        assertThat(parse(ReviewPremise.render(ImmutableList.of(incomplete, accepted, original), ImmutableList.of())).path("explanationRunId").asText()).isEqualTo("refresh");

        JsonNode premise = parse(ReviewPremise.render(
                ImmutableList.of(next.ended(ReviewRun.State.ACCEPTED, Optional.empty(), NOW), current, original, accepted), ImmutableList.of(finding)));
        assertThat(premise.path("explanationRunId").asText()).isEqualTo("next-refresh");
        assertThat(premise.path("explanationHead").asText()).isEqualTo("d".repeat(40));
        assertThat(premise.path("purpose").asText()).isEqualTo("Newest intent");
        assertThat(premise.path("commits").get(0).path("sha").asText()).isEqualTo("d".repeat(40));
        assertThat(premise.path("findings").get(0).path("id").asText()).isEqualTo("f1");
        assertThat(premise.path("candidates").get(0).path("claim").asText()).isEqualTo("Current candidate");
    }

    @Test
    void rendersEmptyListsBeforeTheReviewSavedAnything()
    {
        assertThat(ReviewPremise.render(ImmutableList.of(run("run-1", 1, "a".repeat(40))), ImmutableList.of()))
                .isEqualTo(HEADER + "{\"findings\":[],\"candidates\":[]}");
    }

    @Test
    void cutsLongTextAndAnUnassessedCandidateCarriesOnlyItsClaim()
            throws Exception
    {
        ReviewRun run = run("run-1", 1, "a".repeat(40))
                .withSummary(new ReviewSummary(1, "p".repeat(5_000),
                        ImmutableList.of(new ReviewSummary.CommitExplanation("a".repeat(40), "Changes input")), "", Optional.empty(), ImmutableList.of()), NOW)
                .withInvestigation(investigation(ImmutableList.of("x".repeat(1_500)), ImmutableList.of()), NOW);

        JsonNode premise = parse(ReviewPremise.render(ImmutableList.of(seal(run)), ImmutableList.of()));

        assertThat(premise.path("purpose").asText()).isEqualTo("p".repeat(4_000) + " [truncated]");
        assertThat(premise.path("candidates").get(0).path("claim").asText()).isEqualTo("x".repeat(1_000) + " [truncated]");
        assertThat(premise.path("candidates").get(0).has("outcome")).isFalse();
    }

    @Test
    void refusesAPremiseOverTheSeedBudget()
    {
        ImmutableList<ReviewFinding> findings = IntStream.range(0, 320)
                .mapToObj(index -> new ReviewFinding("f" + index, "r1", "run-1", index + 1, "Finding " + index, "b".repeat(4_000), Optional.empty(), Optional.empty(),
                        ImmutableList.of(), 1, ReviewFinding.State.OPEN, Optional.empty(), "a".repeat(40), false, 0, NOW, NOW, ImmutableList.of(),
                        ReviewFinding.Origin.AUTOMATIC, Optional.empty()))
                .collect(toImmutableList());

        assertThatThrownBy(() -> ReviewPremise.render(ImmutableList.of(), findings))
                .isInstanceOf(AgentConflictException.class)
                .hasMessage("This review is too long to ask about");
    }

    private static JsonNode parse(String premise)
            throws Exception
    {
        assertThat(premise).startsWith(HEADER);
        return new ObjectMapper().readTree(premise.substring(HEADER.length()));
    }

    private static ReviewInvestigation investigation(ImmutableList<String> candidates, ImmutableList<ReviewCandidateValidation> validations)
    {
        return new ReviewInvestigation("change map", ImmutableList.of(), ImmutableList.of(), IntStream.range(0, candidates.size()).mapToObj(index -> discoveryCandidate(index + 1, candidates.get(index))).collect(toImmutableList()), validations, ImmutableList.of(), Optional.empty(), perspectives(), Optional.empty());
    }

    private static ReviewRun seal(ReviewRun run)
    {
        ReviewInvestigation report = run.investigation().orElseThrow();
        ReviewStep step = ReviewStep.initial().withInvestigation(report);
        ReviewRun sealed = run.withStep(step, NOW);
        if (report.validations().isEmpty()) {
            return sealed;
        }
        Map<Integer, ReviewCandidateValidation> latest = new LinkedHashMap<>();
        report.validations().forEach(assessment -> latest.put(assessment.candidate(), assessment));
        ReviewValidation validation = new ReviewValidation(step.number(), ImmutableList.copyOf(latest.values()),
                new ReviewCoverage(false, ImmutableList.of("Review ongoing"), ImmutableList.of(), NOW), ImmutableList.of(), ImmutableList.of(), "Not selected yet",
                ImmutableList.of());
        return sealed.withStep(step.next(ReviewStep.Kind.VALIDATE).withValidation(validation), NOW);
    }

    private static ReviewCandidateValidation validation(int candidate, ReviewCandidateValidation.Outcome outcome, String conclusion)
    {
        return new ReviewCandidateValidation(candidate, outcome, conclusion, ImmutableList.of(), "", Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static ReviewFinding finding(String id, String title, Instant createdAt, Optional<ReviewAnchor> anchor, boolean outdated)
    {
        return new ReviewFinding(id, "r1", "run-1", 1, title, "Body of " + title, Optional.of("major"), anchor, ImmutableList.of(), 1,
                ReviewFinding.State.OPEN, Optional.empty(), "a".repeat(40), outdated, 0, createdAt, createdAt, ImmutableList.of(), ReviewFinding.Origin.AUTOMATIC,
                Optional.empty());
    }

    private static ReviewRun run(String id, int ordinal, String headSha)
    {
        return new ReviewRun(id, "r1", ordinal, ReviewRun.Scope.FULL, "main", "0".repeat(40), "0".repeat(40), headSha, Optional.empty(), Optional.empty(),
                ImmutableList.of(new ReviewCommit(headSha, "Change input", "dev")), ImmutableList.of(), ImmutableList.of(),
                ReviewRun.State.RUNNING, Optional.empty(), Optional.empty(), Optional.empty(),
                ImmutableList.of(), false, ImmutableMap.of(), ImmutableMap.of(), Optional.empty(), 0, NOW, NOW, Optional.empty());
    }
}
