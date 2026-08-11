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
package com.bytequay.app.flow.runtime;

import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ExpiredClaim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationKind;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestNewFlowDispatcher
{
    private static final Instant NOW = Instant.parse(
            "2026-08-11T00:00:00Z");
    private static final Duration TTL = Duration.ofSeconds(1);

    @TempDir
    private Path temporaryDirectory;

    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private FlowRuntime runtime;

    @BeforeEach
    void setUp()
    {
        dataSource = new DriverManagerDataSource(
                "jdbc:sqlite:" + temporaryDirectory.resolve("flow.db")
                        + "?foreign_keys=ON&busy_timeout=30000"
                        + "&transaction_mode=IMMEDIATE");
        new NewFlowDatabase(
                dataSource,
                Clock.fixed(NOW, ZoneOffset.UTC)).bootstrap();
        jdbc = new JdbcTemplate(dataSource);
        runtime = new FlowRuntime(
                dataSource, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void claimsHighestPriorityAcrossTheExactWiredKinds()
    {
        var task = runtime.startTask(
                "priority-request", "repo", "goal", "task/priority",
                temporaryDirectory.resolve("priority").toString());
        String provision = operation(task.taskId(), "PROVISION_TASK");
        jdbc.update("UPDATE flow_runtime_dispatch_ticket SET priority = 1 "
                + "WHERE operation_id = ?", provision);
        insertActiveSession(task.taskId());
        insertOperation(
                "unsupported-publish", null, "PUBLISH", 1_000);
        insertOperation(
                "high-reconcile", task.taskId(), "RECONCILE_TASK", 100);

        List<OperationKind> executed = new ArrayList<>();
        NewFlowDispatcher dispatcher = dispatcher(List.of(
                handler(OperationKind.PROVISION_TASK, executed),
                handler(OperationKind.RECONCILE_TASK, executed)));

        assertThat(dispatcher.dispatchOnce()).isTrue();
        assertThat(executed).containsExactly(OperationKind.RECONCILE_TASK);
        assertThat(jdbc.queryForObject(
                "SELECT state FROM flow_runtime_operation "
                        + "WHERE operation_id = 'unsupported-publish'",
                String.class)).isEqualTo("READY");
    }

    @Test
    void capacityIncludesClaimsAndUnprovenActivatedProcesses()
    {
        var firstTask = runtime.startTask(
                "capacity-1", "repo", "first", "task/one",
                temporaryDirectory.resolve("one").toString());
        runtime.startTask(
                "capacity-2", "repo", "second", "task/two",
                temporaryDirectory.resolve("two").toString());
        Set<OperationKind> kinds = Set.of(OperationKind.PROVISION_TASK);
        Claim first = runtime.claimNextForDispatch(
                kinds, "worker-one", TTL, 1).orElseThrow();
        FlowRuntime secondRuntime = new FlowRuntime(
                dataSource, Clock.fixed(NOW, ZoneOffset.UTC));
        assertThat(secondRuntime.claimNextForDispatch(
                kinds, "worker-two", TTL, 1)).isEmpty();

        jdbc.update("UPDATE flow_runtime_operation SET state = 'FAILED' "
                + "WHERE operation_id = ?", first.operationId());
        jdbc.update("UPDATE flow_runtime_dispatch_ticket "
                + "SET delivery_state = 'DONE' WHERE operation_id = ?",
                first.operationId());
        insertActivatedProcess(firstTask.taskId(), first);
        assertThat(secondRuntime.claimNextForDispatch(
                kinds, "worker-two", TTL, 1)).isEmpty();

        jdbc.update(
                """
                UPDATE flow_runtime_agent_process_attempt
                SET state = 'STOPPED', capability_revoked_at = ?,
                    stop_type = 'NORMAL_RETURN', stop_proof_ref = 'proof',
                    stopped_at = ?
                WHERE operation_id = ?
                """,
                NOW.toEpochMilli(), NOW.toEpochMilli(), first.operationId());
        assertThat(secondRuntime.claimNextForDispatch(
                kinds, "worker-two", TTL, 1)).isPresent();
    }

    @Test
    void recoversExpiredClaimsOnceWithoutSpinning()
    {
        var task = runtime.startTask(
                "recovery", "repo", "goal", "task/recovery",
                temporaryDirectory.resolve("recovery").toString());
        Claim expired = runtime.claimNextForDispatch(
                Set.of(OperationKind.PROVISION_TASK), "old", TTL, 1)
                .orElseThrow();
        assertThat(expired.taskId()).isEqualTo(task.taskId());
        FlowRuntime restarted = new FlowRuntime(
                dataSource,
                Clock.fixed(NOW.plusSeconds(2), ZoneOffset.UTC));
        AtomicBoolean recovered = new AtomicBoolean();
        NewFlowDispatcher.Handler handler = new NewFlowDispatcher.Handler()
        {
            @Override
            public OperationKind kind()
            {
                return OperationKind.PROVISION_TASK;
            }

            @Override
            public void execute(Claim claim) {}

            @Override
            public boolean recover(ExpiredClaim expired)
            {
                boolean changed = restarted.recoverExpiredClaim(
                        expired.operationId(), expired.generation());
                recovered.set(changed);
                return changed;
            }
        };
        NewFlowDispatcher dispatcher = new NewFlowDispatcher(
                restarted, config("recovery-worker"), List.of(handler));

        assertThat(dispatcher.dispatchOnce()).isTrue();
        assertThat(recovered).isTrue();
        assertThat(dispatcher.dispatchOnce()).isFalse();
        assertThat(operation(task.taskId(), "PROVISION_TASK")).isNotBlank();
    }

    @Test
    void shutdownInterruptsTheLiveHandlerAndClaimsNoMoreWork()
            throws Exception
    {
        runtime.startTask(
                "blocked-1", "repo", "first", "task/blocked-one",
                temporaryDirectory.resolve("blocked-one").toString());
        runtime.startTask(
                "blocked-2", "repo", "second", "task/blocked-two",
                temporaryDirectory.resolve("blocked-two").toString());
        CountDownLatch entered = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();
        NewFlowDispatcher.Handler handler = new NewFlowDispatcher.Handler()
        {
            @Override
            public OperationKind kind()
            {
                return OperationKind.PROVISION_TASK;
            }

            @Override
            public void execute(Claim claim)
                    throws Exception
            {
                entered.countDown();
                try {
                    new CountDownLatch(1).await();
                }
                catch (InterruptedException stopped) {
                    interrupted.set(true);
                    throw stopped;
                }
            }

            @Override
            public boolean recover(ExpiredClaim expired)
            {
                return false;
            }
        };
        NewFlowDispatcher dispatcher = new NewFlowDispatcher(
                runtime,
                new NewFlowDispatcher.Config(
                        "blocked-worker", TTL, Duration.ofMillis(50), 1),
                List.of(handler));
        dispatcher.start();
        dispatcher.wake();
        assertThat(entered.await(2, TimeUnit.SECONDS)).isTrue();

        dispatcher.close();

        assertThat(interrupted).isTrue();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flow_runtime_operation "
                        + "WHERE state = 'CLAIMED'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void twoRuntimeInstancesExecuteOneTicketOnce()
            throws Exception
    {
        runtime.startTask(
                "race-one", "repo", "goal", "task/race-one",
                temporaryDirectory.resolve("race-one").toString());
        FlowRuntime other = new FlowRuntime(
                dataSource, Clock.fixed(NOW, ZoneOffset.UTC));
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger calls = new AtomicInteger();
        NewFlowDispatcher first = racingDispatcher(
                runtime, "race-worker-one", start, calls);
        NewFlowDispatcher second = racingDispatcher(
                other, "race-worker-two", start, calls);
        Thread firstThread = Thread.ofPlatform().start(first::dispatchOnce);
        Thread secondThread = Thread.ofPlatform().start(second::dispatchOnce);
        start.countDown();
        firstThread.join();
        secondThread.join();

        assertThat(calls).hasValue(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flow_runtime_operation "
                        + "WHERE state = 'CLAIMED'",
                Integer.class)).isEqualTo(1);
    }

    @Test
    void concurrentCapacityLeavesOneTicketAvailableUntilRelease()
            throws Exception
    {
        runtime.startTask(
                "global-capacity-1", "repo", "first", "task/global-one",
                temporaryDirectory.resolve("global-one").toString());
        runtime.startTask(
                "global-capacity-2", "repo", "second", "task/global-two",
                temporaryDirectory.resolve("global-two").toString());
        FlowRuntime other = new FlowRuntime(
                dataSource, Clock.fixed(NOW, ZoneOffset.UTC));
        CountDownLatch start = new CountDownLatch(1);
        List<Claim> claims = new ArrayList<>();
        Runnable firstClaim = () -> concurrentClaim(runtime, start, claims);
        Runnable secondClaim = () -> concurrentClaim(other, start, claims);
        Thread first = Thread.ofPlatform().start(firstClaim);
        Thread second = Thread.ofPlatform().start(secondClaim);
        start.countDown();
        first.join();
        second.join();

        assertThat(claims).hasSize(1);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flow_runtime_dispatch_ticket "
                        + "WHERE delivery_state = 'AVAILABLE'",
                Integer.class)).isEqualTo(1);
        Claim active = claims.getFirst();
        jdbc.update("UPDATE flow_runtime_operation SET state = 'FAILED' "
                + "WHERE operation_id = ?", active.operationId());
        jdbc.update("UPDATE flow_runtime_dispatch_ticket "
                + "SET delivery_state = 'DONE' WHERE operation_id = ?",
                active.operationId());

        assertThat(other.claimNextForDispatch(
                Set.of(OperationKind.PROVISION_TASK),
                "after-release", TTL, 1)).isPresent();
    }

    @Test
    void pollingAndEarlyWakeBothFindDurableTickets()
            throws Exception
    {
        runtime.startTask(
                "poll-only", "repo", "poll", "task/poll-only",
                temporaryDirectory.resolve("poll-only").toString());
        CountDownLatch polled = new CountDownLatch(1);
        NewFlowDispatcher pollDispatcher = asynchronousDispatcher(
                "poll-worker", polled);
        pollDispatcher.start();
        assertThat(polled.await(2, TimeUnit.SECONDS)).isTrue();
        pollDispatcher.close();

        settleAllClaimed();
        runtime.startTask(
                "early-wake", "repo", "wake", "task/early-wake",
                temporaryDirectory.resolve("early-wake").toString());
        CountDownLatch woken = new CountDownLatch(1);
        NewFlowDispatcher wakeDispatcher = asynchronousDispatcher(
                "wake-worker", woken);
        wakeDispatcher.wake();
        wakeDispatcher.start();
        assertThat(woken.await(2, TimeUnit.SECONDS)).isTrue();
        wakeDispatcher.close();
    }

    @Test
    void failedAcceptanceRollsBackBeforeTheDispatcherCanObserveIt()
            throws Exception
    {
        jdbc.execute(
                """
                CREATE TRIGGER fail_new_flow_ticket
                BEFORE INSERT ON flow_runtime_dispatch_ticket
                BEGIN SELECT RAISE(ABORT, 'injected ticket failure'); END
                """);
        CountDownLatch called = new CountDownLatch(1);
        NewFlowDispatcher dispatcher = asynchronousDispatcher(
                "rollback-worker", called);
        dispatcher.start();

        assertThatThrownBy(() ->
                runtime.startTask(
                        "rollback-request", "repo", "goal",
                        "task/rollback",
                        temporaryDirectory.resolve("rollback").toString()))
                .isInstanceOf(RuntimeException.class);
        assertThat(called.await(150, TimeUnit.MILLISECONDS)).isFalse();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flow_runtime_task "
                        + "WHERE request_key = 'rollback-request'",
                Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM flow_runtime_dispatch_ticket",
                Integer.class)).isZero();

        jdbc.execute("DROP TRIGGER fail_new_flow_ticket");
        runtime.startTask(
                "rollback-request", "repo", "goal", "task/rollback",
                temporaryDirectory.resolve("rollback").toString());
        dispatcher.wake();
        assertThat(called.await(2, TimeUnit.SECONDS)).isTrue();
        dispatcher.close();
    }

    private NewFlowDispatcher dispatcher(
            List<NewFlowDispatcher.Handler> handlers)
    {
        return new NewFlowDispatcher(
                runtime, config("test-dispatcher"), handlers);
    }

    private NewFlowDispatcher asynchronousDispatcher(
            String worker, CountDownLatch called)
    {
        return new NewFlowDispatcher(
                runtime,
                new NewFlowDispatcher.Config(
                        worker, TTL, Duration.ofMillis(50), 1),
                List.of(new NewFlowDispatcher.Handler()
                {
                    @Override
                    public OperationKind kind()
                    {
                        return OperationKind.PROVISION_TASK;
                    }

                    @Override
                    public void execute(Claim claim)
                    {
                        called.countDown();
                    }

                    @Override
                    public boolean recover(ExpiredClaim expired)
                    {
                        return false;
                    }
                }));
    }

    private static NewFlowDispatcher racingDispatcher(
            FlowRuntime owner,
            String worker,
            CountDownLatch start,
            AtomicInteger calls)
    {
        return new NewFlowDispatcher(
                owner,
                new NewFlowDispatcher.Config(
                        worker, TTL, Duration.ofMillis(50), 1),
                List.of(new NewFlowDispatcher.Handler()
                {
                    @Override
                    public OperationKind kind()
                    {
                        return OperationKind.PROVISION_TASK;
                    }

                    @Override
                    public void execute(Claim claim)
                            throws Exception
                    {
                        start.await();
                        calls.incrementAndGet();
                    }

                    @Override
                    public boolean recover(ExpiredClaim expired)
                    {
                        return false;
                    }
                }));
    }

    private static void concurrentClaim(
            FlowRuntime owner,
            CountDownLatch start,
            List<Claim> claims)
    {
        try {
            start.await();
            owner.claimNextForDispatch(
                    Set.of(OperationKind.PROVISION_TASK),
                    "capacity-racer", TTL, 1).ifPresent(claim -> {
                        synchronized (claims) {
                            claims.add(claim);
                        }
                    });
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void settleAllClaimed()
    {
        jdbc.update("UPDATE flow_runtime_operation SET state = 'FAILED' "
                + "WHERE state = 'CLAIMED'");
        jdbc.update("UPDATE flow_runtime_dispatch_ticket "
                + "SET delivery_state = 'DONE' "
                + "WHERE delivery_state = 'CLAIMED'");
    }

    private static NewFlowDispatcher.Config config(String worker)
    {
        return new NewFlowDispatcher.Config(
                worker, TTL, Duration.ofMillis(50), 1);
    }

    private static NewFlowDispatcher.Handler handler(
            OperationKind kind, List<OperationKind> executed)
    {
        return new NewFlowDispatcher.Handler()
        {
            @Override
            public OperationKind kind()
            {
                return kind;
            }

            @Override
            public void execute(Claim claim)
            {
                executed.add(claim.kind());
            }

            @Override
            public boolean recover(ExpiredClaim expired)
            {
                return false;
            }
        };
    }

    private void insertActiveSession(String taskId)
    {
        jdbc.update(
                """
                INSERT INTO flow_runtime_agent_session (
                    session_id, task_id, role, state, created_at, updated_at
                ) VALUES ('priority-session', ?, 'TASK_AGENT', 'IDLE', ?, ?)
                """,
                taskId, NOW.toEpochMilli(), NOW.toEpochMilli());
        jdbc.update(
                """
                UPDATE flow_runtime_task
                SET status = 'ACTIVE', task_session_id = 'priority-session'
                WHERE task_id = ?
                """,
                taskId);
    }

    private void insertOperation(
            String operationId,
            String taskId,
            String kind,
            int priority)
    {
        jdbc.update(
                """
                INSERT INTO flow_runtime_operation (
                    operation_id, owner_kind, owner_id, task_id, kind,
                    subject_digest, input_ref, work_watermark,
                    state, attempt, created_at
                ) VALUES (?, 'TEST', ?, ?, ?, ?, ?, 0, 'READY', 0, ?)
                """,
                operationId,
                operationId,
                taskId,
                kind,
                "digest:" + operationId,
                "input:" + operationId,
                NOW.toEpochMilli());
        jdbc.update(
                """
                INSERT INTO flow_runtime_dispatch_ticket (
                    operation_id, not_before, claim_generation,
                    priority, delivery_state
                ) VALUES (?, ?, 0, ?, 'AVAILABLE')
                """,
                operationId, NOW.toEpochMilli(), priority);
    }

    private void insertActivatedProcess(String taskId, Claim claim)
    {
        jdbc.update(
                """
                INSERT INTO flow_runtime_agent_session (
                    session_id, task_id, role, state, created_at, updated_at
                ) VALUES ('capacity-session', ?, 'CI_LEARNER', 'RUNNING', ?, ?)
                """,
                taskId, NOW.toEpochMilli(), NOW.toEpochMilli());
        jdbc.update(
                """
                INSERT INTO flow_runtime_agent_run (
                    run_id, operation_id, session_id, role, head_sha,
                    prompt_manifest_ref, capability_set_ref, input_ref,
                    state, created_at, started_at
                ) VALUES ('capacity-run', ?, 'capacity-session', 'CI_LEARNER',
                          'head', 'prompt', 'capabilities', 'input',
                          'RUNNING', ?, ?)
                """,
                claim.operationId(), NOW.toEpochMilli(), NOW.toEpochMilli());
        jdbc.update(
                """
                INSERT INTO flow_runtime_agent_process_attempt (
                    process_attempt_id, run_id, operation_id,
                    claim_generation, claim_token_digest, execution_id,
                    capability_id, state, jvm_pid, jvm_started_at,
                    thread_id, thread_name, reserved_at, activated_at
                ) VALUES ('capacity-process', 'capacity-run', ?, ?, 'digest',
                          'capacity-execution', 'capacity-capability',
                          'ACTIVATED', 1, ?, 1, 'thread', ?, ?)
                """,
                claim.operationId(),
                claim.generation(),
                NOW.toEpochMilli(),
                NOW.toEpochMilli(),
                NOW.toEpochMilli());
    }

    private String operation(String taskId, String kind)
    {
        return jdbc.queryForObject(
                "SELECT operation_id FROM flow_runtime_operation "
                        + "WHERE task_id = ? AND kind = ?",
                String.class,
                taskId,
                kind);
    }
}
