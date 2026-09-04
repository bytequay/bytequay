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
import com.bytequay.agent.spi.CodeGraphBridge;
import com.bytequay.agent.spi.ProviderApprovalDecision;
import com.bytequay.agent.spi.ProviderApprovalRequest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TestClaudePermissionBroker
{
    private final ObjectMapper objectMapper = new ObjectMapper();

    @TempDir
    private Path temporaryDirectory;

    @Test
    void servesTheCodeGraphToolOnlyWhenBridged()
            throws Exception
    {
        ClaudePermissionBroker broker = new ClaudePermissionBroker(
                () -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"),
                objectMapper,
                (taskId, query) -> "memory");
        SecretRedactor redactor = SecretRedactor.fromEnvironment(ImmutableMap.of("ANTHROPIC_API_KEY", "known-secret"));
        ClaudePermissionBroker.Registration bridged = broker.register(
                "task-1",
                temporaryDirectory,
                temporaryDirectory,
                temporaryDirectory,
                AgentPermissionPolicy.AUTO,
                false,
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()),
                redactor,
                Optional.of(new CodeGraphBridge(query -> "graph for " + query + " known-secret", "use the graph")));
        ClaudePermissionBroker.Registration plain = broker.register(
                "task-2",
                temporaryDirectory,
                temporaryDirectory,
                temporaryDirectory,
                AgentPermissionPolicy.AUTO,
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()),
                redactor);

        JsonNode listed = broker.handle(bridged.token(), objectMapper.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                """)).body().orElseThrow();
        assertThat(listed.at("/result/tools")).hasSize(2);
        assertThat(listed.at("/result/tools/1/name").asText()).isEqualTo("codegraph_explore");
        assertThat(listed.at("/result/tools/1/inputSchema/required/0").asText()).isEqualTo("query");

        JsonNode called = broker.handle(bridged.token(), objectMapper.readTree("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"codegraph_explore","arguments":{"query":"who calls cleanup"}}}
                """)).body().orElseThrow();
        assertThat(called.at("/result/content/0/text").asText()).isEqualTo("graph for who calls cleanup [REDACTED]");

        for (String arguments : ImmutableList.of("{}", "{\"query\":\"  \"}", "{\"query\":\"x\",\"path\":\"/\"}", "{\"query\":5}")) {
            JsonNode rejected = broker.handle(bridged.token(), objectMapper.readTree(
                    "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"codegraph_explore\",\"arguments\":" + arguments + "}}")).body().orElseThrow();
            assertThat(rejected.at("/error/code").asInt()).as(arguments).isEqualTo(-32602);
        }

        JsonNode unbridged = broker.handle(plain.token(), objectMapper.readTree("""
                {"jsonrpc":"2.0","id":4,"method":"tools/list","params":{}}
                """)).body().orElseThrow();
        assertThat(unbridged.at("/result/tools")).hasSize(1);
        JsonNode denied = broker.handle(plain.token(), objectMapper.readTree("""
                {"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"codegraph_explore","arguments":{"query":"x"}}}
                """)).body().orElseThrow();
        assertThat(denied.at("/error/code").asInt()).isEqualTo(-32602);
        bridged.close();
        plain.close();
    }

    @Test
    void codeGraphOnlyRegistrationsHideThePermissionTools()
            throws Exception
    {
        ClaudePermissionBroker broker = new ClaudePermissionBroker(
                () -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"),
                objectMapper,
                (taskId, query) -> "memory");
        ClaudePermissionBroker.CodeGraphRegistration registration = broker.registerCodeGraph(
                "task-3",
                temporaryDirectory,
                AgentPermissionPolicy.CI_AUTOFIX,
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()),
                SecretRedactor.fromEnvironment(ImmutableMap.of()),
                new CodeGraphBridge(query -> "graph for " + query, "use the graph"));
        assertThat(registration.endpoint()).isEqualTo(URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"));

        JsonNode listed = broker.handle(registration.token(), objectMapper.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                """)).body().orElseThrow();
        assertThat(listed.at("/result/tools")).hasSize(1);
        assertThat(listed.at("/result/tools/0/name").asText()).isEqualTo("codegraph_explore");
        JsonNode called = broker.handle(registration.token(), objectMapper.readTree("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"codegraph_explore","arguments":{"query":"routes"}}}
                """)).body().orElseThrow();
        assertThat(called.at("/result/content/0/text").asText()).isEqualTo("graph for routes");
        JsonNode hidden = broker.handle(registration.token(), objectMapper.readTree("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"permission_prompt","arguments":{"tool_name":"Bash","input":{}}}}
                """)).body().orElseThrow();
        assertThat(hidden.at("/error/code").asInt()).isEqualTo(-32602);

        registration.close();
        assertThat(broker.handle(registration.token(), objectMapper.createObjectNode()).status()).isEqualTo(401);
    }

    @Test
    void writesOwnerOnlyConfigAndBridgesTypedApprovalRequests()
            throws Exception
    {
        ClaudePermissionBroker broker = new ClaudePermissionBroker(
                () -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"),
                objectMapper,
                (taskId, query) -> "memory");
        AtomicReference<ProviderApprovalRequest> approval = new AtomicReference<>();
        ClaudePermissionBroker.Registration registration = broker.register(
                "task-1",
                temporaryDirectory,
                temporaryDirectory,
                temporaryDirectory,
                AgentPermissionPolicy.AUTO,
                (request, timeout) -> {
                    approval.set(request);
                    return CompletableFuture.completedFuture(ProviderApprovalDecision.allow(Optional.empty()));
                },
                SecretRedactor.fromEnvironment(ImmutableMap.of("ANTHROPIC_API_KEY", "known-secret")));

        JsonNode config = objectMapper.readTree(Files.readString(registration.configFile()));
        String authorization = config.at("/mcpServers/bytequay/headers/Authorization").asText();
        String token = authorization.substring("Bearer ".length());
        assertThat(PosixFilePermissions.toString(Files.getPosixFilePermissions(registration.configFile())))
                .isEqualTo("rw-------");

        ClaudePermissionBroker.McpHttpResponse initialized = broker.handle(token, objectMapper.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"initialize",
                "params":{"protocolVersion":"2025-03-26","capabilities":{},
                "clientInfo":{"name":"claude-code","version":"2.1.233"}}}
                """));
        assertThat(initialized.status()).isEqualTo(200);
        assertThat(initialized.body().orElseThrow().at("/result/protocolVersion").asText())
                .isEqualTo("2025-03-26");

        ClaudePermissionBroker.McpHttpResponse listed = broker.handle(token, objectMapper.readTree("""
                {"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}}
                """));
        assertThat(listed.body().orElseThrow().at("/result/tools/0/name").asText())
                .isEqualTo("permission_prompt");
        assertThat(listed.body().orElseThrow().at("/result/tools")).hasSize(1);

        ClaudePermissionBroker.McpHttpResponse called = broker.handle(token, objectMapper.readTree("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
                "name":"permission_prompt","arguments":{"tool_name":"Bash",
                "input":{"command":"echo known-secret"}}}}
                """));
        JsonNode permission = objectMapper.readTree(
                called.body().orElseThrow().at("/result/content/0/text").asText());
        assertThat(permission.get("behavior").asText()).isEqualTo("allow");
        assertThat(approval.get().kind()).isEqualTo(ProviderApprovalRequest.Kind.COMMAND);
        assertThat(approval.get().detail()).contains("[REDACTED]").doesNotContain("known-secret");

        assertThat(broker.handle("wrong", objectMapper.createObjectNode()).status()).isEqualTo(401);
        registration.close();
        assertThat(registration.configFile()).doesNotExist();
        assertThat(broker.handle(token, objectMapper.createObjectNode()).status()).isEqualTo(401);
    }

    @Test
    void enforcesCiPolicyAndInjectsTaskIdentityIntoMemoryReads()
            throws Exception
    {
        AtomicReference<String> readTask = new AtomicReference<>();
        AtomicReference<Optional<String>> readQuery = new AtomicReference<>();
        ClaudePermissionBroker broker = new ClaudePermissionBroker(
                () -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"),
                objectMapper,
                (taskId, query) -> {
                    readTask.set(taskId);
                    readQuery.set(query);
                    return query.filter("missing"::equals).isPresent()
                            ? "CI memory is unavailable"
                            : "bounded memory";
                });
        Path worktree = Files.createDirectories(temporaryDirectory.resolve("worktree"));
        Path provider = temporaryDirectory.resolve("provider");
        ClaudePermissionBroker.Registration registration = broker.register(
                "task-ci",
                worktree,
                temporaryDirectory,
                provider,
                AgentPermissionPolicy.CI_AUTOFIX,
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.deny()),
                SecretRedactor.fromEnvironment(ImmutableMap.of()));
        String token = token(registration);

        JsonNode settings = objectMapper.readTree(Files.readString(registration.settingsFile()));
        assertThat(settings.at("/sandbox/enabled").asBoolean()).isTrue();
        assertThat(settings.at("/sandbox/autoAllowBashIfSandboxed").asBoolean()).isTrue();
        assertThat(settings.at("/sandbox/failIfUnavailable").asBoolean()).isTrue();
        assertThat(settings.at("/sandbox/allowUnsandboxedCommands").asBoolean()).isFalse();
        assertThat(settings.at("/sandbox/network/allowedDomains")).isEmpty();
        assertThat(settings.at("/sandbox/filesystem/denyRead").toString()).contains(".config/gh");
        assertThat(settings.at("/permissions/deny").toString())
                .contains("WebFetch", "Bash(git credential:*)", "Bash(git push:*)", "Read(~/.config/gh/**)");

        ClaudePermissionBroker.McpHttpResponse listed = broker.handle(token, objectMapper.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                """));
        assertThat(listed.body().orElseThrow().at("/result/tools")).hasSize(2);
        assertThat(listed.body().orElseThrow().at("/result/tools/1/name").asText())
                .isEqualTo("read_ci_memory");

        String longQuery = "compile failure ".repeat(20);
        ClaudePermissionBroker.McpHttpResponse read = broker.handle(token, objectMapper.readTree("""
                {"jsonrpc":"2.0","id":2,"method":"tools/call","params":{
                "name":"read_ci_memory","arguments":{"query":"%s"}}}
                """.formatted(longQuery)));
        assertThat(read.body().orElseThrow().at("/result/content/0/text").asText()).isEqualTo("bounded memory");
        assertThat(readTask.get()).isEqualTo("task-ci");
        assertThat(readQuery.get().orElseThrow()).hasSize(200);

        ClaudePermissionBroker.McpHttpResponse unavailable = broker.handle(token, objectMapper.readTree("""
                {"jsonrpc":"2.0","id":20,"method":"tools/call","params":{
                "name":"read_ci_memory","arguments":{"query":"missing"}}}
                """));
        assertThat(unavailable.body().orElseThrow().at("/result/content/0/text").asText())
                .isEqualTo("CI memory is unavailable");

        ClaudePermissionBroker.McpHttpResponse crossTask = broker.handle(token, objectMapper.readTree("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
                "name":"read_ci_memory","arguments":{"taskId":"another-task"}}}
                """));
        assertThat(crossTask.body().orElseThrow().at("/error/code").asInt()).isEqualTo(-32602);

        assertThat(permission(broker, token, "Edit", "file_path", worktree.resolve("src/Main.java").toString()))
                .isEqualTo("allow");
        assertThat(permission(broker, token, "Write", "file_path", temporaryDirectory.resolve("outside").toString()))
                .isEqualTo("deny");
        assertThat(permission(broker, token, "Bash", "command", "git push origin HEAD"))
                .isEqualTo("deny");

        Path settingsFile = registration.settingsFile();
        registration.close();
        assertThat(registration.configFile()).doesNotExist();
        assertThat(settingsFile).doesNotExist();
        assertThat(broker.handle(token, objectMapper.createObjectNode()).status()).isEqualTo(401);
    }

    @Test
    void sandboxesCherryPickWithoutExposingCiMemory()
            throws Exception
    {
        ClaudePermissionBroker broker = new ClaudePermissionBroker(
                () -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"),
                objectMapper,
                (taskId, query) -> "memory must not reach cherry-pick");
        Path worktree = Files.createDirectories(temporaryDirectory.resolve("cherry-pick-worktree"));
        ClaudePermissionBroker.Registration registration = broker.register(
                "task-cherry-pick",
                worktree,
                temporaryDirectory,
                temporaryDirectory.resolve("cherry-pick-provider"),
                AgentPermissionPolicy.CHERRY_PICK,
                (request, timeout) -> CompletableFuture.completedFuture(ProviderApprovalDecision.allow(Optional.empty())),
                SecretRedactor.fromEnvironment(ImmutableMap.of()));
        String token = token(registration);

        JsonNode settings = objectMapper.readTree(Files.readString(registration.settingsFile()));
        assertThat(settings.at("/sandbox/enabled").asBoolean()).isTrue();
        assertThat(settings.at("/sandbox/autoAllowBashIfSandboxed").asBoolean()).isFalse();
        assertThat(settings.at("/sandbox/allowUnsandboxedCommands").asBoolean()).isFalse();
        assertThat(settings.at("/sandbox/network/allowedDomains")).isEmpty();
        assertThat(settings.at("/permissions/deny").toString())
                .contains("WebFetch", "Bash(git push:*)", "Bash(gh:*)", "Read(~/.ssh/**)");

        ClaudePermissionBroker.McpHttpResponse listed = broker.handle(token, objectMapper.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                """));
        assertThat(listed.body().orElseThrow().at("/result/tools")).hasSize(1);
        assertThat(listed.body().orElseThrow().at("/result/tools/0/name").asText()).isEqualTo("permission_prompt");

        ClaudePermissionBroker.McpHttpResponse memory = broker.handle(token, objectMapper.readTree("""
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
    }

    @Test
    void sandboxesAutoTasksWhileStillEscalatingToTheUser()
            throws Exception
    {
        ClaudePermissionBroker broker = new ClaudePermissionBroker(
                () -> URI.create("http://127.0.0.1:9000/internal/agent/claude/mcp"),
                objectMapper,
                (taskId, query) -> "memory must not reach auto");
        Path worktree = Files.createDirectories(temporaryDirectory.resolve("auto-worktree"));
        AtomicReference<ProviderApprovalRequest> approval = new AtomicReference<>();
        ClaudePermissionBroker.Registration registration = broker.register(
                "task-auto",
                worktree,
                temporaryDirectory,
                temporaryDirectory.resolve("auto-provider"),
                AgentPermissionPolicy.AUTO,
                (request, timeout) -> {
                    approval.set(request);
                    return CompletableFuture.completedFuture(ProviderApprovalDecision.deny());
                },
                SecretRedactor.fromEnvironment(ImmutableMap.of()));
        String token = token(registration);

        JsonNode settings = objectMapper.readTree(Files.readString(registration.settingsFile()));
        assertThat(settings.at("/sandbox/enabled").asBoolean()).isTrue();
        assertThat(settings.at("/sandbox/autoAllowBashIfSandboxed").asBoolean()).isTrue();
        assertThat(settings.at("/sandbox/allowUnsandboxedCommands").asBoolean()).isTrue();
        assertThat(settings.at("/sandbox/network").isMissingNode()).isTrue();
        assertThat(settings.at("/sandbox/filesystem/denyRead").toString()).contains(".ssh");
        assertThat(settings.at("/sandbox/filesystem/denyWrite").toString())
                .contains(temporaryDirectory.toRealPath().toString());
        assertThat(settings.at("/permissions/deny").toString())
                .contains("Bash(git push:*)", "Bash(gh:*)", "Read(~/.ssh/**)")
                .doesNotContain("WebFetch", "WebSearch");

        ClaudePermissionBroker.McpHttpResponse listed = broker.handle(token, objectMapper.readTree("""
                {"jsonrpc":"2.0","id":1,"method":"tools/list","params":{}}
                """));
        assertThat(listed.body().orElseThrow().at("/result/tools")).hasSize(1);

        // An attended policy escalates past the sandbox to the user instead of deciding from a rule.
        assertThat(permission(broker, token, "Write", "file_path", temporaryDirectory.resolve("outside.txt").toString()))
                .isEqualTo("deny");
        assertThat(approval.get().kind()).isEqualTo(ProviderApprovalRequest.Kind.FILE_CHANGE);
        registration.close();
    }

    private String permission(
            ClaudePermissionBroker broker,
            String token,
            String toolName,
            String field,
            String value)
            throws Exception
    {
        ObjectNode arguments = objectMapper.createObjectNode().put("tool_name", toolName);
        arguments.set("input", objectMapper.createObjectNode().put(field, value));
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

    private String token(ClaudePermissionBroker.Registration registration)
            throws Exception
    {
        JsonNode config = objectMapper.readTree(Files.readString(registration.configFile()));
        return config.at("/mcpServers/bytequay/headers/Authorization").asText().substring("Bearer ".length());
    }
}
