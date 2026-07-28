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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class TestReviewBuildOutcomeService
{
    @TempDir
    private Path tempDir;

    @Test
    void restartRecoveryResolvesOnlyExactCompletedSelectionAndIsIdempotent()
    {
        Fixture fixture = fixture("completed.db", "COMPLETED", false);

        fixture.service().recoverUnprocessedOutcomes();
        fixture.service().acceptTaskOutcome("task-1");
        new ReviewBuildOutcomeService(
                fixture.jdbc(), fixture.selections(), fixture.transactions())
                .recoverUnprocessedOutcomes();

        assertThat(fixture.text("""
                SELECT status FROM review_findings WHERE id = 'finding-1'
                """)).isEqualTo("resolved");
        assertThat(fixture.text("""
                SELECT resolution FROM review_findings WHERE id = 'finding-1'
                """)).isEqualTo("task_outcome:outcome-1");
        assertThat(fixture.text("""
                SELECT disposition || '|' || resolved_count
                FROM review_build_outcome_receipt
                """)).isEqualTo("RESOLVED|1");
        assertThat(fixture.count("review_build_outcome_receipt")).isEqualTo(1);
    }

    @Test
    void canceledAndRemoteClosedOutcomesNeverResolveFindings()
    {
        for (String reason : List.of("CANCELED", "REMOTE_CLOSED")) {
            Fixture fixture = fixture(
                    reason.toLowerCase(Locale.ROOT) + ".db", reason, false);

            fixture.service().acceptTaskOutcome("task-1");

            assertThat(fixture.text("""
                    SELECT status FROM review_findings WHERE id = 'finding-1'
                    """)).isEqualTo("agreed");
            assertThat(fixture.text("""
                    SELECT disposition FROM review_build_outcome_receipt
                    """)).isEqualTo("IGNORED_TERMINAL");
        }
    }

    @Test
    void mutationAfterFreezeCannotBeResolvedByTheOldTaskOutcome()
    {
        Fixture fixture = fixture("stale.db", "COMPLETED", false);
        fixture.jdbc().update("""
                UPDATE review_findings SET body = 'changed'
                WHERE id = 'finding-1'
                """);
        fixture.jdbc().update("""
                UPDATE review_findings SET body = 'Fix the exact race'
                WHERE id = 'finding-1'
                """);

        fixture.service().acceptTaskOutcome("task-1");

        assertThat(fixture.text("""
                SELECT status || '|' || revision
                FROM review_findings WHERE id = 'finding-1'
                """)).isEqualTo("agreed|3");
        assertThat(fixture.text("""
                SELECT disposition FROM review_build_outcome_receipt
                """)).isEqualTo("STALE_SELECTION");
    }

    @Test
    void siblingOrNonterminalTaskCannotResolveTheSelection()
    {
        Fixture fixture = fixture("sibling.db", "COMPLETED", true);

        fixture.service().acceptTaskOutcome("task-1");
        fixture.service().acceptTaskOutcome("task-without-outcome");

        assertThat(fixture.text("""
                SELECT status FROM review_findings WHERE id = 'finding-1'
                """)).isEqualTo("agreed");
        assertThat(fixture.count("review_build_outcome_receipt")).isZero();
    }

    private Fixture fixture(
            String file, String terminalReason, boolean sibling)
    {
        String url = "jdbc:sqlite:" + tempDir.resolve(file)
                + "?busy_timeout=30000";
        Flyway.configure().dataSource(url, "", "").target("258").load().migrate();
        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl(url);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        for (String trigger : List.of(
                "task_assignment_v2_exact_insert",
                "v2_task_insert_shape",
                "v2_task_creation_authority_insert",
                "task_outcome_insert")) {
            jdbc.execute("DROP TRIGGER " + trigger);
        }
        jdbc.update("""
                INSERT INTO workspaces(
                    id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
                VALUES ('workspace', 'Workspace', '', 0, 1, 1)
                """);
        jdbc.update("""
                INSERT INTO workspace_repos(
                    workspace_id, repo_full_name, default_base_branch,
                    auto_fix_enabled, added_at_ms)
                VALUES ('workspace', 'acme/widget', 'main', 0, 1)
                """);
        insertThread(jdbc, "review-thread", "review");
        insertThread(jdbc, "build-thread", "build");
        insertThread(jdbc, "sibling-thread", "build");
        jdbc.update("""
                INSERT INTO review_passes(
                    id, thread_id, repo_full_name, pr_number, head_sha, phase,
                    round, round_cap, cost_cap_milli, cost_usd_milli,
                    created_at_ms, ended_at_ms, host_kind, host_id, kind,
                    spawned_build_thread_id)
                VALUES ('review-pass', 'review-thread', 'acme/widget', 42,
                    'head-1', 'TERMINATE', 0, 3, 500, 0, 2, 2,
                    'THREAD', 'review-thread', 'FRESH', 'build-thread')
                """);
        jdbc.update("""
                INSERT INTO review_findings(
                    id, review_pass_id, path, line, severity, status, body,
                    created_at_ms)
                VALUES ('finding-1', 'review-pass', 'src/Main.java', 17,
                    'blocker', 'agreed', 'Fix the exact race', 3)
                """);

        ReviewBuildSelectionStore selections = new ReviewBuildSelectionStore(
                jdbc, new ObjectMapper().findAndRegisterModules());
        ReviewBuildSelectionStore.Selection selection = selections.freeze(
                "build-thread", "review-pass", "acme/widget", 42, "head-1",
                new ReviewBuildSelectionStore.SpawnInput(
                        "workspace", "Fix review findings on PR #42",
                        ReviewBuildSelectionStore.SelectionPolicy.ALL_ELIGIBLE,
                        ReviewBuildSpawnService.MODE_AUTHOR,
                        "acme/widget", "acme/widget", "main", "feature/review"),
                List.of(new ReviewFinding(
                        "finding-1", "review-pass", "src/Main.java", 17,
                        ReviewFindingSeverity.BLOCKER,
                        ReviewFindingStatus.AGREED, "Fix the exact race",
                        null, null, Instant.ofEpochMilli(3))),
                Instant.ofEpochMilli(4));

        String taskThread = sibling ? "sibling-thread" : "build-thread";
        jdbc.update("""
                INSERT INTO task_assignment(
                    id, trunk_id, kind, source_id, repository_id, pr_number,
                    remote_head_sha, selected_findings_json, created_by,
                    created_at_ms, base_repository_id, head_repository_id,
                    base_ref, head_ref, remote_base_sha, repository_route)
                VALUES ('assignment-1', ?, 'REVIEW_FINDINGS', 'review-pass',
                    'acme/widget', 42, 'head-1', '[]', 'test', 5,
                    'acme/widget', 'acme/widget', 'main', 'feature/review',
                    'base-1', 'DIRECT')
                """, taskThread);
        ReviewBuildSelectionStore.Finding frozen =
                selection.findings().getFirst();
        jdbc.update("""
                INSERT INTO task_assignment_review_finding(
                    assignment_id, position, source_review_id, finding_id,
                    finding_revision, content_digest)
                VALUES ('assignment-1', 1, 'review-pass', 'finding-1', ?, ?)
                """, frozen.findingRevision(), frozen.contentDigest());
        jdbc.update("""
                INSERT INTO tasks(
                    id, thread_id, seq, status, phase, created_at_ms,
                    workflow_version, epoch, aggregate_version,
                    lifecycle_state, assignment_id, name, linked_pr_number,
                    linked_pr_ref)
                VALUES ('task-1', ?, 1, 'COMPLETED', 'COMPLETED', 6,
                    'V2', 1, 1, ?, 'assignment-1', 'review build', 42,
                    'acme/widget#42')
                """, taskThread, terminalReason);
        insertOutcome(jdbc, terminalReason);

        DataSourceTransactionManager transactions =
                new DataSourceTransactionManager(source);
        ReviewBuildOutcomeService service = new ReviewBuildOutcomeService(
                jdbc, selections, transactions);
        return new Fixture(jdbc, selections, transactions, service);
    }

    private static void insertOutcome(JdbcTemplate jdbc, String reason)
    {
        jdbc.update("""
                INSERT INTO task_outcome(
                    id, task_id, trunk_id, task_epoch,
                    terminal_acceptance_id, cleanup_operation_id,
                    cleanup_stage_id, terminal_reason, cleanup_summary_digest,
                    summary_state, summary_text, summary_digest,
                    follow_up_proposals_json, backlog_items_json,
                    recorded_at_ms)
                VALUES ('outcome-1', 'task-1', (
                        SELECT thread_id FROM tasks WHERE id = 'task-1'), 1,
                    'acceptance-1', 'cleanup-1', 'cleanup-stage-1', ?,
                    'summary-digest', 'FALLBACK',
                    'TaskOutcome:task-1:' || ? || ':summary-digest',
                    'summary-digest', '[]', '[]', 7)
                """, reason, reason);
    }

    private static void insertThread(
            JdbcTemplate jdbc, String id, String flow)
    {
        jdbc.update("""
                INSERT INTO threads(
                    id, kind, provider, title, status, model,
                    cost_usd_milli, tokens_in, tokens_out,
                    created_at_ms, updated_at_ms, workspace_id, flow,
                    parallel_slots, turn_version, lifecycle_state,
                    aggregate_version)
                VALUES (?, 'CLI_AGENT', 'codex', ?, 'IDLE', 'gpt-test',
                    0, 0, 0, 1, 1, 'workspace', ?, 1, 'V2', 'IDLE', 0)
                """, id, id, flow);
    }

    private record Fixture(
            JdbcTemplate jdbc,
            ReviewBuildSelectionStore selections,
            DataSourceTransactionManager transactions,
            ReviewBuildOutcomeService service)
    {
        int count(String table)
        {
            return jdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + table, Integer.class);
        }

        String text(String sql)
        {
            return jdbc.queryForObject(sql, String.class);
        }
    }
}
