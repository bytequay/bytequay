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

import com.bytequay.app.domain.DiffFile;
import com.bytequay.app.domain.InvestigationReviewData.AgentReviewRow;
import com.bytequay.app.domain.InvestigationReviewData.ReviewCapabilities;
import com.bytequay.app.domain.PR;
import com.bytequay.app.repository.sqlite.InvestigationReviewStore;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestFrozenReviewSubject
{
    @TempDir
    private Path tempDir;

    @Test
    void restartReconstructsRoutePromptAndFilesWithoutCurrentPrOrCheckout()
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("restart.db");
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(url));
        jdbc.execute("""
                CREATE TABLE review_round_snapshot_v291(
                    round_id TEXT PRIMARY KEY, repository TEXT,
                    remote_pr_number INTEGER, base_branch TEXT NOT NULL,
                    pr_title TEXT NOT NULL, pr_description TEXT NOT NULL,
                    base_commit TEXT NOT NULL, head_commit TEXT NOT NULL,
                    diff TEXT NOT NULL, files_json TEXT NOT NULL,
                    file_contents_json TEXT NOT NULL, local_root TEXT,
                    repository_root TEXT, capabilities_json TEXT NOT NULL,
                    created_at_ms INTEGER NOT NULL)
                """);
        InvestigationReviewStore first = new InvestigationReviewStore(
                jdbc, new ObjectMapper());
        first.insertRoundSnapshot(snapshot(
                "acme/frozen", 42, "main", "Frozen title",
                "Frozen description"));

        InvestigationReviewStore restarted = new InvestigationReviewStore(
                jdbc, new ObjectMapper());
        AgentReviewRow review = new AgentReviewRow(
                "review-1", "mutable/repository", "pr-1", "base", "head",
                "ACTIVE", null, null, null);
        InvestigationReviewContext.Snapshot restored =
                FrozenReviewSubject.snapshot(
                        review, restarted.findRoundSnapshot("round-1")
                                .orElseThrow());

        assertThat(restored.pr().repo()).isEqualTo("acme/frozen");
        assertThat(restored.pr().remotePrNumber()).isEqualTo(42);
        assertThat(restored.pr().baseBranch()).isEqualTo("main");
        assertThat(restored.pr().title()).isEqualTo("Frozen title");
        assertThat(restored.pr().description())
                .isEqualTo("Frozen description");
        assertThat(restored.readFile("src/A.java"))
                .isEqualTo("complete frozen body\n");
        assertThatThrownBy(() -> restored.readFile("src/Uncaptured.java"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not captured in frozen review snapshot");
    }

    @Test
    void standaloneRestartFailsClosedWithoutACompleteRemoteRoute()
    {
        AgentReviewRow review = new AgentReviewRow(
                "review-1", "repo", "pr-1", "base", "head", "ACTIVE",
                null, null, null);

        assertThatThrownBy(() -> FrozenReviewSubject.snapshot(
                review, snapshot(null, null, "main", "title", "body")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no frozen PR route");
    }

    @Test
    void pushedTaskRestartRetainsItsTaskOwnership()
    {
        AgentReviewRow review = new AgentReviewRow(
                "review-1", "repo", "pr-1", "base", "head", "ACTIVE",
                "workspace-1", "trunk-1", "task-1");

        InvestigationReviewContext.Snapshot restored =
                FrozenReviewSubject.snapshot(review, snapshot(
                        "acme/frozen", 42, "main", "title", "body"));

        assertThat(restored.pr().taskId()).isEqualTo("task-1");
        assertThat(restored.pr().origin()).isEqualTo(PR.ORIGIN_TASK);
        assertThat(restored.pr().repo()).isEqualTo("acme/frozen");
        assertThat(restored.pr().remotePrNumber()).isEqualTo(42);
    }

    private ReviewRoundSnapshot snapshot(
            String repository, Integer number, String baseBranch,
            String title, String description)
    {
        String missingRoot = tempDir.resolve("cleaned-checkout").toString();
        return new ReviewRoundSnapshot(
                "round-1", repository, number, baseBranch, title, description,
                "base", "head", "diff --git a/src/A.java b/src/A.java",
                List.of(new DiffFile(
                        "src/A.java", "modified", 1, 1, "patch")),
                Map.of("src/A.java", "complete frozen body\n"),
                missingRoot, missingRoot,
                ReviewCapabilities.frozenChangedFiles(), 1L);
    }
}
