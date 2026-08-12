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
package com.bytequay.app.flow.ci;

import com.bytequay.app.flow.ci.CiAutofixRecords.AttemptState;
import com.bytequay.app.flow.ci.CiAutofixRecords.CiCleanupCompletion;
import com.bytequay.app.flow.ci.CiAutofixRecords.CiCleanupSeal;
import com.bytequay.app.flow.ci.CiAutofixRecords.CiRepairAttempt;
import com.bytequay.app.flow.ci.CiAutofixRecords.CleanupOutcome;
import com.bytequay.app.flow.runtime.FlowRuntime;
import com.bytequay.app.flow.runtime.FlowRuntime.CiCleanupAdmissionBlockedException;
import com.bytequay.app.flow.runtime.FlowRuntime.CiCleanupFinalizationReceipt;
import com.bytequay.app.flow.runtime.FlowRuntime.CiCleanupWriterStart;
import com.bytequay.app.flow.runtime.FlowRuntime.PreparedCiCleanupAdmission;
import com.bytequay.app.flow.runtime.FlowRuntime.PreparedCiCleanupFinalState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentResult;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRole;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRun;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Operation;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.RunState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Task;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.WriterFence;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector.NonCleanInspection;
import com.bytequay.app.flow.runtime.InProcessWriterAgentSupervisor;
import com.bytequay.app.flow.runtime.InProcessWriterAgentSupervisor.AgentCompletion;
import com.bytequay.app.flow.runtime.InProcessWriterAgentSupervisor.ExecutionHandle;
import com.bytequay.app.flow.runtime.InProcessWriterAgentSupervisor.WriterToolCapability;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

/**
 * The one concrete transaction joining CI evidence to runtime dispatch.
 *
 * <p>It joins exact-head enqueue/selection to one runtime-owned writer run and
 * joins the runtime's stopped-writer transaction to CI attempt finalization.

/** Owns the sealed cleanup successor of a non-clean CI repair. */
public final class CiCleanupCoordinator
{
    private static final String CI_CLEANUP_PROMPT_MANIFEST =
            "ci-cleanup-prompt:v1";
    private static final String CI_CLEANUP_CAPABILITY_SET =
            "ci-cleanup-capabilities:v1";

    private final TransactionTemplate transactions;
    private final CiAutofix autofix;
    private final FlowRuntime runtime;

    public CiCleanupCoordinator(
            DataSource dataSource, CiAutofix autofix, FlowRuntime runtime)
    {
        this.transactions = new TransactionTemplate(
                new DataSourceTransactionManager(
                        requireNonNull(dataSource, "dataSource is null")));
        this.autofix = requireNonNull(autofix, "autofix is null");
        this.runtime = requireNonNull(runtime, "runtime is null");
    }

    public record CleanupBinding(
            CiRepairAttempt predecessor,
            CiCleanupSeal seal,
            AgentRun run,
            WriterFence fence)
    {
        public CleanupBinding
        {
            requireNonNull(predecessor, "predecessor is null");
            requireNonNull(seal, "seal is null");
            requireNonNull(run, "run is null");
            requireNonNull(fence, "fence is null");
        }
    }

    /** Private-construction authority for one exact current policy poll. */

    public Optional<CleanupBinding> beginCleanup(
            Claim claim,
            Path programOwnedRepositoryRoot,
            Duration leaseTtl)
    {
        requireNonNull(claim, "claim is null");
        requireNonNull(programOwnedRepositoryRoot,
                "programOwnedRepositoryRoot is null");
        requireNonNull(leaseTtl, "leaseTtl is null");
        CiCleanupSeal seal = autofix.cleanupSealForSuccessor(
                claim.operationId()).orElseThrow(() ->
                        new IllegalArgumentException(
                                "claim is not a durable CI cleanup"));
        CiRepairAttempt predecessor = autofix.repairAttempt(
                seal.repairAttemptId()).orElseThrow();
        if (predecessor.state() != AttemptState.NON_CLEAN_HANDOFF
                || !seal.successorOperationId().equals(claim.operationId())
                || predecessor.resultRef() == null) {
            throw new IllegalStateException(
                    "CI cleanup predecessor identity is inconsistent");
        }
        Optional<CiCleanupCompletion> terminal =
                autofix.cleanupCompletion(seal.cleanupId());
        if (terminal.isPresent()
                && terminal.get().outcome()
                    == CleanupOutcome.ADMISSION_BLOCKED) {
            return Optional.empty();
        }
        if (terminal.isPresent()) {
            throw new IllegalStateException(
                    "CI cleanup is already terminal");
        }
        NonCleanInspection sealedState = toInspection(seal);
        PreparedCiCleanupAdmission prepared;
        try {
            prepared = runtime.prepareCiCleanupAdmission(
                    claim,
                    programOwnedRepositoryRoot,
                    seal.cleanupId(),
                    predecessor.attemptId(),
                    predecessor.operationId(),
                    predecessor.agentRunId(),
                    predecessor.resultRef(),
                    predecessor.inputChangeSetRevisionId(),
                    predecessor.inputLocalHead(),
                    sealedState);
        }
        catch (CiCleanupAdmissionBlockedException blocked) {
            requireNonNull(transactions.execute(ignored -> {
                CiCleanupFinalizationReceipt receipt =
                        runtime.blockCiCleanupAdmission(
                                claim, blocked.prepared());
                autofix.storeCleanupCompletion(receipt);
                return Boolean.TRUE;
            }), "cleanup admission block transaction returned null");
            return Optional.empty();
        }
        CiCleanupWriterStart start = runtime.startCiCleanupWriter(
                claim,
                prepared,
                leaseTtl,
                CI_CLEANUP_PROMPT_MANIFEST,
                CI_CLEANUP_CAPABILITY_SET);
        CleanupBinding binding = new CleanupBinding(
                predecessor, seal, start.run(), start.fence());
        requireCleanupLaunchBinding(binding, claim);
        return Optional.of(binding);
    }

    /** Launches cleanup only with its immutable program-owned finalizer route. */
    public ExecutionHandle launchCleanup(
            InProcessWriterAgentSupervisor supervisor,
            CleanupBinding binding,
            Claim claim,
            Path programOwnedRepositoryRoot,
            Function<WriterToolCapability, AgentCompletion> body)
    {
        requireNonNull(supervisor, "supervisor is null");
        requireNonNull(binding, "binding is null");
        requireNonNull(programOwnedRepositoryRoot,
                "programOwnedRepositoryRoot is null");
        requireNonNull(body, "body is null");
        requireCleanupLaunchBinding(binding, claim);
        return supervisor.launch(
                binding.run().runId(),
                claim,
                binding.fence(),
                cleanupFinalizerKey(binding.seal().cleanupId()),
                (runId, currentClaim, currentFence, completion) ->
                        finalizeCleanup(
                                binding.seal().cleanupId(),
                                runId,
                                currentClaim,
                                currentFence,
                                completion,
                                programOwnedRepositoryRoot),
                body);
    }

    public AgentResult awaitCleanup(
            InProcessWriterAgentSupervisor supervisor,
            CleanupBinding binding,
            ExecutionHandle handle,
            Duration timeout)
    {
        requireNonNull(supervisor, "supervisor is null");
        requireNonNull(binding, "binding is null");
        return supervisor.awaitAndFinalize(
                handle,
                timeout,
                cleanupFinalizerKey(binding.seal().cleanupId()));
    }

    /** Replays only a durably STOPPED fixer completion; no body is relaunched. */

    /** Replays only a durably STOPPED cleanup; no body is relaunched. */
    public AgentResult recoverExpiredStoppedCleanup(
            String operationId, long generation, Duration ttl)
    {
        FlowRuntime.StoppedWriterRecovery recovery =
                runtime.reviveExpiredStoppedWriter(
                        operationId, generation, ttl);
        Operation operation = runtime.operation(operationId).orElseThrow();
        if (!operation.ownerKind().equals("CI_CLEANUP")) {
            throw new IllegalArgumentException(
                    "stopped cleanup has an unknown owner");
        }
        Task task = runtime.task(operation.taskId()).orElseThrow();
        CiCleanupSeal seal = autofix.cleanupSealForSuccessor(
                operationId).orElseThrow(() ->
                        new IllegalStateException(
                                "stopped cleanup lost its seal"));
        AgentCompletion completion = new AgentCompletion(
                recovery.completion().terminalOutcome(),
                recovery.completion().finalContent(),
                recovery.completion().errorRef());
        return finalizeCleanup(
                seal.cleanupId(), recovery.run().runId(),
                recovery.claim(), recovery.fence(), completion,
                Path.of(task.repositoryRoot()));
    }

    AgentResult finalizeCleanup(
            String cleanupId,
            String runId,
            Claim claim,
            WriterFence fence,
            AgentCompletion completion,
            Path programOwnedRepositoryRoot)
    {
        requireText(cleanupId, "cleanupId");
        requireText(runId, "runId");
        requireNonNull(claim, "claim is null");
        requireNonNull(fence, "fence is null");
        requireNonNull(completion, "completion is null");
        requireNonNull(programOwnedRepositoryRoot,
                "programOwnedRepositoryRoot is null");
        CiCleanupSeal seal = autofix.cleanupSeal(cleanupId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown CI cleanup: " + cleanupId));
        CiRepairAttempt predecessor = autofix.repairAttempt(
                seal.repairAttemptId()).orElseThrow();
        Optional<CiCleanupCompletion> existing =
                autofix.cleanupCompletion(cleanupId);
        if (existing.isPresent()) {
            CiCleanupCompletion stored = existing.get();
            if (!Objects.equals(stored.runId(), runId)
                    || !Objects.equals(stored.resultRef(),
                            runtime.resultForRun(runId)
                                    .map(AgentResult::resultId)
                                    .orElse(null))) {
                throw new IllegalStateException(
                        "CI cleanup completion changed run identity");
            }
            if (stored.outcome() == CleanupOutcome.FIX_PREPARED
                    || stored.outcome() == CleanupOutcome.NO_HEAD_CHANGE) {
                CiCleanupFinalizationReceipt replay =
                        runtime.replayCiCleanupRun(
                                runId,
                                claim,
                                fence,
                                completion.terminalOutcome(),
                                completion.finalContent(),
                                completion.errorRef(),
                                cleanupId,
                                stored.outputHead(),
                                stored.outputChangeSetRevisionId());
                assertStoredCleanupCompletion(stored, replay);
                return replay.result().orElseThrow();
            }
            AgentResult replay = runtime.replayCiCleanupAttention(
                    runId,
                    claim,
                    fence,
                    completion.terminalOutcome(),
                    completion.finalContent(),
                    completion.errorRef(),
                    cleanupId);
            if (!stored.resultRef().equals(replay.resultId())) {
                throw new IllegalStateException(
                        "CI cleanup attention changed result identity");
            }
            return replay;
        }
        if (!seal.successorOperationId().equals(claim.operationId())
                || predecessor.state() != AttemptState.NON_CLEAN_HANDOFF) {
            throw new IllegalStateException(
                    "CI cleanup finalizer changed immutable identity");
        }
        runtime.assertWriterRunStopped(runId, claim);
        PreparedCiCleanupFinalState prepared =
                runtime.prepareCiCleanupFinalState(
                        claim,
                        fence,
                        programOwnedRepositoryRoot,
                        predecessor.inputChangeSetRevisionId());
        return requireNonNull(transactions.execute(ignored -> {
            CiCleanupFinalizationReceipt receipt =
                    runtime.finalizeCiCleanup(
                            runId,
                            claim,
                            fence,
                            completion.terminalOutcome(),
                            completion.finalContent(),
                            completion.errorRef(),
                            cleanupId,
                            prepared);
            CiCleanupCompletion stored =
                    autofix.storeCleanupCompletion(receipt);
            assertStoredCleanupCompletion(stored, receipt);
            return receipt.result().orElseThrow();
        }), "cleanup finalization transaction returned null");
    }

    /**
     * Mechanically prepares Git, then atomically adopts and settles the exact
     * clean CI attempt. Final prose remains opaque.
     */

    private void requireCleanupLaunchBinding(
            CleanupBinding binding, Claim claim)
    {
        CiCleanupSeal seal = autofix.cleanupSeal(
                binding.seal().cleanupId()).orElseThrow(() ->
                        new IllegalStateException(
                                "CI cleanup seal is not durable"));
        CiRepairAttempt predecessor = autofix.repairAttempt(
                binding.predecessor().attemptId()).orElseThrow();
        AgentRun run = runtime.runForOperation(claim.operationId())
                .orElseThrow(() -> new IllegalStateException(
                        "CI cleanup run is not durable"));
        if (!seal.equals(binding.seal())
                || !predecessor.equals(binding.predecessor())
                || predecessor.state() != AttemptState.NON_CLEAN_HANDOFF
                || !run.equals(binding.run())
                || !run.operationId().equals(seal.successorOperationId())
                || run.role() != AgentRole.CI_FIXER
                || (run.state() != RunState.QUEUED
                        && run.state() != RunState.RUNNING)
                || !run.headSha().equals(predecessor.inputLocalHead())
                || !binding.fence().operationId().equals(
                        seal.successorOperationId())
                || !binding.fence().headSha().equals(
                        predecessor.inputLocalHead())
                || !binding.fence().treeDigest().equals(seal.stateDigest())) {
            throw new IllegalStateException(
                    "CI cleanup binding changed before launch");
        }
        runtime.assertWriterFence(claim, binding.fence());
    }

    private static void assertStoredCleanupCompletion(
            CiCleanupCompletion stored,
            CiCleanupFinalizationReceipt receipt)
    {
        AgentResult result = receipt.result().orElse(null);
        if (!stored.cleanupId().equals(receipt.cleanupId())
                || !Objects.equals(
                        stored.runId(), result == null ? null : result.runId())
                || !Objects.equals(
                        stored.resultRef(),
                        result == null ? null : result.resultId())
                || !Objects.equals(
                        stored.outputHead(), receipt.outputHead().orElse(null))
                || !Objects.equals(
                        stored.outputChangeSetRevisionId(),
                        receipt.outputChangeSetRevisionId().orElse(null))) {
            throw new IllegalStateException(
                    "CI cleanup completion changed runtime receipt identity");
        }
    }

    private static NonCleanInspection toInspection(CiCleanupSeal seal)
    {
        return new NonCleanInspection(
                seal.actualHead(),
                seal.branchHead(),
                seal.attachmentState(),
                seal.kind(),
                seal.operations(),
                seal.stateDigest());
    }

    private static String cleanupFinalizerKey(String cleanupId)
    {
        return "CI_CLEANUP:" + cleanupId;
    }

    private static void requireText(String value, String name)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }
}
