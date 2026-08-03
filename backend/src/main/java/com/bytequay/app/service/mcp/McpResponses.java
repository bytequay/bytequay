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
package com.bytequay.app.service.mcp;

import com.bytequay.app.beans.mcp.AllowEnvelope;
import com.bytequay.app.beans.mcp.DenyEnvelope;
import com.bytequay.app.beans.mcp.JsonRpcError;
import com.bytequay.app.beans.mcp.JsonRpcSuccess;
import com.bytequay.app.beans.mcp.ToolCallResult;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import static java.util.Objects.requireNonNull;

/**
 * One-stop helper for the JSON-RPC + MCP wire framing the
 * dispatcher and every {@link ToolHandler} need. Built so handlers
 * don't carry an {@link ObjectMapper} themselves and the envelope-
 * construction boilerplate (allow / deny / plain text / tool-
 * response wrapping) appears in exactly one place.
 */
@Component
public class McpResponses
{
    private final ObjectMapper mapper;

    public McpResponses(ObjectMapper mapper)
    {
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    /**
     * Bind {@code args} to {@code type}, defaulting missing args to
     * an empty object so optional-only records bind cleanly. Throws
     * {@link JsonProcessingException} so the caller surfaces a
     * {@code -32602 invalid params} error matching the per-tool
     * argument-bind path the legacy code used.
     */
    public <T> T bindArgs(JsonNode args, Class<T> type)
            throws JsonProcessingException
    {
        JsonNode node = (args == null || args.isMissingNode())
                ? mapper.createObjectNode()
                : args;
        return mapper.treeToValue(node, type);
    }

    public JsonNode ok(JsonNode id, Object result)
    {
        return mapper.valueToTree(JsonRpcSuccess.of(id, result));
    }

    public JsonNode error(JsonNode id, int code, String message)
    {
        return mapper.valueToTree(JsonRpcError.of(id, code, message));
    }

    /** Plain-text MCP tool response — no allow/deny envelope. */
    public JsonNode plainText(JsonNode id, String text)
    {
        return ok(id, ToolCallResult.text(text));
    }

    /**
     * A tool-<em>execution</em> failure: a successful JSON-RPC response
     * carrying {@code isError: true}. The model reads {@code message} and
     * may correct its call within the same session. Use this for bad
     * arguments and rejected submissions; keep {@link #error} for protocol
     * faults (unknown tool, malformed request) and {@link #deny} for the
     * permission-prompt protocol, which ends the turn.
     */
    public JsonNode toolError(JsonNode id, String message)
    {
        return ok(id, ToolCallResult.error(message));
    }

    /**
     * Wrap an allow / deny envelope (or any Jackson-serialisable
     * value) as a {@code tools/call} result. MCP returns tool
     * results as a content array whose entries are typed text;
     * here the text is the envelope serialised to JSON.
     */
    public JsonNode toolResponse(JsonNode id, Object envelope)
    {
        try {
            return ok(id, ToolCallResult.text(mapper.writeValueAsString(envelope)));
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to serialise tool envelope: " + envelope, e);
        }
    }

    public AllowEnvelope allow(JsonNode updatedInput)
    {
        return AllowEnvelope.of(mapper, updatedInput);
    }

    public DenyEnvelope deny(String message)
    {
        return DenyEnvelope.of(message);
    }

    /**
     * Exposed for the handful of call sites that still need to
     * serialise a structured payload of their own (e.g. the
     * unattended-gate escalation notification, the run_shell
     * result body). Handlers should prefer the framing methods
     * above wherever they fit.
     */
    public ObjectMapper mapper()
    {
        return mapper;
    }
}
