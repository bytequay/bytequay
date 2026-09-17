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

import com.bytequay.agent.cli.development.DevelopmentResultTool;
import com.bytequay.agent.cli.process.SecretRedactor;
import com.bytequay.agent.spi.AgentApprovalHandler;
import com.bytequay.agent.spi.AgentCommandPolicy;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.agent.spi.CiDescriptionProposal;
import com.bytequay.agent.spi.CodeGraphBridge;
import com.bytequay.agent.spi.DevelopmentResultBridge;
import com.bytequay.agent.spi.DevelopmentResultBridgeResolver;
import com.bytequay.agent.spi.DevelopmentResultReport;
import com.bytequay.agent.spi.DraftPullRequestPublisher;
import com.bytequay.agent.spi.IssueContextBridge;
import com.bytequay.agent.spi.IssueContextBridgeResolver;
import com.bytequay.agent.spi.LocalReviewBridge;
import com.bytequay.agent.spi.LocalReviewBridgeResolver;
import com.bytequay.agent.spi.ProviderApprovalDecision;
import com.bytequay.agent.spi.ProviderApprovalRequest;
import com.bytequay.agent.spi.PullRequestCiBridge;
import com.bytequay.agent.spi.PullRequestCiBridgeResolver;
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
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/** Serves ByteQuay's task-scoped MCP tools. */
public final class PermissionBroker
{
    private static final Duration APPROVAL_TIMEOUT = Duration.ofMinutes(5);
    private static final int MAX_DETAIL_CHARACTERS = 16 * 1024;
    private static final int MAXIMUM_TITLE_CHARACTERS = 256;
    private static final int MAXIMUM_BODY_CHARACTERS = 60_000;
    private static final Set<String> PUBLISH_ARGUMENTS = ImmutableSet.of("title", "body");
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
    private final DraftPullRequestPublisher draftPullRequests;
    private final LocalReviewBridgeResolver localReviews;
    private final PullRequestCiBridgeResolver pullRequestChecks;
    private final IssueContextBridgeResolver issueContexts;
    private final Map<String, PermissionContext> contexts = new ConcurrentHashMap<>();

    public PermissionBroker(
            Supplier<URI> endpoint,
            ObjectMapper objectMapper,
            CiMemoryReader ciMemoryReader)
    {
        this(endpoint, objectMapper, ciMemoryReader, ReviewBridgeResolver.NONE);
    }

    public PermissionBroker(
            Supplier<URI> endpoint,
            ObjectMapper objectMapper,
            CiMemoryReader ciMemoryReader,
            ReviewBridgeResolver reviews)
    {
        this(endpoint, objectMapper, ciMemoryReader, reviews, DevelopmentResultBridgeResolver.NONE);
    }

    public PermissionBroker(
            Supplier<URI> endpoint,
            ObjectMapper objectMapper,
            CiMemoryReader ciMemoryReader,
            ReviewBridgeResolver reviews,
            DevelopmentResultBridgeResolver developments)
    {
        this(endpoint, objectMapper, ciMemoryReader, reviews, developments, DraftPullRequestPublisher.NONE);
    }

    public PermissionBroker(
            Supplier<URI> endpoint,
            ObjectMapper objectMapper,
            CiMemoryReader ciMemoryReader,
            ReviewBridgeResolver reviews,
            DevelopmentResultBridgeResolver developments,
            DraftPullRequestPublisher draftPullRequests)
    {
        this(endpoint, objectMapper, ciMemoryReader, reviews, developments, draftPullRequests, LocalReviewBridgeResolver.NONE);
    }

    public PermissionBroker(
            Supplier<URI> endpoint,
            ObjectMapper objectMapper,
            CiMemoryReader ciMemoryReader,
            ReviewBridgeResolver reviews,
            DevelopmentResultBridgeResolver developments,
            DraftPullRequestPublisher draftPullRequests,
            LocalReviewBridgeResolver localReviews)
    {
        this(endpoint, objectMapper, ciMemoryReader, reviews, developments, draftPullRequests, localReviews, PullRequestCiBridgeResolver.NONE);
    }

    public PermissionBroker(
            Supplier<URI> endpoint,
            ObjectMapper objectMapper,
            CiMemoryReader ciMemoryReader,
            ReviewBridgeResolver reviews,
            DevelopmentResultBridgeResolver developments,
            DraftPullRequestPublisher draftPullRequests,
            LocalReviewBridgeResolver localReviews,
            PullRequestCiBridgeResolver pullRequestChecks)
    {
        this(endpoint, objectMapper, ciMemoryReader, reviews, developments, draftPullRequests, localReviews, pullRequestChecks, IssueContextBridgeResolver.NONE);
    }

    public PermissionBroker(
            Supplier<URI> endpoint,
            ObjectMapper objectMapper,
            CiMemoryReader ciMemoryReader,
            ReviewBridgeResolver reviews,
            DevelopmentResultBridgeResolver developments,
            DraftPullRequestPublisher draftPullRequests,
            LocalReviewBridgeResolver localReviews,
            PullRequestCiBridgeResolver pullRequestChecks,
            IssueContextBridgeResolver issueContexts)
    {
        this.endpoint = requireNonNull(endpoint, "endpoint is null");
        this.objectMapper = requireNonNull(objectMapper, "objectMapper is null")
                .copy()
                .registerModule(new Jdk8Module())
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
        this.ciMemoryReader = requireNonNull(ciMemoryReader, "ciMemoryReader is null");
        this.reviews = requireNonNull(reviews, "reviews is null");
        this.developments = requireNonNull(developments, "developments is null");
        this.draftPullRequests = requireNonNull(draftPullRequests, "draftPullRequests is null");
        this.localReviews = requireNonNull(localReviews, "localReviews is null");
        this.pullRequestChecks = requireNonNull(pullRequestChecks, "pullRequestChecks is null");
        this.issueContexts = requireNonNull(issueContexts, "issueContexts is null");
    }

    McpConfigRegistration register(
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

    McpConfigRegistration register(
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
        return register(taskId, worktree, runtimeDirectory, providerDirectory, permissionPolicy, textOnly, approvals, redactor, codeGraph, ImmutableMap.of());
    }

    public McpConfigRegistration register(
            String taskId,
            Path worktree,
            Path runtimeDirectory,
            Path providerDirectory,
            AgentPermissionPolicy permissionPolicy,
            boolean textOnly,
            AgentApprovalHandler approvals,
            SecretRedactor redactor,
            Optional<CodeGraphBridge> codeGraph,
            Map<String, String> environment)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(worktree, "worktree is null");
        requireNonNull(runtimeDirectory, "runtimeDirectory is null");
        requireNonNull(providerDirectory, "providerDirectory is null");
        requireNonNull(permissionPolicy, "permissionPolicy is null");
        requireNonNull(approvals, "approvals is null");
        requireNonNull(redactor, "redactor is null");
        requireNonNull(environment, "environment is null");
        String token = token();
        Path configFile = providerDirectory.resolve("bytequay-mcp.json");
        Path settingsFile = providerDirectory.resolve("sandbox-settings.json");
        requireNonNull(codeGraph, "codeGraph is null");
        PermissionContext context = resolveContext(taskId, worktree, permissionPolicy, approvals, redactor.withSecret(token), codeGraph, textOnly, true);
        contexts.put(token, context);
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
            writeOwnerOnly(settingsFile, sandboxSettings(permissionPolicy, textOnly, context.review().isPresent(), runtimeDirectory.toRealPath(), environment));
            List<String> toolNames = tools(context).stream()
                    .map(ToolDefinition::name)
                    .filter(name -> !name.equals("permission_prompt"))
                    .collect(toImmutableList());
            return new McpConfigRegistration(
                    configFile, settingsFile, token, toolNames, codeGraph, context.review(), context.development(), context.localReview(), context.pullRequestCi(),
                    context.issueContext(), () -> {
                        contexts.remove(token);
                        delete(configFile);
                        delete(settingsFile);
                    });
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
        String token = token();
        PermissionContext context = resolveContext(taskId, worktree, permissionPolicy, approvals, redactor.withSecret(token), codeGraph, false, false);
        // A policy-only review still needs registration to select the provider's review execution profile.
        if (context.review().isEmpty() && tools(context).isEmpty()) {
            return Optional.empty();
        }
        contexts.put(token, context);
        return Optional.of(new CodeGraphRegistration(
                token, requireNonNull(endpoint.get(), "endpoint URI is null"), codeGraph,
                context.review(), context.development(), context.localReview(), context.pullRequestCi(), context.issueContext(), () -> contexts.remove(token)));
    }

    private PermissionContext resolveContext(
            String taskId,
            Path worktree,
            AgentPermissionPolicy permissionPolicy,
            AgentApprovalHandler approvals,
            SecretRedactor redactor,
            Optional<CodeGraphBridge> codeGraph,
            boolean textOnly,
            boolean permissionTools)
    {
        Optional<ReviewBridge> review = reviews.resolve(taskId);
        Optional<DevelopmentResultBridge> development = textOnly || review.isPresent() ? Optional.empty() : developments.resolve(taskId);
        Optional<LocalReviewBridge> localReview = textOnly ? Optional.empty() : localReviews.resolve(taskId);
        Optional<PullRequestCiBridge> pullRequestCi = !textOnly && (permissionPolicy == AgentPermissionPolicy.CI_AUTOFIX
                || permissionPolicy.allowsGitHubCommands(false, review.isPresent()))
                ? pullRequestChecks.resolve(taskId) : Optional.empty();
        Optional<IssueContextBridge> issueContext = textOnly ? Optional.empty() : issueContexts.resolve(taskId);
        return new PermissionContext(
                taskId, worktree, permissionPolicy, approvals, redactor, permissionTools, codeGraph, review, development, localReview, pullRequestCi, issueContext);
    }

    /** Combines the fixed capabilities' guidance for the session's instruction channel. */
    static Optional<String> managedInstruction(
            Optional<CodeGraphBridge> codeGraph, Optional<ReviewBridge> review, Optional<DevelopmentResultBridge> development,
            Optional<LocalReviewBridge> localReview, Optional<PullRequestCiBridge> pullRequestCi, Optional<IssueContextBridge> issueContext)
    {
        String joined = Stream.of(
                        review.map(ReviewBridge::instruction),
                        codeGraph.map(CodeGraphBridge::instruction),
                        development.map(DevelopmentResultBridge::instruction),
                        localReview.map(LocalReviewBridge::instruction),
                        pullRequestCi.map(PullRequestCiBridge::instruction),
                        issueContext.map(IssueContextBridge::instruction))
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
            case IssueContextBridge.TOOL_NAME -> readIssueContext(request.id(), params.arguments(), context);
            case DevelopmentResultBridge.TOOL_NAME -> developmentResult(request.id(), params.arguments(), context);
            case DevelopmentResultBridge.PUBLISH_TOOL_NAME -> createDraftPullRequest(request.id(), params.arguments(), context);
            case CiDescriptionProposal.TOOL_NAME -> proposeCiDescription(request.id(), params.arguments(), context);
            case PullRequestCiBridge.RERUN_TOOL_NAME -> requestCiRerun(request.id(), params.arguments(), context);
            case PullRequestCiBridge.TOOL_NAME, PullRequestCiBridge.RAW_TOOL_NAME -> pullRequestCi(request.id(), params.name(), params.arguments(), context);
            case LocalReviewBridge.LIST_TOOL, LocalReviewBridge.REPLY_TOOL, LocalReviewBridge.RESOLVE_TOOL -> localReviewTool(request.id(), params, context);
            default -> reviewTool(request.id(), params, context);
        };
    }

    private McpHttpResponse readIssueContext(JsonNode id, JsonNode arguments, PermissionContext context)
    {
        if (context.issueContext().isEmpty()) {
            return error(id, -32602, "Unknown tool");
        }
        if (!arguments.isObject() || !arguments.isEmpty()) {
            return error(id, -32602, "read_issue_context accepts no arguments");
        }
        try {
            IssueContextBridge bridge = issueContexts.resolve(context.taskId())
                    .orElseThrow(() -> new IllegalStateException("Issue context is no longer available for this task"));
            String text = context.redactor().redact(bridge.read().get());
            return response(id, new ToolResult(ImmutableList.of(new TextContent("text", text)), false));
        }
        catch (RuntimeException failure) {
            String reason = Optional.ofNullable(failure.getMessage()).orElse(failure.getClass().getSimpleName());
            return response(id, new ToolResult(
                    ImmutableList.of(new TextContent("text", "Unable to read local issue context: " + context.redactor().redact(reason))), true));
        }
    }

    private McpHttpResponse proposeCiDescription(JsonNode id, JsonNode arguments, PermissionContext context)
    {
        var proposer = context.pullRequestCi().flatMap(PullRequestCiBridge::proposeDescription);
        if (context.permissionPolicy() != AgentPermissionPolicy.CI_AUTOFIX || proposer.isEmpty()) {
            return error(id, -32602, "Unknown tool");
        }
        CiDescriptionProposal proposal;
        try {
            checkArgument(arguments != null && arguments.isObject() && arguments.size() == 3, "Only jobId, expectedBody and body may be supplied");
            checkArgument(arguments.path("jobId").isIntegralNumber() && arguments.path("jobId").canConvertToLong(), "jobId must be a positive integer");
            checkArgument(arguments.path("expectedBody").isTextual() && arguments.path("body").isTextual(), "expectedBody and body must be strings");
            proposal = new CiDescriptionProposal(arguments.path("jobId").longValue(), arguments.path("expectedBody").textValue(), arguments.path("body").textValue());
        }
        catch (IllegalArgumentException failure) {
            return error(id, -32602, failure.getMessage());
        }
        try {
            String text = proposer.orElseThrow().apply(proposal);
            return response(id, new ToolResult(ImmutableList.of(new TextContent("text", context.redactor().redact(text))), false));
        }
        catch (RuntimeException failure) {
            return response(id, new ToolResult(ImmutableList.of(new TextContent("text",
                    "Description proposal was not accepted. Read current CI and description before trying again.")), true));
        }
    }

    private McpHttpResponse requestCiRerun(JsonNode id, JsonNode arguments, PermissionContext context)
    {
        var requester = context.pullRequestCi().flatMap(PullRequestCiBridge::requestRerun);
        if (context.permissionPolicy() != AgentPermissionPolicy.CI_AUTOFIX || requester.isEmpty()) {
            return error(id, -32602, "Unknown tool");
        }
        try {
            checkArgument(arguments != null && arguments.isObject() && arguments.size() == 2, "Only jobId and reason may be supplied");
            checkArgument(arguments.path("jobId").isIntegralNumber() && arguments.path("jobId").canConvertToLong()
                    && arguments.path("jobId").longValue() > 0, "jobId must be a positive integer");
            checkArgument(arguments.path("reason").isTextual() && !arguments.path("reason").textValue().isBlank()
                    && arguments.path("reason").textValue().length() <= 4000, "reason must contain 1 to 4000 characters");
        }
        catch (IllegalArgumentException failure) {
            return error(id, -32602, failure.getMessage());
        }
        try {
            String text = requester.orElseThrow().apply(arguments.path("jobId").longValue(), arguments.path("reason").textValue());
            return response(id, new ToolResult(ImmutableList.of(new TextContent("text", context.redactor().redact(text))), false));
        }
        catch (RuntimeException failure) {
            return response(id, new ToolResult(ImmutableList.of(new TextContent("text", "Rerun request was not accepted. Read current CI before trying again.")), true));
        }
    }

    private McpHttpResponse pullRequestCi(JsonNode id, String tool, JsonNode arguments, PermissionContext context)
    {
        if (context.pullRequestCi().isEmpty()) {
            return error(id, -32602, "Unknown tool");
        }
        Optional<Long> jobId = Optional.empty();
        String logId = "";
        long offset = 0;
        boolean raw = tool.equals(PullRequestCiBridge.RAW_TOOL_NAME);
        try {
            checkArgument(arguments != null && arguments.isObject(), "arguments must be an object");
            if (raw) {
                checkArgument(arguments.has("logId") && arguments.size() == (arguments.has("offset") ? 2 : 1), "Only logId and offset may be supplied");
                checkArgument(arguments.path("logId").isTextual() && arguments.path("logId").asText().matches("[0-9a-f]{64}"), "logId must be a returned raw-log identifier");
                logId = arguments.path("logId").asText();
                if (arguments.has("offset")) {
                    JsonNode selected = arguments.get("offset");
                    checkArgument(selected.isIntegralNumber() && selected.canConvertToLong() && selected.longValue() >= 0, "offset must be a nonnegative integer");
                    offset = selected.longValue();
                }
            }
            else {
                checkArgument(arguments.isEmpty() || arguments.size() == 1 && arguments.has("jobId"), "Only jobId may be supplied");
            }
            if (!raw && arguments.has("jobId")) {
                JsonNode selected = arguments.get("jobId");
                checkArgument(selected.isIntegralNumber() && selected.canConvertToLong() && selected.longValue() > 0, "jobId must be a positive integer");
                jobId = Optional.of(selected.longValue());
            }
        }
        catch (IllegalArgumentException failure) {
            return error(id, -32602, failure.getMessage());
        }
        String text;
        boolean failed = false;
        try {
            PullRequestCiBridge bridge = pullRequestChecks.resolve(context.taskId())
                    .orElseThrow(() -> new IllegalStateException("Pull-request CI is unavailable for this task"));
            text = raw ? bridge.readRaw().apply(logId, offset) : bridge.read().apply(jobId);
        }
        catch (RuntimeException failure) {
            text = "Unable to read pull-request CI: " + Optional.ofNullable(failure.getMessage()).orElse(failure.getClass().getSimpleName());
            failed = true;
        }
        text = context.redactor().redact(text);
        if (text.length() > PullRequestCiBridge.MAXIMUM_OUTPUT_LENGTH) {
            text = "CI evidence exceeded the tool-response limit; no partial log was returned.";
            failed = true;
        }
        return response(id, new ToolResult(ImmutableList.of(new TextContent("text", text)), failed));
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
            DevelopmentResultReport report = DevelopmentResultTool.parse(arguments, bridge.orElseThrow());
            String receipt = DevelopmentResultTool.receipt(bridge.orElseThrow(), DevelopmentResultTool.redact(report, context.redactor()));
            // This is a candidate only: the native provider stream must prove current root ownership.
            return response(id, new ToolResult(ImmutableList.of(new TextContent("text", receipt)), false));
        }
        catch (IllegalArgumentException failure) {
            return error(id, -32602, "Invalid report_development_result arguments");
        }
    }

    /**
     * Publishes the task's verified revision as a draft pull request.
     *
     * <p>The caller supplies only a title and body. The destination, the exact revision, and the
     * lease all come from ByteQuay's own publication follow-up, so a session cannot choose where
     * its work lands, and a call made outside that follow-up is refused. A refusal is tool text
     * the agent can report, not a dropped connection.
     */
    private McpHttpResponse createDraftPullRequest(JsonNode id, JsonNode arguments, PermissionContext context)
    {
        if (context.development().isEmpty()) {
            return error(id, -32602, "Draft pull request publication is unavailable for this task");
        }
        String title;
        String body;
        try {
            checkArgument(arguments != null && arguments.isObject(), "arguments must be an object");
            arguments.fieldNames().forEachRemaining(name ->
                    checkArgument(PUBLISH_ARGUMENTS.contains(name), "unknown argument: %s", name));
            title = requireText(arguments, "title", MAXIMUM_TITLE_CHARACTERS);
            body = requireText(arguments, "body", MAXIMUM_BODY_CHARACTERS);
        }
        catch (IllegalArgumentException failure) {
            return error(id, -32602, "Invalid " + DevelopmentResultBridge.PUBLISH_TOOL_NAME + " arguments");
        }
        try {
            String result = draftPullRequests.publish(context.taskId(), title, body);
            return response(id, new ToolResult(ImmutableList.of(new TextContent("text", context.redactor().redact(result))), false));
        }
        catch (RuntimeException failure) {
            String detail = Optional.ofNullable(failure.getMessage()).orElseGet(() -> failure.getClass().getSimpleName());
            return response(id, new ToolResult(
                    ImmutableList.of(new TextContent("text", context.redactor().redact("Publication was refused: " + detail))), true));
        }
    }

    private static String requireText(JsonNode arguments, String field, int maximumCharacters)
    {
        JsonNode value = arguments.get(field);
        checkArgument(value != null && value.isTextual(), "%s must be a string", field);
        String text = value.asText();
        checkArgument(text.length() <= maximumCharacters, "%s is too long", field);
        checkArgument(!field.equals("title") || !text.isBlank(), "title is blank");
        return text;
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
        context.issueContext().ifPresent(ignored -> tools.add(new ToolDefinition(
                IssueContextBridge.TOOL_NAME,
                "Read the accepted issue snapshot and locally cached source observations for this task; missing or stale context is marked. Read-only; no remote fetch",
                ImmutableMap.of("type", "object", "properties", ImmutableMap.of(), "additionalProperties", false))));
        context.pullRequestCi().ifPresent(ignored -> {
            tools.add(new ToolDefinition(
                    PullRequestCiBridge.TOOL_NAME,
                    "Read current CI checks, or supply a failed jobId to cache the full raw log and extract error blocks; no matches returns a 32 KiB tail and a raw logId",
                    ImmutableMap.of(
                            "type", "object",
                            "properties", ImmutableMap.of("jobId", ImmutableMap.of("type", "integer", "minimum", 1, "maximum", Long.MAX_VALUE)),
                            "additionalProperties", false)));
            tools.add(new ToolDefinition(
                    PullRequestCiBridge.RAW_TOOL_NAME,
                    "Read the cached complete credential-redacted raw CI log. Use logId from the error log and follow nextOffset until complete; no download occurs",
                    ImmutableMap.of(
                            "type", "object",
                            "properties", ImmutableMap.of(
                                    "logId", ImmutableMap.of("type", "string", "pattern", "^[0-9a-f]{64}$"),
                                    "offset", ImmutableMap.of("type", "integer", "minimum", 0, "maximum", Long.MAX_VALUE)),
                            "required", ImmutableList.of("logId"),
                            "additionalProperties", false)));
        });
        if (context.permissionPolicy() == AgentPermissionPolicy.CI_AUTOFIX && context.pullRequestCi().flatMap(PullRequestCiBridge::proposeDescription).isPresent()) {
            tools.add(new ToolDefinition(CiDescriptionProposal.TOOL_NAME,
                    "Propose a minimal PR-description repair for a current failed job. Read the complete description first. " +
                            "ByteQuay applies it after the turn and verifies a new check",
                    ImmutableMap.of("type", "object", "properties", ImmutableMap.of(
                            "jobId", ImmutableMap.of("type", "integer", "minimum", 1, "maximum", Long.MAX_VALUE),
                            "expectedBody", ImmutableMap.of("type", "string", "maxLength", 60_000),
                            "body", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", 60_000)),
                            "required", ImmutableList.of("jobId", "expectedBody", "body"), "additionalProperties", false)));
        }
        if (context.permissionPolicy() == AgentPermissionPolicy.CI_AUTOFIX && context.pullRequestCi().flatMap(PullRequestCiBridge::requestRerun).isPresent()) {
            tools.add(new ToolDefinition(PullRequestCiBridge.RERUN_TOOL_NAME,
                    "Request one failed-job rerun only after diagnosing evidence of a flaky test or transient infrastructure failure. " +
                            "Give the current failed jobId and an evidence-based reason; repair deterministic failures instead",
                    ImmutableMap.of("type", "object", "properties", ImmutableMap.of(
                            "jobId", ImmutableMap.of("type", "integer", "minimum", 1, "maximum", Long.MAX_VALUE),
                            "reason", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", 4000)),
                            "required", ImmutableList.of("jobId", "reason"), "additionalProperties", false)));
        }
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
        context.development().ifPresent(bridge -> tools.add(new ToolDefinition(
                DevelopmentResultBridge.TOOL_NAME,
                "Report current root development readiness; the user's accepted automation settings control delivery",
                DevelopmentResultTool.schema(bridge))));
        context.development().ifPresent(ignored -> tools.add(new ToolDefinition(
                DevelopmentResultBridge.PUBLISH_TOOL_NAME,
                "Publish this task's verified revision as a draft pull request; ByteQuay owns the push and the GitHub write",
                ImmutableMap.of(
                        "type", "object",
                        "properties", ImmutableMap.of(
                                "title", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", MAXIMUM_TITLE_CHARACTERS),
                                "body", ImmutableMap.of("type", "string", "maxLength", MAXIMUM_BODY_CHARACTERS)),
                        "required", ImmutableList.of("title", "body"),
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
            Path runtimeDirectory,
            Map<String, String> environment)
            throws IOException
    {
        boolean gitHubCommands = permissionPolicy.allowsGitHubCommands(textOnly, reviewSession);
        ImmutableList.Builder<String> deny = ImmutableList.builder();
        ImmutableList.Builder<String> denyRead = ImmutableList.builder();
        if (!gitHubCommands) {
            deny.add("Bash(git credential)", "Bash(git credential:*)", "Bash(git push:*)", "Bash(gh:*)",
                    "Read(~/.config/gh/**)", "Read(~/.git-credentials)", "Read(~/.netrc)", "Read(~/.ssh/**)");
            denyRead.add("~/.config/gh", "~/.git-credentials", "~/.netrc", "~/.ssh");
            // Custom CLI config locations must not evade the restricted session's credential boundary.
            Optional.ofNullable(environment.get("XDG_CONFIG_HOME")).filter(value -> !value.isBlank())
                    .map(value -> Path.of(value).resolve("gh").toString()).ifPresent(denyRead::add);
            Optional.ofNullable(environment.get("GH_CONFIG_DIR")).filter(value -> !value.isBlank()).ifPresent(denyRead::add);
        }
        if (reviewSession) {
            // This task's MCP token does not distinguish a root agent from a child caller.
            // Skills and workflows can start children; SendMessage can resume earlier children.
            deny.add("Agent", "Task", "Skill", "Workflow", "SendMessage");
            // Native approval must preserve the review boundary while allowing local experiment edits.
            for (String command : ImmutableList.of("commit", "commit-tree", "update-ref", "merge", "rebase", "cherry-pick", "revert", "am", "switch", "pull")) {
                deny.add("Bash(git " + command + ")", "Bash(git " + command + ":*)");
            }
        }
        ImmutableMap.Builder<String, Object> sandbox = ImmutableMap.builder();
        sandbox.put("enabled", true)
                .put("failIfUnavailable", true)
                .put("autoAllowBashIfSandboxed", !gitHubCommands && !reviewSession && permissionPolicy != AgentPermissionPolicy.CHERRY_PICK)
                // Auto mode grants an unsandboxed command without asking, so the agent could
                // waive its own confinement for any path. The sandbox is the containment boundary
                // and nothing may opt out of it.
                .put("allowUnsandboxedCommands", false)
                .put("excludedCommands", ImmutableList.of())
                .put("filesystem", ImmutableMap.of(
                        // The sandbox otherwise permits only the worktree and TMPDIR, so a build
                        // cannot populate its dependency cache.
                        // ponytail: add a root here when a task toolchain needs one.
                        // Only Maven's repository and wrapper, never ~/.m2/settings.xml, which names the repository for every build and would
                        // let one session choose where the next session's Maven writes.
                        "allowWrite", ImmutableList.of("~/.m2/repository", "~/.m2/wrapper", "~/.gradle", "~/.npm", "/var/tmp"),
                        "denyRead", denyRead.build(),
                        // The task runtime directory holds the CI logs, the event log, and this
                        // settings file. The agent reads its attachments from there and must not
                        // be able to rewrite the evidence its own work is judged on.
                        "denyWrite", ImmutableList.of(runtimeDirectory.toString())));
        if (permissionPolicy.unattended() || textOnly) {
            deny.add("WebFetch", "WebSearch");
            sandbox.put("network", ImmutableMap.of("allowedDomains", ImmutableList.of()));
        }
        else if (gitHubCommands) {
            // An omitted domain list is empty, so even reviewed commands need a separate proxy approval.
            // Development egress is governed by complete-command review, including the shared remote-write rules.
            ImmutableMap.Builder<String, Object> network = ImmutableMap.<String, Object>builder()
                    .put("allowedDomains", ImmutableList.of("*"));
            Optional.ofNullable(environment.get("SSH_AUTH_SOCK")).filter(value -> !value.isBlank())
                    .ifPresent(socket -> network.put("allowUnixSockets", ImmutableList.of(socket)));
            sandbox.put("network", network.buildOrThrow());
        }
        else if (reviewSession) {
            // Review tests may start localhost servers; outbound hosts still need native approval.
            sandbox.put("network", ImmutableMap.of("allowedDomains", ImmutableList.of(), "allowLocalBinding", true));
        }
        ImmutableMap.Builder<String, Object> settings = ImmutableMap.<String, Object>builder()
                .put("autoMemoryEnabled", false)
                .put("permissions", ImmutableMap.of("deny", deny.build()))
                .put("sandbox", sandbox.buildOrThrow());
        if (gitHubCommands) {
            // Native allow rules must not skip review, and native exceptions must not waive ByteQuay's authorization scope.
            settings.put("autoMode", ImmutableMap.of(
                    "classifyAllShell", true,
                    "hard_deny", ImmutableList.<String>builder().add("$defaults").addAll(AgentCommandPolicy.restrictions()).build()));
        }
        else if (reviewSession) {
            settings.put("autoMode", ImmutableMap.of(
                    "classifyAllShell", true,
                    "environment", ImmutableList.of("$defaults", AgentCommandPolicy.reviewInstructions()),
                    "hard_deny", ImmutableList.<String>builder().add("$defaults").addAll(AgentCommandPolicy.reviewRestrictions()).build()));
        }
        return objectMapper.writeValueAsString(settings.buildOrThrow());
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
            Optional<LocalReviewBridge> localReview,
            Optional<PullRequestCiBridge> pullRequestCi,
            Optional<IssueContextBridge> issueContext)
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
            requireNonNull(pullRequestCi, "pullRequestCi is null");
            requireNonNull(issueContext, "issueContext is null");
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
