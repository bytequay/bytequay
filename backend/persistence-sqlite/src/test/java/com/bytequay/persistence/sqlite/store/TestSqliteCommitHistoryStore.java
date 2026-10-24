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
import com.bytequay.core.domain.CommitRefreshMetadata;
import com.bytequay.persistence.sqlite.repository.CommitEnrichmentJpaRepository;
import com.bytequay.persistence.sqlite.repository.CommitRefreshMetadataJpaRepository;
import com.google.common.collect.ImmutableList;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TestSqliteCommitHistoryStore
        extends AbstractTestTransactionalSqliteStore
{
    private static final Instant NOW = Instant.parse("2026-08-23T00:00:00Z");
    private static final String SHA = "0123456789abcdef";

    @Autowired
    private SqliteCommitHistoryStore store;

    @Autowired
    private CommitEnrichmentJpaRepository enrichmentRepository;

    @Autowired
    private CommitRefreshMetadataJpaRepository refreshRepository;

    @Autowired
    private EntityManager entities;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void roundTripsPositiveAndCheckedNegativeEnrichment()
    {
        CommitEnrichment positive = enrichment(100, SHA, Optional.of(42), Optional.of("PR title"));
        CommitEnrichment negative = enrichment(100, "fedcba9876543210", Optional.empty(), Optional.empty());

        store.upsertEnrichment(ImmutableList.of(positive, negative));
        entities.flush();
        entities.clear();

        assertThat(store.findEnrichment(100, ImmutableList.of(SHA, negative.sha())).commits())
                .containsExactlyInAnyOrder(positive, negative);
        assertThat(jdbc.queryForList("SELECT last_seen_at FROM commit_enrichment", String.class)).containsOnly(NOW.toString());
    }

    @Test
    void upsertPreservesUnrelatedAndPreviouslyKnownValues()
    {
        CommitEnrichment first = enrichment(100, SHA, Optional.of(42), Optional.of("PR title"));
        CommitEnrichment unrelated = enrichment(100, "fedcba9876543210", Optional.empty(), Optional.empty());
        store.upsertEnrichment(ImmutableList.of(first, unrelated));

        CommitEnrichment seenAgain = new CommitEnrichment(
                100, SHA, Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), NOW.plusSeconds(1));
        store.upsertEnrichment(ImmutableList.of(seenAgain));

        var snapshot = store.findEnrichment(100, ImmutableList.of(SHA, unrelated.sha())).commits();
        assertThat(snapshot).hasSize(2);
        assertThat(snapshot.stream().filter(value -> value.sha().equals(SHA)).findFirst().orElseThrow())
                .extracting(CommitEnrichment::authorLogin, CommitEnrichment::committerLogin, CommitEnrichment::pullRequestNumber, CommitEnrichment::pullRequestTitle,
                        CommitEnrichment::pullRequestMergedAt)
                .containsExactly(Optional.of("percy-r"), Optional.of("ezra-b"), Optional.of(42), Optional.of("PR title"), Optional.of(NOW.minusSeconds(60)));
    }

    @Test
    void reloadsDurableMetadataAndKeepsLastSuccessOnFailure()
    {
        store.upsertEnrichment(ImmutableList.of(enrichment(100, SHA, Optional.of(42), Optional.of("PR title"))));
        store.saveRefreshMetadata(new CommitRefreshMetadata("workspace", Optional.of(NOW), Optional.of(NOW), Optional.empty()));
        store.saveRefreshMetadata(new CommitRefreshMetadata("workspace", Optional.of(NOW.plusSeconds(1)), Optional.empty(), Optional.of("Retry")));

        SqliteCommitHistoryStore reloaded = new SqliteCommitHistoryStore(enrichmentRepository, refreshRepository);

        assertThat(reloaded.findEnrichment(100, ImmutableList.of(SHA)).commits()).hasSize(1);
        assertThat(reloaded.findRefreshMetadata("workspace"))
                .isEqualTo(new CommitRefreshMetadata("workspace", Optional.of(NOW.plusSeconds(1)), Optional.of(NOW), Optional.of("Retry")));
        assertThat(reloaded.findRefreshMetadata("missing")).isEqualTo(CommitRefreshMetadata.empty("missing"));
    }

    private static CommitEnrichment enrichment(long repositoryId, String sha, Optional<Integer> pullRequestNumber, Optional<String> pullRequestTitle)
    {
        return new CommitEnrichment(
                repositoryId,
                sha,
                Optional.of("percy-r"),
                Optional.of("avatar-key"),
                Optional.of("ezra-b"),
                Optional.of("committer-avatar-key"),
                pullRequestNumber,
                pullRequestTitle,
                pullRequestNumber.map(number -> "https://example.invalid/pull/" + number),
                pullRequestNumber.map(number -> NOW.minusSeconds(60)),
                Optional.of(NOW),
                Optional.of(NOW),
                NOW);
    }
}
