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
package com.bytequay.app.service.threads;

import com.bytequay.app.domain.AgentMetrics;
import com.bytequay.app.domain.CredentialType;
import com.bytequay.app.domain.PermissionDecision;
import com.bytequay.app.domain.StreamEvent;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadMessage;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.CredentialService;
import com.bytequay.app.service.ai.ModelPricing;
import com.bytequay.app.service.local.ds4.Ds4Instrumentation;
import com.bytequay.app.service.local.ds4.Ds4LifecycleService;
import com.bytequay.app.service.local.ds4.Ds4State;
import com.bytequay.app.service.threads.tools.AgentTool;
import com.bytequay.app.service.threads.tools.AgentToolContext;
import com.bytequay.app.service.threads.tools.LogicLoopToolRegistry;
import com.bytequay.app.service.threads.tools.ToolPermissionMediator;
import com.bytequay.app.service.tools.Gating;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

/**
 * In-JVM API-lane {@link ThreadAgent}. Drives the loop by calling the
 * resolved provider's HTTP API directly and translating streaming deltas
 * into the same {@link StreamEvent} shapes the CLI lane emits.
 *
 * <p>B5 multi-provider transport: Anthropic (SSE Messages API), OpenAI
 * (chat-completions SSE), and DeepSeek (OpenAI-compatible surface, cloud
 * and local ds4 variant) are all wired. Tools route through
 * {@link LogicLoopToolRegistry} on all three providers; the Anthropic path
 * uses {@code tool_use} content blocks while OpenAI/DeepSeek use the
 * {@code tool_calls} message role.
 *
 * <p>History replay limits to {@value REPLAY_HISTORY_LIMIT} recent text
 * messages per turn. Tool-call / tool-result rows from prior turns are
 * excluded — only the final assistant text survives across turn boundaries.
 *
 * <p>Mirrors {@link ClaudeCodeCliThreadAgent} on the contract that matters
 * for callers: same {@link ThreadStore#appendMessage} writes, same event
 * ordering (SessionStarted → AssistantTextDelta… → AssistantText →
 * UsageUpdated → TurnDone → SessionEnded), same lifecycle state machine
 * (IDLE/RUNNING/AWAITING/STOPPED), so the frontend renders trunk and task
 * panes identically across lanes.
 */
public class LogicLoopThreadAgent
        implements ThreadAgent
{
    private static final Logger log = LoggerFactory.getLogger(LogicLoopThreadAgent.class);

    // ── Anthropic ─────────────────────────────────────────────────────────
    private static final String ANTHROPIC_PROVIDER_ID = "anthropic";
    private static final String ANTHROPIC_MESSAGES_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";

    // ── OpenAI ────────────────────────────────────────────────────────────
    private static final String OPENAI_PROVIDER_ID = "openai";
    private static final String OPENAI_COMPLETIONS_URL = "https://api.openai.com/v1/chat/completions";

    // ── DeepSeek ──────────────────────────────────────────────────────────
    private static final String DEEPSEEK_PROVIDER_ID = "deepseek";
    private static final String DEEPSEEK_COMPLETIONS_URL = "https://api.deepseek.com/chat/completions";
    /** Local ds4 model served by the Ds4LifecycleService subprocess. */
    /** Fallback local URL when no Ds4LifecycleService is wired (test
     *  paths). Production resolves the URL dynamically from the
     *  supervisor's status endpoint so a user-configured ds4.port
     *  flows through end-to-end.
     *  <p>Note the {@code /v1/} prefix — ds4-server's README pins
     *  the chat-completions endpoint at {@code POST /v1/chat/completions};
     *  hitting {@code /chat/completions} (without {@code /v1/}) was
     *  the bug that surfaced as the trunk thread flipping to ERRORED. */
    private static final String DEEPSEEK_LOCAL_COMPLETIONS_URL = "http://127.0.0.1:8000/v1/chat/completions";
    private static final String DEEPSEEK_LOCAL_MODEL_ID = "deepseek-v4-flash";
    /** Placeholder token the local ds4 server accepts; the real gate is
     *  "server running", not "key present". */
    private static final String DEEPSEEK_LOCAL_AUTH_TOKEN = "dsv4-local";

    private static final int MAX_OUTPUT_TOKENS = 4_096;
    /** History tail replayed to the provider on each turn. Bound by row
     *  count rather than token budget for simplicity in v1. */
    private static final int REPLAY_HISTORY_LIMIT = 80;
    /** Safety cap on the per-turn tool-use ↔ result loop. Reset on
     *  every {@code send()}. */
    private static final int MAX_TOOL_ITERATIONS = 12;

    // ── Fields ────────────────────────────────────────────────────────────

    private final String threadId;
    private final ThreadKind kind;
    private final ThreadStore store;
    private final TaskStore taskStore;
    private final ObjectMapper mapper;
    private final ExecutorService executor;
    private final CredentialService credentialService;
    private final WorkModel resolvedModel;
    private final String workingDir;
    private final String branchName;
    private final String roleSkillText;
    private final String sessionId;
    private final long sessionStartedMs;
    private final HttpClient httpClient;
    /** Tools the model is told about and can call. Null on the
     *  legacy text-only constructor used by older test paths. */
    private final LogicLoopToolRegistry toolRegistry;
    /** Local ds4 supervisor — null when the agent is built without
     *  the local lane wired (e.g. unit tests). Used to (a) build the
     *  current local endpoint URL so a user-configured ds4.port is
     *  honoured, and (b) refuse to dispatch when the server isn't
     *  RUNNING instead of silently 404'ing. */
    private final Ds4LifecycleService ds4;
    /** Metrics ring the local-ds4 path records each round into so
     *  the Metrics tab reflects thread turns alongside review calls.
     *  Null on the legacy paths. */
    private final Ds4Instrumentation ds4Instrumentation;
    /** Coordinates Allow / Deny prompts with {@link ThreadAgent#decide}.
     *  Null when the agent is built outside the production wiring
     *  (legacy tests); the BridgedTool falls back to refusing gated
     *  calls when this is missing. */
    private final McpPermissionGate permissionGate;
    private final CopyOnWriteArrayList<Consumer<StreamEvent>> listeners = new CopyOnWriteArrayList<>();

    private final AtomicReference<ThreadStatus> status = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<Void>> currentTurn = new AtomicReference<>();
    private final AtomicBoolean userInterrupted = new AtomicBoolean(false);
    private final AtomicLong nextSeq = new AtomicLong();
    private final AtomicLong runningTokensIn = new AtomicLong();
    private final AtomicLong runningTokensOut = new AtomicLong();
    private final AtomicLong runningCostUsdMilli = new AtomicLong();
    private final AtomicReference<String> activeModel = new AtomicReference<>("");
    /** Per-tool auto-approval budget the user granted via "Allow next
     *  N". {@code -1} is the sentinel for "always for this tool" until
     *  the session ends. Lives only for the session — a stop /
     *  failed thread drops the map. */
    private final ConcurrentHashMap<String, Integer> toolBudget = new ConcurrentHashMap<>();
    /** Pending Allow / Deny prompts so {@link #stop} / {@link #interrupt}
     *  can cancel them rather than leak a waiting handler thread. */
    private final Set<String> pendingPermissions = ConcurrentHashMap.newKeySet();
    private static final int BUDGET_ALWAYS = -1;
    /** Default cap on how long we wait for a user Allow / Deny
     *  before treating the prompt as denied. Five minutes matches
     *  the CLI lane's MCP gate. */
    private static final Duration PERMISSION_WAIT_TIMEOUT = Duration.ofMinutes(5);

    /** Trunk turns are planning conversations — they read memory,
     *  inspect existing PRs / tasks, and spawn new tasks, but they
     *  don't execute the work themselves. Restricting the tool
     *  catalog to this allowlist on trunk turns cuts ~2-3 K input
     *  tokens per round (mostly schema bloat from publish + shell
     *  tools the trunk would never call). Task turns get the full
     *  catalog because they're the surface that actually executes.
     *
     *  <p>Tools that intentionally stay <em>off</em> the trunk:
     *  every {@code PublishToolHandlers} mutator (push, merge_pr,
     *  approve_pr, open_pr, post_comment, …), {@code run_shell},
     *  {@code run_checks}, {@code load_skill}, and the native
     *  {@code read_file}. Add a name here only after confirming the
     *  trunk can complete its planning loop without it. */
    private static final Set<String> TRUNK_TOOL_ALLOWLIST = Set.of(
            "recall_memory",
            "lookup_memory",
            "read_workspace_memory",
            "recall_thread",
            "list_skills",
            "list_tools",
            "list_prs",
            "read_pr",
            "read_task",
            "create_task");

    /** Mediator passed into every {@link AgentToolContext} so the
     *  bridged-CLI catalog can route through the permission gate.
     *  Lazy-stateless; one instance per agent. */
    private final ToolPermissionMediator permissionMediator = this::admitToolCall;

    public LogicLoopThreadAgent(
            Thread thread,
            ThreadStore store,
            TaskStore taskStore,
            ObjectMapper mapper,
            ExecutorService executor,
            CredentialService credentialService,
            WorkModel resolvedModel,
            String workingDir,
            String roleSkillText)
    {
        this(thread, store, taskStore, mapper, executor, credentialService,
                resolvedModel, workingDir, roleSkillText, /* toolRegistry */ null,
                /* ds4 */ null, /* ds4Instrumentation */ null,
                /* permissionGate */ null);
    }

    public LogicLoopThreadAgent(
            Thread thread,
            ThreadStore store,
            TaskStore taskStore,
            ObjectMapper mapper,
            ExecutorService executor,
            CredentialService credentialService,
            WorkModel resolvedModel,
            String workingDir,
            String roleSkillText,
            LogicLoopToolRegistry toolRegistry)
    {
        this(thread, store, taskStore, mapper, executor, credentialService,
                resolvedModel, workingDir, roleSkillText, toolRegistry,
                /* ds4 */ null, /* ds4Instrumentation */ null,
                /* permissionGate */ null);
    }

    public LogicLoopThreadAgent(
            Thread thread,
            ThreadStore store,
            TaskStore taskStore,
            ObjectMapper mapper,
            ExecutorService executor,
            CredentialService credentialService,
            WorkModel resolvedModel,
            String workingDir,
            String roleSkillText,
            LogicLoopToolRegistry toolRegistry,
            Ds4LifecycleService ds4,
            Ds4Instrumentation ds4Instrumentation)
    {
        this(thread, store, taskStore, mapper, executor, credentialService,
                resolvedModel, workingDir, roleSkillText, toolRegistry,
                ds4, ds4Instrumentation, /* permissionGate */ null);
    }

    public LogicLoopThreadAgent(
            Thread thread,
            ThreadStore store,
            TaskStore taskStore,
            ObjectMapper mapper,
            ExecutorService executor,
            CredentialService credentialService,
            WorkModel resolvedModel,
            String workingDir,
            String roleSkillText,
            LogicLoopToolRegistry toolRegistry,
            Ds4LifecycleService ds4,
            Ds4Instrumentation ds4Instrumentation,
            McpPermissionGate permissionGate)
    {
        this.threadId = thread.id();
        this.kind = thread.kind();
        this.store = requireNonNull(store, "store is null");
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
        this.executor = requireNonNull(executor, "executor is null");
        this.credentialService = requireNonNull(credentialService, "credentialService is null");
        this.resolvedModel = requireNonNull(resolvedModel, "resolvedModel is null");
        this.workingDir = workingDir;
        this.branchName = thread.activeTask() == null ? null : thread.activeTask().branchName();
        this.roleSkillText = roleSkillText;
        this.sessionId = thread.agentSessionId() == null
                ? "logic-loop-" + UUID.randomUUID()
                : thread.agentSessionId();
        this.status.set(thread.status() == null ? ThreadStatus.IDLE : thread.status());
        this.sessionStartedMs = System.currentTimeMillis();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        this.runningTokensIn.set(thread.tokensIn());
        this.runningTokensOut.set(thread.tokensOut());
        this.runningCostUsdMilli.set(thread.costUsdMilli());
        this.activeModel.set(thread.model() == null ? "" : thread.model());
        this.toolRegistry = toolRegistry;
        this.ds4 = ds4;
        this.ds4Instrumentation = ds4Instrumentation;
        this.permissionGate = permissionGate;
        store.maxMessageSeq(threadId).ifPresent(max -> nextSeq.set(max + 1));
    }

    // ── ThreadAgent interface ─────────────────────────────────────────────

    @Override
    public String id()
    {
        return sessionId;
    }

    @Override
    public ThreadKind kind()
    {
        return kind;
    }

    @Override
    public String provider()
    {
        return resolvedModel.agentOrProvider();
    }

    @Override
    public String model()
    {
        String live = activeModel.get();
        if (live != null && !live.isEmpty()) {
            return live;
        }
        return resolvedModel.model();
    }

    @Override
    public String workingDir()
    {
        return workingDir;
    }

    @Override
    public String branchName()
    {
        return branchName;
    }

    @Override
    public ThreadStatus status()
    {
        return status.get();
    }

    @Override
    public AgentMetrics metrics()
    {
        long runtime = Math.max(0L, System.currentTimeMillis() - sessionStartedMs);
        return new AgentMetrics(
                runtime,
                runningCostUsdMilli.get(),
                runningTokensIn.get(),
                runningTokensOut.get(),
                /* toolCallCount */ 0,
                /* filesTouched */ 0);
    }

    @Override
    public List<ThreadMessage> history()
    {
        return store.listMessages(threadId);
    }

    @Override
    public CompletionStage<Void> send(String userInput)
    {
        if (status.get() == ThreadStatus.AWAITING) {
            CompletableFuture<Void> done = new CompletableFuture<>();
            done.completeExceptionally(new IllegalStateException(
                    "session is paused; resume() before sending another turn"));
            return done;
        }
        ThreadStatus current = status.get();
        if (current == ThreadStatus.RUNNING) {
            CompletableFuture<Void> done = new CompletableFuture<>();
            done.completeExceptionally(new IllegalStateException(
                    "a turn is already in flight"));
            return done;
        }
        if (current == ThreadStatus.COMPLETED || current == ThreadStatus.ERRORED) {
            CompletableFuture<Void> done = new CompletableFuture<>();
            done.complete(null);
            return done;
        }
        userInterrupted.set(false);
        status.set(ThreadStatus.RUNNING);
        CompletableFuture<Void> turn = CompletableFuture.runAsync(
                () -> runTurn(userInput), executor);
        currentTurn.set(turn);
        return turn;
    }

    // ── Turn dispatch ─────────────────────────────────────────────────────

    private void runTurn(String userInput)
    {
        Instant now = Instant.now();
        persistUserMessage(userInput, now);
        publish(new StreamEvent.UserMessage(now, userInput));
        publish(new StreamEvent.SessionStarted(now, sessionId, workingDir, model()));

        try {
            String provider = resolvedModel.agentOrProvider();
            if (ANTHROPIC_PROVIDER_ID.equalsIgnoreCase(provider)) {
                runAnthropicTurn(userInput, now);
            }
            else if (OPENAI_PROVIDER_ID.equalsIgnoreCase(provider)
                    || DEEPSEEK_PROVIDER_ID.equalsIgnoreCase(provider)) {
                runOpenAiCompatibleTurn(userInput, now);
            }
            else {
                emitFatal("Provider '" + provider + "' is not supported by the API lane. "
                        + "Supported providers: anthropic, openai, deepseek.", now);
            }
        }
        catch (UnsupportedProviderException e) {
            emitFatal(e.getMessage(), now);
        }
        catch (RuntimeException e) {
            if (userInterrupted.get()) {
                publish(new StreamEvent.SessionEnded(Instant.now(), 0, null));
                status.set(ThreadStatus.IDLE);
                return;
            }
            log.warn("LogicLoopThreadAgent turn failed for thread {}: {}", threadId, e.getMessage());
            emitFatal(e.getMessage() == null ? "Provider call failed" : e.getMessage(), now);
        }
        finally {
            currentTurn.set(null);
        }
    }

    // ── Anthropic transport ───────────────────────────────────────────────

    private void runAnthropicTurn(String userInput, Instant turnStart)
    {
        if (resolvedModel.kind() != WorkModelKind.API) {
            throw new UnsupportedProviderException(
                    "LogicLoopThreadAgent expected an API work model but got "
                            + resolvedModel.kind());
        }
        String account = resolvedModel.account();
        Optional<String> apiKey = account == null || account.isBlank()
                ? credentialService.getSecret(CredentialType.AI, ANTHROPIC_PROVIDER_ID)
                : credentialService.getSecret(CredentialType.AI, ANTHROPIC_PROVIDER_ID, account);
        String key = apiKey.orElseThrow(() -> new IllegalStateException(
                "No Anthropic API key on file" + (account == null ? "" : " for account " + account)
                        + ". Add one in Settings → Credentials."));
        String modelId = resolvedModel.model() == null || resolvedModel.model().isBlank()
                ? "claude-sonnet-4-6"
                : resolvedModel.model();
        activeModel.set(modelId);

        String system = composeSystemPrompt();
        ArrayNode messages = buildMessageHistory(userInput);
        // Trunk turns get a narrow allowlist; task turns get the full
        // catalog. Saves ~3 K input tokens per trunk round (publish + shell
        // schemas are by far the heaviest). See TRUNK_TOOL_ALLOWLIST.
        Set<String> toolFilter = isTrunkTurn() ? TRUNK_TOOL_ALLOWLIST : null;
        ArrayNode toolsArray = toolRegistry == null
                ? null
                : toolRegistry.renderAsAnthropicTools(mapper, toolFilter);

        String finalText = "";
        long totalTokensIn = 0;
        long totalTokensOut = 0;

        for (int iteration = 0; iteration < MAX_TOOL_ITERATIONS; iteration++) {
            if (userInterrupted.get()) {
                return;
            }
            RoundResult round = runAnthropicRound(modelId, key, system, messages, toolsArray);
            totalTokensIn += round.tokensIn;
            totalTokensOut += round.tokensOut;
            finalText = round.text;
            if (round.toolUseBlocks.isEmpty()) {
                break;
            }
            messages.add(assistantContent(round.text, round.toolUseBlocks));
            messages.add(dispatchTools(round.toolUseBlocks));
        }

        finalizeTurn(modelId, turnStart, finalText, totalTokensIn, totalTokensOut);
    }

    /** One round-trip with the Anthropic provider: send the current message
     *  history, stream the response, collect text + any tool_use blocks. */
    private RoundResult runAnthropicRound(
            String modelId, String apiKey, String system,
            ArrayNode messages, ArrayNode toolsArray)
    {
        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", modelId);
        requestBody.put("max_tokens", MAX_OUTPUT_TOKENS);
        requestBody.put("stream", true);
        if (system != null && !system.isEmpty()) {
            requestBody.put("system", system);
        }
        requestBody.set("messages", messages);
        if (toolsArray != null && !toolsArray.isEmpty()) {
            requestBody.set("tools", toolsArray);
        }

        String payload;
        try {
            payload = mapper.writeValueAsString(requestBody);
        }
        catch (Exception e) {
            throw new IllegalStateException("Failed to encode Anthropic request body", e);
        }

        // Per-round byte breakdown so we can see whether tools, system
        // prompt, or conversation history is dominating input-token cost.
        // chars / 4 is a coarse-but-useful estimator for English + code.
        logPromptBreakdown("anthropic", modelId,
                payload.length(),
                system == null ? 0 : system.length(),
                messages.toString().length(),
                toolsArray == null ? 0 : toolsArray.toString().length());

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(ANTHROPIC_MESSAGES_URL))
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json")
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("x-api-key", apiKey)
                .header("accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        StringBuilder accumulated = new StringBuilder();
        List<ToolUseBlock> toolUseBlocks = new ArrayList<>();
        long roundTokensIn = 0;
        long roundTokensOut = 0;

        // Anthropic indexes content blocks within a single response;
        // tool_use input arrives as input_json_delta chunks under the same
        // index. Track per open index so text + tool_use don't interleave.
        Map<Integer, ToolUseBlock> openToolBlocks = new HashMap<>();

        try {
            HttpResponse<InputStream> response = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                String errBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                throw new IllegalStateException(
                        "Anthropic API returned " + response.statusCode() + ": " + errBody);
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (userInterrupted.get()) {
                        break;
                    }
                    if (line.isEmpty() || line.startsWith(":")) {
                        continue;
                    }
                    if (!line.startsWith("data: ")) {
                        continue;
                    }
                    String data = line.substring("data: ".length());
                    JsonNode frame;
                    try {
                        frame = mapper.readTree(data);
                    }
                    catch (Exception parseFail) {
                        continue;
                    }
                    String frameType = frame.path("type").asText("");
                    switch (frameType) {
                        case "content_block_start" -> {
                            int index = frame.path("index").asInt(0);
                            String blockType = frame.path("content_block").path("type").asText("");
                            if ("tool_use".equals(blockType)) {
                                String id = frame.path("content_block").path("id").asText("");
                                String toolName = frame.path("content_block").path("name").asText("");
                                openToolBlocks.put(index, new ToolUseBlock(id, toolName, new StringBuilder()));
                            }
                        }
                        case "content_block_delta" -> {
                            JsonNode delta = frame.path("delta");
                            String deltaType = delta.path("type").asText("");
                            if ("text_delta".equals(deltaType)) {
                                String chunk = delta.path("text").asText("");
                                if (!chunk.isEmpty()) {
                                    accumulated.append(chunk);
                                    publish(new StreamEvent.AssistantTextDelta(
                                            Instant.now(),
                                            frame.path("index").asInt(0),
                                            chunk));
                                }
                            }
                            else if ("input_json_delta".equals(deltaType)) {
                                int index = frame.path("index").asInt(0);
                                ToolUseBlock block = openToolBlocks.get(index);
                                if (block != null) {
                                    block.partialJson.append(delta.path("partial_json").asText(""));
                                }
                            }
                        }
                        case "content_block_stop" -> {
                            int index = frame.path("index").asInt(0);
                            ToolUseBlock block = openToolBlocks.remove(index);
                            if (block != null) {
                                toolUseBlocks.add(block);
                            }
                        }
                        case "message_delta" -> {
                            JsonNode usage = frame.path("usage");
                            if (usage.has("output_tokens")) {
                                roundTokensOut = usage.path("output_tokens").asLong(roundTokensOut);
                            }
                            if (usage.has("input_tokens")) {
                                roundTokensIn = usage.path("input_tokens").asLong(roundTokensIn);
                            }
                            publish(new StreamEvent.UsageUpdated(
                                    Instant.now(), roundTokensIn, roundTokensOut));
                        }
                        case "message_start" -> {
                            JsonNode usage = frame.path("message").path("usage");
                            if (usage.has("input_tokens")) {
                                roundTokensIn = usage.path("input_tokens").asLong(roundTokensIn);
                            }
                        }
                        default -> { /* ping, message_stop, etc — nothing to do */ }
                    }
                }
            }
        }
        catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                java.lang.Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Anthropic streaming failed: " + e.getMessage(), e);
        }

        return new RoundResult(accumulated.toString(), toolUseBlocks, roundTokensIn, roundTokensOut);
    }

    // ── OpenAI-compatible transport (OpenAI + DeepSeek) ───────────────────

    private void runOpenAiCompatibleTurn(String userInput, Instant turnStart)
    {
        if (resolvedModel.kind() != WorkModelKind.API) {
            throw new UnsupportedProviderException(
                    "LogicLoopThreadAgent expected an API work model but got "
                            + resolvedModel.kind());
        }
        String provider = resolvedModel.agentOrProvider();
        String modelId = resolvedModel.model() == null || resolvedModel.model().isBlank()
                ? defaultModelForProvider(provider)
                : resolvedModel.model();
        activeModel.set(modelId);

        String url;
        String token;

        if (DEEPSEEK_PROVIDER_ID.equalsIgnoreCase(provider)) {
            if (DEEPSEEK_LOCAL_MODEL_ID.equals(modelId)) {
                url = resolveLocalDs4Url();
                token = DEEPSEEK_LOCAL_AUTH_TOKEN;
            }
            else {
                url = DEEPSEEK_COMPLETIONS_URL;
                token = credentialService.getSecret(CredentialType.AI, DEEPSEEK_PROVIDER_ID)
                        .orElseThrow(() -> new IllegalStateException(
                                "No DeepSeek API key on file. Add one in Settings → Credentials."));
            }
        }
        else {
            // openai
            url = OPENAI_COMPLETIONS_URL;
            token = credentialService.getSecret(CredentialType.AI, OPENAI_PROVIDER_ID)
                    .orElseThrow(() -> new IllegalStateException(
                            "No OpenAI API key on file. Add one in Settings → Credentials."));
        }

        String system = composeSystemPrompt();
        ArrayNode messages = buildOpenAiMessages(system, userInput);
        Set<String> toolFilter = isTrunkTurn() ? TRUNK_TOOL_ALLOWLIST : null;
        ArrayNode toolsArray = toolRegistry == null
                ? null
                : toolRegistry.renderAsOpenAiTools(mapper, toolFilter);

        String finalText = "";
        long totalTokensIn = 0;
        long totalTokensOut = 0;

        boolean isLocalDs4 = DEEPSEEK_PROVIDER_ID.equalsIgnoreCase(provider)
                && DEEPSEEK_LOCAL_MODEL_ID.equals(modelId);

        for (int iteration = 0; iteration < MAX_TOOL_ITERATIONS; iteration++) {
            if (userInterrupted.get()) {
                return;
            }
            long roundStartNanos = System.nanoTime();
            OaiRoundResult round = runOpenAiCompatibleRound(modelId, token, url, messages, toolsArray);
            totalTokensIn += round.tokensIn;
            totalTokensOut += round.tokensOut;
            finalText = round.text;
            recordLocalDs4Sample(isLocalDs4, roundStartNanos, round);
            if (round.toolCallBlocks.isEmpty()) {
                break;
            }
            // Echo the assistant's tool_calls turn back into the history, then
            // append one role:tool message per result so the next round can
            // stitch results back to the requests by tool_call_id.
            messages.add(assistantContentOpenAi(round.text, round.toolCallBlocks));
            for (ObjectNode toolMsg : dispatchToolsOpenAi(round.toolCallBlocks)) {
                messages.add(toolMsg);
            }
        }

        finalizeTurn(modelId, turnStart, finalText, totalTokensIn, totalTokensOut);
    }

    /** Build the local ds4 chat-completions URL from the supervisor's
     *  live status so a user-configured ds4.port is honoured. Pre-
     *  validates the server is RUNNING; turning a 404 from a stopped
     *  server into a clear "open Settings → Local AI (ds4) to Start
     *  it" message saves the user from chasing an opaque ERRORED. */
    private String resolveLocalDs4Url()
    {
        if (ds4 == null) {
            return DEEPSEEK_LOCAL_COMPLETIONS_URL;
        }
        Ds4State state = ds4.status().state();
        if (state != Ds4State.RUNNING) {
            throw new IllegalStateException(
                    "Local ds4 server is " + state + "; open Settings → Local AI (ds4) "
                            + "to Start it (or pick a different work model).");
        }
        String endpoint = ds4.status().endpoint();
        if (endpoint == null || endpoint.isBlank()) {
            return DEEPSEEK_LOCAL_COMPLETIONS_URL;
        }
        return endpoint + "/v1/chat/completions";
    }

    /** Record one completed local-ds4 round into the metrics ring so
     *  the Metrics tab reflects thread turns alongside review calls.
     *  No-op when the round wasn't local-ds4 or the instrumentation
     *  isn't wired (test paths). */
    private void recordLocalDs4Sample(boolean isLocalDs4, long roundStartNanos, OaiRoundResult round)
    {
        if (!isLocalDs4 || ds4Instrumentation == null) {
            return;
        }
        long elapsedMs = Math.max(1L, (System.nanoTime() - roundStartNanos) / 1_000_000L);
        double tps = round.tokensOut == 0 ? 0.0 : (round.tokensOut * 1000.0) / elapsedMs;
        String caller = activeTaskId() == null ? "trunk" : "task";
        ds4Instrumentation.record(Ds4Instrumentation.Sample.of(
                caller, "/v1/chat/completions",
                round.tokensIn, round.tokensOut, tps, elapsedMs, "200"));
    }

    /** One round-trip with an OpenAI-compatible provider. Parses the
     *  {@code choices[0].delta} SSE format common to OpenAI and DeepSeek. */
    private OaiRoundResult runOpenAiCompatibleRound(
            String modelId, String token, String url,
            ArrayNode messages, ArrayNode toolsArray)
    {
        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", modelId);
        requestBody.put("max_tokens", MAX_OUTPUT_TOKENS);
        requestBody.put("stream", true);
        // Request token counts in the final streaming chunk.
        ObjectNode streamOpts = mapper.createObjectNode();
        streamOpts.put("include_usage", true);
        requestBody.set("stream_options", streamOpts);
        requestBody.set("messages", messages);
        if (toolsArray != null && !toolsArray.isEmpty()) {
            requestBody.set("tools", toolsArray);
        }

        String payload;
        try {
            payload = mapper.writeValueAsString(requestBody);
        }
        catch (Exception e) {
            throw new IllegalStateException("Failed to encode OpenAI-compatible request body", e);
        }

        // OpenAI puts the system prompt inside messages[0] (role=system),
        // so the system length is pulled out of there rather than passed
        // separately. Everything else mirrors the Anthropic round.
        int sysCharsOai = extractSystemContentLength(messages);
        logPromptBreakdown("openai", modelId,
                payload.length(),
                sysCharsOai,
                messages.toString().length(),
                toolsArray == null ? 0 : toolsArray.toString().length());

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMinutes(5))
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + token)
                .header("accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        StringBuilder accumulated = new StringBuilder();
        // Tool call accumulation indexed by the provider's tool_calls[i].index.
        Map<Integer, OaiToolCallBlock> openToolCalls = new HashMap<>();
        long roundTokensIn = 0;
        long roundTokensOut = 0;

        try {
            HttpResponse<InputStream> response = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                String errBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                throw new IllegalStateException(
                        "OpenAI-compatible API returned " + response.statusCode() + ": " + errBody);
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (userInterrupted.get()) {
                        break;
                    }
                    if (line.isEmpty() || line.startsWith(":")) {
                        continue;
                    }
                    if (!line.startsWith("data: ")) {
                        continue;
                    }
                    String data = line.substring("data: ".length()).trim();
                    if ("[DONE]".equals(data)) {
                        break;
                    }
                    JsonNode frame;
                    try {
                        frame = mapper.readTree(data);
                    }
                    catch (Exception parseFail) {
                        continue;
                    }

                    // Usage — sent in a dedicated frame when stream_options.include_usage is set.
                    JsonNode usageNode = frame.path("usage");
                    if (!usageNode.isMissingNode() && !usageNode.isNull()) {
                        if (usageNode.has("prompt_tokens")) {
                            roundTokensIn = usageNode.path("prompt_tokens").asLong(roundTokensIn);
                        }
                        if (usageNode.has("completion_tokens")) {
                            roundTokensOut = usageNode.path("completion_tokens").asLong(roundTokensOut);
                        }
                        publish(new StreamEvent.UsageUpdated(
                                Instant.now(), roundTokensIn, roundTokensOut));
                    }

                    JsonNode choices = frame.path("choices");
                    if (!choices.isArray() || choices.isEmpty()) {
                        continue;
                    }
                    JsonNode delta = choices.path(0).path("delta");

                    // Text content
                    if (delta.has("content") && !delta.path("content").isNull()) {
                        String chunk = delta.path("content").asText("");
                        if (!chunk.isEmpty()) {
                            accumulated.append(chunk);
                            publish(new StreamEvent.AssistantTextDelta(Instant.now(), 0, chunk));
                        }
                    }

                    // Tool calls — id and name arrive only in the first chunk for each index;
                    // subsequent chunks carry additional argument fragments.
                    JsonNode toolCallsNode = delta.path("tool_calls");
                    if (toolCallsNode.isArray()) {
                        for (JsonNode tc : toolCallsNode) {
                            int tcIndex = tc.path("index").asInt(0);
                            OaiToolCallBlock block = openToolCalls.get(tcIndex);
                            if (block == null) {
                                String id = tc.path("id").asText("");
                                String name = tc.path("function").path("name").asText("");
                                block = new OaiToolCallBlock(id, name, new StringBuilder());
                                openToolCalls.put(tcIndex, block);
                            }
                            String argChunk = tc.path("function").path("arguments").asText("");
                            if (!argChunk.isEmpty()) {
                                block.partialArgs.append(argChunk);
                            }
                        }
                    }
                }
            }
        }
        catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                java.lang.Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("OpenAI-compatible streaming failed: " + e.getMessage(), e);
        }

        List<OaiToolCallBlock> toolCallBlocks = new ArrayList<>(openToolCalls.values());
        return new OaiRoundResult(accumulated.toString(), toolCallBlocks, roundTokensIn, roundTokensOut);
    }

    // ── Message assembly helpers ──────────────────────────────────────────

    /** Assemble the assistant's role message echoing the round's text +
     *  the tool_use blocks (Anthropic format). Anthropic requires this
     *  exact shape on follow-up turns so the model can stitch tool_result
     *  back to the prior request. */
    private ObjectNode assistantContent(String text, List<ToolUseBlock> toolUses)
    {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("role", "assistant");
        ArrayNode content = mapper.createArrayNode();
        if (text != null && !text.isEmpty()) {
            ObjectNode textBlock = mapper.createObjectNode();
            textBlock.put("type", "text");
            textBlock.put("text", text);
            content.add(textBlock);
        }
        for (ToolUseBlock block : toolUses) {
            ObjectNode useBlock = mapper.createObjectNode();
            useBlock.put("type", "tool_use");
            useBlock.put("id", block.id);
            useBlock.put("name", block.name);
            useBlock.set("input", parseToolInput(block.partialJson.toString()));
            content.add(useBlock);
        }
        msg.set("content", content);
        return msg;
    }

    /** Assemble the assistant message carrying OpenAI-format tool_calls.
     *  The provider requires this as the message immediately preceding the
     *  role:tool result messages on the next round. */
    private ObjectNode assistantContentOpenAi(String text, List<OaiToolCallBlock> toolCalls)
    {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("role", "assistant");
        if (text != null && !text.isEmpty()) {
            msg.put("content", text);
        }
        else {
            msg.putNull("content");
        }
        ArrayNode toolCallsArray = mapper.createArrayNode();
        for (OaiToolCallBlock block : toolCalls) {
            ObjectNode tc = mapper.createObjectNode();
            tc.put("id", block.id);
            tc.put("type", "function");
            ObjectNode fn = mapper.createObjectNode();
            fn.put("name", block.name);
            fn.put("arguments", block.partialArgs.toString());
            tc.set("function", fn);
            toolCallsArray.add(tc);
        }
        msg.set("tool_calls", toolCallsArray);
        return msg;
    }

    // ── Tool dispatch ─────────────────────────────────────────────────────

    /** Dispatch each tool_use block (Anthropic) and build the follow-up
     *  user message whose content carries every tool_result. */
    private ObjectNode dispatchTools(List<ToolUseBlock> toolUses)
    {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("role", "user");
        ArrayNode content = mapper.createArrayNode();
        for (ToolUseBlock block : toolUses) {
            JsonNode input = parseToolInput(block.partialJson.toString());
            String inputJson;
            try {
                inputJson = mapper.writeValueAsString(input);
            }
            catch (Exception e) {
                inputJson = "{}";
            }
            publish(new StreamEvent.ToolCallStarted(
                    Instant.now(), block.id, block.name, inputJson));
            persistToolCall(block.id, block.name, inputJson);
            AgentTool.Result result = invokeTool(block.name, input);
            String outputJson;
            try {
                outputJson = mapper.writeValueAsString(
                        mapper.createObjectNode().put("text", result.text()));
            }
            catch (Exception e) {
                outputJson = "{\"text\":\"\"}";
            }
            publish(new StreamEvent.ToolCallDone(
                    Instant.now(), block.id, outputJson, result.isError()));
            persistToolResult(block.id, result.text(), result.isError());

            ObjectNode resultBlock = mapper.createObjectNode();
            resultBlock.put("type", "tool_result");
            resultBlock.put("tool_use_id", block.id);
            resultBlock.put("content", result.text());
            if (result.isError()) {
                resultBlock.put("is_error", true);
            }
            content.add(resultBlock);
        }
        msg.set("content", content);
        return msg;
    }

    /** Dispatch each tool call (OpenAI format). Returns one role:tool
     *  message per call; callers append each to the messages array. */
    private List<ObjectNode> dispatchToolsOpenAi(List<OaiToolCallBlock> toolCalls)
    {
        List<ObjectNode> toolMessages = new ArrayList<>();
        for (OaiToolCallBlock block : toolCalls) {
            JsonNode input = parseToolInput(block.partialArgs.toString());
            String inputJson;
            try {
                inputJson = mapper.writeValueAsString(input);
            }
            catch (Exception e) {
                inputJson = "{}";
            }
            publish(new StreamEvent.ToolCallStarted(
                    Instant.now(), block.id, block.name, inputJson));
            persistToolCall(block.id, block.name, inputJson);
            AgentTool.Result result = invokeTool(block.name, input);
            String outputJson;
            try {
                outputJson = mapper.writeValueAsString(
                        mapper.createObjectNode().put("text", result.text()));
            }
            catch (Exception e) {
                outputJson = "{\"text\":\"\"}";
            }
            publish(new StreamEvent.ToolCallDone(
                    Instant.now(), block.id, outputJson, result.isError()));
            persistToolResult(block.id, result.text(), result.isError());

            ObjectNode toolMsg = mapper.createObjectNode();
            toolMsg.put("role", "tool");
            toolMsg.put("tool_call_id", block.id);
            toolMsg.put("content", result.text() == null ? "" : result.text());
            toolMessages.add(toolMsg);
        }
        return toolMessages;
    }

    private JsonNode parseToolInput(String raw)
    {
        if (raw == null || raw.isEmpty()) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(raw);
        }
        catch (Exception e) {
            return mapper.createObjectNode();
        }
    }

    private AgentTool.Result invokeTool(String name, JsonNode input)
    {
        if (toolRegistry == null) {
            return AgentTool.Result.error(
                    "No tool registry wired for this session — text-only mode.");
        }
        Optional<AgentTool> tool = toolRegistry.find(name);
        if (tool.isEmpty()) {
            return AgentTool.Result.error(
                    "Unknown tool: " + name + ". Available: " + toolRegistry.list().stream()
                            .map(AgentTool::name).toList());
        }
        Path cwd = workingDir == null ? null : Path.of(workingDir);
        String taskId = activeTaskId();
        try {
            return tool.get().invoke(input, new AgentToolContext(
                    threadId, taskId, cwd, permissionMediator));
        }
        catch (RuntimeException e) {
            log.warn("Tool {} threw on thread {}: {}", name, threadId, e.getMessage());
            return AgentTool.Result.error("Tool '" + name + "' failed: " + e.getMessage());
        }
    }

    // ── Persistence helpers ───────────────────────────────────────────────

    private void persistToolCall(String callId, String toolName, String inputJson)
    {
        long seq = nextSeq.getAndIncrement();
        ObjectNode body = mapper.createObjectNode();
        body.put("callId", callId);
        body.put("toolName", toolName);
        body.set("input", parseToolInput(inputJson));
        String contentJson;
        try {
            contentJson = mapper.writeValueAsString(body);
        }
        catch (Exception e) {
            contentJson = "{}";
        }
        store.appendMessage(new ThreadMessage(
                UUID.randomUUID().toString(), threadId, activeTaskId(), seq,
                "tool", "tool_call", contentJson,
                null, null, null, null, Instant.now()));
    }

    private void persistToolResult(String callId, String text, boolean isError)
    {
        long seq = nextSeq.getAndIncrement();
        ObjectNode body = mapper.createObjectNode();
        body.put("callId", callId);
        body.put("text", text == null ? "" : text);
        body.put("isError", isError);
        String contentJson;
        try {
            contentJson = mapper.writeValueAsString(body);
        }
        catch (Exception e) {
            contentJson = "{}";
        }
        store.appendMessage(new ThreadMessage(
                UUID.randomUUID().toString(), threadId, activeTaskId(), seq,
                "tool", "tool_result", contentJson,
                null, null, null, null, Instant.now()));
    }

    // ── Inner types ───────────────────────────────────────────────────────

    /** Bookkeeping for Anthropic tool_use blocks. Partial input JSON
     *  accumulates via input_json_delta frames and is finalised on
     *  content_block_stop. */
    private static final class ToolUseBlock
    {
        final String id;
        final String name;
        final StringBuilder partialJson;

        ToolUseBlock(String id, String name, StringBuilder partialJson)
        {
            this.id = id;
            this.name = name;
            this.partialJson = partialJson;
        }
    }

    private record RoundResult(
            String text,
            List<ToolUseBlock> toolUseBlocks,
            long tokensIn,
            long tokensOut)
    {
    }

    /** Bookkeeping for OpenAI-compatible tool_calls. The provider sends
     *  id + name only in the first chunk for each index; subsequent chunks
     *  carry argument fragments. */
    private static final class OaiToolCallBlock
    {
        final String id;
        final String name;
        final StringBuilder partialArgs;

        OaiToolCallBlock(String id, String name, StringBuilder partialArgs)
        {
            this.id = id;
            this.name = name;
            this.partialArgs = partialArgs;
        }
    }

    private record OaiRoundResult(
            String text,
            List<OaiToolCallBlock> toolCallBlocks,
            long tokensIn,
            long tokensOut)
    {
    }

    // ── Prompt / history builders ─────────────────────────────────────────

    private String composeSystemPrompt()
    {
        if (roleSkillText == null || roleSkillText.isBlank()) {
            return null;
        }
        return roleSkillText.trim();
    }

    /** One-line breakdown of the outbound request payload so we can
     *  spot which axis is dominating input-token cost. The estimator is
     *  {@code chars / 4} which is widely-used as a rough English+code
     *  approximation; close enough to distinguish 500-token bloat from
     *  5000-token bloat without paying for a real tokenizer. Fires per
     *  round, so a multi-iteration tool turn logs once per iteration.
     *
     *  <p>Note on OpenAI vs Anthropic: in OpenAI's wire shape the
     *  system prompt lives inside {@code messages[0]}, so the messages
     *  count there already includes the system bytes. We log both
     *  numbers anyway for parity — the rest (history + user) is
     *  {@code messages - system} for OpenAI and {@code messages} for
     *  Anthropic. */
    private static void logPromptBreakdown(
            String provider, String modelId,
            int totalChars, int systemChars, int messagesChars, int toolsChars)
    {
        log.info(
                "[prompt-bytes] provider={} model={} total={}c (~{}t)  tools={}c (~{}t)  system={}c (~{}t)  messages={}c (~{}t)",
                provider, modelId,
                totalChars, totalChars / 4,
                toolsChars, toolsChars / 4,
                systemChars, systemChars / 4,
                messagesChars, messagesChars / 4);
    }

    /** Length of the {@code content} field on the first
     *  {@code role: "system"} entry of an OpenAI-shape messages array,
     *  or 0 if none. Used only by {@link #logPromptBreakdown} — we
     *  don't otherwise inspect messages by role. */
    private static int extractSystemContentLength(ArrayNode messages)
    {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        var first = messages.get(0);
        if (first == null || !first.isObject()) {
            return 0;
        }
        var role = first.get("role");
        if (role == null || !"system".equals(role.asText())) {
            return 0;
        }
        var content = first.get("content");
        return content == null ? 0 : content.asText().length();
    }

    /** Build the Anthropic message history array (no system message —
     *  Anthropic takes the system prompt as a top-level field). */
    private ArrayNode buildMessageHistory(String userInput)
    {
        ArrayNode messages = mapper.createArrayNode();
        List<ThreadMessage> tail = store.listRecentMessages(threadId, REPLAY_HISTORY_LIMIT);
        for (ThreadMessage row : tail) {
            String role = row.role();
            if (!"user".equals(role) && !"assistant".equals(role)) {
                continue;
            }
            if (!"text".equals(row.type())) {
                continue;
            }
            String text = extractText(row.contentJson());
            if (text == null || text.isEmpty()) {
                continue;
            }
            ObjectNode msg = mapper.createObjectNode();
            msg.put("role", role);
            msg.put("content", text);
            messages.add(msg);
        }
        ObjectNode userMsg = mapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", userInput == null ? "" : userInput);
        messages.add(userMsg);
        return messages;
    }

    /** Build the OpenAI-compatible message list. The system prompt goes as
     *  the first role:system message; OpenAI does not accept a top-level
     *  {@code system} field. */
    private ArrayNode buildOpenAiMessages(String system, String userInput)
    {
        ArrayNode messages = mapper.createArrayNode();
        if (system != null && !system.isEmpty()) {
            ObjectNode sysMsg = mapper.createObjectNode();
            sysMsg.put("role", "system");
            sysMsg.put("content", system.trim());
            messages.add(sysMsg);
        }
        List<ThreadMessage> tail = store.listRecentMessages(threadId, REPLAY_HISTORY_LIMIT);
        for (ThreadMessage row : tail) {
            String role = row.role();
            if (!"user".equals(role) && !"assistant".equals(role)) {
                continue;
            }
            if (!"text".equals(row.type())) {
                continue;
            }
            String text = extractText(row.contentJson());
            if (text == null || text.isEmpty()) {
                continue;
            }
            ObjectNode msg = mapper.createObjectNode();
            msg.put("role", role);
            msg.put("content", text);
            messages.add(msg);
        }
        ObjectNode userMsg = mapper.createObjectNode();
        userMsg.put("role", "user");
        userMsg.put("content", userInput == null ? "" : userInput);
        messages.add(userMsg);
        return messages;
    }

    private String extractText(String contentJson)
    {
        if (contentJson == null || contentJson.isEmpty()) {
            return null;
        }
        try {
            JsonNode node = mapper.readTree(contentJson);
            return node.path("text").asText(null);
        }
        catch (Exception e) {
            return null;
        }
    }

    // ── Cost / model helpers ──────────────────────────────────────────────

    /** Look up per-model pricing and compute the turn cost in milli-USD.
     *  Falls back to Sonnet-class pricing for unrecognised model ids. */
    private static long estimateCostMilli(String modelId, long tokensIn, long tokensOut)
    {
        return ModelPricing.estimateCostMilli(modelId, tokensIn, tokensOut);
    }

    private static String defaultModelForProvider(String provider)
    {
        if (DEEPSEEK_PROVIDER_ID.equalsIgnoreCase(provider)) {
            return "deepseek-chat";
        }
        // openai
        return "gpt-4o-mini";
    }

    // ── Turn finalisation ─────────────────────────────────────────────────

    /** Shared post-loop bookkeeping: persist the assistant message, emit
     *  the summary events, update running totals, and mark the session idle. */
    private void finalizeTurn(
            String modelId, Instant turnStart, String finalText,
            long totalTokensIn, long totalTokensOut)
    {
        Instant finishedAt = Instant.now();
        long durationMs = Math.max(0L, finishedAt.toEpochMilli() - turnStart.toEpochMilli());
        runningTokensIn.addAndGet(totalTokensIn);
        runningTokensOut.addAndGet(totalTokensOut);
        long turnCostMilli = estimateCostMilli(modelId, totalTokensIn, totalTokensOut);
        runningCostUsdMilli.addAndGet(turnCostMilli);

        persistAssistantMessage(finalText, finishedAt, durationMs,
                totalTokensIn, totalTokensOut, turnCostMilli);
        publish(new StreamEvent.AssistantText(finishedAt, finalText));
        publish(new StreamEvent.TurnDone(finishedAt, durationMs, turnCostMilli,
                totalTokensIn, totalTokensOut));
        status.set(ThreadStatus.IDLE);
        persistThreadProgress();
    }

    // ── Message persistence ───────────────────────────────────────────────

    private void persistUserMessage(String text, Instant ts)
    {
        long seq = nextSeq.getAndIncrement();
        String contentJson = encodeText(text);
        store.appendMessage(new ThreadMessage(
                UUID.randomUUID().toString(), threadId, activeTaskId(), seq,
                "user", "text", contentJson,
                /* durationMs */ null, /* tokensIn */ null, /* tokensOut */ null,
                /* costUsdMilli */ null, ts));
    }

    private void persistAssistantMessage(
            String text, Instant ts, long durationMs,
            long tokensIn, long tokensOut, long costMilli)
    {
        long seq = nextSeq.getAndIncrement();
        store.appendMessage(new ThreadMessage(
                UUID.randomUUID().toString(), threadId, activeTaskId(), seq,
                "assistant", "text", encodeText(text),
                durationMs, tokensIn, tokensOut, costMilli, ts));
    }

    /** Bind every persisted row to the foreground task at write time so
     *  jumping back to a parked sibling never mixes histories. */
    private String activeTaskId()
    {
        return taskStore.findActiveTaskForThread(threadId)
                .map(t -> t.id())
                .orElse(null);
    }

    /** True when this turn is happening at the trunk (planning) level
     *  rather than inside a task's execution window. Reused by the
     *  tool-catalog filter — see {@link #TRUNK_TOOL_ALLOWLIST}.
     *
     *  <p>"No active task on the thread" is a good-enough proxy for
     *  "this is a trunk turn" because the trunk agent only runs when
     *  the user typed into the trunk composer (which doesn't open a
     *  task), and the task agent only runs while a task is active.
     *  If we later allow trunk turns while a task is also active
     *  (e.g. background planning while a task executes), we'll plumb
     *  an explicit {@code isTrunk} flag through the constructor and
     *  switch this to read it. */
    private boolean isTrunkTurn()
    {
        return activeTaskId() == null;
    }

    private String encodeText(String text)
    {
        try {
            ObjectNode node = mapper.createObjectNode();
            node.put("text", text == null ? "" : text);
            return mapper.writeValueAsString(node);
        }
        catch (Exception e) {
            throw new IllegalStateException("Failed to encode message text", e);
        }
    }

    // ── Event publishing ──────────────────────────────────────────────────

    private void publish(StreamEvent event)
    {
        for (Consumer<StreamEvent> listener : listeners) {
            try {
                listener.accept(event);
            }
            catch (RuntimeException e) {
                log.warn("LogicLoopThreadAgent subscriber failed on {}: {}",
                        event.getClass().getSimpleName(), e.getMessage());
            }
        }
    }

    private void emitFatal(String message, Instant turnStart)
    {
        Instant now = Instant.now();
        publish(new StreamEvent.ErrorOccurred(now, message, /* recoverable */ false));
        publish(new StreamEvent.SessionEnded(now, /* exitCode */ 1, message));
        status.set(ThreadStatus.ERRORED);
        long durationMs = Math.max(0L, now.toEpochMilli() - turnStart.toEpochMilli());
        publish(new StreamEvent.TurnDone(now, durationMs, 0L, 0L, 0L));
        persistThreadProgress();
    }

    /** Mirror the agent's running totals back to the Thread row so the
     *  metrics strip and the dashboard's per-thread spend stay in sync. */
    private void persistThreadProgress()
    {
        Optional<Thread> current = store.findThreadById(threadId);
        if (current.isEmpty()) {
            return;
        }
        Thread t = current.get();
        Thread next = new Thread(
                t.id(), t.kind(), t.provider(), t.agentSessionId(),
                t.title(), status.get(),
                model(),
                runningCostUsdMilli.get(), runningTokensIn.get(), runningTokensOut.get(),
                t.createdAt(), Instant.now(),
                t.endedAt(), t.errorMessage(),
                t.flow(), t.workspaceId(), t.workModel(), t.activeTask());
        store.saveThread(next);
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────

    @Override
    public void interrupt()
    {
        userInterrupted.set(true);
        cancelPendingPermissions();
        CompletableFuture<Void> turn = currentTurn.get();
        if (turn != null) {
            turn.cancel(true);
        }
    }

    @Override
    public void pause()
    {
        ThreadStatus current = status.get();
        if (current == ThreadStatus.RUNNING || current == ThreadStatus.IDLE) {
            status.set(ThreadStatus.AWAITING);
        }
    }

    @Override
    public void resume()
    {
        if (status.get() == ThreadStatus.AWAITING) {
            status.set(ThreadStatus.IDLE);
        }
    }

    @Override
    public void stop()
    {
        userInterrupted.set(true);
        cancelPendingPermissions();
        CompletableFuture<Void> turn = currentTurn.get();
        if (turn != null) {
            turn.cancel(true);
        }
        status.set(ThreadStatus.COMPLETED);
        publish(new StreamEvent.SessionEnded(Instant.now(), 0, null));
    }

    /** Cancel every outstanding Allow / Deny prompt so a stop /
     *  interrupt doesn't leak handler threads blocked on the gate. */
    private void cancelPendingPermissions()
    {
        if (permissionGate == null) {
            return;
        }
        for (String callId : pendingPermissions) {
            permissionGate.cancel(callId);
        }
        pendingPermissions.clear();
    }

    @Override
    public void notifyPermissionRequested(String callId, String toolName, String summary)
    {
        // The mediator publishes its own PermissionRequested event,
        // so this hook is only useful as a structural log for
        // surprise registrations.
        log.debug("notifyPermissionRequested: callId={}, tool={}", callId, toolName);
    }

    @Override
    public void decide(String callId, PermissionDecision decision)
    {
        if (permissionGate != null) {
            permissionGate.decide(callId, decision);
        }
    }

    @Override
    public void grantToolBudget(String toolName, int count)
    {
        if (toolName == null || toolName.isBlank()) {
            return;
        }
        toolBudget.merge(toolName, count, (existing, add) -> {
            if (existing == BUDGET_ALWAYS || add == BUDGET_ALWAYS) {
                return BUDGET_ALWAYS;
            }
            return existing + add;
        });
        // Drain any pending Allow / Deny prompts for the same tool —
        // a user who just clicked "Allow next 5" shouldn't have to
        // click Approve N more times for the calls that piled up.
        if (permissionGate != null) {
            for (String pendingCallId : permissionGate.pendingCallIdsFor(toolName)) {
                OptionalInt slot = tryConsumeToolBudget(toolName);
                if (slot.isEmpty()) {
                    break;
                }
                permissionGate.decide(pendingCallId, PermissionDecision.ALLOW);
                publish(new StreamEvent.PermissionAutoAllowed(
                        Instant.now(), pendingCallId, toolName, slot.getAsInt()));
            }
        }
    }

    @Override
    public OptionalInt tryConsumeToolBudget(String toolName)
    {
        if (toolName == null) {
            return OptionalInt.empty();
        }
        // Atomic decrement with the sentinel kept intact for ALWAYS
        // grants. computeIfPresent returns the new value (or null if
        // the entry was removed) so the caller knows what's left.
        Integer remaining = toolBudget.computeIfPresent(toolName, (k, v) -> {
            if (v == BUDGET_ALWAYS) {
                return BUDGET_ALWAYS;
            }
            int next = v - 1;
            return next <= 0 ? null : next;
        });
        if (remaining == null) {
            // Either no budget existed, or the last slot was just
            // consumed and the entry removed.
            return toolBudget.containsKey(toolName) ? OptionalInt.empty() : OptionalInt.of(0);
        }
        if (remaining == BUDGET_ALWAYS) {
            return OptionalInt.of(BUDGET_ALWAYS);
        }
        return OptionalInt.of(remaining);
    }

    @Override
    public void notifyPermissionAutoAllowed(String callId, String toolName, int remaining)
    {
        publish(new StreamEvent.PermissionAutoAllowed(Instant.now(), callId, toolName, remaining));
    }

    /** {@link ToolPermissionMediator} body. Lives on the agent so it
     *  can drive the gate, the budget, and the event stream the UI
     *  listens to — none of those are accessible from inside the
     *  registry singleton. */
    private PermissionDecision admitToolCall(String callId, String toolName, Gating gating, String summary)
    {
        if (gating == Gating.AUTO) {
            return PermissionDecision.ALLOW;
        }
        if (gating == Gating.PARKED) {
            // Parked tools (request_review, push, merge_pr, …) ride
            // the CLI lane's notification + publish-service flow.
            // Refusing here is safer than running on the model's
            // word; the bridge surfaces a clear pointer to the CLI
            // lane in its error envelope.
            return PermissionDecision.DENY;
        }
        // GATED — consult the per-tool budget first, then prompt.
        OptionalInt slot = tryConsumeToolBudget(toolName);
        if (slot.isPresent()) {
            int left = slot.getAsInt();
            publish(new StreamEvent.PermissionAutoAllowed(Instant.now(), callId, toolName, left));
            return PermissionDecision.ALLOW;
        }
        if (permissionGate == null) {
            // No gate wired — refuse rather than silently allow.
            return PermissionDecision.DENY;
        }
        CompletableFuture<PermissionDecision> future = permissionGate.register(callId, toolName);
        pendingPermissions.add(callId);
        publish(new StreamEvent.PermissionRequested(Instant.now(), callId, toolName, summary));
        try {
            PermissionDecision decision = future.get(
                    PERMISSION_WAIT_TIMEOUT.toSeconds(), TimeUnit.SECONDS);
            publish(new StreamEvent.PermissionDecided(Instant.now(), callId, decision));
            return decision;
        }
        catch (TimeoutException e) {
            permissionGate.cancel(callId);
            publish(new StreamEvent.PermissionDecided(Instant.now(), callId, PermissionDecision.DENY));
            return PermissionDecision.DENY;
        }
        catch (InterruptedException e) {
            java.lang.Thread.currentThread().interrupt();
            permissionGate.cancel(callId);
            return PermissionDecision.DENY;
        }
        catch (ExecutionException e) {
            log.warn("Permission gate future failed for {}/{}: {}", callId, toolName, e.getMessage());
            return PermissionDecision.DENY;
        }
        finally {
            pendingPermissions.remove(callId);
        }
    }

    @Override
    public Runnable subscribeToEvents(Consumer<StreamEvent> listener)
    {
        requireNonNull(listener, "listener is null");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** Distinct exception so the runTurn catch can branch on it and show
     *  a clear "wrong provider" message instead of a generic failure. */
    private static final class UnsupportedProviderException
            extends RuntimeException
    {
        UnsupportedProviderException(String message)
        {
            super(message);
        }
    }
}
