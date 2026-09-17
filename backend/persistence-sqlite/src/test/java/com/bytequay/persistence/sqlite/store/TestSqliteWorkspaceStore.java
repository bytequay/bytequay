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
package com.bytequay.persistence.sqlite.store;

import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.core.ci.CiAutofixAttempt;
import com.bytequay.core.ci.CiAutofixAttemptId;
import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.ci.CiAutofixRunId;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RecentText;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.domain.Workspace.Topology;
import com.bytequay.core.rebase.BranchRebase;
import com.bytequay.core.rebase.BranchRebaseId;
import com.bytequay.core.review.LocalRun;
import com.bytequay.persistence.sqlite.entity.BranchRebaseEntity;
import com.bytequay.persistence.sqlite.entity.CiAutofixAttemptEntity;
import com.bytequay.persistence.sqlite.entity.CiAutofixRunEntity;
import com.bytequay.persistence.sqlite.entity.PullRequestEntity;
import com.bytequay.persistence.sqlite.entity.WorkspaceEntity;
import com.bytequay.persistence.sqlite.repository.WorkspaceJpaRepository;
import com.google.common.collect.ImmutableList;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSqliteWorkspaceStore
        extends AbstractTestSqliteStore
{
    private static final String DATABASE_PATH = Path.of(
            System.getProperty("java.io.tmpdir"),
            "bytequay-workspace-store-" + UUID.randomUUID() + ".db").toString();
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @Autowired
    private SqliteWorkspaceStore store;

    @Autowired
    private WorkspaceJpaRepository repository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entities;

    @Autowired
    private SqliteAgentTaskStore tasks;

    @Autowired
    private SqliteRecentStore recent;

    @Autowired
    private SqliteAppSettingsStore settings;

    @Autowired
    private SqliteLocalRunStore localRuns;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry)
    {
        createLegacyWorkspaceTable();
        registerSqliteProperties(registry, "jdbc:sqlite:" + DATABASE_PATH + "?foreign_keys=ON", "update");
    }

    @Test
    void keepsLegacyWorkspaceTableSeparate()
    {
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM workspaces", Long.class)).isEqualTo(1);
        assertThat(store.findAll()).isEmpty();
    }

    @Test
    void roundTripsUnlinkedAndLinkedRouting()
    {
        Workspace direct = store.createOrGet(direct("direct", 100, 0));
        Workspace fork = store.createOrGet(fork("fork", 200, 300, 1));

        assertThat(store.findById(direct.id())).contains(direct);
        assertThat(store.findById(fork.id())).contains(fork);
        assertThat(fork.upstreamRepositoryId()).contains(300L);
        assertThat(fork.upstreamRepository()).contains(RepoRef.of("upstream", "project"));
        assertThat(fork.monitoredRepositoryId()).isEqualTo(300);
        assertThat(fork.monitoredRepository()).isEqualTo(RepoRef.of("upstream", "project"));
    }

    @Test
    void repeatedStableRepositoryIdReturnsExistingWorkspace()
    {
        Workspace first = store.createOrGet(direct("first", 100, 0));
        Workspace repeated = store.createOrGet(direct("second", 100, 1));

        assertThat(repeated.id()).isEqualTo(first.id());
        assertThat(repository.count()).isEqualTo(1);
    }

    @Test
    void unwatchRetainsIdentityAndRoutingUntilExplicitlyWatchedAgain()
    {
        Workspace direct = store.createOrGet(direct("direct", 100, 0));
        Workspace fork = store.createOrGet(fork("fork", 200, 300, 1));
        assertThat(store.isWatched(fork.id())).isTrue();

        store.setWatched(fork.id(), false);

        assertThat(store.isWatched(fork.id())).isFalse();
        assertThat(jdbc.queryForObject("SELECT watched FROM watched_repo_workspaces WHERE id = ?", Boolean.class, fork.id())).isFalse();
        assertThat(store.findAll()).containsExactly(direct);
        assertThat(store.findAllRetained()).containsExactly(direct, fork);
        assertThat(store.findById(fork.id())).contains(fork);
        assertThat(store.findBySelectedRepositoryId(fork.selectedRepositoryId())).contains(fork);
        assertThat(store.createOrGet(fork("replacement", 200, 300, 2))).isEqualTo(fork);
        assertThat(store.isWatched(fork.id())).isFalse();

        Workspace renamed = store.save(fork.withName("Retained local work", NOW.plusSeconds(60)));
        assertThat(store.findAll()).containsExactly(direct);
        assertThat(store.isWatched(fork.id())).isFalse();

        store.setWatched(fork.id(), true);

        assertThat(store.isWatched(fork.id())).isTrue();
        assertThat(store.findAll()).containsExactly(direct, renamed);
        assertThat(renamed.id()).isEqualTo(fork.id());
        assertThat(renamed.localRepositoryPath()).isEqualTo(fork.localRepositoryPath());
        assertThat(renamed.upstreamRepositoryId()).isEqualTo(fork.upstreamRepositoryId());
        assertThat(repository.count()).isEqualTo(2);
    }

    @Test
    void unknownWorkspaceCannotBeWatched()
    {
        assertThat(store.isWatched("missing")).isFalse();
        assertThatThrownBy(() -> store.setWatched("missing", true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Workspace not found: missing");
        assertThat(store.findAllRetained()).isEmpty();
    }

    @Test
    void commitsTargetBranchSurvivesUnwatchAndRewatchUntilWorkspaceDeletion()
    {
        Workspace workspace = store.createOrGet(direct("target-settings", 100, 0));
        String key = "workspace.commitsTargetBranch." + workspace.id();
        settings.put(key, "release/next");

        store.setWatched(workspace.id(), false);
        entities.flush();
        entities.clear();
        assertThat(settings.get(key)).contains("release/next");

        store.setWatched(workspace.id(), true);
        entities.flush();
        entities.clear();
        assertThat(settings.get(key)).contains("release/next");

        store.delete(workspace.id());
        assertThat(settings.get(key)).isEmpty();
    }

    @Test
    void deletePurgesTheWorkspaceLocalRunsWithoutAReview()
    {
        Workspace removed = store.createOrGet(direct("runs-removed", 100, 0));
        Workspace retained = store.createOrGet(direct("runs-retained", 101, 1));
        for (Workspace workspace : ImmutableList.of(removed, retained)) {
            LocalRun run = localRuns.insert(LocalRun.started("run-" + workspace.id(), "task-" + workspace.id(), workspace.id(), 1, "src/test/java/TestCode.java", "TestCode",
                    Optional.empty(), Optional.empty(), "./mvnw -B -Dtest=TestCode test", "a".repeat(40), ImmutableList.of(), "fingerprint", "launch", NOW));
            localRuns.update(run.finished(LocalRun.Status.STOPPED, NOW, OptionalInt.empty(), Optional.empty(), ImmutableList.of(), false, OptionalLong.empty()), "output");
        }

        store.delete(removed.id());

        assertThat(jdbc.queryForList("SELECT id FROM local_run", String.class)).containsExactly("run-" + retained.id());
        assertThat(jdbc.queryForList("SELECT run_id FROM local_run_output", String.class)).containsExactly("run-" + retained.id());
    }

    @Test
    void deletePurgesOwnedDataAndPreservesRetainedSharedAndAccountData()
    {
        Workspace removed = store.createOrGet(fork("removed", 200, 300, 0));
        Workspace retained = store.createOrGet(workspace(
                "retained", 201, RepoRef.of("other", "project"), Optional.of(300L), Optional.of(RepoRef.of("upstream", "project")),
                300, RepoRef.of("upstream", "project"), Topology.FORK, Optional.of("https://github.com/upstream/project.git"), 1));
        store.setWatched(retained.id(), false);
        for (Workspace workspace : ImmutableList.of(removed, retained)) {
            jdbc.update("INSERT INTO workspace_credentials (workspace_id, ciphertext, login) VALUES (?, 'encrypted', 'owner')", workspace.id());
            jdbc.update("INSERT INTO app_settings (setting_key, setting_value) VALUES (?, 'model')", "workspace.agentModel." + workspace.id());
            settings.put("workspace.commitsTargetBranch." + workspace.id(), "release/next");
            jdbc.update("INSERT INTO commit_refresh_metadata (workspace_id, error) VALUES (?, 'retained')", workspace.id());
            jdbc.update("INSERT INTO workspace_remote_sources (id, workspace_id, name, url) VALUES (?, ?, 'extra', 'https://github.com/extra/project.git')",
                    workspace.id() + ":extra", workspace.id());
        }
        for (long repositoryId : ImmutableList.of(200L, 201L, 300L)) {
            jdbc.update("INSERT INTO repository_issue_sync (repository_id, synced_at) VALUES (?, ?)", repositoryId, NOW.toString());
            jdbc.update("INSERT INTO commit_enrichment (repository_id, sha, last_seen_at) VALUES (?, 'sha', ?)", repositoryId, NOW.toString());
        }
        long ownedPr = insertPullRequest("contributor/project", 1, false, false);
        long sharedPr = insertPullRequest("upstream/project", 2, false, false);
        long dashboardPr = insertPullRequest("contributor/project", 3, true, false);
        long monitoredPr = insertPullRequest("contributor/project", 4, false, true);
        for (long prId : ImmutableList.of(ownedPr, sharedPr, dashboardPr, monitoredPr)) {
            jdbc.update("INSERT INTO pr_view_state (pr_id, viewed_at) VALUES (?, ?)", prId, NOW.toString());
        }

        store.delete(removed.id());

        assertThat(store.findById(removed.id())).isEmpty();
        assertThat(store.findAllRetained()).containsExactly(retained);
        assertThat(store.isWatched(retained.id())).isFalse();
        for (String table : ImmutableList.of("workspace_credentials", "commit_refresh_metadata", "workspace_remote_sources")) {
            assertThat(jdbc.queryForList("SELECT workspace_id FROM " + table, String.class)).containsExactly(retained.id());
        }
        assertThat(jdbc.queryForList("SELECT setting_key FROM app_settings", String.class))
                .containsExactlyInAnyOrder("workspace.agentModel." + retained.id(), "workspace.commitsTargetBranch." + retained.id());
        assertThat(jdbc.queryForList("SELECT repository_id FROM repository_issue_sync ORDER BY repository_id", Long.class)).containsExactly(201L, 300L);
        assertThat(jdbc.queryForList("SELECT repository_id FROM commit_enrichment ORDER BY repository_id", Long.class)).containsExactly(201L, 300L);
        assertThat(jdbc.queryForList("SELECT id FROM pull_requests ORDER BY id", Long.class)).containsExactly(sharedPr, dashboardPr, monitoredPr);
        assertThat(jdbc.queryForList("SELECT pr_id FROM pr_view_state ORDER BY pr_id", Long.class)).containsExactly(sharedPr, dashboardPr, monitoredPr);
    }

    @Test
    void extraRemoteProtectsRepositoryCacheWithoutResolvedProviderIdentity()
    {
        Workspace removed = store.createOrGet(fork("removed", 200, 300, 0));
        Workspace retained = store.createOrGet(direct("retained", 100, 1));
        jdbc.update("INSERT INTO workspace_remote_sources (id, workspace_id, name, url) VALUES ('remote', ?, 'extra', 'git@github.com:contributor/project.git')",
                retained.id());
        jdbc.update("INSERT INTO repository_issue_sync (repository_id, synced_at) VALUES (200, ?)", NOW.toString());

        store.delete(removed.id());

        assertThat(jdbc.queryForList("SELECT repository_id FROM repository_issue_sync", Long.class)).containsExactly(200L);
        assertThat(jdbc.queryForList("SELECT id FROM workspace_remote_sources", String.class)).containsExactly("remote");
    }

    @Test
    void recentWordsAndTouchesFollowIssueAndPullRequestOwnership()
    {
        Workspace removed = store.createOrGet(fork("removed", 200, 300, 0));
        Workspace retained = store.createOrGet(workspace(
                "retained", 201, RepoRef.of("other", "project"), Optional.of(300L), Optional.of(RepoRef.of("upstream", "project")),
                300, RepoRef.of("upstream", "project"), Topology.FORK, Optional.of("https://github.com/upstream/project.git"), 1));
        long removedPr = insertPullRequest("contributor/project", 1, false, false);
        long dashboardPr = insertPullRequest("contributor/project", 2, true, false);
        insertPullRequest("contributor/project", 3, false, true);
        insertPullRequest("upstream/project", 4, false, false);
        jdbc.update("UPDATE pull_requests SET repo = 'Contributor/Project' WHERE id IN (?, ?)", removedPr, dashboardPr);
        entities.clear();
        insertRecent("issue:Contributor/Project#10", "ISSUE", "Contributor/Project", 10, removed.id());
        insertRecent("issue:upstream/project#11", "ISSUE", "upstream/project", 11, removed.id());
        insertRecent("pr:Contributor/Project#1", "PULL_REQUEST", "Contributor/Project", 1, removed.id());
        insertRecent("pr:Contributor/Project#2", "PULL_REQUEST", "Contributor/Project", 2, removed.id());
        insertRecent("pr:contributor/project#3", "PULL_REQUEST", "contributor/project", 3, removed.id());
        insertRecent("pr:upstream/project#4", "PULL_REQUEST", "upstream/project", 4, removed.id());

        store.delete(removed.id());

        assertThat(recent.findTextContaining("words", 30)).extracting(RecentText::itemKey)
                .containsOnly("issue:upstream/project#11", "pr:Contributor/Project#2", "pr:contributor/project#3", "pr:upstream/project#4");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recent_text", Integer.class)).isEqualTo(8);
        assertThat(jdbc.queryForList("SELECT item_key FROM recent_touch", String.class))
                .containsExactlyInAnyOrder("issue:upstream/project#11", "pr:Contributor/Project#2", "pr:contributor/project#3", "pr:upstream/project#4");
        assertThat(jdbc.queryForObject("SELECT workspace_id FROM recent_touch WHERE item_key = 'issue:upstream/project#11'", String.class)).isEqualTo(retained.id());
        assertThat(jdbc.queryForObject("SELECT workspace_id FROM recent_touch WHERE item_key = 'pr:Contributor/Project#2'", String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM pull_requests WHERE id = ?", Integer.class, removedPr)).isZero();
        assertThat(jdbc.queryForObject("SELECT workspace_visible FROM pull_requests WHERE id = ?", Boolean.class, dashboardPr)).isFalse();
    }

    private void insertRecent(String key, String type, String repo, int number, String workspaceId)
    {
        jdbc.update("""
                INSERT INTO recent_touch (id, item_type, item_key, touch_day, touched_at, reason, reason_count, item_number, title, repo, workspace_id, mine)
                VALUES (?, ?, ?, '2026-08-21', ?, 'COMMENTED', 1, ?, 'Title', ?, ?, 1)
                """, key + "@2026-08-21", type, key, NOW.toString(), number, repo, workspaceId);
        for (String kind : ImmutableList.of("COMMENT", "DRAFT")) {
            jdbc.update("""
                    INSERT INTO recent_text (id, item_type, item_key, kind, body, written_at, item_number, repo)
                    VALUES (?, ?, ?, ?, 'words', ?, ?, ?)
                    """, key + ":" + kind, type, key, kind, NOW.toString(), number, repo);
        }
    }

    @Test
    void allWorkflowHistoryIsCheckedAndPurgedWithoutRecentListLimits()
    {
        Workspace workspace = store.createOrGet(direct("workflow", 100, 0));
        CiAutofixRun active = ciRun(workspace.id(), 0);
        entities.persist(CiAutofixRunEntity.fromDomain(active));
        for (int index = 1; index <= 205; index++) {
            CiAutofixRun run = ciRun(workspace.id(), index).finish(CiAutofixRun.FinishReason.CANCELLED, "finished", NOW.plusSeconds(index));
            entities.persist(CiAutofixRunEntity.fromDomain(run));
            CiAutofixAttempt attempt = CiAutofixAttempt.create(
                    CiAutofixAttemptId.random(), run.id(), 1, "a".repeat(40),
                    ImmutableList.of(new CiAutofixAttempt.FailureEvidence("failure", "Failed", 1, 1, 1, 1, Optional.empty())), NOW);
            entities.persist(CiAutofixAttemptEntity.fromDomain(attempt));
        }
        entities.flush();
        assertThat(store.hasActiveWorkflows(workspace.id())).isTrue();
        assertThatThrownBy(() -> store.delete(workspace.id())).isInstanceOf(IllegalStateException.class).hasMessageContaining("active workflow");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ci_autofix_run", Integer.class)).isEqualTo(206);
        jdbc.update("UPDATE ci_autofix_run SET terminal_at = ?, status = 'FINISHED', active_branch_slot = NULL WHERE id = ?", NOW.toString(), active.id().toString());
        entities.clear();
        assertThat(store.hasActiveWorkflows(workspace.id())).isFalse();

        store.delete(workspace.id());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ci_autofix_run", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ci_autofix_attempt", Integer.class)).isZero();
        assertThat(store.findAllRetained()).isEmpty();
    }

    @Test
    void legacyCiPathRetainsTheWorkspaceAssociation()
    {
        Workspace workspace = store.createOrGet(direct("legacy-ci", 100, 0));
        CiAutofixRun run = ciRun(workspace.id(), 0);
        entities.persist(CiAutofixRunEntity.fromDomain(run));
        entities.flush();
        jdbc.update("UPDATE ci_autofix_run SET workspace_id = NULL, worktree = ? WHERE id = ?", workspace.localRepositoryPath(), run.id().toString());
        entities.clear();

        assertThat(store.hasActiveWorkflows(workspace.id())).isTrue();
        jdbc.update("UPDATE ci_autofix_run SET terminal_at = ?, status = 'FINISHED', active_branch_slot = NULL WHERE id = ?", NOW.toString(), run.id().toString());

        store.delete(workspace.id());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM ci_autofix_run", Integer.class)).isZero();
    }

    @Test
    void taskDeletionRejectsActiveRebasesAndRemovesFinishedRebaseAssociations()
    {
        Workspace workspace = store.createOrGet(direct("with-task", 100, 0));
        AgentTask task = AgentTask.created(TaskId.random(), Optional.of(workspace.id()), false, "Retained task", AgentLaneId.CODEX_CLI,
                new RepositoryIdentity("owner", "project"), Path.of("/tmp/task"), AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), NOW);
        tasks.insert(task);
        BranchRebase rebase = BranchRebase.create(BranchRebaseId.random(), "rebase", task.id().toString(), RepoRef.of("owner", "project"),
                task.worktree(), "feature", "main", "a".repeat(40), "b".repeat(40), NOW);
        entities.persist(BranchRebaseEntity.fromDomain(rebase));
        entities.flush();

        assertThat(store.hasActiveWorkflows(workspace.id())).isTrue();
        assertThatThrownBy(() -> tasks.delete(task.id())).isInstanceOf(AgentConflictException.class).hasMessageContaining("active branch rebase");
        jdbc.update("UPDATE branch_rebase SET terminal_at = ?, status = 'FINISHED' WHERE id = ?", NOW.toString(), rebase.id().toString());
        entities.clear();
        assertThatThrownBy(() -> store.delete(workspace.id())).isInstanceOf(IllegalStateException.class).hasMessageContaining("agent tasks");

        tasks.delete(task.id());
        store.delete(workspace.id());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM branch_rebase", Integer.class)).isZero();
        assertThat(tasks.find(task.id())).isEmpty();
        assertThat(store.findById(workspace.id())).isEmpty();
    }

    private long insertPullRequest(String repo, int number, boolean dashboard, boolean monitored)
    {
        PullRequestEntity entity = new PullRequestEntity();
        entity.setRepo(repo);
        entity.setNumber(number);
        entity.setTitle("PR " + number);
        entity.setOrigin("AUTHORED");
        entity.setLabels(ImmutableList.of());
        entity.setSyncedAt(NOW);
        entity.setDashboardVisible(dashboard);
        entity.setWorkspaceVisible(true);
        entity.setMonitoredAt(monitored ? NOW : null);
        entities.persist(entity);
        entities.flush();
        return entity.getId();
    }

    private static CiAutofixRun ciRun(String workspaceId, int index)
    {
        return CiAutofixRun.create(CiAutofixRunId.random(), "run-" + index,
                new CiAutofixRun.Scope(PullRequestRef.of("owner", "project", index + 1), RepoRef.of("owner", "project"), workspaceId, Optional.of(RepoRef.of("owner", "project")),
                        "author", "branch-" + index, "main", "codex", Optional.empty()),
                "a".repeat(40), "a".repeat(40), "b".repeat(40),
                new CiAutofixRun.Authorization(false, false, false, 1, NOW, NOW.plusSeconds(3600)), NOW);
    }

    @Test
    void databaseRejectsDuplicateSelectedRepositoryId()
    {
        repository.saveAndFlush(WorkspaceEntity.fromDomain(direct("first", 100, 0)));

        assertThatThrownBy(() ->
                repository.saveAndFlush(WorkspaceEntity.fromDomain(direct("second", 100, 1))))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void multipleWorkspacesCanShareOneNonUniqueUpstream()
    {
        Workspace first = store.createOrGet(fork("first", 200, 300, 0));
        Workspace second = store.createOrGet(fork("second", 201, 300, 1));

        assertThat(first.upstreamRepositoryId()).contains(300L);
        assertThat(second.upstreamRepositoryId()).contains(300L);
        assertThat(repository.count()).isEqualTo(2);
    }

    private static Workspace direct(String id, long selectedId, int displayOrder)
    {
        RepoRef repository = RepoRef.of("owner", "project");
        return workspace(
                id, selectedId, repository, Optional.empty(), Optional.empty(), selectedId,
                repository, Topology.DIRECT, Optional.empty(), displayOrder);
    }

    private static void createLegacyWorkspaceTable()
    {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + DATABASE_PATH);
                var statement = connection.createStatement()) {
            statement.execute("""
                    CREATE TABLE workspaces (
                        id INTEGER PRIMARY KEY AUTOINCREMENT,
                        created_at varchar(255) NOT NULL,
                        last_opened_at varchar(255),
                        name varchar(255) NOT NULL,
                        repos TEXT NOT NULL,
                        slug varchar(255) NOT NULL)
                    """);
            statement.execute("""
                    INSERT INTO workspaces (created_at, name, repos, slug)
                    VALUES ('2026-08-01T00:00:00Z', 'Legacy workspace', '[]', 'legacy-workspace')
                    """);
        }
        catch (SQLException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static Workspace fork(String id, long selectedId, long upstreamId, int displayOrder)
    {
        RepoRef selected = RepoRef.of("contributor", "project");
        RepoRef upstream = RepoRef.of("upstream", "project");
        return workspace(
                id, selectedId, selected, Optional.of(upstreamId), Optional.of(upstream), upstreamId,
                upstream, Topology.FORK, Optional.of("https://github.com/upstream/project.git"), displayOrder);
    }

    private static Workspace workspace(
            String id,
            long selectedId,
            RepoRef selected,
            Optional<Long> upstreamId,
            Optional<RepoRef> upstream,
            long monitoredId,
            RepoRef monitored,
            Topology topology,
            Optional<String> upstreamCloneUrl,
            int displayOrder)
    {
        return new Workspace(
                id,
                selected.fullName(),
                selectedId,
                selected,
                upstreamId,
                upstream,
                monitoredId,
                monitored,
                topology,
                "main",
                "https://github.com/" + selected.fullName() + ".git",
                upstreamId.map(ignored -> "main"),
                upstreamCloneUrl,
                "/tmp/workspaces/" + id + "/repository",
                Optional.empty(),
                Optional.empty(),
                displayOrder,
                NOW,
                NOW);
    }
}
