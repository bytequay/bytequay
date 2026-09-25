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

import com.bytequay.agent.cli.mcp.CodeGraphRegistration;
import com.bytequay.agent.cli.mcp.PermissionBroker;
import com.bytequay.agent.cli.process.SecretRedactor;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.BridgeResolver;
import com.bytequay.agent.spi.DevelopmentResultBridge;
import com.bytequay.agent.spi.ProviderApprovalDecision;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestDeepSeekTaskMcpBridge
{
    @TempDir
    private Path temporary;

    @Test
    void servesOnlyItsRegisteredTaskToolsAndClosesWithTheSession()
            throws Exception
    {
        ObjectMapper mapper = new ObjectMapper();
        PermissionBroker permissions = new PermissionBroker(
                () -> URI.create("http://127.0.0.1:9527/internal/agent/claude/mcp"), mapper,
                (taskId, query) -> "memory", BridgeResolver.none(),
                taskId -> taskId.equals("task") ? Optional.of(new DevelopmentResultBridge("turn", DevelopmentResultBridge.INSTRUCTION)) : Optional.empty());
        try (CodeGraphRegistration registration = permissions.registerTools("task", temporary, AgentPermissionPolicy.AUTO,
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()),
                SecretRedactor.fromEnvironment(ImmutableMap.of()), Optional.empty()).orElseThrow()) {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request;
            try (DeepSeekTaskMcpBridge bridge = DeepSeekTaskMcpBridge.start(permissions, registration, mapper)) {
                assertThat(bridge.endpoint().toString()).doesNotContain(registration.token());
                request = post(bridge.endpoint(), """
                        {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                        """);
                HttpResponse<String> listed = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertThat(listed.statusCode()).isEqualTo(200);
                assertThat(mapper.readTree(listed.body()).at("/result/tools").findValuesAsText("name"))
                        .contains(DevelopmentResultBridge.TOOL_NAME, DevelopmentResultBridge.PUBLISH_TOOL_NAME);
                HttpResponse<String> initialized = client.send(post(bridge.endpoint(),
                        """
                        {"jsonrpc":"2.0","id":2,"method":"initialize","params":{}}
                        """),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(initialized.statusCode()).isEqualTo(200);
                assertThat(client.send(post(URI.create(bridge.endpoint() + "?extra=1"), "{}"), HttpResponse.BodyHandlers.ofString()).statusCode())
                        .isEqualTo(404);
            }
            assertThatThrownBy(() -> client.send(request, HttpResponse.BodyHandlers.ofString())).isInstanceOf(IOException.class);
        }
    }

    @Test
    void routesCiToolCallsThroughTheRegisteredTaskContext()
            throws Exception
    {
        ObjectMapper mapper = new ObjectMapper();
        PermissionBroker permissions = new PermissionBroker(
                () -> URI.create("http://127.0.0.1:9527/internal/agent/claude/mcp"), mapper,
                (taskId, query) -> taskId + ": " + query.orElse(""), BridgeResolver.none());
        try (CodeGraphRegistration registration = permissions.registerTools("ci-task", temporary, AgentPermissionPolicy.CI_AUTOFIX,
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()),
                SecretRedactor.fromEnvironment(ImmutableMap.of()), Optional.empty()).orElseThrow();
                DeepSeekTaskMcpBridge bridge = DeepSeekTaskMcpBridge.start(permissions, registration, mapper)) {
            HttpResponse<String> called = HttpClient.newHttpClient().send(post(bridge.endpoint(), """
                    {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
                    "name":"read_ci_memory","arguments":{"query":"build failure"}}}
                    """), HttpResponse.BodyHandlers.ofString());
            assertThat(called.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(called.body()).at("/result/content/0/text").asText())
                    .isEqualTo("ci-task: build failure");
        }
    }

    private static HttpRequest post(URI uri, String body)
    {
        return HttpRequest.newBuilder(uri).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }
}
