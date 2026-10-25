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
import com.bytequay.agent.core.AgentEventSource;
import com.bytequay.agent.core.AgentStorageException;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.Getter;

import java.time.Instant;
import java.util.Optional;

import static com.bytequay.persistence.sqlite.entity.JsonGuard.AGENT;
import static java.nio.charset.StandardCharsets.UTF_8;

@Entity
@Table(name = "agent_event_outbox", indexes = {
        @Index(name = "idx_agent_outbox_task_pending", columnList = "task_id,event_sequence,id"),
        @Index(name = "idx_agent_outbox_pending", columnList = "event_sequence,id")})
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

    // Hibernate schema update cannot expand an existing SQLite enum CHECK when an event kind is added.
    @Column(nullable = false)
    private String payloadKind;

    @Column(nullable = false)
    private boolean blobBacked;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payloadJson;

    @Column(nullable = false)
    private Instant occurredAt;

    @Getter
    private Long eventSequence;

    public AgentEventOutboxEntity() {}

    public static AgentEventOutboxEntity fromDomain(AgentEventOutboxEntry entry)
    {
        AgentEventOutboxEntity entity = new AgentEventOutboxEntity();
        entity.eventId = entry.eventId().toString();
        entity.taskId = entry.taskId().toString();
        entity.turnId = entry.turnId().map(TurnId::toString).orElse(null);
        entity.sourceJson = AGENT.write(entry.source(), "event source");
        entity.payloadKind = entry.payload().kind().name();
        entity.blobBacked = entry.payload() instanceof AgentEventOutboxPayload.UserMessageBlob;
        entity.payloadJson = AGENT.write(switch (entry.payload()) {
            case AgentEventOutboxPayload.Inline inline -> inline.payload();
            case AgentEventOutboxPayload.UserMessageBlob blob -> blob;
        }, "event payload");
        checkEventBound(entry.payload(), entity.sourceJson, entity.payloadJson);
        entity.occurredAt = entry.occurredAt();
        entity.eventSequence = entry.sequence().orElse(null);
        return entity;
    }

    private static void checkEventBound(AgentEventOutboxPayload payload, String sourceJson, String payloadJson)
    {
        long bytes = (long) sourceJson.getBytes(UTF_8).length + payloadJson.getBytes(UTF_8).length + ENVELOPE_HEADROOM_BYTES;
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

    public void markAppended(long sequence)
    {
        eventSequence = sequence;
    }

    public AgentEventOutboxEntry toDomain()
    {
        return new AgentEventOutboxEntry(
                EventId.valueOf(eventId), TaskId.valueOf(taskId), Optional.ofNullable(turnId).map(TurnId::valueOf), AGENT.read(sourceJson, AgentEventSource.class, "event source"),
                decodePayload(AgentEventPayload.Kind.valueOf(payloadKind), blobBacked, payloadJson), occurredAt, Optional.ofNullable(eventSequence));
    }

    static AgentEventOutboxPayload decodePayload(AgentEventPayload.Kind kind, boolean blobBacked, String json)
    {
        if (blobBacked) {
            if (kind != AgentEventPayload.Kind.USER_MESSAGE) {
                throw new AgentStorageException("Blob-backed outbox payload is not a user message");
            }
            return AGENT.read(json, AgentEventOutboxPayload.UserMessageBlob.class, "event payload");
        }
        return new AgentEventOutboxPayload.Inline(AGENT.read(json, kind.type(), "event payload"));
    }
}
