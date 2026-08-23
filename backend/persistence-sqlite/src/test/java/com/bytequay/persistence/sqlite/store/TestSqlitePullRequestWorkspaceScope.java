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
import com.bytequay.persistence.sqlite.config.SqlitePersistenceConfiguration;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
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
    private static final String DATABASE_PATH = Path.of(
            System.getProperty("java.io.tmpdir"),
            "bytequay-pr-scope-" + UUID.randomUUID() + ".db").toString();

    @Autowired
    private SqlitePullRequestStore store;

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
        store.completeRepositorySync("owner/project", "workspace-1");
        store.saveRepositorySyncPage("other/project", ImmutableList.of(otherWorkspace), "workspace-2");
        store.completeRepositorySync("other/project", "workspace-2");

        assertThat(store.findPage(0, 50).pullRequests()).extracting(PullRequest::id).containsExactlyInAnyOrder(1L, 2L);
        assertThat(store.findRepositoryPage("OWNER/PROJECT", 0, 50).pullRequests())
                .extracting(PullRequest::id)
                .containsExactlyInAnyOrder(2L, 3L);
        assertThat(store.findRepositoryPage("owner/project", 0, 50).pullRequests())
                .extracting(PullRequest::id)
                .doesNotContain(4L);

        store.saveDashboardSyncPage(ImmutableList.of(global), "global-2");
        store.completeDashboardSync("global-2");

        assertThat(store.findPage(0, 50).pullRequests()).extracting(PullRequest::id).containsExactly(1L);
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
        store.completeRepositorySync("owner/project", "workspace-page");

        var first = store.findRepositoryPage("owner/project", 0, 50);
        var second = store.findRepositoryPage("owner/project", 1, 50);

        assertThat(first.pullRequests()).hasSize(50);
        assertThat(first.totalPullRequests()).isEqualTo(55);
        assertThat(first.hasMore()).isTrue();
        assertThat(second.pullRequests()).hasSize(5);
        assertThat(second.hasMore()).isFalse();
    }

    @Test
    void storesDirectlyOpenedPullRequestInTheLocalDashboard()
    {
        PullRequest observed = pullRequest(91, "external/project", 12);

        store.saveObserved(observed);

        assertThat(store.findIdByRepoAndNumber("external/project", 12)).contains(91L);
        assertThat(store.findPage(0, 50).pullRequests()).containsExactly(observed);
    }

    private static PullRequest pullRequest(long id, String repository, int number)
    {
        return new PullRequest(
                id,
                repository,
                number,
                "PR " + number,
                "alice",
                "https://github.com/" + repository + "/pull/" + number,
                Instant.parse("2026-08-20T00:00:00Z"),
                Instant.parse("2026-08-21T00:00:00Z"),
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
                "open",
                null,
                null,
                null,
                null,
                null,
                ImmutableMap.of(),
                "feature");
    }
}
