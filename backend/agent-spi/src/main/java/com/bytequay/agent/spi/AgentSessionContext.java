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
package com.bytequay.agent.spi;

import java.nio.file.Path;
import java.util.Optional;

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

public record AgentSessionContext(
        String taskId,
        Path worktree,
        Path providerDirectory,
        AgentPermissionPolicy permissionPolicy,
        Optional<AgentSessionRef> sessionReference,
        AgentApprovalHandler approvals)
{
    public AgentSessionContext
    {
        checkNotBlank(taskId, "taskId");
        requireNonNull(worktree, "worktree is null");
        requireNonNull(providerDirectory, "providerDirectory is null");
        requireNonNull(permissionPolicy, "permissionPolicy is null");
        requireNonNull(sessionReference, "sessionReference is null");
        requireNonNull(approvals, "approvals is null");
        checkArgument(worktree.isAbsolute(), "worktree is not absolute");
        checkArgument(providerDirectory.isAbsolute(), "providerDirectory is not absolute");
    }
}
