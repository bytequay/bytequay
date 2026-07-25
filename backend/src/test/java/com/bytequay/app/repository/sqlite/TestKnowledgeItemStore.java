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
import org.assertj.core.groups.Tuple;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestKnowledgeItemStore
{
    @TempDir
    private Path tempDir;

    private JdbcTemplate jdbc;
    private KnowledgeItemStore store;

    @BeforeEach
    void setUp()
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("knowledge.db")
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
    }

    @Test
    void testInsertAndDigestLookup()
    {
        String digest = KnowledgeItemStore.statementDigest("  A Split is the unit.  ");
        store.insert(item("k-1", "pr-learning", "pending", digest),
                List.of(new KnowledgeItem.Provenance("pr", "acme/widget#7",
                        "merge", null, null, null)),
                List.of(new KnowledgeItem.Applicability("path", "core/File.java")));

        // Normalization makes case/whitespace variants the same digest.
        assertThat(KnowledgeItemStore.statementDigest("a split is  the unit."))
                .isEqualTo(digest);
        assertThat(store.findByDigest("ws-1", "acme/widget", digest)).isPresent();
        assertThat(store.applicability("k-1"))
                .containsExactly(new KnowledgeItem.Applicability("path", "core/File.java"));
    }

    @Test
    void testProvenanceMergeIsIdempotent()
    {
        store.insert(item("k-1", "pr-learning", "pending", "d1"),
                List.of(new KnowledgeItem.Provenance("pr", "acme/widget#7",
                        null, null, null, null)),
                List.of());
        store.addProvenance("k-1", List.of(
                new KnowledgeItem.Provenance("pr", "acme/widget#7", null, null, null, null),
                new KnowledgeItem.Provenance("pr", "acme/widget#9", null, null, null, null)));

        assertThat(store.provenance("k-1")).hasSize(2);
        assertThat(store.distinctPrSources("k-1")).isEqualTo(2);
    }

    @Test
    void testEditReplacesCurationButKeepsLearnedEvidence()
    {
        store.insert(item("k-1", "user", "active", null),
                List.of(
                        new KnowledgeItem.Provenance("thread", "9001", null, null, null, null),
                        new KnowledgeItem.Provenance("distill-operation", "op-1",
                                null, null, null, null)),
                List.of());

        store.replaceCurationProvenance("k-1", List.of(
                new KnowledgeItem.Provenance("distill-operation", "op-2", null, null, null, null)));

        assertThat(store.provenance("k-1"))
                .extracting(KnowledgeItem.Provenance::sourceKind,
                        KnowledgeItem.Provenance::sourceRef)
                .containsExactlyInAnyOrder(
                        Tuple.tuple("thread", "9001"),
                        Tuple.tuple("distill-operation", "op-2"));
    }

    @Test
    void testManagedListingExcludesLearnedRows()
    {
        store.insert(item("k-user", "user", "active", null), List.of(), List.of());
        store.insert(item("k-learned", "pr-learning", "active", null), List.of(), List.of());

        assertThat(store.listManaged("ws-1"))
                .extracting(KnowledgeItem::id)
                .containsExactly("k-user");
        assertThat(store.countByCreator("ws-1", "acme/widget", "pr-learning")).isEqualTo(1);
    }

    @Test
    void testLifecycleTransitionAndGlossaryListing()
    {
        store.insert(glossary("k-g", "pending"), List.of(), List.of());
        assertThat(store.listActiveGlossary()).isEmpty();

        store.setLifecycle("k-g", "active", "sha-1", 99);

        assertThat(store.listActiveGlossary()).extracting(KnowledgeItem::id)
                .containsExactly("k-g");
        KnowledgeItem item = store.findById("k-g").orElseThrow();
        assertThat(item.validatedAtCommit()).isEqualTo("sha-1");
        assertThat(item.lastVerifiedAtMs()).isEqualTo(99);
    }

    @Test
    void testDeleteCascadesProvenanceAndApplicability()
    {
        store.insert(item("k-1", "user", "active", null),
                List.of(new KnowledgeItem.Provenance("pr", "acme/widget#7",
                        null, null, null, null)),
                List.of(new KnowledgeItem.Applicability("symbol", "Connector")));

        store.delete("k-1");

        assertThat(store.findById("k-1")).isEmpty();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM knowledge_provenance", Integer.class)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM knowledge_applicability", Integer.class)).isZero();
    }

    private static KnowledgeItem item(
            String id, String createdBy, String lifecycle, String digest)
    {
        return new KnowledgeItem(
                id, "ws-1", "acme/widget", "recurring-concern", "Title",
                "A Split is the unit.", null, List.of("dev"), "medium", lifecycle,
                null, null, createdBy, digest, "{}", 1, 1);
    }

    private static KnowledgeItem glossary(String id, String lifecycle)
    {
        return new KnowledgeItem(
                id, "ws-1", "acme/widget", "glossary", "Split",
                "A split is the smallest schedulable unit.", null, List.of("plan"),
                "high", lifecycle, null, null, "pr-learning", null, "{}", 1, 1);
    }
}
