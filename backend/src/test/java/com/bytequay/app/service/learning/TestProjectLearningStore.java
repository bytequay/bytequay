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

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

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
    void testCursorPersistenceRoundTrips()
    {
        store.insertRun(run("run-1", "cataloging", null));
        String cursor = "{\"partitions\":[{\"from\":\"2020-01-01\",\"to\":\"2020-06-30\","
                + "\"nextPage\":3,\"exhausted\":false}]}";
        store.updateRun("run-1", "cataloging", "sha", cursor, "{}", null, null, 5L);

        Optional<ProjectLearningRun> reloaded = store.findRun("run-1");
        assertThat(reloaded).get().extracting(ProjectLearningRun::catalogCursor).isEqualTo(cursor);
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
