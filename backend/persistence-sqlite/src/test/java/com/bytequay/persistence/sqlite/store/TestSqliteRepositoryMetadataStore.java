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

import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryContributor;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.RepositoryLabel;
import com.bytequay.persistence.sqlite.config.SqlitePersistenceConfiguration;
import com.bytequay.persistence.sqlite.repository.RepositoryMetadataJpaRepository;
import com.google.common.collect.ImmutableList;
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
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = SqlitePersistenceConfiguration.class)
class TestSqliteRepositoryMetadataStore
{
    private static final String DATABASE_PATH = Path.of(
            System.getProperty("java.io.tmpdir"),
            "bytequay-repository-metadata-" + UUID.randomUUID() + ".db").toString();
    private static final RepoRef REPOSITORY = RepoRef.of("owner", "project");
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @Autowired
    private SqliteRepositoryMetadataStore store;

    @Autowired
    private SqliteRepositoryIssueStore issueStore;

    @Autowired
    private RepositoryMetadataJpaRepository repository;

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
    void upsertsProviderIdsWithoutDuplicatingTheRepositoryProjection()
    {
        store.upsertContributors(
                100,
                REPOSITORY,
                ImmutableList.of(new RepositoryContributor(1, "alice", "old-avatar", false)),
                NOW.minusSeconds(1));
        store.upsertContributors(
                100,
                REPOSITORY,
                ImmutableList.of(
                        new RepositoryContributor(1, "alice", "new-avatar", false),
                        new RepositoryContributor(2, "bob", "bob-avatar", false)),
                NOW);
        store.upsertLabelsAndTopics(
                100,
                REPOSITORY,
                ImmutableList.of(new RepositoryLabel(10, "bug", "ff0000")),
                ImmutableList.of("java"),
                NOW);

        var snapshot = store.find(100).orElseThrow();
        assertThat(repository.count()).isEqualTo(1);
        assertThat(snapshot.contributors())
                .extracting(RepositoryContributor::avatarUrl)
                .containsExactly("new-avatar", "bob-avatar");
        assertThat(snapshot.labels()).extracting(RepositoryLabel::id).containsExactly(10L);
        assertThat(snapshot.topics()).containsExactly("java");
    }

    @Test
    void replacesIssueProjectionWithoutDuplicatingStableIssueIds()
    {
        RepositoryIssue issue = new RepositoryIssue(
                50, 7, "Issue", "body", "alice", "avatar",
                "https://github.com/owner/project/issues/7",
                ImmutableList.of("bug"), ImmutableList.of("bob"), 2, NOW, NOW);

        issueStore.saveSyncPage(100, ImmutableList.of(issue), "first");
        issueStore.completeSync(100, REPOSITORY, "first", NOW.minusSeconds(1));
        issueStore.saveSyncPage(100, ImmutableList.of(issue), "second");
        issueStore.completeSync(100, REPOSITORY, "second", NOW);

        var page = issueStore.findPage(100, REPOSITORY, 0, 50);
        assertThat(page.issues()).containsExactly(issue);
        assertThat(issueStore.find(100, 7)).contains(issue);
        assertThat(issueStore.find(100, 8)).isEmpty();
        assertThat(page.syncedAt()).contains(NOW);
        assertThat(page.totalIssues()).isEqualTo(1);
    }

    @Test
    void readsIssuesInDatabasePagesOrderedByMostRecentlyUpdated()
    {
        var issues = IntStream.rangeClosed(1, 55)
                .mapToObj(number -> new RepositoryIssue(
                        number,
                        number,
                        "Issue " + number,
                        "body",
                        "alice",
                        "avatar",
                        "https://github.com/owner/project/issues/" + number,
                        ImmutableList.of(),
                        ImmutableList.of(),
                        0,
                        NOW,
                        NOW.plusSeconds(number)))
                .toList();
        issueStore.saveSyncPage(100, issues.subList(0, 30), "sync");
        issueStore.saveSyncPage(100, issues.subList(30, 55), "sync");
        issueStore.completeSync(100, REPOSITORY, "sync", NOW);

        var first = issueStore.findPage(100, REPOSITORY, 0, 50);
        var second = issueStore.findPage(100, REPOSITORY, 1, 50);

        assertThat(first.issues()).hasSize(50);
        assertThat(first.issues().getFirst().number()).isEqualTo(55);
        assertThat(first.hasMore()).isTrue();
        assertThat(second.issues()).hasSize(5);
        assertThat(second.hasMore()).isFalse();
        assertThat(second.totalIssues()).isEqualTo(55);
    }
}
