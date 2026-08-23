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

import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.RepositoryIssueDetail;
import com.bytequay.persistence.sqlite.config.SqlitePersistenceConfiguration;
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
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = SqlitePersistenceConfiguration.class)
class TestSqliteRepositoryIssueDetailStore
{
    private static final String DATABASE_PATH = Path.of(
            System.getProperty("java.io.tmpdir"),
            "bytequay-issue-detail-store-" + UUID.randomUUID() + ".db").toString();
    private static final Instant NOW = Instant.parse("2026-08-24T00:00:00Z");

    @Autowired
    private SqliteRepositoryIssueDetailStore store;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry)
    {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE_PATH);
        registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.community.dialect.SQLiteDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
    }

    @Test
    void storesIssueDetailsByRepositoryAndIssueNumber()
    {
        PrTimelineEvent event = new PrTimelineEvent(
                7L, "commented", "alice", NOW, "Hello", Optional.empty());
        RepositoryIssueDetail first = new RepositoryIssueDetail(
                100,
                42,
                ImmutableList.of(event),
                "Task",
                ImmutableList.of("Task", "Bug"),
                Optional.of(NOW));
        RepositoryIssueDetail second = new RepositoryIssueDetail(
                200,
                42,
                ImmutableList.of(),
                null,
                ImmutableList.of("Task"),
                Optional.of(NOW.plusSeconds(1)));

        store.save(first);
        store.save(second);

        assertThat(store.find(100, 42)).contains(first);
        assertThat(store.find(200, 42)).contains(second);
        assertThat(store.find(100, 43)).isEmpty();
    }
}
