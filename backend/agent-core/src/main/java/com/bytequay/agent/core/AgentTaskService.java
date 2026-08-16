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

import java.util.List;

/**
 * Creates agent tasks and exposes their durable task-level state.
 *
 * <p>This service owns task creation and rollback after a failed creation. Turn
 * execution belongs to {@link AgentTurnService}, while completion and retained
 * resource cleanup belong to {@link AgentCompletionService}.
 */
public interface AgentTaskService
{
    /**
     * Creates a durable task for one clean, validated worktree and provider lane.
     *
     * <p>Creation acquires the lease, allocates task resources, verifies provider
     * readiness, and persists a {@link TaskState#READY} task without starting a
     * turn. Failure after lease acquisition must delete newly allocated resources
     * and release the lease.
     *
     * @param command lane, worktree, repository, title, and permission policy
     * @return newly persisted ready task
     * @throws InvalidAgentRequestException when the command or worktree is invalid
     * @throws AgentConflictException when the worktree already has a live task
     */
    AgentTask create(CreateAgentTaskCommand command);

    /**
     * Returns the latest durable representation of a task.
     *
     * @param taskId task to read
     * @return stored task
     * @throws AgentTaskNotFoundException when the task does not exist
     */
    AgentTask get(TaskId taskId);

    /**
     * Lists durable task facts in reverse update order.
     *
     * @param limit positive maximum number of tasks
     * @return at most {@code limit} tasks
     * @throws InvalidAgentRequestException when {@code limit} is not positive
     */
    List<AgentTask> list(int limit);
}
