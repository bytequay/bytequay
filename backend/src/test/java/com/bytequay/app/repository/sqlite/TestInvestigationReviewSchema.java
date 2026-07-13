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
package com.bytequay.app.repository.sqlite;

import com.bytequay.app.domain.InvestigationReviewData.AssignmentBudget;
import com.bytequay.app.domain.InvestigationReviewData.CriterionRow;
import com.bytequay.app.domain.InvestigationReviewData.FindingEvidenceRow;
import com.bytequay.app.domain.InvestigationReviewData.FindingRow;
import com.bytequay.app.domain.InvestigationReviewData.InvestigationStepRow;
import com.bytequay.app.domain.InvestigationReviewData.ObservationRow;
import com.bytequay.app.domain.InvestigationReviewData.ReviewAssignmentRow;
import com.bytequay.app.domain.InvestigationReviewData.ReviewObjectiveRow;
import com.bytequay.app.domain.InvestigationReviewData.ReviewRoundRow;
import com.bytequay.app.domain.InvestigationReviewData.ReviewSessionRow;
import com.bytequay.app.domain.InvestigationReviewData.ReviewerDefRow;
import com.bytequay.app.domain.InvestigationReviewData.RoundBudget;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRCommit;
import com.bytequay.app.domain.PRTimelineEntry;
import com.bytequay.app.repository.PRStore;
import com.bytequay.app.service.review.InvestigationReviewService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestExecutionListeners(
        listeners = DependencyInjectionTestExecutionListener.class,
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS)
class TestInvestigationReviewSchema
{
    @Autowired
    private InvestigationReviewStore reviews;
    @Autowired
    private PRStore prs;
    @Autowired
    private InvestigationReviewService investigationReviews;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private MockMvc mvc;

    @Test
    void persistsTheFrozenTraceabilityChainAndDetachedPanelRun()
            throws Exception
    {
        String prId = UUID.randomUUID().toString();
        prs.save(PR.createExternal(
                prId, "acme/widget", 7, "https://example.test/7", "octocat",
                "feature", "main", "Change behavior", "", PR.STATUS_REMOTE_OPEN,
                Instant.parse("2026-07-01T00:00:00Z"), null, null));
        investigationReviews.recordPublished(prId, "APPROVE", List.of(), List.of());
        assertThat(prs.timelineFor(prId)).isEmpty();
        String runId = UUID.randomUUID().toString();
        jdbc.update("INSERT INTO agent_run (id,kind,status,iterations,budget,started_at_ms) VALUES (?,?,?,?,?,?)",
                runId, "panel_review", "running", 0, 50, 1L);

        String sessionId = UUID.randomUUID().toString();
        String roundId = UUID.randomUUID().toString();
        String criterionId = UUID.randomUUID().toString();
        String objectiveId = UUID.randomUUID().toString();
        String assignmentId = UUID.randomUUID().toString();
        String stepId = UUID.randomUUID().toString();
        String observationId = UUID.randomUUID().toString();
        String findingId = UUID.randomUUID().toString();
        reviews.insertSession(new ReviewSessionRow(
                sessionId, "acme/widget", prId, "base", "head", "ACTIVE"), Instant.now());
        reviews.insertRound(new ReviewRoundRow(
                roundId, sessionId, runId, "initial", "full", "head", null,
                "RUNNING", new RoundBudget(50, 10), 0), Instant.now());
        reviews.insertCriterion(new CriterionRow(
                criterionId, "acme/widget", "hard-invariant", "Preserve behavior", "shipped-rule", "correctness"));
        reviews.insertObjective(new ReviewObjectiveRow(
                objectiveId, roundId, criterionId, "Preserve behavior", "shipped-rule", "applicable", "pending"));
        reviews.insertAssignment(new ReviewAssignmentRow(
                assignmentId, roundId, "general-api", "api", "investigating", "summary",
                List.of(), List.of(), new AssignmentBudget(6, 3, 12, 5)));
        reviews.insertStep(new InvestigationStepRow(
                stepId, assignmentId, null, "read_file", mapper.createObjectNode(),
                "inspect changed behavior", true, 0, "completed"));
        String unexecutedStepId = UUID.randomUUID().toString();
        reviews.insertStep(new InvestigationStepRow(
                unexecutedStepId, assignmentId, null, "search_diff", mapper.createObjectNode(),
                "planned but not executed", true, 0, "running"));
        reviews.insertStep(new InvestigationStepRow(
                UUID.randomUUID().toString(), assignmentId, null, "sweep:line-scan", mapper.createObjectNode(),
                "mandatory deterministic sweep", false, 0, "completed"));
        reviews.insertStep(new InvestigationStepRow(
                UUID.randomUUID().toString(), assignmentId, null, "user-answer", mapper.createObjectNode(),
                "continuation seed", false, 0, "completed"));
        reviews.skipRunningSteps(assignmentId);
        reviews.insertObservation(new ObservationRow(
                observationId, stepId, "source", "head", "src/A.java", 10, 12,
                null, null, null, null, "digest", "10 return true;"));
        reviews.insertFinding(new FindingRow(
                findingId, sessionId, roundId, objectiveId, null, "hard-invariant",
                "Missing values are accepted", 3, "SUPPORTED", "unknown",
                "Preserve the old failure", "candidate", "head"));
        reviews.insertEvidence(new FindingEvidenceRow(
                findingId, observationId, "SUPPORTS", "The return path accepts missing values",
                "E1", "Local source interpretation.", "DIRECT_ONLY", mapper.createObjectNode()));

        assertThat(reviews.findSession(sessionId)).isPresent();
        assertThat(reviews.rounds(sessionId)).singleElement().extracting(ReviewRoundRow::agentRunId).isEqualTo(runId);
        assertThat(reviews.findings(sessionId)).singleElement().extracting(FindingRow::id).isEqualTo(findingId);
        assertThat(reviews.evidence(sessionId)).singleElement().extracting(FindingEvidenceRow::observationId)
                .isEqualTo(observationId);
        assertThat(reviews.steps(sessionId)).filteredOn(step -> step.id().equals(unexecutedStepId))
                .singleElement().extracting(InvestigationStepRow::status).isEqualTo("skipped");
        assertThat(reviews.countSteps(assignmentId)).isEqualTo(2);
        assertThat(reviews.dashboardStates()).containsEntry(prId, "running");
        assertThat(jdbc.queryForObject("SELECT task_id FROM agent_run WHERE id=?", String.class, runId)).isNull();
        mvc.perform(get("/api/runs/{runId}/log", runId))
                .andExpect(status().isConflict());
        String genuineReviewEventId = UUID.randomUUID().toString();
        prs.addEvent(new PRTimelineEntry(
                genuineReviewEventId, prId, PRTimelineEntry.TYPE_REVIEW, "agent", true,
                null, Instant.now(), "{\"reviewEvent\":\"round-complete\"}", null));
        prs.addEvent(new PRTimelineEntry(
                UUID.randomUUID().toString(), prId, PRTimelineEntry.TYPE_REVIEW, "github", false,
                null, Instant.now(), "{\"body\":\"literal reviewEvent text\"}", 99L));
        assertThat(investigationReviews.roundLog(roundId).prTimelineEvents())
                .extracting(event -> event.id()).containsExactly(genuineReviewEventId);
        assertThat(reviews.updateRunningRoundCost(roundId, 7)).isTrue();
        assertThat(reviews.finishRunningRound(roundId, "CANCELLED", null, 7)).isTrue();
        assertThat(reviews.updateRunningRoundCost(roundId, 8)).isFalse();
        assertThat(reviews.finishRunningRound(roundId, "COMPLETED", "head", 8)).isFalse();
        assertThat(reviews.findRound(roundId)).get().satisfies(cancelled -> {
            assertThat(cancelled.status()).isEqualTo("CANCELLED");
            assertThat(cancelled.costCents()).isEqualTo(7);
        });
        assertThat(reviews.dashboardStates()).containsEntry(prId, "done");
    }

    @Test
    void recordsManualOnlySubmissionAgainstAnInvestigationSession()
            throws Exception
    {
        String prId = UUID.randomUUID().toString();
        prs.save(PR.createExternal(
                prId, "acme/widget", 8, "https://example.test/8", "octocat",
                "feature", "main", "Change behavior", "", PR.STATUS_REMOTE_OPEN,
                Instant.parse("2026-07-01T00:00:00Z"), null, null));
        reviews.insertSession(new ReviewSessionRow(
                UUID.randomUUID().toString(), "acme/widget", prId, "base", "head", "ACTIVE"),
                Instant.now());

        investigationReviews.recordPublished(
                prId, "COMMENT", List.of(), List.of("manual-comment"));

        PRTimelineEntry submitted = prs.timelineFor(prId).get(0);
        assertThat(mapper.readTree(submitted.payloadJson()).path("reviewEvent").asText())
                .isEqualTo("submitted");
        assertThat(mapper.readTree(submitted.payloadJson()).path("count").asInt()).isEqualTo(1);
    }

    @Test
    void staleHeadCancelsTheRoundAndEveryOwnedRun()
    {
        String prId = UUID.randomUUID().toString();
        prs.save(PR.createExternal(
                prId, "acme/widget", 9, "https://example.test/9", "octocat",
                "feature", "main", "Change behavior", "", PR.STATUS_REMOTE_OPEN,
                Instant.parse("2026-07-01T00:00:00Z"), null, null));
        prs.addCommit(new PRCommit(
                UUID.randomUUID().toString(), prId, "new-head", "push", 1, 0,
                Instant.parse("2026-07-01T00:00:00Z"), Instant.parse("2026-07-01T00:00:00Z")));
        String sessionId = UUID.randomUUID().toString();
        String roundId = UUID.randomUUID().toString();
        String roundRunId = UUID.randomUUID().toString();
        String verifierRunId = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO agent_run
                (id,kind,review_round_id,status,iterations,budget,started_at_ms)
                VALUES (?,?,?,?,?,?,?)
                """, roundRunId, "panel_review", roundId, "running", 0, 50, 1L);
        jdbc.update("""
                INSERT INTO agent_run
                (id,kind,review_round_id,status,iterations,budget,started_at_ms)
                VALUES (?,?,?,?,?,?,?)
                """, verifierRunId, "panel_review", roundId, "running", 0, 20, 2L);
        reviews.insertSession(new ReviewSessionRow(
                sessionId, "acme/widget", prId, "base", "old-head", "ACTIVE"), Instant.now());
        reviews.insertRound(new ReviewRoundRow(
                roundId, sessionId, roundRunId, "initial", "full", "old-head", null,
                "RUNNING", new RoundBudget(50, 10), 0), Instant.now());

        var detail = investigationReviews.findByPr(prId).orElseThrow();

        assertThat(detail.session().status()).isEqualTo("STALE");
        assertThat(detail.rounds()).singleElement()
                .extracting(ReviewRoundRow::status).isEqualTo("CANCELLED");
        assertThat(detail.runs()).extracting(run -> run.id() + ":" + run.status())
                .containsExactlyInAnyOrder(
                        roundRunId + ":cancelled", verifierRunId + ":cancelled");
    }

    @Test
    void keepsLegacyResponseRoundsSeparateFromInvestigationRounds()
    {
        List<String> tables = jdbc.queryForList(
                "SELECT name FROM sqlite_master WHERE type='table' AND name IN ('response_round','review_round')",
                String.class);
        assertThat(tables).containsExactlyInAnyOrder("response_round", "review_round");
        assertThat(jdbc.query("PRAGMA table_info(pr_comment)", (rs, row) -> rs.getString("name")))
                .contains("finding_id");
    }

    @Test
    void reviewerDefinitionsAreConfigurableWithoutBreakingHistoricalAssignments()
    {
        ReviewerDefRow custom = new ReviewerDefRow(
                "security-api", "Security", "Security boundary specialist", "api",
                mapper.createObjectNode().put("provider", "auto"),
                "Prioritize authentication and authorization boundaries.",
                List.of("standard", "high-risk"), true);

        reviews.upsertReviewerDef(custom);
        assertThat(reviews.findReviewerDef(custom.id())).contains(custom);
        assertThat(reviews.reviewerDefs()).extracting(ReviewerDefRow::id)
                .contains("general-api", "general-cli", "independent-verifier", custom.id());

        assertThat(reviews.disableReviewerDef(custom.id())).isTrue();
        assertThat(reviews.findReviewerDef(custom.id())).get()
                .extracting(ReviewerDefRow::enabled).isEqualTo(false);
    }

    @Test
    void exposesReviewerDefinitionCrud()
            throws Exception
    {
        String id = "api-contract-" + UUID.randomUUID();
        String body = mapper.writeValueAsString(Map.of(
                "id", id,
                "name", "Contract reviewer",
                "description", "Checks interface contracts",
                "runner", "api",
                "runner_json", Map.of("provider", "auto"),
                "persona", "Trace externally visible behavior.",
                "eligible_kinds", List.of("standard"),
                "enabled", true));

        mvc.perform(post("/api/reviewer-defs")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.eligible_kinds[0]").value("standard"));
        mvc.perform(get("/api/reviewer-defs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.id == '" + id + "')]").exists());
        mvc.perform(delete("/api/reviewer-defs/{id}", id))
                .andExpect(status().isOk());
        assertThat(reviews.findReviewerDef(id)).get()
                .extracting(ReviewerDefRow::enabled).isEqualTo(false);
    }
}
