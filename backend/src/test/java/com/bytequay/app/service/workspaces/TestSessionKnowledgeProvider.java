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
package com.bytequay.app.service.workspaces;

import com.bytequay.app.domain.KnowledgeItem;
import com.bytequay.app.repository.sqlite.KnowledgeItemStore;
import com.bytequay.app.repository.sqlite.KnowledgeSearchIndex;
import com.bytequay.app.repository.sqlite.SqliteMemoryItemStore;
import com.bytequay.app.service.learning.KnowledgeRetrievalService;
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
 * The bounded session projection: a knowledge base far past any prompt limit
 * still renders a small capsule + brain + retrieved slice, pending rows never
 * reach an agent, and the inserted item ids are recorded for the inspector.
 */
class TestSessionKnowledgeProvider
{
    @TempDir
    private Path tempDir;

    private JdbcTemplate jdbc;
    private KnowledgeItemStore store;
    private SessionKnowledgeProvider provider;

    @BeforeEach
    void setUp()
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("session.db")
                + "?foreign_keys=ON&busy_timeout=5000";
        Flyway.configure().dataSource(url, "", "").load().migrate();
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
                INSERT INTO workspaces (id, name, memory_md, is_scratch,
                    created_at_ms, updated_at_ms)
                VALUES ('ws-1', 'acme/widget', '## Decisions\n\n- Ship weekly.', 0, 1, 1)
                """);
        jdbc.update("""
                INSERT INTO repo_project_capsule (
                    workspace_id, repo, capsule_md, source_digest, generated_at_ms)
                VALUES ('ws-1', 'acme/widget', '# Project capsule\n\nWidget maker.', 'd', 1)
                """);
        ObjectMapper mapper = new ObjectMapper();
        store = new KnowledgeItemStore(jdbc, mapper);
        KnowledgeSearchIndex index = new KnowledgeSearchIndex(jdbc);
        index.initialize();
        KnowledgeRetrievalService retrieval = new KnowledgeRetrievalService(
                jdbc, store, index, new SqliteMemoryItemStore(jdbc, mapper));
        provider = new SessionKnowledgeProvider(jdbc, mapper, retrieval);
    }

    @Test
    void testHugeKnowledgeBaseStillRendersBounded()
    {
        String filler = "x".repeat(1_500);
        for (int i = 0; i < 200; i++) {
            insert("k-" + i, "active", "Fact " + i + " about scheduling. " + filler);
        }

        String rendered = provider.render("ws-1", "dev", "scheduling work");

        // 200 x 1.5k chars of active knowledge exists; the projection stays
        // near the caps instead of concatenating everything.
        assertThat(rendered.length()).isLessThan(20_000);
        assertThat(rendered).contains("# Project capsule");
        assertThat(rendered).contains("Ship weekly.");
        assertThat(rendered).contains("# Knowledge base (dev)");
    }

    @Test
    void testPendingKnowledgeNeverReachesTheSession()
    {
        insert("k-pending", "pending", "A pending marker fact about scheduling.");

        assertThat(provider.render("ws-1", "dev", "scheduling"))
                .doesNotContain("pending marker fact");
    }

    @Test
    void testProjectionRecordsInsertedItemIds()
    {
        insert("k-1", "active", "Scheduler slots are bounded per lane.");

        provider.render("ws-1", "dev", "scheduler slots");

        String ids = jdbc.queryForObject("""
                SELECT item_ids_json FROM session_context_projection
                WHERE workspace_id = 'ws-1' AND audience = 'dev'
                """, String.class);
        assertThat(ids).contains("k-1");
    }

    @Test
    void testUnknownAudienceRendersNothing()
    {
        assertThat(provider.render("ws-1", "nope", null)).isEmpty();
        assertThat(provider.render(null, "dev", null)).isEmpty();
    }

    private void insert(String id, String lifecycle, String statement)
    {
        store.insert(new KnowledgeItem(
                id, "ws-1", "acme/widget", "recurring-concern", null, statement,
                null, List.of("dev"), "medium", lifecycle, null, null,
                "pr-learning", KnowledgeItemStore.statementDigest(statement),
                "{}", 1, 1), List.of(), List.of());
    }
}
