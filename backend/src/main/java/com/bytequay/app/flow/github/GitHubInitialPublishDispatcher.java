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

import com.bytequay.app.domain.CredentialType;
import com.bytequay.app.flow.gate.UserGates;
import com.bytequay.app.flow.runtime.FlowRuntime;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ExpiredClaim;
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

/** Bounded owner lane for exact greenfield INITIAL publication plans. */
public final class GitHubInitialPublishDispatcher
        implements AutoCloseable
{
    private static final Logger log = LoggerFactory.getLogger(
            GitHubInitialPublishDispatcher.class);

    public record Config(
            String workerId, Duration claimTtl, Duration pollInterval,
            int capacity)
    {
        public Config
        {
            if (workerId == null || workerId.isBlank()
                    || claimTtl == null || claimTtl.isNegative()
                    || claimTtl.isZero()
                    || pollInterval == null || pollInterval.isNegative()
                    || pollInterval.isZero() || capacity < 1) {
                throw new IllegalArgumentException(
                        "initial publish dispatcher config is invalid");
            }
        }
    }

    private final FlowRuntime runtime;
    private final UserGates gates;
    private final GitHubEffects effects;
    private final GitHubInitialPublishExecutor executor;
    private final Config config;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ReentrantLock wakeLock = new ReentrantLock();
    private final Condition wakeSignal = wakeLock.newCondition();
    private volatile Thread thread;

    public GitHubInitialPublishDispatcher(
            FlowRuntime runtime,
            UserGates gates,
            GitHubEffects effects,
            CredentialStore credentials,
            Clock clock,
            Config config)
    {
        this(
                runtime,
                gates,
                effects,
                new GitHubInitialPublishExecutor(
                        gates,
                        effects,
                        new GitHubProvider(
                                runtime,
                                repoSecrets(requireNonNull(
                                        credentials,
                                        "credentials is null"))),
                        requireNonNull(clock, "clock is null")),
                config);
    }

    GitHubInitialPublishDispatcher(
            FlowRuntime runtime,
            UserGates gates,
            GitHubEffects effects,
            GitHubInitialPublishExecutor executor,
            Config config)
    {
        this.runtime = requireNonNull(runtime, "runtime is null");
        this.gates = requireNonNull(gates, "gates is null");
        this.effects = requireNonNull(effects, "effects is null");
        this.executor = requireNonNull(executor, "executor is null");
        this.config = requireNonNull(config, "config is null");
    }

    static GitHubProvider.SecretSource repoSecrets(CredentialStore credentials)
    {
        requireNonNull(credentials, "credentials is null");
        return (externalId, owner, repository) -> {
            String secret = credentials.getSecret(
                    CredentialType.REPO, owner + "/" + repository)
                    .orElse(null);
            return secret == null || secret.isBlank()
                    ? null : new GitHubProvider.RepositoryCredential(
                            externalId, secret.toCharArray());
        };
    }

    public void start()
    {
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
                    "initial publish handler ignored interruption");
        }
    }

    boolean dispatchOnce()
    {
        boolean changed = recoverExpired();
        var claim = runtime.claimNextInitialPublish(
                config.workerId(), config.claimTtl(), config.capacity());
        if (claim.isEmpty()) {
            return changed;
        }
        try {
            executor.execute(claim.orElseThrow());
        }
        catch (RuntimeException failure) {
            log.warn("INITIAL publication failed; durable recovery owns retry",
                    failure);
        }
        return true;
    }

    private boolean recoverExpired()
    {
        boolean changed = false;
        for (ExpiredClaim expired : runtime.expiredClaims()) {
            if (expired.kind() != OperationKind.PUBLISH) {
                continue;
            }
            try {
                var operation = runtime.operation(expired.operationId());
                if (operation.isEmpty()
                        || effects.initialPublishPlan(
                                operation.orElseThrow().inputRef()).isEmpty()) {
                    continue;
                }
                gates.recoverExpiredInitialPublish(
                        expired.operationId(), expired.generation());
                changed = true;
            }
            catch (RuntimeException failure) {
                log.warn("INITIAL publication recovery failed", failure);
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
                log.warn("INITIAL publication poll failed; retrying", failure);
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
}
