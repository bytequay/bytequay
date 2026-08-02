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

import com.bytequay.app.developmentflow.execution.publish.GitLocalPublishBaseSyncEffects;
import com.bytequay.app.developmentflow.execution.publish.LocalPublishBaseSyncOperationHandler;
import com.bytequay.app.developmentflow.execution.publish.LocalPublishBaseSyncOperationHandler.Disposition;
import com.bytequay.app.developmentflow.execution.publish.LocalPublishBaseSyncOperationHandler.OperationContext;
import com.bytequay.app.developmentflow.execution.publish.LocalPublishBaseSyncOperationHandler.Proof;
import com.bytequay.app.developmentflow.execution.publish.LocalPublishBaseSyncOperationHandler.Result;
import com.bytequay.app.service.checks.CodeFingerprints;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.local.GitRunner.RebaseApplyResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.LOCAL_GIT;
import static com.bytequay.app.developmentflow.execution.CapacityManager.WorkflowSource.V2;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestLocalPublishBaseSyncOperationHandler
{
    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");
    private static final String TASK_ID = "task-1";
    private static final String STAGE_ID = "local-stage-1";
    private static final String BRANCH = "dev/task-1";

    @TempDir
    private Path tempDir;

    private ObjectMapper json;
    private GitRunner git;
    private CodeFingerprints fingerprints;
    private InMemoryExecutionSupport.MutableClock clock;
    private CapacityManager capacity;
    private WorktreeWriterLeaseManager writers;
    private int contextSequence;

    @BeforeEach
    void setUp()
    {
        json = new ObjectMapper();
        git = new GitRunner();
        fingerprints = new CodeFingerprints(git);
        clock = new InMemoryExecutionSupport.MutableClock(NOW);
        capacity = new CapacityManager(
                new InMemoryExecutionSupport.CapacityStore(),
                () -> CapacityManager.CapacityPolicy.initial(
                        4, 4, Map.of(LOCAL_GIT, 4)),
                clock,
                Duration.ofMinutes(5));
        writers = new WorktreeWriterLeaseManager(
                new InMemoryExecutionSupport.WorktreeStore(), clock);
    }

    @Test
    void cancellationCannotReachTheGitEffects()
    {
        OperationContext operation = operation(
                LocalPublishBaseSyncOperationHandler.FETCH_COMPARE,
                tempDir.resolve("missing"), "fp", "head", "base", "target");
        NeverEffects effects = new NeverEffects();

        try (ContextFixture fixture = context(operation, operation.expectedHeadSha())) {
            fixture.cancellation().cancel();
            assertThatThrownBy(() -> handler(operation, effects)
                    .execute(fixture.context()))
                    .isInstanceOf(ExecutionPorts.OperationCanceledException.class);
        }
        assertThat(effects.calls).isZero();
    }

    @Test
    void cancelingTaskDiscardsReconciliationWithoutTouchingGit()
            throws Exception
    {
        OperationContext active = operation(
                LocalPublishBaseSyncOperationHandler.FETCH_COMPARE,
                tempDir.resolve("missing"), "fp", "head", "base", "target");
        OperationContext canceling = withLifecycle(
                active, "CANCELING", 2, null, 0, active.stageCheckpoint());
        NeverEffects effects = new NeverEffects();

        try (ContextFixture fixture = context(
                canceling, canceling.expectedHeadSha())) {
            DispatchTicket.DispatchResult raw = handler(canceling, effects)
                    .reconcile(fixture.context());
            assertThat(raw.outcome()).isEqualTo(DispatchTicket.Outcome.CANCELED);
            assertThat(raw.error()).contains("cancellation discarded");
        }
        assertThat(effects.calls).isZero();
    }

    @Test
    void pausingFetchWithNoLocalTargetParksWithoutFetching()
            throws Exception
    {
        RepositoryFixture repository = repository("pause-before-fetch", false);
        OperationContext active = operation(
                LocalPublishBaseSyncOperationHandler.FETCH_COMPARE,
                repository.worktree(), repository.sourceFingerprint(),
                repository.sourceHead(), repository.sourceBase(),
                repository.targetBase());
        OperationContext pausing = withLifecycle(
                active, "PAUSING", 1, STAGE_ID, 1, "LOCAL_REVIEW");

        try (ContextFixture fixture = context(
                pausing, pausing.expectedHeadSha())) {
            DispatchTicket.DispatchResult raw = handler(
                    pausing,
                    new GitLocalPublishBaseSyncEffects(git, fingerprints))
                    .reconcile(fixture.context());
            assertThat(raw.outcome()).isEqualTo(DispatchTicket.Outcome.CANCELED);
            assertThat(raw.error()).contains("Pause parked");
        }
        assertThat(git.headSha(repository.worktree()))
                .isEqualTo(repository.sourceHead());
        assertThat(git.resolveCommitSha(
                repository.worktree(), repository.targetBase())).isEmpty();
    }

    @Test
    void pausingAfterACompletedRebaseReturnsTheExactProofWithoutRebasingAgain()
            throws Exception
    {
        RepositoryFixture repository = repository("pause-after-rebase", false);
        fetch(repository);
        OperationContext active = operation(
                LocalPublishBaseSyncOperationHandler.MECHANICAL_REBASE,
                repository.worktree(), repository.sourceFingerprint(),
                repository.sourceHead(), repository.sourceBase(),
                repository.targetBase());
        Result applied = execute(active);
        OperationContext pausing = withLifecycle(
                active, "PAUSING", 1, STAGE_ID, 1, "LOCAL_REVIEW");

        try (ContextFixture fixture = context(
                pausing, pausing.expectedHeadSha())) {
            DispatchTicket.DispatchResult raw = handler(
                    pausing,
                    new GitLocalPublishBaseSyncEffects(git, fingerprints))
                    .reconcile(fixture.context());
            Result recovered = json.readValue(raw.payloadJson(), Result.class);
            assertThat(raw.outcome()).isEqualTo(DispatchTicket.Outcome.SUCCEEDED);
            assertThat(recovered.disposition()).isEqualTo(Disposition.REBASED);
            assertThat(recovered.headSha()).isEqualTo(applied.headSha());
            assertThat(recovered.evidence().recovered()).isTrue();
        }
    }

    @Test
    void wrongEnvelopeFenceCannotReachTheGitEffects()
    {
        OperationContext operation = operation(
                LocalPublishBaseSyncOperationHandler.FETCH_COMPARE,
                tempDir.resolve("missing"), "fp", "head", "base", "target");
        NeverEffects effects = new NeverEffects();

        try (ContextFixture fixture = context(operation, "wrong-head")) {
            assertThatThrownBy(() -> handler(operation, effects)
                    .execute(fixture.context()))
                    .isInstanceOf(
                            ExecutionPorts.IndeterminateExecutionException.class)
                    .hasMessageContaining("fence is stale");
        }
        assertThat(effects.calls).isZero();
    }

    @Test
    void fetchesTheExactRepositoryTargetWithoutChangingTheSubject()
            throws Exception
    {
        RepositoryFixture repository = repository("fetch", false);
        OperationContext operation = operation(
                LocalPublishBaseSyncOperationHandler.FETCH_COMPARE,
                repository.worktree(), repository.sourceFingerprint(),
                repository.sourceHead(), repository.sourceBase(),
                repository.targetBase());

        Result result = execute(operation);

        assertThat(result.disposition()).isEqualTo(Disposition.FETCHED);
        assertThat(result.codeFingerprint())
                .isEqualTo(repository.sourceFingerprint());
        assertThat(result.headSha()).isEqualTo(repository.sourceHead());
        assertThat(result.baseSha()).isEqualTo(repository.sourceBase());
        assertThat(result.evidence().proof()).isEqualTo(Proof.TARGET_PRESENT);
        assertThat(result.evidence().remoteName()).isEqualTo("origin");
        assertThat(result.evidence().recovered()).isFalse();
        assertThat(git.headSha(repository.worktree()))
                .isEqualTo(repository.sourceHead());
        assertThat(git.statusPorcelainZ(repository.worktree())).isEmpty();
    }

    @Test
    void cleanMechanicalRebaseIsProbeableAndNeverPushes()
            throws Exception
    {
        RepositoryFixture repository = repository("clean", false);
        fetch(repository);
        OperationContext operation = operation(
                LocalPublishBaseSyncOperationHandler.MECHANICAL_REBASE,
                repository.worktree(), repository.sourceFingerprint(),
                repository.sourceHead(), repository.sourceBase(),
                repository.targetBase());

        Result applied = execute(operation);
        String rebasedHead = applied.headSha();

        assertThat(applied.disposition()).isEqualTo(Disposition.REBASED);
        assertThat(applied.baseSha()).isEqualTo(repository.targetBase());
        assertThat(applied.evidence().proof()).isEqualTo(Proof.CLEAN);
        assertThat(applied.evidence().recovered()).isFalse();
        assertThat(rebasedHead).isNotEqualTo(repository.sourceHead());
        assertThat(git.mergeBase(
                repository.worktree(), rebasedHead, repository.targetBase()))
                .contains(repository.targetBase());
        assertThat(remoteHead(repository.remote(), "refs/heads/main"))
                .isEqualTo(repository.targetBase());
        assertThat(remoteHead(repository.remote(), "refs/heads/" + BRANCH))
                .isNull();

        Result recovered = reconcile(operation);

        assertThat(recovered.disposition()).isEqualTo(Disposition.REBASED);
        assertThat(recovered.headSha()).isEqualTo(rebasedHead);
        assertThat(recovered.evidence().proof()).isEqualTo(Proof.CLEAN);
        assertThat(recovered.evidence().recovered()).isTrue();
        assertThat(remoteHead(repository.remote(), "refs/heads/" + BRANCH))
                .isNull();
    }

    @Test
    void cancellationAfterTheClaimedRebaseDoesNotOverwriteItsObservedSuccess()
            throws Exception
    {
        RepositoryFixture repository = repository("cancel-after-rebase", false);
        fetch(repository);
        OperationContext operation = operation(
                LocalPublishBaseSyncOperationHandler.MECHANICAL_REBASE,
                repository.worktree(), repository.sourceFingerprint(),
                repository.sourceHead(), repository.sourceBase(),
                repository.targetBase());

        try (ContextFixture fixture = context(
                operation, operation.expectedHeadSha())) {
            GitRunner cancelingGit = new GitRunner()
            {
                @Override
                public RebaseApplyResult rebaseAndClassify(
                        Path workingDir, String base)
                        throws IOException, InterruptedException
                {
                    RebaseApplyResult result = super.rebaseAndClassify(
                            workingDir, base);
                    fixture.cancellation().cancel();
                    return result;
                }
            };
            LocalPublishBaseSyncOperationHandler handler = handler(
                    operation,
                    new GitLocalPublishBaseSyncEffects(
                            cancelingGit, new CodeFingerprints(cancelingGit)));

            DispatchTicket.DispatchResult raw = handler.execute(fixture.context());
            Result result = json.readValue(raw.payloadJson(), Result.class);

            assertThat(raw.outcome()).isEqualTo(DispatchTicket.Outcome.SUCCEEDED);
            assertThat(result.disposition()).isEqualTo(Disposition.REBASED);
            assertThat(result.baseSha()).isEqualTo(repository.targetBase());
            assertThat(result.evidence().proof()).isEqualTo(Proof.CLEAN);
            assertThat(git.headSha(repository.worktree()))
                    .isEqualTo(result.headSha());
        }
    }

    @Test
    void reconciliationObservesAClaimedRebaseEvenAfterCancellation()
            throws Exception
    {
        RepositoryFixture repository = repository("cancel-before-probe", false);
        fetch(repository);
        OperationContext operation = operation(
                LocalPublishBaseSyncOperationHandler.MECHANICAL_REBASE,
                repository.worktree(), repository.sourceFingerprint(),
                repository.sourceHead(), repository.sourceBase(),
                repository.targetBase());
        Result applied = execute(operation);

        try (ContextFixture fixture = context(
                operation, operation.expectedHeadSha())) {
            fixture.cancellation().cancel();
            LocalPublishBaseSyncOperationHandler handler = handler(
                    operation,
                    new GitLocalPublishBaseSyncEffects(git, fingerprints));

            DispatchTicket.DispatchResult raw = handler.reconcile(fixture.context());
            Result recovered = json.readValue(raw.payloadJson(), Result.class);

            assertThat(raw.outcome()).isEqualTo(DispatchTicket.Outcome.SUCCEEDED);
            assertThat(recovered.disposition()).isEqualTo(Disposition.REBASED);
            assertThat(recovered.headSha()).isEqualTo(applied.headSha());
            assertThat(recovered.evidence().recovered()).isTrue();
        }
    }

    @Test
    void reconciliationReexecutesAnExactRebaseFromItsCleanSource()
            throws Exception
    {
        RepositoryFixture repository = repository("recover-before-rebase", false);
        fetch(repository);
        OperationContext operation = operation(
                LocalPublishBaseSyncOperationHandler.MECHANICAL_REBASE,
                repository.worktree(), repository.sourceFingerprint(),
                repository.sourceHead(), repository.sourceBase(),
                repository.targetBase());

        Result recovered = reconcile(operation);

        assertThat(recovered.disposition()).isEqualTo(Disposition.REBASED);
        assertThat(recovered.headSha()).isNotEqualTo(repository.sourceHead());
        assertThat(recovered.baseSha()).isEqualTo(repository.targetBase());
        assertThat(recovered.evidence().proof()).isEqualTo(Proof.CLEAN);
        assertThat(recovered.evidence().recovered()).isTrue();
        assertThat(git.statusPorcelainZ(repository.worktree())).isEmpty();
    }

    @Test
    void reconciliationAbortsItsExactInterruptedRebaseAndClassifiesConflict()
            throws Exception
    {
        RepositoryFixture repository = repository("recover-conflict", true);
        fetch(repository);
        OperationContext operation = operation(
                LocalPublishBaseSyncOperationHandler.MECHANICAL_REBASE,
                repository.worktree(), repository.sourceFingerprint(),
                repository.sourceHead(), repository.sourceBase(),
                repository.targetBase());
        GitCommand interrupted = runGitAllowFailure(
                repository.worktree(), "rebase", repository.targetBase());
        assertThat(interrupted.exitCode()).isNotZero();

        Result recovered = reconcile(operation);

        assertThat(recovered.disposition()).isEqualTo(Disposition.CONFLICT);
        assertThat(recovered.headSha()).isEqualTo(repository.sourceHead());
        assertThat(recovered.baseSha()).isEqualTo(repository.sourceBase());
        assertThat(recovered.evidence().proof()).isEqualTo(Proof.CONFLICT);
        assertThat(recovered.evidence().conflictPaths()).contains("shared.txt");
        assertThat(recovered.evidence().recovered()).isTrue();
        assertThat(git.statusPorcelainZ(repository.worktree())).isEmpty();
        assertThat(git.currentBranch(repository.worktree())).isEqualTo(BRANCH);
    }

    @Test
    void reconciliationLeavesAForeignInProgressRebaseUntouched()
            throws Exception
    {
        RepositoryFixture repository = repository("foreign-rebase", true);
        fetch(repository);
        OperationContext operation = operation(
                LocalPublishBaseSyncOperationHandler.MECHANICAL_REBASE,
                repository.worktree(), repository.sourceFingerprint(),
                repository.sourceHead(), repository.sourceBase(),
                repository.targetBase());
        GitCommand interrupted = runGitAllowFailure(
                repository.worktree(), "rebase", repository.targetBase());
        assertThat(interrupted.exitCode()).isNotZero();
        Path state = gitPath(repository.worktree(), "rebase-merge");
        assertThat(state).isDirectory();
        Path onto = state.resolve("onto");
        String exactTarget = Files.readString(onto, StandardCharsets.UTF_8);
        Files.writeString(
                onto, repository.sourceBase() + "\n", StandardCharsets.UTF_8);

        try (ContextFixture fixture = context(
                operation, operation.expectedHeadSha())) {
            LocalPublishBaseSyncOperationHandler handler = handler(
                    operation,
                    new GitLocalPublishBaseSyncEffects(git, fingerprints));

            assertThatThrownBy(() -> handler.reconcile(fixture.context()))
                    .isInstanceOf(
                            ExecutionPorts.IndeterminateExecutionException.class)
                    .hasMessageContaining(
                            "Interrupted rebase could not be recovered exactly");
            assertThat(state).isDirectory();
        }
        finally {
            Files.writeString(onto, exactTarget, StandardCharsets.UTF_8);
            runGit(repository.worktree(), "rebase", "--abort");
        }
        assertThat(git.headSha(repository.worktree()))
                .isEqualTo(repository.sourceHead());
        assertThat(git.statusPorcelainZ(repository.worktree())).isEmpty();
    }

    @Test
    void actualConflictOverridesAnOptimisticPreviewAndRestoresTheSource()
            throws Exception
    {
        RepositoryFixture repository = repository("actual-conflict", true);
        fetch(repository);
        OperationContext operation = operation(
                LocalPublishBaseSyncOperationHandler.MECHANICAL_REBASE,
                repository.worktree(), repository.sourceFingerprint(),
                repository.sourceHead(), repository.sourceBase(),
                repository.targetBase());
        GitRunner optimisticPreview = new GitRunner()
        {
            @Override
            public RebaseOutcome rebasePreview(
                    Path workingDir, String head, String base)
            {
                return RebaseOutcome.CLEAN;
            }
        };

        Result result;
        try (ContextFixture fixture = context(
                operation, operation.expectedHeadSha())) {
            LocalPublishBaseSyncOperationHandler handler = handler(
                    operation,
                    new GitLocalPublishBaseSyncEffects(
                            optimisticPreview,
                            new CodeFingerprints(optimisticPreview)));
            DispatchTicket.DispatchResult raw = handler.execute(fixture.context());
            result = json.readValue(raw.payloadJson(), Result.class);
        }

        assertThat(result.disposition()).isEqualTo(Disposition.CONFLICT);
        assertThat(result.headSha()).isEqualTo(repository.sourceHead());
        assertThat(result.baseSha()).isEqualTo(repository.sourceBase());
        assertThat(result.evidence().proof()).isEqualTo(Proof.CONFLICT);
        assertThat(result.evidence().conflictPaths()).contains("shared.txt");
        assertThat(git.headSha(repository.worktree()))
                .isEqualTo(repository.sourceHead());
        assertThat(git.statusPorcelainZ(repository.worktree())).isEmpty();
    }

    @Test
    void unknownRebaseFailureOnTheRestoredSourceIsNotAConflict()
            throws Exception
    {
        RepositoryFixture repository = repository("unknown-rebase", false);
        fetch(repository);
        OperationContext operation = operation(
                LocalPublishBaseSyncOperationHandler.MECHANICAL_REBASE,
                repository.worktree(), repository.sourceFingerprint(),
                repository.sourceHead(), repository.sourceBase(),
                repository.targetBase());
        GitRunner failingGit = new GitRunner()
        {
            @Override
            public RebaseApplyResult rebaseAndClassify(
                    Path workingDir, String base)
                    throws IOException
            {
                throw new IOException("simulated Git infrastructure failure");
            }
        };

        Result result;
        try (ContextFixture fixture = context(
                operation, operation.expectedHeadSha())) {
            LocalPublishBaseSyncOperationHandler handler = handler(
                    operation,
                    new GitLocalPublishBaseSyncEffects(
                            failingGit, new CodeFingerprints(failingGit)));
            DispatchTicket.DispatchResult raw = handler.execute(fixture.context());
            result = json.readValue(raw.payloadJson(), Result.class);
            assertThat(raw.outcome()).isEqualTo(DispatchTicket.Outcome.FAILED);
        }

        assertThat(result.disposition()).isEqualTo(Disposition.FAILED);
        assertThat(result.evidence()).isNull();
        assertThat(result.error()).contains("restored its source head");
        assertThat(git.headSha(repository.worktree()))
                .isEqualTo(repository.sourceHead());
        assertThat(git.statusPorcelainZ(repository.worktree())).isEmpty();
    }

    @Test
    void conflictingPreviewReturnsTypedEvidenceWithoutMovingOrPushing()
            throws Exception
    {
        RepositoryFixture repository = repository("conflict", true);
        fetch(repository);
        OperationContext operation = operation(
                LocalPublishBaseSyncOperationHandler.MECHANICAL_REBASE,
                repository.worktree(), repository.sourceFingerprint(),
                repository.sourceHead(), repository.sourceBase(),
                repository.targetBase());

        Result result = execute(operation);

        assertThat(result.disposition()).isEqualTo(Disposition.CONFLICT);
        assertThat(result.headSha()).isEqualTo(repository.sourceHead());
        assertThat(result.baseSha()).isEqualTo(repository.sourceBase());
        assertThat(result.evidence().proof()).isEqualTo(Proof.CONFLICT);
        assertThat(result.evidence().conflictPaths()).contains("shared.txt");
        assertThat(git.headSha(repository.worktree()))
                .isEqualTo(repository.sourceHead());
        assertThat(git.statusPorcelainZ(repository.worktree())).isEmpty();
        assertThat(remoteHead(repository.remote(), "refs/heads/" + BRANCH))
                .isNull();
    }

    private void fetch(RepositoryFixture repository)
            throws Exception
    {
        Result result = execute(operation(
                LocalPublishBaseSyncOperationHandler.FETCH_COMPARE,
                repository.worktree(), repository.sourceFingerprint(),
                repository.sourceHead(), repository.sourceBase(),
                repository.targetBase()));
        assertThat(result.disposition()).isEqualTo(Disposition.FETCHED);
    }

    private Result execute(OperationContext operation)
            throws Exception
    {
        LocalPublishBaseSyncOperationHandler handler = handler(
                operation, new GitLocalPublishBaseSyncEffects(git, fingerprints));
        try (ContextFixture fixture = context(
                operation, operation.expectedHeadSha())) {
            DispatchTicket.DispatchResult raw = handler.execute(fixture.context());
            assertThat(raw.outcome()).isEqualTo(DispatchTicket.Outcome.SUCCEEDED);
            return json.readValue(raw.payloadJson(), Result.class);
        }
    }

    private Result reconcile(OperationContext operation)
            throws Exception
    {
        LocalPublishBaseSyncOperationHandler handler = handler(
                operation, new GitLocalPublishBaseSyncEffects(git, fingerprints));
        try (ContextFixture fixture = context(
                operation, operation.expectedHeadSha())) {
            DispatchTicket.DispatchResult raw = handler.reconcile(fixture.context());
            assertThat(raw.outcome()).isEqualTo(DispatchTicket.Outcome.SUCCEEDED);
            return json.readValue(raw.payloadJson(), Result.class);
        }
    }

    private LocalPublishBaseSyncOperationHandler handler(
            OperationContext operation,
            LocalPublishBaseSyncOperationHandler.Effects effects)
    {
        return new LocalPublishBaseSyncOperationHandler(
                operationId -> {
                    if (!operation.operationId().equals(operationId)) {
                        throw new IllegalStateException("missing operation");
                    }
                    return operation;
                },
                effects,
                writers,
                json);
    }

    private OperationContext operation(
            String operationKind,
            Path worktree,
            String fingerprint,
            String head,
            String base,
            String target)
    {
        return new OperationContext(
                "operation-" + operationKind,
                operationKind,
                "DISPATCHED",
                "workspace-1",
                "trunk-1",
                TASK_ID,
                1,
                STAGE_ID,
                1,
                1,
                "V2",
                "ACTIVE",
                1,
                STAGE_ID,
                1,
                "LOCAL_REVIEW",
                "acme/widget",
                BRANCH,
                worktree.toAbsolutePath().normalize().toString(),
                fingerprint,
                head,
                base,
                target);
    }

    private static OperationContext withLifecycle(
            OperationContext source,
            String lifecycle,
            long currentEpoch,
            String currentStageId,
            long currentStageGeneration,
            String checkpoint)
    {
        return new OperationContext(
                source.operationId(), source.operationKind(),
                source.operationStatus(), source.workspaceId(),
                source.trunkId(), source.taskId(), source.taskEpoch(),
                source.stageId(), source.stageGeneration(),
                source.semanticAttempt(), source.workflowVersion(), lifecycle,
                currentEpoch, currentStageId, currentStageGeneration,
                checkpoint, source.repositoryId(), source.branchName(),
                source.worktreePath(), source.expectedCodeFingerprint(),
                source.expectedHeadSha(), source.expectedBaseSha(),
                source.targetBaseSha());
    }

    private ContextFixture context(OperationContext operation, String fencedHead)
    {
        CapacityManager.CapacityRequest request =
                new CapacityManager.CapacityRequest(
                        operation.operationId(),
                        V2,
                        Set.of(LOCAL_GIT),
                        new CapacityManager.CapacityScope(
                                operation.workspaceId(), operation.trunkId(),
                                operation.taskId(), operation.taskEpoch()),
                        false,
                        true,
                        true);
        String ticketId = "ticket-" + ++contextSequence;
        String owner = "dispatcher-1";
        CapacityManager.CapacityLease lease = capacity.tryAcquireForTicket(
                        ticketId, request, owner)
                .lease()
                .orElseThrow();
        String callback = operation.kind()
                == LocalPublishBaseSyncOperationHandler.Kind.FETCH_COMPARE
                        ? LocalPublishBaseSyncOperationHandler.FETCH_CALLBACK
                        : LocalPublishBaseSyncOperationHandler.REBASE_CALLBACK;
        DispatchTicket.DispatchEnvelope envelope =
                new DispatchTicket.DispatchEnvelope(
                        operation.operationKind(),
                        DispatchTicket.AsyncFamily.LOCAL_GIT,
                        new DispatchTicket.OwnerReference(
                                DispatchTicket.OwnerKind.STAGE,
                                STAGE_ID,
                                callback),
                        new DispatchTicket.OperationFence(
                                operation.taskEpoch(),
                                STAGE_ID,
                                operation.stageGeneration(),
                                operation.operationId(),
                                operation.semanticAttempt(),
                                operation.expectedCodeFingerprint(),
                                fencedHead,
                                operation.expectedBaseSha()),
                        request);
        ExecutionContext.Cancellation cancellation =
                new ExecutionContext.Cancellation();
        ExecutionContext execution = new ExecutionContext(
                envelope,
                lease,
                cancellation,
                new NoOpEvidence(),
                "execution-" + contextSequence,
                clock,
                () -> capacity.requireExactLeaseForTicket(
                        ticketId, lease.id(), request, owner));
        return new ContextFixture(execution, cancellation, lease, owner);
    }

    private RepositoryFixture repository(String name, boolean conflict)
            throws Exception
    {
        Path root = tempDir.resolve(name);
        Path remote = root.resolve("acme/widget.git");
        Path seed = root.resolve("seed");
        Path worktree = root.resolve("worktree");
        Files.createDirectories(remote.getParent());
        runGit(root, "init", "--bare", remote.toString());
        Files.createDirectories(seed);
        runGit(seed, "init", "-b", "main");
        configureIdentity(seed);
        commit(seed, "shared.txt", "base\n", "Base");
        String sourceBase = git.headSha(seed);
        runGit(seed, "remote", "add", "origin", remote.toString());
        runGit(seed, "push", "-u", "origin", "main");
        runGit(root, "--git-dir=" + remote, "symbolic-ref",
                "HEAD", "refs/heads/main");
        runGit(root, "clone", "--branch", "main",
                remote.toString(), worktree.toString());
        configureIdentity(worktree);
        runGit(worktree, "switch", "-c", BRANCH);
        if (conflict) {
            commit(worktree, "shared.txt", "task\n", "Task change");
        }
        else {
            commit(worktree, "task.txt", "task\n", "Task change");
        }
        String sourceHead = git.headSha(worktree);
        String sourceFingerprint = fingerprints.fingerprint(worktree);
        if (conflict) {
            commit(seed, "shared.txt", "target\n", "Move base");
        }
        else {
            commit(seed, "base-target.txt", "target\n", "Move base");
        }
        String targetBase = git.headSha(seed);
        runGit(seed, "push", "origin", "main");
        return new RepositoryFixture(
                remote, worktree, sourceBase, targetBase,
                sourceHead, sourceFingerprint);
    }

    private static void configureIdentity(Path repository)
            throws Exception
    {
        runGit(repository, "config", "user.name", "ByteQuay Test");
        runGit(repository, "config", "user.email", "bytequay@example.test");
    }

    private static void commit(
            Path repository, String file, String body, String message)
            throws Exception
    {
        Files.writeString(repository.resolve(file), body, StandardCharsets.UTF_8);
        runGit(repository, "add", file);
        runGit(repository, "commit", "-m", message);
    }

    private static String remoteHead(Path remote, String ref)
            throws Exception
    {
        GitCommand result = runGitAllowFailure(
                remote.getParent(), "--git-dir=" + remote,
                "rev-parse", "--verify", ref);
        return result.exitCode() == 0 ? result.output().strip() : null;
    }

    private static Path gitPath(Path repository, String name)
            throws Exception
    {
        Path path = Path.of(runGit(
                repository, "rev-parse", "--git-path", name));
        return (path.isAbsolute() ? path : repository.resolve(path))
                .toAbsolutePath().normalize();
    }

    private static String runGit(Path directory, String... arguments)
            throws Exception
    {
        GitCommand result = runGitAllowFailure(directory, arguments);
        if (result.exitCode() != 0) {
            throw new IllegalStateException(
                    "git failed: " + result.output());
        }
        return result.output().strip();
    }

    private static GitCommand runGitAllowFailure(
            Path directory, String... arguments)
            throws IOException, InterruptedException
    {
        List<String> command = new ArrayList<>();
        command.add("git");
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command)
                .redirectErrorStream(true);
        if (directory != null) {
            builder.directory(directory.toFile());
        }
        Process process = builder.start();
        String output = new String(
                process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        return new GitCommand(process.waitFor(), output);
    }

    private record RepositoryFixture(
            Path remote,
            Path worktree,
            String sourceBase,
            String targetBase,
            String sourceHead,
            String sourceFingerprint) {}

    private record GitCommand(int exitCode, String output) {}

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

        private ExecutionContext context()
        {
            return context;
        }

        private ExecutionContext.Cancellation cancellation()
        {
            return cancellation;
        }

        @Override
        public void close()
        {
            context.closeWriterResource();
            capacity.release(lease.id(), owner);
        }
    }

    private static final class NeverEffects
            implements LocalPublishBaseSyncOperationHandler.Effects
    {
        private int calls;

        @Override
        public Result execute(
                OperationContext operation,
                ExecutionContext execution,
                WorktreeWriterLeaseManager.MutationFence writerFence)
        {
            calls++;
            throw new AssertionError("effect must not run");
        }

        @Override
        public Result probe(
                OperationContext operation,
                ExecutionContext execution,
                WorktreeWriterLeaseManager.MutationFence writerFence)
        {
            calls++;
            throw new AssertionError("effect must not run");
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
                String executionId,
                long sequence,
                String payloadJson,
                Instant createdAt) {}
        @Override public void recordUsage(
                String executionId,
                long inputTokens,
                long outputTokens,
                long costUsdMilli) {}
        @Override public void finish(
                String executionId,
                DispatchTicket.DispatchResult result,
                String failure,
                Instant finishedAt) {}
    }
}
