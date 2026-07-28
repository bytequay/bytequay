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

import com.bytequay.app.developmentflow.execution.provisioning.ProvisionTaskOperationHandler;
import com.bytequay.app.developmentflow.execution.provisioning.ProvisionTaskOperationHandler.BaseSource;
import com.bytequay.app.developmentflow.execution.provisioning.ProvisionTaskOperationHandler.MutationTarget;
import com.bytequay.app.developmentflow.execution.provisioning.ProvisionTaskOperationHandler.OperationStore;
import com.bytequay.app.developmentflow.execution.provisioning.ProvisionTaskOperationHandler.ProvisionRequest;
import com.bytequay.app.developmentflow.execution.provisioning.ProvisionTaskOperationHandler.ProvisionSourceEvidence;
import com.bytequay.app.developmentflow.execution.provisioning.ProvisionTaskOperationHandler.ProvisionSourceProof;
import com.bytequay.app.developmentflow.execution.provisioning.ProvisionTaskOperationHandler.ProvisioningGit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.LOCAL_GIT;
import static com.bytequay.app.developmentflow.execution.CapacityManager.WorkflowSource.V2;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.SUCCEEDED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestProvisionTaskOperationHandler
{
    private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");
    private static final String TASK_ID = "task-1";
    private static final String OPERATION_ID = "operation-1";
    private static final String BASE_SHA = "base-sha";
    private static final String HEAD_SHA = "head-sha";

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
                        4, 4, Map.of(LOCAL_GIT, 4)),
                clock,
                Duration.ofMinutes(1));
        writers = new WorktreeWriterLeaseManager(
                new InMemoryExecutionSupport.WorktreeStore(), clock);
    }

    @Test
    void planningSnapshotCreatesExactTargetWithoutFetch()
            throws Exception
    {
        ProvisionRequest request = request(BaseSource.PLANNING_SNAPSHOT);
        FakeGit git = new FakeGit();
        git.localCommits.add(BASE_SHA);
        ProvisionTaskOperationHandler handler = handler(request, git);

        try (ContextFixture fixture = context(request)) {
            git.beforeCreate = () -> assertThat(fixture.evidence().logs).hasSize(1);
            DispatchTicket.DispatchResult result = handler.execute(fixture.context());

            assertResult(result, BASE_SHA, BASE_SHA);
            assertThat(git.fetches).isEmpty();
            assertThat(git.createdHeads).containsExactly(BASE_SHA);
            assertThat(git.mutationTokens).containsExactly(1L);
        }
    }

    @Test
    void freshBaseFetchesAndResolvesOnlyFrozenRemoteRef()
            throws Exception
    {
        ProvisionRequest request = request(BaseSource.FRESH_REMOTE_BASE);
        FakeGit git = new FakeGit();
        git.remotes.put("acme/widget", "origin");
        git.remoteBranches.put("origin:main", BASE_SHA);
        ProvisionTaskOperationHandler handler = handler(request, git);

        try (ContextFixture fixture = context(request)) {
            git.beforeCreate = () -> assertThat(fixture.evidence().logs).hasSize(1);
            DispatchTicket.DispatchResult result = handler.execute(fixture.context());

            assertResult(result, BASE_SHA, BASE_SHA);
            assertThat(git.fetches).containsExactly("origin");
            assertThat(git.resolvedRemoteBranches).containsExactly("origin:main");
            assertThat(git.createdHeads).containsExactly(BASE_SHA);
            assertThat(git.mutationTokens).containsExactly(1L, 1L);
            assertThat(fixture.evidence().logs).hasSize(1);
            ProvisionSourceEvidence sourceProof = json.readValue(
                    fixture.evidence().logs.getFirst().payloadJson(),
                    ProvisionSourceEvidence.class);
            assertThat(sourceProof.schema())
                    .isEqualTo(ProvisionTaskOperationHandler.SOURCE_PROOF_SCHEMA);
            assertThat(sourceProof.executionId()).isEqualTo("execution-1");
            assertThat(sourceProof.operationId()).isEqualTo(OPERATION_ID);
            assertThat(sourceProof.operationAttempt()).isEqualTo(1);
            assertThat(sourceProof.baseSha()).isEqualTo(BASE_SHA);
            assertThat(sourceProof.headSha()).isEqualTo(BASE_SHA);
        }
    }

    @Test
    void existingPrVerifiesExactForkBaseAndHead()
            throws Exception
    {
        ProvisionRequest request = request(BaseSource.EXISTING_PR_HEAD);
        FakeGit git = new FakeGit();
        git.remotes.put("upstream/widget", "upstream");
        git.remotes.put("acme/widget", "origin");
        git.remoteBranches.put("upstream:main", BASE_SHA);
        git.remoteBranches.put("origin:topic", HEAD_SHA);
        ProvisionTaskOperationHandler handler = handler(request, git);

        try (ContextFixture fixture = context(request)) {
            DispatchTicket.DispatchResult result = handler.execute(fixture.context());

            assertResult(result, BASE_SHA, HEAD_SHA);
            assertThat(git.fetches).containsExactly("upstream", "origin");
            assertThat(git.resolvedRemoteBranches)
                    .containsExactly("upstream:main", "origin:topic");
            assertThat(git.createdHeads).containsExactly(HEAD_SHA);
            assertThat(git.mutationTokens).containsExactly(1L, 1L, 1L);
        }
    }

    @Test
    void cancellationBeforeMutationDoesNotProbeFetchOrCreate()
    {
        ProvisionRequest request = request(BaseSource.FRESH_REMOTE_BASE);
        FakeGit git = new FakeGit();
        ProvisionTaskOperationHandler handler = handler(request, git);

        try (ContextFixture fixture = context(request)) {
            fixture.cancellation().cancel();

            assertThatThrownBy(() -> handler.execute(fixture.context()))
                    .isInstanceOf(ExecutionPorts.OperationCanceledException.class)
                    .hasMessageContaining("before Git mutation");
            assertThat(git.probeCalls).isZero();
            assertThat(git.fetches).isEmpty();
            assertThat(git.createdHeads).isEmpty();
        }
    }

    @Test
    void cancellationInterruptsBlockingGitMutationAndDoesNotContinueToCreate()
            throws Exception
    {
        ProvisionRequest request = request(BaseSource.FRESH_REMOTE_BASE);
        FakeGit git = new FakeGit();
        git.remotes.put("acme/widget", "origin");
        git.remoteBranches.put("origin:main", BASE_SHA);
        git.blockFetch = true;
        ProvisionTaskOperationHandler handler = handler(request, git);

        try (ContextFixture fixture = context(request);
                ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<DispatchTicket.DispatchResult> result = executor.submit(
                    () -> handler.execute(fixture.context()));
            assertThat(git.fetchStarted.await(5, TimeUnit.SECONDS)).isTrue();

            fixture.cancellation().cancel();

            assertThatThrownBy(() -> result.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(ExecutionPorts.OperationCanceledException.class);
            assertThat(git.fetchInterrupted).isTrue();
            assertThat(git.createdHeads).isEmpty();
            assertThat(fixture.evidence().logs).isEmpty();
        }
    }

    @Test
    void staleEnvelopeFenceCannotReachGit()
    {
        ProvisionRequest request = request(BaseSource.PLANNING_SNAPSHOT);
        FakeGit git = new FakeGit();
        ProvisionTaskOperationHandler handler = handler(request, git);

        try (ContextFixture fixture = context(request, "stale-base")) {
            assertThatThrownBy(() -> handler.execute(fixture.context()))
                    .isInstanceOf(ExecutionPorts.IndeterminateExecutionException.class)
                    .hasMessageContaining("envelope");
            assertThat(git.probeCalls).isZero();
            assertThat(git.fetches).isEmpty();
            assertThat(git.createdHeads).isEmpty();
        }
    }

    @Test
    void storedBranchAndPathCannotEscapeTheFrozenTaskTarget()
    {
        Path root = tempDir.toAbsolutePath().normalize();
        List<ProvisionRequest> invalid = List.of(
                request(
                        BaseSource.PLANNING_SNAPSHOT,
                        "dev/another-task",
                        root.resolve(".worktrees").resolve(TASK_ID).toString()),
                request(
                        BaseSource.PLANNING_SNAPSHOT,
                        "dev/" + TASK_ID,
                        root.resolve("outside-task").toString()));

        for (ProvisionRequest request : invalid) {
            FakeGit git = new FakeGit();
            ProvisionTaskOperationHandler handler = handler(request, git);
            try (ContextFixture fixture = context(request)) {
                assertThatThrownBy(() -> handler.execute(fixture.context()))
                        .isInstanceOf(ExecutionPorts.IndeterminateExecutionException.class);
                assertThat(git.probeCalls).isZero();
                assertThat(git.fetches).isEmpty();
                assertThat(git.createdHeads).isEmpty();
            }
        }
    }

    @Test
    void ambiguousCreateIsAcceptedOnlyAfterExactProbe()
            throws Exception
    {
        ProvisionRequest request = request(BaseSource.PLANNING_SNAPSHOT);
        FakeGit git = new FakeGit();
        git.localCommits.add(BASE_SHA);
        git.failAfterCreate = true;
        ProvisionTaskOperationHandler handler = handler(request, git);

        try (ContextFixture fixture = context(request)) {
            DispatchTicket.DispatchResult result = handler.execute(fixture.context());

            assertResult(result, BASE_SHA, BASE_SHA);
            assertThat(git.probeCalls).isEqualTo(2);
            assertThat(git.createdHeads).containsExactly(BASE_SHA);
        }
    }

    @Test
    void conflictingAmbiguousCreateFailsClosed()
    {
        ProvisionRequest request = request(BaseSource.PLANNING_SNAPSHOT);
        FakeGit git = new FakeGit();
        git.localCommits.add(BASE_SHA);
        git.conflictAfterCreate = true;
        ProvisionTaskOperationHandler handler = handler(request, git);

        try (ContextFixture fixture = context(request)) {
            assertThatThrownBy(() -> handler.execute(fixture.context()))
                    .isInstanceOf(ExecutionPorts.IndeterminateExecutionException.class)
                    .hasMessageContaining("conflicting provisioning state");
        }
    }

    @Test
    void restartReconcileAdoptsOnlyFreshTargetWithPriorDurableSourceProof()
            throws Exception
    {
        ProvisionRequest request = request(BaseSource.FRESH_REMOTE_BASE);
        FakeGit git = new FakeGit();
        git.probe = ProvisioningGit.Probe.exact(HEAD_SHA);
        git.localCommits.add(HEAD_SHA);
        ProvisionTaskOperationHandler restarted = handler(
                request,
                git,
                Optional.of(sourceProof(request, "execution-previous", HEAD_SHA, HEAD_SHA)));

        try (ContextFixture fixture = context(request)) {
            DispatchTicket.DispatchResult result = restarted.reconcile(fixture.context());

            assertResult(result, HEAD_SHA, HEAD_SHA);
            assertThat(git.fetches).isEmpty();
            assertThat(git.createdHeads).isEmpty();
        }
    }

    @Test
    void restartRejectsExactFreshTargetWithoutPriorDurableSourceProof()
    {
        ProvisionRequest request = request(BaseSource.FRESH_REMOTE_BASE);
        FakeGit git = new FakeGit();
        git.probe = ProvisioningGit.Probe.exact(HEAD_SHA);
        ProvisionTaskOperationHandler restarted = handler(request, git);

        try (ContextFixture fixture = context(request)) {
            assertThatThrownBy(() -> restarted.reconcile(fixture.context()))
                    .isInstanceOf(ExecutionPorts.IndeterminateExecutionException.class)
                    .hasMessageContaining("no prior durable source proof");
            assertThat(git.fetches).isEmpty();
            assertThat(git.createdHeads).isEmpty();
        }
    }

    @Test
    void restartRejectsBranchPathOrHeadConflictWithoutMutation()
    {
        ProvisionRequest request = request(BaseSource.EXISTING_PR_HEAD);
        FakeGit git = new FakeGit();
        git.probe = ProvisioningGit.Probe.conflict("branch HEAD differs");
        ProvisionTaskOperationHandler restarted = handler(request, git);

        try (ContextFixture fixture = context(request)) {
            assertThatThrownBy(() -> restarted.reconcile(fixture.context()))
                    .isInstanceOf(ExecutionPorts.IndeterminateExecutionException.class)
                    .hasMessageContaining("conflicts with frozen identity");
            assertThat(git.fetches).isEmpty();
            assertThat(git.createdHeads).isEmpty();
        }
    }

    private ProvisionTaskOperationHandler handler(ProvisionRequest request, FakeGit git)
    {
        return handler(request, git, Optional.empty());
    }

    private ProvisionTaskOperationHandler handler(
            ProvisionRequest request,
            FakeGit git,
            Optional<ProvisionSourceProof> priorSourceProof)
    {
        OperationStore store = new OperationStore()
        {
            @Override
            public ProvisionRequest requireByOperationId(String operationId)
            {
                return request;
            }

            @Override
            public Optional<ProvisionSourceProof> findPriorSourceProof(
                    String operationId)
            {
                return priorSourceProof;
            }
        };
        return new ProvisionTaskOperationHandler(
                store, git, writers, json);
    }

    private ProvisionSourceProof sourceProof(
            ProvisionRequest request,
            String executionId,
            String baseSha,
            String headSha)
    {
        ProvisionSourceEvidence evidence = new ProvisionSourceEvidence(
                ProvisionTaskOperationHandler.SOURCE_PROOF_SCHEMA,
                executionId,
                request.operationId(),
                request.attempt(),
                request.taskId(),
                request.taskEpoch(),
                request.baseSource(),
                request.repositoryId(),
                request.baseRepositoryId(),
                request.baseRef(),
                request.baseSource() == BaseSource.EXISTING_PR_HEAD
                        ? request.assignmentHeadRepositoryId()
                        : null,
                request.baseSource() == BaseSource.EXISTING_PR_HEAD
                        ? request.headRef()
                        : null,
                request.branchName(),
                request.worktreePath(),
                baseSha,
                headSha);
        return new ProvisionSourceProof(executionId, 1, evidence);
    }

    private ContextFixture context(ProvisionRequest request)
    {
        return context(request, request.expectedBaseSha());
    }

    private ContextFixture context(ProvisionRequest request, String envelopeBase)
    {
        CapacityManager.CapacityRequest capacityRequest =
                new CapacityManager.CapacityRequest(
                        request.operationId(),
                        V2,
                        Set.of(LOCAL_GIT),
                        new CapacityManager.CapacityScope(
                                request.workspaceId(), request.trunkId(),
                                request.taskId(), request.taskEpoch()),
                        false,
                        true,
                        true);
        String ticketId = "ticket-" + request.operationId();
        String owner = "dispatcher-1";
        CapacityManager.CapacityLease lease = capacity.tryAcquireForTicket(
                        ticketId, capacityRequest, owner)
                .lease()
                .orElseThrow();
        DispatchTicket.DispatchEnvelope envelope = new DispatchTicket.DispatchEnvelope(
                ProvisionTaskOperationHandler.OPERATION_KIND,
                DispatchTicket.AsyncFamily.LOCAL_GIT,
                new DispatchTicket.OwnerReference(
                        DispatchTicket.OwnerKind.TASK,
                        request.taskId(),
                        ProvisionTaskOperationHandler.CALLBACK_ROUTE),
                new DispatchTicket.OperationFence(
                        request.taskEpoch(), null, null, request.operationId(),
                        request.attempt(), null, request.expectedHeadSha(), envelopeBase),
                capacityRequest);
        ExecutionContext.Cancellation cancellation = new ExecutionContext.Cancellation();
        RecordingEvidence evidence = new RecordingEvidence();
        ExecutionContext context = new ExecutionContext(
                envelope,
                lease,
                cancellation,
                evidence,
                "execution-1",
                clock,
                () -> capacity.requireExactLeaseForTicket(
                        ticketId, lease.id(), capacityRequest, owner));
        return new ContextFixture(context, cancellation, evidence, lease, owner);
    }

    private ProvisionRequest request(BaseSource source)
    {
        Path root = tempDir.toAbsolutePath().normalize();
        return request(
                source,
                "dev/" + TASK_ID,
                root.resolve(".worktrees").resolve(TASK_ID).toString());
    }

    private ProvisionRequest request(BaseSource source, String branch, String path)
    {
        Path root = tempDir.toAbsolutePath().normalize();
        boolean existing = source == BaseSource.EXISTING_PR_HEAD;
        boolean planning = source == BaseSource.PLANNING_SNAPSHOT;
        String baseRepository = existing ? "upstream/widget" : "acme/widget";
        String expectedBase = planning || existing ? BASE_SHA : null;
        String expectedHead = existing ? HEAD_SHA : null;
        return new ProvisionRequest(
                "provision-1",
                TASK_ID,
                "trunk-1",
                "workspace-1",
                "V2",
                "PROVISIONING",
                1,
                "assignment-1",
                "assignment-1",
                OPERATION_ID,
                1,
                "acme/widget",
                source,
                baseRepository,
                "main",
                expectedBase,
                expectedHead,
                branch,
                path,
                "DISPATCHED",
                "assignment-1",
                "acme/widget",
                existing ? "upstream/widget" : null,
                "acme/widget",
                source,
                baseRepository,
                "main",
                planning ? BASE_SHA : null,
                existing ? BASE_SHA : null,
                existing ? HEAD_SHA : null,
                "acme/widget",
                "acme/widget",
                branch,
                path,
                existing ? "EXISTING_OWN_PR"
                        : planning ? "NEW_FROM_TRUNK" : "ISSUE",
                existing ? "acme/widget" : null,
                planning ? BASE_SHA : null,
                existing ? "upstream/widget" : null,
                existing ? "acme/widget" : null,
                existing ? "main" : null,
                existing ? "topic" : null,
                existing ? BASE_SHA : null,
                existing ? HEAD_SHA : null,
                root.toString());
    }

    private void assertResult(
            DispatchTicket.DispatchResult result,
            String expectedBase,
            String expectedHead)
            throws Exception
    {
        assertThat(result.outcome()).isEqualTo(SUCCEEDED);
        assertThat(result.payloadJson()).isEqualTo(result.evidenceJson());
        JsonNode payload = json.readTree(result.payloadJson());
        assertThat(payload.get("operationId").asText()).isEqualTo(OPERATION_ID);
        assertThat(payload.get("taskId").asText()).isEqualTo(TASK_ID);
        assertThat(payload.get("baseSha").asText()).isEqualTo(expectedBase);
        assertThat(payload.get("headSha").asText()).isEqualTo(expectedHead);
        assertThat(payload.get("codeFingerprint").asText())
                .isEqualTo("fingerprint-" + expectedHead);
    }

    private final class ContextFixture
            implements AutoCloseable
    {
        private final ExecutionContext context;
        private final ExecutionContext.Cancellation cancellation;
        private final RecordingEvidence evidence;
        private final CapacityManager.CapacityLease lease;
        private final String owner;

        private ContextFixture(
                ExecutionContext context,
                ExecutionContext.Cancellation cancellation,
                RecordingEvidence evidence,
                CapacityManager.CapacityLease lease,
                String owner)
        {
            this.context = context;
            this.cancellation = cancellation;
            this.evidence = evidence;
            this.lease = lease;
            this.owner = owner;
        }

        ExecutionContext context()
        {
            return context;
        }

        ExecutionContext.Cancellation cancellation()
        {
            return cancellation;
        }

        RecordingEvidence evidence()
        {
            return evidence;
        }

        @Override
        public void close()
        {
            context.closeWriterResource();
            capacity.release(lease.id(), owner);
        }
    }

    private static final class FakeGit
            implements ProvisioningGit
    {
        private Probe probe = Probe.absent();
        private final Map<String, String> remotes = new HashMap<>();
        private final Map<String, String> remoteBranches = new HashMap<>();
        private final Set<String> localCommits = new HashSet<>();
        private final List<String> fetches = new ArrayList<>();
        private final List<String> resolvedRemoteBranches = new ArrayList<>();
        private final List<String> createdHeads = new ArrayList<>();
        private final List<Long> mutationTokens = new ArrayList<>();
        private final CountDownLatch fetchStarted = new CountDownLatch(1);
        private final CountDownLatch releaseFetch = new CountDownLatch(1);
        private int probeCalls;
        private boolean failAfterCreate;
        private boolean conflictAfterCreate;
        private boolean blockFetch;
        private volatile boolean fetchInterrupted;
        private Runnable beforeCreate = () -> {};

        @Override
        public Probe probe(MutationTarget target)
        {
            probeCalls++;
            return probe;
        }

        @Override
        public String requireExactRemote(Path repositoryRoot, String repositoryId)
        {
            String remote = remotes.get(repositoryId);
            if (remote == null) {
                throw new IllegalStateException("missing exact fake remote");
            }
            return remote;
        }

        @Override
        public Optional<String> resolveCommit(Path repositoryRoot, String ref)
        {
            return localCommits.contains(ref) ? Optional.of(ref) : Optional.empty();
        }

        @Override
        public Optional<String> resolveRemoteBranch(
                Path repositoryRoot, String remote, String branch)
        {
            String key = remote + ":" + branch;
            resolvedRemoteBranches.add(key);
            return Optional.ofNullable(remoteBranches.get(key));
        }

        @Override
        public void fetchRemote(
                MutationTarget target,
                String remote,
                WorktreeWriterLeaseManager.MutationFence fence)
        {
            requireFence(target, fence);
            fetches.add(remote);
            fetchStarted.countDown();
            if (blockFetch) {
                try {
                    releaseFetch.await();
                }
                catch (InterruptedException interrupted) {
                    fetchInterrupted = true;
                    Thread.currentThread().interrupt();
                    throw new MutationFailure(
                            "simulated interrupted fetch", interrupted);
                }
            }
        }

        @Override
        public void createWorktree(
                MutationTarget target,
                String headSha,
                WorktreeWriterLeaseManager.MutationFence fence)
        {
            requireFence(target, fence);
            beforeCreate.run();
            createdHeads.add(headSha);
            probe = conflictAfterCreate
                    ? Probe.conflict("wrong HEAD")
                    : Probe.exact(headSha);
            if (failAfterCreate || conflictAfterCreate) {
                throw new MutationFailure(
                        "simulated ambiguous worktree creation",
                        new IllegalStateException("lost completion"));
            }
        }

        @Override
        public String codeFingerprint(MutationTarget target)
        {
            return "fingerprint-" + probe.headSha();
        }

        private void requireFence(
                MutationTarget target,
                WorktreeWriterLeaseManager.MutationFence fence)
        {
            assertThat(fence.taskId()).isEqualTo(target.taskId());
            assertThat(fence.taskEpoch()).isEqualTo(target.taskEpoch());
            assertThat(fence.operationId()).isEqualTo(target.operationId());
            assertThat(fence.worktreePath()).isEqualTo(target.worktreePath().toString());
            mutationTokens.add(fence.fencingToken());
        }
    }

    private static final class RecordingEvidence
            implements ExecutionPorts.ExecutionEvidencePort
    {
        private final List<LogEntry> logs = new ArrayList<>();

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
                String executionId, long sequence, String payloadJson, Instant createdAt)
        {
            logs.add(new LogEntry(executionId, sequence, payloadJson, createdAt));
        }

        @Override public void recordUsage(
                String executionId, long inputTokens, long outputTokens, long costUsdMilli) {}

        @Override public void finish(
                String executionId,
                DispatchTicket.DispatchResult result,
                String failure,
                Instant finishedAt) {}

        private record LogEntry(
                String executionId,
                long sequence,
                String payloadJson,
                Instant createdAt) {}
    }
}
