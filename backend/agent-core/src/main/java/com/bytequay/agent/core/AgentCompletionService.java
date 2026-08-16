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

public interface AgentCompletionService
{
    /**
     * Closes an idle task and commits its durable final result.
     *
     * <p>The summary, completed state, terminal time, totals, and seven-day
     * deletion deadline are committed together before the worktree lease is
     * released. Runtime logs remain retained.
     *
     * @param command task and optional user completion notes
     * @return durable completion summary and retention deadline
     * @throws AgentConflictException when a turn is active or completion is invalid
     */
    TaskCompletion complete(CompleteAgentTaskCommand command);

    /**
     * Deletes retained ByteQuay runtime resources after their deadline.
     *
     * <p>The operation is idempotent. Deletion failure records
     * {@link CleanupState#RETRY_PENDING} without changing terminal task state or
     * summary.
     *
     * @param taskId terminal task whose retention deadline has passed
     * @return updated cleanup result
     * @throws AgentConflictException when the task is live or cleanup is early
     */
    CleanupResult cleanup(TaskId taskId);
}
