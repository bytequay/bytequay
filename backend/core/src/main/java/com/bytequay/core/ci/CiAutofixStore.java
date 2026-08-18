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
package com.bytequay.core.ci;

import java.util.List;
import java.util.Optional;

/** Stores complete CI Autofix run projections and their ordered attempts. */
public interface CiAutofixStore
{
    /**
     * Inserts one run and enforces start idempotency and one non-finished run
     * for a repository head branch.
     *
     * @param run new version-zero run
     * @throws CiAutofixConflictException when an identifier, idempotency key, or active branch conflicts
     * @throws CiAutofixStorageException when persistence fails
     */
    void insert(CiAutofixRun run);

    /**
     * Finds one complete run projection.
     *
     * @param runId run identifier
     * @return run with attempts ordered by attempt number, or empty
     * @throws CiAutofixStorageException when persistence fails
     */
    Optional<CiAutofixRun> find(CiAutofixRunId runId);

    /**
     * Finds the run created by one start idempotency key.
     *
     * @param key bounded start idempotency key
     * @return matching complete run projection, or empty
     * @throws CiAutofixStorageException when persistence fails
     */
    Optional<CiAutofixRun> findByIdempotencyKey(String key);

    /**
     * Finds the run owned by one agent task.
     *
     * @param taskId durable agent task identifier
     * @return matching complete run projection, or empty
     * @throws CiAutofixStorageException when persistence fails
     */
    Optional<CiAutofixRun> findByAgentTaskId(String taskId);

    /**
     * Lists complete runs in reverse update order.
     *
     * @param limit positive result limit
     * @return at most {@code limit} complete run projections
     * @throws IllegalArgumentException when limit is not positive
     * @throws CiAutofixStorageException when persistence fails
     */
    List<CiAutofixRun> listRecent(int limit);

    /**
     * Lists active and paused runs in oldest-update-first monitor order.
     *
     * @param limit positive batch size
     * @return at most {@code limit} non-finished run projections
     * @throws IllegalArgumentException when limit is not positive
     * @throws CiAutofixStorageException when persistence fails
     */
    List<CiAutofixRun> listNonFinished(int limit);

    /**
     * Atomically replaces one run and its attempts using optimistic version checking.
     *
     * <p>Existing attempts cannot be removed or moved to another run. Newly
     * appended attempts and updates to existing attempts commit with the run or
     * roll back with it.
     *
     * @param run updated complete projection carrying the expected version
     * @return stored projection carrying the next version
     * @throws CiAutofixNotFoundException when the run does not exist
     * @throws CiAutofixConflictException when the version or a durable constraint changed
     * @throws CiAutofixStorageException when persistence fails
     */
    CiAutofixRun update(CiAutofixRun run);
}
