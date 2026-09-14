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
package com.bytequay.app.review;

import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.spi.ReviewBridge;
import com.bytequay.agent.spi.ReviewTool;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.repository.github.GitHubIssuePreview;
import com.bytequay.app.service.RepoService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewAnchor;
import com.bytequay.core.review.ReviewCandidateValidation;
import com.bytequay.core.review.ReviewConflictException;
import com.bytequay.core.review.ReviewCoverage;
import com.bytequay.core.review.ReviewEvidence;
import com.bytequay.core.review.ReviewFinding;
import com.bytequay.core.review.ReviewInvalidRequestException;
import com.bytequay.core.review.ReviewInvestigation;
import com.bytequay.core.review.ReviewMessage;
import com.bytequay.core.review.ReviewNotFoundException;
import com.bytequay.core.review.ReviewOperation;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.review.ReviewSummary;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.Hashing;
import com.google.common.util.concurrent.Striped;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.function.Function;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

/**
 * The fixed review tools, bound to one review by the task token that calls them. Every write
 * is validated here against the bound run, replays by operation key, and commits its replay
 * record with the row it writes. The model never names a review, run, path root, or token.
 */
@Component
public class ReviewTools
{
    private static final int MAX_TEXT = 6000;
    private static final int MAX_TITLE = 200;
    private static final int MAX_KEY = 100;
    private static final int MAX_EVIDENCE = 8;
    private static final int MAX_CANDIDATES = 30;
    private static final int MAX_EXCERPT = 4000;
    private static final int SOURCE_WINDOW = 200;
    private static final int DIFF_PAGE_LINES = 300;
    private static final int TIMELINE_PAGE = 20;
    private static final List<String> SEVERITIES = ImmutableList.of("Critical", "High", "Medium", "Low");

    private final AgentReviewStore store;
    private final PullRequestService pullRequests;
    private final RepoService repositories;
    private final AgentEventService events;
    private final WorkspaceRepositoryService workspaces;
    private final GitCommandRunner git;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Striped<Lock> locks = Striped.lock(32);

    public ReviewTools(
            AgentReviewStore store,
            PullRequestService pullRequests,
            RepoService repositories,
            AgentEventService events,
            WorkspaceRepositoryService workspaces,
            GitCommandRunner git,
            ObjectMapper objectMapper,
            Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.repositories = requireNonNull(repositories, "repositories is null");
        this.events = requireNonNull(events, "events is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.git = requireNonNull(git, "git is null");
        this.objectMapper = requireNonNull(objectMapper, "objectMapper is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    /** The bridge for one review-owned task, or empty for any other task. */
    public Optional<ReviewBridge> bridge(String taskId)
    {
        return store.findReviewByTask(taskId)
                .filter(review -> review.state() == AgentReview.State.OPEN)
                .map(review -> new ReviewBridge(tools(review.id()), ReviewPrompts.INSTRUCTION));
    }

    private List<ReviewTool> tools(String reviewId)
    {
        return ImmutableList.of(
                new ReviewTool("review_get_context",
                        "Read bounded pull request context for this review: kind pr, timeline, checks, issue (number), diff (optional path, page), "
                                + "source (path, startLine, endLine, revision: head/base/previous), command_output, investigation, findings, "
                                + "summary, discussion (target), or evidence (ref). "
                                + "Diff lines carry anchor ids; source, issue and command reads return evidence refs to cite in findings.",
                        schema(ImmutableMap.<String, Object>builder()
                                .put(
                                        "kind",
                                        ImmutableMap.of(
                                                "type",
                                                "string",
                                                "enum",
                                                ImmutableList.of(
                                                        "pr",
                                                        "timeline",
                                                        "checks",
                                                        "issue",
                                                        "diff",
                                                        "source",
                                                        "command_output",
                                                        "investigation",
                                                        "findings",
                                                        "summary",
                                                        "discussion",
                                                        "evidence")))
                                .put("path", ImmutableMap.of("type", "string", "maxLength", 1000))
                                .put("revision", ImmutableMap.of("type", "string", "enum", ImmutableList.of("head", "base", "previous")))
                                .put("number", ImmutableMap.of("type", "integer", "minimum", 1))
                                .put("page", ImmutableMap.of("type", "integer", "minimum", 1))
                                .put("startLine", ImmutableMap.of("type", "integer", "minimum", 1))
                                .put("endLine", ImmutableMap.of("type", "integer", "minimum", 1))
                                .put("target", ImmutableMap.of("type", "string", "maxLength", 200))
                                .put("ref", ImmutableMap.of("type", "string", "maxLength", 20))
                                .buildOrThrow(), ImmutableList.of("kind")),
                        arguments -> call(reviewId, arguments, this::getContext)),
                new ReviewTool("review_save_summary",
                        "Save only the pull request's intention and one design/implementation explanation per commit in scope. "
                                + "Put bugs and concrete code improvements in inline findings. Saving again creates the next revision.",
                        schema(ImmutableMap.<String, Object>builder()
                                .put("operationKey", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", MAX_KEY))
                                .put("purpose", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", 2000))
                                .put(
                                        "commits",
                                        ImmutableMap.of(
                                                "type",
                                                "array",
                                                "maxItems",
                                                200,
                                                "items",
                                                ImmutableMap.of(
                                                        "type",
                                                        "object",
                                                        "properties",
                                                        ImmutableMap.of(
                                                                "sha",
                                                                ImmutableMap.of("type", "string"),
                                                                "explanation",
                                                                ImmutableMap.of("type", "string", "maxLength", 1000)),
                                                        "required",
                                                        ImmutableList.of("sha", "explanation"))))
                                .buildOrThrow(), ImmutableList.of("operationKey", "purpose", "commits")),
                        arguments -> call(reviewId, arguments, this::saveSummary)),
                new ReviewTool("review_save_investigation",
                        "Save the change map, assessed changed-file paths, supporting evidence refs, and candidate claims before ending investigation. "
                                + "Candidates are private hypotheses numbered from 1, not findings. "
                                + "During validation, preserve existing candidates and append newly discovered ones.",
                        schema(ImmutableMap.of(
                                "operationKey", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", MAX_KEY),
                                "changeMap", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", MAX_TEXT),
                                "reviewedFiles", stringArray(1000, 1000),
                                "contextEvidenceRefs", stringArray(80, 20),
                                "candidates", stringArray(MAX_CANDIDATES, 2000)),
                                ImmutableList.of("operationKey", "changeMap", "reviewedFiles", "contextEvidenceRefs", "candidates")),
                        arguments -> call(reviewId, arguments, this::saveInvestigation)),
                new ReviewTool("review_save_finding",
                        "During validation, create or revise an inline bug report or concrete code improvement, with evidence refs and diff anchors from review_get_context. "
                                + "Use severity for defects; mark optional improvements as suggestions and omit severity. For an exact replacement, "
                                + "include one GitHub ```suggestion block replacing the complete RIGHT-side startAnchor/endAnchor range. "
                                + "Revisions require findingId and expectedRevision; nothing is published.",
                        schema(ImmutableMap.<String, Object>builder()
                                .put("operationKey", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", MAX_KEY))
                                .put("findingId", ImmutableMap.of("type", "string"))
                                .put("expectedRevision", ImmutableMap.of("type", "integer", "minimum", 1))
                                .put("title", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", MAX_TITLE))
                                .put("body", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", MAX_TEXT))
                                .put("validation", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", 2000))
                                .put("severity", ImmutableMap.of("type", "string", "enum", ImmutableList.of("critical", "high", "medium", "low")))
                                .put("startAnchor", ImmutableMap.of("type", "string"))
                                .put("endAnchor", ImmutableMap.of("type", "string"))
                                .put(
                                        "evidence",
                                        ImmutableMap.of(
                                                "type",
                                                "array",
                                                "minItems",
                                                1,
                                                "maxItems",
                                                MAX_EVIDENCE,
                                                "items",
                                                ImmutableMap.of(
                                                        "type",
                                                        "object",
                                                        "properties",
                                                        ImmutableMap.of(
                                                                "ref",
                                                                ImmutableMap.of("type", "string"),
                                                                "explanation",
                                                                ImmutableMap.of("type", "string", "maxLength", 800)),
                                                        "required",
                                                        ImmutableList.of("ref", "explanation"))))
                                .buildOrThrow(), ImmutableList.of("operationKey", "title", "body", "startAnchor", "evidence", "validation")),
                        arguments -> call(reviewId, arguments, this::saveFinding)),
                new ReviewTool("review_reply",
                        "Append your private answer to a discussion: target is \"summary\", a run-scoped cardId, or a findingId.",
                        schema(ImmutableMap.of(
                                "operationKey", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", MAX_KEY),
                                "target", ImmutableMap.of("type", "string", "minLength", 1),
                                "body", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", MAX_TEXT)), ImmutableList.of("operationKey", "target", "body")),
                        arguments -> call(reviewId, arguments, this::reply)),
                new ReviewTool("review_withdraw_finding",
                        "Withdraw a finding you no longer stand behind, with its expectedRevision and an explanation.",
                        schema(ImmutableMap.of(
                                "operationKey", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", MAX_KEY),
                                "findingId", ImmutableMap.of("type", "string", "minLength", 1),
                                "expectedRevision", ImmutableMap.of("type", "integer", "minimum", 1),
                                "explanation", ImmutableMap.of(
                                        "type",
                                        "string",
                                        "minLength",
                                        1,
                                        "maxLength",
                                        2000)), ImmutableList.of("operationKey", "findingId", "expectedRevision", "explanation")),
                        arguments -> call(reviewId, arguments, this::withdraw)),
                new ReviewTool("review_finish",
                        "Finish validation with honest coverage and one conclusion per numbered candidate. A retained candidate names its findingId; "
                                + "a rejected candidate omits findingId and explains the counterevidence. complete=false preserves partial work without advancing review coverage.",
                        schema(ImmutableMap.of(
                                "operationKey", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", MAX_KEY),
                                "complete", ImmutableMap.of("type", "boolean"),
                                "omissions", stringArray(50, 500),
                                "validations", ImmutableMap.of("type", "array", "maxItems", MAX_CANDIDATES, "items", schema(ImmutableMap.of(
                                        "candidate", ImmutableMap.of("type", "integer", "minimum", 1),
                                        "conclusion", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", 2000),
                                        "findingId", ImmutableMap.of("type", "string", "minLength", 1)), ImmutableList.of("candidate", "conclusion")))),
                                ImmutableList.of("operationKey", "complete", "omissions", "validations")),
                        arguments -> call(reviewId, arguments, this::finish)));
    }

    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required)
    {
        return ImmutableMap.of("type", "object", "properties", properties, "required", required, "additionalProperties", false);
    }

    private static Map<String, Object> stringArray(int maximum, int length)
    {
        return ImmutableMap.of("type", "array", "maxItems", maximum, "items", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", length));
    }

    private String call(String reviewId, String arguments, Function<Call, JsonNode> handler)
    {
        JsonNode parsed;
        try {
            parsed = objectMapper.readTree(arguments);
        }
        catch (JsonProcessingException e) {
            return error("arguments", "arguments must be a JSON object");
        }
        if (parsed == null || !parsed.isObject()) {
            return error("arguments", "arguments must be a JSON object");
        }
        Lock lock = locks.get(reviewId);
        lock.lock();
        try {
            AgentReview review = store.findReview(reviewId).orElseThrow(() -> new ReviewNotFoundException("Review not found: " + reviewId));
            if (review.state() != AgentReview.State.OPEN) {
                return error("review", "this review has ended; nothing can be recorded");
            }
            List<ReviewRun> runs = store.listRuns(reviewId);
            if (runs.isEmpty()) {
                return error("review", "this review has no run yet");
            }
            return handler.apply(new Call(review, runs.getLast(), (ObjectNode) parsed)).toString();
        }
        catch (ReviewInvalidRequestException | ReviewConflictException e) {
            String message = Optional.ofNullable(e.getMessage()).orElse("invalid request");
            int colon = message.indexOf(": ");
            return colon > 0 && colon < 40 ? error(message.substring(0, colon), message.substring(colon + 2)) : error("request", message);
        }
        finally {
            lock.unlock();
        }
    }

    private String error(String field, String message)
    {
        ObjectNode node = objectMapper.createObjectNode();
        node.putObject("error").put("field", field).put("message", message);
        return node.toString();
    }

    // ---- writes ----

    private JsonNode saveSummary(Call call)
    {
        Replay replay = replay(call, "review_save_summary");
        if (replay.result().isPresent()) {
            return replay.result().orElseThrow();
        }
        ObjectNode arguments = call.arguments();
        List<String> commitShas = call.run().commits().stream().map(commit -> commit.sha()).collect(toImmutableList());
        List<ReviewSummary.CommitExplanation> commits = new ArrayList<>();
        for (JsonNode commit : array(arguments, "commits", 200)) {
            String sha = text(commit, "sha", 64, true);
            List<String> matches = commitShas.stream().filter(candidate -> candidate.startsWith(sha)).collect(toImmutableList());
            if (sha.length() < 7 || matches.size() != 1) {
                throw new ReviewInvalidRequestException("commits: " + sha + " is not a commit in this run's scope");
            }
            commits.add(new ReviewSummary.CommitExplanation(matches.getFirst(), text(commit, "explanation", 1000, true)));
        }
        if (commits.size() != commitShas.size() || commits.stream().map(ReviewSummary.CommitExplanation::sha).distinct().count() != commitShas.size()) {
            throw new ReviewInvalidRequestException("commits: include each actual commit exactly once");
        }
        Optional<ReviewSummary> previous = call.run().summary();
        int revision = previous.map(ReviewSummary::revision).orElse(0) + 1;
        ReviewSummary summary = new ReviewSummary(
                revision,
                text(arguments, "purpose", 2000, true),
                commits,
                previous.map(ReviewSummary::combinedResult).orElse(""),
                previous.flatMap(ReviewSummary::evidence),
                previous.map(ReviewSummary::criticalFindingIds).orElseGet(ImmutableList::of));
        ObjectNode result = objectMapper.createObjectNode().put("revision", revision).put("status", "accepted");
        store.updateRun(call.run().withSummary(summary, now()).withOperation(replay.key(), new ReviewOperation(replay.digest(), result.toString()), now()));
        return result;
    }

    private JsonNode saveInvestigation(Call call)
    {
        Replay replay = replay(call, "review_save_investigation");
        if (replay.result().isPresent()) {
            return replay.result().orElseThrow();
        }
        if (!call.run().active() || call.run().coverage().isPresent()) {
            throw new ReviewInvalidRequestException("investigation: the run has already reported its result");
        }
        List<String> files = strings(call.arguments(), "reviewedFiles", 1000, 1000);
        Set<String> allowedFiles = new HashSet<>(requiredFiles(call));
        call.run().diff().forEach(file -> allowedFiles.add(file.filename()));
        if (files.stream().distinct().count() != files.size() || !allowedFiles.containsAll(files)) {
            throw new ReviewInvalidRequestException("reviewedFiles: use distinct paths from this run's frozen diff or requiredReviewFiles");
        }
        List<String> refs = strings(call.arguments(), "contextEvidenceRefs", 80, 20);
        if (refs.stream().anyMatch(ref -> !call.run().evidence().containsKey(ref))) {
            throw new ReviewInvalidRequestException("contextEvidenceRefs: use evidence refs returned in this run");
        }
        List<String> candidates = strings(call.arguments(), "candidates", MAX_CANDIDATES, 2000);
        List<String> previous = call.run().investigation().map(ReviewInvestigation::candidates).orElseGet(ImmutableList::of);
        if (call.run().validationStarted() && (candidates.size() < previous.size() || !candidates.subList(0, previous.size()).equals(previous))) {
            throw new ReviewInvalidRequestException("candidates: preserve candidate numbering during validation; append new hypotheses and reject disproven ones in review_finish");
        }
        ReviewInvestigation investigation = new ReviewInvestigation(text(call.arguments(), "changeMap", MAX_TEXT, true), files, refs, candidates, ImmutableList.of());
        ObjectNode result = objectMapper.createObjectNode().put("status", "accepted").put("candidates", candidates.size())
                .put("next", call.run().validationStarted() ? "Continue validation, then call review_finish"
                        : "End this investigation turn; ByteQuay will start a separate validation turn");
        store.updateRun(call.run().withInvestigation(investigation, now()).withOperation(replay.key(), new ReviewOperation(replay.digest(), result.toString()), now()));
        return result;
    }

    private JsonNode saveFinding(Call call)
    {
        Replay replay = replay(call, "review_save_finding");
        if (replay.result().isPresent()) {
            return replay.result().orElseThrow();
        }
        ObjectNode arguments = call.arguments();
        if (call.run().active() && !call.run().validationStarted()) {
            throw new ReviewInvalidRequestException("validation: save investigation and end the first turn; findings require the separate validation pass");
        }
        String validation = text(arguments, "validation", 2000, true);
        String title = text(arguments, "title", MAX_TITLE, true);
        String body = text(arguments, "body", MAX_TEXT, true);
        Optional<String> severity = Optional.ofNullable(arguments.get("severity")).filter(JsonNode::isTextual).map(JsonNode::asText)
                .map(value -> SEVERITIES.stream().filter(candidate -> candidate.equalsIgnoreCase(value)).findFirst()
                        .orElseThrow(() -> new ReviewInvalidRequestException("severity: use critical, high, medium or low")));
        List<ReviewEvidence> evidence = new ArrayList<>();
        for (JsonNode item : array(arguments, "evidence", MAX_EVIDENCE)) {
            String ref = text(item, "ref", 20, true);
            ReviewEvidence stored = call.run().evidence().get(ref);
            if (stored == null) {
                throw new ReviewInvalidRequestException("evidence: " + ref + " is not an evidence reference returned in this run; read the source or command output first");
            }
            String explanation = text(item, "explanation", 800, true) + (evidence.isEmpty() ? "\n\nValidation: " + validation : "");
            evidence.add(new ReviewEvidence(ref, stored.source(), stored.provenance(), stored.excerpt(), explanation));
        }
        if (evidence.isEmpty()) {
            throw new ReviewInvalidRequestException("evidence: at least one evidence reference is required");
        }
        List<ReviewFinding> findings = store.listFindings(call.review().id());
        ReviewFinding saved;
        boolean insert;
        Optional<String> findingId = Optional.ofNullable(arguments.get("findingId")).filter(JsonNode::isTextual).map(JsonNode::asText);
        if (findingId.isPresent()) {
            ReviewFinding existing = findings.stream().filter(finding -> finding.id().equals(findingId.orElseThrow())).findFirst()
                    .orElseThrow(() -> new ReviewInvalidRequestException("findingId: unknown finding " + findingId.orElseThrow()));
            int expected = integer(arguments, "expectedRevision").orElseThrow(() -> new ReviewInvalidRequestException("expectedRevision: required when revising a finding"));
            if (expected != existing.revision()) {
                throw new ReviewConflictException("expectedRevision: the finding is at revision " + existing.revision());
            }
            if (existing.state() == ReviewFinding.State.WITHDRAWN) {
                throw new ReviewConflictException("findingId: the finding was withdrawn");
            }
            saved = existing.revised(title, body, severity, ImmutableList.copyOf(evidence), now());
            insert = false;
        }
        else {
            if (!call.run().active() || call.run().coverage().isPresent()) {
                throw new ReviewInvalidRequestException("finding: new findings require an active validation pass");
            }
            if (findings.stream().filter(finding -> finding.runId().equals(call.run().id())).count() >= 10) {
                throw new ReviewInvalidRequestException("finding: select at most ten distinct findings per round");
            }
            ReviewDiffAnchors anchors = ReviewDiffAnchors.of(call.run().diff());
            ReviewAnchor anchor = anchors.range(
                    text(arguments, "startAnchor", 20, true),
                    Optional.ofNullable(arguments.get("endAnchor")).filter(JsonNode::isTextual).map(JsonNode::asText));
            Optional<ReviewFinding> duplicate = findings.stream()
                    .filter(finding -> finding.state() != ReviewFinding.State.WITHDRAWN
                            && finding.anchor().path().equals(anchor.path())
                            && finding.anchor().side().equals(anchor.side())
                            && finding.anchor().startLine() == anchor.startLine() && finding.anchor().endLine() == anchor.endLine() && finding.title().equalsIgnoreCase(title))
                    .findFirst();
            if (duplicate.isPresent()) {
                throw new ReviewConflictException(
                        "startAnchor: finding " + duplicate.orElseThrow().id() + " already reports this at the same lines; revise it by findingId instead");
            }
            Instant now = now();
            saved = new ReviewFinding(
                    UUID.randomUUID().toString(),
                    call.review().id(),
                    call.run().id(),
                    findings.size() + 1,
                    title,
                    body,
                    severity,
                    anchor,
                    ImmutableList.copyOf(evidence),
                    1,
                    ReviewFinding.State.OPEN,
                    Optional.empty(),
                    call.run().headSha(),
                    false,
                    0,
                    now,
                    now);
            insert = true;
        }
        if (body.lines().anyMatch(line -> line.stripLeading().startsWith("```suggestion"))) {
            if (!saved.anchor().side().equals("RIGHT")) {
                throw new ReviewInvalidRequestException("startAnchor: a suggestion must replace current RIGHT-side lines, not deleted lines");
            }
            if (body.lines().anyMatch(line -> line.stripLeading().startsWith("```suggestion") && !line.strip().equals("```suggestion"))) {
                throw new ReviewInvalidRequestException("body: use a plain suggestion fence and startAnchor/endAnchor for the exact replacement range");
            }
        }
        ObjectNode result = objectMapper.createObjectNode()
                .put("findingId", saved.id())
                .put("label", saved.label())
                .put("revision", saved.revision())
                .put("location", saved.anchor().path() + " " + saved.anchor().label())
                .put("status", "accepted");
        store.saveFinding(saved, insert, call.run().withOperation(replay.key(), new ReviewOperation(replay.digest(), result.toString()), now()));
        return result;
    }

    private JsonNode reply(Call call)
    {
        Replay replay = replay(call, "review_reply");
        if (replay.result().isPresent()) {
            return replay.result().orElseThrow();
        }
        ObjectNode arguments = call.arguments();
        String target = text(arguments, "target", 200, true);
        String body = text(arguments, "body", MAX_TEXT, true);
        Optional<String> findingId = Optional.empty();
        Optional<String> cardId = Optional.empty();
        ReviewRun targetRun = call.run();
        int revision;
        if (target.equalsIgnoreCase("summary")) {
            revision = call.run().summary().map(ReviewSummary::revision).orElse(1);
        }
        else if (target.contains(":")) {
            targetRun = ReviewCards.requireRun(store, call.review().id(), target);
            cardId = Optional.of(target);
            revision = targetRun.summary().map(ReviewSummary::revision).orElse(1);
        }
        else {
            ReviewFinding finding = store.listFindings(call.review().id()).stream().filter(candidate -> candidate.id().equals(target)).findFirst()
                    .orElseThrow(() -> new ReviewInvalidRequestException("target: unknown finding " + target + "; use \"summary\" or a findingId"));
            findingId = Optional.of(finding.id());
            targetRun = store.findRun(finding.runId()).orElseThrow();
            revision = finding.revision();
        }
        ReviewMessage message = new ReviewMessage(
                UUID.randomUUID().toString(),
                call.review().id(),
                findingId,
                cardId,
                ReviewMessage.Author.AGENT,
                body,
                revision,
                targetRun.id(),
                Optional.empty(),
                Optional.empty(),
                0,
                now());
        ObjectNode result = objectMapper.createObjectNode().put("messageId", message.id()).put("status", "accepted");
        store.insertMessage(message, call.run().withOperation(replay.key(), new ReviewOperation(replay.digest(), result.toString()), now()));
        return result;
    }

    private JsonNode withdraw(Call call)
    {
        Replay replay = replay(call, "review_withdraw_finding");
        if (replay.result().isPresent()) {
            return replay.result().orElseThrow();
        }
        ObjectNode arguments = call.arguments();
        String findingId = text(arguments, "findingId", 100, true);
        ReviewFinding finding = store.listFindings(call.review().id()).stream().filter(candidate -> candidate.id().equals(findingId)).findFirst()
                .orElseThrow(() -> new ReviewInvalidRequestException("findingId: unknown finding " + findingId));
        int expected = integer(arguments, "expectedRevision").orElseThrow(() -> new ReviewInvalidRequestException("expectedRevision: required"));
        if (expected != finding.revision()) {
            throw new ReviewConflictException("expectedRevision: the finding is at revision " + finding.revision());
        }
        String explanation = text(arguments, "explanation", 2000, true);
        Instant now = now();
        // A user's local resolution stands; the withdrawal then lives in the discussion, not the state.
        ReviewFinding updated = finding.state() == ReviewFinding.State.OPEN
                ? finding.withState(ReviewFinding.State.WITHDRAWN, Optional.of(explanation), now)
                : finding;
        ReviewMessage message = new ReviewMessage(UUID.randomUUID().toString(), call.review().id(), Optional.of(finding.id()), Optional.empty(), ReviewMessage.Author.AGENT,
                "Withdrawn: " + explanation, finding.revision(), finding.runId(), Optional.empty(), Optional.empty(), 0, now);
        ObjectNode result = objectMapper.createObjectNode().put("findingId", finding.id()).put("state", updated.state().name()).put("status", "accepted");
        store.saveFinding(updated, false, call.run().withOperation(replay.key(), new ReviewOperation(replay.digest(), result.toString()), now));
        store.insertMessage(message);
        return result;
    }

    private JsonNode finish(Call call)
    {
        Replay replay = replay(call, "review_finish");
        if (replay.result().isPresent()) {
            return replay.result().orElseThrow();
        }
        ObjectNode arguments = call.arguments();
        JsonNode complete = arguments.get("complete");
        if (complete == null || !complete.isBoolean()) {
            throw new ReviewInvalidRequestException("complete: required boolean");
        }
        List<String> omissions = strings(arguments, "omissions", 50, 500);
        if (complete.asBoolean() && !omissions.isEmpty()) {
            throw new ReviewInvalidRequestException("complete: a complete review cannot list omissions");
        }
        if (!call.run().active()) {
            throw new ReviewInvalidRequestException("review: this run has ended; discussion does not change its completion");
        }
        if (complete.asBoolean() && !call.run().validationStarted()) {
            throw new ReviewInvalidRequestException("validation: finish the investigation turn before submitting the validated report");
        }
        if (!complete.asBoolean() && omissions.isEmpty()) {
            throw new ReviewInvalidRequestException("omissions: explain the unreviewed scope or unresolved validation");
        }
        List<ReviewCandidateValidation> validations = new ArrayList<>();
        Set<Integer> candidates = new HashSet<>();
        Set<String> retained = new HashSet<>();
        int candidateCount = call.run().investigation().map(value -> value.candidates().size()).orElse(0);
        for (JsonNode item : array(arguments, "validations", MAX_CANDIDATES)) {
            int candidate = integer(item, "candidate").orElseThrow(() -> new ReviewInvalidRequestException("candidate: required positive number"));
            if (candidate > candidateCount || !candidates.add(candidate)) {
                throw new ReviewInvalidRequestException("validations: include each investigation candidate once, using its one-based number");
            }
            Optional<String> findingId = Optional.of(text(item, "findingId", 100, false)).filter(value -> !value.isEmpty());
            findingId.ifPresent(id -> {
                ReviewFinding finding = store.findFinding(id).filter(value -> value.reviewId().equals(call.review().id()))
                        .orElseThrow(() -> new ReviewInvalidRequestException("findingId: must belong to this review"));
                if (finding.state() == ReviewFinding.State.WITHDRAWN || finding.outdated() || !finding.validatedHeadSha().equals(call.run().headSha())) {
                    throw new ReviewInvalidRequestException("findingId: retained findings must be current and not withdrawn");
                }
                retained.add(id);
            });
            validations.add(new ReviewCandidateValidation(candidate, text(item, "conclusion", 2000, true), findingId));
        }
        if (complete.asBoolean()) {
            if (!call.run().hasCompleteSummary()) {
                throw new ReviewInvalidRequestException("summary: save the intention and each actual commit exactly once");
            }
            ReviewInvestigation investigation = call.run().investigation()
                    .orElseThrow(() -> new ReviewInvalidRequestException("investigation: required before completion"));
            if (!investigation.reviewedFiles().containsAll(requiredFiles(call))) {
                throw new ReviewInvalidRequestException("reviewedFiles: account for every required changed file or finish with complete=false and omissions");
            }
            if (validations.size() != candidateCount) {
                throw new ReviewInvalidRequestException("validations: resolve every candidate, including rejected hypotheses");
            }
            if (store.listFindings(call.review().id()).stream().anyMatch(finding -> finding.runId().equals(call.run().id())
                    && finding.state() != ReviewFinding.State.WITHDRAWN && !retained.contains(finding.id()))) {
                throw new ReviewInvalidRequestException("validations: map every new finding to a candidate or withdraw it");
            }
        }
        ReviewCoverage coverage = new ReviewCoverage(complete.asBoolean(), omissions, now());
        ReviewRun run = call.run();
        if (run.investigation().isPresent()) {
            run = run.withInvestigation(run.investigation().orElseThrow().withValidations(validations), now());
        }
        ObjectNode result = objectMapper.createObjectNode().put("status", "accepted");
        store.updateRun(run.withCoverage(coverage, now()).withOperation(replay.key(), new ReviewOperation(replay.digest(), result.toString()), now()));
        return result;
    }

    // ---- reads ----

    private JsonNode getContext(Call call)
    {
        ObjectNode arguments = call.arguments();
        String kind = text(arguments, "kind", 30, true).toLowerCase(Locale.ROOT);
        return switch (kind) {
            case "pr" -> pullRequest(call);
            case "timeline" -> timeline(call, page(arguments));
            case "checks" -> checks(call);
            case "issue" -> issue(call, integer(arguments, "number").orElseThrow(() -> new ReviewInvalidRequestException("number: required for kind issue")));
            case "diff" -> diff(call, Optional.ofNullable(arguments.get("path")).filter(JsonNode::isTextual).map(JsonNode::asText), page(arguments));
            case "source" -> source(call, text(arguments, "path", 1000, true), integer(arguments, "startLine").orElse(1), integer(arguments, "endLine"));
            case "command_output" -> commandOutput(call);
            case "investigation" -> objectMapper.valueToTree(call.run().investigation().orElse(null));
            case "findings" -> findings(call);
            case "summary" -> summary(call);
            case "discussion" -> discussion(call, text(arguments, "target", 200, true));
            case "evidence" -> evidence(call, text(arguments, "ref", 20, true));
            default -> throw new ReviewInvalidRequestException("kind: unknown context kind " + kind);
        };
    }

    private JsonNode pullRequest(Call call)
    {
        PullRequestDetail detail = pullRequests.getPullRequestDetail(call.review().repo(), call.review().number());
        ReviewRun run = call.run();
        ObjectNode node = objectMapper.createObjectNode()
                .put("repository", call.review().repo())
                .put("number", call.review().number())
                .put("title", detail.title())
                .put("author", detail.author())
                .put("state", detail.state())
                .put("draft", detail.draft())
                .put("baseRef", run.baseRef())
                .put("baseSha", run.baseSha())
                .put("mergeBaseSha", run.mergeBaseSha())
                .put("headSha", run.headSha())
                .put("scope", run.scope().name())
                .put("body", bounded(Optional.ofNullable(detail.body()).orElse(""), 8000));
        run.priorHeadSha().ifPresent(sha -> node.put("previousReviewedHead", sha));
        node.putPOJO("labels", detail.labels().stream().map(PullRequestDetail.Label::name).collect(toImmutableList()));
        node.putPOJO("commits", run.commits());
        node.putPOJO("requiredReviewFiles", requiredFiles(call));
        node.put("pass", run.validationStarted() ? "validation" : "investigation");
        node.putPOJO(
                "changedFiles",
                run
                        .diff()
                        .stream()
                        .map(file -> ImmutableMap.of("path", file.filename(), "status", file.status(), "additions", file.additions(), "deletions", file.deletions()))
                        .collect(toImmutableList()));
        return node;
    }

    private List<String> requiredFiles(Call call)
    {
        Optional<ReviewRun> previous = call.run().priorHeadSha().flatMap(head -> store.listRuns(call.review().id()).stream()
                .filter(run -> run.state() == ReviewRun.State.ACCEPTED && run.headSha().equals(head)
                        && run.hasCompleteSummary() && run.coverage().filter(coverage -> coverage.complete() && coverage.omissions().isEmpty()).isPresent())
                .reduce((first, last) -> last));
        if (call.run().scope() == ReviewRun.Scope.FULL || previous.isEmpty()) {
            return call.run().diff().stream().map(DiffFile::filename).collect(toImmutableList());
        }
        // Reverted-to-base paths disappear from the publication diff; binary patches cannot establish content equality either.
        GitCommandRunner.Result changed = git.run(workspaces.requireReadableRepository(call.review().workspaceId()).repository(),
                "diff", "--name-only", "--no-renames", "-z", previous.orElseThrow().headSha(), call.run().headSha(), "--");
        if (!changed.successful() || changed.truncated()) {
            throw new ReviewInvalidRequestException("coverage: the frozen incremental file list is unavailable; report incomplete coverage");
        }
        return Arrays.stream(changed.output().split("\\x00")).filter(path -> !path.isEmpty()).collect(toImmutableList());
    }

    private JsonNode timeline(Call call, int page)
    {
        PullRequestDetail detail = pullRequests.getPullRequestDetail(call.review().repo(), call.review().number());
        List<PullRequestDetail.ActivityItem> items = detail.recentActivity();
        int from = Math.min(items.size(), (page - 1) * TIMELINE_PAGE);
        int to = Math.min(items.size(), from + TIMELINE_PAGE);
        ObjectNode node = objectMapper.createObjectNode().put("page", page).put("pages", Math.max(1, (items.size() + TIMELINE_PAGE - 1) / TIMELINE_PAGE));
        if (detail.unavailableSections().contains("reviewThreads")) {
            node.put("note", "review threads were unavailable when the pull request was last refreshed");
        }
        node.putPOJO("items", items.subList(from, to).stream().map(item -> {
            Map<String, Object> entry = new TreeMap<>();
            entry.put("actor", Optional.ofNullable(item.actor()).orElse(""));
            entry.put("event", item.eventType());
            entry.put("at", item.timestamp() == null ? "" : item.timestamp().toString());
            entry.put("body", bounded(Optional.ofNullable(item.body()).orElse(""), 2000));
            if (item.payload() != null && item.payload().path() != null) {
                entry.put("path", item.payload().path());
                entry.put("line", item.payload().line() == null ? "" : item.payload().line().toString());
                entry.put("side", Optional.ofNullable(item.payload().side()).orElse(""));
                entry.put("resolved", item.payload().resolved());
            }
            return entry;
        }).collect(toImmutableList()));
        return node;
    }

    private JsonNode checks(Call call)
    {
        PullRequestDetail detail = pullRequests.getPullRequestDetail(call.review().repo(), call.review().number());
        ObjectNode node = objectMapper.createObjectNode().put("ciStatus", detail.ciStatus().name());
        node.putPOJO("checks", detail.checkRuns().stream()
                .map(
                        check -> ImmutableMap.of(
                                "name",
                                Optional.ofNullable(check.name()).orElse(""),
                                "status",
                                Optional.ofNullable(check.status()).orElse(""),
                                "conclusion",
                                Optional.ofNullable(check.conclusion()).orElse(""),
                                "category",
                                check.category()))
                .collect(toImmutableList()));
        return node;
    }

    private JsonNode issue(Call call, int number)
    {
        String[] parts = call.review().repo().split("/", 2);
        GitHubIssuePreview preview = repositories.getIssuePreview(parts[0], parts[1], number);
        String excerpt = bounded(Optional.ofNullable(preview.body()).orElse(""), MAX_EXCERPT);
        String ref = registerEvidence(
                call,
                call.review().repo() + "#" + number + ": " + preview.title(),
                "GitHub " + (preview.pullRequest() ? "pull request" : "issue") + " read by ByteQuay",
                excerpt);
        return objectMapper.createObjectNode()
                .put("number", number)
                .put("title", preview.title())
                .put("state", preview.state())
                .put("author", preview.author())
                .put("pullRequest", preview.pullRequest())
                .put("body", excerpt)
                .put("evidenceRef", ref);
    }

    private JsonNode diff(Call call, Optional<String> path, int page)
    {
        ReviewDiffAnchors anchors = ReviewDiffAnchors.of(call.run().diff());
        if (path.isEmpty()) {
            ObjectNode node = objectMapper.createObjectNode().put("anchors", anchors.size()).put("note", "call again with a path to read its hunks with anchor ids");
            node.putPOJO("files", call.run().diff().stream().map(file -> ImmutableMap.of(
                    "path", file.filename(), "status", file.status(), "additions", file.additions(), "deletions", file.deletions(),
                    "hunks", anchors.hunks(file.filename()).size(), "textual", file.patch() != null)).collect(toImmutableList()));
            return node;
        }
        DiffFile file = call.run().diff().stream().filter(candidate -> candidate.filename().equals(path.orElseThrow())).findFirst()
                .orElseThrow(() -> new ReviewInvalidRequestException("path: " + path.orElseThrow() + " is not in this pull request's diff"));
        List<String> lines = new ArrayList<>();
        if (file.patch() == null) {
            lines.add("(binary file or diff unavailable; no inline anchors)");
        }
        for (ReviewDiffAnchors.Hunk hunk : anchors.hunks(file.filename())) {
            lines.add(hunk.header());
            hunk.lines().forEach(line -> lines.add(line.id() + " " + line.kind() + (line.side().equals("LEFT") ? "L" : "R") + line.line() + " " + line.text()));
        }
        int pages = Math.max(1, (lines.size() + DIFF_PAGE_LINES - 1) / DIFF_PAGE_LINES);
        int from = Math.min(lines.size(), (page - 1) * DIFF_PAGE_LINES);
        int to = Math.min(lines.size(), from + DIFF_PAGE_LINES);
        return objectMapper.createObjectNode()
                .put("path", file.filename())
                .put("status", file.status())
                .put("page", page)
                .put("pages", pages)
                .put("format", "anchorId kind side+line text; cite anchors as startAnchor/endAnchor in review_save_finding")
                .put("hunks", String.join("\n", lines.subList(from, to)));
    }

    private JsonNode source(Call call, String path, int startLine, Optional<Integer> endLine)
    {
        if (path.startsWith("/") || path.contains("..") || path.isBlank()) {
            throw new ReviewInvalidRequestException("path: use a repository-relative path");
        }
        int end = endLine.orElse(startLine + SOURCE_WINDOW - 1);
        if (end < startLine) {
            throw new ReviewInvalidRequestException("endLine: must not precede startLine");
        }
        end = Math.min(end, startLine + SOURCE_WINDOW - 1);
        String revision = text(call.arguments(), "revision", 20, false);
        String sha = switch (revision) {
            case "", "head" -> call.run().headSha();
            case "base" -> call.run().mergeBaseSha();
            case "previous" -> call.run().priorHeadSha().orElseThrow(() -> new ReviewInvalidRequestException("revision: no previously reviewed head"));
            default -> throw new ReviewInvalidRequestException("revision: use head, base (merge base), or previous");
        };
        GitCommandRunner.Result shown = git.run(workspaces.requireReadableRepository(call.review().workspaceId()).repository(), "show", sha + ":" + path);
        if (!shown.successful()) {
            throw new ReviewInvalidRequestException("path: " + path + " does not exist at the selected frozen revision");
        }
        List<String> all = shown.output().lines().toList();
        if (startLine > all.size()) {
            throw new ReviewInvalidRequestException("startLine: the file has " + all.size() + " lines");
        }
        StringBuilder window = new StringBuilder();
        for (int line = startLine; line <= Math.min(end, all.size()); line++) {
            window.append(line).append("  ").append(all.get(line - 1)).append('\n');
        }
        String excerpt = bounded(window.toString(), MAX_EXCERPT);
        String ref = registerEvidence(
                call,
                path + " L" + startLine + "–L" + Math.min(end, all.size()) + " (frozen " + (revision.isEmpty() ? "head" : revision) + " " + ReviewPrompts.shortSha(sha) + ")",
                "source read from frozen commit " + sha + ", not the working files",
                excerpt);
        return objectMapper.createObjectNode()
                .put("path", path)
                .put("commit", sha)
                .put("startLine", startLine)
                .put("endLine", Math.min(end, all.size()))
                .put("totalLines", all.size())
                .put("evidenceRef", ref)
                .put("source", excerpt);
    }

    private JsonNode commandOutput(Call call)
    {
        List<AgentEventEnvelope> recent = events.readHistory(TaskId.valueOf(call.review().taskId()), OptionalLong.empty(), OptionalLong.empty()).events();
        List<Map<String, Object>> commands = new ArrayList<>();
        for (int index = recent.size() - 1; index >= 0 && commands.size() < 10; index--) {
            AgentEventEnvelope envelope = recent.get(index);
            if (!(envelope.payload() instanceof AgentEventPayload.CommandActivity command) || command.output().isEmpty()) {
                continue;
            }
            String commandLine = String.join(" ", command.argv());
            String excerpt = bounded("$ " + commandLine + "\n" + command.output().orElseThrow(), 3000);
            String ref = registerEvidence(
                    call,
                    "command output: " + bounded(commandLine, 200),
                    "command run by the agent in the review worktree" + envelope.turnId().map(turn -> " during turn " + turn).orElse(""),
                    excerpt);
            commands.add(ImmutableMap.of("evidenceRef", ref, "command", commandLine, "exitCode", command.exitCode().map(String::valueOf).orElse(""), "output", excerpt));
        }
        return objectMapper.createObjectNode().putPOJO("commands", commands);
    }

    private JsonNode findings(Call call)
    {
        return objectMapper.createObjectNode().putPOJO("findings", store.listFindings(call.review().id()).stream()
                .map(finding -> ImmutableMap.<String, Object>builder()
                        .put("findingId", finding.id())
                        .put("label", finding.label())
                        .put("revision", finding.revision())
                        .put("state", finding.state().name())
                        .put("outdated", finding.outdated())
                        .put("path", finding.anchor().path())
                        .put("range", finding.anchor().label())
                        .put("title", finding.title())
                        .put("severity", finding.severity().orElse(""))
                        .buildOrThrow())
                .collect(toImmutableList()));
    }

    private JsonNode summary(Call call)
    {
        String target = text(call.arguments(), "target", 200, false);
        if (!target.isBlank() && !target.equalsIgnoreCase("summary")) {
            ReviewRun run = ReviewCards.requireRun(store, call.review().id(), target);
            return objectMapper.createObjectNode().put("cardId", target).put("runId", run.id()).put("headSha", run.headSha())
                    .put("revision", run.summary().map(ReviewSummary::revision).orElse(1)).put("body", ReviewCards.content(run, target));
        }
        return call.run().summary().<JsonNode>map(summary -> objectMapper.valueToTree(summary))
                .orElseGet(() -> objectMapper.createObjectNode().put("summary", "none saved for this run yet"));
    }

    private JsonNode discussion(Call call, String target)
    {
        Optional<String> cardId = target.contains(":") ? Optional.of(target) : Optional.empty();
        cardId.ifPresent(id -> ReviewCards.requireRun(store, call.review().id(), id));
        Optional<String> findingId = target.equalsIgnoreCase("summary") || cardId.isPresent() ? Optional.empty() : Optional.of(target);
        Optional<String> purposeRun = cardId.filter(id -> id.endsWith(":purpose")).map(id -> id.substring(0, id.indexOf(':')));
        return objectMapper.createObjectNode().putPOJO("messages", store.listMessages(call.review().id()).stream()
                .filter(message -> message.findingId().equals(findingId))
                .filter(message -> message.cardId().equals(cardId)
                        || (message.cardId().isEmpty() && purposeRun.filter(message.runId()::equals).isPresent()))
                .filter(message -> findingId.isPresent() || cardId.isPresent() || message.runId().equals(call.run().id()))
                .map(message -> ImmutableMap.of("author", message.author().name(), "at", message.createdAt().toString(),
                        "targetRevision", message.targetRevision(), "body", message.body()))
                .collect(toImmutableList()));
    }

    private JsonNode evidence(Call call, String ref)
    {
        ReviewEvidence item = Optional.ofNullable(call.run().evidence().get(ref))
                .orElseThrow(() -> new ReviewInvalidRequestException("ref: " + ref + " is not an evidence reference of this run"));
        return objectMapper.valueToTree(item);
    }

    private String registerEvidence(Call call, String source, String provenance, String excerpt)
    {
        ReviewRun current = store.findRun(call.run().id()).orElse(call.run());
        String ref = "E" + (current.evidence().size() + 1);
        store.updateRun(current.withEvidence(ref, new ReviewEvidence(ref, source, provenance, excerpt, ""), now()));
        return ref;
    }

    // ---- helpers ----

    private Replay replay(Call call, String tool)
    {
        String operationKey = text(call.arguments(), "operationKey", MAX_KEY, true);
        String key = tool + ":" + operationKey;
        String digest = Hashing.sha256().hashString(canonical(call.arguments()), StandardCharsets.UTF_8).toString();
        ReviewOperation stored = call.run().operations().get(key);
        if (stored == null) {
            return new Replay(key, digest, Optional.empty());
        }
        if (!stored.payloadDigest().equals(digest)) {
            throw new ReviewConflictException("operationKey: " + operationKey + " was already used with different content; choose a new key for a new write");
        }
        try {
            return new Replay(key, digest, Optional.of(objectMapper.readTree(stored.result())));
        }
        catch (JsonProcessingException e) {
            throw new ReviewConflictException("operationKey: stored result is unreadable");
        }
    }

    private static String canonical(JsonNode node)
    {
        if (node.isObject()) {
            TreeMap<String, String> sorted = new TreeMap<>();
            node.fields().forEachRemaining(entry -> sorted.put(entry.getKey(), canonical(entry.getValue())));
            StringBuilder out = new StringBuilder("{");
            sorted.forEach((key, value) -> out.append('"').append(key).append("\":").append(value).append(','));
            return out.append('}').toString();
        }
        if (node.isArray()) {
            StringBuilder out = new StringBuilder("[");
            node.forEach(item -> out.append(canonical(item)).append(','));
            return out.append(']').toString();
        }
        return node.toString();
    }

    private static String text(JsonNode node, String field, int maximum, boolean required)
    {
        JsonNode value = node.get(field);
        if (value == null || value.isNull() || !value.isTextual() || value.asText().isBlank()) {
            if (required) {
                throw new ReviewInvalidRequestException(field + ": required non-empty text");
            }
            return "";
        }
        String text = value.asText().strip();
        if (text.length() > maximum) {
            throw new ReviewInvalidRequestException(field + ": longer than " + maximum + " characters");
        }
        return text;
    }

    private static Optional<Integer> integer(JsonNode node, String field)
    {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) {
            return Optional.empty();
        }
        if (!value.canConvertToInt() || value.asInt() < 1) {
            throw new ReviewInvalidRequestException(field + ": must be a positive integer");
        }
        return Optional.of(value.asInt());
    }

    private static int page(JsonNode node)
    {
        return integer(node, "page").orElse(1);
    }

    private static List<JsonNode> array(JsonNode node, String field, int maximum)
    {
        JsonNode value = node.get(field);
        if (value == null || !value.isArray()) {
            throw new ReviewInvalidRequestException(field + ": required array");
        }
        if (value.size() > maximum) {
            throw new ReviewInvalidRequestException(field + ": at most " + maximum + " items");
        }
        return ImmutableList.copyOf(value);
    }

    private static List<String> strings(JsonNode node, String field, int maximum, int length)
    {
        return array(node, field, maximum).stream()
                .map(item -> {
                    if (!item.isTextual() || item.asText().isBlank()) {
                        throw new ReviewInvalidRequestException(field + ": items must be non-empty text");
                    }
                    if (item.asText().length() > length) {
                        throw new ReviewInvalidRequestException(field + ": items longer than " + length + " characters");
                    }
                    return item.asText().strip();
                })
                .collect(toImmutableList());
    }

    private static String bounded(String text, int maximum)
    {
        return text.length() <= maximum ? text : text.substring(0, maximum) + "\n… (truncated)";
    }

    private Instant now()
    {
        return Instant.now(clock);
    }

    private record Call(AgentReview review, ReviewRun run, ObjectNode arguments) {}

    private record Replay(String key, String digest, Optional<JsonNode> result) {}
}
