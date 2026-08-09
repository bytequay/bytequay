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
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.repository.sqlite.SqliteWorkspaceStore;
import com.bytequay.app.service.local.LocalRepoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.util.FileSystemUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Un-watching a repo retires everything ByteQuay built for it. The
 * workspace attached to the repo is the anchor: deleting it stops the
 * live agents, reaps task worktrees, and DB-cascades threads, tasks,
 * stages, messages, agent-execution logs, sync runs (upstream
 * cherry-pick jobs), CI harness watches, learning runs, and PR
 * evidence. What that cascade can't reach is (a) rows keyed by repo
 * name rather than workspace id and (b) the managed clone on disk;
 * both are handled here.
 *
 * <p>The database teardown is one transaction — a failure leaves the
 * repo watched rather than half-deleted. Directory removal runs after
 * the commit and is best-effort: a locked or already-gone directory
 * must not resurrect a repo the user asked to forget.
 */
@Component
public class WatchedRepoPurger
{
    private static final Logger log = LoggerFactory.getLogger(WatchedRepoPurger.class);

    private final WatchedRepoStore watchedRepos;
    private final SqliteWorkspaceStore workspaceStore;
    private final WorkspaceService workspaces;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;

    public WatchedRepoPurger(
            WatchedRepoStore watchedRepos,
            SqliteWorkspaceStore workspaceStore,
            WorkspaceService workspaces,
            JdbcTemplate jdbc,
            TransactionTemplate transactions)
    {
        this.watchedRepos = requireNonNull(watchedRepos, "watchedRepos is null");
        this.workspaceStore = requireNonNull(workspaceStore, "workspaceStore is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.transactions = requireNonNull(transactions, "transactions is null");
    }

    /** Drop the watched repo and every resource derived from it.
     *  Idempotent: an unknown owner/repo is a no-op. */
    public void purge(String owner, String repo)
    {
        requireNonNull(owner, "owner is null");
        requireNonNull(repo, "repo is null");
        Optional<WatchedRepo> watched = watchedRepos.find(owner, repo);
        if (watched.isEmpty()) {
            return;
        }
        transactions.executeWithoutResult(status -> purgeRows(owner, repo));
        watched.map(WatchedRepo::localClonePath).ifPresent(this::deleteManagedClone);
        log.info("purged watched repo {}/{}", owner, repo);
    }

    private void purgeRows(String owner, String repo)
    {
        String fullName = owner + "/" + repo;
        // workspace_creation.workspace_id has no ON DELETE CASCADE, so the
        // clone-progress rows have to go before the workspace they point at.
        jdbc.update("DELETE FROM workspace_creation WHERE owner = ? AND repo = ?", owner, repo);
        for (String workspaceId : workspaceIdsFor(fullName)) {
            workspaces.delete(workspaceId);
        }
        // Caches and settings keyed by repo name — no workspace to cascade
        // from. pr_view_state keys off the legacy pull_requests row, so it
        // has to be resolved before that row goes away.
        jdbc.update("DELETE FROM pr_view_state WHERE pr_id IN"
                + " (SELECT id FROM pull_requests WHERE repo = ?)", fullName);
        jdbc.update("DELETE FROM pull_requests WHERE repo = ?", fullName);
        jdbc.update("DELETE FROM pr_review_draft WHERE repo = ?", fullName);
        jdbc.update("DELETE FROM pr WHERE repo = ?", fullName);
        jdbc.update("DELETE FROM repo_meta WHERE owner = ? AND repo = ?", owner, repo);
        jdbc.update("DELETE FROM repo_metadata_cache WHERE repo_full_name = ?", fullName);
        jdbc.update("DELETE FROM repo_write_collaborator WHERE repo_full_name = ?", fullName);
        jdbc.update("DELETE FROM criterion WHERE repo_id = ?", fullName);
        jdbc.update("DELETE FROM skill WHERE repo = ?", fullName);
        watchedRepos.remove(owner, repo);
    }

    private List<String> workspaceIdsFor(String fullName)
    {
        return workspaceStore.listWorkspaces().stream()
                .map(Workspace::id)
                .filter(id -> workspaceStore.listRepos(id).stream()
                        .anyMatch(attached -> attached.repoFullName().equalsIgnoreCase(fullName)))
                .toList();
    }

    /**
     * Remove the managed checkout together with the sibling
     * {@code <clone>.bytequay-worktrees} directory every worktree —
     * task, planning, cherry-pick, CI harness — is created under.
     *
     * <p>Only paths inside the app-managed repos root are removed. A
     * clone the user pointed at by hand is left alone; un-watching must
     * never recursively delete a directory ByteQuay didn't create.
     */
    private void deleteManagedClone(String clonePath)
    {
        Path clone = Path.of(clonePath).toAbsolutePath().normalize();
        Path managedRoot = LocalRepoService.managedReposRoot().toAbsolutePath().normalize();
        if (!clone.startsWith(managedRoot) || clone.equals(managedRoot)) {
            log.info("leaving clone {} in place — outside the managed repos root", clone);
            return;
        }
        deleteQuietly(clone);
        deleteQuietly(clone.resolveSibling(clone.getFileName() + ".bytequay-worktrees"));
    }

    private static void deleteQuietly(Path path)
    {
        try {
            if (Files.exists(path)) {
                FileSystemUtils.deleteRecursively(path);
            }
        }
        catch (Exception e) {
            log.warn("could not delete {}: {}", path, e.getMessage());
        }
    }
}
