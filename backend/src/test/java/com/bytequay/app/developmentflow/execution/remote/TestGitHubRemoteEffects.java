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
import com.bytequay.app.developmentflow.stage.BaseCiHistoryRewriter;
import com.bytequay.app.developmentflow.stage.RemoteEffectOperationHandler;
import com.bytequay.app.domain.PrRawDetail;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.service.checks.CodeFingerprints;
import com.bytequay.app.service.checks.ValidationCheck;
import com.bytequay.app.service.checks.ValidationFailure;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.local.GitRunner;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestGitHubRemoteEffects
{
    private static final RepoRef REPOSITORY = RepoRef.parse("acme/widget");
    private static final PullRequestRef PULL_REQUEST = PullRequestRef.of(
            "acme", "widget", 17);

    @TempDir
    private Path tempDir;

    private GitRunner git;
    private BaseCiHistoryRewriter baseHistory;
    private CodeFingerprints fingerprints;
    private PullRequestRepository pullRequests;
    private PatResolver pats;
    private GitHubRemoteEffects effects;
    private ExecutionContext execution;

    @BeforeEach
    void setUp()
    {
        git = mock(GitRunner.class);
        baseHistory = mock(BaseCiHistoryRewriter.class);
        fingerprints = mock(CodeFingerprints.class);
        pullRequests = mock(PullRequestRepository.class);
        pats = mock(PatResolver.class);
        effects = new GitHubRemoteEffects(
                git, mock(GitRunnerProvisioningGit.class),
                baseHistory, fingerprints,
                List.of(), pullRequests, pats, new ObjectMapper());
        execution = mock(ExecutionContext.class);
        when(pats.resolve("acme/widget")).thenReturn("pat");
    }

    @Test
    void rerunsOnlyTheFailedChecksOnTheExactRemoteSubject()
            throws Exception
    {
        when(pullRequests.fetchPrDetail("pat", PULL_REQUEST))
                .thenReturn(detail("head-1", "base-1"));
        when(pullRequests.rerunFailedChecks("pat", REPOSITORY, "head-1"))
                .thenReturn(2);

        RemoteEffectOperationHandler.Result result = effects.perform(
                request("head-1", "base-1"),
                RemoteEffectOperationHandler.Mode.EXECUTE, execution, null);

        assertThat(result.disposition())
                .isEqualTo(RemoteEffectOperationHandler.Disposition.SUCCEEDED);
        assertThat(result.headSha()).isEqualTo("head-1");
        assertThat(result.evidence()).contains("2");
        verify(pullRequests).rerunFailedChecks("pat", REPOSITORY, "head-1");
    }

    @Test
    void refusesToMutateWhenGitHubMovedToAnotherHead()
    {
        when(pullRequests.fetchPrDetail("pat", PULL_REQUEST))
                .thenReturn(detail("head-2", "base-1"));

        assertThatThrownBy(() -> effects.perform(
                request("head-1", "base-1"),
                RemoteEffectOperationHandler.Mode.EXECUTE, execution, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exact effect subject");
        verify(pullRequests, never()).rerunFailedChecks(
                "pat", REPOSITORY, "head-1");
    }

    @Test
    void taskCiRepairUsesOnlyTheNormalPush()
            throws Exception
    {
        Path worktree = tempDir.resolve("task-repair");
        Files.createDirectories(worktree);
        RemoteEffectOperationHandler.Request request = pushRequest(
                worktree, "head-1", null, null, null, null, "remote-head");
        stubPushSubject(request, worktree, "remote-head", "head-1");

        RemoteEffectOperationHandler.Result result = effects.perform(
                request, RemoteEffectOperationHandler.Mode.EXECUTE, execution,
                fence(request));

        assertThat(result.disposition())
                .isEqualTo(RemoteEffectOperationHandler.Disposition.SUCCEEDED);
        verify(git).push(worktree);
        verify(git, never()).pushForceWithLease(worktree);
        verify(git, never()).pushRewrittenBranch(worktree);
        verify(git, never()).pushRewrittenBranch(
                worktree, "feature", "remote-head");
    }

    @Test
    void baseRewrittenPushUsesTheExactNamedLeaseWithoutFallback()
            throws Exception
    {
        Path worktree = tempDir.resolve("base-repair");
        Files.createDirectories(worktree);
        String manifestDigest = "a".repeat(64);
        RemoteEffectOperationHandler.Request request = pushRequest(
                worktree, "rewritten-head", "original-head", "authorization-1",
                manifestDigest, null, "remote-head");
        stubPushSubject(request, worktree, "remote-head", "remote-head");
        when(git.preservesBaseRepairHistory(
                worktree, "base-1", "original-head", "rewritten-head"))
                .thenReturn(true);
        when(git.pushRewrittenBranch(worktree, "feature", "remote-head"))
                .thenThrow(new IOException("lease rejected"));

        assertThatThrownBy(() -> effects.perform(
                request, RemoteEffectOperationHandler.Mode.EXECUTE, execution,
                fence(request)))
                .isInstanceOf(
                        ExecutionPorts.IndeterminateExecutionException.class);

        verify(git).pushRewrittenBranch(worktree, "feature", "remote-head");
        verify(git, never()).pushRewrittenBranch(worktree);
        verify(git, never()).pushForceWithLease(worktree);
        verify(git, never()).push(worktree);
    }

    @Test
    void prepublishRebaseUsesTheExactNamedLeaseWithoutFallback()
            throws Exception
    {
        Path worktree = tempDir.resolve("prepublish-rebase");
        Files.createDirectories(worktree);
        RemoteEffectOperationHandler.Request request = pushRequest(
                worktree, "fixed-rebased-head", null, null, null,
                "branch-sync-1", "remote-head");
        stubPushSubject(
                request, worktree, "remote-head", "fixed-rebased-head");

        RemoteEffectOperationHandler.Result result = effects.perform(
                request, RemoteEffectOperationHandler.Mode.EXECUTE, execution,
                fence(request));

        assertThat(result.disposition())
                .isEqualTo(RemoteEffectOperationHandler.Disposition.SUCCEEDED);
        verify(git).pushRewrittenBranch(
                worktree, "feature", "remote-head");
        verify(git, never()).pushRewrittenBranch(worktree);
        verify(git, never()).pushForceWithLease(worktree);
        verify(git, never()).push(worktree);
    }

    @Test
    void reconcileClosesAnAttemptWhoseRewriteWasNotObserved()
            throws Exception
    {
        Path worktree = tempDir.resolve("base-reconcile-unchanged");
        Files.createDirectories(worktree);
        RemoteEffectOperationHandler.Request request = rewriteRequest(
                worktree, "stage-head");
        stubRewriteSubject(request, worktree, "stage-head", "fingerprint");

        RemoteEffectOperationHandler.Result result = effects.perform(
                request, RemoteEffectOperationHandler.Mode.PROBE, execution,
                fence(request));

        assertThat(result.disposition())
                .isEqualTo(RemoteEffectOperationHandler.Disposition.FAILED);
        assertThat(result.evidence()).contains(
                "CI_BASE_REWRITE_V1", "History rewrite was not observed");
        verify(baseHistory, never()).rewrite(any());
        verify(baseHistory, never()).recover(any());
    }

    @Test
    void reconcileRecoversAnAlreadyRewrittenSubjectWithoutRewriting()
            throws Exception
    {
        Path worktree = tempDir.resolve("base-reconcile-rewritten");
        Files.createDirectories(worktree);
        RemoteEffectOperationHandler.Request request = rewriteRequest(
                worktree, "stage-head");
        stubRewriteSubject(
                request, worktree, "rewritten-head", "rewritten-fingerprint");
        when(baseHistory.recover(any())).thenReturn(
                new BaseCiHistoryRewriter.Result(
                        "rewritten-head", rewriteProof()));

        RemoteEffectOperationHandler.Result result = effects.perform(
                request, RemoteEffectOperationHandler.Mode.PROBE, execution,
                fence(request));

        assertThat(result.disposition())
                .isEqualTo(RemoteEffectOperationHandler.Disposition.SUCCEEDED);
        assertThat(result.headSha()).isEqualTo("rewritten-head");
        assertThat(result.evidence()).contains("repair-head");
        verify(baseHistory).recover(any());
        verify(baseHistory, never()).rewrite(any());
    }

    @Test
    void failedValidationRestoresTheExactStageHeadForTheNextRepair()
            throws Exception
    {
        Path worktree = tempDir.resolve("base-repair-validation-failure");
        Files.createDirectories(worktree);
        init(worktree);
        commit(worktree, "base.txt", "base", "Base");
        GitRunner realGit = new GitRunner();
        String baseSha = realGit.headSha(worktree);
        runGit(worktree, "switch", "-c", "feature");
        commit(worktree, "task.txt", "task", "Task change");
        String originalHead = realGit.headSha(worktree);
        commit(worktree, "repair.txt", "repair one", "Repair base CI");
        String stageHead = realGit.headSha(worktree);
        CodeFingerprints realFingerprints = new CodeFingerprints(realGit);
        String stageFingerprint = realFingerprints.fingerprint(worktree);
        RemoteEffectOperationHandler.Request request = rewriteRequest(
                worktree, stageFingerprint, stageHead, baseSha, originalHead);
        ValidationCheck failing = (taskId, path) -> List.of(
                new ValidationFailure("frontend", "lint failed"));
        ObjectMapper mapper = new ObjectMapper();
        GitHubRemoteEffects failingEffects = new GitHubRemoteEffects(
                realGit, mock(GitRunnerProvisioningGit.class),
                new BaseCiHistoryRewriter(realGit), realFingerprints,
                List.of(failing), pullRequests, pats, mapper);

        RemoteEffectOperationHandler.Result failed = failingEffects.perform(
                request, RemoteEffectOperationHandler.Mode.EXECUTE, execution,
                fence(request));

        RemoteEffectOperationHandler.BaseRewriteEvidence failedEvidence =
                mapper.readValue(
                        failed.evidence(),
                        RemoteEffectOperationHandler.BaseRewriteEvidence.class);
        assertThat(failed.disposition())
                .isEqualTo(RemoteEffectOperationHandler.Disposition.FAILED);
        assertThat(failed.headSha())
                .isEqualTo(failedEvidence.proof().rewrittenHeadSha())
                .isNotEqualTo(stageHead);
        assertThat(failedEvidence.validationFailures()).hasSize(1);
        assertThat(realGit.headSha(worktree)).isEqualTo(stageHead);
        assertThat(realFingerprints.fingerprint(worktree))
                .isEqualTo(stageFingerprint);
        assertThat(realGit.statusPorcelainZ(worktree)).isEmpty();

        commit(worktree, "repair.txt", "repair two", "Retry base CI repair");
        String retryHead = realGit.headSha(worktree);
        RemoteEffectOperationHandler.Request retry = rewriteRequest(
                worktree, realFingerprints.fingerprint(worktree), retryHead,
                baseSha, originalHead);
        GitHubRemoteEffects retryEffects = new GitHubRemoteEffects(
                realGit, mock(GitRunnerProvisioningGit.class),
                new BaseCiHistoryRewriter(realGit), realFingerprints,
                List.of(), pullRequests, pats, mapper);

        RemoteEffectOperationHandler.Result passed = retryEffects.perform(
                retry, RemoteEffectOperationHandler.Mode.EXECUTE, execution,
                fence(retry));

        RemoteEffectOperationHandler.BaseRewriteEvidence passedEvidence =
                mapper.readValue(
                        passed.evidence(),
                        RemoteEffectOperationHandler.BaseRewriteEvidence.class);
        assertThat(passed.disposition())
                .isEqualTo(RemoteEffectOperationHandler.Disposition.SUCCEEDED);
        assertThat(passedEvidence.proof().stageTurnOutputHeadSha())
                .isEqualTo(retryHead);
        assertThat(passedEvidence.proof().frozenOriginalHeadSha())
                .isEqualTo(originalHead);
    }

    @Test
    void retryKeepsTheOriginalTaskSeriesSeparateFromTheRewrittenHead()
            throws Exception
    {
        Path worktree = tempDir.resolve("base-repair-retry");
        Files.createDirectories(worktree);
        String retryHead = "prior-repair-plus-new-repair";
        RemoteEffectOperationHandler.Request request = rewriteRequest(
                worktree, retryHead);
        AtomicReference<String> head = new AtomicReference<>(retryHead);
        when(git.isGitWorkingTree(worktree)).thenReturn(true);
        when(git.currentBranch(worktree)).thenReturn(request.headRef());
        when(git.statusPorcelainZ(worktree)).thenReturn("");
        when(git.headSha(worktree)).thenAnswer(ignored -> head.get());
        when(git.commitShasInRange(
                worktree, "base-1", "original-head"))
                .thenReturn(List.of("original-a", "original-head"));
        when(fingerprints.fingerprint(worktree)).thenReturn("fingerprint");
        when(baseHistory.rewrite(any())).thenAnswer(ignored -> {
            head.set("rewritten-head");
            return new BaseCiHistoryRewriter.Result(
                    "rewritten-head", rewriteProof());
        });

        RemoteEffectOperationHandler.Result result = effects.perform(
                request, RemoteEffectOperationHandler.Mode.EXECUTE, execution,
                fence(request));

        assertThat(result.disposition())
                .isEqualTo(RemoteEffectOperationHandler.Disposition.SUCCEEDED);
        ArgumentCaptor<BaseCiHistoryRewriter.Request> rewrite =
                ArgumentCaptor.forClass(BaseCiHistoryRewriter.Request.class);
        verify(baseHistory).rewrite(rewrite.capture());
        assertThat(rewrite.getValue().frozenOriginalHeadSha())
                .isEqualTo("original-head");
        assertThat(rewrite.getValue().originalCommitShas())
                .containsExactly("original-a", "original-head");
        assertThat(rewrite.getValue().stageTurnOutputHeadSha())
                .isEqualTo(retryHead);
        verify(git).commitShasInRange(
                worktree, "base-1", "original-head");
        verify(git, never()).commitShasInRange(
                worktree, "base-1", retryHead);
    }

    @Test
    void validationCrashAfterRewriteRestoresTheStageTurnSubject()
            throws Exception
    {
        Path worktree = tempDir.resolve("base-reconcile-after-crash");
        Files.createDirectories(worktree);
        RemoteEffectOperationHandler.Request request = rewriteRequest(
                worktree, "stage-head");
        AtomicReference<String> head = new AtomicReference<>("stage-head");
        when(git.isGitWorkingTree(worktree)).thenReturn(true);
        when(git.currentBranch(worktree)).thenReturn(request.headRef());
        when(git.statusPorcelainZ(worktree)).thenReturn("");
        when(git.headSha(worktree)).thenAnswer(ignored -> head.get());
        when(git.commitShasInRange(
                worktree, "base-1", "original-head"))
                .thenReturn(List.of("original-head"));
        when(fingerprints.fingerprint(worktree)).thenAnswer(ignored ->
                head.get().equals("stage-head")
                        ? "fingerprint" : "rewritten-fingerprint");
        BaseCiHistoryRewriter.Result rewritten =
                new BaseCiHistoryRewriter.Result(
                        "rewritten-head", rewriteProof());
        when(baseHistory.rewrite(any())).thenAnswer(ignored -> {
            head.set("rewritten-head");
            return rewritten;
        });
        doAnswer(invocation -> {
            head.set(invocation.getArgument(1));
            return null;
        }).when(git).resetHard(worktree, "stage-head");
        ValidationCheck validation = mock(ValidationCheck.class);
        when(validation.run("task-1", worktree))
                .thenThrow(new IllegalStateException("runner crashed"));
        effects = new GitHubRemoteEffects(
                git, mock(GitRunnerProvisioningGit.class), baseHistory,
                fingerprints, List.of(validation), pullRequests, pats,
                new ObjectMapper());

        assertThatThrownBy(() -> effects.perform(
                request, RemoteEffectOperationHandler.Mode.EXECUTE, execution,
                fence(request)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("runner crashed");

        assertThat(head.get()).isEqualTo("stage-head");
        RemoteEffectOperationHandler.Result reconciled = effects.perform(
                request, RemoteEffectOperationHandler.Mode.PROBE, execution,
                fence(request));

        assertThat(reconciled.disposition())
                .isEqualTo(RemoteEffectOperationHandler.Disposition.FAILED);
        assertThat(reconciled.headSha()).isEqualTo("stage-head");
        verify(baseHistory).rewrite(any());
        verify(baseHistory, never()).recover(any());
        verify(git).resetHard(worktree, "stage-head");
    }

    @Test
    void cancellationAfterRewriteRestoresTheStageTurnSubject()
            throws Exception
    {
        Path worktree = tempDir.resolve("base-rewrite-canceled");
        Files.createDirectories(worktree);
        RemoteEffectOperationHandler.Request request = rewriteRequest(
                worktree, "stage-head");
        AtomicReference<String> head = new AtomicReference<>("stage-head");
        when(git.isGitWorkingTree(worktree)).thenReturn(true);
        when(git.currentBranch(worktree)).thenReturn(request.headRef());
        when(git.statusPorcelainZ(worktree)).thenReturn("");
        when(git.headSha(worktree)).thenAnswer(ignored -> head.get());
        when(git.commitShasInRange(
                worktree, "base-1", "original-head"))
                .thenReturn(List.of("original-head"));
        when(fingerprints.fingerprint(worktree)).thenAnswer(ignored ->
                head.get().equals("stage-head")
                        ? "fingerprint" : "rewritten-fingerprint");
        when(baseHistory.rewrite(any())).thenAnswer(ignored -> {
            head.set("rewritten-head");
            return new BaseCiHistoryRewriter.Result(
                    "rewritten-head", rewriteProof());
        });
        doAnswer(invocation -> {
            head.set(invocation.getArgument(1));
            return null;
        }).when(git).resetHard(worktree, "stage-head");
        ValidationCheck validation = mock(ValidationCheck.class);
        when(execution.isCancellationRequested()).thenReturn(false, true);
        effects = new GitHubRemoteEffects(
                git, mock(GitRunnerProvisioningGit.class), baseHistory,
                fingerprints, List.of(validation), pullRequests, pats,
                new ObjectMapper());

        assertThatThrownBy(() -> effects.perform(
                request, RemoteEffectOperationHandler.Mode.EXECUTE, execution,
                fence(request)))
                .isInstanceOf(ExecutionPorts.OperationCanceledException.class);

        assertThat(head.get()).isEqualTo("stage-head");
        verify(git).resetHard(worktree, "stage-head");
        verify(validation, never()).run("task-1", worktree);
    }

    private static RemoteEffectOperationHandler.Request request(
            String headSha, String baseSha)
    {
        return new RemoteEffectOperationHandler.Request(
                "operation-1", RemoteEffectOperationHandler.RERUN_CI,
                "task-1", "remote-stage-1", "acme/widget", 17,
                "/tmp/worktree", "feature", null, headSha, baseSha,
                null, null, null, null, null, null, "rerun:head-1");
    }

    private static RemoteEffectOperationHandler.Request pushRequest(
            Path worktree,
            String headSha,
            String baseRepairOriginalHeadSha,
            String baseRepairAuthorizationId,
            String baseRepairManifestDigest,
            String prepublishBranchSyncEpisodeId,
            String leaseSha)
    {
        return new RemoteEffectOperationHandler.Request(
                "operation-1", RemoteEffectOperationHandler.PUSH_CI_REPAIR,
                "task-1", "remote-stage-1", "acme/widget", 17,
                worktree.toString(), "feature", "fingerprint", headSha, "base-1",
                null, baseRepairOriginalHeadSha, baseRepairAuthorizationId,
                baseRepairManifestDigest, prepublishBranchSyncEpisodeId,
                leaseSha, "push:" + headSha);
    }

    private static RemoteEffectOperationHandler.Request rewriteRequest(
            Path worktree, String headSha)
    {
        return rewriteRequest(
                worktree, "fingerprint", headSha, "base-1", "original-head");
    }

    private static RemoteEffectOperationHandler.Request rewriteRequest(
            Path worktree,
            String fingerprint,
            String headSha,
            String baseSha,
            String originalHeadSha)
    {
        return new RemoteEffectOperationHandler.Request(
                "operation-1",
                RemoteEffectOperationHandler.REWRITE_VALIDATE_BASE_CI_REPAIR,
                "task-1", "remote-stage-1", "acme/widget", 17,
                worktree.toString(), "feature", fingerprint, headSha,
                baseSha, null, originalHeadSha, "authorization-1",
                "a".repeat(64), null, null, "rewrite:" + headSha);
    }

    private static BaseCiHistoryRewriter.Proof rewriteProof()
    {
        return new BaseCiHistoryRewriter.Proof(
                "base-1", "original-head", "stage-head",
                List.of("original-head"), List.of("repair-input"),
                "repair-head", "rewritten-head", "repair-patch",
                List.of(), "patch-digest", "patch-digest",
                "tree", "tree", false);
    }

    private void stubRewriteSubject(
            RemoteEffectOperationHandler.Request request,
            Path worktree,
            String headSha,
            String fingerprint)
            throws Exception
    {
        when(git.isGitWorkingTree(worktree)).thenReturn(true);
        when(git.currentBranch(worktree)).thenReturn(request.headRef());
        when(git.statusPorcelainZ(worktree)).thenReturn("");
        when(git.headSha(worktree)).thenReturn(headSha);
        when(git.commitShasInRange(
                worktree, "base-1", "original-head"))
                .thenReturn(List.of("original-head"));
        when(fingerprints.fingerprint(worktree)).thenReturn(fingerprint);
    }

    private void stubPushSubject(
            RemoteEffectOperationHandler.Request request,
            Path worktree,
            String beforePush,
            String afterPush)
            throws Exception
    {
        when(git.isGitWorkingTree(worktree)).thenReturn(true);
        when(git.currentBranch(worktree)).thenReturn(request.headRef());
        when(git.statusPorcelainZ(worktree)).thenReturn("");
        when(git.headSha(worktree)).thenReturn(request.expectedHeadSha());
        when(fingerprints.fingerprint(worktree))
                .thenReturn(request.expectedCodeFingerprint());
        when(git.remoteHeadSha(worktree, "origin", request.headRef()))
                .thenReturn(Optional.of(beforePush), Optional.of(afterPush));
    }

    private static WorktreeWriterLeaseManager.MutationFence fence(
            RemoteEffectOperationHandler.Request request)
    {
        WorktreeWriterLeaseManager.MutationFence fence =
                mock(WorktreeWriterLeaseManager.MutationFence.class);
        when(fence.worktreePath()).thenReturn(request.worktreePath());
        when(fence.taskId()).thenReturn(request.taskId());
        when(fence.operationId()).thenReturn(request.operationId());
        return fence;
    }

    private static PrRawDetail detail(String headSha, String baseSha)
    {
        return new PrRawDetail(
                "body", List.of(), false, true, "clean", 1, 1, 1,
                0, List.of(), headSha, "feature", "acme/widget", "main",
                "acme/widget", "open", false, baseSha, null);
    }

    private static void init(Path repo)
            throws IOException, InterruptedException
    {
        runGit(repo, "init", "-b", "main");
        runGit(repo, "config", "user.name", "Test");
        runGit(repo, "config", "user.email", "test@example.com");
        runGit(repo, "config", "commit.gpgsign", "false");
    }

    private static void commit(
            Path repo, String file, String content, String message)
            throws IOException, InterruptedException
    {
        Files.writeString(
                repo.resolve(file), content + "\n", StandardCharsets.UTF_8);
        runGit(repo, "add", ".");
        runGit(repo, "commit", "-m", message);
    }

    private static void runGit(Path repo, String... arguments)
            throws IOException, InterruptedException
    {
        String[] command = new String[arguments.length + 1];
        command[0] = "git";
        System.arraycopy(arguments, 0, command, 1, arguments.length);
        Process process = new ProcessBuilder(command)
                .directory(repo.toFile())
                .redirectErrorStream(true)
                .start();
        String output = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new IllegalStateException(
                    "git " + String.join(" ", arguments) + " failed ("
                            + exitCode + "): " + output);
        }
    }
}
