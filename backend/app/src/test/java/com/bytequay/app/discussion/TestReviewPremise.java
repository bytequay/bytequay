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
import com.bytequay.core.review.ReviewFinding;
import com.bytequay.core.review.ReviewInvestigation;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.review.ReviewSummary;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;
import java.util.stream.IntStream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestReviewPremise
{
    private static final Instant NOW = Instant.parse("2026-10-01T00:00:00Z");
    private static final String HEADER = "--- review so far ---\n\n";

    @Test
    void rendersTheNewestSummaryEveryFindingAndTheNewestCandidatesAtTheirLatestAssessment()
            throws Exception
    {
        ReviewRun older = run("run-1", 1, "a".repeat(40))
                .withSummary(new ReviewSummary(1, "An older purpose", ImmutableList.of(), "", Optional.empty(), ImmutableList.of()), NOW)
                .withInvestigation(investigation(ImmutableList.of("An older claim"), ImmutableList.of()), NOW);
        ReviewRun newer = run("run-2", 2, "b".repeat(40))
                .withSummary(new ReviewSummary(1, "Resolve owners by id", ImmutableList.of(new ReviewSummary.CommitExplanation("c".repeat(40), "Keys the cache by id.")),
                        "", Optional.empty(), ImmutableList.of()), NOW)
                .withInvestigation(investigation(
                        ImmutableList.of("@carol: the cache leaks entries", "The parser keeps state"),
                        ImmutableList.of(
                                validation(1, ReviewCandidateValidation.Outcome.UNRESOLVED, "Needs a trace"),
                                validation(2, ReviewCandidateValidation.Outcome.SUPPORTED, "It does; see F-1"),
                                validation(1, ReviewCandidateValidation.Outcome.REJECTED, "close() evicts every entry"))), NOW);
        ReviewFinding later = finding("f2", "A later finding", NOW.plusSeconds(60), Optional.empty(), false);
        ReviewFinding earlier = finding("f1", "Reset the parser", NOW, Optional.of(new ReviewAnchor("src/Parser.java", "RIGHT", 3, 5, ImmutableList.of())), true);

        JsonNode premise = parse(ReviewPremise.render(ImmutableList.of(newer, older), ImmutableList.of(later, earlier)));

        assertThat(premise.path("head").asText()).isEqualTo("b".repeat(40));
        assertThat(premise.path("purpose").asText()).isEqualTo("Resolve owners by id");
        assertThat(premise.path("commits").toString()).isEqualTo("[{\"sha\":\"" + "c".repeat(40) + "\",\"explanation\":\"Keys the cache by id.\"}]");
        assertThat(premise.path("findings").toString()).isEqualTo("""
                [{"title":"Reset the parser","severity":"major","state":"OPEN","outdated":true,"path":"src/Parser.java","range":"R3–R5","body":"Body of Reset the parser"},\
                {"title":"A later finding","severity":"major","state":"OPEN","body":"Body of A later finding"}]""");
        assertThat(premise.path("candidates").toString()).isEqualTo("""
                [{"claim":"@carol: the cache leaks entries","outcome":"REJECTED","conclusion":"close() evicts every entry"},\
                {"claim":"The parser keeps state","outcome":"SUPPORTED","conclusion":"It does; see F-1"}]""");
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
                .withSummary(new ReviewSummary(1, "p".repeat(5_000), ImmutableList.of(), "", Optional.empty(), ImmutableList.of()), NOW)
                .withInvestigation(investigation(ImmutableList.of("x".repeat(1_500)), ImmutableList.of()), NOW);

        JsonNode premise = parse(ReviewPremise.render(ImmutableList.of(run), ImmutableList.of()));

        assertThat(premise.path("purpose").asText()).isEqualTo("p".repeat(4_000) + " [truncated]");
        assertThat(premise.path("candidates").get(0).path("claim").asText()).isEqualTo("x".repeat(1_000) + " [truncated]");
        assertThat(premise.path("candidates").get(0).has("outcome")).isFalse();
    }

    @Test
    void refusesAPremiseOverTheSeedBudget()
    {
        ImmutableList<ReviewFinding> findings = IntStream.range(0, 320)
                .mapToObj(index -> new ReviewFinding("f" + index, "r1", "run-1", index + 1, "Finding " + index, "b".repeat(4_000), Optional.empty(), Optional.empty(),
                        ImmutableList.of(), 1, ReviewFinding.State.OPEN, Optional.empty(), "a".repeat(40), false, 0, NOW, NOW))
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
        return new ReviewInvestigation("change map", ImmutableList.of(), ImmutableList.of(), candidates, validations, ImmutableList.of(), Optional.empty());
    }

    private static ReviewCandidateValidation validation(int candidate, ReviewCandidateValidation.Outcome outcome, String conclusion)
    {
        return new ReviewCandidateValidation(candidate, outcome, conclusion, ImmutableList.of(), "", Optional.empty(), Optional.empty(), Optional.empty());
    }

    private static ReviewFinding finding(String id, String title, Instant createdAt, Optional<ReviewAnchor> anchor, boolean outdated)
    {
        return new ReviewFinding(id, "r1", "run-1", 1, title, "Body of " + title, Optional.of("major"), anchor, ImmutableList.of(), 1,
                ReviewFinding.State.OPEN, Optional.empty(), "a".repeat(40), outdated, 0, createdAt, createdAt);
    }

    private static ReviewRun run(String id, int ordinal, String headSha)
    {
        return new ReviewRun(id, "r1", ordinal, ReviewRun.Scope.FULL, "main", "0".repeat(40), "0".repeat(40), headSha, Optional.empty(),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ReviewRun.State.RUNNING, Optional.empty(), Optional.empty(), Optional.empty(),
                ImmutableList.of(), false, ImmutableMap.of(), ImmutableMap.of(), Optional.empty(), 0, NOW, NOW, Optional.empty());
    }
}
