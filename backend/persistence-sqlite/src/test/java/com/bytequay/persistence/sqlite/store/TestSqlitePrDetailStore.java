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

import com.bytequay.core.domain.PrCheckRunState;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PrReaction;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PrTimelinePayload;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.persistence.sqlite.config.SqlitePersistenceConfiguration;
import com.bytequay.persistence.sqlite.entity.PullRequestEntity;
import com.bytequay.persistence.sqlite.repository.PrTimelineJpaRepository;
import com.bytequay.persistence.sqlite.repository.PullRequestJpaRepository;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.BeforeEach;
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
class TestSqlitePrDetailStore
{
    private static final String DATABASE_PATH = Path.of(
            System.getProperty("java.io.tmpdir"),
            "bytequay-pr-detail-store-" + UUID.randomUUID() + ".db").toString();
    private static final long PR_ID = 2234;
    private static final PullRequestRef PULL_REQUEST = PullRequestRef.of("bytequay", "sample", 42);
    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");

    @Autowired
    private SqlitePrDetailStore store;

    @Autowired
    private PullRequestJpaRepository pullRequests;

    @Autowired
    private PrTimelineJpaRepository timeline;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry)
    {
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + DATABASE_PATH + "?foreign_keys=ON");
        registry.add("spring.datasource.driver-class-name", () -> "org.sqlite.JDBC");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.community.dialect.SQLiteDialect");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "1");
    }

    @BeforeEach
    void seedPullRequest()
    {
        PullRequestEntity pullRequest = new PullRequestEntity();
        pullRequest.setId(PR_ID);
        pullRequest.setRepo(PULL_REQUEST.repoFullName());
        pullRequest.setNumber(PULL_REQUEST.number());
        pullRequest.setTitle("CI fix");
        pullRequest.setOrigin("AUTHORED");
        pullRequest.setLabels(ImmutableList.of());
        pullRequest.setLabelColors(ImmutableMap.of());
        pullRequest.setSyncedAt(NOW);
        pullRequests.saveAndFlush(pullRequest);
    }

    @Test
    void recordsOneLocalEventAndPreservesItAcrossFullAndIncrementalRefreshes()
    {
        store.save(PR_ID, detail(remote(1, NOW.plusSeconds(2))));
        PrTimelineEvent local = new PrTimelineEvent(
                null,
                "ci_autofix",
                "author",
                NOW.plusSeconds(1),
                "verified push facts",
                Optional.of("ci-autofix:run:attempt:sha"));

        assertThat(store.recordLocalTimelineEvent(PULL_REQUEST, local)).isEqualTo(local);
        assertThat(store.recordLocalTimelineEvent(PULL_REQUEST, local)).isEqualTo(local);
        assertThat(timeline.count()).isEqualTo(2);

        store.save(PR_ID, detail(remote(2, NOW.plusSeconds(3))));
        store.saveIncremental(PR_ID, detail(remote(3, NOW.plusSeconds(4))));

        assertThat(store.find(PR_ID).orElseThrow().timeline())
                .extracting(PrTimelineEvent::localEventKey)
                .containsExactly(
                        Optional.of("ci-autofix:run:attempt:sha"),
                        Optional.empty(),
                        Optional.empty());
    }

    @Test
    void deletingPullRequestDetailsAlsoDeletesLocalTimelineEvents()
    {
        store.save(PR_ID, detail(remote(1, NOW)));
        store.recordLocalTimelineEvent(PULL_REQUEST, new PrTimelineEvent(
                null,
                "ci_autofix",
                "author",
                NOW.plusSeconds(1),
                "verified push facts",
                Optional.of("ci-autofix:delete")));

        store.deleteByPrIds(ImmutableSet.of(PR_ID));

        assertThat(timeline.count()).isZero();
    }

    @Test
    void storesTheGitHubEtag()
    {
        store.save(PR_ID, detail(remote(1, NOW)));
        store.updateEtag(PR_ID, Optional.of("\"etag-1\""));

        assertThat(store.findEtag(PR_ID)).contains("\"etag-1\"");
    }

    @Test
    void storesTheGitHubStatusEtags()
    {
        store.save(PR_ID, detail(remote(1, NOW)));
        store.updateStatusEtags(PR_ID, Optional.of("\"checks-1\""), Optional.of("\"statuses-1\""));

        assertThat(store.findCheckRunsEtag(PR_ID)).contains("\"checks-1\"");
        assertThat(store.findCommitStatusesEtag(PR_ID)).contains("\"statuses-1\"");
    }

    @Test
    void statusRefreshOnlyReplacesMergeabilityAndChecks()
    {
        PrTimelineEvent comment = remote(1, NOW);
        StoredPrDetail original = new StoredPrDetail(
                new PrRawDetail("body", ImmutableList.of(), false, true, "clean", 1, 1, 1, 0, ImmutableList.of(),
                        "a".repeat(40), "feature"),
                ImmutableList.of(new PrReviewState("alice", "APPROVED")),
                ImmutableList.of(comment),
                ImmutableList.of(new PrCheckRunState(1L, "build", "completed", "success")));
        store.save(PR_ID, original);
        StoredPrDetail status = new StoredPrDetail(
                original.raw().withMergeability(false, "dirty"),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(new PrCheckRunState(2L, "test", "completed", "failure")));

        store.saveStatus(PR_ID, status);

        StoredPrDetail restored = store.find(PR_ID).orElseThrow();
        assertThat(restored.raw().mergeable()).isFalse();
        assertThat(restored.raw().mergeableState()).isEqualTo("dirty");
        assertThat(restored.reviews()).containsExactly(new PrReviewState("alice", "APPROVED"));
        assertThat(restored.timeline()).containsExactly(comment);
        assertThat(restored.checkRuns()).containsExactly(
                new PrCheckRunState(2L, "test", "completed", "failure"));
    }

    @Test
    void roundTripsTheTypedTimelinePayloadAndUnavailableSections()
    {
        PrTimelinePayload payload = new PrTimelinePayload(
                "https://avatars/bot", true, null, "https://github/event", "docs", "d4c5f9",
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, false, false,
                ImmutableList.of(new PrReaction("+1", 2)), ImmutableList.of());
        StoredPrDetail detail = new StoredPrDetail(
                new PrRawDetail(
                        "Overview", "https://github/pr/42", "open", "alice", "https://avatars/alice",
                        NOW, NOW, null, "body", ImmutableList.of("docs"), ImmutableMap.of("docs", "d4c5f9"),
                        ImmutableList.of("bob"), "v1", false, true, "clean", 1, 1, 1, 0, ImmutableList.of(),
                        "a".repeat(40), "feature", "main"),
                ImmutableList.of(),
                ImmutableList.of(new PrTimelineEvent(10L, "labeled", "bot", NOW, null, payload, Optional.empty())),
                ImmutableList.of(),
                ImmutableList.of("checks"),
                true);

        store.save(PR_ID, detail);

        StoredPrDetail restored = store.find(PR_ID).orElseThrow();
        assertThat(restored.raw().baseRef()).isEqualTo("main");
        assertThat(restored.raw().labelColors()).containsEntry("docs", "d4c5f9");
        assertThat(restored.timeline()).singleElement().satisfies(event -> {
            assertThat(event.payload().label()).isEqualTo("docs");
            assertThat(event.payload().reactions()).containsExactly(new PrReaction("+1", 2));
        });
        assertThat(restored.unavailableSections()).containsExactly("checks");
        assertThat(restored.viewerCanWrite()).isTrue();
    }

    private static StoredPrDetail detail(PrTimelineEvent event)
    {
        return new StoredPrDetail(
                new PrRawDetail("body", ImmutableList.of(), false, true, "clean", 1, 1, 1, 0, ImmutableList.of(),
                        "a".repeat(40), "feature"),
                ImmutableList.of(),
                ImmutableList.of(event),
                ImmutableList.of());
    }

    private static PrTimelineEvent remote(long githubId, Instant timestamp)
    {
        return new PrTimelineEvent(githubId, "commented", "reviewer", timestamp, "comment", Optional.empty());
    }
}
