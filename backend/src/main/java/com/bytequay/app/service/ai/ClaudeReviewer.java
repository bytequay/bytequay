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
package com.bytequay.app.service.ai;

import com.bytequay.app.domain.CredentialType;
import com.bytequay.app.domain.ReviewOutput;
import com.bytequay.app.domain.ReviewRequest;
import com.bytequay.app.repository.AppSettingsStore;
import com.bytequay.app.service.CredentialService;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

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
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * LlmReviewer implementation against the Anthropic Messages API.
 * Reads the active model from {@code app_settings.llm.model} (default
 * {@code claude-opus-4-7}) and the API key from the credentials vault.
 */
@Component
public class ClaudeReviewer
        implements LlmReviewer
{
    private static final Logger log = LoggerFactory.getLogger(ClaudeReviewer.class);
    private static final String PROVIDER_ID = "claude";
    private static final String DEFAULT_MODEL = "claude-opus-4-7";
    private static final int MAX_OUTPUT_TOKENS = 8_192;
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final String ANTHROPIC_MESSAGES_URL = "https://api.anthropic.com/v1/messages";
    // Streaming responses can run minutes; honour the standard read window
    // here rather than the shorter 120s used by the non-streaming RestClient.
    private static final Duration STREAM_TIMEOUT = Duration.ofMinutes(5);

    private final RestClient client;
    private final HttpClient httpClient;
    private final CredentialService credentialService;
    private final AppSettingsStore appSettings;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ClaudeReviewer(
            RestClient anthropicRestClient,
            CredentialService credentialService,
            AppSettingsStore appSettings)
    {
        this.client = requireNonNull(anthropicRestClient, "anthropicRestClient is null");
        this.credentialService = requireNonNull(credentialService, "credentialService is null");
        this.appSettings = requireNonNull(appSettings, "appSettings is null");
        // RestClient (SimpleClientHttpRequestFactory under the hood) buffers
        // response bodies — useless for SSE. The streaming path uses java.net.http
        // because it exposes the response body as a real InputStream we can read
        // line-by-line as bytes arrive on the wire.
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .build();
    }

    @Override
    public String providerId()
    {
        return PROVIDER_ID;
    }

    @Override
    public String displayName()
    {
        return "Claude (Anthropic)";
    }

    private static final String ANTHROPIC_NAME = "anthropic";

    @Override
    public boolean isConfigured()
    {
        return credentialService.get(CredentialType.AI, ANTHROPIC_NAME).isPresent();
    }

    @Override
    public ReviewOutput review(ReviewRequest request)
    {
        String apiKey = credentialService.getSecret(CredentialType.AI, ANTHROPIC_NAME)
                .orElseThrow(() -> new IllegalStateException(
                        "Anthropic API key not configured. Add it in Settings → AI review."));
        String model = appSettings.get(AppSettingsStore.Key.LLM_MODEL)
                .filter(s -> !s.isBlank())
                .orElse(DEFAULT_MODEL);

        MessagesRequest body = new MessagesRequest(
                model,
                MAX_OUTPUT_TOKENS,
                ReviewPrompt.systemPrompt(request),
                ImmutableList.of(new MessagesRequest.Message("user", ReviewPrompt.userMessage(request))),
                false);

        try {
            MessagesResponse response = client.post()
                    .uri("/v1/messages")
                    .header("x-api-key", apiKey)
                    .body(body)
                    .retrieve()
                    .body(MessagesResponse.class);
            String text = extractText(response);
            return parseReviewOutput(text, model);
        }
        catch (RestClientResponseException e) {
            log.warn("Anthropic Messages API returned {}: {}", e.getStatusCode().value(), e.getResponseBodyAsString());
            throw new IllegalStateException(
                    "Claude API error (" + e.getStatusCode().value() + "). Check your API key and try again.", e);
        }
    }

    @Override
    public ReviewOutput reviewStream(ReviewRequest request, Consumer<String> textChunk)
    {
        requireNonNull(textChunk, "textChunk is null");
        String apiKey = credentialService.getSecret(CredentialType.AI, ANTHROPIC_NAME)
                .orElseThrow(() -> new IllegalStateException(
                        "Anthropic API key not configured. Add it in Settings → AI review."));
        String model = appSettings.get(AppSettingsStore.Key.LLM_MODEL)
                .filter(s -> !s.isBlank())
                .orElse(DEFAULT_MODEL);

        MessagesRequest body = new MessagesRequest(
                model,
                MAX_OUTPUT_TOKENS,
                ReviewPrompt.systemPrompt(request),
                ImmutableList.of(new MessagesRequest.Message("user", ReviewPrompt.userMessage(request))),
                true);
        String requestJson;
        try {
            requestJson = objectMapper.writeValueAsString(body);
        }
        catch (Exception e) {
            throw new IllegalStateException("Failed to encode Anthropic request body", e);
        }

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(ANTHROPIC_MESSAGES_URL))
                .timeout(STREAM_TIMEOUT)
                .header("Content-Type", "application/json")
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("x-api-key", apiKey)
                .header("accept", "text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
                .build();

        StringBuilder accumulated = new StringBuilder();
        try {
            HttpResponse<InputStream> response = httpClient.send(
                    httpRequest, HttpResponse.BodyHandlers.ofInputStream());
            if (response.statusCode() / 100 != 2) {
                String errBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                log.warn("Anthropic stream returned {}: {}", response.statusCode(), errBody);
                throw new IllegalStateException(
                        "Claude streaming error (" + response.statusCode() + "). Check your API key and try again.");
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data: ")) {
                        continue;
                    }
                    String payload = line.substring("data: ".length());
                    String delta = extractTextDelta(payload);
                    if (delta == null) {
                        continue;
                    }
                    accumulated.append(delta);
                    textChunk.accept(delta);
                }
            }
        }
        catch (IOException | InterruptedException e) {
            if (e instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("Claude streaming connection failed: " + e.getMessage(), e);
        }
        return parseReviewOutput(accumulated.toString(), model);
    }

    /**
     * Pull a {@code text} payload out of a single Anthropic SSE
     * {@code data: ...} line. Returns null when the line is a non-text frame
     * ({@code message_start}, {@code message_delta}, {@code ping}, etc.) so
     * the caller can simply skip it.
     */
    String extractTextDelta(String payload)
    {
        try {
            JsonNode node = objectMapper.readTree(payload);
            if (!"content_block_delta".equals(node.path("type").asText())) {
                return null;
            }
            JsonNode delta = node.path("delta");
            if (!"text_delta".equals(delta.path("type").asText())) {
                return null;
            }
            String text = delta.path("text").asText(null);
            return text == null || text.isEmpty() ? null : text;
        }
        catch (Exception e) {
            // A malformed SSE frame is not fatal — just skip it. Anthropic
            // occasionally sends comment lines (": ping") which `readLine`
            // surfaces as plain text we can safely ignore.
            return null;
        }
    }

    @Override
    public String diagnoseCheckRunFailure(String checkName, String logText)
    {
        if (logText == null || logText.trim().isEmpty()) {
            return "";
        }
        String apiKey = credentialService.getSecret(CredentialType.AI, ANTHROPIC_NAME)
                .orElseThrow(() -> new IllegalStateException(
                        "Anthropic API key not configured. Add it in Settings → AI review."));
        String model = appSettings.get(AppSettingsStore.Key.LLM_MODEL)
                .filter(s -> !s.isBlank())
                .orElse(DEFAULT_MODEL);

        // CI logs can be huge; cap the prompt input at the last 60 KB
        // (failure context lives at the end). Cheaper + faster + the
        // model rarely benefits from earlier "everything passed" lines.
        final int promptCap = 60_000;
        String trimmedLog = logText.length() > promptCap
                ? "… (log truncated; showing last " + promptCap + " bytes)\n" + logText.substring(logText.length() - promptCap)
                : logText;

        String system = """
                You diagnose CI check failures from build / test logs and propose a concrete \
                fix. Format your reply as concise markdown with these sections: \
                **Root cause** (1-3 sentences), **Fix** (numbered steps the developer can \
                follow, with code snippets where relevant), and **If that doesn't work** \
                (one short fallback). Be specific — quote the exact failing line / file \
                from the log. Do not pad. Output only the markdown.""";
        String user = "CI check **" + (checkName == null ? "(unnamed)" : checkName) + "** failed.\n\n"
                + "Log:\n```\n" + trimmedLog + "\n```";

        MessagesRequest body = new MessagesRequest(
                model,
                /* maxTokens */ 1200,
                system,
                ImmutableList.of(new MessagesRequest.Message("user", user)),
                false);

        try {
            MessagesResponse response = client.post()
                    .uri("/v1/messages")
                    .header("x-api-key", apiKey)
                    .body(body)
                    .retrieve()
                    .body(MessagesResponse.class);
            return extractText(response).trim();
        }
        catch (RestClientResponseException e) {
            log.warn("Anthropic diagnose call returned {}: {}", e.getStatusCode().value(), e.getResponseBodyAsString());
            throw new IllegalStateException(
                    "Claude diagnose failed (" + e.getStatusCode().value() + "). Check your API key and try again.", e);
        }
    }

    @Override
    public String polishCommentText(String draft)
    {
        if (draft == null || draft.trim().isEmpty()) {
            return "";
        }
        String apiKey = credentialService.getSecret(CredentialType.AI, ANTHROPIC_NAME)
                .orElseThrow(() -> new IllegalStateException(
                        "Anthropic API key not configured. Add it in Settings → AI review."));
        String model = appSettings.get(AppSettingsStore.Key.LLM_MODEL)
                .filter(s -> !s.isBlank())
                .orElse(DEFAULT_MODEL);

        // Single-shot rewrite. Cap output at ~600 tokens — code-review
        // comments shouldn't grow into essays just because the AI got
        // chatty. The system prompt is the contract: output ONLY the
        // rewritten text, no preamble, no markdown fences, same language.
        String system = """
                You rewrite developer-authored code-review comments so they read clearly, \
                politely, and constructively. Keep the technical meaning intact, but soften \
                blunt phrasing, replace commands with suggestions, and fix obvious typos / \
                grammar. Keep the same language as the input. Stay roughly the same length \
                — do not pad. Output ONLY the rewritten comment text. No preamble, no \
                quotes, no markdown fences, no explanation.""";
        String user = "Rewrite this code-review comment to be friendly and suitable for a "
                + "code-review thread:\n\n" + draft.trim();

        MessagesRequest body = new MessagesRequest(
                model,
                /* maxTokens */ 600,
                system,
                ImmutableList.of(new MessagesRequest.Message("user", user)),
                false);

        try {
            MessagesResponse response = client.post()
                    .uri("/v1/messages")
                    .header("x-api-key", apiKey)
                    .body(body)
                    .retrieve()
                    .body(MessagesResponse.class);
            String text = extractText(response).trim();
            // Strip any wrapping quotes the model occasionally adds
            // despite the system prompt.
            if (text.length() >= 2
                    && (text.startsWith("\"") && text.endsWith("\"")
                        || text.startsWith("`") && text.endsWith("`"))) {
                text = text.substring(1, text.length() - 1).trim();
            }
            return text;
        }
        catch (RestClientResponseException e) {
            log.warn("Anthropic polish call returned {}: {}", e.getStatusCode().value(), e.getResponseBodyAsString());
            throw new IllegalStateException(
                    "Claude polish failed (" + e.getStatusCode().value() + "). Check your API key and try again.", e);
        }
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

    private ReviewOutput parseReviewOutput(String text, String model)
    {
        String json = extractJsonObject(text);
        try {
            ParsedOutput parsed = objectMapper.readValue(json, ParsedOutput.class);
            List<ReviewOutput.LineComment> comments = Optional.ofNullable(parsed.comments())
                    .orElse(ImmutableList.of()).stream()
                    .map(comment -> new ReviewOutput.LineComment(comment.file(), comment.line(), comment.body(), normalizeSeverity(comment.severity())))
                    .collect(toImmutableList());
            return new ReviewOutput(
                    parsed.summary() == null ? "" : parsed.summary(),
                    comments,
                    PROVIDER_ID,
                    model);
        }
        catch (Exception e) {
            throw new IllegalStateException("Failed to parse Claude's JSON review output. Raw response: " + text, e);
        }
    }

    /**
     * Grab the first top-level {...} block; models occasionally wrap in fences despite instructions.
     */
    private static String extractJsonObject(String text)
    {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalStateException("No JSON object in model response: " + text);
        }
        return text.substring(start, end + 1);
    }

    private static String normalizeSeverity(String raw)
    {
        if (raw == null) {
            return "suggestion";
        }
        String lower = raw.toLowerCase(Locale.ROOT);
        return switch (lower) {
            case "info", "suggestion", "warning", "blocker" -> lower;
            default -> "suggestion";
        };
    }

    // ── Anthropic request / response DTOs ────────────────────────────────────

    record MessagesRequest(
            String model,
            @JsonProperty("max_tokens") int maxTokens,
            String system,
            List<Message> messages,
            boolean stream)
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

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ParsedOutput(String summary, List<ParsedComment> comments) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ParsedComment(String file, int line, String severity, String body) {}
}
