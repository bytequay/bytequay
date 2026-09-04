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

import com.bytequay.agent.core.QueuedAgentInput;
import com.bytequay.agent.core.QueuedInputId;
import com.bytequay.agent.core.TaskId;

import java.util.List;
import java.util.Optional;

/**
 * Persistence port for a task's FIFO queue of later turns.
 *
 * <p>The queue is durable state, not something rebuilt from provider history. Claiming the head,
 * inserting its turn, and taking the worktree claim happen in one {@link AgentUnitOfWork}, so
 * the port needs only plain reads and version-checked writes.
 */
public interface QueuedInputStore
{
    /**
     * Inserts a queued input.
     *
     * @throws com.bytequay.agent.core.AgentConflictException when the identifier or the task's idempotency key exists
     */
    void insert(QueuedAgentInput input);

    Optional<QueuedAgentInput> find(QueuedInputId inputId);

    Optional<QueuedAgentInput> findQueuedByIdempotencyKey(TaskId taskId, String idempotencyKey);

    /** Every input ever queued on a task, in queue order, whatever became of it. */
    List<QueuedAgentInput> listQueued(TaskId taskId);

    /**
     * Replaces an input using optimistic version checking.
     *
     * @throws com.bytequay.agent.core.AgentConflictException when the version changed
     */
    QueuedAgentInput update(QueuedAgentInput input);
}
