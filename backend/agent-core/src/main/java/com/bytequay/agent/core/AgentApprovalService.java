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

import java.time.Duration;
import java.util.concurrent.CompletionStage;

/**
 * Bridges provider permission requests to explicit frontend decisions.
 *
 * <p>Approval is never inferred from a disconnect or timeout. Unresolved
 * requests are denied when they expire or when their owning turn becomes
 * terminal or is cancelled.
 */
public interface AgentApprovalService
{
    /**
     * Registers a provider permission request and awaits a decision asynchronously.
     *
     * <p>The request and its normalized event are persisted before publication.
     * Expiry completes the returned stage with a denial; approval is never inferred
     * from a frontend disconnect.
     *
     * @param request permission request owned by one task and turn
     * @param timeout maximum time to wait for the user
     * @return explicit decision or timeout denial
     * @throws AgentConflictException when the approval identifier already exists
     */
    CompletionStage<ApprovalDecision> request(ApprovalRequest request, Duration timeout);

    /**
     * Resolves one pending approval from a direct user action.
     *
     * <p>Repeating the same decision is idempotent. A contradictory second
     * decision fails with a conflict.
     *
     * @param command approval identifier, decision, and optional scoped permission
     * @return recorded decision
     * @throws ApprovalNotFoundException when no such approval exists
     * @throws AgentConflictException when a different decision was already recorded
     */
    ApprovalDecision resolve(ResolveApprovalCommand command);

    /**
     * Denies every unresolved approval owned by a terminal or cancelled turn.
     *
     * @param turnId turn whose approvals should be closed
     */
    void denyPending(TurnId turnId);

    /** Denies every unresolved durable approval during startup recovery. */
    void denyAllPending();
}
