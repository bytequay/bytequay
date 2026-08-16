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

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;
import static com.bytequay.agent.spi.AgentChecks.checkPresent;

public record AgentTurnCommand(
        String turnId,
        String message,
        List<AgentAttachment> attachments,
        Duration maximumRuntime)
{
    public AgentTurnCommand
    {
        checkNotBlank(turnId, "turnId");
        checkNotBlank(message, "message");
        checkPresent(attachments, "attachments");
        attachments = ImmutableList.copyOf(attachments);
        checkPresent(maximumRuntime, "maximumRuntime");
        if (maximumRuntime.isZero() || maximumRuntime.isNegative()) {
            throw new IllegalArgumentException("maximumRuntime must be positive");
        }
    }
}
