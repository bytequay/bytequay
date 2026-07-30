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
package com.bytequay.app.developmentflow.execution;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

/**
 * Sole V2 worktree-writer lease boundary. Capacity admission supplies the
 * monotonically increasing fencing token; this manager binds that token to
 * one exact Task worktree before any code or Git mutation is allowed.
 */
public final class WorktreeWriterLeaseManager
{
    private final Store store;
    private final Clock clock;

    public WorktreeWriterLeaseManager(Store store, Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        // Leases persist to millisecond columns and revalidation compares
        // acquiredAt for exact equality. A clock with sub-millisecond
        // resolution — Clock.systemUTC() on every current JDK — would mint an
        // acquiredAt no read-back can ever reproduce, so every heartbeat and
        // every writer fence would fail as "non-exact". Truncate to the
        // precision the store can actually round-trip.
        this.clock = Clock.tick(requireNonNull(clock, "clock is null"), Duration.ofMillis(1));
    }

    public Lease acquire(ExecutionContext context, String worktreePath)
    {
        requireNonBlank(worktreePath, "worktreePath");
        CapacityManager.CapacityLease capacity = requireExactWriter(context);
        Instant now = clock.instant();
        Lease requested = new Lease(
                worktreePath,
                capacity.scope().taskId(),
                capacity.operationId(),
                capacity.scope().taskEpoch(),
                capacity.writerFencingToken(),
                capacity.leaseOwner(),
                now,
                capacity.expiresAt());
        Lease acquired = store.tryAcquire(requested, now)
                .orElseThrow(() -> new WriterLeaseUnavailableException(
                        "worktree writer lease is already held: " + worktreePath));
        try {
            acquired = requirePersistedLease(
                    requested, acquired, capacity, now, true);
            AtomicReference<Lease> live = new AtomicReference<>(acquired);
            context.registerWriterResource(
                    () -> live.set(heartbeat(context, live.get())),
                    () -> release(live.get()));
            return acquired;
        }
        catch (RuntimeException | Error failure) {
            try {
                release(requested);
            }
            catch (RuntimeException releaseFailure) {
                failure.addSuppressed(releaseFailure);
            }
            throw failure;
        }
    }

    /**
     * Extends only the same durable lease identity and never beyond its live
     * CapacityLease. A stale holder cannot heartbeat a replacement lease.
     */
    public Lease heartbeat(ExecutionContext context, Lease expected)
    {
        requireNonNull(expected, "expected is null");
        CapacityManager.CapacityLease capacity = requireExactWriter(context);
        requireCoveredBy(expected, capacity);
        Instant now = clock.instant();
        Lease renewed = store.heartbeat(expected, now, capacity.expiresAt())
                .orElseThrow(() -> new StaleWriterLeaseException(
                        "worktree writer lease is no longer exact"));
        return requirePersistedLease(expected, renewed, capacity, now, true);
    }

    /**
     * Returns a one-shot capability after revalidating both durable leases.
     * A V2 mutating adapter must perform its external side effect inside
     * {@link WriterAuthorization#run(Function)}; that call revalidates again
     * at consumption time rather than trusting a cached token.
     */
    public WriterAuthorization authorizeMutation(
            ExecutionContext context,
            Lease expected)
    {
        requireNonNull(expected, "expected is null");
        requireLive(context, expected);
        return new WriterAuthorization(this, context, expected);
    }

    /** Exact, idempotent release; it cannot delete a replacement lease. */
    public void release(Lease expected)
    {
        requireNonNull(expected, "expected is null");
        store.release(expected, clock.instant());
    }

    private static CapacityManager.CapacityLease requireExactWriter(
            ExecutionContext context)
    {
        requireNonNull(context, "context is null");
        CapacityManager.CapacityLease capacity = context.requireWriterCapacityLease();
        if (capacity.source() != CapacityManager.WorkflowSource.V2
                || capacity.scope().taskId() == null
                || capacity.scope().taskEpoch() == null
                || capacity.writerFencingToken() == null) {
            throw new IllegalStateException(
                    "V2 writer work requires an exact Task capacity lease");
        }
        return capacity;
    }

    private static void requireCoveredBy(
            Lease lease,
            CapacityManager.CapacityLease capacity)
    {
        if (!lease.taskId().equals(capacity.scope().taskId())
                || lease.taskEpoch() != capacity.scope().taskEpoch()
                || !lease.operationId().equals(capacity.operationId())
                || lease.fencingToken() != capacity.writerFencingToken()
                || !lease.leaseOwner().equals(capacity.leaseOwner())
                || lease.expiresAt().isAfter(capacity.expiresAt())) {
            throw new StaleWriterLeaseException(
                    "worktree writer lease does not match live capacity");
        }
    }

    private Lease requireLive(ExecutionContext context, Lease expected)
    {
        CapacityManager.CapacityLease capacity = requireExactWriter(context);
        requireCoveredBy(expected, capacity);
        Instant now = clock.instant();
        Lease current = store.findExact(expected, now)
                .orElseThrow(() -> new StaleWriterLeaseException(
                        "worktree writer lease is no longer live"));
        return requirePersistedLease(expected, current, capacity, now, false);
    }

    private static Lease requirePersistedLease(
            Lease expected,
            Lease actual,
            CapacityManager.CapacityLease capacity,
            Instant now,
            boolean requireCapacityExpiry)
    {
        if (!expected.sameIdentity(actual)
                || !actual.isActiveAt(now)
                || (requireCapacityExpiry
                    && !actual.expiresAt().equals(capacity.expiresAt()))) {
            throw new StaleWriterLeaseException(
                    "worktree writer store returned a non-exact lease");
        }
        requireCoveredBy(actual, capacity);
        return actual;
    }

    public record Lease(
            String worktreePath,
            String taskId,
            String operationId,
            long taskEpoch,
            long fencingToken,
            String leaseOwner,
            Instant acquiredAt,
            Instant expiresAt)
    {
        public Lease
        {
            requireNonBlank(worktreePath, "worktreePath");
            requireNonBlank(taskId, "taskId");
            requireNonBlank(operationId, "operationId");
            requireNonBlank(leaseOwner, "leaseOwner");
            requireNonNull(acquiredAt, "acquiredAt is null");
            requireNonNull(expiresAt, "expiresAt is null");
            if (taskEpoch < 1 || fencingToken < 1) {
                throw new IllegalArgumentException(
                        "Task epoch and fencing token must be positive");
            }
            if (!expiresAt.isAfter(acquiredAt)) {
                throw new IllegalArgumentException(
                        "writer lease expiry must follow acquisition");
            }
        }

        public boolean isActiveAt(Instant instant)
        {
            return expiresAt.isAfter(requireNonNull(instant, "instant is null"));
        }

        boolean sameIdentity(Lease other)
        {
            return other != null
                    && worktreePath.equals(other.worktreePath)
                    && taskId.equals(other.taskId)
                    && operationId.equals(other.operationId)
                    && taskEpoch == other.taskEpoch
                    && fencingToken == other.fencingToken
                    && leaseOwner.equals(other.leaseOwner)
                    && acquiredAt.equals(other.acquiredAt);
        }
    }

    /** One-shot proof consumed around one code- or Git-mutating adapter call. */
    public static final class WriterAuthorization
    {
        private final WorktreeWriterLeaseManager manager;
        private final ExecutionContext context;
        private final Lease expected;
        private final AtomicBoolean consumed = new AtomicBoolean();

        private WriterAuthorization(
                WorktreeWriterLeaseManager manager,
                ExecutionContext context,
                Lease expected)
        {
            this.manager = requireNonNull(manager, "manager is null");
            this.context = requireNonNull(context, "context is null");
            this.expected = requireNonNull(expected, "expected is null");
        }

        public <T> T run(Function<MutationFence, T> mutation)
        {
            requireNonNull(mutation, "mutation is null");
            if (!consumed.compareAndSet(false, true)) {
                throw new IllegalStateException(
                        "writer authorization was already consumed");
            }
            Lease live = manager.requireLive(context, expected);
            return mutation.apply(new MutationFence(
                    live.worktreePath(),
                    live.taskId(),
                    live.operationId(),
                    live.taskEpoch(),
                    live.fencingToken()));
        }
    }

    /**
     * Unforgeable fence values for the consuming adapter. The adapter must
     * bind the token to its durable effect/write boundary; entering the
     * callback alone is not a substitute for fencing a multi-step mutation.
     */
    public static final class MutationFence
    {
        private final String worktreePath;
        private final String taskId;
        private final String operationId;
        private final long taskEpoch;
        private final long fencingToken;

        private MutationFence(
                String worktreePath,
                String taskId,
                String operationId,
                long taskEpoch,
                long fencingToken)
        {
            this.worktreePath = requireNonBlank(worktreePath, "worktreePath");
            this.taskId = requireNonBlank(taskId, "taskId");
            this.operationId = requireNonBlank(operationId, "operationId");
            if (taskEpoch < 1 || fencingToken < 1) {
                throw new IllegalArgumentException(
                        "Task epoch and fencing token must be positive");
            }
            this.taskEpoch = taskEpoch;
            this.fencingToken = fencingToken;
        }

        public String worktreePath() { return worktreePath; }

        public String taskId() { return taskId; }

        public String operationId() { return operationId; }

        public long taskEpoch() { return taskEpoch; }

        public long fencingToken() { return fencingToken; }
    }

    public interface Store
    {
        /** Atomically inserts after removing only provably expired V2 rows. */
        Optional<Lease> tryAcquire(Lease requested, Instant now);

        /** Finds only the same immutable identity and only when still present. */
        Optional<Lease> findExact(Lease expected, Instant now);

        /** Renews only the same live immutable identity. */
        Optional<Lease> heartbeat(
                Lease expected,
                Instant heartbeatAt,
                Instant expiresAt);

        /** Deletes only the same immutable identity. */
        boolean release(Lease expected, Instant releasedAt);
    }

    public static final class WriterLeaseUnavailableException
            extends IllegalStateException
    {
        public WriterLeaseUnavailableException(String message)
        {
            super(message);
        }
    }

    public static final class StaleWriterLeaseException
            extends IllegalStateException
    {
        public StaleWriterLeaseException(String message)
        {
            super(message);
        }
    }

    private static String requireNonBlank(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
