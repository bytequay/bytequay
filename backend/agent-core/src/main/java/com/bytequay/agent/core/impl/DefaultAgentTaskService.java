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
import com.bytequay.agent.core.ArchiveAgentTaskCommand;
import com.bytequay.agent.core.CreateAgentTaskCommand;
import com.bytequay.agent.core.CreateInteractiveAgentTaskCommand;
import com.bytequay.agent.core.InvalidAgentRequestException;
import com.bytequay.agent.core.ProvisionWorktreeCommand;
import com.bytequay.agent.core.RenameAgentTaskCommand;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskNames;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TaskTitles;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.UpdateAgentTaskDefaultsCommand;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.agent.core.WorktreeService;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentProviderUnavailableException;
import com.bytequay.agent.spi.AgentUnavailableException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

import static com.bytequay.agent.core.impl.AgentFailures.suppressFailure;
import static java.util.Objects.requireNonNull;

public final class DefaultAgentTaskService
        implements AgentTaskService
{
    private static final System.Logger sendTiming = System.getLogger("com.bytequay.agent.SendTiming");

    private final AgentTaskStore taskStore;
    private final WorktreeService worktreeService;
    private final AgentResourceService resourceService;
    private final AgentDriverRegistry driverRegistry;
    private final Clock clock;

    public DefaultAgentTaskService(AgentTaskStore taskStore, WorktreeService worktreeService, AgentResourceService resourceService, AgentDriverRegistry driverRegistry, Clock clock)
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
        return createInteractive(command, taskStore::insert);
    }

    @Override
    public AgentTask createInteractive(CreateInteractiveAgentTaskCommand command, Consumer<AgentTask> persistTask)
    {
        requireNonNull(command, "command is null");
        requireNonNull(persistTask, "persistTask is null");
        driverRegistry.checkWorkspaceAccess(command.laneId(), Optional.of(command.workspaceId()));
        sendTiming.log(System.Logger.Level.DEBUG,
                () -> "send-timing phase=task.core_readiness.begin epochMs=" + System.currentTimeMillis() + " taskId=" + command.taskId() + " turnId=-");
        if (command.useTrustedCreationReadiness()) {
            // Only the application creator supplies this after validating the same lane and defaults
            // before repository preparation. Workspace access and policy enforcement still apply here.
            requirePolicy(command.laneId(), command.defaults().permissionPolicy());
        }
        else {
            requireReady(command.laneId(), command.defaults().permissionPolicy());
        }
        sendTiming.log(System.Logger.Level.DEBUG,
                () -> "send-timing phase=task.core_readiness.end epochMs=" + System.currentTimeMillis() + " taskId=" + command.taskId() + " turnId=-");
        if (taskStore.find(command.taskId()).isPresent()) {
            throw new AgentConflictException("Task already exists: " + command.taskId());
        }

        sendTiming.log(System.Logger.Level.DEBUG,
                () -> "send-timing phase=task.worktree.begin epochMs=" + System.currentTimeMillis() + " taskId=" + command.taskId() + " turnId=-");
        TaskWorktree worktree = worktreeService.provision(new ProvisionWorktreeCommand(
                command.taskId(),
                command.workspaceId(),
                command.physicalRepository(),
                command.startCommit(),
                command.generatedBranch()));
        sendTiming.log(System.Logger.Level.DEBUG, () -> "send-timing phase=task.worktree.end epochMs=" + System.currentTimeMillis() + " taskId=" + command.taskId() + " turnId=-");
        return createManaged(
                command.taskId(),
                true,
                command.names().map(TaskNames::title).orElseGet(() -> TaskTitles.provisional(command.message())),
                command.names().isPresent() ? TitleSource.AGENT : TitleSource.PROVISIONAL,
                command.laneId(),
                command.defaults(),
                Optional.of(command.creationFingerprint()),
                worktree,
                persistTask);
    }

    @Override
    public AgentTask create(CreateAgentTaskCommand command)
    {
        requireNonNull(command, "command is null");
        driverRegistry.checkWorkspaceAccess(command.laneId(), Optional.of(command.workspaceId()));
        requireReady(command.laneId(), command.defaults().permissionPolicy());
        return prepare(command);
    }

    @Override
    public AgentTask prepare(CreateAgentTaskCommand command)
    {
        requireNonNull(command, "command is null");
        driverRegistry.checkWorkspaceAccess(command.laneId(), Optional.of(command.workspaceId()));
        driverRegistry.require(command.laneId());
        if (taskStore.find(command.taskId()).isPresent()) {
            throw new AgentConflictException("Task already exists: " + command.taskId());
        }
        TaskWorktree worktree = worktreeService.provision(new ProvisionWorktreeCommand(
                command.taskId(),
                command.workspaceId(),
                command.physicalRepository(),
                command.startCommit(),
                command.generatedBranch()));
        return createManaged(command.taskId(), false, command.title(), TitleSource.USER, command.laneId(), command.defaults(), Optional.empty(), worktree, taskStore::insert);
    }

    @Override
    public AgentTask createShared(CreateAgentTaskCommand command, TaskId sourceTaskId)
    {
        AgentTask source = getInteractive(command.workspaceId(), sourceTaskId);
        if (source.state() != TaskState.OPEN || source.worktreeRemovedAt().isPresent() || !source.ownsWorktree()) {
            throw new AgentConflictException("The development working copy is unavailable");
        }
        // A completion claim means the checkout is being removed; a turn or local run only delays the reviewer's first run.
        source.worktreeMutationClaim().filter(claim -> claim.kind() == WorktreeMutationClaim.Kind.COMPLETION).ifPresent(claim -> {
            throw new AgentConflictException(claim.conflictMessage());
        });
        if (!source.repository().equals(command.physicalRepository())) {
            throw new InvalidAgentRequestException("Shared task belongs to another repository");
        }
        driverRegistry.checkWorkspaceAccess(command.laneId(), source.workspaceId());
        requireReady(command.laneId(), command.defaults().permissionPolicy());
        if (taskStore.find(command.taskId()).isPresent()) {
            throw new AgentConflictException("Task already exists: " + command.taskId());
        }
        return createManaged(command.taskId(), false, command.title(), TitleSource.USER, command.laneId(), command.defaults(), Optional.empty(),
                source.taskWorktree().orElseThrow(), taskStore::insert);
    }

    @Override
    public AgentTask get(TaskId taskId)
    {
        requireNonNull(taskId, "taskId is null");
        return taskStore.require(taskId);
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
        var lane = command.defaults().laneId().orElse(task.laneId());
        driverRegistry.checkWorkspaceAccess(lane, task.workspaceId());
        AgentDriver driver = driverRegistry.require(lane);
        if (!driver.capabilities().permissionPolicies().contains(command.defaults().permissionPolicy())) {
            throw new AgentUnavailableException("Agent lane " + lane + " cannot enforce " + command.defaults().permissionPolicy());
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
    public AgentTask archive(ArchiveAgentTaskCommand command)
    {
        requireNonNull(command, "command is null");
        AgentTask task = get(command.taskId());
        if (task.version() != command.expectedVersion()) {
            throw new AgentConflictException("Task version changed: " + task.id());
        }
        return taskStore.update(task.withArchived(command.archived(), Instant.now(clock)));
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
        AgentDriver driver = requirePolicy(laneId, permissionPolicy);
        AgentProviderStatus status = driver.probe();
        if (!status.available()) {
            throw new AgentProviderUnavailableException(status);
        }
    }

    private AgentDriver requirePolicy(AgentLaneId laneId, AgentPermissionPolicy permissionPolicy)
    {
        AgentDriver driver = driverRegistry.require(laneId);
        if (!driver.capabilities().permissionPolicies().contains(permissionPolicy)) {
            throw new AgentUnavailableException("Agent lane " + laneId + " cannot enforce " + permissionPolicy);
        }
        return driver;
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
        if (worktree.taskId().equals(taskId)) {
            suppressFailure(failure, () -> worktreeService.cleanup(worktree));
        }
    }

    private AgentTask createManaged(
            TaskId taskId,
            boolean interactive,
            String title,
            TitleSource titleSource,
            AgentLaneId laneId,
            AgentTaskDefaults defaults,
            Optional<String> creationFingerprint,
            TaskWorktree worktree,
            Consumer<AgentTask> persistTask)
    {
        AgentTask task;
        try {
            sendTiming.log(System.Logger.Level.DEBUG, () -> "send-timing phase=task.resources.begin epochMs=" + System.currentTimeMillis() + " taskId=" + taskId + " turnId=-");
            resourceService.allocate(taskId);
            sendTiming.log(System.Logger.Level.DEBUG, () -> "send-timing phase=task.resources.end epochMs=" + System.currentTimeMillis() + " taskId=" + taskId + " turnId=-");
            task = AgentTask.created(taskId, interactive, creationFingerprint, title, titleSource, laneId, worktree, defaults, Instant.now(clock));
            sendTiming.log(System.Logger.Level.DEBUG, () -> "send-timing phase=task.persist.begin epochMs=" + System.currentTimeMillis() + " taskId=" + taskId + " turnId=-");
            persistTask.accept(task);
            sendTiming.log(System.Logger.Level.DEBUG, () -> "send-timing phase=task.persist.end epochMs=" + System.currentTimeMillis() + " taskId=" + taskId + " turnId=-");
        }
        catch (RuntimeException failure) {
            rollbackManagedCreation(taskId, worktree, failure);
            throw failure;
        }
        return task;
    }
}
