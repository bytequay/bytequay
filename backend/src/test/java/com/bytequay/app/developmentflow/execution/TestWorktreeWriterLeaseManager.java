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

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.API;
import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.LOCAL_GIT;
import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.REVIEW;
import static com.bytequay.app.developmentflow.execution.CapacityManager.WorkflowSource.V2;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestWorktreeWriterLeaseManager
{
    private static final Instant START = Instant.parse("2026-07-28T00:00:00Z");
    private static final String TICKET = "ticket-1";
    private static final String OWNER = "dispatcher-1";
    private static final String WORKTREE = "/tmp/task-1";

    private InMemoryExecutionSupport.MutableClock clock;
    private InMemoryExecutionSupport.CapacityStore capacityStore;
    private CapacityManager capacityManager;
    private InMemoryExecutionSupport.WorktreeStore worktreeStore;
    private WorktreeWriterLeaseManager manager;

    @BeforeEach
    void setUp()
    {
        clock = new InMemoryExecutionSupport.MutableClock(START);
        capacityStore = new InMemoryExecutionSupport.CapacityStore();
        capacityManager = new CapacityManager(
                capacityStore,
                () -> CapacityManager.CapacityPolicy.initial(
                        4, 4, Map.of(LOCAL_GIT, 4, REVIEW, 4)),
                clock,
                Duration.ofMinutes(1));
        worktreeStore = new InMemoryExecutionSupport.WorktreeStore();
        manager = new WorktreeWriterLeaseManager(worktreeStore, clock);
    }

    @Test
    void mutationRequiresBothExactLiveLeases()
    {
        ContextFixture fixture = writerContext("operation-1", TICKET, OWNER);

        WorktreeWriterLeaseManager.Lease lease = manager.acquire(
                fixture.context(), WORKTREE);
        WorktreeWriterLeaseManager.WriterAuthorization authorization =
                manager.authorizeMutation(fixture.context(), lease);
        WorktreeWriterLeaseManager.MutationFence fence = authorization.run(
                value -> value);

        assertThat(fence.worktreePath()).isEqualTo(WORKTREE);
        assertThat(fence.taskId()).isEqualTo("task-1");
        assertThat(fence.operationId()).isEqualTo("operation-1");
        assertThat(fence.taskEpoch()).isEqualTo(3);
        assertThat(fence.fencingToken()).isEqualTo(1);

        WorktreeWriterLeaseManager.WriterAuthorization revoked =
                manager.authorizeMutation(fixture.context(), lease);
        capacityManager.release(fixture.capacity().id(), OWNER);
        assertThatThrownBy(() -> revoked.run(value -> value))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("capacity lease");
        assertThatThrownBy(() -> authorization.run(value -> value))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already consumed");
    }

    @Test
    void readOnlyExecutionCannotAcquireWriterLease()
    {
        CapacityManager.CapacityRequest request = new CapacityManager.CapacityRequest(
                "read-only",
                V2,
                Set.of(API, REVIEW),
                new CapacityManager.CapacityScope(
                        "workspace-1", "trunk-1", "task-1", 3L),
                false,
                false,
                false);
        ContextFixture fixture = context(request, "ticket-read", OWNER);

        assertThatThrownBy(() -> manager.acquire(fixture.context(), WORKTREE))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("writer fencing token");
    }

    @Test
    void heartbeatCannotOutliveCapacity()
    {
        ContextFixture fixture = writerContext("operation-1", TICKET, OWNER);
        WorktreeWriterLeaseManager.Lease lease = manager.acquire(
                fixture.context(), WORKTREE);
        Instant firstExpiry = lease.expiresAt();

        clock.advance(Duration.ofSeconds(20));
        CapacityManager.CapacityLease renewedCapacity = capacityManager.heartbeat(
                fixture.capacity().id(), OWNER).orElseThrow();
        WorktreeWriterLeaseManager.Lease renewed = manager.heartbeat(
                fixture.context(), lease);

        assertThat(renewed.expiresAt()).isAfter(firstExpiry);
        assertThat(renewed.expiresAt()).isEqualTo(renewedCapacity.expiresAt());
        assertThat(manager.authorizeMutation(fixture.context(), lease)
                .run(WorktreeWriterLeaseManager.MutationFence::fencingToken))
                .isEqualTo(1);
    }

    @Test
    void executionContextHeartbeatsAndReleasesRegisteredWriterLease()
    {
        ContextFixture fixture = writerContext("operation-1", TICKET, OWNER);
        WorktreeWriterLeaseManager.Lease lease = manager.acquire(
                fixture.context(), WORKTREE);

        clock.advance(Duration.ofSeconds(20));
        CapacityManager.CapacityLease renewedCapacity = capacityManager.heartbeat(
                fixture.capacity().id(), OWNER).orElseThrow();
        fixture.context().heartbeatWriterResource();

        WorktreeWriterLeaseManager.Lease renewed = worktreeStore
                .findExact(lease, clock.instant())
                .orElseThrow();
        assertThat(renewed.expiresAt()).isEqualTo(renewedCapacity.expiresAt());

        fixture.context().closeWriterResource();
        fixture.context().closeWriterResource();
        assertThat(worktreeStore.findExact(lease, clock.instant())).isEmpty();
    }

    @Test
    void failedReleaseRemainsRegisteredForExactRetry()
    {
        ContextFixture fixture = writerContext("operation-1", TICKET, OWNER);
        WorktreeWriterLeaseManager.Lease lease = manager.acquire(
                fixture.context(), WORKTREE);
        worktreeStore.failNextRelease();

        assertThatThrownBy(fixture.context()::closeWriterResource)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("release failed");
        assertThat(worktreeStore.findExact(lease, clock.instant())).contains(lease);

        fixture.context().closeWriterResource();
        assertThat(worktreeStore.findExact(lease, clock.instant())).isEmpty();
    }

    @Test
    void rejectsNonExactLeaseReturnedByStore()
    {
        ContextFixture fixture = writerContext("operation-1", TICKET, OWNER);
        AtomicReference<WorktreeWriterLeaseManager.Lease> released =
                new AtomicReference<>();
        WorktreeWriterLeaseManager defective = new WorktreeWriterLeaseManager(
                new WorktreeWriterLeaseManager.Store()
                {
                    @Override
                    public Optional<WorktreeWriterLeaseManager.Lease> tryAcquire(
                            WorktreeWriterLeaseManager.Lease requested,
                            Instant now)
                    {
                        return Optional.of(new WorktreeWriterLeaseManager.Lease(
                                requested.worktreePath(),
                                requested.taskId(),
                                "wrong-operation",
                                requested.taskEpoch(),
                                requested.fencingToken(),
                                requested.leaseOwner(),
                                requested.acquiredAt(),
                                requested.expiresAt()));
                    }

                    @Override
                    public Optional<WorktreeWriterLeaseManager.Lease> findExact(
                            WorktreeWriterLeaseManager.Lease expected,
                            Instant now)
                    {
                        return Optional.empty();
                    }

                    @Override
                    public Optional<WorktreeWriterLeaseManager.Lease> heartbeat(
                            WorktreeWriterLeaseManager.Lease expected,
                            Instant heartbeatAt,
                            Instant expiresAt)
                    {
                        return Optional.empty();
                    }

                    @Override
                    public boolean release(
                            WorktreeWriterLeaseManager.Lease expected,
                            Instant releasedAt)
                    {
                        released.set(expected);
                        return true;
                    }
                },
                clock);

        assertThatThrownBy(() -> defective.acquire(fixture.context(), WORKTREE))
                .isInstanceOf(WorktreeWriterLeaseManager.StaleWriterLeaseException.class)
                .hasMessageContaining("non-exact");
        assertThat(released.get())
                .extracting(WorktreeWriterLeaseManager.Lease::operationId)
                .isEqualTo("operation-1");
    }

    @Test
    void staleReleaseCannotDeleteReplacementLease()
    {
        ContextFixture first = writerContext("operation-1", TICKET, OWNER);
        WorktreeWriterLeaseManager.Lease stale = manager.acquire(first.context(), WORKTREE);
        manager.release(stale);
        capacityManager.release(first.capacity().id(), OWNER);

        ContextFixture second = writerContext(
                "operation-2", "ticket-2", "dispatcher-2");
        WorktreeWriterLeaseManager.Lease replacement = manager.acquire(
                second.context(), WORKTREE);

        manager.release(stale);

        assertThat(worktreeStore.findExact(replacement, clock.instant()))
                .contains(replacement);
        assertThat(replacement.fencingToken()).isEqualTo(2);
        assertThatThrownBy(() -> manager.authorizeMutation(first.context(), stale))
                .isInstanceOf(IllegalStateException.class);
        assertThat(manager.authorizeMutation(second.context(), replacement)
                .run(WorktreeWriterLeaseManager.MutationFence::fencingToken))
                .isEqualTo(2);
    }

    @Test
    void oneTaskCannotHoldTwoWriterWorktrees()
    {
        ContextFixture fixture = writerContext("operation-1", TICKET, OWNER);
        manager.acquire(fixture.context(), WORKTREE);

        assertThatThrownBy(() -> manager.acquire(
                fixture.context(), "/tmp/task-1-other"))
                .isInstanceOf(
                        WorktreeWriterLeaseManager.WriterLeaseUnavailableException.class);
    }

    private ContextFixture writerContext(
            String operationId,
            String ticketId,
            String owner)
    {
        CapacityManager.CapacityRequest request = new CapacityManager.CapacityRequest(
                operationId,
                V2,
                Set.of(LOCAL_GIT),
                new CapacityManager.CapacityScope(
                        "workspace-1", "trunk-1", "task-1", 3L),
                false,
                true,
                true);
        return context(request, ticketId, owner);
    }

    private ContextFixture context(
            CapacityManager.CapacityRequest request,
            String ticketId,
            String owner)
    {
        CapacityManager.CapacityLease capacity = capacityManager.tryAcquireForTicket(
                        ticketId, request, owner)
                .lease()
                .orElseThrow();
        DispatchTicket.DispatchEnvelope envelope = new DispatchTicket.DispatchEnvelope(
                "TEST_OPERATION",
                request.writerRequired()
                        ? DispatchTicket.AsyncFamily.LOCAL_GIT
                        : DispatchTicket.AsyncFamily.AGENT_TURN,
                new DispatchTicket.OwnerReference(
                        DispatchTicket.OwnerKind.STAGE,
                        "stage-1",
                        "test"),
                new DispatchTicket.OperationFence(
                        request.scope().taskEpoch(),
                        "stage-1",
                        2L,
                        request.operationId(),
                        1,
                        "fingerprint-1",
                        "head-1",
                        "base-1"),
                request);
        ExecutionContext context = new ExecutionContext(
                envelope,
                capacity,
                new ExecutionContext.Cancellation(),
                new NoopEvidence(),
                "execution-" + operationId(request),
                clock,
                () -> capacityManager.requireExactLeaseForTicket(
                        ticketId, capacity.id(), request, owner));
        return new ContextFixture(context, capacity);
    }

    private static String operationId(CapacityManager.CapacityRequest request)
    {
        return request.operationId();
    }

    private record ContextFixture(
            ExecutionContext context,
            CapacityManager.CapacityLease capacity) {}

    private static final class NoopEvidence
            implements ExecutionPorts.ExecutionEvidencePort
    {
        @Override
        public String start(
                DispatchTicket ticket,
                CapacityManager.CapacityLease lease,
                DispatchTicket.ClaimPurpose purpose,
                Instant startedAt)
        {
            return "execution";
        }

        @Override public void heartbeat(String executionId, Instant at) {}

        @Override public void providerSession(
                String executionId, String provider, String providerSessionId) {}

        @Override public void processStarted(
                String executionId, long processPid, String logReference) {}

        @Override public void appendLog(
                String executionId, long sequence, String payloadJson, Instant createdAt) {}

        @Override public void recordUsage(
                String executionId, long inputTokens, long outputTokens, long costUsdMilli) {}

        @Override public void finish(
                String executionId,
                DispatchTicket.DispatchResult result,
                String failure,
                Instant finishedAt) {}
    }
}
