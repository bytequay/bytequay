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

/**
 * Completes agent tasks and performs retention-aware runtime cleanup.
 *
 * <p>Completion stores the final summary and deletion deadline without changing
 * the filesystem. The caller persists its own terminal result before requesting
 * idempotent worktree removal. Runtime cleanup removes ByteQuay-owned resources
 * only after the deadline; a cleanup failure must not change terminal facts.
 */
public interface AgentCompletionService
{
    /**
     * Closes an idle task and commits its durable final result.
     *
     * <p>The summary, completed state, terminal time, totals, and seven-day
     * deletion deadline are committed together. This method does not remove the
     * worktree; callers invoke {@link #cleanupWorktree(TaskId)} only after their
     * own terminal result is durable. Runtime logs remain retained.
     *
     * @param command task and optional user completion notes
     * @return durable completion summary and retention deadline
     * @throws AgentConflictException when a turn is active or completion is invalid
     */
    TaskCompletion complete(CompleteAgentTaskCommand command);

    /**
     * Fails a task whose provider could not be shown to have stopped touching its worktree.
     *
     * <p>A turn that cannot prove the provider went quiet keeps its worktree mutation claim, so no
     * later turn edits files something may still be writing to. That deliberately leaves the task
     * stuck, and this is the way out: it stops and verifies the task's own provider process,
     * releases the held claim, and marks the task failed while retaining the worktree, so whatever
     * the provider left behind can still be inspected. A task with an active turn is refused; the
     * turn has to be stopped first.
     *
     * @param taskId the stuck task
     * @param reason why it is being abandoned
     * @return the failed task
     * @throws AgentConflictException when the task is terminal or a turn is still active
     */
    AgentTask abandon(TaskId taskId, String reason);

    /**
     * Retries removal of a terminal task's verified clean worktree.
     *
     * <p>The generated branch is retained and the operation is idempotent when
     * an earlier completion already removed the worktree.
     *
     * @param taskId terminal task whose worktree should be removed
     * @throws AgentConflictException when the task is live or cleanup is unsafe
     */
    void cleanupWorktree(TaskId taskId);

    /**
     * Removes the terminal worktree and retained runtime resources after their deadline.
     *
     * <p>Worktree removal is retried before runtime deletion. The operation is
     * idempotent. Any cleanup failure records
     * {@link CleanupState#RETRY_PENDING} without changing terminal task state or
     * summary.
     *
     * @param taskId terminal task whose retention deadline has passed
     * @return updated cleanup result
     * @throws AgentConflictException when the task is live or cleanup is early
     */
    CleanupResult cleanup(TaskId taskId);
}
