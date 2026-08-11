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

import com.bytequay.app.flow.gate.UserGateRecords.ReadyForReviewAcceptance;
import com.bytequay.app.flow.gate.UserGates;
import com.bytequay.app.flow.gate.UserGates.PreparedReadyRequest;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentProcessAttempt;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentResult;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ChangeSetRevision;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.CiFixReviewOrigin;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.InProcessStopType;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.LocalCheckRun;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ProcessAttemptState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ProcessQuarantineReason;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ReviewerRequest;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TerminalOutcome;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.WriterFence;

import java.lang.management.ManagementFactory;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/**
 * Concrete owner of new-flow Task/CI writer execution inside this JVM.
 *
 * <p>This class deliberately has no CLI path. A body cannot begin until its
 * dormant Java thread identity is durably activated. Tool handlers receive the
 * opaque capability object and run every synchronous tool effect through
 * {@link WriterToolCapability#callTool(Supplier)}; it exposes neither the
 * dispatch claim token nor the writer fence.
 */
public final class InProcessWriterAgentSupervisor
{
    private static final long JVM_PID = ProcessHandle.current().pid();
    private static final Instant JVM_STARTED_AT = Instant.ofEpochMilli(
            ManagementFactory.getRuntimeMXBean().getStartTime());
    private static final Object LAUNCH_LOCK = new Object();
    private static final String RUNTIME_FINALIZER = "RUNTIME_AGENT_RESULT";
    private static final Duration DORMANT_START_TIMEOUT = Duration.ofSeconds(5);
    private static final ConcurrentMap<String, ManagedExecution> LIVE_EXECUTIONS =
            new ConcurrentHashMap<>();

    private final FlowRuntime runtime;
    private final StoppedFinalizer runtimeFinalizer;

    public InProcessWriterAgentSupervisor(FlowRuntime runtime)
    {
        this.runtime = requireNonNull(runtime, "runtime is null");
        this.runtimeFinalizer = this::finishWithRuntime;
    }

    /** Program-owned terminal data; finalContent remains opaque. */
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
                        "non-completed execution requires errorRef");
            }
        }
    }

    /** Program-owned command invoked only after durable exact-thread stop. */
    @FunctionalInterface
    public interface StoppedFinalizer
    {
        AgentResult finish(
                String runId,
                Claim claim,
                WriterFence fence,
                AgentCompletion completion);
    }

    /** Non-secret identity used by dispatcher code to await or cancel. */
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

    public enum CancellationDisposition
    {
        CANCELED,
        ALREADY_FINISHED,
        QUARANTINED
    }

    public record Cancellation(
            CancellationDisposition disposition, AgentResult result)
    {
        public Cancellation
        {
            requireNonNull(disposition, "disposition is null");
            if ((disposition != CancellationDisposition.QUARANTINED)
                    != (result != null)) {
                throw new IllegalArgumentException(
                        "only terminal cancellation outcomes have a result");
            }
        }
    }

    /**
     * Capability passed only to program tool wrappers. Its default identity
     * string contains no claim token or fence material.
     */
    public static final class WriterToolCapability
    {
        private final ManagedExecution execution;

        private WriterToolCapability(ManagedExecution execution)
        {
            this.execution = execution;
        }

        public <T> T callTool(Supplier<T> effect)
        {
            return execution.callTool(effect);
        }

        public void runTool(Runnable effect)
        {
            requireNonNull(effect, "effect is null");
            callTool(() -> {
                effect.run();
                return null;
            });
        }

        /** Program-bound local checks; the model supplies no command/evidence. */
        public List<LocalCheckRun> runChecks(
                LocalChecks localChecks,
                Path programOwnedRepositoryRoot,
                String profileName)
        {
            return execution.runChecks(
                    localChecks, programOwnedRepositoryRoot, profileName);
        }

        /** Program-only inspected adoption; no model owner ids are accepted. */
        public ChangeSetRevision adoptChangeSet(
                Path programOwnedRepositoryRoot,
                String expectedChangeSetRevisionId)
        {
            return execution.adoptChangeSet(
                    programOwnedRepositoryRoot,
                    expectedChangeSetRevisionId);
        }

        /** Terminal Task tool; its immutable request durably seals all tools. */
        public ReviewerRequest spawnAdversarialReviewer(
                Path programOwnedRepositoryRoot,
                String expectedChangeSetRevisionId,
                CiFixReviewOrigin origin,
                LocalChecks.ReviewerEvidence reviewerEvidence)
        {
            return execution.spawnAdversarialReviewer(
                    programOwnedRepositoryRoot,
                    expectedChangeSetRevisionId,
                    origin,
                    reviewerEvidence);
        }

        public ReviewerRequest replayAdversarialReviewer(
                Path programOwnedRepositoryRoot,
                String expectedChangeSetRevisionId,
                CiFixReviewOrigin origin,
                String localCheckPolicyRevisionId,
                List<String> checkRunRefs)
        {
            return execution.replayAdversarialReviewer(
                    programOwnedRepositoryRoot,
                    expectedChangeSetRevisionId,
                    origin,
                    localCheckPolicyRevisionId,
                    checkRunRefs);
        }

        /** Terminal ready command over one program-derived immutable subject. */
        public ReadyForReviewAcceptance readyForReview(
                UserGates userGates,
                Path repositoryRoot,
                ReviewerRequest reviewerRequest,
                AgentResult reviewerResult,
                CiFixReviewOrigin origin)
        {
            return execution.readyForReview(
                    userGates,
                    repositoryRoot,
                    reviewerRequest,
                    reviewerResult,
                    origin);
        }

        @Override
        public String toString()
        {
            return "WriterToolCapability[opaque]";
        }
    }

    /** Exact terminated-thread witness; only this supervisor can construct it. */
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
                        "writer thread is not terminated");
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

    public static final class StoppedAwaitingRecoveryException
            extends IllegalStateException
    {
        private final ExecutionHandle handle;

        private StoppedAwaitingRecoveryException(
                ExecutionHandle handle, Throwable cause)
        {
            super("writer stopped after its dispatch claim expired", cause);
            this.handle = handle;
        }

        public ExecutionHandle handle()
        {
            return handle;
        }
    }

    /**
     * Starts one owned Java thread behind a closed dormant gate, durably
     * activates its identity, then opens the gate for the writer body. Repeated
     * launch in this live JVM returns the same execution.
     */
    public ExecutionHandle launch(
            String runId,
            Claim claim,
            WriterFence fence,
            Function<WriterToolCapability, AgentCompletion> body)
    {
        return launch(
                runId,
                claim,
                fence,
                RUNTIME_FINALIZER,
                runtimeFinalizer,
                body);
    }

    /** Launches with one finalizer route bound before the body is exposed. */
    public ExecutionHandle launch(
            String runId,
            Claim claim,
            WriterFence fence,
            String finalizerKey,
            StoppedFinalizer finalizer,
            Function<WriterToolCapability, AgentCompletion> body)
    {
        requireText(runId, "runId");
        requireNonNull(claim, "claim is null");
        requireNonNull(fence, "fence is null");
        requireText(finalizerKey, "finalizerKey");
        requireNonNull(finalizer, "finalizer is null");
        requireNonNull(body, "body is null");

        synchronized (LAUNCH_LOCK) {
            return launchLocked(
                    runId,
                    claim,
                    fence,
                    finalizerKey,
                    finalizer,
                    body);
        }
    }

    private ExecutionHandle launchLocked(
            String runId,
            Claim claim,
            WriterFence fence,
            String finalizerKey,
            StoppedFinalizer finalizer,
            Function<WriterToolCapability, AgentCompletion> body)
    {
        AgentProcessAttempt attempt = runtime.reserveInProcessWriterAttempt(
                runId, claim, fence);
        ManagedExecution live = LIVE_EXECUTIONS.get(attempt.executionId());
        if (live != null) {
            live.adoptCurrentOwner(runId, claim, fence, finalizerKey);
            return live.handle();
        }
        if (attempt.state() != ProcessAttemptState.RESERVED) {
            if (attempt.state() == ProcessAttemptState.ACTIVATED) {
                runtime.revokeInProcessWriterCapability(
                        attempt.processAttemptId(), claim, fence);
                runtime.quarantineInProcessWriterAttempt(
                        attempt.processAttemptId(),
                        claim,
                        fence,
                        ProcessQuarantineReason
                                .IN_PROCESS_OWNER_UNAVAILABLE);
            }
            throw unavailableExecution(attempt);
        }

        ManagedExecution created = new ManagedExecution(
                runId,
                claim,
                fence,
                attempt,
                finalizerKey,
                finalizer);
        Thread thread = new Thread(
                () -> created.run(body),
                "flow-writer-agent-" + attempt.executionId());
        thread.setDaemon(true);
        created.thread = thread;
        ManagedExecution raced = LIVE_EXECUTIONS.putIfAbsent(
                attempt.executionId(), created);
        if (raced != null) {
            raced.adoptCurrentOwner(runId, claim, fence, finalizerKey);
            return raced.handle();
        }
        try {
            thread.start();
            created.awaitDormant();
            runtime.activateInProcessWriterAttempt(
                    attempt.processAttemptId(),
                    claim,
                    fence,
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
                LIVE_EXECUTIONS.remove(attempt.executionId(), created);
            }
            throw failure;
        }
    }

    /** Waits for normal return, then revokes, proves stop, and finishes. */
    public AgentResult awaitAndFinish(
            ExecutionHandle handle, Duration timeout)
    {
        return awaitAndFinalize(
                handle,
                timeout,
                RUNTIME_FINALIZER);
    }

    /**
     * Waits for exact-thread stop, then invokes one retryable program finalizer.
     * A failed finalizer leaves the stopped execution registered for retry.
     */
    public AgentResult awaitAndFinalize(
            ExecutionHandle handle,
            Duration timeout,
            String expectedFinalizerKey)
    {
        requireText(expectedFinalizerKey, "expectedFinalizerKey");
        ManagedExecution execution = requireLive(handle);
        execution.assertFinalizerKey(expectedFinalizerKey);
        join(execution.thread, timeout);
        if (execution.thread.isAlive()) {
            throw new IllegalStateException(
                    "in-process agent did not finish before the deadline");
        }
        return finishEnded(execution, false);
    }

    /**
     * Revokes tools first, then interrupts and waits. An execution that ignores
     * the deadline remains activated and owns its writer lease.
     */
    public Cancellation cancel(ExecutionHandle handle, Duration timeout)
    {
        ManagedExecution execution = requireLive(handle);
        boolean alreadyStopped;
        boolean cancellationStarted;
        synchronized (execution) {
            if (execution.result != null) {
                return new Cancellation(
                        CancellationDisposition.ALREADY_FINISHED,
                        execution.result);
            }
            alreadyStopped = execution.stoppedAttempt != null;
            if (execution.completion == null
                    && !alreadyStopped
                    && !execution.terminalCommandAccepted
                    && execution.terminalCommandInProgress
                    && execution.thread.isAlive()) {
                execution.cancellationPending = true;
            }
            cancellationStarted = execution.completion == null
                    && !alreadyStopped
                    && !execution.terminalCommandAccepted
                    && !execution.terminalCommandInProgress
                    && execution.thread.isAlive();
            if (cancellationStarted) {
                execution.cancellationRequested = true;
                execution.revocationRequested = true;
                runtime.revokeInProcessWriterCapability(
                        execution.attempt.processAttemptId(),
                        execution.claim,
                        execution.fence);
                execution.thread.interrupt();
            }
        }

        if (!alreadyStopped) {
            join(execution.thread, timeout);
            if (execution.thread.isAlive()) {
                if (!cancellationStarted) {
                    throw new IllegalStateException(
                            "accepted terminal writer body did not finish before the deadline");
                }
                synchronized (execution) {
                    if (!execution.quarantined) {
                        runtime.quarantineInProcessWriterAttempt(
                                execution.attempt.processAttemptId(),
                                execution.claim,
                                execution.fence,
                                ProcessQuarantineReason
                                        .UNCOOPERATIVE_CANCELLATION);
                        execution.quarantined = true;
                    }
                }
                return new Cancellation(
                        CancellationDisposition.QUARANTINED, null);
            }
        }
        boolean cancellationEffective;
        synchronized (execution) {
            cancellationEffective = cancellationStarted
                    || execution.cancellationRequested;
        }
        AgentResult result = finishEnded(execution, cancellationEffective);
        CancellationDisposition disposition = result.terminalOutcome()
                == TerminalOutcome.CANCELED
                ? CancellationDisposition.CANCELED
                : CancellationDisposition.ALREADY_FINISHED;
        return new Cancellation(disposition, result);
    }

    /** Visible for runtime health/recovery decisions, never for model context. */
    public Optional<ExecutionHandle> liveExecution(String executionId)
    {
        requireText(executionId, "executionId");
        return Optional.ofNullable(LIVE_EXECUTIONS.get(executionId))
                .filter(execution -> execution.activated)
                .map(ManagedExecution::handle);
    }

    private AgentResult finishEnded(
            ManagedExecution execution,
            boolean cancellation)
    {
        synchronized (execution) {
            if (execution.result != null) {
                return execution.result;
            }
            if (execution.quarantined || execution.thread.isAlive()) {
                throw new IllegalStateException(
                        "in-process execution has no terminal stop proof");
            }
            execution.freezeFinalization(cancellation);
            Claim finalizationClaim = execution.claim;
            WriterFence finalizationFence = execution.fence;
            if (execution.stoppedAttempt == null) {
                execution.revocationRequested = true;
                runtime.revokeInProcessWriterCapability(
                        execution.attempt.processAttemptId(),
                        finalizationClaim,
                        finalizationFence);
                if (execution.activeToolCalls != 0) {
                    throw new IllegalStateException(
                            "writer stopped with an active tool effect");
                }
                TerminatedThreadWitness witness =
                        new TerminatedThreadWitness(execution.thread);
                execution.stoppedAttempt =
                        runtime.recordInProcessWriterStopped(
                                execution.attempt.processAttemptId(),
                                finalizationClaim,
                                finalizationFence,
                                witness,
                                execution.stopType,
                                execution.finalizationCompletion
                                        .terminalOutcome(),
                                execution.finalizationCompletion
                                        .finalContent(),
                                execution.finalizationCompletion.errorRef());
            }
            try {
                AgentResult callbackResult = requireNonNull(
                        execution.finalizer.finish(
                        execution.runId,
                        finalizationClaim,
                        finalizationFence,
                        execution.finalizationCompletion),
                        "stopped finalizer returned null");
                assertExactFinalizationResult(
                        callbackResult,
                        execution.runId,
                        execution.finalizationCompletion,
                        execution.stoppedAttempt.stopProofRef());
                execution.result = runtime.verifyFinalizedAgentResult(
                        execution.runId,
                        finalizationClaim,
                        finalizationFence,
                        callbackResult);
                LIVE_EXECUTIONS.remove(
                        execution.attempt.executionId(), execution);
                return execution.result;
            }
            catch (FlowRuntime.StaleClaimException expired) {
                LIVE_EXECUTIONS.remove(
                        execution.attempt.executionId(), execution);
                throw new StoppedAwaitingRecoveryException(
                        execution.handle(), expired);
            }
        }
    }

    private AgentResult finishWithRuntime(
            String runId,
            Claim claim,
            WriterFence fence,
            AgentCompletion completion)
    {
        return runtime.finishAgentRun(
                runId,
                claim,
                fence,
                completion.terminalOutcome(),
                completion.finalContent(),
                completion.errorRef());
    }

    private static void assertExactFinalizationResult(
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
                    "stopped finalizer changed immutable completion identity");
        }
    }

    private static ManagedExecution requireLive(ExecutionHandle handle)
    {
        requireNonNull(handle, "handle is null");
        ManagedExecution execution = LIVE_EXECUTIONS.get(handle.executionId());
        if (execution == null
                || !execution.activated
                || !execution.runId.equals(handle.runId())
                || !execution.attempt.processAttemptId()
                        .equals(handle.processAttemptId())) {
            throw new IllegalStateException(
                    "in-process execution is not owned by this live JVM");
        }
        return execution;
    }

    private static IllegalStateException unavailableExecution(
            AgentProcessAttempt attempt)
    {
        if (Long.valueOf(JVM_PID).equals(attempt.jvmPid())
                && JVM_STARTED_AT.equals(attempt.jvmStartedAt())) {
            return new IllegalStateException(
                    "live-JVM execution is not present in the supervisor registry");
        }
        return new IllegalStateException(
                "prior-JVM in-process execution cannot be proven stopped");
    }

    private static void join(Thread thread, Duration timeout)
    {
        requirePositive(timeout, "timeout");
        try {
            thread.join(timeout);
        }
        catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(
                    "interrupted while waiting for in-process agent", exception);
        }
    }

    private static void requireText(String value, String name)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }

    private static void requirePositive(Duration value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }

    private final class ManagedExecution
    {
        private final String runId;
        private volatile Claim claim;
        private volatile WriterFence fence;
        private final AgentProcessAttempt attempt;
        private final String finalizerKey;
        private final StoppedFinalizer finalizer;
        private final CountDownLatch dormantReady = new CountDownLatch(1);
        private final CountDownLatch startGate = new CountDownLatch(1);
        private volatile Thread thread;
        private volatile AgentCompletion completion;
        private volatile boolean activated;
        private volatile boolean abortBeforeActivation;
        private boolean revocationRequested;
        private int activeToolCalls;
        private boolean cancellationRequested;
        private boolean cancellationPending;
        private boolean terminalCommandInProgress;
        private boolean terminalCommandAccepted;
        private boolean quarantined;
        private boolean localCheckBoundaryUnproven;
        private AgentResult result;
        private AgentCompletion finalizationCompletion;
        private InProcessStopType stopType;
        private AgentProcessAttempt stoppedAttempt;

        private ManagedExecution(
                String runId,
                Claim claim,
                WriterFence fence,
                AgentProcessAttempt attempt,
                String finalizerKey,
                StoppedFinalizer finalizer)
        {
            this.runId = runId;
            this.claim = claim;
            this.fence = fence;
            this.attempt = attempt;
            this.finalizerKey = finalizerKey;
            this.finalizer = finalizer;
        }

        private void run(
                Function<WriterToolCapability, AgentCompletion> body)
        {
            dormantReady.countDown();
            awaitStartGate();
            if (abortBeforeActivation) {
                return;
            }
            if (!activated) {
                throw new IllegalStateException(
                        "writer body gate opened before durable activation");
            }
            try {
                completion = requireNonNull(
                        body.apply(new WriterToolCapability(this)),
                        "agent body returned null");
            }
            catch (RuntimeException | Error ignored) {
                completion = new AgentCompletion(
                        TerminalOutcome.FAILED,
                        null,
                        "IN_PROCESS_EXECUTION_FAILED");
            }
        }

        private void awaitDormant()
        {
            try {
                if (!dormantReady.await(
                        DORMANT_START_TIMEOUT.toMillis(),
                        TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException(
                            "writer thread did not reach its dormant gate");
                }
            }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "interrupted before writer activation", exception);
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

        private ExecutionHandle handle()
        {
            return new ExecutionHandle(
                    runId,
                    attempt.processAttemptId(),
                    attempt.executionId());
        }

        private <T> T callTool(Supplier<T> effect)
        {
            requireNonNull(effect, "effect is null");
            synchronized (this) {
                if (Thread.currentThread() != thread) {
                    throw new FlowRuntime.StaleCapabilityException(
                            "writer tools require the owned writer thread");
                }
                if (revocationRequested) {
                    throw new FlowRuntime.StaleCapabilityException(
                            "writer tool capability is revoked");
                }
                if (localCheckBoundaryUnproven) {
                    throw new FlowRuntime.StaleCapabilityException(
                            "local-check process boundary is unresolved");
                }
                runtime.assertInProcessWriterToolCapability(
                        runId,
                        claim,
                        fence,
                        attempt.capabilityId());
                activeToolCalls++;
            }
            try {
                return effect.get();
            }
            finally {
                synchronized (this) {
                    activeToolCalls--;
                    notifyAll();
                }
            }
        }

        private <T> T callTerminalTool(Supplier<T> effect)
        {
            requireNonNull(effect, "effect is null");
            synchronized (this) {
                if (Thread.currentThread() != thread
                        || revocationRequested
                        || localCheckBoundaryUnproven
                        || terminalCommandInProgress
                        || terminalCommandAccepted) {
                    throw new FlowRuntime.StaleCapabilityException(
                            "writer terminal capability is revoked");
                }
                runtime.assertInProcessWriterToolCapability(
                        runId,
                        claim,
                        fence,
                        attempt.capabilityId());
                terminalCommandInProgress = true;
                activeToolCalls++;
            }
            boolean accepted = false;
            boolean revokeAfterFailure = false;
            try {
                T result = effect.get();
                synchronized (this) {
                    terminalCommandAccepted = true;
                    cancellationPending = false;
                    revocationRequested = true;
                    accepted = true;
                }
                return result;
            }
            finally {
                synchronized (this) {
                    if (!accepted) {
                        terminalCommandInProgress = false;
                        if (cancellationPending) {
                            cancellationPending = false;
                            cancellationRequested = true;
                            revocationRequested = true;
                            revokeAfterFailure = true;
                        }
                    }
                    activeToolCalls--;
                    notifyAll();
                }
                if (revokeAfterFailure) {
                    try {
                        runtime.revokeInProcessWriterCapability(
                                attempt.processAttemptId(), claim, fence);
                    }
                    finally {
                        thread.interrupt();
                    }
                }
            }
        }

        private List<LocalCheckRun> runChecks(
                LocalChecks localChecks,
                Path programOwnedRepositoryRoot,
                String profileName)
        {
            requireNonNull(localChecks, "localChecks is null");
            requireNonNull(programOwnedRepositoryRoot,
                    "programOwnedRepositoryRoot is null");
            return callTool(() -> {
                LocalChecks.PreparedLocalCheckBatch batch =
                        localChecks.prepareBatch(runId, profileName);
                renewFor(batch.requiredAuthorityDuration());
                try {
                    return localChecks.runAndRecord(
                            batch,
                            claim,
                            fence,
                            programOwnedRepositoryRoot,
                            () -> {
                                synchronized (this) {
                                    localCheckBoundaryUnproven = true;
                                }
                            });
                }
                catch (LocalChecks.ProcessBoundaryUnprovenException failure) {
                    synchronized (this) {
                        localCheckBoundaryUnproven = true;
                    }
                    throw failure;
                }
            });
        }

        private ChangeSetRevision adoptChangeSet(
                Path programOwnedRepositoryRoot,
                String expectedChangeSetRevisionId)
        {
            requireNonNull(programOwnedRepositoryRoot,
                    "programOwnedRepositoryRoot is null");
            requireText(expectedChangeSetRevisionId,
                    "expectedChangeSetRevisionId");
            return callTool(() -> runtime.adoptChangeSet(
                    claim,
                    fence,
                    programOwnedRepositoryRoot,
                    expectedChangeSetRevisionId));
        }

        private void renewFor(Duration requiredTtl)
        {
            Claim previousClaim;
            WriterFence previousFence;
            synchronized (this) {
                previousClaim = claim;
                previousFence = fence;
            }
            Claim renewedClaim = runtime.renewClaim(
                    previousClaim, requiredTtl);
            WriterFence renewedFence = runtime.renewWriterLease(
                    renewedClaim, previousFence, requiredTtl);
            synchronized (this) {
                if (!sameClaimAuthority(claim, renewedClaim)
                        || !sameFenceAuthority(fence, renewedFence)) {
                    throw new IllegalStateException(
                            "writer authority changed during local-check renewal");
                }
                claim = laterClaim(claim, renewedClaim);
                fence = laterFence(fence, renewedFence);
            }
        }

        private ReviewerRequest spawnAdversarialReviewer(
                Path programOwnedRepositoryRoot,
                String expectedChangeSetRevisionId,
                CiFixReviewOrigin origin,
                LocalChecks.ReviewerEvidence reviewerEvidence)
        {
            synchronized (this) {
                if (Thread.currentThread() != thread) {
                    throw new FlowRuntime.StaleCapabilityException(
                            "terminal tool requires the owned writer thread");
                }
            }
            return callTerminalTool(() -> {
                FlowRuntime.PreparedReviewerRequest prepared =
                        runtime.prepareReviewerRequest(
                                runId,
                                claim,
                                fence,
                                programOwnedRepositoryRoot,
                                expectedChangeSetRevisionId,
                                origin,
                                reviewerEvidence);
                return runtime.reserveReviewerRequest(
                        runId,
                        claim,
                        fence,
                        attempt.processAttemptId(),
                        attempt.capabilityId(),
                        prepared);
            });
        }

        private ReviewerRequest replayAdversarialReviewer(
                Path programOwnedRepositoryRoot,
                String expectedChangeSetRevisionId,
                CiFixReviewOrigin origin,
                String localCheckPolicyRevisionId,
                List<String> checkRunRefs)
        {
            synchronized (this) {
                if (Thread.currentThread() != thread) {
                    throw new FlowRuntime.StaleCapabilityException(
                            "terminal tool requires the owned writer thread");
                }
            }
            return callTerminalTool(() -> runtime.replayReviewerRequest(
                    runId,
                    programOwnedRepositoryRoot,
                    expectedChangeSetRevisionId,
                    origin,
                    localCheckPolicyRevisionId,
                    checkRunRefs));
        }

        private ReadyForReviewAcceptance readyForReview(
                UserGates userGates,
                Path repositoryRoot,
                ReviewerRequest reviewerRequest,
                AgentResult reviewerResult,
                CiFixReviewOrigin origin)
        {
            requireNonNull(userGates, "userGates is null");
            synchronized (this) {
                if (Thread.currentThread() != thread) {
                    throw new FlowRuntime.StaleCapabilityException(
                            "terminal tool requires the owned writer thread");
                }
            }
            if (runtime.readyForReviewRequestForRun(runId).isPresent()) {
                return callTerminalTool(
                        () -> userGates.replayReadyRequest(runId));
            }
            return callTerminalTool(() -> {
                PreparedReadyRequest prepared = userGates.prepareReadyRequest(
                        runId,
                        claim,
                        fence,
                        repositoryRoot,
                        reviewerRequest,
                        reviewerResult,
                        origin);
                return userGates.reserveReadyRequest(
                        runId,
                        claim,
                        fence,
                        attempt.processAttemptId(),
                        attempt.capabilityId(),
                        prepared);
            });
        }

        private synchronized void adoptCurrentOwner(
                String expectedRunId,
                Claim expectedClaim,
                WriterFence expectedFence,
                String expectedFinalizerKey)
        {
            if (!runId.equals(expectedRunId)
                    || !sameClaimAuthority(claim, expectedClaim)
                    || !sameFenceAuthority(fence, expectedFence)
                    || !finalizerKey.equals(expectedFinalizerKey)) {
                throw new IllegalStateException(
                        "live execution belongs to another owner");
            }
            claim = laterClaim(claim, expectedClaim);
            fence = laterFence(fence, expectedFence);
        }

        private synchronized void assertFinalizerKey(String expectedKey)
        {
            if (!finalizerKey.equals(expectedKey)) {
                throw new IllegalStateException(
                        "stopped execution belongs to another finalizer");
            }
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
                            completion == null ? null : completion.finalContent(),
                            "IN_PROCESS_CANCELED")
                    : requireNonNull(
                            completion, "writer ended without a completion");
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

    private static boolean sameFenceAuthority(
            WriterFence first, WriterFence second)
    {
        return first.taskId().equals(second.taskId())
                && first.operationId().equals(second.operationId())
                && first.taskEpoch() == second.taskEpoch()
                && first.holderKind() == second.holderKind()
                && first.fencingToken() == second.fencingToken()
                && first.claimGeneration() == second.claimGeneration()
                && first.claimTokenDigest().equals(second.claimTokenDigest())
                && first.headSha().equals(second.headSha())
                && first.treeDigest().equals(second.treeDigest())
                && first.snapshotEvidenceRef()
                        .equals(second.snapshotEvidenceRef());
    }

    private static Claim laterClaim(Claim first, Claim second)
    {
        return first.expiresAt().isAfter(second.expiresAt()) ? first : second;
    }

    private static WriterFence laterFence(
            WriterFence first, WriterFence second)
    {
        return first.expiresAt().isAfter(second.expiresAt()) ? first : second;
    }
}
