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

import java.nio.file.Path;

/**
 * Validates Git worktrees and maintains exclusive in-process task leases.
 *
 * <p>The service operates on existing worktrees only. It prevents two live
 * tasks from owning the same canonical worktree and never creates or deletes a
 * Git worktree.
 */
public interface AgentWorkspaceService
{
    /**
     * Validates and exclusively leases a clean Git worktree for a new task.
     *
     * <p>The implementation canonicalizes the path, rejects symlink escape,
     * verifies Git and repository identity, and rejects tracked or untracked
     * changes before granting the lease.
     *
     * @param taskId owner of the lease
     * @param requestedPath user-selected worktree path
     * @param repository expected repository identity
     * @return canonical worktree and idempotent release handle
     * @throws InvalidAgentRequestException when the worktree is invalid or dirty
     * @throws AgentConflictException when another task owns the worktree
     */
    WorkspaceLease acquire(TaskId taskId, Path requestedPath, RepositoryIdentity repository);

    /**
     * Reconstructs a live task's lease during startup recovery.
     *
     * <p>The worktree and repository are revalidated, but agent-created changes
     * are permitted because cleanliness was checked when the task was created.
     *
     * @param taskId durable live task that owns the lease
     * @param canonicalWorktree stored canonical worktree
     * @param repository stored repository identity
     * @return restored idempotent lease
     * @throws InvalidAgentRequestException when the stored worktree is no longer valid
     * @throws AgentConflictException when another live task claims the worktree
     */
    WorkspaceLease restore(TaskId taskId, Path canonicalWorktree, RepositoryIdentity repository);

    /**
     * Reads current Git facts without modifying the worktree.
     *
     * @param worktree canonical leased worktree
     * @return branch, HEAD, dirty state, and repository identity
     * @throws InvalidAgentRequestException when the path is no longer a worktree
     */
    WorkspaceSnapshot inspect(Path worktree);

    /**
     * Releases a task's in-process lease without deleting its worktree.
     *
     * @param taskId task whose lease should be released
     */
    void release(TaskId taskId);
}
