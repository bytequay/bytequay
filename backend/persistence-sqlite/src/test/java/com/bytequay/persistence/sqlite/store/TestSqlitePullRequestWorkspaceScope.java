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

import com.bytequay.core.domain.FetchedPullRequest;
import com.bytequay.core.domain.HandledAction;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.persistence.sqlite.config.SqlitePersistenceConfiguration;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = SqlitePersistenceConfiguration.class)
class TestSqlitePullRequestWorkspaceScope
{
    private static final Instant RETENTION_CUTOFF = Instant.parse("2026-08-01T00:00:00Z");
    private static final String DATABASE_PATH = Path.of(
            System.getProperty("java.io.tmpdir"),
            "bytequay-pr-scope-" + UUID.randomUUID() + ".db").toString();

    @Autowired
    private SqlitePullRequestStore store;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private SqlitePrViewStateStore viewStateStore;

    @Autowired
    private SqliteWorkspaceStore workspaceStore;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry)
    {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE_PATH + "?foreign_keys=ON");
        registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.community.dialect.SQLiteDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
    }

    @Test
    void currentWorkspaceRowsAppearInTheGlobalListWithoutLeakingRemovedWorkspaces()
    {
        FetchedPullRequest global = pullRequest(1, "account/repo", 1);
        FetchedPullRequest shared = pullRequest(2, "owner/project", 2);
        FetchedPullRequest workspaceOnly = pullRequest(3, "owner/project", 3);
        FetchedPullRequest otherWorkspace = pullRequest(4, "other/project", 4);
        Workspace workspace = workspace("current", 100, RepoRef.of("owner", "project"));
        workspaceStore.createOrGet(workspace);

        store.saveDashboardSyncPage(ImmutableList.of(global, shared), "global-1");
        store.completeDashboardSync("global-1", "alice");
        store.saveRepositorySyncPage("owner/project", ImmutableList.of(shared, workspaceOnly), "workspace-1");
        store.completeRepositorySync("owner/project", RETENTION_CUTOFF);
        store.saveRepositorySyncPage("other/project", ImmutableList.of(otherWorkspace), "workspace-2");
        store.completeRepositorySync("other/project", RETENTION_CUTOFF);

        assertThat(store.findPage(0, 50).pullRequests())
                .extracting(PullRequest::id)
                .containsExactlyInAnyOrder(1L, 2L, 3L);
        assertThat(store.findPage(0, 50).openPullRequests()).isEqualTo(3);
        assertThat(store.findPage(0, 50, "#3").pullRequests())
                .extracting(PullRequest::id)
                .containsExactly(3L);
        assertThat(store.findPage(0, 50, "#4").pullRequests()).isEmpty();
        assertThat(store.findRepositoryPage("OWNER/PROJECT", 0, 50).pullRequests())
                .extracting(PullRequest::id)
                .containsExactlyInAnyOrder(2L, 3L);
        assertThat(store.findRepositoryPage("owner/project", 0, 50).pullRequests())
                .extracting(PullRequest::id)
                .doesNotContain(4L);

        store.saveDashboardSyncPage(ImmutableList.of(global), "global-2");
        store.completeDashboardSync("global-2", "alice");

        assertThat(store.findPage(0, 50).pullRequests())
                .extracting(PullRequest::id)
                .containsExactlyInAnyOrder(1L, 2L, 3L);
        assertThat(store.findRepositoryPage("owner/project", 0, 50).pullRequests())
                .extracting(PullRequest::id)
                .containsExactlyInAnyOrder(2L, 3L);

        workspaceStore.delete(workspace.id());

        assertThat(store.findPage(0, 50).pullRequests())
                .extracting(PullRequest::id)
                .containsExactlyInAnyOrder(1L, 2L);
        assertThat(store.findPage(0, 50, "#3").pullRequests()).isEmpty();
    }

    @Test
    void readsPullRequestsOneDatabasePageAtATime()
    {
        ImmutableList.Builder<FetchedPullRequest> pullRequests = ImmutableList.builder();
        for (int index = 1; index <= 55; index++) {
            pullRequests.add(pullRequest(index, "owner/project", index));
        }
        store.saveRepositorySyncPage("owner/project", pullRequests.build(), "workspace-page");
        store.completeRepositorySync("owner/project", RETENTION_CUTOFF);

        var first = store.findRepositoryPage("owner/project", 0, 50);
        var second = store.findRepositoryPage("owner/project", 1, 50);

        assertThat(first.pullRequests()).hasSize(50);
        assertThat(first.totalPullRequests()).isEqualTo(55);
        assertThat(first.hasMore()).isTrue();
        assertThat(second.pullRequests()).hasSize(5);
        assertThat(second.hasMore()).isFalse();
    }

    @Test
    void searchesARepositoryByTitleOrPullRequestNumber()
    {
        FetchedPullRequest first = pullRequest(1, "owner/project", 41);
        FetchedPullRequest second = pullRequest(2, "owner/project", 42);
        store.saveRepositorySyncPage("owner/project", ImmutableList.of(first, second), "workspace-search");
        store.completeRepositorySync("owner/project", RETENTION_CUTOFF);

        assertThat(store.findRepositoryPage("owner/project", 0, 50, "pr 41").pullRequests())
                .extracting(PullRequest::number)
                .containsExactly(41);
        assertThat(store.findRepositoryPage("owner/project", 0, 50, "#42").pullRequests())
                .extracting(PullRequest::number)
                .containsExactly(42);
    }

    @Test
    void storesDirectlyOpenedPullRequestInTheLocalDashboard()
    {
        FetchedPullRequest observed = pullRequest(91, "external/project", 12);

        PullRequest stored = store.saveObserved(observed);

        assertThat(store.findIdByRepoAndNumber("external/project", 12)).contains(stored.id());
        assertThat(store.findRefById(stored.id())).contains(PullRequestRef.of("external", "project", 12));
        assertThat(store.findPage(0, 50).pullRequests()).containsExactly(stored);
    }

    @Test
    void repairsAuthoredOriginsForTheCurrentGitHubUser()
    {
        long mine = store.saveObserved(pullRequest(92, "external/project", 12)).id();
        long someoneElses = store.saveObserved(pullRequest(93, "external/project", 13)).id();
        jdbc.update("UPDATE pull_requests SET author = 'bob' WHERE id = ?", someoneElses);

        store.completeDashboardSync("dashboard", "ALICE");

        assertThat(store.findPage(0, 50).pullRequests())
                .extracting(PullRequest::id, PullRequest::origin)
                .containsExactlyInAnyOrder(
                        tuple(mine, PullRequest.Origin.AUTHORED),
                        tuple(someoneElses, PullRequest.Origin.OTHER));
    }

    @Test
    void updatesByRepositoryAndNumberWhenGitHubApisReturnDifferentIds()
    {
        // search/issues and the pulls endpoint report different ids for one
        // pull request; neither may decide the row identity.
        FetchedPullRequest searchResult = pullRequest(91, "external/project", 12);
        FetchedPullRequest pullEndpointResult = pullRequest(92, "external/project", 12);
        long first = store.saveObserved(searchResult).id();
        long second = store.saveObserved(pullEndpointResult).id();

        assertThat(second).isEqualTo(first);
        assertThat(store.findIdByRepoAndNumber("EXTERNAL/PROJECT", 12)).contains(first);
        assertThat(store.findPage(0, 50).pullRequests())
                .extracting(PullRequest::id)
                .containsExactly(first);
    }

    @Test
    void keepsSweptPullRequestsThatStillCarryViewState()
    {
        FetchedPullRequest reviewed = pullRequest(301, "owner/project", 301);
        long id = store.saveObserved(reviewed).id();
        viewStateStore.markReviewed(id, HandledAction.APPROVED, "headsha");
        jdbc.update("UPDATE pull_requests SET dashboard_visible = 0, workspace_visible = 0 WHERE id = ?", id);

        assertThat(store.completeDashboardSync("sweep", "alice")).doesNotContain(id);
        assertThat(store.findRefById(id)).isPresent();
    }

    @Test
    void keepsOpenPullRequestsOmittedFromLaterDiscovery()
    {
        FetchedPullRequest first = pullRequest(101, "owner/project", 101);
        FetchedPullRequest second = pullRequest(102, "owner/project", 102);
        store.saveRepositorySyncPage("owner/project", ImmutableList.of(first), "first-discovery");
        store.completeRepositorySync("owner/project", RETENTION_CUTOFF);

        store.saveRepositorySyncPage("owner/project", ImmutableList.of(second), "second-discovery");
        store.completeRepositorySync("owner/project", RETENTION_CUTOFF);

        assertThat(store.findRepositoryPage("owner/project", 0, 50).pullRequests())
                .extracting(PullRequest::number)
                .containsExactlyInAnyOrder(101, 102);
    }

    @Test
    void findsRowsOmittedFromTheCurrentRepositoryDiscovery()
    {
        FetchedPullRequest omitted = pullRequest(111, "owner/project", 111);
        FetchedPullRequest discovered = pullRequest(112, "owner/project", 112);
        store.saveRepositorySyncPage(
                "owner/project", ImmutableList.of(omitted, discovered), "first-discovery");
        store.saveRepositorySyncPage(
                "owner/project", ImmutableList.of(discovered), "current-discovery");

        assertThat(store.findRepositoryRowsMissingSync("owner/project", "current-discovery"))
                .extracting(PullRequest::number)
                .containsExactly(111);
    }

    @Test
    void expiresOnlyClosedPullRequestsInactiveBeforeTheCutoff()
    {
        FetchedPullRequest expired = pullRequest(
                201, "owner/project", 201, "closed",
                Instant.parse("2026-06-30T00:00:00Z"), Instant.parse("2026-07-01T00:00:00Z"));
        FetchedPullRequest recentlyUpdated = pullRequest(
                202, "owner/project", 202, "closed",
                Instant.parse("2026-06-30T00:00:00Z"), Instant.parse("2026-08-20T00:00:00Z"));
        FetchedPullRequest open = pullRequest(
                203, "owner/project", 203, "open", null, Instant.parse("2026-06-01T00:00:00Z"));
        FetchedPullRequest merged = pullRequest(
                204, "owner/project", 204, "merged",
                Instant.parse("2026-06-30T00:00:00Z"), Instant.parse("2026-07-01T00:00:00Z"));
        store.saveRepositorySyncPage(
                "owner/project", ImmutableList.of(expired, recentlyUpdated, open, merged), "retention");

        store.completeRepositorySync("owner/project", RETENTION_CUTOFF);

        assertThat(store.findRepositoryPage("owner/project", 0, 50).pullRequests())
                .extracting(PullRequest::number)
                .containsExactlyInAnyOrder(202, 203);
        assertThat(store.findIdByRepoAndNumber("owner/project", 201)).isEmpty();
        assertThat(store.findIdByRepoAndNumber("owner/project", 204)).isEmpty();
    }

    @Test
    void findsThePullRequestsOnOneHeadBranch()
    {
        String branch = "bytequay/task/0f1e2d3c-0000-4000-8000-000000000001";
        store.saveRepositorySyncPage("Owner/Project", ImmutableList.of(
                pullRequest(301, "Owner/Project", 301, branch),
                pullRequest(302, "Owner/Project", 302, branch),
                pullRequest(303, "Owner/Project", 303),
                pullRequest(304, "other/project", 304, branch)), "heads");

        assertThat(store.findByHeadRef("owner/project", branch))
                .extracting(PullRequest::number)
                .containsExactly(302, 301);
        assertThat(store.findByHeadRef("owner/project", "bytequay/task/none")).isEmpty();
    }

    private static FetchedPullRequest pullRequest(long githubId, String repository, int number)
    {
        return pullRequest(githubId, repository, number, "feature");
    }

    private static FetchedPullRequest pullRequest(long githubId, String repository, int number, String headRef)
    {
        return pullRequest(
                githubId, repository, number, "open", null, Instant.parse("2026-08-21T00:00:00Z"), headRef);
    }

    private static FetchedPullRequest pullRequest(
            long githubId,
            String repository,
            int number,
            String state,
            Instant closedAt,
            Instant updatedAt)
    {
        return pullRequest(githubId, repository, number, state, closedAt, updatedAt, "feature");
    }

    private static FetchedPullRequest pullRequest(
            long githubId,
            String repository,
            int number,
            String state,
            Instant closedAt,
            Instant updatedAt,
            String headRef)
    {
        return new FetchedPullRequest(
                githubId,
                repository,
                number,
                "PR " + number,
                "alice",
                "https://github.com/" + repository + "/pull/" + number,
                Instant.parse("2026-08-20T00:00:00Z"),
                updatedAt,
                PullRequest.Origin.AUTHORED,
                ImmutableList.of(),
                ImmutableMap.of(),
                false,
                state,
                closedAt,
                null,
                ImmutableList.of(),
                null,
                null,
                headRef);
    }

    private static Workspace workspace(String id, long repositoryId, RepoRef repository)
    {
        Instant now = Instant.parse("2026-08-21T00:00:00Z");
        return new Workspace(
                id,
                repository.fullName(),
                repositoryId,
                repository,
                Optional.empty(),
                Optional.empty(),
                repositoryId,
                repository,
                Workspace.Topology.DIRECT,
                "main",
                "https://github.com/" + repository.fullName() + ".git",
                Optional.empty(),
                Optional.empty(),
                "/tmp/workspaces/" + id + "/repository",
                Optional.empty(),
                Optional.empty(),
                0,
                now,
                now);
    }
}
