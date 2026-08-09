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

import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.domain.Workspace;
import com.bytequay.app.domain.WorkspaceRepo;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.repository.sqlite.SqliteWorkspaceStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestWatchedRepoPurger
{
    private static final String OWNER = "acme";
    private static final String REPO = "widget";

    @Test
    void purgesWorkspaceCachesAndManagedClone(@TempDir Path home)
            throws IOException
    {
        Path clone = home.resolve("Library/Application Support/ByteQuay/repos")
                .resolve(OWNER).resolve(REPO);
        Path worktrees = clone.resolveSibling(REPO + ".bytequay-worktrees");
        Files.createDirectories(clone.resolve(".git"));
        Files.createDirectories(worktrees.resolve("upstream-cherry-pick"));

        Fixture fixture = new Fixture(clone.toString());
        withHome(home, () -> fixture.purger.purge(OWNER, REPO));

        verify(fixture.workspaces).delete("ws-acme-widget");
        verify(fixture.watchedRepos).remove(OWNER, REPO);
        verify(fixture.jdbc).update(
                eq("DELETE FROM workspace_creation WHERE owner = ? AND repo = ?"),
                eq(OWNER), eq(REPO));
        verify(fixture.jdbc).update(
                eq("DELETE FROM pull_requests WHERE repo = ?"), eq("acme/widget"));
        assertThat(clone).doesNotExist();
        assertThat(worktrees).doesNotExist();
    }

    @Test
    void leavesAClonePointedAtByTheUserOnDisk(@TempDir Path home)
            throws IOException
    {
        Path elsewhere = home.resolve("code").resolve(REPO);
        Files.createDirectories(elsewhere.resolve(".git"));

        Fixture fixture = new Fixture(elsewhere.toString());
        withHome(home, () -> fixture.purger.purge(OWNER, REPO));

        verify(fixture.watchedRepos).remove(OWNER, REPO);
        assertThat(elsewhere).exists();
    }

    @Test
    void unknownRepoIsANoOp(@TempDir Path home)
    {
        Fixture fixture = new Fixture(null);
        when(fixture.watchedRepos.find(OWNER, REPO)).thenReturn(Optional.empty());

        withHome(home, () -> fixture.purger.purge(OWNER, REPO));

        verify(fixture.workspaces, never()).delete(any());
        verify(fixture.watchedRepos, never()).remove(any(), any());
    }

    private static void withHome(Path home, Runnable body)
    {
        String previous = System.getProperty("user.home");
        System.setProperty("user.home", home.toString());
        try {
            body.run();
        }
        finally {
            System.setProperty("user.home", previous);
        }
    }

    private static final class Fixture
    {
        final WatchedRepoStore watchedRepos = mock(WatchedRepoStore.class);
        final SqliteWorkspaceStore workspaceStore = mock(SqliteWorkspaceStore.class);
        final WorkspaceService workspaces = mock(WorkspaceService.class);
        final JdbcTemplate jdbc = mock(JdbcTemplate.class);
        final WatchedRepoPurger purger;

        Fixture(String clonePath)
        {
            when(watchedRepos.find(OWNER, REPO)).thenReturn(Optional.of(new WatchedRepo(
                    1L, OWNER, REPO, 0, clonePath, null, null)));
            Workspace workspace = new Workspace(
                    "ws-acme-widget", "acme/widget", "", false, null,
                    Instant.EPOCH, Instant.EPOCH);
            when(workspaceStore.listWorkspaces()).thenReturn(List.of(workspace));
            when(workspaceStore.listRepos("ws-acme-widget")).thenReturn(List.of(
                    new WorkspaceRepo("ws-acme-widget", "acme/widget", "main",
                            false, Instant.EPOCH)));
            purger = new WatchedRepoPurger(
                    watchedRepos, workspaceStore, workspaces, jdbc,
                    new TransactionTemplate(mock(PlatformTransactionManager.class)));
        }
    }
}
