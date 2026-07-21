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

import com.bytequay.app.beans.workspace.WorkspaceCreationDto;
import com.bytequay.app.domain.LocalRepoStatus;
import com.bytequay.app.domain.WatchedRepo;
import com.bytequay.app.domain.Workspace;
import com.bytequay.app.domain.WorkspaceRepo;
import com.bytequay.app.repository.WatchedRepoStore;
import com.bytequay.app.service.RepoService;
import com.bytequay.app.service.learning.ProjectLearningService;
import com.bytequay.app.service.local.LocalRepoService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Objects.requireNonNull;

/** Persisted, restart-safe clone and first-sync coordinator. */
@Service
public class WorkspaceCreationService
{
    private static final Logger log = LoggerFactory.getLogger(WorkspaceCreationService.class);
    private static final int FIRST_SYNC_STEPS = 3;
    private static final int RECLONE_STEPS = 2;
    private static final Set<String> LIVE_STATES = Set.of(
            "queued", "forking", "cloning", "syncing");

    private final JdbcTemplate jdbc;
    private final LocalRepoService localRepos;
    private final RepoService repos;
    private final WorkspaceService workspaces;
    private final WorkspaceConfigurationService configuration;
    private final WatchedRepoStore watchedRepos;
    private final ProjectLearningService projectLearning;
    private final Set<String> activeJobs = ConcurrentHashMap.newKeySet();

    public WorkspaceCreationService(
            JdbcTemplate jdbc,
            LocalRepoService localRepos,
            RepoService repos,
            WorkspaceService workspaces,
            WorkspaceConfigurationService configuration,
            WatchedRepoStore watchedRepos,
            ProjectLearningService projectLearning)
    {
        this.jdbc = requireNonNull(jdbc, "jdbc is null");
        this.localRepos = requireNonNull(localRepos, "localRepos is null");
        this.repos = requireNonNull(repos, "repos is null");
        this.workspaces = requireNonNull(workspaces, "workspaces is null");
        this.configuration = requireNonNull(configuration, "configuration is null");
        this.watchedRepos = requireNonNull(watchedRepos, "watchedRepos is null");
        this.projectLearning = requireNonNull(projectLearning, "projectLearning is null");
    }

    @Transactional
    public WorkspaceCreationDto create(
            String owner,
            String repo,
            String writeMode)
    {
        requireText(owner, "owner");
        requireText(repo, "repo");
        LocalRepoService.WriteMode.parse(writeMode);
        List<WorkspaceCreationDto> existing = jdbc.query("""
                SELECT * FROM workspace_creation
                WHERE lower(owner) = lower(?)
                  AND lower(repo) = lower(?)
                  AND state IN ('queued', 'forking', 'cloning', 'syncing')
                ORDER BY created_at_ms DESC
                LIMIT 1
                """, WorkspaceCreationService::map, owner, repo);
        if (!existing.isEmpty()) {
            return existing.getFirst();
        }
        long now = Instant.now().toEpochMilli();
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO workspace_creation (
                    id, operation_kind, owner, repo, write_mode, state,
                    stage_message, progress_current, progress_total,
                    attempt, created_at_ms, updated_at_ms)
                VALUES (?, 'connect', ?, ?, ?, 'queued',
                    'Waiting to start', 0, ?, 1, ?, ?)
                """,
                id, owner, repo, writeMode.toUpperCase(Locale.ROOT),
                FIRST_SYNC_STEPS, now, now);
        launchAfterCommit(id);
        return require(id);
    }

    public List<WorkspaceCreationDto> list()
    {
        return jdbc.query("""
                SELECT * FROM workspace_creation
                ORDER BY created_at_ms DESC
                """, WorkspaceCreationService::map);
    }

    @Transactional
    public WorkspaceCreationDto reclone(String workspaceId)
    {
        Workspace workspace = workspaces.require(workspaceId);
        List<WorkspaceRepo> owned = workspaces.listRepos(workspace.id());
        if (owned.size() != 1) {
            throw new IllegalStateException(
                    "workspace must own exactly one repository: " + workspaceId);
        }
        String fullName = owned.getFirst().repoFullName();
        int slash = fullName.indexOf('/');
        if (slash < 1 || slash == fullName.length() - 1) {
            throw new IllegalStateException(
                    "invalid workspace repository: " + fullName);
        }
        String owner = fullName.substring(0, slash);
        String repo = fullName.substring(slash + 1);
        WatchedRepo watched = watchedRepos.find(owner, repo)
                .orElseThrow(() -> new IllegalStateException(
                        "workspace repository is not watched: " + fullName));
        if (watched.localClonePath() == null
                || !Files.isDirectory(Path.of(watched.localClonePath()))) {
            throw new IllegalStateException(
                    "workspace has no verified clone to replace: " + fullName);
        }
        List<WorkspaceCreationDto> live = jdbc.query("""
                SELECT * FROM workspace_creation
                WHERE lower(owner) = lower(?)
                  AND lower(repo) = lower(?)
                  AND state IN ('queued', 'forking', 'cloning', 'syncing')
                ORDER BY created_at_ms DESC
                LIMIT 1
                """, WorkspaceCreationService::map, owner, repo);
        if (!live.isEmpty()) {
            return live.getFirst();
        }

        configuration.pauseAllSessions(workspaceId);
        String mode = watched.upstreamRemoteName() == null ? "DIRECT" : "FORK";
        long now = Instant.now().toEpochMilli();
        String id = UUID.randomUUID().toString();
        jdbc.update("""
                INSERT INTO workspace_creation (
                    id, operation_kind, owner, repo, write_mode, state,
                    stage_message, progress_current, progress_total,
                    workspace_id, previous_clone_path, attempt,
                    created_at_ms, updated_at_ms)
                VALUES (?, 'reclone', ?, ?, ?, 'queued',
                    'Waiting to re-clone', 0, ?, ?, ?, 1, ?, ?)
                """,
                id, owner, repo, mode, RECLONE_STEPS, workspaceId,
                watched.localClonePath(), now, now);
        launchAfterCommit(id);
        return require(id);
    }

    public WorkspaceCreationDto require(String id)
    {
        List<WorkspaceCreationDto> rows = jdbc.query("""
                SELECT * FROM workspace_creation WHERE id = ?
                """, WorkspaceCreationService::map, id);
        if (rows.isEmpty()) {
            throw new NoSuchElementException("no workspace creation: " + id);
        }
        return rows.getFirst();
    }

    @Transactional
    public WorkspaceCreationDto retry(String id)
    {
        WorkspaceCreationDto current = require(id);
        if (!"failed".equals(current.state())) {
            return current;
        }
        jdbc.update("""
                UPDATE workspace_creation
                SET state = 'queued',
                    stage_message = 'Waiting to retry',
                    error_message = NULL,
                    progress_current = 0,
                    attempt = attempt + 1,
                    updated_at_ms = ?
                WHERE id = ?
                """, Instant.now().toEpochMilli(), id);
        launchAfterCommit(id);
        return require(id);
    }

    /** Ready cards omit detached workspaces and a workspace whose first
     *  persisted creation is still syncing or failed. */
    public boolean visible(String workspaceId)
    {
        if (configuration.detached(workspaceId)) {
            return false;
        }
        Long blocked = jdbc.queryForObject("""
                SELECT count(*)
                FROM workspace_creation
                WHERE workspace_id = ?
                  AND operation_kind = 'connect'
                  AND state <> 'ready'
                """, Long.class, workspaceId);
        return blocked == null || blocked == 0;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void recover()
    {
        jdbc.queryForList("""
                SELECT id FROM workspace_creation
                WHERE state IN ('queued', 'forking', 'cloning', 'syncing')
                """, String.class).forEach(this::launch);
    }

    private void launch(String id)
    {
        if (!activeJobs.add(id)) {
            return;
        }
        Thread.startVirtualThread(() -> {
            try {
                execute(id);
            }
            finally {
                activeJobs.remove(id);
            }
        });
    }

    private void launchAfterCommit(String id)
    {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            launch(id);
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization()
                {
                    @Override
                    public void afterCommit()
                    {
                        launch(id);
                    }
                });
    }

    private void execute(String id)
    {
        WorkspaceCreationDto operation = require(id);
        if (!LIVE_STATES.contains(operation.state())) {
            return;
        }
        try {
            if ("reclone".equals(operation.operationKind())) {
                executeReclone(operation);
                return;
            }
            String cloneState = "FORK".equals(operation.writeMode())
                    ? "forking" : "cloning";
            update(id, cloneState,
                    "FORK".equals(operation.writeMode())
                            ? "Preparing your fork" : "Cloning repository",
                    0, null, null, null);
            if ("FORK".equals(operation.writeMode())) {
                update(id, "cloning", "Cloning repository", 0,
                        null, null, null);
            }
            LocalRepoStatus local = localRepos.cloneManaged(
                    operation.owner(),
                    operation.repo(),
                    LocalRepoService.WriteMode.parse(operation.writeMode()));
            Workspace workspace = workspaces.ensureForVerifiedClone(
                    operation.owner(), operation.repo());
            configuration.reconnect(workspace.id());
            configuration.settings(workspace.id());
            configuration.onboarding(workspace.id());
            updateOnboardingProgress(workspace.id(), 0);
            update(id, "syncing", "Syncing pull requests", 0,
                    workspace.id(), local.localClonePath(), null);

            repos.getRepoPullRequests(operation.owner(), operation.repo());
            updateOnboardingProgress(workspace.id(), 1);
            update(id, "syncing", "Syncing open issues", 1,
                    workspace.id(), local.localClonePath(), null);
            repos.getRepoIssues(operation.owner(), operation.repo(), "open");
            updateOnboardingProgress(workspace.id(), 2);
            update(id, "syncing", "Syncing repository details", 2,
                    workspace.id(), local.localClonePath(), null);
            repos.getRepoMeta(operation.owner(), operation.repo());

            updateOnboardingReady(workspace.id());
            update(id, "ready", "Workspace ready", FIRST_SYNC_STEPS,
                    workspace.id(), local.localClonePath(), null);

            // The workspace is usable now; historical project learning runs
            // on its own durable, resumable background run and never blocks
            // this ready transition.
            projectLearning.enqueue(workspace.id(),
                    operation.owner() + "/" + operation.repo(), "clone");
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail(id, "Background creation was interrupted");
        }
        catch (Exception e) {
            log.warn("workspace creation {} failed: {}", id, e.getMessage());
            fail(id, e.getMessage() == null ? "Workspace creation failed" : e.getMessage());
        }
    }

    private void executeReclone(WorkspaceCreationDto operation)
            throws Exception
    {
        String upstream = "FORK".equals(operation.writeMode())
                ? "upstream" : null;
        Path destination = replacementPath(operation);
        LocalRepoService.PreparedClone prepared =
                localRepos.verifiedPreparedClone(destination, upstream)
                        .orElse(null);
        if (prepared == null) {
            if (Files.exists(destination)) {
                destination = recoveryReplacementPath(operation);
            }
            update(operation.id(), "cloning",
                    "Cloning into a new directory", 0,
                    operation.workspaceId(), destination.toString(), null);
            prepared = localRepos.prepareManagedClone(
                    operation.owner(),
                    operation.repo(),
                    LocalRepoService.WriteMode.parse(operation.writeMode()),
                    destination);
        }
        update(operation.id(), "syncing",
                "Verifying replacement clone", 1,
                operation.workspaceId(), prepared.path().toString(), null);
        LocalRepoStatus activated = localRepos.activatePreparedClone(
                operation.owner(), operation.repo(), prepared);
        if (activated.state() != LocalRepoStatus.State.CLEAN
                && activated.state() != LocalRepoStatus.State.MODIFIED) {
            throw new IllegalStateException(
                    "replacement clone did not pass verification");
        }
        update(operation.id(), "ready",
                "Workspace re-cloned", RECLONE_STEPS,
                operation.workspaceId(), prepared.path().toString(), null);
    }

    private static Path replacementPath(WorkspaceCreationDto operation)
    {
        if (operation.clonePath() != null
                && !operation.clonePath().isBlank()) {
            return Path.of(operation.clonePath());
        }
        Path current = Path.of(operation.previousClonePath());
        String suffix = operation.id().substring(
                0, Math.min(8, operation.id().length()));
        return current.resolveSibling(
                current.getFileName() + ".reclone-" + suffix
                        + "-a" + operation.attempt());
    }

    private static Path recoveryReplacementPath(
            WorkspaceCreationDto operation)
    {
        Path initial = replacementPath(operation);
        return initial.resolveSibling(
                initial.getFileName() + "-recovery-"
                        + Instant.now().toEpochMilli());
    }

    private void updateOnboardingReady(String workspaceId)
    {
        long now = Instant.now().toEpochMilli();
        jdbc.update("""
                UPDATE workspace_onboarding
                SET clone_complete = 1,
                    sync_state = 'ready',
                    sync_current = ?,
                    sync_total = ?,
                    updated_at_ms = ?
                WHERE workspace_id = ?
                """, FIRST_SYNC_STEPS, FIRST_SYNC_STEPS, now, workspaceId);
    }

    private void updateOnboardingProgress(String workspaceId, int current)
    {
        long now = Instant.now().toEpochMilli();
        jdbc.update("""
                UPDATE workspace_onboarding
                SET clone_complete = 1,
                    sync_state = 'syncing',
                    sync_current = ?,
                    sync_total = ?,
                    updated_at_ms = ?
                WHERE workspace_id = ?
                """, current, FIRST_SYNC_STEPS, now, workspaceId);
    }

    private void fail(String id, String message)
    {
        update(id, "failed", "Couldn't finish workspace setup",
                null, null, null, message);
    }

    private void update(
            String id,
            String state,
            String message,
            Integer current,
            String workspaceId,
            String clonePath,
            String error)
    {
        jdbc.update("""
                UPDATE workspace_creation
                SET state = ?,
                    stage_message = ?,
                    progress_current = COALESCE(?, progress_current),
                    workspace_id = COALESCE(?, workspace_id),
                    clone_path = COALESCE(?, clone_path),
                    error_message = ?,
                    updated_at_ms = ?
                WHERE id = ?
                """,
                state, message, current, workspaceId, clonePath, error,
                Instant.now().toEpochMilli(), id);
    }

    private static WorkspaceCreationDto map(
            ResultSet rs,
            int ignored)
            throws SQLException
    {
        return new WorkspaceCreationDto(
                rs.getString("id"),
                rs.getString("operation_kind"),
                rs.getString("owner"),
                rs.getString("repo"),
                rs.getString("write_mode"),
                rs.getString("state"),
                rs.getString("stage_message"),
                rs.getInt("progress_current"),
                rs.getInt("progress_total"),
                rs.getString("workspace_id"),
                rs.getString("clone_path"),
                rs.getString("previous_clone_path"),
                rs.getString("error_message"),
                rs.getInt("attempt"),
                rs.getLong("created_at_ms"),
                rs.getLong("updated_at_ms"));
    }

    private static void requireText(String value, String name)
    {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " is required");
        }
    }
}
