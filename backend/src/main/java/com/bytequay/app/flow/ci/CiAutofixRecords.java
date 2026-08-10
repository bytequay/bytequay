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
import com.bytequay.app.flow.runtime.FlowWorktreeInspector.GitOperation;
import com.bytequay.app.flow.runtime.FlowWorktreeInspector.NonCleanKind;

import java.time.Instant;
import java.util.List;

import static java.util.Objects.requireNonNull;

/** Program-owned records for the greenfield CI Autofix component. */
public final class CiAutofixRecords
{
    private CiAutofixRecords() {}

    public enum PolicyResolution
    {
        RESOLVED,
        UNAVAILABLE
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
        CLEANUP_PENDING,
        FIX_PREPARED,
        NO_HEAD_CHANGE,
        NEEDS_ATTENTION
    }

    public enum FinalizeBlocker
    {
        CI_POLICY_MISSING,
        CI_POLICY_UNAVAILABLE,
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
            NormalizedCheck check)
    {
        public CiCheckObservation
        {
            requireNonNull(observationId, "observationId is null");
            requireNonNull(prId, "prId is null");
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
            checkObservationIds = List.copyOf(checkObservationIds);
            failedLogRefs = List.copyOf(failedLogRefs);
            requireNonNull(state, "state is null");
            requireNonNull(createdAt, "createdAt is null");
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
            if ((cleanTerminal || state == AttemptState.CLEANUP_PENDING)
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
