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
package com.bytequay.app.service.learning;

import com.bytequay.app.domain.KnowledgeItem;
import com.bytequay.app.domain.PrReviewThreadMessage;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.service.agents.ToolExecutor;
import com.bytequay.app.service.agents.TurnHooks;
import com.bytequay.app.service.agents.TurnResult;
import com.bytequay.app.service.agents.TurnRunner;
import com.bytequay.app.service.agents.TurnSpec;
import com.bytequay.app.service.review.CliReviewException;
import com.bytequay.app.service.review.CliReviewRunner;
import com.bytequay.app.service.review.ReviewProviderEndpoints;
import com.bytequay.app.service.workmodel.SessionAudience;
import com.bytequay.app.service.workmodel.WorkModelResolver;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableSet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import static com.google.common.base.Strings.nullToEmpty;
import static java.lang.Math.toIntExact;
import static java.util.Objects.requireNonNull;

/**
 * Ephemeral, provider-neutral extraction call: one bounded model turn per
 * analyzed PR that distills durable lessons from the evidence bundle. Runs
 * synchronously in the bounded project-learning owner; the provider comes
 * from the workspace's resolved review engine, so the same default used by
 * ordinary workspace sessions also applies here.
 * No tools, one round, strict JSON out — and {@code {"lessons": []}} is an
 * expected, correct answer.
 */
@Component
public class LessonExtractor
{
    private static final Logger log = LoggerFactory.getLogger(LessonExtractor.class);

    private static final int MAX_OUTPUT_TOKENS = 4_096;
    /** Per-PR extraction cost ceiling, in milli-USD. */
    private static final long COST_CAP_MILLI_USD = 300;
    private static final int MAX_LESSONS_PER_PR = 8;
    private static final int BODY_CAP = 4_000;
    private static final int COMMENT_CAP = 700;
    private static final int MAX_COMMENTS = 30;
    private static final int MAX_FILES = 60;
    private static final int MAX_EXISTING = 12;

    private final TurnRunner runner;
    private final ReviewProviderEndpoints endpoints;
    private final WorkModelResolver workModels;
    private final CliReviewRunner cliRunner;
    private final ObjectMapper mapper;

    public LessonExtractor(
            TurnRunner runner,
            ReviewProviderEndpoints endpoints,
            WorkModelResolver workModels,
            CliReviewRunner cliRunner,
            ObjectMapper mapper)
    {
        this.runner = requireNonNull(runner, "runner is null");
        this.endpoints = requireNonNull(endpoints, "endpoints is null");
        this.workModels = requireNonNull(workModels, "workModels is null");
        this.cliRunner = requireNonNull(cliRunner, "cliRunner is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    /** The configured provider/key cannot make an API call at all — the run
     *  should park retryable instead of failing PR by PR. */
    public static class ExtractionUnavailableException
            extends RuntimeException
    {
        public ExtractionUnavailableException(String message, Throwable cause)
        {
            super(message, cause);
        }
    }

    /** One PR's extraction failed (turn error or malformed output); the
     *  analysis loop records it and continues. */
    public static class ExtractionFailedException
            extends RuntimeException
    {
        public ExtractionFailedException(String message, Throwable cause)
        {
            super(message, cause);
        }
    }

    /**
     * Distill candidate lessons for one PR. {@code existing} is nearby
     * active/pending knowledge for the same repository so the model can mark
     * duplicates and conflicts instead of restating them.
     */
    public List<ExtractedLesson> extract(
            String workspaceId,
            PrEvidenceBundle bundle,
            List<KnowledgeItem> existing)
    {
        WorkModel workModel = resolveWorkModel(workspaceId);
        String system = systemPrompt();
        String prompt = userPrompt(bundle, existing);
        String finalText = workModel.kind() == WorkModelKind.CLI
                ? runCli(workModel, system, prompt)
                : runApi(workModel, system, prompt);
        return parse(finalText, bundle.refs().size(), existing);
    }

    private String runApi(WorkModel workModel, String system, String prompt)
    {
        ReviewProviderEndpoints.Endpoint endpoint = resolveEndpoint(workModel);
        ArrayNode messages = messages(endpoint.transport(), system, prompt);
        ToolExecutor executor = call ->
                ToolExecutor.ToolCallResult.error("no tools are available in this turn");
        TurnHooks hooks = new TurnHooks()
        {
            @Override
            public boolean abortTurn(long costSoFarMilliUsd)
            {
                return costSoFarMilliUsd >= COST_CAP_MILLI_USD;
            }
        };
        TurnResult result;
        try {
            result = runner.runTurn(
                    new TurnSpec(endpoint.transport(), endpoint.url(), endpoint.authToken(),
                            endpoint.modelId(),
                            endpoint.transport() == TurnSpec.Transport.ANTHROPIC ? system : null,
                            messages, mapper.createArrayNode(),
                            MAX_OUTPUT_TOKENS, 1),
                    executor, hooks);
        }
        catch (RuntimeException e) {
            throw new ExtractionFailedException(
                    "extraction turn failed: " + e.getMessage(), e);
        }
        if (result.end() == TurnResult.End.ABORTED) {
            throw new ExtractionFailedException(
                    "extraction turn exceeded its cost cap", null);
        }
        return result.finalText();
    }

    private String runCli(WorkModel workModel, String system, String prompt)
    {
        CliReviewRunner.Provider provider = cliProvider(workModel.agentOrProvider());
        CliReviewRunner.Result result;
        try {
            result = cliRunner.run(
                    provider, system + "\n\n" + prompt, null,
                    Path.of(System.getProperty("java.io.tmpdir")), null,
                    toIntExact(COST_CAP_MILLI_USD / 10));
        }
        catch (CliReviewException e) {
            throw new ExtractionUnavailableException(
                    "no usable extraction provider: " + e.getMessage(), e);
        }
        catch (RuntimeException e) {
            throw new ExtractionFailedException(
                    "extraction turn failed: " + e.getMessage(), e);
        }
        if ("ABORTED".equals(result.end())) {
            throw new ExtractionFailedException(
                    "extraction turn exceeded its cost cap", null);
        }
        if (!"COMPLETED".equals(result.end())) {
            throw new ExtractionFailedException(
                    "extraction turn failed: " + result.errorMessage(), null);
        }
        return result.text();
    }

    /**
     * Resolve project learning like other workspace review work: the review
     * row wins, then the workspace default, then the curated global default.
     */
    private WorkModel resolveWorkModel(String workspaceId)
    {
        try {
            return workModels.resolveForWorkspace(workspaceId, SessionAudience.REVIEW).choice();
        }
        catch (RuntimeException e) {
            throw new ExtractionUnavailableException(
                    "no usable extraction provider: " + e.getMessage(), e);
        }
    }

    private ReviewProviderEndpoints.Endpoint resolveEndpoint(WorkModel workModel)
    {
        try {
            ReviewProviderEndpoints.Endpoint endpoint =
                    endpoints.resolve(workModel.agentOrProvider());
            return workModel.model() == null ? endpoint : new ReviewProviderEndpoints.Endpoint(
                    endpoint.transport(), endpoint.url(), endpoint.authToken(), workModel.model());
        }
        catch (RuntimeException e) {
            throw new ExtractionUnavailableException(
                    "no usable extraction provider: " + e.getMessage(), e);
        }
    }

    private static CliReviewRunner.Provider cliProvider(String agent)
    {
        return switch (agent == null ? "" : agent.toLowerCase(Locale.ROOT)) {
            case "claude-code", "claude-cli" -> CliReviewRunner.Provider.CLAUDE;
            case "codex", "codex-cli" -> CliReviewRunner.Provider.CODEX;
            default -> throw new ExtractionUnavailableException(
                    "no usable extraction provider: unsupported CLI agent '" + agent + "'", null);
        };
    }

    // ── prompt ──────────────────────────────────────────────────────

    private static String systemPrompt()
    {
        return """
                You distill durable project knowledge from one merged pull request's evidence.

                Return ONLY one JSON object, no prose, of the form {"lessons": [...]}.
                An empty array means the PR taught no durable lesson — a common, correct answer.
                Never invent a principle from a normal implementation detail.

                Each lesson object:
                {
                  "kind": "architecture-principle" | "domain-invariant" | "investigation-recipe" |
                          "recurring-concern" | "design-rationale" | "performance-assumption" |
                          "compatibility-contract" | "glossary" | "build-test-rule",
                  "title": "short name, <= 80 chars",
                  "statement": "one concise, actionable fact, <= 400 chars",
                  "rationale": "why — only when the evidence states it" | null,
                  "appliesTo": {"modules": [], "paths": [], "symbols": [], "concepts": []},
                  "audiences": subset of ["plan", "dev", "review", "ci-fix"],
                  "evidence": ["E3", ...] non-empty, only ids listed in the EVIDENCE section,
                  "explicitSourceQuote": true only when a reviewer or author explicitly stated it,
                  "confidence": "high" | "medium" | "low",
                  "duplicateOf": "<id from EXISTING KNOWLEDGE>" | null,
                  "conflictsWith": ["<id from EXISTING KNOWLEDGE>", ...],
                  "route": "knowledge" | "workspace-memory",
                  "memoryKind": "DECISION" | "CONVENTION" | null
                }

                Rules:
                - Durable means it will still matter in future unrelated tasks: invariants,
                  compatibility contracts, rejected alternatives with stated rationale,
                  recurring risks, reusable investigation recipes, project terminology.
                - Implementation narration, one-off review nitpicks, and restating the diff
                  are not lessons.
                - Cite only listed evidence ids; a lesson without resolvable evidence is dropped.
                - Use "workspace-memory" only for an explicitly adopted cross-task operating
                  decision or convention; public PR history rarely contains these.
                - Mark duplicateOf/conflictsWith against EXISTING KNOWLEDGE instead of
                  restating or silently contradicting it.
                """;
    }

    private String userPrompt(PrEvidenceBundle bundle, List<KnowledgeItem> existing)
    {
        StringBuilder out = new StringBuilder();
        out.append("PROJECT: ").append(bundle.repo())
                .append("\nPR: #").append(bundle.prNumber())
                .append(" — ").append(nullToEmpty(bundle.title()))
                .append("\nAUTHOR: ").append(nullToEmpty(bundle.author()))
                .append("\nMERGED: ").append(bundle.merged())
                .append("\n\nDESCRIPTION:\n")
                .append(cap(nullToEmpty(bundle.bodyText()), BODY_CAP));

        out.append("\n\nCHANGED FILES:");
        List<PullRequestDetail.ChangedFile> files = bundle.files();
        for (int i = 0; i < files.size() && i < MAX_FILES; i++) {
            out.append("\n- ").append(files.get(i).filename());
        }
        if (files.size() > MAX_FILES) {
            out.append("\n… ").append(files.size() - MAX_FILES).append(" more");
        }

        out.append("\n\nREVIEW OUTCOME CHAINS:");
        if (bundle.chains().isEmpty()) {
            out.append("\n(none)");
        }
        for (OutcomeChain chain : bundle.chains()) {
            out.append("\n- concern by ").append(nullToEmpty(chain.concernAuthor()))
                    .append(chain.concernPath() == null ? "" : " on " + chain.concernPath())
                    .append("; addressed=").append(chain.addressed())
                    .append(", resolved=").append(chain.resolved())
                    .append(", merged=").append(chain.merged());
        }

        out.append("\n\nREVIEW DISCUSSION:");
        List<PrReviewThreadMessage> comments = bundle.reviewComments();
        if (comments.isEmpty()) {
            out.append("\n(none)");
        }
        for (int i = 0; i < comments.size() && i < MAX_COMMENTS; i++) {
            PrReviewThreadMessage comment = comments.get(i);
            out.append("\n[").append(nullToEmpty(comment.author()))
                    .append(comment.filePath() == null ? "" : " @ " + comment.filePath())
                    .append("] ")
                    .append(cap(nullToEmpty(comment.body()), COMMENT_CAP));
        }
        if (comments.size() > MAX_COMMENTS) {
            out.append("\n… ").append(comments.size() - MAX_COMMENTS).append(" more comments");
        }

        out.append("\n\nEVIDENCE (cite these ids):");
        List<PrEvidenceBundle.EvidenceRef> refs = bundle.refs();
        for (int i = 0; i < refs.size(); i++) {
            PrEvidenceBundle.EvidenceRef ref = refs.get(i);
            out.append("\nE").append(i + 1).append(": ").append(ref.kind());
            if (ref.filePath() != null) {
                out.append(' ').append(ref.filePath());
            }
            if (ref.githubId() != null) {
                out.append(" id=").append(ref.githubId());
            }
            if (ref.commitSha() != null) {
                out.append(" commit=").append(shortSha(ref.commitSha()));
            }
        }

        out.append("\n\nEXISTING KNOWLEDGE:");
        if (existing.isEmpty()) {
            out.append("\n(none)");
        }
        for (int i = 0; i < existing.size() && i < MAX_EXISTING; i++) {
            KnowledgeItem item = existing.get(i);
            out.append("\n").append(item.id()).append(": [").append(item.kind())
                    .append("] ").append(cap(item.statement(), 300));
        }
        return out.toString();
    }

    // ── parsing ─────────────────────────────────────────────────────

    /** Package-private so tests can drive parsing without a model call. */
    List<ExtractedLesson> parse(String raw, int refCount, List<KnowledgeItem> existing)
    {
        JsonNode json;
        try {
            json = mapper.readTree(stripFence(raw));
        }
        catch (IOException e) {
            throw new ExtractionFailedException("extraction output is not valid JSON", e);
        }
        if (json == null || !json.isObject() || !json.path("lessons").isArray()) {
            throw new ExtractionFailedException(
                    "extraction output must be {\"lessons\": [...]}", null);
        }
        Set<String> knownIds = new LinkedHashSet<>();
        existing.forEach(item -> knownIds.add(item.id()));
        List<ExtractedLesson> lessons = new ArrayList<>();
        for (JsonNode node : json.path("lessons")) {
            if (lessons.size() >= MAX_LESSONS_PER_PR) {
                break;
            }
            ExtractedLesson lesson = readLesson(node, refCount, knownIds);
            if (lesson != null) {
                lessons.add(lesson);
            }
        }
        return lessons;
    }

    private ExtractedLesson readLesson(JsonNode node, int refCount, Set<String> knownIds)
    {
        String kind = text(node, "kind");
        String statement = text(node, "statement");
        if (kind == null || !ExtractedLesson.KINDS.contains(kind)
                || statement == null || statement.isBlank() || statement.length() > 600) {
            log.debug("dropping malformed lesson candidate: kind={}", kind);
            return null;
        }
        List<Integer> evidence = new ArrayList<>();
        for (JsonNode reference : node.path("evidence")) {
            Integer index = parseEvidenceId(reference.asText(), refCount);
            if (index != null) {
                evidence.add(index);
            }
        }
        if (evidence.isEmpty()) {
            // Mandatory provenance: a candidate that cites nothing resolvable
            // is dropped rather than stored as unattributable prose.
            log.debug("dropping lesson without resolvable evidence: {}", statement);
            return null;
        }
        String confidence = text(node, "confidence");
        String route = text(node, "route");
        JsonNode applies = node.path("appliesTo");
        String duplicateOf = text(node, "duplicateOf");
        List<String> conflicts = new ArrayList<>();
        for (JsonNode conflict : node.path("conflictsWith")) {
            if (knownIds.contains(conflict.asText())) {
                conflicts.add(conflict.asText());
            }
        }
        return new ExtractedLesson(
                kind,
                cap(text(node, "title"), 120),
                statement.strip(),
                text(node, "rationale"),
                strings(applies.path("modules")),
                strings(applies.path("paths")),
                strings(applies.path("symbols")),
                strings(applies.path("concepts")),
                audiences(node.path("audiences")),
                evidence,
                node.path("explicitSourceQuote").asBoolean(false),
                confidence != null && ExtractedLesson.CONFIDENCES.contains(confidence)
                        ? confidence : "low",
                duplicateOf != null && knownIds.contains(duplicateOf) ? duplicateOf : null,
                conflicts,
                route != null && ExtractedLesson.ROUTES.contains(route) ? route : "knowledge",
                memoryKind(text(node, "memoryKind")));
    }

    private static Integer parseEvidenceId(String id, int refCount)
    {
        if (id == null || !id.matches("[Ee]\\d{1,4}")) {
            return null;
        }
        int index = Integer.parseInt(id.substring(1));
        return index >= 1 && index <= refCount ? index - 1 : null;
    }

    private static String memoryKind(String value)
    {
        if (value == null) {
            return null;
        }
        String normalized = value.toUpperCase(Locale.ROOT);
        return "DECISION".equals(normalized) || "CONVENTION".equals(normalized)
                ? normalized : null;
    }

    private static List<String> audiences(JsonNode node)
    {
        List<String> values = strings(node).stream()
                .map(value -> value.toLowerCase(Locale.ROOT))
                .filter(ImmutableSet.of("plan", "dev", "review", "ci-fix")::contains)
                .toList();
        return values.isEmpty() ? List.of("plan", "dev", "review") : values;
    }

    private static List<String> strings(JsonNode node)
    {
        if (!node.isArray()) {
            return List.of();
        }
        List<String> out = new ArrayList<>();
        for (JsonNode value : node) {
            String text = value.asText();
            if (text != null && !text.isBlank() && out.size() < 16) {
                out.add(text.strip());
            }
        }
        return out;
    }

    private static String text(JsonNode node, String field)
    {
        JsonNode value = node.path(field);
        return value.isTextual() && !value.asText().isBlank() ? value.asText() : null;
    }

    private ArrayNode messages(TurnSpec.Transport transport, String system, String prompt)
    {
        ArrayNode messages = mapper.createArrayNode();
        if (transport == TurnSpec.Transport.OPENAI_COMPAT) {
            messages.add(message("system", system));
        }
        messages.add(message("user", prompt));
        return messages;
    }

    private ObjectNode message(String role, String content)
    {
        ObjectNode node = mapper.createObjectNode();
        node.put("role", role);
        node.put("content", content);
        return node;
    }

    private static String stripFence(String raw)
    {
        String value = raw == null ? "" : raw.strip();
        if (value.startsWith("```")) {
            int firstNewline = value.indexOf('\n');
            int closing = value.lastIndexOf("```");
            if (firstNewline >= 0 && closing > firstNewline) {
                return value.substring(firstNewline + 1, closing).strip();
            }
        }
        return value;
    }

    private static String cap(String value, int max)
    {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max) + "…";
    }

    private static String shortSha(String sha)
    {
        return sha != null && sha.length() > 12 ? sha.substring(0, 12) : sha;
    }
}
