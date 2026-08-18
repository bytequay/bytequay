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
import com.bytequay.agent.core.AgentStorageException;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentWorkspaceService;
import com.bytequay.agent.core.CreateAgentTaskCommand;
import com.bytequay.agent.core.InvalidAgentRequestException;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskResources;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.WorkspaceLease;
import com.bytequay.agent.core.WorkspaceSnapshot;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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

    @TempDir
    private Path temporaryDirectory;

    private InMemoryAgentStores stores;
    private RecordingWorkspaceService workspace;
    private RecordingResourceService resources;
    private DeterministicAgentDriver driver;
    private DefaultAgentTaskService service;

    @BeforeEach
    void setUp()
    {
        stores = new InMemoryAgentStores();
        workspace = new RecordingWorkspaceService(temporaryDirectory.resolve("worktree").toAbsolutePath());
        resources = new RecordingResourceService(temporaryDirectory.resolve("runtime").toAbsolutePath());
        driver = new DeterministicAgentDriver();
        service = new DefaultAgentTaskService(
                stores,
                workspace,
                resources,
                new StaticAgentDriverRegistry(ImmutableList.of(driver)),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void createsReadyTaskAndExposesDurableReads()
    {
        AgentTask task = service.create(command());

        assertThat(task.state()).isEqualTo(TaskState.READY);
        assertThat(task.worktree()).isEqualTo(workspace.worktree);
        assertThat(resources.allocated).contains(task.id());
        assertThat(workspace.released).isEmpty();
        assertThat(service.get(task.id())).isEqualTo(task);
        assertThat(service.list(1)).containsExactly(task);
        assertThatThrownBy(() -> service.list(0))
                .isInstanceOf(InvalidAgentRequestException.class);
    }

    @Test
    void rollsBackResourcesAndLeaseWhenProviderIsUnavailable()
    {
        driver.unavailable();

        assertThatThrownBy(() -> service.create(command()))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("unavailable");
        assertThat(resources.deleted).containsExactlyElementsOf(resources.allocated);
        assertThat(workspace.released).hasSize(1);
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

    @Test
    void rejectsPermissionPolicyTheLaneCannotEnforceBeforeAllocatingResources()
    {
        assertThatThrownBy(() -> service.create(command(AgentPermissionPolicy.CI_AUTOFIX)))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("cannot enforce CI_AUTOFIX");

        assertThat(resources.allocated).isEmpty();
        assertThat(workspace.released).isEmpty();
        assertThat(stores.tasks()).isEmpty();
    }

    private CreateAgentTaskCommand command()
    {
        return command(AgentPermissionPolicy.MANUAL);
    }

    private CreateAgentTaskCommand command(AgentPermissionPolicy permissionPolicy)
    {
        return new CreateAgentTaskCommand(
                DeterministicAgentDriver.LANE_ID,
                workspace.worktree,
                REPOSITORY,
                "Test task",
                permissionPolicy);
    }

    private static final class RecordingWorkspaceService
            implements AgentWorkspaceService
    {
        private final Path worktree;
        private final List<TaskId> released = new ArrayList<>();

        private RecordingWorkspaceService(Path worktree)
        {
            this.worktree = worktree;
        }

        @Override
        public WorkspaceLease acquire(TaskId taskId, Path requestedPath, RepositoryIdentity repository)
        {
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
