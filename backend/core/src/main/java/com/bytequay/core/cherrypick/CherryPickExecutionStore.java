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
package com.bytequay.core.cherrypick;

import java.util.List;
import java.util.Optional;

/** Persists versioned cherry-pick executions without making transitions. */
public interface CherryPickExecutionStore
{
    /** Largest accepted value for {@code limit}. */
    int MAX_LIST_LIMIT = 200;

    /**
     * Creates an execution, or returns the existing one with the same start
     * idempotency key.
     *
     * @param execution proposed version-zero execution
     * @return created or existing execution
     * @throws CherryPickConflictException when the identifier already exists with a different key
     * @throws CherryPickStorageException when persistence fails
     */
    CherryPickExecution createOrGet(CherryPickExecution execution);

    /**
     * Finds the execution created by one start idempotency key.
     *
     * <p>A repeated start must be answered before anything is created, so this
     * is separate from {@link #createOrGet}.
     *
     * @param idempotencyKey bounded start idempotency key
     * @return matching execution, or empty
     * @throws CherryPickStorageException when persistence fails
     */
    Optional<CherryPickExecution> findByIdempotencyKey(String idempotencyKey);

    /**
     * Finds one complete execution projection.
     *
     * @param executionId execution identifier
     * @return execution with items in plan order, or empty
     * @throws CherryPickStorageException when persistence fails
     */
    Optional<CherryPickExecution> find(CherryPickExecutionId executionId);

    /**
     * Finds the execution owned by one agent task.
     *
     * @param taskId durable agent task identifier
     * @return matching execution, or empty
     * @throws CherryPickStorageException when persistence fails
     */
    Optional<CherryPickExecution> findByAgentTaskId(String taskId);

    /**
     * Lists executions in reverse update order.
     *
     * @param limit result limit between 1 and {@link #MAX_LIST_LIMIT}
     * @return at most {@code limit} executions
     * @throws IllegalArgumentException when limit is out of range
     * @throws CherryPickStorageException when persistence fails
     */
    List<CherryPickExecution> listRecent(int limit);

    /**
     * Lists running executions in oldest-update-first monitor order.
     *
     * @param limit batch size between 1 and {@link #MAX_LIST_LIMIT}
     * @return at most {@code limit} running executions
     * @throws IllegalArgumentException when limit is out of range
     * @throws CherryPickStorageException when persistence fails
     */
    List<CherryPickExecution> listRunning(int limit);

    /**
     * Saves one transition using the aggregate's expected version.
     *
     * @param execution updated execution
     * @return stored execution carrying its next version
     * @throws CherryPickNotFoundException when the execution no longer exists
     * @throws CherryPickConflictException when another writer advanced the version
     * @throws CherryPickStorageException when persistence fails
     */
    CherryPickExecution save(CherryPickExecution execution);
}
