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
import com.bytequay.agent.spi.DiscussionContextBridge;
import com.bytequay.agent.spi.ProviderApprovalDecision;
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

class TestDiscussionContextTool
{
    @TempDir
    private Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AgentApprovalHandler approvals = (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny());
    private final SecretRedactor redactor = SecretRedactor.fromEnvironment(ImmutableMap.of("OPENAI_API_KEY", "known-secret"));

    @Test
    void bothProvidersExposeOnlyTheirBoundReadAndRejectCrossDiscussionOrWriteArguments()
            throws Exception
    {
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/mcp"), mapper, (task, query) -> "")
                .reviews(task -> { throw new AssertionError("Read-only sessions must not resolve task write capabilities"); }).build();
        String closed;
        try (McpRegistration codex = broker.registerTools(
                "same-task",
                directory,
                AgentPermissionPolicy.AUTO,
                approvals,
                redactor,
                Optional.empty(),
                SessionAccess.READ_ONLY,
                Optional.of(new DiscussionContextBridge(query -> "first known-secret")),
                ImmutableList.of()).orElseThrow();
                McpRegistration claude = broker.register("same-task", directory, directory, directory.resolve("provider"), AgentPermissionPolicy.AUTO,
                        SessionAccess.READ_ONLY, approvals, redactor, Optional.empty(), ImmutableMap.of(),
                        Optional.of(new DiscussionContextBridge(query -> "second")), ImmutableList.of())) {
            closed = codex.token();
            assertThat(codex.toolNames()).containsExactly(DiscussionContextBridge.TOOL_NAME, "bytequay_list_tools", "bytequay_get_tool", "bytequay_call_tool");
            assertThat(claude.toolNames()).isEqualTo(codex.toolNames());
            assertThat(codex.instruction()).hasValueSatisfying(text -> assertThat(text).contains(DiscussionContextBridge.INSTRUCTION));
            assertThat(call(broker, codex.token(), "read_discussion_context", "{\"section\":\"history\"}").at("/result/content/0/text").asText())
                    .startsWith("first ").doesNotContain("known-secret", "second");
            assertThat(call(broker, claude.token(), "read_discussion_context", "{\"section\":\"history\",\"offset\":0}").at("/result/content/0/text").asText()).isEqualTo("second");
            for (String token : ImmutableList.of(codex.token(), claude.token())) {
                for (String invalid : ImmutableList.of("{}", "{\"section\":\"other-task\"}", "{\"section\":\"history\",\"taskId\":\"other\"}",
                        "{\"section\":\"history\",\"offset\":-1}", "{\"section\":\"history\",\"offset\":0.5}",
                        "{\"section\":\"history\",\"offset\":2147483648}", "{\"section\":\"history\",\"offset\":0,\"discussionId\":\"other\"}")) {
                    assertThat(call(broker, token, "read_discussion_context", invalid).at("/error/code").asInt()).isEqualTo(-32602);
                }
                assertThat(call(broker, token, "resolve_local_review_comment", "{}").has("error")).isTrue();
                assertThat(call(broker, token, "bytequay_call_tool", "{\"name\":\"read_discussion_context\",\"arguments\":{\"section\":\"source\"}}")
                        .at("/result/isError").asBoolean()).isFalse();
            }
            JsonNode settings = mapper.readTree(Files.readString(claude.settingsFile().orElseThrow()));
            assertThat(settings.at("/sandbox/filesystem/allowWrite")).isEmpty();
            assertThat(settings.at("/sandbox/allowUnsandboxedCommands").asBoolean()).isFalse();
        }
        assertThat(broker.handle(closed, mapper.createObjectNode()).status()).isEqualTo(401);
        assertThat(broker.registerTools(
                "same-task",
                directory,
                AgentPermissionPolicy.AUTO,
                approvals,
                redactor,
                Optional.empty(),
                SessionAccess.READ_ONLY,
                Optional.empty(),
                ImmutableList.of())).isEmpty();
        assertThatThrownBy(() -> broker.registerTools(
                "same-task",
                directory,
                AgentPermissionPolicy.AUTO,
                approvals,
                redactor,
                Optional.empty(),
                SessionAccess.FULL,
                Optional.of(new DiscussionContextBridge(query -> "context")),
                ImmutableList.of()))
                .hasMessageContaining("read-only");
    }

    private JsonNode call(PermissionBroker broker, String token, String name, String arguments)
            throws Exception
    {
        return broker.handle(token, mapper.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"%s","arguments":%s}}
                """.formatted(name, arguments))).body().orElseThrow();
    }
}
