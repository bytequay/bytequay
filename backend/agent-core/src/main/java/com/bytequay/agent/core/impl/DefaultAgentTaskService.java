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
package com.bytequay.agent.core.impl;

import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskNotFoundException;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.AgentWorkspaceService;
import com.bytequay.agent.core.CreateAgentTaskCommand;
import com.bytequay.agent.core.InvalidAgentRequestException;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.WorkspaceLease;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentUnavailableException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;

import static java.util.Objects.requireNonNull;

public final class DefaultAgentTaskService
        implements AgentTaskService
{
    private final AgentTaskStore taskStore;
    private final AgentWorkspaceService workspaceService;
    private final AgentResourceService resourceService;
    private final AgentDriverRegistry driverRegistry;
    private final Clock clock;

    public DefaultAgentTaskService(
            AgentTaskStore taskStore,
            AgentWorkspaceService workspaceService,
            AgentResourceService resourceService,
            AgentDriverRegistry driverRegistry,
            Clock clock)
    {
        requireNonNull(taskStore, "taskStore is null");
        requireNonNull(workspaceService, "workspaceService is null");
        requireNonNull(resourceService, "resourceService is null");
        requireNonNull(driverRegistry, "driverRegistry is null");
        requireNonNull(clock, "clock is null");
        this.taskStore = taskStore;
        this.workspaceService = workspaceService;
        this.resourceService = resourceService;
        this.driverRegistry = driverRegistry;
        this.clock = clock;
    }

    @Override
    public AgentTask create(CreateAgentTaskCommand command)
    {
        requireNonNull(command, "command is null");
        TaskId taskId = TaskId.random();
        WorkspaceLease lease = workspaceService.acquire(
                taskId,
                command.worktree(),
                command.repository());
        try {
            resourceService.allocate(taskId);
            AgentDriver driver = driverRegistry.require(command.laneId());
            AgentProviderStatus status = driver.probe();
            if (!status.available()) {
                throw new AgentUnavailableException(status.diagnostic());
            }
            Instant now = Instant.now(clock);
            AgentTask task = AgentTask.created(
                    taskId,
                    command.title(),
                    command.laneId(),
                    command.repository(),
                    lease.worktree(),
                    command.permissionPolicy(),
                    now).ready(now);
            taskStore.insert(task);
            return task;
        }
        catch (RuntimeException failure) {
            rollbackResources(taskId, failure);
            rollbackLease(lease, failure);
            throw failure;
        }
    }

    @Override
    public AgentTask get(TaskId taskId)
    {
        requireNonNull(taskId, "taskId is null");
        return taskStore.find(taskId).orElseThrow(() -> new AgentTaskNotFoundException(taskId));
    }

    @Override
    public List<AgentTask> list(int limit)
    {
        if (limit <= 0) {
            throw new InvalidAgentRequestException("limit must be positive");
        }
        return taskStore.listRecent(limit);
    }

    private void rollbackResources(TaskId taskId, RuntimeException failure)
    {
        try {
            resourceService.delete(taskId);
        }
        catch (RuntimeException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }

    private static void rollbackLease(WorkspaceLease lease, RuntimeException failure)
    {
        try {
            lease.close();
        }
        catch (RuntimeException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
    }
}
