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
package com.bytequay.app.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.async.DeferredResult;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins the Streamable-HTTP transport framing the MCP controller adds on top of
 * the JSON-RPC body. This is what lets a strict client — notably the
 * {@code rmcp} HTTP client Codex uses — get past the handshake and go on to
 * {@code tools/list}: a JSON-RPC notification must come back as {@code 202
 * Accepted} with an empty body, and every response carries an
 * {@code Mcp-Session-Id}. A regression here is invisible to Claude (its client
 * tolerates a plain 200) but silently strands Codex on the bytequay tools.
 */
@SpringBootTest
class TestMcpStreamableHttpFraming
{
    @Autowired
    private McpController controller;
    @Autowired
    private ObjectMapper mapper;

    // Both notification methods we answer (initialized + cancelled) carry no
    // id and must come back 202 + empty body + session header.
    @ParameterizedTest
    @ValueSource(strings = {"notifications/initialized", "notifications/cancelled"})
    void notificationGets202WithEmptyBodyAndASessionHeader(String method)
            throws Exception
    {
        MockHttpServletResponse response = new MockHttpServletResponse();

        JsonNode body = await(controller.handle("thread-xyz", "trunk",
                mapper.readTree("{\"jsonrpc\":\"2.0\",\"method\":\"" + method + "\"}"),
                response));

        // 202 Accepted + empty body is what the spec mandates for a JSON-RPC
        // notification; a plain 200 trips Codex's transport.
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_ACCEPTED);
        assertThat(body).isNull();
        assertThat(response.getHeader("Mcp-Session-Id")).isEqualTo("thread-xyz");
    }

    @Test
    void initializeKeeps200AndCarriesTheSessionHeader()
            throws Exception
    {
        MockHttpServletResponse response = new MockHttpServletResponse();

        JsonNode body = await(controller.handle("thread-xyz", "trunk",
                mapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
                        + "\"params\":{\"protocolVersion\":\"2024-11-05\"}}"),
                response));

        // A real request stays a 200 with a JSON-RPC result body; only the
        // session header is added.
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(body.path("result").path("serverInfo").path("name").asText())
                .isNotBlank();
        assertThat(response.getHeader("Mcp-Session-Id")).isEqualTo("thread-xyz");
    }

    @Test
    void nonNotificationRequestKeeps200()
            throws Exception
    {
        // tools/list is a request (it carries an id), so it must NOT be
        // downgraded to 202 — only notifications/* are.
        MockHttpServletResponse response = new MockHttpServletResponse();

        JsonNode body = await(controller.handle("thread-xyz", "trunk",
                mapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}"),
                response));

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(body.path("result").path("tools").isArray()).isTrue();
        assertThat(response.getHeader("Mcp-Session-Id")).isEqualTo("thread-xyz");
    }

    private static JsonNode await(DeferredResult<JsonNode> deferred)
            throws InterruptedException
    {
        long deadline = System.currentTimeMillis() + 5_000L;
        while (!deferred.hasResult() && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        if (!deferred.hasResult()) {
            throw new IllegalStateException("DeferredResult did not complete in time");
        }
        return (JsonNode) deferred.getResult();
    }
}
