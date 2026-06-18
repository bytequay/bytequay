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

import com.bytequay.app.service.review.ReviewMcpService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Coverage for the Streamable-HTTP framing the review-seat MCP controller
 * layers on top of the JSON-RPC body — the same 202-for-notifications and
 * {@code Mcp-Session-Id} contract the thread {@code McpController} enforces,
 * without which a strict client (Codex's rmcp client) abandons the handshake
 * before it ever lists the review tools. The JSON-RPC body itself is covered
 * by {@code TestReviewMcpService}; here we only assert the transport envelope.
 */
class TestReviewMcpController
{
    private final ObjectMapper mapper = new ObjectMapper();
    private final ReviewMcpService service = mock(ReviewMcpService.class);
    private final ReviewMcpController controller = new ReviewMcpController(service);

    @Test
    void notificationGets202EmptyBodyAndSessionHeader()
            throws Exception
    {
        // A notification yields a null body from the service; the controller
        // must still answer 202 (not 200) so the client treats the handshake
        // as accepted.
        when(service.handle(eq("pass-1"), eq("seat-1"), any())).thenReturn(null);
        MockHttpServletResponse response = new MockHttpServletResponse();

        JsonNode body = controller.handle("pass-1", "seat-1",
                mapper.readTree("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"),
                response);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_ACCEPTED);
        assertThat(body).isNull();
        assertThat(response.getHeader("Mcp-Session-Id")).isEqualTo("pass-1");
    }

    @Test
    void realRequestStays200WithSessionHeader()
            throws Exception
    {
        when(service.handle(eq("pass-1"), eq("seat-1"), any()))
                .thenReturn(mapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{}}"));
        MockHttpServletResponse response = new MockHttpServletResponse();

        JsonNode body = controller.handle("pass-1", "seat-1",
                mapper.readTree("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}"),
                response);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(body.path("result").isObject()).isTrue();
        assertThat(response.getHeader("Mcp-Session-Id")).isEqualTo("pass-1");
    }
}
