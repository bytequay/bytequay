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
        Path runtimeDirectory,
        Path providerDirectory,
        AgentPermissionPolicy permissionPolicy,
        Optional<AgentSessionRef> sessionReference,
        AgentApprovalHandler approvals,
        Optional<String> model,
        Optional<String> effort,
        boolean textOnly,
        Optional<CodeGraphBridge> codeGraph)
{
    public AgentSessionContext(
            String taskId,
            Path worktree,
            Path runtimeDirectory,
            Path providerDirectory,
            AgentPermissionPolicy permissionPolicy,
            Optional<AgentSessionRef> sessionReference,
            AgentApprovalHandler approvals)
    {
        this(
                taskId,
                worktree,
                runtimeDirectory,
                providerDirectory,
                permissionPolicy,
                sessionReference,
                approvals,
                Optional.empty(),
                Optional.empty(),
                false);
    }

    public AgentSessionContext(
            String taskId,
            Path worktree,
            Path runtimeDirectory,
            Path providerDirectory,
            AgentPermissionPolicy permissionPolicy,
            Optional<AgentSessionRef> sessionReference,
            AgentApprovalHandler approvals,
            Optional<String> model,
            Optional<String> effort,
            boolean textOnly)
    {
        this(
                taskId,
                worktree,
                runtimeDirectory,
                providerDirectory,
                permissionPolicy,
                sessionReference,
                approvals,
                model,
                effort,
                textOnly,
                Optional.empty());
    }

    public AgentSessionContext
    {
        checkNotBlank(taskId, "taskId");
        requireNonNull(worktree, "worktree is null");
        requireNonNull(runtimeDirectory, "runtimeDirectory is null");
        requireNonNull(providerDirectory, "providerDirectory is null");
        requireNonNull(permissionPolicy, "permissionPolicy is null");
        requireNonNull(sessionReference, "sessionReference is null");
        requireNonNull(approvals, "approvals is null");
        requireNonNull(model, "model is null").ifPresent(value -> checkNotBlank(value, "model"));
        requireNonNull(effort, "effort is null").ifPresent(value -> checkNotBlank(value, "effort"));
        requireNonNull(codeGraph, "codeGraph is null");
        checkArgument(model.isPresent() || effort.isEmpty(), "effort requires a model");
        checkArgument(worktree.isAbsolute(), "worktree is not absolute");
        checkArgument(runtimeDirectory.isAbsolute() && providerDirectory.isAbsolute(), "runtime directories are not absolute");
        checkArgument(providerDirectory.toAbsolutePath().normalize().startsWith(runtimeDirectory.toAbsolutePath().normalize()), "providerDirectory escapes runtimeDirectory");
        checkArgument(!textOnly || worktree.toAbsolutePath().normalize().startsWith(runtimeDirectory.toAbsolutePath().normalize()), "text-only worktree escapes runtimeDirectory");
    }
}
