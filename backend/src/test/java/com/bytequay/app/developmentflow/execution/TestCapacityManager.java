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

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.API;
import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.CLI;
import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.GITHUB;
import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.LOCAL_GIT;
import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.REVIEW;
import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.VALIDATION;
import static com.bytequay.app.developmentflow.execution.CapacityManager.Denial.LANE_LIMIT;
import static com.bytequay.app.developmentflow.execution.CapacityManager.Denial.TASK_MUTATION_LIMIT;
import static com.bytequay.app.developmentflow.execution.CapacityManager.Denial.TASK_SCOPE_CONFLICT;
import static com.bytequay.app.developmentflow.execution.CapacityManager.Denial.TRUNK_LIMIT;
import static com.bytequay.app.developmentflow.execution.CapacityManager.Denial.UNCONFIGURED_LANE;
import static com.bytequay.app.developmentflow.execution.CapacityManager.Denial.WORKSPACE_LIMIT;
import static com.bytequay.app.developmentflow.execution.CapacityManager.WorkflowSource.LEGACY;
import static com.bytequay.app.developmentflow.execution.CapacityManager.WorkflowSource.V2;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestCapacityManager
{
    private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");

    @Test
    void sharesHardCliCeilingAndReservedControlPermitAcrossLegacyAndV2()
    {
        Fixture fixture = fixture(policy(100, 100));

        assertAdmitted(fixture.tryAcquire(
                request("legacy-1", LEGACY, CLI, "w1", "t1", "task-1", false, false),
                "legacy-worker-1"));
        assertAdmitted(fixture.tryAcquire(
                request("v2-1", V2, CLI, "w2", "t2", "task-2", false, false),
                "dispatcher"));
        assertAdmitted(fixture.tryAcquire(
                request("v2-2", V2, CLI, "w3", "t3", "task-3", false, false),
                "dispatcher"));

        assertDenied(fixture.tryAcquire(
                request("ordinary-blocked", V2, CLI, "w4", "t4", "task-4", false, false),
                "dispatcher"), LANE_LIMIT);
        assertAdmitted(fixture.tryAcquire(
                request("trunk-control", V2, CLI, "w4", "t4", null, true, false),
                "dispatcher"));
        assertDenied(fixture.tryAcquire(
                request("control-blocked", V2, CLI, "w5", "t5", null, true, false),
                "dispatcher"), LANE_LIMIT);

        assertThat(fixture.store.activeCount(NOW)).isEqualTo(4);

        Fixture controlFirst = fixture(policy(100, 100));
        assertAdmitted(controlFirst.tryAcquire(
                request("control-first", V2, CLI, "w0", "t0", null, true, false),
                "dispatcher"));
        for (int index = 0; index < 3; index++) {
            assertAdmitted(controlFirst.tryAcquire(
                    request("ordinary-after-control-" + index, V2, CLI,
                            "w" + index, "t" + index, "task-" + index, false, false),
                    "dispatcher"));
        }
        assertThat(controlFirst.store.activeCount(NOW)).isEqualTo(4);
    }

    @Test
    void reservesOneOfSixApiPermitsForTrunkControl()
    {
        Fixture fixture = fixture(policy(100, 100));

        for (int index = 0; index < 5; index++) {
            assertAdmitted(fixture.tryAcquire(
                    request("api-" + index, V2, API,
                            "w" + index, "t" + index, "task-" + index, false, false),
                    "dispatcher"));
        }
        assertDenied(fixture.tryAcquire(
                request("api-ordinary-blocked", LEGACY, API,
                        "wx", "tx", "task-x", false, false),
                "legacy-worker"), LANE_LIMIT);
        assertAdmitted(fixture.tryAcquire(
                request("api-control", V2, API, "wx", "tx", null, true, false),
                "dispatcher"));
    }

    @Test
    void countsDistinctExecutingTasksAtWorkspaceAndTrunkScopes()
    {
        Fixture fixture = fixture(policy(2, 1));

        assertAdmitted(fixture.tryAcquire(
                request("a-read-1", V2, REVIEW, "w1", "trunk-a", "task-a", false, false),
                "dispatcher"));
        assertAdmitted(fixture.tryAcquire(
                request("a-read-2", V2, REVIEW, "w1", "trunk-a", "task-a", false, false),
                "dispatcher"));
        assertDenied(fixture.tryAcquire(
                request("b-same-trunk", V2, REVIEW,
                        "w1", "trunk-a", "task-b", false, false),
                "dispatcher"), TRUNK_LIMIT);
        assertAdmitted(fixture.tryAcquire(
                request("b-other-trunk", V2, REVIEW,
                        "w1", "trunk-b", "task-b", false, false),
                "dispatcher"));
        assertDenied(fixture.tryAcquire(
                request("c-workspace-full", V2, REVIEW,
                        "w1", "trunk-c", "task-c", false, false),
                "dispatcher"), WORKSPACE_LIMIT);
        assertAdmitted(fixture.tryAcquire(
                request("d-other-workspace", V2, REVIEW,
                        "w2", "trunk-d", "task-d", false, false),
                "dispatcher"));
    }

    @Test
    void permitsOnlyOneMutatingLeasePerTaskButAllowsReadOnlyWork()
    {
        Fixture fixture = fixture(policy(100, 100));

        assertAdmitted(fixture.tryAcquire(
                request("writer-1", V2, LOCAL_GIT,
                        "w1", "trunk-a", "task-a", false, true),
                "dispatcher"));
        assertDenied(fixture.tryAcquire(
                request("writer-2", V2, LOCAL_GIT,
                        "w1", "trunk-a", "task-a", false, true),
                "dispatcher"), TASK_MUTATION_LIMIT);
        assertAdmitted(fixture.tryAcquire(
                request("read-only", V2, REVIEW,
                        "w1", "trunk-a", "task-a", false, false),
                "dispatcher"));
    }

    @Test
    void blocksASecondEpochOrRouteForAnExecutingTask()
    {
        Fixture fixture = fixture(policy(100, 100));
        assertAdmitted(fixture.tryAcquire(
                request("epoch-1", V2, VALIDATION,
                        "w1", "trunk-a", "task-a", false, false),
                "dispatcher"));
        CapacityManager.CapacityRequest nextEpoch = new CapacityManager.CapacityRequest(
                "epoch-2",
                V2,
                Set.of(VALIDATION),
                new CapacityManager.CapacityScope("w1", "trunk-a", "task-a", 2L),
                false,
                true,
                false);
        CapacityManager.CapacityRequest otherRoute = new CapacityManager.CapacityRequest(
                "other-route",
                V2,
                Set.of(VALIDATION),
                new CapacityManager.CapacityScope("w1", "trunk-b", "task-a", 1L),
                false,
                true,
                false);

        assertDenied(fixture.tryAcquire(nextEpoch, "dispatcher"), TASK_SCOPE_CONFLICT);
        assertDenied(fixture.tryAcquire(otherRoute, "dispatcher"), TASK_SCOPE_CONFLICT);
    }

    @Test
    void validatesExactLeaseAndAdvancesWriterFencingToken()
    {
        Fixture fixture = fixture(policy(100, 100));
        CapacityManager.CapacityRequest firstRequest = request(
                "writer-1", V2, LOCAL_GIT,
                "w1", "trunk-a", "task-a", false, true);
        CapacityManager.CapacityLease first = fixture.tryAcquire(
                firstRequest, "dispatcher").lease().orElseThrow();

        assertThat(first.ticketId()).isEqualTo("ticket-writer-1");
        assertThat(first.writerFencingToken()).isEqualTo(1L);
        assertThatThrownBy(() -> fixture.manager.tryAcquire(firstRequest, "dispatcher"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DispatchTicket");
        assertThat(fixture.requireExactLease(
                first.id(), firstRequest, "dispatcher")).isEqualTo(first);
        assertThatThrownBy(() -> fixture.requireExactLease(
                first.id(), firstRequest, "other-dispatcher"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> fixture.requireExactLease(
                first.id(), request("writer-1", V2, GITHUB,
                        "w1", "trunk-a", "task-a", false, true), "dispatcher"))
                .isInstanceOf(IllegalStateException.class);

        fixture.manager.release(first.id(), "dispatcher");
        assertThat(fixture.store.findById(first.id()).orElseThrow().releaseReason())
                .isEqualTo("RELEASED");
        CapacityManager.CapacityLease second = fixture.tryAcquire(
                request("writer-2", V2, LOCAL_GIT,
                        "w1", "trunk-a", "task-a", false, true),
                "dispatcher").lease().orElseThrow();
        assertThat(second.writerFencingToken()).isEqualTo(2L);
    }

    @Test
    void denialWritesNothingAndExpiredLeasesAreReclaimed()
    {
        Fixture fixture = fixture(CapacityManager.CapacityPolicy.initial(
                100, 100, Map.of()));

        assertDenied(fixture.tryAcquire(
                request("unconfigured", V2, VALIDATION,
                        "w1", "t1", "task-1", false, false),
                "dispatcher"), UNCONFIGURED_LANE);
        assertThat(fixture.store.activeCount(NOW)).isZero();

        CapacityManager.CapacityLease lease = fixture.tryAcquire(
                request("configured", V2, CLI,
                        "w1", "t1", "task-1", false, false),
                "dispatcher").lease().orElseThrow();
        fixture.clock.advance(Duration.ofSeconds(31));

        assertThat(fixture.manager.expireLeases()).extracting(CapacityManager.CapacityLease::id)
                .containsExactly(lease.id());
        assertThat(fixture.store.activeCount(fixture.clock.instant())).isZero();
        assertThatThrownBy(() -> fixture.requireExactLease(
                lease.id(), leaseRequest(lease), "dispatcher"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void admissionIsSerializedAcrossManagersSharingOneDurableStore()
            throws Exception
    {
        InMemoryExecutionSupport.MutableClock clock =
                new InMemoryExecutionSupport.MutableClock(NOW);
        InMemoryExecutionSupport.CapacityStore store =
                new InMemoryExecutionSupport.CapacityStore();
        CapacityManager.CapacityPolicy policy = CapacityManager.CapacityPolicy.initial(
                100, 100, Map.of(VALIDATION, 1));
        AtomicInteger ids = new AtomicInteger();
        CapacityManager first = new CapacityManager(
                store,
                () -> policy,
                clock,
                Duration.ofSeconds(30),
                () -> "first-lease-" + ids.incrementAndGet());
        CapacityManager second = new CapacityManager(
                store,
                () -> policy,
                clock,
                Duration.ofSeconds(30),
                () -> "second-lease-" + ids.incrementAndGet());
        CapacityManager.CapacityRequest firstRequest = request(
                "first", V2, VALIDATION,
                "workspace", "trunk", "first-task", false, false);
        CapacityManager.CapacityRequest secondRequest = request(
                "second", V2, VALIDATION,
                "workspace", "trunk", "second-task", false, false);
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService callers = Executors.newFixedThreadPool(2);
        try {
            Future<CapacityManager.Admission> firstAdmission = callers.submit(() -> {
                ready.countDown();
                start.await();
                return first.tryAcquireForTicket(
                        "first-ticket", firstRequest, "first-dispatcher");
            });
            Future<CapacityManager.Admission> secondAdmission = callers.submit(() -> {
                ready.countDown();
                start.await();
                return second.tryAcquireForTicket(
                        "second-ticket", secondRequest, "second-dispatcher");
            });
            assertThat(ready.await(2, TimeUnit.SECONDS)).isTrue();
            start.countDown();

            assertThat(List.of(
                    firstAdmission.get(2, TimeUnit.SECONDS),
                    secondAdmission.get(2, TimeUnit.SECONDS)))
                    .filteredOn(CapacityManager.Admission::isAdmitted)
                    .hasSize(1);
            assertThat(store.activeCount(NOW)).isEqualTo(1);
        }
        finally {
            callers.shutdownNow();
        }
    }

    @Test
    void appliesTwoLevelRoundRobinWithDurableFifoInsideEachTrunk()
    {
        Fixture fixture = fixture(policy(100, 100));
        List<Candidate> candidates = List.of(
                candidate("a2", "w1", "a", 4),
                candidate("c2", "w2", "c", 6),
                candidate("b1", "w1", "b", 3),
                candidate("a1", "w1", "a", 1),
                candidate("c1", "w2", "c", 2),
                candidate("b2", "w1", "b", 5));

        List<Candidate> ordered = fixture.manager.fairOrder(
                candidates, Candidate::request, Candidate::createdAt, Candidate::id);

        assertThat(ordered).extracting(Candidate::id)
                .containsExactly("a1", "c1", "b1", "c2", "a2", "b2");
        assertThat(fixture.manager.fairOrder(
                List.of(candidate("next-a", "w1", "a", 8),
                        candidate("next-c", "w2", "c", 7)),
                Candidate::request,
                Candidate::createdAt,
                Candidate::id))
                .extracting(Candidate::id)
                .containsExactly("next-c", "next-a");
    }

    @Test
    void rotatesTrunksBetweenAdmissionSweeps()
    {
        Fixture fixture = fixture(policy(100, 100));

        assertThat(fixture.manager.fairOrder(
                List.of(candidate("only-a", "w1", "a", 1)),
                Candidate::request,
                Candidate::createdAt,
                Candidate::id))
                .extracting(Candidate::id)
                .containsExactly("only-a");
        assertThat(fixture.manager.fairOrder(
                List.of(candidate("again-a", "w1", "a", 2),
                        candidate("now-b", "w1", "b", 3)),
                Candidate::request,
                Candidate::createdAt,
                Candidate::id))
                .extracting(Candidate::id)
                .containsExactly("now-b", "again-a");
    }

    @Test
    void legacyPermitUsesSameManagerAndReleasesIdempotently()
    {
        Fixture fixture = fixture(policy(100, 100));
        LegacyCapacityBridge bridge = new LegacyCapacityBridge(fixture.manager);
        CapacityManager.CapacityRequest request = request(
                "legacy", LEGACY, CLI, "w1", "t1", "task-1", false, false);

        LegacyCapacityBridge.Permit permit = bridge.tryAcquire(
                request, "legacy-worker").orElseThrow();
        assertThat(permit.lease().operationId()).isEqualTo("legacy");
        fixture.clock.advance(Duration.ofSeconds(10));
        permit.heartbeat();
        permit.close();
        permit.close();

        assertThat(fixture.store.activeCount(fixture.clock.instant())).isZero();
        assertThatThrownBy(permit::lease).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void bridgeAndV2ShareLaneWorkspaceTrunkAndTaskCaps()
    {
        Fixture fixture = fixture(policy(2, 1));
        LegacyCapacityBridge bridge = new LegacyCapacityBridge(fixture.manager);

        LegacyCapacityBridge.Permit first = bridge.tryAcquire(
                request("legacy-a", LEGACY, CLI,
                        "w1", "trunk-a", "task-a", false, false),
                "legacy-a").orElseThrow();
        assertDenied(fixture.tryAcquire(
                request("v2-same-trunk", V2, API,
                        "w1", "trunk-a", "task-b", false, false),
                "dispatcher"), TRUNK_LIMIT);
        assertDenied(fixture.tryAcquire(
                request("v2-same-task", V2, API,
                        "w1", "trunk-a", "task-a", false, false),
                "dispatcher"), TASK_MUTATION_LIMIT);
        assertAdmitted(fixture.tryAcquire(
                request("v2-other-trunk", V2, API,
                        "w1", "trunk-b", "task-b", false, false),
                "dispatcher"));
        assertDenied(fixture.tryAcquire(
                request("legacy-workspace-full", LEGACY, API,
                        "w1", "trunk-c", "task-c", false, false),
                "legacy-c"), WORKSPACE_LIMIT);

        assertThat(fixture.store.activeCount(NOW)).isEqualTo(2);
        first.close();
        assertThat(fixture.store.activeCount(NOW)).isEqualTo(1);
    }

    @Test
    void releaseAndExpiryWakeRegisteredWaitersAndDeregistrationStopsHints()
    {
        Fixture fixture = fixture(policy(100, 100));
        AtomicInteger wakes = new AtomicInteger();
        CapacityManager.AvailabilityRegistration registration =
                fixture.manager.onCapacityAvailable(wakes::incrementAndGet);
        CapacityManager.CapacityLease first = fixture.tryAcquire(
                request("first", LEGACY, CLI,
                        "w1", "t1", "task-1", false, false),
                "legacy").lease().orElseThrow();

        fixture.manager.release(first.id(), "legacy");
        assertThat(wakes).hasValue(1);

        CapacityManager.CapacityLease expiring = fixture.tryAcquire(
                request("expiring", LEGACY, CLI,
                        "w1", "t1", "task-2", false, false),
                "legacy").lease().orElseThrow();
        fixture.clock.advance(Duration.ofSeconds(31));
        assertThat(fixture.manager.expireLeases()).extracting(CapacityManager.CapacityLease::id)
                .containsExactly(expiring.id());
        assertThat(wakes).hasValue(2);

        registration.close();
        CapacityManager.CapacityLease afterClose = fixture.tryAcquire(
                request("after-close", LEGACY, CLI,
                        "w1", "t1", "task-3", false, false),
                "legacy").lease().orElseThrow();
        fixture.manager.release(afterClose.id(), "legacy");
        assertThat(wakes).hasValue(2);
    }

    @Test
    void bridgeStopsExactlyOnceAfterDefinitiveLeaseLoss()
    {
        Fixture fixture = fixture(policy(100, 100));
        LegacyCapacityBridge bridge = new LegacyCapacityBridge(fixture.manager);
        AtomicInteger stops = new AtomicInteger();
        LegacyCapacityBridge.Permit permit = bridge.tryAcquire(
                request("lost", LEGACY, CLI,
                        "w1", "t1", "task-1", false, false),
                "legacy",
                stops::incrementAndGet).orElseThrow();

        fixture.clock.advance(Duration.ofSeconds(31));
        bridge.maintainLeases();
        bridge.maintainLeases();

        assertThat(stops).hasValue(1);
        assertThat(bridge.activePermitCount()).isZero();
        assertThatThrownBy(permit::lease).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void transientHeartbeatFailureRetriesBeforeExpiry()
    {
        Fixture fixture = fixture(policy(100, 100));
        LegacyCapacityBridge bridge = new LegacyCapacityBridge(fixture.manager);
        AtomicInteger stops = new AtomicInteger();
        LegacyCapacityBridge.Permit permit = bridge.tryAcquire(
                request("retry-heartbeat", LEGACY, API,
                        "w1", "t1", "task-1", false, false),
                "legacy",
                stops::incrementAndGet).orElseThrow();

        fixture.clock.advance(Duration.ofSeconds(10));
        fixture.store.failNextHeartbeat();
        bridge.maintainLeases();
        assertThat(stops).hasValue(0);
        assertThat(bridge.activePermitCount()).isEqualTo(1);

        fixture.clock.advance(Duration.ofSeconds(5));
        bridge.maintainLeases();
        assertThat(permit.lease().expiresAt())
                .isEqualTo(fixture.clock.instant().plusSeconds(30));
        assertThat(stops).hasValue(0);
        permit.close();
    }

    @Test
    void failedCloseIsRetriedWithoutLeakingOrRenewingCompletedWork()
    {
        Fixture fixture = fixture(policy(100, 100));
        LegacyCapacityBridge bridge = new LegacyCapacityBridge(fixture.manager);
        LegacyCapacityBridge.Permit permit = bridge.tryAcquire(
                request("release-retry", LEGACY, CLI,
                        "w1", "t1", "task-1", false, false),
                "legacy").orElseThrow();

        fixture.store.failNextRelease();
        assertThatThrownBy(permit::close)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("release failed");
        assertThat(bridge.activePermitCount()).isEqualTo(1);

        bridge.maintainLeases();

        assertThat(bridge.activePermitCount()).isZero();
        assertThat(fixture.store.activeCount(fixture.clock.instant())).isZero();
    }

    @Test
    void initialHardCeilingsCannotBeOverridden()
    {
        assertThatThrownBy(() -> CapacityManager.CapacityPolicy.initial(
                1, 1, Map.of(CLI, 10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be overridden");
    }

    @Test
    void laneProfilesCannotBypassWriterOrTaskExclusivity()
    {
        CapacityManager.CapacityScope scope =
                new CapacityManager.CapacityScope("w1", "t1", "task-1", 1L);

        assertThatThrownBy(() -> new CapacityManager.CapacityRequest(
                "git-without-writer", V2, Set.of(LOCAL_GIT), scope,
                false, false, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("LOCAL_GIT");
        assertThatThrownBy(() -> new CapacityManager.CapacityRequest(
                "validation-without-mutex", V2, Set.of(VALIDATION), scope,
                false, false, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("VALIDATION");
        assertThatThrownBy(() -> new CapacityManager.CapacityRequest(
                "mutating-review", V2, Set.of(REVIEW), scope,
                false, true, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("read-only");
    }

    @Test
    void laneSetsRoundTripThroughTheDurableMaskContract()
    {
        assertThat(CapacityManager.CapacityLane.values())
                .extracting(CapacityManager.CapacityLane::maskBit)
                .containsExactly(1, 2, 4, 8, 16, 32, 64, 128, 256);
        Set<CapacityManager.CapacityLane> lanes = Set.of(CLI, LOCAL_GIT, GITHUB);

        int mask = CapacityManager.CapacityLane.toMask(lanes);

        assertThat(mask).isEqualTo(49);
        assertThat(CapacityManager.CapacityLane.fromMask(mask)).containsExactlyInAnyOrderElementsOf(
                lanes);
        assertThatThrownBy(() -> CapacityManager.CapacityLane.fromMask(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CapacityManager.CapacityLane.fromMask(512))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Fixture fixture(CapacityManager.CapacityPolicy policy)
    {
        InMemoryExecutionSupport.MutableClock clock =
                new InMemoryExecutionSupport.MutableClock(NOW);
        InMemoryExecutionSupport.CapacityStore store =
                new InMemoryExecutionSupport.CapacityStore();
        AtomicInteger ids = new AtomicInteger();
        CapacityManager manager = new CapacityManager(
                store,
                () -> policy,
                clock,
                Duration.ofSeconds(30),
                () -> "lease-" + ids.incrementAndGet());
        return new Fixture(clock, store, manager);
    }

    private static CapacityManager.CapacityPolicy policy(
            int workspaceLimit,
            int trunkLimit)
    {
        return CapacityManager.CapacityPolicy.initial(
                workspaceLimit,
                trunkLimit,
                Map.of(VALIDATION, 20, REVIEW, 20, LOCAL_GIT, 20, GITHUB, 20));
    }

    private static CapacityManager.CapacityRequest request(
            String operationId,
            CapacityManager.WorkflowSource source,
            CapacityManager.CapacityLane lane,
            String workspaceId,
            String trunkId,
            String taskId,
            boolean trunkControl,
            boolean writer)
    {
        boolean exclusive = taskId != null && switch (lane) {
            case CLI, API, VALIDATION, LOCAL_GIT, GITHUB, MERGE, CLEANUP -> true;
            case REVIEW, REMOTE_OBSERVATION -> false;
        };
        return new CapacityManager.CapacityRequest(
                operationId,
                source,
                Set.of(lane),
                new CapacityManager.CapacityScope(
                        workspaceId, trunkId, taskId, taskId == null ? null : 1L),
                trunkControl,
                exclusive,
                writer);
    }

    private static CapacityManager.CapacityRequest leaseRequest(
            CapacityManager.CapacityLease lease)
    {
        return new CapacityManager.CapacityRequest(
                lease.operationId(),
                lease.source(),
                lease.lanes(),
                lease.scope(),
                lease.trunkControl(),
                lease.exclusiveTask(),
                lease.writerRequired());
    }

    private static Candidate candidate(
            String id,
            String workspaceId,
            String trunkId,
            long seconds)
    {
        return new Candidate(
                id,
                request(id, V2, VALIDATION,
                        workspaceId, trunkId, id + "-task", false, false),
                NOW.plusSeconds(seconds));
    }

    private static void assertAdmitted(CapacityManager.Admission admission)
    {
        assertThat(admission.isAdmitted()).isTrue();
        assertThat(admission.denial()).isNull();
    }

    private static void assertDenied(
            CapacityManager.Admission admission,
            CapacityManager.Denial denial)
    {
        assertThat(admission.isAdmitted()).isFalse();
        assertThat(admission.denial()).isEqualTo(denial);
    }

    private record Fixture(
            InMemoryExecutionSupport.MutableClock clock,
            InMemoryExecutionSupport.CapacityStore store,
            CapacityManager manager)
    {
        private CapacityManager.Admission tryAcquire(
                CapacityManager.CapacityRequest request,
                String leaseOwner)
        {
            return request.source() == V2
                    ? manager.tryAcquireForTicket(
                            ticketId(request), request, leaseOwner)
                    : manager.tryAcquire(request, leaseOwner);
        }

        private CapacityManager.CapacityLease requireExactLease(
                String leaseId,
                CapacityManager.CapacityRequest request,
                String leaseOwner)
        {
            return request.source() == V2
                    ? manager.requireExactLeaseForTicket(
                            ticketId(request), leaseId, request, leaseOwner)
                    : manager.requireExactLease(leaseId, request, leaseOwner);
        }

        private static String ticketId(CapacityManager.CapacityRequest request)
        {
            return "ticket-" + request.operationId();
        }
    }

    private record Candidate(
            String id,
            CapacityManager.CapacityRequest request,
            Instant createdAt) {}
}
