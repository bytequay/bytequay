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
package com.bytequay.app.developmentflow.execution.agentturn;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static com.bytequay.app.developmentflow.execution.DispatchTicket.OwnerKind.STAGE_TURN;
import static com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession.ToolProfile.STAGE_DEVELOPMENT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestLoopbackOwnerMcpClient
{
    @Test
    @SuppressWarnings("unchecked")
    void sendsTheExactWriterIdentityToTheExactOwnerEndpoint()
            throws Exception
    {
        HttpClient http = mock(HttpClient.class);
        HttpResponse<String> response = mock(HttpResponse.class);
        AtomicReference<HttpRequest> sent = new AtomicReference<>();
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("""
                {"jsonrpc":"2.0","id":1,"result":{"tools":[{
                  "name":"report_development",
                  "description":"Report development",
                  "inputSchema":{"type":"object"}
                }]}}
                """);
        when(http.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenAnswer(invocation -> {
                    sent.set(invocation.getArgument(0));
                    return response;
                });
        LoopbackOwnerMcpClient client =
                new LoopbackOwnerMcpClient(http, new ObjectMapper());
        AgentTurnProviderSession.WriterFence fence =
                new AgentTurnProviderSession.WriterFence(
                        "/tmp/worktree", "task-1", "operation-1", 7, 13);

        List<ApiAgentTurnProviderSession.OwnerMcpClient.ToolDefinition> tools =
                client.list(endpoint(), fence);

        assertThat(tools).singleElement().satisfies(tool -> {
            assertThat(tool.name()).isEqualTo("report_development");
            assertThat(tool.inputSchema().path("type").asText()).isEqualTo("object");
        });
        HttpRequest request = sent.get();
        assertThat(request.uri().toString()).isEqualTo(endpoint().url());
        assertThat(request.headers().firstValue("X-ByteQuay-Task-Id"))
                .contains("task-1");
        assertThat(request.headers().firstValue("X-ByteQuay-Operation-Id"))
                .contains("operation-1");
        assertThat(request.headers().firstValue("X-ByteQuay-Task-Epoch"))
                .contains("7");
        assertThat(request.headers().firstValue("X-ByteQuay-Writer-Fencing-Token"))
                .contains("13");
    }

    private static AgentTurnProviderSession.OwnerToolEndpoint endpoint()
    {
        return new AgentTurnProviderSession.OwnerToolEndpoint(
                "bytequay",
                "http://127.0.0.1:53123/api/v2/stage-turns/stage-turn-1/"
                        + "operations/operation-1/mcp",
                STAGE_TURN,
                "stage-turn-1",
                "operation-1",
                STAGE_DEVELOPMENT,
                "mcp__bytequay__approval_prompt");
    }
}
