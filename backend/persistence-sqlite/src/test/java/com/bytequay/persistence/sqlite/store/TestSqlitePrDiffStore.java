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

import com.bytequay.core.domain.DiffCommit;
import com.bytequay.core.domain.DiffFile;
import com.bytequay.core.domain.DiffFileContent;
import com.bytequay.core.domain.StoredPrDiff;
import com.bytequay.persistence.sqlite.config.SqlitePersistenceConfiguration;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
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
class TestSqlitePrDiffStore
{
    private static final String DATABASE_PATH = Path.of(
            System.getProperty("java.io.tmpdir"),
            "bytequay-pr-diff-store-" + UUID.randomUUID() + ".db").toString();

    @Autowired
    private SqlitePrDiffStore store;

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
    void keepsDefaultAndCommitRangeSnapshotsIndependent()
    {
        long prId = 42;
        DiffFile allFile = new DiffFile("all.txt", "modified", 1, 0, "+all");
        DiffCommit commit = new DiffCommit(
                "b".repeat(40), "a".repeat(40), "change", "alice", Instant.parse("2026-08-24T00:00:00Z"));
        DiffFile rangeFile = new DiffFile("range.txt", "added", 2, 0, "+range");
        store.save(prId, null, null, new StoredPrDiff(ImmutableList.of(allFile), ImmutableList.of(commit)));
        store.save(
                prId,
                "a".repeat(40),
                "b".repeat(40),
                new StoredPrDiff(ImmutableList.of(rangeFile), ImmutableList.of()));

        DiffFileContent content = new DiffFileContent("old", "new");
        store.saveFileContent(prId, null, null, "all.txt", content);

        assertThat(store.find(prId, null, null).orElseThrow().files()).containsExactly(allFile);
        assertThat(store.find(prId, null, null).orElseThrow().commits()).containsExactly(commit);
        assertThat(store.find(prId, null, null).orElseThrow().fileContents())
                .containsEntry("all.txt", content);
        assertThat(store.find(prId, "a".repeat(40), "b".repeat(40)).orElseThrow().files())
                .containsExactly(rangeFile);

        store.deleteByPrIds(ImmutableSet.of(prId));
        assertThat(store.find(prId, null, null)).isEmpty();
        assertThat(store.find(prId, "a".repeat(40), "b".repeat(40))).isEmpty();
    }
}
