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

import static com.bytequay.core.domain.Checks.checkText;
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
        SessionAccess access,
        Optional<CodeQueryBridge> codeQueries,
        String managedInstructions,
        Optional<DiscussionContextBridge> discussionContext,
        Optional<ReviewTool> reviewContext)
{
    public AgentSessionContext(
            String taskId, Path worktree, Path runtimeDirectory, Path providerDirectory, AgentPermissionPolicy permissionPolicy,
            Optional<AgentSessionRef> sessionReference, AgentApprovalHandler approvals, Optional<String> model, Optional<String> effort,
            SessionAccess access, Optional<CodeQueryBridge> codeQueries, String managedInstructions)
    {
        this(taskId, worktree, runtimeDirectory, providerDirectory, permissionPolicy, sessionReference, approvals, model, effort,
                access, codeQueries, managedInstructions, Optional.empty());
    }

    public AgentSessionContext(
            String taskId, Path worktree, Path runtimeDirectory, Path providerDirectory, AgentPermissionPolicy permissionPolicy,
            Optional<AgentSessionRef> sessionReference, AgentApprovalHandler approvals, Optional<String> model, Optional<String> effort,
            SessionAccess access, Optional<CodeQueryBridge> codeQueries, String managedInstructions, Optional<DiscussionContextBridge> discussionContext)
    {
        this(taskId, worktree, runtimeDirectory, providerDirectory, permissionPolicy, sessionReference, approvals, model, effort,
                access, codeQueries, managedInstructions, discussionContext, Optional.empty());
    }

    public AgentSessionContext
    {
        checkText(taskId, "taskId");
        requireNonNull(worktree, "worktree is null");
        requireNonNull(runtimeDirectory, "runtimeDirectory is null");
        requireNonNull(providerDirectory, "providerDirectory is null");
        requireNonNull(permissionPolicy, "permissionPolicy is null");
        requireNonNull(sessionReference, "sessionReference is null");
        requireNonNull(approvals, "approvals is null");
        requireNonNull(model, "model is null").ifPresent(value -> checkText(value, "model"));
        requireNonNull(effort, "effort is null").ifPresent(value -> checkText(value, "effort"));
        requireNonNull(access, "access is null");
        requireNonNull(codeQueries, "codeQueries is null");
        requireNonNull(managedInstructions, "managedInstructions is null");
        requireNonNull(reviewContext, "reviewContext is null");
        checkArgument(reviewContext.isEmpty() || access == SessionAccess.READ_ONLY, "Review context requires a read-only session");
        reviewContext.ifPresent(tool -> checkArgument(tool.name().equals("review_get_context") && tool.childCallable(), "Only review context reads are allowed"));
        requireNonNull(discussionContext, "discussionContext is null");
        checkArgument(discussionContext.isEmpty() || access == SessionAccess.READ_ONLY, "Discussion context requires a read-only session");
        checkArgument(model.isPresent() || effort.isEmpty(), "effort requires a model");
        checkArgument(worktree.isAbsolute(), "worktree is not absolute");
        checkArgument(runtimeDirectory.isAbsolute() && providerDirectory.isAbsolute(), "runtime directories are not absolute");
        checkArgument(providerDirectory.toAbsolutePath().normalize().startsWith(runtimeDirectory.toAbsolutePath().normalize()), "providerDirectory escapes runtimeDirectory");
        checkArgument(access != SessionAccess.TEXT_ONLY || worktree.toAbsolutePath().normalize().startsWith(runtimeDirectory.toAbsolutePath().normalize()),
                "text-only worktree escapes runtimeDirectory");
    }

    /** Whether this session has no tools at all. */
    public boolean textOnly()
    {
        return access == SessionAccess.TEXT_ONLY;
    }

    /** Whether this session withholds the capability to change its worktree. */
    public boolean restricted()
    {
        return access.restricted();
    }
}
