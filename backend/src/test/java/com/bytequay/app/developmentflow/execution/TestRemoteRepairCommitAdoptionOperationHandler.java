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

import com.bytequay.app.developmentflow.execution.WorktreeWriterLeaseManager.MutationFence;
import com.bytequay.app.developmentflow.stage.RemoteRepairCommitAdoptionOperationHandler;
import com.bytequay.app.developmentflow.stage.RemoteRepairCommitAdoptionOperationHandler.AdoptionResult;
import com.bytequay.app.developmentflow.stage.RemoteRepairCommitAdoptionOperationHandler.Candidate;
import com.bytequay.app.developmentflow.stage.RemoteRepairCommitAdoptionOperationHandler.Operation;
import com.bytequay.app.developmentflow.stage.RemoteRepairCommitAdoptionOperationHandler.ResultReceipt;
import com.bytequay.app.service.checks.CodeFingerprints;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.local.GitRunner.ReflogEntry;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.LOCAL_GIT;
import static com.bytequay.app.developmentflow.execution.CapacityManager.WorkflowSource.V2;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestRemoteRepairCommitAdoptionOperationHandler
{
    private static final Instant NOW = Instant.parse("2026-08-02T00:00:00Z");
    private static final Instant EXECUTION_STARTED = NOW.minusSeconds(30);
    private static final Instant EXECUTION_FINISHED = NOW.minusSeconds(10);
    private static final String SOURCE_HEAD = "source-head";
    private static final String SOURCE_FINGERPRINT = "source-fingerprint";
    private static final String SOURCE_TREE = "source-tree";
    private static final String CANDIDATE_HEAD = "candidate-head";
    private static final String CANDIDATE_FINGERPRINT = "candidate-fingerprint";
    private static final String CANDIDATE_TREE = "candidate-tree";

    @TempDir
    private Path worktree;

    private InMemoryExecutionSupport.MutableClock clock;
    private CapacityManager capacity;
    private InMemoryExecutionSupport.WorktreeStore writerStore;
    private WorktreeWriterLeaseManager writers;
    private GitRunner git;
    private CodeFingerprints fingerprints;
    private ObjectMapper json;

    @BeforeEach
    void setUp()
            throws Exception
    {
        clock = new InMemoryExecutionSupport.MutableClock(NOW);
        capacity = new CapacityManager(
                new InMemoryExecutionSupport.CapacityStore(),
                () -> CapacityManager.CapacityPolicy.initial(
                        4, 4, Map.of(LOCAL_GIT, 4)),
                clock,
                Duration.ofMinutes(5));
        writerStore = new InMemoryExecutionSupport.WorktreeStore();
        writers = new WorktreeWriterLeaseManager(writerStore, clock);
        git = mock(GitRunner.class);
        fingerprints = mock(CodeFingerprints.class);
        json = new ObjectMapper();
        when(git.currentBranch(worktree)).thenReturn("dev/task-1");
        when(git.hasUncommittedChanges(worktree)).thenReturn(false);
        when(git.inProgressOperations(worktree)).thenReturn(List.of());
    }

    @Test
    void exactFrozenCandidateIsAdoptedUnderTheWriterFence()
            throws Exception
    {
        Operation operation = frozenOperation(true);
        ReceiptStore store = new ReceiptStore(operation);
        stubCandidate(CANDIDATE_HEAD, SOURCE_HEAD, CANDIDATE_TREE);
        when(git.headSha(worktree)).thenReturn(SOURCE_HEAD, CANDIDATE_HEAD);
        when(fingerprints.fingerprint(worktree))
                .thenReturn(SOURCE_FINGERPRINT, CANDIDATE_FINGERPRINT);

        DispatchTicket.DispatchResult raw;
        try (ContextFixture fixture = context(operation)) {
            raw = handler(store).execute(fixture.context());
        }

        AdoptionResult result = json.readValue(
                raw.payloadJson(), AdoptionResult.class);
        assertThat(raw.outcome()).isEqualTo(DispatchTicket.Outcome.SUCCEEDED);
        assertThat(result.disposition())
                .isEqualTo(RemoteRepairCommitAdoptionOperationHandler
                        .Disposition.ADOPTED);
        assertThat(result.candidateHeadSha()).isEqualTo(CANDIDATE_HEAD);
        assertThat(result.resultCodeFingerprint())
                .isEqualTo(CANDIDATE_FINGERPRINT);
        assertThat(store.observedFencingTokens).singleElement()
                .satisfies(token -> assertThat(token).isPositive());
        assertThat(result.evidence())
                .contains("\"candidateCaptureKind\":\"FROZEN_WRITER_PROOF_V1\"")
                .contains("\"candidateParentSha\":\"source-head\"")
                .contains("\"sourceCodeSubjectRevision\":42")
                .contains("\"sourceCodeSubjectKind\":\"REMOTE_WORKTREE\"")
                .contains("\"sourceCodeSubjectId\":\"subject-1\"")
                .contains("\"candidateCount\":1")
                .contains("\"mode\":\"EXECUTE\"");
        verify(git).resetHard(worktree, CANDIDATE_HEAD);
    }

    @Test
    void legacyCandidateIsDiscoveredOnlyFromTheExactReflogWindow()
            throws Exception
    {
        Operation operation = legacyOperation(true);
        ReceiptStore store = new ReceiptStore(operation);
        when(git.listReflog(worktree, 256)).thenReturn(List.of(
                reflog("too-early", EXECUTION_STARTED.minusSeconds(1)),
                reflog(CANDIDATE_HEAD, EXECUTION_STARTED.plusSeconds(1)),
                reflog("too-late", EXECUTION_FINISHED.plusSeconds(1))));
        stubCandidate(CANDIDATE_HEAD, SOURCE_HEAD, CANDIDATE_TREE);
        when(git.headSha(worktree)).thenReturn(SOURCE_HEAD, CANDIDATE_HEAD);
        when(fingerprints.fingerprint(worktree))
                .thenReturn(SOURCE_FINGERPRINT, CANDIDATE_FINGERPRINT);

        DispatchTicket.DispatchResult raw;
        try (ContextFixture fixture = context(operation)) {
            raw = handler(store).execute(fixture.context());
        }

        AdoptionResult result = json.readValue(
                raw.payloadJson(), AdoptionResult.class);
        assertThat(raw.outcome()).isEqualTo(DispatchTicket.Outcome.SUCCEEDED);
        assertThat(result.candidateHeadSha()).isEqualTo(CANDIDATE_HEAD);
        assertThat(result.evidence())
                .contains("\"candidateCaptureKind\":\"LEGACY_REFLOG_WINDOW_V1\"")
                .contains(EXECUTION_STARTED.plusSeconds(1).toString());
        verify(git).resetHard(worktree, CANDIDATE_HEAD);
    }

    @Test
    void zeroLegacyCandidatesFailClosedWithoutReset()
            throws Exception
    {
        assertLegacyDiscoveryFails(List.of());
    }

    @Test
    void multipleLegacyCandidatesFailClosedWithoutReset()
            throws Exception
    {
        ReflogEntry first = reflog(
                CANDIDATE_HEAD, EXECUTION_STARTED.plusSeconds(1));
        ReflogEntry second = reflog(
                "second-candidate", EXECUTION_STARTED.plusSeconds(2));
        stubCandidate(CANDIDATE_HEAD, SOURCE_HEAD, CANDIDATE_TREE);
        stubCandidate("second-candidate", SOURCE_HEAD, "second-tree");

        assertLegacyDiscoveryFails(List.of(first, second));
    }

    @Test
    void wrongParentLegacyCandidateFailsClosedWithoutReset()
            throws Exception
    {
        ReflogEntry candidate = reflog(
                CANDIDATE_HEAD, EXECUTION_STARTED.plusSeconds(1));
        when(git.resolveCommitSha(worktree, CANDIDATE_HEAD))
                .thenReturn(Optional.of(CANDIDATE_HEAD));
        when(git.commitParentShas(worktree, CANDIDATE_HEAD))
                .thenReturn(List.of("unrelated-parent"));

        assertLegacyDiscoveryFails(List.of(candidate));
    }

    @Test
    void candidateWithoutExpectedBaseAncestryFailsWithoutReset()
            throws Exception
    {
        Operation operation = frozenOperation(true);
        ReceiptStore store = new ReceiptStore(operation);
        stubCandidate(CANDIDATE_HEAD, SOURCE_HEAD, CANDIDATE_TREE);
        when(git.mergeBase(worktree, CANDIDATE_HEAD, "base-1"))
                .thenReturn(Optional.empty());

        DispatchTicket.DispatchResult raw;
        try (ContextFixture fixture = context(operation)) {
            raw = handler(store).execute(fixture.context());
        }

        AdoptionResult result = json.readValue(
                raw.payloadJson(), AdoptionResult.class);
        assertThat(raw.outcome()).isEqualTo(DispatchTicket.Outcome.FAILED);
        assertThat(result.error()).contains("ancestry proof is invalid");
        assertThat(store.receipt).isNull();
        verify(git, never()).resetHard(eq(worktree), anyString());
    }

    @Test
    void staleOwnerNeverAcquiresAWriterOrTouchesGit()
            throws Exception
    {
        Operation operation = frozenOperation(false);
        ReceiptStore store = new ReceiptStore(operation);

        DispatchTicket.DispatchResult raw;
        try (ContextFixture fixture = context(operation)) {
            raw = handler(store).execute(fixture.context());
        }

        AdoptionResult result = json.readValue(
                raw.payloadJson(), AdoptionResult.class);
        assertThat(raw.outcome()).isEqualTo(DispatchTicket.Outcome.FAILED);
        assertThat(result.disposition())
                .isEqualTo(RemoteRepairCommitAdoptionOperationHandler
                        .Disposition.STALE);
        assertThat(store.receipt).isNull();
        verifyNoInteractions(git, fingerprints);
    }

    @Test
    void stoppedCiEpisodeNeverAcquiresAWriterOrTouchesGit()
            throws Exception
    {
        Operation operation = frozenOperation(true, false, true);
        ReceiptStore store = new ReceiptStore(operation);

        DispatchTicket.DispatchResult raw;
        try (ContextFixture fixture = context(operation)) {
            raw = handler(store).execute(fixture.context());
        }

        AdoptionResult result = json.readValue(
                raw.payloadJson(), AdoptionResult.class);
        assertThat(raw.outcome()).isEqualTo(DispatchTicket.Outcome.FAILED);
        assertThat(result.disposition())
                .isEqualTo(RemoteRepairCommitAdoptionOperationHandler
                        .Disposition.STALE);
        assertThat(store.receipt).isNull();
        verifyNoInteractions(git, fingerprints);
    }

    @Test
    void staleMalformedBlockerNeverAcquiresAWriterOrTouchesGit()
            throws Exception
    {
        Operation operation = frozenOperation(true, true, false);
        ReceiptStore store = new ReceiptStore(operation);

        DispatchTicket.DispatchResult raw;
        try (ContextFixture fixture = context(operation)) {
            raw = handler(store).execute(fixture.context());
        }

        AdoptionResult result = json.readValue(
                raw.payloadJson(), AdoptionResult.class);
        assertThat(raw.outcome()).isEqualTo(DispatchTicket.Outcome.FAILED);
        assertThat(result.disposition())
                .isEqualTo(RemoteRepairCommitAdoptionOperationHandler
                        .Disposition.STALE);
        assertThat(store.receipt).isNull();
        verifyNoInteractions(git, fingerprints);
    }

    @Test
    void replacedCodeSourceNeverAcquiresAWriterOrTouchesGit()
            throws Exception
    {
        Operation operation = frozenOperation(true, true, true, false);
        ReceiptStore store = new ReceiptStore(operation);

        DispatchTicket.DispatchResult raw;
        try (ContextFixture fixture = context(operation)) {
            raw = handler(store).execute(fixture.context());
        }

        AdoptionResult result = json.readValue(
                raw.payloadJson(), AdoptionResult.class);
        assertThat(raw.outcome()).isEqualTo(DispatchTicket.Outcome.FAILED);
        assertThat(result.disposition())
                .isEqualTo(RemoteRepairCommitAdoptionOperationHandler
                        .Disposition.STALE);
        assertThat(store.receipt).isNull();
        verifyNoInteractions(git, fingerprints);
    }

    @Test
    void cancellationNeverAcquiresAWriterOrTouchesGit()
            throws Exception
    {
        Operation operation = frozenOperation(true);
        ReceiptStore store = new ReceiptStore(operation);

        DispatchTicket.DispatchResult raw;
        try (ContextFixture fixture = context(operation)) {
            fixture.cancel();
            raw = handler(store).execute(fixture.context());
        }

        AdoptionResult result = json.readValue(
                raw.payloadJson(), AdoptionResult.class);
        assertThat(raw.outcome()).isEqualTo(DispatchTicket.Outcome.CANCELED);
        assertThat(result.disposition())
                .isEqualTo(RemoteRepairCommitAdoptionOperationHandler
                        .Disposition.CANCELED);
        assertThat(store.receipt).isNull();
        verifyNoInteractions(git, fingerprints);
    }

    @Test
    void reconciliationRecordsAnAlreadyCurrentCandidateWithoutReset()
            throws Exception
    {
        Operation operation = frozenOperation(true);
        ReceiptStore store = new ReceiptStore(operation);
        stubCandidate(CANDIDATE_HEAD, SOURCE_HEAD, CANDIDATE_TREE);
        when(git.headSha(worktree)).thenReturn(CANDIDATE_HEAD);
        when(fingerprints.fingerprint(worktree))
                .thenReturn(CANDIDATE_FINGERPRINT);

        DispatchTicket.DispatchResult raw;
        try (ContextFixture fixture = context(operation)) {
            raw = handler(store).reconcile(fixture.context());
        }

        AdoptionResult result = json.readValue(
                raw.payloadJson(), AdoptionResult.class);
        assertThat(raw.outcome()).isEqualTo(DispatchTicket.Outcome.SUCCEEDED);
        assertThat(result.evidence()).contains("\"mode\":\"RECONCILE\"");
        assertThat(store.observedFencingTokens).hasSize(1);
        verify(git, never()).resetHard(eq(worktree), anyString());
    }

    @Test
    void failedSourceRestoreQuarantinesTheExactTaskWorktree()
            throws Exception
    {
        Operation operation = frozenOperation(true);
        ReceiptStore store = new ReceiptStore(operation);
        store.failRecord = true;
        stubCandidate(CANDIDATE_HEAD, SOURCE_HEAD, CANDIDATE_TREE);
        when(git.headSha(worktree))
                .thenReturn(SOURCE_HEAD, CANDIDATE_HEAD, CANDIDATE_HEAD);
        when(fingerprints.fingerprint(worktree))
                .thenReturn(SOURCE_FINGERPRINT, CANDIDATE_FINGERPRINT,
                        CANDIDATE_FINGERPRINT);
        doThrow(new IOException("restore failed"))
                .when(git).resetHard(worktree, SOURCE_HEAD);

        DispatchTicket.DispatchResult raw;
        try (ContextFixture fixture = context(operation)) {
            raw = handler(store).execute(fixture.context());
        }

        AdoptionResult result = json.readValue(
                raw.payloadJson(), AdoptionResult.class);
        assertThat(raw.outcome()).isEqualTo(DispatchTicket.Outcome.FAILED);
        assertThat(result.error()).contains("could not restore");
        assertThat(writerStore.findOpenQuarantine(
                operation.taskId(), operation.worktreePath()))
                .get()
                .satisfies(quarantine -> {
                    assertThat(quarantine.sourceOperationId())
                            .isEqualTo(operation.operationId());
                    assertThat(quarantine.observedHeadSha())
                            .isEqualTo(CANDIDATE_HEAD);
                    assertThat(quarantine.reason())
                            .contains("could not restore");
                });
    }

    private void assertLegacyDiscoveryFails(List<ReflogEntry> entries)
            throws Exception
    {
        Operation operation = legacyOperation(true);
        ReceiptStore store = new ReceiptStore(operation);
        when(git.listReflog(worktree, 256)).thenReturn(entries);

        DispatchTicket.DispatchResult raw;
        try (ContextFixture fixture = context(operation)) {
            raw = handler(store).execute(fixture.context());
        }

        AdoptionResult result = json.readValue(
                raw.payloadJson(), AdoptionResult.class);
        assertThat(raw.outcome()).isEqualTo(DispatchTicket.Outcome.FAILED);
        assertThat(result.disposition())
                .isEqualTo(RemoteRepairCommitAdoptionOperationHandler
                        .Disposition.FAILED);
        assertThat(result.error()).contains("found");
        assertThat(store.receipt).isNull();
        verify(git, never()).resetHard(eq(worktree), anyString());
    }

    private void stubCandidate(
            String head, String parent, String resultTree)
            throws Exception
    {
        when(git.resolveCommitSha(worktree, head)).thenReturn(Optional.of(head));
        when(git.commitParentShas(worktree, head)).thenReturn(List.of(parent));
        when(git.commitTreeSha(worktree, SOURCE_HEAD)).thenReturn(SOURCE_TREE);
        when(git.commitTreeSha(worktree, head)).thenReturn(resultTree);
        when(git.mergeBase(worktree, head, SOURCE_HEAD))
                .thenReturn(Optional.of(SOURCE_HEAD));
        when(git.mergeBase(worktree, head, "base-1"))
                .thenReturn(Optional.of("base-1"));
    }

    private RemoteRepairCommitAdoptionOperationHandler handler(
            ReceiptStore store)
    {
        return new RemoteRepairCommitAdoptionOperationHandler(
                store, writers, git, fingerprints, json, clock);
    }

    private Operation frozenOperation(boolean currentOwner)
    {
        return frozenOperation(currentOwner, true, true, true);
    }

    private Operation frozenOperation(
            boolean currentOwner,
            boolean currentCiEpisodeFixing,
            boolean currentMalformedBlockerOpen)
    {
        return frozenOperation(
                currentOwner, currentCiEpisodeFixing,
                currentMalformedBlockerOpen, true);
    }

    private Operation frozenOperation(
            boolean currentOwner,
            boolean currentCiEpisodeFixing,
            boolean currentMalformedBlockerOpen,
            boolean currentCodeSource)
    {
        return operation(
                CANDIDATE_HEAD, CANDIDATE_FINGERPRINT,
                SOURCE_TREE, CANDIDATE_TREE, currentOwner,
                currentCiEpisodeFixing, currentMalformedBlockerOpen,
                currentCodeSource);
    }

    private Operation legacyOperation(boolean currentOwner)
    {
        return operation(
                null, null, null, null, currentOwner, true, true, true);
    }

    private Operation operation(
            String candidateHead,
            String candidateFingerprint,
            String candidateSourceTree,
            String candidateResultTree,
            boolean currentOwner,
            boolean currentCiEpisodeFixing,
            boolean currentMalformedBlockerOpen,
            boolean currentCodeSource)
    {
        return new Operation(
                "adoption-row", "normalization-row", "source-operation",
                "adoption-operation", "adoption-ticket", "task-1", 3,
                "stage-1", 4, 1, "workspace-1", "trunk-1",
                worktree.toString(), "dev/task-1",
                42, "REMOTE_WORKTREE", "subject-1", SOURCE_FINGERPRINT,
                SOURCE_HEAD, "base-1", candidateHead, candidateFingerprint,
                candidateSourceTree, candidateResultTree,
                EXECUTION_STARTED, EXECUTION_FINISHED, "DISPATCHED",
                currentCiEpisodeFixing, currentMalformedBlockerOpen,
                currentCodeSource, "ACTIVE", currentOwner ? 3 : 4,
                "stage-1", 4L, SOURCE_FINGERPRINT, SOURCE_HEAD, "base-1");
    }

    private ReflogEntry reflog(String sha, Instant at)
    {
        return new ReflogEntry(
                sha, sha, "HEAD@{0}", "commit: repair", at.toString());
    }

    private ContextFixture context(Operation operation)
    {
        String dispatcher = "dispatcher-1";
        CapacityManager.CapacityRequest request =
                new CapacityManager.CapacityRequest(
                        operation.operationId(), V2, Set.of(LOCAL_GIT),
                        new CapacityManager.CapacityScope(
                                operation.workspaceId(), operation.trunkId(),
                                operation.taskId(), operation.taskEpoch()),
                        false, true, true);
        CapacityManager.CapacityLease lease = capacity.tryAcquireForTicket(
                        operation.ticketId(), request, dispatcher)
                .lease().orElseThrow();
        DispatchTicket.DispatchEnvelope envelope =
                new DispatchTicket.DispatchEnvelope(
                        RemoteRepairCommitAdoptionOperationHandler.OPERATION_KIND,
                        DispatchTicket.AsyncFamily.LOCAL_GIT,
                        new DispatchTicket.OwnerReference(
                                DispatchTicket.OwnerKind.TASK,
                                operation.taskId(),
                                RemoteRepairCommitAdoptionOperationHandler
                                        .CALLBACK_ROUTE),
                        new DispatchTicket.OperationFence(
                                operation.taskEpoch(), operation.stageId(),
                                operation.stageGeneration(),
                                operation.operationId(), operation.attempt(),
                                operation.sourceCodeFingerprint(),
                                operation.sourceHeadSha(),
                                operation.expectedBaseSha()),
                        request);
        ExecutionContext.Cancellation cancellation =
                new ExecutionContext.Cancellation();
        ExecutionContext execution = new ExecutionContext(
                envelope, lease, cancellation, new NoopEvidence(),
                "execution-1", clock,
                () -> capacity.requireExactLeaseForTicket(
                        operation.ticketId(), lease.id(), request, dispatcher));
        return new ContextFixture(
                execution, cancellation, lease, dispatcher);
    }

    private final class ContextFixture
            implements AutoCloseable
    {
        private final ExecutionContext context;
        private final ExecutionContext.Cancellation cancellation;
        private final CapacityManager.CapacityLease lease;
        private final String dispatcher;

        private ContextFixture(
                ExecutionContext context,
                ExecutionContext.Cancellation cancellation,
                CapacityManager.CapacityLease lease,
                String dispatcher)
        {
            this.context = context;
            this.cancellation = cancellation;
            this.lease = lease;
            this.dispatcher = dispatcher;
        }

        private ExecutionContext context()
        {
            return context;
        }

        private void cancel()
        {
            cancellation.cancel();
        }

        @Override
        public void close()
        {
            context.closeWriterResource();
            capacity.release(lease.id(), dispatcher);
        }
    }

    private static final class ReceiptStore
            implements RemoteRepairCommitAdoptionOperationHandler.Store
    {
        private final Operation operation;
        private final List<Long> observedFencingTokens = new ArrayList<>();
        private ResultReceipt receipt;
        private boolean failRecord;

        private ReceiptStore(Operation operation)
        {
            this.operation = operation;
        }

        @Override
        public Operation requireByOperationId(String operationId)
        {
            assertThat(operationId).isEqualTo(operation.operationId());
            return operation;
        }

        @Override
        public Optional<ResultReceipt> findResultByOperationId(
                String operationId)
        {
            assertThat(operationId).isEqualTo(operation.operationId());
            return Optional.ofNullable(receipt);
        }

        @Override
        public ResultReceipt recordAdopted(
                Operation ignored,
                MutationFence fence,
                Candidate candidate,
                String resultCodeFingerprint,
                String evidence,
                Instant recordedAt)
        {
            assertThat(fence.operationId()).isEqualTo(operation.operationId());
            assertThat(fence.taskId()).isEqualTo(operation.taskId());
            assertThat(fence.taskEpoch()).isEqualTo(operation.taskEpoch());
            if (failRecord) {
                throw new IllegalStateException("record failed");
            }
            observedFencingTokens.add(fence.fencingToken());
            if (receipt == null) {
                receipt = new ResultReceipt(
                        "receipt-1", operation.id(), operation.operationId(),
                        candidate.headSha(), resultCodeFingerprint,
                        candidate.resultTreeSha(), fence.fencingToken(),
                        evidence, recordedAt);
            }
            return receipt;
        }
    }

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
                String executionId,
                long sequence,
                String payloadJson,
                Instant createdAt) {}

        @Override
        public void recordUsage(
                String executionId,
                long inputTokens,
                long outputTokens,
                long costUsdMilli) {}

        @Override
        public void finish(
                String executionId,
                DispatchTicket.DispatchResult result,
                String failure,
                Instant finishedAt) {}
    }
}
