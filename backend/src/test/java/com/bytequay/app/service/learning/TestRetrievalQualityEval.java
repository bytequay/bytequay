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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static com.bytequay.app.testing.MigratedSqliteDatabase.copyTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The retrieval half of the Phase 6 quality evaluation, runnable on every
 * build: a gold corpus of knowledge items and task-shaped queries measures
 * relevant-knowledge recall at top K and the unsupported-item rate (rows
 * that are pending/decayed/retired must never be retrieved). The corpus is
 * a test resource so distilled real-repository lessons can extend it
 * without touching this harness.
 *
 * <p>Task-level A/B measures (tool calls, tokens, review revisions with
 * learning on/off) need accumulated real usage and stay a manual protocol.
 */
class TestRetrievalQualityEval
{
    private static final Logger log = LoggerFactory.getLogger(TestRetrievalQualityEval.class);

    private static final int TOP_K = 8;
    /** Recall floor over the gold queries — the retrieval acceptance bar. */
    private static final double RECALL_FLOOR = 0.99;

    @TempDir
    private Path tempDir;

    private KnowledgeItemStore store;
    private KnowledgeRetrievalService retrieval;
    private JsonNode corpus;

    @BeforeEach
    void setUp()
            throws IOException
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("eval.db")
                + "?foreign_keys=ON&busy_timeout=5000";
        copyTo(tempDir.resolve("eval.db"));
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
                INSERT INTO workspaces (id, name, memory_md, is_scratch,
                    created_at_ms, updated_at_ms)
                VALUES ('ws-eval', 'acme/widget', '', 0, 1, 1)
                """);
        ObjectMapper mapper = new ObjectMapper();
        store = new KnowledgeItemStore(jdbc, mapper);
        KnowledgeSearchIndex index = new KnowledgeSearchIndex(jdbc);
        try (InputStream in = getClass().getResourceAsStream(
                "/learning/retrieval-gold-corpus.json")) {
            corpus = mapper.readTree(in);
        }
        for (JsonNode item : corpus.path("items")) {
            seed(item);
        }
        index.initialize();
        retrieval = new KnowledgeRetrievalService(
                jdbc, store, index, new SqliteMemoryItemStore(jdbc, mapper));
    }

    @Test
    void testGoldCorpusRecallAndInactiveLeakRate()
    {
        Set<String> inactive = new HashSet<>();
        for (JsonNode item : corpus.path("items")) {
            if (!"active".equals(item.path("lifecycle").asText())) {
                inactive.add(item.path("id").asText());
            }
        }

        int relevantTotal = 0;
        int relevantHit = 0;
        int retrievedTotal = 0;
        int inactiveLeaks = 0;
        List<String> misses = new ArrayList<>();

        for (JsonNode query : corpus.path("queries")) {
            String question = query.path("query").asText();
            String audience = query.path("audience").isTextual()
                    ? query.path("audience").asText() : null;
            Set<String> retrieved = retrieval.retrieve(
                            "ws-eval", "acme/widget", question, audience, TOP_K)
                    .stream()
                    .map(entry -> entry.item().id())
                    .collect(Collectors.toSet());
            retrievedTotal += retrieved.size();
            for (String id : retrieved) {
                if (inactive.contains(id)) {
                    inactiveLeaks++;
                }
            }
            for (JsonNode expected : query.path("relevant")) {
                relevantTotal++;
                if (retrieved.contains(expected.asText())) {
                    relevantHit++;
                }
                else {
                    misses.add(question + " -> " + expected.asText());
                }
            }
        }

        double recall = relevantTotal == 0 ? 1.0 : (double) relevantHit / relevantTotal;
        log.info("retrieval eval: recall@{}={} ({}/{}), retrieved={}, inactiveLeaks={}",
                TOP_K, recall, relevantHit, relevantTotal, retrievedTotal, inactiveLeaks);

        assertThat(misses).as("gold queries missing their relevant knowledge").isEmpty();
        assertThat(recall).isGreaterThanOrEqualTo(RECALL_FLOOR);
        // Inactive/pending/retired knowledge must never steer an agent.
        assertThat(inactiveLeaks).isZero();
    }

    private void seed(JsonNode item)
    {
        List<KnowledgeItem.Applicability> tags = new ArrayList<>();
        for (JsonNode path : item.path("paths")) {
            tags.add(new KnowledgeItem.Applicability("path", path.asText()));
        }
        for (JsonNode symbol : item.path("symbols")) {
            tags.add(new KnowledgeItem.Applicability("symbol", symbol.asText()));
        }
        List<String> audiences = new ArrayList<>();
        item.path("audiences").forEach(audience -> audiences.add(audience.asText()));
        String statement = item.path("statement").asText();
        store.insert(new KnowledgeItem(
                        item.path("id").asText(), "ws-eval", "acme/widget",
                        item.path("kind").asText(), item.path("title").asText(),
                        statement, null, audiences,
                        item.path("confidence").asText("medium"),
                        item.path("lifecycle").asText("pending"),
                        null, null, "pr-learning",
                        KnowledgeItemStore.statementDigest(statement), "{}", 1, 1),
                List.of(new KnowledgeItem.Provenance(
                        "pr", "acme/widget#1", null, null, null, null)),
                tags);
    }
}
