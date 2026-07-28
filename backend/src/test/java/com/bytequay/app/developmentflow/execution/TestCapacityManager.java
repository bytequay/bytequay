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
import java.util.concurrent.atomic.AtomicInteger;

import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.API;
import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.CLI;
import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.GITHUB;
import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.LOCAL_GIT;
import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.VALIDATION;
import static com.bytequay.app.developmentflow.execution.CapacityManager.Denial.LANE_LIMIT;
import static com.bytequay.app.developmentflow.execution.CapacityManager.Denial.TASK_MUTATION_LIMIT;
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

        assertAdmitted(fixture.manager.tryAcquire(
                request("legacy-1", LEGACY, CLI, "w1", "t1", "task-1", false, false),
                "legacy-worker-1"));
        assertAdmitted(fixture.manager.tryAcquire(
                request("v2-1", V2, CLI, "w2", "t2", "task-2", false, false),
                "dispatcher"));
        assertAdmitted(fixture.manager.tryAcquire(
                request("v2-2", V2, CLI, "w3", "t3", "task-3", false, false),
                "dispatcher"));

        assertDenied(fixture.manager.tryAcquire(
                request("ordinary-blocked", V2, CLI, "w4", "t4", "task-4", false, false),
                "dispatcher"), LANE_LIMIT);
        assertAdmitted(fixture.manager.tryAcquire(
                request("trunk-control", V2, CLI, "w4", "t4", null, true, false),
                "dispatcher"));
        assertDenied(fixture.manager.tryAcquire(
                request("control-blocked", V2, CLI, "w5", "t5", null, true, false),
                "dispatcher"), LANE_LIMIT);

        assertThat(fixture.store.activeCount(NOW)).isEqualTo(4);
    }

    @Test
    void reservesOneOfSixApiPermitsForTrunkControl()
    {
        Fixture fixture = fixture(policy(100, 100));

        for (int index = 0; index < 5; index++) {
            assertAdmitted(fixture.manager.tryAcquire(
                    request("api-" + index, V2, API,
                            "w" + index, "t" + index, "task-" + index, false, false),
                    "dispatcher"));
        }
        assertDenied(fixture.manager.tryAcquire(
                request("api-ordinary-blocked", LEGACY, API,
                        "wx", "tx", "task-x", false, false),
                "legacy-worker"), LANE_LIMIT);
        assertAdmitted(fixture.manager.tryAcquire(
                request("api-control", V2, API, "wx", "tx", null, true, false),
                "dispatcher"));
    }

    @Test
    void countsDistinctExecutingTasksAtWorkspaceAndTrunkScopes()
    {
        Fixture fixture = fixture(policy(2, 1));

        assertAdmitted(fixture.manager.tryAcquire(
                request("a-read-1", V2, VALIDATION, "w1", "trunk-a", "task-a", false, false),
                "dispatcher"));
        assertAdmitted(fixture.manager.tryAcquire(
                request("a-read-2", V2, VALIDATION, "w1", "trunk-a", "task-a", false, false),
                "dispatcher"));
        assertDenied(fixture.manager.tryAcquire(
                request("b-same-trunk", V2, VALIDATION,
                        "w1", "trunk-a", "task-b", false, false),
                "dispatcher"), TRUNK_LIMIT);
        assertAdmitted(fixture.manager.tryAcquire(
                request("b-other-trunk", V2, VALIDATION,
                        "w1", "trunk-b", "task-b", false, false),
                "dispatcher"));
        assertDenied(fixture.manager.tryAcquire(
                request("c-workspace-full", V2, VALIDATION,
                        "w1", "trunk-c", "task-c", false, false),
                "dispatcher"), WORKSPACE_LIMIT);
        assertAdmitted(fixture.manager.tryAcquire(
                request("d-other-workspace", V2, VALIDATION,
                        "w2", "trunk-d", "task-d", false, false),
                "dispatcher"));
    }

    @Test
    void permitsOnlyOneMutatingLeasePerTaskButAllowsReadOnlyWork()
    {
        Fixture fixture = fixture(policy(100, 100));

        assertAdmitted(fixture.manager.tryAcquire(
                request("writer-1", V2, LOCAL_GIT,
                        "w1", "trunk-a", "task-a", false, true),
                "dispatcher"));
        assertDenied(fixture.manager.tryAcquire(
                request("writer-2", V2, LOCAL_GIT,
                        "w1", "trunk-a", "task-a", false, true),
                "dispatcher"), TASK_MUTATION_LIMIT);
        assertAdmitted(fixture.manager.tryAcquire(
                request("read-only", V2, VALIDATION,
                        "w1", "trunk-a", "task-a", false, false),
                "dispatcher"));
    }

    @Test
    void validatesExactLeaseAndAdvancesWriterFencingToken()
    {
        Fixture fixture = fixture(policy(100, 100));
        CapacityManager.CapacityRequest firstRequest = request(
                "writer-1", V2, LOCAL_GIT,
                "w1", "trunk-a", "task-a", false, true);
        CapacityManager.CapacityLease first = fixture.manager.tryAcquire(
                firstRequest, "dispatcher").lease().orElseThrow();

        assertThat(first.writerFencingToken()).isEqualTo(1L);
        assertThat(fixture.manager.requireExactLease(
                first.id(), firstRequest, "dispatcher")).isEqualTo(first);
        assertThatThrownBy(() -> fixture.manager.requireExactLease(
                first.id(), firstRequest, "other-dispatcher"))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> fixture.manager.requireExactLease(
                first.id(), request("writer-1", V2, GITHUB,
                        "w1", "trunk-a", "task-a", false, true), "dispatcher"))
                .isInstanceOf(IllegalStateException.class);

        fixture.manager.release(first.id(), "dispatcher");
        CapacityManager.CapacityLease second = fixture.manager.tryAcquire(
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

        assertDenied(fixture.manager.tryAcquire(
                request("unconfigured", V2, VALIDATION,
                        "w1", "t1", "task-1", false, false),
                "dispatcher"), UNCONFIGURED_LANE);
        assertThat(fixture.store.activeCount(NOW)).isZero();

        CapacityManager.CapacityLease lease = fixture.manager.tryAcquire(
                request("configured", V2, CLI,
                        "w1", "t1", "task-1", false, false),
                "dispatcher").lease().orElseThrow();
        fixture.clock.advance(Duration.ofSeconds(31));

        assertThat(fixture.manager.expireLeases()).extracting(CapacityManager.CapacityLease::id)
                .containsExactly(lease.id());
        assertThat(fixture.store.activeCount(fixture.clock.instant())).isZero();
        assertThatThrownBy(() -> fixture.manager.requireExactLease(
                lease.id(), leaseRequest(lease), "dispatcher"))
                .isInstanceOf(IllegalStateException.class);
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
    void initialHardCeilingsCannotBeOverridden()
    {
        assertThatThrownBy(() -> CapacityManager.CapacityPolicy.initial(
                1, 1, Map.of(CLI, 10)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be overridden");
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
                Map.of(VALIDATION, 20, LOCAL_GIT, 20, GITHUB, 20));
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
        return new CapacityManager.CapacityRequest(
                operationId,
                source,
                Set.of(lane),
                new CapacityManager.CapacityScope(
                        workspaceId, trunkId, taskId, taskId == null ? null : 1L),
                trunkControl,
                writer,
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
            CapacityManager manager) {}

    private record Candidate(
            String id,
            CapacityManager.CapacityRequest request,
            Instant createdAt) {}
}
