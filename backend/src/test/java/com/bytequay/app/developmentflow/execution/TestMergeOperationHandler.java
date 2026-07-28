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

import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.AttemptStatus;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.BlockReason;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.ClaimMode;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.ClaimSpec;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.EffectAttempt;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.EffectClaim;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.EffectEvidence;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.MergeMode;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.MergeRequest;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.OperationSnapshot;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.OperationStatus;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.QueueEntry;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.QueueEntryStatus;
import com.bytequay.app.developmentflow.execution.merge.MergeOperationHandler.RemoteTruthPendingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.MERGE;
import static com.bytequay.app.developmentflow.execution.CapacityManager.WorkflowSource.V2;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestMergeOperationHandler
{
    private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");

    @Test
    void restartProbesTheCommittedEffectWithoutExecutingItTwice()
            throws Exception
    {
        InMemoryExecutionSupport.MutableClock clock =
                new InMemoryExecutionSupport.MutableClock(NOW);
        MutableStore store = new MutableStore(request(OperationStatus.REQUESTED));
        RecordingEffects effects = new RecordingEffects();

        assertThatThrownBy(() -> handler(store, effects, clock)
                .execute(context(store.request, clock)))
                .isInstanceOf(ExecutionPorts.OperationDeferredException.class);
        assertThat(effects.executedKeys).containsExactly("operation:direct");
        assertThat(effects.probedKeys).isEmpty();
        assertThat(store.request.status())
                .isEqualTo(OperationStatus.AWAITING_OBSERVATION);

        clock.advance(Duration.ofSeconds(31));
        MergeOperationHandler restarted = handler(store, effects, clock);

        assertThatThrownBy(() -> restarted.reconcile(context(store.request, clock)))
                .isInstanceOf(ExecutionPorts.OperationDeferredException.class);
        assertThat(effects.executedKeys).containsExactly("operation:direct");
        assertThat(effects.probedKeys).containsExactly("operation:direct");
        assertThat(store.request.attemptCount()).isEqualTo(2);
        assertThat(store.latestAttempt.orElseThrow().claimMode())
                .isEqualTo(ClaimMode.PROBE);
    }

    @Test
    void staleExactHeadEnvelopeCannotReachTheRemoteEffect()
    {
        InMemoryExecutionSupport.MutableClock clock =
                new InMemoryExecutionSupport.MutableClock(NOW);
        MutableStore store = new MutableStore(request(OperationStatus.REQUESTED));
        RecordingEffects effects = new RecordingEffects();

        assertThatThrownBy(() -> handler(store, effects, clock)
                .execute(context(store.request, clock, "stale-head")))
                .isInstanceOf(ExecutionPorts.IndeterminateExecutionException.class)
                .hasMessageContaining("stale");
        assertThat(effects.executedKeys).isEmpty();
        assertThat(store.claims).isZero();
    }

    @Test
    void persistedTerminalOperationReturnsAStableTypedResult()
            throws Exception
    {
        InMemoryExecutionSupport.MutableClock clock =
                new InMemoryExecutionSupport.MutableClock(NOW);
        MutableStore store = new MutableStore(request(OperationStatus.SUCCEEDED));
        RecordingEffects effects = new RecordingEffects();

        DispatchTicket.DispatchResult result = handler(store, effects, clock)
                .reconcile(context(store.request, clock));

        assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.SUCCEEDED);
        assertThat(result.payloadJson())
                .contains("\"mergeOperationId\":\"merge-row\"")
                .contains("\"status\":\"SUCCEEDED\"")
                .contains("\"headSha\":\"head\"");
        assertThat(effects.executedKeys).isEmpty();
        assertThat(store.claims).isZero();
    }

    @Test
    void exhaustedProbeBudgetCreatesADurableManualBlocker()
            throws Exception
    {
        InMemoryExecutionSupport.MutableClock clock =
                new InMemoryExecutionSupport.MutableClock(NOW);
        MutableStore store = new MutableStore(request(
                OperationStatus.AWAITING_OBSERVATION, 1, 1));
        store.latestAttempt = Optional.of(new EffectAttempt(
                "effect-1", 1, MergeOperationHandler.EffectKind.DIRECT_MERGE,
                null, "readiness", "operation:direct", ClaimMode.EXECUTE,
                AttemptStatus.AWAITING_OBSERVATION, NOW.minusSeconds(30),
                NOW.minusSeconds(1)));
        RecordingEffects effects = new RecordingEffects();

        DispatchTicket.DispatchResult result = handler(store, effects, clock)
                .reconcile(context(store.request, clock));

        assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.FAILED);
        assertThat(store.request.status()).isEqualTo(OperationStatus.BLOCKED);
        assertThat(store.blockedReason).isEqualTo(BlockReason.MANUAL_INTERVENTION);
        assertThat(store.claims).isZero();
        assertThat(effects.probedKeys).isEmpty();
    }

    @Test
    void bouncedQueueAtAttemptLimitCreatesADurableManualBlocker()
            throws Exception
    {
        InMemoryExecutionSupport.MutableClock clock =
                new InMemoryExecutionSupport.MutableClock(NOW);
        MutableStore store = new MutableStore(queueRequest(
                OperationStatus.AWAITING_OBSERVATION, 1, 1));
        store.latestAttempt = Optional.of(new EffectAttempt(
                "effect-1", 1, MergeOperationHandler.EffectKind.ENTER_QUEUE,
                1, "readiness", "operation:queue:1", ClaimMode.EXECUTE,
                AttemptStatus.SUCCEEDED, NOW.minusSeconds(30),
                NOW.minusSeconds(1)));
        store.latestQueueEntry = Optional.of(
                new QueueEntry(1, QueueEntryStatus.BOUNCED));
        RecordingEffects effects = new RecordingEffects();

        DispatchTicket.DispatchResult result = handler(store, effects, clock)
                .reconcile(context(store.request, clock));

        assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.FAILED);
        assertThat(store.request.status()).isEqualTo(OperationStatus.BLOCKED);
        assertThat(store.blockedReason).isEqualTo(BlockReason.MANUAL_INTERVENTION);
        assertThat(store.claims).isZero();
    }

    @Test
    void liveHeadMovementWaitsForAcceptedObserverTruthInsteadOfBlocking()
    {
        InMemoryExecutionSupport.MutableClock clock =
                new InMemoryExecutionSupport.MutableClock(NOW);
        MutableStore store = new MutableStore(request(OperationStatus.REQUESTED));
        MergeOperationHandler.MergeEffects pending =
                new MergeOperationHandler.MergeEffects()
                {
                    @Override
                    public EffectEvidence execute(
                            MergeRequest request, EffectClaim claim)
                    {
                        throw new RemoteTruthPendingException(
                                "remote head moved; awaiting RemoteObserver");
                    }

                    @Override
                    public EffectEvidence probe(
                            MergeRequest request, EffectClaim claim)
                    {
                        throw new AssertionError("probe was not expected");
                    }
                };

        assertThatThrownBy(() -> handler(store, pending, clock)
                .execute(context(store.request, clock)))
                .isInstanceOf(ExecutionPorts.OperationDeferredException.class)
                .hasMessageContaining("RemoteObserver");
        assertThat(store.request.status()).isEqualTo(OperationStatus.CLAIMED);
        assertThat(store.blockedReason).isNull();
    }

    private static MergeOperationHandler handler(
            MutableStore store,
            MergeOperationHandler.MergeEffects effects,
            InMemoryExecutionSupport.MutableClock clock)
    {
        return new MergeOperationHandler(
                store, effects, new ObjectMapper(), clock, Duration.ofSeconds(30));
    }

    private static ExecutionContext context(
            MergeRequest request, InMemoryExecutionSupport.MutableClock clock)
    {
        return context(request, clock, request.headSha());
    }

    private static ExecutionContext context(
            MergeRequest operation,
            InMemoryExecutionSupport.MutableClock clock,
            String expectedHead)
    {
        DispatchTicket.OperationFence fence = new DispatchTicket.OperationFence(
                operation.taskEpoch(), operation.stageId(),
                operation.stageGeneration(), operation.operationId(),
                operation.semanticAttempt(), null, expectedHead,
                operation.baseSha());
        CapacityManager.CapacityRequest request = new CapacityManager.CapacityRequest(
                operation.operationId(), V2, Set.of(MERGE),
                new CapacityManager.CapacityScope(
                        operation.workspaceId(), operation.trunkId(),
                        operation.taskId(), operation.taskEpoch()),
                false, true, false);
        DispatchTicket.DispatchEnvelope envelope = new DispatchTicket.DispatchEnvelope(
                MergeOperationHandler.OPERATION_KIND,
                DispatchTicket.AsyncFamily.MERGE,
                new DispatchTicket.OwnerReference(
                        DispatchTicket.OwnerKind.STAGE,
                        operation.stageId(),
                        MergeOperationHandler.CALLBACK_ROUTE),
                fence, request);
        CapacityManager.CapacityLease lease = new CapacityManager.CapacityLease(
                "lease", "ticket", operation.operationId(), V2,
                Set.of(MERGE), request.scope(), false, true, false,
                "worker", null, NOW, clock.instant(), NOW.plusSeconds(90),
                null, null);
        return new ExecutionContext(
                envelope, lease, new ExecutionContext.Cancellation(),
                new NoOpEvidence(), "execution", clock, () -> lease);
    }

    private static MergeRequest request(OperationStatus status)
    {
        return request(status, 0, 4);
    }

    private static MergeRequest request(
            OperationStatus status, int attemptCount, int attemptLimit)
    {
        return new MergeRequest(
                "merge-row", "authorization", "readiness", "operation",
                "remote-stage", "task", "trunk", "workspace", 1, 1, 1,
                MergeMode.DIRECT, "squash", status, attemptCount, attemptLimit, 0, 0,
                "head", "base", "owner/repo", 17, "readiness", "V2",
                "ACTIVE", 1, "remote-stage", 1, "MERGING", null);
    }

    private static MergeRequest queueRequest(
            OperationStatus status, int attemptCount, int attemptLimit)
    {
        return new MergeRequest(
                "merge-row", "authorization", "readiness", "operation",
                "remote-stage", "task", "trunk", "workspace", 1, 1, 1,
                MergeMode.MERGE_QUEUE, "squash", status, attemptCount, attemptLimit, 1, 1,
                "head", "base", "owner/repo", 17, "fresh-readiness", "V2",
                "ACTIVE", 1, "remote-stage", 1, "MERGING", null);
    }

    private static MergeRequest copy(
            MergeRequest request, OperationStatus status, int attempts, String error)
    {
        return new MergeRequest(
                request.mergeOperationId(), request.authorizationId(),
                request.authorizationReadinessId(), request.operationId(),
                request.stageId(), request.taskId(), request.trunkId(),
                request.workspaceId(), request.taskEpoch(),
                request.stageGeneration(), request.semanticAttempt(),
                request.mode(), request.mergeMethod(), status, attempts, request.attemptLimit(),
                request.queueBounceCount(), request.maxQueueReenqueues(),
                request.headSha(), request.baseSha(), request.remoteRepositoryId(),
                request.remotePrNumber(), request.currentReadinessId(),
                request.workflowVersion(), request.taskLifecycle(),
                request.currentTaskEpoch(), request.currentStageId(),
                request.currentStageGeneration(), request.stageCheckpoint(), error);
    }

    private static EffectAttempt attempt(
            EffectClaim claim, AttemptStatus status)
    {
        return new EffectAttempt(
                claim.id(), claim.ordinal(), claim.spec().kind(),
                claim.spec().effectOrdinal(), claim.spec().readinessEvidenceId(),
                claim.spec().idempotencyKey(), claim.spec().mode(), status,
                claim.claimedAt(), claim.leaseUntil());
    }

    private static final class MutableStore
            implements MergeOperationHandler.OperationStore
    {
        private MergeRequest request;
        private Optional<EffectAttempt> latestAttempt = Optional.empty();
        private Optional<QueueEntry> latestQueueEntry = Optional.empty();
        private int claims;
        private BlockReason blockedReason;

        private MutableStore(MergeRequest request)
        {
            this.request = request;
        }

        @Override
        public OperationSnapshot requireByOperationId(String operationId)
        {
            assertThat(operationId).isEqualTo(request.operationId());
            return new OperationSnapshot(request, latestAttempt, latestQueueEntry);
        }

        @Override
        public void reconcileAcceptedObservation(String operationId, Instant at) {}

        @Override
        public Optional<EffectClaim> tryClaim(
                String operationId,
                ClaimSpec spec,
                String claimOwner,
                Instant claimedAt,
                Instant leaseUntil)
        {
            claims++;
            int ordinal = request.attemptCount() + 1;
            EffectClaim claim = new EffectClaim(
                    "effect-" + ordinal, request.mergeOperationId(), ordinal,
                    spec, claimOwner, claimedAt, leaseUntil);
            request = copy(request, OperationStatus.CLAIMED, ordinal, null);
            latestAttempt = Optional.of(attempt(claim, AttemptStatus.CLAIMED));
            return Optional.of(claim);
        }

        @Override
        public boolean markAwaiting(
                EffectClaim claim, EffectEvidence evidence, Instant at)
        {
            latestAttempt = Optional.of(attempt(claim, AttemptStatus.AWAITING_OBSERVATION));
            request = copy(
                    request, OperationStatus.AWAITING_OBSERVATION,
                    request.attemptCount(), null);
            return true;
        }

        @Override
        public boolean markIndeterminate(EffectClaim claim, String detail, Instant at)
        {
            latestAttempt = Optional.of(attempt(claim, AttemptStatus.INDETERMINATE));
            return true;
        }

        @Override
        public void block(
                String operationId, BlockReason reason, String detail, Instant at)
        {
            blockedReason = reason;
            request = copy(request, OperationStatus.BLOCKED, request.attemptCount(), detail);
        }

        @Override
        public void cancel(String operationId, String detail, Instant at)
        {
            request = copy(request, OperationStatus.CANCELED, request.attemptCount(), detail);
        }
    }

    private static final class RecordingEffects
            implements MergeOperationHandler.MergeEffects
    {
        private final List<String> executedKeys = new ArrayList<>();
        private final List<String> probedKeys = new ArrayList<>();

        @Override
        public EffectEvidence execute(MergeRequest request, EffectClaim claim)
        {
            executedKeys.add(claim.spec().idempotencyKey());
            return new EffectEvidence("remote-effect", "execute accepted", false);
        }

        @Override
        public EffectEvidence probe(MergeRequest request, EffectClaim claim)
        {
            probedKeys.add(claim.spec().idempotencyKey());
            return new EffectEvidence("remote-effect", "probe accepted", true);
        }
    }

    private static final class NoOpEvidence
            implements ExecutionPorts.ExecutionEvidencePort
    {
        private final AtomicInteger ids = new AtomicInteger();

        @Override
        public String start(
                DispatchTicket ticket, CapacityManager.CapacityLease lease,
                DispatchTicket.ClaimPurpose purpose, Instant startedAt)
        {
            return "execution-" + ids.incrementAndGet();
        }

        @Override
        public void heartbeat(String executionId, Instant at) {}

        @Override
        public void providerSession(
                String executionId, String provider, String providerSessionId) {}

        @Override
        public void processStarted(
                String executionId, long processPid, String logReference) {}

        @Override
        public void appendLog(
                String executionId, long sequence, String payloadJson, Instant createdAt) {}

        @Override
        public void recordUsage(
                String executionId, long inputTokens, long outputTokens,
                long costUsdMilli) {}

        @Override
        public void finish(
                String executionId, DispatchTicket.DispatchResult result,
                String failure, Instant finishedAt) {}
    }
}
