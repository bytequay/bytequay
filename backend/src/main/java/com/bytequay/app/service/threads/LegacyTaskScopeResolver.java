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
package com.bytequay.app.service.threads;

import com.bytequay.app.developmentflow.execution.CapacityManager;
import com.bytequay.app.developmentflow.execution.LegacySagaCapacity;
import com.bytequay.app.domain.Task;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import org.springframework.stereotype.Component;

import static java.util.Objects.requireNonNull;

/** Resolves one exact persisted legacy Task capacity scope by primary key. */
@Component
public class LegacyTaskScopeResolver
        implements LegacySagaCapacity.TaskScopeResolver
{
    private final TaskStore tasks;
    private final ThreadStore threads;

    public LegacyTaskScopeResolver(TaskStore tasks, ThreadStore threads)
    {
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.threads = requireNonNull(threads, "threads is null");
    }

    @Override
    public CapacityManager.CapacityScope resolve(String taskId)
    {
        requireNonNull(taskId, "taskId is null");
        if (taskId.isBlank()) {
            throw new IllegalArgumentException("taskId must not be blank");
        }
        Task task = tasks.findTaskById(taskId)
                .orElseThrow(() -> new IllegalStateException(
                        "saga effect has no exact Task: " + taskId));
        if (!taskId.equals(task.id())) {
            throw new IllegalStateException("saga Task lookup returned another Task");
        }
        com.bytequay.app.domain.Thread trunk = threads.findThreadById(task.threadId())
                .orElseThrow(() -> new IllegalStateException(
                        "saga Task has no exact Trunk: " + task.threadId()));
        if (!task.threadId().equals(trunk.id())) {
            throw new IllegalStateException("saga Trunk lookup returned another Trunk");
        }
        long taskEpoch = tasks.findTaskEpoch(taskId)
                .orElseThrow(() -> new IllegalStateException(
                        "saga Task has no exact epoch: " + taskId));
        return new CapacityManager.CapacityScope(
                trunk.workspaceId(), trunk.id(), task.id(), taskEpoch);
    }
}
