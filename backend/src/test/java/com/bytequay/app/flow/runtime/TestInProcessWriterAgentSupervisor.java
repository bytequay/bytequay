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

import com.bytequay.app.flow.runtime.FlowRuntime.StaleCapabilityException;
import com.bytequay.app.flow.runtime.FlowRuntime.StaleClaimException;
import com.bytequay.app.flow.runtime.FlowRuntime.StaleWriterFenceException;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentProcessAttempt;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentResult;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRole;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRun;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.InProcessStopType;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ProcessAttemptState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.RunState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.SessionState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Task;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TaskStatus;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TerminalOutcome;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.WorktreeSnapshot;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.WriterFence;
import com.bytequay.app.flow.runtime.InProcessWriterAgentSupervisor.AgentCompletion;
import com.bytequay.app.flow.runtime.InProcessWriterAgentSupervisor.CancellationDisposition;
import com.bytequay.app.flow.runtime.InProcessWriterAgentSupervisor.ExecutionHandle;
import com.bytequay.app.flow.runtime.InProcessWriterAgentSupervisor.StoppedAwaitingRecoveryException;
import com.bytequay.app.flow.runtime.InProcessWriterAgentSupervisor.StoppedFinalizer;
import com.bytequay.app.flow.runtime.InProcessWriterAgentSupervisor.WriterToolCapability;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestInProcessWriterAgentSupervisor
{
    private static final String HEAD_SHA = "b".repeat(40);
    private static final Instant NOW = Instant.parse("2026-08-10T10:15:30Z");
    private static final Duration TTL = Duration.ofMinutes(5);
    private static final Duration WAIT = Duration.ofSeconds(5);

    @TempDir
    private Path temporaryDirectory;

    private MutableClock clock;
    private DataSource dataSource;
    private JdbcTemplate jdbc;
    private FlowRuntime runtime;
    private InProcessWriterAgentSupervisor supervisor;

    @BeforeEach
    void setUp()
    {
        dataSource = new DriverManagerDataSource(
                "jdbc:sqlite:" + temporaryDirectory.resolve("runtime.db")
                        + "?foreign_keys=ON");
        FlowRuntimeSchema.install(dataSource);
        clock = new MutableClock(NOW);
        jdbc = new JdbcTemplate(dataSource);
        runtime = new FlowRuntime(dataSource, clock);
        supervisor = new InProcessWriterAgentSupervisor(runtime);
    }

    @Test
    void activatesBeforeBodyAndStopsBeforeFinishing()
            throws Exception
    {
        ActiveWriter writer = startWriter("activation");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<String> observedState = new AtomicReference<>();

        ExecutionHandle handle = FlowRuntimeTestSupport.launchWriterFixture(supervisor, runtime,
                writer.run().runId(),
                writer.claim(),
                writer.fence(),
                capability -> {
                    observedState.set(jdbc.queryForObject(
                            """
                            SELECT state
                            FROM flow_runtime_agent_process_attempt
                            WHERE run_id = ?
                            """,
                            String.class,
                            writer.run().runId()));
                    capability.runTool(() -> {});
                    entered.countDown();
                    await(release);
                    return new AgentCompletion(
                            TerminalOutcome.COMPLETED,
                            "ordinary final prose",
                            null);
                });

        assertThat(entered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS))
                .isTrue();
        assertThat(observedState).hasValue("ACTIVATED");
        assertThat(runtime.runForOperation(writer.claim().operationId())
                .orElseThrow().state()).isEqualTo(RunState.RUNNING);
        release.countDown();

        var result = supervisor.awaitAndFinish(handle, WAIT);
        AgentProcessAttempt stopped = attempt(writer);
        assertThat(stopped.state()).isEqualTo(ProcessAttemptState.STOPPED);
        assertThat(stopped.stopType())
                .isEqualTo(InProcessStopType.NORMAL_RETURN);
        assertThat(stopped.capabilityRevokedAt()).isNotNull();
        assertThat(stopped.stoppedAt())
                .isAfterOrEqualTo(stopped.capabilityRevokedAt());
        assertThat(result.stopProofRef()).isEqualTo(stopped.stopProofRef());
        assertThat(result.finalContent()).isEqualTo("ordinary final prose");
        assertThat(runtime.session(
                writer.claim().taskId(), AgentRole.TASK_AGENT)
                .orElseThrow().state()).isEqualTo(SessionState.IDLE);
        assertThat(count("flow_runtime_writer_lease")).isZero();
    }

    @Test
    void stoppedFinalizerFailureRetriesWithoutRerunningBody()
    {
        ActiveWriter writer = startWriter("finalizer-retry");
        AtomicInteger bodyStarts = new AtomicInteger();
        AtomicInteger toolCalls = new AtomicInteger();
        AtomicInteger finalizerCalls = new AtomicInteger();
        AtomicReference<AgentCompletion> firstCompletion =
                new AtomicReference<>();
        AtomicReference<Claim> renewedClaim = new AtomicReference<>();
        AtomicReference<WriterFence> renewedFence = new AtomicReference<>();
        StoppedFinalizer finalizer = (runId, claim, fence, completion) -> {
            AgentProcessAttempt stopped = attempt(writer);
            assertThat(stopped.state()).isEqualTo(ProcessAttemptState.STOPPED);
            assertThat(stopped.capabilityRevokedAt()).isNotNull();
            assertThat(runId).isEqualTo(writer.run().runId());
            int call = finalizerCalls.incrementAndGet();
            if (call == 1) {
                assertThat(claim).isSameAs(writer.claim());
                assertThat(fence).isSameAs(writer.fence());
                firstCompletion.set(completion);
                throw new IllegalStateException("injected finalizer failure");
            }
            assertThat(claim).isSameAs(renewedClaim.get());
            assertThat(fence).isSameAs(renewedFence.get());
            assertThat(completion).isSameAs(firstCompletion.get());
            return FlowRuntimeTestSupport.finishWriterFixture(
                    runtime,
                    runId,
                    claim,
                    fence,
                    completion);
        };
        ExecutionHandle handle = FlowRuntimeTestSupport.launchWriterFixture(supervisor, runtime,
                writer.run().runId(),
                writer.claim(),
                writer.fence(),
                "TEST_DOMAIN_FINALIZER",
                finalizer,
                capability -> {
                    bodyStarts.incrementAndGet();
                    capability.runTool(toolCalls::incrementAndGet);
                    return new AgentCompletion(
                            TerminalOutcome.COMPLETED,
                            "opaque completion",
                            null);
                });

        assertThatThrownBy(() -> supervisor.awaitAndFinalize(
                handle,
                WAIT,
                "TEST_DOMAIN_FINALIZER"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("injected finalizer failure");

        assertThat(bodyStarts).hasValue(1);
        assertThat(toolCalls).hasValue(1);
        assertThat(supervisor.liveExecution(handle.executionId()))
                .contains(handle);
        assertThat(runtime.runForOperation(writer.claim().operationId())
                .orElseThrow().state()).isEqualTo(RunState.RUNNING);
        assertThat(count("flow_runtime_agent_result")).isZero();
        assertThat(count("flow_runtime_writer_lease")).isOne();
        assertThat(runtime.task(writer.claim().taskId()).orElseThrow()
                .selectedWriterOperationId())
                .isEqualTo(writer.claim().operationId());

        clock.advance(Duration.ofMinutes(1));
        renewedClaim.set(runtime.renewClaim(writer.claim(), TTL));
        renewedFence.set(runtime.renewWriterLease(
                renewedClaim.get(), writer.fence(), TTL));
        Claim wrongTask = new Claim(
                renewedClaim.get().operationId(),
                "forged-task",
                renewedClaim.get().kind(),
                renewedClaim.get().generation(),
                renewedClaim.get().claimToken(),
                renewedClaim.get().workerId(),
                renewedClaim.get().expiresAt());
        Claim wrongKind = new Claim(
                renewedClaim.get().operationId(),
                renewedClaim.get().taskId(),
                OperationKind.RUN_CI_FIXER,
                renewedClaim.get().generation(),
                renewedClaim.get().claimToken(),
                renewedClaim.get().workerId(),
                renewedClaim.get().expiresAt());
        assertThatThrownBy(() -> FlowRuntimeTestSupport.launchWriterFixture(supervisor, runtime,
                writer.run().runId(),
                wrongTask,
                renewedFence.get(),
                "TEST_DOMAIN_FINALIZER",
                finalizer,
                capability -> completed()))
                .isInstanceOf(FlowRuntime.StaleClaimException.class)
                .hasMessageContaining("metadata");
        assertThatThrownBy(() -> FlowRuntimeTestSupport.launchWriterFixture(supervisor, runtime,
                writer.run().runId(),
                wrongKind,
                renewedFence.get(),
                "TEST_DOMAIN_FINALIZER",
                finalizer,
                capability -> completed()))
                .isInstanceOf(FlowRuntime.StaleClaimException.class)
                .hasMessageContaining("metadata");
        ExecutionHandle retry = FlowRuntimeTestSupport.launchWriterFixture(supervisor, runtime,
                writer.run().runId(),
                renewedClaim.get(),
                renewedFence.get(),
                "TEST_DOMAIN_FINALIZER",
                (runId, claim, fence, completion) -> {
                    throw new AssertionError("replacement finalizer ran");
                },
                capability -> {
                    bodyStarts.incrementAndGet();
                    return completed();
                });
        assertThat(retry).isEqualTo(handle);
        AgentResult result = supervisor.awaitAndFinalize(
                retry,
                WAIT,
                "TEST_DOMAIN_FINALIZER");

        assertThat(bodyStarts).hasValue(1);
        assertThat(toolCalls).hasValue(1);
        assertThat(finalizerCalls).hasValue(2);
        assertThat(result.finalContent()).isEqualTo("opaque completion");
        assertThat(supervisor.liveExecution(handle.executionId())).isEmpty();
        assertThat(count("flow_runtime_agent_result")).isOne();
        assertThat(count("flow_runtime_writer_lease")).isZero();
    }

    @Test
    void exactShapedUnstoredResultCannotReleaseOwnership()
    {
        ActiveWriter writer = startWriter("finalizer-fake-receipt");
        AtomicBoolean returnFake = new AtomicBoolean(true);
        StoppedFinalizer finalizer = (runId, claim, fence, completion) -> {
            if (returnFake.getAndSet(false)) {
                return new AgentResult(
                        "not-stored",
                        runId,
                        completion.terminalOutcome(),
                        completion.finalContent(),
                        completion.errorRef(),
                        attempt(writer).stopProofRef(),
                        NOW);
            }
            return FlowRuntimeTestSupport.finishWriterFixture(
                    runtime,
                    runId,
                    claim,
                    fence,
                    completion);
        };
        ExecutionHandle handle = FlowRuntimeTestSupport.launchWriterFixture(supervisor, runtime,
                writer.run().runId(),
                writer.claim(),
                writer.fence(),
                "TEST_DURABLE_FINALIZER",
                finalizer,
                capability -> completed());

        assertThatThrownBy(() -> supervisor.awaitAndFinalize(
                handle,
                WAIT,
                "TEST_DURABLE_FINALIZER"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not store AgentResult");
        assertThat(supervisor.liveExecution(handle.executionId()))
                .contains(handle);
        assertThat(runtime.runForOperation(writer.claim().operationId())
                .orElseThrow().state()).isEqualTo(RunState.RUNNING);
        assertThat(count("flow_runtime_agent_result")).isZero();
        assertThat(count("flow_runtime_writer_lease")).isOne();
        assertThat(runtime.task(writer.claim().taskId()).orElseThrow()
                .selectedWriterOperationId())
                .isEqualTo(writer.claim().operationId());

        AgentResult result = supervisor.awaitAndFinalize(
                handle,
                WAIT,
                "TEST_DURABLE_FINALIZER");
        assertThat(result.finalContent()).isEqualTo("done");
        assertThat(supervisor.liveExecution(handle.executionId())).isEmpty();
        assertThat(count("flow_runtime_writer_lease")).isZero();
    }

    @Test
    void retryAfterFinalizerCommittedReusesDurableStopAndResult()
    {
        ActiveWriter writer = startWriter("finalizer-after-commit");
        AtomicInteger calls = new AtomicInteger();
        StoppedFinalizer finalizer = (runId, claim, fence, completion) -> {
            AgentResult result = FlowRuntimeTestSupport.finishWriterFixture(
                    runtime,
                    runId,
                    claim,
                    fence,
                    completion);
            if (calls.incrementAndGet() == 1) {
                throw new IllegalStateException("failure after commit");
            }
            return result;
        };
        ExecutionHandle handle = FlowRuntimeTestSupport.launchWriterFixture(supervisor, runtime,
                writer.run().runId(),
                writer.claim(),
                writer.fence(),
                "TEST_AFTER_COMMIT_FINALIZER",
                finalizer,
                capability -> completed());

        assertThatThrownBy(() -> supervisor.awaitAndFinalize(
                handle,
                WAIT,
                "TEST_AFTER_COMMIT_FINALIZER"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failure after commit");
        assertThat(supervisor.liveExecution(handle.executionId()))
                .contains(handle);
        assertThat(count("flow_runtime_agent_result")).isOne();
        assertThat(count("flow_runtime_writer_lease")).isZero();

        var cancellation = supervisor.cancel(handle, WAIT);
        assertThat(cancellation.disposition())
                .isEqualTo(CancellationDisposition.ALREADY_FINISHED);
        assertThat(cancellation.result().finalContent()).isEqualTo("done");
        assertThat(calls).hasValue(2);
        assertThat(supervisor.liveExecution(handle.executionId())).isEmpty();
    }

    @Test
    void domainFinalizerMayParkASettledSession()
    {
        ActiveWriter writer = startWriter("finalizer-parked-session");
        StoppedFinalizer finalizer = (runId, claim, fence, completion) -> {
            AgentResult result = FlowRuntimeTestSupport.finishWriterFixture(
                    runtime,
                    runId,
                    claim,
                    fence,
                    completion);
            assertThat(jdbc.update(
                    """
                    UPDATE flow_runtime_agent_session
                    SET state = 'PARKED_CHILD'
                    WHERE session_id = ? AND state = 'IDLE'
                      AND last_run_id = ?
                    """,
                    writer.run().sessionId(),
                    runId)).isOne();
            return result;
        };
        ExecutionHandle handle = FlowRuntimeTestSupport.launchWriterFixture(supervisor, runtime,
                writer.run().runId(),
                writer.claim(),
                writer.fence(),
                "TEST_PARKED_FINALIZER",
                finalizer,
                capability -> completed());

        assertThat(supervisor.awaitAndFinalize(
                handle,
                WAIT,
                "TEST_PARKED_FINALIZER").finalContent()).isEqualTo("done");
        assertThat(runtime.session(
                writer.claim().taskId(), AgentRole.TASK_AGENT)
                .orElseThrow().state()).isEqualTo(SessionState.PARKED_CHILD);
        assertThat(supervisor.liveExecution(handle.executionId())).isEmpty();
    }

    @Test
    void stoppedFinalizerCannotChangeRouteOrCompletion()
    {
        ActiveWriter writer = startWriter("finalizer-identity");
        AtomicBoolean substituteCompletion = new AtomicBoolean(true);
        StoppedFinalizer finalizer = (runId, claim, fence, completion) -> {
            if (substituteCompletion.getAndSet(false)) {
                return new AgentResult(
                        "not-stored",
                        runId,
                        TerminalOutcome.FAILED,
                        "different",
                        "DIFFERENT",
                        attempt(writer).stopProofRef(),
                        NOW);
            }
            return FlowRuntimeTestSupport.finishWriterFixture(
                    runtime,
                    runId,
                    claim,
                    fence,
                    completion);
        };
        ExecutionHandle handle = FlowRuntimeTestSupport.launchWriterFixture(supervisor, runtime,
                writer.run().runId(),
                writer.claim(),
                writer.fence(),
                "TEST_DOMAIN_FINALIZER",
                finalizer,
                capability -> completed());

        assertThatThrownBy(() -> supervisor.awaitAndFinalize(
                handle,
                WAIT,
                "TEST_DOMAIN_FINALIZER"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("immutable completion identity");
        assertThatThrownBy(() -> supervisor.awaitAndFinish(handle, WAIT))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("another finalizer");
        assertThat(supervisor.liveExecution(handle.executionId()))
                .contains(handle);
        assertThat(count("flow_runtime_agent_result")).isZero();
        assertThat(count("flow_runtime_writer_lease")).isOne();

        var cancellation = supervisor.cancel(handle, WAIT);
        assertThat(cancellation.disposition())
                .isEqualTo(CancellationDisposition.ALREADY_FINISHED);
        assertThat(cancellation.result().terminalOutcome())
                .isEqualTo(TerminalOutcome.COMPLETED);
        assertThat(supervisor.liveExecution(handle.executionId())).isEmpty();
        assertThat(count("flow_runtime_agent_result")).isOne();
        assertThat(count("flow_runtime_writer_lease")).isZero();
    }

    @Test
    void cancellationBeforeAwaitUsesTheLaunchBoundFinalizer()
            throws Exception
    {
        ActiveWriter writer = startWriter("finalizer-cancel-first");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch waitForever = new CountDownLatch(1);
        AtomicInteger finalizerCalls = new AtomicInteger();
        AtomicReference<AgentCompletion> seenCompletion =
                new AtomicReference<>();
        StoppedFinalizer finalizer = (runId, claim, fence, completion) -> {
            finalizerCalls.incrementAndGet();
            seenCompletion.set(completion);
            return FlowRuntimeTestSupport.finishWriterFixture(
                    runtime,
                    runId,
                    claim,
                    fence,
                    completion);
        };
        ExecutionHandle handle = FlowRuntimeTestSupport.launchWriterFixture(supervisor, runtime,
                writer.run().runId(),
                writer.claim(),
                writer.fence(),
                "TEST_CANCEL_FINALIZER",
                finalizer,
                capability -> {
                    entered.countDown();
                    await(waitForever);
                    return completed();
                });
        assertThat(entered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS))
                .isTrue();

        var cancellation = supervisor.cancel(handle, WAIT);

        assertThat(cancellation.disposition())
                .isEqualTo(CancellationDisposition.CANCELED);
        assertThat(finalizerCalls).hasValue(1);
        assertThat(seenCompletion.get().terminalOutcome())
                .isEqualTo(TerminalOutcome.CANCELED);
        assertThat(cancellation.result().terminalOutcome())
                .isEqualTo(TerminalOutcome.CANCELED);
        assertThat(supervisor.liveExecution(handle.executionId())).isEmpty();
    }

    @Test
    void revokedCapabilityRejectsToolBeforeRunFinishes()
            throws Exception
    {
        ActiveWriter writer = startWriter("revoked-tool");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch invokeAgain = new CountDownLatch(1);
        AtomicReference<WriterToolCapability> tool = new AtomicReference<>();
        AtomicReference<Throwable> denied = new AtomicReference<>();
        ExecutionHandle handle = FlowRuntimeTestSupport.launchWriterFixture(supervisor, runtime,
                writer.run().runId(),
                writer.claim(),
                writer.fence(),
                capability -> {
                    tool.set(capability);
                    capability.runTool(() -> {});
                    entered.countDown();
                    await(invokeAgain);
                    try {
                        capability.runTool(() -> {});
                    }
                    catch (Throwable failure) {
                        denied.set(failure);
                    }
                    return completed();
                });
        assertThat(entered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS))
                .isTrue();
        assertThat(tool.get().toString())
                .doesNotContain(writer.claim().claimToken())
                .doesNotContain(writer.fence().claimTokenDigest());

        runtime.revokeInProcessWriterCapability(
                handle.processAttemptId(), writer.claim(), writer.fence());
        invokeAgain.countDown();
        supervisor.awaitAndFinish(handle, WAIT);
        assertThat(denied.get()).isInstanceOf(StaleCapabilityException.class);
    }

    @Test
    void staleClaimRejectsToolCapability()
            throws Exception
    {
        ActiveWriter claimWriter = startWriter("stale-claim");
        RunningBody staleClaim = launchWaiting(claimWriter);
        clock.advance(TTL.plusSeconds(1));
        staleClaim.invoke().countDown();
        assertThat(staleClaim.ended().await(
                WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        assertThat(staleClaim.toolFailure().get())
                .isInstanceOf(StaleClaimException.class);
    }

    @Test
    void staleFenceRejectsToolCapability()
            throws Exception
    {
        ActiveWriter fenceWriter = startWriter("stale-fence");
        RunningBody staleFence = launchWaiting(fenceWriter);
        jdbc.update(
                """
                UPDATE flow_runtime_writer_lease
                SET fencing_token = fencing_token + 1
                WHERE task_id = ?
                """,
                fenceWriter.claim().taskId());
        staleFence.invoke().countDown();
        assertThat(staleFence.ended().await(
                WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        assertThat(staleFence.toolFailure().get())
                .isInstanceOf(StaleWriterFenceException.class);
    }

    @Test
    void finishRejectsActivatedExecutionWithoutStopProof()
    {
        ActiveWriter writer = startWriter("active-finish");
        RunningBody running = launchWaiting(writer);

        assertThatThrownBy(() -> FlowRuntimeTestSupport.finishWriterFixture(
                runtime,
                writer.run().runId(),
                writer.claim(),
                writer.fence(),
                new AgentCompletion(
                        TerminalOutcome.COMPLETED,
                        "cannot finish yet",
                        null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stopped in-process proof");
        assertThatThrownBy(() -> supervisor.awaitAndFinish(
                running.handle(), Duration.ofMillis(20)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("did not finish");
        assertThat(attempt(writer).state())
                .isEqualTo(ProcessAttemptState.ACTIVATED);
        assertThat(attempt(writer).stopProofRef()).isNull();

        assertThat(supervisor.cancel(running.handle(), WAIT).disposition())
                .isEqualTo(CancellationDisposition.CANCELED);
    }

    @Test
    void cancellationDrainsAdmittedToolAndDeniesTheNextTool()
            throws Exception
    {
        ActiveWriter writer = startWriter("cooperative-cancel");
        CountDownLatch toolEntered = new CountDownLatch(1);
        CountDownLatch cancellationInterruptedTool = new CountDownLatch(1);
        AtomicBoolean releaseTool = new AtomicBoolean(false);
        AtomicReference<Throwable> laterToolFailure = new AtomicReference<>();
        ExecutionHandle handle = FlowRuntimeTestSupport.launchWriterFixture(supervisor, runtime,
                writer.run().runId(),
                writer.claim(),
                writer.fence(),
                capability -> {
                    capability.runTool(() -> {
                        toolEntered.countDown();
                        while (!releaseTool.get()) {
                            if (Thread.interrupted()) {
                                cancellationInterruptedTool.countDown();
                            }
                            Thread.onSpinWait();
                        }
                    });
                    try {
                        capability.runTool(() -> {});
                    }
                    catch (Throwable failure) {
                        laterToolFailure.set(failure);
                    }
                    return completed();
                });
        assertThat(toolEntered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS))
                .isTrue();

        CompletableFuture<InProcessWriterAgentSupervisor.Cancellation>
                cancelFuture = CompletableFuture.supplyAsync(
                        () -> supervisor.cancel(handle, WAIT));
        assertThat(cancellationInterruptedTool.await(
                WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        assertThat(cancelFuture).isNotDone();
        assertThat(count("flow_runtime_writer_lease")).isOne();

        releaseTool.set(true);
        var cancellation = cancelFuture.get(
                WAIT.toMillis(), TimeUnit.MILLISECONDS);
        assertThat(cancellation.disposition())
                .isEqualTo(CancellationDisposition.CANCELED);
        assertThat(cancellation.result().terminalOutcome())
                .isEqualTo(TerminalOutcome.CANCELED);
        assertThat(laterToolFailure.get())
                .isInstanceOf(StaleCapabilityException.class);
        assertThat(attempt(writer).stopType())
                .isEqualTo(InProcessStopType.COOPERATIVE_CANCELLATION);
        assertThat(count("flow_runtime_writer_lease")).isZero();
    }

    @Test
    void uncooperativeCancellationQuarantinesWithoutReleasingWriter()
            throws Exception
    {
        ActiveWriter writer = startWriter("uncooperative-cancel");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch ended = new CountDownLatch(1);
        AtomicBoolean keepRunning = new AtomicBoolean(true);
        ExecutionHandle handle = FlowRuntimeTestSupport.launchWriterFixture(supervisor, runtime,
                writer.run().runId(),
                writer.claim(),
                writer.fence(),
                capability -> {
                    entered.countDown();
                    try {
                        while (keepRunning.get()) {
                            Thread.interrupted();
                            Thread.onSpinWait();
                        }
                        return completed();
                    }
                    finally {
                        ended.countDown();
                    }
                });
        assertThat(entered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS))
                .isTrue();

        var cancellation = supervisor.cancel(
                handle, Duration.ofMillis(20));
        assertThat(cancellation.disposition())
                .isEqualTo(CancellationDisposition.QUARANTINED);
        AgentProcessAttempt attempt = attempt(writer);
        assertThat(attempt.state()).isEqualTo(ProcessAttemptState.ACTIVATED);
        assertThat(attempt.capabilityRevokedAt()).isNotNull();
        assertThat(attempt.quarantineReason()).isEqualTo(
                FlowRuntimeRecords.ProcessQuarantineReason
                        .UNCOOPERATIVE_CANCELLATION);
        assertThat(runtime.task(writer.claim().taskId()).orElseThrow().status())
                .isEqualTo(TaskStatus.NEEDS_ATTENTION);
        assertThat(count("flow_runtime_writer_lease")).isOne();
        assertThat(runtime.task(writer.claim().taskId()).orElseThrow()
                .selectedWriterOperationId())
                .isEqualTo(writer.claim().operationId());
        assertThat(runtime.claimNext("other-worker", TTL)).isEmpty();

        keepRunning.set(false);
        assertThat(ended.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        assertThat(count("flow_runtime_writer_lease")).isOne();
        clock.advance(TTL.plusSeconds(1));
        assertThat(runtime.recoverExpiredClaim(
                writer.claim().operationId(), writer.claim().generation()))
                .isFalse();
        assertThat(runtime.recoverExpiredClaim(
                writer.claim().operationId(), writer.claim().generation()))
                .isFalse();
    }

    @Test
    void normalReturnAfterClaimExpiryStopsAndAwaitsRecovery()
            throws Exception
    {
        ActiveWriter writer = startWriter("expired-normal");
        CountDownLatch ended = new CountDownLatch(1);
        ExecutionHandle handle = FlowRuntimeTestSupport.launchWriterFixture(supervisor, runtime,
                writer.run().runId(),
                writer.claim(),
                writer.fence(),
                capability -> {
                    ended.countDown();
                    return completed();
                });
        assertThat(ended.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        clock.advance(TTL.plusSeconds(1));

        assertThatThrownBy(() -> supervisor.awaitAndFinish(handle, WAIT))
                .isInstanceOf(StoppedAwaitingRecoveryException.class);
        assertStoppedAwaitingRecovery(writer, handle);
        assertThat(runtime.recoverExpiredClaim(
                writer.claim().operationId(), writer.claim().generation()))
                .isFalse();
        assertThat(runtime.recoverExpiredClaim(
                writer.claim().operationId(), writer.claim().generation()))
                .isFalse();
    }

    @Test
    void cancellationAfterClaimExpiryStopsAndAwaitsRecovery()
            throws Exception
    {
        ActiveWriter writer = startWriter("expired-cancel");
        CountDownLatch entered = new CountDownLatch(1);
        ExecutionHandle handle = FlowRuntimeTestSupport.launchWriterFixture(supervisor, runtime,
                writer.run().runId(),
                writer.claim(),
                writer.fence(),
                capability -> {
                    entered.countDown();
                    await(new CountDownLatch(1));
                    return completed();
                });
        assertThat(entered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS))
                .isTrue();
        clock.advance(TTL.plusSeconds(1));

        assertThatThrownBy(() -> supervisor.cancel(handle, WAIT))
                .isInstanceOf(StoppedAwaitingRecoveryException.class);
        assertStoppedAwaitingRecovery(writer, handle);
        assertThat(attempt(writer).stopType())
                .isEqualTo(InProcessStopType.COOPERATIVE_CANCELLATION);
        assertThat(runtime.recoverExpiredClaim(
                writer.claim().operationId(), writer.claim().generation()))
                .isFalse();
    }

    @Test
    void recoveryFirstStillAllowsCooperativeStopCapture()
            throws Exception
    {
        ActiveWriter writer = startWriter("recovery-first-stop");
        CountDownLatch entered = new CountDownLatch(1);
        ExecutionHandle handle = FlowRuntimeTestSupport.launchWriterFixture(supervisor, runtime,
                writer.run().runId(),
                writer.claim(),
                writer.fence(),
                capability -> {
                    entered.countDown();
                    await(new CountDownLatch(1));
                    return completed();
                });
        assertThat(entered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS))
                .isTrue();
        clock.advance(TTL.plusSeconds(1));
        assertThat(runtime.recoverExpiredClaim(
                writer.claim().operationId(), writer.claim().generation()))
                .isFalse();

        assertThatThrownBy(() -> supervisor.cancel(handle, WAIT))
                .isInstanceOf(StoppedAwaitingRecoveryException.class);
        assertStoppedAwaitingRecovery(writer, handle);
        assertThat(attempt(writer).stopType())
                .isEqualTo(InProcessStopType.COOPERATIVE_CANCELLATION);
    }

    @Test
    void recoveryFirstUncooperativeCancelRetainsQuarantineAndOwnership()
            throws Exception
    {
        ActiveWriter writer = startWriter("recovery-first-quarantine");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch ended = new CountDownLatch(1);
        AtomicBoolean keepRunning = new AtomicBoolean(true);
        ExecutionHandle handle = FlowRuntimeTestSupport.launchWriterFixture(supervisor, runtime,
                writer.run().runId(),
                writer.claim(),
                writer.fence(),
                capability -> {
                    entered.countDown();
                    try {
                        while (keepRunning.get()) {
                            Thread.interrupted();
                            Thread.onSpinWait();
                        }
                        return completed();
                    }
                    finally {
                        ended.countDown();
                    }
                });
        assertThat(entered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS))
                .isTrue();
        clock.advance(TTL.plusSeconds(1));
        assertThat(runtime.recoverExpiredClaim(
                writer.claim().operationId(), writer.claim().generation()))
                .isFalse();

        assertThat(supervisor.cancel(handle, Duration.ofMillis(20))
                .disposition()).isEqualTo(CancellationDisposition.QUARANTINED);
        AgentProcessAttempt quarantined = attempt(writer);
        assertThat(quarantined.state())
                .isEqualTo(ProcessAttemptState.ACTIVATED);
        assertThat(quarantined.quarantineReason()).isEqualTo(
                FlowRuntimeRecords.ProcessQuarantineReason
                        .UNCOOPERATIVE_CANCELLATION);
        assertThat(supervisor.liveExecution(handle.executionId()))
                .contains(handle);
        assertThat(count("flow_runtime_agent_result")).isZero();
        assertThat(count("flow_runtime_writer_lease")).isOne();
        assertThat(runtime.task(writer.claim().taskId()).orElseThrow()
                .selectedWriterOperationId())
                .isEqualTo(writer.claim().operationId());
        assertThatThrownBy(() -> runtime.assertWriterFence(
                writer.claim(), writer.fence()))
                .isInstanceOf(StaleWriterFenceException.class);

        keepRunning.set(false);
        assertThat(ended.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)).isTrue();
        assertThat(supervisor.liveExecution(handle.executionId()))
                .contains(handle);
        assertThat(count("flow_runtime_writer_lease")).isOne();
    }

    @Test
    void threadRenameIsDiagnosticAndDoesNotInvalidateStopWitness()
            throws Exception
    {
        ActiveWriter writer = startWriter("thread-rename");
        ExecutionHandle handle = FlowRuntimeTestSupport.launchWriterFixture(supervisor, runtime,
                writer.run().runId(),
                writer.claim(),
                writer.fence(),
                capability -> {
                    Thread.currentThread().setName("renamed-writer-thread");
                    return completed();
                });

        supervisor.awaitAndFinish(handle, WAIT);
        assertThat(attempt(writer).state())
                .isEqualTo(ProcessAttemptState.STOPPED);
        assertThat(attempt(writer).threadName())
                .startsWith("flow-writer-agent-");
    }

    @Test
    void activationFailureAbortsDormantThreadAndRedeliveryStartsOnce()
    {
        ActiveWriter writer = startWriter("activation-failure");
        AtomicInteger bodyStarts = new AtomicInteger();
        jdbc.execute(
                """
                CREATE TRIGGER fail_writer_activation
                BEFORE UPDATE OF state
                ON flow_runtime_agent_process_attempt
                WHEN NEW.state = 'ACTIVATED'
                BEGIN
                    SELECT RAISE(ABORT, 'activation failed');
                END
                """);

        assertThatThrownBy(() -> FlowRuntimeTestSupport.launchWriterFixture(supervisor, runtime,
                writer.run().runId(),
                writer.claim(),
                writer.fence(),
                capability -> {
                    bodyStarts.incrementAndGet();
                    return completed();
                })).isInstanceOf(RuntimeException.class);
        AgentProcessAttempt reserved = attempt(writer);
        assertThat(reserved.state()).isEqualTo(ProcessAttemptState.RESERVED);
        assertThat(reserved.jvmPid()).isNull();
        assertThat(bodyStarts).hasValue(0);
        assertThat(supervisor.liveExecution(reserved.executionId())).isEmpty();

        jdbc.execute("DROP TRIGGER fail_writer_activation");
        ExecutionHandle redelivery = FlowRuntimeTestSupport.launchWriterFixture(supervisor, runtime,
                writer.run().runId(),
                writer.claim(),
                writer.fence(),
                capability -> {
                    bodyStarts.incrementAndGet();
                    return completed();
                });
        supervisor.awaitAndFinish(redelivery, WAIT);
        assertThat(bodyStarts).hasValue(1);
    }

    @Test
    void duplicateSupervisorReusesLiveExecutionWithoutRestartingBody()
            throws Exception
    {
        ActiveWriter writer = startWriter("duplicate-live");
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger bodyStarts = new AtomicInteger();
        AtomicReference<Throwable> toolFailure = new AtomicReference<>();
        ExecutionHandle first = FlowRuntimeTestSupport.launchWriterFixture(supervisor, runtime,
                writer.run().runId(),
                writer.claim(),
                writer.fence(),
                capability -> {
                    bodyStarts.incrementAndGet();
                    entered.countDown();
                    await(release);
                    try {
                        capability.runTool(() -> {});
                    }
                    catch (Throwable failure) {
                        toolFailure.set(failure);
                    }
                    return completed();
                });
        assertThat(entered.await(WAIT.toMillis(), TimeUnit.MILLISECONDS))
                .isTrue();

        var restartedRuntime = new FlowRuntime(dataSource, clock);
        var secondSupervisor =
                new InProcessWriterAgentSupervisor(restartedRuntime);
        clock.advance(Duration.ofMinutes(1));
        Claim renewedClaim = restartedRuntime.renewClaim(
                writer.claim(), TTL);
        WriterFence renewedFence = restartedRuntime.renewWriterLease(
                renewedClaim, writer.fence(), TTL);
        ExecutionHandle duplicate = secondSupervisor.launch(
                writer.run().runId(),
                renewedClaim,
                renewedFence,
                capability -> {
                    bodyStarts.incrementAndGet();
                    return completed();
                });
        assertThat(duplicate).isEqualTo(first);
        assertThat(bodyStarts).hasValue(1);

        release.countDown();
        var firstResult = secondSupervisor.awaitAndFinish(duplicate, WAIT);
        assertThat(toolFailure.get()).isNull();
        assertThat(FlowRuntimeTestSupport.finishWriterFixture(
                restartedRuntime,
                writer.run().runId(),
                writer.claim(),
                writer.fence(),
                completed())).isEqualTo(firstResult);
        assertThat(count("flow_runtime_agent_process_attempt")).isOne();
        assertThat(count("flow_runtime_agent_result")).isOne();
    }

    @Test
    void liveJvmAttemptMissingFromRegistryCannotBeCalledRestarted()
    {
        ActiveWriter writer = startWriter("missing-registry");
        AgentProcessAttempt attempt = runtime.reserveInProcessWriterAttempt(
                writer.run().runId(), writer.claim(), writer.fence());
        runtime.activateInProcessWriterAttempt(
                attempt.processAttemptId(),
                writer.claim(),
                writer.fence(),
                ProcessHandle.current().pid(),
                Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean()
                        .getStartTime()),
                Thread.currentThread().threadId(),
                Thread.currentThread().getName());

        var restartedSupervisor = new InProcessWriterAgentSupervisor(
                new FlowRuntime(dataSource, clock));
        assertThatThrownBy(() -> restartedSupervisor.launch(
                writer.run().runId(),
                writer.claim(),
                writer.fence(),
                capability -> completed()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("live-JVM execution")
                .hasMessageContaining("not present");
        AgentProcessAttempt quarantined = attempt(writer);
        assertThat(quarantined.state())
                .isEqualTo(ProcessAttemptState.ACTIVATED);
        assertThat(quarantined.capabilityRevokedAt()).isNotNull();
        assertThat(quarantined.quarantineReason()).isEqualTo(
                FlowRuntimeRecords.ProcessQuarantineReason
                        .IN_PROCESS_OWNER_UNAVAILABLE);
        assertThat(runtime.task(writer.claim().taskId()).orElseThrow().status())
                .isEqualTo(TaskStatus.NEEDS_ATTENTION);
        assertThat(count("flow_runtime_writer_lease")).isOne();
    }

    @Test
    void aCrashedRunsAgentGroupIsBuriedBeforeAnotherWriterIsConsidered(
            @TempDir Path groupRoot)
            throws Exception
    {
        // The failure with no recovery: a leftover CLI agent still editing the
        // worktree the next writer is handed. Revoking a capability cannot reach
        // it — a subprocess never held one — so only killing the group does.
        ActiveWriter writer = startWriter("orphaned-agent-group");
        AgentProcessAttempt attempt = runtime.reserveInProcessWriterAttempt(
                writer.run().runId(), writer.claim(), writer.fence());
        runtime.activateInProcessWriterAttempt(
                attempt.processAttemptId(),
                writer.claim(),
                writer.fence(),
                ProcessHandle.current().pid(),
                Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean()
                        .getStartTime()),
                Thread.currentThread().threadId(),
                Thread.currentThread().getName());
        ProcessGroup.Spawned agent = ProcessGroup.start(
                List.of("/bin/sh", "-c", "sh -c 'sleep 300 &' ; sleep 300"),
                groupRoot,
                Map.of(),
                groupRoot.resolve("pgid"));
        try {
            runtime.recordInProcessWriterAgentGroup(
                    attempt.processAttemptId(),
                    writer.claim(),
                    writer.fence(),
                    agent.process().pid(),
                    agent.pgid(),
                    agent.leaderStartedAt());
            assertThat(ProcessGroup.isAlive(agent.pgid())).isTrue();

            var restartedSupervisor = new InProcessWriterAgentSupervisor(
                    new FlowRuntime(dataSource, clock));
            assertThatThrownBy(() -> restartedSupervisor.launch(
                    writer.run().runId(),
                    writer.claim(),
                    writer.fence(),
                    capability -> completed()))
                    .isInstanceOf(IllegalStateException.class);

            // The reparented grandchild goes too, which is the whole reason the
            // group is the receipt and a descendants() walk is not.
            assertThat(ProcessGroup.isAlive(agent.pgid()))
                    .as("the previous run's agent group must not survive"
                            + " recovery")
                    .isFalse();
            assertThat(runtime.task(writer.claim().taskId()).orElseThrow()
                    .status()).isEqualTo(TaskStatus.NEEDS_ATTENTION);
        }
        finally {
            ProcessGroup.bury(agent.pgid());
        }
    }

    @Test
    void anUnidentifiableGroupIsLeftAloneRatherThanSignalled(
            @TempDir Path groupRoot)
            throws Exception
    {
        // Without a recorded start time the number alone cannot say whether the
        // group is ours, and by now it may be a stranger's. Parking the task is
        // the safe half; killing processes we cannot identify is not.
        ActiveWriter writer = startWriter("unidentifiable-agent-group");
        AgentProcessAttempt attempt = runtime.reserveInProcessWriterAttempt(
                writer.run().runId(), writer.claim(), writer.fence());
        runtime.activateInProcessWriterAttempt(
                attempt.processAttemptId(),
                writer.claim(),
                writer.fence(),
                ProcessHandle.current().pid(),
                Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean()
                        .getStartTime()),
                Thread.currentThread().threadId(),
                Thread.currentThread().getName());
        ProcessGroup.Spawned bystander = ProcessGroup.start(
                List.of("sleep", "300"), groupRoot, Map.of(),
                groupRoot.resolve("pgid"));
        try {
            runtime.recordInProcessWriterAgentGroup(
                    attempt.processAttemptId(),
                    writer.claim(),
                    writer.fence(),
                    bystander.process().pid(),
                    bystander.pgid(),
                    null);

            var restartedSupervisor = new InProcessWriterAgentSupervisor(
                    new FlowRuntime(dataSource, clock));
            assertThatThrownBy(() -> restartedSupervisor.launch(
                    writer.run().runId(),
                    writer.claim(),
                    writer.fence(),
                    capability -> completed()))
                    .isInstanceOf(IllegalStateException.class);

            assertThat(ProcessGroup.isAlive(bystander.pgid()))
                    .as("a group that cannot be identified must not be killed")
                    .isTrue();
            assertThat(runtime.task(writer.claim().taskId()).orElseThrow()
                    .status()).isEqualTo(TaskStatus.NEEDS_ATTENTION);
        }
        finally {
            ProcessGroup.bury(bystander.pgid());
        }
    }

    @Test
    void turnUsageLandsOnTheAttemptAndAccumulatesOnTheSession()
    {
        // What has to survive the turn: the vendor's handle on the conversation
        // so the next one continues it, and the spend a budget stops on.
        ActiveWriter writer = startWriter("turn-usage");
        AgentProcessAttempt attempt = activatedAttempt(writer);

        runtime.recordAgentTurnUsage(
                attempt.processAttemptId(), writer.claim(), "prov-1",
                11, 5, 250);

        assertThat(runtime.resumableProviderSession(writer.run().runId()))
                .contains("prov-1");
        assertThat(jdbc.queryForObject(
                """
                SELECT total_tokens_in || '/' || total_tokens_out || '/'
                    || total_cost_milli_usd || '/' || provider_session_id
                FROM flow_runtime_agent_session
                """,
                String.class))
                .isEqualTo("11/5/250/prov-1");
        assertThat(jdbc.queryForObject(
                """
                SELECT attempt_tokens_in FROM flow_runtime_agent_process_attempt
                WHERE process_attempt_id = ?
                """,
                Long.class,
                attempt.processAttemptId()))
                .isEqualTo(11L);
    }

    @Test
    void turnUsageIsCountedOnceEvenIfTheCompletionIsRedelivered()
    {
        // A redelivery that quietly added again would inflate the session total
        // and stop a healthy run on a budget it never actually spent.
        ActiveWriter writer = startWriter("turn-usage-once");
        AgentProcessAttempt attempt = activatedAttempt(writer);
        runtime.recordAgentTurnUsage(
                attempt.processAttemptId(), writer.claim(), "prov-1", 11, 5, 250);

        assertThatThrownBy(() -> runtime.recordAgentTurnUsage(
                attempt.processAttemptId(), writer.claim(), "prov-1", 11, 5, 250))
                .isInstanceOf(FlowRuntime.StaleOwnerRevisionException.class);
        assertThat(jdbc.queryForObject(
                "SELECT total_tokens_in FROM flow_runtime_agent_session",
                Long.class))
                .isEqualTo(11L);
    }

    private AgentProcessAttempt activatedAttempt(ActiveWriter writer)
    {
        AgentProcessAttempt attempt = runtime.reserveInProcessWriterAttempt(
                writer.run().runId(), writer.claim(), writer.fence());
        runtime.activateInProcessWriterAttempt(
                attempt.processAttemptId(),
                writer.claim(),
                writer.fence(),
                ProcessHandle.current().pid(),
                Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean()
                        .getStartTime()),
                Thread.currentThread().threadId(),
                Thread.currentThread().getName());
        return attempt;
    }

    @Test
    void aLiveAgentGroupIsRecordedOnceAndNeverSilentlyReplaced()
            throws Exception
    {
        // Two agents under one attempt is not a state to overwrite quietly: the
        // group that gets forgotten is the one nothing can bury afterwards.
        // While the recorded group is alive, a different record is refused.
        ActiveWriter writer = startWriter("set-once-agent-group");
        AgentProcessAttempt attempt = runtime.reserveInProcessWriterAttempt(
                writer.run().runId(), writer.claim(), writer.fence());
        runtime.activateInProcessWriterAttempt(
                attempt.processAttemptId(),
                writer.claim(),
                writer.fence(),
                ProcessHandle.current().pid(),
                Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean()
                        .getStartTime()),
                Thread.currentThread().threadId(),
                Thread.currentThread().getName());
        Instant startedAt = Instant.ofEpochMilli(1_700_000_000_000L);
        long livePgid = ownProcessGroup();
        runtime.recordInProcessWriterAgentGroup(
                attempt.processAttemptId(),
                writer.claim(), writer.fence(), livePgid, livePgid, startedAt);

        // A redelivery naming the same group is the no-op it looks like.
        runtime.recordInProcessWriterAgentGroup(
                attempt.processAttemptId(),
                writer.claim(), writer.fence(), livePgid, livePgid, startedAt);

        assertThatThrownBy(() -> runtime.recordInProcessWriterAgentGroup(
                attempt.processAttemptId(),
                writer.claim(), writer.fence(),
                99_999_991L, 99_999_991L, startedAt))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("changed identity");
        AgentProcessAttempt stored = attempt(writer);
        assertThat(stored.agentPgid()).isEqualTo(livePgid);
        assertThat(stored.agentStartedAt()).isEqualTo(startedAt);
    }

    @Test
    void aSuccessorProcessMayReplaceAProvenAbsentGroup()
    {
        // A turn whose provider session could not be resumed launches a second
        // process under the same attempt. That is legitimate exactly when the
        // first group is provably gone — the row must always name the one
        // group a later JVM would have to bury.
        ActiveWriter writer = startWriter("replace-buried-agent-group");
        AgentProcessAttempt attempt = runtime.reserveInProcessWriterAttempt(
                writer.run().runId(), writer.claim(), writer.fence());
        runtime.activateInProcessWriterAttempt(
                attempt.processAttemptId(),
                writer.claim(),
                writer.fence(),
                ProcessHandle.current().pid(),
                Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean()
                        .getStartTime()),
                Thread.currentThread().threadId(),
                Thread.currentThread().getName());
        Instant startedAt = Instant.ofEpochMilli(1_700_000_000_000L);
        runtime.recordInProcessWriterAgentGroup(
                attempt.processAttemptId(),
                writer.claim(), writer.fence(),
                99_999_991L, 99_999_991L, startedAt);

        Instant successorStartedAt = Instant.ofEpochMilli(1_700_000_001_000L);
        runtime.recordInProcessWriterAgentGroup(
                attempt.processAttemptId(),
                writer.claim(), writer.fence(),
                99_999_992L, 99_999_992L, successorStartedAt);

        AgentProcessAttempt stored = attempt(writer);
        assertThat(stored.agentPgid()).isEqualTo(99_999_992L);
        assertThat(stored.agentStartedAt()).isEqualTo(successorStartedAt);
    }

    private static long ownProcessGroup()
            throws IOException, InterruptedException
    {
        Process ps = new ProcessBuilder(
                "ps", "-o", "pgid=", "-p",
                Long.toString(ProcessHandle.current().pid())).start();
        String output = new String(
                ps.getInputStream().readAllBytes(),
                StandardCharsets.UTF_8).strip();
        if (ps.waitFor() != 0 || output.isBlank()) {
            throw new IllegalStateException(
                    "could not resolve this JVM's process group");
        }
        return Long.parseLong(output);
    }

    private RunningBody launchWaiting(ActiveWriter writer)
    {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch invoke = new CountDownLatch(1);
        CountDownLatch ended = new CountDownLatch(1);
        AtomicReference<Throwable> toolFailure = new AtomicReference<>();
        ExecutionHandle handle = FlowRuntimeTestSupport.launchWriterFixture(supervisor, runtime,
                writer.run().runId(),
                writer.claim(),
                writer.fence(),
                capability -> {
                    capability.runTool(() -> {});
                    entered.countDown();
                    try {
                        await(invoke);
                        try {
                            capability.runTool(() -> {});
                        }
                        catch (Throwable failure) {
                            toolFailure.set(failure);
                        }
                        return completed();
                    }
                    finally {
                        ended.countDown();
                    }
                });
        await(entered);
        return new RunningBody(
                handle, invoke, ended, toolFailure);
    }

    private ActiveWriter startWriter(String suffix)
    {
        Task started = FlowRuntimeTestSupport.startTask(runtime,
                "request-" + suffix,
                "repo-1",
                "Implement the task",
                temporaryDirectory.resolve("worktree-" + suffix).toString());
        Claim provision = claim(OperationKind.PROVISION_TASK);
        FlowRuntimeTestSupport.provisionTask(runtime, provision, HEAD_SHA);
        Claim reconciliation = claim(OperationKind.RECONCILE_TASK);
        assertThat(runtime.selectNext(reconciliation).orElseThrow().kind())
                .isEqualTo(OperationKind.RUN_TASK_TURN);
        Claim turn = claim(OperationKind.RUN_TASK_TURN);
        WriterFence fence = FlowRuntimeTestSupport.acquireWriterFixture(
                runtime,
                turn,
                AgentRole.TASK_AGENT,
                new WorktreeSnapshot(
                        HEAD_SHA,
                        "tree:" + HEAD_SHA,
                        "snapshot:" + HEAD_SHA),
                TTL);
        AgentRun run = runtime.startWriterAgent(
                turn, fence, "prompt:task", "capabilities:task");
        assertThat(started.taskId()).isEqualTo(turn.taskId());
        return new ActiveWriter(turn, fence, run);
    }

    private void assertStoppedAwaitingRecovery(
            ActiveWriter writer, ExecutionHandle handle)
    {
        assertThat(attempt(writer).state())
                .isEqualTo(ProcessAttemptState.STOPPED);
        assertThat(attempt(writer).capabilityRevokedAt()).isNotNull();
        assertThat(supervisor.liveExecution(handle.executionId())).isEmpty();
        assertThat(count("flow_runtime_agent_result")).isZero();
        assertThat(count("flow_runtime_writer_lease")).isOne();
        assertThat(runtime.task(writer.claim().taskId()).orElseThrow()
                .selectedWriterOperationId())
                .isEqualTo(writer.claim().operationId());
    }

    private Claim claim(OperationKind kind)
    {
        Claim claim = runtime.claimNext("worker", TTL).orElseThrow();
        assertThat(claim.kind()).isEqualTo(kind);
        return claim;
    }

    private AgentProcessAttempt attempt(ActiveWriter writer)
    {
        return jdbc.query(
                """
                SELECT * FROM flow_runtime_agent_process_attempt
                WHERE run_id = ?
                """,
                (result, row) -> new AgentProcessAttempt(
                        result.getString("process_attempt_id"),
                        result.getString("run_id"),
                        result.getString("operation_id"),
                        result.getLong("claim_generation"),
                        result.getString("claim_token_digest"),
                        result.getString("execution_id"),
                        result.getString("capability_id"),
                        ProcessAttemptState.valueOf(result.getString("state")),
                        nullableLong(result, "jvm_pid"),
                        nullableInstant(result, "jvm_started_at"),
                        nullableLong(result, "thread_id"),
                        result.getString("thread_name"),
                        nullableLong(result, "agent_pid"),
                        nullableLong(result, "agent_pgid"),
                        nullableInstant(result, "agent_started_at"),
                        instant(result, "reserved_at"),
                        nullableInstant(result, "activated_at"),
                        nullableInstant(result, "capability_revoked_at"),
                        nullableStopType(result.getString("stop_type")),
                        result.getString("stop_proof_ref"),
                        nullableInstant(result, "stopped_at"),
                        result.getString("quarantine_reason") == null
                                ? null
                                : FlowRuntimeRecords.ProcessQuarantineReason
                                        .valueOf(result.getString(
                                                "quarantine_reason")),
                        nullableInstant(result, "quarantined_at")),
                writer.run().runId()).getFirst();
    }

    private int count(String table)
    {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private static AgentCompletion completed()
    {
        return new AgentCompletion(TerminalOutcome.COMPLETED, "done", null);
    }

    private static void await(CountDownLatch latch)
    {
        try {
            if (!latch.await(WAIT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("latch timed out");
            }
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static Instant instant(
            ResultSet result, String column)
            throws SQLException
    {
        return Instant.ofEpochMilli(result.getLong(column));
    }

    private static Instant nullableInstant(
            ResultSet result, String column)
            throws SQLException
    {
        long value = result.getLong(column);
        return result.wasNull() ? null : Instant.ofEpochMilli(value);
    }

    private static Long nullableLong(
            ResultSet result, String column)
            throws SQLException
    {
        long value = result.getLong(column);
        return result.wasNull() ? null : value;
    }

    private static InProcessStopType nullableStopType(String value)
    {
        return value == null ? null : InProcessStopType.valueOf(value);
    }

    private record ActiveWriter(
            Claim claim, WriterFence fence, AgentRun run) {}

    private record RunningBody(
            ExecutionHandle handle,
            CountDownLatch invoke,
            CountDownLatch ended,
            AtomicReference<Throwable> toolFailure) {}

    private static final class MutableClock
            extends Clock
    {
        private final AtomicReference<Instant> instant;

        private MutableClock(Instant initial)
        {
            instant = new AtomicReference<>(initial);
        }

        private void advance(Duration duration)
        {
            instant.updateAndGet(value -> value.plus(duration));
        }

        @Override
        public ZoneId getZone()
        {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone)
        {
            return this;
        }

        @Override
        public Instant instant()
        {
            return instant.get();
        }
    }
}
