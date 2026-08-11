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

/** Immutable GitHub effect-plan and execution evidence. */
public final class GitHubEffectRecords
{
    private GitHubEffectRecords() {}

    public enum EffectKind
    {
        CI_UPDATE
    }

    public enum StepKind
    {
        PUSH_EXACT
    }

    public enum ProbeOutcome
    {
        APPLIED,
        ABSENT,
        DIVERGED,
        UNKNOWN
    }

    /** Implemented only by GitHubProvider's privately constructed proof. */
    public sealed interface ProviderObservation
            permits GitHubProvider.ExactProviderObservation
    {
        String operationId();
        String planId();
        String attemptId();
        String headRepositoryExternalId();
        String headRepositoryOwner();
        String headRepositoryName();
        String branchRef();
        String expectedRemoteHead();
        String proposedHead();
        ProbeOutcome outcome();
        String observedHead();
        boolean matchesClaim(Claim claim);
    }

    public enum ProviderFailureKind
    {
        INVALID,
        UNAVAILABLE
    }

    /** Implemented only by GitHubProvider; never a remote observation. */
    public sealed interface ProviderFailure
            permits GitHubProvider.ExactProviderFailure
    {
        String operationId();
        String planId();
        String attemptId();
        String headRepositoryExternalId();
        String headRepositoryOwner();
        String headRepositoryName();
        String branchRef();
        String expectedRemoteHead();
        String proposedHead();
        ProviderFailureKind kind();
        boolean matchesClaim(Claim claim);
    }

    public record ExternalEffectPlan(
            String planId,
            String operationId,
            String authorizationId,
            String prId,
            long prSequence,
            EffectKind kind,
            String headRepositoryExternalId,
            String headRepositoryOwner,
            String headRepositoryName,
            String expectedRemoteHead,
            String actionRef,
            String actionDigest,
            String requiredCiPolicyRevisionId,
            String planDigest,
            Instant createdAt)
    {
        public ExternalEffectPlan
        {
            requireNonNull(planId, "planId is null");
            requireNonNull(operationId, "operationId is null");
            requireNonNull(authorizationId, "authorizationId is null");
            requireNonNull(prId, "prId is null");
            requireNonNull(kind, "kind is null");
            requireNonNull(headRepositoryExternalId,
                    "headRepositoryExternalId is null");
            requireNonNull(headRepositoryOwner,
                    "headRepositoryOwner is null");
            requireNonNull(headRepositoryName,
                    "headRepositoryName is null");
            requireNonNull(expectedRemoteHead,
                    "expectedRemoteHead is null");
            requireNonNull(actionRef, "actionRef is null");
            requireNonNull(actionDigest, "actionDigest is null");
            requireNonNull(requiredCiPolicyRevisionId,
                    "requiredCiPolicyRevisionId is null");
            requireNonNull(planDigest, "planDigest is null");
            requireNonNull(createdAt, "createdAt is null");
            if (prSequence < 1) {
                throw new IllegalArgumentException(
                        "prSequence must be positive");
            }
        }
    }

    public record ExternalEffectStep(
            String stepId,
            String planId,
            int ordinal,
            StepKind kind,
            String headRepositoryExternalId,
            String headRepositoryOwner,
            String headRepositoryName,
            String branchRef,
            String expectedRemoteHead,
            String proposedHead,
            boolean forcePush,
            String actionRef,
            String actionDigest,
            String preconditionDigest)
    {
        public ExternalEffectStep
        {
            requireNonNull(stepId, "stepId is null");
            requireNonNull(planId, "planId is null");
            requireNonNull(kind, "kind is null");
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
            requireNonNull(actionRef, "actionRef is null");
            requireNonNull(actionDigest, "actionDigest is null");
            requireNonNull(preconditionDigest,
                    "preconditionDigest is null");
            if (ordinal != 1 || forcePush) {
                throw new IllegalArgumentException(
                        "CI_UPDATE is one non-force PUSH_EXACT step");
            }
        }
    }

    public record ExternalEffectAttempt(
            String attemptId,
            String operationId,
            String planId,
            String stepId,
            int attemptNumber,
            long claimGeneration,
            String claimTokenDigest,
            String headRepositoryExternalId,
            String headRepositoryOwner,
            String headRepositoryName,
            String branchRef,
            String expectedRemoteHead,
            String proposedHead,
            String requestDigest,
            String executionTokenDigest,
            Instant activatedAt)
    {
        public ExternalEffectAttempt
        {
            requireNonNull(attemptId, "attemptId is null");
            requireNonNull(operationId, "operationId is null");
            requireNonNull(planId, "planId is null");
            requireNonNull(stepId, "stepId is null");
            requireNonNull(claimTokenDigest,
                    "claimTokenDigest is null");
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
            requireNonNull(requestDigest, "requestDigest is null");
            requireNonNull(executionTokenDigest,
                    "executionTokenDigest is null");
            requireNonNull(activatedAt, "activatedAt is null");
            if (attemptNumber < 1 || attemptNumber > 2
                    || claimGeneration < 1) {
                throw new IllegalArgumentException(
                        "invalid GitHub effect attempt number/generation");
            }
        }
    }

    public record ExternalEffectProbe(
            String probeId,
            String operationId,
            String planId,
            String stepId,
            String attemptId,
            long claimGeneration,
            int probeNumber,
            String headRepositoryExternalId,
            String headRepositoryOwner,
            String headRepositoryName,
            String branchRef,
            String expectedRemoteHead,
            String proposedHead,
            ProbeOutcome outcome,
            String observedHead,
            String probeDigest,
            Instant observedAt)
    {
        public ExternalEffectProbe
        {
            requireNonNull(probeId, "probeId is null");
            requireNonNull(operationId, "operationId is null");
            requireNonNull(planId, "planId is null");
            requireNonNull(stepId, "stepId is null");
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
            requireNonNull(outcome, "outcome is null");
            requireNonNull(probeDigest, "probeDigest is null");
            requireNonNull(observedAt, "observedAt is null");
            if (claimGeneration < 1 || probeNumber < 1
                    || outcome == ProbeOutcome.UNKNOWN
                            && observedHead != null
                    || (outcome == ProbeOutcome.APPLIED
                            || outcome == ProbeOutcome.ABSENT)
                            && observedHead == null) {
                throw new IllegalArgumentException(
                        "invalid GitHub effect probe");
            }
        }
    }

    public record ExternalEffectReceipt(
            String receiptId,
            String operationId,
            String planId,
            String stepId,
            String attemptId,
            String probeId,
            String headRepositoryExternalId,
            String headRepositoryOwner,
            String headRepositoryName,
            String branchRef,
            String expectedRemoteHead,
            String proposedHead,
            String receiptDigest,
            Instant recordedAt)
    {
        public ExternalEffectReceipt
        {
            requireNonNull(receiptId, "receiptId is null");
            requireNonNull(operationId, "operationId is null");
            requireNonNull(planId, "planId is null");
            requireNonNull(stepId, "stepId is null");
            requireNonNull(probeId, "probeId is null");
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
            requireNonNull(receiptDigest, "receiptDigest is null");
            requireNonNull(recordedAt, "recordedAt is null");
        }
    }
}
