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
package com.bytequay.app.service.agents;

import com.bytequay.app.service.ai.ModelPricing;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * The provider tool-round loop, extracted from the in-JVM thread agent
 * so every agent composition (build threads, review seats, the review
 * lead) drives the same machinery. The runner owns exactly three
 * things: the wire dialects (Anthropic Messages SSE and the
 * OpenAI-compatible chat-completions SSE), the round loop bounded by
 * {@link TurnSpec#maxToolIterations()}, and the provider-mandated
 * message shapes for echoing tool calls and results between rounds.
 *
 * <p>It owns NOTHING else: no persistence, no event stream, no tool
 * semantics, no credentials. Tool calls are parsed and handed to the
 * caller's {@link ToolExecutor}; everything observable rides the
 * caller's {@link TurnHooks}.
 */
public final class TurnRunner
{
    private static final Logger log = LoggerFactory.getLogger(TurnRunner.class);

    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final Duration REQUEST_TIMEOUT = Duration.ofMinutes(5);

    private final HttpClient httpClient;
    private final ObjectMapper mapper;

    public TurnRunner(HttpClient httpClient, ObjectMapper mapper)
    {
        this.httpClient = requireNonNull(httpClient, "httpClient is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    /**
     * Run one bounded turn: call the provider, execute any tool calls
     * through {@code executor}, feed the results back, and repeat
     * until the model stops calling tools, the iteration bound hits,
     * or a hook stops the loop. Appends the between-round messages to
     * {@code spec.messages()} in place.
     */
    public TurnResult runTurn(TurnSpec spec, ToolExecutor executor, TurnHooks hooks)
    {
        requireNonNull(spec, "spec is null");
        requireNonNull(executor, "executor is null");
        requireNonNull(hooks, "hooks is null");

        String finalText = "";
        long totalTokensIn = 0;
        long totalTokensOut = 0;
        int rounds = 0;

        for (int iteration = 0; iteration < spec.maxToolIterations(); iteration++) {
            if (hooks.interrupted()) {
                return result(finalText, totalTokensIn, totalTokensOut, rounds,
                        spec.modelId(), TurnResult.End.INTERRUPTED);
            }
            long roundStartNanos = System.nanoTime();
            Round round = spec.transport() == TurnSpec.Transport.ANTHROPIC
                    ? runAnthropicRound(spec, hooks)
                    : runOpenAiCompatibleRound(spec, hooks);
            rounds++;
            totalTokensIn += round.tokensIn;
            totalTokensOut += round.tokensOut;
            finalText = round.text;
            hooks.onRoundCompleted(round.tokensIn, round.tokensOut,
                    System.nanoTime() - roundStartNanos);
            long costSoFar = ModelPricing.estimateCostMilli(
                    spec.modelId(), totalTokensIn, totalTokensOut);
            if (hooks.abortTurn(costSoFar)) {
                return result(finalText, totalTokensIn, totalTokensOut, rounds,
                        spec.modelId(), TurnResult.End.ABORTED);
            }
            if (round.toolCalls.isEmpty()) {
                break;
            }
            if (spec.transport() == TurnSpec.Transport.ANTHROPIC) {
                spec.messages().add(anthropicAssistantEcho(round.text, round.toolCalls));
                spec.messages().add(dispatchAnthropic(round.toolCalls, executor, hooks));
            }
            else {
                spec.messages().add(openAiAssistantEcho(round.text, round.toolCalls));
                for (ObjectNode toolMsg : dispatchOpenAi(round.toolCalls, executor, hooks)) {
                    spec.messages().add(toolMsg);
                }
            }
        }

        return result(finalText, totalTokensIn, totalTokensOut, rounds,
                spec.modelId(), TurnResult.End.COMPLETED);
    }

    private static TurnResult result(
            String finalText, long tokensIn, long tokensOut, int rounds,
            String modelId, TurnResult.End end)
    {
        return new TurnResult(
                finalText, tokensIn, tokensOut,
                ModelPricing.estimateCostMilli(modelId, tokensIn, tokensOut),
                rounds, end);
    }

    // ── Anthropic transport ───────────────────────────────────────────

    /** One round-trip with the Anthropic provider: send the current
     *  message history, stream the response, collect text + any
     *  tool_use blocks. */
    private Round runAnthropicRound(TurnSpec spec, TurnHooks hooks)
    {
        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", spec.modelId());
        requestBody.put("max_tokens", spec.maxOutputTokens());
        requestBody.put("stream", true);
        if (spec.system() != null && !spec.system().isEmpty()) {
            requestBody.put("system", spec.system());
        }
        requestBody.set("messages", spec.messages());
        if (spec.tools() != null && !spec.tools().isEmpty()) {
            requestBody.set("tools", spec.tools());
        }

        String payload = encode(requestBody, "Anthropic");
        logPromptBreakdown("anthropic", spec.modelId(),
                payload.length(),
                spec.system() == null ? 0 : spec.system().length(),
                spec.messages().toString().length(),
                spec.tools() == null ? 0 : spec.tools().toString().length());

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(spec.url()))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("x-api-key", spec.authToken())
                .header("accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        StringBuilder accumulated = new StringBuilder();
        List<ToolCall> toolCalls = new ArrayList<>();
        long roundTokensIn = 0;
        long roundTokensOut = 0;

        // Anthropic indexes content blocks within a single response;
        // tool_use input arrives as input_json_delta chunks under the
        // same index. Track per open index so text + tool_use don't
        // interleave.
        Map<Integer, OpenBlock> openToolBlocks = new HashMap<>();

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
                    if (hooks.interrupted()) {
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
                    catch (IOException parseFail) {
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
                                openToolBlocks.put(index, new OpenBlock(id, toolName));
                            }
                        }
                        case "content_block_delta" -> {
                            JsonNode delta = frame.path("delta");
                            String deltaType = delta.path("type").asText("");
                            if ("text_delta".equals(deltaType)) {
                                String chunk = delta.path("text").asText("");
                                if (!chunk.isEmpty()) {
                                    accumulated.append(chunk);
                                    hooks.onTextDelta(frame.path("index").asInt(0), chunk);
                                }
                            }
                            else if ("input_json_delta".equals(deltaType)) {
                                int index = frame.path("index").asInt(0);
                                OpenBlock block = openToolBlocks.get(index);
                                if (block != null) {
                                    block.partial.append(delta.path("partial_json").asText(""));
                                }
                            }
                        }
                        case "content_block_stop" -> {
                            int index = frame.path("index").asInt(0);
                            OpenBlock block = openToolBlocks.remove(index);
                            if (block != null) {
                                toolCalls.add(toolCall(block));
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
                            hooks.onUsage(roundTokensIn, roundTokensOut);
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
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Anthropic streaming failed: " + e.getMessage(), e);
        }

        return new Round(accumulated.toString(), toolCalls, roundTokensIn, roundTokensOut);
    }

    // ── OpenAI-compatible transport (OpenAI + DeepSeek) ───────────────

    /** One round-trip with an OpenAI-compatible provider. Parses the
     *  {@code choices[0].delta} SSE format common to OpenAI and
     *  DeepSeek. */
    private Round runOpenAiCompatibleRound(TurnSpec spec, TurnHooks hooks)
    {
        ObjectNode requestBody = mapper.createObjectNode();
        requestBody.put("model", spec.modelId());
        requestBody.put("max_tokens", spec.maxOutputTokens());
        requestBody.put("stream", true);
        // Request token counts in the final streaming chunk.
        ObjectNode streamOpts = mapper.createObjectNode();
        streamOpts.put("include_usage", true);
        requestBody.set("stream_options", streamOpts);
        requestBody.set("messages", spec.messages());
        if (spec.tools() != null && !spec.tools().isEmpty()) {
            requestBody.set("tools", spec.tools());
        }

        String payload = encode(requestBody, "OpenAI-compatible");

        // OpenAI puts the system prompt inside messages[0] (role=system),
        // so the system length is pulled out of there rather than passed
        // separately. Everything else mirrors the Anthropic round.
        logPromptBreakdown("openai", spec.modelId(),
                payload.length(),
                extractSystemContentLength(spec.messages()),
                spec.messages().toString().length(),
                spec.tools() == null ? 0 : spec.tools().toString().length());

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(spec.url()))
                .timeout(REQUEST_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("Authorization", "Bearer " + spec.authToken())
                .header("accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(payload, StandardCharsets.UTF_8))
                .build();

        StringBuilder accumulated = new StringBuilder();
        // Tool call accumulation indexed by the provider's tool_calls[i].index.
        Map<Integer, OpenBlock> openToolCalls = new HashMap<>();
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
                    if (hooks.interrupted()) {
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
                    catch (IOException parseFail) {
                        continue;
                    }

                    // Usage — sent in a dedicated frame when
                    // stream_options.include_usage is set.
                    JsonNode usageNode = frame.path("usage");
                    if (!usageNode.isMissingNode() && !usageNode.isNull()) {
                        if (usageNode.has("prompt_tokens")) {
                            roundTokensIn = usageNode.path("prompt_tokens").asLong(roundTokensIn);
                        }
                        if (usageNode.has("completion_tokens")) {
                            roundTokensOut = usageNode.path("completion_tokens").asLong(roundTokensOut);
                        }
                        hooks.onUsage(roundTokensIn, roundTokensOut);
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
                            hooks.onTextDelta(0, chunk);
                        }
                    }

                    // Tool calls — id and name arrive only in the first
                    // chunk for each index; subsequent chunks carry
                    // additional argument fragments.
                    JsonNode toolCallsNode = delta.path("tool_calls");
                    if (toolCallsNode.isArray()) {
                        for (JsonNode tc : toolCallsNode) {
                            int tcIndex = tc.path("index").asInt(0);
                            OpenBlock block = openToolCalls.get(tcIndex);
                            if (block == null) {
                                String id = tc.path("id").asText("");
                                String name = tc.path("function").path("name").asText("");
                                block = new OpenBlock(id, name);
                                openToolCalls.put(tcIndex, block);
                            }
                            String argChunk = tc.path("function").path("arguments").asText("");
                            if (!argChunk.isEmpty()) {
                                block.partial.append(argChunk);
                            }
                        }
                    }
                }
            }
        }
        catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("OpenAI-compatible streaming failed: " + e.getMessage(), e);
        }

        List<ToolCall> toolCalls = new ArrayList<>();
        for (OpenBlock block : openToolCalls.values()) {
            toolCalls.add(toolCall(block));
        }
        return new Round(accumulated.toString(), toolCalls, roundTokensIn, roundTokensOut);
    }

    // ── Message assembly ──────────────────────────────────────────────

    /** Assemble the assistant's role message echoing the round's text +
     *  the tool_use blocks (Anthropic format). Anthropic requires this
     *  exact shape on follow-up turns so the model can stitch
     *  tool_result back to the prior request. */
    private ObjectNode anthropicAssistantEcho(String text, List<ToolCall> toolCalls)
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
        for (ToolCall call : toolCalls) {
            ObjectNode useBlock = mapper.createObjectNode();
            useBlock.put("type", "tool_use");
            useBlock.put("id", call.id());
            useBlock.put("name", call.name());
            useBlock.set("input", call.input());
            content.add(useBlock);
        }
        msg.set("content", content);
        return msg;
    }

    /** Assemble the assistant message carrying OpenAI-format
     *  tool_calls. The provider requires this as the message
     *  immediately preceding the role:tool result messages on the
     *  next round. */
    private ObjectNode openAiAssistantEcho(String text, List<ToolCall> toolCalls)
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
        for (ToolCall call : toolCalls) {
            ObjectNode tc = mapper.createObjectNode();
            tc.put("id", call.id());
            tc.put("type", "function");
            ObjectNode fn = mapper.createObjectNode();
            fn.put("name", call.name());
            fn.put("arguments", call.rawArguments());
            tc.set("function", fn);
            toolCallsArray.add(tc);
        }
        msg.set("tool_calls", toolCallsArray);
        return msg;
    }

    /** Run each tool call through the executor and build the follow-up
     *  user message whose content carries every tool_result
     *  (Anthropic format). */
    private ObjectNode dispatchAnthropic(
            List<ToolCall> toolCalls, ToolExecutor executor, TurnHooks hooks)
    {
        ObjectNode msg = mapper.createObjectNode();
        msg.put("role", "user");
        ArrayNode content = mapper.createArrayNode();
        for (ToolCall call : toolCalls) {
            ToolExecutor.ToolCallResult result = executeWithHooks(call, executor, hooks);
            ObjectNode resultBlock = mapper.createObjectNode();
            resultBlock.put("type", "tool_result");
            resultBlock.put("tool_use_id", call.id());
            resultBlock.put("content", result.text());
            if (result.isError()) {
                resultBlock.put("is_error", true);
            }
            content.add(resultBlock);
        }
        msg.set("content", content);
        return msg;
    }

    /** Run each tool call through the executor (OpenAI format).
     *  Returns one role:tool message per call; the loop appends each
     *  to the messages array. */
    private List<ObjectNode> dispatchOpenAi(
            List<ToolCall> toolCalls, ToolExecutor executor, TurnHooks hooks)
    {
        List<ObjectNode> toolMessages = new ArrayList<>();
        for (ToolCall call : toolCalls) {
            ToolExecutor.ToolCallResult result = executeWithHooks(call, executor, hooks);
            ObjectNode toolMsg = mapper.createObjectNode();
            toolMsg.put("role", "tool");
            toolMsg.put("tool_call_id", call.id());
            toolMsg.put("content", result.text() == null ? "" : result.text());
            toolMessages.add(toolMsg);
        }
        return toolMessages;
    }

    private ToolExecutor.ToolCallResult executeWithHooks(
            ToolCall call, ToolExecutor executor, TurnHooks hooks)
    {
        String inputJson;
        try {
            inputJson = mapper.writeValueAsString(call.input());
        }
        catch (IOException e) {
            inputJson = "{}";
        }
        hooks.onToolCallStarted(call.id(), call.name(), inputJson);
        ToolExecutor.ToolCallResult result = executor.execute(call);
        hooks.onToolCallDone(call.id(), result.text(), result.isError());
        return result;
    }

    // ── Helpers ───────────────────────────────────────────────────────

    private ToolCall toolCall(OpenBlock block)
    {
        String raw = block.partial.toString();
        return new ToolCall(block.id, block.name, raw, parseToolInput(raw));
    }

    private JsonNode parseToolInput(String raw)
    {
        if (raw == null || raw.isEmpty()) {
            return mapper.createObjectNode();
        }
        try {
            return mapper.readTree(raw);
        }
        catch (IOException e) {
            return mapper.createObjectNode();
        }
    }

    private String encode(ObjectNode requestBody, String label)
    {
        try {
            return mapper.writeValueAsString(requestBody);
        }
        catch (IOException e) {
            throw new IllegalStateException("Failed to encode " + label + " request body", e);
        }
    }

    /** One-line breakdown of the outbound request payload so we can
     *  spot which axis is dominating input-token cost. The estimator
     *  is {@code chars / 4} — a coarse-but-useful approximation for
     *  English + code, close enough to distinguish 500-token bloat
     *  from 5000-token bloat without paying for a real tokenizer.
     *  Fires per round, so a multi-iteration tool turn logs once per
     *  iteration. */
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
     *  or 0 if none. Used only by {@link #logPromptBreakdown}. */
    private static int extractSystemContentLength(ArrayNode messages)
    {
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        JsonNode first = messages.get(0);
        if (first == null || !first.isObject()) {
            return 0;
        }
        JsonNode role = first.get("role");
        if (role == null || !"system".equals(role.asText())) {
            return 0;
        }
        JsonNode content = first.get("content");
        return content == null ? 0 : content.asText().length();
    }

    /** Streaming accumulation for one tool call: Anthropic fills
     *  {@code partial} from input_json_delta frames, OpenAI from
     *  function.arguments fragments. */
    private static final class OpenBlock
    {
        private final String id;
        private final String name;
        private final StringBuilder partial = new StringBuilder();

        private OpenBlock(String id, String name)
        {
            this.id = id;
            this.name = name;
        }
    }

    private record Round(
            String text,
            List<ToolCall> toolCalls,
            long tokensIn,
            long tokensOut)
    {
    }
}
