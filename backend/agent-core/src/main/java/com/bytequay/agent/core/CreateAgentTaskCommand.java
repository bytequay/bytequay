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

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;
import static com.bytequay.agent.spi.AgentChecks.checkPresent;

public record CreateAgentTaskCommand(
        AgentLaneId laneId,
        Path worktree,
        RepositoryIdentity repository,
        String title,
        AgentPermissionPolicy permissionPolicy)
{
    public CreateAgentTaskCommand
    {
        checkPresent(laneId, "laneId");
        checkPresent(worktree, "worktree");
        checkPresent(repository, "repository");
        checkNotBlank(title, "title");
        checkPresent(permissionPolicy, "permissionPolicy");
    }
}
