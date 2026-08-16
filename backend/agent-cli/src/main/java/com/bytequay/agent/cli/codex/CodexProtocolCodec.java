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
package com.bytequay.agent.cli.codex;

import com.bytequay.agent.spi.AgentUnavailableException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;

import static java.util.Objects.requireNonNull;

final class CodexProtocolCodec
{
    private final ObjectMapper objectMapper;

    CodexProtocolCodec(ObjectMapper objectMapper)
    {
        this.objectMapper = requireNonNull(objectMapper, "objectMapper is null")
                .copy()
                .registerModule(new Jdk8Module())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.READ_UNKNOWN_ENUM_VALUES_USING_DEFAULT_VALUE);
    }

    CodexProtocol.JsonRpcEnvelope decodeEnvelope(String line)
    {
        return decode(line, CodexProtocol.JsonRpcEnvelope.class);
    }

    CodexProtocol.Notification decodeNotification(String line)
    {
        return decode(line, CodexProtocol.Notification.class);
    }

    CodexProtocol.ServerRequest decodeServerRequest(String line)
    {
        return decode(line, CodexProtocol.ServerRequest.class);
    }

    <T> T decodeResult(JsonNode result, Class<T> type)
    {
        requireNonNull(result, "result is null");
        requireNonNull(type, "type is null");
        try {
            return objectMapper.treeToValue(result, type);
        }
        catch (JsonProcessingException e) {
            throw new AgentUnavailableException("Invalid Codex response", e);
        }
    }

    String encode(Object value)
    {
        requireNonNull(value, "value is null");
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new AgentUnavailableException("Unable to encode Codex message", e);
        }
    }

    private <T> T decode(String line, Class<T> type)
    {
        requireNonNull(line, "line is null");
        try {
            return objectMapper.readValue(line, type);
        }
        catch (JsonProcessingException e) {
            throw new AgentUnavailableException("Invalid Codex message", e);
        }
    }
}
