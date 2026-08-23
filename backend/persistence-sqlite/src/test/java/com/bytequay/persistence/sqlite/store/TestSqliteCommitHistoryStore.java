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

import com.bytequay.core.domain.CommitEnrichment;
import com.bytequay.core.domain.CommitRefreshStatus;
import com.bytequay.core.domain.CommitRefreshStatus.State;
import com.bytequay.persistence.sqlite.config.SqlitePersistenceConfiguration;
import com.bytequay.persistence.sqlite.repository.CommitEnrichmentJpaRepository;
import com.bytequay.persistence.sqlite.repository.CommitRefreshStatusJpaRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.jdbc.AutoConfigureTestDatabase;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = SqlitePersistenceConfiguration.class)
class TestSqliteCommitHistoryStore
{
    private static final String DATABASE_PATH = Path.of(
            System.getProperty("java.io.tmpdir"),
            "bytequay-commit-history-" + UUID.randomUUID() + ".db").toString();
    private static final Instant NOW = Instant.parse("2026-08-23T00:00:00Z");
    private static final String SHA = "0123456789abcdef";

    @Autowired
    private SqliteCommitHistoryStore store;

    @Autowired
    private CommitEnrichmentJpaRepository enrichmentRepository;

    @Autowired
    private CommitRefreshStatusJpaRepository refreshRepository;

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
    void roundTripsPositiveAndCheckedNegativeEnrichment()
    {
        CommitEnrichment positive = enrichment(100, SHA, Optional.of(42), Optional.of("PR title"));
        CommitEnrichment negative = enrichment(100, "fedcba9876543210", Optional.empty(), Optional.empty());

        store.upsertEnrichment(List.of(positive, negative));

        assertThat(store.findEnrichment(100, List.of(SHA, negative.sha())).commits())
                .containsExactlyInAnyOrder(positive, negative);
    }

    @Test
    void upsertPreservesUnrelatedAndPreviouslyKnownValues()
    {
        CommitEnrichment first = enrichment(100, SHA, Optional.of(42), Optional.of("PR title"));
        CommitEnrichment unrelated = enrichment(100, "fedcba9876543210", Optional.empty(), Optional.empty());
        store.upsertEnrichment(List.of(first, unrelated));

        CommitEnrichment seenAgain = new CommitEnrichment(
                100, SHA, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), NOW.plusSeconds(1));
        store.upsertEnrichment(List.of(seenAgain));

        var snapshot = store.findEnrichment(100, List.of(SHA, unrelated.sha())).commits();
        assertThat(snapshot).hasSize(2);
        assertThat(snapshot.stream().filter(value -> value.sha().equals(SHA)).findFirst().orElseThrow())
                .extracting(CommitEnrichment::pullRequestNumber, CommitEnrichment::pullRequestTitle)
                .containsExactly(Optional.of(42), Optional.of("PR title"));
    }

    @Test
    void reloadsDurableStateAndKeepsLastSuccessOnFailure()
    {
        store.upsertEnrichment(List.of(enrichment(100, SHA, Optional.of(42), Optional.of("PR title"))));
        store.saveRefreshStatus(new CommitRefreshStatus(
                "workspace", State.IDLE, Optional.of(NOW), Optional.of(NOW), Optional.empty()));
        store.saveRefreshStatus(new CommitRefreshStatus(
                "workspace", State.FAILED, Optional.of(NOW.plusSeconds(1)), Optional.empty(), Optional.of("Retry")));

        SqliteCommitHistoryStore reloaded = new SqliteCommitHistoryStore(enrichmentRepository, refreshRepository);

        assertThat(reloaded.findEnrichment(100, List.of(SHA)).commits()).hasSize(1);
        assertThat(reloaded.findRefreshStatus("workspace"))
                .isEqualTo(new CommitRefreshStatus(
                        "workspace", State.FAILED, Optional.of(NOW.plusSeconds(1)), Optional.of(NOW), Optional.of("Retry")));
        assertThat(reloaded.findRefreshStatus("missing")).isEqualTo(CommitRefreshStatus.idle("missing"));
    }

    private static CommitEnrichment enrichment(
            long repositoryId,
            String sha,
            Optional<Integer> pullRequestNumber,
            Optional<String> pullRequestTitle)
    {
        return new CommitEnrichment(
                repositoryId,
                sha,
                Optional.of("avatar-key"),
                pullRequestNumber,
                pullRequestTitle,
                pullRequestNumber.map(number -> "https://example.invalid/pull/" + number),
                Optional.of(NOW),
                Optional.of(NOW),
                NOW);
    }
}
