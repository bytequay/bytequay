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
package com.bytequay.app.developmentflow.execution.publish;

import com.bytequay.app.developmentflow.execution.ExecutionContext;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.developmentflow.execution.WorktreeWriterLeaseManager;
import com.bytequay.app.developmentflow.execution.publish.LocalPublishBaseSyncOperationHandler.Disposition;
import com.bytequay.app.developmentflow.execution.publish.LocalPublishBaseSyncOperationHandler.Evidence;
import com.bytequay.app.developmentflow.execution.publish.LocalPublishBaseSyncOperationHandler.OperationContext;
import com.bytequay.app.developmentflow.execution.publish.LocalPublishBaseSyncOperationHandler.Proof;
import com.bytequay.app.developmentflow.execution.publish.LocalPublishBaseSyncOperationHandler.Result;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.service.checks.CodeFingerprints;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.local.GitRunner.RebaseApplyResult;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Exact local Git effects for pre-publish base synchronization; never pushes. */
public final class GitLocalPublishBaseSyncEffects
        implements LocalPublishBaseSyncOperationHandler.Effects
{
    private final GitRunner git;
    private final CodeFingerprints fingerprints;

    public GitLocalPublishBaseSyncEffects(
            GitRunner git, CodeFingerprints fingerprints)
    {
        this.git = requireNonNull(git, "git is null");
        this.fingerprints = requireNonNull(fingerprints, "fingerprints is null");
    }

    @Override
    public Result execute(
            OperationContext operation,
            ExecutionContext execution,
            WorktreeWriterLeaseManager.MutationFence writerFence)
            throws Exception
    {
        Path worktree = requireWriter(operation, writerFence);
        return switch (operation.kind()) {
            case FETCH_COMPARE -> fetch(operation, execution, worktree);
            case MECHANICAL_REBASE ->
                    rebase(operation, execution, worktree, false);
        };
    }

    @Override
    public Result probe(
            OperationContext operation,
            ExecutionContext execution,
            WorktreeWriterLeaseManager.MutationFence writerFence)
            throws Exception
    {
        requireNonNull(execution, "execution is null");
        Path worktree = requireWriter(operation, writerFence);
        return switch (operation.kind()) {
            case FETCH_COMPARE -> probeFetch(operation, worktree);
            case MECHANICAL_REBASE ->
                    probeRebase(operation, execution, worktree);
        };
    }

    @Override
    public Optional<Result> settlePause(
            OperationContext operation,
            WorktreeWriterLeaseManager.MutationFence writerFence)
            throws Exception
    {
        Path worktree = requireWriter(operation, writerFence);
        return switch (operation.kind()) {
            case FETCH_COMPARE -> pauseFetch(operation, worktree);
            case MECHANICAL_REBASE -> pauseRebase(operation, worktree);
        };
    }

    private Optional<Result> pauseFetch(
            OperationContext operation, Path worktree)
            throws Exception
    {
        Subject source = requireSource(operation, worktree);
        if (!targetPresent(operation, worktree)) {
            return Optional.empty();
        }
        return Optional.of(fetched(
                operation, source, requireExactRemote(operation, worktree), true));
    }

    private Optional<Result> pauseRebase(
            OperationContext operation, Path worktree)
            throws Exception
    {
        if (abortExactInterruptedRebase(operation, worktree)) {
            return Optional.empty();
        }
        String currentHead = requireCleanBranch(operation, worktree);
        if (operation.expectedHeadSha().equals(currentHead)) {
            requireSource(operation, worktree);
            return Optional.empty();
        }
        return Optional.of(
                rebased(operation, requireRebased(operation, worktree), true));
    }

    private Result fetch(
            OperationContext operation,
            ExecutionContext execution,
            Path worktree)
            throws Exception
    {
        Subject source = requireSource(operation, worktree);
        String remote = requireExactRemote(operation, worktree);
        requireActive(execution);
        try {
            git.fetchRemote(worktree, remote);
        }
        catch (IOException | RuntimeException failure) {
            throw new ExecutionPorts.IndeterminateExecutionException(
                    "Exact Local publish base fetch outcome is unknown", failure);
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new ExecutionPorts.IndeterminateExecutionException(
                    "Exact Local publish base fetch was interrupted", failure);
        }
        requireActive(execution);
        if (!targetPresent(operation, worktree)) {
            return failed(operation,
                    "target base is not present after the exact remote fetch");
        }
        Subject after = requireSource(operation, worktree);
        if (!source.equals(after)) {
            throw new ExecutionPorts.IndeterminateExecutionException(
                    "Local publish base fetch changed its code subject");
        }
        return fetched(operation, after, remote, false);
    }

    private Result probeFetch(
            OperationContext operation,
            Path worktree)
            throws Exception
    {
        Subject source = requireSource(operation, worktree);
        String remote = requireExactRemote(operation, worktree);
        if (!targetPresent(operation, worktree)) {
            return failed(operation,
                    "target base is not present during fetch recovery");
        }
        return fetched(operation, source, remote, true);
    }

    private Result rebase(
            OperationContext operation,
            ExecutionContext execution,
            Path worktree,
            boolean recovered)
            throws Exception
    {
        Subject source = requireSource(operation, worktree);
        if (!targetPresent(operation, worktree)) {
            return failed(operation,
                    "mechanical rebase target is not present locally");
        }
        requireActive(execution);
        GitRunner.RebaseOutcome preview = git.rebasePreview(
                worktree, operation.expectedHeadSha(),
                operation.targetBaseSha());
        if (preview == GitRunner.RebaseOutcome.CONFLICTS) {
            List<String> conflicts = git.listMergeConflictPaths(
                    worktree, operation.expectedHeadSha(),
                    operation.targetBaseSha());
            requireSource(operation, worktree);
            return conflict(operation, source, conflicts, recovered);
        }
        if (preview != GitRunner.RebaseOutcome.CLEAN) {
            return failed(operation,
                    "Git could not prove a clean mechanical rebase");
        }
        requireActive(execution);
        RebaseApplyResult applied;
        try {
            applied = git.rebaseAndClassify(
                    worktree, operation.targetBaseSha());
        }
        catch (IOException | RuntimeException failure) {
            return recoverCanceledOrFailed(
                    operation, execution, worktree, failure);
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return recoverCanceledOrFailed(
                    operation, execution, worktree, failure);
        }
        if (!applied.rebased()) {
            Subject restored = requireSource(operation, worktree);
            return conflict(
                    operation, restored, applied.conflictPaths(), recovered);
        }
        return rebased(
                operation, requireRebased(operation, worktree), recovered);
    }

    private Result recoverCanceledOrFailed(
            OperationContext operation,
            ExecutionContext execution,
            Path worktree,
            Exception failure)
            throws Exception
    {
        Result recovered = recoverAfterRebaseFailure(
                operation, worktree, failure);
        if (execution.isCancellationRequested()
                && recovered.disposition() == Disposition.FAILED) {
            throw new ExecutionPorts.OperationCanceledException(
                    "Pause or cancel restored the exact base-sync source");
        }
        return recovered;
    }

    private Result probeRebase(
            OperationContext operation,
            ExecutionContext execution,
            Path worktree)
            throws Exception
    {
        abortExactInterruptedRebase(operation, worktree);
        String currentHead = requireCleanBranch(operation, worktree);
        if (!operation.expectedHeadSha().equals(currentHead)) {
            return rebased(operation, requireRebased(operation, worktree), true);
        }
        requireSource(operation, worktree);
        return rebase(operation, execution, worktree, true);
    }

    private boolean abortExactInterruptedRebase(
            OperationContext operation, Path worktree)
            throws Exception
    {
        try {
            if (git.abortExactRebase(
                    worktree, operation.branchName(),
                    operation.expectedHeadSha(), operation.targetBaseSha())) {
                requireSource(operation, worktree);
                return true;
            }
            return false;
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new ExecutionPorts.IndeterminateExecutionException(
                    "Interrupted rebase could not be recovered exactly",
                    failure);
        }
        catch (IOException | RuntimeException failure) {
            throw new ExecutionPorts.IndeterminateExecutionException(
                    "Interrupted rebase could not be recovered exactly",
                    failure);
        }
    }

    private Result recoverAfterRebaseFailure(
            OperationContext operation, Path worktree, Exception failure)
            throws Exception
    {
        try {
            String current = requireCleanBranch(operation, worktree);
            if (operation.expectedHeadSha().equals(current)) {
                return failed(operation,
                        "mechanical rebase failed and restored its source head");
            }
            return rebased(operation, requireRebased(operation, worktree), true);
        }
        catch (Exception recoveryFailure) {
            failure.addSuppressed(recoveryFailure);
            throw new ExecutionPorts.IndeterminateExecutionException(
                    "Mechanical rebase outcome is not independently proven",
                    failure);
        }
    }

    private Subject requireSource(OperationContext operation, Path worktree)
            throws Exception
    {
        String head = requireCleanBranch(operation, worktree);
        String fingerprint = fingerprints.fingerprint(worktree);
        Optional<String> base = git.mergeBase(
                worktree, head, operation.expectedBaseSha());
        if (!operation.expectedHeadSha().equals(head)
                || !operation.expectedCodeFingerprint().equals(fingerprint)
                || base.filter(operation.expectedBaseSha()::equals).isEmpty()) {
            throw new ExecutionPorts.IndeterminateExecutionException(
                    "Local publish base-sync source fence is stale");
        }
        return new Subject(
                fingerprint, head, operation.expectedBaseSha());
    }

    private RebasedSubject requireRebased(
            OperationContext operation, Path worktree)
            throws Exception
    {
        String head = requireCleanBranch(operation, worktree);
        if (operation.expectedHeadSha().equals(head)
                || git.mergeBase(worktree, head, operation.targetBaseSha())
                        .filter(operation.targetBaseSha()::equals).isEmpty()) {
            throw new ExecutionPorts.IndeterminateExecutionException(
                    "Recovered worktree is not based on the exact target");
        }
        List<String> sourcePatches = patchSeries(
                worktree, operation.expectedBaseSha(),
                operation.expectedHeadSha());
        List<String> resultPatches = patchSeries(
                worktree, operation.targetBaseSha(), head);
        if (sourcePatches.isEmpty() || !sourcePatches.equals(resultPatches)) {
            throw new ExecutionPorts.IndeterminateExecutionException(
                    "Mechanical rebase did not preserve the exact Task patch series");
        }
        return new RebasedSubject(fingerprints.fingerprint(worktree), head);
    }

    private List<String> patchSeries(Path worktree, String base, String head)
            throws IOException, InterruptedException
    {
        List<String> patches = new ArrayList<>();
        for (String commit : git.commitShasInRange(worktree, base, head)) {
            patches.add(git.stablePatchId(worktree, commit));
        }
        return List.copyOf(patches);
    }

    private String requireCleanBranch(
            OperationContext operation, Path worktree)
            throws Exception
    {
        if (!Files.isDirectory(worktree) || !git.isGitWorkingTree(worktree)
                || !operation.branchName().equals(git.currentBranch(worktree))
                || !git.statusPorcelainZ(worktree).isEmpty()) {
            throw new ExecutionPorts.IndeterminateExecutionException(
                    "Local publish base-sync worktree fence is stale");
        }
        return git.headSha(worktree);
    }

    private String requireExactRemote(
            OperationContext operation, Path worktree)
            throws IOException, InterruptedException
    {
        List<String> matches = new ArrayList<>();
        for (GitRunner.Remote remote : git.listRemotes(worktree)) {
            Optional<RepoRef> slug = git.remoteSlug(worktree, remote.name());
            if (slug.isPresent()
                    && operation.repositoryId().equalsIgnoreCase(
                            slug.orElseThrow().fullName())) {
                matches.add(remote.name());
            }
        }
        if (matches.size() != 1) {
            throw new IllegalStateException(
                    "expected one exact Git remote for "
                            + operation.repositoryId() + ", found "
                            + matches.size());
        }
        return matches.getFirst();
    }

    private boolean targetPresent(OperationContext operation, Path worktree)
            throws IOException, InterruptedException
    {
        return git.resolveCommitSha(worktree, operation.targetBaseSha())
                .filter(operation.targetBaseSha()::equals)
                .isPresent();
    }

    private static Path requireWriter(
            OperationContext operation,
            WorktreeWriterLeaseManager.MutationFence writerFence)
    {
        requireNonNull(operation, "operation is null");
        requireNonNull(writerFence, "writerFence is null");
        Path worktree = worktree(operation);
        Path fenced = Path.of(writerFence.worktreePath())
                .toAbsolutePath().normalize();
        if (!worktree.equals(fenced)
                || !operation.taskId().equals(writerFence.taskId())
                || operation.taskEpoch() != writerFence.taskEpoch()
                || !operation.operationId().equals(writerFence.operationId())) {
            throw new IllegalStateException(
                    "Local publish base-sync lacks its exact writer fence");
        }
        return worktree;
    }

    private static Path worktree(OperationContext operation)
    {
        return Path.of(operation.worktreePath()).toAbsolutePath().normalize();
    }

    private static void requireActive(ExecutionContext execution)
            throws ExecutionPorts.OperationCanceledException
    {
        if (execution.isCancellationRequested()) {
            throw new ExecutionPorts.OperationCanceledException(
                    "Local publish base-sync was canceled");
        }
    }

    private static Result fetched(
            OperationContext operation,
            Subject subject,
            String remote,
            boolean recovered)
    {
        Evidence evidence = evidence(
                operation, Proof.TARGET_PRESENT, remote, subject.fingerprint(),
                subject.headSha(), subject.baseSha(), List.of(), recovered);
        return result(
                operation, Disposition.FETCHED, subject.fingerprint(),
                subject.headSha(), subject.baseSha(), evidence, null);
    }

    private static Result rebased(
            OperationContext operation,
            RebasedSubject subject,
            boolean recovered)
    {
        Evidence evidence = evidence(
                operation, Proof.CLEAN, null, subject.fingerprint(),
                subject.headSha(), operation.targetBaseSha(), List.of(),
                recovered);
        return result(
                operation, Disposition.REBASED, subject.fingerprint(),
                subject.headSha(), operation.targetBaseSha(), evidence, null);
    }

    private static Result conflict(
            OperationContext operation,
            Subject subject,
            List<String> conflicts,
            boolean recovered)
    {
        Evidence evidence = evidence(
                operation, Proof.CONFLICT, null, subject.fingerprint(),
                subject.headSha(), subject.baseSha(), conflicts, recovered);
        return result(
                operation, Disposition.CONFLICT, subject.fingerprint(),
                subject.headSha(), subject.baseSha(), evidence, null);
    }

    private static Result failed(OperationContext operation, String error)
    {
        return result(operation, Disposition.FAILED,
                null, null, null, null, error);
    }

    private static Result result(
            OperationContext operation,
            Disposition disposition,
            String fingerprint,
            String head,
            String base,
            Evidence evidence,
            String error)
    {
        return new Result(
                1, operation.operationId(), operation.kind(), disposition,
                fingerprint, head, base, operation.targetBaseSha(), evidence,
                error);
    }

    private static Evidence evidence(
            OperationContext operation,
            Proof proof,
            String remote,
            String resultFingerprint,
            String resultHead,
            String resultBase,
            List<String> conflicts,
            boolean recovered)
    {
        return new Evidence(
                1, operation.kind(), proof, operation.repositoryId(), remote,
                operation.branchName(), operation.worktreePath(),
                operation.expectedCodeFingerprint(),
                operation.expectedHeadSha(), operation.expectedBaseSha(),
                operation.targetBaseSha(), resultFingerprint, resultHead,
                resultBase, conflicts, recovered);
    }

    private record Subject(String fingerprint, String headSha, String baseSha) {}

    private record RebasedSubject(String fingerprint, String headSha) {}
}
