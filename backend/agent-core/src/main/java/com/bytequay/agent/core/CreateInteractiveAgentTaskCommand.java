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
package com.bytequay.agent.core;

import com.bytequay.agent.spi.AgentLaneId;

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;
import static java.util.Objects.requireNonNull;

/** Path-free input for one workspace-owned interactive task. */
public record CreateInteractiveAgentTaskCommand(
        TaskId taskId,
        String workspaceId,
        AgentLaneId laneId,
        RepositoryIdentity physicalRepository,
        String startCommit,
        String message,
        AgentTaskDefaults defaults)
{
    public CreateInteractiveAgentTaskCommand
    {
        requireNonNull(taskId, "taskId is null");
        checkNotBlank(workspaceId, "workspaceId");
        requireNonNull(laneId, "laneId is null");
        requireNonNull(physicalRepository, "physicalRepository is null");
        ProvisionWorktreeCommand.checkObjectId(startCommit, "startCommit");
        checkNotBlank(message, "message");
        requireNonNull(defaults, "defaults is null");
    }
}
