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

import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.google.common.collect.ImmutableList;

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static com.bytequay.agent.spi.AgentChecks.checkOptionalText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Immutable requested settings recorded when a turn is accepted. */
public record AgentTurnSettings(
        Optional<String> model,
        Optional<String> effort,
        AgentPermissionPolicy permissionPolicy,
        AgentSandboxPolicy sandboxPolicy,
        Duration maximumRuntime,
        List<AgentAttachmentReference> attachments)
{
    public AgentTurnSettings
    {
        checkOptionalText(model, "model");
        checkOptionalText(effort, "effort");
        requireNonNull(permissionPolicy, "permissionPolicy is null");
        requireNonNull(sandboxPolicy, "sandboxPolicy is null");
        requireNonNull(maximumRuntime, "maximumRuntime is null");
        attachments = ImmutableList.copyOf(attachments);
        checkArgument(effort.isEmpty() || model.isPresent(), "effort requires a model");
        checkArgument(!maximumRuntime.isZero() && !maximumRuntime.isNegative(), "maximumRuntime must be positive");
    }
}
