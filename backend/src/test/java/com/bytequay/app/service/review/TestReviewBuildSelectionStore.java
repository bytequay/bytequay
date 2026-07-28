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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestReviewBuildSelectionStore
{
    @TempDir
    private Path tempDir;

    @Test
    void freezesExactSelectionAndRejectsMutationOrDifferentReplay()
    {
        JdbcTemplate jdbc = database();
        ReviewBuildSelectionStore store = new ReviewBuildSelectionStore(
                jdbc, new ObjectMapper().findAndRegisterModules());
        ReviewFinding finding = new ReviewFinding(
                "finding-1", "review-pass", "src/Main.java", 17,
                ReviewFindingSeverity.BLOCKER, ReviewFindingStatus.AGREED,
                "Fix the exact race", null, null, Instant.ofEpochMilli(3));

        ReviewBuildSelectionStore.Selection first = store.freeze(
                "build-thread", "review-pass", "acme/widget", 42,
                "head-1", List.of(finding), Instant.ofEpochMilli(4));
        ReviewBuildSelectionStore.Selection replay = store.freeze(
                "build-thread", "review-pass", "acme/widget", 42,
                "head-1", List.of(finding), Instant.ofEpochMilli(99));

        assertThat(replay).isEqualTo(first);
        assertThat(store.find("build-thread")).contains(first);
        assertThat(first.findings()).singleElement().satisfies(item -> {
            assertThat(item.findingId()).isEqualTo("finding-1");
            assertThat(item.findingRevision()).isEqualTo(1);
            assertThat(item.contentJson()).contains("Fix the exact race");
            assertThat(item.contentDigest()).hasSize(64);
        });
        assertThatThrownBy(() -> store.freeze(
                "build-thread", "review-pass", "acme/widget", 42,
                "head-2", List.of(finding), Instant.ofEpochMilli(5)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("different input");
        assertThatThrownBy(() -> jdbc.update("""
                UPDATE review_build_selection_item
                SET content_digest = 'changed'
                WHERE thread_id = 'build-thread'
                """))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("immutable");
        assertThatThrownBy(() -> jdbc.update("""
                DELETE FROM review_build_selection
                WHERE thread_id = 'build-thread'
                """))
                .isInstanceOf(DataAccessException.class)
                .hasMessageContaining("cannot be deleted");
    }

    private JdbcTemplate database()
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("review-build.db")
                + "?foreign_keys=ON&busy_timeout=30000";
        Flyway.configure().dataSource(url, "", "").target("258").load().migrate();
        SQLiteDataSource source = new SQLiteDataSource();
        source.setUrl(url);
        JdbcTemplate jdbc = new JdbcTemplate(source);
        jdbc.update("""
                INSERT INTO workspaces(
                    id, name, memory_md, is_scratch, created_at_ms, updated_at_ms)
                VALUES ('workspace', 'Workspace', '', 0, 1, 1)
                """);
        insertThread(jdbc, "review-thread", "review");
        insertThread(jdbc, "build-thread", "build");
        jdbc.update("""
                INSERT INTO review_passes(
                    id, thread_id, repo_full_name, pr_number, head_sha, phase,
                    round, round_cap, cost_cap_milli, cost_usd_milli,
                    created_at_ms, ended_at_ms, host_kind, host_id, kind)
                VALUES ('review-pass', 'review-thread', 'acme/widget', 42,
                    'head-1', 'TERMINATE', 0, 3, 500, 0, 2, 2,
                    'THREAD', 'review-thread', 'FRESH')
                """);
        jdbc.update("""
                INSERT INTO review_findings(
                    id, review_pass_id, path, line, severity, status, body,
                    created_at_ms)
                VALUES ('finding-1', 'review-pass', 'src/Main.java', 17,
                    'blocker', 'agreed', 'Fix the exact race', 3)
                """);
        return jdbc;
    }

    private static void insertThread(
            JdbcTemplate jdbc, String id, String flow)
    {
        jdbc.update("""
                INSERT INTO threads(
                    id, kind, provider, title, status, model,
                    cost_usd_milli, tokens_in,
                    tokens_out, created_at_ms, updated_at_ms, workspace_id,
                    flow, parallel_slots, turn_version, lifecycle_state,
                    aggregate_version)
                VALUES (?, 'CLI_AGENT', 'codex', ?, 'IDLE', 'gpt-test',
                    0, 0, 0, 1, 1,
                    'workspace', ?, 1, 'V2', 'IDLE', 0)
                """, id, id, flow);
    }
}
