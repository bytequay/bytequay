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
import java.time.Duration;
import java.time.Instant;
import java.util.List;
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

        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", modelId);
        requestBody.put("max_tokens", MAX_OUTPUT_TOKENS);
        requestBody.put("stream", true);
        String system = composeSystemPrompt();
        if (system != null && !system.isEmpty()) {
            requestBody.put("system", system);
        }
        requestBody.set("messages", buildMessageHistory(userInput));

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
                .header("x-api-key", key)
                .header("accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        StringBuilder accumulated = new StringBuilder();
        long turnTokensIn = 0;
        long turnTokensOut = 0;
        boolean toolUseSeen = false;
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
                        return;
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
                            String blockType = frame.path("content_block").path("type").asText("");
                            if ("tool_use".equals(blockType)) {
                                toolUseSeen = true;
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
                        }
                        case "message_delta" -> {
                            JsonNode usage = frame.path("usage");
                            if (usage.has("output_tokens")) {
                                turnTokensOut = usage.path("output_tokens").asLong(turnTokensOut);
                            }
                            if (usage.has("input_tokens")) {
                                turnTokensIn = usage.path("input_tokens").asLong(turnTokensIn);
                            }
                            publish(new StreamEvent.UsageUpdated(
                                    Instant.now(),
                                    turnTokensIn,
                                    turnTokensOut));
                        }
                        case "message_start" -> {
                            JsonNode usage = frame.path("message").path("usage");
                            if (usage.has("input_tokens")) {
                                turnTokensIn = usage.path("input_tokens").asLong(turnTokensIn);
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

        if (toolUseSeen) {
            // The model attempted a tool call but B3 has no tool
            // dispatcher. Surface a clear error and stop the turn —
            // B4 plugs in the in-JVM tool registry.
            emitFatal("The model attempted a tool call, but tool execution on the API "
                    + "lane is not enabled yet (lands in B4). Pin a CLI work model "
                    + "if you need tools right now.", turnStart);
            return;
        }

        String finalText = accumulated.toString();
        Instant finishedAt = Instant.now();
        long durationMs = Math.max(0L, finishedAt.toEpochMilli() - turnStart.toEpochMilli());
        runningTokensIn.addAndGet(turnTokensIn);
        runningTokensOut.addAndGet(turnTokensOut);
        long turnCostMilli = estimateCostMilli(turnTokensIn, turnTokensOut);
        runningCostUsdMilli.addAndGet(turnCostMilli);

        persistAssistantMessage(finalText, finishedAt, durationMs, turnTokensIn, turnTokensOut, turnCostMilli);
        publish(new StreamEvent.AssistantText(finishedAt, finalText));
        publish(new StreamEvent.TurnDone(finishedAt, durationMs, turnCostMilli, turnTokensIn, turnTokensOut));
        status.set(ThreadStatus.IDLE);
        persistThreadProgress();
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
