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

import com.bytequay.app.developmentflow.execution.CapacityManager;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.DispatchTicketControl;
import com.bytequay.app.developmentflow.execution.ExecutionContext;
import com.bytequay.app.developmentflow.execution.ExecutionDispatcher;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOperationHandler;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession;
import com.bytequay.app.developmentflow.persistence.SqliteCapacityLeaseStore;
import com.bytequay.app.developmentflow.persistence.SqliteDispatchTicketStore;
import com.bytequay.app.developmentflow.persistence.SqliteDispatchWakeStore;
import com.bytequay.app.developmentflow.persistence.SqliteExecutionEvidencePort;
import com.bytequay.app.developmentflow.persistence.SqliteReviewAssignmentTurnStore;
import com.bytequay.app.repository.sqlite.InvestigationReviewStore;
import com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.Admission;
import com.bytequay.app.testing.MigratedSqliteDatabase;
import com.bytequay.app.testing.SqliteTestPools;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.ACCEPTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(SqliteTestPools.class)
class TestReviewSessionPurge
{
    private static final Instant NOW = Instant.parse("2026-07-30T04:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    @TempDir
    private Path tempDir;

    @Test
    void terminalStandaloneFullReviewDoesNotBlockWorkspaceDelete()
    {
        Fixture fixture = fixture("terminal-full-review.db");
        assertThat(fixture.ticket("snapshot-ticket-1").state())
                .isEqualTo(DispatchTicket.State.CANCELED);
        assertThat(fixture.jdbc().queryForObject("""
                SELECT COUNT(*) FROM agent_execution
                WHERE ticket_id = 'snapshot-ticket-1' AND status = 'CANCELED'
                """, Integer.class)).isOne();
        assertThat(fixture.jdbc().queryForObject("""
                SELECT COUNT(*) FROM capacity_lease
                WHERE ticket_id = 'snapshot-ticket-1'
                  AND released_at_ms IS NOT NULL
                """, Integer.class)).isOne();
        assertThat(fixture.jdbc().queryForObject("""
                SELECT COUNT(*) FROM agent_execution_log log
                JOIN agent_execution execution ON execution.id = log.execution_id
                WHERE execution.ticket_id = 'snapshot-ticket-1'
                """, Integer.class)).isOne();
        assertThat(fixture.jdbc().queryForObject("""
                SELECT COUNT(*) FROM outbox
                WHERE aggregate_kind = 'DISPATCH_TICKET'
                  AND aggregate_id = 'snapshot-ticket-1'
                """, Integer.class)).isOne();

        DispatchTicket requested = fixture.ticket("review-ticket-1");
        assertThat(fixture.ticketControl().requestCancel(requested.id())).isTrue();
        DispatchTicket pending = fixture.ticket(requested.id());
        assertThat(fixture.ticketStore().compareAndSet(
                pending.id(), pending.version(), pending.completeDelivery(
                        new DispatchTicket.DeliveryReceipt(ACCEPTED, "purge test"),
                        NOW.plusSeconds(1)))).isTrue();
        assertThat(fixture.ticket(requested.id()).state())
                .isEqualTo(DispatchTicket.State.CANCELED);

        assertThatThrownBy(() -> fixture.jdbc().update(
                "DELETE FROM review_session WHERE id = 'review-1'"))
                .hasMessageContaining("purge authorization");

        fixture.purge().purgeWorkspace("workspace-1", fixture::deleteReview);
        fixture.deleteWorkspaceAndAssertClean();
    }

    @Test
    void runningStandaloneFullReviewIsCanceledBeforeWorkspaceDelete()
            throws Exception
    {
        Fixture fixture = fixture("running-full-review.db");
        try (RunningWorker worker = fixture.startRunning()) {
            assertThat(fixture.ticket("review-ticket-1").state())
                    .isEqualTo(DispatchTicket.State.RUNNING);
            assertThat(fixture.jdbc().queryForObject("""
                    SELECT COUNT(*) FROM agent_execution
                    WHERE ticket_id = 'review-ticket-1' AND status = 'RUNNING'
                    """, Integer.class)).isOne();

            worker.purge().purgeWorkspace("workspace-1", () -> {
                assertThat(fixture.jdbc().queryForObject("""
                        SELECT cancel_requested_at_ms IS NOT NULL
                        FROM dispatch_ticket WHERE id = 'review-ticket-1'
                        """, Boolean.class)).isTrue();
                assertThat(worker.cancellationSignaled().getCount()).isZero();
                assertThat(worker.lateResultReleased().getCount()).isOne();
                fixture.deleteReview();
            });

            fixture.deleteWorkspaceAndAssertClean();

            // Simulate a provider that returns despite the cancellation signal.
            // The purge has committed and the Workspace is already gone, so
            // the missing ticket must fence both the result and its evidence.
            worker.lateResultReleased().countDown();
            assertThat(worker.finishAttempted().await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(worker.releaseAttempted().await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(worker.deliveryCalls()).hasValue(0);
            fixture.assertClean();
        }
    }

    private Fixture fixture(String name)
    {
        String url = "jdbc:sqlite:" + tempDir.resolve(name)
                + "?foreign_keys=ON&busy_timeout=30000";
        MigratedSqliteDatabase.migrate(url);
        DataSource dataSource = SqliteTestPools.open(url);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        seedReview(jdbc);

        SqliteDispatchTicketStore ticketStore =
                new SqliteDispatchTicketStore(dataSource);
        DispatchTicketControl ticketControl = new DispatchTicketControl(
                ticketStore,
                new StaticListableBeanFactory()
                        .getBeanProvider(ExecutionDispatcher.class));
        SqliteReviewAssignmentTurnStore turns =
                new SqliteReviewAssignmentTurnStore(
                        jdbc, new SqliteDispatchWakeStore(jdbc), JSON);
        turns.admitRound("round-1", "head-1", List.of(admission()), NOW);
        InvestigationReviewStore reviews = new InvestigationReviewStore(jdbc, JSON);
        DataSourceTransactionManager transactionManager =
                new DataSourceTransactionManager(dataSource);
        seedTerminalSnapshot(
                jdbc, dataSource, ticketStore, ticketControl, transactionManager);
        ReviewSessionPurge purge = new ReviewSessionPurge(
                jdbc, ticketControl, transactionManager);
        return new Fixture(
                dataSource, jdbc, ticketStore, ticketControl, turns, reviews, purge);
    }

    private static void seedReview(JdbcTemplate jdbc)
    {
        jdbc.update("""
                INSERT INTO workspaces(
                    id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
                VALUES ('workspace-1', 'Review workspace', '', 0, 10, 10)
                """);
        jdbc.update("""
                INSERT INTO watched_repos(owner, repo, local_clone_path)
                VALUES ('acme', 'full', '/tmp/full')
                """);
        jdbc.update("""
                INSERT INTO workspace_repos(
                    workspace_id, repo_full_name, auto_fix_enabled, added_at_ms)
                VALUES ('workspace-1', 'acme/full', 0, 20)
                """);
        jdbc.update("""
                INSERT INTO pr(
                    id, branch_name, base_branch, title, description, status,
                    created_at_ms, pushed_at_ms, remote_pr_number, remote_pr_url,
                    origin, repo, author, synced_at_ms)
                VALUES ('pr-1', 'feature/review', 'main', 'Review', '',
                    'remote-open', 20, 20, 12, 'https://example.test/12',
                    'external', 'acme/full', 'octocat', 20)
                """);
        jdbc.update("""
                INSERT INTO pr_commit(
                    id, pr_id, sha, message, additions, deletions, authored_at_ms)
                VALUES ('base-commit', 'pr-1', 'base-1', 'base', 0, 0, 20),
                       ('head-commit', 'pr-1', 'head-1', 'head', 1, 0, 21)
                """);
        jdbc.update("""
                INSERT INTO review_session(
                    id, repo_id, pr_id, base_commit, reviewed_head_commit,
                    status, created_at_ms, updated_at_ms, workspace_id,
                    owner_thread_id, owner_task_id)
                VALUES ('review-1', 'acme/full', 'pr-1', 'base-1', 'head-1',
                    'ACTIVE', 22, 22, 'workspace-1', NULL, NULL)
                """);
        jdbc.update("""
                INSERT INTO agent_run(
                    id, kind, source, review_round_id, status,
                    started_at_ms, finished_at_ms, outcome)
                VALUES ('review-run-1', 'review_compatibility_header',
                    'v2_review_assignment_turn_fk', 'round-1', 'succeeded',
                    23, 23, 'completed')
                """);
        jdbc.update("""
                INSERT INTO review_round(
                    id, session_id, agent_run_id, trigger, scope, start_commit,
                    status, budget_json, cost_cents, message_gate_open,
                    lifecycle_finalized, created_at_ms)
                VALUES ('round-1', 'review-1', 'review-run-1', 'initial', 'full',
                    'head-1', 'RUNNING',
                    '{"cost_cap_cents":100,"wall_clock_minutes":10}',
                    0, 1, 0, 23)
                """);
        jdbc.update("""
                INSERT INTO review_round_snapshot_v291(
                    round_id, repository, remote_pr_number, base_branch,
                    pr_title, pr_description, base_commit, head_commit, diff, files_json,
                    file_contents_json, local_root, repository_root,
                    capabilities_json, created_at_ms)
                VALUES ('round-1', 'acme/full', 12, 'main', 'Review', '',
                    'base-1', 'head-1', 'diff', '[]', '{}', NULL, '/tmp/full', '{}', 23)
                """);
        jdbc.update("""
                INSERT INTO review_assignment(
                    id, round_id, reviewer_def_id, runner, status,
                    understanding_summary, assumptions_json, unknowns_json,
                    budget_json)
                VALUES ('assignment-1', 'round-1', 'general-api', 'api',
                    'queued', '', '[]', '[]',
                    '{"hypotheses":6,"active_hypotheses":3,"steps":12,"findings":5}')
                """);
    }

    private static void seedTerminalSnapshot(
            JdbcTemplate jdbc,
            DataSource dataSource,
            SqliteDispatchTicketStore ticketStore,
            DispatchTicketControl ticketControl,
            DataSourceTransactionManager transactionManager)
    {
        new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> {
            jdbc.update("""
                    INSERT INTO review_session_snapshot_operation_v293(
                        id, dispatch_ticket_id, review_id, command_id, pr_id,
                        repository, remote_pr_number, base_branch,
                        pr_title, pr_description, workspace_id,
                        repository_root, scope, request_json, expected_base_sha,
                        expected_head_sha, status, requested_at_ms)
                    VALUES ('snapshot-operation-1', 'snapshot-ticket-1',
                        'review-1', 'snapshot-command-1', 'pr-1',
                        'acme/full', 12, 'main', 'Review', '', 'workspace-1',
                        '/tmp/full', 'full', '{}', 'base-1', 'head-1',
                        'REQUESTED', 30)
                    """);
            jdbc.update("""
                    INSERT INTO dispatch_ticket(
                        id, operation_id, operation_kind, async_family,
                        owner_kind, owner_id, callback_route, lane_mask,
                        trunk_control, exclusive_task, writer_required,
                        workspace_id, attempt, expected_head_sha,
                        expected_base_sha, status, created_at_ms)
                    VALUES ('snapshot-ticket-1', 'snapshot-operation-1',
                        'CAPTURE_REVIEW_SESSION_SNAPSHOT', 'LOCAL_GIT',
                        'REVIEW_SESSION', 'review-1',
                        'REVIEW_SESSION_SNAPSHOT_RESULT', 48, 0, 0, 0,
                        'workspace-1', 1, 'head-1', 'base-1', 'REQUESTED', 30)
                    """);
        });

        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        CapacityManager capacity = new CapacityManager(
                new SqliteCapacityLeaseStore(dataSource),
                () -> CapacityManager.CapacityPolicy.initial(
                        10, 10, Map.of(
                                CapacityManager.CapacityLane.LOCAL_GIT, 2,
                                CapacityManager.CapacityLane.GITHUB, 2)),
                clock,
                Duration.ofSeconds(30));
        DispatchTicket requested = ticketStore.findById("snapshot-ticket-1")
                .orElseThrow();
        CapacityManager.CapacityLease lease = capacity.tryAcquireForTicket(
                requested.id(), requested.envelope().capacityRequest(),
                "snapshot-worker").lease().orElseThrow();
        DispatchTicket claimed = requested.claim(
                "snapshot-worker", lease.id(), NOW.plusSeconds(20));
        assertThat(ticketStore.compareAndSet(
                requested.id(), requested.version(), claimed)).isTrue();
        DispatchTicket running = claimed.markRunning(NOW.plusSeconds(1));
        assertThat(ticketStore.compareAndSet(
                claimed.id(), claimed.version(), running)).isTrue();

        SqliteExecutionEvidencePort evidence =
                new SqliteExecutionEvidencePort(dataSource, JSON);
        String executionId = evidence.start(
                running, lease, DispatchTicket.ClaimPurpose.EXECUTE,
                NOW.plusSeconds(1));
        evidence.appendLog(
                executionId, 0, "{\"event\":\"snapshot\"}",
                NOW.plusSeconds(2));
        assertThat(ticketControl.requestCancel(running.id())).isTrue();
        DispatchTicket cancelRequested = ticketStore.findById(running.id())
                .orElseThrow();
        DispatchTicket.DispatchResult canceled =
                DispatchTicket.DispatchResult.canceled(
                        cancelRequested.envelope().fence());
        DispatchTicket pending = cancelRequested.resultPending(
                canceled, NOW.plusSeconds(3));
        assertThat(ticketStore.compareAndSet(
                cancelRequested.id(), cancelRequested.version(), pending)).isTrue();
        evidence.finish(executionId, canceled, null, NOW.plusSeconds(3));
        assertThat(capacity.release(lease.id(), "snapshot-worker")).isTrue();
        DispatchTicket terminal = pending.completeDelivery(
                new DispatchTicket.DeliveryReceipt(ACCEPTED, "snapshot canceled"),
                NOW.plusSeconds(4));
        assertThat(ticketStore.compareAndSet(
                pending.id(), pending.version(), terminal)).isTrue();
        assertThat(jdbc.update("""
                UPDATE review_session_snapshot_operation_v293
                SET status = 'CANCELED', result_json = '{}',
                    error_message = 'snapshot canceled', completed_at_ms = ?
                WHERE id = 'snapshot-operation-1' AND status = 'REQUESTED'
                """, NOW.plusSeconds(4).toEpochMilli())).isOne();
    }

    private static Admission admission()
    {
        return new Admission(
                "review-turn-1", "review-operation-1", "review-ticket-1",
                "assignment-1", "investigate", "assignment-1", null, 1,
                "head-1", AgentTurnProviderSession.Transport.API, 1000,
                launchInput());
    }

    private static String launchInput()
    {
        try {
            AgentTurnProviderSession.OwnerToolEndpoint endpoint =
                    new AgentTurnProviderSession.OwnerToolEndpoint(
                            "bytequay",
                            "http://127.0.0.1:53123/api/v2/review-assignment-turns/"
                                    + "review-turn-1/operations/review-operation-1/mcp",
                            DispatchTicket.OwnerKind.REVIEW_ASSIGNMENT_TURN,
                            "review-turn-1", "review-operation-1",
                            AgentTurnProviderSession.ToolProfile.REVIEW_ASSIGNMENT_READ_ONLY,
                            "mcp__bytequay__approval_prompt");
            return JSON.writeValueAsString(new AgentTurnOperationHandler.LaunchInput(
                    1, AgentTurnProviderSession.Transport.API, "openai", "account-1",
                    "gpt-5", null, "/tmp/full", "review system",
                    "review this change", endpoint));
        }
        catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private record Fixture(
            DataSource dataSource,
            JdbcTemplate jdbc,
            SqliteDispatchTicketStore ticketStore,
            DispatchTicketControl ticketControl,
            SqliteReviewAssignmentTurnStore turns,
            InvestigationReviewStore reviews,
            ReviewSessionPurge purge)
    {
        DispatchTicket ticket(String id)
        {
            return ticketStore.findById(id).orElseThrow();
        }

        RunningWorker startRunning()
                throws Exception
        {
            Clock clock = Clock.fixed(NOW.plusSeconds(1), ZoneOffset.UTC);
            ReleasingLeaseStore leases = new ReleasingLeaseStore(dataSource);
            CapacityManager capacity = new CapacityManager(
                    leases,
                    () -> CapacityManager.CapacityPolicy.initial(
                            10, 10, Map.of(
                                    CapacityManager.CapacityLane.REVIEW, 4)),
                    clock,
                    Duration.ofSeconds(30));
            DelayedHandler handler = new DelayedHandler();
            FinishingEvidence evidence = new FinishingEvidence(
                    new SqliteExecutionEvidencePort(dataSource, JSON));
            AtomicInteger deliveryCalls = new AtomicInteger();
            ExecutionDispatcher dispatcher = new ExecutionDispatcher(
                    capacity,
                    ticketStore,
                    new SqliteDispatchWakeStore(jdbc),
                    operationKind -> handler,
                    (owner, fence, result) -> {
                        deliveryCalls.incrementAndGet();
                        return new DispatchTicket.DeliveryReceipt(
                                ACCEPTED, "unexpected late delivery");
                    },
                    evidence,
                    clock,
                    new ExecutionDispatcher.Config(
                            "review-purge-test",
                            Duration.ofSeconds(1),
                            Duration.ofSeconds(10),
                            Duration.ofSeconds(1),
                            3,
                            10));
            StaticListableBeanFactory beans = new StaticListableBeanFactory();
            beans.addBean("dispatcher", dispatcher);
            DispatchTicketControl liveControl = new DispatchTicketControl(
                    ticketStore, beans.getBeanProvider(ExecutionDispatcher.class));
            ReviewSessionPurge livePurge = new ReviewSessionPurge(
                    jdbc, liveControl, new DataSourceTransactionManager(dataSource));
            dispatcher.runMaintenance();
            if (!handler.started.await(2, TimeUnit.SECONDS)) {
                dispatcher.close();
                throw new IllegalStateException("review worker did not start");
            }
            return new RunningWorker(
                    livePurge,
                    dispatcher,
                    handler.cancellationSignaled,
                    handler.lateResultReleased,
                    evidence.finishAttempted,
                    leases.releaseAttempted,
                    deliveryCalls);
        }

        void deleteReview()
        {
            turns.cancelFlow("round-1", NOW.plusSeconds(2));
            reviews.deleteReview("review-1");
        }

        void deleteWorkspaceAndAssertClean()
        {
            assertThat(jdbc.update(
                    "DELETE FROM workspaces WHERE id = 'workspace-1'"))
                    .isPositive();
            assertClean();
        }

        void assertClean()
        {
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM dispatch_ticket
                    WHERE id IN ('review-ticket-1', 'snapshot-ticket-1')
                    """, Integer.class)).isZero();
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM outbox
                    WHERE aggregate_kind = 'DISPATCH_TICKET'
                      AND aggregate_id IN ('review-ticket-1', 'snapshot-ticket-1')
                    """, Integer.class)).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM capacity_lease "
                            + "WHERE ticket_id IN ('review-ticket-1', 'snapshot-ticket-1')",
                    Integer.class)).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM agent_execution "
                            + "WHERE ticket_id IN ('review-ticket-1', 'snapshot-ticket-1')",
                    Integer.class)).isZero();
            assertThat(jdbc.queryForObject("""
                    SELECT COUNT(*) FROM agent_execution_log log
                    JOIN agent_execution execution ON execution.id = log.execution_id
                    WHERE execution.ticket_id IN (
                        'review-ticket-1', 'snapshot-ticket-1')
                    """, Integer.class)).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM dispatch_delivery_claim "
                            + "WHERE ticket_id IN ("
                            + "'review-ticket-1', 'snapshot-ticket-1')",
                    Integer.class)).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM review_session_snapshot_operation_v293 "
                            + "WHERE id = 'snapshot-operation-1'",
                    Integer.class)).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM review_assignment_turn "
                            + "WHERE id = 'review-turn-1'",
                    Integer.class)).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM review_round WHERE id = 'round-1'",
                    Integer.class)).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM review_session WHERE id = 'review-1'",
                    Integer.class)).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM review_session_purge_authorization_v293",
                    Integer.class)).isZero();
            assertThat(jdbc.queryForObject(
                    "SELECT COUNT(*) FROM pragma_foreign_key_check", Integer.class))
                    .isZero();
            assertThat(jdbc.queryForObject(
                    "PRAGMA integrity_check", String.class)).isEqualTo("ok");
        }
    }

    private record RunningWorker(
            ReviewSessionPurge purge,
            ExecutionDispatcher dispatcher,
            CountDownLatch cancellationSignaled,
            CountDownLatch lateResultReleased,
            CountDownLatch finishAttempted,
            CountDownLatch releaseAttempted,
            AtomicInteger deliveryCalls)
            implements AutoCloseable
    {
        @Override
        public void close()
        {
            lateResultReleased.countDown();
            dispatcher.close();
        }
    }

    private static final class ReleasingLeaseStore
            extends SqliteCapacityLeaseStore
    {
        private final CountDownLatch releaseAttempted = new CountDownLatch(1);

        private ReleasingLeaseStore(DataSource dataSource)
        {
            super(dataSource);
        }

        @Override
        public boolean release(
                String leaseId,
                String leaseOwner,
                Instant releasedAt)
        {
            try {
                return super.release(leaseId, leaseOwner, releasedAt);
            }
            finally {
                releaseAttempted.countDown();
            }
        }
    }

    private static final class DelayedHandler
            implements ExecutionPorts.OperationHandler
    {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch cancellationSignaled = new CountDownLatch(1);
        private final CountDownLatch lateResultReleased = new CountDownLatch(1);

        @Override
        public DispatchTicket.DispatchResult execute(ExecutionContext context)
                throws Exception
        {
            context.onCancellation(cancellationSignaled::countDown);
            started.countDown();
            if (!lateResultReleased.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("late review result was not released");
            }
            return new DispatchTicket.DispatchResult(
                    context.envelope().fence(),
                    DispatchTicket.Outcome.SUCCEEDED,
                    "{}",
                    "{}",
                    null);
        }
    }

    private static final class FinishingEvidence
            implements ExecutionPorts.ExecutionEvidencePort
    {
        private final ExecutionPorts.ExecutionEvidencePort delegate;
        private final CountDownLatch finishAttempted = new CountDownLatch(1);

        private FinishingEvidence(ExecutionPorts.ExecutionEvidencePort delegate)
        {
            this.delegate = delegate;
        }

        @Override
        public String start(
                DispatchTicket ticket,
                CapacityManager.CapacityLease lease,
                DispatchTicket.ClaimPurpose purpose,
                Instant startedAt)
        {
            return delegate.start(ticket, lease, purpose, startedAt);
        }

        @Override
        public void heartbeat(String executionId, Instant at)
        {
            delegate.heartbeat(executionId, at);
        }

        @Override
        public void providerSession(
                String executionId,
                String provider,
                String providerSessionId)
        {
            delegate.providerSession(executionId, provider, providerSessionId);
        }

        @Override
        public void processStarted(
                String executionId,
                long processPid,
                String logReference)
        {
            delegate.processStarted(executionId, processPid, logReference);
        }

        @Override
        public void appendLog(
                String executionId,
                long sequence,
                String payloadJson,
                Instant createdAt)
        {
            delegate.appendLog(executionId, sequence, payloadJson, createdAt);
        }

        @Override
        public void recordUsage(
                String executionId,
                long inputTokens,
                long outputTokens,
                long costUsdMilli)
        {
            delegate.recordUsage(
                    executionId, inputTokens, outputTokens, costUsdMilli);
        }

        @Override
        public void finish(
                String executionId,
                DispatchTicket.DispatchResult result,
                String failure,
                Instant finishedAt)
        {
            try {
                delegate.finish(executionId, result, failure, finishedAt);
            }
            finally {
                finishAttempted.countDown();
            }
        }

        @Override
        public void infrastructureFailure(
                String ticketId,
                String failure,
                Instant recordedAt)
        {
            delegate.infrastructureFailure(ticketId, failure, recordedAt);
        }
    }
}
