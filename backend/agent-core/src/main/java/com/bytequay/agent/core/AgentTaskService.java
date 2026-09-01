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
     * Creates an interactive task in a ByteQuay-managed workspace worktree.
     *
     * <p>Provider readiness is checked before provisioning. The task becomes
     * visible only after the exact generated worktree has been verified and
     * task resources have been allocated.
     *
     * @param command trusted workspace, exact commit, lane, and task defaults
     * @return newly persisted open task
     */
    AgentTask createInteractive(CreateInteractiveAgentTaskCommand command);

    /**
     * Creates a durable task for one clean, validated worktree and provider lane.
     *
     * <p>Creation verifies provider readiness before acquiring the lease or
     * allocating task resources, then persists a {@link TaskState#OPEN} task
     * without starting a turn. Failure after lease acquisition must delete newly
     * allocated resources and release the lease.
     *
     * @param command lane, worktree, repository, title, task defaults, and optional workspace ownership
     * @return newly persisted open task
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
     * Returns an interactive task only when it belongs to the expected workspace.
     *
     * @param workspaceId expected owning workspace
     * @param taskId task to read
     * @return matching interactive task
     * @throws AgentTaskNotFoundException when the task is absent, internal, or belongs to another workspace
     */
    AgentTask getInteractive(String workspaceId, TaskId taskId);

    /**
     * Replaces defaults used by later turns using optimistic task versioning.
     *
     * @param command task, observed version, and replacement defaults
     * @return updated task with its next version
     * @throws AgentConflictException when the task changed concurrently
     */
    AgentTask updateDefaults(UpdateAgentTaskDefaultsCommand command);

    /**
     * Lists durable task facts in reverse update order.
     *
     * <p>This keeps the current unscoped listing working until workspace-owned
     * task creation is activated.
     *
     * @param limit positive maximum number of tasks
     * @return at most {@code limit} tasks
     * @throws InvalidAgentRequestException when {@code limit} is not positive
     */
    List<AgentTask> list(int limit);

    /**
     * Lists interactive task facts for one workspace in reverse update order.
     *
     * @param workspaceId owning workspace
     * @param limit positive maximum number of tasks
     * @return at most {@code limit} matching interactive tasks
     * @throws InvalidAgentRequestException when the workspace or limit is invalid
     */
    List<AgentTask> list(String workspaceId, int limit);
}
