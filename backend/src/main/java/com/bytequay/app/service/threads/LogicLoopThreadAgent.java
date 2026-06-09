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
import com.bytequay.app.service.threads.tools.AgentTool;
import com.bytequay.app.service.threads.tools.AgentToolContext;
import com.bytequay.app.service.threads.tools.LogicLoopToolRegistry;
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
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static java.util.Objects.requireNonNull;

/**
 * In-JVM API-lane {@link ThreadAgent}. Drives the loop by calling the
 * resolved provider's HTTP API directly and translating streaming
 * deltas into the same {@link StreamEvent} shapes the CLI lane emits.
 *
 * <p>B3 vertical slice: text-only. The resolver's choice picks the
 * provider id and model id; we only know how to talk to Anthropic for
 * now (B5 adds OpenAI + DeepSeek transports). Tools are NOT wired —
 * the request body omits the {@code tools} block so the model never
 * tries to call one. B4 introduces an in-JVM tool registry + permission
 * gate hook; until then a user who needs tools should pin a CLI work
 * model.
 *
 * <p>Mirrors {@link ClaudeCodeCliThreadAgent} on the contract that
 * matters for callers: same {@link ThreadStore#appendMessage} writes,
 * same event ordering (SessionStarted → AssistantTextDelta… →
 * AssistantText → UsageUpdated → TurnDone → SessionEnded), same
 * lifecycle state machine (IDLE/RUNNING/AWAITING/STOPPED), so the
 * frontend renders trunk and task panes identically across lanes.
 */
public class LogicLoopThreadAgent
        implements ThreadAgent
{
    private static final Logger log = LoggerFactory.getLogger(LogicLoopThreadAgent.class);

    private static final String ANTHROPIC_PROVIDER_ID = "anthropic";
    private static final String ANTHROPIC_MESSAGES_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final int MAX_OUTPUT_TOKENS = 4_096;
    /** History tail we replay back to the provider on each turn. The
     *  loop has no built-in resume id, so we send the recent context
     *  every turn. Bound by a row count rather than a token budget
     *  because the table is small in v1; B5 can tighten this when long
     *  task histories show up. */
    private static final int REPLAY_HISTORY_LIMIT = 80;

    /** Safety cap on the per-user-turn tool-use ↔ tool-result loop so a
     *  confused model can't burn the user's API budget on infinite
     *  fans. Reset on every {@code send()}. */
    private static final int MAX_TOOL_ITERATIONS = 12;

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
    private final CopyOnWriteArrayList<Consumer<StreamEvent>> listeners = new CopyOnWriteArrayList<>();

    private final AtomicReference<ThreadStatus> status = new AtomicReference<>();
    private final AtomicReference<CompletableFuture<Void>> currentTurn = new AtomicReference<>();
    private final AtomicBoolean userInterrupted = new AtomicBoolean(false);
    private final AtomicLong nextSeq = new AtomicLong();
    private final AtomicLong runningTokensIn = new AtomicLong();
    private final AtomicLong runningTokensOut = new AtomicLong();
    private final AtomicLong runningCostUsdMilli = new AtomicLong();
    private final AtomicReference<String> activeModel = new AtomicReference<>("");

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
                resolvedModel, workingDir, roleSkillText, /* toolRegistry */ null);
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
        // Seed seq from the persisted tail so restarting the agent on
        // an existing thread doesn't reuse seqs.
        store.maxMessageSeq(threadId).ifPresent(max -> nextSeq.set(max + 1));
    }

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

    private void runTurn(String userInput)
    {
        Instant now = Instant.now();
        // Persist + announce the user message before we hit the wire so
        // the conversation pane shows what was sent even if the API
        // call fails mid-turn.
        persistUserMessage(userInput, now);
        publish(new StreamEvent.UserMessage(now, userInput));
        publish(new StreamEvent.SessionStarted(now, sessionId, workingDir, model()));

        try {
            runAnthropicTurn(userInput, now);
        }
        catch (UnsupportedProviderException e) {
            // The resolver picked a provider B3 doesn't know how to talk
            // to yet. Fail loudly so the user sees the exact mismatch
            // rather than a silent stall.
            emitFatal(e.getMessage(), now);
        }
        catch (RuntimeException e) {
            if (userInterrupted.get()) {
                // User-cancelled mid-stream; treat as IDLE so they can
                // continue typing without clicking Resume.
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

    private void runAnthropicTurn(String userInput, Instant turnStart)
    {
        if (resolvedModel.kind() != WorkModelKind.API) {
            throw new UnsupportedProviderException(
                    "LogicLoopThreadAgent expected an API work model but got "
                            + resolvedModel.kind());
        }
        if (!ANTHROPIC_PROVIDER_ID.equalsIgnoreCase(resolvedModel.agentOrProvider())) {
            throw new UnsupportedProviderException(
                    "LogicLoopThreadAgent currently supports the 'anthropic' provider "
                            + "only; resolved choice is '" + resolvedModel.agentOrProvider() + "'. "
                            + "Pick an Anthropic model on the picker or wait for the multi-"
                            + "provider transport (B5).");
        }
        String account = resolvedModel.account();
        Optional<String> apiKey = account == null || account.isBlank()
                ? credentialService.getSecret(CredentialType.AI, ANTHROPIC_PROVIDER_ID)
                : credentialService.getSecret(CredentialType.AI, ANTHROPIC_PROVIDER_ID, account);
        String key = apiKey.orElseThrow(() -> new IllegalStateException(
                "No Anthropic API key on file" + (account == null ? "" : " for account " + account)
                        + ". Add one in Settings → Credentials."));
        String modelId = resolvedModel.model() == null || resolvedModel.model().isBlank()
                ? defaultAnthropicModel()
                : resolvedModel.model();
        activeModel.set(modelId);

        String system = composeSystemPrompt();
        ArrayNode messages = buildMessageHistory(userInput);
        ArrayNode toolsArray = toolRegistry == null
                ? null
                : toolRegistry.renderAsAnthropicTools(mapper);

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
            // Echo the assistant's tool-use turn back into the message
            // history so the next round carries the conversation
            // forward properly — Anthropic requires every tool_use to
            // be followed by a user message whose content carries the
            // matching tool_result blocks.
            messages.add(assistantContent(round.text, round.toolUseBlocks));
            messages.add(dispatchTools(round.toolUseBlocks));
        }

        Instant finishedAt = Instant.now();
        long durationMs = Math.max(0L, finishedAt.toEpochMilli() - turnStart.toEpochMilli());
        runningTokensIn.addAndGet(totalTokensIn);
        runningTokensOut.addAndGet(totalTokensOut);
        long turnCostMilli = estimateCostMilli(totalTokensIn, totalTokensOut);
        runningCostUsdMilli.addAndGet(turnCostMilli);

        persistAssistantMessage(finalText, finishedAt, durationMs, totalTokensIn, totalTokensOut, turnCostMilli);
        publish(new StreamEvent.AssistantText(finishedAt, finalText));
        publish(new StreamEvent.TurnDone(finishedAt, durationMs, turnCostMilli, totalTokensIn, totalTokensOut));
        status.set(ThreadStatus.IDLE);
        persistThreadProgress();
    }

    /** One round-trip with the provider: send the current message
     *  history, stream the response, collect text + any tool_use
     *  blocks. */
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

        // Streaming-block bookkeeping. Anthropic indexes content
        // blocks within a single response; tool_use input arrives as
        // input_json_delta chunks under the same index. We track per
        // open index so concurrent text + tool_use blocks don't get
        // their inputs interleaved.
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

    /** Assemble the assistant's role message echoing the round's text
     *  + the tool_use blocks the model produced. Anthropic requires
     *  this exact shape on follow-up turns so the model can stitch
     *  the tool_result back to its previous request. */
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

    /** Dispatch each tool_use block against the registry and build the
     *  follow-up user message whose content carries every tool_result.
     *  Emits {@link StreamEvent.ToolCallStarted} / {@link
     *  StreamEvent.ToolCallDone} so the conversation pane renders the
     *  same way the CLI lane does for tool turns. */
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

    private JsonNode parseToolInput(String raw)
    {
        if (raw == null || raw.isEmpty()) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(raw);
        }
        catch (Exception e) {
            // Provider truncation or malformed json — surface as
            // empty so the tool's own validator returns a clean error
            // instead of throwing inside the loop.
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
            return tool.get().invoke(input, new AgentToolContext(threadId, taskId, cwd));
        }
        catch (RuntimeException e) {
            log.warn("Tool {} threw on thread {}: {}", name, threadId, e.getMessage());
            return AgentTool.Result.error("Tool '" + name + "' failed: " + e.getMessage());
        }
    }

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

    /** Bookkeeping carried through the inner streaming loop —
     *  partial input JSON accumulates here while content_block_delta
     *  frames stream in and is finalised on content_block_stop. */
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

    private String composeSystemPrompt()
    {
        if (roleSkillText == null || roleSkillText.isBlank()) {
            return null;
        }
        return roleSkillText.trim();
    }

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

    /** Rough cost estimate. We don't have a per-model price sheet in
     *  v1; the figure is mostly for the progress strip. B5 can plug a
     *  real catalog price in alongside the multi-provider transport. */
    private static long estimateCostMilli(long tokensIn, long tokensOut)
    {
        // Sonnet-class: $3/M in, $15/M out → 0.003 milli-cents per
        // input token, 0.015 per output. Multiply by 1000 for the
        // costUsdMilli convention.
        double inputCostUsd = tokensIn * 3.0 / 1_000_000.0;
        double outputCostUsd = tokensOut * 15.0 / 1_000_000.0;
        return Math.round((inputCostUsd + outputCostUsd) * 1000.0);
    }

    private static String defaultAnthropicModel()
    {
        return "claude-sonnet-4-6";
    }

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
     *  jumping back to a parked sibling never mixes histories. Null on
     *  trunk-only threads. */
    private String activeTaskId()
    {
        return taskStore.findActiveTaskForThread(threadId)
                .map(t -> t.id())
                .orElse(null);
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
     *  metrics strip and the dashboard's per-thread spend stay in sync
     *  without waiting for the next checkpoint. */
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

    @Override
    public void interrupt()
    {
        userInterrupted.set(true);
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
        CompletableFuture<Void> turn = currentTurn.get();
        if (turn != null) {
            turn.cancel(true);
        }
        status.set(ThreadStatus.COMPLETED);
        publish(new StreamEvent.SessionEnded(Instant.now(), 0, null));
    }

    @Override
    public void notifyPermissionRequested(String callId, String toolName, String summary)
    {
        // B3 has no tools wired; if a permission request reached the
        // agent, the gate's mediator made a mistake. Drop it loudly so
        // a future regression surfaces in the logs rather than as a
        // hung turn.
        log.warn("LogicLoopThreadAgent received permission request {} for tool {} but tools "
                + "are not enabled yet (lands in B4)", callId, toolName);
    }

    @Override
    public void decide(String callId, PermissionDecision decision)
    {
        // No tools → no waiters to decide.
    }

    @Override
    public void grantToolBudget(String toolName, int count)
    {
        // No tools yet. Recording would be harmless but misleading.
    }

    @Override
    public OptionalInt tryConsumeToolBudget(String toolName)
    {
        return OptionalInt.empty();
    }

    @Override
    public void notifyPermissionAutoAllowed(String callId, String toolName, int remaining)
    {
        // No tools yet — see notifyPermissionRequested.
    }

    @Override
    public Runnable subscribeToEvents(Consumer<StreamEvent> listener)
    {
        requireNonNull(listener, "listener is null");
        listeners.add(listener);
        return () -> listeners.remove(listener);
    }

    /** Distinct exception so the runTurn catch can branch on it and
     *  show a clear "wrong provider" message instead of a generic
     *  failure. */
    private static final class UnsupportedProviderException
            extends RuntimeException
    {
        UnsupportedProviderException(String message)
        {
            super(message);
        }
    }
}
