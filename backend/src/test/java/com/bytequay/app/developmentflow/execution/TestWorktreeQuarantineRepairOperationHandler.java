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
import com.bytequay.app.developmentflow.execution.worktree.WorktreeQuarantineRepairOperationHandler;
import com.bytequay.app.developmentflow.execution.worktree.WorktreeQuarantineRepairOperationHandler.Operation;
import com.bytequay.app.developmentflow.execution.worktree.WorktreeQuarantineRepairOperationHandler.RepairResult;
import com.bytequay.app.developmentflow.execution.worktree.WorktreeQuarantineRepairOperationHandler.ResultReceipt;
import com.bytequay.app.service.checks.CodeFingerprints;
import com.bytequay.app.service.local.GitRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestWorktreeQuarantineRepairOperationHandler
{
    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");

    @TempDir
    private Path worktree;

    private InMemoryExecutionSupport.MutableClock clock;
    private CapacityManager capacity;
    private InMemoryExecutionSupport.WorktreeStore worktrees;
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
        worktrees = new InMemoryExecutionSupport.WorktreeStore();
        writers = new WorktreeWriterLeaseManager(worktrees, clock);
        git = mock(GitRunner.class);
        fingerprints = mock(CodeFingerprints.class);
        json = new ObjectMapper();
        when(git.currentBranch(worktree)).thenReturn("dev/task-1");
        when(git.inProgressOperations(worktree)).thenReturn(List.of());
    }

    @Test
    void reconciliationWithANewFenceReusesTheImmutablePreCrashReceipt()
            throws Exception
    {
        WorktreeWriterLeaseManager.WorktreeQuarantine quarantine = quarantine();
        Operation operation = operation(quarantine.id());
        ReceiptStore receipts = new ReceiptStore(operation);
        when(git.headSha(worktree)).thenReturn("head-1");
        when(git.hasUncommittedChanges(worktree)).thenReturn(false);
        when(fingerprints.fingerprint(worktree)).thenReturn("fingerprint-1");
        WorktreeQuarantineRepairOperationHandler handler =
                new WorktreeQuarantineRepairOperationHandler(
                        receipts, writers, git, fingerprints, json, clock);

        RepairResult beforeCrash;
        try (ContextFixture first = repairContext(
                operation, "repair-ticket-1", "dispatcher-1")) {
            DispatchTicket.DispatchResult raw = handler.execute(first.context());
            beforeCrash = json.readValue(raw.payloadJson(), RepairResult.class);
            assertThat(raw.outcome()).isEqualTo(DispatchTicket.Outcome.SUCCEEDED);
        }

        clearInvocations(git);
        RepairResult reconciled;
        try (ContextFixture second = repairContext(
                operation, "repair-ticket-2", "dispatcher-2")) {
            DispatchTicket.DispatchResult raw = handler.reconcile(second.context());
            reconciled = json.readValue(raw.payloadJson(), RepairResult.class);
            assertThat(raw.outcome()).isEqualTo(DispatchTicket.Outcome.SUCCEEDED);
        }

        assertThat(receipts.observedFencingTokens)
                .hasSize(2)
                .doesNotHaveDuplicates();
        assertThat(reconciled.writerFencingToken())
                .isEqualTo(beforeCrash.writerFencingToken())
                .isEqualTo(receipts.receipt.writerFencingToken());
        assertThat(reconciled.evidence())
                .isEqualTo(beforeCrash.evidence())
                .isEqualTo(receipts.receipt.evidence())
                .contains("\"mode\":\"EXECUTE\"")
                .doesNotContain("\"mode\":\"RECONCILE\"");
        assertNoRepairMutation();
    }

    @Test
    void reconciliationWithAReceiptFailsWithoutMutationWhenStateChanged()
            throws Exception
    {
        WorktreeWriterLeaseManager.WorktreeQuarantine quarantine = quarantine();
        Operation operation = operation(quarantine.id());
        ReceiptStore receipts = new ReceiptStore(operation);
        when(git.headSha(worktree)).thenReturn("head-1");
        when(git.hasUncommittedChanges(worktree)).thenReturn(false);
        when(fingerprints.fingerprint(worktree)).thenReturn("fingerprint-1");
        WorktreeQuarantineRepairOperationHandler handler = handler(receipts);

        try (ContextFixture first = repairContext(
                operation, "changed-ticket-1", "changed-dispatcher-1")) {
            assertThat(handler.execute(first.context()).outcome())
                    .isEqualTo(DispatchTicket.Outcome.SUCCEEDED);
        }

        clearInvocations(git);
        when(git.headSha(worktree)).thenReturn("changed-head");
        DispatchTicket.DispatchResult reconciled;
        try (ContextFixture second = repairContext(
                operation, "changed-ticket-2", "changed-dispatcher-2")) {
            reconciled = handler.reconcile(second.context());
        }

        assertThat(reconciled.outcome()).isEqualTo(DispatchTicket.Outcome.FAILED);
        assertThat(receipts.observedFencingTokens).hasSize(1);
        assertNoRepairMutation();
    }

    @Test
    void reconciliationWithoutAReceiptAdoptsExactStateWithoutMutation()
            throws Exception
    {
        WorktreeWriterLeaseManager.WorktreeQuarantine quarantine = quarantine();
        Operation operation = operation(quarantine.id());
        ReceiptStore receipts = new ReceiptStore(operation);
        when(git.headSha(worktree)).thenReturn("head-1");
        when(git.hasUncommittedChanges(worktree)).thenReturn(false);
        when(fingerprints.fingerprint(worktree)).thenReturn("fingerprint-1");

        DispatchTicket.DispatchResult reconciled;
        try (ContextFixture fixture = repairContext(
                operation, "adopt-ticket", "adopt-dispatcher")) {
            reconciled = handler(receipts).reconcile(fixture.context());
        }

        RepairResult result = json.readValue(
                reconciled.payloadJson(), RepairResult.class);
        assertThat(reconciled.outcome())
                .isEqualTo(DispatchTicket.Outcome.SUCCEEDED);
        assertThat(result.expectedBaseSha()).isEqualTo("base-1");
        assertThat(result.evidence()).contains("\"mode\":\"RECONCILE\"");
        assertThat(receipts.observedFencingTokens).hasSize(1);
        assertNoRepairMutation();
    }

    @Test
    void dirtyWrongBranchIsDiscardedBeforeSwitchingTheTaskBranch()
            throws Exception
    {
        assertBranchRepair("other-branch");
    }

    @Test
    void dirtyDetachedHeadIsDiscardedBeforeSwitchingTheTaskBranch()
            throws Exception
    {
        assertBranchRepair(null);
    }

    @Test
    void explicitRepairAbortsAQuarantinedRebaseBeforeRestoring()
            throws Exception
    {
        WorktreeWriterLeaseManager.WorktreeQuarantine quarantine = quarantine();
        Operation operation = operation(quarantine.id());
        ReceiptStore receipts = new ReceiptStore(operation);
        when(git.abortInProgressOperationForRepair(worktree)).thenReturn(true);
        when(git.headSha(worktree)).thenReturn("head-1");
        when(git.hasUncommittedChanges(worktree)).thenReturn(false);
        when(fingerprints.fingerprint(worktree)).thenReturn("fingerprint-1");

        DispatchTicket.DispatchResult raw;
        try (ContextFixture fixture = repairContext(
                operation, "rebase-ticket", "dispatcher-rebase")) {
            raw = handler(receipts).execute(fixture.context());
        }

        assertThat(raw.outcome()).isEqualTo(DispatchTicket.Outcome.SUCCEEDED);
        InOrder order = inOrder(git);
        order.verify(git).abortInProgressOperationForRepair(worktree);
        order.verify(git).resetHard(worktree, "HEAD");
        order.verify(git).resetHard(worktree, "head-1");
    }

    @Test
    void lingeringGitControlStateNeverProducesARepairReceipt()
            throws Exception
    {
        WorktreeWriterLeaseManager.WorktreeQuarantine quarantine = quarantine();
        Operation operation = operation(quarantine.id());
        ReceiptStore receipts = new ReceiptStore(operation);
        when(git.headSha(worktree)).thenReturn("head-1");
        when(git.hasUncommittedChanges(worktree)).thenReturn(false);
        when(fingerprints.fingerprint(worktree)).thenReturn("fingerprint-1");
        when(git.inProgressOperations(worktree))
                .thenReturn(List.of("rebase-merge"));

        DispatchTicket.DispatchResult raw;
        try (ContextFixture fixture = repairContext(
                operation, "stuck-ticket", "dispatcher-stuck")) {
            raw = handler(receipts).execute(fixture.context());
        }

        assertThat(raw.outcome()).isEqualTo(DispatchTicket.Outcome.FAILED);
        assertThat(receipts.receipt).isNull();
    }

    private void assertBranchRepair(String initialBranch)
            throws Exception
    {
        WorktreeWriterLeaseManager.WorktreeQuarantine quarantine = quarantine();
        Operation operation = operation(quarantine.id());
        ReceiptStore receipts = new ReceiptStore(operation);
        when(git.currentBranch(worktree))
                .thenReturn(initialBranch, "dev/task-1");
        when(git.headSha(worktree)).thenReturn("head-1");
        when(git.hasUncommittedChanges(worktree)).thenReturn(false);
        when(fingerprints.fingerprint(worktree)).thenReturn("fingerprint-1");

        DispatchTicket.DispatchResult raw;
        try (ContextFixture fixture = repairContext(
                operation, "branch-ticket", "dispatcher-branch")) {
            raw = handler(receipts).execute(fixture.context());
        }

        assertThat(raw.outcome()).isEqualTo(DispatchTicket.Outcome.SUCCEEDED);
        InOrder order = inOrder(git);
        order.verify(git).resetHard(worktree, "HEAD");
        order.verify(git).cleanUntracked(worktree, List.of(".bytequay-hooks"));
        order.verify(git).switchBranch(worktree, "dev/task-1");
        order.verify(git).resetHard(worktree, "head-1");
        verify(git).inProgressOperations(worktree);
    }

    private WorktreeQuarantineRepairOperationHandler handler(
            ReceiptStore receipts)
    {
        return new WorktreeQuarantineRepairOperationHandler(
                receipts, writers, git, fingerprints, json, clock);
    }

    private void assertNoRepairMutation()
            throws Exception
    {
        verify(git, never()).abortInProgressOperationForRepair(eq(worktree));
        verify(git, never()).resetHard(eq(worktree), anyString());
        verify(git, never()).cleanUntracked(eq(worktree), anyList());
        verify(git, never()).switchBranch(eq(worktree), anyString());
    }

    private WorktreeWriterLeaseManager.WorktreeQuarantine quarantine()
    {
        ContextFixture source = context(
                "source-operation", "source-ticket", "source-dispatcher",
                "TEST_OPERATION", "TEST_RESULT", "source-stage", 1);
        try (source) {
            WorktreeWriterLeaseManager.Lease lease = writers.acquire(
                    source.context(), worktree.toString());
            return writers.quarantine(
                    source.context(), lease,
                    new WorktreeWriterLeaseManager.QuarantineEvidence(
                            "task-1", "source-stage", "source-operation",
                            worktree.toString(), "dev/task-1", "fingerprint-1",
                            "head-1", null, "dirty-head", false,
                            "dirty-fingerprint", null,
                            "source restoration was not exact"));
        }
    }

    private ContextFixture repairContext(
            Operation operation, String ticketId, String dispatcher)
    {
        return context(
                operation.operationId(), ticketId, dispatcher,
                WorktreeQuarantineRepairOperationHandler.OPERATION_KIND,
                WorktreeQuarantineRepairOperationHandler.CALLBACK_ROUTE,
                operation.stageId(), operation.stageGeneration());
    }

    private ContextFixture context(
            String operationId,
            String ticketId,
            String dispatcher,
            String operationKind,
            String callback,
            String stageId,
            long stageGeneration)
    {
        CapacityManager.CapacityRequest request = new CapacityManager.CapacityRequest(
                operationId, V2, Set.of(LOCAL_GIT),
                new CapacityManager.CapacityScope(
                        "workspace-1", "trunk-1", "task-1", 3L),
                false, true, true);
        CapacityManager.CapacityLease lease = capacity.tryAcquireForTicket(
                        ticketId, request, dispatcher)
                .lease().orElseThrow();
        DispatchTicket.DispatchEnvelope envelope =
                new DispatchTicket.DispatchEnvelope(
                        operationKind, DispatchTicket.AsyncFamily.LOCAL_GIT,
                        new DispatchTicket.OwnerReference(
                                WorktreeQuarantineRepairOperationHandler
                                        .OPERATION_KIND.equals(operationKind)
                                        ? DispatchTicket.OwnerKind.TASK
                                        : DispatchTicket.OwnerKind.STAGE,
                                WorktreeQuarantineRepairOperationHandler
                                        .OPERATION_KIND.equals(operationKind)
                                        ? "task-1"
                                        : stageId,
                                callback),
                        new DispatchTicket.OperationFence(
                                3L, stageId, stageGeneration, operationId, 1,
                                "fingerprint-1", "head-1", "base-1"),
                        request);
        ExecutionContext execution = new ExecutionContext(
                envelope, lease, new ExecutionContext.Cancellation(),
                new NoopEvidence(), "execution-" + ticketId, clock,
                () -> capacity.requireExactLeaseForTicket(
                        ticketId, lease.id(), request, dispatcher));
        return new ContextFixture(execution, lease, dispatcher);
    }

    private Operation operation(String quarantineId)
    {
        return new Operation(
                "repair-row", quarantineId, "blocker-1", "task-1", 3,
                "current-stage", 4, "source-operation", "repair-operation",
                "repair-ticket", 1, "command-1", "user", "repair worktree",
                "workspace-1", "trunk-1", worktree.toString(), "dev/task-1",
                "fingerprint-1", "head-1", "base-1", "DISPATCHED",
                "ACTIVE", 3, "current-stage", 4L, "fingerprint-1",
                "head-1", "base-1", "dev/task-1", "OPEN", "OPEN");
    }

    private final class ContextFixture
            implements AutoCloseable
    {
        private final ExecutionContext context;
        private final CapacityManager.CapacityLease lease;
        private final String dispatcher;

        private ContextFixture(
                ExecutionContext context,
                CapacityManager.CapacityLease lease,
                String dispatcher)
        {
            this.context = context;
            this.lease = lease;
            this.dispatcher = dispatcher;
        }

        private ExecutionContext context()
        {
            return context;
        }

        @Override
        public void close()
        {
            context.closeWriterResource();
            capacity.release(lease.id(), dispatcher);
        }
    }

    private static final class ReceiptStore
            implements WorktreeQuarantineRepairOperationHandler.Store
    {
        private final Operation operation;
        private final List<Long> observedFencingTokens = new ArrayList<>();
        private ResultReceipt receipt;

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
        public Optional<ResultReceipt> findResultByOperationId(String operationId)
        {
            assertThat(operationId).isEqualTo(operation.operationId());
            return Optional.ofNullable(receipt);
        }

        @Override
        public ResultReceipt recordRestored(
                Operation ignored,
                MutationFence fence,
                String resultBranchName,
                String resultCodeFingerprint,
                String resultHeadSha,
                String evidence,
                Instant recordedAt)
        {
            observedFencingTokens.add(fence.fencingToken());
            if (receipt == null) {
                receipt = new ResultReceipt(
                        "receipt-1", operation.id(), operation.operationId(),
                        operation.quarantineId(), resultCodeFingerprint,
                        resultHeadSha, resultBranchName, true, true,
                        fence.fencingToken(), evidence, recordedAt);
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
