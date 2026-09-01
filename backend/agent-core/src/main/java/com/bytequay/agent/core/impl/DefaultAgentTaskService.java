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

import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentResourceService;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskNotFoundException;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.AgentWorkspaceService;
import com.bytequay.agent.core.CreateAgentTaskCommand;
import com.bytequay.agent.core.CreateInteractiveAgentTaskCommand;
import com.bytequay.agent.core.InvalidAgentRequestException;
import com.bytequay.agent.core.ProvisionWorktreeCommand;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.UpdateAgentTaskDefaultsCommand;
import com.bytequay.agent.core.WorkspaceLease;
import com.bytequay.agent.core.WorktreeService;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
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
    private final WorktreeService worktreeService;
    private final AgentResourceService resourceService;
    private final AgentDriverRegistry driverRegistry;
    private final Clock clock;

    public DefaultAgentTaskService(
            AgentTaskStore taskStore,
            AgentWorkspaceService workspaceService,
            WorktreeService worktreeService,
            AgentResourceService resourceService,
            AgentDriverRegistry driverRegistry,
            Clock clock)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.workspaceService = requireNonNull(workspaceService, "workspaceService is null");
        this.worktreeService = requireNonNull(worktreeService, "worktreeService is null");
        this.resourceService = requireNonNull(resourceService, "resourceService is null");
        this.driverRegistry = requireNonNull(driverRegistry, "driverRegistry is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    public AgentTask createInteractive(CreateInteractiveAgentTaskCommand command)
    {
        requireNonNull(command, "command is null");
        requireReady(command.laneId(), command.defaults().permissionPolicy());
        if (taskStore.find(command.taskId()).isPresent()) {
            throw new AgentConflictException("Task already exists: " + command.taskId());
        }

        TaskWorktree worktree = worktreeService.provision(new ProvisionWorktreeCommand(
                command.taskId(),
                command.workspaceId(),
                command.physicalRepository(),
                command.startCommit()));
        try {
            resourceService.allocate(command.taskId());
            Instant now = Instant.now(clock);
            AgentTask task = AgentTask.created(
                    command.taskId(),
                    true,
                    command.title(),
                    command.laneId(),
                    worktree,
                    command.defaults(),
                    now);
            taskStore.insert(task);
            return task;
        }
        catch (RuntimeException failure) {
            rollbackManagedCreation(command.taskId(), worktree, failure);
            throw failure;
        }
    }

    @Override
    public AgentTask create(CreateAgentTaskCommand command)
    {
        requireNonNull(command, "command is null");
        requireReady(command.laneId(), command.permissionPolicy());
        TaskId taskId = TaskId.random();
        WorkspaceLease lease = workspaceService.acquire(
                taskId,
                command.worktree(),
                command.repository());
        try {
            resourceService.allocate(taskId);
            Instant now = Instant.now(clock);
            AgentTask task = AgentTask.created(
                    taskId,
                    command.workspaceId(),
                    command.interactive(),
                    command.title(),
                    command.laneId(),
                    command.repository(),
                    lease.worktree(),
                    command.defaults(),
                    now);
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
    public AgentTask getInteractive(String workspaceId, TaskId taskId)
    {
        checkWorkspaceId(workspaceId);
        AgentTask task = get(taskId);
        if (!task.interactive() || task.workspaceId().stream().noneMatch(workspaceId::equals)) {
            throw new AgentTaskNotFoundException(taskId);
        }
        return task;
    }

    @Override
    public AgentTask updateDefaults(UpdateAgentTaskDefaultsCommand command)
    {
        requireNonNull(command, "command is null");
        AgentTask task = get(command.taskId());
        if (task.version() != command.expectedVersion()) {
            throw new AgentConflictException("Task version changed: " + task.id());
        }
        AgentDriver driver = driverRegistry.require(task.laneId());
        if (!driver.capabilities().permissionPolicies().contains(command.defaults().permissionPolicy())) {
            throw new AgentUnavailableException(
                    "Agent lane " + task.laneId() + " cannot enforce " + command.defaults().permissionPolicy());
        }
        return taskStore.update(task.withDefaults(command.defaults(), Instant.now(clock)));
    }

    @Override
    public List<AgentTask> list(int limit)
    {
        if (limit <= 0) {
            throw new InvalidAgentRequestException("limit must be positive");
        }
        return taskStore.listRecent(limit);
    }

    @Override
    public List<AgentTask> list(String workspaceId, int limit)
    {
        checkWorkspaceId(workspaceId);
        if (limit <= 0) {
            throw new InvalidAgentRequestException("limit must be positive");
        }
        return taskStore.listRecentInteractive(workspaceId, limit);
    }

    private static void checkWorkspaceId(String workspaceId)
    {
        if (workspaceId == null || workspaceId.isBlank()) {
            throw new InvalidAgentRequestException("workspaceId must not be blank");
        }
    }

    private void requireReady(AgentLaneId laneId, AgentPermissionPolicy permissionPolicy)
    {
        AgentDriver driver = driverRegistry.require(laneId);
        if (!driver.capabilities().permissionPolicies().contains(permissionPolicy)) {
            throw new AgentUnavailableException("Agent lane " + laneId + " cannot enforce " + permissionPolicy);
        }
        AgentProviderStatus status = driver.probe();
        if (!status.available()) {
            throw new AgentUnavailableException(status.diagnostic());
        }
    }

    private void rollbackManagedCreation(TaskId taskId, TaskWorktree worktree, RuntimeException failure)
    {
        try {
            if (taskStore.find(taskId).isPresent()) {
                return;
            }
        }
        catch (RuntimeException ownershipFailure) {
            failure.addSuppressed(ownershipFailure);
            return;
        }
        rollbackResources(taskId, failure);
        try {
            worktreeService.cleanup(worktree);
        }
        catch (RuntimeException rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
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
