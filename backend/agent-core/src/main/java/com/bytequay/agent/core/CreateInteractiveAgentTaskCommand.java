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

import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkSha;
import static com.bytequay.core.domain.Checks.checkText;
import static java.util.Objects.requireNonNull;

/**
 * Path-free input for one workspace-owned interactive task.
 *
 * @param useTrustedCreationReadiness true only when the application creator has just checked this
 * lane and these defaults in the same creation operation; never accepted from external input
 */
public record CreateInteractiveAgentTaskCommand(
        TaskId taskId,
        String workspaceId,
        AgentLaneId laneId,
        RepositoryIdentity physicalRepository,
        String startCommit,
        String generatedBranch,
        String message,
        AgentTaskDefaults defaults,
        String creationFingerprint,
        Optional<TaskNames> names,
        boolean useTrustedCreationReadiness)
{
    public CreateInteractiveAgentTaskCommand
    {
        requireNonNull(taskId, "taskId is null");
        checkText(workspaceId, "workspaceId");
        requireNonNull(laneId, "laneId is null");
        requireNonNull(physicalRepository, "physicalRepository is null");
        checkSha(startCommit, "startCommit");
        TaskWorktree.checkGeneratedBranch(taskId, generatedBranch);
        checkText(message, "message");
        requireNonNull(defaults, "defaults is null");
        checkText(creationFingerprint, "creationFingerprint");
        requireNonNull(names, "names is null");
    }
}
