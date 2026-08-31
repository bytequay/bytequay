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
import com.bytequay.agent.spi.AgentPermissionPolicy;

import java.nio.file.Path;
import java.util.Optional;

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;
import static com.bytequay.agent.spi.AgentChecks.checkOptionalText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

public record CreateAgentTaskCommand(
        AgentLaneId laneId,
        Path worktree,
        RepositoryIdentity repository,
        String title,
        AgentTaskDefaults defaults,
        Optional<String> workspaceId,
        boolean interactive)
{
    public CreateAgentTaskCommand
    {
        requireNonNull(laneId, "laneId is null");
        requireNonNull(worktree, "worktree is null");
        requireNonNull(repository, "repository is null");
        checkNotBlank(title, "title");
        requireNonNull(defaults, "defaults is null");
        checkOptionalText(workspaceId, "workspaceId");
        checkArgument(!interactive || workspaceId.isPresent(), "interactive task has no workspaceId");
    }

    public CreateAgentTaskCommand(
            AgentLaneId laneId,
            Path worktree,
            RepositoryIdentity repository,
            String title,
            AgentPermissionPolicy permissionPolicy)
    {
        this(
                laneId,
                worktree,
                repository,
                title,
                AgentTaskDefaults.forPermission(permissionPolicy),
                Optional.empty(),
                false);
    }

    public CreateAgentTaskCommand(
            AgentLaneId laneId,
            Path worktree,
            RepositoryIdentity repository,
            String title,
            AgentPermissionPolicy permissionPolicy,
            Optional<String> workspaceId,
            boolean interactive)
    {
        this(
                laneId,
                worktree,
                repository,
                title,
                AgentTaskDefaults.forPermission(permissionPolicy),
                workspaceId,
                interactive);
    }

    public AgentPermissionPolicy permissionPolicy()
    {
        return defaults.permissionPolicy();
    }
}
