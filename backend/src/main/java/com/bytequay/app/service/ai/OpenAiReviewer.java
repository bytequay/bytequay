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
import com.bytequay.app.domain.PullRequestDraft;
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
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;

/**
 * LlmReviewer implementation against OpenAI's chat-completions API. Same
 * request shape as {@link DeepSeekReviewer} (DeepSeek's surface mirrors
 * OpenAI's) — only the base URL, credential key, and default model
 * differ. Streaming reuses the inherited default (delegates to the
 * non-streaming path); switching to real SSE streaming is a follow-up
 * once the rest of the flow exercises the provider.
 */
@Component
public class OpenAiReviewer
        implements LlmReviewer
{
    private static final Logger log = LoggerFactory.getLogger(OpenAiReviewer.class);
    private static final String PROVIDER_ID = "openai";
    private static final String OPENAI_NAME = "openai";
    private static final String DEFAULT_MODEL = "gpt-4o-mini";
    private static final int MAX_OUTPUT_TOKENS = 8_192;

    private final RestClient client;
    private final CredentialService credentialService;
    private final AppSettingsStore appSettings;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public OpenAiReviewer(
            @Qualifier("openAiRestClient") RestClient openAiRestClient,
            CredentialService credentialService,
            AppSettingsStore appSettings)
    {
        this.client = requireNonNull(openAiRestClient, "openAiRestClient is null");
        this.credentialService = requireNonNull(credentialService, "credentialService is null");
        this.appSettings = requireNonNull(appSettings, "appSettings is null");
    }

    @Override
    public String providerId()
    {
        return PROVIDER_ID;
    }

    @Override
    public String displayName()
    {
        return "OpenAI";
    }

    @Override
    public boolean isConfigured()
    {
        return credentialService.get(CredentialType.AI, OPENAI_NAME).isPresent();
    }

    @Override
    public ReviewOutput review(ReviewRequest request)
    {
        String apiKey = resolveApiKey();
        String model = resolveModel();

        ChatRequest body = new ChatRequest(
                model,
                ImmutableList.of(
                        new ChatRequest.Message("system", ReviewPrompt.systemPrompt(request)),
                        new ChatRequest.Message("user", ReviewPrompt.userMessage(request))),
                MAX_OUTPUT_TOKENS,
                false);

        ChatResponse response = postChat(body, apiKey, "chat completions", "OpenAI API error");
        String text = extractText(response);
        return parseReviewOutput(text, model);
    }

    @Override
    public LlmCompletion complete(String systemPrompt, String userPrompt)
    {
        String apiKey = resolveApiKey();
        String model = resolveModel();

        ChatRequest body = new ChatRequest(
                model,
                ImmutableList.of(
                        new ChatRequest.Message("system", requireNonNullElse(systemPrompt, "")),
                        new ChatRequest.Message("user", requireNonNullElse(userPrompt, ""))),
                MAX_OUTPUT_TOKENS,
                false);

        ChatResponse response = postChat(body, apiKey, "complete call", "OpenAI API error");
        String text = extractText(response);
        Map<String, Object> usage = response == null ? null : response.usage();
        return new LlmCompletion(
                text,
                usageTokens(usage, "prompt_tokens"),
                usageTokens(usage, "completion_tokens"),
                model);
    }

    private static long usageTokens(Map<String, Object> usage, String key)
    {
        if (usage == null) {
            return 0L;
        }
        return usage.get(key) instanceof Number n ? n.longValue() : 0L;
    }

    @Override
    public String polishCommentText(String draft)
    {
        if (draft == null || draft.trim().isEmpty()) {
            return "";
        }
        String apiKey = resolveApiKey();
        String model = resolveModel();

        // Same prompt contract as the other providers' polish paths so a
        // user switching providers gets the same polish behaviour.
        String system = "You rewrite developer-authored code-review comments so they read clearly, "
                + "politely, and constructively. Keep the technical meaning intact, but soften "
                + "blunt phrasing, replace commands with suggestions, and fix obvious typos / "
                + "grammar. Keep the same language as the input. Stay roughly the same length "
                + "— do not pad. Output ONLY the rewritten comment text. No preamble, no "
                + "quotes, no markdown fences, no explanation.";
        String user = "Rewrite this code-review comment to be friendly and suitable for a "
                + "code-review thread:\n\n" + draft.trim();

        ChatRequest body = new ChatRequest(
                model,
                ImmutableList.of(
                        new ChatRequest.Message("system", system),
                        new ChatRequest.Message("user", user)),
                /* maxTokens */ 600,
                false);

        ChatResponse response = postChat(body, apiKey, "polish call", "OpenAI polish failed");
        String text = extractText(response).trim();
        if (text.length() >= 2
                && ((text.startsWith("\"") && text.endsWith("\""))
                    || (text.startsWith("`") && text.endsWith("`")))) {
            text = text.substring(1, text.length() - 1).trim();
        }
        return text;
    }

    @Override
    public PullRequestDraft draftPullRequest(
            String headBranch, String baseBranch, String diff, String prTemplate)
    {
        if (diff == null || diff.isBlank()) {
            throw new IllegalStateException(
                    "No diff between " + headBranch + " and " + baseBranch + " — nothing to summarize.");
        }
        String apiKey = resolveApiKey();
        String model = resolveModel();

        // Same prompt contract as ClaudeReviewer.draftPullRequest so the
        // user sees comparable output regardless of provider.
        String system = """
                You draft a GitHub pull request title and description from a unified diff. \
                Output STRICT JSON: {"title": string, "description": string}. \
                The title is short, imperative, and specific (under 72 chars). \
                The description is markdown. If the team's PR template is included below, \
                fill its sections; otherwise default to a brief Summary, a What changed list \
                (one bullet per substantive change), and a Test plan (1-3 verifications). \
                Do not invent files or behaviors not present in the diff. \
                Output ONLY the JSON object — no preamble, no markdown fences.""";
        StringBuilder user = new StringBuilder();
        user.append("Head branch: `").append(requireNonNullElse(headBranch, "(unknown)")).append("`\n");
        user.append("Base branch: `").append(requireNonNullElse(baseBranch, "(unknown)")).append("`\n\n");
        if (prTemplate != null && !prTemplate.isBlank()) {
            user.append("Repo PR template:\n```markdown\n").append(prTemplate).append("\n```\n\n");
        }
        user.append("Unified diff:\n```diff\n").append(diff).append("\n```\n");

        ChatRequest body = new ChatRequest(
                model,
                ImmutableList.of(
                        new ChatRequest.Message("system", system),
                        new ChatRequest.Message("user", user.toString())),
                /* maxTokens */ 2_000,
                false);

        ChatResponse response = postChat(body, apiKey, "draftPullRequest", "OpenAI PR draft failed");
        String text = extractText(response).trim();
        String json = extractJsonObject(text);
        try {
            JsonNode node = objectMapper.readTree(json);
            String title = node.path("title").asText("").trim();
            String description = node.path("description").asText("").trim();
            if (title.isEmpty()) {
                throw new IllegalStateException("OpenAI returned no title for the PR draft.");
            }
            return new PullRequestDraft(title, description);
        }
        catch (IOException e) {
            throw new IllegalStateException("OpenAI returned malformed PR draft JSON: " + e.getMessage(), e);
        }
    }

    private String resolveApiKey()
    {
        return credentialService.getSecret(CredentialType.AI, OPENAI_NAME)
                .orElseThrow(() -> new IllegalStateException(
                        "OpenAI API key not configured. Add it in Settings → AI review."));
    }

    private String resolveModel()
    {
        return appSettings.get(AppSettingsStore.Key.LLM_MODEL)
                .filter(s -> !s.isBlank())
                .orElse(DEFAULT_MODEL);
    }

    private ChatResponse postChat(ChatRequest body, String apiKey, String opLabel, String userError)
    {
        try {
            return client.post()
                    .uri("/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .body(body)
                    .retrieve()
                    .body(ChatResponse.class);
        }
        catch (RestClientResponseException e) {
            log.warn("OpenAI {} returned {}: {}", opLabel, e.getStatusCode().value(), e.getResponseBodyAsString());
            throw new IllegalStateException(
                    userError + " (" + e.getStatusCode().value() + "). Check your API key and try again.", e);
        }
    }

    private static String extractText(ChatResponse response)
    {
        if (response == null || response.choices() == null || response.choices().isEmpty()) {
            throw new IllegalStateException("OpenAI returned an empty response");
        }
        ChatResponse.Choice first = response.choices().get(0);
        if (first == null || first.message() == null) {
            throw new IllegalStateException("OpenAI returned no message in choice 0");
        }
        String content = first.message().content();
        if (content == null || content.isBlank()) {
            throw new IllegalStateException("OpenAI returned empty content");
        }
        return content;
    }

    private ReviewOutput parseReviewOutput(String text, String model)
    {
        String json = extractJsonObject(text);
        try {
            ReviewPrompt.ParsedOutput parsed = objectMapper.readValue(json, ReviewPrompt.ParsedOutput.class);
            List<ReviewOutput.LineComment> comments = Optional.ofNullable(parsed.comments())
                    .orElse(ImmutableList.of()).stream()
                    .map(comment -> new ReviewOutput.LineComment(
                            comment.file(),
                            comment.line(),
                            comment.body(),
                            normalizeSeverity(comment.severity())))
                    .collect(toImmutableList());
            return new ReviewOutput(
                    requireNonNullElse(parsed.summary(), ""),
                    comments,
                    PROVIDER_ID,
                    model);
        }
        catch (Exception e) {
            throw new IllegalStateException("Failed to parse OpenAI's JSON review output. Raw response: " + text, e);
        }
    }

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

    // ── OpenAI chat completions DTOs ────────────────────────────────────────

    record ChatRequest(
            String model,
            List<Message> messages,
            @JsonProperty("max_tokens") int maxTokens,
            boolean stream)
    {
        record Message(String role, String content) {}
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record ChatResponse(
            String id,
            String model,
            List<Choice> choices,
            Map<String, Object> usage)
    {
        @JsonIgnoreProperties(ignoreUnknown = true)
        record Choice(int index, ResponseMessage message, @JsonProperty("finish_reason") String finishReason) {}

        @JsonIgnoreProperties(ignoreUnknown = true)
        record ResponseMessage(String role, String content) {}
    }
}
