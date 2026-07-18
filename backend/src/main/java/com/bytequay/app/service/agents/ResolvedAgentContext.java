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
package com.bytequay.app.service.agents;

import com.bytequay.app.domain.StageType;
import com.bytequay.app.service.skills.AgentResource;
import com.bytequay.app.service.skills.ByteQuayRole;
import com.bytequay.app.service.skills.ManagedSkill;
import com.bytequay.app.service.tools.AgentRole;
import com.bytequay.app.service.tools.SecurityType;

import java.util.List;
import java.util.Set;

/** Provider-neutral role, skill, resource, permission, and tool decision for one turn. */
public record ResolvedAgentContext(
        ByteQuayRole role,
        String roleVersion,
        AgentRole permissionRole,
        StageType stageType,
        Set<SecurityType> capabilities,
        List<String> skillNames,
        List<ManagedSkill> skills,
        Set<AgentResource> resources,
        Set<String> toolNames)
{
    public ResolvedAgentContext
    {
        capabilities = Set.copyOf(capabilities);
        skillNames = List.copyOf(skillNames);
        skills = List.copyOf(skills);
        resources = Set.copyOf(resources);
        toolNames = Set.copyOf(toolNames);
    }

    /** Compatibility shape for contexts that carry names but no resolved bodies. */
    public ResolvedAgentContext(
            ByteQuayRole role,
            String roleVersion,
            AgentRole permissionRole,
            StageType stageType,
            Set<SecurityType> capabilities,
            List<String> skillNames,
            Set<AgentResource> resources,
            Set<String> toolNames)
    {
        this(role, roleVersion, permissionRole, stageType, capabilities, skillNames,
                List.of(), resources, toolNames);
    }

    public String roleReference()
    {
        return role.id() + "@" + roleVersion;
    }
}
