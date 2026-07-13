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
package com.bytequay.app.service.review;

import com.bytequay.app.domain.InvestigationReviewData.FindingEvidenceRow;
import com.bytequay.app.domain.InvestigationReviewData.FindingRow;
import com.bytequay.app.domain.InvestigationReviewData.FindingVerificationRow;
import com.bytequay.app.domain.InvestigationReviewData.HypothesisRow;
import com.bytequay.app.domain.InvestigationReviewData.InvestigationStepRow;
import com.bytequay.app.domain.InvestigationReviewData.ObservationRow;
import com.bytequay.app.domain.InvestigationReviewData.ReviewSessionRow;
import com.bytequay.app.domain.PR;
import com.bytequay.app.repository.sqlite.InvestigationReviewStore;
import com.bytequay.app.service.agents.ToolCall;
import com.bytequay.app.service.agents.ToolExecutor;
import com.bytequay.app.service.agents.TurnSpec;
import com.bytequay.app.service.localpr.PRService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static com.bytequay.app.service.agents.ToolExecutor.ToolCallResult.error;
import static com.bytequay.app.service.agents.ToolExecutor.ToolCallResult.ok;
import static java.util.Objects.requireNonNull;

/** Frozen runner contracts plus the deterministic persistence/validation layer. */
@Component
public class InvestigationReviewTools
{
    private static final Set<String> CRITERION_KINDS = Set.of(
            "hard-invariant", "engineering-principle", "repo-convention");
    private static final Set<String> DEPENDENCY_MODES = Set.of(
            "DIRECT_ONLY", "SYMBOL_BODY", "CALLER_SET", "MODULE_CONTRACT");
    private static final int MAX_HYPOTHESES = 6;
    private static final int MAX_ACTIVE_HYPOTHESES = 3;
    // The twelfth fixed-budget slot is reserved for the code-enforced
    // self-refutation audit written by InvestigationReviewService.
    private static final int MAX_REVIEWER_STEPS = 11;
    private static final int MAX_FINDINGS = 5;
    private static final int MAX_TOOL_TEXT = 32_000;

    private final InvestigationReviewStore store;
    private final InvestigationReviewContext contexts;
    private final PRService prs;
    private final ObjectMapper mapper;
    private final ConcurrentHashMap<String, InvestigationReviewContext.Snapshot> snapshots =
            new ConcurrentHashMap<>();

    public InvestigationReviewTools(
            InvestigationReviewStore store, InvestigationReviewContext contexts,
            PRService prs, ObjectMapper mapper)
    {
        this.store = requireNonNull(store, "store is null");
        this.contexts = requireNonNull(contexts, "contexts is null");
        this.prs = requireNonNull(prs, "prs is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    public ToolExecutor executor(String sessionId, String assignmentId)
    {
        if (!store.assignmentBelongsToSession(assignmentId, sessionId)) {
            throw new IllegalArgumentException("assignment does not belong to session");
        }
        return call -> execute(sessionId, assignmentId, call);
    }

    public ArrayNode tools(TurnSpec.Transport transport, boolean verifier)
    {
        List<ToolSchema> schemas = verifier ? verifierSchemas() : investigatorSchemas();
        ArrayNode tools = mapper.createArrayNode();
        for (ToolSchema schema : schemas) {
            JsonNode parameters = tree(schema.schema());
            if (transport == TurnSpec.Transport.ANTHROPIC) {
                ObjectNode node = mapper.createObjectNode();
                node.put("name", schema.name());
                node.put("description", schema.description());
                node.set("input_schema", parameters);
                tools.add(node);
            }
            else {
                ObjectNode function = mapper.createObjectNode();
                function.put("name", schema.name());
                function.put("description", schema.description());
                function.set("parameters", parameters);
                ObjectNode wrapper = mapper.createObjectNode();
                wrapper.put("type", "function");
                wrapper.set("function", function);
                tools.add(wrapper);
            }
        }
        return tools;
    }

    private ToolExecutor.ToolCallResult execute(String sessionId, String assignmentId, ToolCall call)
    {
        try {
            return switch (call.name()) {
                case "record_assignment" -> recordAssignment(assignmentId, call.input());
                case "record_hypothesis" -> recordHypothesis(assignmentId, call.input());
                case "record_step" -> recordStep(assignmentId, call.input());
                case "read_diff" -> readDiff(sessionId, assignmentId, call.input());
                case "read_file" -> readFile(sessionId, assignmentId, call.input());
                case "search_diff" -> searchDiff(sessionId, assignmentId, call.input());
                case "record_finding" -> recordFinding(sessionId, assignmentId, call.input());
                case "record_evidence" -> recordEvidence(sessionId, call.input());
                case "record_verification" -> recordVerification(sessionId, call.input());
                default -> error("unknown investigation tool: " + call.name());
            };
        }
        catch (RuntimeException e) {
            return error(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    private ToolExecutor.ToolCallResult recordAssignment(String assignmentId, JsonNode input)
    {
        String summary = required(input, "understanding_summary");
        store.updateAssignment(assignmentId, "investigating", summary,
                strings(input.path("assumptions")), strings(input.path("unknowns")));
        return okJson("assignment_id", assignmentId);
    }

    private ToolExecutor.ToolCallResult recordHypothesis(String assignmentId, JsonNode input)
    {
        if (store.countHypotheses(assignmentId) >= MAX_HYPOTHESES) {
            return error("hypothesis budget exhausted (max " + MAX_HYPOTHESES + ")");
        }
        String status = input.path("status").asText("candidate");
        if ("active".equals(status) && store.countActiveHypotheses(assignmentId) >= MAX_ACTIVE_HYPOTHESES) {
            return error("active hypothesis budget exhausted (max " + MAX_ACTIVE_HYPOTHESES + ")");
        }
        String id = UUID.randomUUID().toString();
        store.insertHypothesis(new HypothesisRow(
                id, assignmentId, optional(input, "objective_id"), required(input, "claim"),
                input.path("origin").asText("reviewer"), status, "TENTATIVE"));
        return okJson("hypothesis_id", id);
    }

    private ToolExecutor.ToolCallResult recordStep(String assignmentId, JsonNode input)
    {
        if (store.countSteps(assignmentId) >= MAX_REVIEWER_STEPS) {
            return error("adaptive step budget exhausted (one of 12 slots is reserved for self-refutation)");
        }
        String id = UUID.randomUUID().toString();
        store.insertStep(new InvestigationStepRow(
                id, assignmentId, optional(input, "hypothesis_id"),
                required(input, "action_type"), input.path("arguments"),
                required(input, "reason"), input.path("planned").asBoolean(true),
                0, "running"));
        return okJson("step_id", id);
    }

    private ToolExecutor.ToolCallResult readDiff(String sessionId, String assignmentId, JsonNode input)
    {
        String stepId = requireStep(assignmentId, input);
        InvestigationReviewContext.Snapshot snapshot = snapshot(sessionId, assignmentId);
        String path = optional(input, "path");
        String text = snapshot.diff();
        if (path != null) {
            text = diffFile(text, path);
        }
        return observation(stepId, "source", snapshot.headCommit(), path, null, null, text);
    }

    private ToolExecutor.ToolCallResult readFile(String sessionId, String assignmentId, JsonNode input)
    {
        String stepId = requireStep(assignmentId, input);
        InvestigationReviewContext.Snapshot snapshot = snapshot(sessionId, assignmentId);
        String path = required(input, "path");
        String content = contexts.readFile(snapshot, path);
        int start = Math.max(1, input.path("start_line").asInt(1));
        int end = Math.max(start, input.path("end_line").asInt(start + 199));
        String excerpt = lines(content, start, end);
        String type = isAuthoritativePath(path) ? "authoritative" : "source";
        return observation(stepId, type, snapshot.headCommit(), path, start, end, excerpt);
    }

    private ToolExecutor.ToolCallResult searchDiff(String sessionId, String assignmentId, JsonNode input)
    {
        String stepId = requireStep(assignmentId, input);
        InvestigationReviewContext.Snapshot snapshot = snapshot(sessionId, assignmentId);
        String query = required(input, "query").toLowerCase(Locale.ROOT);
        List<String> matches = snapshot.diff().lines()
                .filter(line -> line.toLowerCase(Locale.ROOT).contains(query))
                .limit(80)
                .toList();
        String preview = matches.isEmpty() ? "No matching changed lines." : String.join("\n", matches);
        return observation(stepId, "static-trace", snapshot.headCommit(), null, null, null, preview);
    }

    private ToolExecutor.ToolCallResult recordFinding(String sessionId, String assignmentId, JsonNode input)
    {
        if (store.countFindings(assignmentId) >= MAX_FINDINGS) {
            return error("finding budget exhausted (max " + MAX_FINDINGS + ")");
        }
        String objectiveId = required(input, "objective_id");
        boolean objectiveExists = store.objectives(sessionId).stream().anyMatch(row -> row.id().equals(objectiveId));
        if (!objectiveExists) {
            return error("objective does not belong to session");
        }
        String kind = input.path("criterion_kind").asText("hard-invariant");
        if (!CRITERION_KINDS.contains(kind)) {
            return error("invalid criterion_kind");
        }
        int severity = input.path("severity").asInt(0);
        if (severity < 1 || severity > 5) {
            return error("severity must be 1..5");
        }
        ReviewSessionRow session = requireSession(sessionId);
        String hypothesisId = required(input, "hypothesis_id");
        if (!store.hypothesisBelongsToAssignment(hypothesisId, assignmentId)) {
            return error("hypothesis does not belong to assignment");
        }
        String roundId = store.assignments(sessionId).stream()
                .filter(row -> row.id().equals(assignmentId)).findFirst().orElseThrow().roundId();
        String id = UUID.randomUUID().toString();
        store.insertFinding(new FindingRow(
                id, sessionId, roundId, objectiveId, hypothesisId, kind,
                required(input, "claim"), severity, "TENTATIVE", "unknown",
                required(input, "requested_action"), "candidate", session.reviewedHeadCommit()));
        return okJson("finding_id", id);
    }

    private ToolExecutor.ToolCallResult recordEvidence(String sessionId, JsonNode input)
    {
        String findingId = required(input, "finding_id");
        FindingRow finding = store.findFinding(findingId)
                .filter(row -> row.sessionId().equals(sessionId))
                .orElseThrow(() -> new IllegalArgumentException("finding does not belong to session"));
        ObservationRow observation = store.findObservation(required(input, "observation_id"))
                .orElseThrow(() -> new IllegalArgumentException("unknown observation"));
        if (!store.observationBelongsToSession(observation.id(), sessionId)) {
            return error("observation does not belong to session");
        }
        String relation = input.path("relation").asText("");
        if (!Set.of("SUPPORTS", "REFUTES").contains(relation)) {
            return error("relation must be SUPPORTS or REFUTES");
        }
        String mode = input.path("dependency_mode").asText("DIRECT_ONLY");
        if (!DEPENDENCY_MODES.contains(mode)) {
            return error("invalid dependency_mode");
        }
        String strength = strength(observation.sourceType());
        store.insertEvidence(new FindingEvidenceRow(
                findingId, observation.id(), relation, required(input, "proposition"), strength,
                strengthReason(strength), mode, input.path("dependency")));
        String confidence = confidence(strength, finding.criterionKind());
        store.updateFinding(finding.id(), finding.lifecycleStatus(), finding.verificationStatus(),
                confidence, finding.claim(), finding.severity());
        return okJson("strength_class", strength);
    }

    private ToolExecutor.ToolCallResult recordVerification(String sessionId, JsonNode input)
    {
        String findingId = required(input, "finding_id");
        FindingRow finding = store.findFinding(findingId)
                .filter(row -> row.sessionId().equals(sessionId))
                .orElseThrow(() -> new IllegalArgumentException("finding does not belong to session"));
        String status = input.path("status").asText("");
        if (!Set.of("verified", "partially", "unknown", "rejected").contains(status)) {
            return error("invalid verification status");
        }
        boolean evidenceAccurate = input.path("evidence_accurate").asBoolean(false);
        boolean scopeAccurate = input.path("claim_scope_accurate").asBoolean(false);
        boolean severityAccurate = input.path("severity_accurate").asBoolean(false);
        int severity = input.has("revised_severity")
                ? Math.max(1, Math.min(5, input.path("revised_severity").asInt(finding.severity())))
                : finding.severity();
        String claim = input.path("revised_claim").asText(finding.claim());
        String confidence = switch (status) {
            case "verified" -> "VERIFIED";
            case "partially" -> "SUPPORTED";
            case "unknown" -> "UNKNOWN";
            default -> "REJECTED";
        };
        boolean userJudgement = "partially".equals(status)
                && !"hard-invariant".equals(finding.criterionKind())
                && evidenceAccurate && scopeAccurate;
        String lifecycle = "rejected".equals(status) ? "dropped"
                : "unknown".equals(status) ? "NEEDS_AUTHOR_INPUT"
                : userJudgement ? "NEEDS_USER_JUDGEMENT" : "ready";
        store.insertVerification(new FindingVerificationRow(
                UUID.randomUUID().toString(), findingId, required(input, "verifier_run_id"),
                evidenceAccurate, scopeAccurate, severityAccurate,
                strings(input.path("counter_evidence")), status, confidence,
                required(input, "explanation")));
        store.updateFinding(findingId, lifecycle, status, confidence, claim, severity);
        return okJson("verification_status", status);
    }

    private ToolExecutor.ToolCallResult observation(
            String stepId, String sourceType, String commitSha, String path,
            Integer startLine, Integer endLine, String raw)
    {
        String preview = raw == null ? "" : raw;
        if (preview.length() > MAX_TOOL_TEXT) {
            preview = preview.substring(0, MAX_TOOL_TEXT) + "\n... (observation truncated)";
        }
        String id = UUID.randomUUID().toString();
        store.insertObservation(new ObservationRow(
                id, stepId, sourceType, commitSha, path, startLine, endLine,
                null, null, null, null, digest(preview), preview));
        store.updateStepStatus(stepId, "completed", 0);
        ObjectNode result = mapper.createObjectNode();
        result.put("observation_id", id);
        result.put("content_digest", digest(preview));
        result.put("preview", preview);
        return ok(result.toString());
    }

    private InvestigationReviewContext.Snapshot snapshot(String sessionId, String assignmentId)
    {
        return snapshots.computeIfAbsent(assignmentId, id -> {
            ReviewSessionRow session = requireSession(sessionId);
            PR pr = prs.findById(session.prId())
                    .orElseThrow(() -> new IllegalArgumentException("unknown PR " + session.prId()));
            return contexts.load(pr);
        });
    }

    private ReviewSessionRow requireSession(String sessionId)
    {
        return store.findSession(sessionId)
                .orElseThrow(() -> new IllegalArgumentException("unknown review session"));
    }

    private String requireStep(String assignmentId, JsonNode input)
    {
        String stepId = required(input, "step_id");
        if (!store.stepBelongsToAssignment(stepId, assignmentId)) {
            throw new IllegalArgumentException("step does not belong to assignment");
        }
        return stepId;
    }

    private ToolExecutor.ToolCallResult okJson(String key, String value)
    {
        ObjectNode node = mapper.createObjectNode();
        node.put(key, value);
        return ok(node.toString());
    }

    private static String required(JsonNode input, String field)
    {
        String value = input.path(field).asText("").strip();
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static String optional(JsonNode input, String field)
    {
        String value = input.path(field).asText("").strip();
        return value.isBlank() ? null : value;
    }

    private static List<String> strings(JsonNode node)
    {
        List<String> values = new ArrayList<>();
        if (node.isArray()) {
            node.forEach(value -> {
                if (value.isTextual() && !value.asText().isBlank()) {
                    values.add(value.asText().strip());
                }
            });
        }
        return List.copyOf(values);
    }

    private JsonNode tree(String json)
    {
        try {
            return mapper.readTree(json);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("invalid built-in investigation schema", e);
        }
    }

    private static String diffFile(String diff, String path)
    {
        String marker = "diff --git a/" + path + " b/" + path;
        int start = diff.indexOf(marker);
        if (start < 0) {
            return "No diff for " + path;
        }
        int end = diff.indexOf("\ndiff --git ", start + marker.length());
        return diff.substring(start, end < 0 ? diff.length() : end);
    }

    private static String lines(String content, int start, int end)
    {
        List<String> lines = content.lines().toList();
        int from = Math.min(lines.size(), start - 1);
        int to = Math.min(lines.size(), end);
        StringBuilder out = new StringBuilder();
        for (int i = from; i < to; i++) {
            out.append(i + 1).append(' ').append(lines.get(i)).append('\n');
        }
        return out.toString();
    }

    private static boolean isAuthoritativePath(String path)
    {
        String lower = path.toLowerCase(Locale.ROOT);
        return lower.contains("test") || lower.endsWith(".md")
                || lower.contains("spec") || lower.contains("contract");
    }

    private static String strength(String sourceType)
    {
        return switch (sourceType) {
            case "runtime" -> "E5";
            case "reproduction" -> "E4";
            case "authoritative" -> "E3";
            case "static-trace" -> "E2";
            default -> "E1";
        };
    }

    private static String strengthReason(String strength)
    {
        return switch (strength) {
            case "E5" -> "Observed CI/runtime failure.";
            case "E4" -> "Executable reproduction.";
            case "E3" -> "Authoritative contract, history, or test expectation.";
            case "E2" -> "Corroborated static trace.";
            default -> "Local source interpretation.";
        };
    }

    private static String confidence(String strength, String criterionKind)
    {
        boolean principle = !"hard-invariant".equals(criterionKind);
        return switch (strength) {
            case "E4", "E5" -> principle ? "SUPPORTED" : "STRONGLY_SUPPORTED";
            case "E3" -> "SUPPORTED";
            default -> "TENTATIVE";
        };
    }

    private static String digest(String value)
    {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        }
        catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private static List<ToolSchema> investigatorSchemas()
    {
        return List.of(
                schema("record_assignment", "Record the structured understanding before investigating.",
                        """
                        {"type":"object","properties":{"understanding_summary":{"type":"string"},"assumptions":{"type":"array","items":{"type":"string"}},"unknowns":{"type":"array","items":{"type":"string"}}},"required":["understanding_summary"]}
                        """),
                schema("record_hypothesis", "Record one bounded candidate or active hypothesis.",
                        """
                        {"type":"object","properties":{"objective_id":{"type":"string"},"claim":{"type":"string"},"origin":{"type":"string"},"status":{"type":"string","enum":["candidate","active","refuted","supported"]}},"required":["objective_id","claim"]}
                        """),
                schema("record_step", "Record a planned investigation step before executing it.",
                        """
                        {"type":"object","properties":{"hypothesis_id":{"type":"string"},"action_type":{"type":"string"},"arguments":{"type":"object"},"reason":{"type":"string"},"planned":{"type":"boolean"}},"required":["action_type","reason"]}
                        """),
                schema("read_diff", "Read the immutable PR diff and persist the output as an observation.",
                        """
                        {"type":"object","properties":{"step_id":{"type":"string"},"path":{"type":"string"}},"required":["step_id"]}
                        """),
                schema("read_file", "Read a file at the reviewed head and persist a cited observation.",
                        """
                        {"type":"object","properties":{"step_id":{"type":"string"},"path":{"type":"string"},"start_line":{"type":"integer"},"end_line":{"type":"integer"}},"required":["step_id","path"]}
                        """),
                schema("search_diff", "Search changed lines and persist the bounded trace as an observation.",
                        """
                        {"type":"object","properties":{"step_id":{"type":"string"},"query":{"type":"string"}},"required":["step_id","query"]}
                        """),
                schema("record_finding", "Record an actionable candidate finding. Evidence is attached separately.",
                        """
                        {"type":"object","properties":{"objective_id":{"type":"string"},"hypothesis_id":{"type":"string"},"criterion_kind":{"type":"string","enum":["hard-invariant","engineering-principle","repo-convention"]},"claim":{"type":"string"},"severity":{"type":"integer","minimum":1,"maximum":5},"requested_action":{"type":"string"}},"required":["objective_id","hypothesis_id","criterion_kind","claim","severity","requested_action"]}
                        """),
                schema("record_evidence", "Link reproducible observation output to a finding. Strength is assigned by code.",
                        """
                        {"type":"object","properties":{"finding_id":{"type":"string"},"observation_id":{"type":"string"},"relation":{"type":"string","enum":["SUPPORTS","REFUTES"]},"proposition":{"type":"string"},"dependency_mode":{"type":"string","enum":["DIRECT_ONLY","SYMBOL_BODY","CALLER_SET","MODULE_CONTRACT"]},"dependency":{"type":"object"}},"required":["finding_id","observation_id","relation","proposition","dependency_mode"]}
                        """));
    }

    private static List<ToolSchema> verifierSchemas()
    {
        return List.of(schema("record_verification", "Record the independent structured verification result.",
                """
                {"type":"object","properties":{"finding_id":{"type":"string"},"verifier_run_id":{"type":"string"},"evidence_accurate":{"type":"boolean"},"claim_scope_accurate":{"type":"boolean"},"severity_accurate":{"type":"boolean"},"counter_evidence":{"type":"array","items":{"type":"string"}},"status":{"type":"string","enum":["verified","partially","unknown","rejected"]},"revised_claim":{"type":"string"},"revised_severity":{"type":"integer","minimum":1,"maximum":5},"explanation":{"type":"string"}},"required":["finding_id","verifier_run_id","evidence_accurate","claim_scope_accurate","severity_accurate","status","explanation"]}
                """));
    }

    private static ToolSchema schema(String name, String description, String schema)
    {
        return new ToolSchema(name, description, schema);
    }

    private record ToolSchema(String name, String description, String schema) {}
}
