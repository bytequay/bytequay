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
package com.bytequay.app.developmentflow.execution.remote;

import com.bytequay.app.developmentflow.execution.ExecutionContext;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.developmentflow.execution.WorktreeWriterLeaseManager;
import com.bytequay.app.developmentflow.execution.provisioning.GitRunnerProvisioningGit;
import com.bytequay.app.developmentflow.stage.RemoteEffectOperationHandler;
import com.bytequay.app.service.checks.CodeFingerprints;
import com.bytequay.app.service.checks.RepoTestValidationCheck;
import com.bytequay.app.service.checks.ValidationFailure;
import com.bytequay.app.service.local.GitRunner;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static com.bytequay.app.developmentflow.stage.RemoteEffectOperationHandler.Disposition.CONFLICT;
import static com.bytequay.app.developmentflow.stage.RemoteEffectOperationHandler.Disposition.FAILED;
import static com.bytequay.app.developmentflow.stage.RemoteEffectOperationHandler.Disposition.SUCCEEDED;
import static com.bytequay.app.developmentflow.stage.RemoteEffectOperationHandler.FETCH_BRANCH;
import static com.bytequay.app.developmentflow.stage.RemoteEffectOperationHandler.PUSH_BRANCH;
import static com.bytequay.app.developmentflow.stage.RemoteEffectOperationHandler.REBASE_BRANCH;
import static com.bytequay.app.developmentflow.stage.RemoteEffectOperationHandler.VALIDATE_BRANCH;
import static java.util.Objects.requireNonNull;

/** Exact Git effects for the finite branch-sync protocol. */
@Component
public final class GitHubRemoteEffects
        implements RemoteEffectOperationHandler.EffectPort
{
    private final GitRunner git;
    private final GitRunnerProvisioningGit remotes;
    private final CodeFingerprints fingerprints;
    private final RepoTestValidationCheck check;
    private final ObjectMapper json;

    public GitHubRemoteEffects(
            GitRunner git,
            GitRunnerProvisioningGit remotes,
            CodeFingerprints fingerprints,
            RepoTestValidationCheck check,
            ObjectMapper json)
    {
        this.git = requireNonNull(git, "git is null");
        this.remotes = requireNonNull(remotes, "remotes is null");
        this.fingerprints = requireNonNull(fingerprints, "fingerprints is null");
        this.check = requireNonNull(check, "check is null");
        this.json = requireNonNull(json, "json is null");
    }

    @Override
    public RemoteEffectOperationHandler.Result perform(
            RemoteEffectOperationHandler.Request request,
            RemoteEffectOperationHandler.Mode mode,
            ExecutionContext execution,
            WorktreeWriterLeaseManager.MutationFence writerFence)
            throws Exception
    {
        requireNonNull(request, "request is null");
        requireNonNull(mode, "mode is null");
        requireNonNull(execution, "execution is null");
        return switch (request.operationKind()) {
            case FETCH_BRANCH -> fetch(request, execution, writerFence);
            case REBASE_BRANCH -> rebase(request, execution, writerFence);
            case VALIDATE_BRANCH ->
                    validate(request, execution, writerFence);
            case PUSH_BRANCH -> push(request, execution, writerFence);
            default -> throw new IllegalArgumentException(
                    "Unsupported Remote effect " + request.operationKind());
        };
    }

    private RemoteEffectOperationHandler.Result fetch(
            RemoteEffectOperationHandler.Request request,
            ExecutionContext execution,
            WorktreeWriterLeaseManager.MutationFence writerFence)
            throws Exception
    {
        Path worktree = requireWriter(request, writerFence);
        CodeSubject before = requireSubject(request, worktree);
        requireActive(execution);
        String remote = remotes.requireExactRemote(
                worktree, request.repositoryId());
        git.fetchRemote(worktree, remote);
        requireActive(execution);
        String target = requireText(request.targetBaseSha(), "targetBaseSha");
        if (git.resolveCommitSha(worktree, target).isEmpty()) {
            return failed(request, before.fingerprint(), before.headSha(),
                    request.expectedBaseSha(),
                    "target base is not present after exact remote fetch");
        }
        CodeSubject after = requireSubject(request, worktree);
        if (!before.equals(after)) {
            return failed(request, after.fingerprint(), after.headSha(),
                    target, "fetch changed the local code subject");
        }
        return succeeded(request, after.fingerprint(), after.headSha(), target,
                "fetched exact remote " + remote + " at " + target);
    }

    private RemoteEffectOperationHandler.Result rebase(
            RemoteEffectOperationHandler.Request request,
            ExecutionContext execution,
            WorktreeWriterLeaseManager.MutationFence writerFence)
            throws Exception
    {
        Path worktree = requireWriter(request, writerFence);
        String target = requireText(request.targetBaseSha(), "targetBaseSha");
        requireCleanBranch(request, worktree);
        String currentHead = git.headSha(worktree);
        if (!request.expectedHeadSha().equals(currentHead)) {
            Optional<String> base = git.mergeBase(worktree, currentHead, target);
            if (base.filter(target::equals).isPresent()) {
                return succeeded(request, fingerprints.fingerprint(worktree),
                        currentHead, target, "rebase already proven after recovery");
            }
            return failed(request, fingerprints.fingerprint(worktree),
                    currentHead, target,
                    "worktree moved to a head that is not based on the target");
        }
        CodeSubject before = requireSubject(request, worktree);
        requireActive(execution);
        GitRunner.RebaseOutcome preview = git.rebasePreview(
                worktree, request.expectedHeadSha(), target);
        if (preview == GitRunner.RebaseOutcome.CONFLICTS) {
            return result(request, CONFLICT, before.fingerprint(),
                    before.headSha(), target,
                    write(git.listMergeConflictPaths(
                            worktree, request.expectedHeadSha(), target)), null);
        }
        if (preview != GitRunner.RebaseOutcome.CLEAN) {
            return failed(request, before.fingerprint(), before.headSha(), target,
                    "Git could not prove that the rebase is safe");
        }
        git.rebase(worktree, target);
        requireActive(execution);
        String rebasedHead = git.headSha(worktree);
        if (request.expectedHeadSha().equals(rebasedHead)
                || git.mergeBase(worktree, rebasedHead, target)
                        .filter(target::equals).isEmpty()) {
            return failed(request, fingerprints.fingerprint(worktree),
                    rebasedHead, target,
                    "rebase did not produce a new head on the exact target");
        }
        return succeeded(request, fingerprints.fingerprint(worktree),
                rebasedHead, target, "rebased exact head onto " + target);
    }

    private RemoteEffectOperationHandler.Result validate(
            RemoteEffectOperationHandler.Request request,
            ExecutionContext execution,
            WorktreeWriterLeaseManager.MutationFence writerFence)
            throws Exception
    {
        requireNoWriter(writerFence);
        Path worktree = Path.of(request.worktreePath());
        CodeSubject before = requireSubject(request, worktree);
        requireActive(execution);
        List<ValidationFailure> failures = check.run(request.taskId(), worktree);
        CodeSubject after = requireSubject(request, worktree);
        if (!before.equals(after)) {
            return failed(request, after.fingerprint(), after.headSha(),
                    request.expectedBaseSha(),
                    "code subject changed while validation was running");
        }
        if (!failures.isEmpty()) {
            return result(request, FAILED, after.fingerprint(), after.headSha(),
                    request.expectedBaseSha(), write(failures),
                    failures.size() + " validation failure(s)");
        }
        return succeeded(request, after.fingerprint(), after.headSha(),
                request.expectedBaseSha(), "validation passed");
    }

    private RemoteEffectOperationHandler.Result push(
            RemoteEffectOperationHandler.Request request,
            ExecutionContext execution,
            WorktreeWriterLeaseManager.MutationFence writerFence)
            throws Exception
    {
        Path worktree = requireWriter(request, writerFence);
        CodeSubject subject = requireSubject(request, worktree);
        requireCleanBranch(request, worktree);
        requireActive(execution);
        git.fetchRemote(worktree, "origin");
        Optional<String> observed = git.remoteHeadSha(
                worktree, "origin", request.headRef());
        if (observed.filter(subject.headSha()::equals).isPresent()) {
            return succeeded(request, subject.fingerprint(), subject.headSha(),
                    request.expectedBaseSha(), "exact pushed head already observed");
        }
        if (observed.isEmpty()
                || !request.forceWithLeaseExpectedSha().equals(
                        observed.orElseThrow())) {
            return failed(request, subject.fingerprint(), subject.headSha(),
                    request.expectedBaseSha(),
                    "remote head moved outside the exact push authorization");
        }
        try {
            GitRunner.GitResult pushed = git.pushRewrittenBranch(
                    worktree, request.headRef(),
                    request.forceWithLeaseExpectedSha());
            pushed.requireSuccess();
        }
        catch (IOException | RuntimeException failure) {
            return recoverPush(request, subject, worktree, failure);
        }
        catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return recoverPush(request, subject, worktree, failure);
        }
        Optional<String> pushed = git.remoteHeadSha(
                worktree, "origin", request.headRef());
        if (pushed.filter(subject.headSha()::equals).isEmpty()) {
            throw new ExecutionPorts.IndeterminateExecutionException(
                    "push returned but the exact remote head is not observable");
        }
        return succeeded(request, subject.fingerprint(), subject.headSha(),
                request.expectedBaseSha(), "force-with-lease push proven");
    }

    private RemoteEffectOperationHandler.Result recoverPush(
            RemoteEffectOperationHandler.Request request,
            CodeSubject subject,
            Path worktree,
            Exception failure)
            throws Exception
    {
        try {
            Optional<String> remote = git.remoteHeadSha(
                    worktree, "origin", request.headRef());
            if (remote.filter(subject.headSha()::equals).isPresent()) {
                return succeeded(request, subject.fingerprint(), subject.headSha(),
                        request.expectedBaseSha(),
                        "exact pushed head recovered after ambiguous response");
            }
        }
        catch (Exception probeFailure) {
            failure.addSuppressed(probeFailure);
        }
        throw new ExecutionPorts.IndeterminateExecutionException(
                "push outcome is not independently proven", failure);
    }

    private CodeSubject requireSubject(
            RemoteEffectOperationHandler.Request request, Path worktree)
            throws IOException, InterruptedException
    {
        requireCleanBranch(request, worktree);
        CodeSubject subject = new CodeSubject(
                fingerprints.fingerprint(worktree), git.headSha(worktree));
        if (!request.expectedHeadSha().equals(subject.headSha())
                || request.expectedCodeFingerprint() != null
                && !request.expectedCodeFingerprint().equals(subject.fingerprint())) {
            throw new IllegalStateException(
                    "local code differs from the exact Remote effect subject");
        }
        return subject;
    }

    private void requireCleanBranch(
            RemoteEffectOperationHandler.Request request, Path worktree)
            throws IOException, InterruptedException
    {
        if (!Files.isDirectory(worktree) || !git.isGitWorkingTree(worktree)) {
            throw new IllegalStateException("Remote effect worktree is missing");
        }
        if (!request.headRef().equals(git.currentBranch(worktree))) {
            throw new IllegalStateException(
                    "Remote effect worktree is not on its exact head branch");
        }
        if (!git.statusPorcelainZ(worktree).isEmpty()) {
            throw new IllegalStateException(
                    "Remote effect worktree contains uncommitted changes");
        }
    }

    private static Path requireWriter(
            RemoteEffectOperationHandler.Request request,
            WorktreeWriterLeaseManager.MutationFence writerFence)
    {
        requireNonNull(writerFence, "writerFence is null");
        Path requestPath = Path.of(request.worktreePath()).toAbsolutePath().normalize();
        Path fencedPath = Path.of(writerFence.worktreePath())
                .toAbsolutePath().normalize();
        if (!requestPath.equals(fencedPath)
                || !request.taskId().equals(writerFence.taskId())
                || !request.operationId().equals(writerFence.operationId())) {
            throw new IllegalStateException(
                    "Remote mutation does not hold its exact writer fence");
        }
        return requestPath;
    }

    private static void requireNoWriter(
            WorktreeWriterLeaseManager.MutationFence writerFence)
    {
        if (writerFence != null) {
            throw new IllegalArgumentException(
                    "read-only Remote effect unexpectedly received a writer fence");
        }
    }

    private static void requireActive(ExecutionContext execution)
            throws ExecutionPorts.OperationCanceledException
    {
        if (execution.isCancellationRequested()) {
            throw new ExecutionPorts.OperationCanceledException(
                    "Remote effect was canceled");
        }
    }

    private RemoteEffectOperationHandler.Result succeeded(
            RemoteEffectOperationHandler.Request request,
            String fingerprint,
            String headSha,
            String baseSha,
            String evidence)
    {
        return result(request, SUCCEEDED, fingerprint, headSha, baseSha,
                evidence, null);
    }

    private RemoteEffectOperationHandler.Result failed(
            RemoteEffectOperationHandler.Request request,
            String fingerprint,
            String headSha,
            String baseSha,
            String error)
    {
        return result(request, FAILED, fingerprint, headSha, baseSha,
                null, error);
    }

    private static RemoteEffectOperationHandler.Result result(
            RemoteEffectOperationHandler.Request request,
            RemoteEffectOperationHandler.Disposition disposition,
            String fingerprint,
            String headSha,
            String baseSha,
            String evidence,
            String error)
    {
        return new RemoteEffectOperationHandler.Result(
                1, request.operationId(), disposition, fingerprint,
                headSha, baseSha, evidence, error);
    }

    private String write(Object value)
    {
        try {
            return json.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("Serializing Remote evidence failed", e);
        }
    }

    private static String requireText(String value, String name)
    {
        requireNonNull(value, name + " is null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }

    private record CodeSubject(String fingerprint, String headSha) {}
}
