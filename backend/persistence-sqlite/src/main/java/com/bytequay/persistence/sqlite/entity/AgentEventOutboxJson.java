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

import com.bytequay.agent.core.AgentEventOutboxPayload;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentEventSource;
import com.bytequay.agent.core.AgentStorageException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

final class AgentEventOutboxJson
{
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    private AgentEventOutboxJson() {}

    public static String encodeSource(AgentEventSource source)
    {
        return encode(source, "event source");
    }

    public static AgentEventSource decodeSource(String json)
    {
        return decode(json, AgentEventSource.class, "event source");
    }

    public static String encodePayload(AgentEventOutboxPayload payload)
    {
        return switch (payload) {
            case AgentEventOutboxPayload.Inline inline -> encode(inline.payload(), "event payload");
            case AgentEventOutboxPayload.UserMessageBlob blob -> encode(blob, "event payload");
        };
    }

    public static AgentEventOutboxPayload decodePayload(
            AgentEventPayload.Kind kind,
            boolean blobBacked,
            String json)
    {
        if (blobBacked) {
            if (kind != AgentEventPayload.Kind.USER_MESSAGE) {
                throw new AgentStorageException("Blob-backed outbox payload is not a user message");
            }
            return decode(json, AgentEventOutboxPayload.UserMessageBlob.class, "event payload");
        }

        try {
            JsonNode payload = MAPPER.readTree(json);
            AgentEventPayload decoded = switch (kind) {
                case USER_MESSAGE -> MAPPER.treeToValue(payload, AgentEventPayload.UserMessage.class);
                case ASSISTANT_MESSAGE -> MAPPER.treeToValue(payload, AgentEventPayload.AssistantMessage.class);
                case PLAN_UPDATED -> MAPPER.treeToValue(payload, AgentEventPayload.PlanUpdated.class);
                case COMMAND -> MAPPER.treeToValue(payload, AgentEventPayload.CommandActivity.class);
                case FILE_CHANGE -> MAPPER.treeToValue(payload, AgentEventPayload.FileActivity.class);
                case TOOL -> MAPPER.treeToValue(payload, AgentEventPayload.ToolActivity.class);
                case APPROVAL_REQUESTED -> MAPPER.treeToValue(payload, AgentEventPayload.ApprovalRequested.class);
                case APPROVAL_RESOLVED -> MAPPER.treeToValue(payload, AgentEventPayload.ApprovalResolved.class);
                case USAGE -> MAPPER.treeToValue(payload, AgentEventPayload.UsageUpdated.class);
                case LIFECYCLE -> MAPPER.treeToValue(payload, AgentEventPayload.Lifecycle.class);
                case ERROR -> MAPPER.treeToValue(payload, AgentEventPayload.Error.class);
            };
            return new AgentEventOutboxPayload.Inline(decoded);
        }
        catch (JsonProcessingException | RuntimeException e) {
            throw storageException("Cannot deserialize event payload", e);
        }
    }

    private static String encode(Object value, String description)
    {
        try {
            return MAPPER.writeValueAsString(value);
        }
        catch (JsonProcessingException | RuntimeException e) {
            throw storageException("Cannot serialize " + description, e);
        }
    }

    private static <T> T decode(String json, Class<T> type, String description)
    {
        try {
            return MAPPER.readValue(json, type);
        }
        catch (JsonProcessingException | RuntimeException e) {
            throw storageException("Cannot deserialize " + description, e);
        }
    }

    private static AgentStorageException storageException(String message, Exception failure)
    {
        if (failure instanceof AgentStorageException storageException) {
            return storageException;
        }
        return new AgentStorageException(message, failure);
    }
}
