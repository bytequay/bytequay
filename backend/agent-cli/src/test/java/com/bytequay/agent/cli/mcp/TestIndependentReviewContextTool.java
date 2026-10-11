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
import com.bytequay.agent.spi.ReviewTool;
import com.bytequay.agent.spi.SessionAccess;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestIndependentReviewContextTool
{
    @TempDir
    private Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentApprovalHandler approvals = (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny());
    private final SecretRedactor redactor = SecretRedactor.fromEnvironment(ImmutableMap.of("OPENAI_API_KEY", "known-secret"));

    @Test
    void contextReadersAreSessionBoundReadOnlyAndRevokedOnClose()
            throws Exception
    {
        PermissionBroker broker = new PermissionBroker(() -> URI.create("http://127.0.0.1:9000/mcp"), mapper, (task, query) -> "",
                task -> { throw new AssertionError("Independent reviews must not resolve task write capabilities"); });
        String closed;
        try (McpRegistration codex = broker.registerTools("same-task", directory, AgentPermissionPolicy.AUTO, approvals, redactor,
                Optional.empty(), SessionAccess.READ_ONLY, Optional.empty(), Optional.of(context("first known-secret"))).orElseThrow();
                McpRegistration claude = broker.register("same-task", directory, directory, directory.resolve("provider"), AgentPermissionPolicy.AUTO,
                        SessionAccess.READ_ONLY, approvals, redactor, Optional.empty(), ImmutableMap.of(), Optional.empty(), Optional.of(context("second")))) {
            closed = codex.token();
            assertThat(codex.toolNames()).containsExactly("review_get_context", "bytequay_list_tools", "bytequay_get_tool", "bytequay_call_tool");
            assertThat(claude.toolNames()).isEqualTo(codex.toolNames());
            assertThat(codex.review()).isEmpty();
            assertThat(call(broker, codex.token(), "review_get_context", "{}").at("/result/content/0/text").asText())
                    .startsWith("first ").doesNotContain("known-secret", "second");
            assertThat(call(broker, claude.token(), "review_get_context", "{}").at("/result/content/0/text").asText()).isEqualTo("second");
            for (String token : ImmutableList.of(codex.token(), claude.token())) {
                assertThat(call(broker, token, "review_save_finding", "{}").has("error")).isTrue();
                assertThat(call(broker, token, "bytequay_call_tool", "{\"name\":\"review_get_context\",\"arguments\":{}}")
                        .at("/result/isError").asBoolean()).isFalse();
            }
            JsonNode settings = mapper.readTree(Files.readString(claude.settingsFile().orElseThrow()));
            assertThat(settings.at("/sandbox/filesystem/allowWrite")).isEmpty();
            assertThat(settings.at("/sandbox/allowUnsandboxedCommands").asBoolean()).isFalse();
        }
        assertThat(broker.handle(closed, mapper.createObjectNode()).status()).isEqualTo(401);
        assertThatThrownBy(() -> broker.registerTools("same-task", directory, AgentPermissionPolicy.AUTO, approvals, redactor,
                Optional.empty(), SessionAccess.FULL, Optional.empty(), Optional.of(context("context")))).hasMessageContaining("read-only");
        assertThatThrownBy(() -> broker.registerTools("same-task", directory, AgentPermissionPolicy.AUTO, approvals, redactor,
                Optional.empty(), SessionAccess.READ_ONLY, Optional.empty(),
                Optional.of(new ReviewTool("review_save_finding", "Write", ImmutableMap.of(), query -> "", false))))
                .hasMessageContaining("Only review context reads");
    }

    private static ReviewTool context(String text)
    {
        return new ReviewTool("review_get_context", "Read captured context", ImmutableMap.of("type", "object"), query -> text, true);
    }

    private JsonNode call(PermissionBroker broker, String token, String name, String arguments)
            throws Exception
    {
        return broker.handle(token, mapper.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"%s","arguments":%s}}
                """.formatted(name, arguments))).body().orElseThrow();
    }
}
