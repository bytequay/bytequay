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
package com.bytequay.app.flow.runtime;

import com.bytequay.app.flow.runtime.FlowRuntimeRecords.Task;

import static java.util.Objects.requireNonNull;

/** Program-owned greenfield Task entry command. */
public final class TaskCommands
{
    private final TaskProvisioning provisioning;
    private final NewFlowDispatcher dispatcher;
    private final InitialTaskDispatcher initialTasks;

    public TaskCommands(
            TaskProvisioning provisioning,
            NewFlowDispatcher dispatcher,
            InitialTaskDispatcher initialTasks)
    {
        this.provisioning = requireNonNull(
                provisioning, "provisioning is null");
        this.dispatcher = requireNonNull(dispatcher, "dispatcher is null");
        this.initialTasks = requireNonNull(
                initialTasks, "initialTasks is null");
    }

    public Task startTask(
            String requestKey, String repositoryId, String goalText)
    {
        Task task = provisioning.startTask(
                requestKey, repositoryId, goalText);
        dispatcher.wake();
        initialTasks.wake();
        return task;
    }
}
