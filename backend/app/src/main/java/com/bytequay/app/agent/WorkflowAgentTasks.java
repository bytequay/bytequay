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

import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskNotFoundException;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.core.domain.RepoRef;

import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Provisioning steps shared by the durable workflows that own an agent task.
 *
 * <p>Each workflow keeps its own task validation, because the facts a run
 * freezes about its worktree differ. Only the steps that are identical live
 * here, parameterized by the workflow's own conflict exception.
 */
public final class WorkflowAgentTasks
{
    private WorkflowAgentTasks() {}

    /**
     * Creates the task, tolerating a concurrent creator that won the race.
     *
     * <p>A conflict is only survivable if the task now exists; otherwise the
     * conflict describes a real problem and is rethrown.
     */
    public static AgentTask createOrGet(AgentTaskService tasks, TaskId taskId, Supplier<AgentTask> create)
    {
        try {
            return create.get();
        }
        catch (AgentConflictException conflict) {
            try {
                return tasks.get(taskId);
            }
            catch (AgentTaskNotFoundException missing) {
                throw conflict;
            }
        }
    }

    public static RepositoryIdentity repositoryIdentity(RepoRef repository)
    {
        return new RepositoryIdentity(repository.owner(), repository.repo());
    }

    /** Rejects provisioning against a repository the workspace no longer selects. */
    public static void requireSelectedRepository(
            RepositoryIdentity selectedRepository,
            RepositoryIdentity expectedRepository,
            Function<String, RuntimeException> conflict)
    {
        if (!selectedRepository.equals(expectedRepository)) {
            throw conflict.apply("Workspace repository selection changed before task provisioning");
        }
    }

    /** Requires a lane that both enforces {@code policy} and is available now. */
    public static void requireReady(
            AgentDriverRegistry drivers,
            AgentLaneId laneId,
            AgentPermissionPolicy policy)
    {
        AgentDriver driver = drivers.require(laneId);
        if (!driver.capabilities().permissionPolicies().contains(policy)) {
            throw new AgentUnavailableException("Agent lane " + laneId + " cannot enforce " + policy);
        }
        AgentProviderStatus status = driver.probe();
        if (!status.available()) {
            throw new AgentUnavailableException(status.diagnostic());
        }
    }
}
