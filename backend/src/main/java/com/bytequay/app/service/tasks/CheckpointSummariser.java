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
package com.bytequay.app.service.tasks;

import com.bytequay.app.domain.CredentialType;
import com.bytequay.app.domain.TaskCheckpoint;
import com.bytequay.app.domain.TaskMessage;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.CredentialService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static java.util.Objects.requireNonNull;

/**
 * Calls Anthropic's Messages API with a cheap Haiku model to produce
 * the {@code summaryMd} + bullet titles for a per-segment checkpoint
 * or for the Overall rollup. Cost is intentionally tiny (≈$0.0005
 * per segment) so the scheduler can fire freely.
 *
 * <p>The summariser does <em>not</em> persist anything — it returns
 * a {@link CheckpointSummaryResult} that the scheduler stitches with
 * the message-range metadata before calling {@code TaskCheckpointStore}.
 * That split keeps the Anthropic-facing code free of JPA concerns and
 * makes the scheduler unit-testable with a mock summariser.
 */
@Component
public class CheckpointSummariser
{
    private static final Logger log = LoggerFactory.getLogger(CheckpointSummariser.class);

    /** Cheap, fast model picked for high-volume summarisation. The
     *  design doc commits to Haiku 4.5 specifically — a more capable
     *  model isn't worth the 10x cost when the summariser only has
     *  to compress factual conversation prose. */
    private static final String HAIKU_MODEL = "claude-haiku-4-5";
    private static final int MAX_OUTPUT_TOKENS = 1024;
    /** Conservative Haiku pricing (May 2026): $1/M input, $5/M output.
     *  Stored as micro-USD-per-token so the math fits in a long. */
    private static final long HAIKU_INPUT_MICRO_PER_TOKEN = 1;     // $1 per 1M tokens = 1 µUSD/token
    private static final long HAIKU_OUTPUT_MICRO_PER_TOKEN = 5;    // $5 per 1M tokens = 5 µUSD/token

    private static final String SEGMENT_SYSTEM_PROMPT = """
            You are a code-context summariser. Given a conversation segment, write a tight \
            150–250 word summary in Markdown that captures (a) the work accomplished, \
            (b) the key files / classes / decisions, (c) any open threads or unresolved \
            questions. Use 2–3 leading bullet titles ("- Bullet title\\n") for at-a-glance \
            preview, then 1–2 short paragraphs. Avoid mentioning timestamps or token counts. \
            Do not greet, do not editorialise.""";

    private static final String OVERALL_SYSTEM_PROMPT = """
            You are summarising a task end-to-end given its per-segment summaries. Produce \
            a single rollup of 200–350 words in Markdown: start with 2–3 bullet titles, \
            then 1–2 paragraphs that capture the through-line of the task. Treat earlier \
            segments as background context and later segments as the current state. Do not \
            re-state every bullet from every segment; pick the load-bearing facts.""";

    private static final String ANTHROPIC_NAME = "anthropic";

    private final RestClient client;
    private final CredentialService credentialService;
    private final TaskStore store;
    private final ObjectMapper objectMapper;

    public CheckpointSummariser(
            RestClient anthropicRestClient,
            CredentialService credentialService,
            TaskStore store,
            ObjectMapper objectMapper)
    {
        this.client = requireNonNull(anthropicRestClient, "anthropicRestClient is null");
        this.credentialService = requireNonNull(credentialService, "credentialService is null");
        this.store = requireNonNull(store, "store is null");
        this.objectMapper = requireNonNull(objectMapper, "objectMapper is null");
    }

    /**
     * Summarise the messages in {@code [firstSeq, lastSeq]} for one
     * per-segment checkpoint. Caller is responsible for choosing the
     * range; this method only renders + sends + parses.
     */
    public CheckpointSummaryResult summariseSegment(String taskId, long firstSeq, long lastSeq)
    {
        requireNonNull(taskId, "taskId is null");
        List<TaskMessage> msgs = store.listMessagesBetween(taskId, firstSeq, lastSeq);
        if (msgs.isEmpty()) {
            throw new IllegalStateException(
                    "summariseSegment called with empty range " + firstSeq + "–" + lastSeq
                            + " for task " + taskId);
        }
        String rendered = renderForSummary(msgs);
        String user = "Segment of conversation to summarise:\n\n" + rendered;
        return callAnthropic(SEGMENT_SYSTEM_PROMPT, user);
    }

    /**
     * Roll up an Overall checkpoint from the currently-active per-segment
     * checkpoints. {@code segments} is expected in seq-ascending order
     * so the model sees earliest first; the scheduler reverses the
     * descending list it gets from the store before calling.
     */
    public CheckpointSummaryResult refreshOverall(String taskId, List<TaskCheckpoint> segments)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(segments, "segments is null");
        if (segments.isEmpty()) {
            throw new IllegalStateException(
                    "refreshOverall called with no segments for task " + taskId);
        }
        StringBuilder sb = new StringBuilder();
        for (TaskCheckpoint cp : segments) {
            sb.append("### cp-").append(cp.seq())
                    .append(" · turns ").append(cp.firstMsgSeq()).append('–').append(cp.lastMsgSeq())
                    .append('\n')
                    .append(cp.summaryMd())
                    .append("\n\n");
        }
        return callAnthropic(OVERALL_SYSTEM_PROMPT, sb.toString().trim());
    }

    private CheckpointSummaryResult callAnthropic(String system, String user)
    {
        String apiKey = credentialService.getSecret(CredentialType.AI, ANTHROPIC_NAME)
                .orElseThrow(() -> new IllegalStateException(
                        "Anthropic API key not configured. Add it in Settings → AI review."));

        MessagesRequest body = new MessagesRequest(
                HAIKU_MODEL,
                MAX_OUTPUT_TOKENS,
                system,
                ImmutableList.of(new MessagesRequest.Message("user", user)));

        MessagesResponse response = client.post()
                .uri("/v1/messages")
                .header("x-api-key", apiKey)
                .body(body)
                .retrieve()
                .body(MessagesResponse.class);

        String text = extractText(response);
        List<String> bullets = extractBullets(text);
        long promptTokens = extractUsage(response, "input_tokens");
        long completionTokens = extractUsage(response, "output_tokens");
        long costUsdMilli = computeCostMilli(promptTokens, completionTokens);
        return new CheckpointSummaryResult(
                text.trim(), bullets, HAIKU_MODEL,
                promptTokens, completionTokens, costUsdMilli);
    }

    /**
     * Compact conversation rendering — drops the raw JSON noise the
     * model doesn't need but keeps the tool-call shapes (file paths +
     * deltas) so the summary can reference what changed. The design
     * doc's example is the contract; we mirror it shape-for-shape.
     */
    String renderForSummary(List<TaskMessage> msgs)
    {
        StringBuilder out = new StringBuilder();
        List<String> pendingTools = new ArrayList<>();
        String pendingAssistantText = null;
        for (TaskMessage m : msgs) {
            String role = m.role();
            String type = m.type();
            if ("user".equals(role) && "text".equals(type)) {
                flushAssistant(out, pendingTools, pendingAssistantText);
                pendingTools.clear();
                pendingAssistantText = null;
                String text = readTextField(m.contentJson());
                if (text != null && !text.isBlank()) {
                    out.append("User: ").append(text.trim()).append("\n\n");
                }
            }
            else if ("assistant".equals(role) && "text".equals(type)) {
                String text = readTextField(m.contentJson());
                if (text != null && !text.isBlank()) {
                    // Concatenate consecutive assistant text blobs into
                    // one paragraph so the model sees one coherent reply
                    // per turn rather than fragmented chunks.
                    pendingAssistantText = pendingAssistantText == null
                            ? text.trim()
                            : pendingAssistantText + " " + text.trim();
                }
            }
            else if ("tool_call".equals(type) || "tool_use".equals(type)) {
                pendingTools.add(formatToolCall(m.contentJson()));
            }
            // tool_result, thinking, permission_request and others are
            // dropped — the summariser does fine with just user prose,
            // assistant prose, and which tools were called.
        }
        flushAssistant(out, pendingTools, pendingAssistantText);
        return out.toString().trim();
    }

    private static void flushAssistant(
            StringBuilder out, List<String> tools, String text)
    {
        if (tools.isEmpty() && text == null) {
            return;
        }
        if (!tools.isEmpty()) {
            out.append("Assistant called:\n");
            for (String t : tools) {
                out.append("- ").append(t).append('\n');
            }
        }
        if (text != null && !text.isBlank()) {
            if (!tools.isEmpty()) {
                out.append('\n');
            }
            out.append("Assistant prose: ").append(text).append('\n');
        }
        out.append('\n');
    }

    private String readTextField(String contentJson)
    {
        if (contentJson == null || contentJson.isBlank()) {
            return null;
        }
        try {
            JsonNode node = objectMapper.readTree(contentJson);
            JsonNode text = node.path("text");
            return text.isMissingNode() || text.isNull() ? null : text.asText();
        }
        catch (Exception e) {
            return null;
        }
    }

    private String formatToolCall(String contentJson)
    {
        if (contentJson == null || contentJson.isBlank()) {
            return "(tool call)";
        }
        try {
            JsonNode node = objectMapper.readTree(contentJson);
            String tool = node.path("toolName").asText(node.path("name").asText("tool"));
            JsonNode input = node.path("input");
            String path = input.path("file_path").asText(input.path("path").asText(""));
            if (!path.isBlank()) {
                return tool + " " + path;
            }
            String pattern = input.path("pattern").asText("");
            if (!pattern.isBlank()) {
                return tool + " '" + truncate(pattern, 60) + "'";
            }
            return tool;
        }
        catch (Exception e) {
            return "(tool call)";
        }
    }

    private static String truncate(String s, int max)
    {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** Pull the first 1-3 leading {@code "- title"} lines out of the
     *  summary text. The system prompt asks for 2-3 bullet titles up
     *  top, so this is essentially a guaranteed match — but we handle
     *  the empty case defensively so a slightly-off-spec response
     *  doesn't bury an otherwise-good summary. */
    List<String> extractBullets(String summary)
    {
        if (summary == null || summary.isBlank()) {
            return List.of();
        }
        List<String> bullets = new ArrayList<>();
        for (String line : Splitter.on('\n').split(summary)) {
            String trimmed = line.trim();
            if (trimmed.startsWith("- ")) {
                String title = trimmed.substring(2).trim();
                // Strip trailing punctuation the model sometimes adds.
                while (!title.isEmpty()
                        && (title.endsWith(".") || title.endsWith(":") || title.endsWith(";"))) {
                    title = title.substring(0, title.length() - 1).trim();
                }
                if (!title.isEmpty()) {
                    bullets.add(title);
                    if (bullets.size() == 3) {
                        break;
                    }
                }
            }
            else if (!bullets.isEmpty() && !trimmed.isEmpty()) {
                // First non-bullet line after the leading run ends the
                // bullet block — anything below is the prose paragraph.
                break;
            }
        }
        return List.copyOf(bullets);
    }

    private static String extractText(MessagesResponse response)
    {
        if (response == null || response.content() == null || response.content().isEmpty()) {
            throw new IllegalStateException("Claude returned an empty response");
        }
        return response.content().stream()
                .filter(c -> "text".equals(c.type()))
                .map(MessagesResponse.ContentBlock::text)
                .reduce("", String::concat);
    }

    private static long extractUsage(MessagesResponse response, String key)
    {
        Map<String, Object> usage = response.usage();
        if (usage == null) {
            return 0;
        }
        Object raw = usage.get(key);
        if (raw instanceof Number n) {
            return n.longValue();
        }
        if (raw instanceof String s) {
            try {
                return Long.parseLong(s);
            }
            catch (NumberFormatException ignored) {
                log.debug("Anthropic usage.{} not numeric: {}", key, s);
            }
        }
        return 0;
    }

    /** {@code cost_usd_milli} is millis of USD; one µUSD-per-token
     *  rate times token count yields total µUSD, and dividing by 1000
     *  gives milli-USD. Rounds up so a sub-milli charge doesn't get
     *  silently zeroed. */
    private static long computeCostMilli(long promptTokens, long completionTokens)
    {
        long microUsd = promptTokens * HAIKU_INPUT_MICRO_PER_TOKEN
                + completionTokens * HAIKU_OUTPUT_MICRO_PER_TOKEN;
        return (microUsd + 999) / 1000;
    }

    // ── Anthropic request / response DTOs ────────────────────────────────────

    record MessagesRequest(
            String model,
            @JsonProperty("max_tokens") int maxTokens,
            String system,
            List<Message> messages)
    {
        record Message(String role, String content) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record MessagesResponse(
            String id,
            String model,
            List<ContentBlock> content,
            @JsonProperty("stop_reason") String stopReason,
            Map<String, Object> usage)
    {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record ContentBlock(String type, String text) {}
    }
}
