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
package com.bytequay.app.developmentflow.persistence;

import com.bytequay.app.developmentflow.execution.CapacityManager;
import com.bytequay.app.developmentflow.execution.DispatchDeliveryClaim;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.testing.SqliteTestPools;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.LOCAL_GIT;
import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.VALIDATION;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Acceptance.ACCEPTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@ExtendWith(SqliteTestPools.class)
class TestSqliteDispatchTicketStore
{
    private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");

    @TempDir
    private Path tempDir;

    @Test
    void roundTripsEveryTicketShapeAndKeepsRawResultFenceSeparate()
    {
        SqliteExecutionTestSupport.Database database = database("ticket-roundtrip.db");
        SqliteExecutionTestSupport.seedTrunk(database, "workspace", "trunk");
        SqliteExecutionTestSupport.seedTask(database, "trunk", "task", 1);
        SqliteExecutionTestSupport.seedStage(database, "task", "stage");

        DispatchTicket requested = requestedStageTicket();
        SqliteExecutionTestSupport.insertTicket(database, requested);
        SqliteDispatchTicketStore tickets = new SqliteDispatchTicketStore(
                database.dataSource());
        assertThat(tickets.findById(requested.id())).contains(requested);

        SqliteCapacityLeaseStore leases = new SqliteCapacityLeaseStore(database.dataSource());
        CapacityManager capacity = new CapacityManager(
                leases,
                () -> CapacityManager.CapacityPolicy.initial(
                        10, 10, Map.of(LOCAL_GIT, 2)),
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(30));
        CapacityManager.CapacityLease lease = capacity.tryAcquireForTicket(
                requested.id(), requested.envelope().capacityRequest(), "worker")
                .lease().orElseThrow();

        DispatchTicket claimed = requested.claim("worker", lease.id(), NOW.plusSeconds(20));
        assertThat(tickets.compareAndSet(requested.id(), 0, claimed)).isTrue();
        assertThat(tickets.findById(requested.id())).contains(claimed);
        assertThat(tickets.findExpiredClaims(NOW.plusSeconds(19), 10)).isEmpty();
        assertThat(tickets.findExpiredClaims(NOW.plusSeconds(20), 10))
                .containsExactly(claimed);

        DispatchTicket running = claimed.markRunning(NOW.plusSeconds(1));
        assertThat(tickets.compareAndSet(claimed.id(), claimed.version(), running)).isTrue();
        DispatchTicket.OperationFence rawFence = new DispatchTicket.OperationFence(
                9L,
                "stale-stage",
                7L,
                "raw-operation",
                3,
                "raw-fingerprint",
                null,
                "raw-base");
        DispatchTicket.DispatchResult rawResult = new DispatchTicket.DispatchResult(
                rawFence,
                DispatchTicket.Outcome.SUCCEEDED,
                "{\"result\":true}",
                "{\"evidence\":true}",
                null);
        DispatchTicket pending = running.resultPending(rawResult, NOW.plusSeconds(2));
        assertThat(tickets.compareAndSet(running.id(), running.version(), pending)).isTrue();

        DispatchTicket reloadedPending = tickets.findById(pending.id()).orElseThrow();
        assertThat(reloadedPending).isEqualTo(pending);
        assertThat(reloadedPending.envelope().fence()).isEqualTo(requested.envelope().fence());
        assertThat(reloadedPending.pendingResult().fence()).isEqualTo(rawFence);
        assertThat(reloadedPending.pendingResult().fence())
                .isNotEqualTo(reloadedPending.envelope().fence());

        DispatchDeliveryClaim claim = tickets.claimDelivery(
                pending.id(), pending.version(), "delivery", NOW.plusSeconds(3),
                NOW.plusSeconds(23)).orElseThrow();
        DispatchTicket completed = pending.completeDelivery(
                new DispatchTicket.DeliveryReceipt(ACCEPTED, "{\"accepted\":true}"),
                NOW.plusSeconds(4));
        assertThat(tickets.replaceTicketAndReleaseDeliveryClaim(claim, completed)).isTrue();
        assertThat(tickets.findById(completed.id())).contains(completed);

        DispatchTicket trunk = SqliteExecutionTestSupport.requestedTrunkControlTicket(
                "ticket-trunk", "operation-trunk", "workspace", "trunk",
                NOW.plusSeconds(5));
        SqliteExecutionTestSupport.insertTicket(database, trunk);
        DispatchTicket nullable = tickets.findById(trunk.id()).orElseThrow();
        assertThat(nullable).isEqualTo(trunk);
        assertThat(nullable.envelope().fence().taskEpoch()).isNull();
        assertThat(nullable.envelope().fence().stageGeneration()).isNull();
        assertThat(nullable.envelope().capacityRequest().scope().taskEpoch()).isNull();
    }

    @Test
    void deliveryClaimsSurviveRestartAndExpireOnlyAtTheirExactHeartbeatFence()
    {
        SqliteExecutionTestSupport.Database database = database("delivery-restart.db");
        SqliteExecutionTestSupport.seedTrunk(database, "workspace", "trunk");
        SqliteExecutionTestSupport.seedTask(database, "trunk", "task", 1);
        DispatchTicket pending = SqliteExecutionTestSupport.requestedTaskTicket(
                "ticket", "operation", "workspace", "trunk", "task",
                NOW, VALIDATION, true, false).requestCancel(NOW.plusSeconds(1));
        SqliteExecutionTestSupport.insertTicket(database, pending);

        SqliteDispatchTicketStore first = new SqliteDispatchTicketStore(
                SqliteExecutionTestSupport.dataSource(database.url()));
        DispatchDeliveryClaim original = first.claimDelivery(
                pending.id(), pending.version(), "worker", NOW.plusSeconds(2),
                NOW.plusSeconds(12)).orElseThrow();
        assertThat(first.claimDelivery(
                pending.id(), pending.version(), "other", NOW.plusSeconds(2),
                NOW.plusSeconds(12))).isEmpty();

        SqliteDispatchTicketStore afterRestart = new SqliteDispatchTicketStore(
                SqliteExecutionTestSupport.dataSource(database.url()));
        DispatchDeliveryClaim renewed = afterRestart.heartbeatDeliveryClaim(
                original, NOW.plusSeconds(5), NOW.plusSeconds(20)).orElseThrow();
        assertThat(afterRestart.releaseExpiredDeliveryClaim(
                original, NOW.plusSeconds(12))).isFalse();
        assertThat(afterRestart.findExpiredDeliveryClaims(
                NOW.plusSeconds(19), 10)).isEmpty();
        assertThat(afterRestart.findExpiredDeliveryClaims(
                NOW.plusSeconds(20), 10)).containsExactly(renewed);
        assertThat(afterRestart.releaseExpiredDeliveryClaim(
                renewed, NOW.plusSeconds(20))).isTrue();

        assertThat(first.claimDelivery(
                pending.id(), pending.version(), "replacement", NOW.plusSeconds(21),
                NOW.plusSeconds(31))).isPresent();
    }

    @Test
    void onlyANoLaunchAgentCancellationMayOmitExecutionEvidence()
            throws Exception
    {
        SqliteExecutionTestSupport.Database database = database(
                "agent-cancel-evidence.db");
        SqliteExecutionTestSupport.seedTrunk(database, "workspace", "trunk");
        SqliteExecutionTestSupport.seedTask(database, "trunk", "task", 1);
        SqliteDispatchTicketStore tickets = new SqliteDispatchTicketStore(
                database.dataSource());

        DispatchTicket requestedNoLaunch =
                SqliteExecutionTestSupport.requestedAgentTaskTicket(
                        "no-launch", "operation-no-launch", "workspace", "trunk",
                        "task", NOW, VALIDATION, true, false);
        DispatchTicket noLaunch = requestedNoLaunch.requestCancel(NOW.plusSeconds(1));
        SqliteExecutionTestSupport.insertTicket(database, noLaunch);
        assertThat(tickets.claimDelivery(
                noLaunch.id(), noLaunch.version(), "delivery-no-launch",
                NOW.plusSeconds(2), NOW.plusSeconds(20))).isPresent();

        DispatchTicket requestedLaunched =
                SqliteExecutionTestSupport.requestedAgentTaskTicket(
                        "launched", "operation-launched", "workspace", "trunk",
                        "task", NOW.plusSeconds(3), VALIDATION, true, false);
        DispatchTicket.DispatchResult canceledResult =
                new DispatchTicket.DispatchResult(
                        requestedLaunched.envelope().fence(),
                        DispatchTicket.Outcome.CANCELED, null, "{}",
                        "provider canceled after launch");
        DispatchTicket pending = requestedLaunched.resultPending(
                canceledResult, NOW.plusSeconds(4));
        DispatchTicket launched = new DispatchTicket(
                pending.id(), pending.version(), pending.envelope(), pending.state(),
                null, null, null, null, pending.createdAt(), pending.nextAttemptAt(),
                1, NOW.plusSeconds(3), NOW.plusSeconds(4), pending.pendingResult(),
                null, null, pending.lastError());
        SqliteExecutionTestSupport.insertTicket(database, launched);

        assertThat(tickets.claimDelivery(
                launched.id(), launched.version(), "delivery-launched",
                NOW.plusSeconds(5), NOW.plusSeconds(20))).isEmpty();

        database.jdbc().update("""
                INSERT INTO agent_execution(
                    id, ticket_id, infrastructure_attempt, provider,
                    status, started_at_ms, finished_at_ms, raw_result)
                VALUES ('execution-launched', 'launched', 1, 'openai',
                    'CANCELED', ?, ?, ?)
                """, NOW.plusSeconds(3).toEpochMilli(),
                NOW.plusSeconds(4).toEpochMilli(),
                new ObjectMapper().writeValueAsString(canceledResult));
        assertThat(tickets.claimDelivery(
                launched.id(), launched.version(), "delivery-launched",
                NOW.plusSeconds(5), NOW.plusSeconds(20))).isPresent();
    }

    @Test
    void deliveryCompletionRollsBackClaimDeletionWhenTicketCasFails()
    {
        SqliteExecutionTestSupport.Database database = database("delivery-rollback.db");
        SqliteExecutionTestSupport.seedTrunk(database, "workspace", "trunk");
        SqliteExecutionTestSupport.seedTask(database, "trunk", "task", 1);
        DispatchTicket pending = SqliteExecutionTestSupport.requestedTaskTicket(
                "ticket", "operation", "workspace", "trunk", "task",
                NOW, VALIDATION, true, false).resultPending(
                        new DispatchTicket.DispatchResult(
                                new DispatchTicket.OperationFence(
                                        1L, null, null, "operation", 1,
                                        "fp", "head", "base"),
                                DispatchTicket.Outcome.SUCCEEDED,
                                "{}", "{}", null),
                        NOW.plusSeconds(1));
        SqliteExecutionTestSupport.insertTicket(database, pending);
        SqliteDispatchTicketStore tickets = new SqliteDispatchTicketStore(
                database.dataSource());
        DispatchDeliveryClaim claim = tickets.claimDelivery(
                pending.id(), pending.version(), "worker", NOW.plusSeconds(2),
                NOW.plusSeconds(20)).orElseThrow();

        DispatchTicket invalidTerminal = terminalWithState(
                pending, DispatchTicket.State.FAILED, NOW.plusSeconds(3));
        assertThatThrownBy(() -> tickets.replaceTicketAndReleaseDeliveryClaim(
                claim, invalidTerminal))
                .isInstanceOf(DataAccessException.class);
        assertThat(tickets.findById(pending.id())).contains(pending);
        assertThat(database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM dispatch_delivery_claim WHERE ticket_id = ?",
                Integer.class,
                pending.id())).isEqualTo(1);

        DispatchTicket valid = pending.completeDelivery(
                new DispatchTicket.DeliveryReceipt(ACCEPTED, "{}"),
                NOW.plusSeconds(4));
        assertThat(tickets.replaceTicketAndReleaseDeliveryClaim(claim, valid)).isTrue();
        assertThat(database.jdbc().queryForObject(
                "SELECT COUNT(*) FROM dispatch_delivery_claim WHERE ticket_id = ?",
                Integer.class,
                pending.id())).isZero();
        assertThat(tickets.findById(valid.id())).contains(valid);
    }

    @Test
    void sqlPagingReturnsScopeDiverseClassHeadsAndSupportsCursorWrap()
    {
        SqliteExecutionTestSupport.Database database = database("ticket-page.db");
        SqliteExecutionTestSupport.seedTrunk(database, "w1", "trunk-a");
        SqliteExecutionTestSupport.seedTrunk(database, "w1", "trunk-b");
        SqliteExecutionTestSupport.seedTrunk(database, "w2", "trunk-c");
        SqliteExecutionTestSupport.seedTask(database, "trunk-a", "task-a", 1);
        SqliteExecutionTestSupport.seedTask(database, "trunk-b", "task-b", 1);
        SqliteExecutionTestSupport.seedTask(database, "trunk-c", "task-c", 1);
        database.jdbc().update("""
                UPDATE dispatch_ticket
                SET version = version + 1, status = 'RETRY_WAIT',
                    next_attempt_at_ms = ?, last_error = 'deferred fixture'
                WHERE operation_kind = 'PROVISION_TASK'
                """, NOW.plusSeconds(1).toEpochMilli());

        DispatchTicket a1 = ticket(
                "a1", "op-a1", "w1", "trunk-a", "task-a", 1);
        DispatchTicket c1 = ticket(
                "c1", "op-c1", "w2", "trunk-c", "task-c", 2);
        DispatchTicket b1 = ticket(
                "b1", "op-b1", "w1", "trunk-b", "task-b", 3);
        DispatchTicket delivery = ticket(
                "delivery", "op-delivery", "w1", "trunk-a", "task-a", 4)
                .requestCancel(NOW);
        DispatchTicket control = SqliteExecutionTestSupport.requestedTrunkControlTicket(
                "control", "op-control", "w1", "trunk-a", NOW.plusMillis(5));
        DispatchTicket hiddenSecondOrdinary = ticket(
                "a2", "op-a2", "w1", "trunk-a", "task-a", 9);
        for (DispatchTicket ticket : List.of(
                hiddenSecondOrdinary, control, b1, delivery, c1, a1)) {
            SqliteExecutionTestSupport.insertTicket(database, ticket);
        }

        SqliteDispatchTicketStore tickets = new SqliteDispatchTicketStore(
                database.dataSource());
        ExecutionPorts.TicketScanPage first = tickets.findEligiblePage(NOW, null, 3);
        assertThat(first.tickets()).extracting(DispatchTicket::id)
                .containsExactly("a1", "c1", "b1");
        ExecutionPorts.TicketScanPage second = tickets.findEligiblePage(
                NOW, first.nextCursor(), 3);
        assertThat(second.tickets()).extracting(DispatchTicket::id)
                .containsExactly("delivery", "control");
        ExecutionPorts.TicketScanPage exhausted = tickets.findEligiblePage(
                NOW, second.nextCursor(), 3);
        assertThat(exhausted.tickets()).isEmpty();
        assertThat(exhausted.nextCursor()).isNull();

        ExecutionPorts.TicketScanPage wrapped = tickets.findEligiblePage(NOW, null, 3);
        assertThat(wrapped.tickets()).extracting(DispatchTicket::id)
                .containsExactly("a1", "c1", "b1")
                .doesNotContain(hiddenSecondOrdinary.id());
    }

    private DispatchTicket requestedStageTicket()
    {
        String operationId = "operation-stage";
        CapacityManager.CapacityRequest capacity = new CapacityManager.CapacityRequest(
                operationId,
                CapacityManager.WorkflowSource.V2,
                ImmutableSet.of(LOCAL_GIT),
                new CapacityManager.CapacityScope("workspace", "trunk", "task", 1L),
                false,
                true,
                true);
        return DispatchTicket.requested(
                "ticket-stage",
                new DispatchTicket.DispatchEnvelope(
                        "LOCAL_WRITE",
                        DispatchTicket.AsyncFamily.LOCAL_GIT,
                        new DispatchTicket.OwnerReference(
                                DispatchTicket.OwnerKind.STAGE, "stage", "stage-result"),
                        new DispatchTicket.OperationFence(
                                1L, "stage", 1L, operationId, 2,
                                "envelope-fingerprint", "envelope-head", "envelope-base"),
                        capacity),
                NOW);
    }

    private static DispatchTicket terminalWithState(
            DispatchTicket pending,
            DispatchTicket.State state,
            Instant completedAt)
    {
        return new DispatchTicket(
                pending.id(),
                pending.version() + 1,
                pending.envelope(),
                state,
                null,
                null,
                null,
                null,
                pending.createdAt(),
                pending.nextAttemptAt(),
                pending.infrastructureAttempts(),
                pending.startedAt(),
                pending.cancelRequestedAt(),
                null,
                new DispatchTicket.DeliveryReceipt(ACCEPTED, "{}"),
                completedAt,
                pending.lastError());
    }

    @Test
    void aParkedResultPendingTicketIsInvisibleToTheEligibleScan()
    {
        // The production scan gate is this SQL, not DispatchTicket.isEligibleAt
        // — the in-memory double filters on the Java predicate, so a park
        // verified only there would be a no-op in the real dispatcher.
        SqliteExecutionTestSupport.Database database = database("ticket-parked.db");
        SqliteExecutionTestSupport.seedTrunk(database, "w1", "trunk-a");
        SqliteExecutionTestSupport.seedTask(database, "trunk-a", "task-a", 1);
        DispatchTicket armed = ticket(
                "armed", "op-armed", "w1", "trunk-a", "task-a", 1)
                .requestCancel(NOW);
        DispatchTicket parked = ticket(
                "parked", "op-parked", "w1", "trunk-a", "task-a", 2)
                .requestCancel(NOW)
                .deliveryRetry("OWNER_RESULT_PROTOCOL:cannot decode", null);
        SqliteExecutionTestSupport.insertTicket(database, armed);
        SqliteExecutionTestSupport.insertTicket(database, parked);
        SqliteDispatchTicketStore tickets = new SqliteDispatchTicketStore(
                database.dataSource());

        assertThat(tickets.findEligiblePage(NOW.plusSeconds(3600), null, 20)
                .tickets())
                .extracting(DispatchTicket::id)
                .contains("armed")
                .doesNotContain("parked");
    }

    private static DispatchTicket ticket(
            String id,
            String operation,
            String workspace,
            String trunk,
            String task,
            int millis)
    {
        return SqliteExecutionTestSupport.requestedTaskTicket(
                id, operation, workspace, trunk, task, NOW.plusMillis(millis),
                VALIDATION, true, false);
    }

    private SqliteExecutionTestSupport.Database database(String name)
    {
        return SqliteExecutionTestSupport.database(tempDir.resolve(name));
    }
}
