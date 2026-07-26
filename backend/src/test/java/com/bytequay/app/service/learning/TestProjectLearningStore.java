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
package com.bytequay.app.service.learning;

import com.bytequay.app.domain.PullRequestCommit;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Store-level tests against the real {@code V192} schema (full Flyway
 * migration on a temp SQLite file). Covers durable-run creation and the
 * catalog's no-duplicate-rerun idempotency.
 */
class TestProjectLearningStore
{
    @TempDir
    private Path tempDir;

    private JdbcTemplate jdbc;
    private ProjectLearningStore store;

    @BeforeEach
    void migrate()
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("learning.db") + "?foreign_keys=ON";
        Flyway.configure().dataSource(url, "", "").load().migrate();
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
                INSERT INTO workspaces (id, name, memory_md, is_scratch,
                    created_at_ms, updated_at_ms)
                VALUES ('ws-1', 'acme/widget', '', 0, 1, 1)
                """);
        store = new ProjectLearningStore(jdbc);
    }

    @Test
    void testProjectLearningMigrationChecksumMatchesReleasedSchema()
    {
        // V192 was installed from a conflict-resolution branch before this
        // code path was merged. Its text is immutable for existing databases.
        assertThat(jdbc.queryForObject("""
                SELECT checksum FROM flyway_schema_history
                WHERE version = '192'
                """, String.class)).isEqualTo("-1938762556");
    }

    @Test
    void testInsertAndRecoverResumableRun()
    {
        store.insertRun(run("run-1", "queued", null));
        assertThat(store.resumableRunIds()).containsExactly("run-1");
        assertThat(store.latestRun("ws-1", "acme/widget"))
                .get().extracting(ProjectLearningRun::state).isEqualTo("queued");

        // A rate-limited (partial) run is still resumable on restart.
        store.updateRun("run-1", "partial", "sha", "{}", "{}", null, "rate limit", 5L);
        assertThat(store.resumableRunIds()).containsExactly("run-1");

        store.updateRun("run-1", "caught-up", "sha", "{}", "{}", 9L, null, 9L);
        assertThat(store.resumableRunIds()).isEmpty();
    }

    @Test
    void testRerunDoesNotDuplicateSources()
    {
        store.insertRun(run("run-1", "cataloging", "{}"));
        store.upsertPrSource(source(42, "digest-a"));
        store.upsertPrSource(source(42, "digest-b"));  // same PR, re-cataloged
        store.upsertPrSource(source(43, "digest-c"));

        assertThat(store.countCataloged("ws-1", "acme/widget")).isEqualTo(2);
    }

    @Test
    void testChangedSourceDigestRequeuesAnalyzedPr()
    {
        store.upsertPrSource(source(42, "digest-a"));
        store.markSelected("ws-1", "acme/widget", 42, 3.5);
        store.markAnalyzed("ws-1", "acme/widget", 42, 9.0, "merge-sha", 100L);

        store.upsertPrSource(source(42, "digest-a"));
        assertThat(store.findPrSource("ws-1", "acme/widget", 42))
                .get().extracting(RepoPrSource::analysisState).isEqualTo("analyzed");

        store.upsertPrSource(source(42, "digest-b"));
        assertThat(store.findPrSource("ws-1", "acme/widget", 42))
                .get()
                .satisfies(source -> {
                    assertThat(source.analysisState()).isEqualTo("cataloged");
                    assertThat(source.analyzedAtMs()).isNull();
                    assertThat(source.priorityScore()).isNull();
                    assertThat(source.mergeSha()).isEqualTo("merge-sha");
                });
    }

    @Test
    void testIncompleteEvidenceCanBeRequeuedForAnotherFetch()
    {
        store.upsertPrSource(source(7, "digest-a"));
        store.markSelected("ws-1", "acme/widget", 7, 3.5);
        store.persistEvidence(bundle(List.of(), "partial:reviews"), 5.0, 100L);
        store.markAnalyzed("ws-1", "acme/widget", 7, 9.0, "merge", 100L);

        assertThat(store.requeueIncompleteEvidence("ws-1", "acme/widget")).isOne();
        assertThat(store.findPrSource("ws-1", "acme/widget", 7))
                .get().extracting(RepoPrSource::analysisState).isEqualTo("cataloged");
    }

    @Test
    void testCursorPersistenceRoundTrips()
    {
        store.insertRun(run("run-1", "cataloging", null));
        String cursor = "{\"partitions\":[{\"from\":\"2020-01-01\",\"to\":\"2020-06-30\","
                + "\"nextPage\":3,\"exhausted\":false}]}";
        store.updateRun("run-1", "cataloging", "sha", cursor, "{}", null, null, 5L);

        Optional<ProjectLearningRun> reloaded = store.findRun("run-1");
        assertThat(reloaded).get().extracting(ProjectLearningRun::catalogCursor).isEqualTo(cursor);
    }

    @Test
    void testSelectionAndAnalysisLifecycle()
    {
        store.insertRun(run("run-1", "cataloging", "{}"));
        store.upsertPrSource(source(42, "digest-a"));

        store.markSelected("ws-1", "acme/widget", 42, 3.5);
        assertThat(store.selectedSources("ws-1", "acme/widget", 10))
                .singleElement()
                .satisfies(s -> {
                    assertThat(s.prNumber()).isEqualTo(42);
                    assertThat(s.priorityScore()).isEqualTo(3.5);
                });

        store.markAnalyzed("ws-1", "acme/widget", 42, 9.0, "merge-sha", 100L);
        assertThat(store.countAnalyzed("ws-1", "acme/widget")).isEqualTo(1);
        assertThat(store.selectedSources("ws-1", "acme/widget", 10)).isEmpty();
    }

    @Test
    void testEvidenceRoundTripsAgainstRealSchema()
    {
        store.persistEvidence(bundle(List.of(
                ref("file", null, "repoSha", "core/Scheduler.java"),
                ref("commit", "c1", "c1", null))), 5.0, 100L);

        assertThat(store.countEvidenceRefs("ws-1", "acme/widget", 7)).isEqualTo(2);
        assertThat(jdbc.queryForObject(
                "SELECT overall_completeness FROM repo_pr_evidence_bundle WHERE pr_number = 7",
                String.class)).isEqualTo("complete");

        // Re-persisting replaces rather than duplicates.
        store.persistEvidence(bundle(List.of(ref("file", null, "repoSha", "core/Only.java"))), 5.0, 200L);
        assertThat(store.countEvidenceRefs("ws-1", "acme/widget", 7)).isEqualTo(1);
    }

    @Test
    void testPersistRejectsRefCrossingPinnedSnapshot()
    {
        PrEvidenceBundle crossing = bundle(List.of(
                ref("thread", "9", "sha-from-a-later-push", "core/X.java")));
        assertThatThrownBy(() -> store.persistEvidence(crossing, 1.0, 100L))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("crosses pinned repository SHA");
    }

    private static PrEvidenceBundle bundle(List<PrEvidenceBundle.EvidenceRef> refs)
    {
        return bundle(refs, "complete");
    }

    private static PrEvidenceBundle bundle(
            List<PrEvidenceBundle.EvidenceRef> refs, String completeness)
    {
        return new PrEvidenceBundle("ws-1", "acme/widget", 7, "alice",
                "Title", "Body", "base", "head", "merge", "repoSha",
                List.of(), List.of(), List.of(new PullRequestCommit("c1", "alice", "alice",
                        Instant.parse("2020-01-01T00:00:00Z"), "msg")),
                List.of(), List.of(),
                Map.of("reviews", completeness), completeness, refs, List.of());
    }

    private static PrEvidenceBundle.EvidenceRef ref(
            String kind, String githubId, String commitSha, String filePath)
    {
        return new PrEvidenceBundle.EvidenceRef(kind, githubId, null, commitSha,
                filePath, null, null, "digest");
    }

    private static ProjectLearningRun run(String id, String state, String cursor)
    {
        return new ProjectLearningRun(id, "ws-1", "acme/widget", "clone", state,
                null, cursor, "{}", 1, 1, 1, null, null);
    }

    private static RepoPrSource source(int number, String digest)
    {
        return new RepoPrSource("ws-1", "acme/widget", number, "2020-01-02T00:00:00Z",
                null, "{}", "{\"catalog\":\"complete\"}", digest, null, "cataloged", 1, null, null);
    }
}
