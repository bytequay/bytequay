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

import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestRef;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

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
    void workspaceRowsReuseTheStoreWithoutLeakingIntoGlobalList()
    {
        PullRequest global = pullRequest(1, "account/repo", 1);
        PullRequest shared = pullRequest(2, "owner/project", 2);
        PullRequest workspaceOnly = pullRequest(3, "owner/project", 3);
        PullRequest otherWorkspace = pullRequest(4, "other/project", 4);

        store.saveDashboardSyncPage(ImmutableList.of(global, shared), "global-1");
        store.completeDashboardSync("global-1");
        store.saveRepositorySyncPage("owner/project", ImmutableList.of(shared, workspaceOnly), "workspace-1");
        store.completeRepositorySync("owner/project", RETENTION_CUTOFF);
        store.saveRepositorySyncPage("other/project", ImmutableList.of(otherWorkspace), "workspace-2");
        store.completeRepositorySync("other/project", RETENTION_CUTOFF);

        assertThat(store.findPage(0, 50).pullRequests()).extracting(PullRequest::id).containsExactlyInAnyOrder(1L, 2L);
        assertThat(store.findRepositoryPage("OWNER/PROJECT", 0, 50).pullRequests())
                .extracting(PullRequest::id)
                .containsExactlyInAnyOrder(2L, 3L);
        assertThat(store.findRepositoryPage("owner/project", 0, 50).pullRequests())
                .extracting(PullRequest::id)
                .doesNotContain(4L);

        store.saveDashboardSyncPage(ImmutableList.of(global), "global-2");
        store.completeDashboardSync("global-2");

        assertThat(store.findPage(0, 50).pullRequests()).extracting(PullRequest::id).containsExactlyInAnyOrder(1L, 2L);
        assertThat(store.findRepositoryPage("owner/project", 0, 50).pullRequests())
                .extracting(PullRequest::id)
                .containsExactlyInAnyOrder(2L, 3L);
    }

    @Test
    void readsPullRequestsOneDatabasePageAtATime()
    {
        ImmutableList.Builder<PullRequest> pullRequests = ImmutableList.builder();
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
        PullRequest first = pullRequest(1, "owner/project", 41);
        PullRequest second = pullRequest(2, "owner/project", 42);
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
        PullRequest observed = pullRequest(91, "external/project", 12);

        store.saveObserved(observed);

        assertThat(store.findIdByRepoAndNumber("external/project", 12)).contains(91L);
        assertThat(store.findRefById(91L)).contains(PullRequestRef.of("external", "project", 12));
        assertThat(store.findPage(0, 50).pullRequests()).containsExactly(observed);
    }

    @Test
    void updatesByRepositoryAndNumberWhenGitHubApisReturnDifferentIds()
    {
        PullRequest searchResult = pullRequest(91, "external/project", 12);
        PullRequest pullEndpointResult = pullRequest(92, "external/project", 12);
        store.saveObserved(searchResult);
        store.saveObserved(pullEndpointResult);

        assertThat(store.findIdByRepoAndNumber("EXTERNAL/PROJECT", 12)).contains(91L);
        assertThat(store.findPage(0, 50).pullRequests())
                .extracting(PullRequest::id)
                .containsExactly(91L);
    }

    @Test
    void schemaEnforcesRepositoryAndNumberUniqueness()
    {
        Integer matchingIndexes = jdbc.queryForObject(
                "SELECT count(*) FROM pragma_index_list('pull_requests') "
                        + "WHERE name = 'uq_pull_requests_repo_number' AND \"unique\" = 1",
                Integer.class);

        assertThat(matchingIndexes).isOne();
    }

    @Test
    void keepsOpenPullRequestsOmittedFromLaterDiscovery()
    {
        PullRequest first = pullRequest(101, "owner/project", 101);
        PullRequest second = pullRequest(102, "owner/project", 102);
        store.saveRepositorySyncPage("owner/project", ImmutableList.of(first), "first-discovery");
        store.completeRepositorySync("owner/project", RETENTION_CUTOFF);

        store.saveRepositorySyncPage("owner/project", ImmutableList.of(second), "second-discovery");
        store.completeRepositorySync("owner/project", RETENTION_CUTOFF);

        assertThat(store.findRepositoryPage("owner/project", 0, 50).pullRequests())
                .extracting(PullRequest::id)
                .containsExactlyInAnyOrder(101L, 102L);
    }

    @Test
    void findsRowsOmittedFromTheCurrentRepositoryDiscovery()
    {
        PullRequest omitted = pullRequest(111, "owner/project", 111);
        PullRequest discovered = pullRequest(112, "owner/project", 112);
        store.saveRepositorySyncPage(
                "owner/project", ImmutableList.of(omitted, discovered), "first-discovery");
        store.saveRepositorySyncPage(
                "owner/project", ImmutableList.of(discovered), "current-discovery");

        assertThat(store.findRepositoryRowsMissingSync("owner/project", "current-discovery"))
                .extracting(PullRequest::id)
                .containsExactly(111L);
    }

    @Test
    void expiresOnlyClosedPullRequestsInactiveBeforeTheCutoff()
    {
        PullRequest expired = pullRequest(
                201, "owner/project", 201, "closed",
                Instant.parse("2026-06-30T00:00:00Z"), Instant.parse("2026-07-01T00:00:00Z"));
        PullRequest recentlyUpdated = pullRequest(
                202, "owner/project", 202, "closed",
                Instant.parse("2026-06-30T00:00:00Z"), Instant.parse("2026-08-20T00:00:00Z"));
        PullRequest open = pullRequest(
                203, "owner/project", 203, "open", null, Instant.parse("2026-06-01T00:00:00Z"));
        PullRequest merged = pullRequest(
                204, "owner/project", 204, "merged",
                Instant.parse("2026-06-30T00:00:00Z"), Instant.parse("2026-07-01T00:00:00Z"));
        store.saveRepositorySyncPage(
                "owner/project", ImmutableList.of(expired, recentlyUpdated, open, merged), "retention");

        store.completeRepositorySync("owner/project", RETENTION_CUTOFF);

        assertThat(store.findRepositoryPage("owner/project", 0, 50).pullRequests())
                .extracting(PullRequest::id)
                .containsExactlyInAnyOrder(202L, 203L);
        assertThat(store.findIdByRepoAndNumber("owner/project", 201)).isEmpty();
        assertThat(store.findIdByRepoAndNumber("owner/project", 204)).isEmpty();
    }

    private static PullRequest pullRequest(long id, String repository, int number)
    {
        return pullRequest(
                id, repository, number, "open", null, Instant.parse("2026-08-21T00:00:00Z"));
    }

    private static PullRequest pullRequest(
            long id,
            String repository,
            int number,
            String state,
            Instant closedAt,
            Instant updatedAt)
    {
        return new PullRequest(
                id,
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
                null,
                null,
                null,
                ImmutableList.of(),
                null,
                0,
                0,
                0,
                null,
                state,
                closedAt,
                null,
                null,
                null,
                null,
                ImmutableMap.of(),
                "feature");
    }
}
