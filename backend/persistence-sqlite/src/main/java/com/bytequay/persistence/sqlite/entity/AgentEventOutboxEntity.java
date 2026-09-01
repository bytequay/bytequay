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
package com.bytequay.persistence.sqlite.entity;

import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.AgentEventOutboxEntry;
import com.bytequay.agent.core.AgentEventOutboxPayload;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentStorageException;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.persistence.sqlite.converter.InstantToTextConverter;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.Optional;

import static java.nio.charset.StandardCharsets.UTF_8;

@Entity
@Table(name = "agent_event_outbox")
public class AgentEventOutboxEntity
{
    private static final int ENVELOPE_HEADROOM_BYTES = 4096;
    private static final int MAX_JSON_ESCAPE_EXPANSION = 6;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String eventId;

    @Column(nullable = false)
    private String taskId;

    private String turnId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String sourceJson;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private AgentEventPayload.Kind payloadKind;

    @Column(nullable = false)
    private boolean blobBacked;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Column(nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant occurredAt;

    private Long eventSequence;

    public AgentEventOutboxEntity() {}

    public static AgentEventOutboxEntity fromDomain(AgentEventOutboxEntry entry)
    {
        AgentEventOutboxEntity entity = new AgentEventOutboxEntity();
        entity.eventId = entry.eventId().toString();
        entity.taskId = entry.taskId().toString();
        entity.turnId = entry.turnId().map(TurnId::toString).orElse(null);
        entity.sourceJson = AgentEventOutboxJson.encodeSource(entry.source());
        entity.payloadKind = entry.payload().kind();
        entity.blobBacked = entry.payload() instanceof AgentEventOutboxPayload.UserMessageBlob;
        entity.payloadJson = AgentEventOutboxJson.encodePayload(entry.payload());
        checkEventBound(entry.payload(), entity.sourceJson, entity.payloadJson);
        entity.occurredAt = entry.occurredAt();
        entity.eventSequence = entry.sequence().orElse(null);
        return entity;
    }

    private static void checkEventBound(AgentEventOutboxPayload payload, String sourceJson, String payloadJson)
    {
        long bytes = (long) sourceJson.getBytes(UTF_8).length
                + payloadJson.getBytes(UTF_8).length
                + ENVELOPE_HEADROOM_BYTES;
        if (payload instanceof AgentEventOutboxPayload.UserMessageBlob message) {
            long textBytes = message.textBlob().size();
            bytes += textBytes > AgentEventEnvelope.MAX_SERIALIZED_BYTES / MAX_JSON_ESCAPE_EXPANSION
                    ? AgentEventEnvelope.MAX_SERIALIZED_BYTES + 1L
                    : textBytes * MAX_JSON_ESCAPE_EXPANSION;
        }
        if (bytes > AgentEventEnvelope.MAX_SERIALIZED_BYTES) {
            throw new AgentStorageException("Event outbox payload exceeds the task-log size limit");
        }
    }

    public Long getEventSequence()
    {
        return eventSequence;
    }

    public void markAppended(long sequence)
    {
        eventSequence = sequence;
    }

    public AgentEventOutboxEntry toDomain()
    {
        return new AgentEventOutboxEntry(
                EventId.valueOf(eventId),
                TaskId.valueOf(taskId),
                Optional.ofNullable(turnId).map(TurnId::valueOf),
                AgentEventOutboxJson.decodeSource(sourceJson),
                AgentEventOutboxJson.decodePayload(payloadKind, blobBacked, payloadJson),
                occurredAt,
                Optional.ofNullable(eventSequence));
    }
}
