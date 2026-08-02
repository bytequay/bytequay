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
package com.bytequay.app.developmentflow.compatibility;

import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.PRCheck;
import com.bytequay.app.domain.PRTimelineEntry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

import static java.util.Objects.requireNonNull;

/**
 * Replays immutable V2 lifecycle facts into the existing PR timeline wire
 * model. It never writes compatibility rows, so repeated reads and restarts
 * cannot duplicate lifecycle history, including terminal failed review
 * attempts that precede a successful retry.
 */
@Component
public final class SqliteV2PrTimelineProjection
        implements V2PrTimelineProjection
{
    private static final String ACTOR_LOCAL = "v2-local-runtime";
    private static final String ACTOR_PUBLISH = "v2-publish-runtime";
    private static final String ACTOR_REMOTE = "v2-remote-runtime";
    private static final String ACTOR_CI_REPAIR = "ci-fix";
    private static final String ACTOR_MERGE = "v2-merge-runtime";
    private static final String ACTOR_CLEANUP = "v2-cleanup-runtime";

    private final JdbcTemplate jdbc;
    private final ObjectMapper json;

    public SqliteV2PrTimelineProjection(JdbcTemplate jdbc, ObjectMapper json)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.json = requireNonNull(json, "json is null");
    }

    @Override
    public List<PRTimelineEntry> project(PR pr, List<PRTimelineEntry> stored)
    {
        requireNonNull(pr, "pr is null");
        requireNonNull(stored, "stored is null");
        if (pr.taskId() == null) {
            return List.copyOf(stored);
        }

        Map<String, PRTimelineEntry> events = new LinkedHashMap<>();
        stored.forEach(event -> events.put(event.id(), event));

        projectDevelopmentCommits(pr, events);
        projectBrainReviews(pr, events);
        projectFirstPush(pr, events);
        projectCiRepairs(pr, events);
        projectMarkReady(pr, events);
        projectMerge(pr, events);
        projectCleanup(pr, events);

        return events.values().stream()
                .sorted(Comparator.comparing(PRTimelineEntry::createdAt)
                        .thenComparingInt(this::semanticOrder)
                        .thenComparing(PRTimelineEntry::id))
                .toList();
    }

    private void projectDevelopmentCommits(
            PR pr, Map<String, PRTimelineEntry> events)
    {
        for (DevelopmentCommit commit : jdbc.query("""
                SELECT id, revision, head_sha, commit_summary, created_at_ms
                FROM dev_report
                WHERE task_id = ? AND workflow_version = 'V2'
                  AND head_sha IS NOT NULL
                ORDER BY revision, created_at_ms, id
                """, (rs, row) -> new DevelopmentCommit(
                rs.getString("id"), rs.getInt("revision"),
                rs.getString("head_sha"), rs.getString("commit_summary"),
                rs.getLong("created_at_ms")), pr.taskId())) {
            if (hasCommit(events, commit.headSha())) {
                continue;
            }
            String message = commit.summary();
            if (message == null || message.isBlank()) {
                message = "Development revision " + commit.revision();
            }
            put(events, entry(
                    "v2:dev-commit:" + commit.id(), pr,
                    PRTimelineEntry.TYPE_COMMIT, ACTOR_LOCAL, false,
                    commit.at(), payload(
                            "sha", commit.headSha(), "message", message)));
        }
    }

    private void projectBrainReviews(PR pr, Map<String, PRTimelineEntry> events)
    {
        List<BrainReview> reviews = jdbc.query("""
                SELECT episode.id, episode.semantic_attempt, episode.status,
                       episode.verdict, episode.unresolved_finding_count,
                       episode.verdict_summary, episode.requested_at_ms,
                       episode.completed_at_ms, episode.error_message,
                       (SELECT json_extract(json_extract(execution.raw_result,
                                   '$.payloadJson'), '$.finalText')
                          FROM dispatch_ticket ticket
                          JOIN agent_execution execution
                            ON execution.ticket_id = ticket.id
                         WHERE ticket.owner_kind = 'TASK_TURN'
                           AND ticket.owner_id = episode.task_turn_id
                           AND execution.status = 'SUCCEEDED'
                           AND execution.raw_result IS NOT NULL
                         ORDER BY execution.infrastructure_attempt DESC
                         LIMIT 1) AS final_text
                FROM brain_review_episode episode
                WHERE episode.task_id = ?
                ORDER BY episode.requested_at_ms, episode.id
                """, (rs, row) -> new BrainReview(
                rs.getString("id"), rs.getInt("semantic_attempt"),
                rs.getString("status"), rs.getString("verdict"),
                rs.getInt("unresolved_finding_count"),
                rs.getString("verdict_summary"),
                rs.getLong("requested_at_ms"),
                nullableLong(rs, "completed_at_ms"),
                rs.getString("error_message"),
                rs.getString("final_text")), pr.taskId());

        for (BrainReview review : reviews) {
            put(events, entry(
                    "v2:brain-review-start:" + review.id(), pr,
                    PRTimelineEntry.TYPE_REVIEW, PRTimelineEntry.ACTOR_BRAIN,
                    true, review.requestedAt(), payload(
                            "reviewEvent", "started", "scope", "dev",
                            "iteration", review.attempt(), "roundId", review.id())));
            if (review.completedAt() == null) {
                continue;
            }
            if (!"SUCCEEDED".equals(review.status())) {
                String reason = review.error();
                if (reason == null || reason.isBlank()) {
                    reason = review.status().toLowerCase(Locale.ROOT);
                }
                put(events, entry(
                        "v2:brain-review-failed:" + review.id(), pr,
                        PRTimelineEntry.TYPE_REVIEW,
                        PRTimelineEntry.ACTOR_BRAIN, true,
                        review.completedAt(), payload(
                                "reviewEvent",
                                "BUDGET_EXHAUSTED".equals(review.status())
                                        ? "parked" : "failed",
                                "scope", "dev", "iteration", review.attempt(),
                                "roundId", review.id(), "reason", reason,
                                "status", review.status())));
                continue;
            }
            BrainSummary summary = brainSummary(review);
            put(events, entry(
                    "v2:brain-review-finish:" + review.id(), pr,
                    PRTimelineEntry.TYPE_REVIEW, PRTimelineEntry.ACTOR_BRAIN,
                    true, review.completedAt(), payload(
                            "reviewEvent", "finished", "scope", "dev",
                            "iteration", review.attempt(), "roundId", review.id(),
                            "verdict", review.verdict(),
                            "findingCount", review.findingCount(),
                            "body", summary.body(),
                            "findings", summary.findings(),
                            "structuredSummary", true)));
        }
    }

    private void projectFirstPush(PR pr, Map<String, PRTimelineEntry> events)
    {
        boolean hasCreated = events.values().stream().anyMatch(event ->
                PRTimelineEntry.TYPE_PULL_REQUEST_CREATED.equals(
                        event.eventType()));
        boolean hasRemoteDrafted = hasStatus(
                events, PR.STATUS_REMOTE_DRAFTED);
        jdbc.query("""
                SELECT id, remote_pr_number, remote_pr_url, remote_head_ref,
                       bound_at_ms
                FROM remote_pr_binding
                WHERE task_id = ? AND pr_id = ?
                """, (rs, row) -> {
            String bindingId = rs.getString("id");
            long at = rs.getLong("bound_at_ms");
            if (!hasCreated) {
                put(events, entry(
                        "v2:first-push:" + bindingId, pr,
                        PRTimelineEntry.TYPE_PULL_REQUEST_CREATED, ACTOR_PUBLISH,
                        false, at, payload(
                                "phase", PRTimelineEntry.PHASE_CREATED,
                                "branch", rs.getString("remote_head_ref"),
                                "baseBranch", pr.baseBranch(),
                                "number", rs.getInt("remote_pr_number"),
                                "url", rs.getString("remote_pr_url"))));
            }
            if (!hasRemoteDrafted) {
                put(events, entry(
                        "v2:remote-drafted:" + bindingId, pr,
                        PRTimelineEntry.TYPE_STATUS, ACTOR_PUBLISH, false, at,
                        payload("from", PR.STATUS_LOCAL_OPEN,
                                "to", PR.STATUS_REMOTE_DRAFTED)));
            }
            return null;
        }, pr.taskId(), pr.id());
    }

    private void projectCiRepairs(PR pr, Map<String, PRTimelineEntry> events)
    {
        jdbc.query("""
                SELECT id, classification, subject_head_sha,
                       last_pushed_head_sha, status, opened_at_ms,
                       completed_at_ms, stop_reason
                FROM ci_repair_episode
                WHERE task_id = ?
                ORDER BY opened_at_ms, id
                """, (rs, row) -> {
            String id = rs.getString("id");
            String classification = rs.getString("classification");
            String subjectHeadSha = rs.getString("subject_head_sha");
            put(events, entry(
                    "v2:ci-repair-start:" + id, pr,
                    PRTimelineEntry.TYPE_CI, ACTOR_CI_REPAIR, false,
                    rs.getLong("opened_at_ms"), payload(
                            "status", "repair_started",
                            "classification", classification,
                            "headSha", subjectHeadSha)));
            Long completedAt = nullableLong(rs, "completed_at_ms");
            String terminalStatus = terminalCiRepairStatus(
                    rs.getString("status"));
            if (completedAt != null && terminalStatus != null) {
                String terminalHeadSha = rs.getString("last_pushed_head_sha");
                if (terminalHeadSha == null) {
                    terminalHeadSha = subjectHeadSha;
                }
                put(events, entry(
                        "v2:ci-repair-terminal:" + id, pr,
                        PRTimelineEntry.TYPE_CI, ACTOR_CI_REPAIR, false,
                        completedAt, payload(
                                "status", terminalStatus,
                                "classification", classification,
                                "headSha", terminalHeadSha,
                                "reason", rs.getString("stop_reason"))));
            }
            return null;
        }, pr.taskId());

        jdbc.query("""
                WITH ci_subject AS (
                    SELECT subject.id, subject.task_id, subject.head_sha,
                       COALESCE(
                           (SELECT previous.head_sha
                              FROM remote_worktree_subject previous
                             WHERE previous.task_id = subject.task_id
                               AND previous.task_epoch = subject.task_epoch
                               AND previous.revision < subject.revision
                             ORDER BY previous.revision DESC LIMIT 1),
                           (SELECT binding.remote_head_sha
                              FROM remote_pr_binding binding
                             WHERE binding.task_id = subject.task_id))
                           AS source_head_sha,
                       subject.recorded_at_ms AS created_at_ms
                    FROM remote_worktree_subject subject
                    WHERE subject.task_id = ?
                      AND subject.source_kind = 'CI_STAGE_TURN'
                )
                SELECT id, source_head_sha, head_sha, created_at_ms
                FROM ci_subject
                WHERE head_sha <> source_head_sha
                ORDER BY created_at_ms, id
                """, (rs, row) -> {
            String id = rs.getString("id");
            String sourceSha = rs.getString("source_head_sha");
            String headSha = rs.getString("head_sha");
            long at = rs.getLong("created_at_ms");
            put(events, entry(
                    "v2:ci-repair-addressed:" + id, pr,
                    PRTimelineEntry.TYPE_CI, ACTOR_CI_REPAIR, false, at,
                    payload("status", "repair_addressed",
                            "previousHeadSha", sourceSha, "headSha", headSha)));
            if (!hasCommit(events, headSha)) {
                put(events, entry(
                        "v2:ci-repair-commit:" + id, pr,
                        PRTimelineEntry.TYPE_COMMIT, ACTOR_CI_REPAIR, false, at,
                        payload("sha", headSha,
                                "message", "Repair CI failure")));
            }
            return null;
        }, pr.taskId());
    }

    private void projectMarkReady(PR pr, Map<String, PRTimelineEntry> events)
    {
        if (hasStatus(events, PR.STATUS_REMOTE_OPEN)) {
            return;
        }
        jdbc.query("""
                SELECT id, head_sha, completed_at_ms
                FROM remote_mark_ready_operation
                WHERE task_id = ? AND status = 'SUCCEEDED'
                ORDER BY completed_at_ms, id
                """, (rs, row) -> {
            put(events, entry(
                    "v2:mark-ready:" + rs.getString("id"), pr,
                    PRTimelineEntry.TYPE_STATUS, ACTOR_REMOTE, false,
                    rs.getLong("completed_at_ms"), payload(
                            "from", PR.STATUS_REMOTE_DRAFTED,
                            "to", PR.STATUS_REMOTE_OPEN,
                            "sha", rs.getString("head_sha"))));
            return null;
        }, pr.taskId());
    }

    private void projectMerge(PR pr, Map<String, PRTimelineEntry> events)
    {
        if (hasStatus(events, PR.STATUS_MERGED)) {
            return;
        }
        jdbc.query("""
                SELECT id, head_sha, completed_at_ms
                FROM remote_merge_operation
                WHERE task_id = ? AND status = 'SUCCEEDED'
                ORDER BY completed_at_ms, id
                """, (rs, row) -> {
            put(events, entry(
                    "v2:merge:" + rs.getString("id"), pr,
                    PRTimelineEntry.TYPE_STATUS, ACTOR_MERGE, false,
                    rs.getLong("completed_at_ms"), payload(
                            "from", PR.STATUS_REMOTE_OPEN,
                            "to", PR.STATUS_MERGED,
                            "sha", rs.getString("head_sha"))));
            return null;
        }, pr.taskId());
    }

    private void projectCleanup(PR pr, Map<String, PRTimelineEntry> events)
    {
        jdbc.query("""
                SELECT id, started_at_ms, completed_at_ms
                FROM cleanup_operation
                WHERE task_id = ?
                ORDER BY requested_at_ms, id
                """, (rs, row) -> {
            String id = rs.getString("id");
            Long startedAt = nullableLong(rs, "started_at_ms");
            Long completedAt = nullableLong(rs, "completed_at_ms");
            if (startedAt != null) {
                put(events, entry(
                        "v2:cleanup-start:" + id, pr,
                        PRTimelineEntry.TYPE_STATUS, ACTOR_CLEANUP, false,
                        startedAt, payload("to", "cleanup-started")));
            }
            if (completedAt != null) {
                put(events, entry(
                        "v2:cleanup-complete:" + id, pr,
                        PRTimelineEntry.TYPE_STATUS, ACTOR_CLEANUP, false,
                        completedAt, payload("to", "cleanup-completed")));
            }
            return null;
        }, pr.taskId());
    }

    private BrainSummary brainSummary(BrainReview review)
    {
        String summary = review.summary();
        List<String> findings = List.of();
        try {
            JsonNode root = json.readTree(review.finalText());
            if (root != null && root.isObject()) {
                JsonNode summaryNode = root.get("summary");
                if (summaryNode != null && summaryNode.isTextual()
                        && !summaryNode.textValue().isBlank()) {
                    summary = summaryNode.textValue();
                }
                JsonNode findingNodes = root.get("findings");
                if (findingNodes != null && findingNodes.isArray()) {
                    List<String> parsed = new ArrayList<>();
                    findingNodes.forEach(node -> {
                        if (node.isTextual() && !node.textValue().isBlank()) {
                            parsed.add(node.textValue());
                        }
                    });
                    findings = List.copyOf(parsed);
                }
            }
        }
        catch (JsonProcessingException | IllegalArgumentException ignored) {
            // The accepted episode summary remains safe to project even if an
            // old execution payload is unavailable or unreadable.
        }
        if (summary == null || summary.isBlank()) {
            summary = review.verdict();
        }
        String body = summary;
        if (!findings.isEmpty()) {
            body += "\n\nFindings:\n- " + String.join("\n- ", findings);
        }
        return new BrainSummary(body, findings);
    }

    private boolean hasCommit(
            Map<String, PRTimelineEntry> events, String sha)
    {
        return events.values().stream()
                .filter(event -> PRTimelineEntry.TYPE_COMMIT.equals(
                        event.eventType()))
                .map(PRTimelineEntry::payloadJson)
                .map(this::payloadNode)
                .map(node -> node.path("sha").asText(null))
                .filter(Objects::nonNull)
                .anyMatch(existing -> existing.equals(sha)
                        || existing.startsWith(sha) || sha.startsWith(existing));
    }

    private boolean hasStatus(
            Map<String, PRTimelineEntry> events, String target)
    {
        return events.values().stream()
                .filter(event -> PRTimelineEntry.TYPE_STATUS.equals(
                        event.eventType()))
                .map(PRTimelineEntry::payloadJson)
                .map(this::payloadNode)
                .anyMatch(node -> target.equals(node.path("to").asText(null)));
    }

    private JsonNode payloadNode(String value)
    {
        if (value == null || value.isBlank()) {
            return json.nullNode();
        }
        try {
            return json.readTree(value);
        }
        catch (JsonProcessingException e) {
            return json.nullNode();
        }
    }

    private int semanticOrder(PRTimelineEntry event)
    {
        if (PRTimelineEntry.TYPE_PULL_REQUEST_CREATED.equals(
                event.eventType())) {
            return 10;
        }
        if (PRTimelineEntry.TYPE_STATUS.equals(event.eventType())
                && PR.STATUS_REMOTE_DRAFTED.equals(
                        payloadNode(event.payloadJson()).path("to")
                                .asText(null))) {
            return 20;
        }
        return 100;
    }

    private static String terminalCiRepairStatus(String status)
    {
        return switch (status) {
            case "SUCCEEDED" -> "repair_succeeded";
            case "EXHAUSTED" -> "repair_exhausted";
            case "STOPPED" -> "repair_stopped";
            default -> null;
        };
    }

    private static void put(
            Map<String, PRTimelineEntry> events, PRTimelineEntry event)
    {
        events.putIfAbsent(event.id(), event);
    }

    private static Long nullableLong(ResultSet result, String column)
            throws SQLException
    {
        Number value = (Number) result.getObject(column);
        return value == null ? null : value.longValue();
    }

    private PRTimelineEntry entry(
            String id, PR pr, String type, String actor, boolean localOnly,
            long at, String payload)
    {
        return new PRTimelineEntry(
                id, pr.id(), type, actor, localOnly, null,
                Instant.ofEpochMilli(at), payload, null);
    }

    private String payload(Object... fields)
    {
        Map<String, Object> value = new LinkedHashMap<>();
        for (int index = 0; index < fields.length; index += 2) {
            Object item = fields[index + 1];
            if (item != null) {
                value.put((String) fields[index], item);
            }
        }
        try {
            return json.writeValueAsString(value);
        }
        catch (JsonProcessingException e) {
            throw new IllegalStateException("V2 PR timeline payload is invalid", e);
        }
    }

    @Override
    public List<PRCheck> remoteChecks(PR pr)
    {
        requireNonNull(pr, "pr is null");
        if (pr.taskId() == null) {
            return List.of();
        }
        return jdbc.query("""
                SELECT observed.id, observed.check_name,
                       observed.normalized_state,
                       observed.started_at_ms, observed.completed_at_ms,
                       observed.observed_at_ms
                FROM remote_ci_check_snapshot observed
                JOIN remote_development_stage remote
                  ON remote.accepted_snapshot_id = observed.remote_pr_snapshot_id
                WHERE remote.task_id = ?
                ORDER BY observed.check_name
                """, (rs, row) -> {
                    Long started = nullableLong(rs, "started_at_ms");
                    Long completed = nullableLong(rs, "completed_at_ms");
                    // A check that never reported a start (a skipped or
                    // instant job) still has to carry one: the PR wire model
                    // reads startedAt unconditionally. Fall back to when the
                    // owner observed it.
                    long startedAt = started != null ? started
                            : completed != null ? completed
                                    : rs.getLong("observed_at_ms");
                    return new PRCheck(
                            rs.getString("id"), pr.id(), PRCheck.KIND_REMOTE,
                            rs.getString("check_name"),
                            checkStatus(rs.getString("normalized_state")),
                            started == null || completed == null
                                    ? null : completed - started,
                            Instant.ofEpochMilli(startedAt),
                            completed == null ? null : Instant.ofEpochMilli(completed),
                            null);
                }, pr.taskId());
    }

    /** Maps the owner's normalized CI state onto the PR rail's own vocabulary.
     *  An unrecognised state stays pending rather than inventing an outcome. */
    private static String checkStatus(String normalizedState)
    {
        return switch (normalizedState == null ? "" : normalizedState) {
            case "PASSED" -> PRCheck.STATUS_PASSED;
            case "FAILED" -> PRCheck.STATUS_FAILED;
            case "SKIPPED", "NEUTRAL" -> PRCheck.STATUS_NEUTRAL;
            case "RUNNING" -> PRCheck.STATUS_RUNNING;
            default -> PRCheck.STATUS_PENDING;
        };
    }

    private record DevelopmentCommit(
            String id, int revision, String headSha, String summary, long at) {}

    private record BrainReview(
            String id, int attempt, String status, String verdict,
            int findingCount, String summary, long requestedAt,
            Long completedAt, String error, String finalText) {}

    private record BrainSummary(String body, List<String> findings) {}
}
