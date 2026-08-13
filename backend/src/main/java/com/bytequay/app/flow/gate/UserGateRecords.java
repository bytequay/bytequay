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
        INITIAL_PUBLISH,
        CI_UPDATE
    }

    public enum GateState
    {
        OPEN,
        AUTHORIZED,
        EXECUTING,
        NEEDS_ATTENTION,
        CONSUMED,
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

    /** Existence proves the exact candidate has no private review items. */
    public record LocalReviewBinding(
            String bindingId,
            String prId,
            String candidateChangeSetRevisionId,
            String digest,
            Instant createdAt)
    {
        public LocalReviewBinding
        {
            requireNonNull(bindingId, "bindingId is null");
            requireNonNull(prId, "prId is null");
            requireNonNull(candidateChangeSetRevisionId,
                    "candidateChangeSetRevisionId is null");
            requireNonNull(digest, "digest is null");
            requireNonNull(createdAt, "createdAt is null");
        }
    }

    public record CodePublicationReviewBinding(
            String candidateChangeSetRevisionId,
            String bindingId,
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
            if (!batchIds.isEmpty() || !latestRevisionIds.isEmpty()) {
                throw new IllegalArgumentException(
                        "local-review items are not implemented");
            }
            if (ownerPresent != (bindingId != null)) {
                throw new IllegalArgumentException(
                        "local-review owner and binding must agree");
            }
        }
    }

    public record GateSubject(
            String subjectId,
            String taskId,
            String prId,
            String repositoryId,
            String headRepositoryExternalId,
            String headRepositoryOwner,
            String headRepositoryName,
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
            requireNonNull(headRepositoryExternalId,
                    "headRepositoryExternalId is null");
            requireNonNull(headRepositoryOwner,
                    "headRepositoryOwner is null");
            requireNonNull(headRepositoryName,
                    "headRepositoryName is null");
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
            String headRepositoryExternalId,
            String headRepositoryOwner,
            String headRepositoryName,
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
            requireNonNull(headRepositoryExternalId,
                    "headRepositoryExternalId is null");
            requireNonNull(headRepositoryOwner,
                    "headRepositoryOwner is null");
            requireNonNull(headRepositoryName,
                    "headRepositoryName is null");
            requireNonNull(branchRef, "branchRef is null");
            requireNonNull(expectedRemoteHead,
                    "expectedRemoteHead is null");
            requireNonNull(proposedHead, "proposedHead is null");
            requireNonNull(actionDigest, "actionDigest is null");
            requireNonNull(createdAt, "createdAt is null");
        }
    }

    /** Program-built, unpublished-PR subject for a first GitHub publication. */
    public record InitialPublishSubject(
            String subjectId,
            String taskId,
            String prId,
            String repositoryId,
            String launchDigest,
            String changeSetRevisionId,
            String baseRevisionId,
            String expectedBaseSha,
            String proposedHead,
            String headTreeDigest,
            String diffDigest,
            String draftRevisionId,
            String draftDigest,
            String requiredCiPolicyRevisionId,
            String localCheckPolicyRevisionId,
            List<LocalCheckBinding> localChecks,
            String reviewerRequestId,
            String reviewerRunId,
            String reviewerResultId,
            CodePublicationReviewBinding localReview,
            String baseRepositoryExternalId,
            String baseRepositoryOwner,
            String baseRepositoryName,
            String headRepositoryExternalId,
            String headRepositoryOwner,
            String headRepositoryName,
            String branchRef,
            String targetBaseRef,
            String targetSnapshotId,
            String targetSnapshotDigest,
            InitialPublishVerificationProvider.Verification ownerVerification,
            String subjectDigest,
            String createdByRunId,
            Instant createdAt)
    {
        public InitialPublishSubject
        {
            requireNonNull(subjectId, "subjectId is null");
            requireNonNull(taskId, "taskId is null");
            requireNonNull(prId, "prId is null");
            requireNonNull(repositoryId, "repositoryId is null");
            requireNonNull(launchDigest, "launchDigest is null");
            requireNonNull(changeSetRevisionId, "changeSetRevisionId is null");
            requireNonNull(baseRevisionId, "baseRevisionId is null");
            requireNonNull(expectedBaseSha, "expectedBaseSha is null");
            requireNonNull(proposedHead, "proposedHead is null");
            requireNonNull(headTreeDigest, "headTreeDigest is null");
            requireNonNull(diffDigest, "diffDigest is null");
            requireNonNull(draftRevisionId, "draftRevisionId is null");
            requireNonNull(draftDigest, "draftDigest is null");
            requireNonNull(requiredCiPolicyRevisionId,
                    "requiredCiPolicyRevisionId is null");
            requireNonNull(localCheckPolicyRevisionId,
                    "localCheckPolicyRevisionId is null");
            localChecks = List.copyOf(localChecks);
            requireNonNull(reviewerRequestId, "reviewerRequestId is null");
            requireNonNull(reviewerRunId, "reviewerRunId is null");
            requireNonNull(reviewerResultId, "reviewerResultId is null");
            requireNonNull(localReview, "localReview is null");
            requireNonNull(baseRepositoryExternalId,
                    "baseRepositoryExternalId is null");
            requireNonNull(baseRepositoryOwner, "baseRepositoryOwner is null");
            requireNonNull(baseRepositoryName, "baseRepositoryName is null");
            requireNonNull(headRepositoryExternalId,
                    "headRepositoryExternalId is null");
            requireNonNull(headRepositoryOwner, "headRepositoryOwner is null");
            requireNonNull(headRepositoryName, "headRepositoryName is null");
            requireNonNull(branchRef, "branchRef is null");
            requireNonNull(targetBaseRef, "targetBaseRef is null");
            requireNonNull(targetSnapshotId, "targetSnapshotId is null");
            requireNonNull(targetSnapshotDigest,
                    "targetSnapshotDigest is null");
            if (ownerVerification != null
                    && (!ownerVerification.taskId().equals(taskId)
                        || !ownerVerification.expectedBaseSha().equals(
                                expectedBaseSha)
                        || !ownerVerification.proposedHead().equals(
                                proposedHead))) {
                throw new IllegalArgumentException(
                        "initial owner verification is for another subject");
            }
            requireNonNull(subjectDigest, "subjectDigest is null");
            requireNonNull(createdByRunId, "createdByRunId is null");
            requireNonNull(createdAt, "createdAt is null");
            if (!branchRef.startsWith("refs/heads/")
                    || !localReview.ownerPresent()
                    || !localReview.candidateChangeSetRevisionId().equals(
                            changeSetRevisionId)) {
                throw new IllegalArgumentException(
                        "initial publication subject is invalid");
            }
        }
    }

    /** The only currently supported first-publication action: exact draft PR. */
    public record InitialPublishAction(
            String actionRef,
            String actionDigest,
            String prId,
            String changeSetRevisionId,
            String baseRepositoryExternalId,
            String baseRepositoryOwner,
            String baseRepositoryName,
            String headRepositoryExternalId,
            String headRepositoryOwner,
            String headRepositoryName,
            String branchRef,
            String targetBaseRef,
            String expectedBaseSha,
            String proposedHead,
            String draftRevisionId,
            String draftDigest,
            String requiredCiPolicyRevisionId,
            String readyPolicy,
            String targetSnapshotId,
            String targetSnapshotDigest,
            Instant createdAt)
    {
        public InitialPublishAction
        {
            requireNonNull(actionRef, "actionRef is null");
            requireNonNull(actionDigest, "actionDigest is null");
            requireNonNull(prId, "prId is null");
            requireNonNull(changeSetRevisionId, "changeSetRevisionId is null");
            requireNonNull(baseRepositoryExternalId,
                    "baseRepositoryExternalId is null");
            requireNonNull(baseRepositoryOwner, "baseRepositoryOwner is null");
            requireNonNull(baseRepositoryName, "baseRepositoryName is null");
            requireNonNull(headRepositoryExternalId,
                    "headRepositoryExternalId is null");
            requireNonNull(headRepositoryOwner, "headRepositoryOwner is null");
            requireNonNull(headRepositoryName, "headRepositoryName is null");
            requireNonNull(branchRef, "branchRef is null");
            requireNonNull(targetBaseRef, "targetBaseRef is null");
            requireNonNull(expectedBaseSha, "expectedBaseSha is null");
            requireNonNull(proposedHead, "proposedHead is null");
            requireNonNull(draftRevisionId, "draftRevisionId is null");
            requireNonNull(draftDigest, "draftDigest is null");
            requireNonNull(requiredCiPolicyRevisionId,
                    "requiredCiPolicyRevisionId is null");
            requireNonNull(readyPolicy, "readyPolicy is null");
            requireNonNull(targetSnapshotId, "targetSnapshotId is null");
            requireNonNull(targetSnapshotDigest,
                    "targetSnapshotDigest is null");
            requireNonNull(createdAt, "createdAt is null");
            if (!readyPolicy.equals("KEEP_DRAFT")
                    && !readyPolicy.equals("MARK_READY_ON_EXACT_GREEN")) {
                throw new IllegalArgumentException("ready policy is invalid");
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
            requireNonNull(createdByRunId,
                    "createdByRunId is null");
            requireNonNull(createdAt, "createdAt is null");
        }
    }

    /** One immutable revision of the one-shot Task CI_UPDATE consent. */
    public record CiUpdateConsentRevision(
            String consentId,
            long revision,
            String taskId,
            String prId,
            String repositoryId,
            String remoteIdentityId,
            String headRepositoryExternalId,
            String headRepositoryOwner,
            String headRepositoryName,
            String branchName,
            String branchRef,
            boolean enabled,
            Instant expiresAt,
            String actorId,
            String idempotencyKey,
            String revisionDigest,
            Instant recordedAt)
    {
        public CiUpdateConsentRevision
        {
            requireNonNull(consentId, "consentId is null");
            requireNonNull(taskId, "taskId is null");
            requireNonNull(prId, "prId is null");
            requireNonNull(repositoryId, "repositoryId is null");
            requireNonNull(remoteIdentityId, "remoteIdentityId is null");
            requireNonNull(headRepositoryExternalId,
                    "headRepositoryExternalId is null");
            requireNonNull(headRepositoryOwner,
                    "headRepositoryOwner is null");
            requireNonNull(headRepositoryName,
                    "headRepositoryName is null");
            requireNonNull(branchName, "branchName is null");
            requireNonNull(branchRef, "branchRef is null");
            requireNonNull(expiresAt, "expiresAt is null");
            requireNonNull(actorId, "actorId is null");
            requireNonNull(idempotencyKey, "idempotencyKey is null");
            requireNonNull(revisionDigest, "revisionDigest is null");
            requireNonNull(recordedAt, "recordedAt is null");
            if (revision < 1
                    || !actorId.equals("LOCAL_DESKTOP_USER")
                    || !branchRef.equals("refs/heads/" + branchName)) {
                throw new IllegalArgumentException(
                        "CI_UPDATE consent identity is invalid");
            }
        }
    }

    public record GateAuthorization(
            String authorizationId,
            String gateId,
            long gateRevision,
            String prId,
            String subjectDigest,
            String actionDigest,
            String authority,
            String actorId,
            String consentId,
            Long consentRevision,
            String consentDigest,
            String idempotencyKey,
            String operationId,
            String effectPlanRef,
            Instant authorizedAt)
    {
        public GateAuthorization
        {
            requireNonNull(authorizationId,
                    "authorizationId is null");
            requireNonNull(gateId, "gateId is null");
            requireNonNull(prId, "prId is null");
            requireNonNull(subjectDigest, "subjectDigest is null");
            requireNonNull(actionDigest, "actionDigest is null");
            requireNonNull(authority, "authority is null");
            requireNonNull(actorId, "actorId is null");
            requireNonNull(idempotencyKey, "idempotencyKey is null");
            requireNonNull(operationId, "operationId is null");
            requireNonNull(effectPlanRef, "effectPlanRef is null");
            requireNonNull(authorizedAt, "authorizedAt is null");
            boolean manual = authority.equals("USER")
                    && actorId.equals("LOCAL_DESKTOP_USER")
                    && consentId == null
                    && consentRevision == null
                    && consentDigest == null;
            boolean automatic = authority.equals("CI_UPDATE_CONSENT")
                    && actorId.equals("USER_GATES_CI_CONSENT")
                    && consentId != null
                    && consentRevision != null
                    && consentRevision > 0
                    && consentDigest != null;
            if (gateRevision < 1 || !manual && !automatic) {
                throw new IllegalArgumentException(
                        "authorization identity is invalid");
            }
        }
    }

    public record AuthorizedCiUpdate(
            GateAuthorization authorization,
            String planId,
            String operationId,
            long prSequence)
    {
        public AuthorizedCiUpdate
        {
            requireNonNull(authorization, "authorization is null");
            requireNonNull(planId, "planId is null");
            requireNonNull(operationId, "operationId is null");
            if (prSequence < 1) {
                throw new IllegalArgumentException(
                        "prSequence must be positive");
            }
        }
    }

    public record AuthorizedInitialPublish(
            GateAuthorization authorization,
            String planId,
            String operationId,
            long prSequence)
    {
        public AuthorizedInitialPublish
        {
            requireNonNull(authorization, "authorization is null");
            requireNonNull(planId, "planId is null");
            requireNonNull(operationId, "operationId is null");
            if (prSequence < 1) {
                throw new IllegalArgumentException("prSequence must be positive");
            }
        }
    }

    public record CiUpdateEffectActivation(
            String authorizationId,
            String planId,
            String operationId,
            String prId,
            long prSequence,
            String repositoryRoot,
            String headRepositoryExternalId,
            String headRepositoryOwner,
            String headRepositoryName,
            String branchRef,
            String expectedRemoteHead,
            String proposedHead,
            boolean forcePush,
            boolean mutationAllowed,
            String planDigest)
    {
        public CiUpdateEffectActivation
        {
            requireNonNull(authorizationId,
                    "authorizationId is null");
            requireNonNull(planId, "planId is null");
            requireNonNull(operationId, "operationId is null");
            requireNonNull(prId, "prId is null");
            requireNonNull(repositoryRoot, "repositoryRoot is null");
            requireNonNull(headRepositoryExternalId,
                    "headRepositoryExternalId is null");
            requireNonNull(headRepositoryOwner,
                    "headRepositoryOwner is null");
            requireNonNull(headRepositoryName,
                    "headRepositoryName is null");
            requireNonNull(branchRef, "branchRef is null");
            requireNonNull(expectedRemoteHead,
                    "expectedRemoteHead is null");
            requireNonNull(proposedHead, "proposedHead is null");
            requireNonNull(planDigest, "planDigest is null");
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
