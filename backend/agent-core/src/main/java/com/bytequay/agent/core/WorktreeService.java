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

/** Creates, verifies, and removes ByteQuay-generated task worktrees without owning workflow state. */
public interface WorktreeService
{
    /**
     * Creates or verifies the task-derived worktree described by the command.
     *
     * @throws AgentConflictException when the derived path or branch conflicts with existing Git state
     * @throws InvalidAgentRequestException when trusted repository or commit facts cannot be verified
     * @throws AgentStorageException when Git cannot be updated safely
     */
    TaskWorktree provision(ProvisionWorktreeCommand command);

    /**
     * Resolves the owned checkout for read-only source inspection without requiring its generated branch or refreshing its index.
     *
     * @throws InvalidAgentRequestException when the checkout is absent or its workspace/repository ownership cannot be verified
     */
    Path ownedCheckout(TaskWorktree worktree);

    /**
     * Returns current Git facts after revalidating the recorded ownership facts.
     *
     * @throws InvalidAgentRequestException when the worktree is absent or unverifiable
     * @throws AgentStorageException when Git cannot be read safely
     */
    WorktreeSnapshot inspect(TaskWorktree worktree);

    /**
     * Removes a verified clean worktree without deleting its generated branch.
     *
     * <p>The caller owns task quiescence. The implementation revalidates the
     * path, repository, and branch, deletes the Claude CLI's
     * {@code .claude/.cc-writes/} staging files, which are never work, refuses
     * dirty work or an open Git operation, and never force-removes files.
     * Repeating a successful cleanup is safe.
     *
     * @throws AgentConflictException when cleanliness or Git operation state blocks cleanup
     * @throws InvalidAgentRequestException when ownership is unverifiable
     * @throws AgentStorageException when Git cannot be updated safely
     */
    void cleanup(TaskWorktree worktree);

    /**
     * Destroys a task's checkout and its generated branch, uncommitted work included.
     *
     * <p>The one destructive worktree operation. Deleting a task is its only caller: the branch is
     * the task's local result, so nothing that keeps the task may call this. Ownership is
     * revalidated first, dirty work is not a refusal, and repeating it is safe.
     *
     * @throws InvalidAgentRequestException when ownership is unverifiable
     * @throws AgentStorageException when Git cannot be updated safely
     */
    void delete(TaskWorktree worktree);
}
