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
import com.bytequay.app.flow.ci.CiAutofixRecords.LearningCompletionState;
import com.bytequay.app.flow.ci.CiAutofixRecords.NormalizedCheck;
import com.bytequay.app.flow.ci.CiAutofixRecords.RequiredCiPolicyRevision;
import com.bytequay.app.flow.ci.CiAutofixRecords.RoundState;
import com.bytequay.app.flow.ci.CiLearningStore.LearningOrigin;
import com.bytequay.app.flow.ci.CiLearningStore.LessonRequest;
import com.bytequay.app.flow.ci.CiLearningStore.ResultView;
import com.bytequay.app.flow.gate.UserGates;
import com.bytequay.app.flow.gate.UserGates.CiUpdateLearningPublication;
import com.bytequay.app.flow.runtime.FlowRuntime;
import com.bytequay.app.flow.runtime.FlowRuntime.CiLearningStart;
import com.bytequay.app.flow.runtime.FlowRuntime.StoppedCiLearningResult;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentResult;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.AgentRun;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ExpiredClaim;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Operation;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.OperationKind;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.ProcessAttemptState;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.PullRequestSubject;
import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Task;
import com.bytequay.app.flow.runtime.InProcessCiLearningAgentSupervisor;
import com.bytequay.app.flow.runtime.InProcessCiLearningAgentSupervisor.LearningOwner;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.sql.DataSource;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * The one concrete transaction joining CI evidence to runtime dispatch.
 *
 * <p>It joins exact-head enqueue/selection to one runtime-owned writer run and
 * joins the runtime's stopped-writer transaction to CI attempt finalization.

/** Owns optional CI learning reservation, execution, and immutable lessons. */
public final class CiLearningCoordinator
        implements LearningOwner
{
    private static final String CI_LEARNING_PROMPT_MANIFEST =
            "ci-learning-prompt:v1";
    private static final String CI_LEARNING_CAPABILITY_SET =
            "ci-learning-capabilities:v1";

    private final TransactionTemplate transactions;
    private final CiLearningStore store;
    private final CiAutofix autofix;
    private final FlowRuntime runtime;
    private final UserGates userGates;

    public CiLearningCoordinator(
            DataSource dataSource,
            CiAutofix autofix,
            FlowRuntime runtime,
            UserGates userGates,
            Clock clock)
    {
        this.transactions = new TransactionTemplate(
                new DataSourceTransactionManager(
                        requireNonNull(dataSource, "dataSource is null")));
        this.store = new CiLearningStore(dataSource, clock);
        this.autofix = requireNonNull(autofix, "autofix is null");
        this.runtime = requireNonNull(runtime, "runtime is null");
        this.userGates = requireNonNull(userGates, "userGates is null");
    }

    List<CiLesson> candidateLessons(String repositoryId)
    {
        return store.candidateLessons(repositoryId);
    }
    void assertGreenLearningReplay(CiRound round)
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
        LearningOrigin origin = store.learningOrigin(green.sourceReceiptId())
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
    Optional<CiLearningSubject> reserveCiLearningIfEligible(
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
        LearningOrigin origin = store.learningOrigin(green.sourceReceiptId())
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
        ResultView repairResult = store.resultView(
                origin.repairResultId()).orElse(null);
        ResultView cleanupResult = origin.cleanupResultId() == null
                ? null : store.resultView(origin.cleanupResultId()).orElse(null);
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
        store.insertLearningSubject(subject);
        assertLearningSubjectIntegrity(subject);
        return Optional.of(subject);
    }

    public Optional<CiLearningSubject> learningSubject(String subjectId)
    {
        return store.learningSubject(subjectId);
    }
    public Optional<CiLearningSubject> learningSubjectForReceipt(
            String receiptId)
    {
        return store.learningSubjectForReceipt(receiptId);
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
        if (store.countOutputChangeSet(subject) != 1) {
            throw new IllegalStateException(
                    "CI learning output change set changed");
        }
        ResultView repairResult = store.resultView(subject.repairResultId())
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
            ResultView cleanupResult = store.resultView(
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
            Optional<LessonRequest> request = store.lessonRequest(operationId);
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
                    ? store.storeLessonCandidate(
                            subject, recovered.runId(),
                            request.orElseThrow())
                    : null;
            String reason = candidate
                    ? "LESSON_CANDIDATE_SAVED"
                    : request.isEmpty()
                            ? "LESSON_NOT_PROPOSED"
                            : "GREEN_SUPERSEDED";
            CiLearningCompletion completion = store.storeLearningCompletion(
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
        ResultView repair = store.resultView(subject.repairResultId())
                .orElseThrow();
        ResultView cleanup = subject.cleanupResultId() == null
                ? null
                : store.resultView(subject.cleanupResultId()).orElseThrow();
        return "publicationPolicy="
                + subject.publicationPolicyRevisionId()
                + "\ngreenPolicy=" + subject.greenPolicyRevisionId()
                + "\noutputHead=" + subject.publishedHead()
                + "\noutputDiffDigest=" + subject.outputDiffDigest()
                + "\nrepairOutcome=" + repair.terminalOutcome()
                + "\nrepairError=" + nullToEmpty(repair.errorRef())
                + "\nrepairResult=" + boundedEvidence(
                        repair.finalContent())
                + "\ncleanupPresent=" + (cleanup != null)
                + (cleanup == null ? "" : "\ncleanupOutcome="
                        + cleanup.terminalOutcome()
                        + "\ncleanupError="
                        + nullToEmpty(cleanup.errorRef())
                        + "\ncleanupResult="
                        + boundedEvidence(cleanup.finalContent()))
                + "\nfailedLogCount=" + subject.failedLogRefs().size();
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
            if (store.lessonRequest(subject.operationId()).isPresent()) {
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
                    LessonRequest request = store.lessonRequest(
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
            Optional<LessonRequest> request = store.lessonRequest(
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
                lessonId = store.storeLessonCandidate(
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
            store.storeLearningCompletion(
                    subject, start.run().runId(), result.resultId(), state,
                    lessonId, reason);
            return result;
        }), "CI learning finalization transaction returned null");
    }

    private void assertLearningCompletion(
            CiLearningSubject subject, CiLearningCompletion completion)
    {
        AgentRun run = runtime.runForOperation(subject.operationId())
                .orElseThrow();
        ResultView result = store.resultView(completion.resultId()).orElseThrow();
        if (!completion.operationId().equals(subject.operationId())
                || !completion.runId().equals(run.runId())
                || !result.runId().equals(run.runId())) {
            throw new IllegalStateException(
                    "CI learning completion changed its runtime owner");
        }
        if (completion.state() == LearningCompletionState.CANDIDATE) {
            LessonRequest request = store.lessonRequest(
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
        int roundLocked = store.lockGreenRound(subject);
        return roundLocked == 1
                && bound.state() == RoundState.GREEN
                && bound.supersededBy() == null
                && Objects.equals(
                        pr.currentRemoteHead(),
                        subject.publishedHead());
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
        return store.learningCompletion(operationId);
    }
    public Optional<CiLesson> lesson(String lessonId)
    {
        return store.lesson(lessonId);
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
