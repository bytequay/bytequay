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
import java.util.function.Consumer;

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

    /** Persists the prepared task through the caller's atomic task-and-source write, without putting provisioning in a transaction. */
    AgentTask createInteractive(CreateInteractiveAgentTaskCommand command, Consumer<AgentTask> persistTask);

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

    /** Prepares a workflow-owned working copy without requiring a ready provider or starting a turn. */
    AgentTask prepare(CreateAgentTaskCommand command);

    /** Creates an internal conversation borrowing the source checkout. The caller must hold the source's worktree mutation claim. */
    AgentTask createShared(CreateAgentTaskCommand command, TaskId sourceTaskId);

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
     * Gives a task the title a person chose, using optimistic task versioning. That title is
     * final: no later agent proposal replaces it.
     *
     * @param command task, observed version, and the chosen title
     * @return updated task with its next version
     * @throws AgentConflictException when the task changed concurrently
     */
    AgentTask rename(RenameAgentTaskCommand command);

    /**
     * Shelves a task or brings it back, using optimistic task versioning. Archiving changes nothing
     * about the task's work: state, checkout, branch, and provider session stay as they are.
     *
     * @param command task, observed version, and whether it should be archived
     * @return updated task with its next version
     * @throws AgentConflictException when the task changed concurrently
     */
    AgentTask archive(ArchiveAgentTaskCommand command);

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

    /**
     * Lists completed interactive tasks with retained summaries for one workspace and physical repository.
     *
     * <p>Workspace, repository, ownership, and completion filters apply before the row limit. Results include
     * archived tasks and summaries whose runtime files were cleaned up, ordered by descending update time
     * and ascending task identifier for ties. Reading these durable facts never loads transcripts or starts
     * a provider. Summaries remain agent conclusions, not independently verified repository facts.
     *
     * @param workspaceId owning workspace
     * @param repository physical repository recorded at task creation, including a selected fork
     * @param limit positive maximum number of matching tasks
     * @return at most {@code limit} completed tasks with summaries; repository names match without case sensitivity
     * @throws InvalidAgentRequestException when the workspace or limit is invalid
     */
    List<AgentTask> listCompleted(String workspaceId, RepositoryIdentity repository, int limit);
}
