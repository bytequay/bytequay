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

import java.nio.file.Path;

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Immutable ownership facts for one verified ByteQuay-generated worktree. */
public record TaskWorktree(
        TaskId taskId,
        String workspaceId,
        RepositoryIdentity physicalRepository,
        Path canonicalWorktree,
        String generatedBranch,
        String startCommit)
{
    public TaskWorktree
    {
        requireNonNull(taskId, "taskId is null");
        checkNotBlank(workspaceId, "workspaceId");
        requireNonNull(physicalRepository, "physicalRepository is null");
        requireNonNull(canonicalWorktree, "canonicalWorktree is null");
        ProvisionWorktreeCommand.checkObjectId(startCommit, "startCommit");

        checkArgument(canonicalWorktree.isAbsolute(), "canonicalWorktree is not absolute");
        checkArgument(canonicalWorktree.equals(canonicalWorktree.normalize()), "canonicalWorktree is not normalized");
        checkArgument(canonicalWorktree.getFileName() != null
                        && canonicalWorktree.getFileName().toString().equals(taskId.toString()),
                "canonicalWorktree is not derived from taskId");
        checkGeneratedBranch(taskId, generatedBranch);
    }

    static void checkGeneratedBranch(TaskId taskId, String branch)
    {
        requireNonNull(taskId, "taskId is null");
        checkNotBlank(branch, "branch");
        checkArgument(branch.equals("bytequay/task/" + taskId), "branch is not derived from taskId");
    }
}
