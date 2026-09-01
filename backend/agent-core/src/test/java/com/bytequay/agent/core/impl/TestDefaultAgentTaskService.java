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
import com.bytequay.agent.core.AgentStorageException;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTaskNotFoundException;
import com.bytequay.agent.core.AgentWorkspaceService;
import com.bytequay.agent.core.CreateAgentTaskCommand;
import com.bytequay.agent.core.CreateInteractiveAgentTaskCommand;
import com.bytequay.agent.core.InvalidAgentRequestException;
import com.bytequay.agent.core.ProvisionWorktreeCommand;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskResources;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.UpdateAgentTaskDefaultsCommand;
import com.bytequay.agent.core.WorkspaceLease;
import com.bytequay.agent.core.WorkspaceSnapshot;
import com.bytequay.agent.core.WorktreeService;
import com.bytequay.agent.core.WorktreeSnapshot;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestDefaultAgentTaskService
{
    private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");
    private static final RepositoryIdentity REPOSITORY = new RepositoryIdentity("bytequay", "bytequay");
    private static final String WORKSPACE_ID = "workspace-a";

    @TempDir
    private Path temporaryDirectory;

    private InMemoryAgentStores stores;
    private RecordingWorkspaceService workspace;
    private RecordingWorktreeService managedWorktrees;
    private RecordingResourceService resources;
    private DeterministicAgentDriver driver;
    private DefaultAgentTaskService service;

    @BeforeEach
    void setUp()
    {
        stores = new InMemoryAgentStores();
        workspace = new RecordingWorkspaceService(temporaryDirectory.resolve("worktree").toAbsolutePath());
        managedWorktrees = new RecordingWorktreeService(
                temporaryDirectory.resolve("managed").toAbsolutePath());
        resources = new RecordingResourceService(temporaryDirectory.resolve("runtime").toAbsolutePath());
        driver = new DeterministicAgentDriver();
        service = new DefaultAgentTaskService(
                stores,
                workspace,
                managedWorktrees,
                resources,
                new StaticAgentDriverRegistry(ImmutableList.of(driver)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createsInteractiveTaskWithPersistedManagedWorktreeFacts()
    {
        TaskId taskId = TaskId.random();

        AgentTask task = service.createInteractive(interactiveCommand(taskId));

        assertThat(task.state()).isEqualTo(TaskState.OPEN);
        assertThat(task.id()).isEqualTo(taskId);
        assertThat(task.workspaceId()).contains(WORKSPACE_ID);
        assertThat(task.interactive()).isTrue();
        assertThat(task.taskWorktree()).contains(managedWorktrees.provisioned.getFirst());
        assertThat(resources.allocated).containsExactly(taskId);
        assertThat(managedWorktrees.cleaned).isEmpty();
    }

    @Test
    void checksReadinessBeforeProvisioningManagedWorktree()
    {
        driver.unavailable();

        assertThatThrownBy(() -> service.createInteractive(interactiveCommand(TaskId.random())))
                .isInstanceOf(AgentUnavailableException.class);

        assertThat(managedWorktrees.provisioned).isEmpty();
        assertThat(resources.allocated).isEmpty();
        assertThat(stores.tasks()).isEmpty();
    }

    @Test
    void removesFreshManagedWorktreeAfterResourceFailure()
    {
        resources.failAllocation = true;

        assertThatThrownBy(() -> service.createInteractive(interactiveCommand(TaskId.random())))
                .isInstanceOf(AgentStorageException.class);

        assertThat(managedWorktrees.provisioned).hasSize(1);
        assertThat(managedWorktrees.cleaned).containsExactly(managedWorktrees.provisioned.getFirst());
        assertThat(stores.tasks()).isEmpty();
    }

    @Test
    void leavesProvisioningConflictForInspection()
    {
        managedWorktrees.provisionFailure = new AgentConflictException("conflicting worktree");

        assertThatThrownBy(() -> service.createInteractive(interactiveCommand(TaskId.random())))
                .isSameAs(managedWorktrees.provisionFailure);

        assertThat(managedWorktrees.cleaned).isEmpty();
        assertThat(resources.allocated).isEmpty();
    }

    @Test
    void removesFreshResourcesAndWorktreeAfterInsertFailure()
    {
        TaskId taskId = TaskId.random();
        stores.failTaskInsert(new AgentStorageException("insert failed"), false);

        assertThatThrownBy(() -> service.createInteractive(interactiveCommand(taskId)))
                .isInstanceOf(AgentStorageException.class)
                .hasMessageContaining("insert failed");

        assertThat(resources.deleted).containsExactly(taskId);
        assertThat(managedWorktrees.cleaned).containsExactly(managedWorktrees.provisioned.getFirst());
    }

    @Test
    void preservesResourcesWhenFailedInsertMayHavePersistedOwnership()
    {
        TaskId taskId = TaskId.random();
        stores.failTaskInsert(new AgentStorageException("insert result unknown"), true);

        assertThatThrownBy(() -> service.createInteractive(interactiveCommand(taskId)))
                .isInstanceOf(AgentStorageException.class)
                .hasMessageContaining("unknown");

        assertThat(resources.deleted).isEmpty();
        assertThat(managedWorktrees.cleaned).isEmpty();
        assertThat(stores.tasks()).hasSize(1);
    }

    @Test
    void preservesResourcesWhenOwnershipCannotBeReadAfterInsertFailure()
    {
        TaskId taskId = TaskId.random();
        stores.failTaskInsert(new AgentStorageException("insert failed"), false);
        stores.failTaskFind(2, new AgentStorageException("read failed"));

        assertThatThrownBy(() -> service.createInteractive(interactiveCommand(taskId)))
                .isInstanceOf(AgentStorageException.class)
                .satisfies(failure -> assertThat(failure.getSuppressed())
                        .extracting(Throwable::getMessage)
                        .containsExactly("read failed"));

        assertThat(resources.deleted).isEmpty();
        assertThat(managedWorktrees.cleaned).isEmpty();
    }

    @Test
    void createsReadyTaskAndExposesDurableReads()
    {
        AgentTask task = service.create(command());

        assertThat(task.state()).isEqualTo(TaskState.OPEN);
        assertThat(task.workspaceId()).contains(WORKSPACE_ID);
        assertThat(task.interactive()).isTrue();
        assertThat(task.worktree()).isEqualTo(workspace.worktree);
        assertThat(resources.allocated).contains(task.id());
        assertThat(workspace.released).isEmpty();
        assertThat(service.get(task.id())).isEqualTo(task);
        assertThat(service.getInteractive(WORKSPACE_ID, task.id())).isEqualTo(task);
        assertThat(service.list(1)).containsExactly(task);
        assertThat(service.list(WORKSPACE_ID, 1)).containsExactly(task);
        assertThatThrownBy(() -> service.getInteractive("workspace-b", task.id()))
                .isInstanceOf(AgentTaskNotFoundException.class);
        assertThatThrownBy(() -> service.list(0))
                .isInstanceOf(InvalidAgentRequestException.class);
        assertThatThrownBy(() -> service.list(" ", 1))
                .isInstanceOf(InvalidAgentRequestException.class);
    }

    @Test
    void keepsInternalTasksOutOfWorkspaceReads()
    {
        AgentTask task = service.create(new CreateAgentTaskCommand(
                DeterministicAgentDriver.LANE_ID,
                workspace.worktree,
                REPOSITORY,
                "Internal task",
                AgentPermissionPolicy.AUTO,
                Optional.empty(),
                false));

        assertThat(service.list(WORKSPACE_ID, 10)).isEmpty();
        assertThatThrownBy(() -> service.getInteractive(WORKSPACE_ID, task.id()))
                .isInstanceOf(AgentTaskNotFoundException.class);
    }

    @Test
    void updatesDefaultsWithTheObservedTaskVersion()
    {
        AgentTask created = service.create(command());
        AgentTaskDefaults defaults = new AgentTaskDefaults(
                Optional.of("model-a"),
                Optional.of("high"),
                AgentPermissionPolicy.AUTO);

        AgentTask updated = service.updateDefaults(new UpdateAgentTaskDefaultsCommand(
                created.id(),
                created.version(),
                defaults));

        assertThat(updated.defaults()).isEqualTo(defaults);
        assertThat(updated.version()).isEqualTo(created.version() + 1);
        assertThat(service.get(created.id())).isEqualTo(updated);
        assertThatThrownBy(() -> service.updateDefaults(new UpdateAgentTaskDefaultsCommand(
                created.id(),
                created.version(),
                AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO))))
                .isInstanceOf(AgentConflictException.class)
                .hasMessageContaining("version changed");
    }

    @Test
    void rejectsUnavailableProviderBeforeAllocatingTaskResources()
    {
        driver.unavailable();

        assertThatThrownBy(() -> service.create(command()))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("unavailable");
        assertThat(resources.allocated).isEmpty();
        assertThat(resources.deleted).isEmpty();
        assertThat(workspace.acquired).isEmpty();
        assertThat(workspace.released).isEmpty();
        assertThat(stores.tasks()).isEmpty();
    }

    @ParameterizedTest
    @EnumSource(
            value = AgentProviderStatus.Readiness.class,
            names = {"AUTHENTICATION_REQUIRED", "CLI_MISSING", "UNSUPPORTED", "TEMPORARILY_UNAVAILABLE"})
    void rejectsNonReadyProviderStatesBeforeAllocatingAnything(AgentProviderStatus.Readiness readiness)
    {
        driver.readiness(readiness);

        assertThatThrownBy(() -> service.create(command()))
                .isInstanceOf(AgentUnavailableException.class);
        assertThat(resources.allocated).isEmpty();
        assertThat(resources.deleted).isEmpty();
        assertThat(workspace.acquired).isEmpty();
        assertThat(workspace.released).isEmpty();
        assertThat(stores.tasks()).isEmpty();
    }

    @Test
    void deletesPartiallyAllocatedResourcesAfterAllocationFailure()
    {
        resources.failAllocation = true;

        assertThatThrownBy(() -> service.create(command()))
                .isInstanceOf(AgentStorageException.class)
                .hasMessageContaining("partial allocation");
        assertThat(resources.deleted).containsExactlyElementsOf(resources.allocated);
        assertThat(workspace.released).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(value = AgentPermissionPolicy.class, names = {"CI_AUTOFIX", "CHERRY_PICK"})
    void rejectsPermissionPolicyTheLaneCannotEnforceBeforeAllocatingResources(AgentPermissionPolicy permissionPolicy)
    {
        assertThatThrownBy(() -> service.create(command(permissionPolicy)))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("cannot enforce " + permissionPolicy);

        assertThat(resources.allocated).isEmpty();
        assertThat(workspace.released).isEmpty();
        assertThat(stores.tasks()).isEmpty();
    }

    private CreateAgentTaskCommand command()
    {
        return command(AgentPermissionPolicy.AUTO);
    }

    private CreateAgentTaskCommand command(AgentPermissionPolicy permissionPolicy)
    {
        return new CreateAgentTaskCommand(
                DeterministicAgentDriver.LANE_ID,
                workspace.worktree,
                REPOSITORY,
                "Test task",
                permissionPolicy,
                Optional.of(WORKSPACE_ID),
                true);
    }

    private CreateInteractiveAgentTaskCommand interactiveCommand(TaskId taskId)
    {
        return new CreateInteractiveAgentTaskCommand(
                taskId,
                WORKSPACE_ID,
                DeterministicAgentDriver.LANE_ID,
                REPOSITORY,
                "0123456789012345678901234567890123456789",
                "Interactive task",
                AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO));
    }

    private static final class RecordingWorktreeService
            implements WorktreeService
    {
        private final Path worktreeRoot;
        private final List<TaskWorktree> provisioned = new ArrayList<>();
        private final List<TaskWorktree> cleaned = new ArrayList<>();
        private RuntimeException provisionFailure;

        private RecordingWorktreeService(Path worktreeRoot)
        {
            this.worktreeRoot = worktreeRoot;
        }

        @Override
        public TaskWorktree provision(ProvisionWorktreeCommand command)
        {
            if (provisionFailure != null) {
                throw provisionFailure;
            }
            TaskWorktree worktree = new TaskWorktree(
                    command.taskId(),
                    command.workspaceId(),
                    command.physicalRepository(),
                    worktreeRoot.resolve(command.taskId().toString()),
                    "bytequay/task/" + command.taskId(),
                    command.startCommit());
            provisioned.add(worktree);
            return worktree;
        }

        @Override
        public WorktreeSnapshot inspect(TaskWorktree worktree)
        {
            return new WorktreeSnapshot(worktree, worktree.startCommit(), false, false);
        }

        @Override
        public void cleanup(TaskWorktree worktree)
        {
            cleaned.add(worktree);
        }
    }

    private static final class RecordingWorkspaceService
            implements AgentWorkspaceService
    {
        private final Path worktree;
        private final List<TaskId> acquired = new ArrayList<>();
        private final List<TaskId> released = new ArrayList<>();

        private RecordingWorkspaceService(Path worktree)
        {
            this.worktree = worktree;
        }

        @Override
        public WorkspaceLease acquire(TaskId taskId, Path requestedPath, RepositoryIdentity repository)
        {
            acquired.add(taskId);
            return new WorkspaceLease(taskId, worktree, () -> release(taskId));
        }

        @Override
        public WorkspaceLease restore(TaskId taskId, Path canonicalWorktree, RepositoryIdentity repository)
        {
            return new WorkspaceLease(taskId, worktree, () -> release(taskId));
        }

        @Override
        public WorkspaceSnapshot inspect(Path ignored)
        {
            return new WorkspaceSnapshot(
                    worktree,
                    REPOSITORY,
                    Optional.of("main"),
                    "0123456789012345678901234567890123456789",
                    false);
        }

        @Override
        public void release(TaskId taskId)
        {
            released.add(taskId);
        }
    }

    private static final class RecordingResourceService
            implements AgentResourceService
    {
        private final Path root;
        private final List<TaskId> allocated = new ArrayList<>();
        private final List<TaskId> deleted = new ArrayList<>();
        private boolean failAllocation;

        private RecordingResourceService(Path root)
        {
            this.root = root;
        }

        @Override
        public TaskResources allocate(TaskId taskId)
        {
            allocated.add(taskId);
            if (failAllocation) {
                throw new AgentStorageException("partial allocation failed");
            }
            return resources(taskId);
        }

        @Override
        public TaskResources require(TaskId taskId)
        {
            return resources(taskId);
        }

        @Override
        public void delete(TaskId taskId)
        {
            deleted.add(taskId);
        }

        private TaskResources resources(TaskId taskId)
        {
            Path taskRoot = root.resolve(taskId.toString());
            return new TaskResources(
                    taskRoot,
                    taskRoot.resolve("events.jsonl"),
                    taskRoot.resolve("blobs"),
                    taskRoot.resolve("provider/scratch"));
        }
    }
}
