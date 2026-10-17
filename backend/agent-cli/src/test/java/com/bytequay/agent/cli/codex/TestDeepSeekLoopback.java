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

import com.bytequay.agent.cli.mcp.McpRegistration;
import com.bytequay.agent.cli.mcp.PermissionBroker;
import com.bytequay.agent.cli.process.SecretRedactor;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.DevelopmentResultBridge;
import com.bytequay.agent.spi.ProviderApprovalDecision;
import com.bytequay.agent.spi.SessionAccess;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestDeepSeekLoopback
{
    private static final URI UNREACHABLE = URI.create("http://127.0.0.1:1/responses");

    private final ObjectMapper mapper = new ObjectMapper();

    @TempDir
    private Path temporary;

    @Test
    void closesTheTaskScopedListener()
    {
        DeepSeekLoopback loopback = DeepSeekLoopback.start("fake-secret-for-test", UNREACHABLE, broker(), Optional.empty(), mapper);
        URI responses = loopback.baseUrl().resolve("responses");

        loopback.close();

        assertThatThrownBy(() -> HttpClient.newHttpClient().send(HttpRequest.newBuilder(responses)
                .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding()))
                .isInstanceOf(IOException.class);
    }

    @Test
    void forwardsOnlyResponsesWithTheSavedKeyAndStreamsTheProviderReply()
            throws Exception
    {
        AtomicInteger providerRequests = new AtomicInteger();
        AtomicReference<String> providerAuthorization = new AtomicReference<>();
        AtomicReference<String> providerBody = new AtomicReference<>();
        HttpServer provider = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        provider.createContext("/responses", exchange -> {
            providerRequests.incrementAndGet();
            providerAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            providerBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] stream = "data: {\"type\":\"response.completed\"}\n\n".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (exchange) {
                exchange.getResponseBody().write(stream);
            }
        });
        provider.start();
        try (DeepSeekLoopback loopback = DeepSeekLoopback.start(
                "fake-secret-for-test", URI.create("http://127.0.0.1:" + provider.getAddress().getPort() + "/responses"), broker(), Optional.empty(), mapper)) {
            HttpClient client = HttpClient.newHttpClient();
            URI responses = loopback.baseUrl().resolve("responses");
            HttpResponse<String> result = client.send(HttpRequest.newBuilder(responses)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"model\":\"deepseek-flash\"}"))
                    .build(), HttpResponse.BodyHandlers.ofString());

            assertThat(result.statusCode()).isEqualTo(200);
            assertThat(result.headers().firstValue("Content-Type")).contains("text/event-stream");
            assertThat(result.body()).isEqualTo("data: {\"type\":\"response.completed\"}\n\n");
            assertThat(providerAuthorization).hasValue("Bearer fake-secret-for-test");
            assertThat(providerBody).hasValue("{\"model\":\"deepseek-flash\"}");
            assertThat(loopback.baseUrl().toString()).doesNotContain("fake-secret-for-test");

            assertThat(client.send(HttpRequest.newBuilder(responses).GET().build(), HttpResponse.BodyHandlers.discarding()).statusCode())
                    .isEqualTo(404);
            assertThat(client.send(HttpRequest.newBuilder(responses).header("Authorization", "Bearer not-trusted")
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding()).statusCode())
                    .isEqualTo(404);
            assertThat(client.send(HttpRequest.newBuilder(loopback.baseUrl().resolve("other"))
                    .POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.discarding()).statusCode())
                    .isEqualTo(404);
            // Without task tools the MCP path is not served at all.
            assertThat(client.send(post(loopback.mcpEndpoint(), "{}"), HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(404);
            assertThat(providerRequests).hasValue(1);
        }
        finally {
            provider.stop(0);
        }
    }

    @Test
    void servesOnlyItsRegisteredTaskToolsAndClosesWithTheSession()
            throws Exception
    {
        PermissionBroker permissions = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9527/internal/agent/claude/mcp"), mapper, (taskId, query) -> "memory")
                .developments(taskId -> taskId.equals("task")
                        ? Optional.of(new DevelopmentResultBridge("turn", DevelopmentResultBridge.INSTRUCTION, false, Optional.empty())) : Optional.empty()).build();
        try (McpRegistration registration = permissions.registerTools(
                "task",
                temporary,
                AgentPermissionPolicy.AUTO,
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()),
                SecretRedactor.fromEnvironment(ImmutableMap.of()),
                Optional.empty(),
                SessionAccess.FULL,
                Optional.empty(),
                ImmutableList.of()).orElseThrow()) {
            HttpClient client = HttpClient.newHttpClient();
            HttpRequest request;
            try (DeepSeekLoopback loopback = DeepSeekLoopback.start("fake-secret-for-test", UNREACHABLE, permissions, Optional.of(registration), mapper)) {
                URI endpoint = loopback.mcpEndpoint();
                assertThat(endpoint.toString()).doesNotContain(registration.token());
                request = post(endpoint, """
                        {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                        """);
                HttpResponse<String> listed = client.send(request, HttpResponse.BodyHandlers.ofString());
                assertThat(listed.statusCode()).isEqualTo(200);
                assertThat(mapper.readTree(listed.body()).at("/result/tools").findValuesAsText("name"))
                        .contains(DevelopmentResultBridge.TOOL_NAME, DevelopmentResultBridge.PUBLISH_TOOL_NAME);
                HttpResponse<String> initialized = client.send(post(endpoint,
                        """
                        {"jsonrpc":"2.0","id":2,"method":"initialize","params":{}}
                        """),
                        HttpResponse.BodyHandlers.ofString());
                assertThat(initialized.statusCode()).isEqualTo(200);
                assertThat(client.send(post(URI.create(endpoint + "?extra=1"), "{}"), HttpResponse.BodyHandlers.ofString()).statusCode())
                        .isEqualTo(404);
                assertThat(client.send(HttpRequest.newBuilder(endpoint).header("Authorization", "Bearer " + registration.token())
                        .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.discarding()).statusCode())
                        .isEqualTo(404);
            }
            assertThatThrownBy(() -> client.send(request, HttpResponse.BodyHandlers.ofString())).isInstanceOf(IOException.class);
        }
    }

    @Test
    void routesCiToolCallsThroughTheRegisteredTaskContext()
            throws Exception
    {
        PermissionBroker permissions = PermissionBroker.builder(
                () -> URI.create("http://127.0.0.1:9527/internal/agent/claude/mcp"), mapper, (taskId, query) -> taskId + ": " + query.orElse("")).build();
        try (McpRegistration registration = permissions.registerTools(
                "ci-task",
                temporary,
                AgentPermissionPolicy.CI_AUTOFIX,
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()),
                SecretRedactor.fromEnvironment(ImmutableMap.of()),
                Optional.empty(),
                SessionAccess.FULL,
                Optional.empty(),
                ImmutableList.of()).orElseThrow();
                DeepSeekLoopback loopback = DeepSeekLoopback.start("fake-secret-for-test", UNREACHABLE, permissions, Optional.of(registration), mapper)) {
            HttpResponse<String> called = HttpClient.newHttpClient().send(post(loopback.mcpEndpoint(), """
                    {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
                    "name":"read_ci_memory","arguments":{"query":"build failure"}}}
                    """), HttpResponse.BodyHandlers.ofString());
            assertThat(called.statusCode()).isEqualTo(200);
            assertThat(mapper.readTree(called.body()).at("/result/content/0/text").asText())
                    .isEqualTo("ci-task: build failure");
        }
    }

    private PermissionBroker broker()
    {
        return PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9527/internal/agent/claude/mcp"), mapper, (taskId, query) -> "memory").build();
    }

    private static HttpRequest post(URI uri, String body)
    {
        return HttpRequest.newBuilder(uri).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body)).build();
    }
}
