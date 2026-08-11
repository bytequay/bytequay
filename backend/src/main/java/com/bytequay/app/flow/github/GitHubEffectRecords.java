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

import java.time.Instant;

import static java.util.Objects.requireNonNull;

/** Immutable GitHub effect-plan records; provider execution is not implemented. */
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

    public record ExternalEffectPlan(
            String planId,
            String operationId,
            String authorizationId,
            String prId,
            long prSequence,
            EffectKind kind,
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
}
