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

import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.repository.WorkspaceStore;
import com.bytequay.app.repository.sqlite.KnowledgeItemStore;
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.workspaces.WorkspaceRepositoryResolver;
import com.bytequay.app.testing.SqliteTestPools;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.bytequay.app.testing.MigratedSqliteDatabase.copyTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coordinator tests: idempotent run creation and restart recovery that
 * resumes from the persisted cursor rather than restarting from page one.
 * The store is real (temp SQLite); the doc indexer and merged-PR catalog are
 * mocked so the lifecycle is deterministic.
 */
@ExtendWith(SqliteTestPools.class)
class TestProjectLearningService
{
    @TempDir
    private Path tempDir;

    private JdbcTemplate jdbc;
    private ProjectLearningStore store;
    private DocumentIndexer indexer;
    private MergedPrCatalog catalog;
    private WorkspaceRepositoryResolver resolver;
    private WatchedRepoStore watchedRepos;
    private PrEvidenceFetcher evidenceFetcher;
    private ProjectLearningService service;

    @BeforeEach
    void setUp()
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("learning.db")
                + "?foreign_keys=ON&busy_timeout=5000";
        copyTo(tempDir.resolve("learning.db"));
        DataSource dataSource = SqliteTestPools.open(url);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
                INSERT INTO workspaces (id, name, memory_md, is_scratch,
                    created_at_ms, updated_at_ms)
                VALUES ('ws-1', 'acme/widget', '', 0, 1, 1)
                """);
        store = new ProjectLearningStore(jdbc);
        indexer = mock(DocumentIndexer.class);
        catalog = mock(MergedPrCatalog.class);
        resolver = mock(WorkspaceRepositoryResolver.class);
        watchedRepos = mock(WatchedRepoStore.class);
        PatResolver patResolver = mock(PatResolver.class);
        PrPriorityScorer scorer = new PrPriorityScorer(new ObjectMapper());
        ModuleCoverageSelector selector = new ModuleCoverageSelector();
        evidenceFetcher = mock(PrEvidenceFetcher.class);

        when(resolver.resolve("ws-1")).thenReturn(new WorkspaceRepositoryResolver
                .RepositoryIdentity("acme", "widget", "acme/widget", "main"));
        when(watchedRepos.find("acme", "widget")).thenReturn(Optional.of(new WatchedRepo(
                1, "acme", "widget", 0, tempDir.toString(), null, null)));
        when(patResolver.resolve(anyString())).thenReturn("pat");
        when(indexer.index(anyString(), anyString(), any(), any()))
                .thenReturn(new DocumentIndexer.IndexResult(0, "capsule", "digest"));

        LessonExtractor extractor = mock(LessonExtractor.class);
        when(extractor.extract(anyString(), any(), any())).thenReturn(List.of());
        KnowledgeIngestor ingestor = mock(KnowledgeIngestor.class);
        when(ingestor.ingest(anyString(), any(), any(), any()))
                .thenReturn(new KnowledgeIngestor.IngestResult(0, 0, 0));
        KnowledgeItemStore knowledge = new KnowledgeItemStore(jdbc, new ObjectMapper());
        WorkspaceStore workspaceStore = mock(WorkspaceStore.class);
        when(workspaceStore.listWorkspaces()).thenReturn(List.of());

        service = new ProjectLearningService(store, resolver, watchedRepos, workspaceStore,
                indexer, catalog, scorer, selector, evidenceFetcher, extractor, ingestor,
                knowledge, patResolver, new ObjectMapper(), Runnable::run);
    }

    @Test
    void testEnqueueCreatesOneRunAndIsIdempotent()
            throws InterruptedException
    {
        when(catalog.catalog(anyString(), anyString(), anyString(), anyInt(), any(), any()))
                .thenAnswer(inv -> new MergedPrCatalog.Outcome(
                        inv.getArgument(4), MergedPrCatalog.State.CAUGHT_UP, null));

        ProjectLearningRun first = service.enqueue("ws-1", "acme/widget", "clone");
        ProjectLearningRun second = service.enqueue("ws-1", "acme/widget", "clone");

        assertThat(second.id()).isEqualTo(first.id());
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM repo_learning_run", Integer.class)).isEqualTo(1);
        // Let the background run (launched after enqueue) settle before the
        // temp DB is torn down.
        awaitTerminal(first.id());
    }

    private void awaitTerminal(String id)
            throws InterruptedException
    {
        for (int attempt = 0; attempt < 200; attempt++) {
            String state = store.findRun(id).map(ProjectLearningRun::state).orElse(null);
            if ("caught-up".equals(state) || "useful".equals(state)
                    || "failed".equals(state) || "partial".equals(state)) {
                return;
            }
            Thread.sleep(10);
        }
    }

    @Test
    void testRestartResumesFromPersistedCursorNotPageOne()
    {
        // A run interrupted mid-catalog: its cursor is parked at page 3 of an
        // unfinished window.
        String cursor = "{\"partitions\":[{\"from\":\"2020-01-01\",\"to\":\"2020-06-30\","
                + "\"nextPage\":3,\"exhausted\":false}]}";
        store.insertRun(new ProjectLearningRun("run-1", "ws-1", "acme/widget", "clone",
                "cataloging", "sha", cursor, "{}", 1, 1, 1, null, null));

        ArgumentCaptor<CatalogCursor> started = ArgumentCaptor.forClass(CatalogCursor.class);
        when(catalog.catalog(anyString(), anyString(), anyString(), anyInt(),
                started.capture(), any()))
                .thenAnswer(inv -> new MergedPrCatalog.Outcome(
                        inv.getArgument(4), MergedPrCatalog.State.CAUGHT_UP, null));

        service.execute("run-1");

        // Resumed the parked window at page 3 — not a fresh full-history cursor.
        CatalogCursor resumed = started.getValue();
        assertThat(resumed.partitions()).singleElement()
                .satisfies(p -> {
                    assertThat(p.from()).isEqualTo("2020-01-01");
                    assertThat(p.nextPage()).isEqualTo(3);
                });
        // A resumed run does not re-index docs.
        verify(indexer, never()).index(anyString(), anyString(), any(), any());
        assertThat(store.findRun("run-1")).get()
                .extracting(ProjectLearningRun::state).isEqualTo("caught-up");
    }

    @Test
    void testAnalyzeDrainsWholeCatalogNotJustFirstBatch()
    {
        when(catalog.catalog(anyString(), anyString(), anyString(), anyInt(), any(), any()))
                .thenAnswer(inv -> new MergedPrCatalog.Outcome(
                        inv.getArgument(4), MergedPrCatalog.State.CAUGHT_UP, null));
        store.insertRun(new ProjectLearningRun("run-1", "ws-1", "acme/widget", "clone",
                "queued", null, null, "{}", 1, 1, 1, null, null));
        // More cataloged PRs than a single SELECT_LIMIT (50) wave can promote.
        for (int pr = 1; pr <= 60; pr++) {
            store.upsertPrSource(new RepoPrSource("ws-1", "acme/widget", pr,
                    "2020-01-" + String.format("%02d", (pr % 28) + 1), null, "{}", "{}",
                    "d" + pr, null, "cataloged", 1, null, null));
        }

        service.execute("run-1");

        // Every cataloged PR was worked through in SELECT_LIMIT waves, not just
        // the first 50 — the whole catalog drained to 'analyzed'.
        assertThat(store.countAnalyzed("ws-1", "acme/widget")).isEqualTo(60);
    }

    @Test
    void testMergeTriggeredLearningIsIdempotentBySourceDigest()
    {
        store.insertRun(new ProjectLearningRun("run-1", "ws-1", "acme/widget", "clone",
                "caught-up", "sha", null, "{}", 1, 1, 1, 1L, null));

        service.learnOne("ws-1", "acme/widget", 42, "Fix retry", "alice", "merge");
        service.learnOne("ws-1", "acme/widget", 42, "Fix retry", "alice", "merge");

        // One analysis for the same source digest/extractor version: the
        // evidence fetch ran exactly once (its null return marks the row
        // analyzed with an error, which is still a completed analysis).
        verify(evidenceFetcher, times(1)).fetch(
                anyString(), anyString(), anyString(), anyInt(), any(), any(), any(), any());
        assertThat(store.findPrSource("ws-1", "acme/widget", 42))
                .get()
                .extracting(RepoPrSource::analysisState)
                .isEqualTo("analyzed");

        // A changed source (new title → new digest) supersedes: analysis runs again.
        service.learnOne("ws-1", "acme/widget", 42, "Fix retry properly", "alice", "merge");
        verify(evidenceFetcher, times(2)).fetch(
                anyString(), anyString(), anyString(), anyInt(), any(), any(), any(), any());
    }

    @Test
    void testFreshRunIndexesDocsThenCatalogsFullHistory()
    {
        ArgumentCaptor<CatalogCursor> started = ArgumentCaptor.forClass(CatalogCursor.class);
        when(catalog.catalog(anyString(), anyString(), anyString(), anyInt(),
                started.capture(), any()))
                .thenAnswer(inv -> new MergedPrCatalog.Outcome(
                        inv.getArgument(4), MergedPrCatalog.State.CAUGHT_UP, null));
        store.insertRun(new ProjectLearningRun("run-1", "ws-1", "acme/widget", "clone",
                "queued", null, null, "{}", 1, 1, 1, null, null));

        service.execute("run-1");

        verify(indexer).index(eq("ws-1"), eq("acme/widget"), any(), any());
        // Fresh run starts one full-history window at page 1.
        assertThat(started.getValue().partitions()).singleElement()
                .satisfies(p -> assertThat(p.nextPage()).isEqualTo(1));
        assertThat(store.capsuleDigest("ws-1")).contains("digest");
    }

    @Test
    void testRetryRequeuesIncompleteEvidence()
            throws InterruptedException
    {
        when(catalog.catalog(anyString(), anyString(), anyString(), anyInt(), any(), any()))
                .thenAnswer(inv -> new MergedPrCatalog.Outcome(
                        inv.getArgument(4), MergedPrCatalog.State.CAUGHT_UP, null));
        store.insertRun(new ProjectLearningRun("run-1", "ws-1", "acme/widget", "clone",
                "partial", "sha", null, "{}", 1, 1, 1, null, "rate limit"));
        store.upsertPrSource(new RepoPrSource("ws-1", "acme/widget", 7,
                "2026-07-20T00:00:00Z", null, "{}", "{}", "digest-7",
                null, "cataloged", 1, null, null));
        store.persistEvidence(new PrEvidenceBundle(
                "ws-1", "acme/widget", 7, "alice", "Title", "Body",
                "base", "head", "merge", "repoSha", List.of(), List.of(), List.of(),
                List.of(), List.of(), Map.of("reviews", "partial:reviews"),
                "partial:reviews", List.of(), List.of()), 5.0, 100L);
        store.markAnalyzed("ws-1", "acme/widget", 7, 5.0, "merge", 100L);
        store.upsertPrSource(new RepoPrSource("ws-1", "acme/widget", 8,
                "2026-07-20T00:00:00Z", null, "{}", "{}", "digest-8",
                null, "cataloged", 1, null, null));
        store.markAnalyzed("ws-1", "acme/widget", 8, 5.0, null, 100L,
                "evidence: unavailable");

        service.retry("run-1");
        awaitTerminal("run-1");

        verify(evidenceFetcher).fetch(
                anyString(), anyString(), anyString(), eq(7), any(), any(), any(), any());
        verify(evidenceFetcher).fetch(
                anyString(), anyString(), anyString(), eq(8), any(), any(), any(), any());
    }

    @Test
    void testCaughtUpRefreshOverlapsLastCoveredDayAndFindsNewMerge()
            throws InterruptedException
    {
        String cursor = "{\"partitions\":[{\"from\":\"2008-01-01\","
                + "\"to\":\"2026-07-20\",\"nextPage\":1,\"exhausted\":true}]}";
        store.insertRun(new ProjectLearningRun("run-1", "ws-1", "acme/widget", "clone",
                "caught-up", "sha", cursor, "{}", 1, 1, 1, 1L, null));
        ArgumentCaptor<CatalogCursor> started = ArgumentCaptor.forClass(CatalogCursor.class);
        when(catalog.catalog(anyString(), anyString(), anyString(), anyInt(),
                started.capture(), any()))
                .thenAnswer(inv -> {
                    MergedPrCatalog.Sink sink = inv.getArgument(5);
                    sink.record(new RepoPrSource(
                            "ws-1", "acme/widget", 42, "2026-07-21T00:00:00Z",
                            null, "{}", "{\"catalog\":\"complete\"}", "digest-42",
                            null, "cataloged", 1, null, null));
                    CatalogCursor next = inv.getArgument(4);
                    return new MergedPrCatalog.Outcome(
                            next.replace(0, new CatalogCursor.Partition(
                                    next.partitions().getFirst().from(),
                                    next.partitions().getFirst().to(), 1, true)),
                            MergedPrCatalog.State.CAUGHT_UP, null);
                });

        service.refreshCompleted("run-1");
        awaitTerminal("run-1");

        assertThat(started.getValue().partitions()).singleElement().satisfies(partition -> {
            assertThat(partition.from()).isEqualTo("2026-07-20");
            assertThat(partition.to()).isEqualTo(LocalDate.now().toString());
            assertThat(partition.nextPage()).isEqualTo(1);
        });
        assertThat(store.findPrSource("ws-1", "acme/widget", 42)).isPresent();
        verify(evidenceFetcher).fetch(
                anyString(), anyString(), anyString(), eq(42), any(), any(), any(), any());
    }
}
