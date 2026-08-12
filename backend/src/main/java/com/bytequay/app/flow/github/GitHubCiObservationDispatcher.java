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
package com.bytequay.app.flow.github;

import com.bytequay.app.flow.ci.CiAutofixRecords.RoundState;
import com.bytequay.app.flow.ci.CiObservationCoordinator;
import com.bytequay.app.flow.runtime.CiAutofixDispatcher;
import com.bytequay.app.flow.runtime.FlowRuntime;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationKind;
import com.bytequay.app.repository.CredentialStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import static java.util.Objects.requireNonNull;

/** Bounded owner lane for durable receipt-owned GitHub CI reads. */
public final class GitHubCiObservationDispatcher
        implements AutoCloseable
{
    private static final Logger log = LoggerFactory.getLogger(
            GitHubCiObservationDispatcher.class);

    private final FlowRuntime runtime;
    private final GitHubCiObservationExecutor executor;
    private final CiAutofixDispatcher ciAgents;
    private final String workerId;
    private final Duration claimTtl;
    private final Duration pollInterval;
    private final int capacity;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ReentrantLock wakeLock = new ReentrantLock();
    private final Condition wakeSignal = wakeLock.newCondition();
    private volatile Thread thread;

    public GitHubCiObservationDispatcher(
            FlowRuntime runtime,
            CiObservationCoordinator coordinator,
            CiAutofixDispatcher ciAgents,
            CredentialStore credentials,
            Clock clock,
            String workerId,
            Duration claimTtl,
            Duration pollInterval,
            int capacity)
    {
        this(
                runtime,
                new GitHubCiObservationExecutor(
                        runtime,
                        coordinator,
                        new GitHubCiProvider(
                                GitHubInitialPublishDispatcher.repoSecrets(
                                        requireNonNull(
                                                credentials,
                                                "credentials is null")),
                                requireNonNull(clock, "clock is null")),
                        clock),
                ciAgents,
                workerId,
                claimTtl,
                pollInterval,
                capacity);
    }

    GitHubCiObservationDispatcher(
            FlowRuntime runtime,
            GitHubCiObservationExecutor executor,
            CiAutofixDispatcher ciAgents,
            String workerId,
            Duration claimTtl,
            Duration pollInterval,
            int capacity)
    {
        this.runtime = requireNonNull(runtime, "runtime is null");
        this.executor = requireNonNull(executor, "executor is null");
        this.ciAgents = requireNonNull(ciAgents, "ciAgents is null");
        if (workerId == null || workerId.isBlank()
                || claimTtl == null || claimTtl.isNegative()
                || claimTtl.isZero()
                || pollInterval == null || pollInterval.isNegative()
                || pollInterval.isZero() || capacity < 1) {
            throw new IllegalArgumentException(
                    "CI observation dispatcher config is invalid");
        }
        this.workerId = workerId;
        this.claimTtl = claimTtl;
        this.pollInterval = pollInterval;
        this.capacity = capacity;
    }

    public void start()
    {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Thread worker = Thread.ofPlatform().daemon(true).name(workerId)
                .unstarted(this::run);
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

    @Override
    public void close()
    {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        wake();
        Thread worker = thread;
        if (worker == null || worker == Thread.currentThread()) {
            return;
        }
        worker.interrupt();
        try {
            worker.join(pollInterval.plusSeconds(1).toMillis());
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (worker.isAlive()) {
            throw new IllegalStateException(
                    "CI observation handler ignored interruption");
        }
    }

    boolean dispatchOnce()
    {
        boolean changed = recoverExpired();
        var claim = runtime.claimNextCiObservation(
                workerId, claimTtl, capacity);
        if (claim.isEmpty()) {
            return changed;
        }
        try {
            executor.execute(claim.orElseThrow())
                    .filter(round -> round.state() == RoundState.QUEUED)
                    .ifPresent(ignored -> ciAgents.repairAvailable());
        }
        catch (RuntimeException failure) {
            log.warn("GitHub CI observation failed; recovery owns retry",
                    failure);
        }
        return true;
    }

    private boolean recoverExpired()
    {
        boolean changed = false;
        for (var expired : runtime.expiredClaims()) {
            if (expired.kind() != OperationKind.OBSERVE_CI) {
                continue;
            }
            try {
                runtime.recoverExpiredCiObservation(
                        expired.operationId(), expired.generation());
                changed = true;
            }
            catch (RuntimeException failure) {
                log.warn("GitHub CI observation recovery failed for {}",
                        expired.operationId(), failure);
            }
        }
        return changed;
    }

    private void run()
    {
        while (running.get()) {
            boolean changed = false;
            try {
                changed = dispatchOnce();
            }
            catch (RuntimeException failure) {
                log.warn("GitHub CI observation poll failed; retrying",
                        failure);
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
                wakeSignal.awaitNanos(pollInterval.toNanos());
            }
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        finally {
            wakeLock.unlock();
        }
    }
}
