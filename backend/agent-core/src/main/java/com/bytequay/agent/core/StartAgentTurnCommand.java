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
import java.util.OptionalLong;

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

public record StartAgentTurnCommand(
        TaskId taskId,
        OptionalLong expectedTaskVersion,
        String message,
        List<AgentAttachment> attachments,
        String idempotencyKey)
{
    public StartAgentTurnCommand
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(expectedTaskVersion, "expectedTaskVersion is null");
        expectedTaskVersion.ifPresent(version -> checkArgument(version >= 0, "expectedTaskVersion is negative"));
        checkNotBlank(message, "message");
        attachments = ImmutableList.copyOf(attachments);
        checkNotBlank(idempotencyKey, "idempotencyKey");
    }

    /** Starts against an exact task snapshot, for callers that validated its mutable defaults. */
    public StartAgentTurnCommand(
            TaskId taskId,
            long expectedTaskVersion,
            String message,
            List<AgentAttachment> attachments,
            String idempotencyKey)
    {
        this(taskId, OptionalLong.of(expectedTaskVersion), message, attachments, idempotencyKey);
    }

    /** Starts with the latest defaults; suitable when the caller made no decision from them. */
    public StartAgentTurnCommand(
            TaskId taskId,
            String message,
            List<AgentAttachment> attachments,
            String idempotencyKey)
    {
        this(taskId, OptionalLong.empty(), message, attachments, idempotencyKey);
    }
}
