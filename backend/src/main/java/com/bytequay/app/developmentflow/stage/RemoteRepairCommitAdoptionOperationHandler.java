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
package com.bytequay.app.developmentflow.stage;

import com.bytequay.app.developmentflow.execution.CapacityManager;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.ExecutionContext;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.developmentflow.execution.WorktreeWriterLeaseManager;
import com.bytequay.app.developmentflow.execution.WorktreeWriterLeaseManager.MutationFence;
import com.bytequay.app.service.checks.CodeFingerprints;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.local.GitRunner.ReflogEntry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSet;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.LOCAL_GIT;
import static com.bytequay.app.developmentflow.execution.CapacityManager.WorkflowSource.V2;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.CANCELED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.FAILED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.Outcome.SUCCEEDED;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.OwnerKind.TASK;
import static java.util.Objects.requireNonNull;

/** Adopts one independently proven commit after strict result normalization. */
public final class RemoteRepairCommitAdoptionOperationHandler
        implements ExecutionPorts.OperationHandler
{
    public static final String OPERATION_KIND =
            "ADOPT_NORMALIZED_REMOTE_REPAIR";
    public static final String CALLBACK_ROUTE =
            "REMOTE_REPAIR_COMMIT_ADOPTION_RESULT";
    public static final int RESULT_SCHEMA_VERSION = 1;

    private static final int REFLOG_SCAN_LIMIT = 256;

    private final Store store;
    private final WorktreeWriterLeaseManager writers;
    private final GitRunner git;
    private final CodeFingerprints fingerprints;
    private final ObjectMapper json;
    private final Clock clock;

    public RemoteRepairCommitAdoptionOperationHandler(
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
        Operation operation = store.requireByOperationId(
                context.envelope().fence().operationId());
        if (!exactEnvelope(context.envelope(), operation)
                || !operation.currentOwner()) {
            return result(context.envelope().fence(), AdoptionResult.stale(
                    operation, "Remote repair adoption owner fence is stale"));
        }
        if (context.isCancellationRequested()) {
            return result(context.envelope().fence(), AdoptionResult.canceled(
                    operation, "Remote repair adoption was canceled"));
        }
        WorktreeWriterLeaseManager.Lease lease = writers.acquire(
                context, operation.worktreePath());
        AdoptionResult adopted;
        try {
            adopted = writers.authorizeMutation(context, lease)
                    .run(fence -> adopt(context, operation, fence, mode));
        }
        catch (AdoptionRestoreException failure) {
            writers.quarantine(
                    context, lease,
                    quarantineEvidence(operation, failure));
            adopted = AdoptionResult.failed(
                    operation, null, failure.getMessage());
        }
        return result(context.envelope().fence(), adopted);
    }

    private AdoptionResult adopt(
            ExecutionContext context,
            Operation operation,
            MutationFence fence,
            Mode mode)
    {
        Path worktree = Path.of(operation.worktreePath());
        Candidate candidate = null;
        boolean restoreOnFailure = false;
        try {
            candidate = candidate(operation, worktree);
            Optional<ResultReceipt> prior = store.findResultByOperationId(
                    operation.operationId());
            Probe initial = probe(operation, candidate, worktree);
            if (prior.isPresent()) {
                if (!initial.candidateExact()) {
                    return AdoptionResult.failed(
                            operation, candidate,
                            "Adopted Remote repair commit changed after proof");
                }
                return AdoptionResult.adopted(operation, candidate,
                        store.recordAdopted(
                                operation, fence, candidate,
                                initial.codeFingerprint(), prior.orElseThrow().evidence(),
                                clock.instant()));
            }
            if (initial.candidateExact()) {
                restoreOnFailure = true;
                return record(operation, candidate, fence, initial, mode);
            }
            if (!initial.sourceExact()) {
                return AdoptionResult.failed(
                        operation, candidate,
                        "Task worktree is neither the frozen source nor candidate");
            }
            if (context.isCancellationRequested()) {
                return AdoptionResult.canceled(
                        operation, "Remote repair adoption was canceled");
            }
            git.resetHard(worktree, candidate.headSha());
            restoreOnFailure = true;
            Probe adopted = probe(operation, candidate, worktree);
            if (!adopted.candidateExact()) {
                restoreSource(operation, worktree);
                restoreOnFailure = false;
                return AdoptionResult.failed(
                        operation, candidate,
                        "Candidate commit did not produce the exact clean Task subject");
            }
            if (context.isCancellationRequested()) {
                restoreSource(operation, worktree);
                restoreOnFailure = false;
                return AdoptionResult.canceled(
                        operation, "Remote repair adoption was canceled before proof");
            }
            return record(operation, candidate, fence, adopted, mode);
        }
        catch (AdoptionRestoreException failure) {
            throw failure;
        }
        catch (InterruptedException failure) {
            if (restoreOnFailure) {
                restoreSource(operation, worktree);
            }
            Thread.currentThread().interrupt();
            return AdoptionResult.failed(
                    operation, candidate,
                    "Remote repair adoption was interrupted: "
                            + failure.getMessage());
        }
        catch (IOException | RuntimeException failure) {
            if (restoreOnFailure) {
                restoreSource(operation, worktree);
            }
            return AdoptionResult.failed(
                    operation, candidate,
                    "Remote repair adoption failed: " + failure.getMessage());
        }
    }

    private AdoptionResult record(
            Operation operation,
            Candidate candidate,
            MutationFence fence,
            Probe probe,
            Mode mode)
    {
        String evidence = write(new AdoptionEvidence(
                RESULT_SCHEMA_VERSION, operation.id(), operation.normalizationId(),
                operation.operationId(), operation.sourceOperationId(),
                operation.taskId(), operation.taskEpoch(), operation.stageId(),
                operation.stageGeneration(), operation.worktreePath(),
                operation.expectedBranchName(),
                operation.sourceCodeSubjectRevision(),
                operation.sourceCodeSubjectKind(),
                operation.sourceCodeSubjectId(),
                operation.sourceCodeFingerprint(),
                operation.sourceHeadSha(), operation.expectedBaseSha(),
                candidate.headSha(), candidate.sourceTreeSha(),
                candidate.resultTreeSha(), probe.codeFingerprint(),
                candidate.discoveryKind(), operation.sourceHeadSha(), 1,
                operation.sourceExecutionStartedAt().toEpochMilli(),
                operation.sourceExecutionFinishedAt().toEpochMilli(),
                candidate.reflogAt(),
                fence.fencingToken(), mode.name()));
        ResultReceipt receipt = store.recordAdopted(
                operation, fence, candidate, probe.codeFingerprint(), evidence,
                clock.instant());
        return AdoptionResult.adopted(operation, candidate, receipt);
    }

    private Candidate candidate(Operation operation, Path worktree)
            throws IOException, InterruptedException
    {
        if (operation.candidateHeadSha() != null) {
            return verifiedCandidate(
                    operation, worktree, operation.candidateHeadSha(),
                    operation.candidateSourceTreeSha(),
                    operation.candidateResultTreeSha(),
                    "FROZEN_WRITER_PROOF_V1", null);
        }
        Map<String, ReflogEntry> matching = new LinkedHashMap<>();
        for (ReflogEntry entry : git.listReflog(worktree, REFLOG_SCAN_LIMIT)) {
            Instant at = parseInstant(entry.reflogAt());
            if (at == null
                    || at.isBefore(operation.sourceExecutionStartedAt())
                    || at.isAfter(operation.sourceExecutionFinishedAt())
                    || operation.sourceHeadSha().equals(entry.sha())) {
                continue;
            }
            Optional<String> resolved = git.resolveCommitSha(worktree, entry.sha());
            if (resolved.isEmpty() || !entry.sha().equals(resolved.orElseThrow())) {
                continue;
            }
            List<String> parents = git.commitParentShas(worktree, entry.sha());
            if (parents.equals(List.of(operation.sourceHeadSha()))
                    && !git.commitTreeSha(worktree, operation.sourceHeadSha())
                            .equals(git.commitTreeSha(worktree, entry.sha()))) {
                matching.putIfAbsent(entry.sha(), entry);
            }
        }
        if (matching.size() != 1) {
            throw new IllegalStateException(
                    "Expected one exact repair commit in the frozen execution "
                            + "window, found " + matching.size());
        }
        ReflogEntry selected = matching.values().iterator().next();
        return verifiedCandidate(
                operation, worktree, selected.sha(), null, null,
                "LEGACY_REFLOG_WINDOW_V1", selected.reflogAt());
    }

    private Candidate verifiedCandidate(
            Operation operation,
            Path worktree,
            String headSha,
            String expectedSourceTreeSha,
            String expectedResultTreeSha,
            String discoveryKind,
            String reflogAt)
            throws IOException, InterruptedException
    {
        String resolved = git.resolveCommitSha(worktree, headSha)
                .orElseThrow(() -> new IllegalStateException(
                        "Remote repair candidate commit is missing"));
        if (!headSha.equals(resolved)
                || !git.commitParentShas(worktree, headSha)
                        .equals(List.of(operation.sourceHeadSha()))) {
            throw new IllegalStateException(
                    "Remote repair candidate is not one append-only commit");
        }
        String sourceTree = git.commitTreeSha(worktree, operation.sourceHeadSha());
        String resultTree = git.commitTreeSha(worktree, headSha);
        if (sourceTree.equals(resultTree)
                || expectedSourceTreeSha != null
                    && !expectedSourceTreeSha.equals(sourceTree)
                || expectedResultTreeSha != null
                    && !expectedResultTreeSha.equals(resultTree)
                || !git.mergeBase(worktree, headSha, operation.sourceHeadSha())
                        .filter(operation.sourceHeadSha()::equals).isPresent()
                || !git.mergeBase(worktree, headSha, operation.expectedBaseSha())
                        .filter(operation.expectedBaseSha()::equals).isPresent()) {
            throw new IllegalStateException(
                    "Remote repair candidate tree or ancestry proof is invalid");
        }
        return new Candidate(
                headSha, sourceTree, resultTree, discoveryKind, reflogAt);
    }

    private Probe probe(
            Operation operation, Candidate candidate, Path worktree)
            throws IOException, InterruptedException
    {
        String branch = git.currentBranch(worktree);
        String head = git.headSha(worktree);
        boolean clean = !git.hasUncommittedChanges(worktree);
        boolean controlClear = git.inProgressOperations(worktree).isEmpty();
        String fingerprint = fingerprints.fingerprint(worktree);
        boolean common = operation.expectedBranchName().equals(branch)
                && clean && controlClear;
        boolean source = common
                && operation.sourceHeadSha().equals(head)
                && operation.sourceCodeFingerprint().equals(fingerprint);
        boolean candidateExact = common
                && candidate.headSha().equals(head)
                && (operation.candidateCodeFingerprint() == null
                    || operation.candidateCodeFingerprint().equals(fingerprint));
        return new Probe(branch, head, fingerprint, source, candidateExact);
    }

    private void restoreSource(Operation operation, Path worktree)
    {
        try {
            git.resetHard(worktree, operation.sourceHeadSha());
            Probe restored = probe(
                    operation,
                    new Candidate(operation.sourceHeadSha(), "source", "source",
                            "RESTORE", null),
                    worktree);
            if (!restored.sourceExact()) {
                throw new AdoptionRestoreException(
                        "could not restore the exact Remote repair source");
            }
        }
        catch (IOException failure) {
            throw new AdoptionRestoreException(
                    "could not restore Remote repair source", failure);
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AdoptionRestoreException(
                    "Remote repair source restore was interrupted", failure);
        }
        catch (AdoptionRestoreException failure) {
            throw failure;
        }
        catch (RuntimeException failure) {
            throw new AdoptionRestoreException(
                    "could not prove restored Remote repair source", failure);
        }
    }

    private WorktreeWriterLeaseManager.QuarantineEvidence quarantineEvidence(
            Operation operation, AdoptionRestoreException failure)
    {
        Path worktree = Path.of(operation.worktreePath());
        String observedBranch = null;
        String observedHead = null;
        Boolean observedClean = null;
        String observedFingerprint = null;
        String probeError = null;
        try {
            observedBranch = git.currentBranch(worktree);
            observedHead = git.headSha(worktree);
            observedClean = !git.hasUncommittedChanges(worktree);
            observedFingerprint = fingerprints.fingerprint(worktree);
        }
        catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            probeError = "post-restore probe was interrupted: "
                    + interrupted.getMessage();
        }
        catch (IOException | RuntimeException probeFailure) {
            probeError = "post-restore probe failed: "
                    + probeFailure.getMessage();
        }
        return new WorktreeWriterLeaseManager.QuarantineEvidence(
                operation.taskId(), operation.stageId(),
                operation.operationId(), operation.worktreePath(),
                operation.expectedBranchName(),
                operation.sourceCodeFingerprint(), operation.sourceHeadSha(),
                observedBranch, observedHead, observedClean,
                observedFingerprint, probeError, failure.getMessage());
    }

    private static Instant parseInstant(String value)
    {
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        }
        catch (RuntimeException ignored) {
            return null;
        }
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
                && operation.sourceCodeFingerprint().equals(
                        fence.expectedCodeFingerprint())
                && operation.sourceHeadSha().equals(fence.expectedHeadSha())
                && operation.expectedBaseSha().equals(fence.expectedBaseSha())
                && operation.operationId().equals(capacity.operationId())
                && capacity.source() == V2
                && capacity.lanes().equals(ImmutableSet.of(LOCAL_GIT))
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
            DispatchTicket.OperationFence fence, AdoptionResult result)
    {
        String payload = write(result);
        DispatchTicket.Outcome outcome = switch (result.disposition()) {
            case ADOPTED -> SUCCEEDED;
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
                    "Could not serialize Remote repair adoption proof", failure);
        }
    }

    public interface Store
    {
        Operation requireByOperationId(String operationId);

        Optional<ResultReceipt> findResultByOperationId(String operationId);

        ResultReceipt recordAdopted(
                Operation operation,
                MutationFence fence,
                Candidate candidate,
                String resultCodeFingerprint,
                String evidence,
                Instant recordedAt);
    }

    public record Operation(
            String id,
            String normalizationId,
            String sourceOperationId,
            String operationId,
            String ticketId,
            String taskId,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            int attempt,
            String workspaceId,
            String trunkId,
            String worktreePath,
            String expectedBranchName,
            long sourceCodeSubjectRevision,
            String sourceCodeSubjectKind,
            String sourceCodeSubjectId,
            String sourceCodeFingerprint,
            String sourceHeadSha,
            String expectedBaseSha,
            String candidateHeadSha,
            String candidateCodeFingerprint,
            String candidateSourceTreeSha,
            String candidateResultTreeSha,
            Instant sourceExecutionStartedAt,
            Instant sourceExecutionFinishedAt,
            String status,
            boolean currentCiEpisodeFixing,
            boolean currentMalformedBlockerOpen,
            boolean currentCodeSource,
            String taskLifecycle,
            long currentTaskEpoch,
            String currentStageId,
            Long currentStageGeneration,
            String currentCodeFingerprint,
            String currentHeadSha,
            String currentBaseSha)
    {
        public boolean currentOwner()
        {
            return currentCiEpisodeFixing
                    && currentMalformedBlockerOpen
                    && currentCodeSource
                    && "ACTIVE".equals(taskLifecycle)
                    && currentTaskEpoch == taskEpoch
                    && stageId.equals(currentStageId)
                    && Long.valueOf(stageGeneration).equals(
                            currentStageGeneration)
                    && sourceCodeFingerprint.equals(currentCodeFingerprint)
                    && sourceHeadSha.equals(currentHeadSha)
                    && expectedBaseSha.equals(currentBaseSha);
        }
    }

    public record Candidate(
            String headSha,
            String sourceTreeSha,
            String resultTreeSha,
            String discoveryKind,
            String reflogAt) {}

    public record ResultReceipt(
            String id,
            String adoptionOperationId,
            String operationId,
            String candidateHeadSha,
            String resultCodeFingerprint,
            String resultTreeSha,
            long writerFencingToken,
            String evidence,
            Instant recordedAt) {}

    public record AdoptionResult(
            int schemaVersion,
            Disposition disposition,
            String adoptionOperationId,
            String operationId,
            String normalizationId,
            String sourceOperationId,
            String sourceHeadSha,
            String expectedBaseSha,
            String candidateHeadSha,
            String sourceTreeSha,
            String resultTreeSha,
            String resultReceiptId,
            String resultCodeFingerprint,
            String evidence,
            String error)
    {
        private static AdoptionResult adopted(
                Operation operation, Candidate candidate, ResultReceipt receipt)
        {
            return new AdoptionResult(
                    RESULT_SCHEMA_VERSION, Disposition.ADOPTED, operation.id(),
                    operation.operationId(), operation.normalizationId(),
                    operation.sourceOperationId(), operation.sourceHeadSha(),
                    operation.expectedBaseSha(), candidate.headSha(),
                    candidate.sourceTreeSha(), candidate.resultTreeSha(),
                    receipt.id(), receipt.resultCodeFingerprint(),
                    receipt.evidence(), null);
        }

        private static AdoptionResult failed(
                Operation operation, Candidate candidate, String error)
        {
            return terminal(operation, candidate, Disposition.FAILED, error);
        }

        private static AdoptionResult stale(Operation operation, String error)
        {
            return terminal(operation, null, Disposition.STALE, error);
        }

        private static AdoptionResult canceled(Operation operation, String error)
        {
            return terminal(operation, null, Disposition.CANCELED, error);
        }

        private static AdoptionResult terminal(
                Operation operation,
                Candidate candidate,
                Disposition disposition,
                String error)
        {
            return new AdoptionResult(
                    RESULT_SCHEMA_VERSION, disposition, operation.id(),
                    operation.operationId(), operation.normalizationId(),
                    operation.sourceOperationId(), operation.sourceHeadSha(),
                    operation.expectedBaseSha(),
                    candidate == null ? null : candidate.headSha(),
                    candidate == null ? null : candidate.sourceTreeSha(),
                    candidate == null ? null : candidate.resultTreeSha(),
                    null, null, null, error);
        }
    }

    public record AdoptionEvidence(
            int schemaVersion,
            String adoptionOperationId,
            String normalizationId,
            String operationId,
            String sourceOperationId,
            String taskId,
            long taskEpoch,
            String stageId,
            long stageGeneration,
            String worktreePath,
            String branchName,
            long sourceCodeSubjectRevision,
            String sourceCodeSubjectKind,
            String sourceCodeSubjectId,
            String sourceCodeFingerprint,
            String sourceHeadSha,
            String baseSha,
            String candidateHeadSha,
            String sourceTreeSha,
            String resultTreeSha,
            String resultCodeFingerprint,
            String candidateCaptureKind,
            String candidateParentSha,
            int candidateCount,
            long sourceExecutionStartedAtMs,
            long sourceExecutionFinishedAtMs,
            String reflogAt,
            long writerFencingToken,
            String mode) {}

    private record Probe(
            String branchName,
            String headSha,
            String codeFingerprint,
            boolean sourceExact,
            boolean candidateExact) {}

    private enum Mode
    {
        EXECUTE,
        RECONCILE
    }

    private static final class AdoptionRestoreException
            extends RuntimeException
    {
        private AdoptionRestoreException(String message)
        {
            super(message);
        }

        private AdoptionRestoreException(String message, Throwable cause)
        {
            super(message, cause);
        }
    }

    public enum Disposition
    {
        ADOPTED,
        FAILED,
        STALE,
        CANCELED
    }
}
