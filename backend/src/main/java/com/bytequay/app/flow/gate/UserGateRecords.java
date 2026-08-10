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
package com.bytequay.app.flow.gate;

import com.bytequay.app.flow.runtime.FlowRuntimeRecords.LocalCheckConclusion;

import java.time.Instant;
import java.util.List;

import static java.util.Objects.requireNonNull;

/** Immutable records for the implemented local CI_UPDATE gate boundary. */
public final class UserGateRecords
{
    private UserGateRecords() {}

    public enum GateKind
    {
        CI_UPDATE
    }

    public enum GateState
    {
        OPEN,
        STALE
    }

    public record LocalCheckBinding(
            String checkRunId,
            String profileId,
            LocalCheckConclusion conclusion)
    {
        public LocalCheckBinding
        {
            requireNonNull(checkRunId, "checkRunId is null");
            requireNonNull(profileId, "profileId is null");
            requireNonNull(conclusion, "conclusion is null");
        }
    }

    public record CodePublicationReviewBinding(
            String candidateChangeSetRevisionId,
            boolean ownerPresent,
            List<String> batchIds,
            List<String> latestRevisionIds,
            String digest)
    {
        public CodePublicationReviewBinding
        {
            requireNonNull(candidateChangeSetRevisionId,
                    "candidateChangeSetRevisionId is null");
            batchIds = List.copyOf(batchIds);
            latestRevisionIds = List.copyOf(latestRevisionIds);
            requireNonNull(digest, "digest is null");
            if (ownerPresent || !batchIds.isEmpty()
                    || !latestRevisionIds.isEmpty()) {
                throw new IllegalArgumentException(
                        "local-review owner is not implemented");
            }
        }
    }

    public record GateSubject(
            String subjectId,
            String taskId,
            String prId,
            String repositoryId,
            String branchRef,
            String expectedRemoteHead,
            String changeSetRevisionId,
            String baseRevisionId,
            String baseSha,
            String proposedHead,
            String headTreeDigest,
            String diffDigest,
            String localCheckPolicyRevisionId,
            List<LocalCheckBinding> localChecks,
            String reviewerRequestId,
            String reviewerRunId,
            String reviewerResultId,
            String originCiFixPendingId,
            String originCiFixSourceKind,
            String originCiFixSourceId,
            String ciRoundId,
            String requiredCiPolicyRevisionId,
            long ciEvidenceRevision,
            List<String> ciObservationIds,
            List<String> failedLogRefs,
            String repairAttemptId,
            String repairResultId,
            String cleanupId,
            String cleanupResultId,
            List<String> ciMemoryRefs,
            CodePublicationReviewBinding localReview,
            boolean manualOnly,
            List<String> warningCodes,
            String subjectDigest,
            String createdByRunId,
            Instant createdAt)
    {
        public GateSubject
        {
            requireNonNull(subjectId, "subjectId is null");
            requireNonNull(taskId, "taskId is null");
            requireNonNull(prId, "prId is null");
            requireNonNull(repositoryId, "repositoryId is null");
            requireNonNull(branchRef, "branchRef is null");
            requireNonNull(expectedRemoteHead,
                    "expectedRemoteHead is null");
            requireNonNull(changeSetRevisionId,
                    "changeSetRevisionId is null");
            requireNonNull(baseRevisionId, "baseRevisionId is null");
            requireNonNull(baseSha, "baseSha is null");
            requireNonNull(proposedHead, "proposedHead is null");
            requireNonNull(headTreeDigest, "headTreeDigest is null");
            requireNonNull(diffDigest, "diffDigest is null");
            requireNonNull(localCheckPolicyRevisionId,
                    "localCheckPolicyRevisionId is null");
            localChecks = List.copyOf(localChecks);
            requireNonNull(reviewerRequestId,
                    "reviewerRequestId is null");
            requireNonNull(reviewerRunId, "reviewerRunId is null");
            requireNonNull(reviewerResultId,
                    "reviewerResultId is null");
            requireNonNull(originCiFixPendingId,
                    "originCiFixPendingId is null");
            requireNonNull(originCiFixSourceKind,
                    "originCiFixSourceKind is null");
            requireNonNull(originCiFixSourceId,
                    "originCiFixSourceId is null");
            requireNonNull(ciRoundId, "ciRoundId is null");
            requireNonNull(requiredCiPolicyRevisionId,
                    "requiredCiPolicyRevisionId is null");
            ciObservationIds = List.copyOf(ciObservationIds);
            failedLogRefs = List.copyOf(failedLogRefs);
            requireNonNull(repairAttemptId,
                    "repairAttemptId is null");
            requireNonNull(repairResultId,
                    "repairResultId is null");
            if ((cleanupId == null) != (cleanupResultId == null)) {
                throw new IllegalArgumentException(
                        "cleanup identity and result must be paired");
            }
            ciMemoryRefs = List.copyOf(ciMemoryRefs);
            if (!ciMemoryRefs.isEmpty()) {
                throw new IllegalArgumentException(
                        "CI memory owner is not implemented");
            }
            requireNonNull(localReview, "localReview is null");
            warningCodes = List.copyOf(warningCodes);
            requireNonNull(subjectDigest, "subjectDigest is null");
            requireNonNull(createdByRunId,
                    "createdByRunId is null");
            requireNonNull(createdAt, "createdAt is null");
        }
    }

    public record CiUpdateAction(
            String actionRef,
            String branchRef,
            String expectedRemoteHead,
            String proposedHead,
            boolean forcePush,
            String actionDigest,
            Instant createdAt)
    {
        public CiUpdateAction
        {
            requireNonNull(actionRef, "actionRef is null");
            requireNonNull(branchRef, "branchRef is null");
            requireNonNull(expectedRemoteHead,
                    "expectedRemoteHead is null");
            requireNonNull(proposedHead, "proposedHead is null");
            requireNonNull(actionDigest, "actionDigest is null");
            requireNonNull(createdAt, "createdAt is null");
            if (forcePush) {
                throw new IllegalArgumentException(
                        "CI_UPDATE cannot force push");
            }
        }
    }

    public record UserGate(
            String gateId,
            String taskId,
            String prId,
            GateKind kind,
            long currentRevision,
            Instant createdAt)
    {
        public UserGate
        {
            requireNonNull(gateId, "gateId is null");
            requireNonNull(taskId, "taskId is null");
            requireNonNull(prId, "prId is null");
            requireNonNull(kind, "kind is null");
            requireNonNull(createdAt, "createdAt is null");
        }
    }

    public record GateRevision(
            String gateId,
            long revision,
            String subjectManifestRef,
            String subjectDigest,
            String actionManifestRef,
            String actionDigest,
            String readinessEvidenceRef,
            String createdByRunId,
            Instant createdAt)
    {
        public GateRevision
        {
            requireNonNull(gateId, "gateId is null");
            requireNonNull(subjectManifestRef,
                    "subjectManifestRef is null");
            requireNonNull(subjectDigest, "subjectDigest is null");
            requireNonNull(actionManifestRef,
                    "actionManifestRef is null");
            requireNonNull(actionDigest, "actionDigest is null");
            requireNonNull(readinessEvidenceRef,
                    "readinessEvidenceRef is null");
            requireNonNull(createdByRunId,
                    "createdByRunId is null");
            requireNonNull(createdAt, "createdAt is null");
        }
    }

    public record GateTransition(
            String gateId,
            long gateRevision,
            long sequence,
            GateState fromState,
            GateState toState,
            String reasonCode,
            String detailRef,
            Instant recordedAt)
    {
        public GateTransition
        {
            requireNonNull(gateId, "gateId is null");
            requireNonNull(toState, "toState is null");
            requireNonNull(reasonCode, "reasonCode is null");
            requireNonNull(recordedAt, "recordedAt is null");
        }
    }

    public record ReadyForReviewAcceptance(String status)
    {
        public ReadyForReviewAcceptance
        {
            if (!"ACCEPTED_SEALED".equals(status)) {
                throw new IllegalArgumentException(
                        "unsupported ready acceptance");
            }
        }
    }
}
