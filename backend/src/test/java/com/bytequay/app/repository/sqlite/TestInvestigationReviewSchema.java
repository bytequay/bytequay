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

import com.bytequay.app.domain.InvestigationReviewData.AgentReviewRow;
import com.bytequay.app.domain.InvestigationReviewData.AssignmentBudget;
import com.bytequay.app.domain.InvestigationReviewData.CriterionRow;
import com.bytequay.app.domain.InvestigationReviewData.FindingEvidenceRow;
import com.bytequay.app.domain.InvestigationReviewData.FindingRow;
import com.bytequay.app.domain.InvestigationReviewData.InvestigationStepRow;
import com.bytequay.app.domain.InvestigationReviewData.ObservationRow;
import com.bytequay.app.domain.InvestigationReviewData.ReviewAssignmentRow;
import com.bytequay.app.domain.InvestigationReviewData.ReviewObjectiveRow;
import com.bytequay.app.domain.InvestigationReviewData.ReviewRoundMessageRow;
import com.bytequay.app.domain.InvestigationReviewData.ReviewRoundRow;
import com.bytequay.app.domain.InvestigationReviewData.ReviewedCommitRow;
import com.bytequay.app.domain.InvestigationReviewData.ReviewerDefRow;
import com.bytequay.app.domain.InvestigationReviewData.RoundBudget;
import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRComment;
import com.bytequay.app.domain.PRCommit;
import com.bytequay.app.domain.PRTimelineEntry;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.repository.PRStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.localpr.PRService;
import com.bytequay.app.service.review.InvestigationReviewService;
import com.bytequay.app.service.review.InvestigationReviewService.StartOptions;
import com.bytequay.app.service.threads.ThreadService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
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
    private PRService localPrs;
    @Autowired
    private ThreadStore threads;
    @Autowired
    private ThreadService threadService;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper mapper;
    @Autowired
    private MockMvc mvc;

    @BeforeEach
    void createFixtureWorkspace()
    {
        jdbc.update("""
                INSERT OR IGNORE INTO workspaces(id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
                VALUES ('ws-test', 'test/repository', '', 0, 1, 1)
                """);
    }

    @Test
    void persistsTheFrozenTraceabilityChainAndDetachedPanelRun()
            throws Exception
    {
        long spendBefore = reviews.sumRoundCostCentsSince(Instant.EPOCH);
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
        reviews.insertReview(new AgentReviewRow(
                sessionId, "acme/widget", prId, "base", "head", "ACTIVE",
                null, null, null), Instant.now());
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

        assertThat(reviews.findReview(sessionId)).isPresent();
        assertThat(reviews.rounds(sessionId)).singleElement().satisfies(persistedRound -> {
            assertThat(persistedRound.agentRunId()).isEqualTo(runId);
            assertThat(persistedRound.capabilitiesJson().sourceMode()).isEqualTo("remote-only");
            assertThat(persistedRound.triggerStageId()).isNull();
        });
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
        assertThat(reviews.sumRoundCostCentsSince(Instant.EPOCH)).isEqualTo(spendBefore + 7);
        assertThat(reviews.agentReviewSpend(
                Instant.EPOCH, Instant.now().plusSeconds(60))).anySatisfy(spend -> {
                    assertThat(spend.provider()).isEqualTo("agent-review");
                    assertThat(spend.costMilli()).isEqualTo(70L);
                    assertThat(spend.calls()).isEqualTo(1L);
                });
        reviews.markRoundFinalized(roundId);
        assertThat(reviews.dashboardStates()).containsEntry(prId, "done");
    }

    @Test
    void atomicallyQueuesRoundsUntilTheEarlierLifecycleIsFinalized()
    {
        String prId = UUID.randomUUID().toString();
        String reviewId = UUID.randomUUID().toString();
        prs.save(PR.createExternal(
                prId, "acme/queue-" + prId, 17, "https://example.test/17", "octocat",
                "feature", "main", "Serialize review rounds", "", PR.STATUS_REMOTE_OPEN,
                Instant.parse("2026-07-01T00:00:00Z"), null, null));
        reviews.insertReview(new AgentReviewRow(
                reviewId, "acme/widget", prId, "base", "head", "ACTIVE",
                null, null, null), Instant.now());
        String firstRun = insertRun();
        String secondRun = insertRun();
        ReviewRoundRow first = reviews.insertLiveRound(new ReviewRoundRow(
                UUID.randomUUID().toString(), reviewId, firstRun, "continuation", "delta",
                "head", null, "RUNNING", new RoundBudget(100, 10), 0), Instant.now());
        ReviewRoundRow second = reviews.insertLiveRound(new ReviewRoundRow(
                UUID.randomUUID().toString(), reviewId, secondRun, "continuation", "delta",
                "head", null, "RUNNING", new RoundBudget(100, 10), 0), Instant.now());

        assertThat(first.status()).isEqualTo("RUNNING");
        assertThat(second.status()).isEqualTo("QUEUED");
        assertThat(reviews.dashboardStates()).containsEntry(prId, "running");
        assertThat(prs.hasRunningAgentReview(prId)).isTrue();
        assertThat(reviews.queuedRoundCanStart(second.id())).isFalse();

        assertThat(reviews.finishRunningRound(first.id(), "COMPLETED", "head", 25)).isTrue();
        assertThat(reviews.queuedRoundCanStart(second.id())).isFalse();
        assertThat(reviews.startQueuedRound(second.id())).isFalse();
        reviews.markRoundFinalized(first.id());
        assertThat(reviews.queuedRoundCanStart(second.id())).isTrue();
        assertThat(reviews.startQueuedRound(second.id())).isTrue();
        assertThat(reviews.queuedRoundCanStart(second.id())).isFalse();

        String assignmentId = UUID.randomUUID().toString();
        reviews.insertAssignment(new ReviewAssignmentRow(
                assignmentId, second.id(), "general-api", "api", "investigating", "",
                List.of(), List.of(), new AssignmentBudget(6, 3, 12, 5)));
        reviews.settleRoundCost(second.id(), 40);
        assertThat(reviews.cancelLiveRound(second.id(), 5)).isTrue();
        assertThat(reviews.findRound(second.id())).get()
                .extracting(ReviewRoundRow::costCents).isEqualTo(40);
        assertThat(reviews.assignments(reviewId)).filteredOn(row -> row.id().equals(assignmentId))
                .singleElement().extracting(ReviewAssignmentRow::status).isEqualTo("cancelled");
        reviews.markRoundFinalized(second.id());
        assertThat(prs.hasRunningAgentReview(prId)).isFalse();
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
        String ownerThreadId = saveReviewThread();
        reviews.insertReview(new AgentReviewRow(
                UUID.randomUUID().toString(), "acme/widget", prId, "base", "head", "ACTIVE",
                "ws-test", ownerThreadId, null),
                Instant.now());

        investigationReviews.recordPublished(
                prId, "COMMENT", List.of(), List.of("manual-comment"));

        PRTimelineEntry submitted = prs.timelineFor(prId).get(0);
        assertThat(mapper.readTree(submitted.payloadJson()).path("reviewEvent").asText())
                .isEqualTo("submitted");
        assertThat(mapper.readTree(submitted.payloadJson()).path("count").asInt()).isEqualTo(1);
        assertThat(mapper.readTree(submitted.payloadJson()).path("reviewId").asText()).isNotBlank();
    }

    @Test
    void standaloneReviewReachesTheRemoteReviewPath()
    {
        String prId = UUID.randomUUID().toString();
        prs.save(PR.createExternal(
                prId, "acme/standalone-" + prId, 10, "https://example.test/10", "octocat",
                "feature", "main", "Change behavior", "", PR.STATUS_REMOTE_OPEN,
                Instant.parse("2026-07-01T00:00:00Z"), null, null));

        assertThatThrownBy(() -> investigationReviews.start(
                prId, new StartOptions(null, null, null)))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("GitHub PAT not configured");
    }

    @Test
    void resolvingAndReopeningAFindingKeepsItsLocalCommentInSync()
    {
        String prId = UUID.randomUUID().toString();
        String reviewId = UUID.randomUUID().toString();
        String roundId = UUID.randomUUID().toString();
        String runId = insertRun();
        String criterionId = UUID.randomUUID().toString();
        String objectiveId = UUID.randomUUID().toString();
        String findingId = UUID.randomUUID().toString();
        prs.save(PR.createExternal(
                prId, "acme/lifecycle-" + prId, 18, "https://example.test/18", "octocat",
                "feature", "main", "Synchronize review state", "", PR.STATUS_REMOTE_OPEN,
                Instant.parse("2026-07-01T00:00:00Z"), null, null));
        reviews.insertReview(new AgentReviewRow(
                reviewId, "acme/widget", prId, "base", "head", "ACTIVE",
                null, null, null), Instant.now());
        reviews.insertRound(new ReviewRoundRow(
                roundId, reviewId, runId, "initial", "full", "head", "head",
                "COMPLETED", new RoundBudget(50, 10), 1), Instant.now());
        reviews.insertCriterion(new CriterionRow(
                criterionId, "acme/widget", "hard-invariant", "Preserve behavior", "test", null));
        reviews.insertObjective(new ReviewObjectiveRow(
                objectiveId, roundId, criterionId, "Preserve behavior", "test", "applicable", "finding"));
        reviews.insertFinding(new FindingRow(
                findingId, reviewId, roundId, objectiveId, null, "hard-invariant",
                "The behavior regressed", 3, "VERIFIED", "verified",
                "Restore it", "included", "head"));
        PRComment root = localPrs.addComment(
                prId, PRComment.ORIGIN_LOCAL, PRComment.SCOPE_PR,
                null, null, null, null, null, "agent", "Finding", null);
        localPrs.attachFinding(root.id(), findingId);

        investigationReviews.mutateFinding(
                findingId, new InvestigationReviewService.FindingMutation("resolve", null));

        assertThat(reviews.findFinding(findingId)).get()
                .extracting(FindingRow::lifecycleStatus).isEqualTo("resolved");
        assertThat(prs.commentsFor(prId)).singleElement().satisfies(comment -> {
            assertThat(comment.resolvedAt()).isNotNull();
            assertThat(comment.dismissedAt()).isNull();
        });

        investigationReviews.mutateFinding(
                findingId, new InvestigationReviewService.FindingMutation("reopen", null));

        assertThat(reviews.findFinding(findingId)).get()
                .extracting(FindingRow::lifecycleStatus).isEqualTo("open");
        assertThat(prs.commentsFor(prId)).singleElement().satisfies(comment -> {
            assertThat(comment.resolvedAt()).isNull();
            assertThat(comment.dismissedAt()).isNull();
        });
    }

    @Test
    void deletingReviewOwnerThreadPurgesLocalReviewDataButKeepsThePr()
    {
        String prId = UUID.randomUUID().toString();
        String reviewId = UUID.randomUUID().toString();
        String roundId = UUID.randomUUID().toString();
        String runId = UUID.randomUUID().toString();
        String criterionId = UUID.randomUUID().toString();
        String objectiveId = UUID.randomUUID().toString();
        String findingId = UUID.randomUUID().toString();
        String ownerThreadId = saveReviewThread();
        prs.save(PR.createExternal(
                prId, "acme/purge-" + prId, 11, "https://example.test/11", "octocat",
                "feature", "main", "Change behavior", "", PR.STATUS_REMOTE_OPEN,
                Instant.parse("2026-07-01T00:00:00Z"), null, null));
        jdbc.update("INSERT INTO agent_run (id,kind,status,iterations,budget,started_at_ms) VALUES (?,?,?,?,?,?)",
                runId, "panel_review", "completed", 0, 50, 1L);
        reviews.insertReview(new AgentReviewRow(
                reviewId, "acme/widget", prId, "base", "head", "ACTIVE",
                "ws-test", ownerThreadId, null), Instant.now());
        reviews.insertRound(new ReviewRoundRow(
                roundId, reviewId, runId, "initial", "full", "head", "head",
                "COMPLETED", new RoundBudget(50, 10), 1), Instant.now());
        reviews.insertCriterion(new CriterionRow(
                criterionId, "acme/widget", "hard-invariant", "Preserve behavior", "test", null));
        reviews.insertObjective(new ReviewObjectiveRow(
                objectiveId, roundId, criterionId, "Preserve behavior", "test", "applicable", "finding"));
        reviews.insertFinding(new FindingRow(
                findingId, reviewId, roundId, objectiveId, null, "hard-invariant",
                "The behavior regressed", 3, "VERIFIED", "verified",
                "Restore it", "included", "head"));
        PRComment root = localPrs.addComment(
                prId, PRComment.ORIGIN_LOCAL, PRComment.SCOPE_PR,
                null, null, null, null, null, "agent", "Finding", null);
        localPrs.attachFinding(root.id(), findingId);
        localPrs.addComment(
                prId, PRComment.ORIGIN_LOCAL, PRComment.SCOPE_PR,
                null, null, null, null, null, "you", "Follow-up", root.id());
        localPrs.recordReviewEvent(
                prId, "agent", "{\"reviewEvent\":\"round-complete\",\"reviewId\":\"" + reviewId + "\"}");

        threadService.purge(ownerThreadId);

        assertThat(threads.findThreadById(ownerThreadId)).isEmpty();
        assertThat(reviews.findReview(reviewId)).isEmpty();
        assertThat(prs.commentsFor(prId)).isEmpty();
        assertThat(prs.timelineFor(prId)).isEmpty();
        assertThat(prs.findById(prId)).isPresent();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM agent_run WHERE id = ?", Integer.class, runId)).isZero();
    }

    @Test
    void staleHeadCancelsTheRoundAndEveryOwnedRun()
            throws Exception
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
        String ownerThreadId = saveReviewThread();
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
        reviews.insertReview(new AgentReviewRow(
                sessionId, "acme/widget", prId, "base", "old-head", "ACTIVE",
                "ws-test", ownerThreadId, null), Instant.now());
        reviews.insertRound(new ReviewRoundRow(
                roundId, sessionId, roundRunId, "initial", "full", "old-head", null,
                "RUNNING", new RoundBudget(50, 10), 0), Instant.now());

        var detail = investigationReviews.findByPr(prId).orElseThrow();

        assertThat(detail.review().status()).isEqualTo("STALE");
        assertThat(detail.review().workspaceId()).isEqualTo("ws-test");
        assertThat(detail.review().ownerTaskId()).isNull();
        assertThat(detail.review().ownerThreadId()).isNotBlank();
        assertThat(detail.rounds()).singleElement()
                .extracting(ReviewRoundRow::status).isEqualTo("CANCELLED");
        assertThat(detail.runs()).extracting(run -> run.id() + ":" + run.status())
                .containsExactlyInAnyOrder(
                        roundRunId + ":cancelled", verifierRunId + ":cancelled");
        assertThat(jdbc.queryForObject(
                "SELECT status FROM threads WHERE id=?", String.class,
                detail.review().ownerThreadId())).isEqualTo("NEEDS_ATTENTION");
        mvc.perform(get("/api/agent-reviews/by-thread/{threadId}",
                        detail.review().ownerThreadId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.review.owner_thread_id")
                        .value(detail.review().ownerThreadId()))
                .andExpect(jsonPath("$.review.workspace_id").value("ws-test"));
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
        assertThat(jdbc.query("PRAGMA table_info(review_session)", (rs, row) -> rs.getString("name")))
                .contains("workspace_id", "owner_thread_id", "owner_task_id");
        assertThat(jdbc.query("PRAGMA table_info(review_round)", (rs, row) -> rs.getString("name")))
                .contains("capabilities_json", "trigger_stage_id", "message_gate_open");
        assertThat(jdbc.queryForList("""
                SELECT name FROM sqlite_master
                WHERE type = 'table' AND name IN ('review_round_message', 'review_round_commit')
                """, String.class))
                .containsExactlyInAnyOrder("review_round_message", "review_round_commit");
        assertThat(jdbc.query("PRAGMA table_info(review_round_message)",
                (rs, row) -> rs.getString("name"))).contains("assignment_id");
    }

    @Test
    void persistsRoundGuidanceAndCommitsWithoutLosingFinishRaces()
    {
        RunningRound running = insertRunningRound();
        reviews.insertReviewedCommit(new ReviewedCommitRow(
                running.roundId(), "commit-a", "First relevant change", 0));
        reviews.insertReviewedCommit(new ReviewedCommitRow(
                running.roundId(), "commit-b", "Second relevant change", 1));
        ReviewRoundMessageRow message = new ReviewRoundMessageRow(
                UUID.randomUUID().toString(), running.roundId(), "panel", "you",
                "Check the retry boundary", "pending", null, 10L, null);

        assertThat(reviews.insertPendingRoundMessage(message)).isTrue();
        assertThat(reviews.closeMessageGateIfDrained(running.roundId())).isFalse();
        assertThat(reviews.finishRunningRound(
                running.roundId(), "COMPLETED", "head", 3)).isFalse();
        assertThat(reviews.claimRoundMessage(message.id())).isTrue();
        String guidanceAssignmentId = reviews.assignments(running.reviewId()).get(0).id();
        assertThat(reviews.linkRoundMessageAssignment(
                message.id(), guidanceAssignmentId)).isTrue();
        assertThat(reviews.completeRoundMessage(
                message.id(), "completed", "Boundary checked.", Instant.ofEpochMilli(20))).isTrue();
        assertThat(reviews.closeMessageGateIfDrained(running.roundId())).isTrue();
        assertThat(reviews.insertPendingRoundMessage(new ReviewRoundMessageRow(
                UUID.randomUUID().toString(), running.roundId(), "panel", "you",
                "Too late", "pending", null, 30L, null))).isFalse();
        assertThat(reviews.updateRunningRoundBudget(
                running.roundId(), new RoundBudget(75, 10))).isTrue();
        assertThat(reviews.finishRunningRound(
                running.roundId(), "COMPLETED", "head", 3)).isTrue();

        assertThat(reviews.roundMessages(running.reviewId())).singleElement().satisfies(row -> {
            assertThat(row.target()).isEqualTo("panel");
            assertThat(row.sender()).isEqualTo("you");
            assertThat(row.body()).isEqualTo("Check the retry boundary");
            assertThat(row.status()).isEqualTo("completed");
            assertThat(row.response()).isEqualTo("Boundary checked.");
            assertThat(row.createdAt()).isEqualTo(10L);
            assertThat(row.completedAt()).isEqualTo(20L);
            assertThat(row.assignmentId()).isEqualTo(guidanceAssignmentId);
        });
        assertThat(reviews.reviewedCommits(running.reviewId()))
                .extracting(ReviewedCommitRow::sha)
                .containsExactly("commit-a", "commit-b");
        assertThat(reviews.findRound(running.roundId())).get()
                .satisfies(row -> {
                    assertThat(row.budgetJson().costCapCents()).isEqualTo(75);
                    assertThat(row.messageGateOpen()).isFalse();
                });
    }

    @Test
    void cancellingARoundAtomicallyCancelsAcceptedGuidance()
    {
        RunningRound running = insertRunningRound();
        ReviewRoundMessageRow message = new ReviewRoundMessageRow(
                UUID.randomUUID().toString(), running.roundId(), "general-api", "you",
                "Inspect cancellation", "pending", null, 10L, null);
        assertThat(reviews.insertPendingRoundMessage(message)).isTrue();

        assertThat(reviews.finishRunningRound(
                running.roundId(), "CANCELLED", null, 0)).isTrue();

        assertThat(reviews.roundMessages(running.reviewId())).singleElement().satisfies(row -> {
            assertThat(row.status()).isEqualTo("cancelled");
            assertThat(row.response()).contains("cancelled");
            assertThat(row.completedAt()).isNotNull();
        });
        assertThat(reviews.claimRoundMessage(message.id())).isFalse();
    }

    @Test
    void cancelledRoundsRejectLaterChildArtifactMutations()
    {
        RunningRound running = insertRunningRound();
        String assignmentId = reviews.assignments(running.reviewId()).get(0).id();
        String firstStep = UUID.randomUUID().toString();
        assertThat(reviews.mutateWhileAssignmentRoundRunning(assignmentId, () ->
                reviews.insertStep(new InvestigationStepRow(
                        firstStep, assignmentId, null, "read-diff", mapper.createObjectNode(),
                        "Inspect the frozen diff", true, 0, "running")))).isTrue();

        assertThat(reviews.cancelLiveRound(running.roundId(), 0)).isTrue();
        assertThat(reviews.mutateWhileAssignmentRoundRunning(assignmentId, () ->
                reviews.insertStep(new InvestigationStepRow(
                        UUID.randomUUID().toString(), assignmentId, null, "late-write",
                        mapper.createObjectNode(), "Must not persist", true, 0, "running"))))
                .isFalse();

        assertThat(reviews.steps(running.reviewId()))
                .extracting(InvestigationStepRow::id)
                .containsExactly(firstStep);
    }

    @Test
    void exposesRoundGuidanceAndDynamicBudgetEndpoints()
            throws Exception
    {
        RunningRound running = insertRunningRound();

        mvc.perform(post("/api/review-rounds/{roundId}/messages", running.roundId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"panel\",\"text\":\"Trace the retry path\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.round_messages[0].round_id").value(running.roundId()))
                .andExpect(jsonPath("$.round_messages[0].target").value("panel"))
                .andExpect(jsonPath("$.round_messages[0].sender").value("you"))
                .andExpect(jsonPath("$.round_messages[0].body").value("Trace the retry path"))
                .andExpect(jsonPath("$.round_messages[0].status").value("pending"))
                .andExpect(jsonPath("$.rounds[0].message_gate_open").value(true));
        mvc.perform(post("/api/review-rounds/{roundId}/messages", running.roundId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"independent-verifier\",\"text\":\"Challenge the evidence\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/review-rounds/{roundId}/messages", running.roundId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"not-assigned\",\"text\":\"No\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/review-rounds/{roundId}/budget", running.roundId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"costCapCents\":60}"))
                .andExpect(status().isBadRequest());
        mvc.perform(patch("/api/review-rounds/{roundId}/budget", running.roundId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"costCapCents\":75}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rounds[0].budget_json.cost_cap_cents").value(75));

        assertThat(reviews.finishRunningRound(
                running.roundId(), "CANCELLED", null, 0)).isTrue();
        mvc.perform(post("/api/review-rounds/{roundId}/messages", running.roundId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"target\":\"panel\",\"text\":\"Late\"}"))
                .andExpect(status().isConflict());
        mvc.perform(patch("/api/review-rounds/{roundId}/budget", running.roundId())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"costCapCents\":100}"))
                .andExpect(status().isConflict());
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

    private String saveReviewThread()
    {
        String id = UUID.randomUUID().toString();
        Instant now = Instant.now();
        threads.saveThread(new Thread(
                id, ThreadKind.LOGIC_LOOP, "agent-review", null, "Agent review",
                ThreadStatus.COMPLETED, "agent-review", 0L, 0L, 0L,
                now, now, now, null, ThreadFlow.REVIEW, "ws-test", null));
        return id;
    }

    private String insertRun()
    {
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO agent_run (id,kind,status,iterations,budget,started_at_ms)
                VALUES (?,?,?,?,?,?)
                """, id, "panel_review", "running", 0, 100, 1L);
        return id;
    }

    private RunningRound insertRunningRound()
    {
        String prId = UUID.randomUUID().toString();
        String reviewId = UUID.randomUUID().toString();
        String roundId = UUID.randomUUID().toString();
        String runId = UUID.randomUUID().toString();
        prs.save(PR.createExternal(
                prId, "acme/guidance-" + prId, 12, "https://example.test/12", "octocat",
                "feature", "main", "Guide this review", "", PR.STATUS_REMOTE_OPEN,
                Instant.parse("2026-07-01T00:00:00Z"), null, null));
        jdbc.update("""
                INSERT INTO agent_run (id,kind,status,iterations,budget,started_at_ms)
                VALUES (?,?,?,?,?,?)
                """, runId, "panel_review", "running", 0, 50, 1L);
        reviews.insertReview(new AgentReviewRow(
                reviewId, "acme/widget", prId, "base", "head", "ACTIVE",
                null, null, null), Instant.now());
        reviews.insertRound(new ReviewRoundRow(
                roundId, reviewId, runId, "continuation", "full", "head", null,
                "RUNNING", new RoundBudget(50, 10), 0), Instant.now());
        reviews.insertAssignment(new ReviewAssignmentRow(
                UUID.randomUUID().toString(), roundId, "general-api", "api", "running", "",
                List.of(), List.of(), new AssignmentBudget(6, 3, 12, 5)));
        return new RunningRound(reviewId, roundId);
    }

    private record RunningRound(String reviewId, String roundId) {}
}
