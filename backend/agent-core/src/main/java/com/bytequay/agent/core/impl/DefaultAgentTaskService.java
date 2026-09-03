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
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTaskNotFoundException;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.CreateAgentTaskCommand;
import com.bytequay.agent.core.CreateInteractiveAgentTaskCommand;
import com.bytequay.agent.core.InvalidAgentRequestException;
import com.bytequay.agent.core.ProvisionWorktreeCommand;
import com.bytequay.agent.core.RenameAgentTaskCommand;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskTitles;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.UpdateAgentTaskDefaultsCommand;
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

import static com.bytequay.agent.core.impl.AgentFailures.suppressFailure;
import static java.util.Objects.requireNonNull;

public final class DefaultAgentTaskService
        implements AgentTaskService
{
    private final AgentTaskStore taskStore;
    private final WorktreeService worktreeService;
    private final AgentResourceService resourceService;
    private final AgentDriverRegistry driverRegistry;
    private final Clock clock;

    public DefaultAgentTaskService(
            AgentTaskStore taskStore,
            WorktreeService worktreeService,
            AgentResourceService resourceService,
            AgentDriverRegistry driverRegistry,
            Clock clock)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
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
        // Named after its first message until the agent's first answer proposes something better.
        return createManaged(
                command.taskId(),
                true,
                TaskTitles.provisional(command.message()),
                TitleSource.PROVISIONAL,
                command.laneId(),
                command.defaults(),
                worktree);
    }

    @Override
    public AgentTask create(CreateAgentTaskCommand command)
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
        return createManaged(command.taskId(), false, command.title(), TitleSource.USER, command.laneId(), command.defaults(), worktree);
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
    public AgentTask rename(RenameAgentTaskCommand command)
    {
        requireNonNull(command, "command is null");
        AgentTask task = get(command.taskId());
        if (task.version() != command.expectedVersion()) {
            throw new AgentConflictException("Task version changed: " + task.id());
        }
        return taskStore.update(task.withTitle(command.title().strip(), TitleSource.USER, Instant.now(clock)));
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
        suppressFailure(failure, () -> resourceService.delete(taskId));
        suppressFailure(failure, () -> worktreeService.cleanup(worktree));
    }

    private AgentTask createManaged(
            TaskId taskId,
            boolean interactive,
            String title,
            TitleSource titleSource,
            AgentLaneId laneId,
            AgentTaskDefaults defaults,
            TaskWorktree worktree)
    {
        try {
            resourceService.allocate(taskId);
            AgentTask task = AgentTask.created(
                    taskId,
                    interactive,
                    title,
                    titleSource,
                    laneId,
                    worktree,
                    defaults,
                    Instant.now(clock));
            taskStore.insert(task);
            return task;
        }
        catch (RuntimeException failure) {
            rollbackManagedCreation(taskId, worktree, failure);
            throw failure;
        }
    }
}
