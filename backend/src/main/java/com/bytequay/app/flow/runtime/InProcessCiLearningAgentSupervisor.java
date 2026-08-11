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

import com.bytequay.app.flow.runtime.FlowRuntime.CiLearningStart;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentProcessAttempt;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentResult;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.InProcessStopType;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ProcessAttemptState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TerminalOutcome;

import java.lang.management.ManagementFactory;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

/** Concrete in-JVM owner for one isolated read-only CI-learning turn. */
public final class InProcessCiLearningAgentSupervisor
{
    private static final long JVM_PID = ProcessHandle.current().pid();
    private static final Instant JVM_STARTED_AT = Instant.ofEpochMilli(
            ManagementFactory.getRuntimeMXBean().getStartTime());
    private static final Duration START_TIMEOUT = Duration.ofSeconds(5);
    private static final Object LAUNCH_LOCK = new Object();
    private static final ConcurrentMap<String, ManagedExecution> LIVE =
            new ConcurrentHashMap<>();

    private final FlowRuntime runtime;

    public InProcessCiLearningAgentSupervisor(FlowRuntime runtime)
    {
        this.runtime = requireNonNull(runtime, "runtime is null");
    }

    /** Program-owned exact evidence/log/finalization bridge. */
    public interface LearningOwner
    {
        String readRepairEvidence(String subjectId);

        String readLog(String subjectId, String logRef, long offset,
                int maxBytes);

        String saveLesson(
                CiLearningStart start,
                Claim claim,
                String processAttemptId,
                String title,
                String markdown);

        AgentResult finish(
                CiLearningStart start,
                Claim claim,
                AgentCompletion completion);
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
                        "non-completed learner requires errorRef");
            }
        }
    }

    /** The only model-authored semantic output; provenance is program-bound. */
    public record LessonProposal(String title, String markdown)
    {
        public LessonProposal
        {
            requireText(title, "title");
            requireText(markdown, "markdown");
            if (title.length() > 160 || markdown.length() > 16_384) {
                throw new IllegalArgumentException(
                        "CI lesson exceeds its bounded size");
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

    /** Zero-choice read-only tools plus one terminal lesson proposal. */
    public static final class CiLearningToolCapability
    {
        private final ManagedExecution execution;

        private CiLearningToolCapability(ManagedExecution execution)
        {
            this.execution = execution;
        }

        public String readCiRepairEvidence()
        {
            return execution.readEvidence();
        }

        public String readCiLog(String logRef, long offset, int maxBytes)
        {
            return execution.readLog(logRef, offset, maxBytes);
        }

        public void saveCiLesson(String title, String markdown)
        {
            execution.save(new LessonProposal(title, markdown));
        }

        @Override
        public String toString()
        {
            return "CiLearningToolCapability[opaque-read-only-one-save]";
        }
    }

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
                        "CI learner thread is not terminated");
            }
            this.jvmPid = JVM_PID;
            this.jvmStartedAt = JVM_STARTED_AT;
        }

        Thread thread() { return thread; }
        long jvmPid() { return jvmPid; }
        Instant jvmStartedAt() { return jvmStartedAt; }
    }

    public ExecutionHandle launch(
            CiLearningStart start,
            Claim claim,
            LearningOwner owner,
            Function<CiLearningToolCapability, AgentCompletion> body)
    {
        requireNonNull(start, "start is null");
        requireNonNull(claim, "claim is null");
        requireNonNull(owner, "owner is null");
        requireNonNull(body, "body is null");
        synchronized (LAUNCH_LOCK) {
            AgentProcessAttempt attempt =
                    runtime.reserveInProcessCiLearningAttempt(
                            start.run().runId(), claim);
            ManagedExecution live = LIVE.get(attempt.executionId());
            if (live != null) {
                live.adopt(start, claim, owner);
                return live.handle();
            }
            if (attempt.state() != ProcessAttemptState.RESERVED) {
                throw new IllegalStateException(
                        "prior CI learner cannot be proven stopped");
            }
            ManagedExecution execution = new ManagedExecution(
                    start, claim, owner, attempt);
            Thread thread = new Thread(
                    () -> execution.run(body),
                    "flow-ci-learner-" + attempt.executionId());
            thread.setDaemon(true);
            execution.thread = thread;
            ManagedExecution raced = LIVE.putIfAbsent(
                    attempt.executionId(), execution);
            if (raced != null) {
                raced.adopt(start, claim, owner);
                return raced.handle();
            }
            try {
                thread.start();
                execution.awaitDormant();
                runtime.activateInProcessCiLearningAttempt(
                        attempt.processAttemptId(), claim, JVM_PID,
                        JVM_STARTED_AT, thread.threadId(), thread.getName());
                execution.activate();
                return execution.handle();
            }
            catch (RuntimeException | Error failure) {
                execution.abort();
                join(thread, START_TIMEOUT);
                LIVE.remove(attempt.executionId(), execution);
                throw failure;
            }
        }
    }

    public AgentResult awaitAndFinish(
            ExecutionHandle handle, Duration timeout)
    {
        ManagedExecution execution = requireLive(handle);
        join(execution.thread, timeout);
        if (execution.thread.isAlive()) {
            throw new IllegalStateException(
                    "CI learner did not finish before the deadline");
        }
        synchronized (execution) {
            if (execution.result != null) {
                return execution.result;
            }
            execution.revoked = true;
            runtime.revokeInProcessCiLearningCapability(
                    execution.attempt.processAttemptId(), execution.claim);
            AgentCompletion completion = requireNonNull(
                    execution.completion,
                    "CI learner ended without completion");
            AgentProcessAttempt stopped =
                    runtime.recordInProcessCiLearningStopped(
                            execution.attempt.processAttemptId(),
                            execution.claim,
                            new TerminatedThreadWitness(execution.thread),
                            InProcessStopType.NORMAL_RETURN,
                            completion.terminalOutcome(),
                            completion.finalContent(),
                            completion.errorRef());
            execution.result = execution.owner.finish(
                    execution.start,
                    execution.claim,
                    completion);
            if (!execution.result.stopProofRef().equals(
                    stopped.stopProofRef())) {
                throw new IllegalStateException(
                        "CI learner finalizer changed stop proof");
            }
            LIVE.remove(execution.attempt.executionId(), execution);
            return execution.result;
        }
    }

    /** Revokes first, then cooperatively stops and finalizes one learner. */
    public AgentResult cancel(ExecutionHandle handle, Duration timeout)
    {
        ManagedExecution execution = requireLive(handle);
        boolean cancellationStarted;
        synchronized (execution) {
            if (execution.result != null) {
                return execution.result;
            }
            cancellationStarted = execution.completion == null
                    && !execution.terminalCommandAccepted
                    && execution.thread.isAlive();
            if (cancellationStarted) {
                execution.revoked = true;
                runtime.revokeInProcessCiLearningCapability(
                        execution.attempt.processAttemptId(), execution.claim);
                execution.thread.interrupt();
            }
        }
        join(execution.thread, timeout);
        if (execution.thread.isAlive()) {
            throw new IllegalStateException(
                    "CI learner ignored cooperative cancellation");
        }
        return awaitAndFinish(handle, Duration.ofMillis(1));
    }

    private static ManagedExecution requireLive(ExecutionHandle handle)
    {
        requireNonNull(handle, "handle is null");
        ManagedExecution execution = LIVE.get(handle.executionId());
        if (execution == null
                || !execution.activated
                || !execution.start.run().runId().equals(handle.runId())
                || !execution.attempt.processAttemptId().equals(
                        handle.processAttemptId())) {
            throw new IllegalStateException(
                    "CI learner is not owned by this JVM");
        }
        return execution;
    }

    private final class ManagedExecution
    {
        private final CiLearningStart start;
        private volatile Claim claim;
        private volatile LearningOwner owner;
        private final AgentProcessAttempt attempt;
        private final CountDownLatch dormant = new CountDownLatch(1);
        private final CountDownLatch gate = new CountDownLatch(1);
        private volatile Thread thread;
        private volatile boolean activated;
        private volatile boolean aborted;
        private boolean revoked;
        private boolean terminalCommandAccepted;
        private AgentCompletion completion;
        private AgentResult result;

        private ManagedExecution(
                CiLearningStart start,
                Claim claim,
                LearningOwner owner,
                AgentProcessAttempt attempt)
        {
            this.start = start;
            this.claim = claim;
            this.owner = owner;
            this.attempt = attempt;
        }

        private void run(
                Function<CiLearningToolCapability, AgentCompletion> body)
        {
            dormant.countDown();
            awaitGate();
            if (aborted) {
                return;
            }
            try {
                completion = requireNonNull(
                        body.apply(new CiLearningToolCapability(this)),
                        "CI learner body returned null");
            }
            catch (RuntimeException | Error ignored) {
                completion = new AgentCompletion(
                        TerminalOutcome.FAILED, null,
                        "IN_PROCESS_CI_LEARNER_FAILED");
            }
        }

        private synchronized String readEvidence()
        {
            assertUsable();
            return owner.readRepairEvidence(start.run().inputRef());
        }

        private synchronized String readLog(
                String logRef, long offset, int maxBytes)
        {
            assertUsable();
            requireText(logRef, "logRef");
            if (offset < 0 || maxBytes < 1 || maxBytes > 32_768) {
                throw new IllegalArgumentException(
                        "CI log window is outside its bound");
            }
            return owner.readLog(
                    start.run().inputRef(), logRef, offset, maxBytes);
        }

        private synchronized void save(LessonProposal proposal)
        {
            assertUsable();
            owner.saveLesson(
                    start, claim, attempt.processAttemptId(),
                    proposal.title(), proposal.markdown());
            terminalCommandAccepted = true;
            revoked = true;
        }

        private void assertUsable()
        {
            if (Thread.currentThread() != thread || revoked) {
                throw new FlowRuntime.StaleCapabilityException(
                        "CI learner capability is revoked");
            }
            runtime.assertInProcessCiLearningToolCapability(
                    start.run().runId(), claim, attempt.capabilityId());
        }

        private synchronized void adopt(
                CiLearningStart current,
                Claim currentClaim,
                LearningOwner currentOwner)
        {
            if (!start.equals(current) || !sameClaim(claim, currentClaim)) {
                throw new IllegalStateException(
                        "live CI learner belongs to another owner");
            }
            claim = currentClaim;
            owner = currentOwner;
        }

        private void awaitDormant()
        {
            try {
                if (!dormant.await(
                        START_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                    throw new IllegalStateException(
                            "CI learner did not reach its dormant gate");
                }
            }
            catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(
                        "interrupted before CI learner activation", exception);
            }
        }

        private void awaitGate()
        {
            boolean interrupted = false;
            while (true) {
                try {
                    gate.await();
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

        private void activate()
        {
            activated = true;
            gate.countDown();
        }

        private void abort()
        {
            aborted = true;
            gate.countDown();
        }

        private ExecutionHandle handle()
        {
            return new ExecutionHandle(
                    start.run().runId(), attempt.processAttemptId(),
                    attempt.executionId());
        }
    }

    private static boolean sameClaim(Claim left, Claim right)
    {
        return left.operationId().equals(right.operationId())
                && Objects.equals(left.taskId(), right.taskId())
                && left.kind() == right.kind()
                && left.generation() == right.generation()
                && left.claimToken().equals(right.claimToken())
                && left.workerId().equals(right.workerId());
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
                    "interrupted while waiting for CI learner", exception);
        }
    }

    private static void requireText(String value, String name)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }
}
