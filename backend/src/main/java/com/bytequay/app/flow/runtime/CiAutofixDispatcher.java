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

import com.bytequay.app.flow.ci.CiCleanupCoordinator;
import com.bytequay.app.flow.ci.CiCleanupCoordinator.CleanupBinding;
import com.bytequay.app.flow.ci.CiFixReviewCoordinator;
import com.bytequay.app.flow.ci.CiLearningCoordinator;
import com.bytequay.app.flow.ci.CiRepairCoordinator;
import com.bytequay.app.flow.ci.CiRepairCoordinator.RepairLaunchBinding;
import com.bytequay.app.flow.runtime.FlowRuntime.CiLearningStart;
import com.bytequay.app.flow.runtime.FlowRuntime.ReviewerStart;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ExpiredClaim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.GateIntent;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Operation;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ProcessAttemptState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/** One bounded production lane for only the greenfield CI-autofix owners. */
public final class CiAutofixDispatcher
        implements AutoCloseable
{
    private static final Logger log = LoggerFactory.getLogger(
            CiAutofixDispatcher.class);

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
                        "CI dispatcher bounds are inconsistent");
            }
        }
    }

    private final FlowRuntime runtime;
    private final CiRepairCoordinator repairs;
    private final CiCleanupCoordinator cleanups;
    private final CiLearningCoordinator learning;
    private final CiFixReviewCoordinator reviewCoordinator;
    private final InProcessWriterAgentSupervisor writers;
    private final InProcessReviewerAgentSupervisor reviewers;
    private final InProcessCiLearningAgentSupervisor learners;
    private final NewFlowAgentBodies bodies;
    private final Config config;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<Runnable> activeCancellation =
            new AtomicReference<>();
    private final AtomicReference<String> activeOperationId =
            new AtomicReference<>();
    private final Object lifecycleLock = new Object();
    private final ReentrantLock wakeLock = new ReentrantLock();
    private final Condition wakeSignal = wakeLock.newCondition();
    private volatile Thread thread;

    public CiAutofixDispatcher(
            FlowRuntime runtime,
            CiRepairCoordinator repairs,
            CiCleanupCoordinator cleanups,
            CiLearningCoordinator learning,
            CiFixReviewCoordinator reviewCoordinator,
            InProcessWriterAgentSupervisor writers,
            InProcessReviewerAgentSupervisor reviewers,
            InProcessCiLearningAgentSupervisor learners,
            NewFlowAgentBodies bodies,
            Config config)
    {
        this.runtime = requireNonNull(runtime, "runtime is null");
        this.repairs = requireNonNull(repairs, "repairs is null");
        this.cleanups = requireNonNull(cleanups, "cleanups is null");
        this.learning = requireNonNull(learning, "learning is null");
        this.reviewCoordinator = requireNonNull(
                reviewCoordinator, "reviewCoordinator is null");
        this.writers = requireNonNull(writers, "writers is null");
        this.reviewers = requireNonNull(reviewers, "reviewers is null");
        this.learners = requireNonNull(learners, "learners is null");
        this.bodies = requireNonNull(bodies, "bodies is null");
        this.config = requireNonNull(config, "config is null");
    }

    public void start()
    {
        if (closed.get()) {
            throw new IllegalStateException("CI autofix dispatcher is closed");
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

    public void repairAvailable()
    {
        Runnable cancellation = null;
        synchronized (lifecycleLock) {
            String operationId = activeOperationId.get();
            if (operationId != null
                    && runtime.operation(operationId)
                            .filter(operation -> operation.kind()
                                    == OperationKind.RUN_CI_LEARNING)
                            .isPresent()) {
                cancellation = activeCancellation.get();
            }
        }
        if (cancellation != null) {
            cancellation.run();
        }
        wake();
    }

    @Override
    public void close()
    {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        Runnable cancellation;
        synchronized (lifecycleLock) {
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
                log.warn("CI agent cancellation did not settle cleanly", failure);
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
                    "CI autofix dispatcher ignored bounded shutdown");
        }
    }

    boolean dispatchOnce()
    {
        if (retryActiveCancellation()) {
            return true;
        }
        boolean changed = recoverExpired();
        Optional<Claim> claim = runtime.claimNextCiAutofix(
                config.workerId(), config.claimTtl(), config.capacity());
        if (claim.isEmpty()) {
            claim = runtime.claimNextCiLearning(
                    config.workerId(), config.claimTtl(), config.capacity());
        }
        if (claim.isEmpty()) {
            return changed;
        }
        try {
            dispatch(claim.orElseThrow());
        }
        catch (RuntimeException failure) {
            log.warn("CI autofix owner failed; durable recovery owns retry",
                    failure);
        }
        return true;
    }

    private void dispatch(Claim claim)
    {
        switch (claim.kind()) {
            case RECONCILE_TASK -> repairs.selectNext(claim);
            case RUN_CI_FIXER -> dispatchFixer(claim);
            case RUN_TASK_TURN -> dispatchTaskTurn(claim);
            case RUN_REVIEWER -> dispatchReviewer(claim);
            case RUN_CI_LEARNING -> dispatchLearner(claim);
            default -> throw new IllegalArgumentException(
                    "operation is not owned by CI autofix");
        }
    }

    private void dispatchFixer(Claim claim)
    {
        Operation operation = runtime.operation(claim.operationId())
                .orElseThrow();
        if (operation.ownerKind().equals("CI_ROUND")) {
            RepairLaunchBinding start = repairs.beginInspectedRepair(
                    claim, config.claimTtl());
            var launch = bodies.bindRepair(start.repair().run());
            var context = repairs.repairToolContext(start.repair());
            var handle = launchRegistered(
                    claim.operationId(),
                    () -> repairs.launchRepair(
                            writers,
                            start.repair(),
                            claim,
                            start.fence(),
                            start.repositoryRoot(),
                            capability -> bodies.repair(
                                    launch, start.repositoryRoot(),
                                    start.worktree(), context,
                                    capability)),
                    value -> () -> writers.cancel(
                            value, config.shutdownTimeout()));
            awaitRegistered(() -> repairs.awaitRepair(
                    writers, start.repair(), handle, config.bodyTimeout()));
            return;
        }
        if (!operation.ownerKind().equals("CI_CLEANUP")) {
            throw new IllegalArgumentException("unknown CI fixer owner");
        }
        var task = runtime.task(operation.taskId()).orElseThrow();
        Path repositoryRoot = Path.of(task.repositoryRoot());
        Optional<CleanupBinding> start = cleanups.beginCleanup(
                claim, repositoryRoot, config.claimTtl());
        if (start.isEmpty()) {
            return;
        }
        CleanupBinding binding = start.orElseThrow();
        var launch = bodies.bindCleanup(binding.run());
        String sealed = "attachment=" + binding.seal().attachmentState()
                + "\nkind=" + binding.seal().kind()
                + "\noperations=" + binding.seal().operations().stream()
                        .map(Enum::name).toList();
        var handle = launchRegistered(
                claim.operationId(),
                () -> cleanups.launchCleanup(
                        writers,
                        binding,
                        claim,
                        repositoryRoot,
                        capability -> bodies.cleanup(
                                launch, sealed, Path.of(task.worktreePath()),
                                capability)),
                value -> () -> writers.cancel(
                        value, config.shutdownTimeout()));
        awaitRegistered(() -> cleanups.awaitCleanup(
                writers, binding, handle, config.bodyTimeout()));
    }

    private void dispatchTaskTurn(Claim claim)
    {
        Operation operation = runtime.operation(claim.operationId())
                .orElseThrow();
        var task = runtime.task(operation.taskId()).orElseThrow();
        Path repositoryRoot = Path.of(task.repositoryRoot());
        Path worktree = Path.of(task.worktreePath());
        if (operation.ownerKind().equals("CI_ATTEMPT")) {
            var binding = reviewCoordinator.beginTaskInspection(
                    claim, repositoryRoot, config.claimTtl());
            var launch = bodies.bindTaskFix(binding.run());
            var context = reviewCoordinator.taskToolContext(binding);
            var handle = launchRegistered(
                    claim.operationId(),
                    () -> reviewCoordinator.launchTaskInspection(
                            writers,
                            binding,
                            claim,
                            capability -> bodies.taskFixReview(
                                    launch, worktree, context, capability,
                                    false)),
                    value -> () -> writers.cancel(
                            value, config.shutdownTimeout()));
            awaitRegistered(() -> reviewCoordinator.awaitTaskInspection(
                    writers, binding, handle, config.bodyTimeout()));
            return;
        }
        if (!operation.ownerKind().equals("AGENT_RUN")) {
            throw new IllegalArgumentException("unknown CI Task-turn owner");
        }
        var binding = reviewCoordinator.beginReviewerResultContinuation(
                claim, config.claimTtl());
        var launch = bodies.bindTaskReviewResult(binding.run());
        var context = reviewCoordinator.taskToolContext(binding);
        var handle = launchRegistered(
                claim.operationId(),
                () -> reviewCoordinator.launchReviewerResultContinuation(
                        writers,
                        binding,
                        claim,
                        capability -> bodies.taskFixReview(
                                launch, worktree, context, capability, true)),
                value -> () -> writers.cancel(
                        value, config.shutdownTimeout()));
        awaitRegistered(() ->
                reviewCoordinator.awaitReviewerResultContinuation(
                        writers, binding, handle, config.bodyTimeout()));
    }

    private void dispatchReviewer(Claim claim)
    {
        Operation operation = runtime.operation(claim.operationId())
                .orElseThrow();
        ReviewerStart start = reviewCoordinator.beginReviewer(
                operation.ownerId(), claim);
        var launch = bodies.bindReviewer(start.run());
        var handle = launchRegistered(
                claim.operationId(),
                () -> reviewCoordinator.launchReviewer(
                        reviewers,
                        start,
                        claim,
                        capability -> bodies.reviewer(launch, capability)),
                value -> () -> reviewers.cancel(
                        value, config.shutdownTimeout()));
        awaitRegistered(() -> reviewCoordinator.awaitReviewer(
                reviewers, handle, config.bodyTimeout()));
    }

    private void dispatchLearner(Claim claim)
    {
        Optional<CiLearningStart> start = learning.beginCiLearning(claim);
        if (start.isEmpty()) {
            return;
        }
        CiLearningStart binding = start.orElseThrow();
        var launch = bodies.bindLearner(binding.run());
        var subject = learning.learningSubject(binding.run().inputRef())
                .orElseThrow();
        var handle = launchRegistered(
                claim.operationId(),
                () -> learners.launch(
                        binding,
                        claim,
                        learning,
                        capability -> bodies.learner(
                                launch, subject.failedLogRefs(), capability)),
                value -> () -> learners.cancel(
                        value, config.shutdownTimeout()));
        awaitRegistered(() ->
                learners.awaitAndFinish(handle, config.bodyTimeout()));
    }

    private <T> T launchRegistered(
            String operationId,
            Supplier<T> launch,
            Function<T, Runnable> cancellation)
    {
        requireText(operationId, "operationId");
        synchronized (lifecycleLock) {
            if (closed.get()) {
                throw new IllegalStateException(
                        "CI dispatcher closed before agent activation");
            }
            T handle = launch.get();
            if (!activeCancellation.compareAndSet(
                    null, cancellation.apply(handle))) {
                throw new IllegalStateException(
                        "CI dispatcher already owns an active agent");
            }
            if (!activeOperationId.compareAndSet(null, operationId)) {
                activeCancellation.set(null);
                throw new IllegalStateException(
                        "CI dispatcher already owns an active operation");
            }
            return handle;
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
                if (expired.kind() == OperationKind.RUN_CI_LEARNING) {
                    learning.recoverExpiredCiLearning(
                            expired.operationId(), expired.generation());
                    changed = true;
                    continue;
                }
                Operation operation = runtime.operation(expired.operationId())
                        .orElseThrow();
                if (!owns(operation)) {
                    continue;
                }
                if (expired.processAttemptState()
                        == ProcessAttemptState.STOPPED) {
                    switch (operation.kind()) {
                        case RUN_CI_FIXER -> {
                            if (operation.ownerKind().equals("CI_ROUND")) {
                                repairs.recoverExpiredStoppedRepair(
                                        operation.operationId(),
                                        expired.generation(),
                                        config.claimTtl());
                            }
                            else if (operation.ownerKind().equals(
                                    "CI_CLEANUP")) {
                                cleanups.recoverExpiredStoppedCleanup(
                                        operation.operationId(),
                                        expired.generation(),
                                        config.claimTtl());
                            }
                            else {
                                throw new IllegalArgumentException(
                                        "unknown CI fixer owner");
                            }
                        }
                        case RUN_TASK_TURN ->
                                reviewCoordinator
                                        .recoverExpiredStoppedTaskTurn(
                                                operation.operationId(),
                                                expired.generation(),
                                                config.claimTtl());
                        case RUN_REVIEWER ->
                                reviewCoordinator
                                        .recoverExpiredStoppedReviewer(
                                                operation.operationId(),
                                                expired.generation(),
                                                config.claimTtl());
                        default -> throw new IllegalStateException(
                                "unexpected stopped CI operation");
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
                log.warn("CI autofix recovery failed; polling will retry",
                        failure);
            }
        }
        return changed;
    }

    private boolean owns(Operation operation)
    {
        return switch (operation.kind()) {
            case RECONCILE_TASK -> runtime.pendingWork(operation.taskId())
                    .stream().anyMatch(item -> switch (item.kind()) {
                        case FINAL_RED, CI_FIX_READY -> true;
                        case AGENT_RESULT_READY -> runtime.run(
                                item.externalKey())
                                .flatMap(run -> runtime
                                        .reviewerRequestForReviewerRun(
                                                run.runId()))
                                .filter(request -> request.intendedGateKind()
                                        == GateIntent.CI_UPDATE)
                                .isPresent();
                        default -> false;
                    });
            case RUN_CI_FIXER -> operation.ownerKind().equals("CI_ROUND")
                    || operation.ownerKind().equals("CI_CLEANUP");
            case RUN_TASK_TURN -> operation.ownerKind().equals("CI_ATTEMPT")
                    || operation.ownerKind().equals("AGENT_RUN")
                    && runtime.reviewerRequestForReviewerRun(
                            operation.ownerId())
                            .filter(request -> request.intendedGateKind()
                                    == GateIntent.CI_UPDATE)
                            .isPresent();
            case RUN_REVIEWER -> runtime.reviewerRequest(operation.ownerId())
                    .filter(request -> request.intendedGateKind()
                            == GateIntent.CI_UPDATE)
                    .isPresent();
            default -> false;
        };
    }

    private void run()
    {
        while (running.get()) {
            boolean changed;
            try {
                changed = dispatchOnce();
            }
            catch (RuntimeException failure) {
                log.warn("CI autofix poll failed; retrying", failure);
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
        if (value == null || value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
    }
}
