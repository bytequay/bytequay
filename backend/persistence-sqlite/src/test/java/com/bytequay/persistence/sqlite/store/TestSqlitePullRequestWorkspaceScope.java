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

import com.bytequay.core.domain.FetchedPullRequest;
import com.bytequay.core.domain.HandledAction;
import com.bytequay.core.domain.PrActivitySummary;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class TestSqlitePullRequestWorkspaceScope
        extends AbstractTestSqliteStore
{
    private static final Instant RETENTION_CUTOFF = Instant.parse("2026-08-01T00:00:00Z");
    private static final String DATABASE_PATH = Path.of(
            System.getProperty("java.io.tmpdir"),
            "bytequay-pr-scope-" + UUID.randomUUID() + ".db").toString();

    @Autowired
    private SqlitePullRequestStore store;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private SqlitePrViewStateStore viewStateStore;

    @Autowired
    private SqliteWorkspaceStore workspaceStore;

    @DynamicPropertySource
    static void configureDatabase(DynamicPropertyRegistry registry)
    {
        registerSqliteProperties(registry, "jdbc:sqlite:" + DATABASE_PATH + "?foreign_keys=ON", "create-drop");
    }

    @Test
    void notificationPreviewAndDiscussionTimestampSurviveOlderDiscovery()
    {
        FetchedPullRequest fetched = pullRequest(1, "owner/project", 1);
        PullRequest row = store.saveObserved(fetched);
        Instant replyAt = fetched.updatedAt().plusSeconds(60);
        PrActivitySummary reply = new PrActivitySummary("commented", Optional.of("reviewer"), replyAt,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of("New reply"), Optional.empty(), OptionalInt.empty());
        store.observeActivity(row.id(), Optional.of(reply), replyAt);
        store.saveObserved(fetched);
        store.observeActivity(row.id(), Optional.empty(), fetched.updatedAt());

        assertThat(store.findDiscussionActivityAt(row.id())).contains(replyAt);
        assertThat(store.findActivities(Set.of(row.id()))).containsEntry(row.id(), reply);
        assertThat(store.findById(row.id()).orElseThrow().activityAt()).isEqualTo(replyAt);
    }

    @Test
    void monitorSurvivesSyncAndRetentionAndDisablesWithoutChangingActivity()
    {
        FetchedPullRequest fetched = pullRequest(1, "owner/project", 1, "closed", RETENTION_CUTOFF.minusSeconds(1), RETENTION_CUTOFF.minusSeconds(1))
                .withOrigin(PullRequest.Origin.OTHER);
        PullRequest row = store.saveObserved(fetched);
        store.setMonitored(row.id(), true);
        Instant baseline = store.findById(row.id()).orElseThrow().monitoredAt();
        store.saveDashboardSyncPage(ImmutableList.of(fetched), "old");
        store.completeDashboardSync("new", "alice");

        assertThat(store.findById(row.id()).orElseThrow().monitoredAt()).isEqualTo(baseline);
        assertThat(store.findPage(0, 50).pullRequests()).extracting(PullRequest::id).contains(row.id());
        assertThat(store.findAttentionCandidates()).extracting(PullRequest::id).contains(row.id());
        store.setMonitored(row.id(), false);
        assertThat(store.findById(row.id()).orElseThrow().monitoredAt()).isNull();
        assertThat(store.findById(row.id()).orElseThrow().activityAt()).isEqualTo(fetched.updatedAt());
        assertThat(store.findAttentionCandidates()).isEmpty();
    }

    @Test
    void activityChangesOnlyWhenObservedGithubFactsChange()
    {
        PullRequest row = store.saveObserved(pullRequest(1, "owner/project", 1));
        store.updateEnrichment(row.id(), row.updatedAt(), PullRequestDetail.CiStatus.PENDING, 1, 0, 0, true, "clean", null, "feature",
                ImmutableList.of(), PullRequest.Origin.AUTHORED, Optional.empty(), Optional.empty(), ImmutableMap.of("checks", "pending"));
        store.setMonitored(row.id(), true);
        Instant baselineActivity = store.findById(row.id()).orElseThrow().activityAt();
        store.updateEnrichment(row.id(), row.updatedAt(), PullRequestDetail.CiStatus.PENDING, 1, 0, 0, true, "clean", null, "feature",
                ImmutableList.of(), PullRequest.Origin.AUTHORED, Optional.empty(), Optional.empty(), ImmutableMap.of("checks", "pending"));
        assertThat(store.findById(row.id()).orElseThrow().activityAt()).isEqualTo(baselineActivity);

        store.updateEnrichment(row.id(), row.updatedAt(), PullRequestDetail.CiStatus.FAILING, 1, 0, 0, true, "clean", null, "feature",
                ImmutableList.of(), PullRequest.Origin.AUTHORED, Optional.empty(), Optional.empty(), ImmutableMap.of("summary", "failed"));
        PullRequest changed = store.findById(row.id()).orElseThrow();
        assertThat(changed.updatedAt()).isEqualTo(row.updatedAt());
        assertThat(changed.activityAt()).isAfter(changed.monitoredAt());
        store.updateEnrichment(row.id(), row.updatedAt(), PullRequestDetail.CiStatus.FAILING, 1, 0, 0, true, "clean", null, "feature",
                ImmutableList.of(), PullRequest.Origin.AUTHORED, Optional.empty(), Optional.empty(), ImmutableMap.of("summary", "failed"));
        assertThat(store.findById(row.id()).orElseThrow().activityAt()).isEqualTo(changed.activityAt());
    }

    @Test
    void reviewProjectionDifferencesDoNotCreateActivityButChangedReviewSourcesDo()
    {
        PullRequest row = store.saveObserved(pullRequest(1, "owner/project", 1));
        store.updateEnrichment(row.id(), row.updatedAt(), PullRequestDetail.CiStatus.PASSING, 1, 0, 0, true, "clean", null, "feature",
                ImmutableList.of(), PullRequest.Origin.AUTHORED, Optional.empty(), Optional.of("APPROVED"),
                ImmutableMap.of("summary", "summary-v1", "reviews", "reviews-v1"));
        store.setMonitored(row.id(), true);
        Instant baselineActivity = store.findById(row.id()).orElseThrow().activityAt();

        for (Optional<String> decision : ImmutableList.of(Optional.of("REVIEW_REQUIRED"), Optional.<String>empty(), Optional.of("APPROVED"))) {
            store.updateEnrichment(row.id(), row.updatedAt(), PullRequestDetail.CiStatus.PASSING, 1, 0, 0, true, "clean", null, "feature",
                    ImmutableList.of(), PullRequest.Origin.AUTHORED, Optional.empty(), decision, ImmutableMap.of("summary", "summary-v1"));
            store.updateEnrichment(row.id(), row.updatedAt(), PullRequestDetail.CiStatus.PASSING, 1, 0, 0, true, "clean", null, "feature",
                    ImmutableList.of(), PullRequest.Origin.AUTHORED, Optional.empty(), Optional.of("APPROVED"), ImmutableMap.of("reviews", "reviews-v1"));
            assertThat(store.findById(row.id()).orElseThrow().activityAt()).isEqualTo(baselineActivity);
        }

        store.updateEnrichment(row.id(), row.updatedAt(), PullRequestDetail.CiStatus.PASSING, 1, 0, 0, true, "clean", null, "feature",
                ImmutableList.of(), PullRequest.Origin.AUTHORED, Optional.empty(), Optional.of("CHANGES_REQUESTED"), ImmutableMap.of("summary", "summary-v2"));
        PullRequest summaryChanged = store.findById(row.id()).orElseThrow();
        assertThat(summaryChanged.activityAt()).isAfter(summaryChanged.monitoredAt());
        store.updateEnrichment(row.id(), row.updatedAt(), PullRequestDetail.CiStatus.PASSING, 1, 0, 0, true, "clean", null, "feature",
                ImmutableList.of(), PullRequest.Origin.AUTHORED, Optional.empty(), Optional.of("CHANGES_REQUESTED"), ImmutableMap.of("reviews", "reviews-v2"));
        assertThat(store.findById(row.id()).orElseThrow().activityAt()).isAfter(summaryChanged.activityAt());
    }

    @Test
    void unchangedSummaryAndDetailKeepAcknowledgedFactsAfterReload()
    {
        FetchedPullRequest fetched = pullRequest(1, "owner/project", 1, "merged", RETENTION_CUTOFF, RETENTION_CUTOFF);
        PullRequest row = store.saveObserved(fetched);
        store.updateEnrichment(row.id(), row.updatedAt(), PullRequestDetail.CiStatus.FAILING, 1, 0, 4, null, "unknown", null, "feature",
                ImmutableList.of(), PullRequest.Origin.AUTHORED, Optional.empty(), Optional.of("APPROVED"), ImmutableMap.of("summary", "summary-v1"));
        // Detail ignores an old failed check after a successful rerun; the summary rollup can still include it.
        store.updateEnrichment(row.id(), row.updatedAt(), PullRequestDetail.CiStatus.PASSING, 1, 0, 4, null, "unknown", null, "feature",
                ImmutableList.of(), PullRequest.Origin.AUTHORED, Optional.empty(), Optional.of("APPROVED"), ImmutableMap.of("checks", "checks-v1"));
        PullRequest acknowledged = store.findById(row.id()).orElseThrow();

        for (int refresh = 0; refresh < 3; refresh++) {
            entityManager.flush();
            entityManager.clear();
            store.saveObserved(fetched);
            store.updateEnrichment(row.id(), row.updatedAt(), PullRequestDetail.CiStatus.FAILING, 1, 0, 4, null, "unknown", null, "feature",
                    ImmutableList.of(), PullRequest.Origin.AUTHORED, Optional.empty(), Optional.of("APPROVED"), ImmutableMap.of("summary", "summary-v1"));
            assertThat(store.findById(row.id()).orElseThrow()).isEqualTo(acknowledged);
            store.updateEnrichment(row.id(), row.updatedAt(), PullRequestDetail.CiStatus.PASSING, 1, 0, 4, null, "unknown", null, "feature",
                    ImmutableList.of(), PullRequest.Origin.AUTHORED, Optional.empty(), Optional.of("APPROVED"), ImmutableMap.of("checks", "checks-v1"));
            assertThat(store.findById(row.id()).orElseThrow()).isEqualTo(acknowledged);
        }

        store.updateEnrichment(row.id(), row.updatedAt(), PullRequestDetail.CiStatus.FAILING, 1, 0, 4, null, "unknown", null, "feature",
                ImmutableList.of(), PullRequest.Origin.AUTHORED, Optional.empty(), Optional.of("APPROVED"), ImmutableMap.of("summary", "summary-v2"));
        PullRequest changed = store.findById(row.id()).orElseThrow();
        assertThat(changed.ciStatus()).isEqualTo(PullRequestDetail.CiStatus.FAILING);
        assertThat(changed.activityAt()).isAfter(acknowledged.activityAt());
        store.updateEnrichment(row.id(), row.updatedAt(), PullRequestDetail.CiStatus.PASSING, 1, 0, 4, null, "unknown", null, "feature",
                ImmutableList.of(), PullRequest.Origin.AUTHORED, Optional.empty(), Optional.of("APPROVED"), ImmutableMap.of("checks", "checks-v1"));
        assertThat(store.findById(row.id()).orElseThrow()).isEqualTo(changed);
        store.updateEnrichment(row.id(), row.updatedAt(), PullRequestDetail.CiStatus.PASSING, 1, 0, 4, null, "unknown", null, "feature",
                ImmutableList.of(), PullRequest.Origin.AUTHORED, Optional.empty(), Optional.of("APPROVED"), ImmutableMap.of("checks", "checks-v2"));
        assertThat(store.findById(row.id()).orElseThrow().ciStatus()).isEqualTo(PullRequestDetail.CiStatus.PASSING);
        assertThat(store.findById(row.id()).orElseThrow().activityAt()).isAfter(changed.activityAt());
    }

    @Test
    void labelOrderingAndEquivalentMergedStatesDoNotCreateActivity()
    {
        Instant timestamp = Instant.parse("2026-08-20T00:00:00Z");
        FetchedPullRequest fetched = new FetchedPullRequest(
                1L, "owner/project", 1, "title", "alice", "url", timestamp, timestamp, PullRequest.Origin.AUTHORED,
                ImmutableList.of("a", "b"), ImmutableMap.of(), false, "closed", timestamp, timestamp, ImmutableList.of(), null, null, "feature");
        PullRequest row = store.saveObserved(fetched);
        store.updateDetails(row.id(), new PrRawDetail(
                "title", "url", "merged", "alice", null, timestamp, timestamp, timestamp, "body",
                ImmutableList.of("b", "a"), ImmutableMap.of(), ImmutableList.of(), null, false, null, null,
                0, 0, 0, 0, ImmutableList.of(), "head", "feature", "main"));
        store.saveObserved(new FetchedPullRequest(
                1L, "owner/project", 1, "title", "alice", "url", timestamp, timestamp, PullRequest.Origin.AUTHORED,
                ImmutableList.of("b", "a"), ImmutableMap.of(), false, "closed", timestamp, timestamp, ImmutableList.of(), null, null, "feature"));
        assertThat(store.findById(row.id()).orElseThrow().activityAt()).isEqualTo(row.activityAt());
        assertThat(store.findById(row.id()).orElseThrow().state()).isEqualTo("merged");
    }

    @Test
    void detailBaselinePreventsOldListChangesFromCreatingMonitoredActivity()
    {
        Instant closedAt = Instant.parse("2026-08-20T00:00:00Z");
        Instant updatedAt = Instant.parse("2026-08-21T00:00:00Z");
        PullRequest row = store.saveObserved(pullRequest(1, "owner/project", 1, "closed", closedAt, closedAt));
        store.updateDetails(row.id(), new PrRawDetail(
                "Updated title", row.htmlUrl(), "closed", "alice", null, row.createdAt(), updatedAt, null, "body",
                ImmutableList.of("java"), ImmutableMap.of("java", "abcdef"), ImmutableList.of(), null, true, null, null,
                0, 0, 0, 0, ImmutableList.of(), "head", "feature", "main"));
        store.setMonitored(row.id(), true);
        PullRequest baseline = store.findById(row.id()).orElseThrow();
        assertThat(baseline.title()).isEqualTo("Updated title");
        assertThat(baseline.draft()).isTrue();
        assertThat(baseline.labels()).containsExactly("java");
        assertThat(baseline.labelColors()).containsEntry("java", "abcdef");
        assertThat(baseline.closedAt()).isEqualTo(closedAt);
        assertThat(baseline.activityAt()).isBeforeOrEqualTo(baseline.monitoredAt());

        store.saveRepositorySyncPage("owner/project", ImmutableList.of(new FetchedPullRequest(
                1L, "owner/project", 1, "Updated title", "alice", row.htmlUrl(), row.createdAt(), updatedAt, PullRequest.Origin.AUTHORED,
                ImmutableList.of("java"), ImmutableMap.of("java", "abcdef"), true, "closed", closedAt, null, ImmutableList.of(), null, null, "feature")), "refreshed");

        assertThat(store.findById(row.id()).orElseThrow().activityAt()).isEqualTo(baseline.activityAt());
    }

    @Test
    void staleObservedAndScanRowsPreserveNewerFactsAndCompleteTheirSyncMarkers()
    {
        Instant freshTime = Instant.parse("2026-08-22T00:00:00Z");
        PullRequest fresh = store.saveObserved(new FetchedPullRequest(
                1L, "owner/project", 1, "Current title", "alice", "url", freshTime, freshTime, PullRequest.Origin.REVIEW_REQUESTED,
                ImmutableList.of("current"), ImmutableMap.of("current", "abcdef"), true, "closed", freshTime, null, ImmutableList.of("bob"), null, null, "feature"));
        FetchedPullRequest stale = pullRequest(2, "owner/project", 1, "open", null, freshTime.minusSeconds(60)).withOrigin(PullRequest.Origin.OTHER);

        assertThat(store.saveObserved(stale)).isEqualTo(fresh);
        assertThat(store.saveDashboardSyncPage(ImmutableList.of(stale), "dashboard-current")).containsExactly(fresh);
        assertThat(store.saveRepositorySyncPage("owner/project", ImmutableList.of(stale), "workspace-current")).containsExactly(fresh);

        assertThat(store.findRepositoryRowsMissingSync("owner/project", "workspace-current")).isEmpty();
        assertThat(store.completeDashboardSync("dashboard-current", "alice")).isEmpty();
        assertThat(store.findById(fresh.id())).contains(fresh);
    }

    @Test
    void staleDetailCannotRegressANewerSummaryOrCreateActivity()
    {
        Instant freshTime = Instant.parse("2026-08-22T00:00:00Z");
        PullRequest fresh = store.saveObserved(pullRequest(1, "owner/project", 1, "closed", freshTime, freshTime));
        store.updateDetails(fresh.id(), new PrRawDetail(
                "Old title", "url", "open", "alice", null, fresh.createdAt(), freshTime.minusSeconds(60), null, "body",
                ImmutableList.of("old"), ImmutableMap.of(), ImmutableList.of(), null, true, null, null,
                0, 0, 0, 0, ImmutableList.of(), "head", "feature", "main"));
        assertThat(store.findById(fresh.id())).contains(fresh);

        store.updateDetails(fresh.id(), new PrRawDetail(
                "Current title", "url", "closed", "alice", null, fresh.createdAt(), freshTime, null, "body",
                ImmutableList.of("current"), ImmutableMap.of(), ImmutableList.of(), null, true, null, null,
                0, 0, 0, 0, ImmutableList.of(), "head", "feature", "main"));
        PullRequest current = store.findById(fresh.id()).orElseThrow();
        assertThat(current.title()).isEqualTo("Current title");
        assertThat(current.labels()).containsExactly("current");
        assertThat(current.draft()).isTrue();
        assertThat(current.updatedAt()).isEqualTo(freshTime);
    }

    @Test
    void editingATitlePreservesOtherListFields()
    {
        PullRequest previous = store.saveObserved(pullRequest(1, "owner/project", 1));

        store.updateTitle(previous.id(), "Edited title");

        PullRequest updated = store.findById(previous.id()).orElseThrow();
        assertThat(updated.title()).isEqualTo("Edited title");
        assertThat(updated).usingRecursiveComparison().ignoringFields("title", "activityAt").isEqualTo(previous);
        assertThat(updated.activityAt()).isAfter(previous.updatedAt());
        assertThat(store.findPage(0, 50).pullRequests()).extracting(PullRequest::title).contains("Edited title");
    }

    @Test
    void staleEnrichmentCannotClearANewerReviewRequest()
    {
        Instant freshTime = Instant.parse("2026-08-22T00:00:00Z");
        PullRequest fresh = store.saveObserved(pullRequest(1, "owner/project", 1, "open", null, freshTime).withOrigin(PullRequest.Origin.REVIEW_REQUESTED));
        store.updateEnrichment(fresh.id(), freshTime, PullRequestDetail.CiStatus.PENDING, 1, 0, 2, true, "clean", null, "feature",
                ImmutableList.of("bob"), PullRequest.Origin.REVIEW_REQUESTED, Optional.empty(), Optional.empty(), ImmutableMap.of("raw", "current"));
        PullRequest baseline = store.findById(fresh.id()).orElseThrow();

        store.updateEnrichment(fresh.id(), freshTime.minusSeconds(60), PullRequestDetail.CiStatus.FAILING, 0, 0, 0, false, "blocked", null, "old-feature",
                ImmutableList.of(), PullRequest.Origin.OTHER, Optional.empty(), Optional.of("APPROVED"), ImmutableMap.of("raw", "stale"));

        assertThat(store.findById(fresh.id())).contains(baseline);
    }

    @Test
    void newReviewRequestsAdvanceTheirTimestampAndSearchDoesNotInventRequests()
    {
        FetchedPullRequest fetched = pullRequest(1, "owner/project", 1);
        PullRequest row = store.saveObserved(fetched.withOrigin(PullRequest.Origin.REVIEW_REQUESTED));
        Instant firstRequestedAt = row.reviewRequestedAt();
        store.saveObserved(fetched.withOrigin(PullRequest.Origin.OTHER));
        Instant reviewedAt = Instant.now();
        viewStateStore.markReviewed(row.id(), HandledAction.APPROVED, "head", reviewedAt);
        store.saveObserved(fetched.withOrigin(PullRequest.Origin.REVIEW_REQUESTED));
        Instant requestedAgainAt = store.findById(row.id()).orElseThrow().reviewRequestedAt();
        assertThat(requestedAgainAt).isAfter(reviewedAt);

        FetchedPullRequest searchRow = pullRequest(1, "owner/project", 1, "open", null, fetched.updatedAt(), null).withOrigin(PullRequest.Origin.OTHER);
        store.saveObserved(searchRow);
        store.saveObserved(searchRow);
        assertThat(store.findById(row.id()).orElseThrow().origin()).isEqualTo(PullRequest.Origin.REVIEW_REQUESTED);
        assertThat(store.findById(row.id()).orElseThrow().reviewRequestedAt()).isEqualTo(requestedAgainAt);
        store.saveRepositorySyncPage("owner/project", ImmutableList.of(searchRow), "generic-search");
        store.saveRepositorySyncPage("owner/project", ImmutableList.of(searchRow.withOrigin(PullRequest.Origin.REVIEW_REQUESTED)), "review-search");
        assertThat(store.findById(row.id()).orElseThrow().reviewRequestedAt()).isEqualTo(requestedAgainAt);
        store.updateEnrichment(row.id(), row.updatedAt(), PullRequestDetail.CiStatus.PASSING, 0, 0, 0, true, "clean", null, "feature",
                ImmutableList.of("alice"), PullRequest.Origin.REVIEW_REQUESTED, Optional.of(firstRequestedAt), Optional.empty(), ImmutableMap.of());
        assertThat(store.findById(row.id()).orElseThrow().reviewRequestedAt()).isEqualTo(requestedAgainAt);
    }

    @Test
    void attentionIncludesObservedUpdatesAndRetainsPastReviewRequests()
    {
        ImmutableList.Builder<FetchedPullRequest> rows = ImmutableList.builder();
        for (int index = 1; index <= 55; index++) {
            rows.add(pullRequest(index, "owner/project", index));
        }
        store.saveRepositorySyncPage("owner/project", rows.build(), "first");
        PullRequest unrelated = store.saveObserved(pullRequest(56, "owner/project", 56).withOrigin(PullRequest.Origin.OTHER));
        PullRequest reviewed = store.saveObserved(pullRequest(57, "owner/project", 57).withOrigin(PullRequest.Origin.OTHER));
        Instant reviewedAt = Instant.parse("2026-08-01T00:00:00Z");
        viewStateStore.markReviewed(reviewed.id(), HandledAction.APPROVED, "head", reviewedAt);
        PullRequest requested = store.saveObserved(pullRequest(58, "owner/project", 58).withOrigin(PullRequest.Origin.REVIEW_REQUESTED));
        store.saveObserved(pullRequest(58, "owner/project", 58).withOrigin(PullRequest.Origin.OTHER));

        assertThat(store.findAttentionCandidates()).hasSize(58).extracting(PullRequest::id).contains(unrelated.id());
        assertThat(store.findById(requested.id()).orElseThrow().reviewRequestedAt()).isNotNull();
        assertThat(store.findById(reviewed.id()).orElseThrow().reviewedAt()).isEqualTo(reviewedAt);
    }

    @Test
    void attentionIncludesOrdinaryWatchedRepositoryUpdatesWithoutADetailSnapshot()
    {
        Workspace workspace = workspace("updates", 100, RepoRef.of("owner", "project"));
        workspaceStore.createOrGet(workspace);
        PullRequest watched = store.saveRepositorySyncPage("owner/project", ImmutableList.of(
                pullRequest(1, "owner/project", 1).withOrigin(PullRequest.Origin.OTHER)), "scan").getFirst();
        store.saveRepositorySyncPage("unwatched/project", ImmutableList.of(
                pullRequest(2, "unwatched/project", 2).withOrigin(PullRequest.Origin.OTHER)), "scan");

        assertThat(store.findAttentionCandidates()).extracting(PullRequest::id).containsExactly(watched.id());
        workspaceStore.setWatched(workspace.id(), false);
        assertThat(store.findAttentionCandidates()).isEmpty();
    }

    @Test
    void unwatchHidesWorkspaceOnlyPullRequestsAndRewatchRestoresThem()
    {
        FetchedPullRequest global = pullRequest(1, "account/repo", 1);
        FetchedPullRequest shared = pullRequest(2, "owner/project", 2);
        FetchedPullRequest workspaceOnly = pullRequest(3, "owner/project", 3);
        FetchedPullRequest otherWorkspace = pullRequest(4, "other/project", 4);
        Workspace workspace = workspace("current", 100, RepoRef.of("owner", "project"));
        workspaceStore.createOrGet(workspace);

        store.saveDashboardSyncPage(ImmutableList.of(global, shared), "global-1");
        store.completeDashboardSync("global-1", "alice");
        store.saveRepositorySyncPage("owner/project", ImmutableList.of(shared, workspaceOnly), "workspace-1");
        store.completeRepositorySync("owner/project", RETENTION_CUTOFF);
        store.saveRepositorySyncPage("other/project", ImmutableList.of(otherWorkspace), "workspace-2");
        store.completeRepositorySync("other/project", RETENTION_CUTOFF);

        assertThat(store.findPage(0, 50).pullRequests())
                .extracting(PullRequest::id)
                .containsExactlyInAnyOrder(1L, 2L, 3L);
        assertThat(store.findPage(0, 50).openPullRequests()).isEqualTo(3);
        assertThat(store.findRepositoryPage("OWNER/PROJECT", 0, 50).pullRequests())
                .extracting(PullRequest::id)
                .containsExactlyInAnyOrder(2L, 3L);
        assertThat(store.findRepositoryPage("owner/project", 0, 50).pullRequests())
                .extracting(PullRequest::id)
                .doesNotContain(4L);

        store.saveDashboardSyncPage(ImmutableList.of(global), "global-2");
        store.completeDashboardSync("global-2", "alice");

        assertThat(store.findPage(0, 50).pullRequests())
                .extracting(PullRequest::id)
                .containsExactlyInAnyOrder(1L, 2L, 3L);
        assertThat(store.findRepositoryPage("owner/project", 0, 50).pullRequests())
                .extracting(PullRequest::id)
                .containsExactlyInAnyOrder(2L, 3L);

        workspaceStore.setWatched(workspace.id(), false);

        assertThat(store.findPage(0, 50).pullRequests())
                .extracting(PullRequest::id)
                .containsExactlyInAnyOrder(1L, 2L);
        assertThat(store.findPage(0, 50).openPullRequests()).isEqualTo(2);
        assertThat(store.findRepositoryPage("owner/project", 0, 50).pullRequests())
                .extracting(PullRequest::id).containsExactlyInAnyOrder(2L, 3L);

        workspaceStore.setWatched(workspace.id(), true);

        assertThat(store.findPage(0, 50).pullRequests()).extracting(PullRequest::id).containsExactlyInAnyOrder(1L, 2L, 3L);
        assertThat(store.findPage(0, 50).openPullRequests()).isEqualTo(3);
    }

    @Test
    void readsPullRequestsOneDatabasePageAtATime()
    {
        ImmutableList.Builder<FetchedPullRequest> pullRequests = ImmutableList.builder();
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
        FetchedPullRequest first = pullRequest(1, "owner/project", 41);
        FetchedPullRequest second = pullRequest(2, "owner/project", 42);
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
        FetchedPullRequest observed = pullRequest(91, "external/project", 12);

        PullRequest stored = store.saveObserved(observed);

        assertThat(store.findIdByRepoAndNumber("external/project", 12)).contains(stored.id());
        assertThat(store.findRefById(stored.id())).contains(PullRequestRef.of("external", "project", 12));
        assertThat(store.findPage(0, 50).pullRequests()).containsExactly(stored);
    }

    @Test
    void repairsAuthoredOriginsForTheCurrentGitHubUser()
    {
        long mine = store.saveObserved(pullRequest(92, "external/project", 12)).id();
        long someoneElses = store.saveObserved(pullRequest(93, "external/project", 13)).id();
        jdbc.update("UPDATE pull_requests SET author = 'bob' WHERE id = ?", someoneElses);

        store.completeDashboardSync("dashboard", "ALICE");

        assertThat(store.findPage(0, 50).pullRequests())
                .extracting(PullRequest::id, PullRequest::origin)
                .containsExactlyInAnyOrder(
                        tuple(mine, PullRequest.Origin.AUTHORED),
                        tuple(someoneElses, PullRequest.Origin.OTHER));
    }

    @Test
    void updatesByRepositoryAndNumberWhenGitHubApisReturnDifferentIds()
    {
        // search/issues and the pulls endpoint report different ids for one
        // pull request; neither may decide the row identity.
        FetchedPullRequest searchResult = pullRequest(91, "external/project", 12);
        FetchedPullRequest pullEndpointResult = pullRequest(92, "external/project", 12);
        long first = store.saveObserved(searchResult).id();
        long second = store.saveObserved(pullEndpointResult).id();

        assertThat(second).isEqualTo(first);
        assertThat(store.findIdByRepoAndNumber("EXTERNAL/PROJECT", 12)).contains(first);
        assertThat(store.findPage(0, 50).pullRequests())
                .extracting(PullRequest::id)
                .containsExactly(first);
    }

    @Test
    void keepsSweptPullRequestsThatStillCarryViewState()
    {
        FetchedPullRequest reviewed = pullRequest(301, "owner/project", 301);
        long id = store.saveObserved(reviewed).id();
        viewStateStore.markReviewed(id, HandledAction.APPROVED, "headsha", Instant.now());
        jdbc.update("UPDATE pull_requests SET dashboard_visible = 0, workspace_visible = 0 WHERE id = ?", id);

        assertThat(store.completeDashboardSync("sweep", "alice")).doesNotContain(id);
        assertThat(store.findRefById(id)).isPresent();
    }

    @Test
    void keepsOpenPullRequestsOmittedFromLaterDiscovery()
    {
        FetchedPullRequest first = pullRequest(101, "owner/project", 101);
        FetchedPullRequest second = pullRequest(102, "owner/project", 102);
        store.saveRepositorySyncPage("owner/project", ImmutableList.of(first), "first-discovery");
        store.completeRepositorySync("owner/project", RETENTION_CUTOFF);

        store.saveRepositorySyncPage("owner/project", ImmutableList.of(second), "second-discovery");
        store.completeRepositorySync("owner/project", RETENTION_CUTOFF);

        assertThat(store.findRepositoryPage("owner/project", 0, 50).pullRequests())
                .extracting(PullRequest::number)
                .containsExactlyInAnyOrder(101, 102);
    }

    @Test
    void findsRowsOmittedFromTheCurrentRepositoryDiscovery()
    {
        FetchedPullRequest omitted = pullRequest(111, "owner/project", 111);
        FetchedPullRequest discovered = pullRequest(112, "owner/project", 112);
        store.saveRepositorySyncPage(
                "owner/project", ImmutableList.of(omitted, discovered), "first-discovery");
        store.saveRepositorySyncPage(
                "owner/project", ImmutableList.of(discovered), "current-discovery");

        assertThat(store.findRepositoryRowsMissingSync("owner/project", "current-discovery"))
                .extracting(PullRequest::number)
                .containsExactly(111);
    }

    @Test
    void expiresOnlyClosedPullRequestsInactiveBeforeTheCutoff()
    {
        FetchedPullRequest expired = pullRequest(
                201, "owner/project", 201, "closed",
                Instant.parse("2026-06-30T00:00:00Z"), Instant.parse("2026-07-01T00:00:00Z"));
        FetchedPullRequest recentlyUpdated = pullRequest(
                202, "owner/project", 202, "closed",
                Instant.parse("2026-06-30T00:00:00Z"), Instant.parse("2026-08-20T00:00:00Z"));
        FetchedPullRequest open = pullRequest(
                203, "owner/project", 203, "open", null, Instant.parse("2026-06-01T00:00:00Z"));
        FetchedPullRequest merged = pullRequest(
                204, "owner/project", 204, "merged",
                Instant.parse("2026-06-30T00:00:00Z"), Instant.parse("2026-07-01T00:00:00Z"));
        store.saveRepositorySyncPage(
                "owner/project", ImmutableList.of(expired, recentlyUpdated, open, merged), "retention");

        store.completeRepositorySync("owner/project", RETENTION_CUTOFF);

        assertThat(store.findRepositoryPage("owner/project", 0, 50).pullRequests())
                .extracting(PullRequest::number)
                .containsExactlyInAnyOrder(202, 203);
        assertThat(store.findIdByRepoAndNumber("owner/project", 201)).isEmpty();
        assertThat(store.findIdByRepoAndNumber("owner/project", 204)).isEmpty();
    }

    @Test
    void findsThePullRequestsOnOneHeadBranch()
    {
        String branch = "bytequay/task/0f1e2d3c-0000-4000-8000-000000000001";
        store.saveRepositorySyncPage("Owner/Project", ImmutableList.of(
                pullRequest(301, "Owner/Project", 301, branch),
                pullRequest(302, "Owner/Project", 302, branch),
                pullRequest(303, "Owner/Project", 303),
                pullRequest(304, "other/project", 304, branch)), "heads");

        assertThat(store.findByHeadRef("owner/project", branch))
                .extracting(PullRequest::number)
                .containsExactly(302, 301);
        assertThat(store.findByHeadRef("owner/project", "bytequay/task/none")).isEmpty();
    }

    private static FetchedPullRequest pullRequest(long githubId, String repository, int number)
    {
        return pullRequest(githubId, repository, number, "feature");
    }

    private static FetchedPullRequest pullRequest(long githubId, String repository, int number, String headRef)
    {
        return pullRequest(
                githubId, repository, number, "open", null, Instant.parse("2026-08-21T00:00:00Z"), headRef);
    }

    private static FetchedPullRequest pullRequest(
            long githubId,
            String repository,
            int number,
            String state,
            Instant closedAt,
            Instant updatedAt)
    {
        return pullRequest(githubId, repository, number, state, closedAt, updatedAt, "feature");
    }

    private static FetchedPullRequest pullRequest(
            long githubId,
            String repository,
            int number,
            String state,
            Instant closedAt,
            Instant updatedAt,
            String headRef)
    {
        return new FetchedPullRequest(
                githubId,
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
                state,
                closedAt,
                null,
                ImmutableList.of(),
                null,
                null,
                headRef);
    }

    private static Workspace workspace(String id, long repositoryId, RepoRef repository)
    {
        Instant now = Instant.parse("2026-08-21T00:00:00Z");
        return new Workspace(
                id,
                repository.fullName(),
                repositoryId,
                repository,
                Optional.empty(),
                Optional.empty(),
                repositoryId,
                repository,
                Workspace.Topology.DIRECT,
                "main",
                "https://github.com/" + repository.fullName() + ".git",
                Optional.empty(),
                Optional.empty(),
                "/tmp/workspaces/" + id + "/repository",
                Optional.empty(),
                Optional.empty(),
                0,
                now,
                now);
    }
}
