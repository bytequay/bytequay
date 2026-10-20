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

import com.google.common.collect.ImmutableList;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/**
 * What a provider session runs with.
 *
 * @param asksUser whether the agent may ask the user a question with choices (Claude's {@code AskUserQuestion}); each question reaches the
 * {@code approvals}, and the user's answers ride back on the allow
 */
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
        List<ReviewTool> sessionTools,
        boolean asksUser)
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
                access, codeQueries, managedInstructions, discussionContext, ImmutableList.of());
    }

    public AgentSessionContext(
            String taskId, Path worktree, Path runtimeDirectory, Path providerDirectory, AgentPermissionPolicy permissionPolicy,
            Optional<AgentSessionRef> sessionReference, AgentApprovalHandler approvals, Optional<String> model, Optional<String> effort,
            SessionAccess access, Optional<CodeQueryBridge> codeQueries, String managedInstructions, Optional<DiscussionContextBridge> discussionContext,
            List<ReviewTool> sessionTools)
    {
        this(taskId, worktree, runtimeDirectory, providerDirectory, permissionPolicy, sessionReference, approvals, model, effort,
                access, codeQueries, managedInstructions, discussionContext, sessionTools, false);
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
        sessionTools = ImmutableList.copyOf(sessionTools);
        ReviewTool.checkSessionTools(access, sessionTools);
        requireNonNull(discussionContext, "discussionContext is null");
        checkArgument(discussionContext.isEmpty() || access == SessionAccess.READ_ONLY, "Discussion context requires a read-only session");
        checkArgument(model.isPresent() || effort.isEmpty(), "effort requires a model");
        // Its questions reach the user through the approvals, which a read-only session alone keeps for them.
        checkArgument(!asksUser || access == SessionAccess.READ_ONLY, "Only a read-only session asks the user");
        checkArgument(worktree.isAbsolute(), "worktree is not absolute");
        checkArgument(runtimeDirectory.isAbsolute() && providerDirectory.isAbsolute(), "runtime directories are not absolute");
        checkArgument(providerDirectory.toAbsolutePath().normalize().startsWith(runtimeDirectory.toAbsolutePath().normalize()), "providerDirectory escapes runtimeDirectory");
        checkArgument(access != SessionAccess.TEXT_ONLY || worktree.toAbsolutePath().normalize().startsWith(runtimeDirectory.toAbsolutePath().normalize()),
                "text-only worktree escapes runtimeDirectory");
        // Claude finds a saved session by its working directory, so a tools-only folder outlives the run's own runtime directory:
        // it is <runtime root>/assistant/<day>, beside the run's directory, which sits directly under the runtime root.
        checkArgument(access != SessionAccess.TOOLS_ONLY
                        || runtimeDirectory.toAbsolutePath().normalize().resolveSibling("assistant").equals(worktree.toAbsolutePath().normalize().getParent()),
                "tools-only worktree is not an assistant folder");
    }

    /** Whether this session has no tools at all. */
    public boolean textOnly()
    {
        return access == SessionAccess.TEXT_ONLY;
    }

    /**
     * Whether the agent may ask the user a question with choices: a full session, which is a task's turn, unless it is a review or runs
     * unattended (nobody would answer); and a read-only session that opts in. Every lane applies this one rule.
     */
    public boolean mayAskUser(boolean review)
    {
        return asksUser || (access == SessionAccess.FULL && !review && !permissionPolicy.unattended());
    }

    /** Whether this session withholds the capability to change its worktree. */
    public boolean restricted()
    {
        return access.restricted();
    }

    /**
     * Whether the worktree is no task's checkout: a scratch folder inside the runtime directory, or the artifact writer's checkout in
     * {@code <runtime root>/artifacts}, beside the run's directory.
     */
    public boolean scratchWorktree()
    {
        Path folder = worktree.toAbsolutePath().normalize();
        Path runtime = runtimeDirectory.toAbsolutePath().normalize();
        return folder.startsWith(runtime) || folder.startsWith(runtime.resolveSibling("artifacts"));
    }
}
