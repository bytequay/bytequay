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
import com.bytequay.agent.spi.BridgeResolver;
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
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

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
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/mcp"), mapper, (task, query) -> "")
                .reviews(task -> { throw new AssertionError("Independent reviews must not resolve task write capabilities"); }).build();
        String closed;
        try (McpRegistration codex = broker.registerTools("same-task", directory, AgentPermissionPolicy.AUTO, approvals, redactor,
                Optional.empty(), SessionAccess.READ_ONLY, Optional.empty(), ImmutableList.of(context("first known-secret"))).orElseThrow();
                McpRegistration claude = broker.register("same-task", directory, directory, directory.resolve("provider"), AgentPermissionPolicy.AUTO,
                        SessionAccess.READ_ONLY, approvals, redactor, Optional.empty(), ImmutableMap.of(), Optional.empty(), ImmutableList.of(context("second")))) {
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
                Optional.empty(), SessionAccess.FULL, Optional.empty(), ImmutableList.of(context("context")))).hasMessageContaining("read-only");
        assertThatThrownBy(() -> broker.registerTools("same-task", directory, AgentPermissionPolicy.AUTO, approvals, redactor,
                Optional.empty(), SessionAccess.READ_ONLY, Optional.empty(),
                ImmutableList.of(new ReviewTool("review_save_finding", "Write", ImmutableMap.of(), query -> "", false))))
                .hasMessageContaining("Only context reads");
    }

    @Test
    void anAgendaRunReadsItsDayThroughTheSameSlot()
            throws Exception
    {
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/mcp"), mapper, (task, query) -> "")
                .reviews(task -> { throw new AssertionError("An agenda run must not resolve task write capabilities"); }).build();
        ReviewTool agenda = new ReviewTool("agenda_context", "Read the user's day", ImmutableMap.of("type", "object"), query -> "today", true);
        try (McpRegistration codex = broker.registerTools("agenda-run", directory, AgentPermissionPolicy.AUTO, approvals, redactor,
                Optional.empty(), SessionAccess.READ_ONLY, Optional.empty(), ImmutableList.of(agenda)).orElseThrow()) {
            assertThat(codex.toolNames()).containsExactly("agenda_context", "bytequay_list_tools", "bytequay_get_tool", "bytequay_call_tool");
            assertThat(call(broker, codex.token(), "agenda_context", "{}").at("/result/content/0/text").asText()).isEqualTo("today");
        }
        assertThatThrownBy(() -> broker.registerTools("agenda-run", directory, AgentPermissionPolicy.AUTO, approvals, redactor,
                Optional.empty(), SessionAccess.READ_ONLY, Optional.empty(),
                ImmutableList.of(new ReviewTool("agenda_context", "Read the user's day", ImmutableMap.of(), query -> "", false))))
                .hasMessageContaining("Only context reads");
    }

    @Test
    void aToolsOnlySessionCarriesOnlyTheAssistantToolsAndApprovesNothing()
            throws Exception
    {
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/mcp"), mapper, (task, query) -> "")
                .reviews(unresolved()).developments(unresolved()).localReviews(unresolved()).pullRequestChecks(unresolved()).issueContexts(unresolved())
                .memories(unresolved()).relatedWork(unresolved()).workItems(unresolved()).googleWorkspace(unresolved()).pullRequestContexts(unresolved()).build();
        AgentApprovalHandler noCards = (request, timeout) -> { throw new AssertionError("A tools-only session has nothing to approve"); };
        List<String> acted = new CopyOnWriteArrayList<>();
        ReviewTool read = new ReviewTool("assistant_context", "Read the user's day", ImmutableMap.of("type", "object"), query -> "today", true);
        ReviewTool act = new ReviewTool("assistant_act", "Act on the user's words", ImmutableMap.of("type", "object"), query -> {
            acted.add(query);
            return "done";
        }, false);
        ReviewTool show = new ReviewTool("assistant_show", "Show the user a list", ImmutableMap.of("type", "object"), query -> "shown", false);
        Path day = Files.createDirectories(directory.resolve("assistant/2026-10-17"));
        try (McpRegistration codex = broker.registerTools("assistant-run", day, AgentPermissionPolicy.AUTO, noCards, redactor,
                Optional.empty(), SessionAccess.TOOLS_ONLY, Optional.empty(), ImmutableList.of(read, act, show)).orElseThrow();
                McpRegistration claude = broker.register("assistant-run", day, directory, directory.resolve("provider"), AgentPermissionPolicy.AUTO,
                        SessionAccess.TOOLS_ONLY, noCards, redactor, Optional.empty(), ImmutableMap.of(), Optional.empty(), ImmutableList.of(read, act, show))) {
            // It calls its three tools by name: no catalog, and no second way to reach assistant_act.
            assertThat(codex.toolNames()).containsExactly("assistant_context", "assistant_act", "assistant_show");
            assertThat(claude.toolNames()).isEqualTo(codex.toolNames());
            assertThat(codex.instruction()).isEmpty();
            assertThat(claude.instruction()).isEmpty();
            assertThat(call(broker, codex.token(), "assistant_context", "{\"kind\":\"overview\"}").at("/result/content/0/text").asText()).isEqualTo("today");
            assertThat(call(broker, claude.token(), "assistant_act", "{\"op\":\"reply_to_task\"}").at("/result/content/0/text").asText()).isEqualTo("done");
            assertThat(call(broker, codex.token(), "assistant_show", "{\"op\":\"list\"}").at("/result/content/0/text").asText()).isEqualTo("shown");
            for (String token : ImmutableList.of(codex.token(), claude.token())) {
                assertThat(call(broker, token, "bytequay_list_tools", "{}").at("/error/message").asText()).isEqualTo("Unknown tool");
                assertThat(call(broker, token, "bytequay_get_tool", "{\"name\":\"assistant_act\"}").at("/error/message").asText()).isEqualTo("Unknown tool");
                assertThat(call(broker, token, "bytequay_call_tool", "{\"name\":\"assistant_act\",\"arguments\":{}}").at("/error/message").asText()).isEqualTo("Unknown tool");
            }
            JsonNode refused = mapper.readTree(call(broker, claude.token(), "permission_prompt", "{\"tool_name\":\"Bash\",\"input\":{\"command\":\"ls\"}}")
                    .at("/result/content/0/text").asText());
            assertThat(refused.path("behavior").asText()).isEqualTo("deny");
            assertThat(refused.path("message").asText()).isEqualTo("This session has nothing to approve");
            JsonNode settings = mapper.readTree(Files.readString(claude.settingsFile().orElseThrow()));
            assertThat(settings.at("/permissions/deny").toString()).contains("\"Agent\"", "\"Task\"", "\"Skill\"", "\"Workflow\"", "\"SendMessage\"", "\"WebSearch\"");
            assertThat(settings.at("/sandbox/filesystem/denyWrite").toString()).contains(day.toRealPath().toString());
            assertThat(settings.has("hooks")).isFalse();
        }
        assertThat(acted).containsExactly("{\"op\":\"reply_to_task\"}");
        // The assistant's tools go only to a tools-only session, which has no children to hand a root-only tool to, and it holds no other tool.
        assertThatThrownBy(() -> broker.registerTools("agenda-run", directory, AgentPermissionPolicy.AUTO, approvals, redactor,
                Optional.empty(), SessionAccess.READ_ONLY, Optional.empty(), ImmutableList.of(read))).hasMessageContaining("Only context reads");
        assertThatThrownBy(() -> broker.registerTools("assistant-run", day, AgentPermissionPolicy.AUTO, approvals, redactor,
                Optional.empty(), SessionAccess.TOOLS_ONLY, Optional.empty(), ImmutableList.of(context("context")))).hasMessageContaining("Only assistant tools");
        assertThatThrownBy(() -> broker.registerTools("assistant-run", day, AgentPermissionPolicy.AUTO, approvals, redactor,
                Optional.empty(), SessionAccess.FULL, Optional.empty(), ImmutableList.of(read, act))).hasMessageContaining("read-only or tools-only");
    }

    private static <B> BridgeResolver<B> unresolved()
    {
        return task -> { throw new AssertionError("A tools-only session must not resolve task capabilities"); };
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
