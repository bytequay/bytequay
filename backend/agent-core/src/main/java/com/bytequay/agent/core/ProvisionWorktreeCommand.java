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

import java.util.regex.Pattern;

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Validated internal service command used to derive a task's path and generated branch. */
public record ProvisionWorktreeCommand(
        TaskId taskId,
        String workspaceId,
        RepositoryIdentity physicalRepository,
        String startCommit)
{
    private static final Pattern GIT_OBJECT_ID = Pattern.compile("(?:[0-9a-f]{40}|[0-9a-f]{64})");

    public ProvisionWorktreeCommand
    {
        requireNonNull(taskId, "taskId is null");
        checkNotBlank(workspaceId, "workspaceId");
        requireNonNull(physicalRepository, "physicalRepository is null");
        checkObjectId(startCommit, "startCommit");
    }

    static void checkObjectId(String value, String name)
    {
        checkNotBlank(value, name);
        checkArgument(GIT_OBJECT_ID.matcher(value).matches(), "%s is not an exact Git object ID", name);
    }
}
