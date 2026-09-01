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

import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

public record EventId(UUID value)
{
    public EventId
    {
        requireNonNull(value, "value is null");
    }

    public static EventId random()
    {
        return new EventId(UUID.randomUUID());
    }

    public static EventId valueOf(String value)
    {
        return new EventId(UUID.fromString(value));
    }

    public static EventId legacy(TaskId taskId, long sequence)
    {
        requireNonNull(taskId, "taskId is null");
        checkArgument(sequence > 0, "sequence must be positive");
        String key = "bytequay-agent-event-v1:" + taskId + ":" + sequence;
        return new EventId(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)));
    }

    public static EventId providerUsage(TaskId taskId, TurnId turnId, String providerEventId)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(turnId, "turnId is null");
        requireNonNull(providerEventId, "providerEventId is null");
        checkArgument(!providerEventId.isBlank(), "providerEventId is blank");
        String key = "bytequay-agent-usage-v2:" + taskId + ":" + turnId + ":" + providerEventId;
        return new EventId(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)));
    }

    @Override
    public String toString()
    {
        return value.toString();
    }
}
