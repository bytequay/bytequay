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

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.io.IOException;
import java.time.Instant;
import java.util.Locale;
import java.util.Optional;

/** The stored JSON form of one agent event, shared by every log that keeps agent events. */
public final class AgentEventJson
{
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new Jdk8Module())
            .registerModule(new JavaTimeModule())
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private AgentEventJson() {}

    public static byte[] write(AgentEventEnvelope envelope)
            throws JsonProcessingException
    {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("schemaVersion", envelope.schemaVersion());
        root.put("eventId", envelope.eventId().toString());
        root.put("sequence", envelope.sequence());
        root.put("taskId", envelope.taskId().toString());
        envelope.turnId().ifPresentOrElse(turnId -> root.put("turnId", turnId.toString()), () -> root.putNull("turnId"));
        root.set("source", MAPPER.valueToTree(envelope.source()));
        root.put("occurredAt", envelope.occurredAt().toString());
        root.put("type", envelope.type().name().toLowerCase(Locale.ROOT));
        root.set("payload", MAPPER.valueToTree(envelope.payload()));
        return MAPPER.writeValueAsBytes(root);
    }

    /**
     * Reads one stored event.
     *
     * @throws IOException when the bytes are not JSON
     * @throws RuntimeException when the JSON is not an event of a known schema version
     */
    public static AgentEventEnvelope read(byte[] json)
            throws IOException
    {
        JsonNode root = MAPPER.readTree(json);
        int schemaVersion = root.required("schemaVersion").intValue();
        return switch (schemaVersion) {
            case AgentEventEnvelope.CURRENT_SCHEMA_VERSION -> readV2(root);
            default -> throw new IllegalArgumentException("Unsupported schemaVersion: " + schemaVersion);
        };
    }

    private static AgentEventEnvelope readV2(JsonNode root)
            throws JsonProcessingException
    {
        AgentEventPayload.Kind kind = AgentEventPayload.Kind.valueOf(root.required("type").textValue().toUpperCase(Locale.ROOT));
        AgentEventPayload payload = MAPPER.treeToValue(root.required("payload"), kind.type());
        return new AgentEventEnvelope(
                AgentEventEnvelope.CURRENT_SCHEMA_VERSION,
                EventId.valueOf(root.required("eventId").textValue()),
                root.required("sequence").longValue(),
                TaskId.valueOf(root.required("taskId").textValue()),
                turnId(root),
                MAPPER.treeToValue(root.required("source"), AgentEventSource.class),
                Instant.parse(root.required("occurredAt").textValue()),
                payload);
    }

    private static Optional<TurnId> turnId(JsonNode root)
    {
        JsonNode turnId = root.required("turnId");
        return turnId.isNull() ? Optional.empty() : Optional.of(TurnId.valueOf(turnId.textValue()));
    }
}
