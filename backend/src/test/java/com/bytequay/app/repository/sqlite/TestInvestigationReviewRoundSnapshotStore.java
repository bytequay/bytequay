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
package com.bytequay.app.repository.sqlite;

import com.bytequay.app.domain.DiffFile;
import com.bytequay.app.domain.InvestigationReviewData.ReviewCapabilities;
import com.bytequay.app.repository.sqlite.InvestigationReviewStore.ReviewRoundSnapshot;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TestInvestigationReviewRoundSnapshotStore
{
    @TempDir
    private Path tempDir;

    @Test
    void roundTripsSnapshotByRoundAndAssignment()
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("snapshot-store.db");
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url));
        jdbc.execute("""
                CREATE TABLE review_round_snapshot_v291(
                    round_id TEXT PRIMARY KEY, repository TEXT,
                    remote_pr_number INTEGER, base_branch TEXT NOT NULL,
                    pr_title TEXT NOT NULL, pr_description TEXT NOT NULL,
                    base_commit TEXT NOT NULL,
                    head_commit TEXT NOT NULL, diff TEXT NOT NULL,
                    files_json TEXT NOT NULL, file_contents_json TEXT NOT NULL,
                    local_root TEXT,
                    repository_root TEXT, capabilities_json TEXT NOT NULL,
                    created_at_ms INTEGER NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE review_assignment(
                    id TEXT PRIMARY KEY, round_id TEXT NOT NULL)
                """);
        jdbc.update(
                "INSERT INTO review_assignment(id, round_id) VALUES (?, ?)",
                "assignment-1", "round-1");
        InvestigationReviewStore store = new InvestigationReviewStore(
                jdbc, new ObjectMapper());
        ReviewRoundSnapshot expected = new ReviewRoundSnapshot(
                "round-1", "acme/widget", 42, "main", "Frozen title",
                "Frozen description", "base-1", "head-1",
                "diff --git a/A.java b/A.java",
                List.of(new DiffFile("A.java", "modified", 1, 0, "+return true;")),
                Map.of("A.java", "return true;\n"),
                "/tmp/task", "/tmp/repository",
                ReviewCapabilities.frozenChangedFiles(), 123L);

        store.insertRoundSnapshot(expected);

        assertThat(store.findRoundSnapshot("round-1")).contains(expected);
        assertThat(store.findRoundSnapshotByAssignment("assignment-1"))
                .contains(expected);
    }
}
