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

public interface AgentTurnService
{
    /**
     * Accepts one user message and starts a provider turn asynchronously.
     *
     * <p>A repeated idempotency key returns the previously accepted turn. A new
     * request conflicts while another turn is active. The provider session
     * reference is persisted before the turn is reported as resumable.
     *
     * @param command task, message, attachments, and idempotency key
     * @return accepted or running turn
     * @throws AgentTaskNotFoundException when the task does not exist
     * @throws AgentConflictException when another turn is active
     */
    AgentTurn start(StartAgentTurnCommand command);

    /**
     * Requests cancellation of an accepted or running turn.
     *
     * <p>The request is idempotent. Terminal state is recorded only after the
     * provider exits or the cancellation grace period expires.
     *
     * @param turnId turn to cancel
     * @return latest durable turn state after recording the request
     * @throws AgentTurnNotFoundException when the turn does not exist
     */
    AgentTurn cancel(TurnId turnId);

    /**
     * Returns the latest durable state and metrics for one turn.
     *
     * @param turnId turn to read
     * @return stored turn
     * @throws AgentTurnNotFoundException when the turn does not exist
     */
    AgentTurn get(TurnId turnId);
}
