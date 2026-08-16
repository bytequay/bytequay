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
 * Owns the ByteQuay runtime files allocated for an agent task.
 *
 * <p>These resources live outside the Git worktree and include the event log,
 * blobs, and provider scratch directory. Provider account files and provider-
 * owned session history are outside this service's ownership.
 */
public interface AgentResourceService
{
    /**
     * Creates or returns the owner-only runtime paths for a task.
     *
     * <p>The task directory is outside the worktree with mode {@code 0700}; files
     * created beneath it use {@code 0600}.
     *
     * @param taskId task that owns the resources
     * @return validated event, blob, and provider paths
     * @throws AgentStorageException when safe allocation fails
     */
    TaskResources allocate(TaskId taskId);

    /**
     * Resolves existing task resources without creating them.
     *
     * @param taskId task that owns the resources
     * @return validated existing paths
     * @throws AgentStorageException when resources are missing or escape the root
     */
    TaskResources require(TaskId taskId);

    /**
     * Deletes all ByteQuay-owned runtime resources for a task.
     *
     * <p>Deletion is idempotent and must reject paths outside the configured
     * runtime root. Retention eligibility is enforced by the caller.
     *
     * @param taskId task whose resources should be deleted
     * @throws AgentStorageException when safe deletion cannot be completed
     */
    void delete(TaskId taskId);
}
