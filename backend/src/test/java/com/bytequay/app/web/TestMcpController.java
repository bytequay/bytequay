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

import com.bytequay.app.service.mcp.McpServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.async.DeferredResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The thread MCP controller exposes two routes onto the same service: the
 * legacy {@code /threads/{threadId}/mcp} path (no agent key — single-agent /
 * trunk resolution) and the per-agent {@code /threads/{threadId}/agents/
 * {agentKey}/mcp} path that carries the connecting runtime's task/trunk key.
 */
class TestMcpController
{
    private final ObjectMapper mapper = new ObjectMapper();
    private final McpServiceImpl service = mock(McpServiceImpl.class);
    private final McpController controller = new McpController(service);

    @Test
    void agentScopedRouteForwardsTheAgentKey()
            throws Exception
    {
        DeferredResult<JsonNode> expected = new DeferredResult<>();
        when(service.handle(eq("thread-1"), eq("stage-7"), any())).thenReturn(expected);
        MockHttpServletResponse response = new MockHttpServletResponse();

        DeferredResult<JsonNode> result = controller.handle("thread-1", "stage-7",
                mapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"),
                response);

        assertThat(result).isSameAs(expected);
        assertThat(response.getHeader("Mcp-Session-Id")).isEqualTo("thread-1");
    }

    @Test
    void trunkAgentKeyRoutesThroughTheAgentScopedPath()
            throws Exception
    {
        DeferredResult<JsonNode> expected = new DeferredResult<>();
        when(service.handle(eq("thread-1"), eq("trunk"), any())).thenReturn(expected);
        MockHttpServletResponse response = new MockHttpServletResponse();

        DeferredResult<JsonNode> result = controller.handle("thread-1", "trunk",
                mapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"),
                response);

        assertThat(result).isSameAs(expected);
    }
}
