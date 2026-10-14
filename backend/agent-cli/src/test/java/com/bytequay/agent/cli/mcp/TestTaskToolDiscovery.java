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
package com.bytequay.agent.cli.mcp;

import com.bytequay.agent.cli.process.SecretRedactor;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.ProviderApprovalDecision;
import com.bytequay.agent.spi.ReviewBridge;
import com.bytequay.agent.spi.ReviewTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TestTaskToolDiscovery
{
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicInteger writes = new AtomicInteger();
    private final AtomicReference<ReviewBridge> review = new AtomicReference<>(review("review_finish", "complete"));
    private final PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/mcp"), json, (task, query) -> "")
            .reviews(task -> task.equals("review") ? Optional.of(review.get()) : Optional.empty()).build();

    @TempDir
    private Path directory;

    @Test
    void stableDispatcherDiscoversChangedNamesAndParametersAfterReopening()
            throws Exception
    {
        JsonNode originalDispatcher;
        try (McpRegistration registration = register()) {
            originalDispatcher = json.valueToTree(broker.guardCodexRootTools(registration).rootTools());
            assertThat(registration.instruction()).hasValueSatisfying(value -> assertThat(value)
                    .contains("bytequay_list_tools", "bytequay_get_tool", "bytequay_call_tool", "Choose the tools relevant"));
            assertThat(content(call(registration, "bytequay_list_tools", "{}")).findValuesAsText("name"))
                    .containsExactly("review_get_context", "review_finish");
            assertThat(content(call(registration, "bytequay_list_tools", "{}")).findValues("inputSchema")).isEmpty();
            assertThat(content(call(registration, "bytequay_get_tool", "{\"name\":\"review_finish\"}")).at("/inputSchema/required/0").asText())
                    .isEqualTo("complete");
        }
        review.set(review("review_record_discovery", "changeMap"));
        try (McpRegistration registration = register()) {
            assertThat(json.<JsonNode>valueToTree(broker.guardCodexRootTools(registration).rootTools())).isEqualTo(originalDispatcher);
            assertThat(content(call(registration, "bytequay_list_tools", "{}")).findValuesAsText("name"))
                    .containsExactly("review_get_context", "review_record_discovery");
            JsonNode details = content(call(registration, "bytequay_get_tool", "{\"name\":\"review_record_discovery\"}"));
            assertThat(details.path("description").asText()).contains("Save private review data");
            assertThat(details.at("/inputSchema/required/0").asText()).isEqualTo("changeMap");
            assertThat(details.at("/inputSchema/properties/changeMap/type").asText()).isEqualTo("string");
            JsonNode result = broker.callCodexRootTool(registration.token(), PermissionBroker.CALL_TOOL,
                    json.readTree("{\"name\":\"review_record_discovery\",\"arguments\":{\"changeMap\":\"current map\"}}"))
                    .body().orElseThrow();
            assertThat(result.at("/result/content/0/text").asText()).isEqualTo("saved current map [REDACTED]");
            assertThat(writes).hasValue(1);
        }
    }

    @Test
    void discoveryCannotBypassRootPermissionsOrInvokeUnknownTools()
            throws Exception
    {
        String token;
        try (McpRegistration registration = register()) {
            token = registration.token();
            broker.guardCodexRootTools(registration);
            assertThat(call(registration, PermissionBroker.CALL_TOOL, "{\"name\":\"review_finish\",\"arguments\":{\"complete\":\"yes\"}}")
                    .at("/error/code").asInt()).isEqualTo(-32602);
            for (String name : ImmutableList.of("unknown", "bytequay_call_tool", "bytequay_get_tool", "permission_prompt", "create_draft_pull_request")) {
                JsonNode arguments = json.valueToTree(ImmutableMap.of("name", name, "arguments", ImmutableMap.of()));
                assertThat(broker.callCodexRootTool(token, PermissionBroker.CALL_TOOL, arguments)
                        .body().orElseThrow().at("/error/code").asInt()).isEqualTo(-32602);
            }
            for (String arguments : ImmutableList.of("[]", "{}", "{\"name\":1,\"arguments\":{}}", "{\"name\":\"review_finish\",\"arguments\":[]}")) {
                assertThat(broker.callCodexRootTool(token, PermissionBroker.CALL_TOOL, json.readTree(arguments))
                        .body().orElseThrow().at("/error/code").asInt()).isEqualTo(-32602);
            }
            assertThat(call(registration, "bytequay_get_tool", "{\"name\":\"unknown\"}").at("/error/code").asInt()).isEqualTo(-32602);
            assertThat(call(registration, "bytequay_list_tools", "{\"name\":\"unknown\"}").at("/error/code").asInt()).isEqualTo(-32602);
            assertThat(call(registration, "bytequay_get_tool", "{}").at("/error/code").asInt()).isEqualTo(-32602);
        }
        assertThat(broker.callCodexRootTool(token, PermissionBroker.CALL_TOOL,
                json.readTree("{\"name\":\"review_finish\",\"arguments\":{\"complete\":\"yes\"}}"))
                .body().orElseThrow().at("/error/code").asInt()).isEqualTo(-32602);
        assertThat(writes).hasValue(0);
    }

    @Test
    void ordinaryMcpCallsUseTheSameCatalogAndInputValidation()
            throws Exception
    {
        try (McpRegistration registration = register()) {
            assertThat(call(registration, PermissionBroker.CALL_TOOL, "{\"name\":\"review_finish\",\"arguments\":{\"complete\":\"yes\"}}")
                    .at("/result/content/0/text").asText()).isEqualTo("saved yes [REDACTED]");
            assertThat(call(registration, PermissionBroker.CALL_TOOL, "{\"name\":\"review_finish\",\"arguments\":[]}")
                    .at("/error/code").asInt()).isEqualTo(-32602);
            assertThat(writes).hasValue(1);
        }
    }

    private ReviewBridge review(String name, String parameter)
    {
        return new ReviewBridge(ImmutableList.of(
                new ReviewTool("review_get_context", "Read context", ImmutableMap.of("type", "object"), arguments -> "context", true),
                new ReviewTool(name, "Save private review data with " + parameter,
                        ImmutableMap.of("type", "object", "properties", ImmutableMap.of(parameter, ImmutableMap.of("type", "string")),
                                "required", ImmutableList.of(parameter)), arguments -> {
                                    writes.incrementAndGet();
                                    try {
                                        return "saved " + json.readTree(arguments).path(parameter).asText() + " known-secret";
                                    }
                                    catch (Exception failure) {
                                        throw new IllegalArgumentException(failure);
                                    }
                                })), "Review privately");
    }

    private McpRegistration register()
    {
        return broker.registerTools("review", directory, AgentPermissionPolicy.AUTO,
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()),
                SecretRedactor.fromEnvironment(ImmutableMap.of("OPENAI_API_KEY", "known-secret")), Optional.empty()).orElseThrow();
    }

    private JsonNode call(McpRegistration registration, String name, String arguments)
            throws Exception
    {
        return broker.handle(registration.token(), json.valueToTree(ImmutableMap.of("jsonrpc", "2.0", "id", 1, "method", "tools/call",
                "params", ImmutableMap.of("name", name, "arguments", json.readTree(arguments))))).body().orElseThrow();
    }

    private JsonNode content(JsonNode response)
            throws Exception
    {
        return json.readTree(response.at("/result/content/0/text").asText());
    }
}
