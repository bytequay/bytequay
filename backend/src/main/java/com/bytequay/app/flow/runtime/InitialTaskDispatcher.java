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

import com.bytequay.app.flow.github.GitHubInitialRepositoryObserver;
import com.bytequay.app.flow.runtime.FlowRuntime.ReviewerStart;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ExpiredClaim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Operation;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ProcessAttemptState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import static java.util.Objects.requireNonNull;

/** One bounded lane for only ordinary INITIAL Task and reviewer work. */
public final class InitialTaskDispatcher
        implements AutoCloseable
{
    private static final Logger log = LoggerFactory.getLogger(
            InitialTaskDispatcher.class);

    public record Config(
            String workerId,
            Duration claimTtl,
            Duration pollInterval,
            Duration bodyTimeout,
            Duration shutdownTimeout,
            int capacity)
    {
        public Config
        {
            requireText(workerId, "workerId");
            requirePositive(claimTtl, "claimTtl");
            requirePositive(pollInterval, "pollInterval");
            requirePositive(bodyTimeout, "bodyTimeout");
            requirePositive(shutdownTimeout, "shutdownTimeout");
            if (capacity < 1 || claimTtl.compareTo(bodyTimeout) <= 0
                    || bodyTimeout.compareTo(shutdownTimeout) <= 0) {
                throw new IllegalArgumentException(
                        "INITIAL dispatcher bounds are inconsistent");
            }
        }
    }

    private final FlowRuntime runtime;
    private final InitialTaskCoordinator coordinator;
    private final InProcessWriterAgentSupervisor writers;
    private final InProcessReviewerAgentSupervisor reviewers;
    private final NewFlowAgentBodies bodies;
    private final GitHubInitialRepositoryObserver repositories;
    private final Config config;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Object lifecycle = new Object();
    private final ReentrantLock wakeLock = new ReentrantLock();
    private final Condition wakeSignal = wakeLock.newCondition();
    private final AtomicReference<Runnable> activeCancellation =
            new AtomicReference<>();
    private final AtomicReference<String> activeOperationId =
            new AtomicReference<>();
    private volatile Thread thread;
    private volatile UpstreamSyncCoordinator upstream;

    public InitialTaskDispatcher(
            FlowRuntime runtime,
            InitialTaskCoordinator coordinator,
            InProcessWriterAgentSupervisor writers,
            InProcessReviewerAgentSupervisor reviewers,
            NewFlowAgentBodies bodies,
            GitHubInitialRepositoryObserver repositories,
            Config config)
    {
        this.runtime = requireNonNull(runtime, "runtime is null");
        this.coordinator = requireNonNull(coordinator, "coordinator is null");
        this.writers = requireNonNull(writers, "writers is null");
        this.reviewers = requireNonNull(reviewers, "reviewers is null");
        this.bodies = requireNonNull(bodies, "bodies is null");
        this.repositories = requireNonNull(
                repositories, "repositories is null");
        this.config = requireNonNull(config, "config is null");
    }

    /**
     * Installs upstream synchronization's replacement for the INITIAL body.
     *
     * <p>Injected rather than constructed because upstream synchronization is
     * composed separately, and set before {@code start()} so a Task already
     * mid-range at restart can never be handed the ordinary body. Absent
     * everywhere upstream synchronization is not composed, which is the
     * unchanged default.
     */
    @Autowired(required = false)
    public void bindUpstreamSync(UpstreamSyncCoordinator coordinator)
    {
        this.upstream = requireNonNull(coordinator, "coordinator is null");
    }

    public void start()
    {
        if (closed.get()) {
            throw new IllegalStateException("INITIAL dispatcher is closed");
        }
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Thread worker = Thread.ofPlatform().daemon(true)
                .name(config.workerId()).unstarted(this::run);
        thread = worker;
        worker.start();
    }

    public void wake()
    {
        wakeLock.lock();
        try {
            wakeSignal.signalAll();
        }
        finally {
            wakeLock.unlock();
        }
    }

    /** Cancels and finalizes this lane's exact active operation for a Task. */
    boolean cancelActiveTask(String taskId)
    {
        requireText(taskId, "taskId");
        String operationId = activeOperationId.get();
        if (operationId == null
                || runtime.operation(operationId)
                        .filter(operation -> taskId.equals(operation.taskId()))
                        .isEmpty()) {
            return false;
        }
        Runnable cancellation;
        synchronized (lifecycle) {
            if (!operationId.equals(activeOperationId.get())) {
                return false;
            }
            cancellation = activeCancellation.get();
        }
        if (cancellation == null) {
            return false;
        }
        cancellation.run();
        activeCancellation.compareAndSet(cancellation, null);
        activeOperationId.compareAndSet(operationId, null);
        wake();
        return true;
    }

    @Override
    public void close()
    {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Runnable cancellation;
        synchronized (lifecycle) {
            running.set(false);
            cancellation = activeCancellation.get();
        }
        if (cancellation != null) {
            try {
                cancellation.run();
                activeCancellation.compareAndSet(cancellation, null);
                activeOperationId.set(null);
            }
            catch (RuntimeException failure) {
                log.warn("INITIAL agent cancellation did not settle cleanly",
                        failure);
            }
        }
        wake();
        Thread worker = thread;
        if (worker == null || worker == Thread.currentThread()) {
            return;
        }
        worker.interrupt();
        try {
            worker.join(config.shutdownTimeout().plusSeconds(1).toMillis());
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (worker.isAlive()) {
            throw new IllegalStateException(
                    "INITIAL dispatcher ignored bounded shutdown");
        }
    }

    boolean dispatchOnce()
    {
        if (retryActiveCancellation()) {
            return true;
        }
        boolean changed = recoverExpired();
        Optional<Claim> claim = runtime.claimNextInitialTask(
                config.workerId(), config.claimTtl(), config.capacity());
        if (claim.isEmpty()) {
            return changed;
        }
        try {
            dispatch(claim.orElseThrow());
        }
        catch (RuntimeException failure) {
            log.warn("INITIAL Task owner failed; durable recovery owns retry",
                    failure);
        }
        return true;
    }

    private void dispatch(Claim claim)
    {
        switch (claim.kind()) {
            case RECONCILE_TASK -> runtime.selectNextInitial(claim);
            case RUN_TASK_TURN -> dispatchTask(claim);
            case RUN_REVIEWER -> dispatchReviewer(claim);
            default -> throw new IllegalArgumentException(
                    "operation is not owned by INITIAL Task lane");
        }
    }

    private void dispatchTask(Claim claim)
    {
        UpstreamSyncCoordinator upstreamSync = upstream;
        boolean upstreamRange = upstreamSync != null
                && upstreamSync.owns(claim.taskId());
        InitialTaskCoordinator.TaskBinding binding =
                coordinator.beginTask(
                        claim, config.claimTtl(), upstreamRange);
        // The sealed launch must name the program whose prompt and tools this
        // body actually uses, so the range is decided before the bind, not
        // after it.
        var launch = binding.reviewContinuation()
                ? bodies.bindInitialReviewResult(binding.run())
                : upstreamRange
                        ? bodies.bindUpstreamPickRepair(binding.run())
                        : bodies.bindInitialTask(binding.run());
        InProcessWriterAgentSupervisor.ExecutionHandle handle;
        synchronized (lifecycle) {
            if (!running.get()) {
                throw new IllegalStateException(
                        "INITIAL dispatcher is stopping");
            }
            handle = coordinator.launchTask(
                    writers, binding, claim,
                    () -> repositories.observe(binding.run().runId()),
                    capability -> {
                        Path worktree = Path.of(
                                runtime.task(claim.taskId()).orElseThrow()
                                        .worktreePath());
                        // An upstream range replaces only what happens between
                        // the Task's start and its review request; the gate,
                        // the reviewer and publication stay the ordinary ones.
                        return upstreamRange
                                ? upstreamSync.runTurn(
                                        launch, worktree, capability,
                                        claim.taskId(),
                                        binding.reviewContinuation())
                                : bodies.initialTask(
                                        launch, worktree, capability,
                                        binding.reviewContinuation());
                    });
            registerCancellation(
                    claim.operationId(),
                    () -> writers.cancel(
                            handle, config.shutdownTimeout()));
        }
        awaitRegistered(() -> coordinator.awaitTask(
                writers, binding, handle, config.bodyTimeout()));
        if (upstreamRange) {
            upstreamSync.finishCanceledTask(claim.taskId());
        }
    }

    private void dispatchReviewer(Claim claim)
    {
        Operation operation = runtime.operation(claim.operationId())
                .orElseThrow();
        ReviewerStart start = coordinator.beginReviewer(
                operation.ownerId(), claim);
        var launch = bodies.bindReviewer(start.run());
        InProcessReviewerAgentSupervisor.ExecutionHandle handle;
        synchronized (lifecycle) {
            if (!running.get()) {
                throw new IllegalStateException(
                        "INITIAL dispatcher is stopping");
            }
            handle = coordinator.launchReviewer(
                    reviewers, start, claim,
                    capability -> bodies.reviewer(launch, capability));
            registerCancellation(
                    claim.operationId(),
                    () -> reviewers.cancel(
                            handle, config.shutdownTimeout()));
        }
        awaitRegistered(() -> coordinator.awaitReviewer(
                reviewers, handle, config.bodyTimeout()));
    }

    private void registerCancellation(
            String operationId, Runnable cancellation)
    {
        if (!activeCancellation.compareAndSet(null, cancellation)
                || !activeOperationId.compareAndSet(null, operationId)) {
            activeCancellation.compareAndSet(cancellation, null);
            throw new IllegalStateException(
                    "INITIAL dispatcher already owns an active agent");
        }
    }

    private void awaitRegistered(Runnable await)
    {
        Runnable cancellation = activeCancellation.get();
        boolean settled = false;
        try {
            await.run();
            settled = true;
        }
        catch (RuntimeException failure) {
            try {
                if (cancellation != null) {
                    cancellation.run();
                }
                settled = true;
            }
            catch (RuntimeException cancellationFailure) {
                failure.addSuppressed(cancellationFailure);
            }
            if (durableStoppedRecoveryRequired(failure)) {
                settled = true;
            }
            throw failure;
        }
        finally {
            if (settled) {
                activeCancellation.compareAndSet(cancellation, null);
                activeOperationId.set(null);
            }
        }
    }

    private boolean retryActiveCancellation()
    {
        Runnable cancellation = activeCancellation.get();
        if (cancellation == null) {
            return false;
        }
        try {
            cancellation.run();
        }
        catch (RuntimeException failure) {
            if (!durableStoppedRecoveryRequired(failure)) {
                throw failure;
            }
        }
        activeCancellation.compareAndSet(cancellation, null);
        activeOperationId.set(null);
        return true;
    }

    private boolean durableStoppedRecoveryRequired(Throwable failure)
    {
        String operationId = activeOperationId.get();
        return containsStaleClaim(failure, new HashSet<>())
                && operationId != null
                && runtime.expiredClaims().stream().anyMatch(expired ->
                        expired.operationId().equals(operationId)
                                && expired.processAttemptState()
                                    == ProcessAttemptState.STOPPED);
    }

    private static boolean containsStaleClaim(
            Throwable failure, Set<Throwable> visited)
    {
        if (failure == null || !visited.add(failure)) {
            return false;
        }
        if (failure instanceof FlowRuntime.StaleClaimException
                || failure instanceof InProcessWriterAgentSupervisor
                        .StoppedAwaitingRecoveryException
                || containsStaleClaim(failure.getCause(), visited)) {
            return true;
        }
        for (Throwable suppressed : failure.getSuppressed()) {
            if (containsStaleClaim(suppressed, visited)) {
                return true;
            }
        }
        return false;
    }

    private boolean recoverExpired()
    {
        boolean changed = false;
        for (ExpiredClaim expired : runtime.expiredClaims()) {
            try {
                Operation operation = runtime.operation(expired.operationId())
                        .orElseThrow();
                if (!owns(operation)) {
                    continue;
                }
                if (expired.processAttemptState()
                        == ProcessAttemptState.STOPPED) {
                    if (operation.kind() == OperationKind.RUN_TASK_TURN) {
                        coordinator.recoverExpiredStoppedTask(
                                operation.operationId(), expired.generation(),
                                config.claimTtl());
                    }
                    else if (operation.kind() == OperationKind.RUN_REVIEWER) {
                        coordinator.recoverExpiredStoppedReviewer(
                                operation.operationId(), expired.generation(),
                                config.claimTtl());
                    }
                    else {
                        throw new IllegalStateException(
                                "INITIAL reconciliation cannot be STOPPED");
                    }
                    changed = true;
                    continue;
                }
                boolean recovered = runtime.recoverExpiredClaim(
                        expired.operationId(), expired.generation());
                if (recovered && runtime.operation(expired.operationId())
                        .filter(value -> value.state()
                                == OperationState.RETRYABLE)
                        .isPresent()) {
                    runtime.redriveRetryable(expired.operationId());
                }
                changed |= recovered;
            }
            catch (RuntimeException failure) {
                log.warn("INITIAL recovery failed; polling will retry",
                        failure);
            }
        }
        return changed;
    }

    private boolean owns(Operation operation)
    {
        return runtime.ownsCurrentInitialDispatch(operation);
    }

    private void run()
    {
        while (running.get()) {
            boolean changed;
            try {
                changed = dispatchOnce();
            }
            catch (RuntimeException failure) {
                log.warn("INITIAL dispatcher poll failed; retrying", failure);
                changed = false;
            }
            if (!changed) {
                awaitWake();
            }
        }
    }

    private void awaitWake()
    {
        wakeLock.lock();
        try {
            if (running.get()) {
                wakeSignal.awaitNanos(config.pollInterval().toNanos());
            }
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        finally {
            wakeLock.unlock();
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
}
