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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

import static java.util.Objects.requireNonNull;

/** One bounded polling thread for the currently wired new-flow handlers. */
public final class NewFlowDispatcher
        implements AutoCloseable
{
    private static final Logger log = LoggerFactory.getLogger(
            NewFlowDispatcher.class);

    public interface Handler
    {
        OperationKind kind();

        void execute(Claim claim)
                throws Exception;

        boolean recover(ExpiredClaim expired)
                throws Exception;
    }

    public record Config(
            String workerId,
            Duration claimTtl,
            Duration pollInterval,
            int capacity)
    {
        public Config
        {
            requireText(workerId, "workerId");
            requirePositive(claimTtl, "claimTtl");
            requirePositive(pollInterval, "pollInterval");
            if (capacity < 1) {
                throw new IllegalArgumentException(
                        "capacity must be positive");
            }
        }
    }

    private final FlowRuntime runtime;
    private final Config config;
    private final Map<OperationKind, Handler> handlers;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ReentrantLock wakeLock = new ReentrantLock();
    private final Condition wakeSignal = wakeLock.newCondition();
    private volatile Thread thread;

    public NewFlowDispatcher(
            FlowRuntime runtime,
            Config config,
            List<Handler> handlers)
    {
        this.runtime = requireNonNull(runtime, "runtime is null");
        this.config = requireNonNull(config, "config is null");
        requireNonNull(handlers, "handlers is null");
        EnumMap<OperationKind, Handler> byKind = new EnumMap<>(
                OperationKind.class);
        for (Handler handler : handlers) {
            requireNonNull(handler, "handler is null");
            OperationKind kind = requireNonNull(
                    handler.kind(), "handler kind is null");
            if (kind == OperationKind.PUBLISH
                    || kind == OperationKind.OBSERVE_CI
                    || kind == OperationKind.RUN_CI_LEARNING) {
                throw new IllegalArgumentException(
                        kind + " requires an owner-specific dispatcher lane");
            }
            if (byKind.put(kind, handler) != null) {
                throw new IllegalArgumentException(
                        "duplicate handler for " + kind);
            }
        }
        this.handlers = Map.copyOf(byKind);
    }

    public void start()
    {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        Thread worker = Thread.ofPlatform()
                .daemon(true)
                .name(config.workerId())
                .unstarted(this::run);
        thread = worker;
        worker.start();
    }

    /** Best-effort latency hint; durable polling remains authoritative. */
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
            worker.join(config.pollInterval().plusSeconds(1).toMillis());
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        if (worker.isAlive()) {
            throw new IllegalStateException(
                    "new-flow dispatcher handler ignored interruption");
        }
    }

    boolean dispatchOnce()
    {
        boolean changed = recoverExpired();
        if (handlers.isEmpty()) {
            return changed;
        }
        Optional<Claim> claim = runtime.claimNextForDispatch(
                handlers.keySet(),
                config.workerId(),
                config.claimTtl(),
                config.capacity());
        if (claim.isEmpty()) {
            return changed;
        }
        Claim claimed = claim.orElseThrow();
        Handler handler = handlers.get(claimed.kind());
        try {
            handler.execute(claimed);
        }
        catch (Exception failure) {
            log.warn("New-flow handler {} failed; durable recovery owns retry",
                    claimed.kind(), failure);
        }
        return true;
    }

    private boolean recoverExpired()
    {
        boolean changed = false;
        for (ExpiredClaim expired : runtime.expiredClaims()) {
            Handler handler = handlers.get(expired.kind());
            if (handler == null) {
                continue;
            }
            try {
                changed |= handler.recover(expired);
            }
            catch (Exception failure) {
                log.warn("New-flow recovery {} failed; polling will retry",
                        expired.kind(), failure);
            }
        }
        return changed;
    }

    private void run()
    {
        while (running.get()) {
            boolean changed;
            try {
                changed = dispatchOnce();
            }
            catch (RuntimeException failure) {
                log.warn("New-flow dispatcher poll failed", failure);
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
            running.set(false);
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
