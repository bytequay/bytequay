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

import com.bytequay.agent.cli.development.DevelopmentResultTool;
import com.bytequay.agent.cli.process.SecretRedactor;
import com.bytequay.agent.spi.AgentApprovalHandler;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.agent.spi.CodeGraphBridge;
import com.bytequay.agent.spi.DevelopmentResultBridge;
import com.bytequay.agent.spi.DevelopmentResultBridgeResolver;
import com.bytequay.agent.spi.DevelopmentResultReport;
import com.bytequay.agent.spi.LocalReviewBridge;
import com.bytequay.agent.spi.LocalReviewBridgeResolver;
import com.bytequay.agent.spi.ProviderApprovalDecision;
import com.bytequay.agent.spi.ProviderApprovalRequest;
import com.bytequay.agent.spi.ReviewBridge;
import com.bytequay.agent.spi.ReviewBridgeResolver;
import com.bytequay.agent.spi.ReviewTool;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;

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
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.bytequay.agent.spi.ProviderAgentEventSource.root;
import static com.bytequay.agent.spi.ProviderApprovalRequest.Decision.ALLOW_ONCE;
import static com.bytequay.agent.spi.ProviderApprovalRequest.Decision.DENY;
import static com.google.common.base.Preconditions.checkArgument;
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
    private final ReviewBridgeResolver reviews;
    private final DevelopmentResultBridgeResolver developments;
    private final LocalReviewBridgeResolver localReviews;
    private final Map<String, PermissionContext> contexts = new ConcurrentHashMap<>();

    public ClaudePermissionBroker(
            Supplier<URI> endpoint,
            ObjectMapper objectMapper,
            CiMemoryReader ciMemoryReader)
    {
        this(endpoint, objectMapper, ciMemoryReader, ReviewBridgeResolver.NONE);
    }

    public ClaudePermissionBroker(
            Supplier<URI> endpoint,
            ObjectMapper objectMapper,
            CiMemoryReader ciMemoryReader,
            ReviewBridgeResolver reviews)
    {
        this(endpoint, objectMapper, ciMemoryReader, reviews, DevelopmentResultBridgeResolver.NONE);
    }

    public ClaudePermissionBroker(
            Supplier<URI> endpoint,
            ObjectMapper objectMapper,
            CiMemoryReader ciMemoryReader,
            ReviewBridgeResolver reviews,
            DevelopmentResultBridgeResolver developments)
    {
        this(endpoint, objectMapper, ciMemoryReader, reviews, developments, LocalReviewBridgeResolver.NONE);
    }

    public ClaudePermissionBroker(
            Supplier<URI> endpoint,
            ObjectMapper objectMapper,
            CiMemoryReader ciMemoryReader,
            ReviewBridgeResolver reviews,
            DevelopmentResultBridgeResolver developments,
            LocalReviewBridgeResolver localReviews)
    {
        this.endpoint = requireNonNull(endpoint, "endpoint is null");
        this.objectMapper = requireNonNull(objectMapper, "objectMapper is null")
                .copy()
                .registerModule(new Jdk8Module())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.ciMemoryReader = requireNonNull(ciMemoryReader, "ciMemoryReader is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
        this.developments = requireNonNull(developments, "developments is null");
        this.localReviews = requireNonNull(localReviews, "localReviews is null");
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
        return register(
                taskId,
                worktree,
                runtimeDirectory,
                providerDirectory,
                permissionPolicy,
                false,
                approvals,
                redactor,
                Optional.empty());
    }

    Registration register(
            String taskId,
            Path worktree,
            Path runtimeDirectory,
            Path providerDirectory,
            AgentPermissionPolicy permissionPolicy,
            boolean textOnly,
            AgentApprovalHandler approvals,
            SecretRedactor redactor,
            Optional<CodeGraphBridge> codeGraph)
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
        requireNonNull(codeGraph, "codeGraph is null");
        Optional<ReviewBridge> review = reviews.resolve(taskId);
        Optional<DevelopmentResultBridge> development = textOnly || review.isPresent() ? Optional.empty() : developments.resolve(taskId);
        Optional<LocalReviewBridge> localReview = textOnly || review.isPresent() ? Optional.empty() : localReviews.resolve(taskId);
        contexts.put(token, new PermissionContext(
                taskId,
                worktree,
                permissionPolicy,
                approvals,
                redactor.withSecret(token),
                true,
                codeGraph,
                review,
                development,
                localReview));
        try {
            Files.createDirectories(providerDirectory);
            // Claude's HTTP first-response deadline otherwise expires after 60 seconds, before the approval card.
            String json = objectMapper.writeValueAsString(new McpConfiguration(ImmutableMap.of(
                    "bytequay",
                    new McpServer(
                            "http",
                            requireNonNull(endpoint.get(), "endpoint URI is null").toString(),
                            ImmutableMap.of("Authorization", "Bearer " + token),
                            APPROVAL_TIMEOUT.plusSeconds(30).toMillis()))));
            writeOwnerOnly(configFile, json);
            writeOwnerOnly(settingsFile, sandboxSettings(permissionPolicy, textOnly, review.isPresent(), runtimeDirectory.toRealPath()));
            return new Registration(configFile, settingsFile, token, codeGraph, review, development, localReview);
        }
        catch (IOException e) {
            contexts.remove(token);
            delete(configFile);
            delete(settingsFile);
            throw new AgentUnavailableException("Unable to write Claude MCP configuration", e);
        }
    }

    /**
     * Registers a token for a provider that reaches this endpoint through its own MCP
     * configuration, exposing its CI memory, graph, review, development, or local discussion capability. Empty
     * when the task has no local tools.
     */
    public Optional<CodeGraphRegistration> registerTools(
            String taskId,
            Path worktree,
            AgentPermissionPolicy permissionPolicy,
            AgentApprovalHandler approvals,
            SecretRedactor redactor,
            Optional<CodeGraphBridge> codeGraph)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(worktree, "worktree is null");
        requireNonNull(permissionPolicy, "permissionPolicy is null");
        requireNonNull(approvals, "approvals is null");
        requireNonNull(redactor, "redactor is null");
        requireNonNull(codeGraph, "codeGraph is null");
        Optional<ReviewBridge> review = reviews.resolve(taskId);
        Optional<DevelopmentResultBridge> development = review.isPresent() ? Optional.empty() : developments.resolve(taskId);
        Optional<LocalReviewBridge> localReview = review.isPresent() ? Optional.empty() : localReviews.resolve(taskId);
        if (permissionPolicy != AgentPermissionPolicy.CI_AUTOFIX && codeGraph.isEmpty() && review.isEmpty() && development.isEmpty() && localReview.isEmpty()) {
            return Optional.empty();
        }
        String token = token();
        contexts.put(token, new PermissionContext(taskId, worktree, permissionPolicy, approvals, redactor.withSecret(token), false, codeGraph, review, development, localReview));
        return Optional.of(new CodeGraphRegistration(token, requireNonNull(endpoint.get(), "endpoint URI is null"), codeGraph, review, development, localReview));
    }

    /** Combines the fixed capabilities' guidance for the session's instruction channel. */
    static Optional<String> managedInstruction(
            Optional<CodeGraphBridge> codeGraph, Optional<ReviewBridge> review, Optional<DevelopmentResultBridge> development, Optional<LocalReviewBridge> localReview)
    {
        String joined = Stream.of(
                        review.map(ReviewBridge::instruction),
                        codeGraph.map(CodeGraphBridge::instruction),
                        development.map(DevelopmentResultBridge::instruction),
                        localReview.map(LocalReviewBridge::instruction))
                .flatMap(Optional::stream)
                .collect(Collectors.joining("\n\n"));
        return joined.isBlank() ? Optional.empty() : Optional.of(joined);
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
            case "permission_prompt" -> context.permissionTools() ? permission(request.id(), params.arguments(), context) : error(request.id(), -32602, "Unknown tool");
            case "read_ci_memory" -> readCiMemory(request.id(), params.arguments(), context);
            case CodeGraphBridge.TOOL_NAME -> codeGraphExplore(request.id(), params.arguments(), context);
            case DevelopmentResultBridge.TOOL_NAME -> developmentResult(request.id(), params.arguments(), context);
            case LocalReviewBridge.LIST_TOOL, LocalReviewBridge.REPLY_TOOL, LocalReviewBridge.RESOLVE_TOOL -> localReviewTool(request.id(), params, context);
            default -> reviewTool(request.id(), params, context);
        };
    }

    private McpHttpResponse localReviewTool(JsonNode id, ToolCallParams params, PermissionContext context)
    {
        if (context.localReview().isEmpty()) {
            return error(id, -32602, "Unknown tool");
        }
        Optional<LocalReviewBridge> bridge;
        try {
            bridge = localReviews.resolve(context.taskId());
        }
        catch (RuntimeException failure) {
            return error(id, -32602, "Local review is unavailable for this task");
        }
        if (bridge.isEmpty()) {
            return error(id, -32602, "Local review is unavailable for this task");
        }
        Supplier<String> action;
        try {
            JsonNode arguments = params.arguments();
            checkArgument(arguments != null && arguments.isObject(), "arguments must be an object");
            Set<String> fields = switch (params.name()) {
                case LocalReviewBridge.LIST_TOOL -> arguments.has("commentId") ? ImmutableSet.of("commentId") : ImmutableSet.of();
                case LocalReviewBridge.REPLY_TOOL -> ImmutableSet.of("commentId", "body", "operationKey");
                case LocalReviewBridge.RESOLVE_TOOL -> ImmutableSet.of("commentId", "resolved", "operationKey");
                default -> throw new IllegalArgumentException("Unknown local review tool");
            };
            checkArgument(arguments.size() == fields.size(), "arguments do not match the tool schema");
            fields.forEach(field -> checkArgument(arguments.has(field), "missing argument: %s", field));
            if (params.name().equals(LocalReviewBridge.LIST_TOOL)) {
                Optional<String> commentId = Optional.empty();
                if (arguments.has("commentId")) {
                    checkArgument(arguments.get("commentId").isTextual(), "commentId must be a string");
                    String value = arguments.get("commentId").textValue();
                    checkArgument(!value.isBlank() && value.length() <= LocalReviewBridge.MAXIMUM_ID_LENGTH, "Invalid commentId");
                    commentId = Optional.of(value);
                }
                Optional<String> selectedComment = commentId;
                action = () -> bridge.orElseThrow().list().apply(selectedComment);
            }
            else {
                checkArgument(arguments.path("commentId").isTextual(), "commentId must be a string");
                checkArgument(arguments.path("operationKey").isTextual(), "operationKey must be a string");
                String commentId = arguments.get("commentId").textValue();
                String operationKey = arguments.get("operationKey").textValue();
                if (params.name().equals(LocalReviewBridge.REPLY_TOOL)) {
                    checkArgument(arguments.path("body").isTextual(), "body must be a string");
                    checkArgument(arguments.get("body").textValue().length() <= LocalReviewBridge.MAXIMUM_BODY_LENGTH, "body is too long");
                    LocalReviewBridge.Reply reply = new LocalReviewBridge.Reply(commentId, context.redactor().redact(arguments.get("body").textValue()), operationKey);
                    action = () -> bridge.orElseThrow().reply().apply(reply);
                }
                else {
                    checkArgument(arguments.path("resolved").isBoolean(), "resolved must be a boolean");
                    LocalReviewBridge.Resolution resolution = new LocalReviewBridge.Resolution(commentId, arguments.get("resolved").booleanValue(), operationKey);
                    action = () -> bridge.orElseThrow().resolve().apply(resolution);
                }
            }
        }
        catch (IllegalArgumentException failure) {
            return error(id, -32602, "Invalid " + params.name() + " arguments: " + context.redactor().redact(failure.getMessage()));
        }
        try {
            return response(id, new ToolResult(ImmutableList.of(new TextContent("text", context.redactor().redact(action.get()))), false));
        }
        catch (RuntimeException failure) {
            String reason = Optional.ofNullable(failure.getMessage()).orElse(failure.getClass().getSimpleName());
            return response(id, new ToolResult(ImmutableList.of(new TextContent("text", "Local review operation failed: " + context.redactor().redact(reason))), true));
        }
    }

    private McpHttpResponse developmentResult(JsonNode id, JsonNode arguments, PermissionContext context)
    {
        Optional<DevelopmentResultBridge> bridge = context.development()
                .filter(value -> developments.resolve(context.taskId()).filter(value::equals).isPresent());
        if (bridge.isEmpty()) {
            return error(id, -32602, "Development reporting is unavailable for this turn");
        }
        try {
            DevelopmentResultReport report = DevelopmentResultTool.parse(arguments);
            String receipt = DevelopmentResultTool.receipt(bridge.orElseThrow(), new DevelopmentResultReport(
                    report.readyForDelivery(), report.message().map(context.redactor()::redact)));
            // This is a candidate only: the native provider stream must prove current root ownership.
            return response(id, new ToolResult(ImmutableList.of(new TextContent("text", receipt)), false));
        }
        catch (IllegalArgumentException failure) {
            return error(id, -32602, "Invalid report_development_result arguments");
        }
    }

    /**
     * Runs one review tool for the task the token names. Validation failures come back as tool
     * text the model can repair from; an unexpected failure is an error result, never a thrown
     * exception that would drop the provider's connection.
     */
    private McpHttpResponse reviewTool(JsonNode id, ToolCallParams params, PermissionContext context)
            throws JsonProcessingException
    {
        Optional<ReviewTool> tool = context.review().flatMap(bridge -> bridge.tool(params.name()));
        if (tool.isEmpty()) {
            return error(id, -32602, "Unknown tool");
        }
        JsonNode arguments = params.arguments().isObject() ? params.arguments() : objectMapper.createObjectNode();
        String text;
        boolean failed = false;
        try {
            text = tool.orElseThrow().call().apply(objectMapper.writeValueAsString(arguments));
        }
        catch (RuntimeException failure) {
            text = "The review operation failed: " + Optional.ofNullable(failure.getMessage()).orElse(failure.getClass().getSimpleName());
            failed = true;
        }
        return response(id, new ToolResult(ImmutableList.of(new TextContent("text", context.redactor().redact(text))), failed));
    }

    private McpHttpResponse permission(JsonNode id, JsonNode input, PermissionContext context)
            throws JsonProcessingException
    {
        PermissionArguments arguments = objectMapper.treeToValue(input, PermissionArguments.class);
        Optional<String> refused = Optional.empty();
        if (context.review().isPresent()) {
            if (arguments.toolName().equalsIgnoreCase("Agent")
                    || arguments.toolName().equalsIgnoreCase("Task")
                    || arguments.toolName().equalsIgnoreCase("Skill")
                    || arguments.toolName().equalsIgnoreCase("Workflow")
                    || arguments.toolName().equalsIgnoreCase("SendMessage")) {
                refused = Optional.of("Review sessions cannot start or resume subagents because review tools require the root agent");
            }
            else if (arguments.toolName().equalsIgnoreCase("Bash")) {
                refused = ReviewBridge.deniedReason(Optional.ofNullable(arguments.input().get("command")).filter(JsonNode::isTextual).map(JsonNode::asText).orElse(""));
            }
        }
        if (refused.isPresent()) {
            // Refused before any card exists: review authority is not extended by a tool approval.
            PermissionResult denied = new PermissionResult("deny", Optional.empty(), refused);
            return response(id, new ToolResult(ImmutableList.of(new TextContent("text", objectMapper.writeValueAsString(denied))), false));
        }
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
                ImmutableList.of(ALLOW_ONCE, DENY),
                root(),
                Instant.now());
        ProviderApprovalDecision decision;
        try {
            decision = context.approvals()
                    .request(approval, APPROVAL_TIMEOUT)
                    .toCompletableFuture()
                    .get(APPROVAL_TIMEOUT.plusSeconds(1).toMillis(), TimeUnit.MILLISECONDS);
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            decision = ProviderApprovalDecision.deny();
        }
        catch (ExecutionException e) {
            decision = ProviderApprovalDecision.deny();
        }
        catch (TimeoutException e) {
            decision = ProviderApprovalDecision.expired();
        }

        PermissionResult result = decision.decision() == ProviderApprovalDecision.Decision.ALLOW
                ? new PermissionResult("allow", Optional.of(arguments.input()), Optional.empty())
                : new PermissionResult("deny", Optional.empty(), Optional.of(decision.timedOut() ? "Approval timed out" : "Denied by user"));
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

    private McpHttpResponse codeGraphExplore(JsonNode id, JsonNode arguments, PermissionContext context)
    {
        Optional<String> query = codeGraphQuery(arguments);
        if (context.codeGraph().isEmpty() || query.isEmpty()) {
            return error(id, -32602, "Invalid " + CodeGraphBridge.TOOL_NAME + " arguments");
        }
        String text = context.redactor().redact(context.codeGraph().orElseThrow().explore().apply(query.orElseThrow()));
        return response(id, new ToolResult(ImmutableList.of(new TextContent("text", text)), false));
    }

    /** The one accepted argument shape: an object holding only a non-blank, bounded {@code query} string. */
    private static Optional<String> codeGraphQuery(JsonNode arguments)
    {
        if (arguments == null || !arguments.isObject() || arguments.size() != 1 || !arguments.has("query") || !arguments.get("query").isTextual()) {
            return Optional.empty();
        }
        String query = arguments.get("query").asText().strip();
        return query.isEmpty() || query.length() > CodeGraphBridge.MAXIMUM_QUERY_LENGTH ? Optional.empty() : Optional.of(query);
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

    private static ToolDefinition codeGraphTool()
    {
        return new ToolDefinition(
                CodeGraphBridge.TOOL_NAME,
                "Explore this task's code graph: callers, callees, implementations, routes, dependencies, and cross-file structure "
                        + "for a natural-language query. Returns matching symbols with source and call paths. Navigation help only; verify in current source.",
                ImmutableMap.of(
                        "type", "object",
                        "properties", ImmutableMap.of(
                                "query", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", CodeGraphBridge.MAXIMUM_QUERY_LENGTH)),
                        "required", ImmutableList.of("query"),
                        "additionalProperties", false));
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
        if (context.permissionTools()) {
            tools.add(permissionTool());
        }
        if (context.codeGraph().isPresent()) {
            tools.add(codeGraphTool());
        }
        context.review().ifPresent(review -> review.tools().forEach(tool -> tools.add(new ToolDefinition(tool.name(), tool.description(), tool.inputSchema()))));
        context.localReview().ifPresent(ignored -> {
            Map<String, Object> identifier = ImmutableMap.of("type", "string", "minLength", 1, "maxLength", LocalReviewBridge.MAXIMUM_ID_LENGTH);
            tools.add(new ToolDefinition(
                    LocalReviewBridge.LIST_TOOL,
                    "Read bounded unresolved/recent summaries, or supply commentId for its anchored code and recent discussion; omissions and excerpts are marked",
                    ImmutableMap.of("type", "object", "properties", ImmutableMap.of("commentId", identifier), "additionalProperties", false)));
            tools.add(new ToolDefinition(
                    LocalReviewBridge.REPLY_TOOL,
                    "Reply privately to one returned comment; reuse the operationKey when retrying the same reply",
                    ImmutableMap.of(
                            "type", "object",
                            "properties", ImmutableMap.of(
                                    "commentId", identifier,
                                    "operationKey", identifier,
                                    "body", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", LocalReviewBridge.MAXIMUM_BODY_LENGTH)),
                            "required", ImmutableList.of("commentId", "body", "operationKey"),
                            "additionalProperties", false)));
            tools.add(new ToolDefinition(
                    LocalReviewBridge.RESOLVE_TOOL,
                    "Resolve or reopen one private comment; resolved is explicit and operationKey identifies this exact update",
                    ImmutableMap.of(
                            "type", "object",
                            "properties", ImmutableMap.of("commentId", identifier, "operationKey", identifier, "resolved", ImmutableMap.of("type", "boolean")),
                            "required", ImmutableList.of("commentId", "resolved", "operationKey"),
                            "additionalProperties", false)));
        });
        context.development().ifPresent(ignored -> tools.add(new ToolDefinition(
                DevelopmentResultBridge.TOOL_NAME,
                "Report current root development readiness; the user's accepted automation settings control delivery",
                ImmutableMap.of(
                        "type", "object",
                        "properties", ImmutableMap.of(
                                "readyForDelivery", ImmutableMap.of("type", "boolean"),
                                "message", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", DevelopmentResultReport.MAXIMUM_MESSAGE_LENGTH)),
                        "required", ImmutableList.of("readyForDelivery"),
                        "additionalProperties", false))));
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

    private String sandboxSettings(
            AgentPermissionPolicy permissionPolicy,
            boolean textOnly,
            boolean reviewSession,
            Path runtimeDirectory)
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
        if (reviewSession) {
            // This task's MCP token does not distinguish a root agent from a child caller.
            // Skills and workflows can start children; SendMessage can resume earlier children.
            deny.add("Agent", "Task", "Skill", "Workflow", "SendMessage");
        }
        ImmutableMap.Builder<String, Object> sandbox = ImmutableMap.builder();
        sandbox.put("enabled", true)
                .put("failIfUnavailable", true)
                .put("autoAllowBashIfSandboxed", permissionPolicy != AgentPermissionPolicy.CHERRY_PICK)
                .put("allowUnsandboxedCommands", !permissionPolicy.unattended() && !textOnly)
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
        if (permissionPolicy.unattended() || textOnly) {
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
        private final Optional<CodeGraphBridge> codeGraph;
        private final Optional<ReviewBridge> review;
        private final Optional<DevelopmentResultBridge> development;
        private final Optional<LocalReviewBridge> localReview;

        private Registration(
                Path configFile,
                Path settingsFile,
                String token,
                Optional<CodeGraphBridge> codeGraph,
                Optional<ReviewBridge> review,
                Optional<DevelopmentResultBridge> development,
                Optional<LocalReviewBridge> localReview)
        {
            this.configFile = requireNonNull(configFile, "configFile is null");
            this.settingsFile = requireNonNull(settingsFile, "settingsFile is null");
            this.token = requireNonNull(token, "token is null");
            this.codeGraph = requireNonNull(codeGraph, "codeGraph is null");
            this.review = requireNonNull(review, "review is null");
            this.development = requireNonNull(development, "development is null");
            this.localReview = requireNonNull(localReview, "localReview is null");
        }

        public Optional<DevelopmentResultBridge> development()
        {
            return development;
        }

        Optional<LocalReviewBridge> localReview()
        {
            return localReview;
        }

        /** The review capability this token exposes, resolved from the task when it registered. */
        Optional<ReviewBridge> review()
        {
            return review;
        }

        /** The managed guidance this session's instruction channel carries, if any tool needs one. */
        Optional<String> instruction()
        {
            return managedInstruction(codeGraph, review, development, localReview);
        }

        Path configFile()
        {
            return configFile;
        }

        Path settingsFile()
        {
            return settingsFile;
        }

        String token()
        {
            return token;
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
    /** A task-local tool token and the endpoint it authenticates against; closing forgets the token. */
    public final class CodeGraphRegistration
            implements AutoCloseable
    {
        private final String token;
        private final URI endpoint;
        private final Optional<CodeGraphBridge> codeGraph;
        private final Optional<ReviewBridge> review;
        private final Optional<DevelopmentResultBridge> development;
        private final Optional<LocalReviewBridge> localReview;

        private CodeGraphRegistration(
                String token, URI endpoint, Optional<CodeGraphBridge> codeGraph, Optional<ReviewBridge> review,
                Optional<DevelopmentResultBridge> development, Optional<LocalReviewBridge> localReview)
        {
            this.token = requireNonNull(token, "token is null");
            this.endpoint = requireNonNull(endpoint, "endpoint is null");
            this.codeGraph = requireNonNull(codeGraph, "codeGraph is null");
            this.review = requireNonNull(review, "review is null");
            this.development = requireNonNull(development, "development is null");
            this.localReview = requireNonNull(localReview, "localReview is null");
        }

        public Optional<DevelopmentResultBridge> development()
        {
            return development;
        }

        /** The review capability this token exposes, resolved from the task when it registered. */
        public Optional<ReviewBridge> review()
        {
            return review;
        }

        /** The managed guidance for the launch's instruction channel; empty only for a token without any guidance. */
        public String instruction()
        {
            return managedInstruction(codeGraph, review, development, localReview).orElse("");
        }

        public String token()
        {
            return token;
        }

        public URI endpoint()
        {
            return endpoint;
        }

        @Override
        public void close()
        {
            contexts.remove(token);
        }
    }

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
            SecretRedactor redactor,
            boolean permissionTools,
            Optional<CodeGraphBridge> codeGraph,
            Optional<ReviewBridge> review,
            Optional<DevelopmentResultBridge> development,
            Optional<LocalReviewBridge> localReview)
    {
        private PermissionContext
        {
            requireNonNull(taskId, "taskId is null");
            requireNonNull(worktree, "worktree is null");
            requireNonNull(permissionPolicy, "permissionPolicy is null");
            requireNonNull(approvals, "approvals is null");
            requireNonNull(redactor, "redactor is null");
            requireNonNull(codeGraph, "codeGraph is null");
            requireNonNull(review, "review is null");
            requireNonNull(development, "development is null");
            requireNonNull(localReview, "localReview is null");
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

    private record McpServer(String type, String url, Map<String, String> headers, long timeout)
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
