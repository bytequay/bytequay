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
import com.bytequay.agent.spi.BridgeResolver;
import com.bytequay.agent.spi.CiDescriptionProposal;
import com.bytequay.agent.spi.ProviderApprovalDecision;
import com.bytequay.agent.spi.PullRequestCiBridge;
import com.bytequay.agent.spi.ReviewBridge;
import com.bytequay.agent.spi.ReviewTool;
import com.bytequay.agent.spi.SessionAccess;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TestPullRequestCiTool
{
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SecretRedactor redactor = SecretRedactor.fromEnvironment(ImmutableMap.of("GH_TOKEN", "known-secret"));

    @TempDir
    private Path temporaryDirectory;

    @Test
    void bothProvidersReadFreshTaskBoundCiWithoutAutomationOrCodeQueries()
            throws Exception
    {
        List<Optional<Long>> reads = new ArrayList<>();
        AtomicReference<Optional<PullRequestCiBridge>> current = new AtomicReference<>(Optional.of(new PullRequestCiBridge(jobId -> {
            reads.add(jobId);
            return jobId.isPresent() ? "failed log known-secret" : "No pull request associated yet";
        }, (logId, offset) -> "raw", PullRequestCiBridge.INSTRUCTION, Optional.empty(), Optional.empty())));
        PermissionBroker broker = broker(taskId -> taskId.equals("task-1") ? current.get() : Optional.empty(), BridgeResolver.none());
        String expiredToken;
        try (McpRegistration codex = broker.registerTools(
                "task-1",
                temporaryDirectory,
                AgentPermissionPolicy.AUTO,
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()),
                redactor,
                Optional.empty(),
                SessionAccess.FULL,
                Optional.empty(),
                ImmutableList.of()).orElseThrow();
                McpRegistration claude = broker.register("task-1", temporaryDirectory, temporaryDirectory, temporaryDirectory, AgentPermissionPolicy.AUTO,
                        SessionAccess.FULL, (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()), redactor,
                        Optional.empty(), ImmutableMap.of(), Optional.empty(), ImmutableList.of())) {
            expiredToken = codex.token();
            assertThat(codex.review()).isEmpty();
            assertThat(codex.development()).isEmpty();
            assertThat(codex.instruction()).hasValueSatisfying(value -> assertThat(value).contains(PullRequestCiBridge.INSTRUCTION));
            assertThat(claude.instruction()).hasValueSatisfying(value -> assertThat(value).contains(PullRequestCiBridge.INSTRUCTION));
            for (String token : ImmutableList.of(codex.token(), claude.token())) {
                assertThat(list(broker, token).at("/result/tools").findValuesAsText("name")).contains(PullRequestCiBridge.TOOL_NAME);
                assertThat(text(call(broker, token, "{}"))).contains("No pull request associated yet");
                assertThat(text(call(broker, token, "{\"jobId\":7}"))).contains("failed log [REDACTED]").doesNotContain("known-secret");
            }
            assertThat(reads).containsExactly(Optional.empty(), Optional.of(7L), Optional.empty(), Optional.of(7L));
            current.set(Optional.of(new PullRequestCiBridge(jobId -> "Current head changed", (logId, offset) -> "raw",
                                PullRequestCiBridge.INSTRUCTION, Optional.empty(), Optional.empty())));
            assertThat(text(call(broker, codex.token(), "{}"))).isEqualTo("Current head changed");
            current.set(Optional.empty());
            JsonNode unavailable = call(broker, claude.token(), "{}");
            assertThat(unavailable.at("/result/isError").booleanValue()).isTrue();
            assertThat(text(unavailable)).contains("unavailable for this task");
        }
        assertThat(broker.handle(expiredToken, objectMapper.readTree("{}")).status()).isEqualTo(401);
        assertThat(broker.registerTools(
                "task-2",
                temporaryDirectory,
                AgentPermissionPolicy.AUTO,
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()),
                redactor,
                Optional.empty(),
                SessionAccess.FULL,
                Optional.empty(),
                ImmutableList.of())).isEmpty();
    }

    @Test
    void bothProvidersExposeRawLogReadsForAutofixAndRejectInvalidOffsetsAndPaths()
            throws Exception
    {
        List<String> reads = new ArrayList<>();
        String logId = "a".repeat(64);
        PermissionBroker broker = broker(taskId -> Optional.of(new PullRequestCiBridge(jobId -> "error excerpt", (id, offset) -> {
            reads.add(id + ":" + offset);
            return "complete raw page known-secret";
        }, PullRequestCiBridge.INSTRUCTION, Optional.empty(), Optional.empty())), BridgeResolver.none());
        try (McpRegistration codex = broker.registerTools(
                "ci-task",
                temporaryDirectory,
                AgentPermissionPolicy.CI_AUTOFIX,
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()),
                redactor,
                Optional.empty(),
                SessionAccess.FULL,
                Optional.empty(),
                ImmutableList.of()).orElseThrow();
                McpRegistration claude = broker.register("ci-task", temporaryDirectory, temporaryDirectory, temporaryDirectory,
                        AgentPermissionPolicy.CI_AUTOFIX, SessionAccess.FULL,
                        (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()), redactor, Optional.empty(), ImmutableMap.of(),
                        Optional.empty(), ImmutableList.of())) {
            for (String token : ImmutableList.of(codex.token(), claude.token())) {
                assertThat(list(broker, token).at("/result/tools").findValuesAsText("name"))
                        .contains(PullRequestCiBridge.TOOL_NAME, PullRequestCiBridge.RAW_TOOL_NAME);
                assertThat(text(callRaw(broker, token, "{\"logId\":\"" + logId + "\"}"))).isEqualTo("complete raw page [REDACTED]");
                assertThat(text(callRaw(broker, token, "{\"logId\":\"" + logId + "\",\"offset\":32768}"))).contains("complete raw page");
                for (String arguments : ImmutableList.of("{}", "{\"logId\":\"../outside\"}",
                        "{\"logId\":\"" + logId + "\",\"offset\":-1}", "{\"logId\":\"" + logId + "\",\"offset\":1.5}",
                        "{\"logId\":\"" + logId + "\",\"taskId\":\"other\"}")) {
                    assertThat(callRaw(broker, token, arguments).at("/error/code").intValue()).isEqualTo(-32602);
                }
            }
            assertThat(reads).containsExactly(logId + ":0", logId + ":32768", logId + ":0", logId + ":32768");
        }
    }

    @Test
    void rejectsScopeOverridesAndInvalidJobIdsBeforeReading()
            throws Exception
    {
        PermissionBroker broker = broker(taskId -> Optional.of(new PullRequestCiBridge(jobId -> {
            throw new AssertionError("Invalid tool arguments reached the CI reader");
        }, (logId, offset) -> "raw", PullRequestCiBridge.INSTRUCTION, Optional.empty(), Optional.empty())), BridgeResolver.none());
        try (McpRegistration registration = broker.registerTools(
                "task-1",
                temporaryDirectory,
                AgentPermissionPolicy.AUTO,
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()),
                redactor,
                Optional.empty(),
                SessionAccess.FULL,
                Optional.empty(),
                ImmutableList.of()).orElseThrow()) {
            for (String arguments : ImmutableList.of(
                    "null", "[]", "false", "{\"jobId\":null}", "{\"jobId\":0}", "{\"jobId\":-1}", "{\"jobId\":1.5}",
                    "{\"jobId\":\"7\"}", "{\"jobId\":9223372036854775808}", "{\"repo\":\"other/repo\"}",
                    "{\"number\":7}", "{\"url\":\"https://github.com/other/repo\"}", "{\"path\":\"/tmp/log\"}", "{\"jobId\":7,\"taskId\":\"other\"}")) {
                assertThat(call(broker, registration.token(), arguments).at("/error/code").intValue()).isEqualTo(-32602);
            }
        }
    }

    @Test
    void restrictedProfilesNeverExposeTheCiReader()
            throws Exception
    {
        BridgeResolver<PullRequestCiBridge> reader = taskId -> {
            throw new AssertionError("Restricted profile must not resolve pull-request CI");
        };
        PermissionBroker broker = broker(reader, BridgeResolver.none());
        for (AgentPermissionPolicy policy : ImmutableList.of(AgentPermissionPolicy.CHERRY_PICK)) {
            try (McpRegistration claude = broker.register("task-1", temporaryDirectory, temporaryDirectory, temporaryDirectory, policy, SessionAccess.FULL,
                    (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()), redactor, Optional.empty(), ImmutableMap.of(),
                    Optional.empty(), ImmutableList.of())) {
                assertThat(list(broker, claude.token()).at("/result/tools").findValuesAsText("name")).doesNotContain(PullRequestCiBridge.TOOL_NAME);
                assertThat(call(broker, claude.token(), "{}").at("/error/code").intValue()).isEqualTo(-32602);
            }
            Optional<McpRegistration> codex = broker.registerTools(
                    "task-1",
                    temporaryDirectory,
                    policy,
                    (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()),
                    redactor,
                    Optional.empty(),
                    SessionAccess.FULL,
                    Optional.empty(),
                    ImmutableList.of());
            if (codex.isPresent()) {
                try (McpRegistration registration = codex.orElseThrow()) {
                    assertThat(list(broker, registration.token()).at("/result/tools").findValuesAsText("name")).doesNotContain(PullRequestCiBridge.TOOL_NAME);
                }
            }
        }
        try (McpRegistration helper = broker.register("task-1", temporaryDirectory, temporaryDirectory, temporaryDirectory, AgentPermissionPolicy.AUTO,
                SessionAccess.TEXT_ONLY, (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()), redactor, Optional.empty(),
                ImmutableMap.of(), Optional.empty(), ImmutableList.of())) {
            assertThat(helper.toolNames()).doesNotContain(PullRequestCiBridge.TOOL_NAME, PullRequestCiBridge.RAW_TOOL_NAME);
        }
        ReviewBridge review = new ReviewBridge(ImmutableList.of(new ReviewTool(
                "review_context", "Read review context", ImmutableMap.of("type", "object"), arguments -> "review")), "Review only");
        PermissionBroker reviewBroker = broker(reader, taskId -> Optional.of(review));
        try (McpRegistration registration = reviewBroker.registerTools(
                "task-1",
                temporaryDirectory,
                AgentPermissionPolicy.AUTO,
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()),
                redactor,
                Optional.empty(),
                SessionAccess.FULL,
                Optional.empty(),
                ImmutableList.of()).orElseThrow();
                McpRegistration claude = reviewBroker.register(
                        "task-1", temporaryDirectory, temporaryDirectory, temporaryDirectory, AgentPermissionPolicy.AUTO, SessionAccess.FULL,
                        (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()), redactor,
                        Optional.empty(), ImmutableMap.of(), Optional.empty(), ImmutableList.of())) {
            assertThat(list(reviewBroker, registration.token()).at("/result/tools").findValues("name").stream().filter(JsonNode::isTextual).map(JsonNode::asText).toList())
                    .containsExactly("review_context", "bytequay_list_tools", "bytequay_get_tool", PermissionBroker.CALL_TOOL);
            assertThat(claude.toolNames()).doesNotContain(PullRequestCiBridge.TOOL_NAME, PullRequestCiBridge.RAW_TOOL_NAME);
        }
    }

    @Test
    void boundsAndRedactsCiReadFailures()
            throws Exception
    {
        PermissionBroker broker = broker(taskId -> Optional.of(new PullRequestCiBridge(jobId -> {
            throw new IllegalStateException("known-secret " + "x".repeat(PullRequestCiBridge.MAXIMUM_OUTPUT_LENGTH));
        }, (logId, offset) -> "raw", PullRequestCiBridge.INSTRUCTION, Optional.empty(), Optional.empty())), BridgeResolver.none());
        try (McpRegistration registration = broker.registerTools(
                "task-1",
                temporaryDirectory,
                AgentPermissionPolicy.AUTO,
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()),
                redactor,
                Optional.empty(),
                SessionAccess.FULL,
                Optional.empty(),
                ImmutableList.of()).orElseThrow()) {
            JsonNode response = call(broker, registration.token(), "{}");
            assertThat(response.at("/result/isError").booleanValue()).isTrue();
            assertThat(text(response)).contains("no partial log was returned").doesNotContain("known-secret");
        }
    }

    @Test
    void onlyCiSessionsCanProposeDescriptionsAndBothProvidersValidateTheExactPayload()
            throws Exception
    {
        List<CiDescriptionProposal> proposals = new ArrayList<>();
        PermissionBroker broker = broker(taskId -> Optional.of(new PullRequestCiBridge(jobId -> "checks", (logId, offset) -> "raw",
                PullRequestCiBridge.INSTRUCTION, Optional.of(proposal -> {
                    proposals.add(proposal);
                    return "Saved";
                }), Optional.empty())), BridgeResolver.none());
        for (AgentPermissionPolicy policy : ImmutableList.of(AgentPermissionPolicy.AUTO, AgentPermissionPolicy.CI_AUTOFIX)) {
            try (McpRegistration codex = broker.registerTools(
                    "task-1",
                    temporaryDirectory,
                    policy,
                    (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()),
                    redactor,
                    Optional.empty(),
                    SessionAccess.FULL,
                    Optional.empty(),
                    ImmutableList.of()).orElseThrow();
                    McpRegistration claude = broker.register("task-1", temporaryDirectory, temporaryDirectory, temporaryDirectory, policy, SessionAccess.FULL,
                            (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()), redactor, Optional.empty(),
                            ImmutableMap.of(), Optional.empty(), ImmutableList.of())) {
                for (String token : ImmutableList.of(codex.token(), claude.token())) {
                    JsonNode request = objectMapper.readTree("""
                            {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"propose_ci_description",
                              "arguments":{"jobId":7,"expectedBody":"Fixes #1.","body":"- Fixes #1."}}}
                            """);
                    if (policy == AgentPermissionPolicy.AUTO) {
                        assertThat(list(broker, token).at("/result/tools").findValuesAsText("name")).doesNotContain(CiDescriptionProposal.TOOL_NAME);
                        assertThat(broker.handle(token, request).body().orElseThrow().at("/error/code").intValue()).isEqualTo(-32602);
                        continue;
                    }
                    assertThat(list(broker, token).at("/result/tools").findValuesAsText("description"))
                            .anySatisfy(description -> assertThat(description).contains("descriptionTruncated", "expectedBody", "complete corrected body",
                                    "preserve release notes", "Never include local execution details", "even if already present", "test-plan section",
                                    "no\nshell/API write access", "Do not rerun the old event"));
                    assertThat(text(broker.handle(token, request).body().orElseThrow())).isEqualTo("Saved");
                    ((ObjectNode) request.at("/params/arguments")).put("repo", "other/repo");
                    assertThat(broker.handle(token, request).body().orElseThrow().at("/error/code").intValue()).isEqualTo(-32602);
                }
            }
        }
        assertThat(proposals).containsExactly(new CiDescriptionProposal(7, "Fixes #1.", "- Fixes #1."), new CiDescriptionProposal(7, "Fixes #1.", "- Fixes #1."));
    }

    @Test
    void bothProvidersRequireAnExplicitBoundedRerunAssessmentFromACiSession()
            throws Exception
    {
        List<Long> requests = new ArrayList<>();
        PermissionBroker broker = broker(taskId -> Optional.of(new PullRequestCiBridge(jobId -> "checks", (logId, offset) -> "raw",
                PullRequestCiBridge.INSTRUCTION, Optional.empty(), Optional.of((jobId, reason) -> {
                    requests.add(jobId);
                    return "Assessment saved";
                }))), BridgeResolver.none());
        for (AgentPermissionPolicy policy : ImmutableList.of(AgentPermissionPolicy.AUTO, AgentPermissionPolicy.CI_AUTOFIX)) {
            try (McpRegistration codex = broker.registerTools(
                    "task-1",
                    temporaryDirectory,
                    policy,
                    (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()),
                    redactor,
                    Optional.empty(),
                    SessionAccess.FULL,
                    Optional.empty(),
                    ImmutableList.of()).orElseThrow();
                    McpRegistration claude = broker.register("task-1", temporaryDirectory, temporaryDirectory, temporaryDirectory, policy, SessionAccess.FULL,
                            (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()), redactor, Optional.empty(),
                            ImmutableMap.of(), Optional.empty(), ImmutableList.of())) {
                for (String token : ImmutableList.of(codex.token(), claude.token())) {
                    JsonNode request = objectMapper.readTree("""
                            {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"request_ci_rerun",
                              "arguments":{"jobId":7,"reason":"Runner lost connection before tests started"}}}
                            """);
                    if (policy == AgentPermissionPolicy.AUTO) {
                        assertThat(list(broker, token).at("/result/tools").findValuesAsText("name")).doesNotContain(PullRequestCiBridge.RERUN_TOOL_NAME);
                        assertThat(broker.handle(token, request).body().orElseThrow().at("/error/code").intValue()).isEqualTo(-32602);
                        continue;
                    }
                    assertThat(list(broker, token).at("/result/tools").findValuesAsText("description"))
                            .anySatisfy(description -> assertThat(description).contains("current failed jobId", "does not establish flakiness",
                                    "Repair deterministic failures", "without\ndirectly rerunning anything", "After a failed rerun"));
                    assertThat(text(broker.handle(token, request).body().orElseThrow())).isEqualTo("Assessment saved");
                    ((ObjectNode) request.at("/params/arguments")).put("reason", " ");
                    assertThat(broker.handle(token, request).body().orElseThrow().at("/error/code").intValue()).isEqualTo(-32602);
                    ((ObjectNode) request.at("/params/arguments")).put("reason", "Network failure").put("repo", "other/repo");
                    assertThat(broker.handle(token, request).body().orElseThrow().at("/error/code").intValue()).isEqualTo(-32602);
                }
            }
        }
        assertThat(requests).containsExactly(7L, 7L);
    }

    private PermissionBroker broker(BridgeResolver<PullRequestCiBridge> ci, BridgeResolver<ReviewBridge> review)
    {
        return PermissionBroker.builder(() -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"), objectMapper, (taskId, query) -> "memory")
                .reviews(review)
                .pullRequestChecks(ci).build();
    }

    private JsonNode list(PermissionBroker broker, String token)
            throws Exception
    {
        return broker.handle(token, objectMapper.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                """)).body().orElseThrow();
    }

    private JsonNode call(PermissionBroker broker, String token, String arguments)
            throws Exception
    {
        return broker.handle(token, objectMapper.readTree("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"read_pull_request_ci","arguments":%s}}
                """.formatted(arguments))).body().orElseThrow();
    }

    private JsonNode callRaw(PermissionBroker broker, String token, String arguments)
            throws Exception
    {
        return broker.handle(token, objectMapper.readTree("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"read_ci_raw_log","arguments":%s}}
                """.formatted(arguments))).body().orElseThrow();
    }

    private static String text(JsonNode response)
    {
        return response.at("/result/content/0/text").asText();
    }
}
