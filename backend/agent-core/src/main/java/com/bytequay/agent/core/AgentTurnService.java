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

/**
 * Serializes turns and coordinates execution in a task's provider session.
 *
 * <p>This is the only core service that starts or cancels provider execution.
 * At most one turn may be active for a task, and later turns resume the task's
 * persisted provider session.
 */
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
     * Returns the settings the next turn on this task would be given.
     *
     * <p>Derived from exactly what {@link #start} would snapshot, so the surface that shows the
     * user what is about to run cannot drift from what actually runs. Attachments belong to a
     * specific message, so the returned settings carry none.
     *
     * @param taskId task to describe
     * @return the settings a turn started now would record
     * @throws AgentTaskNotFoundException when the task does not exist
     */
    AgentTurnSettings effectiveSettings(TaskId taskId);

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
