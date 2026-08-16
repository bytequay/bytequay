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

import com.google.common.collect.ImmutableSet;

import java.util.Set;

import static java.util.Objects.requireNonNull;

public record AgentCapabilities(
        boolean sessionResume,
        boolean partialAssistantText,
        boolean structuredPlans,
        boolean toolLifecycle,
        boolean incrementalCommandOutput,
        boolean hookLifecycle,
        boolean nestedAgentActivity,
        boolean interactiveApprovals,
        boolean tokenUsage,
        CostProvenance costProvenance,
        Set<String> attachmentMediaTypes)
{
    public AgentCapabilities
    {
        requireNonNull(costProvenance, "costProvenance is null");
        requireNonNull(attachmentMediaTypes, "attachmentMediaTypes is null");
        attachmentMediaTypes = ImmutableSet.copyOf(attachmentMediaTypes);
        if (attachmentMediaTypes.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("attachmentMediaTypes contains a blank value");
        }
    }

    public static AgentCapabilities none()
    {
        return new AgentCapabilities(
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                false,
                CostProvenance.UNAVAILABLE,
                ImmutableSet.of());
    }
}
