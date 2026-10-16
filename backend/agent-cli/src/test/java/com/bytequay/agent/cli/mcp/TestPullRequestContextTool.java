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
import com.bytequay.agent.spi.AgentApprovalHandler;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.ProviderApprovalDecision;
import com.bytequay.agent.spi.PullRequestContextBridge;
import com.bytequay.agent.spi.SessionAccess;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TestPullRequestContextTool
{
    @TempDir
    private Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentApprovalHandler approvals = (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny());
    private final SecretRedactor redactor = SecretRedactor.fromEnvironment(ImmutableMap.of("OPENAI_API_KEY", "known-secret"));

    @Test
    void bothProvidersDiscoverTheSameTaskBoundReadWithStrictArgumentsAndRevocation()
            throws Exception
    {
        AtomicReference<Optional<PullRequestContextBridge>> available = new AtomicReference<>(Optional.of(new PullRequestContextBridge(query -> "saved known-secret")));
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/mcp"), mapper, (task, query) -> "")
                .pullRequestContexts(task -> task.equals("assigned") ? available.get() : Optional.empty()).build();
        String closed;
        try (McpRegistration codex = broker.registerTools("assigned", directory, AgentPermissionPolicy.AUTO, approvals, redactor, Optional.empty()).orElseThrow();
                McpRegistration claude = broker.register("assigned", directory, directory, directory.resolve("provider"), AgentPermissionPolicy.AUTO,
                        SessionAccess.FULL, approvals, redactor, Optional.empty(), ImmutableMap.of(), Optional.empty(), Optional.empty())) {
            closed = codex.token();
            for (McpRegistration registration : ImmutableList.of(codex, claude)) {
                assertThat(registration.toolNames()).contains(PullRequestContextBridge.TOOL_NAME);
                assertThat(registration.instruction()).hasValueSatisfying(text -> assertThat(text).contains(PullRequestContextBridge.INSTRUCTION));
                assertThat(call(broker, registration.token(), PullRequestContextBridge.TOOL_NAME, "{\"section\":\"snapshot\"}")
                        .at("/result/content/0/text").asText()).startsWith("saved ").doesNotContain("known-secret");
                for (String invalid : ImmutableList.of("{}", "{\"section\":\"other\"}", "{\"section\":\"snapshot\",\"taskId\":\"other\"}",
                        "{\"section\":\"snapshot\",\"offset\":-1}", "{\"section\":\"snapshot\",\"offset\":0.5}",
                        "{\"section\":\"snapshot\",\"offset\":2147483648}", "{\"section\":\"snapshot\",\"repo\":\"other/repo\"}")) {
                    assertThat(call(broker, registration.token(), PullRequestContextBridge.TOOL_NAME, invalid).at("/error/code").asInt()).isEqualTo(-32602);
                }
                assertThat(call(broker, registration.token(), "bytequay_call_tool", "{\"name\":\"read_pull_request_context\",\"arguments\":{\"section\":\"description\"}}")
                        .at("/result/isError").asBoolean()).isFalse();
            }
            available.set(Optional.empty());
            assertThat(call(broker, codex.token(), PullRequestContextBridge.TOOL_NAME, "{\"section\":\"snapshot\"}").at("/result/isError").asBoolean()).isTrue();
        }
        assertThat(broker.handle(closed, mapper.createObjectNode()).status()).isEqualTo(401);
        available.set(Optional.of(new PullRequestContextBridge(query -> "saved")));
        assertThat(broker.registerTools("assigned", directory, AgentPermissionPolicy.AUTO, approvals, redactor,
                Optional.empty(), SessionAccess.READ_ONLY, Optional.empty())).isEmpty();
        assertThat(broker.registerTools("other", directory, AgentPermissionPolicy.AUTO, approvals, redactor, Optional.empty())).isEmpty();
    }

    private JsonNode call(PermissionBroker broker, String token, String name, String arguments)
            throws Exception
    {
        return broker.handle(token, mapper.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"%s","arguments":%s}}
                """.formatted(name, arguments))).body().orElseThrow();
    }
}
