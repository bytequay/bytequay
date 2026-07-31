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

import com.bytequay.app.domain.NotFoundException;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.workspaces.WorkspaceRepositoryResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;

import static com.bytequay.app.testing.MigratedSqliteDatabase.copyTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Approval, evidence thresholds, and module-root derivation for code areas. */
class TestDirectoryScopeService
{
    @TempDir
    private Path tempDir;

    private JdbcTemplate jdbc;
    private Path clone;
    private ThreadStore threads;
    private DirectoryScopeService service;

    @BeforeEach
    void setUp()
            throws IOException
    {
        String url = "jdbc:sqlite:" + tempDir.resolve("directory-scopes.db")
                + "?foreign_keys=ON&busy_timeout=5000";
        copyTo(tempDir.resolve("directory-scopes.db"));
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(url);
        jdbc = new JdbcTemplate(dataSource);
        jdbc.update("""
                INSERT INTO workspaces (id, name, memory_md, is_scratch,
                    created_at_ms, updated_at_ms)
                VALUES ('ws-1', 'acme/widget', '', 0, 1, 1)
                """);

        clone = tempDir.resolve("clone");
        Files.createDirectories(clone.resolve("modules/core/src"));
        Files.createDirectories(clone.resolve("frontend/src"));
        Files.createDirectories(clone.resolve("partial/src"));
        Files.writeString(clone.resolve("pom.xml"), "<project/>");
        Files.writeString(clone.resolve("modules/core/pom.xml"), "<project/>");

        WorkspaceRepositoryResolver repositories = mock(WorkspaceRepositoryResolver.class);
        when(repositories.resolve("ws-1")).thenReturn(new WorkspaceRepositoryResolver
                .RepositoryIdentity("acme", "widget", "acme/widget", "main"));
        WatchedRepoStore watchedRepos = mock(WatchedRepoStore.class);
        when(watchedRepos.find("acme", "widget")).thenReturn(Optional.of(new WatchedRepo(
                1, "acme", "widget", 0, clone.toString(), null, null)));
        threads = mock(ThreadStore.class);
        service = new DirectoryScopeService(jdbc, repositories, watchedRepos, threads);
    }

    @Test
    void testSuggestionsWaitForHistoryAndUseNearestModuleOrTopLevelFallback()
    {
        insertSources(30, 29);
        for (int pr = 1; pr <= 5; pr++) {
            evidence(pr, "file", "modules/core/src/Foo" + pr + ".java");
            evidence(pr, "test", "modules/core/src/Foo" + pr + "Test.java");
        }
        for (int pr = 6; pr <= 10; pr++) {
            evidence(pr, "file", "frontend/src/feature" + pr + ".ts");
        }
        for (int pr = 11; pr <= 15; pr++) {
            evidence(pr, "file", "partial/src/Foo" + pr + ".java", "partial:files");
        }

        DirectoryScopeService.Overview waiting = service.suggestions("ws-1");
        assertThat(waiting.historyReady()).isFalse();
        assertThat(waiting.requiredAnalyzedPrCount()).isEqualTo(25);
        assertThat(waiting.suggestions()).isEmpty();

        jdbc.update("""
                UPDATE repo_pr_source SET analysis_state = 'analyzed'
                WHERE workspace_id = 'ws-1' AND repo = 'acme/widget' AND pr_number = 30
                """);
        bundle(30, "complete");

        DirectoryScopeService.Overview ready = service.suggestions("ws-1");
        assertThat(ready.historyReady()).isTrue();
        assertThat(ready.suggestions()).hasSize(2);
        assertThat(ready.suggestions())
                .filteredOn(candidate -> candidate.paths().contains("modules/core"))
                .singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.name()).isEqualTo("core");
                    assertThat(candidate.evidencePrCount()).isEqualTo(5);
                    assertThat(candidate.confidence()).isEqualTo(0.2);
                    assertThat(candidate.rationale()).contains("pom.xml");
                    assertThat(candidate.decisionState()).isEqualTo("pending");
                });
        assertThat(ready.suggestions())
                .filteredOn(candidate -> candidate.paths().contains("frontend"))
                .singleElement()
                .satisfies(candidate -> assertThat(candidate.rationale())
                        .contains("top-level directory fallback"));
    }

    @Test
    void testOnlyApprovedScopeCanBeAssignedToOwnedThread()
    {
        insertSources(5, 5);
        for (int pr = 1; pr <= 5; pr++) {
            evidence(pr, "file", "modules/core/src/Foo" + pr + ".java");
        }
        insertThread("thread-1", "ws-1");
        when(threads.findThreadById("thread-1")).thenReturn(Optional.of(
                thread("thread-1", "ws-1")));
        when(threads.findThreadById("foreign-thread")).thenReturn(Optional.of(
                thread("foreign-thread", "ws-other")));

        assertThatThrownBy(() -> service.assign("ws-1", "thread-1", "modules/core"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not approved");
        assertThatThrownBy(() -> service.decide("ws-1", "unknown", "approved"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not a current suggestion");

        DirectoryScopeService.Decision approved =
                service.decide("ws-1", "modules/core", "approved");
        assertThat(approved.decisionState()).isEqualTo("approved");
        DirectoryScopeService.Assignment assignment =
                service.assign("ws-1", "thread-1", "modules/core");
        assertThat(assignment.paths()).containsExactly("modules/core");
        assertThat(service.suggestions("ws-1").assignments())
                .singleElement()
                .extracting(DirectoryScopeService.Assignment::threadId)
                .isEqualTo("thread-1");

        assertThatThrownBy(() -> service.assign(
                "ws-1", "foreign-thread", "modules/core"))
                .isInstanceOf(NotFoundException.class);

        service.decide("ws-1", "modules/core", "rejected");
        assertThat(service.suggestions("ws-1").assignments()).isEmpty();
        assertThat(service.suggestions("ws-1").suggestions())
                .singleElement()
                .extracting(DirectoryScopeService.Suggestion::decisionState)
                .isEqualTo("rejected");

        service.decide("ws-1", "modules/core", "approved");
        service.assign("ws-1", "thread-1", "modules/core");
        service.clearAssignment("ws-1", "thread-1");
        assertThat(service.suggestions("ws-1").assignments()).isEmpty();
        verify(threads, never()).saveThread(any());
    }

    private void insertSources(int cataloged, int analyzed)
    {
        for (int pr = 1; pr <= cataloged; pr++) {
            jdbc.update("""
                    INSERT INTO repo_pr_source (
                        workspace_id, repo, pr_number, metadata_json,
                        completeness_json, analysis_state, extractor_version)
                    VALUES ('ws-1', 'acme/widget', ?, '{}', '{}', ?, 1)
                    """, pr, pr <= analyzed ? "analyzed" : "cataloged");
            if (pr <= analyzed) {
                bundle(pr, "complete");
            }
        }
    }

    private void evidence(int prNumber, String kind, String path)
    {
        evidence(prNumber, kind, path, "complete");
    }

    private void evidence(int prNumber, String kind, String path, String completeness)
    {
        bundle(prNumber, completeness);
        jdbc.update("""
                INSERT INTO repo_pr_evidence_ref (
                    workspace_id, repo, pr_number, ref_kind, file_path, detail_json)
                VALUES ('ws-1', 'acme/widget', ?, ?, ?, '{}')
                """, prNumber, kind, path);
    }

    private void bundle(int prNumber, String completeness)
    {
        jdbc.update("""
                INSERT INTO repo_pr_evidence_bundle (
                    workspace_id, repo, pr_number, overall_completeness,
                    extractor_version, built_at_ms)
                VALUES ('ws-1', 'acme/widget', ?, ?, 1, 1)
                ON CONFLICT(workspace_id, repo, pr_number) DO UPDATE SET
                    overall_completeness = excluded.overall_completeness
                """, prNumber, completeness);
    }

    private void insertThread(String id, String workspaceId)
    {
        jdbc.update("""
                INSERT INTO threads (
                    id, kind, provider, title, status, model,
                    created_at_ms, updated_at_ms, workspace_id)
                VALUES (?, 'LOGIC_LOOP', 'local', 'Thread', 'IDLE', 'local', 1, 1, ?)
                """, id, workspaceId);
    }

    private static Thread thread(String id, String workspaceId)
    {
        return new Thread(id, ThreadKind.LOGIC_LOOP, "local", null, "Thread",
                ThreadStatus.IDLE, "local", 0, 0, 0, Instant.EPOCH, Instant.EPOCH,
                null, null, ThreadFlow.BUILD, workspaceId, null);
    }
}
