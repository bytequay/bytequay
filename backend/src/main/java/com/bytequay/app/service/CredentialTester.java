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
package com.bytequay.app.service;

import com.bytequay.app.domain.CredentialType;
import com.bytequay.app.service.local.ds4.Ds4LifecycleService;
import com.bytequay.app.service.local.ds4.Ds4State;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.net.URI;
import java.util.List;
import java.util.Locale;

import static java.util.Objects.requireNonNull;

/**
 * One-shot verification for stored credentials. Each known
 * (type, name) routes to a lightweight call that proves the
 * credential is usable: a HEAD/GET against a free endpoint where
 * possible, a minimal billable call otherwise.
 *
 * <p>Used by Settings → AI review → Credentials' "Test" button so
 * the user gets immediate confirmation a key works instead of finding
 * out the next time they trigger a review or a checkpoint.
 */
@Component
public class CredentialTester
{
    private static final Logger log = LoggerFactory.getLogger(CredentialTester.class);

    private static final String ANTHROPIC_API_URL = "https://api.anthropic.com/v1/messages";
    private static final String ANTHROPIC_VERSION = "2023-06-01";
    private static final String OPENAI_MODELS_URL = "https://api.openai.com/v1/models";
    private static final String GITHUB_USER_URL = "https://api.github.com/user";

    private final CredentialService credentialService;
    private final RestClient deepseekRestClient;
    private final Ds4LifecycleService ds4;

    public CredentialTester(
            CredentialService credentialService,
            RestClient deepseekRestClient,
            Ds4LifecycleService ds4)
    {
        this.credentialService = requireNonNull(credentialService, "credentialService is null");
        this.deepseekRestClient = requireNonNull(deepseekRestClient, "deepseekRestClient is null");
        this.ds4 = requireNonNull(ds4, "ds4 is null");
    }

    /**
     * Run the registered probe for a stored credential. Returns
     * {@code ok=true} when the upstream answered without an auth /
     * not-found error, even if the response body is empty.
     */
    public TestResult test(CredentialType type, String name, String instanceName)
    {
        String secret = credentialService.getSecret(type, name, instanceName)
                .orElse(null);
        if (secret == null || secret.isBlank()) {
            return TestResult.fail("No stored value for this credential.");
        }
        long started = System.nanoTime();
        try {
            String probeName = (type.name() + "/" + name).toLowerCase(Locale.ROOT);
            switch (probeName) {
                case "account/github" -> testGitHub(secret);
                case "ai/anthropic" -> testAnthropic(secret);
                case "ai/openai" -> testOpenAi(secret);
                case "ai/deepseek" -> testDeepSeek(secret);
                case "ai/local" -> testLocal(secret);
                default -> {
                    return TestResult.fail("No test probe registered for " + probeName + ".");
                }
            }
            long ms = (System.nanoTime() - started) / 1_000_000L;
            return TestResult.ok("Looks good.", ms);
        }
        catch (RestClientResponseException e) {
            long ms = (System.nanoTime() - started) / 1_000_000L;
            String body = truncate(e.getResponseBodyAsString(), 200);
            log.warn("Credential test {}/{} failed with {}: {}",
                    type, name, e.getStatusCode().value(), body);
            return TestResult.failWithLatency(
                    "Upstream returned " + e.getStatusCode().value()
                            + (body.isBlank() ? "." : ": " + body),
                    ms);
        }
        catch (RuntimeException e) {
            long ms = (System.nanoTime() - started) / 1_000_000L;
            return TestResult.failWithLatency(
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage(),
                    ms);
        }
    }

    private static void testGitHub(String pat)
    {
        RestClient.create()
                .get()
                .uri(URI.create(GITHUB_USER_URL))
                .header("Accept", "application/vnd.github+json")
                .header("Authorization", "Bearer " + pat)
                .header("X-GitHub-Api-Version", "2022-11-28")
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Smallest possible Anthropic call — 1 output token, fixed cheap
     * model. Costs a fraction of a cent but proves the key works
     * end-to-end (auth + billing + rate limit). Anthropic doesn't
     * publish a free probe endpoint that gates on the API key alone.
     */
    private static void testAnthropic(String apiKey)
    {
        RestClient.create()
                .post()
                .uri(URI.create(ANTHROPIC_API_URL))
                .header("Content-Type", "application/json")
                .header("anthropic-version", ANTHROPIC_VERSION)
                .header("x-api-key", apiKey)
                .body(new AnthropicProbe(
                        "claude-haiku-4-5",
                        1,
                        List.of(new AnthropicMessage("user", "ping"))))
                .retrieve()
                .toBodilessEntity();
    }

    private static void testOpenAi(String apiKey)
    {
        RestClient.create()
                .get()
                .uri(URI.create(OPENAI_MODELS_URL))
                .header("Authorization", "Bearer " + apiKey)
                .retrieve()
                .toBodilessEntity();
    }

    private void testDeepSeek(String apiKey)
    {
        // DeepSeek's /v1/models is OpenAI-compatible and free.
        deepseekRestClient.get()
                .uri("/v1/models")
                .header("Authorization", "Bearer " + apiKey)
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Local LLM stores a base URL, not a key. Probe by GET /v1/models
     * on the supplied base — Ollama, LM Studio, and llama.cpp all
     * expose it. Treat any 2xx as "endpoint is reachable and speaks
     * OpenAI-compatible". Retained for legacy {@code ai/local}
     * credential rows; the curated catalog no longer exposes a
     * "Local" provider — the {@code deepseek-v4-flash} model variant
     * routes through {@link #testDs4Server()} instead.
     */
    private static void testLocal(String baseUrl)
    {
        String trimmed = baseUrl.trim();
        if (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        RestClient.create()
                .get()
                .uri(URI.create(trimmed + "/v1/models"))
                .retrieve()
                .toBodilessEntity();
    }

    /**
     * Readiness check for the locally-served ds4 model variant.
     * Bypasses any stored credential — the dummy local token
     * {@code dsv4-local} is baked into the reviewer — and reports
     * ready iff the lifecycle service is in
     * {@link Ds4State#RUNNING}. Used by Settings and the picker to
     * show "● server running" without exercising a real chat call.
     */
    public TestResult testDs4Server()
    {
        long started = System.nanoTime();
        Ds4State state = ds4.status().state();
        long ms = (System.nanoTime() - started) / 1_000_000L;
        if (state == Ds4State.RUNNING) {
            return TestResult.ok("ds4 server is running.", ms);
        }
        return TestResult.failWithLatency(
                "ds4 server is " + state + "; pick Start in Settings → Local AI (ds4).", ms);
    }

    private static String truncate(String s, int max)
    {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /** UI-facing result. {@code latencyMs} is null when the call
     *  didn't run (e.g. no stored value). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record TestResult(boolean ok, String message, Long latencyMs)
    {
        public static TestResult ok(String message, long latencyMs)
        {
            return new TestResult(true, message, latencyMs);
        }

        public static TestResult fail(String message)
        {
            return new TestResult(false, message, null);
        }

        public static TestResult failWithLatency(String message, long latencyMs)
        {
            return new TestResult(false, message, latencyMs);
        }
    }

    private record AnthropicProbe(
            String model,
            @JsonProperty("max_tokens") int maxTokens,
            List<AnthropicMessage> messages)
    {}

    private record AnthropicMessage(String role, String content) {}
}
