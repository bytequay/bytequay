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
package com.bytequay.agent.core;

import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

/** Durable approval facts; absence of a decision means the request is pending. */
public record AgentApproval(
        ApprovalRequest request,
        Optional<ApprovalDecision> decision,
        Optional<AgentEventPayload.ApprovalOutcome> outcome)
{
    public AgentApproval
    {
        requireNonNull(request, "request is null");
        requireNonNull(decision, "decision is null");
        requireNonNull(outcome, "outcome is null");
        checkArgument(decision.isPresent() == outcome.isPresent(), "decision and outcome must be present together");
        decision.ifPresent(value -> {
            checkArgument(value.approvalId().equals(request.id()), "decision belongs to another approval");
            checkArgument(matches(value, outcome.orElseThrow()), "decision and outcome do not match");
        });
    }

    public static AgentApproval pending(ApprovalRequest request)
    {
        return new AgentApproval(request, Optional.empty(), Optional.empty());
    }

    public boolean pending()
    {
        return decision.isEmpty();
    }

    public AgentApproval resolve(
            ApprovalDecision decision,
            AgentEventPayload.ApprovalOutcome outcome)
    {
        checkState(pending(), "approval is already resolved");
        return new AgentApproval(request, Optional.of(decision), Optional.of(outcome));
    }

    private static boolean matches(
            ApprovalDecision decision,
            AgentEventPayload.ApprovalOutcome outcome)
    {
        return switch (outcome) {
            case ALLOWED -> decision.decision() == ApprovalDecision.Decision.ALLOW && !decision.timedOut();
            case DENIED, CANCELLED -> decision.decision() == ApprovalDecision.Decision.DENY && !decision.timedOut();
            case EXPIRED -> decision.decision() == ApprovalDecision.Decision.DENY && decision.timedOut();
        };
    }
}
