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
import com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.CliContinuation;
import com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.FlowPhase;
import com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.RetryCandidate;
import com.bytequay.app.testing.MigratedSqliteDatabase;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.concurrent.atomic.AtomicInteger;

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
import static com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.ROUND_GUIDANCE;
import static com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.guidanceSubject;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
        MigratedSqliteDatabase.migrate(url);
        try (Connection connection = connect(url)) {
            seedWorkspaceAndTrunk(connection);
            seedPublishedRemoteTask(connection, 1);
            seedReview(connection);
        }
        MigratedSqliteDatabase.migrate(url);
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
                .isEqualTo("RUNNING");
        assertThat(value("""
                SELECT phase FROM review_round_followup_v262
                WHERE round_id = 'round-1'
                """))
                .isEqualTo("PRIMARY");
        assertThat(value("SELECT reviewed_head_commit FROM review_session WHERE id = 'review-1'"))
                .isEqualTo("head-1");
        assertThat(jdbc.queryForObject(
                "SELECT lane_mask FROM dispatch_ticket WHERE id = 'review-ticket-1'",
                Integer.class)).isEqualTo(10);
    }

    @Test
    void cliContinuityRequiresTheExactReviewWorktree()
    {
        Admission admission = cliAdmission(
                "review-turn-cli", "review-operation-cli", "review-ticket-cli");
        store.admitRound("round-1", "head-1", List.of(admission), NOW);
        assertThat(store.tryStart(
                admission.turnId(), admission.operationId(), NOW.plusSeconds(1)))
                .isEqualTo(ReviewAssignmentTurnOperationHandler.StartDisposition.STARTED);
        assertThat(store.accept(result(
                admission, SUCCEEDED, PROVIDER_SUCCEEDED)).acceptance())
                .isEqualTo(ACCEPTED);
        jdbc.update("""
                UPDATE dispatch_ticket
                SET version = version + 1, status = 'SUCCEEDED',
                    completed_at_ms = 20, delivery_acceptance = 'ACCEPTED',
                    delivery_evidence = 'accepted'
                WHERE id = 'review-ticket-cli'
                """);
        jdbc.update("""
                INSERT INTO agent_execution(
                    id, ticket_id, infrastructure_attempt, provider,
                    provider_session_id, status, started_at_ms, finished_at_ms)
                VALUES ('review-execution-cli', 'review-ticket-cli', 1,
                    'claude-code', 'review-session-cli', 'SUCCEEDED', 19, 20)
                """);

        assertThat(store.successfulCliSession(
                "assignment-review-1", "investigate", "assignment-review-1",
                "claude-code", "claude-sonnet-4-5", "/tmp/task-1"))
                .get()
                .satisfies(continuation -> {
                    assertThat(continuation.providerSessionId())
                            .isEqualTo("review-session-cli");
                    assertThat(continuation.executionId())
                            .isEqualTo("review-execution-cli");
                });
        assertThat(store.successfulCliSession(
                "assignment-review-1", "investigate", "assignment-review-1",
                "claude-code", "claude-sonnet-4-5", "/tmp/other"))
                .isEmpty();
        jdbc.update("""
                DELETE FROM review_assignment_turn_result_receipt
                WHERE turn_id = 'review-turn-cli'
                """);
        assertThat(store.successfulCliSession(
                "review-turn-cli", "review-operation-cli", "claude-code",
                "claude-sonnet-4-5", "/tmp/task-1"))
                .get()
                .extracting(CliContinuation::providerSessionId)
                .isEqualTo("review-session-cli");
        assertThat(store.successfulCliSession(
                "review-turn-cli", "review-operation-cli", "claude-code",
                "claude-sonnet-4-5", "/tmp/other"))
                .isEmpty();

        jdbc.update("""
                INSERT INTO review_assignment_turn(
                    id, assignment_id, purpose, subject_key, verifier_run_id,
                    status, operation_id, attempt, start_commit, delivery_lane,
                    cost_cap_usd_milli, launch_input, requested_at_ms,
                    started_at_ms, finished_at_ms, error_message)
                VALUES ('review-turn-later', 'assignment-review-1',
                    'investigate', 'assignment-review-1', NULL, 'FAILED',
                    'review-operation-later', 2, 'head-1', 'CLI', 1, ?,
                    21, 21, 22, 'later attempt failed')
                """, admission.launchInput());
        assertThat(store.successfulCliSession(
                "review-turn-cli", "review-operation-cli", "claude-code",
                "claude-sonnet-4-5", "/tmp/task-1"))
                .isEmpty();
    }

    @Test
    void primaryReservationsAndFollowUpRemainWithinTheRoundCapAcrossRestart()
    {
        seedAssignment("assignment-review-2");
        seedAssignment("assignment-review-3");
        Admission first = primary(
                "review-turn-1", "review-operation-1", "review-ticket-1",
                "assignment-review-1", 334);
        Admission second = primary(
                "review-turn-2", "review-operation-2", "review-ticket-2",
                "assignment-review-2", 333);
        Admission third = primary(
                "review-turn-3", "review-operation-3", "review-ticket-3",
                "assignment-review-3", 333);

        store.admitRound(
                "round-1", "head-1", List.of(first, second, third), NOW);

        assertThat(jdbc.queryForObject("""
                SELECT SUM(cost_cap_usd_milli)
                FROM review_assignment_turn
                WHERE status IN ('REQUESTED', 'QUEUED', 'CLAIMED', 'RUNNING')
                """, Long.class)).isEqualTo(1000L);
        assertThat(store.remainingCostUsdMilli("round-1")).isZero();

        assertThat(store.accept(result(first, SUCCEEDED, PROVIDER_SUCCEEDED)).acceptance())
                .isEqualTo(ACCEPTED);
        assertThat(store.remainingCostUsdMilli("round-1")).isEqualTo(322);

        seedGuidance("message-budget", "assignment-guidance", "planner");
        Admission guidance = new Admission(
                "review-turn-guidance", "review-operation-guidance",
                "review-ticket-guidance", "assignment-guidance", ROUND_GUIDANCE,
                guidanceSubject("message-budget", "planner"), null, 1, "head-1",
                AgentTurnProviderSession.Transport.API, 500,
                launch("review-turn-guidance", "review-operation-guidance"));
        store.admitFollowUp("round-1", "head-1", guidance, NOW.plusSeconds(1));

        assertThat(jdbc.queryForObject("""
                SELECT cost_cap_usd_milli FROM review_assignment_turn
                WHERE id = 'review-turn-guidance'
                """, Long.class)).isEqualTo(322L);
        SqliteReviewAssignmentTurnStore restarted = new SqliteReviewAssignmentTurnStore(
                new JdbcTemplate(dataSource),
                new SqliteDispatchWakeStore(new JdbcTemplate(dataSource)), JSON);
        assertThat(restarted.remainingCostUsdMilli("round-1")).isZero();
        assertThat(restarted.protectedCostUsdMilli("round-1")).isEqualTo(1000);
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE review_round
                SET budget_json = '{"cost_cap_cents":75,"wall_clock_minutes":10}'
                WHERE id = 'round-1'
                """))
                .hasMessageContaining("below durable spend and reservations");
    }

    @Test
    void standaloneReviewUsesWorkspaceScopeAndNullTaskFence()
    {
        seedStandaloneReview();
        Admission admission = new Admission(
                "standalone-turn-1", "standalone-operation-1", "standalone-ticket-1",
                "assignment-review-1", "investigate", "assignment-review-1",
                null, 1, "head-1", AgentTurnProviderSession.Transport.API,
                1000,
                launch("standalone-turn-1", "standalone-operation-1"));

        store.admitRound("round-1", "head-1", List.of(admission), NOW);

        assertThat(value("""
                SELECT workspace_id FROM dispatch_ticket
                WHERE id = 'standalone-ticket-1'
                """)).isEqualTo("workspace-1");
        assertThat(jdbc.queryForObject("""
                SELECT trunk_id FROM dispatch_ticket
                WHERE id = 'standalone-ticket-1'
                """, String.class)).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT task_id FROM dispatch_ticket
                WHERE id = 'standalone-ticket-1'
                """, String.class)).isNull();
        assertThat(jdbc.queryForObject("""
                SELECT task_epoch FROM dispatch_ticket
                WHERE id = 'standalone-ticket-1'
                """, Long.class)).isNull();

        assertThat(store.tryStart(
                admission.turnId(), admission.operationId(), NOW.plusSeconds(1)))
                .isEqualTo(ReviewAssignmentTurnOperationHandler.StartDisposition.STARTED);
        assertThat(store.accept(result(
                admission, SUCCEEDED, PROVIDER_SUCCEEDED, null)).acceptance())
                .isEqualTo(ACCEPTED);
        assertThat(jdbc.queryForObject("""
                SELECT cost_cents FROM review_round
                WHERE id = 'round-1'
                """, Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
                SELECT cost_usd_milli FROM agent_run
                WHERE id = 'review-run-1'
                """, Long.class)).isZero();
    }

    @Test
    void resultDeliveryFailsClosedWhenAProviderReportsCostPastItsTurnCap()
    {
        Admission admission = primary(
                "review-turn-over-cap", "review-operation-over-cap",
                "review-ticket-over-cap", "assignment-review-1", 10);
        store.admitRound("round-1", "head-1", List.of(admission), NOW);

        ResultReceipt receipt = store.accept(
                result(admission, SUCCEEDED, PROVIDER_SUCCEEDED));

        assertThat(receipt.acceptance()).isEqualTo(ACCEPTED);
        assertThat(value("""
                SELECT status FROM review_assignment_turn
                WHERE id = 'review-turn-over-cap'
                """)).isEqualTo("FAILED");
        assertThat(value("SELECT status FROM review_round WHERE id = 'round-1'"))
                .isEqualTo("ERRORED");
        assertThat(jdbc.queryForObject("""
                SELECT cost_usd_milli
                FROM review_assignment_turn_result_receipt
                WHERE turn_id = 'review-turn-over-cap'
                """, Long.class)).isEqualTo(12L);
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
                candidate.assignmentId(), "investigate", candidate.assignmentId(), null,
                2, candidate.startCommit(),
                AgentTurnProviderSession.Transport.API,
                candidate.costCapUsdMilli(),
                launch("review-turn-retry", "review-operation-retry"));
        store.retry(retry, NOW.plusSeconds(2));
        assertThat(jdbc.queryForObject("""
                SELECT cost_cap_usd_milli FROM review_assignment_turn
                WHERE id = 'review-turn-retry'
                """, Long.class)).isEqualTo(988L);
        assertThat(store.tryStart(retry.turnId(), retry.operationId(), NOW.plusSeconds(3)))
                .isEqualTo(ReviewAssignmentTurnOperationHandler.StartDisposition.STARTED);
        assertThat(store.accept(result(retry, SUCCEEDED, PROVIDER_SUCCEEDED)).acceptance())
                .isEqualTo(ACCEPTED);

        assertThat(count("review_assignment_turn_result_receipt")).isEqualTo(2);
        assertThat(value("SELECT status FROM review_round WHERE id = 'round-1'"))
                .isEqualTo("RUNNING");
        assertThat(value("""
                SELECT phase FROM review_round_followup_v262
                WHERE round_id = 'round-1'
                """))
                .isEqualTo("PRIMARY");
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
        AtomicInteger continuations = new AtomicInteger();
        ReviewAssignmentTurnResultDeliveryPort delivery =
                new ReviewAssignmentTurnResultDeliveryPort(
                        store, JSON, Clock.fixed(NOW.plusSeconds(4), ZoneOffset.UTC),
                        () -> turnId -> continuations.incrementAndGet());

        DispatchTicket.DeliveryReceipt receipt = delivery.deliver(
                new DispatchTicket.OwnerReference(
                        DispatchTicket.OwnerKind.REVIEW_ASSIGNMENT_TURN,
                        admission.turnId(),
                        ReviewAssignmentTurnOperationHandler.CALLBACK_ROUTE),
                fence, raw);
        DispatchTicket.DeliveryReceipt duplicate = delivery.deliver(
                new DispatchTicket.OwnerReference(
                        DispatchTicket.OwnerKind.REVIEW_ASSIGNMENT_TURN,
                        admission.turnId(),
                        ReviewAssignmentTurnOperationHandler.CALLBACK_ROUTE),
                fence, raw);

        assertThat(receipt.acceptance()).isEqualTo(ACCEPTED);
        assertThat(duplicate.acceptance()).isEqualTo(ACCEPTED);
        assertThat(continuations).hasValue(2);
        assertThat(value("SELECT status FROM review_assignment_turn WHERE id = 'review-turn-infra'"))
                .isEqualTo("FAILED");
        assertThat(value("SELECT status FROM review_round WHERE id = 'round-1'"))
                .isEqualTo("ERRORED");
    }

    @Test
    void purposeSpecificFollowUpsAreIdempotentAndSurviveRestart()
    {
        Admission primary = primary(
                "review-turn-primary", "review-operation-primary",
                "review-ticket-primary", "assignment-review-1", 250);
        store.admitRound("round-1", "head-1", List.of(primary), NOW);
        assertThat(store.movePhase(
                "round-1", FlowPhase.PRIMARY, FlowPhase.SELF_REFUTATION,
                NOW.plusSeconds(1))).isTrue();

        Admission refutation = followUp(
                "review-turn-refutation", "review-operation-refutation",
                "review-ticket-refutation", "assignment-review-1",
                "self-refutation", "finding-1|finding-2", null);
        assertThat(store.admitFollowUp(
                "round-1", "head-1", refutation, NOW.plusSeconds(2)))
                .isEqualTo(refutation.turnId());
        Admission duplicateRefutation = followUp(
                "ignored-turn", "ignored-operation", "ignored-ticket",
                "assignment-review-1", "self-refutation",
                "finding-1|finding-2", null);
        assertThat(store.admitFollowUp(
                "round-1", "head-1", duplicateRefutation, NOW.plusSeconds(3)))
                .isEqualTo(refutation.turnId());
        assertThat(store.movePhase(
                "round-1", FlowPhase.SELF_REFUTATION, FlowPhase.VERIFYING,
                NOW.plusSeconds(4))).isTrue();

        seedVerifier();
        store.bindVerifier(
                "round-1", "assignment-verifier-1", "verifier-run-1",
                NOW.plusSeconds(5));
        Admission reconstruction = followUp(
                "review-turn-reconstruct", "review-operation-reconstruct",
                "review-ticket-reconstruct", "assignment-verifier-1",
                "blind-reconstruction", "finding-1", null);
        Admission verification = followUp(
                "review-turn-verify", "review-operation-verify",
                "review-ticket-verify", "assignment-verifier-1",
                "independent-verification", "finding-1", "verifier-run-1");
        store.admitFollowUp(
                "round-1", "head-1", reconstruction, NOW.plusSeconds(6));
        store.admitFollowUp(
                "round-1", "head-1", verification, NOW.plusSeconds(7));

        SqliteReviewAssignmentTurnStore restarted = new SqliteReviewAssignmentTurnStore(
                new JdbcTemplate(dataSource),
                new SqliteDispatchWakeStore(new JdbcTemplate(dataSource)), JSON);
        assertThat(restarted.incompleteRoundIds()).containsExactly("round-1");
        assertThat(restarted.turns("round-1"))
                .extracting(turn -> turn.purpose() + ":" + turn.subjectKey())
                .containsExactly(
                        "investigate:assignment-review-1",
                        "self-refutation:finding-1|finding-2",
                        "blind-reconstruction:finding-1",
                        "independent-verification:finding-1");
        assertThat(restarted.find(verification.turnId()).orElseThrow().verifierRunId())
                .isEqualTo("verifier-run-1");
        assertThat(restarted.flow("round-1").orElseThrow())
                .satisfies(flow -> {
                    assertThat(flow.phase()).isEqualTo(FlowPhase.VERIFYING);
                    assertThat(flow.verifierAssignmentId())
                            .isEqualTo("assignment-verifier-1");
                    assertThat(flow.verifierRunId()).isEqualTo("verifier-run-1");
                });
        assertThat(count("review_assignment_turn_request_receipt")).isEqualTo(4);
        assertThat(count("dispatch_ticket")).isGreaterThanOrEqualTo(4);
    }

    @Test
    void canceledFlowRejectsLateProviderResultWithoutBecomingBlocked()
    {
        Admission admission = admission(
                "review-turn-cancel", "review-operation-cancel",
                "review-ticket-cancel", 1);
        store.admitRound("round-1", "head-1", List.of(admission), NOW);
        jdbc.update("""
                UPDATE review_round
                SET status = 'CANCELLED', finished_at_ms = 20,
                    message_gate_open = 0, lifecycle_finalized = 1
                WHERE id = 'round-1'
                """);
        store.cancelFlow("round-1", NOW.plusSeconds(1));

        assertThat(store.accept(result(admission, SUCCEEDED, PROVIDER_SUCCEEDED))
                .acceptance()).isEqualTo(SUPERSEDED);
        assertThat(value("SELECT status FROM review_round WHERE id = 'round-1'"))
                .isEqualTo("CANCELLED");
        assertThat(store.flow("round-1").orElseThrow().phase())
                .isEqualTo(FlowPhase.CANCELED);
        assertThat(store.incompleteRoundIds()).isEmpty();
    }

    @Test
    void failedGuidanceCompletesItsMessageWithoutBlockingTheRound()
    {
        Admission primary = admission(
                "review-turn-primary", "review-operation-primary",
                "review-ticket-primary", 1);
        store.admitRound("round-1", "head-1", List.of(primary), NOW);
        assertThat(store.tryStart(primary.turnId(), primary.operationId(), NOW.plusSeconds(1)))
                .isEqualTo(ReviewAssignmentTurnOperationHandler.StartDisposition.STARTED);
        assertThat(store.accept(result(primary, SUCCEEDED, PROVIDER_SUCCEEDED)).acceptance())
                .isEqualTo(ACCEPTED);
        seedGuidance("message-1", "assignment-guidance-1", "planner");
        Admission guidance = followUp(
                "review-turn-guidance", "review-operation-guidance",
                "review-ticket-guidance", "assignment-guidance-1",
                ROUND_GUIDANCE, guidanceSubject("message-1", "planner"), null);
        store.admitFollowUp("round-1", "head-1", guidance, NOW.plusSeconds(2));
        assertThat(store.tryStart(
                guidance.turnId(), guidance.operationId(), NOW.plusSeconds(3)))
                .isEqualTo(ReviewAssignmentTurnOperationHandler.StartDisposition.STARTED);

        assertThat(store.accept(result(guidance, FAILED, PROVIDER_FAILED)).acceptance())
                .isEqualTo(ACCEPTED);
        assertThat(value("SELECT status FROM review_round WHERE id = 'round-1'"))
                .isEqualTo("RUNNING");
        assertThat(store.flow("round-1").orElseThrow().phase())
                .isEqualTo(FlowPhase.PRIMARY);
        assertThat(value("""
                SELECT status FROM review_round_message WHERE id = 'message-1'
                """)).isEqualTo("failed");
        assertThat(value("""
                SELECT status FROM review_assignment WHERE id = 'assignment-guidance-1'
                """)).isEqualTo("errored");
        assertThat(jdbc.queryForObject("""
                SELECT cost_cents FROM review_round WHERE id = 'round-1'
                """, Integer.class)).isEqualTo(3);
        assertThat(store.remainingCostUsdMilli("round-1")).isEqualTo(976);
    }

    @Test
    void staleGuidanceFailsItsMessageAndBlocksTheMovedHead()
    {
        Admission primary = admission(
                "review-turn-primary", "review-operation-primary",
                "review-ticket-primary", 1);
        store.admitRound("round-1", "head-1", List.of(primary), NOW);
        assertThat(store.accept(result(primary, SUCCEEDED, PROVIDER_SUCCEEDED)).acceptance())
                .isEqualTo(ACCEPTED);
        seedGuidance("message-stale", "assignment-guidance-stale", "planner");
        Admission guidance = followUp(
                "review-turn-guidance", "review-operation-guidance",
                "review-ticket-guidance", "assignment-guidance-stale",
                ROUND_GUIDANCE, guidanceSubject("message-stale", "planner"), null);
        store.admitFollowUp("round-1", "head-1", guidance, NOW.plusSeconds(2));
        jdbc.update("UPDATE review_session SET owner_task_id = NULL WHERE id = 'review-1'");
        jdbc.update("""
                INSERT INTO pr_commit(
                    id, pr_id, sha, message, additions, deletions, authored_at_ms)
                VALUES ('commit-stale-guidance', 'pr-1', 'head-2',
                    'new head', 1, 0, 20)
                """);

        assertThat(store.accept(result(
                guidance, SUCCEEDED, PROVIDER_SUCCEEDED, null)).acceptance())
                .isEqualTo(SUPERSEDED);
        assertThat(value("""
                SELECT status FROM review_round_message WHERE id = 'message-stale'
                """)).isEqualTo("failed");
        assertThat(value("SELECT status FROM review_round WHERE id = 'round-1'"))
                .isEqualTo("ERRORED");
        assertThat(store.flow("round-1").orElseThrow().phase())
                .isEqualTo(FlowPhase.BLOCKED);
    }

    @Test
    void movedHeadSupersedesLateResultAndFailsClosed()
    {
        Admission admission = admission(
                "review-turn-moved", "review-operation-moved",
                "review-ticket-moved", 1);
        store.admitRound("round-1", "head-1", List.of(admission), NOW);
        jdbc.update("UPDATE review_session SET owner_task_id = NULL WHERE id = 'review-1'");
        jdbc.update("""
                INSERT INTO pr_commit(
                    id, pr_id, sha, message, additions, deletions, authored_at_ms)
                VALUES ('commit-2', 'pr-1', 'head-2', 'new head', 1, 0, 20)
                """);

        assertThat(store.accept(result(
                admission, SUCCEEDED, PROVIDER_SUCCEEDED, null))
                .acceptance()).isEqualTo(SUPERSEDED);
        assertThat(value("SELECT status FROM review_round WHERE id = 'round-1'"))
                .isEqualTo("ERRORED");
        assertThat(store.flow("round-1").orElseThrow().phase())
                .isEqualTo(FlowPhase.BLOCKED);
        assertThat(value("""
                SELECT status FROM review_assignment_turn
                WHERE id = 'review-turn-moved'
                """)).isEqualTo("SUPERSEDED");
    }

    private static JdbcTemplate sqlite(String url)
    {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        return new JdbcTemplate(dataSource);
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
                    id, kind, source, review_round_id, status,
                    started_at_ms, finished_at_ms, outcome)
                VALUES (
                    'review-run-1', 'review_compatibility_header',
                    'v2_review_assignment_turn_fk', 'round-1',
                    'succeeded', 10, 10, 'completed')
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
                "investigate", "assignment-review-1", null, attempt, "head-1",
                AgentTurnProviderSession.Transport.API,
                1000,
                launch(turnId, operationId));
    }

    private static Admission cliAdmission(
            String turnId, String operationId, String ticketId)
    {
        return new Admission(
                turnId, operationId, ticketId, "assignment-review-1",
                "investigate", "assignment-review-1", null, 1, "head-1",
                AgentTurnProviderSession.Transport.CLI,
                1000,
                cliLaunch(turnId, operationId));
    }

    private static Admission primary(
            String turnId, String operationId, String ticketId,
            String assignmentId, long costCapUsdMilli)
    {
        return new Admission(
                turnId, operationId, ticketId, assignmentId,
                "investigate", assignmentId, null, 1, "head-1",
                AgentTurnProviderSession.Transport.API, costCapUsdMilli,
                launch(turnId, operationId));
    }

    private static Admission followUp(
            String turnId, String operationId, String ticketId,
            String assignmentId, String purpose, String subjectKey,
            String verifierRunId)
    {
        return new Admission(
                turnId, operationId, ticketId, assignmentId,
                purpose, subjectKey, verifierRunId, 1, "head-1",
                AgentTurnProviderSession.Transport.API,
                250,
                launch(turnId, operationId));
    }

    private void seedVerifier()
    {
        jdbc.update("""
                INSERT INTO agent_run(
                    id, kind, source, review_round_id, status,
                    started_at_ms, finished_at_ms, outcome)
                VALUES (
                    'verifier-run-1', 'review_compatibility_header',
                    'v2_review_assignment_turn_fk', 'round-1',
                    'succeeded', 20, 20, 'completed')
                """);
        jdbc.update("""
                INSERT INTO review_assignment(
                    id, round_id, reviewer_def_id, runner, status,
                    understanding_summary, assumptions_json, unknowns_json,
                    budget_json)
                VALUES ('assignment-verifier-1', 'round-1', 'general-api', 'api',
                    'verifying', 'Independent verification', '[]', '[]',
                    '{"hypotheses":0,"active_hypotheses":0,"steps":6,"findings":5}')
                """);
    }

    private void seedAssignment(String assignmentId)
    {
        jdbc.update("""
                INSERT INTO review_assignment(
                    id, round_id, reviewer_def_id, runner, status,
                    understanding_summary, assumptions_json, unknowns_json,
                    budget_json)
                VALUES (?, 'round-1', 'general-api', 'api', 'queued', '', '[]', '[]',
                    '{"hypotheses":6,"active_hypotheses":3,"steps":12,"findings":5}')
                """, assignmentId);
    }

    private void seedStandaloneReview()
    {
        jdbc.update("""
                UPDATE review_session
                SET owner_thread_id = NULL, owner_task_id = NULL
                WHERE id = 'review-1'
                """);
    }

    private void seedGuidance(
            String messageId, String assignmentId, String target)
    {
        jdbc.update("""
                INSERT INTO review_assignment(
                    id, round_id, reviewer_def_id, runner, status,
                    understanding_summary, assumptions_json, unknowns_json,
                    budget_json)
                VALUES (?, 'round-1', 'general-api', 'api', 'queued', '', '[]', '[]',
                    '{"hypotheses":6,"active_hypotheses":3,"steps":12,"findings":5}')
                """, assignmentId);
        jdbc.update("""
                INSERT INTO review_round_message(
                    id, round_id, assignment_id, target, sender, body,
                    status, created_at_ms)
                VALUES (?, 'round-1', ?, ?, 'you', 'please check this', 'processing', 20)
                """, messageId, assignmentId, target);
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

    private static String cliLaunch(String turnId, String operationId)
    {
        try {
            AgentTurnProviderSession.OwnerToolEndpoint endpoint =
                    new AgentTurnProviderSession.OwnerToolEndpoint(
                            "bytequay",
                            "http://127.0.0.1:53123/api/v2/"
                                    + "review-assignment-turns/" + turnId
                                    + "/operations/" + operationId + "/mcp",
                            DispatchTicket.OwnerKind.REVIEW_ASSIGNMENT_TURN,
                            turnId, operationId,
                            AgentTurnProviderSession.ToolProfile
                                    .REVIEW_ASSIGNMENT_READ_ONLY,
                            "mcp__bytequay__approval_prompt");
            return JSON.writeValueAsString(
                    new AgentTurnOperationHandler.LaunchInput(
                            1, AgentTurnProviderSession.Transport.CLI,
                            "claude-code", null, "claude-sonnet-4-5", "high",
                            "/tmp/task-1", "review system",
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
        return result(admission, outcome, disposition, 1L);
    }

    private static ResultCommand result(
            Admission admission,
            DispatchTicket.Outcome outcome,
            AgentTurnOperationHandler.Disposition disposition,
            Long taskEpoch)
    {
        return new ResultCommand(
                admission.turnId(), admission.operationId(), admission.attempt(),
                taskEpoch, admission.startCommit(), "a".repeat(64), outcome, disposition,
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
