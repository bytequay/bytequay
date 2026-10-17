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

import com.bytequay.core.domain.PrActivitySummary;
import com.bytequay.core.domain.PrCheckRunState;
import com.bytequay.core.domain.PrCommitLine;
import com.bytequay.core.domain.PrMergeQueueRemoval;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PrReaction;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.PrTimelineComment;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PrTimelinePayload;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.persistence.sqlite.entity.PullRequestEntity;
import com.bytequay.persistence.sqlite.repository.PrCheckRunJpaRepository;
import com.bytequay.persistence.sqlite.repository.PrReviewJpaRepository;
import com.bytequay.persistence.sqlite.repository.PrTimelineJpaRepository;
import com.bytequay.persistence.sqlite.repository.PullRequestJpaRepository;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.hibernate.resource.jdbc.spi.StatementInspector;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static org.assertj.core.api.Assertions.assertThat;

class TestSqlitePrDetailStore
        extends AbstractTestSqliteStore
{
    private static final String DATABASE_PATH = Path.of(System.getProperty("java.io.tmpdir"), "bytequay-pr-detail-store-" + UUID.randomUUID() + ".db").toString();
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

    @Autowired
    private JdbcTemplate jdbc;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry)
    {
        registerSqliteProperties(registry, "jdbc:sqlite:" + DATABASE_PATH + "?foreign_keys=ON", "create-drop");
        registry.add("spring.jpa.properties.hibernate.session_factory.statement_inspector", RecordingStatementInspector.class::getName);
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
    void recordsSuccessfulGroupValidationsIndependentlyAndDoesNotValidateLocalPatches()
    {
        StoredPrDetail original = detail(ImmutableList.of(remote(1, NOW)));
        store.save(prId, original);
        var originalTimes = store.findFreshness(prId);

        store.saveStatus(prId, original);
        var afterChecks = store.findFreshness(prId);
        assertThat(afterChecks.metadataAt()).isEqualTo(originalTimes.metadataAt());
        assertThat(afterChecks.discussionAt()).isEqualTo(originalTimes.discussionAt());
        assertThat(afterChecks.discussionSourceUpdatedAt()).isEqualTo(originalTimes.discussionSourceUpdatedAt());
        assertThat(afterChecks.checksAt()).hasValueSatisfying(at -> assertThat(at).isAfter(NOW));

        StoredPrDetail partial = new StoredPrDetail(original.raw(), original.reviews(), original.timeline(), original.checkRuns(), ImmutableList.of(StoredPrDetail.COMMENTS));
        store.saveDiscussion(prId, partial, NOW.plusSeconds(10));
        assertThat(store.findFreshness(prId)).isEqualTo(afterChecks);

        store.saveDiscussion(prId, original, NOW.plusSeconds(10));
        var afterDiscussion = store.findFreshness(prId);
        assertThat(afterDiscussion.metadataAt()).isEqualTo(originalTimes.metadataAt());
        assertThat(afterDiscussion.checksAt()).isEqualTo(afterChecks.checksAt());
        assertThat(afterDiscussion.discussionAt()).hasValueSatisfying(at -> assertThat(at).isAfter(NOW));
        assertThat(afterDiscussion.discussionSourceUpdatedAt()).contains(NOW.plusSeconds(10));

        store.saveCached(prId, original);
        assertThat(store.findFreshness(prId)).isEqualTo(afterDiscussion);
    }

    @Test
    void readsOnlyRequestedCachedAuthorAvatars()
    {
        String avatar = "https://avatars.githubusercontent.com/u/123?v=4";
        store.save(prId, avatarDetail(avatar));
        store.save(prId + 100, avatarDetail("https://avatars.githubusercontent.com/u/456?v=4"));

        assertThat(store.findAuthorAvatarUrls(ImmutableSet.of(prId, prId + 200)))
                .containsExactlyEntriesOf(ImmutableMap.of(prId, avatar));
        assertThat(store.findAuthorAvatarUrls(ImmutableSet.of())).isEmpty();

        store.save(prId, avatarDetail(null));
        assertThat(store.findAuthorAvatarUrls(ImmutableSet.of(prId))).isEmpty();
        store.save(prId, avatarDetail(" "));
        assertThat(store.findAuthorAvatarUrls(ImmutableSet.of(prId))).isEmpty();
    }

    @Test
    void readsLatestGitHubActivityWithoutUsingLocalOrSubscriptionEvents()
    {
        PrActivitySummary expected = new PrActivitySummary("labeled", Optional.of("maintainer"), NOW.plusSeconds(1),
                Optional.empty(), Optional.of("stale-ignore"), Optional.empty(), Optional.empty(), Optional.empty(), OptionalInt.empty());
        store.save(prId, detail(ImmutableList.of(
                remote(1, NOW),
                new PrTimelineEvent(2L, "labeled", "maintainer", NOW.plusSeconds(1), null, PrTimelinePayload.builder().label("stale-ignore").build(), Optional.empty()),
                new PrTimelineEvent(3L, "mentioned", "viewer", NOW.plusSeconds(2), null, Optional.empty()),
                new PrTimelineEvent(4L, "subscribed", "viewer", NOW.plusSeconds(3), null, Optional.empty()),
                new PrTimelineEvent(5L, "unsubscribed", "viewer", NOW.plusSeconds(4), null, Optional.empty()),
                remote(6, null))));
        store.recordLocalTimelineEvent(PULL_REQUEST, new PrTimelineEvent(null, "ci_autofix", "viewer", NOW.plusSeconds(5), "local work", Optional.of("local:activity")));
        store.save(prId + 100, detail(remote(7, NOW.plusSeconds(6))));

        assertThat(store.findLatestActivities(ImmutableSet.of(prId, prId + 200)))
                .containsExactlyEntriesOf(ImmutableMap.of(prId, expected));
        assertThat(store.findLatestActivities(ImmutableSet.of())).isEmpty();
        assertThat(store.findLatestActivities(ImmutableSet.of(prId + 200))).isEmpty();
    }

    @Test
    void usesTheLatestInlineReplyActorTimeTextAndLine()
    {
        PrTimelineComment reply = new PrTimelineComment(11L, "replier", null, false, NOW.plusSeconds(3), "  reply body\n", null, null, 10L, ImmutableList.of());
        store.save(prId, detail(ImmutableList.of(
                new PrTimelineEvent(10L, "review_thread", "root-author", NOW, null,
                        PrTimelinePayload.builder().path("src/Writer.java").originalLine(214).comments(ImmutableList.of(reply)).build(), Optional.empty()),
                remote(12, NOW.plusSeconds(2)))));

        assertThat(store.findLatestActivities(ImmutableSet.of(prId))).containsExactlyEntriesOf(ImmutableMap.of(prId,
                new PrActivitySummary("review_thread", Optional.of("replier"), NOW.plusSeconds(3), Optional.empty(), Optional.empty(), Optional.empty(),
                        Optional.of("reply body"), Optional.of("src/Writer.java"), OptionalInt.of(214))));
    }

    @Test
    void quotesTheStartOfALongComment()
    {
        String body = "x".repeat(1000);
        store.save(prId, detail(new PrTimelineEvent(1L, "commented", "reviewer", NOW, body, Optional.empty())));

        assertThat(store.findLatestActivities(ImmutableSet.of(prId)).get(prId).excerpt()).contains("x".repeat(800) + "…");
        assertThat(PrActivitySummary.excerpt(" \n ")).isEmpty();
        assertThat(PrActivitySummary.excerpt(null)).isEmpty();
    }

    @Test
    void readsCommitsAfterEachPullRequestsOwnInstantNewestFirst()
    {
        store.save(prId, detail(ImmutableList.of(
                commit(1, "aaa", "Old work", NOW),
                commit(2, "bbb", "Map CommitFailedException\n\nLonger explanation", NOW.plusSeconds(10)),
                commit(3, "ccc", "Add a conflict test", NOW.plusSeconds(20)),
                remote(4, NOW.plusSeconds(30)))));
        store.save(prId + 100, detail(commit(5, "ddd", "Elsewhere", NOW.plusSeconds(20))));

        assertThat(store.findCommitsAfter(ImmutableMap.of(prId, NOW.plusSeconds(5), prId + 100, NOW.plusSeconds(25)))).containsExactlyEntriesOf(ImmutableMap.of(prId,
                ImmutableList.of(
                        new PrCommitLine("ccc", "Add a conflict test", Optional.of("author"), NOW.plusSeconds(20)),
                        new PrCommitLine("bbb", "Map CommitFailedException", Optional.of("author"), NOW.plusSeconds(10)))));
        assertThat(store.findCommitsAfter(ImmutableMap.of())).isEmpty();
    }

    @Test
    void readsCheckRunsForEveryRequestedPullRequest()
    {
        store.save(prId, detail(ImmutableList.of(), ImmutableList.of(
                new PrCheckRunState(1L, "ci / test", "completed", "failure", null, "check_run", NOW, NOW),
                new PrCheckRunState(2L, "ci / lint", "completed", "success", null, "check_run", NOW, NOW))));

        assertThat(store.findCheckRuns(ImmutableSet.of(prId, prId + 1)).get(prId))
                .extracting(PrCheckRunState::name)
                .containsExactly("ci / test", "ci / lint");
        assertThat(store.findCheckRuns(ImmutableSet.of(prId + 1))).isEmpty();
        assertThat(store.findCheckRuns(ImmutableSet.of())).isEmpty();
    }

    @Test
    void keepsReviewVerdictsAndRequestRecipientsWithTheirOwnEvents()
    {
        store.save(prId, detail(new PrTimelineEvent(1L, "reviewed", "reviewer", NOW, null, PrTimelinePayload.builder().state("APPROVED").build(), Optional.empty())));
        store.save(prId + 1, detail(new PrTimelineEvent(2L, "review_requested", "maintainer", NOW, null,
                PrTimelinePayload.builder().requestedReviewer("viewer").build(), Optional.empty())));

        assertThat(store.findLatestActivities(ImmutableSet.of(prId, prId + 1))).containsExactlyInAnyOrderEntriesOf(ImmutableMap.of(
                prId, new PrActivitySummary("reviewed", Optional.of("reviewer"), NOW, Optional.of("APPROVED"), Optional.empty(), Optional.empty(),
                        Optional.empty(), Optional.empty(), OptionalInt.empty()),
                prId + 1, new PrActivitySummary("review_requested", Optional.of("maintainer"), NOW, Optional.empty(), Optional.empty(), Optional.of("viewer"),
                        Optional.empty(), Optional.empty(), OptionalInt.empty())));
    }

    @Test
    void latestActivitiesSeekEachPullRequestInsteadOfScanningLocalEventKeys()
    {
        store.save(prId, detail(remote(1, NOW)));
        timeline.flush();
        RecordingStatementInspector.STATEMENTS.clear();

        store.findLatestActivities(ImmutableSet.of(prId, prId + 1));

        assertThat(RecordingStatementInspector.STATEMENTS).singleElement().satisfies(sql -> assertThat(jdbc.query(
                "EXPLAIN QUERY PLAN " + sql,
                (row, rowNumber) -> row.getString("detail"),
                prId, prId + 1, "mentioned", "subscribed", "unsubscribed", "mentioned", "subscribed", "unsubscribed"))
                .filteredOn(step -> step.startsWith("SEARCH") || step.startsWith("SCAN"))
                .isNotEmpty()
                .allMatch(step -> step.contains(" USING INDEX idx_pr_timeline_pr_timestamp (pr_id=?")));
    }

    @Test
    void recordsOneLocalEventAndPreservesItAcrossFullRefreshes()
    {
        store.save(prId, detail(remote(1, NOW.plusSeconds(2))));
        PrTimelineEvent local = new PrTimelineEvent(null, "ci_autofix", "author", NOW.plusSeconds(1), "verified push facts", Optional.of("ci-autofix:run:attempt:sha"));

        assertThat(store.recordLocalTimelineEvent(PULL_REQUEST, local)).isEqualTo(local);
        assertThat(store.recordLocalTimelineEvent(PULL_REQUEST, local)).isEqualTo(local);
        assertThat(timeline.count()).isEqualTo(2);

        store.save(prId, detail(remote(2, NOW.plusSeconds(3))));

        assertThat(store.find(prId).orElseThrow().timeline())
                .extracting(PrTimelineEvent::localEventKey)
                .containsExactly(Optional.of("ci-autofix:run:attempt:sha"), Optional.empty());
    }

    @Test
    void savesPatchedCachedTimelineWithoutReinsertingLocalEvents()
    {
        PrTimelineEvent first = remote(1, NOW);
        store.save(prId, detail(first));
        PrTimelineEvent local = new PrTimelineEvent(null, "ci_autofix", "author", NOW.plusSeconds(1), "verified push facts", Optional.of("ci-autofix:patch:sha"));
        store.recordLocalTimelineEvent(PULL_REQUEST, local);

        StoredPrDetail cached = store.find(prId).orElseThrow();
        PrTimelineEvent second = remote(2, NOW.plusSeconds(2));
        store.save(prId, withTimeline(cached, second));
        StoredPrDetail patched = store.find(prId).orElseThrow();
        PrTimelineEvent third = remote(3, NOW.plusSeconds(3));
        store.save(prId, withTimeline(patched, third));

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
    void forgetsDiscussionEtagsWithAnInvalidatedDetailEtag()
    {
        store.save(prId, detail(remote(1, NOW)));
        store.updateDiscussionEtags(prId, ImmutableMap.of("timeline", "\"timeline-1\""));

        assertThat(store.findDiscussionEtags(prId)).containsExactly(Map.entry("timeline", "\"timeline-1\""));

        // A local write invalidates the detail ETag; the discussion must then be read in full.
        store.updateEtag(prId, Optional.empty());

        assertThat(store.findDiscussionEtags(prId)).isEmpty();
    }

    @Test
    void derivesRequestedReviewerCountFromStoredReviewers()
    {
        StoredPrDetail detail = new StoredPrDetail(
                new PrRawDetail(null, null, null, null, null, null, null, null, "body", ImmutableList.of(), null, ImmutableList.of(), null, false, true, "clean", 1, 1, 1, 99,
                        ImmutableList.of("alice", "bob"), "a".repeat(40), "feature", null),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of());

        store.save(prId, detail);

        assertThat(store.find(prId).orElseThrow().raw().requestedReviewerCount()).isEqualTo(2);
        assertThat(jdbc.queryForList("SELECT name FROM pragma_table_info('pr_detail')", String.class))
                .doesNotContain("requested_reviewer_count");
    }

    @Test
    void statusRefreshOnlyReplacesMergeabilityAndChecks()
    {
        PrTimelineEvent comment = remote(1, NOW);
        StoredPrDetail original = new StoredPrDetail(
                new PrRawDetail(null, null, null, null, null, null, null, null, "body", ImmutableList.of(), null, ImmutableList.of(), null, false, true, "clean", 1, 1, 1, 0,
                        ImmutableList.of(), "a".repeat(40), "feature", null),
                ImmutableList.of(new PrReviewState(null, "alice", null, false, "APPROVED", null, null, null, null)),
                ImmutableList.of(comment),
                ImmutableList.of(new PrCheckRunState(1L, "build", "completed", "success", null, "check_run", null, null)),
                ImmutableList.of());
        store.save(prId, original);
        PrCheckRunState completedCheck = new PrCheckRunState(2L, "test", "completed", "failure", null, "check_run", NOW.plusSeconds(10), NOW.plusSeconds(30));
        StoredPrDetail status = new StoredPrDetail(
                original.raw().withMergeability(false, "dirty"),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(completedCheck),
                ImmutableList.of());

        store.saveStatus(prId, status);

        StoredPrDetail restored = store.find(prId).orElseThrow();
        assertThat(restored.raw().mergeable()).isFalse();
        assertThat(restored.raw().mergeableState()).isEqualTo("dirty");
        assertThat(restored.reviews()).containsExactly(new PrReviewState(null, "alice", null, false, "APPROVED", null, null, null, null));
        assertThat(restored.timeline()).containsExactly(comment);
        assertThat(restored.checkRuns()).containsExactly(completedCheck);
    }

    @Test
    void roundTripsTheTypedTimelinePayloadAndUnavailableSections()
    {
        PrTimelinePayload payload = new PrTimelinePayload(
                "https://avatars/bot", true, null, "https://github/event", "reviewer", "assignee", "docs", "d4c5f9",
                null, null, null, null, null, null, null, null, null, null,
                null, null, null, null, null, null, false, false,
                ImmutableList.of(new PrReaction("+1", 2, true)), ImmutableList.of());
        payload = PrTimelinePayload.builder(payload).mergeQueueRemoval(new PrMergeQueueRemoval(
                Optional.of("failed_checks"), Optional.of("https://github.com/owner/repo/commit/queue"),
                ImmutableList.of(new PrMergeQueueRemoval.FailedCheck("integration", Optional.of("https://github.com/owner/repo/actions/runs/1/job/2"))))).build();
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
            assertThat(event.payload().requestedReviewer()).isEqualTo("reviewer");
            assertThat(event.payload().assignee()).isEqualTo("assignee");
            assertThat(event.payload().label()).isEqualTo("docs");
            assertThat(event.payload().mergeQueueRemoval().reason()).contains("failed_checks");
            assertThat(event.payload().mergeQueueRemoval().failedChecks()).singleElement()
                    .satisfies(check -> assertThat(check.url()).contains("https://github.com/owner/repo/actions/runs/1/job/2"));
            assertThat(event.payload().reactions()).containsExactly(new PrReaction("+1", 2, true));
        });
        assertThat(restored.unavailableSections()).containsExactly("checks");
        assertThat(restored.viewerCanWrite()).isTrue();
    }

    @Test
    void refreshKeepsRowIdentityAndSweepsWhatGitHubDropped()
    {
        store.save(prId, detail(
                ImmutableList.of(review(11L, "alice", "lgtm"), review(12L, "bob", "nit")),
                ImmutableList.of(new PrCheckRunState(21L, "build", "completed", "success", null, "check_run", null, null),
                        new PrCheckRunState(22L, "test", "completed", "failure", null, "check_run", null, null))));
        long aliceRowId = reviews.findByPrId(prId).stream()
                .filter(row -> row.getGithubId() == 11L)
                .findFirst()
                .orElseThrow()
                .getId();

        // GitHub now reports only alice's review, with an edited body, and only the build check.
        store.save(prId, detail(
                ImmutableList.of(review(11L, "alice", "still lgtm")),
                ImmutableList.of(new PrCheckRunState(21L, "build", "completed", "success", null, "check_run", null, null))));

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
                ImmutableList.of(new PrCheckRunState(21L, "build", "completed", "success", null, "check_run", null, null)),
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
                        null, false, null, null, null, null, null, null,
                        null, null, "c0ffee", null, null, null, null, null, null, null,
                        null, null, null, null, null, null, false, false,
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

    private static StoredPrDetail avatarDetail(String avatarUrl)
    {
        return new StoredPrDetail(
                new PrRawDetail(
                        "Overview", "https://github/pr/42", "open", "alice", avatarUrl,
                        NOW, NOW, null, "body", ImmutableList.of(), ImmutableMap.of(), ImmutableList.of(),
                        null, false, true, "clean", 1, 1, 1, 0, ImmutableList.of(),
                        "a".repeat(40), "feature", "main"),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of());
    }

    private static StoredPrDetail detail(List<PrTimelineEvent> events)
    {
        return partial(ImmutableList.of(), ImmutableList.of(), events, ImmutableList.of());
    }

    private static PrReviewState review(long githubId, String login, String body)
    {
        return new PrReviewState(githubId, login, null, false, "COMMENTED", NOW, body, null, null);
    }

    private static StoredPrDetail detail(List<PrReviewState> reviews, List<PrCheckRunState> checkRuns)
    {
        return partial(reviews, checkRuns, ImmutableList.of(), ImmutableList.of());
    }

    private static StoredPrDetail detail(PrTimelineEvent event)
    {
        return detail(ImmutableList.of(event));
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

    private static StoredPrDetail partial(List<PrReviewState> reviews, List<PrCheckRunState> checkRuns, List<PrTimelineEvent> events, List<String> unavailableSections)
    {
        return new StoredPrDetail(
                new PrRawDetail(null, null, null, null, null, null, null, null, "body", ImmutableList.of(), null, ImmutableList.of(), null, false, true, "clean", 1, 1, 1, 0,
                        ImmutableList.of(), "a".repeat(40), "feature", null),
                reviews,
                events,
                checkRuns,
                unavailableSections);
    }

    private static PrTimelineEvent commit(long githubId, String sha, String message, Instant timestamp)
    {
        return new PrTimelineEvent(githubId, "committed", "author", timestamp, null, PrTimelinePayload.builder().commitSha(sha).commitMessage(message).build(), Optional.empty());
    }

    private static PrTimelineEvent remote(long githubId, Instant timestamp)
    {
        return new PrTimelineEvent(githubId, "commented", "reviewer", timestamp, "comment", Optional.empty());
    }

    public static class RecordingStatementInspector
            implements StatementInspector
    {
        private static final List<String> STATEMENTS = new CopyOnWriteArrayList<>();

        @Override
        public String inspect(String sql)
        {
            STATEMENTS.add(sql);
            return sql;
        }
    }
}
