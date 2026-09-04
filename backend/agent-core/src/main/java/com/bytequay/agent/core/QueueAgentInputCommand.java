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
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** A request to run after the current turn. The task version is the one the caller saw, so a queue write cannot race Complete. */
public record QueueAgentInputCommand(
        TaskId taskId,
        long expectedVersion,
        String message,
        List<AgentAttachment> attachments,
        String idempotencyKey)
{
    public QueueAgentInputCommand
    {
        requireNonNull(taskId, "taskId is null");
        checkArgument(expectedVersion >= 0, "expectedVersion is negative");
        checkNotBlank(message, "message");
        attachments = ImmutableList.copyOf(attachments);
        checkNotBlank(idempotencyKey, "idempotencyKey");
    }
}
