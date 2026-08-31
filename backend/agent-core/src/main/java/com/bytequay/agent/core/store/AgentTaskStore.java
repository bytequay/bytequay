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

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.TaskId;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence port for durable agent task lifecycle facts.
 *
 * <p>Implementations store task identity, state, provider session reference,
 * metrics, summary, and cleanup metadata. Detailed event history is owned by
 * {@link TaskLogStore} rather than this store.
 */
public interface AgentTaskStore
{
    /**
     * Inserts a task in its initial durable state.
     *
     * @param task task to insert
     * @throws com.bytequay.agent.core.AgentConflictException when the identifier exists
     */
    void insert(AgentTask task);

    /**
     * Finds a task by its ByteQuay identifier.
     *
     * @param taskId task identifier
     * @return stored task, or empty when absent
     */
    Optional<AgentTask> find(TaskId taskId);

    /**
     * Replaces a task using optimistic version checking.
     *
     * @param task updated task whose version matches the stored row
     * @return stored task with its next version
     * @throws com.bytequay.agent.core.AgentConflictException when the version changed
     */
    AgentTask update(AgentTask task);

    /**
     * Lists recently updated tasks in descending update-time order.
     *
     * <p>This supports the current unscoped listing until workspace-owned task
     * creation is activated.
     *
     * @param limit positive maximum row count
     * @return at most {@code limit} tasks
     */
    List<AgentTask> listRecent(int limit);

    /**
     * Lists recently updated interactive tasks for one workspace.
     *
     * @param workspaceId owning workspace
     * @param limit positive maximum row count
     * @return at most {@code limit} matching tasks
     */
    List<AgentTask> listRecentInteractive(String workspaceId, int limit);

    /**
     * Returns every non-terminal task for startup lease reconstruction.
     *
     * @return live tasks in stable creation order
     */
    List<AgentTask> listLive();

    /**
     * Returns terminal tasks whose deletion deadline passed or whose previous
     * cleanup failed.
     *
     * @param now upper bound for retention deadlines
     * @param limit positive maximum row count
     * @return cleanup candidates ordered by deletion deadline
     */
    List<AgentTask> listCleanupDue(Instant now, int limit);
}
