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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class TestSqliteV2PrTimelineProjection
{
    @TempDir
    private Path tempDir;

    @Test
    void replaysEachVisibleMilestoneFromItsDurableOwnerExactlyOnce()
            throws Exception
    {
        JdbcTemplate jdbc = database();
        ObjectMapper json = new ObjectMapper();
        createTables(jdbc);
        jdbc.update("""
                INSERT INTO dev_report(
                    id, task_id, workflow_version, revision, head_sha,
                    commit_summary, created_at_ms)
                VALUES ('report-1', 'task-1', 'V2', 1, 'dev-head',
                    'Implement the task', 100)
                """);
        String finalText = json.writeValueAsString(Map.of(
                "schemaVersion", 1,
                "verdict", "CHANGES_REQUESTED",
                "summary", "One issue remains.",
                "findings", List.of("Keep the fallback null-safe.")));
        String rawResult = json.writeValueAsString(Map.of(
                "payloadJson", json.writeValueAsString(Map.of(
                        "finalText", finalText))));
        jdbc.update("""
                INSERT INTO brain_review_episode(
                    id, task_id, task_turn_id, semantic_attempt, status,
                    verdict, unresolved_finding_count, verdict_summary,
                    requested_at_ms, completed_at_ms, error_message)
                VALUES ('brain-failed', 'task-1', 'turn-failed', 1, 'FAILED',
                    NULL, 0, NULL, 108, 109, 'malformed output')
                """);
        jdbc.update("""
                INSERT INTO brain_review_episode(
                    id, task_id, task_turn_id, semantic_attempt, status,
                    verdict, unresolved_finding_count, verdict_summary,
                    requested_at_ms, completed_at_ms)
                VALUES ('brain-1', 'task-1', 'turn-1', 1, 'SUCCEEDED',
                    'CHANGES_REQUESTED', 1, 'stored fallback', 110, 130)
                """);
        jdbc.update("""
                INSERT INTO dispatch_ticket(id, owner_kind, owner_id)
                VALUES ('ticket-1', 'TASK_TURN', 'turn-1')
                """);
        jdbc.update("""
                INSERT INTO agent_execution(
                    id, ticket_id, status, infrastructure_attempt, raw_result)
                VALUES ('execution-1', 'ticket-1', 'SUCCEEDED', 1, ?)
                """, rawResult);
        jdbc.update("""
                INSERT INTO remote_pr_binding(
                    id, task_id, pr_id, remote_pr_number, remote_pr_url,
                    remote_head_ref, remote_head_sha, bound_at_ms)
                VALUES ('binding-1', 'task-1', 'pr-1', 17,
                    'https://github.com/acme/widget/pull/17', 'feature/x',
                    'dev-head', 140)
                """);
        jdbc.update("""
                INSERT INTO ci_repair_episode(
                    id, task_id, classification, subject_head_sha,
                    last_pushed_head_sha, status, opened_at_ms,
                    completed_at_ms)
                VALUES ('repair-1', 'task-1', 'TASK_DETERMINISTIC',
                    'dev-head', 'adopted-head', 'SUCCEEDED', 150, 168)
                """);
        jdbc.update("""
                INSERT INTO remote_worktree_subject(
                    id, task_id, task_epoch, revision, source_kind,
                    head_sha, recorded_at_ms)
                VALUES ('branch-subject', 'task-1', 1, 1,
                    'BRANCH_STAGE_TURN', 'branch-head', 155)
                """);
        jdbc.update("""
                INSERT INTO remote_worktree_subject(
                    id, task_id, task_epoch, revision, source_kind,
                    head_sha, recorded_at_ms)
                VALUES ('repair-subject', 'task-1', 1, 2,
                    'CI_STAGE_TURN', 'repair-head', 160)
                """);
        jdbc.update("""
                INSERT INTO remote_worktree_subject(
                    id, task_id, task_epoch, revision, source_kind,
                    head_sha, recorded_at_ms)
                VALUES ('adopted-subject', 'task-1', 1, 3,
                    'CI_STAGE_TURN', 'adopted-head', 165)
                """);
        jdbc.update("""
                INSERT INTO remote_mark_ready_operation(
                    id, task_id, status, head_sha, completed_at_ms)
                VALUES ('ready-1', 'task-1', 'SUCCEEDED', 'repair-head', 170)
                """);
        jdbc.update("""
                INSERT INTO remote_merge_operation(
                    id, task_id, status, head_sha, completed_at_ms)
                VALUES ('merge-1', 'task-1', 'SUCCEEDED', 'repair-head', 180)
                """);
        jdbc.update("""
                INSERT INTO cleanup_operation(
                    id, task_id, requested_at_ms, started_at_ms,
                    completed_at_ms)
                VALUES ('cleanup-1', 'task-1', 181, 185, 190)
                """);
        PR pr = PR.create(
                        "pr-1", "task-1", "feature/x", "main", "Title", "",
                        Instant.ofEpochMilli(1))
                .withStatus(PR.STATUS_LOCAL_OPEN, Instant.ofEpochMilli(105))
                .withRemote("acme/widget", 17,
                        "https://github.com/acme/widget/pull/17",
                        Instant.ofEpochMilli(140))
                .withStatus(PR.STATUS_REMOTE_DRAFTED,
                        Instant.ofEpochMilli(140));
        PRTimelineEntry localOpen = new PRTimelineEntry(
                "stored:local-open", "pr-1", PRTimelineEntry.TYPE_STATUS,
                "v2-local-runtime", false, null, Instant.ofEpochMilli(105),
                "{\"from\":\"local-drafted\",\"to\":\"local-open\"}",
                null);
        SqliteV2PrTimelineProjection projection =
                new SqliteV2PrTimelineProjection(jdbc, json);

        List<PRTimelineEntry> first = projection.project(pr, List.of(localOpen));
        List<PRTimelineEntry> replay = projection.project(pr, first);

        assertThat(first.stream().map(PRTimelineEntry::id)).containsExactly(
                "v2:dev-commit:report-1",
                "stored:local-open",
                "v2:brain-review-start:brain-failed",
                "v2:brain-review-failed:brain-failed",
                "v2:brain-review-start:brain-1",
                "v2:brain-review-finish:brain-1",
                "v2:first-push:binding-1",
                "v2:remote-drafted:binding-1",
                "v2:ci-repair-start:repair-1",
                "v2:ci-repair-addressed:repair-subject",
                "v2:ci-repair-commit:repair-subject",
                "v2:ci-repair-addressed:adopted-subject",
                "v2:ci-repair-commit:adopted-subject",
                "v2:ci-repair-terminal:repair-1",
                "v2:mark-ready:ready-1",
                "v2:merge:merge-1",
                "v2:cleanup-start:cleanup-1",
                "v2:cleanup-complete:cleanup-1");
        assertThat(replay).isEqualTo(first);
        JsonNode review = json.readTree(first.stream()
                .filter(event -> event.id().contains("brain-review-finish"))
                .findFirst().orElseThrow().payloadJson());
        assertThat(review.path("structuredSummary").asBoolean()).isTrue();
        assertThat(review.path("body").asText())
                .contains("One issue remains.", "Keep the fallback null-safe.");
        assertThat(first.stream()
                .filter(event -> event.actor().equals("brain"))
                .map(PRTimelineEntry::id))
                .allMatch(id -> id.contains("brain-review"));
    }

    @Test
    void projectsEveryTerminalCiRepairOutcome()
            throws Exception
    {
        JdbcTemplate jdbc = database();
        ObjectMapper json = new ObjectMapper();
        createTables(jdbc);
        jdbc.update("""
                INSERT INTO ci_repair_episode(
                    id, task_id, classification, subject_head_sha,
                    last_pushed_head_sha, status, opened_at_ms,
                    completed_at_ms, stop_reason)
                VALUES
                    ('success', 'task-1', 'TASK_DETERMINISTIC', 'head-1',
                        'fixed-head-1', 'SUCCEEDED', 100, 110, NULL),
                    ('exhausted', 'task-1', 'TASK_DETERMINISTIC', 'head-2',
                        NULL, 'EXHAUSTED', 120, 130, 'budget exhausted'),
                    ('stopped', 'task-1', 'UNKNOWN', 'head-3',
                        NULL, 'STOPPED', 140, 150, 'user canceled')
                """);
        PR pr = PR.create(
                        "pr-1", "task-1", "feature/x", "main", "Title", "",
                        Instant.ofEpochMilli(1))
                .withStatus(PR.STATUS_LOCAL_OPEN, Instant.ofEpochMilli(2));

        List<JsonNode> terminal = new SqliteV2PrTimelineProjection(jdbc, json)
                .project(pr, List.of()).stream()
                .filter(event -> event.id().startsWith(
                        "v2:ci-repair-terminal:"))
                .map(PRTimelineEntry::payloadJson)
                .map(value -> {
                    try {
                        return json.readTree(value);
                    }
                    catch (Exception e) {
                        throw new AssertionError(e);
                    }
                })
                .toList();

        assertThat(terminal).extracting(node -> node.path("status").asText())
                .containsExactly(
                        "repair_succeeded", "repair_exhausted",
                        "repair_stopped");
        assertThat(terminal.getFirst().path("headSha").asText())
                .isEqualTo("fixed-head-1");
        assertThat(terminal.get(1).path("reason").asText())
                .isEqualTo("budget exhausted");
        assertThat(terminal.get(2).path("reason").asText())
                .isEqualTo("user canceled");
    }

    @Test
    void ordersFirstPushBeforeDraftTransitionAtTheSameInstant()
    {
        JdbcTemplate jdbc = database();
        ObjectMapper json = new ObjectMapper();
        createTables(jdbc);
        PR pr = PR.create(
                        "pr-1", "task-1", "feature/x", "main", "Title", "",
                        Instant.ofEpochMilli(1))
                .withStatus(PR.STATUS_REMOTE_DRAFTED,
                        Instant.ofEpochMilli(100));
        PRTimelineEntry drafted = new PRTimelineEntry(
                "aaa-drafted", "pr-1", PRTimelineEntry.TYPE_STATUS,
                "publisher", false, null, Instant.ofEpochMilli(100),
                "{\"from\":\"local-open\",\"to\":\"remote-drafted\"}",
                null);
        PRTimelineEntry created = new PRTimelineEntry(
                "zzz-created", "pr-1",
                PRTimelineEntry.TYPE_PULL_REQUEST_CREATED, "publisher", false,
                null, Instant.ofEpochMilli(100),
                "{\"phase\":\"created\"}", null);

        assertThat(new SqliteV2PrTimelineProjection(jdbc, json)
                .project(pr, List.of(drafted, created)).stream()
                .map(PRTimelineEntry::id))
                .containsExactly("zzz-created", "aaa-drafted");
    }

    @Test
    void taskOwnedPrReadsItsAcceptedCheckRunsInsteadOfTheUnsyncedCache()
    {
        // The bundle never GitHub-syncs a Task-owned PR, so its stored checks
        // stay empty and the panel reported CI as still running after the
        // owner had already accepted terminal failures.
        JdbcTemplate jdbc = database();
        createTables(jdbc);
        jdbc.execute("""
                CREATE TABLE remote_ci_check_snapshot(
                    id TEXT, remote_pr_snapshot_id TEXT, check_name TEXT,
                    normalized_state TEXT, started_at_ms INTEGER,
                    completed_at_ms INTEGER, observed_at_ms INTEGER)
                """);
        jdbc.execute("""
                CREATE TABLE remote_development_stage(
                    stage_id TEXT, task_id TEXT, accepted_snapshot_id TEXT)
                """);
        jdbc.update("INSERT INTO remote_development_stage VALUES"
                + " ('stage-1', 'task-1', 'snap-1')");
        jdbc.update("INSERT INTO remote_ci_check_snapshot VALUES"
                + " ('c1', 'snap-1', 'Backend — tests', 'FAILED', 10, 20, 30)");
        // A skipped/instant job reports no start; the PR wire model still
        // dereferences startedAt, so it must never come back null.
        jdbc.update("INSERT INTO remote_ci_check_snapshot VALUES"
                + " ('c2', 'snap-1', 'Workflow lint', 'PASSED', NULL, NULL, 42)");
        // A different snapshot the owner has not accepted stays invisible.
        jdbc.update("INSERT INTO remote_ci_check_snapshot VALUES"
                + " ('c3', 'snap-old', 'Stale check', 'PASSED', 1, 2, 3)");
        PR pr = PR.create("pr-1", "task-1", "feature/x", "main", "T", "",
                Instant.ofEpochMilli(1));

        List<PRCheck> checks = new SqliteV2PrTimelineProjection(
                jdbc, new ObjectMapper()).remoteChecks(pr);

        assertThat(checks).extracting(PRCheck::name, PRCheck::status)
                .containsExactly(
                        tuple("Backend — tests", PRCheck.STATUS_FAILED),
                        tuple("Workflow lint", PRCheck.STATUS_PASSED));
        assertThat(checks).allMatch(check -> PRCheck.KIND_REMOTE.equals(check.kind()));
        assertThat(checks).allMatch(check -> check.startedAt() != null);
        assertThat(checks.get(1).startedAt()).isEqualTo(Instant.ofEpochMilli(42));
        // A PR with no Task owner keeps the synced-cache path.
        assertThat(new SqliteV2PrTimelineProjection(jdbc, new ObjectMapper())
                .remoteChecks(PR.create("pr-2", null, "b", "main", "T", "",
                        Instant.ofEpochMilli(1))))
                .isEmpty();
    }

    @Test
    void developmentEvidenceIsDerivedFromItsReportAndSurvivesReplay()
    {
        // Design 3.37: derived from the same immutable dev_report row, never
        // written, so a repeated read yields the same single event.
        JdbcTemplate jdbc = database();
        createTables(jdbc);
        jdbc.update("""
                INSERT INTO dev_report(
                    id, task_id, workflow_version, revision, head_sha,
                    commit_summary, created_at_ms, implemented_intent,
                    file_summary, validation_summary, known_risks,
                    unresolved_concerns)
                VALUES ('report-1', 'task-1', 'V2', 1, 'dev-head',
                    'Raise the label', 100, 'Grew the label to 14px',
                    'two CSS files', 'mvn verify passed', 'purely visual',
                    'none')
                """);
        PR pr = PR.create("pr-1", "task-1", "feature/x", "main", "T", "",
                Instant.ofEpochMilli(1));
        SqliteV2PrTimelineProjection projection =
                new SqliteV2PrTimelineProjection(jdbc, new ObjectMapper());

        List<PRTimelineEntry> first = projection.project(pr, List.of());
        List<PRTimelineEntry> replay = projection.project(pr, first);

        assertThat(first).extracting(PRTimelineEntry::id)
                .containsExactly("v2:dev-commit:report-1", "v2:dev-evidence:report-1");
        assertThat(replay).extracting(PRTimelineEntry::id)
                .isEqualTo(first.stream().map(PRTimelineEntry::id).toList());
        assertThat(first.get(1).payloadJson())
                .contains("Grew the label to 14px")
                .contains("mvn verify passed")
                .contains("purely visual");
    }

    @Test
    void developmentEvidenceIsOmittedWhenTheReportCarriesNone()
    {
        JdbcTemplate jdbc = database();
        createTables(jdbc);
        jdbc.update("""
                INSERT INTO dev_report(
                    id, task_id, workflow_version, revision, head_sha,
                    commit_summary, created_at_ms)
                VALUES ('report-1', 'task-1', 'V2', 1, 'dev-head',
                    'Raise the label', 100)
                """);
        PR pr = PR.create("pr-1", "task-1", "feature/x", "main", "T", "",
                Instant.ofEpochMilli(1));

        assertThat(new SqliteV2PrTimelineProjection(jdbc, new ObjectMapper())
                .project(pr, List.of()))
                .extracting(PRTimelineEntry::id)
                .containsExactly("v2:dev-commit:report-1");
    }

    private JdbcTemplate database()
    {
        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl("jdbc:sqlite:" + tempDir.resolve("timeline.db"));
        return new JdbcTemplate(source);
    }

    private static void createTables(JdbcTemplate jdbc)
    {
        jdbc.execute("""
                CREATE TABLE dev_report(
                    id TEXT, task_id TEXT, workflow_version TEXT, revision INTEGER,
                    head_sha TEXT, commit_summary TEXT, created_at_ms INTEGER,
                    implemented_intent TEXT, file_summary TEXT,
                    validation_summary TEXT, known_risks TEXT,
                    unresolved_concerns TEXT)
                """);
        jdbc.execute("""
                CREATE TABLE brain_review_episode(
                    id TEXT, task_id TEXT, task_turn_id TEXT,
                    semantic_attempt INTEGER, status TEXT, verdict TEXT,
                    unresolved_finding_count INTEGER, verdict_summary TEXT,
                    requested_at_ms INTEGER, completed_at_ms INTEGER,
                    error_message TEXT)
                """);
        jdbc.execute("""
                CREATE TABLE dispatch_ticket(
                    id TEXT, owner_kind TEXT, owner_id TEXT)
                """);
        jdbc.execute("""
                CREATE TABLE agent_execution(
                    id TEXT, ticket_id TEXT, status TEXT,
                    infrastructure_attempt INTEGER, raw_result TEXT)
                """);
        jdbc.execute("""
                CREATE TABLE remote_pr_binding(
                    id TEXT, task_id TEXT, pr_id TEXT, remote_pr_number INTEGER,
                    remote_pr_url TEXT, remote_head_ref TEXT,
                    remote_head_sha TEXT, bound_at_ms INTEGER)
                """);
        jdbc.execute("""
                CREATE TABLE ci_repair_episode(
                    id TEXT, task_id TEXT, classification TEXT,
                    subject_head_sha TEXT, last_pushed_head_sha TEXT,
                    status TEXT, opened_at_ms INTEGER, completed_at_ms INTEGER,
                    stop_reason TEXT)
                """);
        jdbc.execute("""
                CREATE TABLE remote_worktree_subject(
                    id TEXT, task_id TEXT, task_epoch INTEGER, revision INTEGER,
                    source_kind TEXT, head_sha TEXT, recorded_at_ms INTEGER)
                """);
        jdbc.execute("""
                CREATE TABLE remote_mark_ready_operation(
                    id TEXT, task_id TEXT, status TEXT, head_sha TEXT,
                    completed_at_ms INTEGER)
                """);
        jdbc.execute("""
                CREATE TABLE remote_merge_operation(
                    id TEXT, task_id TEXT, status TEXT, head_sha TEXT,
                    completed_at_ms INTEGER)
                """);
        jdbc.execute("""
                CREATE TABLE cleanup_operation(
                    id TEXT, task_id TEXT, requested_at_ms INTEGER,
                    started_at_ms INTEGER, completed_at_ms INTEGER)
                """);
    }
}
