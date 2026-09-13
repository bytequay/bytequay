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
package com.bytequay.agent.cli.claude;

import com.bytequay.agent.cli.process.SecretRedactor;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.DevelopmentResultBridgeResolver;
import com.bytequay.agent.spi.DraftPullRequestPublisher;
import com.bytequay.agent.spi.LocalReviewBridgeResolver;
import com.bytequay.agent.spi.ProviderApprovalDecision;
import com.bytequay.agent.spi.PullRequestCiBridge;
import com.bytequay.agent.spi.PullRequestCiBridgeResolver;
import com.bytequay.agent.spi.ReviewBridge;
import com.bytequay.agent.spi.ReviewBridgeResolver;
import com.bytequay.agent.spi.ReviewTool;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
    void bothProvidersReadFreshTaskBoundCiWithoutAutomationOrCodeGraph()
            throws Exception
    {
        List<Optional<Long>> reads = new ArrayList<>();
        AtomicReference<Optional<PullRequestCiBridge>> current = new AtomicReference<>(Optional.of(new PullRequestCiBridge(jobId -> {
            reads.add(jobId);
            return jobId.isPresent() ? "failed log known-secret" : "No pull request associated yet";
        }, PullRequestCiBridge.INSTRUCTION)));
        ClaudePermissionBroker broker = broker(taskId -> taskId.equals("task-1") ? current.get() : Optional.empty(), ReviewBridgeResolver.NONE);
        String expiredToken;
        try (ClaudePermissionBroker.CodeGraphRegistration codex = broker.registerTools(
                "task-1", temporaryDirectory, AgentPermissionPolicy.AUTO,
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()), redactor, Optional.empty()).orElseThrow();
                ClaudePermissionBroker.Registration claude = broker.register(
                        "task-1", temporaryDirectory, temporaryDirectory, temporaryDirectory, AgentPermissionPolicy.AUTO,
                        false, (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()), redactor, Optional.empty())) {
            expiredToken = codex.token();
            assertThat(codex.review()).isEmpty();
            assertThat(codex.development()).isEmpty();
            assertThat(codex.instruction()).isEqualTo(PullRequestCiBridge.INSTRUCTION);
            assertThat(claude.instruction()).contains(PullRequestCiBridge.INSTRUCTION);
            for (String token : ImmutableList.of(codex.token(), claude.token())) {
                assertThat(list(broker, token).at("/result/tools").findValuesAsText("name")).contains(PullRequestCiBridge.TOOL_NAME);
                assertThat(text(call(broker, token, "{}"))).contains("No pull request associated yet");
                assertThat(text(call(broker, token, "{\"jobId\":7}"))).contains("failed log [REDACTED]").doesNotContain("known-secret");
            }
            assertThat(reads).containsExactly(Optional.empty(), Optional.of(7L), Optional.empty(), Optional.of(7L));
            current.set(Optional.of(new PullRequestCiBridge(jobId -> "Current head changed", PullRequestCiBridge.INSTRUCTION)));
            assertThat(text(call(broker, codex.token(), "{}"))).isEqualTo("Current head changed");
            current.set(Optional.empty());
            JsonNode unavailable = call(broker, claude.token(), "{}");
            assertThat(unavailable.at("/result/isError").booleanValue()).isTrue();
            assertThat(text(unavailable)).contains("unavailable for this task");
        }
        assertThat(broker.handle(expiredToken, objectMapper.readTree("{}")).status()).isEqualTo(401);
        assertThat(broker.registerTools("task-2", temporaryDirectory, AgentPermissionPolicy.AUTO,
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()), redactor, Optional.empty())).isEmpty();
    }

    @Test
    void rejectsScopeOverridesAndInvalidJobIdsBeforeReading()
            throws Exception
    {
        ClaudePermissionBroker broker = broker(taskId -> Optional.of(new PullRequestCiBridge(jobId -> {
            throw new AssertionError("Invalid tool arguments reached the CI reader");
        }, PullRequestCiBridge.INSTRUCTION)), ReviewBridgeResolver.NONE);
        try (ClaudePermissionBroker.CodeGraphRegistration registration = broker.registerTools(
                "task-1", temporaryDirectory, AgentPermissionPolicy.AUTO,
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()), redactor, Optional.empty()).orElseThrow()) {
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
        PullRequestCiBridgeResolver reader = taskId -> {
            throw new AssertionError("Restricted profile must not resolve pull-request CI");
        };
        ClaudePermissionBroker broker = broker(reader, ReviewBridgeResolver.NONE);
        for (AgentPermissionPolicy policy : ImmutableList.of(AgentPermissionPolicy.CI_AUTOFIX, AgentPermissionPolicy.CHERRY_PICK)) {
            try (ClaudePermissionBroker.Registration claude = broker.register(
                    "task-1", temporaryDirectory, temporaryDirectory, temporaryDirectory, policy, false,
                    (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()), redactor, Optional.empty())) {
                assertThat(list(broker, claude.token()).at("/result/tools").findValuesAsText("name")).doesNotContain(PullRequestCiBridge.TOOL_NAME);
                assertThat(call(broker, claude.token(), "{}").at("/error/code").intValue()).isEqualTo(-32602);
            }
            Optional<ClaudePermissionBroker.CodeGraphRegistration> codex = broker.registerTools(
                    "task-1", temporaryDirectory, policy,
                    (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()), redactor, Optional.empty());
            if (codex.isPresent()) {
                try (ClaudePermissionBroker.CodeGraphRegistration registration = codex.orElseThrow()) {
                    assertThat(list(broker, registration.token()).at("/result/tools").findValuesAsText("name")).doesNotContain(PullRequestCiBridge.TOOL_NAME);
                }
            }
        }
        try (ClaudePermissionBroker.Registration helper = broker.register(
                "task-1", temporaryDirectory, temporaryDirectory, temporaryDirectory, AgentPermissionPolicy.AUTO, true,
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()), redactor, Optional.empty())) {
            assertThat(helper.pullRequestCi()).isEmpty();
        }
        ReviewBridge review = new ReviewBridge(ImmutableList.of(new ReviewTool(
                "review_context", "Read review context", ImmutableMap.of("type", "object"), arguments -> "review")), "Review only");
        ClaudePermissionBroker reviewBroker = broker(reader, taskId -> Optional.of(review));
        try (ClaudePermissionBroker.CodeGraphRegistration registration = reviewBroker.registerTools(
                "task-1", temporaryDirectory, AgentPermissionPolicy.AUTO,
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()), redactor, Optional.empty()).orElseThrow();
                ClaudePermissionBroker.Registration claude = reviewBroker.register(
                        "task-1", temporaryDirectory, temporaryDirectory, temporaryDirectory, AgentPermissionPolicy.AUTO, false,
                        (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()), redactor, Optional.empty())) {
            assertThat(list(reviewBroker, registration.token()).at("/result/tools").findValuesAsText("name")).containsExactly("review_context");
            assertThat(claude.pullRequestCi()).isEmpty();
        }
    }

    @Test
    void boundsAndRedactsCiReadFailures()
            throws Exception
    {
        ClaudePermissionBroker broker = broker(taskId -> Optional.of(new PullRequestCiBridge(jobId -> {
            throw new IllegalStateException("known-secret " + "x".repeat(PullRequestCiBridge.MAXIMUM_OUTPUT_LENGTH));
        }, PullRequestCiBridge.INSTRUCTION)), ReviewBridgeResolver.NONE);
        try (ClaudePermissionBroker.CodeGraphRegistration registration = broker.registerTools(
                "task-1", temporaryDirectory, AgentPermissionPolicy.AUTO,
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()), redactor, Optional.empty()).orElseThrow()) {
            JsonNode response = call(broker, registration.token(), "{}");
            assertThat(response.at("/result/isError").booleanValue()).isTrue();
            assertThat(text(response)).hasSize(PullRequestCiBridge.MAXIMUM_OUTPUT_LENGTH)
                    .contains("[REDACTED]").doesNotContain("known-secret").endsWith("[Output truncated by ByteQuay]");
        }
    }

    private ClaudePermissionBroker broker(PullRequestCiBridgeResolver ci, ReviewBridgeResolver review)
    {
        return new ClaudePermissionBroker(() -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"), objectMapper,
                (taskId, query) -> "memory", review, DevelopmentResultBridgeResolver.NONE, DraftPullRequestPublisher.NONE, LocalReviewBridgeResolver.NONE, ci);
    }

    private JsonNode list(ClaudePermissionBroker broker, String token)
            throws Exception
    {
        return broker.handle(token, objectMapper.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                """)).body().orElseThrow();
    }

    private JsonNode call(ClaudePermissionBroker broker, String token, String arguments)
            throws Exception
    {
        return broker.handle(token, objectMapper.readTree("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"read_pull_request_ci","arguments":%s}}
                """.formatted(arguments))).body().orElseThrow();
    }

    private static String text(JsonNode response)
    {
        return response.at("/result/content/0/text").asText();
    }
}
