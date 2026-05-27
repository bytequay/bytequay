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
package com.bytequay.app.service.tools;

import com.bytequay.app.repository.TaskStore;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * First-cut permissions: derive the caller's role from the thread's
 * active task and look up the role's grants in a static map. The real
 * permissions cascade replaces this in a later phase.
 *
 * <p>Role derivation mirrors the ThreadRegistry split: trunk and
 * task are thread-shape distinctions, not momentary states.
 * <ul>
 *   <li>thread has any task ever (even if currently parked at
 *       AWAITING_REVIEW or NEEDS_ATTENTION) → {@link AgentRole#TASK}.
 *       The handler still surfaces its own "no active task" /
 *       "no worktree" message when the runtime preconditions aren't
 *       met — the role check just decides whether the agent is even
 *       allowed to discover the tool.</li>
 *   <li>otherwise (0-task thread) → {@link AgentRole#TRUNK}.</li>
 *   <li>reviewer / lead roles are not yet wired through the CLI lane;
 *       they land with the review-panel work.</li>
 * </ul>
 *
 * <p>The grants table is intentionally narrow — every capability axis
 * mentioned anywhere in the catalog has to appear here, and a role
 * absent from the table gets the empty set (deny-by-default).
 */
@Component
public class RoleBasedPermissionResolver
        implements PermissionResolver
{
    private static final Map<AgentRole, Set<SecurityType>> GRANTS = ImmutableMap.of(
            // Trunk plans, breaks work into tasks, recalls prior
            // threads, and reads. It never edits code, runs code,
            // pushes branches, or publishes to the forge.
            AgentRole.TRUNK, ImmutableSet.of(
                    SecurityType.TASK_READ,
                    SecurityType.TASK_MANAGE,
                    SecurityType.CODE_READ,
                    SecurityType.VCS_READ,
                    SecurityType.MEMORY_READ,
                    SecurityType.SKILL_USE,
                    SecurityType.TOOL_DISCOVER,
                    SecurityType.MCP),
            // Task agents edit + commit + push + publish, and may
            // park themselves at AWAITING_REVIEW (request_review)
            // which exercises TASK_MANAGE. They never create new
            // tasks — that's gated by the tool's roles=TRUNK filter,
            // not by withholding the capability. Memory writes are
            // allowed so a task can leave a brain entry.
            AgentRole.TASK, ImmutableSet.of(
                    SecurityType.CODE_READ,
                    SecurityType.CODE_WRITE,
                    SecurityType.CODE_EXEC,
                    SecurityType.GIT_LOCAL,
                    SecurityType.GIT_PUSH,
                    SecurityType.VCS_READ,
                    SecurityType.VCS_PUBLISH,
                    SecurityType.TASK_READ,
                    SecurityType.TASK_MANAGE,
                    SecurityType.MEMORY_READ,
                    SecurityType.MEMORY_WRITE,
                    SecurityType.SKILL_USE,
                    SecurityType.TOOL_DISCOVER,
                    SecurityType.MCP),
            // Reviewer reads diffs and publishes review comments;
            // never edits code or pushes branches.
            AgentRole.REVIEWER, ImmutableSet.of(
                    SecurityType.CODE_READ,
                    SecurityType.VCS_READ,
                    SecurityType.VCS_PUBLISH,
                    SecurityType.MEMORY_READ,
                    SecurityType.SKILL_USE,
                    SecurityType.TOOL_DISCOVER,
                    SecurityType.MCP));

    private final TaskStore taskStore;

    public RoleBasedPermissionResolver(TaskStore taskStore)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
    }

    @Override
    public AgentRole roleFor(String threadId)
    {
        if (threadId == null || threadId.isBlank()) {
            return AgentRole.TRUNK;
        }
        return taskStore.listTasksByThread(threadId).isEmpty()
                ? AgentRole.TRUNK
                : AgentRole.TASK;
    }

    @Override
    public Set<SecurityType> grants(String threadId)
    {
        return GRANTS.getOrDefault(roleFor(threadId), ImmutableSet.of());
    }
}
