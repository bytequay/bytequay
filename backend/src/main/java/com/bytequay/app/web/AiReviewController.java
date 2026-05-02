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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

import static com.bytequay.app.config.AsyncConfig.APPLICATION_EXECUTOR;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@RestController
public class AiReviewController
{
    private static final Logger log = LoggerFactory.getLogger(AiReviewController.class);
    // Streaming runs are bounded by the Anthropic-side STREAM_TIMEOUT in
    // ClaudeReviewer (5 min); give the SseEmitter a slightly longer ceiling
    // so the upstream timeout fires first and we surface a clean error.
    private static final long STREAM_TIMEOUT_MS = 6 * 60 * 1000L;

    private final AiReviewService aiReviewService;
    private final PatResolver patResolver;
    private final LlmReviewerRegistry registry;
    private final AppSettingsStore appSettings;
    private final Executor executor;

    /**
     * Tracks the in-flight async run for each (repo, number) pair so the
     * frontend's status poller has something to look at while the LLM is
     * working. Lives in memory only — drafts themselves are persisted by
     * AiReviewService, so a backend restart simply drops the "running" flag
     * and the user re-clicks. Keyed by "repo#number" since prId isn't known
     * until after the local PR-store lookup.
     */
    private final ConcurrentHashMap<String, RunState> running = new ConcurrentHashMap<>();

    public AiReviewController(
            AiReviewService aiReviewService,
            PatResolver patResolver,
            LlmReviewerRegistry registry,
            AppSettingsStore appSettings,
            @Qualifier(APPLICATION_EXECUTOR) Executor executor)
    {
        this.aiReviewService = requireNonNull(aiReviewService, "aiReviewService is null");
        this.patResolver = requireNonNull(patResolver, "patResolver is null");
        this.registry = requireNonNull(registry, "registry is null");
        this.appSettings = requireNonNull(appSettings, "appSettings is null");
        this.executor = requireNonNull(executor, "executor is null");
    }

    public record RunState(String state, String error) {}

    public record StatusResponse(String state, String error) {}

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

    /**
     * POST /ai/review/start?repo=&number= — kicks off the AI review on the
     * application executor and returns immediately. The frontend polls
     * /ai/review/status until the run finishes, then GETs the persisted
     * draft via /ai/review/latest. Idempotent for the (repo, number) pair:
     * a second call while one is already running is a no-op.
     */
    @PostMapping("/ai/review/start")
    public Map<String, String> start(
            @RequestParam("prId") long prId,
            @RequestParam("repo") String repo,
            @RequestParam("number") int number)
    {
        String key = repo + "#" + number;
        RunState existing = running.get(key);
        if (existing != null && "RUNNING".equals(existing.state())) {
            return ImmutableMap.of("state", "RUNNING");
        }
        running.put(key, new RunState("RUNNING", null));
        String pat = patResolver.resolve(repo);
        executor.execute(() -> {
            try {
                aiReviewService.runReview(pat, prId, repo, number);
                running.put(key, new RunState("DONE", null));
            }
            catch (Exception e) {
                log.warn("AI review run failed for {}: {}", key, e.getMessage());
                running.put(key, new RunState("FAILED", e.getMessage()));
            }
        });
        return ImmutableMap.of("state", "RUNNING");
    }

    /**
     * GET /ai/review/status?repo=&number= — current state of the most recent
     * start() for this PR. Returns IDLE when nothing has been kicked off
     * since backend startup; the frontend treats IDLE the same as DONE for
     * fetch purposes.
     */
    @GetMapping("/ai/review/status")
    public StatusResponse status(
            @RequestParam("repo") String repo,
            @RequestParam("number") int number)
    {
        RunState state = running.get(repo + "#" + number);
        if (state == null) {
            return new StatusResponse("IDLE", null);
        }
        return new StatusResponse(state.state(), state.error());
    }

    /** POST /ai/review?prId=&repo=&number= — runs a single review against the active LLM. */
    @PostMapping("/ai/review")
    public AiReviewDraft run(
            @RequestParam("prId") long prId,
            @RequestParam("repo") String repo,
            @RequestParam("number") int number)
    {
        String pat = patResolver.resolve(repo);
        return aiReviewService.runReview(pat, prId, repo, number);
    }

    /**
     * GET /ai/review/stream?repo=&number= — same review, streamed back as SSE.
     *
     * <p>Event types:
     * <ul>
     *   <li>{@code delta} — raw text chunks from the model as they arrive</li>
     *   <li>{@code complete} — the persisted {@link AiReviewDraft} after parsing</li>
     *   <li>{@code error} — terminal failure, includes a user-readable message</li>
     * </ul>
     * The work runs on the application executor so we don't pin a Tomcat
     * worker for the duration of the upstream LLM call.
     */
    @GetMapping(value = "/ai/review/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(
            @RequestParam("prId") long prId,
            @RequestParam("repo") String repo,
            @RequestParam("number") int number)
    {
        SseEmitter emitter = new SseEmitter(STREAM_TIMEOUT_MS);
        String pat = patResolver.resolve(repo);
        executor.execute(() -> runStream(emitter, pat, prId, repo, number));
        return emitter;
    }

    private void runStream(SseEmitter emitter, String pat, long prId, String repo, int number)
    {
        try {
            AiReviewDraft draft = aiReviewService.streamReview(pat, prId, repo, number, chunk -> sendDelta(emitter, chunk));
            emitter.send(SseEmitter.event().name("complete").data(draft));
            emitter.complete();
        }
        catch (ResponseStatusException e) {
            sendError(emitter, e.getReason() != null ? e.getReason() : e.getMessage());
        }
        catch (Exception e) {
            log.warn("AI review stream failed for {}#{}: {}", repo, number, e.getMessage());
            sendError(emitter, e.getMessage());
        }
    }

    private static void sendDelta(SseEmitter emitter, String chunk)
    {
        try {
            emitter.send(SseEmitter.event().name("delta").data(ImmutableMap.of("text", chunk)));
        }
        catch (IOException e) {
            // Client closed the stream — surface as a runtime exception so the
            // outer try/catch in runStream stops the upstream call promptly.
            throw new RuntimeException("SSE channel closed", e);
        }
    }

    private void sendError(SseEmitter emitter, String message)
    {
        try {
            emitter.send(SseEmitter.event().name("error").data(ImmutableMap.of("message", message == null ? "unknown error" : message)));
            emitter.complete();
        }
        catch (IOException ignored) {
            emitter.completeWithError(ignored);
        }
    }

    public record EditCommentRequest(String editedBody) {}

    /**
     * PUT /ai/review/{draftId}/comments/{commentId} — edit a single AI
     * comment's body. Pass {@code editedBody=""} (or null) to clear the
     * edit and revert to the AI's original. Returns the parent draft so
     * the frontend re-renders without a separate fetch.
     */
    @PutMapping("/ai/review/{draftId}/comments/{commentId}")
    public AiReviewDraft updateComment(
            @PathVariable long draftId,
            @PathVariable long commentId,
            @RequestBody EditCommentRequest req)
    {
        return aiReviewService.updateCommentBody(draftId, commentId, req.editedBody());
    }

    /** DELETE /ai/review/{draftId}/comments/{commentId} — drop one finding. */
    @DeleteMapping("/ai/review/{draftId}/comments/{commentId}")
    public AiReviewDraft deleteComment(@PathVariable long draftId, @PathVariable long commentId)
    {
        return aiReviewService.deleteComment(draftId, commentId);
    }

    public record StageCommentRequest(
            long prId,
            String repo,
            int number,
            String headSha,
            String filePath,
            int line,
            String side,
            Integer startLine,
            String startSide,
            String body)
    {}

    /**
     * POST /ai/review/stage — appends a human-authored inline comment to
     * the active review draft for a PR (creating a draft if none exists).
     * The comment stays local until the user calls /publish; the response
     * is the refreshed draft so the frontend can re-render the tray.
     */
    @PostMapping("/ai/review/stage")
    public AiReviewDraft stageComment(@RequestBody StageCommentRequest req)
    {
        return aiReviewService.stageHumanComment(
                req.prId(),
                req.repo(),
                req.number(),
                req.headSha(),
                req.filePath(),
                req.line(),
                req.side(),
                req.startLine(),
                req.startSide(),
                req.body());
    }

    public record DismissCommentRequest(boolean dismissed) {}

    /**
     * PUT /ai/review/{draftId}/comments/{commentId}/dismissed — soft-toggle
     * for dismiss/restore. Body: {"dismissed": true|false}. Dismissed
     * comments are kept on the row but excluded from the publish payload.
     */
    @PutMapping("/ai/review/{draftId}/comments/{commentId}/dismissed")
    public AiReviewDraft setCommentDismissed(
            @PathVariable long draftId,
            @PathVariable long commentId,
            @RequestBody DismissCommentRequest req)
    {
        return aiReviewService.setCommentDismissed(draftId, commentId, req.dismissed());
    }

    /**
     * POST /ai/review/{draftId}/publish?event=COMMENT|APPROVE|REQUEST_CHANGES
     * — turns a stored draft into a real GitHub review. The service resolves
     * the draft's repo first and then asks the supplied function for the
     * appropriate PAT (per-repo override falling back to account).
     */
    public record PublishRequest(String body) {}

    @PostMapping("/ai/review/{draftId}/publish")
    public AiReviewDraft publish(
            @PathVariable long draftId,
            @RequestParam(value = "event", required = false, defaultValue = "COMMENT") String event,
            @RequestBody(required = false) PublishRequest req)
    {
        String body = req != null ? req.body() : null;
        return aiReviewService.publish(patResolver::resolve, draftId, event, body);
    }

    public record PublishForPrRequest(
            long prId,
            String repo,
            int number,
            String headSha,
            String event,
            String body)
    {}

    /**
     * POST /ai/review/publish-for-pr — verdict-first publish path. The
     * backend finds-or-creates the active draft for the PR and submits
     * it, so an Approve / Comment with no inline comments still works.
     */
    @PostMapping("/ai/review/publish-for-pr")
    public AiReviewDraft publishForPr(@RequestBody PublishForPrRequest req)
    {
        return aiReviewService.publishForPr(
                patResolver::resolve,
                req.prId(),
                req.repo(),
                req.number(),
                req.headSha(),
                req.event(),
                req.body());
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

    /** DELETE /ai/review/{draftId} — removes a draft + its comments. */
    @DeleteMapping("/ai/review/{draftId}")
    public Map<String, String> delete(@PathVariable long draftId)
    {
        aiReviewService.delete(draftId);
        return ImmutableMap.of("result", "deleted");
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
