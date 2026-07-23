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
import com.bytequay.app.service.credentials.PatResolver;
import com.bytequay.app.service.workspaces.WorkspaceRepositoryResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Coordinator tests: idempotent run creation and restart recovery that
 * resumes from the persisted cursor rather than restarting from page one.
 * The store is real (temp SQLite); the doc indexer and merged-PR catalog are
 * mocked so the lifecycle is deterministic.
 */
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
    private ProjectLearningService service;

    @BeforeEach
    void setUp()
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("learning.db")
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
        store = new ProjectLearningStore(jdbc);
        indexer = mock(DocumentIndexer.class);
        catalog = mock(MergedPrCatalog.class);
        resolver = mock(WorkspaceRepositoryResolver.class);
        watchedRepos = mock(WatchedRepoStore.class);
        PatResolver patResolver = mock(PatResolver.class);
        PrPriorityScorer scorer = new PrPriorityScorer(new ObjectMapper());
        ModuleCoverageSelector selector = new ModuleCoverageSelector();
        PrEvidenceFetcher evidenceFetcher = mock(PrEvidenceFetcher.class);

        when(resolver.resolve("ws-1")).thenReturn(new WorkspaceRepositoryResolver
                .RepositoryIdentity("acme", "widget", "acme/widget", "main"));
        when(watchedRepos.find("acme", "widget")).thenReturn(Optional.of(new WatchedRepo(
                1, "acme", "widget", 0, tempDir.toString(), null, null)));
        when(patResolver.resolve(anyString())).thenReturn("pat");
        when(indexer.index(anyString(), anyString(), any(), any()))
                .thenReturn(new DocumentIndexer.IndexResult(0, "capsule", "digest"));

        service = new ProjectLearningService(store, resolver, watchedRepos, indexer,
                catalog, scorer, selector, evidenceFetcher, patResolver, new ObjectMapper());
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
            if ("caught-up".equals(state) || "failed".equals(state) || "partial".equals(state)) {
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
}
