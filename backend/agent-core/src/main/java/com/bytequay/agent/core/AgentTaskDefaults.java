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

import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;

import java.util.Optional;

import static com.bytequay.core.domain.Checks.checkOptionalText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Defaults copied into each newly accepted turn. */
public record AgentTaskDefaults(
        Optional<String> model,
        Optional<String> effort,
        AgentPermissionPolicy permissionPolicy,
        Optional<String> serviceTier,
        Optional<DevelopmentAutomation> developmentAutomation,
        Optional<AgentLaneId> laneId)
{
    public AgentTaskDefaults(Optional<String> model, Optional<String> effort, AgentPermissionPolicy permissionPolicy, Optional<String> serviceTier,
            Optional<DevelopmentAutomation> developmentAutomation)
    {
        this(model, effort, permissionPolicy, serviceTier, developmentAutomation, Optional.empty());
    }

    public AgentTaskDefaults withLaneId(AgentLaneId lane)
    {
        return new AgentTaskDefaults(model, effort, permissionPolicy, serviceTier, developmentAutomation, Optional.of(lane));
    }

    public AgentTaskDefaults(Optional<String> model, Optional<String> effort, AgentPermissionPolicy permissionPolicy)
    {
        this(model, effort, permissionPolicy, Optional.empty(), Optional.empty());
    }

    public AgentTaskDefaults(Optional<String> model, Optional<String> effort, AgentPermissionPolicy permissionPolicy, Optional<String> serviceTier)
    {
        this(model, effort, permissionPolicy, serviceTier, Optional.empty());
    }

    public AgentTaskDefaults withDevelopmentAutomation(DevelopmentAutomation automation)
    {
        return new AgentTaskDefaults(model, effort, permissionPolicy, serviceTier, Optional.of(automation), laneId);
    }

    public AgentTaskDefaults
    {
        requireNonNull(developmentAutomation, "developmentAutomation is null");
        requireNonNull(laneId, "laneId is null");
        checkOptionalText(model, "model");
        checkOptionalText(effort, "effort");
        checkOptionalText(serviceTier, "serviceTier");
        checkArgument(serviceTier.isEmpty() || model.isPresent(), "service tier requires a model");
        requireNonNull(permissionPolicy, "permissionPolicy is null");
        checkArgument(effort.isEmpty() || model.isPresent(), "effort requires a model");
    }

    public static AgentTaskDefaults forPermission(AgentPermissionPolicy permissionPolicy)
    {
        return new AgentTaskDefaults(Optional.empty(), Optional.empty(), permissionPolicy);
    }
}
