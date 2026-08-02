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
import com.bytequay.app.repository.MemoryItemStore;
import com.bytequay.app.repository.sqlite.KnowledgeItemStore;
import com.bytequay.app.service.concepts.ConceptRegistry;
import com.bytequay.app.service.concepts.ConceptSpec;
import com.bytequay.app.service.workspaces.MemoryItemService;
import com.bytequay.app.testing.SqliteTestPools;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static com.bytequay.app.testing.MigratedSqliteDatabase.copyTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Activation, dedup, conflict, currentness, and routing rules over a real
 * SQLite canonical store. The extraction model is out of scope — lessons
 * arrive pre-parsed.
 */
@ExtendWith(SqliteTestPools.class)
class TestKnowledgeIngestor
{
    @TempDir
    private Path tempDir;

    private JdbcTemplate jdbc;
    private KnowledgeItemStore store;
    private MemoryItemService memoryItems;
    private ConceptRegistry concepts;
    private KnowledgeIngestor ingestor;
    private Path clone;

    @BeforeEach
    void setUp()
            throws IOException
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("knowledge.db")
                + "?foreign_keys=ON&busy_timeout=5000";
        copyTo(tempDir.resolve("knowledge.db"));
        DataSource dataSource = SqliteTestPools.open(url);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
                INSERT INTO workspaces (id, name, memory_md, is_scratch,
                    created_at_ms, updated_at_ms)
                VALUES ('ws-1', 'acme/widget', '', 0, 1, 1)
                """);
        store = new KnowledgeItemStore(jdbc, new ObjectMapper());
        memoryItems = mock(MemoryItemService.class);
        concepts = mock(ConceptRegistry.class);
        ingestor = new KnowledgeIngestor(store, memoryItems, concepts, new ObjectMapper());
        clone = tempDir.resolve("clone");
        Files.createDirectories(clone.resolve("core/spi"));
        Files.writeString(clone.resolve("core/spi/Connector.java"), "class Connector {}");
    }

    @Test
    void testVerifiedChainWithCurrentPathActivates()
    {
        KnowledgeIngestor.IngestResult result = ingestor.ingest(
                "ws-1", bundle(verifiedChain()), List.of(lesson(
                        "recurring-concern", "Close split sources on cancellation.",
                        List.of("core/spi/Connector.java"), false)), clone);

        assertThat(result.newCandidates()).isEqualTo(1);
        List<KnowledgeItem> active = store.listByLifecycle("ws-1", "active");
        assertThat(active).hasSize(1);
        assertThat(active.getFirst().validatedAtCommit()).isEqualTo("repoSha");
        // The PR itself plus the cited thread ref.
        assertThat(store.provenance(active.getFirst().id()))
                .extracting(KnowledgeItem.Provenance::sourceKind)
                .contains("pr", "thread");
    }

    @Test
    void testRestrictedKindNeedsExplicitSourceLanguage()
    {
        ingestor.ingest("ws-1", bundle(verifiedChain()), List.of(lesson(
                "compatibility-contract", "SPI signatures stay frozen in a release line.",
                List.of("core/spi/Connector.java"), false)), clone);
        assertThat(store.listByLifecycle("ws-1", "pending")).hasSize(1);

        ingestor.ingest("ws-1", bundle(verifiedChain()), List.of(lesson(
                "compatibility-contract", "Plugin loaders must tolerate unknown fields.",
                List.of("core/spi/Connector.java"), true)), clone);
        assertThat(store.listByLifecycle("ws-1", "active")).hasSize(1);
    }

    @Test
    void testNoVerifiedChainStaysPending()
    {
        ingestor.ingest("ws-1", bundle(List.of()), List.of(lesson(
                "recurring-concern", "Watch for unclosed cursors.",
                List.of("core/spi/Connector.java"), false)), clone);
        assertThat(store.listByLifecycle("ws-1", "pending")).hasSize(1);
        assertThat(store.listByLifecycle("ws-1", "active")).isEmpty();
    }

    @Test
    void testAbsentAnchorsCannotActivate()
    {
        ingestor.ingest("ws-1", bundle(verifiedChain()), List.of(lesson(
                "recurring-concern", "This code no longer exists.",
                List.of("gone/Removed.java"), false)), clone);

        List<KnowledgeItem> pending = store.listByLifecycle("ws-1", "pending");
        assertThat(pending).hasSize(1);
        assertThat(pending.getFirst().validatedAtCommit()).isNull();
        assertThat(pending.getFirst().countersJson()).contains("possiblyStale");
    }

    @Test
    void testPartialEvidenceNeitherActivatesNorCountsAsConfirmation()
    {
        ingestor.ingest("ws-1", bundle(7, verifiedChain(), "partial:reviews"), List.of(lesson(
                "recurring-concern", "Retry scheduling must be idempotent.",
                List.of("core/spi/Connector.java"), false)), clone);
        assertThat(store.listByLifecycle("ws-1", "active")).isEmpty();

        ingestor.ingest("ws-1", bundle(9, List.of(), "complete"), List.of(lesson(
                "recurring-concern", "Retry scheduling must be idempotent.",
                List.of("core/spi/Connector.java"), false)), clone);

        assertThat(store.listByLifecycle("ws-1", "active")).isEmpty();
        assertThat(store.listByLifecycle("ws-1", "pending")).hasSize(1);
    }

    @Test
    void testUnknownCurrentnessCannotActivateOnIndependentConfirmation()
    {
        ingestor.ingest("ws-1", bundle(7, List.of()), List.of(lesson(
                "recurring-concern", "Retry scheduling must be idempotent.",
                List.of(), false)), clone);
        ingestor.ingest("ws-1", bundle(9, List.of()), List.of(lesson(
                "recurring-concern", "Retry scheduling must be idempotent.",
                List.of(), false)), clone);

        assertThat(store.listByLifecycle("ws-1", "active")).isEmpty();
        assertThat(store.listByLifecycle("ws-1", "pending")).hasSize(1);
    }

    @Test
    void testEquivalentLessonMergesProvenanceAndSecondPrActivates()
    {
        // First sighting from PR 7: pending (no verified chain).
        ingestor.ingest("ws-1", bundle(7, List.of()), List.of(lesson(
                "recurring-concern", "Retry scheduling must be idempotent.",
                List.of("core/spi/Connector.java"), false)), clone);
        assertThat(store.listByLifecycle("ws-1", "pending")).hasSize(1);

        // Same statement from PR 9: merges, and the second independent merged
        // outcome activates the item.
        KnowledgeIngestor.IngestResult second = ingestor.ingest(
                "ws-1", bundle(9, List.of()), List.of(lesson(
                        "recurring-concern", "Retry scheduling must be idempotent.",
                        List.of("core/spi/Connector.java"), false)), clone);

        assertThat(second.newCandidates()).isZero();
        assertThat(second.merged()).isEqualTo(1);
        List<KnowledgeItem> active = store.listByLifecycle("ws-1", "active");
        assertThat(active).hasSize(1);
        assertThat(store.distinctPrSources(active.getFirst().id())).isEqualTo(2);
    }

    @Test
    void testConflictKeepsBothPendingAndMarksExisting()
    {
        ingestor.ingest("ws-1", bundle(verifiedChain()), List.of(lesson(
                "recurring-concern", "Always flush before close.",
                List.of("core/spi/Connector.java"), false)), clone);
        String existingId = store.listByLifecycle("ws-1", "active").getFirst().id();

        ExtractedLesson conflicting = new ExtractedLesson(
                "recurring-concern", null, "Never flush before close.", null,
                List.of(), List.of("core/spi/Connector.java"), List.of(), List.of(),
                List.of("dev"), List.of(0), false, "medium", null,
                List.of(existingId), "knowledge", null);
        ingestor.ingest("ws-1", bundle(verifiedChain()), List.of(conflicting), clone);

        assertThat(store.listByLifecycle("ws-1", "pending")).hasSize(1);
        assertThat(store.findById(existingId).orElseThrow().countersJson())
                .contains("conflictsWith");
    }

    @Test
    void testWorkspaceMemoryRouteProposesInsteadOfStoring()
    {
        ExtractedLesson decision = new ExtractedLesson(
                "build-test-rule", null, "Run mvn verify before every commit.", null,
                List.of(), List.of(), List.of(), List.of(), List.of("dev"),
                List.of(0), true, "high", null, List.of(), "workspace-memory", "CONVENTION");
        KnowledgeIngestor.IngestResult result = ingestor.ingest(
                "ws-1", bundle(verifiedChain()), List.of(decision), clone);

        assertThat(result.memoryProposals()).isEqualTo(1);
        ArgumentCaptor<MemoryItemStore.NewItem> proposed =
                ArgumentCaptor.forClass(MemoryItemStore.NewItem.class);
        verify(memoryItems).propose(proposed.capture());
        assertThat(proposed.getValue().sources())
                .singleElement()
                .satisfies(source -> assertThat(source.prRef()).isEqualTo("acme/widget#7"));
        assertThat(store.listByLifecycle("ws-1", "pending")).isEmpty();
        assertThat(store.listByLifecycle("ws-1", "active")).isEmpty();
    }

    @Test
    void testActiveGlossaryRegistersRepoScopedConcept()
    {
        ExtractedLesson glossary = new ExtractedLesson(
                "glossary", "Split", "A split is the smallest schedulable unit of work.",
                null, List.of(), List.of("core/spi/Connector.java"), List.of(),
                List.of("split"), List.of("plan"), List.of(0), true, "high",
                null, List.of(), "knowledge", null);
        ingestor.ingest("ws-1", bundle(verifiedChain()), List.of(glossary), clone);

        ArgumentCaptor<ConceptSpec> spec = ArgumentCaptor.forClass(ConceptSpec.class);
        verify(concepts).registerRuntime(eq("acme/widget"), spec.capture());
        assertThat(spec.getValue().name()).isEqualTo("Split");
        assertThat(spec.getValue().aka()).containsExactly("split");
    }

    @Test
    void testMergedRevertDecaysLessonsCitingTheRevertedPr()
    {
        ingestor.ingest("ws-1", bundle(7, verifiedChain()), List.of(lesson(
                "recurring-concern", "Always flush before close.",
                List.of("core/spi/Connector.java"), false)), clone);
        assertThat(store.listByLifecycle("ws-1", "active")).hasSize(1);

        PrEvidenceBundle revert = new PrEvidenceBundle("ws-1", "acme/widget", 11, "bob",
                "Revert \"Always flush\"", "Reverts acme/widget#7",
                "base", "head", "merge", "repoSha",
                List.of(), List.of(), List.of(), List.of(), List.of(),
                Map.of("reviews", "complete"), "complete", List.of(), List.of());
        ingestor.ingest("ws-1", revert, List.of(), clone);

        assertThat(store.listByLifecycle("ws-1", "decayed")).hasSize(1);
        assertThat(store.listByLifecycle("ws-1", "active")).isEmpty();
    }

    @Test
    void testDecayedLessonReconfirmsWhenReobservedCurrent()
    {
        ingestor.ingest("ws-1", bundle(7, verifiedChain()), List.of(lesson(
                "recurring-concern", "Close split sources on cancellation.",
                List.of("core/spi/Connector.java"), false)), clone);
        String id = store.listByLifecycle("ws-1", "active").getFirst().id();
        store.setLifecycle(id, "decayed", null, 5);

        ingestor.ingest("ws-1", bundle(9, verifiedChain()), List.of(lesson(
                "recurring-concern", "Close split sources on cancellation.",
                List.of("core/spi/Connector.java"), false)), clone);

        assertThat(store.findById(id).orElseThrow().lifecycle()).isEqualTo("active");
    }

    @Test
    void testPendingGlossaryDoesNotRegisterConcept()
    {
        ExtractedLesson glossary = new ExtractedLesson(
                "glossary", "Stage", "A stage groups splits.", null,
                List.of(), List.of(), List.of(), List.of(), List.of("plan"),
                List.of(0), false, "low", null, List.of(), "knowledge", null);
        ingestor.ingest("ws-1", bundle(List.of()), List.of(glossary), clone);
        verifyNoInteractions(concepts);
    }

    // ── fixtures ────────────────────────────────────────────────────

    private static ExtractedLesson lesson(
            String kind, String statement, List<String> paths, boolean explicitQuote)
    {
        return new ExtractedLesson(
                kind, null, statement, null, List.of(), paths, List.of(), List.of(),
                List.of("dev"), List.of(0), explicitQuote, "medium", null,
                List.of(), "knowledge", null);
    }

    private static PrEvidenceBundle bundle(List<OutcomeChain> chains)
    {
        return bundle(7, chains);
    }

    private static PrEvidenceBundle bundle(int prNumber, List<OutcomeChain> chains)
    {
        return bundle(prNumber, chains, "complete");
    }

    private static PrEvidenceBundle bundle(
            int prNumber, List<OutcomeChain> chains, String completeness)
    {
        return new PrEvidenceBundle("ws-1", "acme/widget", prNumber, "alice",
                "Title", "Body", "base", "head", "merge", "repoSha",
                List.of(), List.of(), List.of(), List.of(), List.of(),
                Map.of("reviews", completeness), completeness,
                List.of(new PrEvidenceBundle.EvidenceRef(
                        "thread", "9001", "https://github.com/acme/widget/pull/"
                                + prNumber + "#discussion_r9001",
                        null, "core/spi/Connector.java", 10, 12, "digest")),
                chains);
    }

    private static List<OutcomeChain> verifiedChain()
    {
        return List.of(new OutcomeChain(
                "bob", "core/spi/Connector.java", "9001", "abc123",
                true, true, 3, "digest"));
    }
}
