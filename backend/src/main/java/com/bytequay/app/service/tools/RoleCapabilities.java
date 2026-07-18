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

import com.bytequay.app.service.skills.RoleRegistry;
import com.google.common.collect.ImmutableSet;

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
    private RoleCapabilities() {}

    /** The base capability set for {@code role}; empty for an
     *  unrecognised role (deny-by-default). */
    public static Set<SecurityType> forRole(AgentRole role)
    {
        if (role == null || role == AgentRole.ANY) {
            return ImmutableSet.of();
        }
        return ImmutableSet.copyOf(RoleRegistry.definitionFor(role).capabilities());
    }
}
