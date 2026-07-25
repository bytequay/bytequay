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

import com.bytequay.app.domain.KnowledgeItem;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FTS5 availability with the bundled driver, trigger-driven sync, and the
 * LIKE fallback returning the same rows when FTS is unavailable.
 */
class TestKnowledgeSearchIndex
{
    @TempDir
    private Path tempDir;

    private JdbcTemplate jdbc;
    private KnowledgeItemStore store;
    private KnowledgeSearchIndex index;

    @BeforeEach
    void setUp()
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("search.db")
                + "?foreign_keys=ON&busy_timeout=5000";
        Flyway.configure().dataSource(url, "", "").load().migrate();
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
                INSERT INTO workspaces (id, name, memory_md, is_scratch,
                    created_at_ms, updated_at_ms)
                VALUES ('ws-1', 'acme/widget', '', 0, 1, 1)
                """);
        store = new KnowledgeItemStore(jdbc, new ObjectMapper());
        index = new KnowledgeSearchIndex(jdbc);
    }

    @Test
    void testFtsIsAvailableWithBundledDriverAndFindsByStatement()
    {
        store.insert(item("k-sched", "active",
                "Scheduler slots are released only after the turn completes."),
                List.of(), List.of());
        index.initialize();

        assertThat(index.degraded()).isFalse();
        assertThat(index.searchActive("ws-1", "acme/widget", List.of("scheduler"), 5))
                .containsExactly("k-sched");
        // Inactive rows never surface.
        store.insert(item("k-pending", "pending",
                "Scheduler queue drains in waves."), List.of(), List.of());
        assertThat(index.searchActive("ws-1", "acme/widget", List.of("scheduler"), 5))
                .containsExactly("k-sched");
    }

    @Test
    void testTriggersKeepIndexInSyncAfterInitialize()
    {
        index.initialize();
        store.insert(item("k-late", "active",
                "Retry scheduling must be idempotent."), List.of(), List.of());

        assertThat(index.searchActive("ws-1", "acme/widget", List.of("idempotent"), 5))
                .containsExactly("k-late");

        store.delete("k-late");
        assertThat(index.searchActive("ws-1", "acme/widget", List.of("idempotent"), 5))
                .isEmpty();
    }

    @Test
    void testFallbackFindsSameRowsWhenFtsUnavailable()
    {
        store.insert(item("k-sched", "active",
                "Scheduler slots are released only after the turn completes."),
                List.of(), List.of());
        // Never initialized: degraded, LIKE path serves the query.
        assertThat(index.degraded()).isTrue();
        assertThat(index.searchActive("ws-1", "acme/widget", List.of("scheduler"), 5))
                .containsExactly("k-sched");
    }

    private static KnowledgeItem item(String id, String lifecycle, String statement)
    {
        return new KnowledgeItem(
                id, "ws-1", "acme/widget", "recurring-concern", null, statement,
                null, List.of("dev"), "medium", lifecycle, null, null,
                "pr-learning", null, "{}", 1, 1);
    }
}
