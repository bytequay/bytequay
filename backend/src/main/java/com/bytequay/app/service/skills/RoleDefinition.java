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
package com.bytequay.app.service.skills;

import com.bytequay.app.service.tools.AgentRole;
import com.bytequay.app.service.tools.SecurityType;

import java.util.Set;

import static java.util.Objects.requireNonNull;

/** Immutable, provider-neutral definition of one ByteQuay role version. */
public record RoleDefinition(
        ByteQuayRole role,
        String version,
        String character,
        String instructions,
        AgentRole permissionRole,
        Set<SecurityType> capabilities,
        Set<AgentResource> resources)
{
    public RoleDefinition
    {
        requireNonNull(role, "role is null");
        requireNonNull(version, "version is null");
        requireNonNull(character, "character is null");
        requireNonNull(instructions, "instructions is null");
        requireNonNull(permissionRole, "permissionRole is null");
        capabilities = Set.copyOf(requireNonNull(capabilities, "capabilities is null"));
        resources = Set.copyOf(requireNonNull(resources, "resources is null"));
    }

    public String reference()
    {
        return role.id() + "@" + version;
    }
}
