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
import com.bytequay.agent.spi.CodeQueryBridge;
import com.bytequay.agent.spi.LocalReviewBridge;
import com.bytequay.agent.spi.ProviderApprovalDecision;
import com.bytequay.agent.spi.ReviewBridge;
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
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TestReviewToolBroker
{
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    private Path temporaryDirectory;

    private static ReviewBridge bridge(AtomicReference<String> received)
    {
        return new ReviewBridge(
                ImmutableList.of(
                        new ReviewTool(
                                "review_save_finding",
                                "Save a finding",
                                ImmutableMap.of("type", "object", "required", ImmutableList.of("operationKey")),
                                arguments -> {
                                    received.set(arguments);
                                    return "{\"findingId\":\"F-1\",\"revision\":1,\"secret\":\"known-secret\"}";
                                }),
                        new ReviewTool(
                                "review_finish",
                                "Finish",
                                ImmutableMap.of("type", "object"),
                                arguments -> {
                                    throw new IllegalStateException("run already ended");
                                })),
                "Review policy: report through the review tools.");
    }

    @Test
    void specCheckToolsAreListedAndDispatchedByBothProviderRegistrations()
            throws Exception
    {
        ImmutableList<String> names = ImmutableList.of("repository_context_read", "repository_context_spec_result");
        AtomicReference<String> called = new AtomicReference<>();
        ReviewBridge check = new ReviewBridge(names.stream()
                .map(name -> new ReviewTool(name, "Check", ImmutableMap.of("type", "object"), arguments -> {
                    called.set(name + ":" + arguments);
                    return "{\"accepted\":true}";
                })).toList(), "Check the selected specs");
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"), objectMapper, (taskId, query) -> "memory")
                .reviews(taskId -> Optional.of(check)).build();
        SecretRedactor redactor = SecretRedactor.fromEnvironment(ImmutableMap.of());
        try (McpRegistration codex = broker.registerTools(
                "check-task",
                temporaryDirectory,
                AgentPermissionPolicy.AUTO,
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()),
                redactor,
                Optional.empty(),
                SessionAccess.FULL,
                Optional.empty(),
                ImmutableList.of()).orElseThrow();
                McpRegistration claude = broker.register("check-task", temporaryDirectory, temporaryDirectory, temporaryDirectory, AgentPermissionPolicy.AUTO,
                        SessionAccess.FULL, (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()), redactor,
                        Optional.empty(), ImmutableMap.of(), Optional.empty(), ImmutableList.of())) {
            for (McpRegistration registration : ImmutableList.of(codex, claude)) {
                assertThat(registration.toolNames()).containsAll(names);
                JsonNode listed = broker.handle(registration.token(), objectMapper.readTree("""
                        {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                        """)).body().orElseThrow();
                assertThat(listed.at("/result/tools").findValuesAsText("name")).containsAll(names);
                JsonNode submitted = broker.handle(registration.token(), objectMapper.readTree("""
                        {"jsonrpc":"2.0","id":2,"method":"tools/call","params":
                        {"name":"repository_context_spec_result","arguments":{"report":"evidenced"}}}
                        """)).body().orElseThrow();
                assertThat(submitted.at("/result/isError").asBoolean()).isFalse();
                assertThat(submitted.at("/result/content/0/text").asText()).isEqualTo("{\"accepted\":true}");
                assertThat(called).hasValue("repository_context_spec_result:{\"report\":\"evidenced\"}");
            }
        }
    }

    @Test
    void privateReviewPolicyRetainsDiscussionsAndExcludesDeliveryToolsForBothProviders()
            throws Exception
    {
        ReviewBridge review = new ReviewBridge(ImmutableList.of(), "Keep private experiments uncommitted");
        LocalReviewBridge local = new LocalReviewBridge((id, contextOffset) -> "[]", reply -> "saved", resolution -> "resolved", LocalReviewBridge.INSTRUCTION);
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"), objectMapper, (taskId, query) -> {
                    throw new AssertionError("Private reviews cannot read CI memory");
                })
                .reviews(taskId -> Optional.of(review))
                .developments(taskId -> {
                    throw new AssertionError("Private reviews cannot expose development delivery");
                })
                .localReviews(taskId -> Optional.of(local))
                .pullRequestChecks(taskId -> {
                    throw new AssertionError("Private reviews cannot expose development CI tools");
                }).build();
        SecretRedactor redactor = SecretRedactor.fromEnvironment(ImmutableMap.of());
        try (McpRegistration codex = broker.registerTools(
                "development-task",
                temporaryDirectory,
                AgentPermissionPolicy.AUTO,
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()),
                redactor,
                Optional.empty(),
                SessionAccess.FULL,
                Optional.empty(),
                ImmutableList.of()).orElseThrow();
                McpRegistration claude = broker.register("development-task", temporaryDirectory, temporaryDirectory, temporaryDirectory,
                        AgentPermissionPolicy.AUTO, SessionAccess.FULL,
                        (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.allow(Optional.empty())), redactor, Optional.empty(),
                        ImmutableMap.of(), Optional.empty(), ImmutableList.of())) {
            assertThat(codex.review()).contains(review);
            assertThat(claude.review()).contains(review);
            assertThat(codex.instruction()).hasValueSatisfying(instruction -> assertThat(instruction).contains(review.instruction(), local.instruction()));
            assertThat(claude.instruction()).hasValueSatisfying(instruction -> assertThat(instruction).contains(review.instruction(), local.instruction()));
            for (String token : ImmutableList.of(codex.token(), claude.token())) {
                JsonNode listed = broker.handle(token, objectMapper.readTree("""
                        {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                        """)).body().orElseThrow();
                assertThat(listed.at("/result/tools").findValues("name").stream().filter(JsonNode::isTextual).map(JsonNode::asText).toList())
                        .containsAll(ImmutableList.of(LocalReviewBridge.LIST_TOOL, LocalReviewBridge.REPLY_TOOL, LocalReviewBridge.RESOLVE_TOOL))
                        .allMatch(name -> ImmutableList.of(LocalReviewBridge.LIST_TOOL, LocalReviewBridge.REPLY_TOOL, LocalReviewBridge.RESOLVE_TOOL).contains(name)
                                || ImmutableList.of("permission_prompt", "bytequay_list_tools", "bytequay_get_tool", PermissionBroker.CALL_TOOL).contains(name));
                JsonNode unavailable = broker.handle(token, objectMapper.readTree("""
                        {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"review_finish","arguments":{}}}
                        """)).body().orElseThrow();
                assertThat(unavailable.at("/error/code").asInt()).isEqualTo(-32602);
            }
            JsonNode denied = broker.handle(claude.token(), objectMapper.readTree("""
                    {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"permission_prompt",
                    "arguments":{"tool_name":"Bash","input":{"command":"git commit -am experiment"}}}}
                    """)).body().orElseThrow();
            JsonNode decision = objectMapper.readTree(denied.at("/result/content/0/text").asText());
            assertThat(decision.path("behavior").asText()).isEqualTo("deny");
            assertThat(decision.path("message").asText()).contains("uncommitted");
        }
    }

    @Test
    void reviewSessionsRetainTheirLocalDiscussionToolsForBothProviders()
            throws Exception
    {
        LocalReviewBridge local = new LocalReviewBridge((id, contextOffset) -> "[]", reply -> "saved", resolution -> "resolved", LocalReviewBridge.INSTRUCTION);
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"), objectMapper, (taskId, query) -> "memory")
                .reviews(taskId -> Optional.of(bridge(new AtomicReference<>())))
                .localReviews(taskId -> Optional.of(local)).build();
        SecretRedactor redactor = SecretRedactor.fromEnvironment(ImmutableMap.of());
        try (McpRegistration registration = broker.registerTools(
                "review-task",
                temporaryDirectory,
                AgentPermissionPolicy.AUTO,
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()),
                redactor,
                Optional.empty(),
                SessionAccess.FULL,
                Optional.empty(),
                ImmutableList.of()).orElseThrow()) {
            assertThat(registration.review()).isPresent();
            assertThat(registration.instruction()).hasValueSatisfying(instruction -> assertThat(instruction).contains(LocalReviewBridge.INSTRUCTION));
            JsonNode reply = broker.handle(registration.token(), objectMapper.readTree("""
                    {"jsonrpc":"2.0","id":1,"method":"tools/call","params":{"name":"reply_local_review_comment",
                    "arguments":{"commentId":"local-1","body":"An explanation","operationKey":"reply"}}}
                    """)).body().orElseThrow();
            assertThat(reply.at("/result/isError").asBoolean()).isFalse();
            assertThat(reply.at("/result/content/0/text").asText()).isEqualTo("saved");
        }
        try (McpRegistration registration = broker.register("review-task", temporaryDirectory, temporaryDirectory, temporaryDirectory,
                AgentPermissionPolicy.AUTO, SessionAccess.FULL, (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()),
                redactor, Optional.empty(), ImmutableMap.of(), Optional.empty(), ImmutableList.of())) {
            assertThat(registration.review()).isPresent();
            assertThat(registration.toolNames()).containsAll(ImmutableList.of(LocalReviewBridge.LIST_TOOL, LocalReviewBridge.REPLY_TOOL, LocalReviewBridge.RESOLVE_TOOL));
        }
    }

    @Test
    void codexTokensExposeReviewToolsWithoutCodeQueriesAndDispatchJsonArguments()
            throws Exception
    {
        AtomicReference<String> received = new AtomicReference<>();
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"), objectMapper, (taskId, query) -> "memory")
                .reviews(taskId -> taskId.equals("review-task") ? Optional.of(bridge(received)) : Optional.empty()).build();
        SecretRedactor redactor = SecretRedactor.fromEnvironment(ImmutableMap.of("ANTHROPIC_API_KEY", "known-secret"));
        assertThat(broker.registerTools(
                "plain-task",
                temporaryDirectory,
                AgentPermissionPolicy.AUTO,
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()),
                redactor,
                Optional.empty(),
                SessionAccess.FULL,
                Optional.empty(),
                ImmutableList.of())).isEmpty();
        McpRegistration registration = broker.registerTools(
                "review-task",
                temporaryDirectory,
                AgentPermissionPolicy.AUTO,
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()),
                redactor,
                Optional.empty(),
                SessionAccess.FULL,
                Optional.empty(),
                ImmutableList.of()).orElseThrow();
        assertThat(registration.review()).isPresent();
        assertThat(registration.instruction()).hasValueSatisfying(value -> assertThat(value).contains("Review policy: report through the review tools."));

        JsonNode listed = broker.handle(registration.token(), objectMapper.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                """)).body().orElseThrow();
        assertThat(listed.at("/result/tools")).hasSize(5);
        assertThat(listed.at("/result/tools/0/name").asText()).isEqualTo("review_save_finding");
        assertThat(listed.at("/result/tools/0/inputSchema/required/0").asText()).isEqualTo("operationKey");

        JsonNode saved = broker.handle(registration.token(), objectMapper.readTree("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"review_save_finding","arguments":{"operationKey":"k1","title":"t"}}}
                """)).body().orElseThrow();
        assertThat(received.get()).isEqualTo("{\"operationKey\":\"k1\",\"title\":\"t\"}");
        assertThat(saved.at("/result/isError").asBoolean()).isFalse();
        assertThat(saved.at("/result/content/0/text").asText()).isEqualTo("{\"findingId\":\"F-1\",\"revision\":1,\"secret\":\"[REDACTED]\"}");

        JsonNode failed = broker.handle(registration.token(), objectMapper.readTree("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"review_finish","arguments":{}}}
                """)).body().orElseThrow();
        assertThat(failed.at("/result/isError").asBoolean()).isTrue();
        assertThat(failed.at("/result/content/0/text").asText()).contains("run already ended");

        JsonNode unknown = broker.handle(registration.token(), objectMapper.readTree("""
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"review_reply","arguments":{}}}
                """)).body().orElseThrow();
        assertThat(unknown.at("/error/code").asInt()).isEqualTo(-32602);
        JsonNode permission = broker.handle(registration.token(), objectMapper.readTree("""
                {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"permission_prompt","arguments":{"tool_name":"Bash","input":{}}}}
                """)).body().orElseThrow();
        assertThat(permission.at("/error/code").asInt()).isEqualTo(-32602);
        registration.close();
    }

    @Test
    void claudeRegistrationsCombineInstructionsAndRefuseRemoteWritesBeforeApproval()
            throws Exception
    {
        AtomicInteger approvals = new AtomicInteger();
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"), objectMapper, (taskId, query) -> "memory")
                .reviews(taskId -> Optional.of(bridge(new AtomicReference<>()))).build();
        McpRegistration registration = broker.register("review-task",
                temporaryDirectory,
                temporaryDirectory,
                temporaryDirectory,
                AgentPermissionPolicy.AUTO,
                SessionAccess.FULL,
                (request, timeout) -> {
                    approvals.incrementAndGet();
                    return CompletableFuture.completedFuture(ProviderApprovalDecision.allow(Optional.empty()));
                },
                SecretRedactor.fromEnvironment(ImmutableMap.of()),
                Optional.of(new CodeQueryBridge(query -> "graph", "use the graph")),
                ImmutableMap.of(),
                Optional.empty(),
                ImmutableList.of());
        assertThat(registration.review()).isPresent();
        assertThat(registration.instruction()).hasValueSatisfying(value -> assertThat(value).contains("Review policy: report through the review tools.\n\nuse the graph"));

        JsonNode listed = broker.handle(registration.token(), objectMapper.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                """)).body().orElseThrow();
        assertThat(listed.at("/result/tools")).hasSize(7);

        JsonNode refused = broker.handle(registration.token(), objectMapper.readTree("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"permission_prompt","arguments":{"tool_name":"Bash","input":{"command":"git push origin HEAD"}}}}
                """)).body().orElseThrow();
        JsonNode refusedResult = objectMapper.readTree(refused.at("/result/content/0/text").asText());
        assertThat(refusedResult.get("behavior").asText()).isEqualTo("deny");
        assertThat(refusedResult.get("message").asText()).contains("cannot push");
        assertThat(approvals).hasValue(0);

        ImmutableList<String> experimentCommands = ImmutableList.of("./mvnw test", "git add TestExperiment.java", "git reset HEAD -- TestExperiment.java",
                "git restore --staged TestExperiment.java", "git apply --cached experiment.patch", "git symbolic-ref --short HEAD", "git write-tree", "git update-index --refresh");
        for (String command : experimentCommands) {
            JsonNode request = objectMapper.valueToTree(ImmutableMap.of(
                    "jsonrpc", "2.0", "id", 3, "method", "tools/call",
                    "params", ImmutableMap.of("name", "permission_prompt", "arguments", ImmutableMap.of("tool_name", "Bash", "input", ImmutableMap.of("command", command)))));
            JsonNode allowed = broker.handle(registration.token(), request).body().orElseThrow();
            assertThat(objectMapper.readTree(allowed.at("/result/content/0/text").asText()).get("behavior").asText()).as(command).isEqualTo("allow");
        }
        assertThat(approvals).hasValue(experimentCommands.size());
        registration.close();
    }

    @Test
    void claudeReviewRegistrationsAdmitOnlyExploreChildrenWhileOrdinaryTasksPreserveApprovals()
            throws Exception
    {
        AtomicInteger approvals = new AtomicInteger();
        PermissionBroker broker = PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"), objectMapper, (taskId, query) -> "memory")
                .reviews(taskId -> taskId.equals("review-task") ? Optional.of(bridge(new AtomicReference<>())) : Optional.empty()).build();
        for (String taskId : ImmutableList.of("review-task", "plain-task")) {
            try (McpRegistration registration = broker.register(taskId,
                    temporaryDirectory,
                    temporaryDirectory,
                    temporaryDirectory.resolve(taskId),
                    AgentPermissionPolicy.AUTO,
                    SessionAccess.FULL,
                    (request, timeout) -> {
                        approvals.incrementAndGet();
                        return CompletableFuture.completedFuture(ProviderApprovalDecision.allow(Optional.empty()));
                    },
                    SecretRedactor.fromEnvironment(ImmutableMap.of()),
                    Optional.empty(),
                    ImmutableMap.of(),
                    Optional.empty(),
                    ImmutableList.of())) {
                JsonNode deniedTools = objectMapper.readTree(Files.readString(registration.settingsFile().orElseThrow())).at("/permissions/deny");
                if (registration.review().isPresent()) {
                    // The child gate, not a blanket refusal, keeps review write tools with the root agent.
                    assertThat(deniedTools).extracting(JsonNode::asText).contains("Skill", "Workflow", "SendMessage").doesNotContain("Agent", "Task");
                }
                else {
                    assertThat(deniedTools).extracting(JsonNode::asText).doesNotContain("Agent", "Task", "Skill", "Workflow", "SendMessage");
                }
                for (String tool : ImmutableList.of("Agent", "Task", "Skill", "Workflow", "SendMessage")) {
                    JsonNode permission = permission(broker, registration.token(), tool, ImmutableMap.of("prompt", "Investigate the change"));
                    if (registration.review().isPresent()) {
                        assertThat(permission.get("behavior").asText()).isEqualTo("deny");
                        assertThat(permission.get("message").asText()).contains(tool.equals("Agent") || tool.equals("Task") ? "only read-only Explore subagents" : "require the root agent");
                        assertThat(approvals).hasValue(0);
                    }
                    else {
                        assertThat(permission.get("behavior").asText()).isEqualTo("allow");
                    }
                }
                if (registration.review().isPresent()) {
                    assertThat(permission(broker, registration.token(), "Agent", ImmutableMap.of("subagent_type", "general-purpose", "prompt", "Fix it")).get("behavior").asText())
                            .isEqualTo("deny");
                    assertThat(approvals).hasValue(0);
                    assertThat(permission(
                            broker,
                            registration.token(),
                            "Agent",
                            ImmutableMap.of("subagent_type", "Explore", "prompt", "Find the callers")).get("behavior").asText())
                            .isEqualTo("allow");
                    assertThat(approvals).hasValue(1);
                }
            }
        }
        assertThat(approvals).hasValue(6);
    }

    private JsonNode permission(PermissionBroker broker, String token, String tool, Map<String, Object> input)
            throws Exception
    {
        JsonNode request = objectMapper.valueToTree(ImmutableMap.of(
                "jsonrpc", "2.0",
                "id", tool,
                "method", "tools/call",
                "params", ImmutableMap.of("name", "permission_prompt", "arguments", ImmutableMap.of("tool_name", tool, "input", input))));
        JsonNode response = broker.handle(token, request).body().orElseThrow();
        return objectMapper.readTree(response.at("/result/content/0/text").asText());
    }
}
