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

import com.bytequay.app.developmentflow.execution.agentturn.ThreadTurnOperationHandler;
import com.bytequay.app.service.mcp.McpServiceImpl;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.context.request.async.DeferredResult;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestThreadTurnMcpController
{
    private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");
    private final ObjectMapper json = new ObjectMapper();
    private final McpServiceImpl service = mock(McpServiceImpl.class);
    private final ThreadTurnOperationHandler.Store turns =
            mock(ThreadTurnOperationHandler.Store.class);
    private final ThreadTurnMcpController controller =
            new ThreadTurnMcpController(
                    service, turns, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void exactActiveOperationRoutesWithItsPrivateAgentKey()
            throws Exception
    {
        when(turns.findMcpTrunk("turn-1", "operation-1", NOW))
                .thenReturn(Optional.of("trunk-1"));
        DeferredResult<JsonNode> expected = new DeferredResult<>();
        when(service.handle(
                eq("trunk-1"),
                eq(ThreadTurnOperationHandler.mcpAgentKey(
                        "turn-1", "operation-1")), any()))
                .thenReturn(expected);
        MockHttpServletResponse response = new MockHttpServletResponse();

        DeferredResult<JsonNode> actual = controller.handle(
                "turn-1", "operation-1",
                json.readTree("""
                        {"jsonrpc":"2.0","id":1,"method":"tools/list"}
                        """), response);

        assertThat(actual).isSameAs(expected);
        assertThat(response.getHeader("Mcp-Session-Id"))
                .isEqualTo("turn-1:operation-1");
    }

    @Test
    void absentOrReleasedOperationFailsClosedBeforeMcpService()
            throws Exception
    {
        when(turns.findMcpTrunk("turn-1", "operation-1", NOW))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> controller.handle(
                "turn-1", "operation-1",
                json.readTree("""
                        {"jsonrpc":"2.0","id":1,"method":"tools/list"}
                        """), new MockHttpServletResponse()))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        failure -> assertThat(failure.getStatusCode().value())
                                .isEqualTo(404));
        verify(service, never()).handle(any(), any(), any());
    }
}
