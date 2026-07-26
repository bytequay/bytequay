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

import com.bytequay.app.domain.KnowledgeItem;
import com.bytequay.app.repository.sqlite.KnowledgeItemStore;
import com.bytequay.app.repository.sqlite.KnowledgeSearchIndex;
import com.bytequay.app.repository.sqlite.SqliteMemoryItemStore;
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
 * The task-scoped retrieval acceptance: a scheduler question retrieves
 * scheduler knowledge (not unrelated UI conventions), pending rows never
 * surface, and audience filters hold.
 */
class TestKnowledgeRetrievalService
{
    @TempDir
    private Path tempDir;

    private KnowledgeItemStore store;
    private KnowledgeRetrievalService retrieval;

    @BeforeEach
    void setUp()
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("retrieval.db")
                + "?foreign_keys=ON&busy_timeout=5000";
        Flyway.configure().dataSource(url, "", "").load().migrate();
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
                INSERT INTO workspaces (id, name, memory_md, is_scratch,
                    created_at_ms, updated_at_ms)
                VALUES ('ws-1', 'acme/widget', '', 0, 1, 1)
                """);
        ObjectMapper mapper = new ObjectMapper();
        store = new KnowledgeItemStore(jdbc, mapper);
        KnowledgeSearchIndex index = new KnowledgeSearchIndex(jdbc);
        index.initialize();
        retrieval = new KnowledgeRetrievalService(
                jdbc, store, index, new SqliteMemoryItemStore(jdbc, mapper));
    }

    @Test
    void testSchedulerQueryRetrievesSchedulerKnowledgeNotUiConventions()
    {
        insert("k-sched", "active", "high",
                "Scheduler slots must be released after every turn.",
                List.of(new KnowledgeItem.Applicability("path", "core/scheduler/Slots.java")));
        insert("k-ui", "active", "high",
                "Buttons use sentence case in dialogs.",
                List.of(new KnowledgeItem.Applicability("path", "frontend/src/ui/Button.tsx")));

        List<KnowledgeRetrievalService.Retrieved> hits = retrieval.retrieve(
                "ws-1", "acme/widget", "fix scheduler slot release on cancellation",
                "dev", 8);

        assertThat(hits).extracting(entry -> entry.item().id())
                .contains("k-sched")
                .doesNotContain("k-ui");
    }

    @Test
    void testPendingKnowledgeNeverSurfaces()
    {
        insert("k-pending", "pending", "high",
                "Scheduler queue drains in waves.", List.of());

        assertThat(retrieval.retrieve("ws-1", "acme/widget", "scheduler waves", null, 8))
                .isEmpty();
    }

    @Test
    void testExactPathMatchOutranksLooseTextMatch()
    {
        insert("k-text", "active", "high",
                "The scheduler is documented in the architecture guide.", List.of());
        insert("k-exact", "active", "low",
                "Slot release must happen in a finally block.",
                List.of(new KnowledgeItem.Applicability(
                        "path", "core/scheduler/Slots.java")));

        List<KnowledgeRetrievalService.Retrieved> hits = retrieval.retrieve(
                "ws-1", "acme/widget",
                "change core/scheduler/Slots.java scheduler behaviour", null, 8);

        assertThat(hits.getFirst().item().id()).isEqualTo("k-exact");
    }

    @Test
    void testModuleApplicabilityMatchesDescendantsButNotSiblingPrefixes()
    {
        insert("k-module", "active", "high",
                "Preserve connector metadata compatibility.",
                List.of(new KnowledgeItem.Applicability(
                        "module", "plugin/trino-iceberg")));
        insert("k-sibling", "active", "high",
                "Unrelated sibling module guidance.",
                List.of(new KnowledgeItem.Applicability(
                        "module", "plugin/trino-iceberg-extra")));

        List<KnowledgeRetrievalService.Retrieved> descendantHits = retrieval.retrieve(
                "ws-1", "acme/widget",
                "plugin/trino-iceberg/src/main/java/io/trino/plugin/iceberg/IcebergMetadata.java",
                "review", 8);
        List<KnowledgeRetrievalService.Retrieved> parentHits = retrieval.retrieve(
                "ws-1", "acme/widget", "plugin/trino-iceberg", "review", 8);

        assertThat(descendantHits).extracting(entry -> entry.item().id())
                .contains("k-module")
                .doesNotContain("k-sibling");
        assertThat(parentHits).extracting(entry -> entry.item().id())
                .contains("k-module")
                .doesNotContain("k-sibling");
    }

    @Test
    void testBlankQueryReturnsTopActiveRows()
    {
        insert("k-1", "active", "high", "Always run the verifier before commit.", List.of());
        insert("k-2", "active", "low", "Prefer records over beans.", List.of());

        List<KnowledgeRetrievalService.Retrieved> hits =
                retrieval.retrieve("ws-1", "acme/widget", "", null, 8);

        assertThat(hits).hasSize(2);
        assertThat(hits.getFirst().item().id()).isEqualTo("k-1");
    }

    @Test
    void testAudienceFilterHolds()
    {
        insert("k-dev", "active", "high", "Scheduler slots are bounded.",
                List.of(), List.of("dev"));

        assertThat(retrieval.retrieve("ws-1", "acme/widget", "scheduler", "review", 8))
                .isEmpty();
        assertThat(retrieval.retrieve("ws-1", "acme/widget", "scheduler", "dev", 8))
                .hasSize(1);
    }

    private void insert(
            String id, String lifecycle, String confidence, String statement,
            List<KnowledgeItem.Applicability> applicability)
    {
        insert(id, lifecycle, confidence, statement, applicability, List.of("dev", "review"));
    }

    private void insert(
            String id, String lifecycle, String confidence, String statement,
            List<KnowledgeItem.Applicability> applicability, List<String> audiences)
    {
        store.insert(new KnowledgeItem(
                id, "ws-1", "acme/widget", "recurring-concern", null, statement,
                null, audiences, confidence, lifecycle, null, null,
                "pr-learning", KnowledgeItemStore.statementDigest(statement),
                "{}", 1, 1), List.of(), applicability);
    }
}
