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
import com.bytequay.app.service.skills.SkillDraft;
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

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;
import static java.util.Objects.requireNonNullElse;

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

    private final RestClient client;
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
    public LlmCompletion complete(String systemPrompt, String userPrompt)
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
                requireNonNullElse(systemPrompt, ""),
                ImmutableList.of(new MessagesRequest.Message("user", requireNonNullElse(userPrompt, ""))),
                false);

        try {
            MessagesResponse response = client.post()
                    .uri("/v1/messages")
                    .header("x-api-key", apiKey)
                    .body(body)
                    .retrieve()
                    .body(MessagesResponse.class);
            String text = extractText(response);
            Map<String, Object> usage = response == null ? null : response.usage();
            return new LlmCompletion(
                    text,
                    usageTokens(usage, "input_tokens"),
                    usageTokens(usage, "output_tokens"),
                    model);
        }
        catch (RestClientResponseException e) {
            log.warn("Anthropic complete call returned {}: {}", e.getStatusCode().value(), e.getResponseBodyAsString());
            throw new IllegalStateException(
                    "Claude API error (" + e.getStatusCode().value() + "). Check your API key and try again.", e);
        }
    }

    private static long usageTokens(Map<String, Object> usage, String key)
    {
        if (usage == null) {
            return 0L;
        }
        return usage.get(key) instanceof Number n ? n.longValue() : 0L;
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
        String user = "CI check **" + requireNonNullElse(checkName, "(unnamed)") + "** failed.\n\n"
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
                    && ((text.startsWith("\"") && text.endsWith("\""))
                        || (text.startsWith("`") && text.endsWith("`")))) {
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

    @Override
    public PullRequestDraft draftPullRequest(
            String headBranch, String baseBranch, String diff, String prTemplate)
    {
        if (diff == null || diff.isBlank()) {
            throw new IllegalStateException(
                    "No diff between " + headBranch + " and " + baseBranch + " — nothing to summarize.");
        }
        String apiKey = credentialService.getSecret(CredentialType.AI, ANTHROPIC_NAME)
                .orElseThrow(() -> new IllegalStateException(
                        "Anthropic API key not configured. Add it in Settings → AI review."));
        String model = appSettings.get(AppSettingsStore.Key.LLM_MODEL)
                .filter(s -> !s.isBlank())
                .orElse(DEFAULT_MODEL);

        // Strict-JSON contract on the system prompt; the user message
        // carries the inputs. Template (when present) is included so
        // the description respects the team's section structure rather
        // than us inventing one.
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

        MessagesRequest body = new MessagesRequest(
                model,
                /* maxTokens */ 2_000,
                system,
                ImmutableList.of(new MessagesRequest.Message("user", user.toString())),
                false);

        try {
            MessagesResponse response = client.post()
                    .uri("/v1/messages")
                    .header("x-api-key", apiKey)
                    .body(body)
                    .retrieve()
                    .body(MessagesResponse.class);
            String text = extractText(response).trim();
            String json = extractJsonObject(text);
            JsonNode node = objectMapper.readTree(json);
            String title = node.path("title").asText("").trim();
            String description = node.path("description").asText("").trim();
            if (title.isEmpty()) {
                throw new IllegalStateException("Claude returned no title for the PR draft.");
            }
            return new PullRequestDraft(title, description);
        }
        catch (RestClientResponseException e) {
            log.warn("Anthropic draftPullRequest returned {}: {}", e.getStatusCode().value(), e.getResponseBodyAsString());
            throw new IllegalStateException(
                    "Claude PR draft failed (" + e.getStatusCode().value() + "). Check your API key and try again.", e);
        }
        catch (IOException e) {
            throw new IllegalStateException("Claude returned malformed PR draft JSON: " + e.getMessage(), e);
        }
    }

    @Override
    public SkillDraft draftSkill(String userPrompt, String scope)
    {
        if (userPrompt == null || userPrompt.isBlank()) {
            throw new IllegalStateException("Prompt is required to draft a skill.");
        }
        String apiKey = credentialService.getSecret(CredentialType.AI, ANTHROPIC_NAME)
                .orElseThrow(() -> new IllegalStateException(
                        "Anthropic API key not configured. Add it in Settings → AI review."));
        String model = appSettings.get(AppSettingsStore.Key.LLM_MODEL)
                .filter(s -> !s.isBlank())
                .orElse(DEFAULT_MODEL);

        // Strict-JSON contract — name + trigger description + body.
        // The description is the trigger ("loads when …"), so it must
        // read as a condition, not a title. Body is markdown the agent
        // will load whole when the trigger fires.
        String resolvedScope = scope == null || scope.isBlank() ? "global" : scope.trim().toLowerCase(Locale.ROOT);
        String system = """
                You draft a single library "skill" for an AI coding agent. \
                A skill is model-triggered: the agent decides whether to load \
                it based on the trigger description. Output STRICT JSON: \
                {"name": string, "description": string, "body": string}. \
                Constraints: \
                - "name" is short, ≤ 6 words, no punctuation other than dashes. \
                - "description" reads as a TRIGGER — a "when X" or "loads when …" \
                  condition the agent matches on. Not a title; not "this skill". \
                - "body" is markdown — the actual instructions the agent loads \
                  when the trigger fires. Keep it focused; one screen at most. \
                Output ONLY the JSON object — no preamble, no markdown fences.""";
        String user = "Scope: " + resolvedScope + ".\n"
                + "User prompt:\n" + userPrompt.trim();

        MessagesRequest body = new MessagesRequest(
                model,
                /* maxTokens */ 800,
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
            String json = extractJsonObject(text);
            JsonNode node = objectMapper.readTree(json);
            String name = node.path("name").asText("").trim();
            String description = node.path("description").asText("").trim();
            String skillBody = node.path("body").asText("").trim();
            if (name.isEmpty() || description.isEmpty() || skillBody.isEmpty()) {
                throw new IllegalStateException("Claude returned an incomplete skill draft.");
            }
            return new SkillDraft(name, description, skillBody);
        }
        catch (RestClientResponseException e) {
            log.warn("Anthropic draftSkill returned {}: {}", e.getStatusCode().value(), e.getResponseBodyAsString());
            throw new IllegalStateException(
                    "Claude skill draft failed (" + e.getStatusCode().value() + "). Check your API key and try again.", e);
        }
        catch (IOException e) {
            throw new IllegalStateException("Claude returned malformed skill draft JSON: " + e.getMessage(), e);
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

    // Package-private for unit tests — parsing the model's free-form
    // output is the fragile part worth pinning directly.
    ReviewOutput parseReviewOutput(String text, String model)
    {
        String json = extractJsonObject(text);
        try {
            ReviewPrompt.ParsedOutput parsed = objectMapper.readValue(json, ReviewPrompt.ParsedOutput.class);
            List<ReviewOutput.LineComment> comments = Optional.ofNullable(parsed.comments())
                    .orElse(ImmutableList.of()).stream()
                    .map(comment -> new ReviewOutput.LineComment(comment.file(), comment.line(), comment.body(), normalizeSeverity(comment.severity())))
                    .collect(toImmutableList());
            return new ReviewOutput(
                    requireNonNullElse(parsed.summary(), ""),
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
    static String extractJsonObject(String text)
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
}
