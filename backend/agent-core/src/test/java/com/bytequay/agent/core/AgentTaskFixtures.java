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
import java.time.Instant;
import java.util.Optional;

public final class AgentTaskFixtures
{
    private AgentTaskFixtures() {}

    public static AgentTask created(
            TaskId id,
            Optional<String> workspaceId,
            boolean interactive,
            String title,
            AgentLaneId laneId,
            RepositoryIdentity repository,
            Path worktree,
            AgentTaskDefaults defaults,
            Instant now)
    {
        return new AgentTask(
                id,
                workspaceId,
                interactive,
                Optional.empty(),
                title,
                TitleSource.USER,
                laneId,
                repository,
                worktree,
                Optional.empty(),
                defaults,
                TaskState.OPEN,
                Optional.empty(),
                0,
                now,
                now,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                AgentMetrics.empty(),
                Optional.empty(),
                Optional.empty(),
                CleanupState.RETAINED,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                false,
                false);
    }

    public static AgentTask created(
            TaskId id,
            boolean interactive,
            String title,
            TitleSource titleSource,
            AgentLaneId laneId,
            TaskWorktree taskWorktree,
            AgentTaskDefaults defaults,
            Instant now)
    {
        return AgentTask.created(id, interactive, Optional.empty(), title, titleSource, laneId, taskWorktree, defaults, now);
    }

    public static AgentTask created(
            TaskId id,
            Optional<String> workspaceId,
            boolean interactive,
            String title,
            AgentLaneId laneId,
            RepositoryIdentity repository,
            Path worktree,
            AgentPermissionPolicy permissionPolicy,
            Instant now)
    {
        return created(id, workspaceId, interactive, title, laneId, repository, worktree, AgentTaskDefaults.forPermission(permissionPolicy), now);
    }
}
