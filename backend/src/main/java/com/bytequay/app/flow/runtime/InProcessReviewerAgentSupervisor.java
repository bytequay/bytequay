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

import com.bytequay.app.flow.runtime.FlowRuntime.ReviewerStart;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentProcessAttempt;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentResult;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.InProcessStopType;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ProcessAttemptState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ProcessQuarantineReason;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ReviewerRequest;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TerminalOutcome;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/** Concrete in-JVM owner for one request-bound immutable reviewer. */
public final class InProcessReviewerAgentSupervisor
{
    private static final long JVM_PID = ProcessHandle.current().pid();
    private static final Instant JVM_STARTED_AT = Instant.ofEpochMilli(
            ManagementFactory.getRuntimeMXBean().getStartTime());
    private static final Duration DORMANT_START_TIMEOUT = Duration.ofSeconds(5);
    private static final Object LAUNCH_LOCK = new Object();
    private static final ConcurrentMap<String, ManagedExecution> LIVE =
            new ConcurrentHashMap<>();

    private final FlowRuntime runtime;

    public InProcessReviewerAgentSupervisor(FlowRuntime runtime)
    {
        this.runtime = requireNonNull(runtime, "runtime is null");
    }

    public record AgentCompletion(
            TerminalOutcome terminalOutcome,
            String finalContent,
            String errorRef)
    {
        public AgentCompletion
        {
            requireNonNull(terminalOutcome, "terminalOutcome is null");
            if (terminalOutcome != TerminalOutcome.COMPLETED
                    && (errorRef == null || errorRef.isBlank())) {
                throw new IllegalArgumentException(
                        "non-completed reviewer requires errorRef");
            }
        }
    }

    public record ExecutionHandle(
            String runId, String processAttemptId, String executionId)
    {
        public ExecutionHandle
        {
            requireText(runId, "runId");
            requireText(processAttemptId, "processAttemptId");
            requireText(executionId, "executionId");
        }
    }

    /** Immutable reads plus one fixed-path report handoff; no generic effect. */
    public static final class ReviewerToolCapability
    {
        private final ManagedExecution execution;

        private ReviewerToolCapability(ManagedExecution execution)
        {
            this.execution = execution;
        }

        public List<ImmutableGitObjectReader.TreeEntry> listTree()
        {
            return execution.read(() -> execution.reader.listTree());
        }

        public byte[] readReviewedBlob(String path)
        {
            return execution.read(() ->
                    execution.reader.readReviewedBlob(path));
        }

        public byte[] readBaseBlob(String path)
        {
            return execution.read(() -> execution.reader.readBaseBlob(path));
        }

        public byte[] readDiff()
        {
            return execution.read(() -> execution.reader.readDiff());
        }

        public byte[] readCommitHistory()
        {
            return execution.read(() ->
                    execution.reader.readCommitHistory());
        }

        /** Saves opaque prose only to the Task's program-owned handoff file. */
        public void saveReport(String report)
        {
            execution.read(() -> {
                ReviewReportFile.save(execution.task(), report);
                return null;
            });
        }

        /**
         * Records the process group a CLI reviewer just launched, before its
         * prompt goes in. Not a read: this is the program recording what it
         * started, and it must work even once the read surface is closed.
         */
        public void recordAgentGroup(
                long agentPid, long agentPgid, Instant agentStartedAt)
        {
            execution.recordAgentGroup(agentPid, agentPgid, agentStartedAt);
        }

        /** What this turn spent, and the handle its successor resumes. */
        public void recordAgentTurnUsage(
                String providerSessionId,
                long tokensIn,
                long tokensOut,
                long costMilliUsd)
        {
            execution.recordAgentTurnUsage(
                    providerSessionId, tokensIn, tokensOut, costMilliUsd);
        }

        @Override
        public String toString()
        {
            return "ReviewerToolCapability[immutable-review]";
        }
    }

    /** Exact terminated reviewer-thread witness; no writer fence is involved. */
    static final class TerminatedThreadWitness
    {
        private final Thread thread;
        private final long jvmPid;
        private final Instant jvmStartedAt;

        private TerminatedThreadWitness(Thread thread)
        {
            this.thread = requireNonNull(thread, "thread is null");
            if (thread.getState() != Thread.State.TERMINATED) {
                throw new IllegalStateException(
                        "reviewer thread is not terminated");
            }
            this.jvmPid = JVM_PID;
            this.jvmStartedAt = JVM_STARTED_AT;
        }

        Thread thread()
        {
            return thread;
        }

        long jvmPid()
        {
            return jvmPid;
        }

        Instant jvmStartedAt()
        {
            return jvmStartedAt;
        }
    }

    public ExecutionHandle launch(
            ReviewerStart start,
            Claim claim,
            Function<ReviewerToolCapability, AgentCompletion> body)
    {
        requireNonNull(start, "start is null");
        requireNonNull(claim, "claim is null");
        requireNonNull(body, "body is null");
        ReviewerRequest request = start.request();
        synchronized (LAUNCH_LOCK) {
            AgentProcessAttempt attempt = runtime.reserveInProcessReviewerAttempt(
                    start.run().runId(), claim, request.requestId());
            ManagedExecution live = LIVE.get(attempt.executionId());
            if (live != null) {
                live.adoptCurrentOwner(start, claim);
                return live.handle();
            }
            if (attempt.state() != ProcessAttemptState.RESERVED) {
                if (attempt.state() == ProcessAttemptState.ACTIVATED) {
                    runtime.revokeInProcessReviewerCapability(
                            attempt.processAttemptId(), claim);
                    runtime.quarantineInProcessReviewerAttempt(
                            attempt.processAttemptId(),
                            claim,
                            ProcessQuarantineReason
                                    .IN_PROCESS_OWNER_UNAVAILABLE);
                }
                throw unavailableExecution(attempt);
            }
            ManagedExecution created = new ManagedExecution(
                    start, claim, attempt);
            Thread thread = new Thread(
                    () -> created.run(body),
                    "flow-reviewer-agent-" + attempt.executionId());
            thread.setDaemon(true);
            created.thread = thread;
            ManagedExecution raced = LIVE.putIfAbsent(
                    attempt.executionId(), created);
            if (raced != null) {
                raced.adoptCurrentOwner(start, claim);
                return raced.handle();
            }
            try {
                thread.start();
                created.awaitDormant();
                runtime.activateInProcessReviewerAttempt(
                        attempt.processAttemptId(),
                        claim,
                        JVM_PID,
                        JVM_STARTED_AT,
                        thread.threadId(),
                        thread.getName());
                created.activateBody();
                return created.handle();
            }
            catch (RuntimeException | Error failure) {
                created.abortDormant();
                try {
                    join(thread, DORMANT_START_TIMEOUT);
                }
                finally {
                    LIVE.remove(attempt.executionId(), created);
                }
                throw failure;
            }
        }
    }

    /** A failed final transaction leaves the stopped execution retryable. */
    public AgentResult awaitAndFinish(
            ExecutionHandle handle, Duration timeout)
    {
        ManagedExecution execution = requireLive(handle);
        join(execution.thread, timeout);
        if (execution.thread.isAlive()) {
            throw new IllegalStateException(
                    "reviewer did not finish before the deadline");
        }
        return finishEnded(execution, false);
    }

    public AgentResult cancel(ExecutionHandle handle, Duration timeout)
    {
        ManagedExecution execution = requireLive(handle);
        boolean cancellationStarted;
        synchronized (execution) {
            if (execution.result != null) {
                return execution.result;
            }
            cancellationStarted = execution.completion == null
                    && execution.stoppedAttempt == null
                    && execution.thread.isAlive();
            if (cancellationStarted) {
                execution.cancellationRequested = true;
                execution.revocationRequested = true;
                runtime.revokeInProcessReviewerCapability(
                        execution.attempt.processAttemptId(),
                        execution.claim);
                execution.thread.interrupt();
            }
        }
        join(execution.thread, timeout);
        if (execution.thread.isAlive()) {
            synchronized (execution) {
                if (!execution.quarantined) {
                    runtime.quarantineInProcessReviewerAttempt(
                            execution.attempt.processAttemptId(),
                            execution.claim,
                            ProcessQuarantineReason
                                    .UNCOOPERATIVE_CANCELLATION);
                    execution.quarantined = true;
                }
            }
            throw new IllegalStateException(
                    "uncooperative reviewer was quarantined");
        }
        return finishEnded(execution, cancellationStarted);
    }

    private AgentResult finishEnded(
            ManagedExecution execution, boolean cancellation)
    {
        synchronized (execution) {
            if (execution.result != null) {
                return execution.result;
            }
            if (execution.quarantined || execution.thread.isAlive()) {
                throw new IllegalStateException(
                        "reviewer has no terminal stop proof");
            }
            execution.freezeFinalization(cancellation);
            AgentCompletion completion = execution.finalizationCompletion;
            if (execution.stoppedAttempt == null) {
                execution.revocationRequested = true;
                runtime.revokeInProcessReviewerCapability(
                        execution.attempt.processAttemptId(),
                        execution.claim);
                if (execution.activeReads != 0) {
                    throw new IllegalStateException(
                            "reviewer stopped with an active object read");
                }
                execution.stoppedAttempt =
                        runtime.recordInProcessReviewerStopped(
                                execution.attempt.processAttemptId(),
                                execution.claim,
                                new TerminatedThreadWitness(execution.thread),
                                execution.stopType,
                                completion.terminalOutcome(),
                                completion.finalContent(),
                                completion.errorRef());
            }
            AgentResult result = runtime.finishReviewerAgentRun(
                    execution.runId,
                    execution.claim,
                    completion.terminalOutcome(),
                    completion.finalContent(),
                    completion.errorRef());
            assertExactResult(
                    result,
                    execution.runId,
                    completion,
                    execution.stoppedAttempt.stopProofRef());
            execution.result = runtime.verifyFinalizedReviewerResult(
                    execution.runId, execution.claim, result);
            LIVE.remove(execution.attempt.executionId(), execution);
            return execution.result;
        }
    }

    private static void assertExactResult(
            AgentResult result,
            String runId,
            AgentCompletion completion,
            String stopProofRef)
    {
        if (!result.runId().equals(runId)
                || result.terminalOutcome() != completion.terminalOutcome()
                || !Objects.equals(
                        result.finalContent(), completion.finalContent())
                || !Objects.equals(result.errorRef(), completion.errorRef())
                || !result.stopProofRef().equals(stopProofRef)) {
            throw new IllegalStateException(
                    "reviewer finalization changed immutable completion");
        }
    }

    private static ManagedExecution requireLive(ExecutionHandle handle)
    {
        requireNonNull(handle, "handle is null");
        ManagedExecution execution = LIVE.get(handle.executionId());
        if (execution == null
                || !execution.activated
                || !execution.runId.equals(handle.runId())
                || !execution.attempt.processAttemptId()
                        .equals(handle.processAttemptId())) {
            throw new IllegalStateException(
                    "reviewer execution is not owned by this JVM");
        }
        return execution;
    }

    private static IllegalStateException unavailableExecution(
            AgentProcessAttempt attempt)
    {
        if (Long.valueOf(JVM_PID).equals(attempt.jvmPid())
                && JVM_STARTED_AT.equals(attempt.jvmStartedAt())) {
            return new IllegalStateException(
                    "live reviewer is absent from the supervisor registry");
        }
        return new IllegalStateException(
                "prior-JVM reviewer cannot be proven stopped");
    }

    private static void join(Thread thread, Duration timeout)
    {
        requireNonNull(timeout, "timeout is null");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        try {
            thread.join(timeout);
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "interrupted while waiting for reviewer", exception);
        }
    }

    private final class ManagedExecution
    {
        private final String runId;
        private final String requestId;
        private final ReviewerRequest request;
        private volatile Claim claim;
        private final AgentProcessAttempt attempt;
        private volatile ImmutableGitObjectReader reader;
        private final CountDownLatch dormantReady = new CountDownLatch(1);
        private final CountDownLatch startGate = new CountDownLatch(1);
        private volatile Thread thread;
        private volatile AgentCompletion completion;
        private volatile boolean activated;
        private volatile boolean abortBeforeActivation;
        private boolean revocationRequested;
        private int activeReads;
        private boolean cancellationRequested;
        private boolean quarantined;
        private AgentResult result;
        private AgentCompletion finalizationCompletion;
        private InProcessStopType stopType;
        private AgentProcessAttempt stoppedAttempt;

        private ManagedExecution(
                ReviewerStart start,
                Claim claim,
                AgentProcessAttempt attempt)
        {
            this.runId = start.run().runId();
            this.requestId = start.request().requestId();
            this.request = start.request();
            this.claim = claim;
            this.attempt = attempt;
        }

        private void run(
                Function<ReviewerToolCapability, AgentCompletion> body)
        {
            dormantReady.countDown();
            awaitStartGate();
            if (abortBeforeActivation) {
                return;
            }
            if (!activated) {
                throw new IllegalStateException(
                        "reviewer body gate opened before durable activation");
            }
            try {
                reader = new ImmutableGitObjectReader(
                        Path.of(request.repositoryRoot()),
                        request.baseHeadSha(),
                        request.reviewedHeadSha());
                AgentCompletion returned = requireNonNull(
                        body.apply(new ReviewerToolCapability(this)),
                        "reviewer body returned null");
                // Reviewer prose has exactly one channel: save_report. Never
                // let an ordinary final message become database data.
                completion = new AgentCompletion(
                        returned.terminalOutcome(), null,
                        returned.errorRef());
            }
            catch (RuntimeException | Error ignored) {
                completion = new AgentCompletion(
                        TerminalOutcome.FAILED,
                        null,
                        "IN_PROCESS_REVIEWER_FAILED");
            }
        }

        private <T> T read(Supplier<T> read)
        {
            requireNonNull(read, "read is null");
            synchronized (this) {
                if (Thread.currentThread() != thread) {
                    throw new FlowRuntime.StaleCapabilityException(
                            "review reads require the owned reviewer thread");
                }
                if (revocationRequested) {
                    throw new FlowRuntime.StaleCapabilityException(
                            "reviewer capability is revoked");
                }
                runtime.assertInProcessReviewerToolCapability(
                        runId, claim, attempt.capabilityId(), requestId);
                activeReads++;
            }
            try {
                return read.get();
            }
            finally {
                synchronized (this) {
                    activeReads--;
                    notifyAll();
                }
            }
        }

        private FlowRuntimeRecords.Task task()
        {
            return runtime.task(request.taskId()).orElseThrow(() ->
                    new IllegalStateException(
                            "reviewer Task disappeared during execution"));
        }

        private void adoptCurrentOwner(ReviewerStart start, Claim current)
        {
            synchronized (this) {
                if (!runId.equals(start.run().runId())
                        || !requestId.equals(start.request().requestId())
                        || !sameClaimAuthority(claim, current)) {
                    throw new IllegalStateException(
                            "live reviewer belongs to another owner");
                }
                claim = current;
            }
        }

        private void awaitDormant()
        {
            try {
                if (!dormantReady.await(
                        DORMANT_START_TIMEOUT.toMillis(),
                        TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException(
                            "reviewer did not reach its dormant gate");
                }
            }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "interrupted before reviewer activation", exception);
            }
        }

        private void awaitStartGate()
        {
            boolean interrupted = false;
            while (true) {
                try {
                    startGate.await();
                    break;
                }
                catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        private void activateBody()
        {
            activated = true;
            startGate.countDown();
        }

        private void abortDormant()
        {
            abortBeforeActivation = true;
            startGate.countDown();
        }

        private void recordAgentTurnUsage(
                String providerSessionId,
                long tokensIn,
                long tokensOut,
                long costMilliUsd)
        {
            if (Thread.currentThread() != thread) {
                throw new FlowRuntime.StaleCapabilityException(
                        "recording turn usage requires the owned reviewer"
                                + " thread");
            }
            runtime.recordAgentTurnUsage(
                    attempt.processAttemptId(),
                    claim,
                    providerSessionId,
                    tokensIn,
                    tokensOut,
                    costMilliUsd);
        }

        private void recordAgentGroup(
                long agentPid, long agentPgid, Instant agentStartedAt)
        {
            // Only the thread that owns the turn may say what it launched; any
            // other caller would be naming a group it does not hold.
            if (Thread.currentThread() != thread) {
                throw new FlowRuntime.StaleCapabilityException(
                        "recording an agent group requires the owned reviewer"
                                + " thread");
            }
            runtime.recordInProcessReadOnlyAgentGroup(
                    attempt.processAttemptId(),
                    claim,
                    agentPid,
                    agentPgid,
                    agentStartedAt);
        }

        private ExecutionHandle handle()
        {
            return new ExecutionHandle(
                    runId,
                    attempt.processAttemptId(),
                    attempt.executionId());
        }

        private void freezeFinalization(boolean cancellation)
        {
            if (finalizationCompletion != null) {
                return;
            }
            stopType = cancellation || cancellationRequested
                    ? InProcessStopType.COOPERATIVE_CANCELLATION
                    : InProcessStopType.NORMAL_RETURN;
            finalizationCompletion = stopType
                    == InProcessStopType.COOPERATIVE_CANCELLATION
                    ? new AgentCompletion(
                            TerminalOutcome.CANCELED,
                            completion == null
                                    ? null
                                    : completion.finalContent(),
                            "IN_PROCESS_REVIEWER_CANCELED")
                    : requireNonNull(
                            completion, "reviewer ended without completion");
        }
    }

    private static boolean sameClaimAuthority(Claim first, Claim second)
    {
        return first.operationId().equals(second.operationId())
                && Objects.equals(first.taskId(), second.taskId())
                && first.kind() == second.kind()
                && first.generation() == second.generation()
                && first.claimToken().equals(second.claimToken())
                && first.workerId().equals(second.workerId());
    }

    private static void requireText(String value, String name)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }
}
