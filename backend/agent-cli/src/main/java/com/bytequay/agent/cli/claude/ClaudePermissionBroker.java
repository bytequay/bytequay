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
import com.bytequay.agent.spi.AgentApprovalHandler;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.agent.spi.ProviderApprovalDecision;
import com.bytequay.agent.spi.ProviderApprovalRequest;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import static java.util.Objects.requireNonNull;

/** Serves ByteQuay's task-scoped Claude MCP tools. */
public final class ClaudePermissionBroker
{
    private static final Duration APPROVAL_TIMEOUT = Duration.ofMinutes(5);
    private static final int MAX_DETAIL_CHARACTERS = 16 * 1024;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final Pattern CHERRY_PICK_FIXUP = Pattern.compile(
            "^git\\s+commit\\s+(?:(?:--allow-empty\\s+--fixup=)|(?:--fixup=))([0-9a-f]{40}|[0-9a-f]{64})(?:\\s+--allow-empty)?$");
    private static final Pattern CHERRY_PICK_AMEND = Pattern.compile(
            "^git\\s+commit\\s+--amend\\s+--no-edit$");
    private static final Pattern HISTORY_MUTATION = Pattern.compile(
            "(?m)\\bgit\\b[^\\n;&|]*\\b(?:commit|cherry-pick|rebase|reset|merge|switch|checkout|branch|tag|push|fetch|pull|update-ref|restore)\\b");

    private final Supplier<URI> endpoint;
    private final ObjectMapper objectMapper;
    private final CiMemoryReader ciMemoryReader;
    private final Map<String, PermissionContext> contexts = new ConcurrentHashMap<>();

    public ClaudePermissionBroker(
            Supplier<URI> endpoint,
            ObjectMapper objectMapper,
            CiMemoryReader ciMemoryReader)
    {
        this.endpoint = requireNonNull(endpoint, "endpoint is null");
        this.objectMapper = requireNonNull(objectMapper, "objectMapper is null")
                .copy()
                .registerModule(new Jdk8Module())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.ciMemoryReader = requireNonNull(ciMemoryReader, "ciMemoryReader is null");
    }

    Registration register(
            String taskId,
            Path worktree,
            Path runtimeDirectory,
            Path providerDirectory,
            AgentPermissionPolicy permissionPolicy,
            AgentApprovalHandler approvals,
            SecretRedactor redactor)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(worktree, "worktree is null");
        requireNonNull(runtimeDirectory, "runtimeDirectory is null");
        requireNonNull(providerDirectory, "providerDirectory is null");
        requireNonNull(permissionPolicy, "permissionPolicy is null");
        requireNonNull(approvals, "approvals is null");
        requireNonNull(redactor, "redactor is null");
        String token = token();
        Path configFile = providerDirectory.resolve("bytequay-mcp.json");
        Path settingsFile = providerDirectory.resolve("sandbox-settings.json");
        contexts.put(token, new PermissionContext(taskId, worktree, permissionPolicy, approvals, redactor));
        try {
            Files.createDirectories(providerDirectory);
            String json = objectMapper.writeValueAsString(new McpConfiguration(ImmutableMap.of(
                    "bytequay",
                    new McpServer(
                            "http",
                            requireNonNull(endpoint.get(), "endpoint URI is null").toString(),
                            ImmutableMap.of("Authorization", "Bearer " + token)))));
            writeOwnerOnly(configFile, json);
            writeOwnerOnly(settingsFile, sandboxSettings(permissionPolicy, runtimeDirectory.toRealPath()));
            return new Registration(configFile, settingsFile, token);
        }
        catch (IOException e) {
            contexts.remove(token);
            delete(configFile);
            delete(settingsFile);
            throw new AgentUnavailableException("Unable to write Claude MCP configuration", e);
        }
    }

    /**
     * Handles one authenticated JSON-RPC request from Claude's MCP client.
     *
     * @param bearerToken task capability token from the HTTP Authorization header
     * @param body JSON-RPC request body
     * @return HTTP status and optional JSON response body
     */
    public McpHttpResponse handle(String bearerToken, JsonNode body)
    {
        requireNonNull(bearerToken, "bearerToken is null");
        requireNonNull(body, "body is null");
        PermissionContext context = contexts.get(bearerToken);
        if (context == null) {
            return new McpHttpResponse(401, Optional.empty());
        }
        try {
            McpRequest request = objectMapper.treeToValue(body, McpRequest.class);
            return switch (request.method()) {
                case "initialize" -> response(request.id(), new InitializeResult(
                        protocolVersion(request.params()),
                        new ServerCapabilities(new ToolCapabilities(false)),
                        new ServerInfo("bytequay", "1")));
                case "notifications/initialized" -> new McpHttpResponse(202, Optional.empty());
                case "ping" -> response(request.id(), ImmutableMap.of());
                case "tools/list" -> response(request.id(), new ToolsResult(tools(context)));
                case "tools/call" -> callTool(request, context);
                default -> error(request.id(), -32601, "Method not found");
            };
        }
        catch (JsonProcessingException | IllegalArgumentException e) {
            return error(null, -32600, "Invalid request");
        }
    }

    private McpHttpResponse callTool(McpRequest request, PermissionContext context)
            throws JsonProcessingException
    {
        ToolCallParams params = objectMapper.treeToValue(request.params(), ToolCallParams.class);
        return switch (params.name()) {
            case "permission_prompt" -> permission(request.id(), params.arguments(), context);
            case "read_ci_memory" -> readCiMemory(request.id(), params.arguments(), context);
            default -> error(request.id(), -32602, "Unknown tool");
        };
    }

    private McpHttpResponse permission(JsonNode id, JsonNode input, PermissionContext context)
            throws JsonProcessingException
    {
        PermissionArguments arguments = objectMapper.treeToValue(input, PermissionArguments.class);
        if (context.permissionPolicy().unattended()) {
            PermissionResult decision = sandboxedPermission(
                    arguments, context.worktree(), context.permissionPolicy());
            return response(id, new ToolResult(
                    ImmutableList.of(new TextContent("text", objectMapper.writeValueAsString(decision))),
                    false));
        }
        String detail = bounded(context.redactor().redact(objectMapper.writeValueAsString(arguments.input())));
        ProviderApprovalRequest approval = new ProviderApprovalRequest(
                UUID.randomUUID().toString(),
                kind(arguments.toolName()),
                "Allow Claude " + arguments.toolName(),
                detail,
                ImmutableList.of(arguments.toolName()),
                Instant.now());
        ProviderApprovalDecision decision;
        try {
            decision = context.approvals()
                    .request(approval, APPROVAL_TIMEOUT)
                    .toCompletableFuture()
                    .get(APPROVAL_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            decision = ProviderApprovalDecision.deny();
        }
        catch (ExecutionException | TimeoutException e) {
            decision = ProviderApprovalDecision.deny();
        }

        PermissionResult result = decision.decision() == ProviderApprovalDecision.Decision.ALLOW
                ? new PermissionResult("allow", Optional.of(arguments.input()), Optional.empty())
                : new PermissionResult("deny", Optional.empty(), Optional.of("Denied by user"));
        String text = objectMapper.writeValueAsString(result);
        return response(id, new ToolResult(ImmutableList.of(new TextContent("text", text)), false));
    }

    private McpHttpResponse readCiMemory(JsonNode id, JsonNode arguments, PermissionContext context)
    {
        if (context.permissionPolicy() != AgentPermissionPolicy.CI_AUTOFIX || !validMemoryArguments(arguments)) {
            return error(id, -32602, "Invalid read_ci_memory arguments");
        }
        Optional<String> query = Optional.ofNullable(arguments.get("query"))
                .filter(node -> !node.isNull())
                .map(JsonNode::asText)
                .map(String::trim)
                .map(value -> value.length() <= 200 ? value : value.substring(0, 200))
                .filter(value -> !value.isEmpty());
        String markdown = ciMemoryReader.read(context.taskId(), query);
        return response(id, new ToolResult(ImmutableList.of(new TextContent("text", markdown)), false));
    }

    private McpHttpResponse response(JsonNode id, Object result)
    {
        return new McpHttpResponse(200, Optional.of(objectMapper.valueToTree(new McpResponse("2.0", id, result))));
    }

    private McpHttpResponse error(JsonNode id, int code, String message)
    {
        return new McpHttpResponse(
                200,
                Optional.of(objectMapper.valueToTree(new McpErrorResponse(
                        "2.0",
                        id,
                        new McpError(code, message)))));
    }

    private String protocolVersion(JsonNode params)
            throws JsonProcessingException
    {
        return objectMapper.treeToValue(params, InitializeParams.class).protocolVersion();
    }

    private static ToolDefinition permissionTool()
    {
        return new ToolDefinition(
                "permission_prompt",
                "Ask the ByteQuay user to approve a Claude tool call",
                ImmutableMap.of(
                        "type", "object",
                        "properties", ImmutableMap.of(
                                "tool_name", ImmutableMap.of("type", "string"),
                                "input", ImmutableMap.of("type", "object")),
                        "required", ImmutableList.of("tool_name", "input"),
                        "additionalProperties", true));
    }

    private static List<ToolDefinition> tools(PermissionContext context)
    {
        ImmutableList.Builder<ToolDefinition> tools = ImmutableList.builder();
        tools.add(permissionTool());
        if (context.permissionPolicy() == AgentPermissionPolicy.CI_AUTOFIX) {
            tools.add(new ToolDefinition(
                    "read_ci_memory",
                    "Read recent or matching CI Autofix history for this task",
                    ImmutableMap.of(
                            "type", "object",
                            "properties", ImmutableMap.of(
                                    "query", ImmutableMap.of("type", "string", "maxLength", 200)),
                            "additionalProperties", false)));
        }
        return tools.build();
    }

    private static PermissionResult sandboxedPermission(
            PermissionArguments arguments,
            Path worktree,
            AgentPermissionPolicy permissionPolicy)
    {
        if (arguments.toolName().equalsIgnoreCase("Edit")
                || arguments.toolName().equalsIgnoreCase("Write")
                || arguments.toolName().equalsIgnoreCase("NotebookEdit")) {
            Optional<Path> requested = requestedPath(arguments.input(), worktree);
            if (requested.isPresent() && requested.orElseThrow().startsWith(worktree.toAbsolutePath().normalize())) {
                return new PermissionResult("allow", Optional.of(arguments.input()), Optional.empty());
            }
        }
        if (arguments.toolName().equalsIgnoreCase("Bash")
                && permissionPolicy == AgentPermissionPolicy.CHERRY_PICK) {
            String command = Optional.ofNullable(arguments.input().get("command"))
                    .filter(JsonNode::isTextual)
                    .map(JsonNode::asText)
                    .map(String::strip)
                    .orElse("");
            if (CHERRY_PICK_FIXUP.matcher(command).matches()
                    || CHERRY_PICK_AMEND.matcher(command).matches()
                    || (!command.isBlank() && !HISTORY_MUTATION.matcher(command).find())) {
                return new PermissionResult("allow", Optional.of(arguments.input()), Optional.empty());
            }
            return new PermissionResult(
                    "deny",
                    Optional.empty(),
                    Optional.of("Cherry-pick tasks allow only the expected fixup-shaped history change"));
        }
        return new PermissionResult(
                "deny",
                Optional.empty(),
                Optional.of("This task permits only sandboxed commands and file edits inside its leased worktree"));
    }

    private static Optional<Path> requestedPath(JsonNode input, Path worktree)
    {
        for (String field : ImmutableList.of("file_path", "notebook_path", "path")) {
            JsonNode value = input.get(field);
            if (value != null && value.isTextual() && !value.asText().isBlank()) {
                try {
                    Path path = Path.of(value.asText());
                    return Optional.of((path.isAbsolute() ? path : worktree.resolve(path)).toAbsolutePath().normalize());
                }
                catch (RuntimeException ignored) {
                    return Optional.empty();
                }
            }
        }
        return Optional.empty();
    }

    private static boolean validMemoryArguments(JsonNode arguments)
    {
        if (!arguments.isObject() || arguments.size() > 1 || (arguments.size() == 1 && !arguments.has("query"))) {
            return false;
        }
        JsonNode query = arguments.get("query");
        return query == null || query.isNull() || query.isTextual();
    }

    private String sandboxSettings(AgentPermissionPolicy permissionPolicy, Path runtimeDirectory)
            throws IOException
    {
        ImmutableList.Builder<String> deny = ImmutableList.builder();
        deny.add(
                "Bash(git credential)",
                "Bash(git credential:*)",
                "Bash(git push:*)",
                "Bash(gh:*)",
                "Read(~/.config/gh/**)",
                "Read(~/.git-credentials)",
                "Read(~/.netrc)",
                "Read(~/.ssh/**)");
        ImmutableMap.Builder<String, Object> sandbox = ImmutableMap.builder();
        sandbox.put("enabled", true)
                .put("failIfUnavailable", true)
                .put("autoAllowBashIfSandboxed", permissionPolicy != AgentPermissionPolicy.CHERRY_PICK)
                .put("allowUnsandboxedCommands", !permissionPolicy.unattended())
                .put("excludedCommands", ImmutableList.of())
                .put("filesystem", ImmutableMap.of(
                        "denyRead", ImmutableList.of(
                                "~/.config/gh",
                                "~/.git-credentials",
                                "~/.netrc",
                                "~/.ssh"),
                        // The task runtime directory holds the CI logs, the event log, and this
                        // settings file. The agent reads its attachments from there and must not
                        // be able to rewrite the evidence its own work is judged on.
                        "denyWrite", ImmutableList.of(runtimeDirectory.toString())));
        if (permissionPolicy.unattended()) {
            deny.add("WebFetch", "WebSearch");
            sandbox.put("network", ImmutableMap.of("allowedDomains", ImmutableList.of()));
        }
        return objectMapper.writeValueAsString(ImmutableMap.of(
                "autoMemoryEnabled", false,
                "permissions", ImmutableMap.of("deny", deny.build()),
                "sandbox", sandbox.buildOrThrow()));
    }

    private static void writeOwnerOnly(Path path, String content)
            throws IOException
    {
        Files.writeString(path, content, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
    }

    private static void delete(Path path)
    {
        try {
            Files.deleteIfExists(path);
        }
        catch (IOException ignored) {
            // Task resource cleanup owns the final deletion retry.
        }
    }

    private static ProviderApprovalRequest.Kind kind(String toolName)
    {
        if (toolName.equalsIgnoreCase("Bash")) {
            return ProviderApprovalRequest.Kind.COMMAND;
        }
        if (toolName.equalsIgnoreCase("Edit") || toolName.equalsIgnoreCase("Write")) {
            return ProviderApprovalRequest.Kind.FILE_CHANGE;
        }
        return ProviderApprovalRequest.Kind.TOOL;
    }

    private static String token()
    {
        byte[] bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String bounded(String text)
    {
        return text.length() <= MAX_DETAIL_CHARACTERS
                ? text
                : text.substring(0, MAX_DETAIL_CHARACTERS);
    }

    final class Registration
            implements AutoCloseable
    {
        private final Path configFile;
        private final Path settingsFile;
        private final String token;

        private Registration(Path configFile, Path settingsFile, String token)
        {
            this.configFile = requireNonNull(configFile, "configFile is null");
            this.settingsFile = requireNonNull(settingsFile, "settingsFile is null");
            this.token = requireNonNull(token, "token is null");
        }

        Path configFile()
        {
            return configFile;
        }

        Path settingsFile()
        {
            return settingsFile;
        }

        @Override
        public void close()
        {
            contexts.remove(token);
            delete(configFile);
            delete(settingsFile);
        }
    }

    /** HTTP response returned to the thin application controller. */
    public record McpHttpResponse(int status, Optional<JsonNode> body)
    {
        public McpHttpResponse
        {
            requireNonNull(body, "body is null");
        }
    }

    private record PermissionContext(
            String taskId,
            Path worktree,
            AgentPermissionPolicy permissionPolicy,
            AgentApprovalHandler approvals,
            SecretRedactor redactor)
    {
        private PermissionContext
        {
            requireNonNull(taskId, "taskId is null");
            requireNonNull(worktree, "worktree is null");
            requireNonNull(permissionPolicy, "permissionPolicy is null");
            requireNonNull(approvals, "approvals is null");
            requireNonNull(redactor, "redactor is null");
        }
    }

    private record McpConfiguration(@JsonProperty("mcpServers") Map<String, McpServer> servers)
    {
        private McpConfiguration
        {
            requireNonNull(servers, "servers is null");
            servers = ImmutableMap.copyOf(servers);
        }
    }

    private record McpServer(String type, String url, Map<String, String> headers)
    {
        private McpServer
        {
            requireNonNull(type, "type is null");
            requireNonNull(url, "url is null");
            requireNonNull(headers, "headers is null");
            headers = ImmutableMap.copyOf(headers);
        }
    }

    private record McpRequest(String jsonrpc, JsonNode id, String method, JsonNode params)
    {
        private McpRequest
        {
            requireNonNull(jsonrpc, "jsonrpc is null");
            requireNonNull(method, "method is null");
        }
    }

    private record InitializeParams(String protocolVersion)
    {
        private InitializeParams
        {
            requireNonNull(protocolVersion, "protocolVersion is null");
        }
    }

    private record InitializeResult(
            String protocolVersion,
            ServerCapabilities capabilities,
            ServerInfo serverInfo) {}

    private record ServerCapabilities(ToolCapabilities tools) {}

    private record ToolCapabilities(boolean listChanged) {}

    private record ServerInfo(String name, String version) {}

    private record ToolsResult(List<ToolDefinition> tools) {}

    private record ToolDefinition(String name, String description, Map<String, Object> inputSchema) {}

    private record ToolCallParams(String name, JsonNode arguments)
    {
        private ToolCallParams
        {
            requireNonNull(name, "name is null");
            requireNonNull(arguments, "arguments is null");
        }
    }

    private record PermissionArguments(
            @JsonProperty("tool_name") String toolName,
            JsonNode input)
    {
        private PermissionArguments
        {
            requireNonNull(toolName, "toolName is null");
            requireNonNull(input, "input is null");
        }
    }

    private record PermissionResult(
            String behavior,
            @JsonProperty("updatedInput") Optional<JsonNode> updatedInput,
            Optional<String> message) {}

    private record ToolResult(List<TextContent> content, @JsonProperty("isError") boolean error) {}

    private record TextContent(String type, String text) {}

    private record McpResponse(String jsonrpc, JsonNode id, Object result) {}

    private record McpErrorResponse(String jsonrpc, JsonNode id, McpError error) {}

    private record McpError(int code, String message) {}

    @FunctionalInterface
    public interface CiMemoryReader
    {
        String read(String taskId, Optional<String> query);
    }
}
