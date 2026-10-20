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

import com.bytequay.agent.cli.process.BuildCacheRoots;
import com.bytequay.agent.cli.process.SecretRedactor;
import com.bytequay.agent.spi.AgentApprovalHandler;
import com.bytequay.agent.spi.AgentCommandPolicy;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentPermissionScope;
import com.bytequay.agent.spi.CodeQueryBridge;
import com.bytequay.agent.spi.DevelopmentResultBridge;
import com.bytequay.agent.spi.IssueContextBridge;
import com.bytequay.agent.spi.LocalReviewBridge;
import com.bytequay.agent.spi.MemoryBridge;
import com.bytequay.agent.spi.ProviderApprovalDecision;
import com.bytequay.agent.spi.ProviderApprovalRequest;
import com.bytequay.agent.spi.PullRequestCiBridge;
import com.bytequay.agent.spi.RelatedWorkspaceBridge;
import com.bytequay.agent.spi.ReviewBridge;
import com.bytequay.agent.spi.ReviewTool;
import com.bytequay.agent.spi.SessionAccess;
import com.bytequay.agent.spi.WorkItemBridge;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

import java.io.OutputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static java.nio.charset.StandardCharsets.UTF_8;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestPermissionBroker
{
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    private Path temporaryDirectory;

    @Test
    void freshNativeAuthorityCannotBeUsedThroughHttpAndIsRevokedOnRelease()
            throws Exception
    {
        AtomicInteger writes = new AtomicInteger();
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/mcp"), objectMapper, (taskId, query) -> "")
                .relatedWork(taskId -> Optional.of(new RelatedWorkspaceBridge(arguments -> {
                    writes.incrementAndGet();
                    return "linked";
                }))).build();
        McpRegistration current = McpTestSupport.registerCodex(
                broker, "task", temporaryDirectory, AgentPermissionPolicy.AUTO, SecretRedactor.fromEnvironment(ImmutableMap.of())).orElseThrow();
        broker.nativeOnly(current);
        JsonNode call = objectMapper.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"bytequay_call_tool","arguments":{"name":"related_workspace_work","arguments":{}}}}
                """);
        assertThat(broker.handle(current.token(), call).status()).isEqualTo(401);
        assertThat(writes).hasValue(0);
        assertThat(rootToolCall(broker, current.token(), "related_workspace_work", "{}").at("/result/content/0/text").asText()).isEqualTo("linked");
        assertThat(writes).hasValue(1);
        current.close();
        assertThat(broker.handle(current.token(), call).status()).isEqualTo(401);
        assertThat(rootToolCall(broker, current.token(), "related_workspace_work", "{}").has("error")).isTrue();
        assertThat(writes).hasValue(1);
    }

    @Test
    void providersExposeAndApproveTheSameTaskTools()
            throws Exception
    {
        ReviewBridge review = new ReviewBridge(ImmutableList.of(new ReviewTool(
                "review_extra_evidence", "Read more evidence", ImmutableMap.of("type", "object"), arguments -> "evidence")), "Review this PR");
        LocalReviewBridge local = new LocalReviewBridge((commentId, contextOffset) -> "[]", reply -> "{}", resolution -> "{}", LocalReviewBridge.INSTRUCTION);
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"), objectMapper, (taskId, query) -> "memory")
                .reviews(taskId -> taskId.equals("review") ? Optional.of(review) : Optional.empty())
                .developments(taskId -> taskId.equals("development")
                        ? Optional.of(new DevelopmentResultBridge("turn-1", DevelopmentResultBridge.INSTRUCTION, false, Optional.empty())) : Optional.empty())
                .localReviews(taskId -> taskId.equals("development") ? Optional.of(local) : Optional.empty())
                .pullRequestChecks(taskId -> taskId.equals("development")
                        ? Optional.of(new PullRequestCiBridge(jobId -> "checks", (logId, offset) -> "raw",
                                PullRequestCiBridge.INSTRUCTION, Optional.empty(), Optional.empty())) : Optional.empty()).build();
        for (String taskId : ImmutableList.of("plain", "review", "development", "graph", "ci")) {
            AgentPermissionPolicy policy = taskId.equals("ci") ? AgentPermissionPolicy.CI_AUTOFIX : AgentPermissionPolicy.AUTO;
            Optional<CodeQueryBridge> graph = taskId.equals("graph") ? Optional.of(new CodeQueryBridge(query -> "graph", "Use the graph")) : Optional.empty();
            try (McpRegistration registration = broker.register(taskId, temporaryDirectory, temporaryDirectory, temporaryDirectory, policy, SessionAccess.FULL,
                    (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()), SecretRedactor.fromEnvironment(ImmutableMap.of()),
                    graph, ImmutableMap.of(), Optional.empty(), ImmutableList.of())) {
                JsonNode listed = listTools(broker, registration.token());
                assertThat(registration.toolNames()).as(taskId)
                        .containsExactlyElementsOf(listed.at("/result/tools").findValues("name").stream().filter(JsonNode::isTextual).map(JsonNode::asText).toList().stream()
                                .filter(name -> !name.equals("permission_prompt"))
                                .toList())
                        .doesNotContain("permission_prompt");
                if (taskId.equals("development")) {
                    assertThat(registration.toolNames()).contains(DevelopmentResultBridge.PUBLISH_TOOL_NAME);
                }
                if (taskId.equals("review")) {
                    assertThat(registration.toolNames()).containsExactly("review_extra_evidence", "bytequay_list_tools", "bytequay_get_tool", PermissionBroker.CALL_TOOL);
                }
                Optional<McpRegistration> codex = broker.registerTools(
                        taskId,
                        temporaryDirectory,
                        policy,
                        (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()),
                        SecretRedactor.fromEnvironment(ImmutableMap.of()),
                        graph,
                        SessionAccess.FULL,
                        Optional.empty(),
                        ImmutableList.of());
                if (taskId.equals("plain")) {
                    assertThat(codex).isEmpty();
                }
                else {
                    try (McpRegistration tools = codex.orElseThrow()) {
                        JsonNode codexListed = listTools(broker, tools.token());
                        assertThat(codexListed.at("/result/tools").findValues("name").stream().filter(JsonNode::isTextual).map(JsonNode::asText).toList())
                                .as(taskId).containsExactlyElementsOf(registration.toolNames());
                        assertThat(tools.instruction()).isEqualTo(registration.instruction());
                    }
                }
            }
        }
    }

    @Test
    void toolsDigestFollowsTheToolsAndNotTheSession()
    {
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/internal/agent/codex/mcp"), objectMapper, (taskId, query) -> "memory")
                .reviews(taskId -> Optional.of(new ReviewBridge(ImmutableList.of(new ReviewTool(
                        "review_extra_evidence", taskId.equals("changed") ? "Read more of the evidence" : "Read more evidence", ImmutableMap.of(
                                "type",
                                "object"), arguments -> "evidence")),
                        "Review this PR")))
                .build();
        Function<String, String> digest = taskId -> {
            try (McpRegistration tools = McpTestSupport.registerCodex(
                    broker, taskId, temporaryDirectory, AgentPermissionPolicy.AUTO, SecretRedactor.fromEnvironment(ImmutableMap.of())).orElseThrow()) {
                return tools.toolsDigest();
            }
        };
        assertThat(digest.apply("first")).matches("[0-9a-f]{64}").isEqualTo(digest.apply("second"));
        assertThat(digest.apply("changed")).isNotEqualTo(digest.apply("first"));
    }

    @Test
    void issueContextIsTaskBoundReadOnlyAndRevalidatedForBothProvidersWithoutCodeQueries()
            throws Exception
    {
        AtomicInteger reads = new AtomicInteger();
        AtomicReference<Optional<IssueContextBridge>> current = new AtomicReference<>(Optional.of(new IssueContextBridge(
                () -> {
                    reads.incrementAndGet();
                    return "Accepted source known-secret";
                }, IssueContextBridge.INSTRUCTION)));
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"), objectMapper, (taskId, query) -> "memory")
                .issueContexts(taskId -> taskId.equals("task-1") ? current.get() : Optional.empty()).build();
        SecretRedactor redactor = SecretRedactor.fromEnvironment(ImmutableMap.of("OPENAI_API_KEY", "known-secret"));
        AgentApprovalHandler approvals = (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny());
        String codexToken;
        String claudeToken;
        try (McpRegistration codex = registerCodexTask(broker, approvals, redactor);
                McpRegistration claude = registerClaudeTask(broker, approvals, redactor)) {
            codexToken = codex.token();
            claudeToken = claude.token();
            assertThat(codex.instruction()).hasValueSatisfying(value -> assertThat(value).contains(IssueContextBridge.INSTRUCTION));
            assertThat(claude.instruction()).hasValueSatisfying(value -> assertThat(value).contains(IssueContextBridge.INSTRUCTION));
            assertThat(claude.toolNames()).containsExactly(IssueContextBridge.TOOL_NAME, "bytequay_list_tools", "bytequay_get_tool", PermissionBroker.CALL_TOOL);
            assertThat(reads).hasValue(0);
            for (String token : ImmutableList.of(codexToken, claudeToken)) {
                JsonNode listed = listTools(broker, token);
                assertThat(listed.at("/result/tools").findValues("name").stream().filter(JsonNode::isTextual).map(JsonNode::asText).toList())
                        .contains(IssueContextBridge.TOOL_NAME);
                JsonNode definition = listed.at("/result/tools").findParents("name").stream()
                        .filter(tool -> tool.path("name").asText().equals(IssueContextBridge.TOOL_NAME)).findFirst().orElseThrow();
                assertThat(definition.at("/inputSchema/properties").isEmpty()).isTrue();
                assertThat(definition.at("/inputSchema/additionalProperties").booleanValue()).isFalse();
                for (String invalid : ImmutableList.of("[]", "{\"taskId\":\"task-2\"}", "{\"issueId\":42}", "{\"repo\":\"other/repo\"}", "{\"refresh\":true}")) {
                    assertThat(localReviewCall(broker, token, IssueContextBridge.TOOL_NAME, invalid).at("/error/code").intValue()).isEqualTo(-32602);
                }
                for (String missingArguments : ImmutableList.of("", ",\"arguments\":null")) {
                    JsonNode invalid = broker.handle(token, objectMapper.readTree("""
                            {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"read_issue_context"%s}}
                            """.formatted(missingArguments))).body().orElseThrow();
                    assertThat(invalid.path("error").path("code").intValue()).isIn(-32600, -32602);
                }
                JsonNode result = localReviewCall(broker, token, IssueContextBridge.TOOL_NAME, "{}");
                assertThat(result.at("/result/isError").booleanValue()).isFalse();
                assertThat(result.at("/result/content/0/text").textValue()).isEqualTo("Accepted source [REDACTED]");
            }
            assertThat(reads).hasValue(2);
            current.set(Optional.of(new IssueContextBridge(() -> "Updated local observations", IssueContextBridge.INSTRUCTION)));
            assertThat(localReviewCall(broker, codexToken, IssueContextBridge.TOOL_NAME, "{}").at("/result/content/0/text").textValue())
                    .isEqualTo("Updated local observations");
            current.set(Optional.empty());
            for (String token : ImmutableList.of(codexToken, claudeToken)) {
                JsonNode missing = localReviewCall(broker, token, IssueContextBridge.TOOL_NAME, "{}");
                assertThat(missing.at("/result/isError").booleanValue()).isTrue();
                assertThat(missing.at("/result/content/0/text").textValue()).contains("no longer available");
            }
        }
        assertThat(broker.handle(codexToken, objectMapper.readTree("{}")).status()).isEqualTo(401);
        assertThat(broker.handle(claudeToken, objectMapper.readTree("{}")).status()).isEqualTo(401);
        current.set(Optional.of(new IssueContextBridge(() -> "bound", IssueContextBridge.INSTRUCTION)));
        assertThat(broker.registerTools(
                "task-2",
                temporaryDirectory,
                AgentPermissionPolicy.AUTO,
                approvals,
                redactor,
                Optional.empty(),
                SessionAccess.FULL,
                Optional.empty(),
                ImmutableList.of())).isEmpty();
        try (McpRegistration unbound = broker.register("task-2", temporaryDirectory, temporaryDirectory, temporaryDirectory, AgentPermissionPolicy.AUTO,
                SessionAccess.FULL, approvals, redactor, Optional.empty(), ImmutableMap.of(), Optional.empty(), ImmutableList.of())) {
            assertThat(unbound.toolNames()).isEmpty();
            assertThat(localReviewCall(broker, unbound.token(), IssueContextBridge.TOOL_NAME, "{}").at("/error/code").intValue()).isEqualTo(-32602);
        }
        try (McpRegistration helper = broker.register("task-1", temporaryDirectory, temporaryDirectory, temporaryDirectory, AgentPermissionPolicy.AUTO,
                SessionAccess.TEXT_ONLY, approvals, redactor, Optional.empty(), ImmutableMap.of(), Optional.empty(), ImmutableList.of())) {
            assertThat(helper.toolNames()).isEmpty();
            assertThat(helper.instruction()).isEmpty();
            assertThat(localReviewCall(broker, helper.token(), IssueContextBridge.TOOL_NAME, "{}").at("/error/code").intValue()).isEqualTo(-32602);
        }
    }

    @Test
    void unifiedWorkToolsReachBothProvidersAndAreWithheldFromTextOnlyHelpers()
            throws Exception
    {
        AtomicReference<String> called = new AtomicReference<>();
        WorkItemBridge bridge = new WorkItemBridge(raw -> { called.set(raw); return "Retained context known-secret"; });
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"), objectMapper, (taskId, query) -> "memory")
                .workItems(ignored -> Optional.of(bridge)).build();
        SecretRedactor redactor = SecretRedactor.fromEnvironment(ImmutableMap.of("OPENAI_API_KEY", "known-secret"));
        AgentApprovalHandler approvals = (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny());
        try (McpRegistration codex = registerCodexTask(broker, approvals, redactor);
                McpRegistration claude = registerClaudeTask(broker, approvals, redactor)) {
            for (McpRegistration provider : ImmutableList.of(codex, claude)) {
                assertThat(provider.instruction()).hasValueSatisfying(value -> assertThat(value).contains(WorkItemBridge.INSTRUCTION));
                JsonNode listed = listTools(broker, provider.token());
                JsonNode definition = listed.at("/result/tools").findParents("name").stream()
                        .filter(tool -> tool.path("name").asText().equals(WorkItemBridge.TOOL_NAME)).findFirst().orElseThrow();
                assertThat(definition.at("/inputSchema/properties/action/enum").toString())
                        .isEqualTo("[\"LIST\",\"READ\",\"CREATE\",\"MARK_EPIC\",\"ASSIGN_EPIC\",\"LINK_CONTEXT\",\"UNLINK_CONTEXT\"]");
                assertThat(definition.at("/inputSchema/properties/targetId/type").asText()).isEqualTo("string");
                assertThat(definition.at("/inputSchema/properties/source").isMissingNode()).isTrue();
                JsonNode result = localReviewCall(broker, provider.token(), WorkItemBridge.TOOL_NAME, "{\"action\":\"READ\"}");
                assertThat(result.at("/result/content/0/text").asText()).isEqualTo("Retained context [REDACTED]");
                assertThat(called.get()).isEqualTo("{\"action\":\"READ\"}");
                localReviewCall(broker, provider.token(), WorkItemBridge.TOOL_NAME, "{\"action\":\"MARK_EPIC\",\"id\":\"local-item\"}");
                assertThat(called.get()).isEqualTo("{\"action\":\"MARK_EPIC\",\"id\":\"local-item\"}");
                localReviewCall(broker, provider.token(), WorkItemBridge.TOOL_NAME, "{\"action\":\"ASSIGN_EPIC\",\"id\":\"child\",\"epicId\":\"LOCAL:local-item\"}");
                assertThat(called.get()).isEqualTo("{\"action\":\"ASSIGN_EPIC\",\"id\":\"child\",\"epicId\":\"LOCAL:local-item\"}");
                for (String action : ImmutableList.of("LINK_CONTEXT", "UNLINK_CONTEXT")) {
                    String request = "{\"action\":\"" + action + "\",\"id\":\"local-item\",\"targetId\":\"context-item\"}";
                    localReviewCall(broker, provider.token(), WorkItemBridge.TOOL_NAME, request);
                    assertThat(called.get()).isEqualTo(request);
                }
            }
        }
        try (McpRegistration helper = broker.register("task-1", temporaryDirectory, temporaryDirectory, temporaryDirectory, AgentPermissionPolicy.AUTO,
                SessionAccess.TEXT_ONLY, approvals, redactor, Optional.empty(), ImmutableMap.of(), Optional.empty(), ImmutableList.of())) {
            assertThat(helper.toolNames()).doesNotContain(WorkItemBridge.TOOL_NAME);
            assertThat(localReviewCall(broker, helper.token(), WorkItemBridge.TOOL_NAME, "{}").at("/error/code").intValue()).isEqualTo(-32602);
        }
    }

    @Test
    void memoryToolCallsTheTaskBridgeAndReturnsRejectionsAsToolErrors()
            throws Exception
    {
        List<String> calls = new ArrayList<>();
        MemoryBridge bridge = new MemoryBridge(arguments -> {
            calls.add(arguments);
            if (arguments.contains("EDIT")) {
                throw new IllegalArgumentException("action must be SEARCH, READ or REPORT: known-secret");
            }
            if (arguments.contains("FAIL")) {
                throw new IllegalStateException();
            }
            return "{\"memories\":[],\"activityLine\":\"Searched memory for “docs” · 0 memories\"}";
        });
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"), objectMapper, (taskId, query) -> "memory")
                .memories(taskId -> taskId.equals("task-1") ? Optional.of(bridge) : Optional.empty()).build();
        SecretRedactor redactor = SecretRedactor.fromEnvironment(ImmutableMap.of("OPENAI_API_KEY", "known-secret"));
        AgentApprovalHandler approvals = (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny());
        try (McpRegistration claude = registerClaudeTask(broker, approvals, redactor)) {
            assertThat(claude.toolNames()).containsExactly(MemoryBridge.TOOL_NAME, "bytequay_list_tools", "bytequay_get_tool", PermissionBroker.CALL_TOOL);
            assertThat(claude.instruction()).hasValueSatisfying(value -> assertThat(value).contains(MemoryBridge.INSTRUCTION));
            JsonNode listed = listTools(broker, claude.token());
            JsonNode definition = listed.at("/result/tools").findParents("name").stream()
                    .filter(tool -> tool.path("name").asText().equals(MemoryBridge.TOOL_NAME)).findFirst().orElseThrow();
            assertThat(definition.at("/inputSchema/properties/action/enum").toString()).isEqualTo("[\"SEARCH\",\"READ\",\"REPORT\",\"REMEMBER\"]");
            assertThat(definition.at("/inputSchema/properties/kind/enum").toString()).isEqualTo("[\"RULE\",\"RECIPE\"]");

            assertThat(definition.at("/inputSchema/properties/workspaceId/type").asText()).isEqualTo("string");
            assertThat(definition.at("/inputSchema/properties/page/maximum").isMissingNode()).isTrue();

            JsonNode searched = localReviewCall(broker, claude.token(), MemoryBridge.TOOL_NAME, "{\"action\":\"SEARCH\",\"query\":\"docs\",\"workspaceId\":\"iceberg\"}");
            assertThat(searched.at("/result/isError").booleanValue()).isFalse();
            assertThat(searched.at("/result/content/0/text").textValue()).contains("Searched memory for “docs”");
            assertThat(calls).containsExactly("{\"action\":\"SEARCH\",\"query\":\"docs\",\"workspaceId\":\"iceberg\"}");

            JsonNode rejected = localReviewCall(broker, claude.token(), MemoryBridge.TOOL_NAME, "{\"action\":\"EDIT\"}");
            assertThat(rejected.at("/result/isError").booleanValue()).isTrue();
            assertThat(rejected.at("/result/content/0/text").textValue()).isEqualTo("Memory call failed: action must be SEARCH, READ or REPORT: [REDACTED]");
            JsonNode failed = localReviewCall(broker, claude.token(), MemoryBridge.TOOL_NAME, "{\"action\":\"FAIL\"}");
            assertThat(failed.at("/result/isError").booleanValue()).isTrue();
            assertThat(failed.at("/result/content/0/text").textValue()).isEqualTo("Memory call failed: IllegalStateException");
        }
        try (McpRegistration unbound = broker.register("task-2", temporaryDirectory, temporaryDirectory, temporaryDirectory, AgentPermissionPolicy.AUTO,
                SessionAccess.FULL, approvals, redactor, Optional.empty(), ImmutableMap.of(), Optional.empty(), ImmutableList.of())) {
            assertThat(unbound.toolNames()).isEmpty();
            assertThat(localReviewCall(broker, unbound.token(), MemoryBridge.TOOL_NAME, "{\"action\":\"SEARCH\"}").at("/error/code").intValue()).isEqualTo(-32602);
        }
        try (McpRegistration helper = broker.register("task-1", temporaryDirectory, temporaryDirectory, temporaryDirectory, AgentPermissionPolicy.AUTO,
                SessionAccess.TEXT_ONLY, approvals, redactor, Optional.empty(), ImmutableMap.of(), Optional.empty(), ImmutableList.of())) {
            assertThat(helper.toolNames()).isEmpty();
        }
    }

    @Test
    void privateLocalReviewToolsShareTheTaskScopeWithoutChangingItsDevelopmentProfile()
            throws Exception
    {
        List<String> operations = new ArrayList<>();
        AtomicReference<Boolean> available = new AtomicReference<>(true);
        LocalReviewBridge local = new LocalReviewBridge(
                (commentId, contextOffset) -> "[{\"id\":\"" + commentId.orElse("comment-1") + "\",\"contextOffset\":" + contextOffset + "}]",
                reply -> {
                    if (!reply.commentId().equals("comment-1")) {
                        throw new IllegalArgumentException("Comment belongs to another task known-secret");
                    }
                    operations.add(reply.commentId() + "|" + reply.body() + "|" + reply.operationKey());
                    return "reply accepted";
                },
                resolution -> {
                    operations.add(resolution.commentId() + "|" + resolution.resolved() + "|" + resolution.operationKey());
                    return "resolution accepted";
                },
                LocalReviewBridge.INSTRUCTION);
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"), objectMapper, (taskId, query) -> "memory")
                .developments(taskId -> taskId.equals("task-1")
                        ? Optional.of(new DevelopmentResultBridge("turn-1", DevelopmentResultBridge.INSTRUCTION, false, Optional.empty())) : Optional.empty())
                .localReviews(taskId -> taskId.equals("task-1") && available.get() ? Optional.of(local) : Optional.empty()).build();
        SecretRedactor redactor = SecretRedactor.fromEnvironment(ImmutableMap.of("OPENAI_API_KEY", "known-secret"));
        AgentApprovalHandler approvals = (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny());
        String closedToken;
        try (McpRegistration codex = registerCodexTask(broker, approvals, redactor);
                McpRegistration claude = registerClaudeTask(broker, approvals, redactor)) {
            closedToken = codex.token();
            assertThat(codex.review()).isEmpty();
            assertThat(claude.review()).isEmpty();
            assertThat(codex.development()).isPresent();
            assertThat(claude.development()).isPresent();
            assertThat(codex.instruction()).hasValueSatisfying(instruction -> assertThat(instruction).contains(LocalReviewBridge.INSTRUCTION, DevelopmentResultBridge.INSTRUCTION));
            for (String token : ImmutableList.of(codex.token(), claude.token())) {
                JsonNode listed = listTools(broker, token);
                assertThat(listed.at("/result/tools").findValues("name").stream().filter(JsonNode::isTextual).map(JsonNode::asText).toList())
                        .contains(LocalReviewBridge.LIST_TOOL, LocalReviewBridge.REPLY_TOOL, LocalReviewBridge.RESOLVE_TOOL, DevelopmentResultBridge.TOOL_NAME);
                assertThat(localReviewCall(broker, token, LocalReviewBridge.LIST_TOOL, "{}").at("/result/content/0/text").textValue()).contains("comment-1");
                assertThat(localReviewCall(broker, token, LocalReviewBridge.REPLY_TOOL, """
                        {"commentId":"comment-1","body":"Answer known-secret","operationKey":"reply-1"}
                        """).at("/result/isError").booleanValue()).isFalse();
                for (boolean resolved : ImmutableList.of(true, false)) {
                    assertThat(localReviewCall(broker, token, LocalReviewBridge.RESOLVE_TOOL, """
                            {"commentId":"comment-1","resolved":%s,"operationKey":"resolve-%s"}
                            """.formatted(resolved, resolved)).at("/result/isError").booleanValue()).isFalse();
                }
                for (String invalid : ImmutableList.of(
                        "{}", "[]", "{\"commentId\":\"comment-1\",\"body\":\"Answer\",\"operationKey\":null}",
                        "{\"commentId\":\"comment-1\",\"body\":\" \",\"operationKey\":\"reply-2\"}",
                        "{\"commentId\":\"comment-1\",\"body\":\"Answer\",\"operationKey\":\"reply-2\",\"taskId\":\"task-2\"}",
                        "{\"commentId\":\"comment-1\",\"body\":\"" + "a".repeat(LocalReviewBridge.MAXIMUM_BODY_LENGTH + 1) + "\",\"operationKey\":\"reply-2\"}")) {
                    assertThat(localReviewCall(broker, token, LocalReviewBridge.REPLY_TOOL, invalid).at("/error/code").intValue()).isEqualTo(-32602);
                }
                assertThat(localReviewCall(
                        broker,
                        token,
                        LocalReviewBridge.LIST_TOOL,
                        "{\"commentId\":\"comment-1\"}").at("/result/content/0/text").textValue()).contains("comment-1");
                JsonNode page = localReviewCall(broker, token, LocalReviewBridge.LIST_TOOL, "{\"commentId\":\"comment-1\",\"contextOffset\":4000}");
                assertThat(objectMapper.readTree(page.at("/result/content/0/text").textValue()).get(0).path("contextOffset").intValue()).isEqualTo(4000);
                for (String offset : ImmutableList.of("-1", "60001", "2147483648", "0.5", "null", "\"4000\"")) {
                    String arguments = "{\"commentId\":\"comment-1\",\"contextOffset\":" + offset + "}";
                    assertThat(localReviewCall(broker, token, LocalReviewBridge.LIST_TOOL, arguments).at("/error/code").intValue()).isEqualTo(-32602);
                }
                assertThat(localReviewCall(broker, token, LocalReviewBridge.LIST_TOOL, "{\"contextOffset\":0}").at("/error/code").intValue()).isEqualTo(-32602);
                for (String invalid : ImmutableList.of(
                        "{\"taskId\":\"task-2\"}",
                        "{\"commentId\":null}",
                        "{\"commentId\":\" \"}",
                        "{\"commentId\":\"comment-1\",\"taskId\":\"task-2\"}")) {
                    assertThat(localReviewCall(broker, token, LocalReviewBridge.LIST_TOOL, invalid).at("/error/code").intValue()).isEqualTo(-32602);
                }
                assertThat(localReviewCall(broker, token, LocalReviewBridge.RESOLVE_TOOL, """
                        {"commentId":"comment-1","resolved":"true","operationKey":"wrong-type"}
                        """).at("/error/code").intValue()).isEqualTo(-32602);
                JsonNode foreign = localReviewCall(broker, token, LocalReviewBridge.REPLY_TOOL, """
                        {"commentId":"foreign-comment","body":"Answer","operationKey":"foreign"}
                        """);
                assertThat(foreign.at("/result/isError").booleanValue()).isTrue();
                assertThat(foreign.at("/result/content/0/text").textValue()).contains("another task", "[REDACTED]").doesNotContain("known-secret");
            }
            assertThat(operations).containsExactly(
                    "comment-1|Answer [REDACTED]|reply-1", "comment-1|true|resolve-true", "comment-1|false|resolve-false",
                    "comment-1|Answer [REDACTED]|reply-1", "comment-1|true|resolve-true", "comment-1|false|resolve-false");
            available.set(false);
            assertThat(localReviewCall(broker, codex.token(), LocalReviewBridge.LIST_TOOL, "{}").at("/error/code").intValue()).isEqualTo(-32602);
        }
        assertThat(broker.handle(closedToken, objectMapper.readTree("{}")).status()).isEqualTo(401);
        assertThat(McpTestSupport.registerCodex(broker, "task-2", temporaryDirectory, AgentPermissionPolicy.AUTO, redactor)).isEmpty();
        available.set(true);
        try (McpRegistration helper = broker.register("task-1", temporaryDirectory, temporaryDirectory, temporaryDirectory, AgentPermissionPolicy.AUTO,
                SessionAccess.TEXT_ONLY, (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()), redactor, Optional.empty(),
                ImmutableMap.of(), Optional.empty(), ImmutableList.of())) {
            assertThat(helper.instruction()).isEmpty();
            assertThat(helper.toolNames()).isEmpty();
        }
        PermissionBroker localOnly = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"), objectMapper, (taskId, query) -> "memory")
                .localReviews(taskId -> Optional.of(local)).build();
        try (McpRegistration registration = localOnly.registerTools(
                "task-1",
                temporaryDirectory,
                AgentPermissionPolicy.AUTO,
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()),
                redactor,
                Optional.empty(),
                SessionAccess.FULL,
                Optional.empty(),
                ImmutableList.of()).orElseThrow()) {
            assertThat(registration.instruction()).hasValueSatisfying(value -> assertThat(value).contains(LocalReviewBridge.INSTRUCTION));
        }
    }

    private JsonNode localReviewCall(PermissionBroker broker, String token, String tool, String arguments)
            throws Exception
    {
        return broker.handle(token, objectMapper.readTree("""
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"%s","arguments":%s}}
                """.formatted(tool, arguments))).body().orElseThrow();
    }

    @Test
    void branchNamingUsesTheSameTurnBoundToolOnBothProviders()
            throws Exception
    {
        AtomicReference<String> proposed = new AtomicReference<>();
        AtomicReference<String> activeTurn = new AtomicReference<>("turn-1");
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"), objectMapper, (taskId, query) -> "memory")
                .developments(taskId -> Optional.of(new DevelopmentResultBridge(activeTurn.get(), DevelopmentResultBridge.BRANCH_INSTRUCTION, false,
                        Optional.of(branch -> {
                            proposed.set(branch);
                            return "Proposed branch: user/bytequay/" + branch + ". Keep using the current branch during this turn.";
                        })))).build();
        SecretRedactor redactor = SecretRedactor.fromEnvironment(ImmutableMap.of());
        AgentApprovalHandler approvals = (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny());
        try (McpRegistration codex = registerCodexTask(broker, approvals, redactor);
                McpRegistration claude = registerClaudeTask(broker, approvals, redactor)) {
            for (String token : ImmutableList.of(codex.token(), claude.token())) {
                assertThat(localReviewCall(broker, token, DevelopmentResultBridge.RENAME_BRANCH_TOOL_NAME,
                        "{\"branch\":\"convert-parquet-time-millis-to-micro\"}").at("/result/content/0/text").asText())
                        .isEqualTo("Proposed branch: user/bytequay/convert-parquet-time-millis-to-micro. Keep using the current branch during this turn.");
                assertThat(proposed.get()).isEqualTo("convert-parquet-time-millis-to-micro");
                for (String invalid : ImmutableList.of("{}", "{\"branch\":\"user/bytequay/fix-bug\"}", "{\"branch\":\"../main\"}",
                        "{\"branch\":\"fix-bug\",\"taskId\":\"other\"}")) {
                    assertThat(localReviewCall(broker, token, DevelopmentResultBridge.RENAME_BRANCH_TOOL_NAME, invalid).at("/error/code").asInt()).isEqualTo(-32602);
                }
            }
            for (McpRegistration registration : ImmutableList.of(codex, claude)) {
                assertThat(registration.instruction()).hasValueSatisfying(instruction -> assertThat(instruction)
                        .contains("provider process is verified stopped", "candidate is not the active branch", "actual diff", "instead of git branch -m")
                        .doesNotContain("Use the returned full branch name"));
            }
            activeTurn.set("turn-2");
            proposed.set(null);
            assertThat(localReviewCall(broker, codex.token(), DevelopmentResultBridge.RENAME_BRANCH_TOOL_NAME,
                    "{\"branch\":\"fix-another-bug\"}").at("/error/code").asInt()).isEqualTo(-32602);
            assertThat(proposed.get()).isNull();
        }
    }

    @Test
    void developmentReportsAreAvailableWithoutCodeQueriesAndRejectExpiredOrInvalidClaims()
            throws Exception
    {
        AtomicReference<Optional<DevelopmentResultBridge>> active = new AtomicReference<>(Optional.of(
                new DevelopmentResultBridge("turn-1", DevelopmentResultBridge.INSTRUCTION, false, Optional.empty())));
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"), objectMapper, (taskId, query) -> "memory")
                .developments(taskId -> taskId.equals("task-1") ? active.get() : Optional.empty()).build();
        SecretRedactor redactor = SecretRedactor.fromEnvironment(ImmutableMap.of("OPENAI_API_KEY", "known-secret"));
        AgentApprovalHandler approvals = (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny());
        try (McpRegistration codex = registerCodexTask(broker, approvals, redactor);
                McpRegistration claude = registerClaudeTask(broker, approvals, redactor)) {
            for (String token : ImmutableList.of(codex.token(), claude.token())) {
                JsonNode listed = listTools(broker, token);
                assertThat(listed.at("/result/tools").findValues("name").stream().filter(JsonNode::isTextual).map(JsonNode::asText).toList()).contains(DevelopmentResultBridge.TOOL_NAME);
                for (JsonNode tool : listed.at("/result/tools")) {
                    if (tool.path("name").asText().equals(DevelopmentResultBridge.TOOL_NAME)) {
                        assertThat(tool.at("/inputSchema/properties").size()).isEqualTo(2);
                        assertThat(tool.at("/inputSchema/properties").has("stopAfter")).isFalse();
                    }
                }
                JsonNode accepted = developmentCall(broker, token, """
                        {"readyForDelivery":true,"message":"done known-secret"}
                        """);
                assertThat(accepted.at("/result/isError").booleanValue()).isFalse();
                assertThat(accepted.at("/result/content/0/text").textValue()).contains("turn-1", "[REDACTED]").doesNotContain("known-secret", "stopAfter");
                for (String input : ImmutableList.of(
                        "{}", "{\"readyForDelivery\":\"true\"}", "{\"readyForDelivery\":true,\"threadId\":\"root\"}",
                        "{\"readyForDelivery\":true,\"stopAfter\":\"draft\"}", "{\"readyForDelivery\":true,\"message\":null}",
                        "{\"readyForDelivery\":true,\"message\":\"" + "a".repeat(4001) + "\"}")) {
                    assertThat(developmentCall(broker, token, input).at("/error/code").intValue()).isEqualTo(-32602);
                }
            }
            assertThat(codex.instruction()).hasValueSatisfying(instruction -> assertThat(instruction)
                    .contains("readyForDelivery=false", "automation settings control delivery", "explicit user restriction").doesNotContain("stopAfter"));
            active.set(Optional.of(new DevelopmentResultBridge("turn-2", DevelopmentResultBridge.INSTRUCTION, false, Optional.empty())));
            assertThat(developmentCall(broker, codex.token(), "{\"readyForDelivery\":true}").at("/error/code").intValue()).isEqualTo(-32602);
        }
        try (McpRegistration helper = broker.register("task-1", temporaryDirectory, temporaryDirectory, temporaryDirectory, AgentPermissionPolicy.AUTO,
                SessionAccess.TEXT_ONLY, (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()), redactor, Optional.empty(),
                ImmutableMap.of(), Optional.empty(), ImmutableList.of())) {
            assertThat(helper.development()).isEmpty();
        }
        assertThat(McpTestSupport.registerCodex(broker, "task-2", temporaryDirectory, AgentPermissionPolicy.AUTO, redactor)).isEmpty();
    }

    @Test
    void learningReportsAreExposedOnlyToSummaryRefinementInBothProviders()
            throws Exception
    {
        for (boolean allowed : ImmutableList.of(false, true)) {
            DevelopmentResultBridge bridge = new DevelopmentResultBridge("turn-1", DevelopmentResultBridge.INSTRUCTION, allowed, Optional.empty());
            PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"), objectMapper, (taskId, query) -> "memory")
                    .developments(taskId -> Optional.of(bridge)).build();
            SecretRedactor redactor = SecretRedactor.fromEnvironment(ImmutableMap.of("OPENAI_API_KEY", "known-secret"));
            AgentApprovalHandler approvals = (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny());
            try (McpRegistration codex = registerCodexTask(broker, approvals, redactor);
                    McpRegistration claude = registerClaudeTask(broker, approvals, redactor)) {
                for (String token : ImmutableList.of(codex.token(), claude.token())) {
                    JsonNode listed = listTools(broker, token);
                    for (JsonNode tool : listed.at("/result/tools")) {
                        if (tool.path("name").asText().equals(DevelopmentResultBridge.TOOL_NAME)) {
                            assertThat(tool.at("/inputSchema/properties").has("learningSummary")).isEqualTo(allowed);
                            if (allowed) {
                                assertThat(tool.at("/inputSchema/properties/learningSummary/properties").toString())
                                        .contains("purpose", "commits").doesNotContain("combinedResult", "evidence", "limitations");
                            }
                        }
                    }
                    JsonNode result = developmentCall(broker, token, """
                            {"readyForDelivery":true,"learningSummary":{"purpose":"Purpose known-secret","commits":[]}}
                            """);
                    if (allowed) {
                        assertThat(result.at("/result/content/0/text").textValue()).contains("Purpose [REDACTED]")
                                .doesNotContain("known-secret", "combinedResult", "evidence", "limitations");
                    }
                    else {
                        assertThat(result.at("/error/code").intValue()).isEqualTo(-32602);
                    }
                }
            }
        }
    }

    @Test
    void draftPullRequestToolPublishesThroughByteQuayAndReportsARefusalAsToolText()
            throws Exception
    {
        List<String> published = new ArrayList<>();
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"), objectMapper, (taskId, query) -> "memory")
                .developments(taskId -> Optional.of(new DevelopmentResultBridge("turn-1", DevelopmentResultBridge.INSTRUCTION, false, Optional.empty())))
                .draftPullRequests((taskId, title, body) -> {
                    if (title.equals("Closed window")) {
                        throw new IllegalStateException("ByteQuay is not requesting a draft pull request for this task right now");
                    }
                    published.add(taskId + "|" + title + "|" + body);
                    return "Pushed abc; created draft pull request https://github.com/owner/repo/pull/7 known-secret";
                }).build();
        SecretRedactor redactor = SecretRedactor.fromEnvironment(ImmutableMap.of("OPENAI_API_KEY", "known-secret"));
        try (McpRegistration claude = registerClaudeTask(broker,
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()), redactor)) {
            String token = claude.token();
            JsonNode listed = listTools(broker, token);
            assertThat(listed.at("/result/tools").findValues("name").stream().filter(JsonNode::isTextual).map(JsonNode::asText).toList()).contains(DevelopmentResultBridge.PUBLISH_TOOL_NAME);

            JsonNode created = publishCall(broker, token, """
                    {"title":"Add a thing","body":"Why it changed"}
                    """);
            assertThat(created.at("/result/isError").booleanValue()).isFalse();
            assertThat(created.at("/result/content/0/text").textValue())
                    .contains("created draft pull request", "[REDACTED]").doesNotContain("known-secret");
            assertThat(published).containsExactly("task-1|Add a thing|Why it changed");

            // A refusal must reach the agent as readable text, never as a dropped connection.
            JsonNode refused = publishCall(broker, token, """
                    {"title":"Closed window","body":"Why it changed"}
                    """);
            assertThat(refused.at("/result/isError").booleanValue()).isTrue();
            assertThat(refused.at("/result/content/0/text").textValue()).contains("Publication was refused", "not requesting a draft pull request");
            assertThat(published).hasSize(1);

            for (String input : ImmutableList.of(
                    "{}", "{\"title\":\"Only a title\"}", "{\"body\":\"Only a body\"}",
                    "{\"title\":\"  \",\"body\":\"blank title\"}", "{\"title\":42,\"body\":\"wrong type\"}",
                    "{\"title\":\"Add a thing\",\"body\":\"x\",\"draft\":false}",
                    "{\"title\":\"" + "a".repeat(257) + "\",\"body\":\"too long\"}")) {
                assertThat(publishCall(broker, token, input).at("/error/code").intValue()).isEqualTo(-32602);
            }
            assertThat(published).hasSize(1);
        }
    }

    private JsonNode publishCall(PermissionBroker broker, String token, String arguments)
            throws Exception
    {
        return broker.handle(token, objectMapper.readTree("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"create_draft_pull_request","arguments":%s}}
                """.formatted(arguments))).body().orElseThrow();
    }

    private JsonNode developmentCall(PermissionBroker broker, String token, String arguments)
            throws Exception
    {
        return broker.handle(token, objectMapper.readTree("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"report_development_result","arguments":%s}}
                """.formatted(arguments))).body().orElseThrow();
    }

    @Test
    void servesTypedLanguageQueriesOnlyWhenBridged()
            throws Exception
    {
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"), objectMapper, (taskId, query) -> "memory").build();
        SecretRedactor redactor = SecretRedactor.fromEnvironment(ImmutableMap.of("ANTHROPIC_API_KEY", "known-secret"));
        McpRegistration bridged = broker.register("task-1", temporaryDirectory, temporaryDirectory, temporaryDirectory, AgentPermissionPolicy.AUTO,
                SessionAccess.FULL, (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()), redactor,
                Optional.of(new CodeQueryBridge(query -> "symbols for " + query.query().orElseThrow() + " known-secret", "use the graph")), ImmutableMap.of(),
                Optional.empty(), ImmutableList.of());
        McpRegistration plain = McpTestSupport.registerClaude(broker, "task-2", temporaryDirectory, AgentPermissionPolicy.AUTO, redactor);

        JsonNode listed = listTools(broker, bridged.token());
        assertThat(listed.at("/result/tools")).hasSize(5);
        assertThat(listed.at("/result/tools/1/name").asText()).isEqualTo("code_query");
        assertThat(listed.at("/result/tools/1/inputSchema/required/0").asText()).isEqualTo("operation");

        JsonNode called = broker.handle(bridged.token(), objectMapper.readTree("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"code_query","arguments":{"operation":"SYMBOLS","path":"src/main.go","limit":20,"query":"cleanup"}}}
                """)).body().orElseThrow();
        assertThat(called.at("/result/content/0/text").asText()).isEqualTo("symbols for cleanup [REDACTED]");

        for (String arguments : ImmutableList.of("{}", "{\"query\":\"  \"}", "{\"query\":\"x\",\"path\":\"/\"}", "{\"query\":5}")) {
            JsonNode rejected = broker.handle(bridged.token(), objectMapper.readTree(
                    "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"code_query\",\"arguments\":" + arguments + "}}")).body().orElseThrow();
            assertThat(rejected.at("/error/code").asInt()).as(arguments).isEqualTo(-32602);
        }

        for (String arguments : ImmutableList.of(
                "{\"operation\":\"executeCommand\",\"path\":\"src/main.go\",\"limit\":20}",
                "{\"operation\":\"DOCUMENT_SYMBOLS\",\"path\":\"../private.go\",\"limit\":20}",
                "{\"operation\":\"DOCUMENT_SYMBOLS\",\"path\":\".git/config\",\"limit\":20}",
                "{\"operation\":\"REFERENCES\",\"path\":\"src/main.go\",\"line\":1.5,\"column\":1,\"limit\":20}",
                "{\"operation\":\"DOCUMENT_SYMBOLS\",\"path\":\"src/main.go\",\"limit\":201}",
                "{\"operation\":\"DOCUMENT_SYMBOLS\",\"path\":\"src/main.go\",\"limit\":20,\"taskId\":\"other\"}")) {
            JsonNode rejected = broker.handle(bridged.token(), objectMapper.readTree(
                    "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"code_query\",\"arguments\":" + arguments + "}}")).body().orElseThrow();
            assertThat(rejected.at("/error/code").asInt()).as(arguments).isEqualTo(-32602);
        }

        JsonNode unbridged = broker.handle(plain.token(), objectMapper.readTree("""
                {"jsonrpc":"2.0","id":4,"method":"tools/list","params":{}}
                """)).body().orElseThrow();
        assertThat(unbridged.at("/result/tools")).hasSize(1);
        JsonNode denied = broker.handle(plain.token(), objectMapper.readTree("""
                {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"code_query","arguments":{"query":"x"}}}
                """)).body().orElseThrow();
        assertThat(denied.at("/error/code").asInt()).isEqualTo(-32602);
        bridged.close();
        plain.close();
    }

    @Test
    void codeQueriesOnlyRegistrationsHideThePermissionTools()
            throws Exception
    {
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"), objectMapper, (taskId, query) -> "memory").build();
        McpRegistration registration = broker.registerTools("task-3", temporaryDirectory, AgentPermissionPolicy.AUTO, (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()), SecretRedactor.fromEnvironment(ImmutableMap.of()), Optional.of(new CodeQueryBridge(
                query -> "symbols for " + query.query().orElseThrow(),
                "use the graph")), SessionAccess.FULL, Optional.empty(), ImmutableList.of()).orElseThrow();
        assertThat(registration.endpoint()).isEqualTo(URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"));

        JsonNode listed = listTools(broker, registration.token());
        assertThat(listed.at("/result/tools")).hasSize(4);
        assertThat(listed.at("/result/tools/0/name").asText()).isEqualTo("code_query");
        JsonNode called = broker.handle(registration.token(), objectMapper.readTree("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"code_query","arguments":{"operation":"SYMBOLS","path":"src/main.go","limit":20,"query":"routes"}}}
                """)).body().orElseThrow();
        assertThat(called.at("/result/content/0/text").asText()).isEqualTo("symbols for routes");
        JsonNode hidden = broker.handle(registration.token(), objectMapper.readTree("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"permission_prompt","arguments":{"tool_name":"Bash","input":{}}}}
                """)).body().orElseThrow();
        assertThat(hidden.at("/error/code").asInt()).isEqualTo(-32602);
        JsonNode memory = broker.handle(registration.token(), objectMapper.readTree("""
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"read_ci_memory","arguments":{}}}
                """)).body().orElseThrow();
        assertThat(memory.at("/error/code").asInt()).isEqualTo(-32602);

        registration.close();
        assertThat(broker.handle(registration.token(), objectMapper.createObjectNode()).status()).isEqualTo(401);
    }

    @Test
    void ciOnlyRegistrationsExposeTaskScopedMemoryWithoutClaudePermissions()
            throws Exception
    {
        AtomicInteger memoryReads = new AtomicInteger();
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"), objectMapper, (taskId, query) -> {
                    memoryReads.incrementAndGet();
                    return taskId + ": " + query.orElse("recent");
                }).build();
        SecretRedactor redactor = SecretRedactor.fromEnvironment(ImmutableMap.of());
        AgentApprovalHandler approvals = (request, timeout) -> {
            throw new AssertionError("Codex must not invoke Claude permission callbacks");
        };
        try (McpRegistration first = broker.registerTools(
                "task-ci-first",
                temporaryDirectory,
                AgentPermissionPolicy.CI_AUTOFIX,
                approvals,
                redactor,
                Optional.empty(),
                SessionAccess.FULL,
                Optional.empty(),
                ImmutableList.of()).orElseThrow();
                McpRegistration second = broker.registerTools(
                        "task-ci-second",
                        temporaryDirectory,
                        AgentPermissionPolicy.CI_AUTOFIX,
                        approvals,
                        redactor,
                        Optional.empty(),
                        SessionAccess.FULL,
                        Optional.empty(),
                        ImmutableList.of()).orElseThrow()) {
            JsonNode listRequest = objectMapper.readTree("""
                    {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                    """);
            assertThat(broker.handle(
                    first.token(),
                    listRequest).body().orElseThrow().at("/result/tools").findValues("name").stream().filter(JsonNode::isTextual).map(JsonNode::asText).toList())
                    .containsExactly("read_ci_memory", "bytequay_list_tools", "bytequay_get_tool", PermissionBroker.CALL_TOOL);
            JsonNode readRequest = objectMapper.readTree("""
                    {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"read_ci_memory","arguments":{}}}
                    """);
            assertThat(broker.handle(first.token(), readRequest).body().orElseThrow().at("/result/content/0/text").asText()).isEqualTo("task-ci-first: recent");
            assertThat(broker.handle(second.token(), readRequest).body().orElseThrow().at("/result/content/0/text").asText()).isEqualTo("task-ci-second: recent");
            JsonNode crossTask = objectMapper.readTree("""
                    {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
                    "name":"read_ci_memory","arguments":{"taskId":"task-ci-second"}}}
                    """);
            assertThat(broker.handle(first.token(), crossTask).body().orElseThrow().at("/error/code").asInt()).isEqualTo(-32602);
            JsonNode permissionRequest = objectMapper.readTree("""
                    {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{
                    "name":"permission_prompt","arguments":{"tool_name":"Bash","input":{"command":"git push"}}}}
                    """);
            assertThat(broker.handle(first.token(), permissionRequest).body().orElseThrow().at("/error/code").asInt()).isEqualTo(-32602);
            assertThat(broker.handle("wrong-token", readRequest).status()).isEqualTo(401);
            first.close();
            assertThat(broker.handle(first.token(), readRequest).status()).isEqualTo(401);
            assertThat(broker.handle(second.token(), readRequest).body().orElseThrow().at("/result/content/0/text").asText()).isEqualTo("task-ci-second: recent");
            assertThat(memoryReads.get()).isEqualTo(3);
        }
    }

    @Test
    void reviewRegistrationsDoNotExposeCiMemory()
            throws Exception
    {
        ReviewBridge review = new ReviewBridge(ImmutableList.of(new ReviewTool(
                "review_context", "Read review context", ImmutableMap.of("type", "object"), arguments -> "review context")), "Review this pull request");
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"), objectMapper, (taskId, query) -> {
                    throw new AssertionError("Review tasks must not read CI memory");
                })
                .reviews(taskId -> Optional.of(review)).build();
        try (McpRegistration registration = McpTestSupport.registerCodex(
                broker, "task-review", temporaryDirectory, AgentPermissionPolicy.AUTO, SecretRedactor.fromEnvironment(ImmutableMap.of())).orElseThrow()) {
            JsonNode listed = listTools(broker, registration.token());
            assertThat(listed.at("/result/tools").findValues("name").stream().filter(JsonNode::isTextual).map(JsonNode::asText).toList()).containsExactly(
                    "review_context",
                    "bytequay_list_tools",
                    "bytequay_get_tool",
                    PermissionBroker.CALL_TOOL);
            assertThat(registration.toolNames()).containsExactlyElementsOf(listed.at("/result/tools").findValues("name").stream().filter(JsonNode::isTextual).map(JsonNode::asText).toList());
            JsonNode denied = broker.handle(registration.token(), objectMapper.readTree("""
                    {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"read_ci_memory","arguments":{}}}
                    """)).body().orElseThrow();
            assertThat(denied.at("/error/code").asInt()).isEqualTo(-32602);
        }
    }

    @Test
    void codexReviewChildTokenCanOnlyCallReadsWhileRootDynamicToolCanSave()
            throws Exception
    {
        AtomicInteger reads = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        ReviewBridge review = new ReviewBridge(ImmutableList.of(
                new ReviewTool("review_get_context", "Read review context", ImmutableMap.of("type", "object"), arguments -> {
                    reads.incrementAndGet();
                    return "context";
                }, true),
                new ReviewTool("review_finish", "Finish the review", ImmutableMap.of("type", "object"), arguments -> {
                    writes.incrementAndGet();
                    return "saved known-secret";
                })), "Review only");
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/mcp"), objectMapper, (taskId, query) -> "")
                .reviews(taskId -> Optional.of(review)).build();
        String token;
        try (McpRegistration registration = McpTestSupport.registerCodex(
                broker, "task-review", temporaryDirectory, AgentPermissionPolicy.AUTO,
                SecretRedactor.fromEnvironment(ImmutableMap.of("OPENAI_API_KEY", "known-secret"))).orElseThrow()) {
            token = registration.token();
            PermissionBroker.CodexRootTools guarded = broker.guardCodexRootTools(registration);
            assertThat(guarded.mcpNames()).containsExactly("review_get_context", "bytequay_list_tools", "bytequay_get_tool");
            assertThat(guarded.rootTools()).singleElement().satisfies(tool ->
                    assertThat(tool).containsEntry("type", "function")
                            .containsEntry("name", PermissionBroker.CALL_TOOL));
            JsonNode listed = listTools(broker, registration.token());
            assertThat(listed.at("/result/tools").findValues("name").stream().filter(JsonNode::isTextual).map(JsonNode::asText).toList()).containsExactly(
                    "review_get_context",
                    "bytequay_list_tools",
                    "bytequay_get_tool");
            JsonNode read = localReviewCall(broker, token, "review_get_context", "{}");
            assertThat(read.at("/result/content/0/text").asText()).isEqualTo("context");
            JsonNode blocked = broker.handle(registration.token(), objectMapper.readTree("""
                    {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"review_finish","arguments":{}}}
                    """)).body().orElseThrow();
            assertThat(blocked.at("/error/code").asInt()).isEqualTo(-32602);
            assertThat(writes).hasValue(0);
            assertThat(broker.callCodexRootTool(token, "review_finish", objectMapper.readTree("[]"))
                    .body().orElseThrow().at("/error/code").asInt()).isEqualTo(-32602);
            assertThat(broker.callCodexRootTool(token, "unknown", objectMapper.createObjectNode())
                    .body().orElseThrow().at("/error/code").asInt()).isEqualTo(-32602);
            assertThat(broker.callCodexRootTool(token, "review_get_context", objectMapper.createObjectNode())
                    .body().orElseThrow().at("/error/code").asInt()).isEqualTo(-32602);
            assertThat(writes).hasValue(0);
            JsonNode saved = rootToolCall(broker, token, "review_finish", "{}");
            assertThat(saved.at("/result/content/0/text").asText()).isEqualTo("saved [REDACTED]");
            assertThat(reads).hasValue(1);
            assertThat(writes).hasValue(1);
        }
        assertThat(broker.handle(token, objectMapper.readTree("{}")).status()).isEqualTo(401);
        assertThat(broker.callCodexRootTool(token, "review_finish", objectMapper.createObjectNode())
                .body().orElseThrow().at("/error/code").asInt()).isEqualTo(-32602);
        assertThat(writes).hasValue(1);
    }

    @Test
    void codexReviewChildrenReadMemoryWhileOnlyRootDynamicCallsCanChangeIt()
            throws Exception
    {
        List<String> calls = new ArrayList<>();
        MemoryBridge memory = new MemoryBridge(arguments -> {
            calls.add(arguments);
            return "memory result";
        });
        ReviewBridge review = new ReviewBridge(ImmutableList.of(
                new ReviewTool("review_get_context", "Read context", ImmutableMap.of("type", "object"), arguments -> "context", true)), "Review");
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/mcp"), objectMapper, (taskId, query) -> "")
                .reviews(taskId -> Optional.of(review))
                .memories(taskId -> Optional.of(memory)).build();
        try (McpRegistration registration = McpTestSupport.registerCodex(
                broker, "task-review", temporaryDirectory, AgentPermissionPolicy.AUTO, SecretRedactor.fromEnvironment(ImmutableMap.of())).orElseThrow()) {
            PermissionBroker.CodexRootTools guarded = broker.guardCodexRootTools(registration);
            assertThat(guarded.mcpNames()).containsExactly("review_get_context", MemoryBridge.TOOL_NAME, "bytequay_list_tools", "bytequay_get_tool");
            JsonNode listed = listTools(broker, registration.token());
            assertThat(listed.at("/result/tools/1/inputSchema/properties/action/enum")).isEqualTo(objectMapper.valueToTree(ImmutableList.of("SEARCH", "READ")));
            assertThat(objectMapper.readTree(localReviewCall(broker, registration.token(), "bytequay_get_tool",
                    "{\"name\":\"repository_memory\"}").at("/result/content/0/text").asText()).at("/inputSchema/properties/action/enum"))
                    .isEqualTo(objectMapper.valueToTree(ImmutableList.of("SEARCH", "READ", "REPORT", "REMEMBER")));
            for (String action : ImmutableList.of("SEARCH", "READ")) {
                assertThat(localReviewCall(broker, registration.token(), MemoryBridge.TOOL_NAME, "{\"action\":\"" + action + "\"}")
                        .at("/result/content/0/text").asText()).isEqualTo("memory result");
            }
            for (String arguments : ImmutableList.of("{}", "{\"action\":null}", "{\"action\":{}}", "{\"action\":\"REPORT\"}", "{\"action\":\"REMEMBER\"}")) {
                assertThat(localReviewCall(broker, registration.token(), MemoryBridge.TOOL_NAME, arguments).at("/error/code").asInt()).isEqualTo(-32602);
            }
            assertThat(calls).containsExactly("{\"action\":\"SEARCH\"}", "{\"action\":\"READ\"}");
            for (String action : ImmutableList.of("REPORT", "REMEMBER")) {
                assertThat(rootToolCall(
                        broker,
                        registration.token(),
                        MemoryBridge.TOOL_NAME,
                        "{\"action\":\"" + action + "\"}").at("/result/content/0/text").asText()).isEqualTo("memory result");
            }
            assertThat(calls).hasSize(4);
        }
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void relatedWorkIsRootBoundOnBothProvidersWithoutRestrictingOrdinaryDevelopmentTools()
            throws Exception
    {
        AtomicInteger starts = new AtomicInteger();
        var bridge = new RelatedWorkspaceBridge(arguments -> {
            starts.incrementAndGet();
            return "linked known-secret";
        });
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/mcp"), objectMapper, (taskId, query) -> "memory")
                .relatedWork(taskId -> Optional.of(bridge)).build();
        var redactor = SecretRedactor.fromEnvironment(ImmutableMap.of("OPENAI_API_KEY", "known-secret"));
        try (var registration = McpTestSupport.registerCodex(broker, "task-related", temporaryDirectory, AgentPermissionPolicy.AUTO, redactor).orElseThrow()) {
            var guard = broker.guardCodexRootTools(registration);
            assertThat(guard.mcpNames()).doesNotContain(RelatedWorkspaceBridge.TOOL_NAME);
            assertThat(localReviewCall(broker, registration.token(), RelatedWorkspaceBridge.TOOL_NAME, "{}").at("/error/code").asInt()).isEqualTo(-32602);
            assertThat(starts).hasValue(0);
            assertThat(rootToolCall(broker, registration.token(), RelatedWorkspaceBridge.TOOL_NAME, "{}").at("/result/content/0/text").asText()).isEqualTo("linked [REDACTED]");
            assertThat(starts).hasValue(1);
        }
        Path runtime = Files.createDirectories(temporaryDirectory.resolve("related runtime"));
        try (var registration = broker.register("task-related", temporaryDirectory, runtime, runtime.resolve("provider"), AgentPermissionPolicy.AUTO,
                SessionAccess.FULL, (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()), redactor, Optional.empty(),
                ImmutableMap.of(), Optional.empty(), ImmutableList.of())) {
            String hook = objectMapper.readTree(Files.readString(registration.settingsFile().orElseThrow())).at("/hooks/PreToolUse/0/hooks/0/command").asText();
            assertThat(gate(hook, "{\"tool_name\":\"mcp__bytequay__related_workspace_work\",\"tool_input\":{}}")).isZero();
            assertThat(gate(hook, "{\"agent_id\":\"child\",\"tool_name\":\"mcp__bytequay__related_workspace_work\",\"tool_input\":{}}")).isEqualTo(2);
            assertThat(gate(hook, "{\"tool_name\":\"Agent\",\"tool_input\":{\"subagent_type\":\"general-purpose\"}}")).isZero();
            assertThat(gate(hook, "broken JSON")).isEqualTo(2);
            assertThat(localReviewCall(broker, registration.token(), RelatedWorkspaceBridge.TOOL_NAME, "{}").at("/result/content/0/text").asText())
                    .isEqualTo("linked [REDACTED]");
            assertThat(starts).hasValue(2);
        }
    }

    @Test
    void writesOwnerOnlyConfigAndBridgesTypedApprovalRequests()
            throws Exception
    {
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"), objectMapper, (taskId, query) -> "memory").build();
        AtomicReference<ProviderApprovalRequest> approval = new AtomicReference<>();
        AtomicReference<Duration> approvalTimeout = new AtomicReference<>();
        McpRegistration registration = broker.register("task-1",
                temporaryDirectory,
                temporaryDirectory,
                temporaryDirectory,
                AgentPermissionPolicy.AUTO,
                SessionAccess.FULL,
                (request, timeout) -> {
                    approval.set(request);
                    approvalTimeout.set(timeout);
                    return CompletableFuture.completedFuture(ProviderApprovalDecision.allow(Optional.empty()));
                },
                SecretRedactor.fromEnvironment(ImmutableMap.of("ANTHROPIC_API_KEY", "known-secret")),
                Optional.empty(),
                ImmutableMap.of(),
                Optional.empty(),
                ImmutableList.of());

        JsonNode config = objectMapper.readTree(Files.readString(registration.configFile().orElseThrow()));
        String authorization = config.at("/mcpServers/bytequay/headers/Authorization").asText();
        String token = authorization.substring("Bearer ".length());
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(registration.configFile().orElseThrow())))
                .isEqualTo("rw-------");

        McpHttpResponse initialized = broker.handle(token, objectMapper.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"initialize",
                "params":{"protocolVersion":"2025-03-26","capabilities":{},
                "clientInfo":{"name":"claude-code","version":"2.1.233"}}}
                """));
        assertThat(initialized.status()).isEqualTo(200);
        assertThat(initialized.body().orElseThrow().at("/result/protocolVersion").asText())
                .isEqualTo("2025-03-26");

        McpHttpResponse listed = broker.handle(token, objectMapper.readTree("""
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """));
        assertThat(listed.body().orElseThrow().at("/result/tools/0/name").asText())
                .isEqualTo("permission_prompt");
        assertThat(listed.body().orElseThrow().at("/result/tools")).hasSize(1);

        McpHttpResponse called = broker.handle(token, objectMapper.readTree("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
                "name":"permission_prompt","arguments":{"tool_name":"Bash",
                "input":{"command":"echo known-secret"}}}}
                """));
        JsonNode permission = objectMapper.readTree(called.body().orElseThrow().at("/result/content/0/text").asText());
        assertThat(permission.get("behavior").asText()).isEqualTo("allow");
        assertThat(approval.get().kind()).isEqualTo(ProviderApprovalRequest.Kind.COMMAND);
        assertThat(approval.get().detail()).contains("[REDACTED]").doesNotContain("known-secret");
        assertThat(approvalTimeout.get()).isEqualTo(Duration.ofMinutes(5));
        assertThat(config.at("/mcpServers/bytequay/timeout").asLong())
                .isGreaterThan(approvalTimeout.get().toMillis())
                .isLessThanOrEqualTo(approvalTimeout.get().plusSeconds(30).toMillis());

        assertThat(broker.handle("wrong", objectMapper.createObjectNode()).status()).isEqualTo(401);
        registration.close();
        assertThat(registration.configFile().orElseThrow()).doesNotExist();
        assertThat(broker.handle(token, objectMapper.createObjectNode()).status()).isEqualTo(401);
    }

    @Test
    void carriesQuestionAnswersBackOnTheToolInputItAsked()
            throws Exception
    {
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"), objectMapper, (taskId, query) -> "memory").build();
        Map<String, String> answers = ImmutableMap.of("Which issue should I work on?", "Both", "Never asked", "Ignored");
        try (McpRegistration registration = broker.register("task-1", temporaryDirectory, temporaryDirectory, temporaryDirectory, AgentPermissionPolicy.AUTO,
                SessionAccess.FULL, (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.allow(Optional.empty(), answers)),
                SecretRedactor.fromEnvironment(ImmutableMap.of()), Optional.empty(), ImmutableMap.of(), Optional.empty(), ImmutableList.of())) {
            String token = objectMapper.readTree(Files.readString(registration.configFile().orElseThrow()))
                    .at("/mcpServers/bytequay/headers/Authorization").asText().substring("Bearer ".length());
            String questions = "[{\"question\":\"Which issue should I work on?\",\"header\":\"Issue\",\"options\":[{\"label\":\"Both\"}]}]";
            McpHttpResponse called = broker.handle(token, objectMapper.readTree(
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{"
                            + "\"name\":\"permission_prompt\",\"arguments\":{\"tool_name\":\"AskUserQuestion\","
                            + "\"input\":{\"questions\":" + questions + "}}}}"));

            JsonNode permission = objectMapper.readTree(called.body().orElseThrow().at("/result/content/0/text").asText());
            assertThat(permission.get("behavior").asText()).isEqualTo("allow");
            // Claude reads the answer off the input it asked with, keyed by the question itself.
            assertThat(permission.at("/updatedInput/answers/Which issue should I work on?").asText()).isEqualTo("Both");
            // An answer to a question that was never asked would make Claude drop the whole input.
            assertThat(permission.at("/updatedInput/answers").size()).isEqualTo(1);
            assertThat(permission.at("/updatedInput/questions")).isEqualTo(objectMapper.readTree(questions));

            // Every other tool runs on the input it asked with.
            McpHttpResponse command = broker.handle(token, objectMapper.readTree(
                    "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\",\"params\":{"
                            + "\"name\":\"permission_prompt\",\"arguments\":{\"tool_name\":\"Bash\","
                            + "\"input\":{\"command\":\"npm test\"}}}}"));
            assertThat(objectMapper.readTree(command.body().orElseThrow().at("/result/content/0/text").asText()).at("/updatedInput"))
                    .isEqualTo(objectMapper.readTree("{\"command\":\"npm test\"}"));
        }
    }

    @Test
    void enforcesCiPolicyAndInjectsTaskIdentityIntoMemoryReads()
            throws Exception
    {
        AtomicReference<String> readTask = new AtomicReference<>();
        AtomicReference<Optional<String>> readQuery = new AtomicReference<>();
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"), objectMapper, (taskId, query) -> {
                    readTask.set(taskId);
                    readQuery.set(query);
                    return query.filter("missing"::equals).isPresent()
                            ? "CI memory is unavailable"
                            : "bounded memory";
                }).build();
        Path worktree = Files.createDirectories(temporaryDirectory.resolve("worktree"));
        Path provider = temporaryDirectory.resolve("provider");
        AtomicReference<ProviderApprovalRequest> requestedApproval = new AtomicReference<>();
        AtomicInteger approvalCount = new AtomicInteger();
        McpRegistration registration = broker.register("task-ci",
                worktree,
                temporaryDirectory,
                provider,
                AgentPermissionPolicy.CI_AUTOFIX,
                SessionAccess.FULL,
                (request, timeout) -> {
                    requestedApproval.set(request);
                    approvalCount.incrementAndGet();
                    return CompletableFuture.completedFuture(ProviderApprovalDecision.deny());
                },
                SecretRedactor.fromEnvironment(ImmutableMap.of()),
                Optional.empty(),
                ImmutableMap.of(),
                Optional.empty(),
                ImmutableList.of());
        String token = token(registration);

        JsonNode settings = objectMapper.readTree(Files.readString(registration.settingsFile().orElseThrow()));
        assertThat(settings.at("/sandbox/enabled").asBoolean()).isTrue();
        assertThat(settings.at("/sandbox/autoAllowBashIfSandboxed").asBoolean()).isTrue();
        assertThat(settings.at("/sandbox/failIfUnavailable").asBoolean()).isTrue();
        assertThat(settings.at("/sandbox/allowUnsandboxedCommands").asBoolean()).isTrue();
        assertThat(settings.at("/permissions/ask").isMissingNode()).isTrue();
        assertThat(settings.at("/sandbox/network/allowedDomains"))
                .isEqualTo(objectMapper.valueToTree(ImmutableList.of("*")));
        assertThat(settings.at("/sandbox/network/allowLocalBinding").asBoolean()).isTrue();
        assertThat(settings.at("/sandbox/filesystem/denyRead").toString()).contains(".config/gh");
        assertThat(settings.at("/permissions/deny").toString())
                .contains("WebFetch", "Bash(git credential:*)", "Bash(git push:*)", "Read(~/.config/gh/**)");

        McpHttpResponse listed = broker.handle(token, objectMapper.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                """));
        assertThat(listed.body().orElseThrow().at("/result/tools")).hasSize(5);
        assertThat(listed.body().orElseThrow().at("/result/tools/1/name").asText())
                .isEqualTo("read_ci_memory");

        String longQuery = "compile failure ".repeat(20);
        McpHttpResponse read = broker.handle(token, objectMapper.readTree("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                "name":"read_ci_memory","arguments":{"query":"%s"}}}
                """.formatted(longQuery)));
        assertThat(read.body().orElseThrow().at("/result/content/0/text").asText()).isEqualTo("bounded memory");
        assertThat(readTask.get()).isEqualTo("task-ci");
        assertThat(readQuery.get().orElseThrow()).hasSize(200);

        McpHttpResponse unavailable = broker.handle(token, objectMapper.readTree("""
                {"jsonrpc":"2.0","id":20,"method":"tools/call","params":{
                "name":"read_ci_memory","arguments":{"query":"missing"}}}
                """));
        assertThat(unavailable.body().orElseThrow().at("/result/content/0/text").asText())
                .isEqualTo("CI memory is unavailable");

        McpHttpResponse crossTask = broker.handle(token, objectMapper.readTree("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
                "name":"read_ci_memory","arguments":{"taskId":"another-task"}}}
                """));
        assertThat(crossTask.body().orElseThrow().at("/error/code").asInt()).isEqualTo(-32602);

        assertThat(permission(broker, token, "Edit", "file_path", worktree.resolve("src/Main.java").toString()))
                .isEqualTo("allow");
        assertThat(approvalCount).hasValue(0);
        assertThat(permission(broker, token, "Write", "file_path", temporaryDirectory.resolve("outside").toString()))
                .isEqualTo("deny");
        assertThat(requestedApproval.get().kind()).isEqualTo(ProviderApprovalRequest.Kind.FILE_CHANGE);
        Path escaped = Files.createDirectories(temporaryDirectory.resolve("escaped"));
        Files.createSymbolicLink(worktree.resolve("linked"), escaped);
        assertThat(permission(broker, token, "Edit", "file_path", worktree.resolve("linked/Main.java").toString()))
                .isEqualTo("deny");
        assertThat(approvalCount).hasValue(2);
        assertThat(permission(broker, token, "Bash", "command", "git push origin HEAD"))
                .isEqualTo("deny");
        assertThat(requestedApproval.get().kind()).isEqualTo(ProviderApprovalRequest.Kind.COMMAND);
        assertThat(approvalCount).hasValue(3);

        Path settingsFile = registration.settingsFile().orElseThrow();
        registration.close();
        assertThat(registration.configFile().orElseThrow()).doesNotExist();
        assertThat(settingsFile).doesNotExist();
        assertThat(broker.handle(token, objectMapper.createObjectNode()).status()).isEqualTo(401);
    }

    @Test
    void forwardsCiPermissionRequestsToTheApprovalHandler()
            throws Exception
    {
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"), objectMapper, (taskId, query) -> "").build();
        Path worktree = Files.createDirectories(temporaryDirectory.resolve("ci-approval-worktree"));
        AtomicReference<ProviderApprovalRequest> requestedApproval = new AtomicReference<>();
        try (McpRegistration registration = broker.register("task-ci-approval",
                worktree,
                temporaryDirectory,
                temporaryDirectory.resolve("ci-approval-provider"),
                AgentPermissionPolicy.CI_AUTOFIX,
                SessionAccess.FULL,
                (request, timeout) -> {
                    requestedApproval.set(request);
                    return CompletableFuture.completedFuture(ProviderApprovalDecision.allow(Optional.empty()));
                },
                SecretRedactor.fromEnvironment(ImmutableMap.of()),
                Optional.empty(),
                ImmutableMap.of(),
                Optional.empty(),
                ImmutableList.of())) {
            ObjectNode retry = objectMapper.createObjectNode()
                    .put("command", "mvn test")
                    .put("dangerouslyDisableSandbox", true);
            assertThat(permission(broker, token(registration), "Bash", retry))
                    .isEqualTo("allow");
            assertThat(requestedApproval.get().title()).isEqualTo("Allow Claude unsandboxed command");
            assertThat(requestedApproval.get().detail()).contains("mvn test", "\"dangerouslyDisableSandbox\":true", "host file access");
            assertThat(requestedApproval.get().availableDecisions()).containsExactly(ProviderApprovalRequest.Decision.ALLOW_ONCE, ProviderApprovalRequest.Decision.DENY);
        }
    }

    @Test
    void sandboxesCherryPickWithoutExposingCiMemory()
            throws Exception
    {
        PermissionBroker broker = PermissionBroker.builder(
                () -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"), objectMapper, (taskId, query) -> "memory must not reach cherry-pick").build();
        Path worktree = Files.createDirectories(temporaryDirectory.resolve("cherry-pick-worktree"));
        McpRegistration registration = broker.register("task-cherry-pick", worktree, temporaryDirectory, temporaryDirectory.resolve("cherry-pick-provider"),
                AgentPermissionPolicy.CHERRY_PICK, SessionAccess.FULL,
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.allow(Optional.empty())),
                SecretRedactor.fromEnvironment(ImmutableMap.of()), Optional.empty(), ImmutableMap.of(), Optional.empty(), ImmutableList.of());
        String token = token(registration);

        JsonNode settings = objectMapper.readTree(Files.readString(registration.settingsFile().orElseThrow()));
        assertThat(settings.at("/sandbox/enabled").asBoolean()).isTrue();
        assertThat(settings.at("/sandbox/autoAllowBashIfSandboxed").asBoolean()).isFalse();
        assertThat(settings.at("/sandbox/allowUnsandboxedCommands").asBoolean()).isFalse();
        assertThat(settings.at("/permissions/ask").isMissingNode()).isTrue();
        assertThat(settings.at("/sandbox/network/allowedDomains")).isEmpty();
        assertThat(settings.at("/permissions/deny").toString())
                .contains("WebFetch", "Bash(git push:*)", "Bash(gh:*)", "Read(~/.ssh/**)");

        McpHttpResponse listed = broker.handle(token, objectMapper.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                """));
        assertThat(listed.body().orElseThrow().at("/result/tools")).hasSize(1);
        assertThat(listed.body().orElseThrow().at("/result/tools/0/name").asText()).isEqualTo("permission_prompt");

        McpHttpResponse memory = broker.handle(token, objectMapper.readTree("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                "name":"read_ci_memory","arguments":{}}}
                """));
        assertThat(memory.body().orElseThrow().at("/error/code").asInt()).isEqualTo(-32602);

        // A sandboxed policy decides tool calls from a fixed rule, so the approval handler above never runs.
        assertThat(permission(broker, token, "Edit", "file_path", worktree.resolve("core/Main.java").toString()))
                .isEqualTo("allow");
        assertThat(permission(broker, token, "Write", "file_path", temporaryDirectory.resolve("outside.txt").toString()))
                .isEqualTo("deny");
        assertThat(permission(broker, token, "Bash", "command", "git push origin HEAD"))
                .isEqualTo("deny");
        assertThat(permission(broker, token, "Bash", "command", "mvn -q test"))
                .isEqualTo("allow");
        assertThat(permission(broker, token, "Bash", "command", "git add src/Main.java"))
                .isEqualTo("allow");
        assertThat(permission(broker, token, "Bash", "command", "git commit --fixup=" + "a".repeat(40)))
                .isEqualTo("allow");
        assertThat(permission(broker, token, "Bash", "command", "git commit --amend --no-edit"))
                .isEqualTo("allow");
        assertThat(permission(broker, token, "Bash", "command", "git commit -m unexpected"))
                .isEqualTo("deny");
        assertThat(permission(broker, token, "Bash", "command", "git cherry-pick --continue"))
                .isEqualTo("deny");
        ObjectNode unsandboxed = objectMapper.createObjectNode()
                .put("command", "mvn test")
                .put("dangerouslyDisableSandbox", true);
        assertThat(permission(broker, token, "Bash", unsandboxed)).isEqualTo("deny");
    }

    @Test
    void reviewsInteractiveGitHubCommandsWhileKeepingTheSandbox()
            throws Exception
    {
        PermissionBroker broker = PermissionBroker.builder(
                () -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"), objectMapper, (taskId, query) -> "memory must not reach auto").build();
        Path worktree = Files.createDirectories(temporaryDirectory.resolve("auto-worktree"));
        AtomicReference<ProviderApprovalRequest> approval = new AtomicReference<>();
        McpRegistration registration = broker.register("task-auto",
                worktree,
                temporaryDirectory,
                temporaryDirectory.resolve("auto-provider"),
                AgentPermissionPolicy.AUTO,
                SessionAccess.FULL,
                (request, timeout) -> {
                    approval.set(request);
                    return CompletableFuture.completedFuture(ProviderApprovalDecision.deny());
                },
                SecretRedactor.fromEnvironment(ImmutableMap.of()),
                Optional.empty(),
                ImmutableMap.of(),
                Optional.empty(),
                ImmutableList.of());
        String token = token(registration);

        JsonNode settings = objectMapper.readTree(Files.readString(registration.settingsFile().orElseThrow()));
        assertThat(settings.at("/sandbox/enabled").asBoolean()).isTrue();
        assertThat(settings.at("/sandbox/autoAllowBashIfSandboxed").asBoolean()).isFalse();
        assertThat(settings.at("/sandbox/allowUnsandboxedCommands").asBoolean()).isTrue();
        assertThat(settings.at("/permissions/ask").isMissingNode()).isTrue();
        assertThat(settings.at("/sandbox/network/allowedDomains"))
                .isEqualTo(objectMapper.valueToTree(ImmutableList.of("*")));
        assertThat(settings.at("/sandbox/network/allowLocalBinding").asBoolean()).isTrue();
        assertThat(settings.at("/sandbox/network/allowUnixSockets"))
                .isEqualTo(objectMapper.valueToTree(ImmutableList.of("/var/run/docker.sock", "~/.docker/run/docker.sock", "~/.orbstack/run/docker.sock")));
        assertThat(settings.at("/sandbox/filesystem/allowWrite"))
                .isEqualTo(objectMapper.valueToTree(temporaryRoots(ImmutableMap.of())));
        assertThat(settings.at("/permissions/allow").isMissingNode()).isTrue();
        assertThat(settings.at("/permissions/deny")).isEmpty();
        assertThat(settings.at("/sandbox/filesystem/denyRead")).isEmpty();
        assertThat(settings.at("/autoMode/classifyAllShell").asBoolean()).isTrue();
        assertThat(settings.at("/autoMode/hard_deny"))
                .isEqualTo(objectMapper.valueToTree(ImmutableList.<String>builder().add("$defaults").addAll(AgentCommandPolicy.restrictions()).build()));
        assertThat(settings.at("/sandbox/filesystem/denyWrite").toString())
                .contains(temporaryDirectory.toRealPath().toString());

        McpHttpResponse listed = broker.handle(token, objectMapper.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                """));
        assertThat(listed.body().orElseThrow().at("/result/tools")).hasSize(1);

        assertThat(permission(broker, token, "Edit", "file_path", worktree.resolve("src/Main.java").toString()))
                .isEqualTo("allow");
        assertThat(approval.get()).isNull();
        assertThat(permission(broker, token, "FutureTool", "file_path", worktree.resolve("src/Main.java").toString()))
                .isEqualTo("deny");
        assertThat(approval.get().kind()).isEqualTo(ProviderApprovalRequest.Kind.TOOL);
        assertThat(approval.get().sandboxed()).isFalse();
        assertThat(permission(broker, token, "Write", "file_path", temporaryDirectory.resolve("outside.txt").toString()))
                .isEqualTo("deny");
        assertThat(approval.get().kind()).isEqualTo(ProviderApprovalRequest.Kind.FILE_CHANGE);
        assertThat(approval.get().sandboxed()).isFalse();
        String chain = "git status && gh pr view 42 && git push origin HEAD";
        assertThat(permission(broker, token, "Bash", "command", chain)).isEqualTo("deny");
        assertThat(approval.get().detail()).contains(chain);
        assertThat(approval.get().sandboxed()).isTrue();
        ObjectNode retry = objectMapper.createObjectNode()
                .put("command", "mvn test")
                .put("dangerouslyDisableSandbox", true);
        assertThat(permission(broker, token, "Bash", retry)).isEqualTo("deny");
        assertThat(approval.get().title()).isEqualTo("Allow Claude unsandboxed command");
        assertThat(approval.get().sandboxed()).isFalse();
        registration.close();
    }

    @Test
    void sharedReviewerRetainsReportToolsButCannotEditTheCheckout()
            throws Exception
    {
        Path worktree = Files.createDirectory(temporaryDirectory.resolve("shared-worktree"));
        Path runtime = Files.createDirectory(temporaryDirectory.resolve("shared-runtime"));
        ReviewBridge review = new ReviewBridge(ImmutableList.of(new ReviewTool(
                "review_save_finding", "Save a finding", ImmutableMap.of("type", "object"), input -> "saved")), "Review only");
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"), objectMapper, (taskId, query) -> "")
                .reviews(taskId -> Optional.of(review)).build();
        try (McpRegistration registration = broker.register("shared-review", worktree, runtime, runtime.resolve("provider"), AgentPermissionPolicy.AUTO,
                SessionAccess.REVIEW_READ_ONLY, (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()),
                SecretRedactor.fromEnvironment(ImmutableMap.of()), Optional.empty(), ImmutableMap.of(), Optional.empty(), ImmutableList.of())) {
            assertThat(registration.toolNames()).contains("review_save_finding");
            assertThat(registration.review()).contains(review);
            JsonNode settings = objectMapper.readTree(Files.readString(registration.settingsFile().orElseThrow()));
            assertThat(settings.at("/sandbox/filesystem/denyWrite")).isEqualTo(objectMapper.valueToTree(ImmutableList.of(
                    runtime.toRealPath().toString(),
                    worktree.toRealPath().toString())));
            assertThat(settings.at("/sandbox/allowUnsandboxedCommands").asBoolean()).isFalse();
            assertThat(permission(broker, token(registration), "Edit", "file_path", worktree.resolve("source.txt").toString())).isEqualTo("deny");
        }
    }

    @Test
    void grantsClaudeOnlyTheSharedValidatedBuildCaches()
            throws Exception
    {
        Path home = Files.createDirectories(temporaryDirectory.resolve("build-home")).toRealPath();
        Path external = Files.createDirectories(temporaryDirectory.resolve("external"));
        Path linkedHome = Files.createDirectories(temporaryDirectory.resolve("linked-build-home")).toRealPath();
        Files.createSymbolicLink(linkedHome.resolve(".m2"), external);
        var environment = ImmutableMap.of("HOME", home.toString(), "TMPDIR", Files.createDirectory(temporaryDirectory.resolve("host-tmp")).toString());
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"), objectMapper, (taskId, query) -> "").build();
        Path worktree = Files.createDirectories(temporaryDirectory.resolve("build-worktree"));
        Path runtime = Files.createDirectory(temporaryDirectory.resolve("build-runtime"));
        Files.createSymbolicLink(worktree.resolve("linked"), external);
        for (AgentPermissionPolicy policy : ImmutableList.of(AgentPermissionPolicy.AUTO, AgentPermissionPolicy.CI_AUTOFIX)) {
            AtomicInteger approvalCount = new AtomicInteger();
            try (McpRegistration registration = broker.register("build-" + policy,
                    worktree,
                    runtime,
                    runtime.resolve("build-provider-" + policy),
                    policy,
                    SessionAccess.FULL,
                    (request, timeout) -> {
                        approvalCount.incrementAndGet();
                        return CompletableFuture.completedFuture(ProviderApprovalDecision.deny());
                    },
                    SecretRedactor.fromEnvironment(environment),
                    Optional.empty(),
                    environment,
                    Optional.empty(),
                    ImmutableList.of())) {
                JsonNode settings = objectMapper.readTree(Files.readString(registration.settingsFile().orElseThrow()));
                List<String> expected = ImmutableList.<String>builder().add(home.resolve(".m2").toString(), home.resolve(".gradle").toString(),
                        home.resolve(".npm").toString()).addAll(temporaryRoots(environment)).build();
                assertThat(settings.at("/sandbox/filesystem/allowWrite"))
                        .isEqualTo(objectMapper.valueToTree(expected));
                assertThat(expected).doesNotContain(external.toString());
                String token = token(registration);
                assertThat(permission(broker, token, "Edit", "file_path", worktree.resolve("src/Main.java").toString()))
                        .isEqualTo("allow");
                assertThat(permission(broker, token, "Write", "file_path", home.resolve(".m2/repository/test.pom").toString()))
                        .isEqualTo("allow");
                assertThat(permission(broker, token, "NotebookEdit", "notebook_path", home.resolve(".gradle/build.ipynb").toString()))
                        .isEqualTo("allow");
                assertThat(approvalCount).hasValue(0);
                assertThat(permission(broker, token, "Write", "file_path", external.resolve("test.pom").toString()))
                        .isEqualTo("deny");
                assertThat(approvalCount).hasValue(1);
                assertThat(permission(broker, token, "Edit", "file_path", worktree.resolve("linked/test.pom").toString()))
                        .isEqualTo("deny");
                assertThat(approvalCount).hasValue(2);
                assertThat(permission(broker, token, "Bash", "command", "mvn test"))
                        .isEqualTo("deny");
                assertThat(approvalCount).hasValue(3);
            }
        }
        var linkedEnvironment = ImmutableMap.of("HOME", linkedHome.toString());
        try (McpRegistration registration = broker.register("linked-build", worktree, runtime, runtime.resolve("linked-provider"), AgentPermissionPolicy.AUTO,
                SessionAccess.FULL, (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()),
                SecretRedactor.fromEnvironment(linkedEnvironment), Optional.empty(), linkedEnvironment, Optional.empty(), ImmutableList.of())) {
            JsonNode settings = objectMapper.readTree(Files.readString(registration.settingsFile().orElseThrow()));
            List<String> expected = ImmutableList.<String>builder().add(linkedHome.resolve(".gradle").toString(),
                    linkedHome.resolve(".npm").toString()).addAll(temporaryRoots(linkedEnvironment)).build();
            assertThat(settings.at("/sandbox/filesystem/allowWrite"))
                    .isEqualTo(objectMapper.valueToTree(expected));
            assertThat(expected).doesNotContain(linkedHome.resolve(".m2").toString(), external.toString());
            assertThat(permission(broker, token(registration), "Write", "file_path", linkedHome.resolve(".m2/test.pom").toString()))
                    .isEqualTo("deny");
        }
    }

    @Test
    void grantsLinkedGitMetadataOnlyToWritableTaskTurns()
            throws Exception
    {
        Path root = temporaryDirectory.toRealPath();
        Path worktree = Files.createDirectory(root.resolve("linked-worktree"));
        Path common = Files.createDirectories(root.resolve("repository/.git"));
        Path privateGit = Files.createDirectories(common.resolve("worktrees/task"));
        Path objects = Files.createDirectory(common.resolve("objects"));
        Files.writeString(worktree.resolve(".git"), "gitdir: " + privateGit + "\n");
        Files.writeString(privateGit.resolve("gitdir"), worktree.resolve(".git") + "\n");
        Files.writeString(privateGit.resolve("commondir"), "../..\n");
        Files.writeString(privateGit.resolve("HEAD"), "ref: refs/heads/bytequay/task\n");
        Path branch = common.resolve("refs/heads/bytequay/task");
        Path reflog = common.resolve("logs/refs/heads/bytequay/task");
        List<String> expected = ImmutableList.<String>builder().add(
                privateGit.toString(), objects.toString(), branch.toString(), branch + ".lock",
                reflog.toString(), reflog + ".lock").addAll(temporaryRoots(ImmutableMap.of())).build();
        Path runtime = Files.createDirectory(root.resolve("linked-runtime"));
        ReviewBridge review = new ReviewBridge(ImmutableList.of(new ReviewTool(
                "review_context", "Read review context", ImmutableMap.of("type", "object"), input -> "context")), "Review only");
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"), objectMapper, (taskId, query) -> "")
                .reviews(taskId -> taskId.equals("review") ? Optional.of(review) : Optional.empty()).build();
        for (AgentPermissionPolicy policy : AgentPermissionPolicy.values()) {
            String taskId = policy.name().toLowerCase();
            try (McpRegistration registration = broker.register(taskId, worktree, runtime, root.resolve("provider-" + taskId), policy, SessionAccess.FULL,
                    (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()), SecretRedactor.fromEnvironment(ImmutableMap.of()),
                    Optional.empty(), ImmutableMap.of(), Optional.empty(), ImmutableList.of())) {
                JsonNode settings = objectMapper.readTree(Files.readString(registration.settingsFile().orElseThrow()));
                assertThat(settings.at("/sandbox/filesystem/allowWrite"))
                        .isEqualTo(objectMapper.valueToTree(expected));
            }
        }
        for (String taskId : ImmutableList.of("review", "read-only")) {
            SessionAccess access = taskId.equals("read-only") ? SessionAccess.READ_ONLY : SessionAccess.FULL;
            try (McpRegistration registration = broker.register(taskId, worktree, runtime, root.resolve("provider-" + taskId), AgentPermissionPolicy.AUTO,
                    access, (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()),
                    SecretRedactor.fromEnvironment(ImmutableMap.of()), Optional.empty(), ImmutableMap.of(), Optional.empty(), ImmutableList.of())) {
                JsonNode settings = objectMapper.readTree(Files.readString(registration.settingsFile().orElseThrow()));
                // A session that may change nothing writes no cache or temporary file a later build would run.
                assertThat(settings.at("/sandbox/filesystem/allowWrite"))
                        .isEqualTo(objectMapper.valueToTree(access == SessionAccess.FULL ? temporaryRoots(ImmutableMap.of()) : ImmutableList.of()));
                assertThat(settings.at("/sandbox/allowUnsandboxedCommands").asBoolean()).isEqualTo(access == SessionAccess.FULL);
                assertThat(settings.at("/permissions/ask").isMissingNode()).isTrue();
            }
        }
    }

    @Test
    void rejectsMalformedLinkedGitMetadataWithoutLeavingClaudeConfiguration()
            throws Exception
    {
        Path root = temporaryDirectory.toRealPath();
        Path worktree = Files.createDirectory(root.resolve("malformed-worktree"));
        Files.createSymbolicLink(worktree.resolve(".git"), root.resolve("missing-gitdir"));
        Path runtime = Files.createDirectory(root.resolve("malformed-runtime"));
        Path provider = root.resolve("malformed-provider");
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"), objectMapper, (taskId, query) -> "").build();
        assertThatThrownBy(() -> broker.register("malformed", worktree, runtime, provider, AgentPermissionPolicy.AUTO, SessionAccess.FULL,
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()), SecretRedactor.fromEnvironment(ImmutableMap.of()),
                Optional.empty(), ImmutableMap.of(), Optional.empty(), ImmutableList.of()))
                .hasMessageContaining("regular file");
        assertThat(provider.resolve("bytequay-mcp.json")).doesNotExist();
        assertThat(provider.resolve("sandbox-settings.json")).doesNotExist();
    }

    @Test
    void scopesAuthenticationAndNativeReviewIndependentlyOfProviderLogin()
            throws Exception
    {
        Path worktree = Files.createDirectories(temporaryDirectory.resolve("auth-worktree"));
        Path runtime = Files.createDirectories(temporaryDirectory.resolve("auth-runtime"));
        String socket = temporaryDirectory.resolve("ssh-agent.sock").toString();
        String ghConfig = temporaryDirectory.resolve("github-config").toString();
        String xdgConfig = temporaryDirectory.resolve("xdg-config").toString();
        ReviewBridge review = new ReviewBridge(ImmutableList.of(new ReviewTool(
                "review_context", "Read review context", ImmutableMap.of("type", "object"), arguments -> "review context")), "Review only");
        for (AgentPermissionPolicy policy : AgentPermissionPolicy.values()) {
            for (SessionAccess access : SessionAccess.values()) {
                for (boolean requestedReview : ImmutableList.of(false, true)) {
                    PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"), objectMapper, (taskId, query) -> "")
                            .reviews(taskId -> requestedReview ? Optional.of(review) : Optional.empty()).build();
                    boolean reviewSession = requestedReview && access != SessionAccess.READ_ONLY && access != SessionAccess.TOOLS_ONLY;
                    boolean interactive = policy == AgentPermissionPolicy.AUTO && access == SessionAccess.FULL && !reviewSession;
                    Optional<JsonNode> previous = Optional.empty();
                    for (String loginVariable : ImmutableList.of("ANTHROPIC_API_KEY", "CLAUDE_CODE_OAUTH_TOKEN")) {
                        var environment = ImmutableMap.of(loginVariable, "provider-secret", "GH_TOKEN", "github-secret",
                                "SSH_AUTH_SOCK", socket, "GH_CONFIG_DIR", ghConfig, "XDG_CONFIG_HOME", xdgConfig);
                        try (var registration = broker.register("task-auth", worktree, runtime, runtime.resolve("provider"), policy, access,
                                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()),
                                SecretRedactor.fromEnvironment(environment), Optional.empty(), environment, Optional.empty(), ImmutableList.of())) {
                            String json = Files.readString(registration.settingsFile().orElseThrow());
                            JsonNode settings = objectMapper.readTree(json);
                            assertThat(settings.path("autoMemoryEnabled")).isEqualTo(objectMapper.valueToTree(false));
                            assertThat(json).doesNotContain("provider-secret", "github-secret");
                            boolean canRetryOutsideSandbox = access == SessionAccess.FULL
                                    && policy != AgentPermissionPolicy.CHERRY_PICK;
                            assertThat(settings.at("/sandbox/allowUnsandboxedCommands").asBoolean())
                                    .isEqualTo(canRetryOutsideSandbox);
                            assertThat(settings.at("/permissions/ask").isMissingNode()).isTrue();
                            boolean ciBuildNetwork = policy == AgentPermissionPolicy.CI_AUTOFIX && access == SessionAccess.FULL;
                            assertThat(settings.at("/sandbox/network/allowLocalBinding").asBoolean())
                                    .isEqualTo((policy == AgentPermissionPolicy.AUTO && access == SessionAccess.FULL) || ciBuildNetwork);
                            boolean dockerAccess = access == SessionAccess.FULL && policy != AgentPermissionPolicy.CHERRY_PICK;
                            assertThat(settings.at("/sandbox/network/allowUnixSockets"))
                                    .isEqualTo(dockerAccess
                                            ? objectMapper.valueToTree(ImmutableList.of("/var/run/docker.sock", "~/.docker/run/docker.sock", "~/.orbstack/run/docker.sock"))
                                            : objectMapper.missingNode());
                            assertThat(settings.at("/sandbox/network/allowAllUnixSockets").isMissingNode()).isTrue();
                            assertThat(settings.at("/sandbox/enabled").asBoolean()).isTrue();
                            // Maven's resolver cannot log in to Claude's sandbox proxy; code under review stays sandboxed.
                            boolean unsandboxedMaven = access == SessionAccess.FULL && !reviewSession
                                    && (policy == AgentPermissionPolicy.AUTO || policy == AgentPermissionPolicy.CI_AUTOFIX);
                            assertThat(settings.at("/sandbox/excludedCommands"))
                                    .isEqualTo(objectMapper.valueToTree(unsandboxedMaven ? ImmutableList.of("mvn *", "./mvnw *", "mvnd *") : ImmutableList.of()));
                            assertThat(PermissionBroker.sandboxInstruction(new AgentPermissionScope(policy, access, reviewSession)).contains("Maven call alone"))
                                    .isEqualTo(unsandboxedMaven);
                            if (interactive) {
                                assertThat(settings.at("/sandbox/network/allowedDomains"))
                                        .isEqualTo(objectMapper.valueToTree(ImmutableList.of("*")));
                                assertThat(settings.at("/permissions/deny")).isEmpty();
                                assertThat(settings.at("/sandbox/filesystem/denyRead")).isEmpty();
                                assertThat(settings.at("/autoMode/classifyAllShell").asBoolean()).isTrue();
                            }
                            else {
                                // An attended review reads remotes with the stored Git and gh login and never writes to them.
                                boolean reviewReads = reviewSession && policy == AgentPermissionPolicy.AUTO && access == SessionAccess.FULL;
                                assertThat(settings.at("/sandbox/network/allowedDomains"))
                                        .isEqualTo(objectMapper.valueToTree(reviewReads || ciBuildNetwork ? ImmutableList.of("*") : ImmutableList.of()));
                                if (reviewSession) {
                                    assertThat(settings.at("/sandbox/autoAllowBashIfSandboxed").asBoolean()).isFalse();
                                    assertThat(settings.at("/sandbox/filesystem/allowWrite"))
                                            .isEqualTo(objectMapper.valueToTree(access == SessionAccess.FULL ? temporaryRoots(ImmutableMap.of()) : ImmutableList.of()));
                                    assertThat(settings.at("/permissions/deny").toString()).contains("Bash(git commit:*)", "Bash(git update-ref:*)")
                                            .doesNotContain("Bash(git add", "Bash(git write-tree", "Bash(git update-index", "Bash(git symbolic-ref", "Bash(git reset");
                                    assertThat(settings.at("/autoMode/classifyAllShell").asBoolean()).isTrue();
                                    assertThat(settings.at("/autoMode/environment"))
                                            .isEqualTo(objectMapper.valueToTree(ImmutableList.of("$defaults", AgentCommandPolicy.reviewInstructions())));
                                    assertThat(settings.at("/autoMode/hard_deny"))
                                            .isEqualTo(objectMapper.valueToTree(ImmutableList.<String>builder().add("$defaults").addAll(AgentCommandPolicy.reviewRestrictions()).build()));
                                    boolean children = policy == AgentPermissionPolicy.AUTO && access == SessionAccess.FULL;
                                    assertThat(settings.at("/permissions/deny").toString()).contains("\"Skill\"", "\"Workflow\"", "\"SendMessage\"");
                                    assertThat(settings.at("/permissions/deny").toString().contains("\"Agent\"")).isNotEqualTo(children);
                                    assertThat(settings.at("/permissions/deny").toString().contains("\"Task\"")).isNotEqualTo(children);
                                    assertThat(settings.path("hooks").isMissingNode()).isNotEqualTo(children);
                                    assertThat(Files.exists(registration.settingsFile().orElseThrow().resolveSibling("review-child-gate.sh"))).isEqualTo(children);
                                }
                                else {
                                    assertThat(settings.path("autoMode").isMissingNode()).isTrue();
                                    assertThat(settings.at("/permissions/deny").toString())
                                            .doesNotContain("Bash(git commit", "Bash(git update-ref", "Bash(git cherry-pick", "Bash(git reset");
                                }
                                String denied = settings.at("/permissions/deny").toString();
                                assertThat(denied).contains("Bash(git push:*)", "Read(~/.config/gh/**)", "Read(~/.ssh/**)");
                                if (reviewReads) {
                                    assertThat(settings.at("/sandbox/filesystem/denyRead")).isEmpty();
                                    assertThat(denied).doesNotContain("Bash(gh:*)")
                                            .contains(
                                                    "Bash(git credential:*)",
                                                    "Bash(gh auth:*)",
                                                    "Bash(gh pr review:*)",
                                                    "Bash(gh pr comment)",
                                                    "Bash(gh pr merge:*)",
                                                    "Bash(gh issue comment:*)");
                                }
                                else {
                                    assertThat(denied).contains("Bash(gh:*)");
                                    assertThat(settings.at("/sandbox/filesystem/denyRead").toString()).contains(ghConfig, xdgConfig + "/gh");
                                }
                            }
                            previous.ifPresent(prior -> assertThat(settings).isEqualTo(prior));
                            previous = Optional.of(settings);
                        }
                    }
                }
            }
        }
    }

    @Test
    @EnabledOnOs(OS.MAC)
    void reviewChildGateKeepsWriteToolsWithTheRootAgent()
            throws Exception
    {
        Path worktree = Files.createDirectories(temporaryDirectory.resolve("gate-worktree"));
        // The runtime directory sits under "Application Support", so the hook command must survive a space.
        Path runtime = Files.createDirectories(temporaryDirectory.resolve("gate runtime"));
        ReviewBridge review = new ReviewBridge(ImmutableList.of(
                new ReviewTool("review_get_context", "Read review context", ImmutableMap.of("type", "object"), arguments -> "context", true),
                new ReviewTool("review_finish", "Finish the review", ImmutableMap.of("type", "object"), arguments -> "{}")), "Review only");
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"), objectMapper, (taskId, query) -> "")
                .reviews(taskId -> Optional.of(review)).build();
        Path gate;
        try (var registration = broker.register("task-gate", worktree, runtime, runtime.resolve("provider"), AgentPermissionPolicy.AUTO, SessionAccess.FULL,
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()), SecretRedactor.fromEnvironment(ImmutableMap.of()),
                Optional.of(new CodeQueryBridge(query -> "graph", "Use the graph")), ImmutableMap.of(), Optional.empty(), ImmutableList.of())) {
            gate = registration.settingsFile().orElseThrow().resolveSibling("review-child-gate.sh");
            JsonNode hook = objectMapper.readTree(Files.readString(registration.settingsFile().orElseThrow())).at("/hooks/PreToolUse/0");
            assertThat(hook.path("matcher").asText()).isEqualTo("^(Agent|Task|mcp__bytequay__.*)$");
            String command = hook.at("/hooks/0/command").asText();
            assertThat(command).isEqualTo("'" + gate + "'");
            assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(gate))).isEqualTo("rwx------");
            String child = "\"agent_id\":\"a1\",\"agent_type\":\"Explore\",";

            // The root agent keeps every tool.
            assertThat(gate(command, "{\"tool_name\":\"mcp__bytequay__review_finish\",\"tool_input\":{}}")).isZero();
            // A child reads and explores, and nothing else.
            assertThat(gate(command, "{" + child + "\"tool_name\":\"mcp__bytequay__review_get_context\",\"tool_input\":{\"kind\":\"pr\"}}")).isZero();
            assertThat(gate(command, "{" + child + "\"tool_name\":\"mcp__bytequay__code_query\",\"tool_input\":{}}")).isZero();
            assertThat(gate(command, "{" + child + "\"tool_name\":\"mcp__bytequay__review_finish\",\"tool_input\":{}}")).isEqualTo(2);
            assertThat(gate(command, "{" + child + "\"tool_name\":\"mcp__bytequay__review_future_tool\",\"tool_input\":{}}")).isEqualTo(2);
            // Only read-only Explore children start.
            assertThat(gate(command, "{\"tool_name\":\"Agent\",\"tool_input\":{\"subagent_type\":\"Explore\",\"prompt\":\"find callers\"}}")).isZero();
            assertThat(gate(command, "{\"tool_name\":\"Agent\",\"tool_input\":{\"subagent_type\":\"general-purpose\",\"prompt\":\"fix it\"}}")).isEqualTo(2);
            assertThat(gate(command, "{\"tool_name\":\"Agent\",\"tool_input\":{\"prompt\":\"no type\"}}")).isEqualTo(2);
            // Text or arguments that merely mention agent_id do not make the root look like a child.
            assertThat(gate(command, "{\"tool_name\":\"mcp__bytequay__review_finish\",\"tool_input\":{\"body\":\"the \\\"agent_id\\\": field\",\"agent_id\":\"x\"}}")).isZero();
            // A call the gate cannot read is refused.
            assertThat(gate(command, "not json")).isEqualTo(2);
        }
        assertThat(gate).doesNotExist();
    }

    private McpRegistration registerCodexTask(PermissionBroker broker, AgentApprovalHandler approvals, SecretRedactor redactor)
    {
        return broker.registerTools("task-1", temporaryDirectory, AgentPermissionPolicy.AUTO, approvals, redactor,
                Optional.empty(), SessionAccess.FULL, Optional.empty(), ImmutableList.of()).orElseThrow();
    }

    private McpRegistration registerClaudeTask(PermissionBroker broker, AgentApprovalHandler approvals, SecretRedactor redactor)
    {
        return broker.register("task-1", temporaryDirectory, temporaryDirectory, temporaryDirectory, AgentPermissionPolicy.AUTO,
                SessionAccess.FULL, approvals, redactor, Optional.empty(), ImmutableMap.of(), Optional.empty(), ImmutableList.of());
    }

    private JsonNode listTools(PermissionBroker broker, String token)
            throws Exception
    {
        return broker.handle(token, objectMapper.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                """)).body().orElseThrow();
    }

    private static int gate(String hookCommand, String payload)
            throws Exception
    {
        Process process = new ProcessBuilder("/bin/sh", "-c", hookCommand).redirectErrorStream(true).start();
        try (OutputStream stdin = process.getOutputStream()) {
            stdin.write(payload.getBytes(UTF_8));
        }
        process.getInputStream().readAllBytes();
        assertThat(process.waitFor(10, SECONDS)).isTrue();
        return process.exitValue();
    }

    private String permission(PermissionBroker broker, String token, String toolName, String field, String value)
            throws Exception
    {
        return permission(broker, token, toolName, objectMapper.createObjectNode().put(field, value));
    }

    private String permission(PermissionBroker broker, String token, String toolName, JsonNode input)
            throws Exception
    {
        ObjectNode arguments = objectMapper.createObjectNode().put("tool_name", toolName);
        arguments.set("input", input);
        ObjectNode params = objectMapper.createObjectNode().put("name", "permission_prompt");
        params.set("arguments", arguments);
        ObjectNode request = objectMapper.createObjectNode()
                .put("jsonrpc", "2.0")
                .put("id", 10)
                .put("method", "tools/call");
        request.set("params", params);
        String result = broker.handle(token, request).body().orElseThrow().at("/result/content/0/text").asText();
        return objectMapper.readTree(result).get("behavior").asText();
    }

    private JsonNode rootToolCall(PermissionBroker broker, String token, String name, String arguments)
            throws Exception
    {
        return broker.callCodexRootTool(token, PermissionBroker.CALL_TOOL,
                objectMapper.valueToTree(ImmutableMap.of("name", name, "arguments", objectMapper.readTree(arguments)))).body().orElseThrow();
    }

    private String token(McpRegistration registration)
            throws Exception
    {
        JsonNode config = objectMapper.readTree(Files.readString(registration.configFile().orElseThrow()));
        return config.at("/mcpServers/bytequay/headers/Authorization").asText().substring("Bearer ".length());
    }

    private static List<String> temporaryRoots(Map<String, String> environment)
    {
        return BuildCacheRoots.temporary(environment).stream().map(Path::toString).collect(ImmutableList.toImmutableList());
    }
}
