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
package com.bytequay.app.repository.sqlite.migration;

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOperationHandler;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession;
import com.bytequay.app.developmentflow.execution.agentturn.ReviewAssignmentTurnOperationHandler;
import com.bytequay.app.developmentflow.persistence.SqliteDispatchWakeStore;
import com.bytequay.app.developmentflow.persistence.SqliteReviewAssignmentTurnStore;
import com.bytequay.app.service.review.ReviewAssignmentTurnResultDeliveryPort;
import com.bytequay.app.service.review.ReviewAssignmentTurnResultDeliveryPort.ResultCommand;
import com.bytequay.app.service.review.ReviewAssignmentTurnResultDeliveryPort.ResultReceipt;
import com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.Admission;
import com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.RetryCandidate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.sql.Connection;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.ACCEPTED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.SUPERSEDED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.FAILED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.SUCCEEDED;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOperationHandler.Disposition.PROVIDER_FAILED;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOperationHandler.Disposition.PROVIDER_SUCCEEDED;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.connect;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.execute;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedPublishedRemoteTask;
import static com.bytequay.app.repository.sqlite.migration.DevelopmentFlowRemoteProtocolFixture.seedWorkspaceAndTrunk;
import static org.assertj.core.api.Assertions.assertThat;

class TestReviewAssignmentTurnRuntimeMigration
{
    private static final Instant NOW = Instant.parse("2026-07-28T01:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    Path temporary;

    private SQLiteDataSource dataSource;
    private JdbcTemplate jdbc;
    private SqliteReviewAssignmentTurnStore store;

    @BeforeEach
    void setUp()
            throws Exception
    {
        String url = "jdbc:sqlite:" + temporary.resolve("review-turn.db")
                + "?foreign_keys=ON&busy_timeout=30000";
        Flyway.configure().dataSource(url, "", "").target("228").load().migrate();
        try (Connection connection = connect(url)) {
            seedWorkspaceAndTrunk(connection);
            seedPublishedRemoteTask(connection, 1);
            seedReview(connection);
        }
        Flyway.configure().dataSource(url, "", "").target("255").load().migrate();
        dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        jdbc = new JdbcTemplate(dataSource);
        store = new SqliteReviewAssignmentTurnStore(
                jdbc, new SqliteDispatchWakeStore(jdbc), JSON);
    }

    @Test
    void exactAdmissionAndResultSurviveDuplicateDeliveryAndRestart()
            throws Exception
    {
        Admission admission = admission(
                "review-turn-1", "review-operation-1", "review-ticket-1", 1);
        store.admitRound("round-1", "head-1", List.of(admission), NOW);

        assertThat(store.tryStart(
                admission.turnId(), admission.operationId(), NOW.plusSeconds(1)))
                .isEqualTo(ReviewAssignmentTurnOperationHandler.StartDisposition.STARTED);
        ResultCommand result = result(admission, SUCCEEDED, PROVIDER_SUCCEEDED);

        assertThat(store.accept(result).acceptance()).isEqualTo(ACCEPTED);
        assertThat(store.accept(result).acceptance()).isEqualTo(ACCEPTED);

        SqliteReviewAssignmentTurnStore restarted = new SqliteReviewAssignmentTurnStore(
                new JdbcTemplate(dataSource),
                new SqliteDispatchWakeStore(new JdbcTemplate(dataSource)), JSON);
        assertThat(restarted.accept(result).acceptance()).isEqualTo(ACCEPTED);
        assertThat(count("review_assignment_turn_request_receipt")).isOne();
        assertThat(count("review_assignment_turn_result_receipt")).isOne();
        assertThat(value("SELECT status FROM review_assignment WHERE id = 'assignment-review-1'"))
                .isEqualTo("completed");
        assertThat(value("SELECT status FROM review_round WHERE id = 'round-1'"))
                .isEqualTo("COMPLETED_WITH_QUESTIONS");
        assertThat(value("SELECT reviewed_head_commit FROM review_session WHERE id = 'review-1'"))
                .isEqualTo("head-1");
        assertThat(jdbc.queryForObject(
                "SELECT lane_mask FROM dispatch_ticket WHERE id = 'review-ticket-1'",
                Integer.class)).isEqualTo(10);
    }

    @Test
    void staleHeadOwnerSupersedesLateResultWithoutReopeningReview()
    {
        Admission admission = admission(
                "review-turn-stale", "review-operation-stale",
                "review-ticket-stale", 1);
        store.admitRound("round-1", "head-1", List.of(admission), NOW);
        jdbc.update("UPDATE review_session SET status = 'STALE' WHERE id = 'review-1'");

        ResultReceipt receipt = store.accept(
                result(admission, SUCCEEDED, PROVIDER_SUCCEEDED));

        assertThat(receipt.acceptance()).isEqualTo(SUPERSEDED);
        assertThat(value("""
                SELECT status FROM review_assignment_turn
                WHERE id = 'review-turn-stale'
                """)).isEqualTo("SUPERSEDED");
        assertThat(value("SELECT status FROM review_session WHERE id = 'review-1'"))
                .isEqualTo("STALE");
        assertThat(value("SELECT status FROM review_round WHERE id = 'round-1'"))
                .isEqualTo("ERRORED");
    }

    @Test
    void exactSemanticRetryCreatesASecondTurnAndCanCompleteTheRound()
    {
        Admission first = admission(
                "review-turn-failed", "review-operation-failed",
                "review-ticket-failed", 1);
        store.admitRound("round-1", "head-1", List.of(first), NOW);
        assertThat(store.tryStart(first.turnId(), first.operationId(), NOW.plusSeconds(1)))
                .isEqualTo(ReviewAssignmentTurnOperationHandler.StartDisposition.STARTED);
        assertThat(store.accept(result(first, FAILED, PROVIDER_FAILED)).acceptance())
                .isEqualTo(ACCEPTED);

        RetryCandidate candidate = store.retryCandidate("assignment-review-1")
                .orElseThrow();
        Admission retry = new Admission(
                "review-turn-retry", "review-operation-retry", "review-ticket-retry",
                candidate.assignmentId(), "investigate", 2, candidate.startCommit(),
                AgentTurnProviderSession.Transport.API,
                launch("review-turn-retry", "review-operation-retry"));
        store.retry(retry, NOW.plusSeconds(2));
        assertThat(store.tryStart(retry.turnId(), retry.operationId(), NOW.plusSeconds(3)))
                .isEqualTo(ReviewAssignmentTurnOperationHandler.StartDisposition.STARTED);
        assertThat(store.accept(result(retry, SUCCEEDED, PROVIDER_SUCCEEDED)).acceptance())
                .isEqualTo(ACCEPTED);

        assertThat(count("review_assignment_turn_result_receipt")).isEqualTo(2);
        assertThat(value("SELECT status FROM review_round WHERE id = 'round-1'"))
                .isEqualTo("COMPLETED_WITH_QUESTIONS");
        assertThat(value("SELECT status FROM agent_run WHERE id = 'review-run-1'"))
                .isEqualTo("succeeded");
    }

    @Test
    void genericDispatcherFailureTerminalizesTheExactTurn()
    {
        Admission admission = admission(
                "review-turn-infra", "review-operation-infra",
                "review-ticket-infra", 1);
        store.admitRound("round-1", "head-1", List.of(admission), NOW);
        DispatchTicket.OperationFence fence = new DispatchTicket.OperationFence(
                1L, null, null, admission.operationId(), 1,
                null, "head-1", null);
        DispatchTicket.DispatchResult raw = new DispatchTicket.DispatchResult(
                fence, FAILED, null, "{}", "provider capacity exhausted");
        ReviewAssignmentTurnResultDeliveryPort delivery =
                new ReviewAssignmentTurnResultDeliveryPort(
                        store, JSON, Clock.fixed(NOW.plusSeconds(4), ZoneOffset.UTC));

        DispatchTicket.DeliveryReceipt receipt = delivery.deliver(
                new DispatchTicket.OwnerReference(
                        DispatchTicket.OwnerKind.REVIEW_ASSIGNMENT_TURN,
                        admission.turnId(),
                        ReviewAssignmentTurnOperationHandler.CALLBACK_ROUTE),
                fence, raw);

        assertThat(receipt.acceptance()).isEqualTo(ACCEPTED);
        assertThat(value("SELECT status FROM review_assignment_turn WHERE id = 'review-turn-infra'"))
                .isEqualTo("FAILED");
        assertThat(value("SELECT status FROM review_round WHERE id = 'round-1'"))
                .isEqualTo("ERRORED");
    }

    private void seedReview(Connection connection)
            throws Exception
    {
        execute(connection, """
                INSERT INTO pr_commit(
                    id, pr_id, sha, message, additions, deletions, authored_at_ms)
                VALUES ('commit-1', 'pr-1', 'head-1', 'change', 1, 0, 10)
                """);
        execute(connection, """
                INSERT INTO review_session(
                    id, repo_id, pr_id, base_commit, reviewed_head_commit,
                    status, created_at_ms, updated_at_ms,
                    workspace_id, owner_thread_id, owner_task_id)
                VALUES ('review-1', 'acme/widget', 'pr-1', 'base-1', 'head-1',
                    'ACTIVE', 10, 10, 'workspace-1', 'trunk-1', 'task-1')
                """);
        execute(connection, """
                INSERT INTO agent_run(
                    id, kind, review_round_id, status, started_at_ms)
                VALUES ('review-run-1', 'panel_review', 'round-1', 'running', 10)
                """);
        execute(connection, """
                INSERT INTO review_round(
                    id, session_id, agent_run_id, trigger, scope, start_commit,
                    status, budget_json, cost_cents, message_gate_open,
                    lifecycle_finalized, created_at_ms)
                VALUES ('round-1', 'review-1', 'review-run-1', 'initial', 'full',
                    'head-1', 'RUNNING',
                    '{"cost_cap_cents":100,"wall_clock_minutes":10}',
                    0, 1, 0, 10)
                """);
        execute(connection, """
                INSERT INTO criterion(id, repo_id, kind, statement, source_type)
                VALUES ('criterion-1', 'acme/widget', 'hard-invariant',
                    'The change preserves state', 'failure-class')
                """);
        execute(connection, """
                INSERT INTO review_objective(
                    id, round_id, criterion_id, statement, source,
                    applicability_status, resolution_status)
                VALUES ('objective-1', 'round-1', 'criterion-1',
                    'The change preserves state', 'failure-class',
                    'applicable', 'pending')
                """);
        execute(connection, """
                INSERT INTO review_assignment(
                    id, round_id, reviewer_def_id, runner, status,
                    understanding_summary, assumptions_json, unknowns_json,
                    budget_json)
                VALUES ('assignment-review-1', 'round-1', 'general-api', 'api',
                    'queued', '', '[]', '[]',
                    '{"hypotheses":6,"active_hypotheses":3,"steps":12,"findings":5}')
                """);
    }

    private static Admission admission(
            String turnId, String operationId, String ticketId, int attempt)
    {
        return new Admission(
                turnId, operationId, ticketId, "assignment-review-1",
                "investigate", attempt, "head-1",
                AgentTurnProviderSession.Transport.API,
                launch(turnId, operationId));
    }

    private static String launch(String turnId, String operationId)
    {
        try {
            AgentTurnProviderSession.OwnerToolEndpoint endpoint =
                    new AgentTurnProviderSession.OwnerToolEndpoint(
                            "bytequay",
                            "http://127.0.0.1:53123/api/v2/review-assignment-turns/"
                                    + turnId + "/operations/" + operationId + "/mcp",
                            DispatchTicket.OwnerKind.REVIEW_ASSIGNMENT_TURN,
                            turnId, operationId,
                            AgentTurnProviderSession.ToolProfile.REVIEW_ASSIGNMENT_READ_ONLY,
                            "mcp__bytequay__approval_prompt");
            return JSON.writeValueAsString(new AgentTurnOperationHandler.LaunchInput(
                    1, AgentTurnProviderSession.Transport.API, "openai", "account-1",
                    "gpt-5", null, "/tmp/task-1", "review system",
                    "review this change", endpoint));
        }
        catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static ResultCommand result(
            Admission admission,
            DispatchTicket.Outcome outcome,
            AgentTurnOperationHandler.Disposition disposition)
    {
        return new ResultCommand(
                admission.turnId(), admission.operationId(), admission.attempt(),
                1L, admission.startCommit(), "a".repeat(64), outcome, disposition,
                "review done", 5, 7, 12, "provider-session-1",
                "{}", "{}", outcome == SUCCEEDED ? null : "provider failed",
                NOW.plusSeconds(4));
    }

    private int count(String table)
    {
        Integer value = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table, Integer.class);
        return value == null ? 0 : value;
    }

    private String value(String sql)
    {
        return jdbc.queryForObject(sql, String.class);
    }
}
