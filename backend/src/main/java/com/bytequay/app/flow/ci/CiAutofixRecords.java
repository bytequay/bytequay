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

import com.bytequay.app.flow.runtime.FlowWorktreeInspector.AttachmentState;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector.FailureCode;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector.GitOperation;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector.NonCleanKind;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;

import static java.util.Objects.requireNonNull;

/** Program-owned records for the greenfield CI Autofix component. */
public final class CiAutofixRecords
{
    private CiAutofixRecords() {}

    /** One exact GitHub App/check-run name selector. */
    public record GitHubCheckSelector(long appId, String name, String key)
    {
        public GitHubCheckSelector
        {
            requireNonNull(name, "name is null");
            requireNonNull(key, "key is null");
            if (appId < 1 || name.isBlank()
                    || name.getBytes(StandardCharsets.UTF_8).length > 256
                    || name.chars().anyMatch(Character::isISOControl)
                    || !key.equals("GITHUB_CHECK:" + appId + ":" + name)) {
                throw new IllegalArgumentException(
                        "invalid GitHub required-check selector");
            }
        }

        public static GitHubCheckSelector parse(String value)
        {
            String prefix = "GITHUB_CHECK:";
            requireNonNull(value, "value is null");
            if (!value.startsWith(prefix)) {
                throw new IllegalArgumentException(
                        "unsupported required-check selector");
            }
            int separator = value.indexOf(':', prefix.length());
            if (separator < 0 || separator == value.length() - 1) {
                throw new IllegalArgumentException(
                        "unsupported required-check selector");
            }
            long appId = Long.parseLong(value.substring(
                    prefix.length(), separator));
            return new GitHubCheckSelector(
                    appId, value.substring(separator + 1), value);
        }
    }

    public enum PolicyResolution
    {
        RESOLVED,
        UNAVAILABLE
    }

    public enum RepairPlacement
    {
        TIP,
        ATTRIBUTED_FIXUP
    }

    /**
     * Where this component's own repair commits land for one Task.
     *
     * <p>Program-owned and immutable per Task. No agent reads or writes it, and
     * it is not a user setting to be toggled mid-run.
     *
     * <p>{@code perCommitCompileSelectors} names the repository's per-commit
     * compile check, resolved from the repository's own CI configuration and
     * never from a check-name heuristic. Empty means the compile check could not
     * be determined, which is fail-safe rather than fail-open: no compile
     * priority, and no boundary acceptance exception.
     *
     * <p>{@code allowsHistoryRewrite} is the standing authority a rewriting
     * placement needs. It is granted once, with the Task; a one-shot
     * {@code CI_UPDATE} consent never confers it.
     */
    public record RepairPlacementPolicy(
            String taskId,
            RepairPlacement placement,
            List<String> perCommitCompileSelectors,
            String compileSourceRef,
            String compileSourceDigest,
            boolean allowsHistoryRewrite,
            Instant recordedAt)
    {
        public RepairPlacementPolicy
        {
            requireNonNull(taskId, "taskId is null");
            requireNonNull(placement, "placement is null");
            perCommitCompileSelectors = List.copyOf(perCommitCompileSelectors);
            if ((compileSourceRef == null) != (compileSourceDigest == null)) {
                throw new IllegalArgumentException(
                        "compile source reference and digest must be paired");
            }
            if (!perCommitCompileSelectors.isEmpty()
                    && compileSourceRef == null) {
                throw new IllegalArgumentException(
                        "a compile selector must cite its CI configuration");
            }
            requireNonNull(recordedAt, "recordedAt is null");
        }

        /** A Task with no stored placement is an ordinary {@code TIP} Task. */
        public static RepairPlacementPolicy tip(String taskId, Instant now)
        {
            return new RepairPlacementPolicy(
                    taskId, RepairPlacement.TIP, List.of(), null, null,
                    false, now);
        }
    }

    /**
     * What the repository's own CI configuration says runs a build per commit.
     *
     * <p>Each check is identified by its exact application and check name, the
     * same identity the required-CI policy selects on. The source reference and
     * digest are the citation: this component stores no compile selector it
     * cannot attribute to configuration it read, precisely so that a check whose
     * name merely looks per-commit cannot excuse a red result.
     */
    public record RepositoryCompileConfiguration(
            String sourceRef,
            String sourceDigest,
            List<GitHubCheckSelector> perCommitCompileChecks)
    {
        public RepositoryCompileConfiguration
        {
            requireNonNull(sourceRef, "sourceRef is null");
            requireNonNull(sourceDigest, "sourceDigest is null");
            if (sourceRef.isBlank() || sourceDigest.isBlank()) {
                throw new IllegalArgumentException(
                        "compile configuration citation is blank");
            }
            perCommitCompileChecks = List.copyOf(perCommitCompileChecks);
        }
    }

    public enum RoundState
    {
        COLLECTING,
        FINAL_RED,
        QUEUED,
        ACTIVE,
        FIX_PREPARED,
        GREEN,
        SUPERSEDED,
        NEEDS_ATTENTION
    }

    public enum AttemptState
    {
        PENDING,
        ACTIVE,
        NON_CLEAN_HANDOFF,
        FIX_PREPARED,
        NO_HEAD_CHANGE,
        NEEDS_ATTENTION
    }

    public enum CleanupOutcome
    {
        FIX_PREPARED,
        NO_HEAD_CHANGE,
        NEEDS_ATTENTION,
        ADMISSION_BLOCKED
    }

    public enum CleanupAttentionReason
    {
        SECOND_DIRTY,
        SECOND_GIT_OPERATION_IN_PROGRESS,
        FINAL_INSPECTION_BLOCKED,
        ADMISSION_SEAL_MISMATCH,
        ADMISSION_INSPECTION_BLOCKED
    }

    public enum FinalizeBlocker
    {
        CI_POLICY_MISSING,
        CI_POLICY_UNAVAILABLE,
        CI_OBSERVATION_PENDING,
        STALE_REMOTE_HEAD
    }

    public record PublishedPrSubject(
            String prId,
            String taskId,
            String repositoryId,
            String scopeKey,
            String targetBaseRef,
            String currentRemoteHead)
    {
        public PublishedPrSubject
        {
            requireNonNull(prId, "prId is null");
            requireNonNull(taskId, "taskId is null");
            requireNonNull(repositoryId, "repositoryId is null");
            requireNonNull(scopeKey, "scopeKey is null");
            requireNonNull(targetBaseRef, "targetBaseRef is null");
            requireNonNull(currentRemoteHead, "currentRemoteHead is null");
        }
    }

    public record RequiredCiPolicyRevision(
            String policyRevisionId,
            String repositoryId,
            String scopeKey,
            String targetBaseRef,
            long sequence,
            PolicyResolution resolution,
            String sourceRef,
            String sourceDigest,
            String unavailableReasonRef,
            List<String> requiredCheckSelectors,
            List<String> acceptedConclusions,
            Instant recordedAt)
    {
        public RequiredCiPolicyRevision
        {
            requireNonNull(policyRevisionId, "policyRevisionId is null");
            requireNonNull(repositoryId, "repositoryId is null");
            requireNonNull(scopeKey, "scopeKey is null");
            requireNonNull(targetBaseRef, "targetBaseRef is null");
            requireNonNull(resolution, "resolution is null");
            requiredCheckSelectors = List.copyOf(requiredCheckSelectors);
            acceptedConclusions = List.copyOf(acceptedConclusions);
            requireNonNull(recordedAt, "recordedAt is null");
        }
    }

    /**
     * One normalized provider fact. {@code selectorKey} is a stable
     * program-defined required-check identity containing provider/application
     * and logical check-slot identity; it is never the mutable display name.
     */
    public record NormalizedCheck(
            String headSha,
            String selectorKey,
            String providerCheckId,
            String providerRunId,
            long attempt,
            String providerStateRevision,
            String name,
            String status,
            String conclusion,
            Instant startedAt,
            Instant completedAt,
            Instant observedAt,
            String rawEvidenceRef)
    {
        public NormalizedCheck
        {
            requireNonNull(headSha, "headSha is null");
            requireNonNull(selectorKey, "selectorKey is null");
            requireNonNull(providerCheckId, "providerCheckId is null");
            requireNonNull(providerRunId, "providerRunId is null");
            requireNonNull(providerStateRevision, "providerStateRevision is null");
            requireNonNull(name, "name is null");
            requireNonNull(status, "status is null");
            requireNonNull(observedAt, "observedAt is null");
            requireNonNull(rawEvidenceRef, "rawEvidenceRef is null");
        }
    }

    public record CiCheckObservation(
            String observationId,
            String prId,
            String sourceOperationId,
            String sourceReceiptId,
            NormalizedCheck check)
    {
        public CiCheckObservation
        {
            requireNonNull(observationId, "observationId is null");
            requireNonNull(prId, "prId is null");
            if ((sourceOperationId == null) != (sourceReceiptId == null)) {
                throw new IllegalArgumentException(
                        "observation source operation/receipt must be paired");
            }
            requireNonNull(check, "check is null");
        }
    }

    public record CiRound(
            String roundId,
            String taskId,
            String prId,
            String remoteHead,
            String policyRevisionId,
            long evidenceRevision,
            String sourceObservationOperationId,
            String sourceReceiptId,
            List<String> checkObservationIds,
            List<String> failedLogRefs,
            RoundState state,
            Instant createdAt,
            String supersededBy)
    {
        public CiRound
        {
            requireNonNull(roundId, "roundId is null");
            requireNonNull(taskId, "taskId is null");
            requireNonNull(prId, "prId is null");
            requireNonNull(remoteHead, "remoteHead is null");
            requireNonNull(policyRevisionId, "policyRevisionId is null");
            if ((sourceObservationOperationId == null)
                    != (sourceReceiptId == null)) {
                throw new IllegalArgumentException(
                        "round source operation/receipt must be paired");
            }
            checkObservationIds = List.copyOf(checkObservationIds);
            failedLogRefs = List.copyOf(failedLogRefs);
            requireNonNull(state, "state is null");
            requireNonNull(createdAt, "createdAt is null");
        }
    }

    /** Exact current CI-fix provenance frozen into a CI_UPDATE gate. */
    public record CiUpdateGateEvidence(
            String sourceKind,
            String sourceId,
            String roundId,
            String taskId,
            String prId,
            String remoteHead,
            String requiredCiPolicyRevisionId,
            long evidenceRevision,
            List<String> checkObservationIds,
            List<String> failedLogRefs,
            String outputChangeSetRevisionId,
            String outputHead,
            String repairAttemptId,
            String repairResultId,
            String cleanupId,
            String cleanupResultId)
    {
        public CiUpdateGateEvidence
        {
            requireNonNull(sourceKind, "sourceKind is null");
            requireNonNull(sourceId, "sourceId is null");
            requireNonNull(roundId, "roundId is null");
            requireNonNull(taskId, "taskId is null");
            requireNonNull(prId, "prId is null");
            requireNonNull(remoteHead, "remoteHead is null");
            requireNonNull(requiredCiPolicyRevisionId,
                    "requiredCiPolicyRevisionId is null");
            checkObservationIds = List.copyOf(checkObservationIds);
            failedLogRefs = List.copyOf(failedLogRefs);
            requireNonNull(outputChangeSetRevisionId,
                    "outputChangeSetRevisionId is null");
            requireNonNull(outputHead, "outputHead is null");
            requireNonNull(repairAttemptId, "repairAttemptId is null");
            requireNonNull(repairResultId, "repairResultId is null");
            if ((cleanupId == null) != (cleanupResultId == null)) {
                throw new IllegalArgumentException(
                        "cleanup identity and result must be paired");
            }
        }
    }

    public record CiRepairAttempt(
            String attemptId,
            String roundId,
            String operationId,
            String agentRunId,
            String inputLocalHead,
            String inputRemoteHead,
            String inputChangeSetRevisionId,
            String outputLocalHead,
            String outputChangeSetRevisionId,
            List<String> localCheckRunIds,
            String resultRef,
            AttemptState state,
            String retryOfAttemptId,
            long retryOrdinal,
            Instant createdAt)
    {
        public CiRepairAttempt
        {
            requireNonNull(attemptId, "attemptId is null");
            requireNonNull(roundId, "roundId is null");
            requireNonNull(state, "state is null");
            boolean pending = state == AttemptState.PENDING;
            boolean hasOperation = operationId != null;
            boolean hasRun = agentRunId != null;
            if ((pending && (hasOperation || hasRun))
                    || (!pending && (!hasOperation || !hasRun))) {
                throw new IllegalArgumentException(
                        "only a pending attempt lacks operation and run");
            }
            requireNonNull(inputLocalHead, "inputLocalHead is null");
            requireNonNull(inputRemoteHead, "inputRemoteHead is null");
            requireNonNull(inputChangeSetRevisionId,
                    "inputChangeSetRevisionId is null");
            boolean cleanTerminal = state == AttemptState.FIX_PREPARED
                    || state == AttemptState.NO_HEAD_CHANGE;
            boolean hasCleanOutput = outputLocalHead != null
                    && outputChangeSetRevisionId != null;
            if (cleanTerminal != hasCleanOutput) {
                throw new IllegalArgumentException(
                        "only a clean terminal attempt has output");
            }
            if ((cleanTerminal || state == AttemptState.NON_CLEAN_HANDOFF)
                    != (resultRef != null)) {
                throw new IllegalArgumentException(
                        "only finalized repair work has a result");
            }
            if ((state == AttemptState.NO_HEAD_CHANGE
                        && !outputLocalHead.equals(inputLocalHead))
                    || (state == AttemptState.FIX_PREPARED
                        && outputLocalHead.equals(inputLocalHead))) {
                throw new IllegalArgumentException(
                        "CI repair outcome contradicts its objective head");
            }
            localCheckRunIds = List.copyOf(localCheckRunIds);
            requireNonNull(createdAt, "createdAt is null");
        }
    }

    /** Immutable exact dirty-state handoff to the one cleanup successor. */
    public record CiCleanupSeal(
            String cleanupId,
            String repairAttemptId,
            String successorOperationId,
            String actualHead,
            String branchHead,
            AttachmentState attachmentState,
            NonCleanKind kind,
            List<GitOperation> operations,
            String stateDigest,
            Instant createdAt)
    {
        public CiCleanupSeal
        {
            requireNonNull(cleanupId, "cleanupId is null");
            requireNonNull(repairAttemptId, "repairAttemptId is null");
            requireNonNull(successorOperationId,
                    "successorOperationId is null");
            requireNonNull(actualHead, "actualHead is null");
            requireNonNull(branchHead, "branchHead is null");
            requireNonNull(attachmentState, "attachmentState is null");
            requireNonNull(kind, "kind is null");
            operations = List.copyOf(operations);
            requireNonNull(stateDigest, "stateDigest is null");
            requireNonNull(createdAt, "createdAt is null");
        }
    }

    /** Immutable objective result of the one cleanup successor. */
    public record CiCleanupCompletion(
            String cleanupId,
            String runId,
            String resultRef,
            CleanupOutcome outcome,
            String outputHead,
            String outputChangeSetRevisionId,
            String finalActualHead,
            String finalBranchHead,
            AttachmentState finalAttachmentState,
            NonCleanKind finalKind,
            List<GitOperation> finalOperations,
            String finalStateDigest,
            CleanupAttentionReason attentionReason,
            FailureCode inspectionFailureCode,
            Instant completedAt)
    {
        public CiCleanupCompletion
        {
            requireNonNull(cleanupId, "cleanupId is null");
            requireNonNull(outcome, "outcome is null");
            boolean admissionBlocked = outcome
                    == CleanupOutcome.ADMISSION_BLOCKED;
            boolean attention = outcome == CleanupOutcome.NEEDS_ATTENTION;
            boolean hasRunResult = runId != null && resultRef != null;
            boolean anyRunIdentity = runId != null || resultRef != null;
            boolean hasOutput = outputHead != null
                    && outputChangeSetRevisionId != null;
            boolean hasFinalNonClean = finalActualHead != null
                    && finalBranchHead != null
                    && finalAttachmentState != null
                    && finalKind != null
                    && finalOperations != null
                    && finalStateDigest != null
                    && attentionReason != null;
            boolean anyFinalNonClean = finalActualHead != null
                    || finalBranchHead != null
                    || finalAttachmentState != null
                    || finalKind != null
                    || finalOperations != null
                    || finalStateDigest != null
                    || attentionReason != null
                    || inspectionFailureCode != null;
            boolean clean = outcome == CleanupOutcome.FIX_PREPARED
                    || outcome == CleanupOutcome.NO_HEAD_CHANGE;
            boolean secondNonClean = attention
                    && (attentionReason == CleanupAttentionReason.SECOND_DIRTY
                            || attentionReason == CleanupAttentionReason
                                    .SECOND_GIT_OPERATION_IN_PROGRESS);
            boolean inspectionBlocked = attention
                    && attentionReason == CleanupAttentionReason
                            .FINAL_INSPECTION_BLOCKED;
            boolean admissionMismatch = admissionBlocked
                    && attentionReason == CleanupAttentionReason
                            .ADMISSION_SEAL_MISMATCH;
            boolean admissionInspectionBlocked = admissionBlocked
                    && attentionReason == CleanupAttentionReason
                            .ADMISSION_INSPECTION_BLOCKED;
            boolean valid = clean && hasRunResult && hasOutput
                    && !anyFinalNonClean
                    || secondNonClean && hasRunResult && !hasOutput
                        && hasFinalNonClean && inspectionFailureCode == null
                    || inspectionBlocked && hasRunResult && !hasOutput
                        && !hasFinalNonClean && inspectionFailureCode != null
                    || admissionMismatch
                        && (hasRunResult || !anyRunIdentity) && !hasOutput
                        && hasFinalNonClean && inspectionFailureCode == null
                    || admissionInspectionBlocked
                        && (hasRunResult || !anyRunIdentity)
                        && !hasOutput && !hasFinalNonClean
                        && inspectionFailureCode != null;
            if (!valid) {
                throw new IllegalArgumentException(
                        "cleanup outcome has inconsistent objective evidence");
            }
            if (secondNonClean
                    && ((finalKind == NonCleanKind.DIRTY)
                            != (attentionReason
                                == CleanupAttentionReason.SECOND_DIRTY))) {
                throw new IllegalArgumentException(
                        "cleanup attention reason contradicts Git state");
            }
            finalOperations = hasFinalNonClean
                    ? List.copyOf(finalOperations)
                    : List.of();
            requireNonNull(completedAt, "completedAt is null");
        }
    }

    public record CiLogEvidence(
            String logRef,
            String observationId,
            String contentDigest,
            String exposedContentDigest,
            long rawByteCount,
            long storedByteCount,
            boolean truncated,
            Instant storedAt)
    {
        public CiLogEvidence
        {
            requireNonNull(logRef, "logRef is null");
            requireNonNull(observationId, "observationId is null");
            requireNonNull(contentDigest, "contentDigest is null");
            requireNonNull(exposedContentDigest,
                    "exposedContentDigest is null");
            requireNonNull(storedAt, "storedAt is null");
        }
    }

    public record CiLogWindow(
            String logRef,
            long offset,
            String content,
            long nextOffset,
            boolean endOfLog)
    {
        public CiLogWindow
        {
            requireNonNull(logRef, "logRef is null");
            requireNonNull(content, "content is null");
        }
    }

    public record QueuedRepair(
            CiRound round,
            String inboxId,
            String reconciliationOperationId,
            String terminalReason)
    {
        public QueuedRepair
        {
            requireNonNull(round, "round is null");
            requireNonNull(inboxId, "inboxId is null");
            if ((reconciliationOperationId == null)
                    == (terminalReason == null)) {
                throw new IllegalArgumentException(
                        "repair registration needs reconciliation or terminal reason");
            }
        }
    }

    /** Exact immutable evidence subject for one optional read-only learner. */
    public record CiLearningSubject(
            String subjectId,
            String operationId,
            String taskId,
            String prId,
            String repositoryId,
            String receiptId,
            String receiptDigest,
            String publicationOperationId,
            String headRepositoryExternalId,
            String headRepositoryOwner,
            String headRepositoryName,
            String branchRef,
            String expectedRemoteHead,
            String planId,
            String planDigest,
            String authorizationId,
            String gateId,
            long gateRevision,
            String gateSubjectDigest,
            String gateActionDigest,
            String publicationPolicyRevisionId,
            String publishedHead,
            String greenRoundId,
            String greenPolicyRevisionId,
            long greenEvidenceRevision,
            String greenObservationOperationId,
            List<String> greenObservationIds,
            List<String> greenObservationDigests,
            String redRoundId,
            String repairAttemptId,
            String repairResultId,
            String repairResultDigest,
            String cleanupId,
            String cleanupResultId,
            String cleanupResultDigest,
            String outputChangeSetRevisionId,
            String outputDiffDigest,
            List<String> failedLogRefs,
            List<String> failedLogDigests,
            String subjectDigest,
            Instant createdAt)
    {
        public CiLearningSubject
        {
            requireNonNull(subjectId, "subjectId is null");
            requireNonNull(operationId, "operationId is null");
            requireNonNull(taskId, "taskId is null");
            requireNonNull(prId, "prId is null");
            requireNonNull(repositoryId, "repositoryId is null");
            requireNonNull(receiptId, "receiptId is null");
            requireNonNull(receiptDigest, "receiptDigest is null");
            requireNonNull(publicationOperationId,
                    "publicationOperationId is null");
            requireNonNull(headRepositoryExternalId,
                    "headRepositoryExternalId is null");
            requireNonNull(headRepositoryOwner,
                    "headRepositoryOwner is null");
            requireNonNull(headRepositoryName,
                    "headRepositoryName is null");
            requireNonNull(branchRef, "branchRef is null");
            requireNonNull(expectedRemoteHead,
                    "expectedRemoteHead is null");
            requireNonNull(planId, "planId is null");
            requireNonNull(planDigest, "planDigest is null");
            requireNonNull(authorizationId, "authorizationId is null");
            requireNonNull(gateId, "gateId is null");
            requireNonNull(gateSubjectDigest,
                    "gateSubjectDigest is null");
            requireNonNull(gateActionDigest,
                    "gateActionDigest is null");
            requireNonNull(publicationPolicyRevisionId,
                    "publicationPolicyRevisionId is null");
            requireNonNull(publishedHead, "publishedHead is null");
            requireNonNull(greenRoundId, "greenRoundId is null");
            requireNonNull(greenPolicyRevisionId,
                    "greenPolicyRevisionId is null");
            requireNonNull(greenObservationOperationId,
                    "greenObservationOperationId is null");
            greenObservationIds = List.copyOf(greenObservationIds);
            greenObservationDigests = List.copyOf(
                    greenObservationDigests);
            if (greenObservationIds.isEmpty()) {
                throw new IllegalArgumentException(
                        "learning requires nonempty green observations");
            }
            if (greenObservationIds.size()
                    != greenObservationDigests.size()) {
                throw new IllegalArgumentException(
                        "green observation IDs/digests differ");
            }
            requireNonNull(redRoundId, "redRoundId is null");
            requireNonNull(repairAttemptId,
                    "repairAttemptId is null");
            requireNonNull(repairResultId, "repairResultId is null");
            requireNonNull(repairResultDigest,
                    "repairResultDigest is null");
            if ((cleanupId == null) != (cleanupResultId == null)) {
                throw new IllegalArgumentException(
                        "cleanup identity and result must be paired");
            }
            if ((cleanupResultId == null)
                    != (cleanupResultDigest == null)) {
                throw new IllegalArgumentException(
                        "cleanup result identity/digest must be paired");
            }
            requireNonNull(outputChangeSetRevisionId,
                    "outputChangeSetRevisionId is null");
            requireNonNull(outputDiffDigest,
                    "outputDiffDigest is null");
            failedLogRefs = List.copyOf(failedLogRefs);
            failedLogDigests = List.copyOf(failedLogDigests);
            if (failedLogRefs.isEmpty()) {
                throw new IllegalArgumentException(
                        "learning requires the originating failed logs");
            }
            if (failedLogRefs.size() != failedLogDigests.size()) {
                throw new IllegalArgumentException(
                        "failed log refs/digests differ");
            }
            requireNonNull(subjectDigest, "subjectDigest is null");
            requireNonNull(createdAt, "createdAt is null");
        }
    }

    public enum LearningCompletionState
    {
        CANDIDATE,
        MISSED
    }

    /** Opaque candidate only; it grants no deterministic routing authority. */
    public record CiLesson(
            String lessonId,
            String repositoryId,
            String learningOperationId,
            String runId,
            String subjectId,
            String title,
            String markdown,
            String contentDigest,
            Instant createdAt)
    {
        public CiLesson
        {
            requireNonNull(lessonId, "lessonId is null");
            requireNonNull(repositoryId, "repositoryId is null");
            requireNonNull(learningOperationId,
                    "learningOperationId is null");
            requireNonNull(runId, "runId is null");
            requireNonNull(subjectId, "subjectId is null");
            requireNonNull(title, "title is null");
            requireNonNull(markdown, "markdown is null");
            requireNonNull(contentDigest, "contentDigest is null");
            requireNonNull(createdAt, "createdAt is null");
        }
    }

    public record CiLearningCompletion(
            String operationId,
            String runId,
            String resultId,
            LearningCompletionState state,
            String lessonId,
            String reasonCode,
            Instant completedAt)
    {
        public CiLearningCompletion
        {
            requireNonNull(operationId, "operationId is null");
            requireNonNull(runId, "runId is null");
            requireNonNull(resultId, "resultId is null");
            requireNonNull(state, "state is null");
            requireNonNull(reasonCode, "reasonCode is null");
            requireNonNull(completedAt, "completedAt is null");
            if ((state == LearningCompletionState.CANDIDATE)
                    != (lessonId != null)) {
                throw new IllegalArgumentException(
                        "only a candidate completion has a lesson");
            }
        }
    }

    public sealed interface FinalizeHeadResult
            permits FinalizedRound, FinalizeBlocked {}

    public record FinalizedRound(CiRound round, boolean newlyFinal)
            implements FinalizeHeadResult
    {
        public FinalizedRound
        {
            requireNonNull(round, "round is null");
        }
    }

    public record FinalizeBlocked(FinalizeBlocker blocker, String detail)
            implements FinalizeHeadResult
    {
        public FinalizeBlocked
        {
            requireNonNull(blocker, "blocker is null");
            requireNonNull(detail, "detail is null");
        }
    }

    /**
     * Read-only acceptance snapshot. This is deliberately not gate authority:
     * the future PR owner must freeze the subject and CI evidence in one
     * transaction before an {@code AcceptedCiEvidence} can exist.
     */
    public record AcceptedCiSnapshot(
            String prId,
            String headSha,
            String policyRevisionId,
            String roundId,
            List<String> observationIds)
    {
        public AcceptedCiSnapshot
        {
            requireNonNull(prId, "prId is null");
            requireNonNull(headSha, "headSha is null");
            requireNonNull(policyRevisionId, "policyRevisionId is null");
            requireNonNull(roundId, "roundId is null");
            observationIds = List.copyOf(observationIds);
        }
    }
}
