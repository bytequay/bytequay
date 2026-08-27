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
import com.bytequay.persistence.sqlite.repository.PrCheckRunJpaRepository;
import com.bytequay.persistence.sqlite.repository.PrReviewJpaRepository;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ContextConfiguration(classes = SqlitePersistenceConfiguration.class)
class TestSqlitePrDetailStore
{
    private static final String DATABASE_PATH = Path.of(
            System.getProperty("java.io.tmpdir"),
            "bytequay-pr-detail-store-" + UUID.randomUUID() + ".db").toString();
    private static final PullRequestRef PULL_REQUEST = PullRequestRef.of("bytequay", "sample", 42);
    private static final Instant NOW = Instant.parse("2026-08-18T00:00:00Z");

    private long prId;

    @Autowired
    private SqlitePrDetailStore store;

    @Autowired
    private PullRequestJpaRepository pullRequests;

    @Autowired
    private PrTimelineJpaRepository timeline;

    @Autowired
    private PrReviewJpaRepository reviews;

    @Autowired
    private PrCheckRunJpaRepository checkRuns;

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
        pullRequest.setRepo(PULL_REQUEST.repoFullName());
        pullRequest.setNumber(PULL_REQUEST.number());
        pullRequest.setTitle("CI fix");
        pullRequest.setOrigin("AUTHORED");
        pullRequest.setLabels(ImmutableList.of());
        pullRequest.setLabelColors(ImmutableMap.of());
        pullRequest.setSyncedAt(NOW);
        prId = pullRequests.saveAndFlush(pullRequest).getId();
    }

    @Test
    void recordsOneLocalEventAndPreservesItAcrossFullAndIncrementalRefreshes()
    {
        store.save(prId, detail(remote(1, NOW.plusSeconds(2))));
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

        store.save(prId, detail(remote(2, NOW.plusSeconds(3))));
        store.saveIncremental(prId, detail(remote(3, NOW.plusSeconds(4))));

        assertThat(store.find(prId).orElseThrow().timeline())
                .extracting(PrTimelineEvent::localEventKey)
                .containsExactly(
                        Optional.of("ci-autofix:run:attempt:sha"),
                        Optional.empty(),
                        Optional.empty());
    }

    @Test
    void savesPatchedCachedTimelineWithoutReinsertingLocalEvents()
    {
        PrTimelineEvent first = remote(1, NOW);
        store.save(prId, detail(first));
        PrTimelineEvent local = new PrTimelineEvent(
                null,
                "ci_autofix",
                "author",
                NOW.plusSeconds(1),
                "verified push facts",
                Optional.of("ci-autofix:patch:sha"));
        store.recordLocalTimelineEvent(PULL_REQUEST, local);

        StoredPrDetail cached = store.find(prId).orElseThrow();
        PrTimelineEvent second = remote(2, NOW.plusSeconds(2));
        store.save(prId, withTimeline(cached, second));
        StoredPrDetail patched = store.find(prId).orElseThrow();
        PrTimelineEvent third = remote(3, NOW.plusSeconds(3));
        store.saveIncremental(prId, withTimeline(patched, third));

        assertThat(store.find(prId).orElseThrow().timeline())
                .extracting(PrTimelineEvent::githubId)
                .containsExactlyInAnyOrder(null, 1L, 2L, 3L);
        assertThat(store.find(prId).orElseThrow().timeline())
                .extracting(PrTimelineEvent::localEventKey)
                .containsOnlyOnce(Optional.of("ci-autofix:patch:sha"));
    }

    @Test
    void deletingPullRequestDetailsAlsoDeletesLocalTimelineEvents()
    {
        store.save(prId, detail(remote(1, NOW)));
        store.recordLocalTimelineEvent(PULL_REQUEST, new PrTimelineEvent(
                null,
                "ci_autofix",
                "author",
                NOW.plusSeconds(1),
                "verified push facts",
                Optional.of("ci-autofix:delete")));

        store.deleteByPrIds(ImmutableSet.of(prId));

        assertThat(timeline.count()).isZero();
    }

    @Test
    void storesTheGitHubEtag()
    {
        store.save(prId, detail(remote(1, NOW)));
        store.updateEtag(prId, Optional.of("\"etag-1\""));

        assertThat(store.findEtag(prId)).contains("\"etag-1\"");
    }

    @Test
    void storesTheGitHubStatusEtags()
    {
        store.save(prId, detail(remote(1, NOW)));
        store.updateStatusEtags(prId, Optional.of("\"checks-1\""), Optional.of("\"statuses-1\""));

        assertThat(store.findCheckRunsEtag(prId)).contains("\"checks-1\"");
        assertThat(store.findCommitStatusesEtag(prId)).contains("\"statuses-1\"");
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
        store.save(prId, original);
        PrCheckRunState completedCheck = new PrCheckRunState(
                2L, "test", "completed", "failure", null, "check_run", NOW.plusSeconds(30));
        StoredPrDetail status = new StoredPrDetail(
                original.raw().withMergeability(false, "dirty"),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(completedCheck));

        store.saveStatus(prId, status);

        StoredPrDetail restored = store.find(prId).orElseThrow();
        assertThat(restored.raw().mergeable()).isFalse();
        assertThat(restored.raw().mergeableState()).isEqualTo("dirty");
        assertThat(restored.reviews()).containsExactly(new PrReviewState("alice", "APPROVED"));
        assertThat(restored.timeline()).containsExactly(comment);
        assertThat(restored.checkRuns()).containsExactly(completedCheck);
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

        store.save(prId, detail);

        StoredPrDetail restored = store.find(prId).orElseThrow();
        assertThat(restored.raw().baseRef()).isEqualTo("main");
        assertThat(restored.raw().labelColors()).containsEntry("docs", "d4c5f9");
        assertThat(restored.timeline()).singleElement().satisfies(event -> {
            assertThat(event.payload().label()).isEqualTo("docs");
            assertThat(event.payload().reactions()).containsExactly(new PrReaction("+1", 2));
        });
        assertThat(restored.unavailableSections()).containsExactly("checks");
        assertThat(restored.viewerCanWrite()).isTrue();
    }

    @Test
    void refreshKeepsRowIdentityAndSweepsWhatGitHubDropped()
    {
        store.save(prId, detail(
                ImmutableList.of(review(11L, "alice", "lgtm"), review(12L, "bob", "nit")),
                ImmutableList.of(new PrCheckRunState(21L, "build", "completed", "success"),
                        new PrCheckRunState(22L, "test", "completed", "failure"))));
        long aliceRowId = reviews.findByPrId(prId).stream()
                .filter(row -> row.getGithubId() == 11L)
                .findFirst()
                .orElseThrow()
                .getId();

        // GitHub now reports only alice's review, with an edited body, and only the build check.
        store.save(prId, detail(
                ImmutableList.of(review(11L, "alice", "still lgtm")),
                ImmutableList.of(new PrCheckRunState(21L, "build", "completed", "success"))));

        assertThat(reviews.findByPrId(prId)).singleElement().satisfies(row -> {
            assertThat(row.getGithubId()).isEqualTo(11L);
            assertThat(row.getId()).isEqualTo(aliceRowId);
            assertThat(row.getBody()).isEqualTo("still lgtm");
        });
        assertThat(checkRuns.findByPrId(prId)).singleElement()
                .satisfies(row -> assertThat(row.getGithubId()).isEqualTo(21L));
    }

    @Test
    void refreshKeepsRowsWhoseSourceSectionWasUnavailable()
    {
        store.save(prId, partial(
                ImmutableList.of(review(11L, "alice", "lgtm")),
                ImmutableList.of(new PrCheckRunState(21L, "build", "completed", "success")),
                ImmutableList.of(remote(31, NOW)),
                ImmutableList.of()));

        // Each failed source arrives as an empty list, which is indistinguishable
        // from "GitHub reports none" — sweeping on it would erase real rows.
        store.save(prId, partial(
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(StoredPrDetail.REVIEWS, StoredPrDetail.CHECKS, StoredPrDetail.REVIEW_THREADS)));

        assertThat(reviews.findByPrId(prId)).singleElement()
                .satisfies(row -> assertThat(row.getGithubId()).isEqualTo(11L));
        assertThat(checkRuns.findByPrId(prId)).singleElement()
                .satisfies(row -> assertThat(row.getGithubId()).isEqualTo(21L));
        assertThat(timeline.findByPrIdOrderByTimestampAscIdAsc(prId)).singleElement()
                .satisfies(row -> assertThat(row.getGithubId()).isEqualTo(31L));
    }

    @Test
    void refreshKeepsTimelineRowIdentityIncludingEventsWithoutAGitHubId()
    {
        PrTimelineEvent comment = remote(31, NOW);
        PrTimelineEvent commit = new PrTimelineEvent(
                null, "committed", "alice", NOW.plusSeconds(1), "first",
                new PrTimelinePayload(
                        null, false, null, null, null, null,
                        null, null, "c0ffee", null, null, null, null, null, null, null,
                        null, null, null, null, null, false, false,
                        ImmutableList.of(), ImmutableList.of()),
                Optional.empty());
        store.save(prId, detail(ImmutableList.of(comment, commit)));
        Map<String, Long> rowIds = timeline.findByPrIdOrderByTimestampAscIdAsc(prId).stream()
                .collect(toImmutableMap(row -> row.getEvent(), row -> row.getId()));

        // A second refresh returns the same two events; neither row should be recreated.
        store.save(prId, detail(ImmutableList.of(comment, commit)));

        assertThat(timeline.findByPrIdOrderByTimestampAscIdAsc(prId))
                .hasSize(2)
                .allSatisfy(row -> assertThat(row.getId()).isEqualTo(rowIds.get(row.getEvent())));
    }

    private static StoredPrDetail detail(List<PrTimelineEvent> events)
    {
        return new StoredPrDetail(
                new PrRawDetail("body", ImmutableList.of(), false, true, "clean", 1, 1, 1, 0, ImmutableList.of(),
                        "a".repeat(40), "feature"),
                ImmutableList.of(),
                events,
                ImmutableList.of());
    }

    private static PrReviewState review(long githubId, String login, String body)
    {
        return new PrReviewState(githubId, login, null, false, "COMMENTED", NOW, body, null, null);
    }

    private static StoredPrDetail detail(List<PrReviewState> reviews, List<PrCheckRunState> checkRuns)
    {
        return new StoredPrDetail(
                new PrRawDetail("body", ImmutableList.of(), false, true, "clean", 1, 1, 1, 0, ImmutableList.of(),
                        "a".repeat(40), "feature"),
                reviews,
                ImmutableList.of(),
                checkRuns);
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

    private static StoredPrDetail withTimeline(StoredPrDetail detail, PrTimelineEvent event)
    {
        return new StoredPrDetail(
                detail.raw(),
                detail.reviews(),
                ImmutableList.<PrTimelineEvent>builder().addAll(detail.timeline()).add(event).build(),
                detail.checkRuns(),
                detail.unavailableSections(),
                detail.viewerCanWrite());
    }

    private static StoredPrDetail partial(
            List<PrReviewState> reviews,
            List<PrCheckRunState> checkRuns,
            List<PrTimelineEvent> events,
            List<String> unavailableSections)
    {
        return new StoredPrDetail(
                new PrRawDetail("body", ImmutableList.of(), false, true, "clean", 1, 1, 1, 0, ImmutableList.of(),
                        "a".repeat(40), "feature"),
                reviews,
                events,
                checkRuns,
                unavailableSections);
    }

    private static PrTimelineEvent remote(long githubId, Instant timestamp)
    {
        return new PrTimelineEvent(githubId, "commented", "reviewer", timestamp, "comment", Optional.empty());
    }
}
