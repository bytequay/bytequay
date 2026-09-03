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
     * Describes what stopping the task's provider process would interrupt.
     *
     * <p>The returned generation names the runtime the caller looked at: the active turn when
     * there is one, otherwise the task version. {@link #stopRuntime} requires it back, so a turn
     * that started after the caller confirmed cannot be stopped by that confirmation.
     *
     * @param taskId task whose runtime is described
     * @return the impact and the generation a stop must echo
     * @throws AgentTaskNotFoundException when the task does not exist
     */
    AgentRuntimeStopImpact stopImpact(TaskId taskId);

    /**
     * Stops the task's own provider process, interrupting whatever it was running.
     *
     * <p>This is the last resort behind ordinary cancellation and reaches no other task. The active
     * turn, if any, is interrupted and its pending approvals denied. The task's worktree claim is
     * released only once the process tree is verified stopped; a process that cannot be verified
     * keeps the task blocked and this method fails. A claim left by an earlier turn whose provider
     * could not be shown quiet is released by the same verification, because the process that
     * might still have been writing is now provably gone.
     *
     * @param taskId task whose process should stop
     * @param generation the value returned by {@link #stopImpact}
     * @return the task after the stop
     * @throws AgentTaskNotFoundException when the task does not exist
     * @throws AgentConflictException when the task is terminal or the generation is stale
     * @throws AgentUnavailableException when the process tree could not be verified stopped
     */
    AgentTask stopRuntime(TaskId taskId, String generation);

    /**
     * Returns the latest durable state and metrics for one turn.
     *
     * @param turnId turn to read
     * @return stored turn
     * @throws AgentTurnNotFoundException when the turn does not exist
     */
    AgentTurn get(TurnId turnId);
}
