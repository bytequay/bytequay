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

import java.time.Instant;
import java.util.Optional;

import static com.bytequay.agent.core.AgentEventSource.root;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Durable handoff from a committed SQLite mutation to the task event log. */
public record AgentEventOutboxEntry(
        EventId eventId,
        TaskId taskId,
        Optional<TurnId> turnId,
        AgentEventSource source,
        AgentEventOutboxPayload payload,
        Instant occurredAt,
        Optional<Long> sequence)
{
    public AgentEventOutboxEntry
    {
        requireNonNull(eventId, "eventId is null");
        requireNonNull(taskId, "taskId is null");
        requireNonNull(turnId, "turnId is null");
        requireNonNull(source, "source is null");
        requireNonNull(payload, "payload is null");
        requireNonNull(occurredAt, "occurredAt is null");
        requireNonNull(sequence, "sequence is null");
        checkArgument(sequence.stream().allMatch(value -> value > 0), "sequence must be positive");
    }

    public static AgentEventOutboxEntry pendingInline(
            EventId eventId,
            TaskId taskId,
            Optional<TurnId> turnId,
            AgentEventSource source,
            AgentEventPayload payload,
            Instant occurredAt)
    {
        return new AgentEventOutboxEntry(
                eventId,
                taskId,
                turnId,
                source,
                new AgentEventOutboxPayload.Inline(payload),
                occurredAt,
                Optional.empty());
    }

    public static AgentEventOutboxEntry pendingLifecycle(
            EventId eventId,
            TaskId taskId,
            Optional<TurnId> turnId,
            AgentEventPayload.LifecyclePhase phase,
            String message,
            Instant occurredAt)
    {
        return pendingInline(
                eventId,
                taskId,
                turnId,
                root(),
                new AgentEventPayload.Lifecycle(phase, message),
                occurredAt);
    }
}
