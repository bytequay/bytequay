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
import com.bytequay.app.flow.ci.CiAutofixRecords.CiRound;
import com.bytequay.app.flow.ci.CiAutofixRecords.CleanupOutcome;
import com.bytequay.app.flow.ci.CiAutofixRecords.FinalizeHeadResult;
import com.bytequay.app.flow.ci.CiAutofixRecords.FinalizedRound;
import com.bytequay.app.flow.ci.CiAutofixRecords.GitHubCheckSelector;
import com.bytequay.app.flow.ci.CiAutofixRecords.NormalizedCheck;
import com.bytequay.app.flow.ci.CiAutofixRecords.QueuedRepair;
import com.bytequay.app.flow.ci.CiAutofixRecords.RequiredCiPolicyRevision;
import com.bytequay.app.flow.ci.CiAutofixRecords.RoundState;
import com.bytequay.app.flow.github.GitHubCiObservationProof;
import com.bytequay.app.flow.runtime.FlowRuntime;
import com.bytequay.app.flow.runtime.FlowRuntime.CiCleanupAdmissionBlockedException;
import com.bytequay.app.flow.runtime.FlowRuntime.CiCleanupFinalizationReceipt;
import com.bytequay.app.flow.runtime.FlowRuntime.CiCleanupWriterStart;
import com.bytequay.app.flow.runtime.FlowRuntime.CiObservationSubject;
import com.bytequay.app.flow.runtime.FlowRuntime.CleanupHandoff;
import com.bytequay.app.flow.runtime.FlowRuntime.PreparedCiCleanupAdmission;
import com.bytequay.app.flow.runtime.FlowRuntime.PreparedCiCleanupFinalState;
import com.bytequay.app.flow.runtime.FlowRuntime.PreparedNonCleanState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentResult;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRole;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRun;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ChangeSetRevision;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.CiFixOutcome;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.FinalRedRegistration;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Operation;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PendingKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PendingWork;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PullRequestSubject;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.RunState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Task;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TaskStatus;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.WriterFence;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector.FailureCode;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector.InspectionFailure;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector.NonCleanInspection;
import com.bytequay.app.flow.runtime.InProcessWriterAgentSupervisor;
import com.bytequay.app.flow.runtime.InProcessWriterAgentSupervisor.AgentCompletion;
import com.bytequay.app.flow.runtime.InProcessWriterAgentSupervisor.ExecutionHandle;
import com.bytequay.app.flow.runtime.InProcessWriterAgentSupervisor.WriterToolCapability;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

import static java.util.Objects.requireNonNull;

/**
 * The one concrete transaction joining CI evidence to runtime dispatch.
 *
 * <p>It joins exact-head enqueue/selection to one runtime-owned writer run and
 * joins the runtime's stopped-writer transaction to CI attempt finalization.
 * The runtime remains the sole owner of dispatch, sessions, and writer fences.
 */
public final class CiAutofixCoordinator
{
    private static final String CI_PROMPT_MANIFEST = "ci-fix-prompt:v1";
    private static final String CI_CAPABILITY_SET = "ci-fix-capabilities:v1";
    private static final String CI_CLEANUP_PROMPT_MANIFEST =
            "ci-cleanup-prompt:v1";
    private static final String CI_CLEANUP_CAPABILITY_SET =
            "ci-cleanup-capabilities:v1";

    private final TransactionTemplate transactions;
    private final CiAutofix autofix;
    private final FlowRuntime runtime;
    private final Clock clock;

    public CiAutofixCoordinator(
            DataSource dataSource, CiAutofix autofix, FlowRuntime runtime)
    {
        this(dataSource, autofix, runtime, Clock.systemUTC());
    }

    CiAutofixCoordinator(
            DataSource dataSource,
            CiAutofix autofix,
            FlowRuntime runtime,
            Clock clock)
    {
        this.transactions = new TransactionTemplate(
                new DataSourceTransactionManager(
                        requireNonNull(dataSource, "dataSource is null")));
        this.autofix = requireNonNull(autofix, "autofix is null");
        this.runtime = requireNonNull(runtime, "runtime is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public record RepairBinding(CiRepairAttempt attempt, AgentRun run)
    {
        public RepairBinding
        {
            requireNonNull(attempt, "attempt is null");
            requireNonNull(run, "run is null");
        }
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
    public static final class CiObservationActivation
    {
        private final Claim claim;
        private final CiObservationSubject subject;
        private final RequiredCiPolicyRevision policy;

        private CiObservationActivation(
                Claim claim,
                CiObservationSubject subject,
                RequiredCiPolicyRevision policy)
        {
            this.claim = requireNonNull(claim, "claim is null");
            this.subject = requireNonNull(subject, "subject is null");
            this.policy = requireNonNull(policy, "policy is null");
        }

        public Claim claim() { return claim; }
        public CiObservationSubject subject() { return subject; }
        public RequiredCiPolicyRevision policy() { return policy; }
    }

    /** Begins only one current receipt/head/policy-bound read-only poll. */
    public Optional<CiObservationActivation> beginCiObservation(Claim claim)
    {
        requireNonNull(claim, "claim is null");
        return requireNonNull(transactions.execute(ignored -> {
            Operation operation = runtime.assertCiObservationClaim(claim);
            CiObservationSubject subject = runtime.ciObservationSubject(claim);
            PullRequestSubject pr = runtime.pullRequest(subject.prId())
                    .orElseThrow(() -> new IllegalStateException(
                            "CI observation PR is missing"));
            Task task = runtime.task(subject.taskId()).orElseThrow(() ->
                    new IllegalStateException(
                            "CI observation Task is missing"));
            boolean staleSubject = operation.kind()
                            != OperationKind.OBSERVE_CI
                    || !pr.taskId().equals(subject.taskId())
                    || !pr.repositoryId().equals(subject.repositoryId())
                    || !pr.targetBaseRef().equals(subject.targetBaseRef())
                    || !pr.scopeKey().equals(subject.scopeKey())
                    || !Objects.equals(
                            pr.currentRemoteHead(), subject.proposedHead())
                    || !Objects.equals(pr.provider(), subject.provider())
                    || !Objects.equals(
                            pr.repositoryExternalId(),
                            subject.repositoryExternalId())
                    || !Objects.equals(
                            pr.repositoryOwner(), subject.repositoryOwner())
                    || !Objects.equals(
                            pr.repositoryName(), subject.repositoryName())
                    || !Objects.equals(
                            pr.headRepositoryExternalId(),
                            subject.headRepositoryExternalId())
                    || !Objects.equals(
                            pr.headRepositoryOwner(),
                            subject.headRepositoryOwner())
                    || !Objects.equals(
                            pr.headRepositoryName(),
                            subject.headRepositoryName())
                    || !Objects.equals(pr.prNumber(), subject.prNumber())
                    || !pr.branchName().equals(subject.branchName());
            if (isTerminal(task.status()) || staleSubject) {
                runtime.cancelCiObservation(
                        claim, "CI_OBSERVATION_STALE");
                return Optional.empty();
            }
            Optional<RequiredCiPolicyRevision> currentPolicy =
                    autofix.currentPolicy(pr.repositoryId(), pr.scopeKey());
            if (currentPolicy.isEmpty()
                    || currentPolicy.orElseThrow().resolution()
                            != CiAutofixRecords.PolicyResolution.RESOLVED
                    || !currentPolicy.orElseThrow().targetBaseRef().equals(
                            pr.targetBaseRef())
                    || currentPolicy.orElseThrow().requiredCheckSelectors().stream()
                            .anyMatch(selector -> {
                                try {
                                    GitHubCheckSelector.parse(selector);
                                    return false;
                                }
                                catch (IllegalArgumentException invalid) {
                                    return true;
                                }
                            })) {
                runtime.rearmCiObservation(
                        claim,
                        "CI_OBSERVATION_POLICY_UNAVAILABLE",
                        clock.instant().plus(Duration.ofMinutes(5)));
                return Optional.empty();
            }
            return Optional.of(new CiObservationActivation(
                    claim, subject, currentPolicy.orElseThrow()));
        }), "CI observation begin transaction returned null");
    }

    /** Accepts one provider-sealed exhaustive batch and rearms its watch. */
    public Optional<CiRound> acceptCiObservation(
            CiObservationActivation activation,
            GitHubCiObservationProof proof)
    {
        requireNonNull(activation, "activation is null");
        requireNonNull(proof, "proof is null");
        if (!proof.matchesActivation(activation)) {
            throw new IllegalArgumentException(
                    "CI observation proof belongs to another activation");
        }
        Claim claim = activation.claim();
        String replayPrefix = "CI_OBSERVATION:" + proof.batchDigest() + ":";
        Operation before = runtime.operation(claim.operationId())
                .orElseThrow(() -> new IllegalStateException(
                        "CI observation operation is missing"));
        if (before.state() == OperationState.READY
                && before.resultRef() != null
                && before.resultRef().startsWith(replayPrefix)) {
            runtime.assertCiObservationRearmReplay(
                    claim, before.resultRef());
            CiRound round = autofix.roundById(
                    before.resultRef().substring(replayPrefix.length()))
                    .orElseThrow(() -> new IllegalStateException(
                            "CI observation replay round is missing"));
            assertSourcedRound(round, activation);
            return Optional.of(round);
        }
        return requireNonNull(transactions.execute(ignored -> {
            runtime.assertCiObservationClaim(claim);
            CiObservationSubject currentSubject =
                    runtime.ciObservationSubject(claim);
            PullRequestSubject pr = runtime.pullRequest(
                    currentSubject.prId()).orElseThrow();
            Task task = runtime.task(currentSubject.taskId()).orElseThrow();
            if (isTerminal(task.status())
                    || !currentSubject.equals(activation.subject())
                    || !Objects.equals(
                            pr.currentRemoteHead(),
                            activation.subject().proposedHead())) {
                runtime.cancelCiObservation(
                        claim, "CI_OBSERVATION_STALE");
                return Optional.empty();
            }
            RequiredCiPolicyRevision currentPolicy = autofix.currentPolicy(
                    pr.repositoryId(), pr.scopeKey()).orElse(null);
            if (!Objects.equals(currentPolicy, activation.policy())
                    || currentPolicy == null
                    || !autofix.lockCurrentPolicy(currentPolicy)) {
                runtime.rearmCiObservation(
                        claim,
                        "CI_OBSERVATION_POLICY_CHANGED",
                        clock.instant().plus(Duration.ofMinutes(1)));
                return Optional.empty();
            }
            runtime.assertAndLockCiUpdatePr(pr);

            List<NormalizedCheck> checks = proof.checks();
            Map<String, byte[]> logs =
                    proof.failedLogsByProviderCheckId();
            Set<String> providerIds = new HashSet<>();
            Set<String> policySelectors = new HashSet<>(
                    currentPolicy.requiredCheckSelectors());
            for (NormalizedCheck check : checks) {
                if (!check.headSha().equals(currentSubject.proposedHead())
                        || !policySelectors.contains(check.selectorKey())
                        || !providerIds.add(check.providerCheckId())) {
                    throw new IllegalStateException(
                            "sealed CI batch contains conflicting checks");
                }
            }
            if (!providerIds.containsAll(logs.keySet())) {
                throw new IllegalStateException(
                        "sealed CI batch contains an unrelated log");
            }
            Map<String, String> observationIds = new HashMap<>();
            for (NormalizedCheck check : checks) {
                var observation = autofix.observeProviderCi(
                        claim.operationId(), currentSubject.receiptId(),
                        currentSubject.prId(), check);
                observationIds.put(
                        check.providerCheckId(), observation.observationId());
            }
            for (Map.Entry<String, byte[]> log : logs.entrySet()) {
                autofix.attachLog(
                        observationIds.get(log.getKey()),
                        log.getValue(), List.of());
            }
            FinalizeHeadResult finalized = autofix.finalizeProviderBatch(
                    claim.operationId(), currentSubject.receiptId(),
                    currentSubject.prId(), currentSubject.proposedHead(),
                    currentPolicy.policyRevisionId(),
                    checks.stream()
                            .map(check -> observationIds.get(
                                    check.providerCheckId()))
                            .toList());
            if (!(finalized instanceof FinalizedRound exact)) {
                throw new IllegalStateException(
                        "sealed CI batch did not finalize its current head");
            }
            CiRound round = exact.round();
            if (round.state() == RoundState.FINAL_RED) {
                round = autofix.queueCurrentFinalRed(round.roundId());
                runtime.registerFinalRed(
                        round.roundId(), round.taskId(), round.prId(),
                        round.remoteHead(), "ci-round:" + round.roundId());
            }
            String resultRef = replayPrefix + round.roundId();
            Duration pollDelay = round.state() == RoundState.COLLECTING
                    ? Duration.ofMinutes(1) : Duration.ofMinutes(5);
            runtime.rearmCiObservation(
                    claim, resultRef,
                    clock.instant().plus(pollDelay));
            return Optional.of(round);
        }), "CI observation acceptance transaction returned null");
    }

    private static void assertSourcedRound(
            CiRound round, CiObservationActivation activation)
    {
        if (!round.prId().equals(activation.subject().prId())
                || !round.remoteHead().equals(
                        activation.subject().proposedHead())
                || !round.policyRevisionId().equals(
                        activation.policy().policyRevisionId())
                || !Objects.equals(
                        round.sourceObservationOperationId(),
                        activation.claim().operationId())
                || !Objects.equals(
                        round.sourceReceiptId(),
                        activation.subject().receiptId())) {
            throw new IllegalStateException(
                    "CI observation replay round has conflicting authority");
        }
    }

    /** Atomically freezes failed logs and records one exact runtime cause. */
    public QueuedRepair enqueueRepair(String roundId)
    {
        requireText(roundId, "roundId");
        return requireNonNull(transactions.execute(ignored -> {
            CiRound round = autofix.roundById(roundId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown CI round: " + roundId));
            Task task = requireExactPublishedSubject(round);
            if (isTerminal(task.status())) {
                var refreshed = autofix.finalizeHeadSnapshot(
                        round.prId(), round.remoteHead());
                if (!(refreshed instanceof FinalizedRound finalized)
                        || (finalized.round().state() != RoundState.FINAL_RED
                        && finalized.round().state() != RoundState.QUEUED)) {
                    throw new IllegalStateException(
                            "Terminal Task has no current red CI evidence");
                }
                round = finalized.round();
                FinalRedRegistration registration = runtime.registerFinalRed(
                        round.roundId(),
                        round.taskId(),
                        round.prId(),
                        round.remoteHead(),
                        "ci-round:" + round.roundId());
                return new QueuedRepair(
                        round,
                        registration.inboxId(),
                        registration.reconciliationOperationId(),
                        registration.terminalReason());
            }
            if (!isParkable(task.status())) {
                throw new IllegalStateException(
                        "CI round Task cannot accept repair work");
            }
            CiRound queued = autofix.queueCurrentFinalRed(roundId);
            FinalRedRegistration registration = runtime.registerFinalRed(
                    queued.roundId(),
                    queued.taskId(),
                    queued.prId(),
                    queued.remoteHead(),
                    "ci-round:" + queued.roundId());
            return new QueuedRepair(
                    queued,
                    registration.inboxId(),
                    registration.reconciliationOperationId(),
                    registration.terminalReason());
        }), "enqueue transaction returned null");
    }

    /**
     * Rechecks provider evidence before delegating one selection to the
     * runtime. Newer red evidence is queued before the stale cause is consumed,
     * so a missed or duplicate provider delivery cannot strand repair work.
     */
    public Optional<Operation> selectNext(Claim reconciliationClaim)
    {
        requireNonNull(reconciliationClaim, "reconciliationClaim is null");
        return requireNonNull(transactions.execute(ignored -> {
            while (true) {
                Optional<PendingWork> candidate =
                        runtime.nextPendingForReconciliation(
                                reconciliationClaim);
                if (candidate.isEmpty()
                        || candidate.get().kind() != PendingKind.FINAL_RED) {
                    return runtime.selectNext(reconciliationClaim);
                }
                PendingWork pending = candidate.get();
                CiRound queued = autofix.roundById(pending.externalKey())
                        .orElseThrow(() -> new IllegalStateException(
                                "Runtime references an unknown CI round"));
                var refreshed = autofix.finalizeHeadSnapshot(
                        queued.prId(), queued.remoteHead());
                if (refreshed instanceof FinalizedRound finalized
                        && finalized.round().roundId().equals(queued.roundId())
                        && finalized.round().state() == RoundState.QUEUED) {
                    return runtime.selectNext(reconciliationClaim);
                }
                if (refreshed instanceof FinalizedRound finalized
                        && finalized.round().state() == RoundState.FINAL_RED
                        && exactNonterminalSubject(finalized.round())) {
                    CiRound successor = autofix.queueCurrentFinalRed(
                            finalized.round().roundId());
                    runtime.registerFinalRed(
                            successor.roundId(),
                            successor.taskId(),
                            successor.prId(),
                            successor.remoteHead(),
                            "ci-round:" + successor.roundId());
                }
                CiRound current = autofix.roundById(queued.roundId())
                        .orElseThrow();
                if (current.state() == RoundState.QUEUED) {
                    autofix.supersedeQueuedRound(current.roundId());
                }
                runtime.discardPendingFinalRed(
                        reconciliationClaim, pending.pendingId());
            }
        }), "selection transaction returned null");
    }

    /** Atomically binds one selected current CI round to its persistent run. */
    public RepairBinding beginRepair(
            String roundId, Claim claim, WriterFence fence)
    {
        requireText(roundId, "roundId");
        requireNonNull(claim, "claim is null");
        requireNonNull(fence, "fence is null");
        return requireNonNull(transactions.execute(ignored -> {
            Optional<CiRepairAttempt> existing =
                    autofix.repairAttemptForRound(roundId, 0);
            if (existing.isPresent()) {
                return redeliverRepairBinding(existing.get(), claim, fence);
            }
            CiRound round = requireCurrentRepairRound(
                    roundId, RoundState.QUEUED, RoundState.ACTIVE);
            Task task = requireExactPublishedSubject(round);
            Operation operation = requireCiOperation(round, claim, fence, task);
            ChangeSetRevision input = runtime.currentChangeSet(task.taskId())
                    .orElseThrow(() -> new IllegalStateException(
                            "CI repair Task has no inspected change set"));
            if (!input.headSha().equals(round.remoteHead())
                    || !input.changeSetRevisionId().equals(
                            task.currentChangeSetRevisionId())) {
                throw new IllegalStateException(
                        "CI repair input change set is not the remote head");
            }
            AgentRun run = runtime.startWriterAgent(
                    claim,
                    fence,
                    CI_PROMPT_MANIFEST,
                    CI_CAPABILITY_SET);
            if (!run.operationId().equals(operation.operationId())
                    || run.role() != AgentRole.CI_FIXER
                    || !run.headSha().equals(round.remoteHead())) {
                throw new IllegalStateException(
                        "CI repair run is not bound to the exact input head");
            }
            CiRepairAttempt attempt = autofix.bindRepairAttempt(
                    round,
                    operation.operationId(),
                    run.runId(),
                    task.currentHeadSha(),
                    input.changeSetRevisionId());
            return new RepairBinding(attempt, run);
        }), "repair begin transaction returned null");
    }

    /** Launches only with the attempt-bound CI finalizer route. */
    public ExecutionHandle launchRepair(
            InProcessWriterAgentSupervisor supervisor,
            RepairBinding binding,
            Claim claim,
            WriterFence fence,
            Path programOwnedRepositoryRoot,
            Function<WriterToolCapability, AgentCompletion> body)
    {
        requireNonNull(supervisor, "supervisor is null");
        requireNonNull(binding, "binding is null");
        requireNonNull(programOwnedRepositoryRoot,
                "programOwnedRepositoryRoot is null");
        requireNonNull(body, "body is null");
        requireLaunchBinding(binding, claim, fence);
        String finalizerKey = finalizerKey(binding.attempt().attemptId());
        return supervisor.launch(
                binding.run().runId(),
                claim,
                fence,
                finalizerKey,
                (runId, currentClaim, currentFence, completion) ->
                        finalizeRepairAttempt(
                                binding.attempt().attemptId(),
                                runId,
                                currentClaim,
                                currentFence,
                                completion,
                                programOwnedRepositoryRoot),
                body);
    }

    public AgentResult awaitRepair(
            InProcessWriterAgentSupervisor supervisor,
            RepairBinding binding,
            ExecutionHandle handle,
            Duration timeout)
    {
        requireNonNull(supervisor, "supervisor is null");
        requireNonNull(binding, "binding is null");
        return supervisor.awaitAndFinalize(
                handle, timeout, finalizerKey(binding.attempt().attemptId()));
    }

    /** Re-inspects and starts the sole sealed CI cleanup successor. */
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
    AgentResult finalizeRepairAttempt(
            String attemptId,
            String runId,
            Claim claim,
            WriterFence fence,
            AgentCompletion completion,
            Path programOwnedRepositoryRoot)
    {
        requireText(attemptId, "attemptId");
        requireText(runId, "runId");
        requireNonNull(claim, "claim is null");
        requireNonNull(fence, "fence is null");
        requireNonNull(completion, "completion is null");
        requireNonNull(programOwnedRepositoryRoot,
                "programOwnedRepositoryRoot is null");
        CiRepairAttempt attempt = autofix.repairAttempt(attemptId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown CI repair attempt: " + attemptId));
        assertAttemptIdentity(attempt, runId, claim, fence);
        if (attempt.state() == AttemptState.NON_CLEAN_HANDOFF) {
            CiCleanupSeal seal = autofix.cleanupSealForRepair(attemptId)
                    .orElseThrow(() -> new IllegalStateException(
                            "CI cleanup attempt has no durable seal"));
            AgentResult replay = runtime.replayStoppedCiCleanupHandoff(
                    runId,
                    claim,
                    fence,
                    completion.terminalOutcome(),
                    completion.finalContent(),
                    completion.errorRef(),
                    attemptId,
                    seal.cleanupId(),
                    attempt.inputChangeSetRevisionId(),
                    attempt.inputLocalHead(),
                    new NonCleanInspection(
                            seal.actualHead(),
                            seal.branchHead(),
                            seal.attachmentState(),
                            seal.kind(),
                            seal.operations(),
                            seal.stateDigest()),
                    seal.stateDigest(),
                    seal.successorOperationId());
            if (!attempt.resultRef().equals(replay.resultId())) {
                throw new IllegalStateException(
                        "CI cleanup attempt changed predecessor result");
            }
            return replay;
        }
        if (attempt.state() == AttemptState.FIX_PREPARED
                || attempt.state() == AttemptState.NO_HEAD_CHANGE) {
            CiFixOutcome outcome = toCiFixOutcome(attempt.state());
            return runtime.finishCiAgentRun(
                    runId,
                    claim,
                    fence,
                    completion.terminalOutcome(),
                    completion.finalContent(),
                    completion.errorRef(),
                    attemptId,
                    outcome,
                    attempt.outputLocalHead(),
                    attempt.outputChangeSetRevisionId());
        }
        runtime.assertWriterRunStopped(runId, claim);
        requireActiveAttemptSubject(attempt, runId, claim, fence, true);
        FlowRuntime.PreparedChangeSet prepared;
        try {
            prepared = runtime.prepareChangeSet(
                    claim,
                    fence,
                    programOwnedRepositoryRoot,
                    attempt.inputChangeSetRevisionId());
        }
        catch (InspectionFailure failure) {
            if (failure.code() == FailureCode.DIRTY
                    || failure.code() == FailureCode.GIT_OPERATION_IN_PROGRESS) {
                return handoffDirtyRepair(
                        attempt,
                        runId,
                        claim,
                        fence,
                        completion,
                        programOwnedRepositoryRoot);
            }
            throw failure;
        }
        return requireNonNull(transactions.execute(ignored -> {
            CiRepairAttempt current = autofix.repairAttempt(attemptId)
                    .orElseThrow();
            assertAttemptIdentity(current, runId, claim, fence);
            requireActiveAttemptSubject(current, runId, claim, fence, true);
            ChangeSetRevision output = runtime.adoptPreparedChangeSet(
                    claim, fence, prepared);
            CiFixOutcome outcome = output.headSha().equals(
                    current.inputLocalHead())
                    ? CiFixOutcome.NO_HEAD_CHANGE
                    : CiFixOutcome.FIX_PREPARED;
            requireActiveAttemptSubject(current, runId, claim, fence, false);
            AgentResult result = runtime.finishCiAgentRun(
                    runId,
                    claim,
                    fence,
                    completion.terminalOutcome(),
                    completion.finalContent(),
                    completion.errorRef(),
                    attemptId,
                    outcome,
                    output.headSha(),
                    output.changeSetRevisionId());
            autofix.completeRepairAttempt(
                    attemptId,
                    output.headSha(),
                    output.changeSetRevisionId(),
                    result.resultId(),
                    toAttemptState(outcome));
            return result;
        }), "repair finalization transaction returned null");
    }

    private AgentResult handoffDirtyRepair(
            CiRepairAttempt attempt,
            String runId,
            Claim claim,
            WriterFence fence,
            AgentCompletion completion,
            Path programOwnedRepositoryRoot)
    {
        PreparedNonCleanState prepared;
        try {
            prepared = runtime.prepareNonCleanState(
                    claim,
                    fence,
                    programOwnedRepositoryRoot,
                    attempt.inputChangeSetRevisionId());
        }
        catch (InspectionFailure failure) {
            if (blocksCleanupSeal(failure.code())) {
                blockUnsealableDirtyRepair(
                        attempt, runId, claim, fence);
            }
            throw failure;
        }
        return requireNonNull(transactions.execute(ignored -> {
            CiRepairAttempt current = autofix.repairAttempt(
                    attempt.attemptId()).orElseThrow();
            assertAttemptIdentity(current, runId, claim, fence);
            requireActiveAttemptSubject(current, runId, claim, fence, true);
            CleanupHandoff handoff = runtime.handoffStoppedCiRunToCleanup(
                    runId,
                    claim,
                    fence,
                    completion.terminalOutcome(),
                    completion.finalContent(),
                    completion.errorRef(),
                    attempt.attemptId(),
                    prepared);
            autofix.storeCleanupSeal(attempt.attemptId(), handoff);
            return handoff.predecessorResult();
        }), "dirty repair handoff transaction returned null");
    }

    private void blockUnsealableDirtyRepair(
            CiRepairAttempt attempt,
            String runId,
            Claim claim,
            WriterFence fence)
    {
        requireNonNull(transactions.execute(ignored -> {
            CiRepairAttempt current = autofix.repairAttempt(
                    attempt.attemptId()).orElseThrow();
            assertAttemptIdentity(current, runId, claim, fence);
            runtime.assertWriterRunStopped(runId, claim);
            requireActiveAttemptSubject(
                    current, runId, claim, fence, true);
            autofix.blockDirtyRepairAttempt(attempt.attemptId());
            return Boolean.TRUE;
        }), "dirty repair block transaction returned null");
    }

    static boolean blocksCleanupSeal(FailureCode code)
    {
        return code == FailureCode.UNTRUSTED_REPOSITORY_STATE
                || code == FailureCode.OUTPUT_LIMIT
                || code == FailureCode.NOT_WORKTREE
                || code == FailureCode.WRONG_REPOSITORY
                || code == FailureCode.DETACHED_HEAD
                || code == FailureCode.WRONG_BRANCH
                || code == FailureCode.BRANCH_HEAD_MISMATCH
                || code == FailureCode.BASE_NOT_FOUND
                || code == FailureCode.PREDECESSOR_NOT_FOUND
                || code == FailureCode.BASE_NOT_ANCESTOR
                || code == FailureCode.PREDECESSOR_NOT_ANCESTOR;
    }

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

    private RepairBinding redeliverRepairBinding(
            CiRepairAttempt attempt, Claim claim, WriterFence fence)
    {
        if (attempt.state() != AttemptState.ACTIVE) {
            throw new IllegalStateException(
                    "CI repair attempt is no longer active");
        }
        AgentRun run = runtime.runForOperation(claim.operationId())
                .orElseThrow(() -> new IllegalStateException(
                        "CI repair run is not durable"));
        assertAttemptIdentity(attempt, run.runId(), claim, fence);
        CiRound round = requireBoundActiveRound(attempt);
        Task task = requireExactPublishedSubject(round);
        requireCiOperation(round, claim, fence, task);
        if ((run.state() != RunState.QUEUED
                && run.state() != RunState.RUNNING)
                || !run.runId().equals(attempt.agentRunId())
                || !run.headSha().equals(attempt.inputLocalHead())
                || !task.currentHeadSha().equals(attempt.inputLocalHead())
                || !task.currentChangeSetRevisionId().equals(
                        attempt.inputChangeSetRevisionId())) {
            throw new IllegalStateException(
                    "CI repair redelivery changed bound identity");
        }
        return new RepairBinding(attempt, run);
    }

    private void requireLaunchBinding(
            RepairBinding binding, Claim claim, WriterFence fence)
    {
        CiRepairAttempt stored = autofix.repairAttempt(
                binding.attempt().attemptId()).orElseThrow(() ->
                        new IllegalStateException(
                                "CI repair attempt is not durable"));
        if (!stored.equals(binding.attempt())
                || stored.state() != AttemptState.ACTIVE
                || !stored.agentRunId().equals(binding.run().runId())) {
            throw new IllegalStateException(
                    "CI repair binding is not the current active attempt");
        }
        assertAttemptIdentity(stored, binding.run().runId(), claim, fence);
        CiRound round = requireBoundActiveRound(stored);
        Task task = requireExactPublishedSubject(round);
        AgentRun current = runtime.runForOperation(claim.operationId())
                .orElseThrow(() -> new IllegalStateException(
                        "CI repair run is not durable"));
        if (!current.equals(binding.run())
                || current.role() != AgentRole.CI_FIXER
                || (current.state() != RunState.QUEUED
                        && current.state() != RunState.RUNNING)
                || task.status() != TaskStatus.ACTIVE
                || !task.selectedWriterOperationId().equals(
                        claim.operationId())
                || !task.currentHeadSha().equals(stored.inputLocalHead())
                || !task.currentChangeSetRevisionId().equals(
                        stored.inputChangeSetRevisionId())) {
            throw new IllegalStateException(
                    "CI repair binding changed before launch");
        }
        runtime.assertWriterFence(claim, fence);
    }

    private static CiFixOutcome toCiFixOutcome(AttemptState state)
    {
        return switch (state) {
            case FIX_PREPARED -> CiFixOutcome.FIX_PREPARED;
            case NO_HEAD_CHANGE -> CiFixOutcome.NO_HEAD_CHANGE;
            default -> throw new IllegalArgumentException(
                    "CI attempt has no clean outcome: " + state);
        };
    }

    private static AttemptState toAttemptState(CiFixOutcome outcome)
    {
        return switch (outcome) {
            case FIX_PREPARED -> AttemptState.FIX_PREPARED;
            case NO_HEAD_CHANGE -> AttemptState.NO_HEAD_CHANGE;
        };
    }

    private void requireActiveAttemptSubject(
            CiRepairAttempt attempt,
            String runId,
            Claim claim,
            WriterFence fence,
            boolean requireInputLocalHead)
    {
        if (attempt.state() != AttemptState.ACTIVE) {
            throw new IllegalStateException("CI repair attempt is not active");
        }
        CiRound round = requireBoundActiveRound(attempt);
        Task task = runtime.task(round.taskId()).orElseThrow();
        PullRequestSubject pr = runtime.pullRequest(round.prId()).orElseThrow();
        AgentRun run = runtime.runForOperation(claim.operationId())
                .orElseThrow();
        if (!pr.published()
                || !pr.taskId().equals(task.taskId())
                || !pr.currentRemoteHead().equals(attempt.inputRemoteHead())
                || !round.remoteHead().equals(attempt.inputRemoteHead())
                || task.status() != TaskStatus.ACTIVE
                || !claim.operationId().equals(
                        task.selectedWriterOperationId())
                || !run.runId().equals(runId)
                || run.role() != AgentRole.CI_FIXER
                || run.state() != RunState.RUNNING
                || (requireInputLocalHead
                        && (!task.currentHeadSha().equals(
                                attempt.inputLocalHead())
                        || !task.currentChangeSetRevisionId().equals(
                                attempt.inputChangeSetRevisionId())))) {
            throw new IllegalStateException(
                    "CI repair subject changed before finalization");
        }
        runtime.assertWriterFence(claim, fence);
    }

    private CiRound requireBoundActiveRound(CiRepairAttempt attempt)
    {
        CiRound bound = autofix.roundById(attempt.roundId()).orElseThrow();
        autofix.finalizeHeadSnapshot(bound.prId(), bound.remoteHead());
        CiRound refreshed = autofix.roundById(attempt.roundId()).orElseThrow();
        if (refreshed.state() != RoundState.ACTIVE
                && refreshed.state() != RoundState.SUPERSEDED) {
            throw new IllegalStateException(
                    "CI repair bound round is not active or superseded");
        }
        return refreshed;
    }

    private CiRound requireCurrentRepairRound(
            String roundId, RoundState... allowedStates)
    {
        CiRound requested = autofix.roundById(roundId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown CI round: " + roundId));
        var refreshed = autofix.finalizeHeadSnapshot(
                requested.prId(), requested.remoteHead());
        if (!(refreshed instanceof FinalizedRound finalized)
                || !finalized.round().roundId().equals(roundId)
                || Arrays.stream(allowedStates).noneMatch(
                        state -> state == finalized.round().state())) {
            throw new IllegalStateException(
                    "CI repair round is no longer current");
        }
        return finalized.round();
    }

    private Operation requireCiOperation(
            CiRound round, Claim claim, WriterFence fence, Task task)
    {
        Operation operation = runtime.operation(claim.operationId())
                .orElseThrow(() -> new IllegalStateException(
                        "CI repair operation does not exist"));
        if (claim.kind() != OperationKind.RUN_CI_FIXER
                || fence.holderKind() != AgentRole.CI_FIXER
                || !operation.operationId().equals(
                        task.selectedWriterOperationId())
                || operation.kind() != OperationKind.RUN_CI_FIXER
                || !operation.ownerKind().equals("CI_ROUND")
                || !operation.ownerId().equals(round.roundId())) {
            throw new IllegalStateException(
                    "CI repair operation is not the selected round owner");
        }
        runtime.assertWriterFence(claim, fence);
        return operation;
    }

    private static void assertAttemptIdentity(
            CiRepairAttempt attempt,
            String runId,
            Claim claim,
            WriterFence fence)
    {
        if (!attempt.operationId().equals(claim.operationId())
                || !attempt.agentRunId().equals(runId)
                || !fence.operationId().equals(claim.operationId())
                || fence.holderKind() != AgentRole.CI_FIXER) {
            throw new IllegalStateException(
                    "CI repair finalizer does not own this attempt");
        }
    }

    private static String finalizerKey(String attemptId)
    {
        return "CI_REPAIR:" + attemptId;
    }

    private Task requireExactPublishedSubject(CiRound round)
    {
        Task task = runtime.task(round.taskId())
                .orElseThrow(() -> new IllegalStateException(
                        "CI round Task does not exist"));
        PullRequestSubject pr = runtime.pullRequest(round.prId())
                .orElseThrow(() -> new IllegalStateException(
                        "CI round PR does not exist"));
        if (!pr.published()
                || !pr.taskId().equals(task.taskId())
                || !round.prId().equals(task.prId())
                || !round.remoteHead().equals(task.currentHeadSha())
                || !round.remoteHead().equals(pr.currentRemoteHead())) {
            throw new IllegalStateException(
                    "CI round is not the exact published Task/PR head");
        }
        return task;
    }

    private boolean exactNonterminalSubject(CiRound round)
    {
        Task task = requireExactPublishedSubject(round);
        return isParkable(task.status());
    }

    private static boolean isParkable(TaskStatus status)
    {
        return status == TaskStatus.ACTIVE
                || status == TaskStatus.WAITING_USER
                || status == TaskStatus.NEEDS_ATTENTION;
    }

    private static boolean isTerminal(TaskStatus status)
    {
        return status == TaskStatus.COMPLETED
                || status == TaskStatus.CANCELED;
    }

    private static void requireText(String value, String name)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }
}
