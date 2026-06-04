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

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

import java.util.Map;
import java.util.Set;

/**
 * The inherent capability set each agent role starts with. This is
 * the <em>base</em> the permission cascade tightens: the resolver
 * begins with {@code forRole(role)} and removes capabilities denied
 * at any scope (global → workspace → thread → task). A role can never
 * gain a capability that isn't in its base — the cascade only
 * subtracts.
 */
public final class RoleCapabilities
{
    private static final Map<AgentRole, Set<SecurityType>> BASE = ImmutableMap.of(
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
                    SecurityType.CONCEPT_USE,
                    SecurityType.MCP),
            // Task agents edit + commit + push + publish, and may
            // park themselves at AWAITING_REVIEW (request_review)
            // which exercises TASK_MANAGE. They never create new
            // tasks — that's gated by the tool's roles=TRUNK filter,
            // not by withholding the capability.
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
                    SecurityType.CONCEPT_USE,
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
                    SecurityType.CONCEPT_USE,
                    SecurityType.MCP));

    private RoleCapabilities() {}

    /** The base capability set for {@code role}; empty for an
     *  unrecognised role (deny-by-default). */
    public static Set<SecurityType> forRole(AgentRole role)
    {
        return BASE.getOrDefault(role, ImmutableSet.of());
    }
}
