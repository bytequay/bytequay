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

import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;
import static com.bytequay.agent.spi.AgentChecks.checkOptionalText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

public record AgentTurnCommand(
        String turnId,
        String message,
        List<AgentAttachment> attachments,
        Duration maximumRuntime,
        Optional<String> model,
        Optional<String> effort)
{
    /** Uses the session defaults, for callers that keep no per-turn settings of their own. */
    public AgentTurnCommand(String turnId, String message, List<AgentAttachment> attachments, Duration maximumRuntime)
    {
        this(turnId, message, attachments, maximumRuntime, Optional.empty(), Optional.empty());
    }

    public AgentTurnCommand
    {
        checkNotBlank(turnId, "turnId");
        checkNotBlank(message, "message");
        requireNonNull(attachments, "attachments is null");
        attachments = ImmutableList.copyOf(attachments);
        requireNonNull(maximumRuntime, "maximumRuntime is null");
        checkOptionalText(model, "model");
        checkOptionalText(effort, "effort");
        checkArgument(!maximumRuntime.isZero() && !maximumRuntime.isNegative(), "maximumRuntime must be positive");
    }
}
