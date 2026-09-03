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
package com.bytequay.app.agent;

import com.bytequay.agent.core.AgentStorageException;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.CreateInteractiveAgentTaskCommand;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.git.GitCommandRunner.Result;
import com.bytequay.app.service.settings.ModelSettingsService;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import org.springframework.stereotype.Component;

import static java.util.Objects.requireNonNull;

/** Resolves trusted workspace Git facts before core creates an interactive task. */
@Component
public final class InteractiveAgentTaskCreator
{
    private final AgentTaskService tasks;
    private final AgentDriverRegistry drivers;
    private final ModelSettingsService models;
    private final WorkspaceRepositoryService workspaces;
    private final GitCommandRunner git;

    public InteractiveAgentTaskCreator(
            AgentTaskService tasks,
            AgentDriverRegistry drivers,
            ModelSettingsService models,
            WorkspaceRepositoryService workspaces,
            GitCommandRunner git)
    {
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.drivers = requireNonNull(drivers, "drivers is null");
        this.models = requireNonNull(models, "models is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.git = requireNonNull(git, "git is null");
    }

    public AgentTask create(
            String workspaceId,
            String message,
            AgentLaneId laneId,
            AgentTaskDefaults defaults)
    {
        requireNonNull(defaults, "defaults is null");
        requireReady(laneId, defaults);
        models.validateSelection(laneId.value(), defaults.model().orElseThrow(() -> new IllegalArgumentException("model must not be blank")), defaults.effort());
        WorkspaceRepositoryContext workspace = workspaces.requireReadyRepository(workspaceId);
        String startRef = "refs/remotes/origin/" + workspace.defaultBranch() + "^{commit}";
        Result head = git.run(workspace.repository(), "rev-parse", "--verify", startRef);
        if (!head.successful() || head.truncated() || head.output().isBlank()) {
            throw new AgentStorageException("Cannot resolve the workspace start commit");
        }
        return tasks.createInteractive(new CreateInteractiveAgentTaskCommand(
                TaskId.random(),
                workspace.workspaceId(),
                laneId,
                new RepositoryIdentity(
                        workspace.selectedRepository().owner(),
                        workspace.selectedRepository().repo()),
                head.output().strip(),
                message,
                defaults));
    }

    private void requireReady(AgentLaneId laneId, AgentTaskDefaults defaults)
    {
        AgentDriver driver = drivers.require(laneId);
        if (!driver.capabilities().permissionPolicies().contains(defaults.permissionPolicy())) {
            throw new AgentUnavailableException("Agent lane " + laneId + " cannot enforce " + defaults.permissionPolicy());
        }
        AgentProviderStatus status = driver.probe();
        if (!status.available()) {
            throw new AgentUnavailableException(status.diagnostic());
        }
    }
}
