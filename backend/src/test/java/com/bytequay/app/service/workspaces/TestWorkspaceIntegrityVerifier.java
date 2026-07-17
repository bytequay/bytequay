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

import com.bytequay.app.service.local.GitRunner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.sqlite.SQLiteDataSource;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestWorkspaceIntegrityVerifier
{
    @TempDir
    private Path tempDir;

    @Test
    void acceptsOneVerifiedCloneForEachActiveWorkspace()
            throws Exception
    {
        Path clone = Files.createDirectory(tempDir.resolve("ready-clone"));
        GitRunner git = mock(GitRunner.class);
        when(git.isGitWorkingTree(clone)).thenReturn(true);
        JdbcTemplate jdbc = schema("ready.db");
        insertWorkspace(jdbc, "ws-ready", "acme/widget", clone, null);

        assertThatCode(() -> new WorkspaceIntegrityVerifier(jdbc, git).verify())
                .doesNotThrowAnyException();
    }

    @Test
    void rejectsAnActiveWorkspaceWhoseDirectoryIsNotAGitClone()
            throws Exception
    {
        Path clone = Files.createDirectory(tempDir.resolve("plain-directory"));
        GitRunner git = mock(GitRunner.class);
        when(git.isGitWorkingTree(clone)).thenReturn(false);
        JdbcTemplate jdbc = schema("invalid.db");
        insertWorkspace(jdbc, "ws-invalid", "acme/invalid", clone, null);

        assertThatThrownBy(
                () -> new WorkspaceIntegrityVerifier(jdbc, git).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "ws-invalid has no verified local clone");
    }

    @Test
    void rejectsAnOrphanVerifiedCloneButIgnoresDetachedWorkspaces()
            throws Exception
    {
        Path orphan = Files.createDirectory(tempDir.resolve("orphan-clone"));
        Path detached = Files.createDirectory(
                tempDir.resolve("detached-clone"));
        GitRunner git = mock(GitRunner.class);
        when(git.isGitWorkingTree(orphan)).thenReturn(true);
        when(git.isGitWorkingTree(detached)).thenReturn(true);
        JdbcTemplate jdbc = schema("orphan.db");
        insertWatched(jdbc, "acme", "orphan", orphan);
        insertWorkspace(
                jdbc, "ws-detached", "acme/detached", detached, 100L);

        assertThatThrownBy(
                () -> new WorkspaceIntegrityVerifier(jdbc, git).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(
                        "verified clone is orphaned: acme/orphan")
                .hasMessageNotContaining(
                        "ws-detached has no verified local clone");
    }

    private JdbcTemplate schema(String name)
    {
        SQLiteDataSource dataSource = new SQLiteDataSource();
        dataSource.setUrl("jdbc:sqlite:" + tempDir.resolve(name));
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("""
                CREATE TABLE workspaces(
                    id TEXT PRIMARY KEY,
                    detached_at_ms INTEGER)
                """);
        jdbc.execute("""
                CREATE TABLE workspace_repos(
                    workspace_id TEXT,
                    repo_full_name TEXT)
                """);
        jdbc.execute("""
                CREATE TABLE watched_repos(
                    owner TEXT,
                    repo TEXT,
                    local_clone_path TEXT)
                """);
        return jdbc;
    }

    private static void insertWorkspace(
            JdbcTemplate jdbc,
            String workspaceId,
            String repo,
            Path clone,
            Long detachedAt)
    {
        jdbc.update("""
                INSERT INTO workspaces(id, detached_at_ms) VALUES (?, ?)
                """, workspaceId, detachedAt);
        jdbc.update("""
                INSERT INTO workspace_repos(workspace_id, repo_full_name)
                VALUES (?, ?)
                """, workspaceId, repo);
        int slash = repo.indexOf('/');
        insertWatched(
                jdbc,
                repo.substring(0, slash),
                repo.substring(slash + 1),
                clone);
    }

    private static void insertWatched(
            JdbcTemplate jdbc,
            String owner,
            String repo,
            Path clone)
    {
        jdbc.update("""
                INSERT INTO watched_repos(owner, repo, local_clone_path)
                VALUES (?, ?, ?)
                """, owner, repo, clone.toString());
    }
}
