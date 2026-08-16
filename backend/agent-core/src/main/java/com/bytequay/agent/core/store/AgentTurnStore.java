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
package com.bytequay.agent.core.store;

import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;

import java.util.List;
import java.util.Optional;

/**
 * Persistence port for durable per-turn state and idempotency records.
 *
 * <p>Implementations enforce one active turn per task and optimistic updates.
 * Turn details remain durable across provider-process and backend restarts.
 */
public interface AgentTurnStore
{
    /**
     * Inserts an accepted turn while enforcing one active turn per task.
     *
     * @param turn accepted turn
     * @throws com.bytequay.agent.core.AgentConflictException when the identifier,
     * idempotency key, or active-turn slot conflicts
     */
    void insert(AgentTurn turn);

    /**
     * Finds a turn by its ByteQuay identifier.
     *
     * @param turnId turn identifier
     * @return stored turn, or empty when absent
     */
    Optional<AgentTurn> find(TurnId turnId);

    /**
     * Finds the turn previously accepted for a task and idempotency key.
     *
     * @param taskId task identifier
     * @param idempotencyKey caller-supplied stable key
     * @return matching turn, or empty when no request was accepted
     */
    Optional<AgentTurn> findByIdempotencyKey(TaskId taskId, String idempotencyKey);

    /**
     * Finds the task's accepted or running turn.
     *
     * @param taskId task identifier
     * @return active turn, or empty when the task is idle
     */
    Optional<AgentTurn> findActive(TaskId taskId);

    /**
     * Replaces a turn using optimistic version checking.
     *
     * @param turn updated turn whose version matches the stored row
     * @return stored turn with its next version
     * @throws com.bytequay.agent.core.AgentConflictException when the version changed
     */
    AgentTurn update(AgentTurn turn);

    /**
     * Lists every turn for a task in ascending creation order.
     *
     * @param taskId task identifier
     * @return ordered turns used for metrics and completion summaries
     */
    List<AgentTurn> list(TaskId taskId);

    /**
     * Returns accepted and running turns that require interruption during startup
     * recovery.
     *
     * @return recoverable turns in stable creation order
     */
    List<AgentTurn> listRecoverable();
}
