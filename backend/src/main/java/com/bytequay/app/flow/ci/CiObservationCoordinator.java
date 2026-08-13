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

import com.bytequay.app.flow.ci.CiAutofixRecords.CiRound;
import com.bytequay.app.flow.ci.CiAutofixRecords.FinalizeHeadResult;
import com.bytequay.app.flow.ci.CiAutofixRecords.FinalizedRound;
import com.bytequay.app.flow.ci.CiAutofixRecords.GitHubCheckSelector;
import com.bytequay.app.flow.ci.CiAutofixRecords.NormalizedCheck;
import com.bytequay.app.flow.ci.CiAutofixRecords.RequiredCiPolicyRevision;
import com.bytequay.app.flow.ci.CiAutofixRecords.RoundState;
import com.bytequay.app.flow.github.GitHubCiObservationProof;
import com.bytequay.app.flow.runtime.FlowRuntime;
import com.bytequay.app.flow.runtime.FlowRuntime.CiObservationSubject;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Operation;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PullRequestSubject;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Task;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.TaskStatus;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import java.time.Clock;
import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * The one concrete transaction joining CI evidence to runtime dispatch.
 *
 * <p>It joins exact-head enqueue/selection to one runtime-owned writer run and
 * joins the runtime's stopped-writer transaction to CI attempt finalization.

/** Joins one exact provider CI observation to its durable runtime owner. */
public final class CiObservationCoordinator
{
    private final TransactionTemplate transactions;
    private final CiAutofix autofix;
    private final FlowRuntime runtime;
    private final CiLearningCoordinator learning;
    private final Clock clock;

    public CiObservationCoordinator(
            DataSource dataSource,
            CiAutofix autofix,
            FlowRuntime runtime,
            CiLearningCoordinator learning,
            Clock clock)
    {
        this.transactions = new TransactionTemplate(
                new DataSourceTransactionManager(
                        requireNonNull(dataSource, "dataSource is null")));
        this.autofix = requireNonNull(autofix, "autofix is null");
        this.runtime = requireNonNull(runtime, "runtime is null");
        this.learning = requireNonNull(learning, "learning is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

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
            learning.assertGreenLearningReplay(round);
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
            if (CiAutofix.admitsRepair(round.state())) {
                round = autofix.queueCurrentFinalRed(round.roundId());
                runtime.registerFinalRed(
                        round.roundId(), round.taskId(), round.prId(),
                        round.remoteHead(), "ci-round:" + round.roundId());
            }
            else if (round.state() == RoundState.GREEN) {
                learning.reserveCiLearningIfEligible(round);
            }
            String resultRef = replayPrefix + round.roundId();
            // A compile-priority round is still collecting the rest of its
            // board, so it keeps the short poll while its repair runs.
            Duration pollDelay = round.state() == RoundState.COLLECTING
                    || round.state() == RoundState.PARTIAL_RED_COMPILE
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

    private static boolean isTerminal(TaskStatus status)
    {
        return status == TaskStatus.COMPLETED
                || status == TaskStatus.CANCELED;
    }
}
