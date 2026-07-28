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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import static java.util.Objects.requireNonNull;

/** Sole policy owner and write boundary for development-flow capacity leases. */
public final class CapacityManager
{
    private static final String UNSCOPED = "\u0000";

    private final CapacityLeaseStore store;
    private final CapacityPolicySource policies;
    private final Clock clock;
    private final Duration leaseDuration;
    private final Supplier<String> idSupplier;

    private long workspaceCursor;
    private final Map<String, Long> trunkCursors = new HashMap<>();

    public CapacityManager(
            CapacityLeaseStore store,
            CapacityPolicySource policies,
            Clock clock,
            Duration leaseDuration)
    {
        this(store, policies, clock, leaseDuration, () -> UUID.randomUUID().toString());
    }

    CapacityManager(
            CapacityLeaseStore store,
            CapacityPolicySource policies,
            Clock clock,
            Duration leaseDuration,
            Supplier<String> idSupplier)
    {
        this.store = requireNonNull(store, "store is null");
        this.policies = requireNonNull(policies, "policies is null");
        this.clock = requireNonNull(clock, "clock is null");
        this.leaseDuration = positiveDuration(leaseDuration, "leaseDuration");
        this.idSupplier = requireNonNull(idSupplier, "idSupplier is null");
    }

    /**
     * Tries every policy dimension before inserting a lease. A denial writes
     * nothing, so durable capacity waits hold neither a worker nor a lease.
     */
    public synchronized Admission tryAcquire(CapacityRequest request, String leaseOwner)
    {
        requireNonNull(request, "request is null");
        requireNonNull(leaseOwner, "leaseOwner is null");
        if (leaseOwner.isBlank()) {
            throw new IllegalArgumentException("leaseOwner must not be blank");
        }
        Instant now = clock.instant();
        Optional<CapacityLease> existing = store.findActiveByOperation(
                request.operationId(), now);
        if (existing.isPresent()) {
            if (existing.get().covers(request, leaseOwner)) {
                return Admission.admitted(existing.get());
            }
            return Admission.denied(Denial.OPERATION_ALREADY_LEASED);
        }

        CapacityPolicy policy = requireNonNull(policies.current(), "current policy is null");
        List<CapacityLease> active = store.listActive(now);

        for (CapacityLane lane : request.lanes()) {
            Integer limit = policy.laneLimits().get(lane);
            if (limit == null) {
                return Admission.denied(Denial.UNCONFIGURED_LANE);
            }
            int reserved = policy.reservedTrunkControl().getOrDefault(lane, 0);
            int usable = request.trunkControl() ? limit : limit - reserved;
            long occupied = active.stream()
                    .filter(lease -> lease.lanes().contains(lane))
                    .count();
            if (occupied >= usable) {
                return Admission.denied(Denial.LANE_LIMIT);
            }
        }

        if (addsExecutingTask(request, active)) {
            if (distinctTasks(active, request.scope().workspaceId(), true)
                    >= policy.workspaceLimit(request.scope().workspaceId())) {
                return Admission.denied(Denial.WORKSPACE_LIMIT);
            }
            if (distinctTasks(active, request.scope().trunkId(), false)
                    >= policy.trunkLimit(request.scope().trunkId())) {
                return Admission.denied(Denial.TRUNK_LIMIT);
            }
        }

        if (request.exclusiveTask() && active.stream()
                .anyMatch(lease -> lease.exclusiveTask()
                        && Objects.equals(lease.scope().taskId(), request.scope().taskId()))) {
            return Admission.denied(Denial.TASK_MUTATION_LIMIT);
        }

        CapacityLeaseDraft draft = new CapacityLeaseDraft(
                requireNonNull(idSupplier.get(), "generated lease id is null"),
                request,
                leaseOwner,
                now,
                now.plus(leaseDuration));
        Optional<CapacityLease> created = store.create(draft);
        return created.map(Admission::admitted)
                .orElseGet(() -> Admission.denied(Denial.CONCURRENT_CONFLICT));
    }

    /** Renews only the exact active lease owned by the same execution. */
    public synchronized Optional<CapacityLease> heartbeat(String leaseId, String leaseOwner)
    {
        requireNonNull(leaseId, "leaseId is null");
        requireNonNull(leaseOwner, "leaseOwner is null");
        Instant now = clock.instant();
        return store.heartbeat(leaseId, leaseOwner, now, now.plus(leaseDuration));
    }

    /** Idempotently releases a lease. */
    public synchronized void release(String leaseId, String leaseOwner)
    {
        requireNonNull(leaseId, "leaseId is null");
        requireNonNull(leaseOwner, "leaseOwner is null");
        store.release(leaseId, leaseOwner, clock.instant());
    }

    /** Expires durable leases after restart or a lost worker. */
    public synchronized List<CapacityLease> expireLeases()
    {
        return List.copyOf(store.expire(clock.instant()));
    }

    /** Rejects adapter execution without the exact live lease. */
    public synchronized CapacityLease requireExactLease(
            String leaseId,
            CapacityRequest request,
            String leaseOwner)
    {
        requireNonNull(leaseId, "leaseId is null");
        requireNonNull(request, "request is null");
        requireNonNull(leaseOwner, "leaseOwner is null");
        Instant now = clock.instant();
        CapacityLease lease = store.findById(leaseId)
                .filter(candidate -> candidate.isActiveAt(now))
                .filter(candidate -> candidate.covers(request, leaseOwner))
                .orElseThrow(() -> new IllegalStateException(
                        "missing or stale exact capacity lease for " + request.operationId()));
        if (request.writerRequired() && lease.writerFencingToken() == null) {
            throw new IllegalStateException(
                    "writer operation has no fencing token: " + request.operationId());
        }
        return lease;
    }

    /**
     * Two-level round robin: one candidate per Workspace, rotating Trunks
     * within each Workspace. Ordering inside one Trunk remains durable FIFO.
     */
    public synchronized <T> List<T> fairOrder(
            List<T> candidates,
            Function<T, CapacityRequest> requestFunction,
            Function<T, Instant> createdAtFunction,
            Function<T, String> idFunction)
    {
        requireNonNull(candidates, "candidates is null");
        requireNonNull(requestFunction, "requestFunction is null");
        requireNonNull(createdAtFunction, "createdAtFunction is null");
        requireNonNull(idFunction, "idFunction is null");

        Comparator<T> fifo = Comparator
                .comparing(createdAtFunction)
                .thenComparing(idFunction);
        Map<String, Map<String, ArrayDeque<T>>> grouped = new LinkedHashMap<>();
        candidates.stream().sorted(fifo).forEach(candidate -> {
            CapacityScope scope = requestFunction.apply(candidate).scope();
            String workspace = key(scope.workspaceId());
            String trunk = key(scope.trunkId());
            grouped.computeIfAbsent(workspace, ignored -> new LinkedHashMap<>())
                    .computeIfAbsent(trunk, ignored -> new ArrayDeque<>())
                    .add(candidate);
        });

        List<String> workspaces = new ArrayList<>(grouped.keySet());
        workspaces.sort(String::compareTo);
        rotate(workspaces, workspaceCursor++);
        Map<String, List<String>> trunks = new HashMap<>();
        for (String workspace : workspaces) {
            List<String> values = new ArrayList<>(grouped.get(workspace).keySet());
            values.sort(String::compareTo);
            rotate(values, trunkCursors.getOrDefault(workspace, 0L));
            trunks.put(workspace, values);
        }

        List<T> ordered = new ArrayList<>(candidates.size());
        boolean added;
        do {
            added = false;
            for (String workspace : workspaces) {
                List<String> workspaceTrunks = trunks.get(workspace);
                for (int offset = 0; offset < workspaceTrunks.size(); offset++) {
                    String trunk = workspaceTrunks.get(offset);
                    ArrayDeque<T> queue = grouped.get(workspace).get(trunk);
                    if (!queue.isEmpty()) {
                        ordered.add(queue.removeFirst());
                        rotate(workspaceTrunks, offset + 1L);
                        trunkCursors.merge(workspace, 1L, Long::sum);
                        added = true;
                        break;
                    }
                }
            }
        }
        while (added);
        return List.copyOf(ordered);
    }

    private static boolean addsExecutingTask(
            CapacityRequest request,
            List<CapacityLease> active)
    {
        String taskId = request.scope().taskId();
        return taskId != null && active.stream()
                .noneMatch(lease -> taskId.equals(lease.scope().taskId()));
    }

    private static long distinctTasks(
            List<CapacityLease> active,
            String scopeId,
            boolean workspace)
    {
        if (scopeId == null) {
            return 0;
        }
        return active.stream()
                .filter(lease -> Objects.equals(
                        workspace ? lease.scope().workspaceId() : lease.scope().trunkId(),
                        scopeId))
                .map(lease -> lease.scope().taskId())
                .filter(Objects::nonNull)
                .distinct()
                .count();
    }

    private static String key(String value)
    {
        return value == null ? UNSCOPED : value;
    }

    private static <T> void rotate(List<T> values, long cursor)
    {
        if (values.size() < 2) {
            return;
        }
        int distance = Math.floorMod(cursor, values.size());
        if (distance == 0) {
            return;
        }
        List<T> copy = new ArrayList<>(values);
        for (int index = 0; index < values.size(); index++) {
            values.set(index, copy.get((index + distance) % copy.size()));
        }
    }

    private static Duration positiveDuration(Duration value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isZero() || value.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return value;
    }

    public enum CapacityLane
    {
        CLI,
        API,
        VALIDATION,
        REVIEW,
        LOCAL_GIT,
        GITHUB,
        REMOTE_OBSERVATION,
        MERGE,
        CLEANUP
    }

    public enum WorkflowSource
    {
        LEGACY,
        V2
    }

    public enum Denial
    {
        UNCONFIGURED_LANE,
        LANE_LIMIT,
        WORKSPACE_LIMIT,
        TRUNK_LIMIT,
        TASK_MUTATION_LIMIT,
        OPERATION_ALREADY_LEASED,
        CONCURRENT_CONFLICT
    }

    public record CapacityScope(
            String workspaceId,
            String trunkId,
            String taskId,
            Long taskEpoch)
    {
        public CapacityScope
        {
            requireNonBlankIfPresent(workspaceId, "workspaceId");
            requireNonBlankIfPresent(trunkId, "trunkId");
            requireNonBlankIfPresent(taskId, "taskId");
            if (trunkId != null && workspaceId == null) {
                throw new IllegalArgumentException(
                        "Trunk capacity requires an exact Workspace");
            }
            if ((taskId == null) != (taskEpoch == null)) {
                throw new IllegalArgumentException(
                        "taskId and taskEpoch must either both be present or both be absent");
            }
            if (taskEpoch != null && taskEpoch < 1) {
                throw new IllegalArgumentException("taskEpoch must be positive");
            }
            if (taskId != null && (workspaceId == null || trunkId == null)) {
                throw new IllegalArgumentException(
                        "Task capacity requires exact Workspace and Trunk scope");
            }
        }
    }

    public record CapacityRequest(
            String operationId,
            WorkflowSource source,
            Set<CapacityLane> lanes,
            CapacityScope scope,
            boolean trunkControl,
            boolean exclusiveTask,
            boolean writerRequired)
    {
        public CapacityRequest
        {
            requireNonNull(operationId, "operationId is null");
            requireNonNull(source, "source is null");
            requireNonNull(lanes, "lanes is null");
            lanes = Set.copyOf(lanes);
            requireNonNull(scope, "scope is null");
            if (operationId.isBlank()) {
                throw new IllegalArgumentException("operationId must not be blank");
            }
            if (lanes.isEmpty()) {
                throw new IllegalArgumentException("at least one capacity lane is required");
            }
            if (trunkControl && scope.trunkId() == null) {
                throw new IllegalArgumentException("Trunk control requires an exact Trunk");
            }
            if ((exclusiveTask || writerRequired) && scope.taskId() == null) {
                throw new IllegalArgumentException(
                        "exclusive or writer work requires an exact Task");
            }
            if (writerRequired && !exclusiveTask) {
                throw new IllegalArgumentException("writer work must be Task-exclusive");
            }
        }
    }

    public record CapacityLeaseDraft(
            String id,
            CapacityRequest request,
            String leaseOwner,
            Instant acquiredAt,
            Instant expiresAt)
    {
        public CapacityLeaseDraft
        {
            requireNonNull(id, "id is null");
            requireNonNull(request, "request is null");
            requireNonNull(leaseOwner, "leaseOwner is null");
            requireNonNull(acquiredAt, "acquiredAt is null");
            requireNonNull(expiresAt, "expiresAt is null");
            if (!expiresAt.isAfter(acquiredAt)) {
                throw new IllegalArgumentException("lease expiry must follow acquisition");
            }
        }
    }

    public record CapacityLease(
            String id,
            String operationId,
            WorkflowSource source,
            Set<CapacityLane> lanes,
            CapacityScope scope,
            boolean trunkControl,
            boolean exclusiveTask,
            boolean writerRequired,
            String leaseOwner,
            Long writerFencingToken,
            Instant acquiredAt,
            Instant heartbeatAt,
            Instant expiresAt,
            Instant releasedAt)
    {
        public CapacityLease
        {
            requireNonNull(id, "id is null");
            requireNonNull(operationId, "operationId is null");
            requireNonNull(source, "source is null");
            requireNonNull(lanes, "lanes is null");
            lanes = Set.copyOf(lanes);
            requireNonNull(scope, "scope is null");
            requireNonNull(leaseOwner, "leaseOwner is null");
            requireNonNull(acquiredAt, "acquiredAt is null");
            requireNonNull(heartbeatAt, "heartbeatAt is null");
            requireNonNull(expiresAt, "expiresAt is null");
            if (writerRequired && writerFencingToken == null) {
                throw new IllegalArgumentException("writer lease requires a fencing token");
            }
        }

        public boolean isActiveAt(Instant instant)
        {
            requireNonNull(instant, "instant is null");
            return releasedAt == null && expiresAt.isAfter(instant);
        }

        public boolean covers(CapacityRequest request, String expectedLeaseOwner)
        {
            requireNonNull(request, "request is null");
            requireNonNull(expectedLeaseOwner, "expectedLeaseOwner is null");
            return operationId.equals(request.operationId())
                    && source == request.source()
                    && lanes.equals(request.lanes())
                    && scope.equals(request.scope())
                    && trunkControl == request.trunkControl()
                    && exclusiveTask == request.exclusiveTask()
                    && writerRequired == request.writerRequired()
                    && leaseOwner.equals(expectedLeaseOwner);
        }
    }

    public record Admission(Optional<CapacityLease> lease, Denial denial)
    {
        public Admission
        {
            requireNonNull(lease, "lease is null");
            if (lease.isPresent() == (denial != null)) {
                throw new IllegalArgumentException(
                        "admission must contain exactly one lease or denial");
            }
        }

        public static Admission admitted(CapacityLease lease)
        {
            return new Admission(Optional.of(requireNonNull(lease, "lease is null")), null);
        }

        public static Admission denied(Denial denial)
        {
            return new Admission(Optional.empty(), requireNonNull(denial, "denial is null"));
        }

        public boolean isAdmitted()
        {
            return lease.isPresent();
        }
    }

    public record CapacityPolicy(
            Map<CapacityLane, Integer> laneLimits,
            Map<CapacityLane, Integer> reservedTrunkControl,
            int defaultWorkspaceTaskLimit,
            int defaultTrunkTaskLimit,
            Map<String, Integer> workspaceTaskLimits,
            Map<String, Integer> trunkTaskLimits)
    {
        public CapacityPolicy
        {
            Map<CapacityLane, Integer> checkedLaneLimits =
                    positiveMap(laneLimits, "laneLimits");
            Map<CapacityLane, Integer> checkedReservations = nonNegativeMap(
                    reservedTrunkControl, "reservedTrunkControl");
            laneLimits = checkedLaneLimits;
            reservedTrunkControl = checkedReservations;
            if (defaultWorkspaceTaskLimit < 1 || defaultTrunkTaskLimit < 1) {
                throw new IllegalArgumentException(
                        "default Workspace and Trunk limits must be positive");
            }
            workspaceTaskLimits = positiveStringMap(
                    workspaceTaskLimits, "workspaceTaskLimits");
            trunkTaskLimits = positiveStringMap(trunkTaskLimits, "trunkTaskLimits");
            checkedReservations.forEach((lane, reserved) -> {
                Integer limit = checkedLaneLimits.get(lane);
                if (limit == null || reserved >= limit) {
                    throw new IllegalArgumentException(
                            "reserved control capacity must be below lane limit for " + lane);
                }
            });
        }

        public static CapacityPolicy initial(
                int defaultWorkspaceTaskLimit,
                int defaultTrunkTaskLimit,
                Map<CapacityLane, Integer> additionalLaneLimits)
        {
            requireNonNull(additionalLaneLimits, "additionalLaneLimits is null");
            if (additionalLaneLimits.containsKey(CapacityLane.CLI)
                    || additionalLaneLimits.containsKey(CapacityLane.API)) {
                throw new IllegalArgumentException(
                        "initial CLI and API hard ceilings cannot be overridden");
            }
            Map<CapacityLane, Integer> limits = new LinkedHashMap<>();
            limits.put(CapacityLane.CLI, 4);
            limits.put(CapacityLane.API, 6);
            limits.putAll(additionalLaneLimits);
            return new CapacityPolicy(
                    limits,
                    Map.of(CapacityLane.CLI, 1, CapacityLane.API, 1),
                    defaultWorkspaceTaskLimit,
                    defaultTrunkTaskLimit,
                    Map.of(),
                    Map.of());
        }

        public int workspaceLimit(String workspaceId)
        {
            return workspaceId == null ? Integer.MAX_VALUE
                    : workspaceTaskLimits.getOrDefault(
                            workspaceId, defaultWorkspaceTaskLimit);
        }

        public int trunkLimit(String trunkId)
        {
            return trunkId == null ? Integer.MAX_VALUE
                    : trunkTaskLimits.getOrDefault(trunkId, defaultTrunkTaskLimit);
        }

        private static Map<CapacityLane, Integer> positiveMap(
                Map<CapacityLane, Integer> values,
                String name)
        {
            requireNonNull(values, name + " is null");
            Map<CapacityLane, Integer> copy = new LinkedHashMap<>(values);
            copy.forEach((key, value) -> {
                requireNonNull(key, name + " contains null key");
                if (value == null || value < 1) {
                    throw new IllegalArgumentException(name + " must contain positive values");
                }
            });
            return Map.copyOf(copy);
        }

        private static Map<CapacityLane, Integer> nonNegativeMap(
                Map<CapacityLane, Integer> values,
                String name)
        {
            requireNonNull(values, name + " is null");
            Map<CapacityLane, Integer> copy = new LinkedHashMap<>(values);
            copy.forEach((key, value) -> {
                requireNonNull(key, name + " contains null key");
                if (value == null || value < 0) {
                    throw new IllegalArgumentException(
                            name + " must contain non-negative values");
                }
            });
            return Map.copyOf(copy);
        }

        private static Map<String, Integer> positiveStringMap(
                Map<String, Integer> values,
                String name)
        {
            requireNonNull(values, name + " is null");
            Map<String, Integer> copy = new LinkedHashMap<>(values);
            copy.forEach((key, value) -> {
                if (key == null || key.isBlank() || value == null || value < 1) {
                    throw new IllegalArgumentException(
                            name + " must contain non-blank keys and positive values");
                }
            });
            return Map.copyOf(copy);
        }
    }

    @FunctionalInterface
    public interface CapacityPolicySource
    {
        CapacityPolicy current();
    }

    /** Persistence only; callers must not make policy decisions here. */
    public interface CapacityLeaseStore
    {
        List<CapacityLease> listActive(Instant now);

        Optional<CapacityLease> findActiveByOperation(String operationId, Instant now);

        Optional<CapacityLease> findById(String leaseId);

        Optional<CapacityLease> create(CapacityLeaseDraft draft);

        Optional<CapacityLease> heartbeat(
                String leaseId,
                String leaseOwner,
                Instant heartbeatAt,
                Instant expiresAt);

        boolean release(String leaseId, String leaseOwner, Instant releasedAt);

        List<CapacityLease> expire(Instant now);
    }

    private static void requireNonBlankIfPresent(String value, String name)
    {
        if (value != null && value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
