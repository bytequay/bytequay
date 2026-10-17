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
import com.bytequay.agent.cli.process.AgentPermissionManager;
import com.bytequay.agent.cli.process.BuildCacheRoots;
import com.bytequay.agent.cli.process.GitWorktreeWriteRoots;
import com.bytequay.agent.cli.process.SecretRedactor;
import com.bytequay.agent.spi.AgentApprovalHandler;
import com.bytequay.agent.spi.AgentCommandPolicy;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.AgentPermissionScope;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.bytequay.agent.spi.BridgeResolver;
import com.bytequay.agent.spi.CiDescriptionProposal;
import com.bytequay.agent.spi.CodeQuery;
import com.bytequay.agent.spi.CodeQueryBridge;
import com.bytequay.agent.spi.DevelopmentResultBridge;
import com.bytequay.agent.spi.DevelopmentResultReport;
import com.bytequay.agent.spi.DiscussionContextBridge;
import com.bytequay.agent.spi.DraftPullRequestPublisher;
import com.bytequay.agent.spi.GoogleWorkspaceBridge;
import com.bytequay.agent.spi.IssueContextBridge;
import com.bytequay.agent.spi.LocalReviewBridge;
import com.bytequay.agent.spi.MemoryBridge;
import com.bytequay.agent.spi.ProviderApprovalDecision;
import com.bytequay.agent.spi.ProviderApprovalRequest;
import com.bytequay.agent.spi.PullRequestCiBridge;
import com.bytequay.agent.spi.PullRequestContextBridge;
import com.bytequay.agent.spi.RelatedWorkspaceBridge;
import com.bytequay.agent.spi.ReviewBridge;
import com.bytequay.agent.spi.ReviewTool;
import com.bytequay.agent.spi.SessionAccess;
import com.bytequay.agent.spi.WorkItemBridge;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jdk8.Jdk8Module;
import com.google.common.base.Ascii;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.hash.Hashing;
import com.google.common.io.Resources;
import lombok.Builder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.bytequay.agent.spi.ProviderAgentEventSource.root;
import static com.bytequay.agent.spi.ProviderApprovalRequest.Decision.ALLOW_ONCE;
import static com.bytequay.agent.spi.ProviderApprovalRequest.Decision.DENY;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Comparator.comparing;
import static java.util.Objects.requireNonNull;

/** Serves ByteQuay's task-scoped MCP tools. */
public final class PermissionBroker
{
    public static final String CALL_TOOL = "bytequay_call_tool";
    private static final String LIST_TOOLS = "bytequay_list_tools";
    private static final String GET_TOOL = "bytequay_get_tool";
    private static final String TOOL_DISCOVERY_INSTRUCTION = """
            Discover ByteQuay capabilities with bytequay_list_tools at the start of a turn. Choose the tools relevant
            to the request, then use bytequay_get_tool with name to read each tool's current description and inputSchema,
            including required parameters and allowed values. Use bytequay_call_tool with name and arguments to run it.
            ByteQuay tool names in task instructions refer to this catalog; they need not be directly exposed functions.
            Check the catalog and details before reporting a ByteQuay tool as unavailable.
            Recheck details after resuming; saved tool descriptions can be outdated. Discovery does not grant permission
            or require using every tool. Existing task scope, user authorization and tool-specific rules still apply.
            In Codex, use the unqualified bytequay_call_tool when available; it belongs to the main agent. Exploration
            children use their directly available read tools and report missing capabilities to the main agent.
            """;
    private static final Duration APPROVAL_TIMEOUT = Duration.ofMinutes(5);
    /** Root-only gate for review sessions; see {@code review-child-gate.sh} for what it refuses and why. */
    private static final String CHILD_GATE_SCRIPT = readChildGateScript();
    /** Every call that starts a child, and every ByteQuay tool call, because a child inherits all of them. */
    private static final String CHILD_GATE_MATCHER = "^(Agent|Task|mcp__bytequay__.*)$";
    private static final Pattern SHELL_SAFE_TOOL_NAME = Pattern.compile("[A-Za-z0-9_]+");
    /**
     * Remote writes and credential commands an attended review refuses outright while it reads the network.
     * ponytail: prefix rules catch only the plain command form; {@link ReviewBridge#deniedReason} at the approval
     * boundary and native review of every shell command cover the rest, such as a gh api request with a write method.
     */
    private static final List<String> REVIEW_REMOTE_WRITES = Stream.of(
                    "git push", "git send-email", "git request-pull", "git credential", "gh auth", "gh release", "gh secret", "gh ssh-key", "gh gpg-key",
                    "gh pr comment", "gh pr review", "gh pr merge", "gh pr close", "gh pr reopen", "gh pr edit", "gh pr create", "gh pr ready", "gh pr lock", "gh pr unlock",
                    "gh issue comment", "gh issue close", "gh issue create", "gh issue edit", "gh issue reopen", "gh issue lock", "gh issue unlock", "gh issue delete",
                    "gh repo create", "gh repo delete", "gh repo edit", "gh repo fork", "gh repo sync",
                    "gh label create", "gh label delete", "gh label edit", "gh workflow run", "gh workflow enable", "gh workflow disable")
            .flatMap(command -> Stream.of("Bash(" + command + ")", "Bash(" + command + ":*)"))
            .collect(toImmutableList());
    private static final List<String> CREDENTIAL_FILE_READS = ImmutableList.of("Read(~/.config/gh/**)", "Read(~/.git-credentials)", "Read(~/.netrc)", "Read(~/.ssh/**)");
    private static final int MAX_DETAIL_CHARACTERS = 16 * 1024;
    /** Claude's own cap on one answer; a longer one would make it refuse the whole answered input. */
    private static final int MAX_ANSWER_CHARACTERS = 8 * 1024;
    private static final String QUESTION_TOOL = "AskUserQuestion";
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
    private final BridgeResolver<ReviewBridge> reviews;
    private final BridgeResolver<DevelopmentResultBridge> developments;
    private final DraftPullRequestPublisher draftPullRequests;
    private final BridgeResolver<LocalReviewBridge> localReviews;
    private final BridgeResolver<PullRequestCiBridge> pullRequestChecks;
    private final BridgeResolver<IssueContextBridge> issueContexts;
    private final BridgeResolver<PullRequestContextBridge> pullRequestContexts;
    private final BridgeResolver<MemoryBridge> memories;
    private final BridgeResolver<WorkItemBridge> workItems;
    private final BridgeResolver<GoogleWorkspaceBridge> googleWorkspace;
    private final BridgeResolver<RelatedWorkspaceBridge> relatedWork;
    private final Map<String, PermissionContext> contexts = new ConcurrentHashMap<>();

    public static PermissionBrokerBuilder builder(Supplier<URI> endpoint, ObjectMapper objectMapper, CiMemoryReader ciMemoryReader)
    {
        return new PermissionBrokerBuilder().endpoint(endpoint).objectMapper(objectMapper).ciMemoryReader(ciMemoryReader);
    }

    public static class PermissionBrokerBuilder
    {
        private BridgeResolver<ReviewBridge> reviews = BridgeResolver.none();
        private BridgeResolver<DevelopmentResultBridge> developments = BridgeResolver.none();
        private DraftPullRequestPublisher draftPullRequests = DraftPullRequestPublisher.NONE;
        private BridgeResolver<LocalReviewBridge> localReviews = BridgeResolver.none();
        private BridgeResolver<PullRequestCiBridge> pullRequestChecks = BridgeResolver.none();
        private BridgeResolver<IssueContextBridge> issueContexts = BridgeResolver.none();
        private BridgeResolver<MemoryBridge> memories = BridgeResolver.none();
        private BridgeResolver<RelatedWorkspaceBridge> relatedWork = BridgeResolver.none();
        private BridgeResolver<WorkItemBridge> workItems = BridgeResolver.none();
        private BridgeResolver<GoogleWorkspaceBridge> googleWorkspace = BridgeResolver.none();
        private BridgeResolver<PullRequestContextBridge> pullRequestContexts = BridgeResolver.none();

        private PermissionBrokerBuilder() {}
    }

    @Builder(builderMethodName = "")
    private PermissionBroker(Supplier<URI> endpoint, ObjectMapper objectMapper, CiMemoryReader ciMemoryReader,
            BridgeResolver<ReviewBridge> reviews, BridgeResolver<DevelopmentResultBridge> developments,
            DraftPullRequestPublisher draftPullRequests, BridgeResolver<LocalReviewBridge> localReviews,
            BridgeResolver<PullRequestCiBridge> pullRequestChecks, BridgeResolver<IssueContextBridge> issueContexts,
            BridgeResolver<MemoryBridge> memories, BridgeResolver<RelatedWorkspaceBridge> relatedWork, BridgeResolver<WorkItemBridge> workItems,
            BridgeResolver<GoogleWorkspaceBridge> googleWorkspace, BridgeResolver<PullRequestContextBridge> pullRequestContexts)
    {
        this.pullRequestContexts = requireNonNull(pullRequestContexts, "pullRequestContexts is null");
        this.googleWorkspace = requireNonNull(googleWorkspace, "googleWorkspace is null");
        this.workItems = requireNonNull(workItems, "workItems is null");
        this.relatedWork = requireNonNull(relatedWork, "relatedWork is null");
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
        this.memories = requireNonNull(memories, "memories is null");
    }

    public McpRegistration register(
            String taskId, Path worktree, Path runtimeDirectory, Path providerDirectory, AgentPermissionPolicy permissionPolicy,
            SessionAccess access, AgentApprovalHandler approvals, SecretRedactor redactor, Optional<CodeQueryBridge> codeQueries,
            Map<String, String> environment, Optional<DiscussionContextBridge> discussionContext, Optional<ReviewTool> reviewContext)
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
        Path childGateFile = providerDirectory.resolve("review-child-gate.sh");
        requireNonNull(codeQueries, "codeQueries is null");
        PermissionContext context = resolveContext(
                taskId,
                worktree,
                permissionPolicy,
                approvals,
                redactor.withSecret(token),
                codeQueries,
                access,
                true,
                environment,
                discussionContext,
                reviewContext);
        contexts.put(token, context);
        Optional<Path> childGate = (context.reviewChildren() || context.relatedWork().isPresent()) ? Optional.of(childGateFile) : Optional.empty();
        try {
            Files.createDirectories(providerDirectory);
            URI uri = requireNonNull(endpoint.get(), "endpoint URI is null");
            // Claude's HTTP first-response deadline otherwise expires after 60 seconds, before the approval card.
            String json = objectMapper.writeValueAsString(new McpConfiguration(ImmutableMap.of(
                    "bytequay",
                    new McpServer("http", uri.toString(), ImmutableMap.of("Authorization", "Bearer " + token), APPROVAL_TIMEOUT.plusSeconds(30).toMillis(), true))));
            writeOwnerOnly(configFile, json);
            if (childGate.isPresent()) {
                writeOwnerOnly(childGateFile, childGateScript(context));
                Files.setPosixFilePermissions(childGateFile, PosixFilePermissions.fromString("rwx------"));
            }
            writeOwnerOnly(settingsFile, sandboxSettings(context.scope(), runtimeDirectory.toRealPath(), worktree, environment, childGate));
            List<ToolDefinition> exposed = exposedTools(context);
            List<String> toolNames = exposed.stream()
                    .map(ToolDefinition::name)
                    .filter(name -> !name.equals("permission_prompt"))
                    .collect(toImmutableList());
            return new McpRegistration(
                    token, uri, toolNames, toolsDigest(exposed), managedInstruction(context), context.review(), context.development(),
                    Optional.of(configFile), Optional.of(settingsFile), () -> {
                        contexts.remove(token);
                        delete(configFile);
                        delete(settingsFile);
                        delete(childGateFile);
                    });
        }
        catch (IOException e) {
            contexts.remove(token);
            delete(configFile);
            delete(settingsFile);
            delete(childGateFile);
            throw new AgentUnavailableException("Unable to write Claude MCP configuration", e);
        }
        catch (RuntimeException failure) {
            contexts.remove(token);
            delete(configFile);
            delete(settingsFile);
            delete(childGateFile);
            throw failure;
        }
    }

    /**
     * Registers a token for a provider that reaches this endpoint through its own MCP
     * configuration, exposing its CI memory, graph, review, development, or local discussion capability. Empty
     * when the task has no local tools.
     */

    public Optional<McpRegistration> registerTools(
            String taskId, Path worktree, AgentPermissionPolicy permissionPolicy, AgentApprovalHandler approvals, SecretRedactor redactor,
            Optional<CodeQueryBridge> codeQueries, SessionAccess access, Optional<DiscussionContextBridge> discussionContext, Optional<ReviewTool> reviewContext)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(worktree, "worktree is null");
        requireNonNull(permissionPolicy, "permissionPolicy is null");
        requireNonNull(approvals, "approvals is null");
        requireNonNull(redactor, "redactor is null");
        requireNonNull(codeQueries, "codeQueries is null");
        String token = token();
        PermissionContext context = resolveContext(
                taskId, worktree, permissionPolicy, approvals, redactor.withSecret(token), codeQueries, access, false, ImmutableMap.of(), discussionContext, reviewContext);
        // A policy-only review still needs registration to select the provider's review execution profile.
        if (context.review().isEmpty() && tools(context).isEmpty()) {
            return Optional.empty();
        }
        contexts.put(token, context);
        List<ToolDefinition> exposed = exposedTools(context);
        return Optional.of(new McpRegistration(
                token, requireNonNull(endpoint.get(), "endpoint URI is null"), exposed.stream().map(ToolDefinition::name).collect(toImmutableList()), toolsDigest(exposed),
                managedInstruction(context), context.review(), context.development(),
                Optional.empty(), Optional.empty(), () -> contexts.remove(token)));
    }

    /** Keeps a review child's shared MCP token read-only; the root receives other tools through App Server. */
    public CodexRootTools guardCodexRootTools(McpRegistration registration)
    {
        requireNonNull(registration, "registration is null");
        PermissionContext context = requireNonNull(contexts.get(registration.token()), "review token is not registered");
        checkArgument(context.review().isPresent() || context.relatedWork().isPresent(), "No root-bound tools on this task");
        Set<String> readable = Stream.concat(
                        context.review().stream().flatMap(review -> review.tools().stream()).filter(ReviewTool::childCallable).map(ReviewTool::name),
                        context.codeQueries().stream().map(ignored -> CodeQueryBridge.TOOL_NAME))
                .filter(name -> name.equals("review_get_context") || name.equals(CodeQueryBridge.TOOL_NAME))
                .collect(ImmutableSet.toImmutableSet());
        if (context.review().isPresent()) {
            checkArgument(readable.contains("review_get_context"), "Review children need readable context");
        }
        else {
            readable = tools(context).stream().map(ToolDefinition::name).filter(name -> !name.equals(RelatedWorkspaceBridge.TOOL_NAME)).collect(ImmutableSet.toImmutableSet());
        }
        Set<String> childTools = readable;
        // Memory reads use the shared MCP token; its mutating actions still require a root-bound dynamic call.
        List<String> mcpNames = tools(context).stream().map(ToolDefinition::name)
                .filter(name -> childTools.contains(name) || (context.review().isPresent() && name.equals(MemoryBridge.TOOL_NAME)))
                .collect(toImmutableList());
        mcpNames = ImmutableList.<String>builder().addAll(mcpNames).add(LIST_TOOLS, GET_TOOL).build();
        // Codex persists this schema for the conversation. Concrete tools and their schemas are discovered at call time.
        ToolDefinition dispatcher = callToolDefinition();
        List<Map<String, Object>> rootTools = ImmutableList.of(ImmutableMap.of(
                "type", "function", "name", dispatcher.name(), "description", dispatcher.description(), "inputSchema", dispatcher.inputSchema()));
        CodexRootTools guarded = new CodexRootTools(mcpNames, rootTools);
        context.codexReviewTools().set(guarded);
        return guarded;
    }

    /** Calls one withheld task tool after App Server has proven its caller is the root review turn. */
    public McpHttpResponse callCodexRootTool(String token, String name, JsonNode arguments)
    {
        PermissionContext context = contexts.get(requireNonNull(token, "token is null"));
        if (context == null || !name.equals(CALL_TOOL) || arguments == null || !arguments.isObject()) {
            return error(null, -32602, "Unknown tool or invalid arguments");
        }
        try {
            return callTool(new McpRequest("2.0", objectMapper.valueToTree(1), "tools/call", objectMapper.valueToTree(new ToolCallParams(name, arguments))), context);
        }
        catch (JsonProcessingException | RuntimeException failure) {
            return error(null, -32603, "Task tool failed");
        }
    }

    public record CodexRootTools(List<String> mcpNames, List<Map<String, Object>> rootTools)
    {
        public CodexRootTools
        {
            mcpNames = ImmutableList.copyOf(mcpNames);
            rootTools = ImmutableList.copyOf(rootTools);
        }
    }

    private PermissionContext resolveContext(
            String taskId,
            Path worktree,
            AgentPermissionPolicy permissionPolicy,
            AgentApprovalHandler approvals,
            SecretRedactor redactor,
            Optional<CodeQueryBridge> codeQueries,
            SessionAccess access,
            boolean permissionTools,
            Map<String, String> environment,
            Optional<DiscussionContextBridge> discussionContext, Optional<ReviewTool> reviewContext)
    {
        requireNonNull(reviewContext, "reviewContext is null");
        checkArgument(reviewContext.isEmpty() || access == SessionAccess.READ_ONLY, "Review context requires a read-only session");
        reviewContext.ifPresent(tool -> checkArgument(ReviewTool.SESSION_CONTEXT_TOOLS.contains(tool.name()) && tool.childCallable(), "Only context reads are allowed"));
        requireNonNull(discussionContext, "discussionContext is null");
        checkArgument(discussionContext.isEmpty() || access == SessionAccess.READ_ONLY, "Discussion context requires a read-only session");
        boolean restricted = access.restricted();
        // A restricted session may carry a real task id, so every bridge is withheld by access
        // rather than by the caller having invented an id no bridge can resolve.
        // Only the read-only axis withholds review: a text-only caller invents its own task id, and
        // changing what it resolves is not this axis's business.
        Optional<ReviewBridge> review = access == SessionAccess.READ_ONLY ? Optional.empty() : reviews.resolve(taskId);
        AgentPermissionScope scope = new AgentPermissionScope(permissionPolicy, access, review.isPresent());
        Optional<DevelopmentResultBridge> development = restricted || review.isPresent() ? Optional.empty() : developments.resolve(taskId);
        Optional<LocalReviewBridge> localReview = restricted ? Optional.empty() : localReviews.resolve(taskId);
        Optional<PullRequestCiBridge> pullRequestCi = !restricted && (permissionPolicy == AgentPermissionPolicy.CI_AUTOFIX || scope.githubCommands())
                ? pullRequestChecks.resolve(taskId) : Optional.empty();
        Optional<PullRequestContextBridge> pullRequestContext = restricted ? Optional.empty() : pullRequestContexts.resolve(taskId);
        Optional<IssueContextBridge> issueContext = restricted ? Optional.empty() : issueContexts.resolve(taskId);
        Optional<MemoryBridge> memory = restricted ? Optional.empty() : memories.resolve(taskId);
        Optional<WorkItemBridge> work = restricted ? Optional.empty() : workItems.resolve(taskId);
        Optional<RelatedWorkspaceBridge> related = restricted || review.isPresent() ? Optional.empty() : relatedWork.resolve(taskId);
        // Claude's attended review gate uses this flag. Codex registers no permission tools and
        // gates its children through its own launch-time PreToolUse hook.
        boolean reviewChildren = permissionTools && scope.reviewReads();
        Map<String, String> fileWriteEnvironment = Optional.ofNullable(environment.get("HOME"))
                .map(home -> ImmutableMap.of("HOME", home)).orElse(ImmutableMap.of());
        return new PermissionContext(
                taskId, worktree, scope, fileWriteEnvironment, approvals, redactor, permissionTools,
                codeQueries, discussionContext, reviewContext, review, development, localReview, pullRequestCi, pullRequestContext, issueContext, memory, related, work,
                restricted || review.isPresent() ? Optional.empty() : googleWorkspace.resolve(taskId), reviewChildren, new AtomicReference<>());
    }

    /** Combines the fixed capabilities' guidance for the session's instruction channel. */
    private static Optional<String> managedInstruction(PermissionContext context)
    {
        String joined = Stream.of(
                        tools(context).stream().anyMatch(tool -> !tool.name().equals("permission_prompt"))
                                ? Optional.of(TOOL_DISCOVERY_INSTRUCTION) : Optional.<String>empty(),
                        context.review().map(ReviewBridge::instruction),
                        context.codeQueries().map(CodeQueryBridge::instruction),
                        context.discussionContext().map(ignored -> DiscussionContextBridge.INSTRUCTION),
                        context.development().map(DevelopmentResultBridge::instruction),
                        context.localReview().map(LocalReviewBridge::instruction),
                        context.pullRequestCi().map(PullRequestCiBridge::instruction),
                        context.issueContext().map(IssueContextBridge::instruction),
                        context.pullRequestContext().map(ignored -> PullRequestContextBridge.INSTRUCTION),
                        context.memory().map(ignored -> MemoryBridge.INSTRUCTION),
                        context.workItems().map(ignored -> WorkItemBridge.INSTRUCTION),
                        context.googleWorkspace().map(ignored -> GoogleWorkspaceBridge.INSTRUCTION),
                        context.relatedWork().map(ignored -> RelatedWorkspaceBridge.INSTRUCTION))
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
        CodexRootTools guarded = context.codexReviewTools().get();
        try {
            McpRequest request = objectMapper.treeToValue(body, McpRequest.class);
            return switch (request.method()) {
                case "initialize" -> response(request.id(), new InitializeResult(
                        protocolVersion(request.params()),
                        new ServerCapabilities(new ToolCapabilities(false)),
                        new ServerInfo("bytequay", "1")));
                case "notifications/initialized" -> new McpHttpResponse(202, Optional.empty());
                case "ping" -> response(request.id(), ImmutableMap.of());
                case "tools/list" -> response(request.id(), new ToolsResult(exposedTools(context).stream()
                        .filter(tool -> guarded == null || guarded.mcpNames().contains(tool.name()))
                        .map(tool -> guarded != null && context.review().isPresent() && tool.name().equals(MemoryBridge.TOOL_NAME) ? memoryTool(true) : tool)
                        .collect(toImmutableList())));
                case "tools/call" -> {
                    String name = request.params().path("name").asText("");
                    if (guarded != null && !guarded.mcpNames().contains(name)) {
                        yield error(request.id(), -32602, "Unknown tool");
                    }
                    if (guarded != null && context.review().isPresent() && name.equals(MemoryBridge.TOOL_NAME)
                            && !ImmutableSet.of("SEARCH", "READ").contains(request.params().path("arguments").path("action").asText(""))) {
                        yield error(request.id(), -32602, "Review child memory access permits only SEARCH and READ");
                    }
                    yield callTool(request, context);
                }
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
            case LIST_TOOLS, GET_TOOL -> describeTools(request.id(), params, context);
            case CALL_TOOL -> callDiscoveredTool(request.id(), params.arguments(), context);
            case "permission_prompt" -> context.permissionTools() ? permission(request.id(), params.arguments(), context) : error(request.id(), -32602, "Unknown tool");
            case "read_ci_memory" -> readCiMemory(request.id(), params.arguments(), context);
            case CodeQueryBridge.TOOL_NAME -> codeQueriesExplore(request.id(), params.arguments(), context);
            case DiscussionContextBridge.TOOL_NAME -> readDiscussionContext(request.id(), params.arguments(), context);
            case PullRequestContextBridge.TOOL_NAME -> readPullRequestContext(request.id(), params.arguments(), context);
            case IssueContextBridge.TOOL_NAME -> readIssueContext(request.id(), params.arguments(), context);
            case RelatedWorkspaceBridge.TOOL_NAME -> relatedWork(request.id(), params.arguments(), context);
            case WorkItemBridge.TOOL_NAME -> workItems(request.id(), params.arguments(), context);
            case GoogleWorkspaceBridge.TOOL_NAME -> googleWorkspace(request.id(), params.arguments(), context);
            case MemoryBridge.TOOL_NAME -> memory(request.id(), params.arguments(), context);
            case DevelopmentResultBridge.TOOL_NAME -> developmentResult(request.id(), params.arguments(), context);
            case DevelopmentResultBridge.PUBLISH_TOOL_NAME -> createDraftPullRequest(request.id(), params.arguments(), context);
            case DevelopmentResultBridge.RENAME_BRANCH_TOOL_NAME -> renameTaskBranch(request.id(), params.arguments(), context);
            case CiDescriptionProposal.TOOL_NAME -> proposeCiDescription(request.id(), params.arguments(), context);
            case PullRequestCiBridge.RERUN_TOOL_NAME -> requestCiRerun(request.id(), params.arguments(), context);
            case PullRequestCiBridge.TOOL_NAME, PullRequestCiBridge.RAW_TOOL_NAME -> pullRequestCi(request.id(), params.name(), params.arguments(), context);
            case LocalReviewBridge.LIST_TOOL, LocalReviewBridge.REPLY_TOOL, LocalReviewBridge.RESOLVE_TOOL -> localReviewTool(request.id(), params, context);
            default -> reviewTool(request.id(), params, context);
        };
    }

    private McpHttpResponse describeTools(JsonNode id, ToolCallParams params, PermissionContext context)
            throws JsonProcessingException
    {
        JsonNode arguments = params.arguments();
        boolean list = params.name().equals(LIST_TOOLS);
        if (!arguments.isObject() || (list ? !arguments.isEmpty() : arguments.size() != 1 || !arguments.path("name").isTextual())) {
            return error(id, -32602, list ? "bytequay_list_tools accepts no arguments" : "bytequay_get_tool requires name");
        }
        List<ToolDefinition> available = tools(context).stream().filter(tool -> !tool.name().equals("permission_prompt")).collect(toImmutableList());
        Object result;
        if (list) {
            result = available.stream().map(tool -> ImmutableMap.of("name", tool.name(), "description", tool.description())).collect(toImmutableList());
        }
        else {
            Optional<ToolDefinition> tool = available.stream().filter(value -> value.name().equals(arguments.path("name").asText())).findFirst();
            if (tool.isEmpty()) {
                return error(id, -32602, "Unknown tool in this task. Use bytequay_list_tools to discover available tools");
            }
            result = tool.orElseThrow();
        }
        return response(id, new ToolResult(ImmutableList.of(new TextContent("text", context.redactor().redact(objectMapper.writeValueAsString(result)))), false));
    }

    private McpHttpResponse callDiscoveredTool(JsonNode id, JsonNode arguments, PermissionContext context)
            throws JsonProcessingException
    {
        if (!arguments.isObject() || arguments.size() != 2 || !arguments.path("name").isTextual() || !arguments.path("arguments").isObject()) {
            return error(id, -32602, "bytequay_call_tool requires name and an arguments object. Read bytequay_get_tool for the tool's parameters");
        }
        String name = arguments.path("name").asText();
        // Only concrete capabilities in this task can run; discovery, permission callbacks and recursive dispatch cannot.
        if (name.equals("permission_prompt") || tools(context).stream().noneMatch(tool -> tool.name().equals(name))) {
            return error(id, -32602, "Unknown tool in this task. Use bytequay_list_tools to discover available tools");
        }
        return callTool(new McpRequest("2.0", id, "tools/call", arguments), context);
    }

    private McpHttpResponse readIssueContext(JsonNode id, JsonNode arguments, PermissionContext context)
    {
        if (context.issueContext().isEmpty()) {
            return error(id, -32602, "Unknown tool");
        }
        if (!arguments.isObject() || !arguments.isEmpty()) {
            return error(id, -32602, "read_issue_context accepts no arguments");
        }
        return runTool(id, context, () -> {
            IssueContextBridge bridge = issueContexts.resolve(context.taskId())
                    .orElseThrow(() -> new IllegalStateException("Issue context is no longer available for this task"));
            return bridge.read().get();
        }, failure -> "Unable to read local issue context: " + context.redactor().redact(failureReason(failure)));
    }

    private McpHttpResponse relatedWork(JsonNode id, JsonNode arguments, PermissionContext context)
    {
        if (context.relatedWork().isEmpty()) {
            return error(id, -32602, "Unknown tool");
        }
        return runTool(id, context, () -> context.relatedWork().orElseThrow().call().apply(objectMapper.writeValueAsString(arguments)),
                failure -> context.redactor().redact("Related work failed: " + Optional.ofNullable(failure.getMessage()).orElse("Request rejected")));
    }

    private McpHttpResponse googleWorkspace(JsonNode id, JsonNode arguments, PermissionContext context)
    {
        if (context.googleWorkspace().isEmpty()) {
            return error(id, -32602, "Unknown tool");
        }
        return runTool(id, context, () -> context.googleWorkspace().orElseThrow().call().apply(objectMapper.writeValueAsString(arguments)),
                failure -> "Google data could not be read. Check access in Settings or list saved items again.");
    }

    private McpHttpResponse workItems(JsonNode id, JsonNode arguments, PermissionContext context)
    {
        if (context.workItems().isEmpty()) {
            return error(id, -32602, "Unknown tool");
        }
        return runTool(id, context, () -> context.workItems().orElseThrow().call().apply(objectMapper.writeValueAsString(arguments)),
                failure -> context.redactor().redact("Work item call failed: " + failureReason(failure)));
    }

    private McpHttpResponse memory(JsonNode id, JsonNode arguments, PermissionContext context)
    {
        if (context.memory().isEmpty()) {
            return error(id, -32602, "Unknown tool");
        }
        return runTool(id, context, () -> context.memory().orElseThrow().call().apply(objectMapper.writeValueAsString(arguments)),
                failure -> context.redactor().redact("Memory call failed: " + failureReason(failure)));
    }

    private McpHttpResponse proposeCiDescription(JsonNode id, JsonNode arguments, PermissionContext context)
    {
        var proposer = context.pullRequestCi().flatMap(PullRequestCiBridge::proposeDescription);
        if (context.scope().policy() != AgentPermissionPolicy.CI_AUTOFIX || proposer.isEmpty()) {
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
        return runTool(id, context, () -> proposer.orElseThrow().apply(proposal),
                failure -> "Description proposal was not accepted. Read current CI and description before trying again.");
    }

    private McpHttpResponse requestCiRerun(JsonNode id, JsonNode arguments, PermissionContext context)
    {
        var requester = context.pullRequestCi().flatMap(PullRequestCiBridge::requestRerun);
        if (context.scope().policy() != AgentPermissionPolicy.CI_AUTOFIX || requester.isEmpty()) {
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
        return runTool(id, context, () -> requester.orElseThrow().apply(arguments.path("jobId").longValue(), arguments.path("reason").textValue()),
                failure -> "Rerun request was not accepted. Read current CI before trying again.");
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
                case LocalReviewBridge.LIST_TOOL -> arguments.has("contextOffset") ? ImmutableSet.of("commentId", "contextOffset")
                        : arguments.has("commentId") ? ImmutableSet.of("commentId") : ImmutableSet.of();
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
                if (arguments.has("contextOffset")) {
                    JsonNode offset = arguments.get("contextOffset");
                    checkArgument(offset.isIntegralNumber() && offset.canConvertToInt() && offset.intValue() >= 0 && offset.intValue() <= 60_000,
                            "contextOffset must be an integer from 0 to 60000");
                }
                int contextOffset = arguments.path("contextOffset").asInt(0);
                Optional<String> selectedComment = commentId;
                action = () -> bridge.orElseThrow().list().apply(selectedComment, contextOffset);
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
        return runTool(id, context, action::get,
                failure -> "Local review operation failed: " + context.redactor().redact(failureReason(failure)));
    }

    private McpHttpResponse developmentResult(JsonNode id, JsonNode arguments, PermissionContext context)
    {
        Optional<DevelopmentResultBridge> bridge = context.development()
                .filter(value -> developments.resolve(context.taskId()).filter(current -> current.turnId().equals(value.turnId())).isPresent());
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
        return runTool(id, context, () -> draftPullRequests.publish(context.taskId(), title, body),
                failure -> context.redactor().redact("Publication was refused: " + failureReason(failure)));
    }

    private McpHttpResponse renameTaskBranch(JsonNode id, JsonNode arguments, PermissionContext context)
    {
        Optional<DevelopmentResultBridge> bridge = context.development()
                .flatMap(bound -> developments.resolve(context.taskId()).filter(current -> current.turnId().equals(bound.turnId())))
                .filter(current -> current.branchRenamer().isPresent());
        if (bridge.isEmpty()) {
            return error(id, -32602, "Branch naming is unavailable for this turn");
        }
        String branch;
        try {
            checkArgument(arguments != null && arguments.isObject() && arguments.size() == 1, "Expected only branch");
            branch = requireText(arguments, "branch", 64);
            checkArgument(branch.matches("[a-z][a-z0-9]*(?:-[a-z0-9]+)+"), "Expected a descriptive branch suffix");
        }
        catch (IllegalArgumentException failure) {
            return error(id, -32602, "Invalid rename_task_branch arguments");
        }
        return runTool(id, context, () -> bridge.orElseThrow().branchRenamer().orElseThrow().apply(branch),
                failure -> context.redactor().redact("Branch rename was refused: " + failureReason(failure)));
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
        Optional<ReviewTool> tool = context.reviewContext().filter(value -> value.name().equals(params.name()))
                .or(() -> context.review().flatMap(bridge -> bridge.tool(params.name())));
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
            if (arguments.toolName().equalsIgnoreCase("Agent") || arguments.toolName().equalsIgnoreCase("Task")) {
                boolean explore = Optional.ofNullable(arguments.input().get("subagent_type")).filter(JsonNode::isTextual).map(JsonNode::asText)
                        .filter("Explore"::equals).isPresent();
                if (!context.reviewChildren()) {
                    refused = Optional.of("Review sessions cannot start or resume subagents because review tools require the root agent");
                }
                else if (!explore) {
                    refused = Optional.of("Review sessions may start only read-only Explore subagents, for searching and exploring");
                }
            }
            else if (arguments.toolName().equalsIgnoreCase("Skill")
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
        boolean unsandboxed = arguments.toolName().equalsIgnoreCase("Bash")
                && Optional.ofNullable(arguments.input().get("dangerouslyDisableSandbox"))
                        .filter(JsonNode::isBoolean).map(JsonNode::asBoolean).orElse(false);
        if (context.scope().policy() == AgentPermissionPolicy.CHERRY_PICK) {
            PermissionResult decision = sandboxedPermission(arguments, context, unsandboxed);
            return response(id, new ToolResult(ImmutableList.of(new TextContent("text", objectMapper.writeValueAsString(decision))), false));
        }
        Optional<PermissionResult> allowed = precheckedFileEditPermission(arguments, context);
        if (allowed.isPresent()) {
            return response(id, new ToolResult(ImmutableList.of(new TextContent("text", objectMapper.writeValueAsString(allowed.orElseThrow()))), false));
        }
        String detail = bounded(context.redactor().redact(objectMapper.writeValueAsString(arguments.input())));
        if (unsandboxed) {
            detail = "Runs outside Claude's filesystem sandbox with host file access.\n" + detail;
        }
        ProviderApprovalRequest approval = new ProviderApprovalRequest(
                kind(arguments.toolName()),
                unsandboxed ? "Allow Claude unsandboxed command" : "Allow Claude " + arguments.toolName(),
                detail,
                ImmutableList.of(arguments.toolName()),
                ImmutableList.of(ALLOW_ONCE, DENY),
                // Claude's sandbox covers only its shell, and that sandbox fails closed rather than running unsandboxed.
                arguments.toolName().equalsIgnoreCase("Bash") && !unsandboxed,
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
                ? new PermissionResult("allow", Optional.of(answered(arguments, decision.answers())), Optional.empty())
                : new PermissionResult("deny", Optional.empty(), Optional.of(decision.timedOut() ? "Approval timed out" : "Denied by user"));
        String text = objectMapper.writeValueAsString(result);
        return response(id, new ToolResult(ImmutableList.of(new TextContent("text", text)), false));
    }

    /**
     * Claude's question tool reads the user's choices back from its own input, keyed by the exact question asked.
     * Only answers to questions that were asked are carried, because Claude drops an edited input as a whole and
     * would then have no answer at all.
     */
    private static JsonNode answered(PermissionArguments arguments, Map<String, String> answers)
    {
        JsonNode input = arguments.input();
        if (answers.isEmpty() || !arguments.toolName().equals(QUESTION_TOOL)
                || !input.isObject() || input.has("answers") || !input.path("questions").isArray()) {
            return input;
        }
        ObjectNode accepted = ((ObjectNode) input).objectNode();
        for (JsonNode question : input.get("questions")) {
            String asked = question.path("question").asText("");
            String answer = answers.getOrDefault(asked, "");
            if (!answer.isBlank() && answer.length() <= MAX_ANSWER_CHARACTERS) {
                accepted.put(asked, answer);
            }
        }
        if (accepted.isEmpty()) {
            return input;
        }
        return ((ObjectNode) input.deepCopy()).set("answers", accepted);
    }

    private McpHttpResponse readCiMemory(JsonNode id, JsonNode arguments, PermissionContext context)
    {
        if (context.scope().policy() != AgentPermissionPolicy.CI_AUTOFIX || !validMemoryArguments(arguments)) {
            return error(id, -32602, "Invalid read_ci_memory arguments");
        }
        Optional<String> query = Optional.ofNullable(arguments.get("query"))
                .filter(node -> !node.isNull())
                .map(JsonNode::asText)
                .map(String::trim)
                .map(value -> Ascii.truncate(value, 200, ""))
                .filter(value -> !value.isEmpty());
        String markdown = ciMemoryReader.read(context.taskId(), query);
        return response(id, new ToolResult(ImmutableList.of(new TextContent("text", markdown)), false));
    }

    private McpHttpResponse readPullRequestContext(JsonNode id, JsonNode arguments, PermissionContext context)
    {
        if (context.pullRequestContext().isEmpty()) {
            return error(id, -32602, "Unknown tool");
        }
        PullRequestContextBridge.Query query;
        try {
            checkArgument(arguments != null && arguments.isObject() && arguments.path("section").isTextual(), "section is required");
            checkArgument(arguments.size() == (arguments.has("offset") ? 2 : 1), "Unexpected arguments");
            checkArgument(!arguments.has("offset") || arguments.get("offset").isIntegralNumber() && arguments.get("offset").canConvertToInt(), "offset must be an integer");
            query = new PullRequestContextBridge.Query(arguments.get("section").textValue(), arguments.path("offset").asInt(0));
        }
        catch (IllegalArgumentException failure) {
            return error(id, -32602, "Invalid read_pull_request_context arguments");
        }
        return runTool(id, context, () -> pullRequestContexts.resolve(context.taskId())
                        .orElseThrow(() -> new IllegalStateException("PR context is no longer available")).read().apply(query),
                failure -> context.redactor().redact(Optional.ofNullable(failure.getMessage()).orElse("Saved context is unavailable")));
    }

    private McpHttpResponse readDiscussionContext(JsonNode id, JsonNode arguments, PermissionContext context)
    {
        if (context.discussionContext().isEmpty()) {
            return error(id, -32602, "Unknown tool");
        }
        DiscussionContextBridge.Query query;
        try {
            checkArgument(arguments != null && arguments.isObject() && arguments.path("section").isTextual(), "section is required");
            checkArgument(arguments.size() == (arguments.has("offset") ? 2 : 1), "Unexpected arguments");
            checkArgument(!arguments.has("offset") || arguments.get("offset").isIntegralNumber() && arguments.get("offset").canConvertToInt(), "offset must be an integer");
            query = new DiscussionContextBridge.Query(arguments.get("section").textValue(), arguments.path("offset").asInt(0));
        }
        catch (IllegalArgumentException failure) {
            return error(id, -32602, "Invalid read_discussion_context arguments");
        }
        return runTool(id, context, () -> context.discussionContext().orElseThrow().read().apply(query),
                failure -> context.redactor().redact(Optional.ofNullable(failure.getMessage()).orElse("Saved context is unavailable")));
    }

    private McpHttpResponse codeQueriesExplore(JsonNode id, JsonNode arguments, PermissionContext context)
    {
        Optional<CodeQuery> query = codeQuery(arguments);
        if (context.codeQueries().isEmpty() || query.isEmpty()) {
            return error(id, -32602, "Invalid " + CodeQueryBridge.TOOL_NAME + " arguments");
        }
        String text = context.redactor().redact(context.codeQueries().orElseThrow().query().apply(query.orElseThrow()));
        return response(id, new ToolResult(ImmutableList.of(new TextContent("text", text)), false));
    }

    private static Optional<CodeQuery> codeQuery(JsonNode arguments)
    {
        if (arguments == null || !arguments.isObject() || !arguments.path("operation").isTextual() || !arguments.path("path").isTextual()) {
            return Optional.empty();
        }
        var fields = arguments.fieldNames();
        while (fields.hasNext()) {
            if (!ImmutableSet.of("operation", "path", "query", "line", "column", "limit").contains(fields.next())) {
                return Optional.empty();
            }
        }
        for (String key : ImmutableList.of("line", "column", "limit")) {
            if ((arguments.has(key) || key.equals("limit")) && (!arguments.path(key).isIntegralNumber() || !arguments.path(key).canConvertToInt())) {
                return Optional.empty();
            }
        }
        if (arguments.has("query") && !arguments.path("query").isTextual()) {
            return Optional.empty();
        }
        try {
            return Optional.of(new CodeQuery(CodeQuery.Operation.valueOf(arguments.path("operation").textValue()), arguments.path("path").textValue(),
                    Optional.ofNullable(arguments.get("query")).map(JsonNode::textValue),
                    arguments.has("line") ? OptionalInt.of(arguments.path("line").intValue()) : OptionalInt.empty(),
                    arguments.has("column") ? OptionalInt.of(arguments.path("column").intValue()) : OptionalInt.empty(), arguments.path("limit").intValue()));
        }
        catch (IllegalArgumentException failure) {
            return Optional.empty();
        }
    }

    private McpHttpResponse runTool(JsonNode id, PermissionContext context, ToolAction action, Function<Exception, String> failureText)
    {
        try {
            String text = context.redactor().redact(action.run());
            return response(id, new ToolResult(ImmutableList.of(new TextContent("text", text)), false));
        }
        catch (JsonProcessingException | RuntimeException failure) {
            return response(id, new ToolResult(ImmutableList.of(new TextContent("text", failureText.apply(failure))), true));
        }
    }

    private static String failureReason(Exception failure)
    {
        return Optional.ofNullable(failure.getMessage()).orElse(failure.getClass().getSimpleName());
    }

    @FunctionalInterface
    private interface ToolAction
    {
        String run() throws JsonProcessingException;
    }

    private McpHttpResponse response(JsonNode id, Object result)
    {
        return new McpHttpResponse(200, Optional.of(objectMapper.valueToTree(new McpResponse("2.0", id, result))));
    }

    private McpHttpResponse error(JsonNode id, int code, String message)
    {
        return new McpHttpResponse(200, Optional.of(objectMapper.valueToTree(new McpErrorResponse("2.0", id, new McpError(code, message)))));
    }

    private String protocolVersion(JsonNode params)
            throws JsonProcessingException
    {
        return objectMapper.treeToValue(params, InitializeParams.class).protocolVersion();
    }

    private static ToolDefinition codeQueriesTool()
    {
        return new ToolDefinition(
                CodeQueryBridge.TOOL_NAME,
                "Query this working copy's IDE language services for symbols, definitions, references, implementations, calls or diagnostics. "
                        + "Use a relative source path; positions are one-based UTF-16. Results may be partial while importing. Verify current source.",
                ImmutableMap.of(
                        "type", "object",
                        "properties", ImmutableMap.of(
                                "operation", ImmutableMap.of("type", "string", "enum",
                                        Arrays.stream(CodeQuery.Operation.values()).map(Enum::name).collect(ImmutableList.toImmutableList())),
                                "path", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", 4096),
                                "query", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", 200),
                                "line", ImmutableMap.of("type", "integer", "minimum", 1),
                                "column", ImmutableMap.of("type", "integer", "minimum", 1),
                                "limit", ImmutableMap.of("type", "integer", "minimum", 1, "maximum", 200)),
                        "required", ImmutableList.of("operation", "path", "limit"),
                        "additionalProperties", false));
    }

    private static ToolDefinition permissionTool()
    {
        return new ToolDefinition(
                "permission_prompt",
                "Ask the ByteQuay user to approve a Claude tool call",
                ImmutableMap.of(
                        "type", "object",
                        "properties", ImmutableMap.of("tool_name", ImmutableMap.of("type", "string"), "input", ImmutableMap.of("type", "object")),
                        "required", ImmutableList.of("tool_name", "input"),
                        "additionalProperties", true));
    }

    /** A digest of the tools a session is given, by name, description and parameters in any order, so two setups can be told apart. */
    private String toolsDigest(List<ToolDefinition> tools)
    {
        try {
            String canonical = objectMapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
                    .writeValueAsString(tools.stream().sorted(comparing(ToolDefinition::name)).collect(toImmutableList()));
            return Hashing.sha256().hashString(canonical, StandardCharsets.UTF_8).toString();
        }
        catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static List<ToolDefinition> exposedTools(PermissionContext context)
    {
        List<ToolDefinition> available = tools(context);
        if (available.stream().noneMatch(tool -> !tool.name().equals("permission_prompt"))) {
            return available;
        }
        return ImmutableList.<ToolDefinition>builder().addAll(available)
                .add(new ToolDefinition(LIST_TOOLS,
                        "List this task's current ByteQuay tool names and descriptions. Choose relevant tools, then read bytequay_get_tool for usage and parameters",
                        ImmutableMap.of("type", "object", "properties", ImmutableMap.of(), "additionalProperties", false)))
                .add(new ToolDefinition(GET_TOOL,
                        "Read one task tool's current description and complete inputSchema, including required parameters and allowed values. Does not run the tool",
                        ImmutableMap.of("type", "object", "properties", ImmutableMap.of("name", ImmutableMap.of("type", "string", "minLength", 1)),
                                "required", ImmutableList.of("name"), "additionalProperties", false)))
                .add(callToolDefinition()).build();
    }

    private static ToolDefinition callToolDefinition()
    {
        return new ToolDefinition(CALL_TOOL,
                "Run one current ByteQuay task tool. Discover names with bytequay_list_tools and read usage and parameters with bytequay_get_tool first. "
                        + "Pass its name and arguments object. Existing permissions apply; review and related-work changes belong to the main agent",
                ImmutableMap.of("type", "object", "properties", ImmutableMap.of(
                        "name", ImmutableMap.of("type", "string", "minLength", 1),
                        "arguments", ImmutableMap.of("type", "object", "additionalProperties", true)),
                        "required", ImmutableList.of("name", "arguments"), "additionalProperties", false));
    }

    private static List<ToolDefinition> tools(PermissionContext context)
    {
        ImmutableList.Builder<ToolDefinition> tools = ImmutableList.builder();
        if (context.permissionTools()) {
            tools.add(permissionTool());
        }
        if (context.codeQueries().isPresent()) {
            tools.add(codeQueriesTool());
        }
        context.reviewContext().ifPresent(tool -> tools.add(new ToolDefinition(tool.name(), tool.description(), tool.inputSchema())));
        context.review().ifPresent(review -> review.tools().forEach(tool -> tools.add(new ToolDefinition(tool.name(), tool.description(), tool.inputSchema()))));
        context.issueContext().ifPresent(ignored -> tools.add(new ToolDefinition(
                IssueContextBridge.TOOL_NAME,
                "Read this task's local source context: its accepted issue or ticket snapshot with cached observations, and the Epic, its work list and this "
                        + "task's work item when the task is Epic work; missing or stale context is marked. Read-only; no remote fetch",
                ImmutableMap.of("type", "object", "properties", ImmutableMap.of(), "additionalProperties", false))));
        context.relatedWork().ifPresent(ignored -> tools.add(new ToolDefinition(RelatedWorkspaceBridge.TOOL_NAME,
                "Start explicitly requested work in a related workspace, or read the linked conversation's result; root agent only",
                ImmutableMap.of("type", "object", "properties", ImmutableMap.<String, Object>builder()
                        .put("action", ImmutableMap.of("type", "string", "enum", ImmutableList.of("START", "READ", "DISCOVER_BACKPORT", "SAVE_CHAIN", "READ_CHAIN")))
                        .put("operationKey", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", 100))
                        .put("workspaceId", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", 100))
                        .put("purpose", ImmutableMap.of("type", "string", "enum", ImmutableList.of("INVESTIGATE", "FIX")))
                        .put("question", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", 16000))
                        .put("commit", ImmutableMap.of("type", "string", "pattern", "^([0-9a-f]{40}|[0-9a-f]{64})$"))
                        .put("revision", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", 200))
                        .put("sourceTaskId", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", 100))
                        .put("chainId", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", 100))
                        .put("sourcePullRequest", ImmutableMap.of("type", "object", "properties", ImmutableMap.of(
                                "workspaceId", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", 100),
                                "number", ImmutableMap.of("type", "integer", "minimum", 1, "maximum", Integer.MAX_VALUE)),
                                "required", ImmutableList.of("workspaceId", "number"), "additionalProperties", false))
                        .put("sourceHops", ImmutableMap.of("type", "array", "minItems", 1, "maxItems", 20,
                                "items", ImmutableMap.of("type", "integer", "minimum", -1, "maximum", 19)))
                        .put("destinations", ImmutableMap.of("type", "array", "minItems", 1, "maxItems", 20,
                                "items", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", 100))).buildOrThrow(),
                        "required", ImmutableList.of("action", "operationKey"), "additionalProperties", false))));
        context.googleWorkspace().ifPresent(ignored -> tools.add(new ToolDefinition(GoogleWorkspaceBridge.TOOL_NAME,
                "Read the user's saved Gmail and Calendar snapshots. LIST then READ; no live sync or remote writes",
                ImmutableMap.of("type", "object", "properties", ImmutableMap.of(
                        "action", ImmutableMap.of("type", "string", "enum", ImmutableList.of("LIST", "READ")),
                        "source", ImmutableMap.of("type", "string", "enum", ImmutableList.of("MAIL", "CALENDAR")),
                        "id", ImmutableMap.of("type", "string"), "query", ImmutableMap.of("type", "string")),
                        "required", ImmutableList.of("action", "source"), "additionalProperties", false))));
        context.workItems().ifPresent(ignored -> tools.add(new ToolDefinition(WorkItemBridge.TOOL_NAME,
                "LIST or READ unified local, GitHub and Jira work items in this workspace; CREATE private local work only. Never publishes",
                ImmutableMap.of("type", "object", "properties", ImmutableMap.of(
                        "action", ImmutableMap.of("type", "string", "enum", ImmutableList.of("LIST", "READ", "CREATE")),
                        "id", ImmutableMap.of("type", "string"), "query", ImmutableMap.of("type", "string"),
                        "offset", ImmutableMap.of("type", "integer", "minimum", 0), "operationKey", ImmutableMap.of("type", "string"),
                        "title", ImmutableMap.of("type", "string", "maxLength", 500),
                        "description", ImmutableMap.of("type", "string", "maxLength", 65536), "epicId", ImmutableMap.of("type", "string")),
                        "required", ImmutableList.of("action"), "additionalProperties", false))));
        context.memory().ifPresent(ignored -> tools.add(memoryTool(false)));
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
        if (context.scope().policy() == AgentPermissionPolicy.CI_AUTOFIX && context.pullRequestCi().flatMap(PullRequestCiBridge::proposeDescription).isPresent()) {
            tools.add(new ToolDefinition(CiDescriptionProposal.TOOL_NAME,
                    """
                    Propose a minimal PR-description repair for a current failed job. Call read_pull_request_ci
                    without arguments for the description; if descriptionTruncated is true, do not propose a partial body.
                    Supply the failed jobId, the exact description as expectedBody, and the complete corrected body.
                    Change only what the check requires; preserve release notes, user restrictions and other change details.
                    Never include local execution details: commands, test runs or counts, pass/fail results, build or
                    validation outcomes, logs or environment limitations. Omit these even if already present, including
                    in a test-plan section; report them only in the task conversation.
                    This saves a proposal. ByteQuay applies it after the turn and verifies a new check. It grants no
                    shell/API write access. Do not rerun the old event yourself.
                    """,
                    ImmutableMap.of("type", "object", "properties", ImmutableMap.of(
                            "jobId", ImmutableMap.of("type", "integer", "minimum", 1, "maximum", Long.MAX_VALUE),
                            "expectedBody", ImmutableMap.of("type", "string", "maxLength", 60_000),
                            "body", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", 60_000)),
                            "required", ImmutableList.of("jobId", "expectedBody", "body"), "additionalProperties", false)));
        }
        if (context.scope().policy() == AgentPermissionPolicy.CI_AUTOFIX && context.pullRequestCi().flatMap(PullRequestCiBridge::requestRerun).isPresent()) {
            tools.add(new ToolDefinition(PullRequestCiBridge.RERUN_TOOL_NAME,
                    """
                    Request one failed-job rerun only after diagnosing evidence of a flaky test or transient infrastructure failure.
                    Supply a current failed jobId and a reason citing that evidence. A clean worktree or inability to reproduce
                    does not establish flakiness. Repair deterministic failures such as description validation, compilation or
                    reproducible assertions instead. This saves an assessment for ByteQuay to consider after the turn, without
                    directly rerunning anything. Each accepted request allows one rerun of that workflow's failed jobs.
                    After a failed rerun, diagnose the new evidence and repair the cause; explain any unrepairable blocker.
                    """,
                    ImmutableMap.of("type", "object", "properties", ImmutableMap.of(
                            "jobId", ImmutableMap.of("type", "integer", "minimum", 1, "maximum", Long.MAX_VALUE),
                            "reason", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", 4000)),
                            "required", ImmutableList.of("jobId", "reason"), "additionalProperties", false)));
        }
        context.pullRequestContext().ifPresent(ignored -> tools.add(new ToolDefinition(PullRequestContextBridge.TOOL_NAME,
                "Read the assigned PR snapshot, cached description or discussion in pages. Read-only; no remote fetch. Use CI tools for checks and logs",
                ImmutableMap.of("type", "object", "properties", ImmutableMap.of(
                        "section", ImmutableMap.of("type", "string", "enum", ImmutableList.of("snapshot", "description", "discussion")),
                        "offset", ImmutableMap.of("type", "integer", "minimum", 0, "maximum", Integer.MAX_VALUE)),
                        "required", ImmutableList.of("section"), "additionalProperties", false))));
        context.discussionContext().ifPresent(ignored -> tools.add(new ToolDefinition(DiscussionContextBridge.TOOL_NAME,
                "Read saved context for this private discussion only. Use source, history or discussion and follow nextOffset for more reference text",
                ImmutableMap.of("type", "object", "properties", ImmutableMap.of(
                        "section", ImmutableMap.of("type", "string", "enum", ImmutableList.of("source", "history", "discussion")),
                        "offset", ImmutableMap.of("type", "integer", "minimum", 0, "maximum", Integer.MAX_VALUE)),
                        "required", ImmutableList.of("section"), "additionalProperties", false))));
        context.localReview().ifPresent(ignored -> {
            Map<String, Object> identifier = ImmutableMap.of("type", "string", "minLength", 1, "maxLength", LocalReviewBridge.MAXIMUM_ID_LENGTH);
            tools.add(new ToolDefinition(
                    LocalReviewBridge.LIST_TOOL,
                    "Read bounded unresolved/recent summaries of this task's private comments and, for a development task, of the review findings on its changes " +
                            "and pull request; or supply a comment's or finding's id as commentId for its code, evidence and recent discussion. " +
                            "Omissions and excerpts are marked. " +
                            "For selected code or saved learning cards, pass nextContextOffset as contextOffset with the same commentId to read the next page",
                    ImmutableMap.of("type", "object", "properties", ImmutableMap.of(
                            "commentId", identifier,
                            "contextOffset", ImmutableMap.of("type", "integer", "minimum", 0, "maximum", 60_000)), "additionalProperties", false)));
            tools.add(new ToolDefinition(
                    LocalReviewBridge.REPLY_TOOL,
                    "Reply privately to one returned comment or review finding; reuse the operationKey when retrying the same reply",
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
                    "Resolve or reopen one private comment, or a review finding you fixed and replied on; resolved is explicit and operationKey identifies this exact update",
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
        context.development().filter(bridge -> bridge.branchRenamer().isPresent()).ifPresent(ignored -> tools.add(new ToolDefinition(
                DevelopmentResultBridge.RENAME_BRANCH_TOOL_NAME,
                "Name this task's unpublished branch after the actual change, only during its first turn and before first pushing",
                ImmutableMap.of(
                        "type", "object",
                        "properties", ImmutableMap.of("branch", ImmutableMap.of("type", "string", "minLength", 3, "maxLength", 64)),
                        "required", ImmutableList.of("branch"),
                        "additionalProperties", false))));
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
        if (context.scope().policy() == AgentPermissionPolicy.CI_AUTOFIX) {
            tools.add(new ToolDefinition(
                    "read_ci_memory",
                    "Read recent or matching CI Autofix history for this task",
                    ImmutableMap.of("type", "object", "properties", ImmutableMap.of("query", ImmutableMap.of("type", "string", "maxLength", 200)), "additionalProperties", false)));
        }
        return tools.build();
    }

    private static ToolDefinition memoryTool(boolean readOnly)
    {
        return new ToolDefinition(
                MemoryBridge.TOOL_NAME,
                readOnly ? "Search or read repository memory and its sources. Only SEARCH and READ are available to review children." : MemoryBridge.DESCRIPTION,
                ImmutableMap.of(
                        "type", "object",
                        "properties", ImmutableMap.<String, Object>builder()
                                .put("action", ImmutableMap.of("type", "string", "enum", readOnly
                                        ? ImmutableList.of("SEARCH", "READ") : ImmutableList.of("SEARCH", "READ", "REPORT", "REMEMBER")))
                                .put("workspaceId", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", 100))
                                .put("kind", ImmutableMap.of("type", "string", "enum", ImmutableList.of("RULE", "RECIPE")))
                                .put("title", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", 80))
                                .put("text", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", 600))
                                .put("scope", ImmutableMap.of("type", "array", "maxItems", 8, "items", ImmutableMap.of("type", "string", "maxLength", 300)))
                                .put("command", ImmutableMap.of("type", "string", "maxLength", 2000))
                                .put("query", ImmutableMap.of("type", "string", "maxLength", 200))
                                .put("path", ImmutableMap.of("type", "string", "maxLength", 300))
                                .put("page", ImmutableMap.of("type", "integer", "minimum", 1))
                                .put("id", ImmutableMap.of("type", "string", "pattern", "^m[1-9][0-9]*$"))
                                .put("reason", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", 500))
                                .put("quote", ImmutableMap.of("type", "string", "maxLength", 300))
                                .buildOrThrow(),
                        "required", ImmutableList.of("action"),
                        "additionalProperties", false));
    }

    private static PermissionResult sandboxedPermission(PermissionArguments arguments, PermissionContext context, boolean unsandboxed)
    {
        if (unsandboxed) {
            return new PermissionResult("deny", Optional.empty(), Optional.of("Cherry-pick commands must remain sandboxed"));
        }
        Optional<PermissionResult> allowed = precheckedFileEditPermission(arguments, context);
        if (allowed.isPresent()) {
            return allowed.orElseThrow();
        }
        if (arguments.toolName().equalsIgnoreCase("Bash")) {
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
            return new PermissionResult("deny", Optional.empty(), Optional.of("Cherry-pick tasks allow only the expected fixup-shaped history change"));
        }
        return new PermissionResult("deny", Optional.empty(), Optional.of("This task permits only sandboxed commands and file edits inside its leased worktree"));
    }

    private static Optional<PermissionResult> precheckedFileEditPermission(PermissionArguments arguments, PermissionContext context)
    {
        Optional<Path> requested = requestedPath(arguments, context.worktree());
        if (requested.isPresent() && AgentPermissionManager.allowsFileWrite(
                requested.orElseThrow(), context.worktree(), context.fileWriteEnvironment(), context.scope())) {
            return Optional.of(new PermissionResult("allow", Optional.of(arguments.input()), Optional.empty()));
        }
        return Optional.empty();
    }

    private static Optional<Path> requestedPath(PermissionArguments arguments, Path worktree)
    {
        String field;
        if (arguments.toolName().equalsIgnoreCase("Edit") || arguments.toolName().equalsIgnoreCase("Write")) {
            field = "file_path";
        }
        else if (arguments.toolName().equalsIgnoreCase("NotebookEdit")) {
            field = "notebook_path";
        }
        else {
            return Optional.empty();
        }
        JsonNode value = arguments.input().get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            return Optional.empty();
        }
        try {
            Path path = Path.of(value.asText());
            return Optional.of((path.isAbsolute() ? path : worktree.resolve(path)).toAbsolutePath().normalize());
        }
        catch (RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private static boolean validMemoryArguments(JsonNode arguments)
    {
        if (!arguments.isObject() || arguments.size() > 1 || (arguments.size() == 1 && !arguments.has("query"))) {
            return false;
        }
        JsonNode query = arguments.get("query");
        return query == null || query.isNull() || query.isTextual();
    }

    private String sandboxSettings(AgentPermissionScope scope, Path runtimeDirectory, Path worktree, Map<String, String> environment, Optional<Path> childGate)
            throws IOException
    {
        AgentPermissionPolicy permissionPolicy = scope.policy();
        SessionAccess access = scope.access();
        boolean reviewSession = scope.review();
        boolean gitHubCommands = scope.githubCommands();
        // An attended review reads remotes with the user's stored Git and gh login; it never writes to them.
        boolean reviewReads = scope.reviewReads();
        ImmutableList.Builder<String> deny = ImmutableList.builder();
        ImmutableList.Builder<String> denyRead = ImmutableList.builder();
        if (reviewReads) {
            // git, gh and ssh read their own login files, so the sandbox lets them; the agent's Read tool still may not.
            deny.addAll(REVIEW_REMOTE_WRITES).addAll(CREDENTIAL_FILE_READS);
        }
        else if (!gitHubCommands) {
            deny.add("Bash(git credential)", "Bash(git credential:*)", "Bash(git push:*)", "Bash(gh:*)").addAll(CREDENTIAL_FILE_READS);
            denyRead.add("~/.config/gh", "~/.git-credentials", "~/.netrc", "~/.ssh");
            // Custom CLI config locations must not evade the restricted session's credential boundary.
            Optional.ofNullable(environment.get("XDG_CONFIG_HOME")).filter(value -> !value.isBlank())
                    .map(value -> Path.of(value).resolve("gh").toString()).ifPresent(denyRead::add);
            Optional.ofNullable(environment.get("GH_CONFIG_DIR")).filter(value -> !value.isBlank()).ifPresent(denyRead::add);
        }
        if (reviewSession) {
            // This task's MCP token does not distinguish a root agent from a child caller, so a child is
            // admitted only behind the gate below, which keeps every ByteQuay write tool with the root.
            // Skills and workflows start other kinds of children; SendMessage can resume earlier children.
            deny.add("Skill", "Workflow", "SendMessage");
            if (childGate.isEmpty()) {
                deny.add("Agent", "Task");
            }
            // Native approval must preserve the review boundary while allowing local experiment edits.
            for (String command : ImmutableList.of("commit", "commit-tree", "update-ref", "merge", "rebase", "cherry-pick", "revert", "am", "switch", "pull")) {
                deny.add("Bash(git " + command + ")", "Bash(git " + command + ":*)");
            }
        }
        boolean allowUnsandboxedRetry = access == SessionAccess.FULL
                && (permissionPolicy == AgentPermissionPolicy.AUTO || permissionPolicy == AgentPermissionPolicy.CI_AUTOFIX);
        ImmutableList.Builder<String> allowWrite = ImmutableList.builder();
        allowWrite.addAll(BuildCacheRoots.buildCaches(scope, environment).stream().map(Path::toString).collect(toImmutableList()));
        if (access == SessionAccess.FULL && !reviewSession
                && Files.exists(worktree.resolve(".git"), LinkOption.NOFOLLOW_LINKS)) {
            List<String> gitRoots = GitWorktreeWriteRoots.writableRoots(worktree);
            allowWrite.addAll(gitRoots.subList(1, gitRoots.size()));
        }
        allowWrite.addAll(BuildCacheRoots.temporary(scope, environment).stream().map(Path::toString).collect(toImmutableList()));
        ImmutableMap.Builder<String, Object> sandbox = ImmutableMap.builder();
        sandbox.put("enabled", true)
                .put("failIfUnavailable", true)
                .put("autoAllowBashIfSandboxed", !gitHubCommands && !reviewSession && permissionPolicy != AgentPermissionPolicy.CHERRY_PICK)
                // Native Auto mode reviews retries first; unresolved requests use the permission callback.
                .put("allowUnsandboxedCommands", allowUnsandboxedRetry)
                .put("excludedCommands", ImmutableList.of())
                .put("filesystem", ImmutableMap.of(
                        // Claude's private scratch allowance does not cover /tmp or macOS Java's host temp root.
                        // ponytail: add a vetted root in BuildCacheRoots when a task toolchain needs one; BuildCacheRoots grants it.
                        "allowWrite", allowWrite.build(),
                        "denyRead", denyRead.build(),
                        // The task runtime directory holds the CI logs, the event log, and this
                        // settings file. The agent reads its attachments from there and must not
                        // be able to rewrite the evidence its own work is judged on.
                        // A read-only session is rooted at a real checkout and its Bash is
                        // auto-approved, so denying the worktree here is the whole containment
                        // boundary rather than a second line of defence. A text-only worktree is
                        // already inside the runtime directory, and need not exist on disk.
                        "denyWrite", access == SessionAccess.READ_ONLY || access == SessionAccess.REVIEW_READ_ONLY
                                ? ImmutableList.of(runtimeDirectory.toString(), worktree.toRealPath().toString())
                                : ImmutableList.of(runtimeDirectory.toString())));
        if (scope.unattended() || access.restricted()) {
            deny.add("WebFetch", "WebSearch");
        }
        sandbox.put("network", scope.buildNetwork()
                ? ImmutableMap.of(
                        "allowedDomains", ImmutableList.of("*"),
                        "allowLocalBinding", true,
                        // Build tools such as Testcontainers connect directly to Docker, without invoking its CLI.
                        "allowUnixSockets", ImmutableList.of("/var/run/docker.sock", "~/.docker/run/docker.sock", "~/.orbstack/run/docker.sock"))
                : ImmutableMap.of("allowedDomains", ImmutableList.of()));
        ImmutableMap.Builder<String, Object> permissions = ImmutableMap.<String, Object>builder()
                .put("deny", deny.build());
        ImmutableMap.Builder<String, Object> settings = ImmutableMap.<String, Object>builder()
                .put("autoMemoryEnabled", false)
                .put("permissions", permissions.buildOrThrow())
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
        // A hook sees agent_id on a child's tool call; the MCP request itself carries nothing that identifies the caller.
        childGate.ifPresent(gate -> settings.put("hooks", ImmutableMap.of("PreToolUse", ImmutableList.of(ImmutableMap.of(
                "matcher", CHILD_GATE_MATCHER,
                "hooks", ImmutableList.of(ImmutableMap.of("type", "command", "command", "'" + gate.toString().replace("'", "'\\''") + "'")))))));
        return objectMapper.writeValueAsString(settings.buildOrThrow());
    }

    /** Fills the gate's allowlist with the tools that only read: a tool stays root-only until it is marked child-callable. */
    private static String childGateScript(PermissionContext context)
    {
        List<String> readable = Stream.concat(
                        context.review().stream().flatMap(review -> review.tools().stream()).filter(ReviewTool::childCallable).map(ReviewTool::name),
                        context.codeQueries().stream().map(graph -> CodeQueryBridge.TOOL_NAME))
                .filter(name -> SHELL_SAFE_TOOL_NAME.matcher(name).matches())
                .map(name -> "mcp__bytequay__" + name)
                .collect(toImmutableList());
        if (context.review().isEmpty()) {
            readable = tools(context).stream().map(ToolDefinition::name).filter(name -> !name.equals(RelatedWorkspaceBridge.TOOL_NAME))
                    .filter(name -> SHELL_SAFE_TOOL_NAME.matcher(name).matches()).map(name -> "mcp__bytequay__" + name).collect(toImmutableList());
        }
        readable = ImmutableList.<String>builder().addAll(readable).add("mcp__bytequay__" + LIST_TOOLS, "mcp__bytequay__" + GET_TOOL).build();
        return CHILD_GATE_SCRIPT.replace("@REVIEW_ONLY@", String.valueOf(context.review().isPresent()))
                .replace("@CHILD_TOOLS@", readable.isEmpty() ? "mcp__bytequay__none" : String.join("|", readable));
    }

    private static String readChildGateScript()
    {
        try {
            return Resources.toString(Resources.getResource(PermissionBroker.class, "review-child-gate.sh"), StandardCharsets.UTF_8);
        }
        catch (IOException e) {
            throw new UncheckedIOException("Cannot load the review child gate", e);
        }
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
            AgentPermissionScope scope,
            Map<String, String> fileWriteEnvironment,
            AgentApprovalHandler approvals,
            SecretRedactor redactor,
            boolean permissionTools,
            Optional<CodeQueryBridge> codeQueries,
            Optional<DiscussionContextBridge> discussionContext,
            Optional<ReviewTool> reviewContext,
            Optional<ReviewBridge> review,
            Optional<DevelopmentResultBridge> development,
            Optional<LocalReviewBridge> localReview,
            Optional<PullRequestCiBridge> pullRequestCi,
            Optional<PullRequestContextBridge> pullRequestContext,
            Optional<IssueContextBridge> issueContext,
            Optional<MemoryBridge> memory,
            Optional<RelatedWorkspaceBridge> relatedWork,
            Optional<WorkItemBridge> workItems,
            Optional<GoogleWorkspaceBridge> googleWorkspace,
            boolean reviewChildren,
            AtomicReference<CodexRootTools> codexReviewTools)
    {
        private PermissionContext
        {
            requireNonNull(taskId, "taskId is null");
            requireNonNull(worktree, "worktree is null");
            requireNonNull(scope, "scope is null");
            fileWriteEnvironment = ImmutableMap.copyOf(fileWriteEnvironment);
            requireNonNull(approvals, "approvals is null");
            requireNonNull(redactor, "redactor is null");
            requireNonNull(codexReviewTools, "codexReviewTools is null");
            requireNonNull(codeQueries, "codeQueries is null");
            requireNonNull(reviewContext, "reviewContext is null");
            requireNonNull(discussionContext, "discussionContext is null");
            requireNonNull(review, "review is null");
            requireNonNull(development, "development is null");
            requireNonNull(localReview, "localReview is null");
            requireNonNull(pullRequestCi, "pullRequestCi is null");
            requireNonNull(pullRequestContext, "pullRequestContext is null");
            requireNonNull(issueContext, "issueContext is null");
            requireNonNull(memory, "memory is null");
            requireNonNull(relatedWork, "relatedWork is null");
            requireNonNull(workItems, "workItems is null");
            requireNonNull(googleWorkspace, "googleWorkspace is null");
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

    /**
     * {@code alwaysLoad} keeps every ByteQuay tool in the prompt. Claude defers MCP tools behind tool
     * search by default, and a deferred {@code report_development_result} is one the agent never finds
     * and never calls, which fails the turn's mandatory report.
     */
    private record McpServer(String type, String url, Map<String, String> headers, long timeout, boolean alwaysLoad)
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

    private record PermissionArguments(@JsonProperty("tool_name") String toolName, JsonNode input)
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
