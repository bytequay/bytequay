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

import com.bytequay.agent.spi.AgentAttachment;
import com.google.common.collect.ImmutableList;

import java.util.List;

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;
import static java.util.Objects.requireNonNull;

/** Input for the turn running now. The turn named is the one the user was looking at; any other is refused. */
public record SteerAgentTurnCommand(
        TurnId turnId,
        String message,
        List<AgentAttachment> attachments,
        String idempotencyKey)
{
    public SteerAgentTurnCommand
    {
        requireNonNull(turnId, "turnId is null");
        checkNotBlank(message, "message");
        attachments = ImmutableList.copyOf(attachments);
        checkNotBlank(idempotencyKey, "idempotencyKey");
    }
}
