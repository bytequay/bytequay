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
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.repository.WorkspaceStore;
import com.bytequay.app.repository.sqlite.KnowledgeItemStore;
import com.bytequay.app.scheduler.QuietHoursPolicy;
import com.bytequay.app.service.workspaces.WorkspaceRepositoryResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static com.bytequay.app.testing.MigratedSqliteDatabase.copyTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Daily maintenance: partial runs resume, useful runs backfill, caught-up
 * runs refresh their merge catalog, and active knowledge revalidates against
 * the clone — present anchors re-confirm, absent anchors decay.
 */
class TestLearningCatchUpJob
{
    @TempDir
    private Path tempDir;

    private ProjectLearningStore runs;
    private KnowledgeItemStore knowledge;
    private ProjectLearningService learning;
    private LearningCatchUpJob job;
    private Path clone;

    @BeforeEach
    void setUp()
            throws IOException
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("catchup.db")
                + "?foreign_keys=ON&busy_timeout=5000";
        copyTo(tempDir.resolve("catchup.db"));
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
                INSERT INTO workspaces (id, name, memory_md, is_scratch,
                    created_at_ms, updated_at_ms)
                VALUES ('ws-1', 'acme/widget', '', 0, 1, 1)
                """);
        runs = new ProjectLearningStore(jdbc);
        knowledge = new KnowledgeItemStore(jdbc, new ObjectMapper());
        learning = mock(ProjectLearningService.class);
        WorkspaceStore workspaces = mock(WorkspaceStore.class);
        WorkspaceRepositoryResolver resolver = mock(WorkspaceRepositoryResolver.class);
        WatchedRepoStore watchedRepos = mock(WatchedRepoStore.class);
        QuietHoursPolicy quietHours = mock(QuietHoursPolicy.class);
        when(quietHours.isQuietNow()).thenReturn(false);
        when(resolver.resolve("ws-1")).thenReturn(new WorkspaceRepositoryResolver
                .RepositoryIdentity("acme", "widget", "acme/widget", "main"));
        clone = tempDir.resolve("clone");
        Files.createDirectories(clone.resolve("core"));
        Files.writeString(clone.resolve("core/Kept.java"), "class Kept {}");
        when(watchedRepos.find("acme", "widget")).thenReturn(Optional.of(new WatchedRepo(
                1, "acme", "widget", 0, clone.toString(), null, null)));

        job = new LearningCatchUpJob(learning, runs, knowledge, workspaces,
                resolver, watchedRepos, quietHours);
    }

    @Test
    void testPartialRunResumesAndUsefulRunBackfills()
    {
        runs.insertRun(run("run-1", "partial"));
        job.catchUp("ws-1");
        verify(learning).retry("run-1");

        runs.updateRun("run-1", "useful", null, null, "{}", null, null, 2);
        job.catchUp("ws-1");
        verify(learning).backfill("ws-1", "acme/widget", 25);
    }

    @Test
    void testCaughtUpRunRefreshesMergeCatalog()
    {
        runs.insertRun(run("run-1", "caught-up"));

        job.catchUp("ws-1");

        verify(learning).refreshCompleted("run-1");
    }

    @Test
    void testRevalidationReconfirmsPresentAnchorsAndDecaysAbsentOnes()
    {
        runs.insertRun(run("run-1", "caught-up"));
        insertActive("k-kept", "core/Kept.java");
        insertActive("k-gone", "core/Removed.java");

        job.catchUp("ws-1");

        assertThat(knowledge.findById("k-kept").orElseThrow().lifecycle())
                .isEqualTo("active");
        assertThat(knowledge.findById("k-gone").orElseThrow().lifecycle())
                .isEqualTo("decayed");
    }

    private static ProjectLearningRun run(String id, String state)
    {
        return new ProjectLearningRun(id, "ws-1", "acme/widget", "clone",
                state, null, null, "{}", 1, 1, 1, null, null);
    }

    private void insertActive(String id, String path)
    {
        knowledge.insert(new KnowledgeItem(
                        id, "ws-1", "acme/widget", "recurring-concern", null,
                        "Fact anchored at " + path, null, List.of("dev"), "medium",
                        "active", "sha", 1L, "pr-learning", null, "{}", 1, 1),
                List.of(),
                List.of(new KnowledgeItem.Applicability("path", path)));
    }
}
