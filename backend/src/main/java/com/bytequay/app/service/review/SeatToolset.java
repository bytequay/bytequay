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

import com.bytequay.app.domain.ReviewFinding;
import com.bytequay.app.domain.ReviewFindingSeverity;
import com.bytequay.app.domain.ReviewFindingStatus;
import com.bytequay.app.domain.ReviewPass;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.ReviewStore;
import com.bytequay.app.service.agents.ToolCall;
import com.bytequay.app.service.agents.ToolExecutor;
import com.bytequay.app.service.agents.TurnSpec;
import com.bytequay.app.service.credentials.PatResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import static com.bytequay.app.utils.PullRequestRefUtil.parseRef;
import static java.util.Objects.requireNonNull;

/**
 * The READ-ONLY tool surface a reviewer seat gets: read the diff, read
 * file content at the head SHA, search the diff text, and report
 * findings. Anything else — write tools, git, publish, the lead's
 * orchestration verbs — returns a structured
 * {@code tool_not_available_to_reviewer} error (422 semantics) so a
 * seat can never mutate code, GitHub, or the pass flow. That refusal
 * is part of the review safety wall; widen this catalog only with the
 * publish-gate tests green.
 */
@Component
public class SeatToolset
{
    private static final Logger log = LoggerFactory.getLogger(SeatToolset.class);

    /** Bound on one {@code get_pr_diff} / {@code get_file_content}
     *  result so a single tool call can't blow the seat's context. */
    private static final int MAX_TOOL_RESULT_CHARS = 60_000;
    private static final int MAX_SEARCH_MATCHES = 50;
    private static final int DEFAULT_BLOB_LINES = 400;

    private final ReviewStore reviewStore;
    private final ReviewDiffCache diffCache;
    private final PullRequestRepository pullRequests;
    private final PatResolver patResolver;
    private final ObjectMapper mapper;

    public SeatToolset(
            ReviewStore reviewStore,
            ReviewDiffCache diffCache,
            PullRequestRepository pullRequests,
            PatResolver patResolver,
            ObjectMapper mapper)
    {
        this.reviewStore = requireNonNull(reviewStore, "reviewStore is null");
        this.diffCache = requireNonNull(diffCache, "diffCache is null");
        this.pullRequests = requireNonNull(pullRequests, "pullRequests is null");
        this.patResolver = requireNonNull(patResolver, "patResolver is null");
        this.mapper = requireNonNull(mapper, "mapper is null");
    }

    /** The seat catalog rendered for the seat's transport. */
    public ArrayNode toolsArray(TurnSpec.Transport transport)
    {
        return ReviewToolSchemas.render(mapper, transport, List.of(
                ReviewToolSchemas.GET_PR_DIFF,
                ReviewToolSchemas.GET_FILE_CONTENT,
                ReviewToolSchemas.SEARCH_CODE,
                ReviewToolSchemas.REPORT_FINDING));
    }

    /** Executor bound to one pass + one seat. {@code reporterLabel}
     *  prefixes reported findings so attribution survives without a
     *  schema change (same convention as disputed-finding bodies). */
    public ToolExecutor executorFor(ReviewPass pass, String participantId, String reporterLabel)
    {
        requireNonNull(pass, "pass is null");
        requireNonNull(participantId, "participantId is null");
        return call -> dispatch(pass, participantId, reporterLabel, call);
    }

    private ToolExecutor.ToolCallResult dispatch(
            ReviewPass pass, String participantId, String reporterLabel, ToolCall call)
    {
        try {
            return switch (call.name()) {
                case "get_pr_diff" -> getPrDiff(pass, call);
                case "get_file_content" -> getFileContent(pass, call);
                case "search_code" -> searchCode(pass, call);
                case "report_finding" -> reportFinding(pass, reporterLabel, call);
                default -> refused(call.name());
            };
        }
        catch (RuntimeException e) {
            log.warn("Seat tool {} failed on pass {} (seat {}): {}",
                    call.name(), pass.id(), participantId, e.getMessage());
            return ToolExecutor.ToolCallResult.error(
                    "Tool '" + call.name() + "' failed: " + e.getMessage());
        }
    }

    /** The structured 422-style refusal for anything outside the
     *  read-only catalog. */
    private ToolExecutor.ToolCallResult refused(String toolName)
    {
        ObjectNode err = mapper.createObjectNode();
        err.put("error", "tool_not_available_to_reviewer");
        err.put("status", 422);
        err.put("tool", toolName);
        err.put("hint", "Reviewer seats are read-only: get_pr_diff, get_file_content, "
                + "search_code, report_finding.");
        return ToolExecutor.ToolCallResult.error(err.toString());
    }

    ToolExecutor.ToolCallResult getPrDiff(ReviewPass pass, ToolCall call)
    {
        String diff = diffCache.diffFor(pass);
        String path = call.input().path("path").asText("");
        if (!path.isBlank()) {
            String slice = ReviewPassService.splitDiffByFile(diff).stream()
                    .filter(chunk -> chunk.contains(" a/" + path) || chunk.contains(" b/" + path))
                    .findFirst()
                    .orElse(null);
            if (slice == null) {
                return ToolExecutor.ToolCallResult.error(
                        "No file '" + path + "' in this PR's diff.");
            }
            return ToolExecutor.ToolCallResult.ok(truncate(slice));
        }
        return ToolExecutor.ToolCallResult.ok(truncate(diff));
    }

    ToolExecutor.ToolCallResult getFileContent(ReviewPass pass, ToolCall call)
    {
        String path = call.input().path("path").asText("");
        if (path.isBlank()) {
            return ToolExecutor.ToolCallResult.error("'path' is required.");
        }
        if (pass.headSha() == null || pass.headSha().isBlank()) {
            return ToolExecutor.ToolCallResult.error(
                    "The pass has no head SHA yet; file content is unavailable.");
        }
        String pat = patResolver.resolve(pass.repoFullName());
        List<String> lines = pullRequests.fetchFileBlobLines(
                pat, parseRef(pass.repoFullName(), pass.prNumber()).repoRef(),
                path, pass.headSha());
        if (lines == null || lines.isEmpty()) {
            return ToolExecutor.ToolCallResult.error(
                    "File '" + path + "' is empty or not found at " + pass.headSha() + ".");
        }
        int start = Math.max(1, call.input().path("start_line").asInt(1));
        int end = call.input().path("end_line").asInt(Math.min(
                lines.size(), start + DEFAULT_BLOB_LINES - 1));
        end = Math.min(end, lines.size());
        StringBuilder out = new StringBuilder();
        for (int i = start; i <= end; i++) {
            out.append(i).append(": ").append(lines.get(i - 1)).append('\n');
        }
        return ToolExecutor.ToolCallResult.ok(truncate(out.toString()));
    }

    ToolExecutor.ToolCallResult searchCode(ReviewPass pass, ToolCall call)
    {
        String query = call.input().path("query").asText("");
        if (query.isBlank()) {
            return ToolExecutor.ToolCallResult.error("'query' is required.");
        }
        String diff = diffCache.diffFor(pass);
        StringBuilder out = new StringBuilder();
        int matches = 0;
        String currentFile = "(unknown)";
        for (String line : diff.split("\n", -1)) {
            if (line.startsWith("diff --git ")) {
                int bIdx = line.indexOf(" b/");
                currentFile = bIdx > 0 ? line.substring(bIdx + 3) : line;
                continue;
            }
            if (line.toLowerCase(Locale.ROOT).contains(query.toLowerCase(Locale.ROOT))) {
                out.append(currentFile).append(": ").append(line).append('\n');
                if (++matches >= MAX_SEARCH_MATCHES) {
                    out.append("… capped at ").append(MAX_SEARCH_MATCHES).append(" matches\n");
                    break;
                }
            }
        }
        if (matches == 0) {
            return ToolExecutor.ToolCallResult.ok(
                    "No matches for '" + query + "' in this PR's diff.");
        }
        return ToolExecutor.ToolCallResult.ok(truncate(out.toString()));
    }

    ToolExecutor.ToolCallResult reportFinding(ReviewPass pass, String reporterLabel, ToolCall call)
    {
        String summary = call.input().path("summary").asText("");
        if (summary.isBlank()) {
            return ToolExecutor.ToolCallResult.error("'summary' is required.");
        }
        String path = call.input().path("path").asText(null);
        Integer line = call.input().has("line") && call.input().path("line").asInt(0) > 0
                ? call.input().path("line").asInt()
                : null;
        ReviewFinding finding = new ReviewFinding(
                UUID.randomUUID().toString(),
                pass.id(),
                path, line,
                severityFrom(call.input().path("severity").asText("")),
                ReviewFindingStatus.REPORTED,
                // Reporter attribution rides as a body prefix — same
                // convention as disputed-finding bodies, no new column.
                "[" + (reporterLabel == null ? "panel" : reporterLabel) + "] " + summary,
                /* resolution */ null,
                /* postedCommentId */ null,
                Instant.now());
        reviewStore.saveFinding(finding);
        ObjectNode ok = mapper.createObjectNode();
        ok.put("finding_id", finding.id());
        ok.put("status", "reported");
        return ToolExecutor.ToolCallResult.ok(ok.toString());
    }

    /** Map the model's severity word to the enum, accepting both this
     *  flow's vocabulary (blocker/major/nit/question) and the one-shot
     *  reviewer's (info/suggestion/warning/blocker). */
    static ReviewFindingSeverity severityFrom(String raw)
    {
        if (raw == null) {
            return ReviewFindingSeverity.MAJOR;
        }
        return switch (raw.toLowerCase(Locale.ROOT)) {
            case "blocker" -> ReviewFindingSeverity.BLOCKER;
            case "major", "warning" -> ReviewFindingSeverity.MAJOR;
            case "nit", "info", "suggestion" -> ReviewFindingSeverity.NIT;
            case "question" -> ReviewFindingSeverity.QUESTION;
            default -> ReviewFindingSeverity.MAJOR;
        };
    }

    private static String truncate(String text)
    {
        if (text.length() <= MAX_TOOL_RESULT_CHARS) {
            return text;
        }
        return text.substring(0, MAX_TOOL_RESULT_CHARS) + "\n… [truncated]";
    }
}
