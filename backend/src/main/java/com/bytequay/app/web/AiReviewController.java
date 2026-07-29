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
package com.bytequay.app.web;

import com.bytequay.app.domain.AiReviewDraft;
import com.bytequay.app.repository.AppSettingsStore;
import com.bytequay.app.service.ai.AiReviewService;
import com.bytequay.app.service.ai.LlmReviewer;
import com.bytequay.app.service.ai.LlmReviewerRegistry;
import com.google.common.collect.ImmutableMap;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@RestController
public class AiReviewController
{
    private final AiReviewService aiReviewService;
    private final LlmReviewerRegistry registry;
    private final AppSettingsStore appSettings;

    public AiReviewController(
            AiReviewService aiReviewService,
            LlmReviewerRegistry registry,
            AppSettingsStore appSettings)
    {
        this.aiReviewService = requireNonNull(aiReviewService, "aiReviewService is null");
        this.registry = requireNonNull(registry, "registry is null");
        this.appSettings = requireNonNull(appSettings, "appSettings is null");
    }

    public record ProviderInfo(String providerId, String displayName, boolean configured, boolean active) {}

    /** GET /ai/providers — lists all known providers and which is active. */
    @GetMapping("/ai/providers")
    public List<ProviderInfo> providers()
    {
        String activeId = registry.active().providerId();
        return registry.all().stream()
                .map((LlmReviewer r) -> new ProviderInfo(
                        r.providerId(), r.displayName(), r.isConfigured(), r.providerId().equals(activeId)))
                .collect(toImmutableList());
    }

    public record PolishRequest(String text) {}
    public record PolishResponse(String text) {}

    /**
     * POST /ai/polish — rewrites a developer-authored code-review comment
     * to read more clearly, politely, and constructively. Used by the
     * "Better words" button in the inline-comment composer. Routes to the
     * currently-active LLM provider via the registry; returns the polished
     * text directly so the UI can replace the textarea contents.
     *
     * <p>Errors are caught and re-thrown as {@link ResponseStatusException}
     * so Spring forwards the *actual* message to the renderer (the IPC
     * bridge surfaces the response body in the JS Error). Without this
     * wrap, the bare exception type is masked into a generic
     * "Internal server error" by Spring's default error attributes.
     */
    @PostMapping("/ai/polish")
    public PolishResponse polish(@RequestBody PolishRequest request)
    {
        if (request == null || request.text() == null || request.text().trim().isEmpty()) {
            return new PolishResponse("");
        }
        try {
            String polished = registry.active().polishCommentText(request.text());
            return new PolishResponse(polished);
        }
        catch (UnsupportedOperationException e) {
            // Provider doesn't implement polish (e.g. local Ollama).
            throw new ResponseStatusException(
                    HttpStatus.NOT_IMPLEMENTED, e.getMessage(), e);
        }
        catch (IllegalStateException e) {
            // Missing API key, upstream error, etc. — these are user-actionable.
            throw new ResponseStatusException(
                    HttpStatus.BAD_GATEWAY, e.getMessage(), e);
        }
    }

    public record DiagnoseCheckRequest(String checkName, String log) {}
    public record DiagnoseCheckResponse(String suggestion) {}

    /**
     * POST /ai/diagnoseCheck — sends a CI failure log to the active LLM and
     * returns a short root-cause-and-fix markdown reply. Powers the
     * "Ask AI to fix" button on the merge bar's failure cards.
     */
    @PostMapping("/ai/diagnoseCheck")
    public DiagnoseCheckResponse diagnoseCheck(@RequestBody DiagnoseCheckRequest request)
    {
        if (request == null || request.log() == null || request.log().trim().isEmpty()) {
            return new DiagnoseCheckResponse("");
        }
        try {
            String suggestion = registry.active().diagnoseCheckRunFailure(request.checkName(), request.log());
            return new DiagnoseCheckResponse(suggestion);
        }
        catch (UnsupportedOperationException e) {
            throw new ResponseStatusException(HttpStatus.NOT_IMPLEMENTED, e.getMessage(), e);
        }
        catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, e.getMessage(), e);
        }
    }

    /** GET /ai/review/latest?prId= — returns the most recent draft for a PR. */
    @GetMapping("/ai/review/latest")
    public AiReviewDraft latest(@RequestParam("prId") long prId)
    {
        return aiReviewService.latest(prId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatusCode.valueOf(404), "No draft for this PR"));
    }

    /** GET /ai/review/history?prId= — all drafts for a PR, newest first. */
    @GetMapping("/ai/review/history")
    public List<AiReviewDraft> history(@RequestParam("prId") long prId)
    {
        return aiReviewService.history(prId);
    }

    /** GET /ai/settings — the active provider + configured model. */
    @GetMapping("/ai/settings")
    public Map<String, String> getSettings()
    {
        LlmReviewer active = registry.active();
        return ImmutableMap.of(
                "provider", active.providerId(),
                "model", appSettings.get(AppSettingsStore.Key.LLM_MODEL).orElse(""));
    }

    /** PUT /ai/settings?provider=&model= — updates the active provider and model. */
    @PostMapping("/ai/settings")
    public Map<String, String> setSettings(
            @RequestParam("provider") String provider,
            @RequestParam(value = "model", required = false) String model)
    {
        appSettings.set(AppSettingsStore.Key.LLM_PROVIDER, provider);
        if (model != null) {
            appSettings.set(AppSettingsStore.Key.LLM_MODEL, model);
        }
        return getSettings();
    }
}
