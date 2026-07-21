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

import com.bytequay.app.domain.LocalRepoStatus;
import com.bytequay.app.domain.Workspace;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.RepoService;
import com.bytequay.app.service.learning.ProjectLearningService;
import com.bytequay.app.service.local.LocalRepoService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Path;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestWorkspaceCreationService
{
    @TempDir
    private Path tempDir;

    @Test
    void recoversAnInterruptedOperationAndPersistsEveryReadyMilestone()
            throws Exception
    {
        JdbcTemplate jdbc = schema();
        jdbc.update("""
                INSERT INTO workspace_creation(
                    id, operation_kind, owner, repo, write_mode, state,
                    stage_message, progress_current, progress_total,
                    attempt, created_at_ms, updated_at_ms)
                VALUES (
                    'operation-1', 'connect', 'acme', 'widget', 'DIRECT',
                    'syncing', 'Syncing pull requests', 1, 3, 1, 1, 1)
                """);
        LocalRepoService local = mock(LocalRepoService.class);
        RepoService repos = mock(RepoService.class);
        WorkspaceService workspaces = mock(WorkspaceService.class);
        WorkspaceConfigurationService configuration =
                mock(WorkspaceConfigurationService.class);
        Path clone = tempDir.resolve("widget");
        when(local.cloneManaged(
                "acme", "widget", LocalRepoService.WriteMode.DIRECT))
                .thenReturn(new LocalRepoStatus(
                        "acme",
                        "widget",
                        clone.toString(),
                        LocalRepoStatus.State.CLEAN,
                        "main",
                        0,
                        null,
                        null,
                        "main",
                        "fork"));
        Workspace workspace = new Workspace(
                "ws-widget",
                "acme/widget",
                "",
                false,
                null,
                Instant.parse("2026-07-17T00:00:00Z"),
                Instant.parse("2026-07-17T00:00:00Z"));
        when(workspaces.ensureForVerifiedClone("acme", "widget"))
                .thenReturn(workspace);
        WorkspaceCreationService service = new WorkspaceCreationService(
                jdbc,
                local,
                repos,
                workspaces,
                configuration,
                mock(WatchedRepoStore.class),
                mock(ProjectLearningService.class));

        service.recover();
        awaitState(jdbc, "operation-1", "ready");

        assertThat(jdbc.queryForObject("""
                SELECT workspace_id FROM workspace_creation WHERE id = ?
                """, String.class, "operation-1"))
                .isEqualTo("ws-widget");
        assertThat(jdbc.queryForObject("""
                SELECT progress_current FROM workspace_creation WHERE id = ?
                """, Integer.class, "operation-1"))
                .isEqualTo(3);
        assertThat(jdbc.queryForObject("""
                SELECT sync_state || ':' || sync_current || ':' || sync_total
                FROM workspace_onboarding WHERE workspace_id = ?
                """, String.class, "ws-widget"))
                .isEqualTo("ready:3:3");
        verify(local).cloneManaged(
                "acme", "widget", LocalRepoService.WriteMode.DIRECT);
        verify(repos).getRepoPullRequests("acme", "widget");
        verify(repos).getRepoIssues("acme", "widget", "open");
        verify(repos).getRepoMeta("acme", "widget");

        clearInvocations(local);
        service.recover();
        Thread.sleep(50);
        verifyNoInteractions(local);
    }

    private JdbcTemplate schema()
    {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl(
                "jdbc:sqlite:" + tempDir.resolve("workspace-creation.db"));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE workspace_creation(
                    id TEXT PRIMARY KEY,
                    operation_kind TEXT NOT NULL,
                    owner TEXT NOT NULL,
                    repo TEXT NOT NULL,
                    write_mode TEXT NOT NULL,
                    state TEXT NOT NULL,
                    stage_message TEXT,
                    progress_current INTEGER NOT NULL,
                    progress_total INTEGER NOT NULL,
                    workspace_id TEXT,
                    clone_path TEXT,
                    previous_clone_path TEXT,
                    error_message TEXT,
                    attempt INTEGER NOT NULL,
                    created_at_ms INTEGER NOT NULL,
                    updated_at_ms INTEGER NOT NULL)
                """);
        jdbc.execute("""
                CREATE TABLE workspace_onboarding(
                    workspace_id TEXT PRIMARY KEY,
                    clone_complete INTEGER NOT NULL DEFAULT 0,
                    sync_state TEXT NOT NULL DEFAULT 'queued',
                    sync_current INTEGER NOT NULL DEFAULT 0,
                    sync_total INTEGER NOT NULL DEFAULT 0,
                    memory_seed_complete INTEGER NOT NULL DEFAULT 0,
                    first_trunk_complete INTEGER NOT NULL DEFAULT 0,
                    memory_imported INTEGER NOT NULL DEFAULT 0,
                    dismissed_at_ms INTEGER,
                    updated_at_ms INTEGER NOT NULL)
                """);
        jdbc.update("""
                INSERT INTO workspace_onboarding(
                    workspace_id, clone_complete, sync_state,
                    sync_current, sync_total, updated_at_ms)
                VALUES ('ws-widget', 1, 'syncing', 1, 3, 1)
                """);
        return jdbc;
    }

    private static void awaitState(
            JdbcTemplate jdbc,
            String operationId,
            String expected)
            throws InterruptedException
    {
        for (int attempt = 0; attempt < 200; attempt++) {
            String state = jdbc.queryForObject("""
                    SELECT state FROM workspace_creation WHERE id = ?
                    """, String.class, operationId);
            if (expected.equals(state)) {
                return;
            }
            if ("failed".equals(state)) {
                throw new AssertionError("operation failed during recovery");
            }
            Thread.sleep(10);
        }
        throw new AssertionError(
                "operation did not reach state " + expected);
    }
}
