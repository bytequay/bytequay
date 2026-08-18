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

import com.bytequay.agent.spi.ProviderAgentEvent;

import java.time.Instant;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

public record AgentEventEnvelope(
        int schemaVersion,
        long sequence,
        TaskId taskId,
        Optional<TurnId> turnId,
        Instant occurredAt,
        ProviderAgentEvent event)
{
    public static final int CURRENT_SCHEMA_VERSION = 1;

    public AgentEventEnvelope
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(turnId, "turnId is null");
        requireNonNull(occurredAt, "occurredAt is null");
        requireNonNull(event, "event is null");
        checkArgument(schemaVersion == CURRENT_SCHEMA_VERSION, "Unsupported schemaVersion: " + schemaVersion);
        checkArgument(sequence > 0, "sequence must be positive");
    }

    public ProviderAgentEvent.Kind type()
    {
        return event.kind();
    }
}
