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

import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler;
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler.ClaimMode;
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler.EffectClaim;
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler.EffectEvidence;
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler.EffectKind;
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler.EffectStep;
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler.OperationSnapshot;
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler.PublishEffects;
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler.PublishRequest;
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler.RemoteReference;
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler.Route;
import com.bytequay.app.developmentflow.execution.publish.PublishOperationHandler.StepStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.GITHUB;
import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.LOCAL_GIT;
import static com.bytequay.app.developmentflow.execution.CapacityManager.WorkflowSource.V2;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestPublishOperationHandler
{
    private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");
    private static final String OPERATION_ID = "publish-operation-command-1";
    private static final String PUBLISH_ID = "publish-operation-1";
    private static final String TASK_ID = "task-1";
    private static final String STAGE_ID = "local-stage-1";
    private static final String HEAD = "head-sha";
    private static final String BASE = "base-sha";

    @TempDir
    private Path tempDir;

    private ObjectMapper json;
    private InMemoryExecutionSupport.MutableClock clock;
    private CapacityManager capacity;
    private WorktreeWriterLeaseManager writers;

    @BeforeEach
    void setUp()
    {
        json = new ObjectMapper();
        clock = new InMemoryExecutionSupport.MutableClock(NOW);
        capacity = new CapacityManager(
                new InMemoryExecutionSupport.CapacityStore(),
                () -> CapacityManager.CapacityPolicy.initial(
                        4, 4, Map.of(LOCAL_GIT, 4, GITHUB, 4)),
                clock,
                Duration.ofMinutes(5));
        writers = new WorktreeWriterLeaseManager(
                new InMemoryExecutionSupport.WorktreeStore(), clock);
    }

    @Test
    void executesTheSixFixedEffectsAndReturnsExactRemoteProof()
            throws Exception
    {
        MemoryStore store = new MemoryStore(snapshot());
        FakeEffects effects = new FakeEffects();
        PublishOperationHandler handler = handler(store, effects);

        try (ContextFixture fixture = context()) {
            DispatchTicket.DispatchResult result = handler.execute(fixture.context());

            assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.SUCCEEDED);
            JsonNode payload = json.readTree(result.payloadJson());
            assertThat(payload.get("operationId").asText()).isEqualTo(OPERATION_ID);
            assertThat(payload.get("disposition").asText()).isEqualTo("PUBLISHED");
            assertThat(payload.at("/remote/number").asInt()).isEqualTo(17);
            assertThat(payload.at("/remote/headSha").asText()).isEqualTo(HEAD);
            assertThat(effects.calls).containsExactly(
                    EffectKind.VERIFY_SUBJECT,
                    EffectKind.RECONCILE_BRANCH_BASE,
                    EffectKind.PUSH_BRANCH,
                    EffectKind.CREATE_OR_ADOPT_DRAFT_PR,
                    EffectKind.FETCH_REMOTE_DETAIL,
                    EffectKind.PROVE_REMOTE_HEAD);
            assertThat(store.snapshot.steps())
                    .allMatch(step -> step.status() == StepStatus.SUCCEEDED);
        }
    }

    @Test
    void expiredPushClaimIsProbedAndNeverReplayed()
            throws Exception
    {
        OperationSnapshot initial = snapshot();
        List<EffectStep> steps = new ArrayList<>(initial.steps());
        steps.set(0, succeeded(steps.get(0)));
        steps.set(1, succeeded(steps.get(1)));
        EffectStep push = steps.get(2);
        steps.set(2, new EffectStep(
                push.id(), push.publishOperationId(), push.ordinal(), push.kind(),
                StepStatus.CLAIMED, 1, push.attemptLimit(), ClaimMode.EXECUTE,
                "dead-worker", NOW.minusSeconds(120), NOW.minusSeconds(60),
                null, null, null));
        MemoryStore store = new MemoryStore(
                new OperationSnapshot(initial.request(), steps));
        FakeEffects effects = new FakeEffects();
        PublishOperationHandler handler = handler(store, effects);

        try (ContextFixture fixture = context()) {
            DispatchTicket.DispatchResult result = handler.reconcile(fixture.context());

            assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.SUCCEEDED);
            assertThat(effects.pushExecutions).isZero();
            assertThat(effects.pushProbes).isEqualTo(1);
            assertThat(store.snapshot.steps().get(2).attemptCount()).isEqualTo(2);
        }
    }

    @Test
    void indeterminateCreateRemainsProbeOnlyAfterRestart()
            throws Exception
    {
        OperationSnapshot initial = snapshot();
        List<EffectStep> steps = new ArrayList<>(initial.steps());
        for (int index = 0; index < 3; index++) {
            steps.set(index, succeeded(steps.get(index)));
        }
        EffectStep create = steps.get(3);
        steps.set(3, new EffectStep(
                create.id(), create.publishOperationId(), create.ordinal(), create.kind(),
                StepStatus.INDETERMINATE, 1, create.attemptLimit(), null,
                null, null, null, null, "unknown create", NOW.minusSeconds(30)));
        MemoryStore store = new MemoryStore(
                new OperationSnapshot(initial.request(), steps));
        FakeEffects effects = new FakeEffects();
        PublishOperationHandler handler = handler(store, effects);

        try (ContextFixture fixture = context()) {
            DispatchTicket.DispatchResult result = handler.reconcile(fixture.context());

            assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.SUCCEEDED);
            assertThat(effects.createExecutions).isZero();
            assertThat(effects.createProbes).isEqualTo(1);
        }
    }

    @Test
    void exhaustedExecuteBudgetFailsWithoutCallingAnEffect()
            throws Exception
    {
        OperationSnapshot initial = snapshot();
        List<EffectStep> steps = new ArrayList<>(initial.steps());
        steps.set(0, succeeded(steps.get(0)));
        steps.set(1, succeeded(steps.get(1)));
        EffectStep push = steps.get(2);
        steps.set(2, new EffectStep(
                push.id(), push.publishOperationId(), push.ordinal(), push.kind(),
                StepStatus.FAILED, 1, 1, null, null, null, null,
                null, "push absent", NOW.minusSeconds(10)));
        MemoryStore store = new MemoryStore(
                new OperationSnapshot(initial.request(), steps));
        FakeEffects effects = new FakeEffects();

        try (ContextFixture fixture = context()) {
            DispatchTicket.DispatchResult result = handler(store, effects)
                    .execute(fixture.context());

            assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.FAILED);
            assertThat(result.error()).contains("budget exhausted");
            assertThat(effects.calls).isEmpty();
        }
    }

    @Test
    void staleEnvelopeCannotClaimOrReachAnEffect()
    {
        MemoryStore store = new MemoryStore(snapshot());
        FakeEffects effects = new FakeEffects();

        try (ContextFixture fixture = context("stale-head")) {
            assertThatThrownBy(() -> handler(store, effects)
                    .execute(fixture.context()))
                    .isInstanceOf(ExecutionPorts.IndeterminateExecutionException.class)
                    .hasMessageContaining("stale");
            assertThat(effects.calls).isEmpty();
            assertThat(store.snapshot.steps().getFirst().attemptCount()).isZero();
        }
    }

    @Test
    void cancellationBeforeTheFirstClaimProducesNoExternalEffect()
            throws Exception
    {
        MemoryStore store = new MemoryStore(snapshot());
        FakeEffects effects = new FakeEffects();

        try (ContextFixture fixture = context()) {
            fixture.cancellation().cancel();
            DispatchTicket.DispatchResult result = handler(store, effects)
                    .execute(fixture.context());

            assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.CANCELED);
            assertThat(effects.calls).isEmpty();
            assertThat(store.snapshot.steps().getFirst().attemptCount()).isZero();
        }
    }

    private PublishOperationHandler handler(MemoryStore store, FakeEffects effects)
    {
        return new PublishOperationHandler(store, effects, writers, json, clock);
    }

    private OperationSnapshot snapshot()
    {
        PublishRequest request = new PublishRequest(
                PUBLISH_ID,
                OPERATION_ID,
                STAGE_ID,
                TASK_ID,
                "trunk-1",
                "workspace-1",
                1,
                1,
                1,
                "DISPATCHED",
                "V2",
                "ACTIVE",
                1,
                STAGE_ID,
                1,
                "PUBLISHING",
                true,
                "manifest-1",
                "pr-1",
                "fingerprint",
                HEAD,
                BASE,
                Route.DIRECT,
                "acme/widget",
                "acme/widget",
                "acme/widget",
                "dev/task-1",
                "dev/task-1",
                "main",
                "Fix widget",
                "Only approved body",
                1,
                "content-digest",
                tempDir.resolve("worktree").toAbsolutePath().normalize().toString());
        List<EffectStep> steps = new ArrayList<>();
        EffectKind[] kinds = EffectKind.values();
        for (int index = 0; index < kinds.length; index++) {
            steps.add(new EffectStep(
                    "step-" + (index + 1), PUBLISH_ID, index + 1, kinds[index],
                    StepStatus.REQUESTED, 0, 3, null, null,
                    null, null, null, null, null));
        }
        return new OperationSnapshot(request, steps);
    }

    private EffectStep succeeded(EffectStep step)
            throws Exception
    {
        EffectEvidence evidence = step.kind() == EffectKind.CREATE_OR_ADOPT_DRAFT_PR
                ? EffectEvidence.remote(step.kind(), "prior", remote())
                : EffectEvidence.local(step.kind(), "prior");
        return new EffectStep(
                step.id(), step.publishOperationId(), step.ordinal(), step.kind(),
                StepStatus.SUCCEEDED, 1, step.attemptLimit(), null, null,
                null, null, json.writeValueAsString(evidence), null,
                NOW.minusSeconds(30));
    }

    private static RemoteReference remote()
    {
        return new RemoteReference(
                "acme/widget", 17, "https://example.test/acme/widget/pull/17",
                "dev/task-1", HEAD, BASE);
    }

    private ContextFixture context()
    {
        return context(HEAD);
    }

    private ContextFixture context(String envelopeHead)
    {
        CapacityManager.CapacityRequest request = new CapacityManager.CapacityRequest(
                OPERATION_ID,
                V2,
                Set.of(LOCAL_GIT, GITHUB),
                new CapacityManager.CapacityScope(
                        "workspace-1", "trunk-1", TASK_ID, 1L),
                false,
                true,
                true);
        String ticketId = "ticket-" + envelopeHead;
        String owner = "dispatcher-1";
        CapacityManager.CapacityLease lease = capacity.tryAcquireForTicket(
                        ticketId, request, owner)
                .lease()
                .orElseThrow();
        DispatchTicket.DispatchEnvelope envelope = new DispatchTicket.DispatchEnvelope(
                PublishOperationHandler.OPERATION_KIND,
                DispatchTicket.AsyncFamily.GITHUB_EFFECT,
                new DispatchTicket.OwnerReference(
                        DispatchTicket.OwnerKind.STAGE,
                        STAGE_ID,
                        PublishOperationHandler.CALLBACK_ROUTE),
                new DispatchTicket.OperationFence(
                        1L, STAGE_ID, 1L, OPERATION_ID, 1,
                        "fingerprint", envelopeHead, BASE),
                request);
        ExecutionContext.Cancellation cancellation = new ExecutionContext.Cancellation();
        ExecutionContext context = new ExecutionContext(
                envelope,
                lease,
                cancellation,
                new NoOpEvidence(),
                "execution-" + envelopeHead,
                clock,
                () -> capacity.requireExactLeaseForTicket(
                        ticketId, lease.id(), request, owner));
        return new ContextFixture(context, cancellation, lease, owner);
    }

    private final class ContextFixture
            implements AutoCloseable
    {
        private final ExecutionContext context;
        private final ExecutionContext.Cancellation cancellation;
        private final CapacityManager.CapacityLease lease;
        private final String owner;

        private ContextFixture(
                ExecutionContext context,
                ExecutionContext.Cancellation cancellation,
                CapacityManager.CapacityLease lease,
                String owner)
        {
            this.context = context;
            this.cancellation = cancellation;
            this.lease = lease;
            this.owner = owner;
        }

        ExecutionContext context() { return context; }

        ExecutionContext.Cancellation cancellation() { return cancellation; }

        @Override
        public void close()
        {
            context.closeWriterResource();
            capacity.release(lease.id(), owner);
        }
    }

    private static final class MemoryStore
            implements PublishOperationHandler.OperationStore
    {
        private OperationSnapshot snapshot;

        private MemoryStore(OperationSnapshot snapshot)
        {
            this.snapshot = snapshot;
        }

        @Override
        public synchronized OperationSnapshot requireByOperationId(String operationId)
        {
            if (!snapshot.request().operationId().equals(operationId)) {
                throw new IllegalStateException("missing operation");
            }
            return snapshot;
        }

        @Override
        public synchronized Optional<EffectClaim> tryClaim(
                EffectStep expected,
                ClaimMode mode,
                String owner,
                Instant claimedAt,
                Instant leaseUntil)
        {
            EffectStep current = snapshot.steps().get(expected.ordinal() - 1);
            if (!current.equals(expected)
                    || current.status() == StepStatus.CLAIMED
                    && current.leaseUntil().isAfter(claimedAt)) {
                return Optional.empty();
            }
            int attempt = current.attemptCount() + 1;
            EffectStep claimed = new EffectStep(
                    current.id(), current.publishOperationId(), current.ordinal(),
                    current.kind(), StepStatus.CLAIMED, attempt,
                    current.attemptLimit(), mode, owner, claimedAt, leaseUntil,
                    null, null, null);
            replace(claimed);
            return Optional.of(new EffectClaim(
                    current, mode, attempt, current.attemptLimit(), owner,
                    claimedAt, leaseUntil));
        }

        @Override
        public synchronized boolean finish(
                EffectClaim claim,
                StepStatus status,
                String evidenceJson,
                String error,
                Instant completedAt)
        {
            EffectStep current = snapshot.steps().get(claim.step().ordinal() - 1);
            if (current.status() != StepStatus.CLAIMED
                    || current.attemptCount() != claim.attempt()
                    || current.claimMode() != claim.mode()
                    || !current.claimOwner().equals(claim.claimOwner())) {
                return false;
            }
            replace(new EffectStep(
                    current.id(), current.publishOperationId(), current.ordinal(),
                    current.kind(), status, current.attemptCount(),
                    current.attemptLimit(), null, null, null, null,
                    evidenceJson, error, completedAt));
            return true;
        }

        private void replace(EffectStep replacement)
        {
            List<EffectStep> steps = new ArrayList<>(snapshot.steps());
            steps.set(replacement.ordinal() - 1, replacement);
            snapshot = new OperationSnapshot(snapshot.request(), steps);
        }
    }

    private static final class FakeEffects
            implements PublishEffects
    {
        private final List<EffectKind> calls = new ArrayList<>();
        private int pushExecutions;
        private int pushProbes;
        private int createExecutions;
        private int createProbes;

        @Override
        public EffectEvidence verifySubject(PublishRequest request)
        {
            return local(EffectKind.VERIFY_SUBJECT);
        }

        @Override
        public EffectEvidence verifyBranchBase(PublishRequest request)
        {
            return local(EffectKind.RECONCILE_BRANCH_BASE);
        }

        @Override
        public EffectEvidence probePushedBranch(PublishRequest request)
        {
            pushProbes++;
            return local(EffectKind.PUSH_BRANCH);
        }

        @Override
        public EffectEvidence pushOrAdoptBranch(
                PublishRequest request,
                WorktreeWriterLeaseManager.MutationFence fence)
        {
            pushExecutions++;
            return local(EffectKind.PUSH_BRANCH);
        }

        @Override
        public EffectEvidence probeDraftPullRequest(PublishRequest request)
        {
            createProbes++;
            return remote(EffectKind.CREATE_OR_ADOPT_DRAFT_PR);
        }

        @Override
        public EffectEvidence createOrAdoptDraftPullRequest(
                PublishRequest request,
                WorktreeWriterLeaseManager.MutationFence fence)
        {
            createExecutions++;
            return remote(EffectKind.CREATE_OR_ADOPT_DRAFT_PR);
        }

        @Override
        public EffectEvidence fetchRemoteDetail(
                PublishRequest request, RemoteReference remote)
        {
            return remote(EffectKind.FETCH_REMOTE_DETAIL);
        }

        @Override
        public EffectEvidence proveRemoteHead(
                PublishRequest request, RemoteReference remote)
        {
            return remote(EffectKind.PROVE_REMOTE_HEAD);
        }

        private EffectEvidence local(EffectKind kind)
        {
            calls.add(kind);
            return EffectEvidence.local(kind, "ok");
        }

        private EffectEvidence remote(EffectKind kind)
        {
            calls.add(kind);
            return EffectEvidence.remote(kind, "ok", TestPublishOperationHandler.remote());
        }
    }

    private static final class NoOpEvidence
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
