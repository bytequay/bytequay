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
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.spi.ReviewBridge;
import com.bytequay.agent.spi.ReviewTool;
import com.bytequay.app.context.SelectedCommitPullRequestReader;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.repository.github.GitHubIssuePreview;
import com.bytequay.app.service.RepoService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.service.pr.SelectedPullRequestDiffReader;
import com.bytequay.app.service.pr.SelectedPullRequestReader;
import com.bytequay.app.workspace.WorkspaceIssueRefreshService;
import com.bytequay.app.workspace.WorkspaceIssueService;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.RepositoryIssueDetail;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewAnchor;
import com.bytequay.core.review.ReviewCandidateValidation;
import com.bytequay.core.review.ReviewConflictException;
import com.bytequay.core.review.ReviewCoverage;
import com.bytequay.core.review.ReviewEvidence;
import com.bytequay.core.review.ReviewEvidenceAnswer;
import com.bytequay.core.review.ReviewEvidenceRequest;
import com.bytequay.core.review.ReviewFinding;
import com.bytequay.core.review.ReviewInvalidRequestException;
import com.bytequay.core.review.ReviewInvestigation;
import com.bytequay.core.review.ReviewMessage;
import com.bytequay.core.review.ReviewNotFoundException;
import com.bytequay.core.review.ReviewOperation;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.review.ReviewStep;
import com.bytequay.core.review.ReviewSummary;
import com.bytequay.core.review.ReviewValidation;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.hash.Hashing;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

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

import static com.bytequay.core.review.ReviewCandidateValidation.Outcome.NEEDS_EVIDENCE;
import static com.bytequay.core.review.ReviewCandidateValidation.Outcome.SUPPORTED;
import static com.bytequay.core.review.ReviewCandidateValidation.Outcome.UNRESOLVED;
import static com.bytequay.core.review.ReviewStep.Kind.COLLECT_EVIDENCE;
import static com.bytequay.core.review.ReviewStep.Kind.INVESTIGATE;
import static com.bytequay.core.review.ReviewStep.Kind.VALIDATE;
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
    private static final int MAX_EVIDENCE_REF = 200;
    private static final int MAX_CANDIDATES = 30;
    private static final int MAX_EXCERPT = 4000;
    private static final int SOURCE_WINDOW = 200;
    private static final int DIFF_PAGE_LINES = 300;
    private static final int RELATED_DIFF_FILES_PAGE = 20;
    private static final int RELATED_DIFF_CODE_POINTS_PAGE = 2000;
    private static final int MAX_RELATED_DIFF_GAPS = 10;
    private static final int TIMELINE_PAGE = 20;
    private static final int CHECK_PAGE = 20;
    private static final int MAX_COMMIT_ASSOCIATION_GAPS = 10;
    private static final List<String> SEVERITIES = ImmutableList.of("Critical", "High", "Medium", "Low");

    private final AgentReviewStore store;
    private final PullRequestService pullRequests;
    private final SelectedPullRequestReader selectedPullRequests;
    private final SelectedPullRequestDiffReader selectedPullRequestDiffs;
    private final SelectedCommitPullRequestReader selectedCommitPullRequests;
    private final RepoService repositories;
    private final WorkspaceIssueService issues;
    private final WorkspaceIssueRefreshService issueRefresh;
    private final AgentEventService events;
    private final WorkspaceRepositoryService workspaces;
    private final GitCommandRunner git;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ReviewLocks locks;
    private final ReviewToolAssignment assignments;

    public ReviewTools(
            AgentReviewStore store,
            PullRequestService pullRequests,
            SelectedPullRequestReader selectedPullRequests,
            SelectedPullRequestDiffReader selectedPullRequestDiffs,
            SelectedCommitPullRequestReader selectedCommitPullRequests,
            RepoService repositories,
            WorkspaceIssueService issues,
            WorkspaceIssueRefreshService issueRefresh,
            AgentEventService events,
            WorkspaceRepositoryService workspaces,
            GitCommandRunner git,
            ObjectMapper objectMapper,
            ReviewLocks locks,
            ReviewToolAssignment assignments,
            Clock clock)
    {
        this.store = requireNonNull(store, "store is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.selectedPullRequests = requireNonNull(selectedPullRequests, "selectedPullRequests is null");
        this.selectedPullRequestDiffs = requireNonNull(selectedPullRequestDiffs, "selectedPullRequestDiffs is null");
        this.selectedCommitPullRequests = requireNonNull(selectedCommitPullRequests, "selectedCommitPullRequests is null");
        this.repositories = requireNonNull(repositories, "repositories is null");
        this.issues = requireNonNull(issues, "issues is null");
        this.issueRefresh = requireNonNull(issueRefresh, "issueRefresh is null");
        this.events = requireNonNull(events, "events is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.git = requireNonNull(git, "git is null");
        this.objectMapper = requireNonNull(objectMapper, "objectMapper is null");
        this.clock = requireNonNull(clock, "clock is null");
        this.locks = requireNonNull(locks, "locks is null");
        this.assignments = requireNonNull(assignments, "assignments is null");
    }

    /** The bridge for one review-owned task, or empty for any other task. */
    public Optional<ReviewBridge> bridge(String taskId)
    {
        return store.findReviewByTask(taskId)
                .filter(review -> review.state() == AgentReview.State.OPEN)
                .map(review -> new ReviewBridge(tools(review.id(), assignments.capture(taskId)), ReviewPrompts.INSTRUCTION));
    }

    private List<ReviewTool> tools(String reviewId, Optional<TurnId> turnId)
    {
        return ImmutableList.of(
                new ReviewTool("review_get_context",
                        "Read bounded pull request context for this review: kind pr, timeline, checks "
                                + "(optional same-repository PR number; defaults to this review; page for timeline/checks), "
                                + "issue (required number), diff (optional same-repository PR number, path, page, diffVersion, refresh), "
                                + "commit_prs (required shas: 1 to " + SelectedCommitPullRequestReader.MAX_COMMITS
                                + " unique full 40/64-hex commit IDs in this repository), "
                                + "source (path, startLine, endLine, revision: head/base/previous), command_output, investigation, findings, "
                                + "summary, discussion (target), or evidence (ref). "
                                + "Only current-review diff lines carry anchor ids; related patch pages and other source reads return evidence refs. "
                                + "For related diffs, read the file index first, then send its diffVersion on every path or later page. "
                                + "refresh=true is only for a related initial index without diffVersion. Patch fragments may split lines. "
                                + "Known PRs requested through issue return PR metadata; use timeline(number, page) for their discussion. "
                                + "Selected metadata can be newer than the frozen reviewed code; inspect freshness before relying on historical discussion or checks.",
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
                                                        "commit_prs",
                                                        "diff",
                                                        "source",
                                                        "command_output",
                                                        "investigation",
                                                        "assignment",
                                                        "findings",
                                                        "summary",
                                                        "discussion",
                                                        "evidence")))
                                .put("path", ImmutableMap.of("type", "string", "maxLength", 1000))
                                .put("revision", ImmutableMap.of("type", "string", "enum", ImmutableList.of("head", "base", "previous")))
                                .put("number", ImmutableMap.of("type", "integer", "minimum", 1))
                                .put("shas", ImmutableMap.of("type", "array", "minItems", 1, "maxItems", SelectedCommitPullRequestReader.MAX_COMMITS,
                                        "uniqueItems", true, "items", ImmutableMap.of("type", "string", "pattern", "^(?:[0-9a-fA-F]{40}|[0-9a-fA-F]{64})$")))
                                .put("diffVersion", ImmutableMap.of("type", "string", "pattern", "^[0-9a-f]{64}$"))
                                .put("refresh", ImmutableMap.of("type", "boolean"))
                                .put("page", ImmutableMap.of("type", "integer", "minimum", 1))
                                .put("startLine", ImmutableMap.of("type", "integer", "minimum", 1))
                                .put("endLine", ImmutableMap.of("type", "integer", "minimum", 1))
                                .put("target", ImmutableMap.of("type", "string", "maxLength", 200))
                                .put("ref", ImmutableMap.of("type", "string", "maxLength", MAX_EVIDENCE_REF))
                                .buildOrThrow(), ImmutableList.of("kind")),
                        arguments -> call(reviewId, turnId, arguments, this::getContext)),
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
                        arguments -> call(reviewId, turnId, arguments, this::saveSummary)),
                new ReviewTool("review_save_investigation",
                        "Save the change map, assessed changed-file paths, supporting evidence refs, and candidate claims before ending investigation. "
                                + "Candidates are private hypotheses numbered from 1, not findings. "
                                + "Only the assigned investigation or evidence-collection turn may seal this report. Evidence collection must answer every requested check.",
                        schema(ImmutableMap.of(
                                "operationKey", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", MAX_KEY),
                                "changeMap", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", MAX_TEXT),
                                "reviewedFiles", stringArray(1000, 1000),
                                "contextEvidenceRefs", stringArray(80, MAX_EVIDENCE_REF),
                                "candidates", stringArray(MAX_CANDIDATES, 2000),
                                "answers", ImmutableMap.of("type", "array", "maxItems", MAX_CANDIDATES, "items", schema(ImmutableMap.of(
                                        "candidate", ImmutableMap.of("type", "integer", "minimum", 1),
                                        "attempted", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", 2000),
                                        "evidenceRefs", stringArray(MAX_EVIDENCE, MAX_EVIDENCE_REF),
                                        "limitation", ImmutableMap.of("type", "string", "maxLength", 2000)), ImmutableList.of("candidate", "attempted", "evidenceRefs")))),
                                ImmutableList.of("operationKey", "changeMap", "reviewedFiles", "contextEvidenceRefs", "candidates", "answers")),
                        arguments -> call(reviewId, turnId, arguments, this::saveInvestigation)),
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
                                                                ImmutableMap.of("type", "string", "maxLength", MAX_EVIDENCE_REF),
                                                                "explanation",
                                                                ImmutableMap.of("type", "string", "maxLength", 800)),
                                                        "required",
                                                        ImmutableList.of("ref", "explanation"))))
                                .buildOrThrow(), ImmutableList.of("operationKey", "title", "body", "startAnchor", "evidence", "validation")),
                        arguments -> call(reviewId, turnId, arguments, this::saveFinding)),
                new ReviewTool("review_reply",
                        "Append your private answer to a discussion: target is \"summary\", a run-scoped cardId, or a findingId.",
                        schema(ImmutableMap.of(
                                "operationKey", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", MAX_KEY),
                                "target", ImmutableMap.of("type", "string", "minLength", 1),
                                "body", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", MAX_TEXT)), ImmutableList.of("operationKey", "target", "body")),
                        arguments -> call(reviewId, turnId, arguments, this::reply)),
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
                        arguments -> call(reviewId, turnId, arguments, this::withdraw)),
                new ReviewTool("review_finish",
                        "Seal this validation assignment. Assess every candidate as SUPPORTED, REJECTED, NEEDS_EVIDENCE, or UNRESOLVED. "
                                + "NEEDS_EVIDENCE requires a specific question, check, and resolving criterion; ByteQuay schedules the evidence turn. "
                                + "SUPPORTED binds a saved findingId and findingRevision. Report the investigationStep from the assignment context. "
                                + "complete=false and omissions are required for unsettled candidates or unreviewed scope.",
                        schema(ImmutableMap.of(
                                "operationKey", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", MAX_KEY),
                                "investigationStep", ImmutableMap.of("type", "integer", "minimum", 1),
                                "newCandidates", stringArray(MAX_CANDIDATES, 2000),
                                "complete", ImmutableMap.of("type", "boolean"),
                                "omissions", stringArray(50, 500),
                                "validations", ImmutableMap.of("type", "array", "maxItems", MAX_CANDIDATES, "items", validationSchema())),
                                ImmutableList.of("operationKey", "complete", "omissions", "validations")),
                        arguments -> call(reviewId, turnId, arguments, this::finish)));
    }

    private static Map<String, Object> validationSchema()
    {
        return schema(ImmutableMap.<String, Object>builder()
                .put("candidate", ImmutableMap.of("type", "integer", "minimum", 1))
                .put("outcome", ImmutableMap.of("type", "string", "enum", ImmutableList.of("SUPPORTED", "REJECTED", "NEEDS_EVIDENCE", "UNRESOLVED")))
                .put("conclusion", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", 2000))
                .put("evidenceRefs", stringArray(MAX_EVIDENCE, MAX_EVIDENCE_REF))
                .put("counterevidence", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", 2000))
                .put("findingId", ImmutableMap.of("type", "string", "minLength", 1))
                .put("findingRevision", ImmutableMap.of("type", "integer", "minimum", 1))
                .put("request", schema(ImmutableMap.of(
                        "question", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", 2000),
                        "check", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", 2000),
                        "resolvesWhen", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", 2000)), ImmutableList.of("question", "check", "resolvesWhen")))
                .buildOrThrow(), ImmutableList.of("candidate", "outcome", "conclusion", "evidenceRefs", "counterevidence"));
    }

    private static Map<String, Object> schema(Map<String, Object> properties, List<String> required)
    {
        return ImmutableMap.of("type", "object", "properties", properties, "required", required, "additionalProperties", false);
    }

    private static Map<String, Object> stringArray(int maximum, int length)
    {
        return ImmutableMap.of("type", "array", "maxItems", maximum, "items", ImmutableMap.of("type", "string", "minLength", 1, "maxLength", length));
    }

    private String call(String reviewId, Optional<TurnId> turnId, String arguments, Function<Call, JsonNode> handler)
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
        Lock lock = locks.lock(reviewId);
        lock.lock();
        try {
            AgentReview review = store.findReview(reviewId).orElseThrow(() -> new ReviewNotFoundException("Review not found: " + reviewId));
            if (review.state() != AgentReview.State.OPEN) {
                return error("review", "this review has ended; nothing can be recorded");
            }
            ReviewToolAssignment.Binding bound = assignments.resolve(reviewId, turnId);
            return handler.apply(new Call(review, bound.run(), bound.step(), bound.discussion(), (ObjectNode) parsed)).toString();
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
        writable(call);
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
        if (call.discussion().flatMap(ReviewMessage::findingId).isPresent()) {
            throw new ReviewInvalidRequestException("target: a finding discussion cannot replace the review summary");
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
        ReviewStep step = assigned(call, INVESTIGATE, COLLECT_EVIDENCE);
        if (!call.run().hasCompleteSummary()) {
            throw new ReviewInvalidRequestException("summary: save the intention and every actual commit before sealing the investigation");
        }
        List<String> files = strings(call.arguments(), "reviewedFiles", 1000, 1000);
        Set<String> allowedFiles = new HashSet<>(requiredFiles(call));
        call.run().diff().forEach(file -> allowedFiles.add(file.filename()));
        if (files.stream().distinct().count() != files.size() || !allowedFiles.containsAll(files)) {
            throw new ReviewInvalidRequestException("reviewedFiles: use distinct paths from this run's frozen diff or requiredReviewFiles");
        }
        List<String> refs = evidenceRefs(call, call.arguments(), "contextEvidenceRefs", 80);
        List<String> candidates = strings(call.arguments(), "candidates", MAX_CANDIDATES, 2000);
        List<String> previous = call.run().investigation().map(ReviewInvestigation::candidates).orElseGet(ImmutableList::of);
        if (step.kind() == COLLECT_EVIDENCE) {
            previous = ImmutableList.<String>builder().addAll(previous).addAll(lastValidation(call.run()).orElseThrow().newCandidates()).build();
        }
        if (step.kind() == COLLECT_EVIDENCE && (candidates.size() < previous.size() || !candidates.subList(0, previous.size()).equals(previous))) {
            throw new ReviewInvalidRequestException("candidates: preserve existing candidate text and numbering; append new hypotheses if needed");
        }
        Set<Integer> requested = new HashSet<>();
        if (step.kind() == COLLECT_EVIDENCE) {
            lastValidation(call.run()).orElseThrow().candidates().stream().filter(value -> value.outcome() == NEEDS_EVIDENCE)
                    .forEach(value -> requested.add(value.candidate()));
        }
        List<ReviewEvidenceAnswer> answers = new ArrayList<>();
        Set<Integer> answered = new HashSet<>();
        for (JsonNode item : array(call.arguments(), "answers", MAX_CANDIDATES)) {
            int candidate = integer(item, "candidate").orElseThrow(() -> new ReviewInvalidRequestException("candidate: required positive number"));
            if (!requested.contains(candidate) || !answered.add(candidate)) {
                throw new ReviewInvalidRequestException("answers: answer each requested candidate once; use the numbers from assignment context");
            }
            List<String> answerRefs = evidenceRefs(call, item, "evidenceRefs", MAX_EVIDENCE);
            Optional<String> limitation = Optional.of(text(item, "limitation", 2000, false)).filter(value -> !value.isEmpty());
            if (answerRefs.isEmpty() && limitation.isEmpty()) {
                throw new ReviewInvalidRequestException("answers: cite the result of the check or explain why evidence is unavailable in limitation");
            }
            answers.add(new ReviewEvidenceAnswer(candidate, text(item, "attempted", 2000, true), answerRefs, limitation));
        }
        if (!answered.equals(requested)) {
            throw new ReviewInvalidRequestException("answers: account for every requested check, including unavailable evidence");
        }
        ReviewInvestigation investigation = new ReviewInvestigation(text(call.arguments(), "changeMap", MAX_TEXT, true), files, refs, candidates, ImmutableList.of(), answers);
        ObjectNode result = objectMapper.createObjectNode().put("status", "accepted").put("candidates", candidates.size()).put("investigationStep", step.number())
                .put("next", "End this investigation turn; ByteQuay will start a separate validation turn");
        store.updateRun(call.run().withInvestigation(investigation, now()).replaceCurrentStep(step.withInvestigation(investigation), now())
                .withOperation(replay.key(), new ReviewOperation(replay.digest(), result.toString()), now()));
        return result;
    }

    private JsonNode saveFinding(Call call)
    {
        Replay replay = replay(call, "review_save_finding");
        if (replay.result().isPresent()) {
            return replay.result().orElseThrow();
        }
        writable(call);
        ObjectNode arguments = call.arguments();
        if (call.run().active()) {
            assigned(call, VALIDATE);
        }
        String validation = text(arguments, "validation", 2000, true);
        String title = text(arguments, "title", MAX_TITLE, true);
        String body = text(arguments, "body", MAX_TEXT, true);
        Optional<String> severity = Optional.ofNullable(arguments.get("severity")).filter(JsonNode::isTextual).map(JsonNode::asText)
                .map(value -> SEVERITIES.stream().filter(candidate -> candidate.equalsIgnoreCase(value)).findFirst()
                        .orElseThrow(() -> new ReviewInvalidRequestException("severity: use critical, high, medium or low")));
        List<ReviewEvidence> evidence = new ArrayList<>();
        for (JsonNode item : array(arguments, "evidence", MAX_EVIDENCE)) {
            String ref = text(item, "ref", MAX_EVIDENCE_REF, true);
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
            discussionTarget(call, findingId.orElseThrow());
            ReviewFinding existing = findings.stream().filter(finding -> finding.id().equals(findingId.orElseThrow())).findFirst()
                    .orElseThrow(() -> new ReviewInvalidRequestException("findingId: unknown finding " + findingId.orElseThrow()));
            int expected = integer(arguments, "expectedRevision").orElseThrow(() -> new ReviewInvalidRequestException("expectedRevision: required when revising a finding"));
            if (expected != existing.revision()) {
                throw new ReviewConflictException("expectedRevision: the finding is at revision " + existing.revision());
            }
            if (!existing.validatedHeadSha().equals(call.run().headSha())) {
                throw new ReviewConflictException("findingId: reassess this finding in a review of its current head before revising it");
            }
            if (existing.state() == ReviewFinding.State.WITHDRAWN) {
                throw new ReviewConflictException("findingId: the finding was withdrawn");
            }
            saved = existing.revised(title, body, severity, ImmutableList.copyOf(evidence), now());
            insert = false;
        }
        else {
            assigned(call, VALIDATE);
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
        discussionTarget(call, target);
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
        writable(call);
        ObjectNode arguments = call.arguments();
        String findingId = text(arguments, "findingId", 100, true);
        discussionTarget(call, findingId);
        if (call.run().active()) {
            assigned(call, VALIDATE);
        }
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
        writable(call);
        ObjectNode arguments = call.arguments();
        JsonNode complete = arguments.get("complete");
        if (complete == null || !complete.isBoolean()) {
            throw new ReviewInvalidRequestException("complete: required boolean");
        }
        List<String> omissions = strings(arguments, "omissions", 50, 500);
        if (complete.asBoolean() && !omissions.isEmpty()) {
            throw new ReviewInvalidRequestException("complete: a complete review cannot list omissions");
        }
        if (!complete.asBoolean() && omissions.isEmpty()) {
            throw new ReviewInvalidRequestException("omissions: explain the unreviewed scope or unresolved validation");
        }
        ReviewStep step = call.step().filter(value -> call.run().active())
                .orElseThrow(() -> new ReviewInvalidRequestException("validation: only the assigned review turn can report completion"));
        ReviewCoverage coverage = new ReviewCoverage(complete.asBoolean(), omissions, now());
        ObjectNode result = objectMapper.createObjectNode().put("status", "accepted").put("next", "End this turn; ByteQuay decides the next assignment");
        // An inaccessible initial scope can stop without inventing an investigation or a validation result.
        if (step.kind() != VALIDATE) {
            if (complete.asBoolean() || !array(arguments, "validations", MAX_CANDIDATES).isEmpty()) {
                throw new ReviewInvalidRequestException("validation: finish investigation before submitting a validated report");
            }
            store.updateRun(call.run().withCoverage(coverage, now()).withOperation(replay.key(), new ReviewOperation(replay.digest(), result.toString()), now()));
            return result;
        }
        int investigationStep = integer(arguments, "investigationStep")
                .orElseThrow(() -> new ReviewInvalidRequestException("investigationStep: required from assignment context"));
        if (investigationStep != investigationStep(call.run())) {
            throw new ReviewConflictException("investigationStep: the report must validate investigation " + investigationStep(call.run()));
        }
        ReviewInvestigation investigation = call.run().investigation().orElseThrow();
        List<String> newCandidates = arguments.has("newCandidates") ? strings(arguments, "newCandidates", MAX_CANDIDATES, 2000) : ImmutableList.of();
        int candidateCount = investigation.candidates().size() + newCandidates.size();
        if (candidateCount > MAX_CANDIDATES) {
            throw new ReviewInvalidRequestException("newCandidates: at most thirty total candidates per run");
        }
        List<ReviewCandidateValidation> validations = new ArrayList<>();
        Set<Integer> candidates = new HashSet<>();
        Set<String> retained = new HashSet<>();
        for (JsonNode item : array(arguments, "validations", MAX_CANDIDATES)) {
            int candidate = integer(item, "candidate").orElseThrow(() -> new ReviewInvalidRequestException("candidate: required positive number"));
            if (candidate > candidateCount || !candidates.add(candidate)) {
                throw new ReviewInvalidRequestException("validations: include each investigation candidate once, using its one-based number");
            }
            ReviewCandidateValidation.Outcome outcome;
            try {
                outcome = ReviewCandidateValidation.Outcome.valueOf(text(item, "outcome", 30, true));
            }
            catch (IllegalArgumentException e) {
                throw new ReviewInvalidRequestException("outcome: use SUPPORTED, REJECTED, NEEDS_EVIDENCE, or UNRESOLVED");
            }
            if (candidate > investigation.candidates().size() && outcome != NEEDS_EVIDENCE) {
                throw new ReviewInvalidRequestException("newCandidates: new hypotheses require NEEDS_EVIDENCE so ByteQuay can assign investigation");
            }
            List<String> refs = evidenceRefs(call, item, "evidenceRefs", MAX_EVIDENCE);
            if (outcome != UNRESOLVED && refs.isEmpty()) {
                throw new ReviewInvalidRequestException("evidenceRefs: cite the evidence supporting the verdict or identifying the remaining gap");
            }
            Optional<String> findingId = Optional.of(text(item, "findingId", 100, false)).filter(value -> !value.isEmpty());
            Optional<Integer> findingRevision = integer(item, "findingRevision");
            if ((outcome == SUPPORTED) != findingId.isPresent() || (outcome == SUPPORTED) != findingRevision.isPresent()) {
                throw new ReviewInvalidRequestException("findingId: only SUPPORTED requires both findingId and findingRevision");
            }
            findingId.ifPresent(id -> {
                ReviewFinding finding = store.findFinding(id).filter(value -> value.reviewId().equals(call.review().id()))
                        .orElseThrow(() -> new ReviewInvalidRequestException("findingId: must belong to this review"));
                if (finding.state() == ReviewFinding.State.WITHDRAWN || finding.outdated() || !finding.validatedHeadSha().equals(call.run().headSha())) {
                    throw new ReviewInvalidRequestException("findingId: supported findings must be current and not withdrawn");
                }
                if (findingRevision.orElseThrow() != finding.revision()) {
                    throw new ReviewConflictException("findingRevision: reassess the current finding revision " + finding.revision());
                }
                if (!refs.containsAll(finding.evidence().stream().map(ReviewEvidence::ref).collect(toImmutableList()))) {
                    throw new ReviewInvalidRequestException("evidenceRefs: assess all evidence cited by the supported finding");
                }
                retained.add(id);
            });
            Optional<ReviewEvidenceRequest> request = Optional.empty();
            if (outcome == NEEDS_EVIDENCE) {
                JsonNode value = item.path("request");
                request = Optional.of(new ReviewEvidenceRequest(text(value, "question", 2000, true), text(value, "check", 2000, true), text(value, "resolvesWhen", 2000, true)));
            }
            else if (item.hasNonNull("request")) {
                throw new ReviewInvalidRequestException("request: only NEEDS_EVIDENCE may request another check");
            }
            if (complete.asBoolean() && (outcome == NEEDS_EVIDENCE || outcome == UNRESOLVED)) {
                throw new ReviewInvalidRequestException("complete: unsettled candidates require complete=false and explicit omissions");
            }
            validations.add(new ReviewCandidateValidation(candidate, outcome, text(item, "conclusion", 2000, true), refs,
                    text(item, "counterevidence", 2000, true), findingId, findingRevision, request));
        }
        if (validations.size() != candidateCount) {
            throw new ReviewInvalidRequestException("validations: assess every candidate, including rejected and unresolved hypotheses");
        }
        if (complete.asBoolean()) {
            if (!call.run().hasCompleteSummary()) {
                throw new ReviewInvalidRequestException("summary: save the intention and each actual commit exactly once");
            }
            if (!investigation.reviewedFiles().containsAll(requiredFiles(call))) {
                throw new ReviewInvalidRequestException("reviewedFiles: account for every required changed file or finish with complete=false and omissions");
            }
            if (store.listFindings(call.review().id()).stream().anyMatch(finding -> finding.runId().equals(call.run().id())
                    && finding.state() != ReviewFinding.State.WITHDRAWN && !retained.contains(finding.id()))) {
                throw new ReviewInvalidRequestException("validations: map every new finding to a supported candidate or withdraw it");
            }
        }
        ReviewValidation validation = new ReviewValidation(investigationStep, validations, coverage, newCandidates);
        ReviewRun run = call.run().withInvestigation(investigation.withValidations(validations), now()).replaceCurrentStep(step.withValidation(validation), now());
        if (validations.stream().noneMatch(value -> value.outcome() == NEEDS_EVIDENCE)) {
            run = run.withCoverage(coverage, now());
        }
        store.updateRun(run.withOperation(replay.key(), new ReviewOperation(replay.digest(), result.toString()), now()));
        return result;
    }

    private static int investigationStep(ReviewRun run)
    {
        return run.steps().stream().filter(step -> step.investigation().isPresent()).reduce((first, last) -> last).orElseThrow().number();
    }

    private static Optional<ReviewValidation> lastValidation(ReviewRun run)
    {
        return run.steps().stream().flatMap(step -> step.validation().stream()).reduce((first, last) -> last);
    }

    private static void writable(Call call)
    {
        if (call.step().filter(ReviewStep::sealed).isPresent() || (call.run().active() && call.run().coverage().isPresent())) {
            throw new ReviewConflictException("assignment: the result is sealed; end this turn and wait for ByteQuay");
        }
        if (call.run().active() && call.step().isEmpty()) {
            throw new ReviewConflictException("assignment: this discussion cannot change the running review report");
        }
    }

    private static ReviewStep assigned(Call call, ReviewStep.Kind... kinds)
    {
        writable(call);
        return call.step().filter(step -> call.run().active() && Arrays.asList(kinds).contains(step.kind()))
                .orElseThrow(() -> new ReviewInvalidRequestException("validation: this tool is unavailable for the current assignment"));
    }

    private static void discussionTarget(Call call, String target)
    {
        call.discussion().ifPresent(message -> {
            String expected = message.findingId().or(() -> message.cardId()).orElse("summary");
            if (!expected.equals(target)) {
                throw new ReviewInvalidRequestException("target: this discussion is assigned to " + expected);
            }
        });
    }

    private static List<String> evidenceRefs(Call call, JsonNode arguments, String field, int maximum)
    {
        List<String> refs = strings(arguments, field, maximum, MAX_EVIDENCE_REF);
        if (refs.stream().distinct().count() != refs.size() || refs.stream().anyMatch(ref -> !call.run().evidence().containsKey(ref))) {
            throw new ReviewInvalidRequestException(field + ": use distinct evidence refs returned in this run");
        }
        return refs;
    }

    // ---- reads ----

    private JsonNode getContext(Call call)
    {
        ObjectNode arguments = call.arguments();
        String kind = text(arguments, "kind", 30, true).toLowerCase(Locale.ROOT);
        return switch (kind) {
            case "pr" -> pullRequest(call, integer(arguments, "number").orElse(call.review().number()));
            case "timeline" -> timeline(call, integer(arguments, "number").orElse(call.review().number()), page(arguments));
            case "checks" -> checks(call, integer(arguments, "number").orElse(call.review().number()), page(arguments));
            case "issue" -> issue(call, integer(arguments, "number").orElseThrow(() -> new ReviewInvalidRequestException("number: required for kind issue")), page(arguments));
            case "commit_prs" -> commitPullRequests(call);
            case "diff" -> diff(call);
            case "source" -> source(call, text(arguments, "path", 1000, true), integer(arguments, "startLine").orElse(1), integer(arguments, "endLine"));
            case "command_output" -> commandOutput(call);
            case "investigation" -> objectMapper.valueToTree(call.run().investigation().orElse(null));
            case "assignment" -> assignment(call);
            case "findings" -> findings(call);
            case "summary" -> summary(call);
            case "discussion" -> discussion(call, text(arguments, "target", 200, true));
            case "evidence" -> evidence(call, text(arguments, "ref", MAX_EVIDENCE_REF, true));
            default -> throw new ReviewInvalidRequestException("kind: unknown context kind " + kind);
        };
    }

    private JsonNode pullRequest(Call call, int number)
    {
        SelectedPullRequestReader.Read read = selectedPullRequest(call, number, SelectedPullRequestReader.Section.PR);
        PullRequestDetail detail = read.detail();
        ReviewRun run = call.run();
        String body = Optional.ofNullable(detail.body()).orElse("");
        ObjectNode node = pullRequestSource(call, number, read)
                .put("title", detail.title())
                .put("author", detail.author())
                .put("state", detail.state())
                .put("draft", detail.draft())
                .put("body", bounded(body, 8000))
                .put("bodyTruncated", body.length() > 8000);
        node.putPOJO("labels", detail.labels().stream().map(PullRequestDetail.Label::name).collect(toImmutableList()));
        node.put("evidenceRef", registerEvidence(call, call.review().repo() + "#" + number + " metadata", pullRequestProvenance(call, number, read),
                bounded(detail.title() + "\nAuthor: " + detail.author() + "; state: " + detail.state() + "; draft: " + detail.draft() + "\n" + body, MAX_EXCERPT)));
        if (number == call.review().number()) {
            node.put("baseRef", run.baseRef()).put("baseSha", run.baseSha()).put("mergeBaseSha", run.mergeBaseSha())
                    .put("headSha", run.headSha()).put("scope", run.scope().name());
            run.priorHeadSha().ifPresent(sha -> node.put("previousReviewedHead", sha));
            node.putPOJO("commits", run.commits());
            node.putPOJO("requiredReviewFiles", requiredFiles(call));
            node.set("assignment", assignment(call));
            node.putPOJO("changedFiles", run.diff().stream()
                    .map(file -> ImmutableMap.of("path", file.filename(), "status", file.status(), "additions", file.additions(), "deletions", file.deletions()))
                    .collect(toImmutableList()));
        }
        else {
            node.put("baseRef", detail.baseRef()).put("headRef", detail.headRef());
        }
        return node;
    }

    private JsonNode assignment(Call call)
    {
        ObjectNode node = objectMapper.createObjectNode();
        call.step().ifPresentOrElse(step -> {
            node.put("step", step.number()).put("kind", step.kind().name()).put("sealed", step.sealed());
            call.run().steps().stream().filter(value -> value.investigation().isPresent()).reduce((first, last) -> last)
                    .ifPresent(value -> node.put("investigationStep", value.number()));
            node.put("evidenceRoundsRemaining", 2 - call.run().steps().stream().filter(value -> value.kind() == COLLECT_EVIDENCE).count());
            lastValidation(call.run()).ifPresent(validation -> node.putPOJO("previousValidation", validation));
        }, () -> node.put("kind", "DISCUSSION"));
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
        return ReviewScopeFiles.required(git, workspaces.requireReadableRepository(call.review().workspaceId()).repository(), call.run(), previous);
    }

    private JsonNode timeline(Call call, int number, int page)
    {
        SelectedPullRequestReader.Read read = selectedPullRequest(call, number, SelectedPullRequestReader.Section.TIMELINE);
        PullRequestDetail detail = read.detail();
        List<PullRequestDetail.ActivityItem> items = detail.recentActivity();
        int from = (int) Math.min(items.size(), (long) (page - 1) * TIMELINE_PAGE);
        int to = Math.min(items.size(), from + TIMELINE_PAGE);
        ObjectNode node = pullRequestSource(call, number, read).put("page", page).put("pages", Math.max(1, (items.size() + TIMELINE_PAGE - 1) / TIMELINE_PAGE))
                .put("totalItems", items.size()).put("omittedItems", items.size() - (to - from)).put("truncated", from > 0 || to < items.size());
        if (detail.unavailableSections().contains("reviewThreads")) {
            node.put("note", "review threads were unavailable when the pull request was last refreshed");
        }
        String provenance = pullRequestProvenance(call, number, read);
        List<ObjectNode> entries = items.subList(from, to).stream().map(item -> {
            ObjectNode entry = objectMapper.createObjectNode()
                    .put("actor", bounded(Optional.ofNullable(item.actor()).orElse(""), 200))
                    .put("event", bounded(Optional.ofNullable(item.eventType()).orElse(""), 200))
                    .put("at", item.timestamp() == null ? "" : item.timestamp().toString());
            String body = Optional.ofNullable(item.body()).orElse("");
            entry.put("body", bounded(body, 2000));
            entry.put("bodyTruncated", body.length() > 2000);
            if (item.githubId() != null) {
                entry.put("id", item.githubId());
            }
            if (item.payload() != null && item.payload().path() != null) {
                entry.put("path", bounded(item.payload().path(), 1000));
                entry.put("line", item.payload().line() == null ? "" : item.payload().line().toString());
                entry.put("side", bounded(Optional.ofNullable(item.payload().side()).orElse(""), 20));
                entry.put("resolved", item.payload().resolved());
            }
            return entry;
        }).collect(toImmutableList());
        retainPageEvidence(call, call.review().repo() + "#" + number + " activity", ignored -> provenance, entries);
        node.putPOJO("items", entries);
        return node;
    }

    private JsonNode checks(Call call, int number, int page)
    {
        SelectedPullRequestReader.Read read = selectedPullRequest(call, number, SelectedPullRequestReader.Section.CHECKS);
        PullRequestDetail detail = read.detail();
        List<PullRequestDetail.CheckRun> checks = detail.checkRuns();
        int from = (int) Math.min(checks.size(), (long) (page - 1) * CHECK_PAGE);
        int to = Math.min(checks.size(), from + CHECK_PAGE);
        ObjectNode node = pullRequestSource(call, number, read).put("ciStatus", detail.ciStatus().name())
                .put("page", page).put("pages", Math.max(1, (checks.size() + CHECK_PAGE - 1) / CHECK_PAGE))
                .put("totalItems", checks.size()).put("omittedItems", checks.size() - (to - from)).put("truncated", from > 0 || to < checks.size());
        String provenance = pullRequestProvenance(call, number, read);
        List<ObjectNode> entries = checks.subList(from, to).stream().map(check -> {
            ObjectNode entry = objectMapper.createObjectNode()
                    .put("name", bounded(Optional.ofNullable(check.name()).orElse(""), 200))
                    .put("status", bounded(Optional.ofNullable(check.status()).orElse(""), 100))
                    .put("conclusion", bounded(Optional.ofNullable(check.conclusion()).orElse(""), 100))
                    .put("category", bounded(Optional.ofNullable(check.category()).orElse(""), 100))
                    .put("detailsUrl", bounded(Optional.ofNullable(check.detailsUrl()).orElse(""), 1000))
                    .put("startedAt", check.startedAt() == null ? "" : check.startedAt().toString())
                    .put("completedAt", check.completedAt() == null ? "" : check.completedAt().toString());
            if (check.githubId() != null) {
                entry.put("id", check.githubId());
            }
            return entry;
        }).collect(toImmutableList());
        retainPageEvidence(call, call.review().repo() + "#" + number + " check", ignored -> provenance, entries);
        node.putPOJO("checks", entries);
        return node;
    }

    private void retainPageEvidence(Call call, String source, Function<ObjectNode, String> provenance, List<ObjectNode> entries)
    {
        if (entries.isEmpty()) {
            return;
        }
        ReviewRun current = store.findRun(call.run().id()).orElse(call.run());
        for (ObjectNode entry : entries) {
            String ref = "E" + (current.evidence().size() + 1);
            current = current.withEvidence(ref, new ReviewEvidence(ref, source, provenance.apply(entry), bounded(entry.toString(), MAX_EXCERPT), ""), now());
            entry.put("evidenceRef", ref);
        }
        store.updateRun(current);
    }

    private JsonNode commitPullRequests(Call call)
    {
        List<String> shas = array(call.arguments(), "shas", SelectedCommitPullRequestReader.MAX_COMMITS).stream().map(value -> {
            if (!value.isTextual() || !value.asText().matches("[0-9a-fA-F]{40}|[0-9a-fA-F]{64}")) {
                throw new ReviewInvalidRequestException("shas: use full 40- or 64-character hexadecimal commit IDs");
            }
            return value.asText().toLowerCase(Locale.ROOT);
        }).collect(toImmutableList());
        if (shas.isEmpty() || shas.stream().distinct().count() != shas.size()) {
            throw new ReviewInvalidRequestException("shas: provide 1 to " + SelectedCommitPullRequestReader.MAX_COMMITS + " distinct full commit IDs");
        }
        WorkspaceRepositoryContext workspace = workspaces.requireReadableRepository(call.review().workspaceId());
        if (!workspace.workspaceId().equals(call.review().workspaceId()) || !workspace.monitoredRepository().fullName().equalsIgnoreCase(call.review().repo())) {
            throw new ReviewInvalidRequestException("shas: the workspace no longer identifies this review's monitored repository");
        }
        long repositoryId = workspace.selectedRepository().equals(workspace.monitoredRepository()) ? workspace.selectedRepositoryId()
                : workspace.upstreamRepositoryId().orElseThrow(() -> new ReviewInvalidRequestException("shas: the monitored repository ID is unavailable"));
        if (repositoryId <= 0) {
            throw new ReviewInvalidRequestException("shas: the monitored repository ID is unavailable");
        }
        SelectedCommitPullRequestReader.Read read = selectedCommitPullRequests.read(workspace, shas);
        if (read.items().size() != shas.size() || read.items().stream().anyMatch(item -> !shas.contains(item.sha()))
                || read.items().stream().map(SelectedCommitPullRequestReader.Item::sha).distinct().count() != read.items().size()) {
            throw new ReviewInvalidRequestException("shas: the selected association response does not match the requested commit IDs");
        }
        List<ObjectNode> items = read.items().stream().map(item -> objectMapper.createObjectNode()
                .put("sha", item.sha())
                .put("pullRequestNumber", item.pullRequestNumber().orElse(null))
                .put("pullRequestTitle", item.pullRequestTitle().map(title -> bounded(title, MAX_TITLE)).orElse(null))
                .put("pullRequestUrl", item.pullRequestUrl().map(url -> bounded(url, 2000)).orElse(null))
                .put("checkedAt", item.checkedAt().map(Instant::toString).orElse(null))
                .put("lookupAttempted", item.lookupAttempted()).put("lookupFailed", item.lookupFailed()).put("persisted", item.persisted())
                .put("status", item.pullRequestNumber().isPresent() ? "MAPPED" : item.lookupFailed() ? "LOOKUP_FAILED"
                        : item.checkedAt().isPresent() ? "CHECKED_NO_SINGLE_MERGED_PR" : "UNKNOWN"))
                .collect(toImmutableList());
        String limitation = "checkedAt can describe an earlier cached observation; status alone does not establish a fresh provider check. "
                + "A checked negative means no single merged PR under the existing association policy and can be ambiguous; "
                + "provider reads consider at most 100 candidates. Association is not proof of a whole PR final diff or why it merged.";
        Instant readAt = now();
        retainPageEvidence(call, call.review().repo() + " commit-to-PR association", item -> "Repository " + call.review().repo() + "; repositoryId=" + repositoryId
                + "; exactSha=" + item.path("sha").asText() + "; observation=" + (item.path("lookupAttempted").asBoolean() ? "selected lookup" : "stored enrichment read")
                + "; readAt=" + readAt + "; checkedAt=" + item.path("checkedAt").asText("unknown") + "; lookupAttempted=" + item.path("lookupAttempted").asBoolean()
                + "; lookupFailed=" + item.path("lookupFailed").asBoolean() + "; persisted=" + item.path("persisted").asBoolean() + "; " + limitation, items);
        List<String> gaps = read.gaps().stream().limit(MAX_COMMIT_ASSOCIATION_GAPS).map(gap -> bounded(gap, 1000)).collect(toImmutableList());
        ObjectNode node = objectMapper.createObjectNode().put("repository", call.review().repo()).put("repositoryId", repositoryId)
                .put("readAt", readAt.toString()).put("maxCommits", SelectedCommitPullRequestReader.MAX_COMMITS).put("maxProviderCandidates", 100)
                .put("source", "selected-commit-pull-request").put("note", limitation).put("omittedGaps", read.gaps().size() - gaps.size());
        node.putPOJO("items", items);
        node.putPOJO("gaps", gaps);
        return node;
    }

    private SelectedPullRequestReader.Read selectedPullRequest(Call call, int number, SelectedPullRequestReader.Section section)
    {
        SelectedPullRequestReader.Read read = selectedPullRequests.read(call.review().repo(), number, section);
        if (!read.detail().repo().equalsIgnoreCase(call.review().repo()) || read.detail().number() != number) {
            throw new ReviewInvalidRequestException("number: the selected pull request response has a different repository or number");
        }
        return read;
    }

    private ObjectNode pullRequestSource(Call call, int number, SelectedPullRequestReader.Read read)
    {
        ObjectNode freshness = objectMapper.createObjectNode()
                .put("sourceUpdatedAt", read.detail().updatedAt() == null ? null : read.detail().updatedAt().toString())
                .put("rowUpdatedAt", read.rowUpdatedAt().map(Instant::toString).orElse(null))
                .put("cacheUpdatedAt", read.cacheUpdatedAt().map(Instant::toString).orElse(null))
                .put("readAt", now().toString())
                .put("refreshAttempted", read.refreshAttempted()).put("refreshFailed", read.refreshFailed())
                .put("stale", read.stale()).put("incomplete", read.incomplete())
                .put("observedHeadSha", read.observedHeadSha().orElse(null))
                .put("note", "incomplete describes the requested section; unavailableSections lists all known cache gaps. Cache freshness does not establish remote currentness "
                        + "or complete historical discussion. cacheUpdatedAt may reflect a status-only update.");
        freshness.putPOJO("unavailableSections", read.detail().unavailableSections());
        if (number == call.review().number()) {
            read.observedHeadSha().ifPresentOrElse(head -> freshness.put("matchesReviewedHead", head.equals(call.run().headSha())),
                    () -> freshness.putNull("matchesReviewedHead"));
        }
        ObjectNode node = objectMapper.createObjectNode().put("repository", call.review().repo()).put("number", number)
                .put("pullRequest", true).put("source", "selected-pull-request");
        node.set("freshness", freshness);
        return node;
    }

    private String pullRequestProvenance(Call call, int number, SelectedPullRequestReader.Read read)
    {
        String provenance = "Selected pull request " + call.review().repo() + "#" + number + " read by ByteQuay at " + now()
                + "; sourceUpdatedAt=" + read.detail().updatedAt() + "; rowUpdatedAt=" + read.rowUpdatedAt().map(Instant::toString).orElse("unknown")
                + "; cacheUpdatedAt=" + read.cacheUpdatedAt().map(Instant::toString).orElse("unknown")
                + "; observedHeadSha=" + read.observedHeadSha().orElse("unknown")
                + "; refreshAttempted=" + read.refreshAttempted() + "; refreshFailed=" + read.refreshFailed()
                + "; stale=" + read.stale() + "; incomplete=" + read.incomplete() + "; unavailableSections=" + read.detail().unavailableSections()
                + "; cache freshness is not remote currentness or complete discussion evidence; cacheUpdatedAt may reflect a status-only update";
        return number == call.review().number() ? provenance + "; frozenReviewedHead=" + call.run().headSha() : provenance + "; related historical source";
    }

    private JsonNode issue(Call call, int number, int page)
    {
        if (pullRequests.findStored(call.review().repo(), number).isPresent()) {
            return pullRequest(call, number);
        }
        if (workspaces.requireReadableRepository(call.review().workspaceId()).monitoredRepository().fullName().equalsIgnoreCase(call.review().repo())) {
            Optional<RepositoryIssue> cached = issues.find(call.review().workspaceId(), number);
            if (cached.isPresent()) {
                return cachedIssue(call, cached.orElseThrow(), page);
            }
        }
        String[] parts = call.review().repo().split("/", 2);
        GitHubIssuePreview preview = repositories.getIssuePreview(parts[0], parts[1], number);
        ObjectNode node = issueNode(call, number, preview, "github-selected-preview",
                "GitHub " + (preview.pullRequest() ? "pull request" : "issue") + " selected preview read by ByteQuay at " + now());
        node.set("activity", objectMapper.createObjectNode().put("missing", true).put("completeness", "unavailable")
                .put("note", "The selected issue preview does not include activity"));
        return node;
    }

    private JsonNode cachedIssue(Call call, RepositoryIssue issue, int page)
    {
        String workspace = call.review().workspaceId();
        Optional<Instant> syncedAt = issues.list(workspace, 0, 1).syncedAt();
        RepositoryIssueDetail detail = issues.detail(workspace, issue.number());
        boolean refreshFailed = false;
        if (detail.refreshedAt().isEmpty() || detail.refreshedAt().orElseThrow().isBefore(issue.updatedAt())) {
            try {
                detail = issueRefresh.refreshDetail(workspace, issue.number());
            }
            catch (RuntimeException ignored) {
                refreshFailed = true;
            }
        }
        String provenance = "Cached open issue projection; synced at " + syncedAt.map(Instant::toString).orElse("unknown")
                + "; GitHub issue updated at " + issue.updatedAt();
        GitHubIssuePreview preview = new GitHubIssuePreview(issue.title(), issue.body(), issue.author(), issue.labels(), issue.updatedAt(), "open", false);
        ObjectNode node = issueNode(call, issue.number(), preview, "workspace-issue-cache", provenance);
        syncedAt.ifPresentOrElse(at -> node.put("projectionSyncedAt", at.toString()), () -> node.putNull("projectionSyncedAt"));
        node.put("commentCount", issue.commentCount());
        ObjectNode activity = objectMapper.createObjectNode()
                .put("page", page)
                .put("pages", Math.max(1, (detail.timeline().size() + TIMELINE_PAGE - 1) / TIMELINE_PAGE))
                .put("totalItems", detail.timeline().size())
                .put("missing", detail.refreshedAt().isEmpty())
                .put("stale", detail.refreshedAt().isEmpty() || detail.refreshedAt().orElseThrow().isBefore(issue.updatedAt()))
                .put("refreshFailed", refreshFailed)
                .put("completeness", "unknown")
                .put("note", refreshFailed ? "Selected activity refresh failed; retained cached activity may be stale or incomplete"
                        : "The issue activity cache does not record upstream pagination completeness");
        detail.refreshedAt().ifPresentOrElse(at -> activity.put("refreshedAt", at.toString()), () -> activity.putNull("refreshedAt"));
        int from = (int) Math.min(detail.timeline().size(), (long) (page - 1) * TIMELINE_PAGE);
        int to = Math.min(detail.timeline().size(), from + TIMELINE_PAGE);
        activity.put("omittedItems", detail.timeline().size() - (to - from));
        activity.put("truncated", from > 0 || to < detail.timeline().size());
        String activityProvenance = provenance + "; activity refreshed at " + detail.refreshedAt().map(Instant::toString).orElse("unknown");
        activity.set("items", objectMapper.valueToTree(detail.timeline().subList(from, to).stream()
                .map(item -> issueActivity(call, issue.number(), item, activityProvenance)).collect(toImmutableList())));
        node.set("activity", activity);
        return node;
    }

    private ObjectNode issueActivity(Call call, int number, PrTimelineEvent item, String provenance)
    {
        String body = Optional.ofNullable(item.body()).orElse("");
        String excerpt = bounded(body, 2000);
        ObjectNode node = objectMapper.createObjectNode()
                .put("event", item.event())
                .put("actor", item.actor())
                .put("at", item.timestamp() == null ? "" : item.timestamp().toString())
                .put("body", excerpt)
                .put("bodyTruncated", body.length() > 2000);
        if (item.githubId() != null) {
            node.put("id", item.githubId());
        }
        node.put("evidenceRef", registerEvidence(call, call.review().repo() + "#" + number + " activity",
                provenance, item.actor() + " " + item.event() + " at " + node.path("at").asText() + "\n" + excerpt));
        return node;
    }

    private ObjectNode issueNode(Call call, int number, GitHubIssuePreview preview, String source, String provenance)
    {
        String excerpt = bounded(preview.body(), MAX_EXCERPT);
        String ref = registerEvidence(
                call,
                call.review().repo() + "#" + number + ": " + preview.title(),
                provenance + "; GitHub object updated at " + preview.updatedAt(),
                excerpt);
        ObjectNode node = objectMapper.createObjectNode()
                .put("number", number)
                .put("title", preview.title())
                .put("state", preview.state())
                .put("author", preview.author())
                .put("pullRequest", preview.pullRequest())
                .put("body", excerpt)
                .put("bodyTruncated", preview.body().length() > MAX_EXCERPT)
                .put("updatedAt", preview.updatedAt().toString())
                .put("source", source)
                .put("readAt", now().toString())
                .put("evidenceRef", ref);
        node.putPOJO("labels", preview.labels());
        return node;
    }

    private JsonNode diff(Call call)
    {
        ObjectNode arguments = call.arguments();
        int number = integer(arguments, "number").orElse(call.review().number());
        int page = page(arguments);
        JsonNode refreshValue = arguments.get("refresh");
        if (refreshValue != null && !refreshValue.isBoolean()) {
            throw new ReviewInvalidRequestException("refresh: must be a boolean");
        }
        boolean refresh = refreshValue != null && refreshValue.booleanValue();
        if (number == call.review().number()) {
            if (arguments.has("diffVersion")) {
                throw new ReviewInvalidRequestException("diffVersion: unavailable for the current review's frozen diff");
            }
            if (refresh) {
                throw new ReviewInvalidRequestException("refresh: the current review's frozen diff cannot be refreshed");
            }
            return diff(call, Optional.ofNullable(arguments.get("path")).filter(JsonNode::isTextual).map(JsonNode::asText), page);
        }
        if (arguments.has("revision")) {
            throw new ReviewInvalidRequestException("revision: related diffs use the selected PR's default artifact and diffVersion");
        }
        Optional<String> path = Optional.ofNullable(arguments.get("path")).map(value -> {
            if (!value.isTextual() || value.textValue().isEmpty() || value.textValue().length() > 1000) {
                throw new ReviewInvalidRequestException("path: must be non-empty text of at most 1000 characters");
            }
            return value.textValue();
        });
        Optional<String> version = Optional.ofNullable(arguments.get("diffVersion")).map(value -> {
            if (!value.isTextual() || !value.textValue().matches("[0-9a-f]{64}")) {
                throw new ReviewInvalidRequestException("diffVersion: must be the lowercase SHA-256 version returned by the initial diff index");
            }
            return value.textValue();
        });
        if ((path.isPresent() || page > 1) && version.isEmpty()) {
            throw new ReviewInvalidRequestException("diffVersion: read the initial diff index first; a version is required for paths and later pages");
        }
        if (refresh && (path.isPresent() || page != 1 || version.isPresent())) {
            throw new ReviewInvalidRequestException("refresh: only the initial related diff index without diffVersion can refresh");
        }
        SelectedPullRequestDiffReader.Read read;
        try {
            read = selectedPullRequestDiffs.read(call.review().repo(), number, version, refresh);
        }
        catch (ResponseStatusException failure) {
            if (failure.getStatusCode().equals(HttpStatus.CONFLICT)) {
                throw new ReviewConflictException("diffVersion: source changed or disappeared; restart with the initial related diff index");
            }
            if (failure.getStatusCode().equals(HttpStatus.NOT_FOUND)) {
                throw new ReviewInvalidRequestException("number: selected pull request diff is unavailable");
            }
            throw failure;
        }
        ObjectNode node = objectMapper.createObjectNode()
                .put("source", "selected-pull-request-diff").put("repository", call.review().repo()).put("number", number)
                .put("diffVersion", read.version()).put("readAt", read.readAt().toString())
                .put("freshnessKnown", false).put("completeKnown", false).put("finalMergedDiffVerified", false)
                .put("refreshAttempted", read.refreshAttempted()).put("refreshFailed", read.refreshFailed())
                .put("observedHeadSha", read.observedHeadSha().orElse(null))
                .put("sourceUpdatedAt", read.sourceUpdatedAt().map(Instant::toString).orElse(null))
                .put("reportedChangedFiles", read.reportedChangedFiles().orElse(null))
                .put("metadataVersionLinked", false).put("page", page);
        node.putPOJO("gaps", read.gaps().stream().limit(MAX_RELATED_DIFF_GAPS).map(gap -> bounded(gap, 1000)).collect(toImmutableList()));
        node.put("omittedGaps", Math.max(0, read.gaps().size() - MAX_RELATED_DIFF_GAPS));
        if (path.isEmpty()) {
            int from = (int) Math.min(read.files().size(), (long) (page - 1) * RELATED_DIFF_FILES_PAGE);
            int to = Math.min(read.files().size(), from + RELATED_DIFF_FILES_PAGE);
            node.put("pages", Math.max(1, (read.files().size() + (long) RELATED_DIFF_FILES_PAGE - 1) / RELATED_DIFF_FILES_PAGE))
                    .put("totalFiles", read.files().size()).put("pageSize", RELATED_DIFF_FILES_PAGE)
                    .put("note", "Metadata-only index of the observed cached artifact; no completeness or final-merge proof. "
                            + "Use diffVersion on path reads and later pages. Separately observed metadata is unverified; absent metadata is unknown.");
            node.putPOJO("files", read.files().subList(from, to).stream().map(file -> {
                boolean readable = !file.filename().isEmpty() && file.filename().length() <= 1000;
                ObjectNode entry = objectMapper.createObjectNode().put("status", bounded(file.status(), MAX_TITLE))
                        .put("additions", file.additions()).put("deletions", file.deletions()).put("patchAvailable", file.patch() != null)
                        .put("pathReadable", readable).put("pathTruncated", file.filename().length() > 1000);
                if (readable) {
                    entry.put("path", file.filename());
                }
                else {
                    entry.put("pathPreview", bounded(file.filename(), 1000));
                }
                return entry;
            }).collect(toImmutableList()));
            return node;
        }
        DiffFile file = read.files().stream().filter(candidate -> candidate.filename().equals(path.orElseThrow())).findFirst()
                .orElseThrow(() -> new ReviewInvalidRequestException("path: not present in this selected PR diff artifact"));
        node.put("path", file.filename()).put("status", bounded(file.status(), MAX_TITLE)).put("patchAvailable", file.patch() != null)
                .put("format", "Exact raw patch fragment; may start or end mid-line. This related source has no current-review inline anchors.");
        if (file.patch() == null) {
            return node.putNull("patch").put("note", "Patch unavailable; this is not evidence that the file has no changes.");
        }
        String patch = file.patch();
        int total = patch.codePointCount(0, patch.length());
        int from = (int) Math.min(total, (long) (page - 1) * RELATED_DIFF_CODE_POINTS_PAGE);
        int to = (int) Math.min(total, (long) from + RELATED_DIFF_CODE_POINTS_PAGE);
        String fragment = patch.substring(patch.offsetByCodePoints(0, from), patch.offsetByCodePoints(0, to));
        node.put("pages", Math.max(1, (total + (long) RELATED_DIFF_CODE_POINTS_PAGE - 1) / RELATED_DIFF_CODE_POINTS_PAGE))
                .put("totalCodePoints", total).put("startCodePoint", from).put("endCodePoint", to).put("patch", fragment);
        String provenance = "Selected default PR diff " + call.review().repo() + "#" + number + "; diffVersion=" + read.version()
                + "; page=" + page + "; readAt=" + read.readAt() + "; exact returned raw patch fragment; may split lines. "
                + "Content identity is not a verified Git range, current freshness, completeness, final merged diff or merge rationale.";
        node.put("evidenceRef", registerEvidence(call, call.review().repo() + "#" + number + " " + file.filename(), provenance, fragment));
        return node;
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
        String key = call.step().map(step -> "review-step:" + call.run().id() + ":" + step.number() + ":").orElse("") + tool + ":" + operationKey;
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
        if (!value.isIntegralNumber() || !value.canConvertToInt() || value.asInt() < 1) {
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

    private record Call(AgentReview review, ReviewRun run, Optional<ReviewStep> step, Optional<ReviewMessage> discussion, ObjectNode arguments) {}

    private record Replay(String key, String digest, Optional<JsonNode> result) {}
}
