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
package com.bytequay.app.flow.github;

import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Claim;

import java.time.Instant;

import static java.util.Objects.requireNonNull;

/** Immutable evidence owned by the bounded INITIAL_PUBLISH effect. */
public final class InitialPublishRecords
{
    private InitialPublishRecords() {}

    public enum StepKind
    {
        CREATE_REF_EXACT,
        CREATE_DRAFT_PR
    }

    public enum Outcome
    {
        APPLIED,
        ABSENT,
        DIVERGED,
        UNKNOWN
    }

    /** A provider-owned, non-constructible proof of one exact remote read. */
    public sealed interface ProviderProof
            permits GitHubProvider.ExactInitialPublishProof
    {
        String operationId();
        String planId();
        String stepId();
        String attemptId();
        int stepOrdinal();
        StepKind stepKind();
        Outcome outcome();
        String observedHead();
        PrIdentity prIdentity();
        boolean matchesClaim(Claim claim);
    }

    /** A provider-owned, non-constructible exact failure capability. */
    public sealed interface ProviderFailure
            permits GitHubProvider.ExactInitialFailure
    {
        boolean matches(Claim claim, String planId, String stepId,
                String attemptId);
        boolean invalid();
        boolean baseDrift();
        String observedBaseSha();
    }

    /** GitHub-owner capability consumed by the cross-owner final transaction. */
    public sealed interface Settlement
            permits GitHubEffects.StoredInitialFinalReceipt,
                    GitHubEffects.StoredInitialPartialReceipt
    {
        String resultId();
        String operationId();
        String planId();
        String proposedHead();
        boolean succeeded();
        boolean bindsIdentity();
        String attentionReason();
    }

    public record FinalReceipt(
            String receiptId,
            String operationId,
            String planId,
            String branchReceiptId,
            String prStepReceiptId,
            String proposedHead,
            long prNumber,
            String prNodeId,
            String htmlUrl,
            String observedBaseSha,
            String receiptDigest,
            Instant recordedAt)
    {
        public FinalReceipt
        {
            requireNonNull(receiptId, "receiptId is null");
            requireNonNull(operationId, "operationId is null");
            requireNonNull(planId, "planId is null");
            requireNonNull(branchReceiptId, "branchReceiptId is null");
            requireNonNull(prStepReceiptId, "prStepReceiptId is null");
            requireNonNull(proposedHead, "proposedHead is null");
            requireNonNull(prNodeId, "prNodeId is null");
            requireNonNull(htmlUrl, "htmlUrl is null");
            requireNonNull(observedBaseSha, "observedBaseSha is null");
            requireNonNull(receiptDigest, "receiptDigest is null");
            requireNonNull(recordedAt, "recordedAt is null");
            if (prNumber < 1) {
                throw new IllegalArgumentException("prNumber must be positive");
            }
        }
    }

    /** Fresh authenticated repository identity used to freeze an INITIAL gate. */
    public sealed interface RepositoryObservation
            permits GitHubProvider.ExactInitialRepositoryObservation
    {
        String repositoryExternalId();
        String owner();
        String name();
        boolean consumeMatches(String runId, String taskId, String repositoryId,
                String launchDigest, String owner, String name);
    }

    public record TargetSnapshot(
            String targetSnapshotId,
            String taskId,
            String prId,
            String repositoryId,
            String launchDigest,
            String baseRepositoryExternalId,
            String baseRepositoryOwner,
            String baseRepositoryName,
            String headRepositoryExternalId,
            String headRepositoryOwner,
            String headRepositoryName,
            String headBranchName,
            String branchRef,
            String targetBaseRef,
            String expectedBaseSha,
            String proposedHead,
            String requiredCiPolicyRevisionId,
            String targetSnapshotDigest,
            Instant observedAt)
    {
        public TargetSnapshot
        {
            requireNonNull(targetSnapshotId, "targetSnapshotId is null");
            requireNonNull(taskId, "taskId is null");
            requireNonNull(prId, "prId is null");
            requireNonNull(repositoryId, "repositoryId is null");
            requireNonNull(launchDigest, "launchDigest is null");
            requireNonNull(baseRepositoryExternalId,
                    "baseRepositoryExternalId is null");
            requireNonNull(baseRepositoryOwner, "baseRepositoryOwner is null");
            requireNonNull(baseRepositoryName, "baseRepositoryName is null");
            requireNonNull(headRepositoryExternalId,
                    "headRepositoryExternalId is null");
            requireNonNull(headRepositoryOwner, "headRepositoryOwner is null");
            requireNonNull(headRepositoryName, "headRepositoryName is null");
            requireNonNull(headBranchName, "headBranchName is null");
            requireNonNull(branchRef, "branchRef is null");
            requireNonNull(targetBaseRef, "targetBaseRef is null");
            requireNonNull(expectedBaseSha, "expectedBaseSha is null");
            requireNonNull(proposedHead, "proposedHead is null");
            requireNonNull(requiredCiPolicyRevisionId,
                    "requiredCiPolicyRevisionId is null");
            requireNonNull(targetSnapshotDigest,
                    "targetSnapshotDigest is null");
            requireNonNull(observedAt, "observedAt is null");
        }
    }

    public record Plan(
            String planId,
            String operationId,
            String authorizationId,
            String prId,
            long prSequence,
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
            String changeSetRevisionId,
            String draftRevisionId,
            String draftDigest,
            String requiredCiPolicyRevisionId,
            String readyPolicy,
            String targetSnapshotId,
            String targetSnapshotDigest,
            String actionRef,
            String actionDigest,
            String planDigest,
            Instant createdAt)
    {
        public Plan
        {
            requireNonNull(planId, "planId is null");
            requireNonNull(operationId, "operationId is null");
            requireNonNull(authorizationId, "authorizationId is null");
            requireNonNull(prId, "prId is null");
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
            requireNonNull(changeSetRevisionId, "changeSetRevisionId is null");
            requireNonNull(draftRevisionId, "draftRevisionId is null");
            requireNonNull(draftDigest, "draftDigest is null");
            requireNonNull(requiredCiPolicyRevisionId,
                    "requiredCiPolicyRevisionId is null");
            requireNonNull(readyPolicy, "readyPolicy is null");
            requireNonNull(targetSnapshotId, "targetSnapshotId is null");
            requireNonNull(targetSnapshotDigest,
                    "targetSnapshotDigest is null");
            requireNonNull(actionRef, "actionRef is null");
            requireNonNull(actionDigest, "actionDigest is null");
            requireNonNull(planDigest, "planDigest is null");
            requireNonNull(createdAt, "createdAt is null");
            if (prSequence < 1) {
                throw new IllegalArgumentException("prSequence must be positive");
            }
        }
    }

    public record Step(
            String stepId,
            String planId,
            int ordinal,
            StepKind kind,
            String stepDigest)
    {
        public Step
        {
            requireNonNull(stepId, "stepId is null");
            requireNonNull(planId, "planId is null");
            requireNonNull(kind, "kind is null");
            requireNonNull(stepDigest, "stepDigest is null");
            if (ordinal != kind.ordinal() + 1) {
                throw new IllegalArgumentException("initial step order is invalid");
            }
        }
    }

    public record Attempt(
            String attemptId,
            String operationId,
            String planId,
            String stepId,
            int stepOrdinal,
            StepKind stepKind,
            int attemptNumber,
            long claimGeneration,
            String claimTokenDigest,
            String planDigest,
            String stepDigest,
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
            String changeSetRevisionId,
            String draftRevisionId,
            String draftDigest,
            String targetSnapshotId,
            String targetSnapshotDigest,
            String actionRef,
            String actionDigest,
            String requestDigest,
            String executionTokenDigest,
            Instant activatedAt)
    {
        public Attempt
        {
            requireNonNull(attemptId, "attemptId is null");
            requireNonNull(operationId, "operationId is null");
            requireNonNull(planId, "planId is null");
            requireNonNull(stepId, "stepId is null");
            requireNonNull(stepKind, "stepKind is null");
            requireNonNull(claimTokenDigest, "claimTokenDigest is null");
            requireNonNull(planDigest, "planDigest is null");
            requireNonNull(stepDigest, "stepDigest is null");
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
            requireNonNull(changeSetRevisionId, "changeSetRevisionId is null");
            requireNonNull(draftRevisionId, "draftRevisionId is null");
            requireNonNull(draftDigest, "draftDigest is null");
            requireNonNull(targetSnapshotId, "targetSnapshotId is null");
            requireNonNull(targetSnapshotDigest,
                    "targetSnapshotDigest is null");
            requireNonNull(actionRef, "actionRef is null");
            requireNonNull(actionDigest, "actionDigest is null");
            requireNonNull(requestDigest, "requestDigest is null");
            requireNonNull(executionTokenDigest, "executionTokenDigest is null");
            requireNonNull(activatedAt, "activatedAt is null");
            if (stepOrdinal != stepKind.ordinal() + 1
                    || attemptNumber < 1 || attemptNumber > 2
                    || claimGeneration < 1) {
                throw new IllegalArgumentException("initial attempt is invalid");
            }
        }
    }

    public record PrIdentity(
            String state,
            boolean draft,
            String baseRepositoryExternalId,
            String baseRepositoryOwner,
            String baseRepositoryName,
            String headRepositoryExternalId,
            String headRepositoryOwner,
            String headRepositoryName,
            String headBranchRef,
            String targetBaseRef,
            long prNumber,
            String prNodeId,
            String htmlUrl,
            String observedBaseSha,
            String titleDigest,
            String bodyDigest,
            String firstPassDigest,
            String secondPassDigest)
    {
        public PrIdentity
        {
            requireNonNull(state, "state is null");
            requireNonNull(baseRepositoryExternalId,
                    "baseRepositoryExternalId is null");
            requireNonNull(baseRepositoryOwner, "baseRepositoryOwner is null");
            requireNonNull(baseRepositoryName, "baseRepositoryName is null");
            requireNonNull(headRepositoryExternalId,
                    "headRepositoryExternalId is null");
            requireNonNull(headRepositoryOwner, "headRepositoryOwner is null");
            requireNonNull(headRepositoryName, "headRepositoryName is null");
            requireNonNull(headBranchRef, "headBranchRef is null");
            requireNonNull(targetBaseRef, "targetBaseRef is null");
            requireNonNull(prNodeId, "prNodeId is null");
            requireNonNull(htmlUrl, "htmlUrl is null");
            requireNonNull(observedBaseSha, "observedBaseSha is null");
            requireNonNull(titleDigest, "titleDigest is null");
            requireNonNull(bodyDigest, "bodyDigest is null");
            requireNonNull(firstPassDigest, "firstPassDigest is null");
            requireNonNull(secondPassDigest, "secondPassDigest is null");
            if (prNumber < 1
                    || !firstPassDigest.equals(secondPassDigest)) {
                throw new IllegalArgumentException(
                        "PR observation is not stable and complete");
            }
        }
    }

    public record Probe(
            String probeId,
            String operationId,
            String planId,
            String stepId,
            String attemptId,
            long claimGeneration,
            String claimTokenDigest,
            int probeNumber,
            int stepOrdinal,
            StepKind stepKind,
            Outcome outcome,
            String observedHead,
            String observationDigest,
            PrIdentity prIdentity,
            Instant observedAt)
    {
        public Probe
        {
            requireNonNull(probeId, "probeId is null");
            requireNonNull(operationId, "operationId is null");
            requireNonNull(planId, "planId is null");
            requireNonNull(stepId, "stepId is null");
            requireNonNull(stepKind, "stepKind is null");
            requireNonNull(claimTokenDigest, "claimTokenDigest is null");
            requireNonNull(outcome, "outcome is null");
            requireNonNull(observationDigest, "observationDigest is null");
            requireNonNull(observedAt, "observedAt is null");
            if (claimGeneration < 1 || probeNumber < 1
                    || stepOrdinal != stepKind.ordinal() + 1
                    || outcome == Outcome.APPLIED && observedHead == null
                    || outcome == Outcome.DIVERGED && observedHead == null
                    || outcome == Outcome.APPLIED && attemptId == null
                    || (outcome == Outcome.ABSENT
                            || outcome == Outcome.UNKNOWN)
                            && (observedHead != null || prIdentity != null)
                    || stepKind == StepKind.CREATE_REF_EXACT
                            && prIdentity != null
                    || (prIdentity != null)
                        != (stepKind == StepKind.CREATE_DRAFT_PR
                                && (outcome == Outcome.APPLIED
                                        || outcome == Outcome.DIVERGED))) {
                throw new IllegalArgumentException("initial probe is invalid");
            }
        }
    }

    public record StepReceipt(
            String receiptId,
            String operationId,
            String planId,
            String planDigest,
            String stepId,
            String stepDigest,
            String attemptId,
            String probeId,
            int stepOrdinal,
            StepKind stepKind,
            String branchRef,
            String targetBaseRef,
            String expectedBaseSha,
            String proposedHead,
            String draftRevisionId,
            String draftDigest,
            String observationDigest,
            String receiptDigest,
            PrIdentity prIdentity,
            Instant recordedAt)
    {
        public StepReceipt
        {
            requireNonNull(receiptId, "receiptId is null");
            requireNonNull(operationId, "operationId is null");
            requireNonNull(planId, "planId is null");
            requireNonNull(planDigest, "planDigest is null");
            requireNonNull(stepId, "stepId is null");
            requireNonNull(stepDigest, "stepDigest is null");
            requireNonNull(attemptId, "attemptId is null");
            requireNonNull(probeId, "probeId is null");
            requireNonNull(stepKind, "stepKind is null");
            requireNonNull(branchRef, "branchRef is null");
            requireNonNull(targetBaseRef, "targetBaseRef is null");
            requireNonNull(expectedBaseSha, "expectedBaseSha is null");
            requireNonNull(proposedHead, "proposedHead is null");
            requireNonNull(draftRevisionId, "draftRevisionId is null");
            requireNonNull(draftDigest, "draftDigest is null");
            requireNonNull(observationDigest,
                    "observationDigest is null");
            requireNonNull(receiptDigest, "receiptDigest is null");
            requireNonNull(recordedAt, "recordedAt is null");
            if (stepOrdinal != stepKind.ordinal() + 1
                    || (prIdentity != null)
                        != (stepKind == StepKind.CREATE_DRAFT_PR)) {
                throw new IllegalArgumentException(
                        "initial step receipt is invalid");
            }
        }
    }
}
