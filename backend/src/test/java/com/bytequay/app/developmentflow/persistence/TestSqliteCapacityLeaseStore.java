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

import com.bytequay.app.beans.workspace.WorkspaceSettingsDto;
import com.bytequay.app.config.DevelopmentFlowExecutionConfig;
import com.bytequay.app.developmentflow.execution.CapacityManager;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.service.runs.AgentRunService;
import com.bytequay.app.service.runs.SessionControlService;
import com.bytequay.app.service.workspaces.WorkspaceConfigurationService;
import com.bytequay.app.service.workspaces.WorkspaceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.CLI;
import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.LOCAL_GIT;
import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.VALIDATION;
import static com.bytequay.app.developmentflow.execution.CapacityManager.Denial.WORKSPACE_LIMIT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class TestSqliteCapacityLeaseStore
{
    private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");

    @TempDir
    private Path tempDir;

    @Test
    void roundTripsNullableScopesAndMonotonicWriterFences()
    {
        SqliteExecutionTestSupport.Database database = database("capacity.db");
        SqliteExecutionTestSupport.seedTrunk(database, "workspace", "trunk");
        SqliteExecutionTestSupport.seedTask(database, "trunk", "task", 1);
        DispatchTicket firstTicket = SqliteExecutionTestSupport.requestedTaskTicket(
                "ticket-first", "operation-first", "workspace", "trunk", "task",
                NOW, LOCAL_GIT, true, true);
        SqliteExecutionTestSupport.insertTicket(database, firstTicket);

        SqliteCapacityLeaseStore store = new SqliteCapacityLeaseStore(database.dataSource());
        CapacityManager manager = manager(store, policy(10));
        CapacityManager.CapacityLease first = manager.tryAcquireForTicket(
                firstTicket.id(), firstTicket.envelope().capacityRequest(), "worker")
                .lease().orElseThrow();

        assertThat(store.findById(first.id())).contains(first);
        assertThat(store.findActiveByOperation("operation-first", NOW)).contains(first);
        assertThat(store.listActive(NOW)).containsExactly(first);
        assertThat(first.writerFencingToken()).isEqualTo(1L);
        assertThat(first.scope().taskEpoch()).isEqualTo(1L);

        CapacityManager.CapacityLease heartbeat = store.heartbeat(
                first.id(), "worker", NOW.plusSeconds(5), NOW.plusSeconds(35))
                .orElseThrow();
        assertThat(heartbeat.heartbeatAt()).isEqualTo(NOW.plusSeconds(5));
        assertThat(heartbeat.expiresAt()).isEqualTo(NOW.plusSeconds(35));
        assertThat(store.heartbeat(
                first.id(), "other", NOW.plusSeconds(6), NOW.plusSeconds(36))).isEmpty();
        assertThat(store.release(first.id(), "other", NOW.plusSeconds(7))).isFalse();
        assertThat(store.release(first.id(), "worker", NOW.plusSeconds(7))).isTrue();
        assertThat(store.release(first.id(), "other", NOW.plusSeconds(8))).isTrue();

        DispatchTicket secondTicket = SqliteExecutionTestSupport.requestedTaskTicket(
                "ticket-second", "operation-second", "workspace", "trunk", "task",
                NOW.plusMillis(1), LOCAL_GIT, true, true);
        SqliteExecutionTestSupport.insertTicket(database, secondTicket);
        CapacityManager.CapacityLease second = manager.tryAcquireForTicket(
                secondTicket.id(), secondTicket.envelope().capacityRequest(), "worker")
                .lease().orElseThrow();
        assertThat(second.writerFencingToken()).isEqualTo(2L);

        CapacityManager.CapacityRequest unscoped = new CapacityManager.CapacityRequest(
                "legacy-unscoped",
                CapacityManager.WorkflowSource.LEGACY,
                Set.of(CLI),
                new CapacityManager.CapacityScope(null, null, null, null),
                false,
                false,
                false);
        CapacityManager.CapacityLease legacy = manager.tryAcquire(unscoped, "legacy")
                .lease().orElseThrow();
        CapacityManager.CapacityLease reloaded = store.findById(legacy.id()).orElseThrow();
        assertThat(reloaded.scope().taskEpoch()).isNull();
        assertThat(reloaded.writerFencingToken()).isNull();

        manager.release(second.id(), "worker");
        assertThat(store.expire(NOW.plusSeconds(31)))
                .extracting(CapacityManager.CapacityLease::id)
                .containsExactly(legacy.id());
        assertThat(store.findById(legacy.id()).orElseThrow().releaseReason())
                .isEqualTo("EXPIRED");
    }

    @Test
    void beginImmediateSerializesAdmissionAcrossTwoStoreAndManagerInstances()
            throws Exception
    {
        SqliteExecutionTestSupport.Database database = database("capacity-race.db");
        SqliteExecutionTestSupport.seedTrunk(database, "workspace", "trunk");
        SqliteExecutionTestSupport.seedTask(database, "trunk", "task-a", 1);
        SqliteExecutionTestSupport.seedTask(database, "trunk", "task-b", 2);
        DispatchTicket firstTicket = SqliteExecutionTestSupport.requestedTaskTicket(
                "ticket-a", "operation-a", "workspace", "trunk", "task-a",
                NOW, VALIDATION, true, false);
        DispatchTicket secondTicket = SqliteExecutionTestSupport.requestedTaskTicket(
                "ticket-b", "operation-b", "workspace", "trunk", "task-b",
                NOW, VALIDATION, true, false);
        SqliteExecutionTestSupport.insertTicket(database, firstTicket);
        SqliteExecutionTestSupport.insertTicket(database, secondTicket);

        SqliteCapacityLeaseStore firstStore = new SqliteCapacityLeaseStore(
                SqliteExecutionTestSupport.dataSource(database.url()));
        SqliteCapacityLeaseStore secondStore = new SqliteCapacityLeaseStore(
                SqliteExecutionTestSupport.dataSource(database.url()));
        CapacityManager.CapacityPolicy singleValidation = CapacityManager.CapacityPolicy.initial(
                10, 10, Map.of(VALIDATION, 1));
        CapacityManager first = manager(firstStore, singleValidation);
        CapacityManager second = manager(secondStore, singleValidation);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<CapacityManager.Admission> a = callers.submit(() -> {
                ready.countDown();
                start.await();
                return first.tryAcquireForTicket(
                        firstTicket.id(), firstTicket.envelope().capacityRequest(), "worker-a");
            });
            Future<CapacityManager.Admission> b = callers.submit(() -> {
                ready.countDown();
                start.await();
                return second.tryAcquireForTicket(
                        secondTicket.id(), secondTicket.envelope().capacityRequest(), "worker-b");
            });
            assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(
                    a.get(5, TimeUnit.SECONDS),
                    b.get(5, TimeUnit.SECONDS)))
                    .filteredOn(CapacityManager.Admission::isAdmitted)
                    .hasSize(1);
            assertThat(firstStore.listActive(NOW)).hasSize(1);
        }
        finally {
            callers.shutdownNow();
        }
    }

    @Test
    void workspaceSettingSaveControlsSqliteAdmissionAndSignalsOnlyAfterCommit()
            throws Exception
    {
        SqliteExecutionTestSupport.Database database = database(
                "workspace-settings-capacity.db");
        SqliteExecutionTestSupport.seedTrunk(database, "workspace", "trunk-a");
        SqliteExecutionTestSupport.seedTrunk(database, "workspace", "trunk-b");
        SqliteExecutionTestSupport.seedTask(database, "trunk-a", "task-a", 1);
        SqliteExecutionTestSupport.seedTask(database, "trunk-b", "task-b", 1);
        DispatchTicket firstTicket = SqliteExecutionTestSupport.requestedTaskTicket(
                "ticket-a", "operation-a", "workspace", "trunk-a", "task-a",
                NOW, VALIDATION, true, false);
        DispatchTicket secondTicket = SqliteExecutionTestSupport.requestedTaskTicket(
                "ticket-b", "operation-b", "workspace", "trunk-b", "task-b",
                NOW, VALIDATION, true, false);
        SqliteExecutionTestSupport.insertTicket(database, firstTicket);
        SqliteExecutionTestSupport.insertTicket(database, secondTicket);

        ObjectMapper mapper = new ObjectMapper();
        SqliteCapacityLeaseStore store = new SqliteCapacityLeaseStore(
                database.dataSource());
        CapacityManager manager = manager(
                store, policySource(mapper));
        WorkspaceConfigurationService settings = workspaceSettings(
                database, mapper, manager);
        TransactionTemplate transactions = transactions(database);
        AtomicInteger wakes = new AtomicInteger();
        CountDownLatch committedWake = new CountDownLatch(1);
        manager.onCapacityAvailable(() -> {
            wakes.incrementAndGet();
            committedWake.countDown();
        });

        transactions.executeWithoutResult(ignored -> {
            settings.saveSettings("workspace", workspaceSettings(1));
            assertThat(wakes).hasValue(0);
        });
        assertThat(committedWake.await(2, TimeUnit.SECONDS)).isTrue();
        assertThat(wakes).hasValue(1);

        assertThat(manager.tryAcquireForTicket(
                firstTicket.id(), firstTicket.envelope().capacityRequest(),
                "worker-a").isAdmitted()).isTrue();
        assertThat(manager.tryAcquireForTicket(
                secondTicket.id(), secondTicket.envelope().capacityRequest(),
                "worker-b").denial()).isEqualTo(WORKSPACE_LIMIT);

        assertThatThrownBy(() -> transactions.executeWithoutResult(ignored -> {
            settings.saveSettings("workspace", workspaceSettings(2));
            assertThat(wakes).hasValue(1);
            throw new IllegalStateException("rollback capacity settings");
        }))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("rollback capacity settings");
        assertThat(wakes).hasValue(1);
        assertThat(settings.settings("workspace").maxRunningTasks()).isEqualTo(1);
        assertThat(manager.tryAcquireForTicket(
                secondTicket.id(), secondTicket.envelope().capacityRequest(),
                "worker-b").denial()).isEqualTo(WORKSPACE_LIMIT);
    }

    @Test
    void loweringWorkspaceLimitBeforeAdmissionTransactionRejectsStalePolicy()
            throws Exception
    {
        SqliteExecutionTestSupport.Database database = database(
                "workspace-policy-lowering-race.db");
        SqliteExecutionTestSupport.seedTrunk(database, "workspace", "trunk-a");
        SqliteExecutionTestSupport.seedTrunk(database, "workspace", "trunk-b");
        SqliteExecutionTestSupport.seedTask(database, "trunk-a", "task-a", 1);
        SqliteExecutionTestSupport.seedTask(database, "trunk-b", "task-b", 1);
        DispatchTicket firstTicket = SqliteExecutionTestSupport.requestedTaskTicket(
                "ticket-a", "operation-a", "workspace", "trunk-a", "task-a",
                NOW, VALIDATION, true, false);
        DispatchTicket secondTicket = SqliteExecutionTestSupport.requestedTaskTicket(
                "ticket-b", "operation-b", "workspace", "trunk-b", "task-b",
                NOW, VALIDATION, true, false);
        SqliteExecutionTestSupport.insertTicket(database, firstTicket);
        SqliteExecutionTestSupport.insertTicket(database, secondTicket);

        ObjectMapper mapper = new ObjectMapper();
        CapacityManager.CapacityPolicySource policies = policySource(mapper);
        SqliteCapacityLeaseStore firstStore = new SqliteCapacityLeaseStore(
                database.dataSource());
        CapacityManager first = manager(firstStore, policies);
        WorkspaceConfigurationService settings = workspaceSettings(
                database, mapper, first);
        TransactionTemplate transactions = transactions(database);
        transactions.executeWithoutResult(ignored ->
                settings.saveSettings("workspace", workspaceSettings(2)));
        assertThat(first.tryAcquireForTicket(
                firstTicket.id(), firstTicket.envelope().capacityRequest(),
                "worker-a").isAdmitted()).isTrue();

        CountDownLatch beforeAdmissionTransaction = new CountDownLatch(1);
        CountDownLatch continueAdmission = new CountDownLatch(1);
        SqliteCapacityLeaseStore gatedStore = new SqliteCapacityLeaseStore(
                SqliteExecutionTestSupport.dataSource(database.url()))
        {
            @Override
            public <T> T inAdmissionTransaction(
                    Function<CapacityManager.CapacityLeaseStore, T> work)
            {
                beforeAdmissionTransaction.countDown();
                try {
                    if (!continueAdmission.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException(
                                "timed out waiting to continue admission");
                    }
                }
                catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "admission was interrupted", interrupted);
                }
                return super.inAdmissionTransaction(work);
            }
        };
        CapacityManager second = manager(gatedStore, policies);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<CapacityManager.Admission> admission = caller.submit(() ->
                    second.tryAcquireForTicket(
                            secondTicket.id(),
                            secondTicket.envelope().capacityRequest(),
                            "worker-b"));
            assertThat(beforeAdmissionTransaction.await(2, TimeUnit.SECONDS)).isTrue();

            transactions.executeWithoutResult(ignored ->
                    settings.saveSettings("workspace", workspaceSettings(1)));
            continueAdmission.countDown();

            assertThat(admission.get(5, TimeUnit.SECONDS).denial())
                    .isEqualTo(WORKSPACE_LIMIT);
            assertThat(firstStore.listActive(NOW)).hasSize(1);
        }
        finally {
            continueAdmission.countDown();
            caller.shutdownNow();
        }
    }

    @Test
    void reportsRouteAndEpochConstraintFailuresInsteadOfCapacityDenial()
    {
        SqliteExecutionTestSupport.Database database = database("capacity-fence.db");
        SqliteExecutionTestSupport.seedTrunk(database, "workspace", "trunk");
        SqliteExecutionTestSupport.seedTask(database, "trunk", "task", 1);
        DispatchTicket ticket = SqliteExecutionTestSupport.requestedTaskTicket(
                "ticket", "operation", "workspace", "trunk", "task",
                NOW, VALIDATION, true, false);
        SqliteExecutionTestSupport.insertTicket(database, ticket);

        CapacityManager.CapacityRequest stale = new CapacityManager.CapacityRequest(
                "operation",
                CapacityManager.WorkflowSource.V2,
                Set.of(VALIDATION),
                new CapacityManager.CapacityScope(
                        "workspace", "trunk", "task", 2L),
                false,
                true,
                false);
        SqliteCapacityLeaseStore store = new SqliteCapacityLeaseStore(database.dataSource());

        assertThatThrownBy(() -> manager(store, policy(10))
                .tryAcquireForTicket(ticket.id(), stale, "worker"))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("Capacity lease insert failed");
        assertThat(store.listActive(NOW)).isEmpty();
    }

    private CapacityManager manager(
            SqliteCapacityLeaseStore store,
            CapacityManager.CapacityPolicy policy)
    {
        return new CapacityManager(
                store,
                () -> policy,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(30));
    }

    private CapacityManager manager(
            SqliteCapacityLeaseStore store,
            CapacityManager.CapacityPolicySource policies)
    {
        return new CapacityManager(
                store, policies, Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofSeconds(30));
    }

    private static CapacityManager.CapacityPolicySource policySource(
            ObjectMapper mapper)
    {
        return new DevelopmentFlowExecutionConfig().developmentFlowCapacityPolicy(
                mapper,
                4, 4, 4, 6, 4, 6, 8, 2, 4);
    }

    private static WorkspaceConfigurationService workspaceSettings(
            SqliteExecutionTestSupport.Database database,
            ObjectMapper mapper,
            CapacityManager manager)
    {
        return new WorkspaceConfigurationService(
                database.jdbc(), mapper, mock(WorkspaceService.class),
                mock(AgentRunService.class), mock(SessionControlService.class),
                manager);
    }

    private static TransactionTemplate transactions(
            SqliteExecutionTestSupport.Database database)
    {
        return new TransactionTemplate(
                new DataSourceTransactionManager(database.dataSource()));
    }

    private static WorkspaceSettingsDto workspaceSettings(int maxRunningTasks)
    {
        WorkspaceSettingsDto defaults = WorkspaceSettingsDto.defaults();
        return new WorkspaceSettingsDto(
                defaults.sessionCapUsd(), defaults.dailyCapUsd(),
                defaults.pauseAtCap(), defaults.syncSeconds(),
                defaults.brainBudgetChars(), defaults.distillMinutes(),
                List.copyOf(defaults.kbAudiences()), Map.copyOf(defaults.providers()),
                defaults.notifyCi(), defaults.notifyCompletions(),
                defaults.qualityScanEnabled(), defaults.remoteIssueIntakeEnabled(),
                maxRunningTasks);
    }

    private static CapacityManager.CapacityPolicy policy(int validationLimit)
    {
        return CapacityManager.CapacityPolicy.initial(
                10, 10, Map.of(
                        VALIDATION, validationLimit,
                        LOCAL_GIT, 10));
    }

    private SqliteExecutionTestSupport.Database database(String name)
    {
        return SqliteExecutionTestSupport.database(tempDir.resolve(name));
    }
}
