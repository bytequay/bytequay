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
package com.bytequay.app.developmentflow.execution.worktree;

import com.bytequay.app.developmentflow.execution.CapacityManager;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.ExecutionContext;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.developmentflow.execution.WorktreeWriterLeaseManager;
import com.bytequay.app.developmentflow.execution.WorktreeWriterLeaseManager.MutationFence;
import com.bytequay.app.developmentflow.execution.WorktreeWriterLeaseManager.QuarantineRepair;
import com.bytequay.app.service.checks.CodeFingerprints;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.threads.WorktreeService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.LOCAL_GIT;
import static com.bytequay.app.developmentflow.execution.CapacityManager.WorkflowSource.V2;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.CANCELED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.FAILED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.SUCCEEDED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.OwnerKind.TASK;
import static java.util.Objects.requireNonNull;

/** Restores one quarantined Task worktree under a separately authorized fence. */
public final class WorktreeQuarantineRepairOperationHandler
        implements ExecutionPorts.OperationHandler
{
    public static final String OPERATION_KIND = "REPAIR_QUARANTINED_WORKTREE";
    public static final String CALLBACK_ROUTE =
            "WORKTREE_QUARANTINE_REPAIR_RESULT";

    public static final int RESULT_SCHEMA_VERSION = 1;

    private final Store store;
    private final WorktreeWriterLeaseManager writers;
    private final GitRunner git;
    private final CodeFingerprints fingerprints;
    private final ObjectMapper json;
    private final Clock clock;

    public WorktreeQuarantineRepairOperationHandler(
            Store store,
            WorktreeWriterLeaseManager writers,
            GitRunner git,
            CodeFingerprints fingerprints,
            ObjectMapper json,
            Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        this.writers = requireNonNull(writers, "writers is null");
        this.git = requireNonNull(git, "git is null");
        this.fingerprints = requireNonNull(fingerprints, "fingerprints is null");
        this.json = requireNonNull(json, "json is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    public DispatchTicket.DispatchResult execute(ExecutionContext context)
    {
        return perform(context, Mode.EXECUTE);
    }

    @Override
    public DispatchTicket.DispatchResult reconcile(ExecutionContext context)
    {
        return perform(context, Mode.RECONCILE);
    }

    private DispatchTicket.DispatchResult perform(
            ExecutionContext context, Mode mode)
    {
        requireNonNull(context, "context is null");
        DispatchTicket.DispatchEnvelope envelope = context.envelope();
        Operation operation = store.requireByOperationId(
                envelope.fence().operationId());
        if (!exactEnvelope(envelope, operation)
                || !operation.currentOwner()) {
            return result(envelope.fence(), RepairResult.stale(
                    operation, "Quarantine repair owner fence is stale"));
        }
        if (context.isCancellationRequested()) {
            return result(envelope.fence(), RepairResult.canceled(
                    operation, "Quarantine repair was canceled before mutation"));
        }

        QuarantineRepair repair = writers.acquireQuarantineRepair(
                context, operation.worktreePath(), operation.quarantineId(),
                operation.id());
        RepairResult repairResult = writers.authorizeQuarantineRepair(
                context, repair).run(fence -> restore(
                        context, operation, fence, mode));
        return result(envelope.fence(), repairResult);
    }

    private RepairResult restore(
            ExecutionContext context,
            Operation operation,
            MutationFence fence,
            Mode mode)
    {
        if (context.isCancellationRequested()) {
            return RepairResult.canceled(
                    operation, "Quarantine repair was canceled before mutation");
        }
        Path worktree = Path.of(operation.worktreePath());
        Optional<ResultReceipt> prior = store.findResultByOperationId(
                operation.operationId());
        if (mode == Mode.RECONCILE || prior.isPresent()) {
            RepairProbe probe = probe(operation, worktree);
            if (probe.exact()) {
                if (context.isCancellationRequested()) {
                    return RepairResult.canceled(operation,
                            "Quarantine repair was canceled before proof was recorded");
                }
                // Even a pre-crash receipt must be adopted through the Store.
                // Its replay arm atomically proves this fresh Capacity/Writer
                // fence before returning the immutable original receipt.
                ResultReceipt receipt = recordRestored(
                        operation, fence, probe, mode);
                return RepairResult.restored(operation, receipt);
            }
            if (prior.isPresent()) {
                return RepairResult.failed(
                        operation, probe.branchName(), probe.headSha(),
                        probe.clean(), probe.codeFingerprint(),
                        probe.gitOperationStateClear(),
                        "Quarantine repair state changed after its durable proof");
            }
        }

        String resultHead = null;
        String resultBranch = null;
        Boolean resultClean = null;
        Boolean gitOperationStateClear = null;
        String resultFingerprint = null;
        try {
            git.abortInProgressOperationForRepair(worktree);
            // Discard quarantined dirt without moving a detached HEAD or the
            // ref of a wrong branch to the Task's frozen commit.
            git.resetHard(worktree, "HEAD");
            git.cleanUntracked(worktree, List.of(WorktreeService.HOOK_DIR_REL));
            if (!operation.expectedBranchName().equals(
                    git.currentBranch(worktree))) {
                git.switchBranch(worktree, operation.expectedBranchName());
            }
            git.resetHard(worktree, operation.expectedHeadSha());
            git.cleanUntracked(worktree, List.of(WorktreeService.HOOK_DIR_REL));
            resultBranch = git.currentBranch(worktree);
            resultHead = git.headSha(worktree);
            resultClean = !git.hasUncommittedChanges(worktree);
            resultFingerprint = fingerprints.fingerprint(worktree);
            gitOperationStateClear = git.inProgressOperations(worktree).isEmpty();
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return RepairResult.failed(
                    operation, resultBranch, resultHead, resultClean,
                    resultFingerprint, gitOperationStateClear,
                    "Quarantine repair was interrupted: " + failure.getMessage());
        }
        catch (IOException | RuntimeException failure) {
            return RepairResult.failed(
                    operation, resultBranch, resultHead, resultClean,
                    resultFingerprint, gitOperationStateClear,
                    "Quarantine repair failed: " + failure.getMessage());
        }
        if (!operation.expectedBranchName().equals(resultBranch)
                || !operation.expectedHeadSha().equals(resultHead)
                || !Boolean.TRUE.equals(resultClean)
                || !operation.expectedCodeFingerprint().equals(
                        resultFingerprint)
                || !Boolean.TRUE.equals(gitOperationStateClear)) {
            return RepairResult.failed(
                    operation, resultBranch, resultHead, resultClean,
                    resultFingerprint, gitOperationStateClear,
                    "Quarantine repair did not restore the exact frozen subject");
        }
        if (context.isCancellationRequested()) {
            return RepairResult.canceled(
                    operation,
                    "Quarantine repair was canceled before proof was recorded");
        }
        ResultReceipt receipt = recordRestored(
                operation, fence, new RepairProbe(
                        resultBranch, resultHead, resultClean,
                        resultFingerprint, gitOperationStateClear, true), mode);
        return RepairResult.restored(operation, receipt);
    }

    private RepairProbe probe(Operation operation, Path worktree)
    {
        String branch = null;
        String head = null;
        Boolean clean = null;
        String fingerprint = null;
        Boolean gitStateClear = null;
        try {
            branch = git.currentBranch(worktree);
            head = git.headSha(worktree);
            clean = !git.hasUncommittedChanges(worktree);
            fingerprint = fingerprints.fingerprint(worktree);
            gitStateClear = git.inProgressOperations(worktree).isEmpty();
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
        }
        catch (IOException | RuntimeException ignored) {
            // A failed probe is non-exact. An unreceipted reconciliation may
            // still execute its already-authorized restore; a receipted one
            // must fail without mutating.
        }
        boolean exact = operation.expectedBranchName().equals(branch)
                && operation.expectedHeadSha().equals(head)
                && Boolean.TRUE.equals(clean)
                && operation.expectedCodeFingerprint().equals(fingerprint)
                && Boolean.TRUE.equals(gitStateClear);
        return new RepairProbe(
                branch, head, clean, fingerprint, gitStateClear, exact);
    }

    private ResultReceipt recordRestored(
            Operation operation,
            MutationFence fence,
            RepairProbe probe,
            Mode mode)
    {
        String evidence = write(new RepairEvidence(
                RESULT_SCHEMA_VERSION, operation.quarantineId(), operation.id(),
                operation.operationId(), operation.taskId(),
                operation.taskEpoch(), operation.stageId(),
                operation.stageGeneration(), operation.worktreePath(),
                operation.expectedBranchName(), probe.branchName(),
                operation.expectedCodeFingerprint(),
                operation.expectedHeadSha(), operation.expectedBaseSha(),
                probe.codeFingerprint(),
                probe.headSha(), true, true, fence.fencingToken(), mode.name()));
        return store.recordRestored(
                operation, fence, probe.branchName(), probe.codeFingerprint(),
                probe.headSha(), evidence, clock.instant());
    }

    private static boolean exactEnvelope(
            DispatchTicket.DispatchEnvelope envelope, Operation operation)
    {
        DispatchTicket.OperationFence fence = envelope.fence();
        CapacityManager.CapacityRequest capacity = envelope.capacityRequest();
        CapacityManager.CapacityScope scope = capacity.scope();
        return OPERATION_KIND.equals(envelope.operationKind())
                && envelope.family() == DispatchTicket.AsyncFamily.LOCAL_GIT
                && envelope.owner().kind() == TASK
                && operation.taskId().equals(envelope.owner().id())
                && CALLBACK_ROUTE.equals(envelope.owner().callbackRoute())
                && operation.operationId().equals(fence.operationId())
                && Long.valueOf(operation.taskEpoch()).equals(fence.taskEpoch())
                && operation.stageId().equals(fence.stageId())
                && Long.valueOf(operation.stageGeneration()).equals(
                        fence.stageGeneration())
                && operation.attempt() == fence.attempt()
                && operation.expectedCodeFingerprint().equals(
                        fence.expectedCodeFingerprint())
                && operation.expectedHeadSha().equals(fence.expectedHeadSha())
                && operation.expectedBaseSha().equals(fence.expectedBaseSha())
                && operation.operationId().equals(capacity.operationId())
                && capacity.source() == V2
                && capacity.lanes().equals(Set.of(LOCAL_GIT))
                && !capacity.trunkControl()
                && capacity.exclusiveTask()
                && capacity.writerRequired()
                && operation.workspaceId().equals(scope.workspaceId())
                && operation.trunkId().equals(scope.trunkId())
                && operation.taskId().equals(scope.taskId())
                && Long.valueOf(operation.taskEpoch()).equals(scope.taskEpoch())
                && "DISPATCHED".equals(operation.status());
    }

    private DispatchTicket.DispatchResult result(
            DispatchTicket.OperationFence fence, RepairResult result)
    {
        String payload = write(result);
        DispatchTicket.Outcome outcome = switch (result.disposition()) {
            case RESTORED -> SUCCEEDED;
            case FAILED, STALE -> FAILED;
            case CANCELED -> CANCELED;
        };
        return new DispatchTicket.DispatchResult(
                fence, outcome, payload, payload, result.error());
    }

    private String write(Object value)
    {
        try {
            return json.writeValueAsString(value);
        }
        catch (JsonProcessingException failure) {
            throw new IllegalStateException(
                    "Could not serialize quarantine repair proof", failure);
        }
    }

    public interface Store
    {
        Operation requireByOperationId(String operationId);

        Optional<ResultReceipt> findResultByOperationId(String operationId);

        ResultReceipt recordRestored(
                Operation operation,
                MutationFence fence,
                String resultBranchName,
                String resultCodeFingerprint,
                String resultHeadSha,
                String evidence,
                Instant recordedAt);
    }

    public record Operation(
            String id,
            String quarantineId,
            String blockerId,
            String taskId,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            String sourceOperationId,
            String operationId,
            String ticketId,
            int attempt,
            String commandId,
            String actor,
            String reason,
            String workspaceId,
            String trunkId,
            String worktreePath,
            String expectedBranchName,
            String expectedCodeFingerprint,
            String expectedHeadSha,
            String expectedBaseSha,
            String status,
            String taskLifecycle,
            long currentTaskEpoch,
            String currentStageId,
            Long currentStageGeneration,
            String currentCodeFingerprint,
            String currentHeadSha,
            String currentBaseSha,
            String currentBranchName,
            String quarantineStatus,
            String blockerStatus)
    {
        public Operation
        {
            requireNonNull(id, "id is null");
            requireNonNull(operationId, "operationId is null");
        }

        public boolean currentOwner()
        {
            return "ACTIVE".equals(taskLifecycle)
                    && currentTaskEpoch == taskEpoch
                    && stageId.equals(currentStageId)
                    && Long.valueOf(stageGeneration).equals(
                            currentStageGeneration)
                    && expectedCodeFingerprint.equals(currentCodeFingerprint)
                    && expectedHeadSha.equals(currentHeadSha)
                    && expectedBaseSha.equals(currentBaseSha)
                    && expectedBranchName.equals(currentBranchName)
                    && "OPEN".equals(quarantineStatus)
                    && "OPEN".equals(blockerStatus);
        }
    }

    public record ResultReceipt(
            String id,
            String repairOperationId,
            String operationId,
            String quarantineId,
            String resultCodeFingerprint,
            String resultHeadSha,
            String resultBranchName,
            boolean resultClean,
            boolean gitOperationStateClear,
            long writerFencingToken,
            String evidence,
            Instant recordedAt)
    {
    }

    public record RepairResult(
            int schemaVersion,
            Disposition disposition,
            String repairOperationId,
            String operationId,
            String quarantineId,
            String resultReceiptId,
            String expectedBranchName,
            String expectedCodeFingerprint,
            String expectedHeadSha,
            String expectedBaseSha,
            String resultBranchName,
            String resultCodeFingerprint,
            String resultHeadSha,
            Boolean resultClean,
            Boolean gitOperationStateClear,
            Long writerFencingToken,
            String evidence,
            String error)
    {
        static RepairResult restored(Operation operation, ResultReceipt receipt)
        {
            return new RepairResult(
                    RESULT_SCHEMA_VERSION, Disposition.RESTORED, operation.id(),
                    operation.operationId(), operation.quarantineId(),
                    receipt.id(), operation.expectedBranchName(),
                    operation.expectedCodeFingerprint(),
                    operation.expectedHeadSha(), operation.expectedBaseSha(),
                    receipt.resultBranchName(),
                    receipt.resultCodeFingerprint(), receipt.resultHeadSha(),
                    receipt.resultClean(), receipt.gitOperationStateClear(),
                    receipt.writerFencingToken(),
                    receipt.evidence(), null);
        }

        static RepairResult failed(
                Operation operation,
                String resultBranch,
                String resultHead,
                Boolean resultClean,
                String resultFingerprint,
                Boolean gitOperationStateClear,
                String error)
        {
            return new RepairResult(
                    RESULT_SCHEMA_VERSION, Disposition.FAILED, operation.id(),
                    operation.operationId(), operation.quarantineId(), null,
                    operation.expectedBranchName(),
                    operation.expectedCodeFingerprint(),
                    operation.expectedHeadSha(), operation.expectedBaseSha(),
                    resultBranch,
                    resultFingerprint, resultHead, resultClean,
                    gitOperationStateClear, null, null, error);
        }

        static RepairResult canceled(Operation operation, String error)
        {
            return new RepairResult(
                    RESULT_SCHEMA_VERSION, Disposition.CANCELED, operation.id(),
                    operation.operationId(), operation.quarantineId(), null,
                    operation.expectedBranchName(),
                    operation.expectedCodeFingerprint(),
                    operation.expectedHeadSha(), operation.expectedBaseSha(),
                    null, null, null, null, null, null, null, error);
        }

        static RepairResult stale(Operation operation, String error)
        {
            return new RepairResult(
                    RESULT_SCHEMA_VERSION, Disposition.STALE, operation.id(),
                    operation.operationId(), operation.quarantineId(), null,
                    operation.expectedBranchName(),
                    operation.expectedCodeFingerprint(),
                    operation.expectedHeadSha(), operation.expectedBaseSha(),
                    null, null, null, null, null, null, null, error);
        }
    }

    public record RepairEvidence(
            int schemaVersion,
            String quarantineId,
            String repairOperationId,
            String operationId,
            String taskId,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            String worktreePath,
            String expectedBranchName,
            String resultBranchName,
            String expectedCodeFingerprint,
            String expectedHeadSha,
            String expectedBaseSha,
            String resultCodeFingerprint,
            String resultHeadSha,
            boolean resultClean,
            boolean gitOperationStateClear,
            long writerFencingToken,
            String mode)
    {
    }

    public enum Disposition
    {
        RESTORED,
        FAILED,
        CANCELED,
        STALE
    }

    private enum Mode
    {
        EXECUTE,
        RECONCILE
    }

    private record RepairProbe(
            String branchName,
            String headSha,
            Boolean clean,
            String codeFingerprint,
            Boolean gitOperationStateClear,
            boolean exact)
    {
    }
}
