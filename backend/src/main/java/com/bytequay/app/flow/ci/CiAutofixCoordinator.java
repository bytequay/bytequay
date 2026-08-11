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
import com.bytequay.app.flow.ci.CiAutofixRecords.CiCheckObservation;
import com.bytequay.app.flow.ci.CiAutofixRecords.CiCleanupCompletion;
import com.bytequay.app.flow.ci.CiAutofixRecords.CiCleanupSeal;
import com.bytequay.app.flow.ci.CiAutofixRecords.CiLearningCompletion;
import com.bytequay.app.flow.ci.CiAutofixRecords.CiLearningSubject;
import com.bytequay.app.flow.ci.CiAutofixRecords.CiLesson;
import com.bytequay.app.flow.ci.CiAutofixRecords.CiRepairAttempt;
import com.bytequay.app.flow.ci.CiAutofixRecords.CiRound;
import com.bytequay.app.flow.ci.CiAutofixRecords.CleanupOutcome;
import com.bytequay.app.flow.ci.CiAutofixRecords.FinalizeHeadResult;
import com.bytequay.app.flow.ci.CiAutofixRecords.FinalizedRound;
import com.bytequay.app.flow.ci.CiAutofixRecords.GitHubCheckSelector;
import com.bytequay.app.flow.ci.CiAutofixRecords.LearningCompletionState;
import com.bytequay.app.flow.ci.CiAutofixRecords.NormalizedCheck;
import com.bytequay.app.flow.ci.CiAutofixRecords.QueuedRepair;
import com.bytequay.app.flow.ci.CiAutofixRecords.RequiredCiPolicyRevision;
import com.bytequay.app.flow.ci.CiAutofixRecords.RoundState;
import com.bytequay.app.flow.gate.UserGates;
import com.bytequay.app.flow.gate.UserGates.CiUpdateLearningPublication;
import com.bytequay.app.flow.github.GitHubCiObservationProof;
import com.bytequay.app.flow.github.GitHubEffects;
import com.bytequay.app.flow.runtime.FlowRuntime;
import com.bytequay.app.flow.runtime.FlowRuntime.CiCleanupAdmissionBlockedException;
import com.bytequay.app.flow.runtime.FlowRuntime.CiCleanupFinalizationReceipt;
import com.bytequay.app.flow.runtime.FlowRuntime.CiCleanupWriterStart;
import com.bytequay.app.flow.runtime.FlowRuntime.CiLearningStart;
import com.bytequay.app.flow.runtime.FlowRuntime.CiObservationSubject;
import com.bytequay.app.flow.runtime.FlowRuntime.CleanupHandoff;
import com.bytequay.app.flow.runtime.FlowRuntime.PreparedCiCleanupAdmission;
import com.bytequay.app.flow.runtime.FlowRuntime.PreparedCiCleanupFinalState;
import com.bytequay.app.flow.runtime.FlowRuntime.PreparedNonCleanState;
import com.bytequay.app.flow.runtime.FlowRuntime.StoppedCiLearningResult;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentResult;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRole;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRun;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ChangeSetRevision;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.CiFixOutcome;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ExpiredClaim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.FinalRedRegistration;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Operation;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PendingKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PendingWork;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ProcessAttemptState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PullRequestSubject;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.RunState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Task;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TaskStatus;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.WriterFence;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector.FailureCode;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector.InspectionFailure;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector.NonCleanInspection;
import com.bytequay.app.flow.runtime.InProcessCiLearningAgentSupervisor;
import com.bytequay.app.flow.runtime.InProcessCiLearningAgentSupervisor.LearningOwner;
import com.bytequay.app.flow.runtime.InProcessWriterAgentSupervisor;
import com.bytequay.app.flow.runtime.InProcessWriterAgentSupervisor.AgentCompletion;
import com.bytequay.app.flow.runtime.InProcessWriterAgentSupervisor.ExecutionHandle;
import com.bytequay.app.flow.runtime.InProcessWriterAgentSupervisor.WriterToolCapability;
import com.bytequay.app.flow.runtime.LocalChecks;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
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
        implements LearningOwner
{
    private static final String CI_PROMPT_MANIFEST = "ci-fix-prompt:v1";
    private static final String CI_CAPABILITY_SET = "ci-fix-capabilities:v1";
    private static final String CI_CLEANUP_PROMPT_MANIFEST =
            "ci-cleanup-prompt:v1";
    private static final String CI_CLEANUP_CAPABILITY_SET =
            "ci-cleanup-capabilities:v1";
    private static final String CI_LEARNING_PROMPT_MANIFEST =
            "ci-learning-prompt:v1";
    private static final String CI_LEARNING_CAPABILITY_SET =
            "ci-learning-capabilities:v1";

    private final TransactionTemplate transactions;
    private final JdbcTemplate jdbc;
    private final CiAutofix autofix;
    private final FlowRuntime runtime;
    private final UserGates userGates;
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
        this.jdbc = new JdbcTemplate(dataSource);
        this.autofix = requireNonNull(autofix, "autofix is null");
        this.runtime = requireNonNull(runtime, "runtime is null");
        this.clock = requireNonNull(clock, "clock is null");
        GitHubEffects githubEffects = new GitHubEffects(dataSource, runtime);
        this.userGates = new UserGates(
                dataSource, runtime,
                new LocalChecks(dataSource, runtime, clock), autofix,
                githubEffects, clock);
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
            assertGreenLearningReplay(round);
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
            else if (round.state() == RoundState.GREEN) {
                reserveCiLearningIfEligible(round);
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

    private void assertGreenLearningReplay(CiRound round)
    {
        if (round.state() != RoundState.GREEN) {
            return;
        }
        Optional<CiLearningSubject> stored = learningSubjectForReceipt(
                round.sourceReceiptId());
        if (stored.isPresent()) {
            assertLearningSubjectIntegrity(stored.orElseThrow());
            return;
        }
        if (!round.checkObservationIds().isEmpty()
                && exactLearningCandidate(round)) {
            throw new IllegalStateException(
                    "eligible GREEN replay is missing its CI learner");
        }
    }

    private boolean exactLearningCandidate(CiRound green)
    {
        LearningOrigin origin = learningOrigin(green.sourceReceiptId())
                .orElse(null);
        if (origin == null
                || !origin.taskId().equals(green.taskId())
                || !origin.prId().equals(green.prId())
                || !origin.publishedHead().equals(green.remoteHead())) {
            return false;
        }
        CiRepairAttempt repair = autofix.repairAttempt(
                origin.repairAttemptId()).orElse(null);
        CiRound red = autofix.roundById(origin.redRoundId()).orElse(null);
        if (repair == null || red == null || red.failedLogRefs().isEmpty()
                || !repair.roundId().equals(red.roundId())
                || !Objects.equals(repair.resultRef(),
                        origin.repairResultId())) {
            return false;
        }
        if (origin.cleanupId() == null) {
            return repair.state() == AttemptState.FIX_PREPARED
                    && origin.publishedHead().equals(
                            repair.outputLocalHead())
                    && origin.gateChangeSetRevisionId().equals(
                            repair.outputChangeSetRevisionId());
        }
        CiCleanupCompletion completion = autofix.cleanupCompletion(
                origin.cleanupId()).orElse(null);
        return repair.state() == AttemptState.NON_CLEAN_HANDOFF
                && completion != null
                && completion.outcome() == CleanupOutcome.FIX_PREPARED
                && Objects.equals(completion.resultRef(),
                        origin.cleanupResultId())
                && origin.publishedHead().equals(completion.outputHead())
                && origin.gateChangeSetRevisionId().equals(
                        completion.outputChangeSetRevisionId());
    }

    /**
     * Reserves one optional learner only for an exact published repair whose
     * later sourced GREEN policy has real check evidence. Empty-policy GREEN
     * is valid, but teaches nothing and deliberately creates no operation.
     */
    private Optional<CiLearningSubject> reserveCiLearningIfEligible(
            CiRound green)
    {
        if (green.state() != RoundState.GREEN
                || green.sourceObservationOperationId() == null
                || green.sourceReceiptId() == null
                || green.checkObservationIds().isEmpty()) {
            return Optional.empty();
        }
        Optional<CiLearningSubject> prior = learningSubjectForReceipt(
                green.sourceReceiptId());
        if (prior.isPresent()) {
            assertLearningSubjectIntegrity(prior.orElseThrow());
            return prior;
        }
        LearningOrigin origin = learningOrigin(green.sourceReceiptId())
                .orElse(null);
        if (origin == null
                || !origin.taskId().equals(green.taskId())
                || !origin.prId().equals(green.prId())
                || !origin.publishedHead().equals(green.remoteHead())
                || !origin.receiptId().equals(green.sourceReceiptId())
                || !origin.gateCiRoundId().equals(origin.redRoundId())
                || !origin.gateRepairAttemptId().equals(
                        origin.repairAttemptId())
                || !origin.gateRepairResultId().equals(
                        origin.repairResultId())
                || !Objects.equals(
                        origin.gateCleanupId(), origin.cleanupId())
                || !Objects.equals(
                        origin.gateCleanupResultId(),
                        origin.cleanupResultId())) {
            return Optional.empty();
        }
        CiRound red = autofix.roundById(origin.redRoundId()).orElse(null);
        CiRepairAttempt repair = autofix.repairAttempt(
                origin.repairAttemptId()).orElse(null);
        if (red == null || repair == null
                || !repair.roundId().equals(red.roundId())
                || !Objects.equals(repair.resultRef(),
                        origin.repairResultId())
                || red.failedLogRefs().isEmpty()) {
            return Optional.empty();
        }
        String outputHead;
        String outputChangeSet;
        if (origin.cleanupId() == null) {
            if (repair.state() != AttemptState.FIX_PREPARED) {
                return Optional.empty();
            }
            outputHead = repair.outputLocalHead();
            outputChangeSet = repair.outputChangeSetRevisionId();
        }
        else {
            CiCleanupSeal seal = autofix.cleanupSeal(
                    origin.cleanupId()).orElse(null);
            CiCleanupCompletion completion = autofix.cleanupCompletion(
                    origin.cleanupId()).orElse(null);
            if (seal == null || completion == null
                    || !seal.repairAttemptId().equals(repair.attemptId())
                    || repair.state() != AttemptState.NON_CLEAN_HANDOFF
                    || completion.outcome() != CleanupOutcome.FIX_PREPARED
                    || !Objects.equals(completion.resultRef(),
                            origin.cleanupResultId())) {
                return Optional.empty();
            }
            outputHead = completion.outputHead();
            outputChangeSet = completion.outputChangeSetRevisionId();
        }
        if (!origin.publishedHead().equals(outputHead)
                || !origin.gateChangeSetRevisionId().equals(outputChangeSet)
                || origin.expectedRemoteHead().equals(origin.publishedHead())) {
            return Optional.empty();
        }
        ResultView repairResult = resultView(
                origin.repairResultId()).orElse(null);
        ResultView cleanupResult = origin.cleanupResultId() == null
                ? null : resultView(origin.cleanupResultId()).orElse(null);
        if (repairResult == null
                || !repairResult.runId().equals(repair.agentRunId())
                || origin.cleanupId() != null && (cleanupResult == null
                    || !cleanupResult.runId().equals(autofix.cleanupCompletion(
                            origin.cleanupId()).orElseThrow().runId()))) {
            return Optional.empty();
        }
        String repairResultDigest = resultDigest(repairResult);
        String cleanupResultDigest = cleanupResult == null
                ? null : resultDigest(cleanupResult);
        Task task = runtime.task(green.taskId()).orElse(null);
        PullRequestSubject pr = runtime.pullRequest(green.prId()).orElse(null);
        if (task == null || pr == null
                || !task.repositoryId().equals(origin.repositoryId())
                || !pr.taskId().equals(task.taskId())
                || !pr.repositoryId().equals(task.repositoryId())
                || !Objects.equals(pr.currentRemoteHead(),
                        origin.publishedHead())) {
            return Optional.empty();
        }

        String operationId = stableId(
                "ci-learning-operation", origin.receiptId());
        String subjectId = stableId(
                "ci-learning-subject", origin.receiptId());
        List<String> greenDigests = green.checkObservationIds().stream()
                .map(id -> observationDigest(
                        autofix.observation(id).orElseThrow()))
                .toList();
        List<String> logDigests = red.failedLogRefs().stream()
                .map(ref -> autofix.logEvidence(ref).orElseThrow()
                        .contentDigest())
                .toList();
        String digest = digest(
                "ci-learning-subject:v1",
                subjectId, operationId, green.taskId(), green.prId(),
                origin.repositoryId(), origin.receiptId(),
                origin.receiptDigest(), origin.publicationOperationId(),
                origin.headRepositoryExternalId(),
                origin.headRepositoryOwner(), origin.headRepositoryName(),
                origin.branchRef(), origin.expectedRemoteHead(),
                origin.planId(), origin.planDigest(),
                origin.authorizationId(), origin.gateId(),
                Long.toString(origin.gateRevision()),
                origin.gateSubjectDigest(), origin.gateActionDigest(),
                origin.publicationPolicyRevisionId(), origin.publishedHead(),
                green.roundId(), green.policyRevisionId(),
                Long.toString(green.evidenceRevision()),
                green.sourceObservationOperationId(),
                digestList(green.checkObservationIds()),
                digestList(greenDigests), red.roundId(),
                repair.attemptId(), origin.repairResultId(),
                repairResultDigest,
                nullToEmpty(origin.cleanupId()),
                nullToEmpty(origin.cleanupResultId()),
                nullToEmpty(cleanupResultDigest), outputChangeSet,
                origin.gateDiffDigest(),
                digestList(red.failedLogRefs()), digestList(logDigests));
        CiLearningSubject subject = new CiLearningSubject(
                subjectId, operationId, green.taskId(), green.prId(),
                origin.repositoryId(), origin.receiptId(),
                origin.receiptDigest(), origin.publicationOperationId(),
                origin.headRepositoryExternalId(),
                origin.headRepositoryOwner(), origin.headRepositoryName(),
                origin.branchRef(), origin.expectedRemoteHead(),
                origin.planId(), origin.planDigest(),
                origin.authorizationId(), origin.gateId(),
                origin.gateRevision(), origin.gateSubjectDigest(),
                origin.gateActionDigest(),
                origin.publicationPolicyRevisionId(), origin.publishedHead(),
                green.roundId(), green.policyRevisionId(),
                green.evidenceRevision(),
                green.sourceObservationOperationId(),
                green.checkObservationIds(), greenDigests, red.roundId(),
                repair.attemptId(), origin.repairResultId(),
                repairResultDigest, origin.cleanupId(),
                origin.cleanupResultId(), cleanupResultDigest, outputChangeSet,
                origin.gateDiffDigest(), red.failedLogRefs(), logDigests,
                digest, green.createdAt());
        runtime.ensureCiLearningOperation(
                operationId, origin.receiptId(), green.taskId(), subjectId,
                digest, subject.createdAt());
        insertLearningSubject(subject);
        assertLearningSubjectIntegrity(subject);
        return Optional.of(subject);
    }

    private Optional<LearningOrigin> learningOrigin(String receiptId)
    {
        return jdbc.query(
                """
                SELECT r.receipt_id, r.receipt_digest, r.operation_id,
                       r.head_repository_external_id,
                       r.head_repository_owner, r.head_repository_name,
                       r.branch_ref,
                       r.expected_remote_head, r.proposed_head,
                       p.plan_id, p.plan_digest,
                       p.required_ci_policy_revision_id,
                       a.authorization_id, a.gate_id, a.gate_revision,
                       a.subject_digest AS gate_subject_digest,
                       a.action_digest AS gate_action_digest,
                       s.task_id, s.pr_id, s.repository_id,
                       s.change_set_revision_id, s.diff_digest,
                       s.ci_round_id AS gate_ci_round_id,
                       s.repair_attempt_id AS gate_repair_attempt_id,
                       s.repair_result_id AS gate_repair_result_id,
                       s.cleanup_id AS gate_cleanup_id,
                       s.cleanup_result_id AS gate_cleanup_result_id,
                       ra.round_id AS red_round_id,
                       ra.attempt_id AS repair_attempt_id,
                       ra.result_ref AS repair_result_id,
                       cc.cleanup_id, cc.result_ref AS cleanup_result_id
                FROM flow_github_external_effect_receipt r
                JOIN flow_github_external_effect_plan p
                  ON p.plan_id = r.plan_id
                 AND p.operation_id = r.operation_id
                JOIN flow_user_gate_authorization a
                  ON a.authorization_id = p.authorization_id
                 AND a.operation_id = p.operation_id
                 AND a.effect_plan_ref = p.plan_id
                JOIN flow_user_gate_revision gr
                  ON gr.gate_id = a.gate_id
                 AND gr.revision = a.gate_revision
                 AND gr.subject_digest = a.subject_digest
                 AND gr.action_digest = a.action_digest
                JOIN flow_user_gate_subject s
                  ON s.subject_id = gr.subject_manifest_ref
                 AND s.subject_digest = gr.subject_digest
                JOIN flow_ci_repair_attempt ra
                  ON ra.attempt_id = s.repair_attempt_id
                 AND ra.round_id = s.ci_round_id
                 AND ra.result_ref = s.repair_result_id
                LEFT JOIN flow_ci_cleanup_completion cc
                  ON cc.cleanup_id = s.cleanup_id
                 AND cc.result_ref = s.cleanup_result_id
                JOIN flow_runtime_operation publish
                  ON publish.operation_id = r.operation_id
                 AND publish.kind = 'PUBLISH'
                 AND publish.state = 'SUCCEEDED'
                 AND publish.result_ref = r.receipt_id
                WHERE r.receipt_id = ?
                  AND EXISTS (
                      SELECT 1 FROM flow_user_gate_transition t
                      WHERE t.gate_id = a.gate_id
                        AND t.gate_revision = a.gate_revision
                        AND t.to_state = 'CONSUMED'
                        AND t.reason_code = 'EFFECT_APPLIED'
                        AND t.actor_type = 'PROGRAM'
                        AND t.actor_id = r.operation_id
                        AND t.detail_ref = r.receipt_id
                        AND t.sequence = (
                            SELECT MAX(t2.sequence)
                            FROM flow_user_gate_transition t2
                            WHERE t2.gate_id = a.gate_id
                              AND t2.gate_revision = a.gate_revision
                        )
                  )
                """,
                (result, row) -> new LearningOrigin(
                        result.getString("receipt_id"),
                        result.getString("receipt_digest"),
                        result.getString("operation_id"),
                        result.getString("head_repository_external_id"),
                        result.getString("head_repository_owner"),
                        result.getString("head_repository_name"),
                        result.getString("branch_ref"),
                        result.getString("expected_remote_head"),
                        result.getString("proposed_head"),
                        result.getString("plan_id"),
                        result.getString("plan_digest"),
                        result.getString("required_ci_policy_revision_id"),
                        result.getString("authorization_id"),
                        result.getString("gate_id"),
                        result.getLong("gate_revision"),
                        result.getString("gate_subject_digest"),
                        result.getString("gate_action_digest"),
                        result.getString("task_id"),
                        result.getString("pr_id"),
                        result.getString("repository_id"),
                        result.getString("change_set_revision_id"),
                        result.getString("diff_digest"),
                        result.getString("gate_ci_round_id"),
                        result.getString("gate_repair_attempt_id"),
                        result.getString("gate_repair_result_id"),
                        result.getString("gate_cleanup_id"),
                        result.getString("gate_cleanup_result_id"),
                        result.getString("red_round_id"),
                        result.getString("repair_attempt_id"),
                        result.getString("repair_result_id"),
                        result.getString("cleanup_id"),
                        result.getString("cleanup_result_id")),
                receiptId).stream().findFirst();
    }

    private void insertLearningSubject(CiLearningSubject subject)
    {
        int inserted = jdbc.update(
                """
                INSERT OR IGNORE INTO flow_ci_learning_subject (
                    subject_id, operation_id, task_id, pr_id, repository_id,
                    receipt_id, receipt_digest, plan_id, plan_digest,
                    publication_operation_id, head_repository_external_id,
                    head_repository_owner, head_repository_name, branch_ref,
                    expected_remote_head,
                    authorization_id, gate_id, gate_revision,
                    gate_subject_digest, gate_action_digest,
                    publication_policy_revision_id, published_head,
                    green_round_id, green_policy_revision_id,
                    green_evidence_revision,
                    green_observation_operation_id, red_round_id,
                    repair_attempt_id, repair_result_id,
                    repair_result_digest, cleanup_id, cleanup_result_id,
                    cleanup_result_digest, output_change_set_revision_id,
                    output_diff_digest, subject_digest, created_at
                ) VALUES (
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                    ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                )
                """,
                subject.subjectId(), subject.operationId(), subject.taskId(),
                subject.prId(), subject.repositoryId(), subject.receiptId(),
                subject.receiptDigest(), subject.planId(),
                subject.planDigest(), subject.publicationOperationId(),
                subject.headRepositoryExternalId(),
                subject.headRepositoryOwner(), subject.headRepositoryName(),
                subject.branchRef(), subject.expectedRemoteHead(),
                subject.authorizationId(),
                subject.gateId(), subject.gateRevision(),
                subject.gateSubjectDigest(), subject.gateActionDigest(),
                subject.publicationPolicyRevisionId(), subject.publishedHead(),
                subject.greenRoundId(), subject.greenPolicyRevisionId(),
                subject.greenEvidenceRevision(),
                subject.greenObservationOperationId(), subject.redRoundId(),
                subject.repairAttemptId(), subject.repairResultId(),
                subject.repairResultDigest(), subject.cleanupId(),
                subject.cleanupResultId(), subject.cleanupResultDigest(),
                subject.outputChangeSetRevisionId(),
                subject.outputDiffDigest(), subject.subjectDigest(),
                subject.createdAt().toEpochMilli());
        if (inserted == 0) {
            CiLearningSubject replay = learningSubject(
                    subject.subjectId()).orElseThrow(() ->
                            new IllegalStateException(
                                    "CI learning subject insert conflicted"));
            if (!replay.equals(subject)) {
                throw new IllegalStateException(
                        "CI learning subject replay changed identity");
            }
            return;
        }
        for (int index = 0; index < subject.greenObservationIds().size();
                index++) {
            jdbc.update(
                    "INSERT INTO flow_ci_learning_green_observation "
                            + "(subject_id, ordinal, observation_id, "
                            + "evidence_digest) VALUES (?, ?, ?, ?)",
                    subject.subjectId(), index,
                    subject.greenObservationIds().get(index),
                    subject.greenObservationDigests().get(index));
        }
        for (int index = 0; index < subject.failedLogRefs().size(); index++) {
            jdbc.update(
                    "INSERT INTO flow_ci_learning_failed_log "
                            + "(subject_id, ordinal, log_ref, content_digest) "
                            + "VALUES (?, ?, ?, ?)",
                    subject.subjectId(), index,
                    subject.failedLogRefs().get(index),
                    subject.failedLogDigests().get(index));
        }
    }

    public Optional<CiLearningSubject> learningSubject(String subjectId)
    {
        requireText(subjectId, "subjectId");
        return jdbc.query(
                "SELECT * FROM flow_ci_learning_subject WHERE subject_id = ?",
                (result, row) -> new CiLearningSubject(
                        result.getString("subject_id"),
                        result.getString("operation_id"),
                        result.getString("task_id"),
                        result.getString("pr_id"),
                        result.getString("repository_id"),
                        result.getString("receipt_id"),
                        result.getString("receipt_digest"),
                        result.getString("publication_operation_id"),
                        result.getString("head_repository_external_id"),
                        result.getString("head_repository_owner"),
                        result.getString("head_repository_name"),
                        result.getString("branch_ref"),
                        result.getString("expected_remote_head"),
                        result.getString("plan_id"),
                        result.getString("plan_digest"),
                        result.getString("authorization_id"),
                        result.getString("gate_id"),
                        result.getLong("gate_revision"),
                        result.getString("gate_subject_digest"),
                        result.getString("gate_action_digest"),
                        result.getString("publication_policy_revision_id"),
                        result.getString("published_head"),
                        result.getString("green_round_id"),
                        result.getString("green_policy_revision_id"),
                        result.getLong("green_evidence_revision"),
                        result.getString("green_observation_operation_id"),
                        learningList(
                                "flow_ci_learning_green_observation",
                                "observation_id", subjectId),
                        learningList(
                                "flow_ci_learning_green_observation",
                                "evidence_digest", subjectId),
                        result.getString("red_round_id"),
                        result.getString("repair_attempt_id"),
                        result.getString("repair_result_id"),
                        result.getString("repair_result_digest"),
                        result.getString("cleanup_id"),
                        result.getString("cleanup_result_id"),
                        result.getString("cleanup_result_digest"),
                        result.getString("output_change_set_revision_id"),
                        result.getString("output_diff_digest"),
                        learningList(
                                "flow_ci_learning_failed_log", "log_ref",
                                subjectId),
                        learningList(
                                "flow_ci_learning_failed_log",
                                "content_digest", subjectId),
                        result.getString("subject_digest"),
                        Instant.ofEpochMilli(
                                result.getLong("created_at"))),
                subjectId).stream().findFirst();
    }

    public Optional<CiLearningSubject> learningSubjectForReceipt(
            String receiptId)
    {
        requireText(receiptId, "receiptId");
        return jdbc.queryForList(
                        "SELECT subject_id FROM flow_ci_learning_subject "
                                + "WHERE receipt_id = ?",
                        String.class, receiptId).stream()
                .findFirst()
                .flatMap(this::learningSubject);
    }

    private void assertLearningSubjectIntegrity(CiLearningSubject subject)
    {
        String expectedOperation = stableId(
                "ci-learning-operation", subject.receiptId());
        String expectedSubject = stableId(
                "ci-learning-subject", subject.receiptId());
        String expectedDigest = digest(
                "ci-learning-subject:v1",
                subject.subjectId(), subject.operationId(), subject.taskId(),
                subject.prId(), subject.repositoryId(), subject.receiptId(),
                subject.receiptDigest(), subject.publicationOperationId(),
                subject.headRepositoryExternalId(),
                subject.headRepositoryOwner(), subject.headRepositoryName(),
                subject.branchRef(), subject.expectedRemoteHead(),
                subject.planId(),
                subject.planDigest(), subject.authorizationId(),
                subject.gateId(), Long.toString(subject.gateRevision()),
                subject.gateSubjectDigest(), subject.gateActionDigest(),
                subject.publicationPolicyRevisionId(), subject.publishedHead(),
                subject.greenRoundId(), subject.greenPolicyRevisionId(),
                Long.toString(subject.greenEvidenceRevision()),
                subject.greenObservationOperationId(),
                digestList(subject.greenObservationIds()),
                digestList(subject.greenObservationDigests()),
                subject.redRoundId(), subject.repairAttemptId(),
                subject.repairResultId(), subject.repairResultDigest(),
                nullToEmpty(subject.cleanupId()),
                nullToEmpty(subject.cleanupResultId()),
                nullToEmpty(subject.cleanupResultDigest()),
                subject.outputChangeSetRevisionId(),
                subject.outputDiffDigest(),
                digestList(subject.failedLogRefs()),
                digestList(subject.failedLogDigests()));
        Operation operation = runtime.operation(subject.operationId())
                .orElseThrow(() -> new IllegalStateException(
                        "CI learning operation is missing"));
        if (!subject.operationId().equals(expectedOperation)
                || !subject.subjectId().equals(expectedSubject)
                || !subject.subjectDigest().equals(expectedDigest)
                || operation.kind() != OperationKind.RUN_CI_LEARNING
                || !operation.ownerKind().equals("CI_UPDATE_RECEIPT")
                || !operation.ownerId().equals(subject.receiptId())
                || !Objects.equals(operation.taskId(), subject.taskId())
                || !operation.inputRef().equals(subject.subjectId())
                || !operation.subjectDigest().equals(
                        subject.subjectDigest())) {
            throw new IllegalStateException(
                    "CI learning subject graph is inconsistent");
        }
        CiUpdateLearningPublication publication = userGates
                .learningPublication(subject.receiptId())
                .orElseThrow(() -> new IllegalStateException(
                        "CI learning publication graph is missing"));
        var receipt = publication.receipt();
        var plan = publication.plan();
        var authorization = publication.authorization();
        var revision = publication.revision();
        var gateSubject = publication.subject();
        var action = publication.action();
        Task task = runtime.task(subject.taskId()).orElseThrow();
        PullRequestSubject pr = runtime.pullRequest(subject.prId())
                .orElseThrow();
        CiRound green = autofix.roundById(subject.greenRoundId())
                .orElseThrow();
        CiRound red = autofix.roundById(subject.redRoundId()).orElseThrow();
        CiRepairAttempt repair = autofix.repairAttempt(
                subject.repairAttemptId()).orElseThrow();
        boolean exact = receipt.receiptId().equals(subject.receiptId())
                && receipt.receiptDigest().equals(subject.receiptDigest())
                && receipt.operationId().equals(
                        subject.publicationOperationId())
                && receipt.headRepositoryExternalId().equals(
                        subject.headRepositoryExternalId())
                && receipt.headRepositoryOwner().equals(
                        subject.headRepositoryOwner())
                && receipt.headRepositoryName().equals(
                        subject.headRepositoryName())
                && receipt.branchRef().equals(subject.branchRef())
                && receipt.expectedRemoteHead().equals(
                        subject.expectedRemoteHead())
                && receipt.proposedHead().equals(subject.publishedHead())
                && plan.planId().equals(subject.planId())
                && plan.planDigest().equals(subject.planDigest())
                && plan.authorizationId().equals(subject.authorizationId())
                && plan.requiredCiPolicyRevisionId().equals(
                        subject.publicationPolicyRevisionId())
                && authorization.authorizationId().equals(
                        subject.authorizationId())
                && authorization.gateId().equals(subject.gateId())
                && authorization.gateRevision() == subject.gateRevision()
                && authorization.subjectDigest().equals(
                        subject.gateSubjectDigest())
                && authorization.actionDigest().equals(
                        subject.gateActionDigest())
                && revision.subjectDigest().equals(
                        subject.gateSubjectDigest())
                && revision.actionDigest().equals(
                        subject.gateActionDigest())
                && gateSubject.taskId().equals(subject.taskId())
                && gateSubject.prId().equals(subject.prId())
                && gateSubject.repositoryId().equals(subject.repositoryId())
                && gateSubject.expectedRemoteHead().equals(
                        subject.expectedRemoteHead())
                && gateSubject.proposedHead().equals(subject.publishedHead())
                && gateSubject.changeSetRevisionId().equals(
                        subject.outputChangeSetRevisionId())
                && gateSubject.diffDigest().equals(
                        subject.outputDiffDigest())
                && gateSubject.requiredCiPolicyRevisionId().equals(
                        subject.publicationPolicyRevisionId())
                && gateSubject.ciRoundId().equals(subject.redRoundId())
                && gateSubject.ciEvidenceRevision()
                        == red.evidenceRevision()
                && gateSubject.ciObservationIds().equals(
                        red.checkObservationIds())
                && gateSubject.failedLogRefs().equals(
                        subject.failedLogRefs())
                && gateSubject.repairAttemptId().equals(
                        subject.repairAttemptId())
                && gateSubject.repairResultId().equals(
                        subject.repairResultId())
                && Objects.equals(gateSubject.cleanupId(),
                        subject.cleanupId())
                && Objects.equals(gateSubject.cleanupResultId(),
                        subject.cleanupResultId())
                && action.headRepositoryExternalId().equals(
                        subject.headRepositoryExternalId())
                && action.headRepositoryOwner().equals(
                        subject.headRepositoryOwner())
                && action.headRepositoryName().equals(
                        subject.headRepositoryName())
                && action.branchRef().equals(subject.branchRef())
                && action.expectedRemoteHead().equals(
                        subject.expectedRemoteHead())
                && action.proposedHead().equals(subject.publishedHead())
                && !action.forcePush()
                && task.repositoryId().equals(subject.repositoryId())
                && Objects.equals(task.prId(), subject.prId())
                && pr.taskId().equals(subject.taskId())
                && pr.repositoryId().equals(subject.repositoryId())
                && "GITHUB".equals(pr.provider())
                && pr.headRepositoryExternalId().equals(
                        subject.headRepositoryExternalId())
                && pr.headRepositoryOwner().equals(
                        subject.headRepositoryOwner())
                && pr.headRepositoryName().equals(
                        subject.headRepositoryName())
                && ("refs/heads/" + pr.branchName()).equals(
                        subject.branchRef())
                && green.roundId().equals(subject.greenRoundId())
                && green.taskId().equals(subject.taskId())
                && green.prId().equals(subject.prId())
                && green.remoteHead().equals(subject.publishedHead())
                && green.policyRevisionId().equals(
                        subject.greenPolicyRevisionId())
                && green.evidenceRevision()
                        == subject.greenEvidenceRevision()
                && Objects.equals(green.sourceObservationOperationId(),
                        subject.greenObservationOperationId())
                && Objects.equals(green.sourceReceiptId(),
                        subject.receiptId())
                && green.checkObservationIds().equals(
                        subject.greenObservationIds())
                && (green.state() == RoundState.GREEN
                    || green.state() == RoundState.SUPERSEDED)
                && red.roundId().equals(subject.redRoundId())
                && red.taskId().equals(subject.taskId())
                && red.prId().equals(subject.prId())
                && red.remoteHead().equals(subject.expectedRemoteHead())
                && red.policyRevisionId().equals(
                        subject.publicationPolicyRevisionId())
                && red.failedLogRefs().equals(subject.failedLogRefs())
                && repair.roundId().equals(red.roundId())
                && Objects.equals(repair.resultRef(),
                        subject.repairResultId());
        if (!exact) {
            throw new IllegalStateException(
                    "CI learning publication authority changed");
        }
        if (green.state() == RoundState.SUPERSEDED) {
            CiRound successor = autofix.roundById(green.supersededBy())
                    .orElseThrow(() -> new IllegalStateException(
                            "CI learning GREEN supersession is missing"));
            if (!successor.prId().equals(green.prId())
                    || !successor.remoteHead().equals(green.remoteHead())) {
                throw new IllegalStateException(
                        "CI learning GREEN supersession changed subject");
            }
        }
        List<String> currentGreenDigests = subject.greenObservationIds()
                .stream()
                .map(id -> {
                    CiCheckObservation observation = autofix.observation(id)
                            .orElseThrow();
                    if (!Objects.equals(observation.sourceOperationId(),
                                subject.greenObservationOperationId())
                            || !Objects.equals(observation.sourceReceiptId(),
                                subject.receiptId())
                            || !observation.prId().equals(subject.prId())
                            || !observation.check().headSha().equals(
                                subject.publishedHead())) {
                        throw new IllegalStateException(
                                "CI learning GREEN observation changed");
                    }
                    return observationDigest(observation);
                })
                .toList();
        List<String> currentLogDigests = subject.failedLogRefs().stream()
                .map(ref -> {
                    var evidence = autofix.logEvidence(ref).orElseThrow();
                    if (!red.checkObservationIds().contains(
                            evidence.observationId())) {
                        throw new IllegalStateException(
                                "CI learning failed log changed source");
                    }
                    return evidence.contentDigest();
                })
                .toList();
        if (!currentGreenDigests.equals(subject.greenObservationDigests())
                || !currentLogDigests.equals(subject.failedLogDigests())) {
            throw new IllegalStateException(
                    "CI learning evidence graph changed");
        }
        Integer outputRevision = jdbc.queryForObject(
                """
                SELECT COUNT(*) FROM flow_runtime_change_set_revision
                WHERE change_set_revision_id = ? AND task_id = ?
                  AND head_sha = ? AND diff_digest = ?
                """,
                Integer.class, subject.outputChangeSetRevisionId(),
                subject.taskId(), subject.publishedHead(),
                subject.outputDiffDigest());
        if (requireNonNull(outputRevision,
                "CI learning output revision count is null") != 1) {
            throw new IllegalStateException(
                    "CI learning output change set changed");
        }
        ResultView repairResult = resultView(subject.repairResultId())
                .orElseThrow();
        if (!repairResult.runId().equals(repair.agentRunId())) {
            throw new IllegalStateException(
                    "CI learning repair AgentResult changed owner");
        }
        if (!resultDigest(repairResult).equals(
                subject.repairResultDigest())) {
            throw new IllegalStateException(
                    "CI learning repair AgentResult changed content");
        }
        if (subject.cleanupId() == null) {
            if (repair.state() != AttemptState.FIX_PREPARED
                    || !subject.publishedHead().equals(
                            repair.outputLocalHead())
                    || !subject.outputChangeSetRevisionId().equals(
                            repair.outputChangeSetRevisionId())) {
                throw new IllegalStateException(
                        "CI learning repair result changed");
            }
        }
        else {
            CiCleanupSeal seal = autofix.cleanupSeal(
                    subject.cleanupId()).orElseThrow();
            CiCleanupCompletion completion = autofix.cleanupCompletion(
                    subject.cleanupId()).orElseThrow();
            if (!seal.repairAttemptId().equals(repair.attemptId())
                    || repair.state() != AttemptState.NON_CLEAN_HANDOFF
                    || completion.outcome() != CleanupOutcome.FIX_PREPARED
                    || !Objects.equals(completion.resultRef(),
                            subject.cleanupResultId())
                    || !subject.publishedHead().equals(
                            completion.outputHead())
                    || !subject.outputChangeSetRevisionId().equals(
                        completion.outputChangeSetRevisionId())) {
                throw new IllegalStateException(
                        "CI learning cleanup result changed");
            }
            ResultView cleanupResult = resultView(
                    subject.cleanupResultId()).orElseThrow();
            if (!cleanupResult.runId().equals(completion.runId())) {
                throw new IllegalStateException(
                        "CI learning cleanup AgentResult changed owner");
            }
            if (!resultDigest(cleanupResult).equals(
                    subject.cleanupResultDigest())) {
                throw new IllegalStateException(
                        "CI learning cleanup AgentResult changed content");
            }
        }
    }

    public Optional<CiLearningStart> beginCiLearning(Claim claim)
    {
        requireNonNull(claim, "claim is null");
        if (runtime.ciLearningCanceledReplay(claim)) {
            Operation operation = runtime.operation(claim.operationId())
                    .orElseThrow();
            CiLearningSubject subject = learningSubject(
                    operation.inputRef()).orElseThrow();
            assertLearningSubjectIntegrity(subject);
            runtime.runForOperation(claim.operationId()).ifPresent(run -> {
                CiLearningCompletion completion = learningCompletion(
                        claim.operationId()).orElseThrow();
                assertLearningCompletion(subject, completion);
                if (completion.state()
                        != LearningCompletionState.MISSED
                        || !completion.reasonCode().equals(
                            "CI_LEARNING_GREEN_SUPERSEDED")) {
                    throw new IllegalStateException(
                            "canceled CI learning changed disposition");
                }
            });
            return Optional.empty();
        }
        return requireNonNull(transactions.execute(ignored -> {
            Operation operation = runtime.assertCiLearningClaim(claim);
            CiLearningSubject subject = learningSubject(
                    operation.inputRef()).orElseThrow(() ->
                            new IllegalStateException(
                                    "CI learning subject is missing"));
            assertLearningSubjectIntegrity(subject);
            if (!lockCurrentLearningGreen(subject)) {
                runtime.cancelClaimedCiLearning(claim);
                return Optional.empty();
            }
            return Optional.of(runtime.startCiLearningAgent(
                    claim, CI_LEARNING_PROMPT_MANIFEST,
                    CI_LEARNING_CAPABILITY_SET));
        }), "CI learning begin transaction returned null");
    }

    /** Owner recovery for the isolated learner; never mutates Task state. */
    public boolean recoverExpiredCiLearning(
            String operationId, long generation)
    {
        requireText(operationId, "operationId");
        return requireNonNull(transactions.execute(ignored -> {
            Operation operation = runtime.operation(operationId)
                    .orElseThrow(() -> new IllegalArgumentException(
                            "Unknown operation: " + operationId));
            if (operation.kind() != OperationKind.RUN_CI_LEARNING) {
                throw new IllegalArgumentException(
                        "operation is not CI learning");
            }
            CiLearningSubject subject = learningSubject(
                    operation.inputRef()).orElseThrow();
            assertLearningSubjectIntegrity(subject);
            Optional<CiLearningCompletion> prior = learningCompletion(
                    operationId);
            if (prior.isPresent()) {
                CiLearningCompletion stored = prior.orElseThrow();
                runtime.assertStoppedCiLearningRecoveryReplay(
                        operationId, generation, stored.resultId());
                assertLearningCompletion(subject, stored);
                return false;
            }
            Optional<ExpiredClaim> expired = runtime.expiredClaims().stream()
                    .filter(value -> value.operationId().equals(operationId)
                            && value.generation() == generation)
                    .findFirst();
            if (expired.isEmpty()
                    || expired.orElseThrow().processAttemptState()
                            != ProcessAttemptState.STOPPED) {
                return runtime.recoverExpiredCiLearning(
                        operationId, generation);
            }
            Optional<LessonRequest> request = lessonRequest(operationId);
            boolean candidate = request.isPresent()
                    && lockCurrentLearningGreen(subject);
            StoppedCiLearningResult recovered =
                    runtime.finishExpiredStoppedCiLearningProcess(
                            operationId, generation);
            if (!recovered.runId().equals(
                        request.map(LessonRequest::runId)
                                .orElse(recovered.runId()))
                    || !recovered.subjectId().equals(subject.subjectId())) {
                throw new IllegalStateException(
                        "stopped learner recovery changed its owner");
            }
            String lessonId = candidate
                    ? storeLessonCandidate(
                            subject, recovered.runId(),
                            request.orElseThrow())
                    : null;
            String reason = candidate
                    ? "LESSON_CANDIDATE_SAVED"
                    : request.isEmpty()
                            ? "LESSON_NOT_PROPOSED"
                            : "GREEN_SUPERSEDED";
            CiLearningCompletion completion = storeLearningCompletion(
                    subject, recovered.runId(),
                    recovered.result().resultId(),
                    candidate ? LearningCompletionState.CANDIDATE
                            : LearningCompletionState.MISSED,
                    lessonId, reason);
            assertLearningCompletion(subject, completion);
            return false;
        }), "CI learning recovery transaction returned null");
    }

    @Override
    public String readRepairEvidence(String subjectId)
    {
        CiLearningSubject subject = learningSubject(subjectId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown CI learning subject: " + subjectId));
        assertLearningSubjectIntegrity(subject);
        ResultView repair = resultView(subject.repairResultId())
                .orElseThrow();
        ResultView cleanup = subject.cleanupResultId() == null
                ? null
                : resultView(subject.cleanupResultId()).orElseThrow();
        return "subject=" + subject.subjectId()
                + "\nreceipt=" + subject.receiptId()
                + "\npublicationPolicy="
                + subject.publicationPolicyRevisionId()
                + "\ngreenPolicy=" + subject.greenPolicyRevisionId()
                + "\nredRound=" + subject.redRoundId()
                + "\ngreenRound=" + subject.greenRoundId()
                + "\nrepairAttempt=" + subject.repairAttemptId()
                + "\noutputHead=" + subject.publishedHead()
                + "\noutputChangeSet="
                + subject.outputChangeSetRevisionId()
                + "\noutputDiffDigest=" + subject.outputDiffDigest()
                + "\nrepairOutcome=" + repair.terminalOutcome()
                + "\nrepairError=" + nullToEmpty(repair.errorRef())
                + "\nrepairResult=" + boundedEvidence(
                        repair.finalContent())
                + "\ncleanup=" + nullToEmpty(subject.cleanupId())
                + (cleanup == null ? "" : "\ncleanupOutcome="
                        + cleanup.terminalOutcome()
                        + "\ncleanupError="
                        + nullToEmpty(cleanup.errorRef())
                        + "\ncleanupResult="
                        + boundedEvidence(cleanup.finalContent()))
                + "\nfailedLogs=" + String.join(",",
                        subject.failedLogRefs());
    }

    @Override
    public String readLog(
            String subjectId, String logRef, long offset, int maxBytes)
    {
        CiLearningSubject subject = learningSubject(subjectId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown CI learning subject: " + subjectId));
        assertLearningSubjectIntegrity(subject);
        if (!subject.failedLogRefs().contains(logRef)) {
            throw new IllegalArgumentException(
                    "log is not bound to this learning subject");
        }
        var window = autofix.readLogWindow(logRef, offset, maxBytes);
        return "offset=" + window.offset()
                + "\nnextOffset=" + window.nextOffset()
                + "\nendOfLog=" + window.endOfLog()
                + "\n" + window.content();
    }

    @Override
    public String saveLesson(
            CiLearningStart start,
            Claim claim,
            String processAttemptId,
            String title,
            String markdown)
    {
        requireNonNull(start, "start is null");
        requireNonNull(claim, "claim is null");
        requireText(processAttemptId, "processAttemptId");
        return requireNonNull(transactions.execute(ignored -> {
            CiLearningSubject subject = learningSubject(
                    start.run().inputRef()).orElseThrow();
            assertLearningSubjectIntegrity(subject);
            if (lessonRequest(subject.operationId()).isPresent()) {
                return runtime.sealCiLearningLesson(
                        processAttemptId, claim, title, markdown);
            }
            if (!lockCurrentLearningGreen(subject)) {
                throw new IllegalStateException(
                        "CI learning GREEN authority is stale");
            }
            return runtime.sealCiLearningLesson(
                    processAttemptId, claim, title, markdown);
        }), "CI lesson seal transaction returned null");
    }

    @Override
    public AgentResult finish(
            CiLearningStart start,
            Claim claim,
            InProcessCiLearningAgentSupervisor.AgentCompletion completion)
    {
        requireNonNull(start, "start is null");
        requireNonNull(claim, "claim is null");
        requireNonNull(completion, "completion is null");
        return requireNonNull(transactions.execute(ignored -> {
            CiLearningSubject subject = learningSubject(
                    start.run().inputRef()).orElseThrow();
            assertLearningSubjectIntegrity(subject);
            Optional<CiLearningCompletion> prior = learningCompletion(
                    subject.operationId());
            if (prior.isPresent()) {
                AgentResult replay = runtime.finishCiLearningAgentRun(
                        start.run().runId(), claim,
                        completion.terminalOutcome(),
                        completion.finalContent(), completion.errorRef());
                CiLearningCompletion stored = prior.orElseThrow();
                if (!stored.runId().equals(start.run().runId())
                        || !stored.resultId().equals(replay.resultId())) {
                    throw new IllegalStateException(
                            "CI learning completion replay changed result");
                }
                if (stored.state()
                        == LearningCompletionState.CANDIDATE) {
                    LessonRequest request = lessonRequest(
                            subject.operationId()).orElseThrow();
                    CiLesson lesson = lesson(stored.lessonId()).orElseThrow();
                    if (!lesson.runId().equals(stored.runId())
                            || !lesson.subjectId().equals(
                                subject.subjectId())
                            || !lesson.title().equals(request.title())
                            || !lesson.markdown().equals(request.markdown())
                            || !lesson.contentDigest().equals(
                                request.contentDigest())) {
                        throw new IllegalStateException(
                                "CI lesson replay graph is inconsistent");
                    }
                }
                return replay;
            }
            Optional<LessonRequest> request = lessonRequest(
                    claim.operationId());
            boolean candidate = request.isPresent()
                    && lockCurrentLearningGreen(subject);
            AgentResult result = runtime.finishCiLearningAgentRun(
                    start.run().runId(), claim,
                    completion.terminalOutcome(), completion.finalContent(),
                    completion.errorRef());
            String lessonId = null;
            String reason;
            if (candidate) {
                LessonRequest sealed = request.orElseThrow();
                lessonId = storeLessonCandidate(
                        subject, start.run().runId(), sealed);
                reason = "LESSON_CANDIDATE_SAVED";
            }
            else if (request.isEmpty()) {
                reason = "LESSON_NOT_PROPOSED";
            }
            else {
                reason = "GREEN_SUPERSEDED";
            }
            LearningCompletionState state = candidate
                    ? LearningCompletionState.CANDIDATE
                    : LearningCompletionState.MISSED;
            storeLearningCompletion(
                    subject, start.run().runId(), result.resultId(), state,
                    lessonId, reason);
            return result;
        }), "CI learning finalization transaction returned null");
    }

    private String storeLessonCandidate(
            CiLearningSubject subject, String runId, LessonRequest request)
    {
        String lessonId = stableId("ci-lesson", subject.receiptId());
        jdbc.update(
                """
                INSERT OR IGNORE INTO flow_ci_lesson (
                    lesson_id, repository_id, learning_operation_id,
                    run_id, subject_id, status, title, markdown,
                    content_digest, created_at
                ) VALUES (?, ?, ?, ?, ?, 'CANDIDATE', ?, ?, ?, ?)
                """,
                lessonId, subject.repositoryId(), subject.operationId(),
                runId, subject.subjectId(), request.title(),
                request.markdown(), request.contentDigest(),
                clock.instant().toEpochMilli());
        CiLesson stored = lesson(lessonId).orElseThrow();
        if (!stored.repositoryId().equals(subject.repositoryId())
                || !stored.learningOperationId().equals(
                    subject.operationId())
                || !stored.runId().equals(runId)
                || !stored.subjectId().equals(subject.subjectId())
                || !stored.title().equals(request.title())
                || !stored.markdown().equals(request.markdown())
                || !stored.contentDigest().equals(
                    request.contentDigest())) {
            throw new IllegalStateException(
                    "CI lesson replay changed immutable content");
        }
        return lessonId;
    }

    private CiLearningCompletion storeLearningCompletion(
            CiLearningSubject subject,
            String runId,
            String resultId,
            LearningCompletionState state,
            String lessonId,
            String reason)
    {
        jdbc.update(
                """
                INSERT OR IGNORE INTO flow_ci_learning_completion (
                    operation_id, run_id, result_id, state, lesson_id,
                    reason_code, completed_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                subject.operationId(), runId, resultId, state.name(),
                lessonId, reason, clock.instant().toEpochMilli());
        CiLearningCompletion stored = learningCompletion(
                subject.operationId()).orElseThrow();
        if (!stored.runId().equals(runId)
                || !stored.resultId().equals(resultId)
                || stored.state() != state
                || !Objects.equals(stored.lessonId(), lessonId)
                || !stored.reasonCode().equals(reason)) {
            throw new IllegalStateException(
                    "CI learning finalization replay changed identity");
        }
        return stored;
    }

    private void assertLearningCompletion(
            CiLearningSubject subject, CiLearningCompletion completion)
    {
        AgentRun run = runtime.runForOperation(subject.operationId())
                .orElseThrow();
        ResultView result = resultView(completion.resultId()).orElseThrow();
        if (!completion.operationId().equals(subject.operationId())
                || !completion.runId().equals(run.runId())
                || !result.runId().equals(run.runId())) {
            throw new IllegalStateException(
                    "CI learning completion changed its runtime owner");
        }
        if (completion.state() == LearningCompletionState.CANDIDATE) {
            LessonRequest request = lessonRequest(
                    subject.operationId()).orElseThrow();
            CiLesson stored = lesson(completion.lessonId()).orElseThrow();
            if (!stored.learningOperationId().equals(subject.operationId())
                    || !stored.runId().equals(run.runId())
                    || !stored.subjectId().equals(subject.subjectId())
                    || !stored.title().equals(request.title())
                    || !stored.markdown().equals(request.markdown())
                    || !stored.contentDigest().equals(
                        request.contentDigest())) {
                throw new IllegalStateException(
                        "CI learning candidate graph is inconsistent");
            }
        }
        else if (completion.lessonId() != null) {
            throw new IllegalStateException(
                    "missed CI learning completion has a lesson");
        }
    }

    private boolean lockCurrentLearningGreen(CiLearningSubject subject)
    {
        CiRound bound = autofix.roundById(subject.greenRoundId())
                .orElseThrow();
        PullRequestSubject pr = runtime.pullRequest(subject.prId())
                .orElseThrow();
        Optional<RequiredCiPolicyRevision> currentPolicy =
                autofix.currentPolicy(subject.repositoryId(),
                        pr.scopeKey());
        if (currentPolicy.isEmpty()
                || !currentPolicy.orElseThrow().policyRevisionId().equals(
                        subject.greenPolicyRevisionId())
                || !autofix.lockCurrentPolicy(currentPolicy.orElseThrow())) {
            return false;
        }
        runtime.assertAndLockCiUpdatePr(pr);
        int roundLocked = jdbc.update(
                """
                UPDATE flow_ci_round SET round_id = round_id
                WHERE round_id = ? AND task_id = ? AND pr_id = ?
                  AND remote_head = ? AND policy_revision_id = ?
                  AND evidence_revision = ? AND state = 'GREEN'
                  AND superseded_by IS NULL
                  AND source_observation_operation_id = ?
                  AND source_receipt_id = ?
                """,
                subject.greenRoundId(), subject.taskId(), subject.prId(),
                subject.publishedHead(), subject.greenPolicyRevisionId(),
                subject.greenEvidenceRevision(),
                subject.greenObservationOperationId(), subject.receiptId());
        return roundLocked == 1
                && bound.state() == RoundState.GREEN
                && bound.supersededBy() == null
                && Objects.equals(
                        pr.currentRemoteHead(),
                        subject.publishedHead());
    }

    private Optional<LessonRequest> lessonRequest(String operationId)
    {
        return jdbc.query(
                "SELECT * FROM flow_ci_learning_lesson_request "
                        + "WHERE operation_id = ?",
                (result, row) -> new LessonRequest(
                        result.getString("operation_id"),
                        result.getString("run_id"),
                        result.getString("subject_id"),
                        result.getString("process_attempt_id"),
                        result.getString("title"),
                        result.getString("markdown"),
                        result.getString("content_digest"),
                        Instant.ofEpochMilli(
                                result.getLong("sealed_at"))),
                operationId).stream().findFirst();
    }

    private Optional<ResultView> resultView(String resultId)
    {
        return jdbc.query(
                "SELECT result_id, run_id, terminal_outcome, final_content, "
                        + "error_ref, stop_proof_ref "
                        + "FROM flow_runtime_agent_result "
                        + "WHERE result_id = ?",
                (result, row) -> new ResultView(
                        result.getString("result_id"),
                        result.getString("run_id"),
                        result.getString("terminal_outcome"),
                        result.getString("final_content"),
                        result.getString("error_ref"),
                        result.getString("stop_proof_ref")),
                resultId).stream().findFirst();
    }

    private static String boundedEvidence(String content)
    {
        if (content == null) {
            return "";
        }
        return content.length() <= 16_384
                ? content
                : content.substring(0, 16_384);
    }

    private static String resultDigest(ResultView result)
    {
        return digest(
                "ci-learning-agent-result:v1", result.resultId(),
                result.runId(), result.terminalOutcome(),
                nullToEmpty(result.finalContent()),
                nullToEmpty(result.errorRef()), result.stopProofRef());
    }

    public Optional<CiLearningCompletion> learningCompletion(
            String operationId)
    {
        requireText(operationId, "operationId");
        return jdbc.query(
                "SELECT * FROM flow_ci_learning_completion "
                        + "WHERE operation_id = ?",
                (result, row) -> new CiLearningCompletion(
                        result.getString("operation_id"),
                        result.getString("run_id"),
                        result.getString("result_id"),
                        LearningCompletionState.valueOf(
                                result.getString("state")),
                        result.getString("lesson_id"),
                        result.getString("reason_code"),
                        Instant.ofEpochMilli(
                                result.getLong("completed_at"))),
                operationId).stream().findFirst();
    }

    public Optional<CiLesson> lesson(String lessonId)
    {
        requireText(lessonId, "lessonId");
        return jdbc.query(
                "SELECT * FROM flow_ci_lesson WHERE lesson_id = ?",
                (result, row) -> new CiLesson(
                        result.getString("lesson_id"),
                        result.getString("repository_id"),
                        result.getString("learning_operation_id"),
                        result.getString("run_id"),
                        result.getString("subject_id"),
                        result.getString("title"),
                        result.getString("markdown"),
                        result.getString("content_digest"),
                        Instant.ofEpochMilli(
                                result.getLong("created_at"))),
                lessonId).stream().findFirst();
    }

    private record LessonRequest(
            String operationId,
            String runId,
            String subjectId,
            String processAttemptId,
            String title,
            String markdown,
            String contentDigest,
            Instant sealedAt) {}

    private record ResultView(
            String resultId,
            String runId,
            String terminalOutcome,
            String finalContent,
            String errorRef,
            String stopProofRef) {}

    private List<String> learningList(
            String table, String column, String subjectId)
    {
        boolean green = table.equals(
                "flow_ci_learning_green_observation")
                && (column.equals("observation_id")
                    || column.equals("evidence_digest"));
        boolean red = table.equals("flow_ci_learning_failed_log")
                && (column.equals("log_ref")
                    || column.equals("content_digest"));
        if (!green && !red) {
            throw new IllegalArgumentException("unsupported learning list");
        }
        return jdbc.queryForList(
                "SELECT " + column + " FROM " + table
                        + " WHERE subject_id = ? ORDER BY ordinal",
                String.class, subjectId);
    }

    private record LearningOrigin(
            String receiptId,
            String receiptDigest,
            String publicationOperationId,
            String headRepositoryExternalId,
            String headRepositoryOwner,
            String headRepositoryName,
            String branchRef,
            String expectedRemoteHead,
            String publishedHead,
            String planId,
            String planDigest,
            String publicationPolicyRevisionId,
            String authorizationId,
            String gateId,
            long gateRevision,
            String gateSubjectDigest,
            String gateActionDigest,
            String taskId,
            String prId,
            String repositoryId,
            String gateChangeSetRevisionId,
            String gateDiffDigest,
            String gateCiRoundId,
            String gateRepairAttemptId,
            String gateRepairResultId,
            String gateCleanupId,
            String gateCleanupResultId,
            String redRoundId,
            String repairAttemptId,
            String repairResultId,
            String cleanupId,
            String cleanupResultId) {}

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

    private static String stableId(String prefix, String... parts)
    {
        return prefix + ":" + digest(prefix, parts);
    }

    private static String digestList(List<String> values)
    {
        return digest("list:v1", values.toArray(String[]::new));
    }

    private static String observationDigest(CiCheckObservation observation)
    {
        NormalizedCheck check = observation.check();
        return digest(
                "ci-learning-observation:v1",
                observation.observationId(), observation.prId(),
                nullToEmpty(observation.sourceOperationId()),
                nullToEmpty(observation.sourceReceiptId()),
                check.headSha(), check.selectorKey(),
                check.providerCheckId(), check.providerRunId(),
                Long.toString(check.attempt()),
                check.providerStateRevision(), check.name(), check.status(),
                nullToEmpty(check.conclusion()),
                instantText(check.startedAt()), instantText(check.completedAt()),
                check.observedAt().toString(), check.rawEvidenceRef());
    }

    private static String instantText(Instant value)
    {
        return value == null ? "" : value.toString();
    }

    private static String nullToEmpty(String value)
    {
        return value == null ? "" : value;
    }

    private static String digest(String prefix, String... parts)
    {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            updateDigest(digest, prefix);
            for (String part : parts) {
                updateDigest(digest, requireNonNull(part, "digest part is null"));
            }
            return HexFormat.of().formatHex(digest.digest());
        }
        catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void updateDigest(MessageDigest digest, String value)
    {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(Integer.toString(bytes.length)
                .getBytes(StandardCharsets.US_ASCII));
        digest.update((byte) ':');
        digest.update(bytes);
        digest.update((byte) '\n');
    }

    private static void requireText(String value, String name)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is blank");
        }
    }
}
