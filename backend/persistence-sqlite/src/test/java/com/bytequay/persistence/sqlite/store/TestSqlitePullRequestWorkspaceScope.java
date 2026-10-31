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
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.hosting.HostingAccount;
import com.bytequay.core.hosting.HostingInstance;
import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.hosting.HostingServer;
import com.bytequay.core.hosting.RemoteHostingAccount;
import com.bytequay.core.hosting.RemoteHostingRepository;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;

import static com.bytequay.core.hosting.HostingProvider.GITHUB;
import static com.bytequay.core.hosting.HostingPullRequestSnapshot.CONNECTED_ACCOUNT;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSqlitePullRequestWorkspaceScope
        extends AbstractTestTransactionalSqliteStore
{
    private static final Instant RETENTION_CUTOFF = Instant.parse("2026-08-01T00:00:00Z");

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

    @Autowired
    private SqliteHostingPullRequestStore hosted;

    @Autowired
    private SqliteHostingIdentityStore identities;

    @Test
    void notificationPreviewAndDiscussionTimestampSurviveOlderDiscovery()
    {
        FetchedPullRequest fetched = pullRequest(1, "owner/project", 1);
        PullRequest row = saveObserved(fetched);
        Instant replyAt = fetched.updatedAt().plusSeconds(60);
        PrActivitySummary reply = new PrActivitySummary("commented", Optional.of("reviewer"), replyAt,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of("New reply"), Optional.empty(), OptionalInt.empty());
        store.observeActivity(row.id(), Optional.of(reply), replyAt);
        saveObserved(fetched);
        store.observeActivity(row.id(), Optional.empty(), fetched.updatedAt());

        assertThat(store.findDiscussionActivityAt(row.id())).contains(replyAt);
        assertThat(store.findDiscussionActivities(Set.of(row.id(), row.id() + 1))).containsExactly(Map.entry(row.id(), replyAt));
        assertThat(store.findActivities(Set.of(row.id()))).containsEntry(row.id(), reply);
        assertThat(store.findById(row.id()).orElseThrow().activityAt()).isEqualTo(replyAt);
    }

    @Test
    void attentionActivityKeepsTheLatestAndSurvivesDiscovery()
    {
        FetchedPullRequest fetched = pullRequest(1, "owner/project", 1);
        PullRequest row = saveObserved(fetched);
        Instant commentAt = fetched.updatedAt().plusSeconds(60);
        assertThat(store.findAttentionActivities(ImmutableSet.of(row.id()))).isEmpty();

        store.observeAttentionActivity(row.id(), commentAt);
        store.observeAttentionActivity(row.id(), fetched.updatedAt());
        saveObserved(fetched);

        assertThat(store.findAttentionActivities(ImmutableSet.of(row.id()))).containsExactly(Map.entry(row.id(), commentAt));
    }

    @Test
    void monitorSurvivesSyncAndDisablesWithoutChangingActivity()
    {
        FetchedPullRequest fetched = pullRequest(1, "owner/project", 1, "closed", RETENTION_CUTOFF.minusSeconds(1), RETENTION_CUTOFF.minusSeconds(1))
                .withOrigin(PullRequest.Origin.OTHER);
        PullRequest row = saveObserved(fetched);
        store.setMonitored(row.id(), true);
        Instant baseline = store.findById(row.id()).orElseThrow().monitoredAt();
        saveDashboardPage(ImmutableList.of(fetched));

        assertThat(store.findById(row.id()).orElseThrow().monitoredAt()).isEqualTo(baseline);
        assertThat(store.findPage(0, 50).pullRequests()).extracting(PullRequest::id).contains(row.id());
        assertThat(store.findAttentionCandidates()).extracting(PullRequest::id).contains(row.id());
        store.setMonitored(row.id(), false);
        assertThat(store.findById(row.id()).orElseThrow().monitoredAt()).isNull();
        assertThat(store.findById(row.id()).orElseThrow().activityAt()).isEqualTo(fetched.updatedAt());
    }

    @Test
    void mergeDiscoveryClearsMonitoringAndCannotBeMonitoredAgain()
    {
        FetchedPullRequest fetched = pullRequest(1, "owner/project", 1);
        PullRequest row = saveObserved(fetched);
        store.setMonitored(row.id(), true);

        saveObserved(pullRequest(1, "owner/project", 1, "merged", fetched.updatedAt(), fetched.updatedAt()));
        entityManager.flush();
        entityManager.clear();

        assertThat(store.findById(row.id()).orElseThrow().monitoredAt()).isNull();
        assertThatThrownBy(() -> store.setMonitored(row.id(), true)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void detailMergeClearsMonitoring()
    {
        FetchedPullRequest fetched = pullRequest(1, "owner/project", 1);
        PullRequest row = saveObserved(fetched);
        store.setMonitored(row.id(), true);
        Instant timestamp = fetched.updatedAt().plusSeconds(60);

        store.updateDetails(row.id(), new PrRawDetail(
                "title", "url", "closed", "alice", null, timestamp, timestamp, timestamp, "body",
                ImmutableList.of(), ImmutableMap.of(), ImmutableList.of(), null, false, null, null,
                0, 0, 0, 0, ImmutableList.of(), "head", "feature", "main"));
        entityManager.flush();
        entityManager.clear();

        assertThat(store.findById(row.id()).orElseThrow().state()).isEqualTo("merged");
        assertThat(store.findById(row.id()).orElseThrow().monitoredAt()).isNull();
    }

    @Test
    void activityChangesOnlyWhenObservedGithubFactsChange()
    {
        PullRequest row = saveObserved(pullRequest(1, "owner/project", 1));
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
        PullRequest row = saveObserved(pullRequest(1, "owner/project", 1));
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
        PullRequest row = saveObserved(fetched);
        store.updateEnrichment(row.id(), row.updatedAt(), PullRequestDetail.CiStatus.FAILING, 1, 0, 4, null, "unknown", null, "feature",
                ImmutableList.of(), PullRequest.Origin.AUTHORED, Optional.empty(), Optional.of("APPROVED"), ImmutableMap.of("summary", "summary-v1"));
        // Detail ignores an old failed check after a successful rerun; the summary rollup can still include it.
        store.updateEnrichment(row.id(), row.updatedAt(), PullRequestDetail.CiStatus.PASSING, 1, 0, 4, null, "unknown", null, "feature",
                ImmutableList.of(), PullRequest.Origin.AUTHORED, Optional.empty(), Optional.of("APPROVED"), ImmutableMap.of("checks", "checks-v1"));
        PullRequest acknowledged = store.findById(row.id()).orElseThrow();

        for (int refresh = 0; refresh < 3; refresh++) {
            entityManager.flush();
            entityManager.clear();
            saveObserved(fetched);
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
    void mergedRepositoryPullRequestsAreNewestMergeFirstAndSearchable()
    {
        workspaceStore.createOrGet(workspace("current", 100, RepoRef.of("owner", "project")));
        Instant older = Instant.parse("2026-08-10T00:00:00Z");
        Instant newer = Instant.parse("2026-08-12T00:00:00Z");
        saveRepositoryPage(ImmutableList.of(
                merged(11L, 11, "Fix the parquet reader", older),
                merged(12L, 12, "Retry S3 reads", newer),
                pullRequest(13L, "owner/project", 13)));

        assertThat(store.findMergedRepositoryPullRequests("OWNER/project", "", 50)).extracting(PullRequest::number).containsExactly(12, 11);
        assertThat(store.findMergedRepositoryPullRequests("owner/project", "parquet", 50)).extracting(PullRequest::number).containsExactly(11);
        assertThat(store.findMergedRepositoryPullRequests("owner/project", "#12", 50)).extracting(PullRequest::number).containsExactly(12);
        assertThat(store.findMergedRepositoryPullRequests("owner/project", "13", 50)).isEmpty();
        assertThat(store.findMergedRepositoryPullRequests("owner/project", "", 1)).extracting(PullRequest::number).containsExactly(12);
    }

    private static FetchedPullRequest merged(long remoteId, int number, String title, Instant mergedAt)
    {
        return new FetchedPullRequest(
                remoteId, "owner/project", number, title, "alice", "https://github.com/owner/project/pull/" + number, mergedAt, mergedAt, PullRequest.Origin.OTHER,
                ImmutableList.of(), ImmutableMap.of(), false, "closed", mergedAt, mergedAt, ImmutableList.of(), null, null, "feature-" + number);
    }

    @Test
    void labelOrderingAndEquivalentMergedStatesDoNotCreateActivity()
    {
        Instant timestamp = Instant.parse("2026-08-20T00:00:00Z");
        FetchedPullRequest fetched = new FetchedPullRequest(
                1L, "owner/project", 1, "title", "alice", "url", timestamp, timestamp, PullRequest.Origin.AUTHORED,
                ImmutableList.of("a", "b"), ImmutableMap.of(), false, "closed", timestamp, timestamp, ImmutableList.of(), null, null, "feature");
        PullRequest row = saveObserved(fetched);
        store.updateDetails(row.id(), new PrRawDetail(
                "title", "url", "merged", "alice", null, timestamp, timestamp, timestamp, "body",
                ImmutableList.of("b", "a"), ImmutableMap.of(), ImmutableList.of(), null, false, null, null,
                0, 0, 0, 0, ImmutableList.of(), "head", "feature", "main"));
        saveObserved(new FetchedPullRequest(
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
        PullRequest row = saveObserved(pullRequest(1, "owner/project", 1, "closed", closedAt, closedAt));
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

        saveRepositoryPage(ImmutableList.of(new FetchedPullRequest(
                1L, "owner/project", 1, "Updated title", "alice", row.htmlUrl(), row.createdAt(), updatedAt, PullRequest.Origin.AUTHORED,
                ImmutableList.of("java"), ImmutableMap.of("java", "abcdef"), true, "closed", closedAt, null, ImmutableList.of(), null, null, "feature")));

        assertThat(store.findById(row.id()).orElseThrow().activityAt()).isEqualTo(baseline.activityAt());
    }

    @Test
    void staleObservedAndScanRowsPreserveNewerFacts()
    {
        Instant freshTime = Instant.parse("2026-08-22T00:00:00Z");
        PullRequest fresh = saveObserved(new FetchedPullRequest(
                1L, "owner/project", 1, "Current title", "alice", "url", freshTime, freshTime, PullRequest.Origin.REVIEW_REQUESTED,
                ImmutableList.of("current"), ImmutableMap.of("current", "abcdef"), true, "closed", freshTime, null, ImmutableList.of("bob"), null, null, "feature"));
        FetchedPullRequest stale = pullRequest(2, "owner/project", 1, "open", null, freshTime.minusSeconds(60)).withOrigin(PullRequest.Origin.OTHER);

        assertThat(saveObserved(stale)).isEqualTo(fresh);
        assertThat(saveDashboardPage(ImmutableList.of(stale))).containsExactly(fresh);
        assertThat(saveRepositoryPage(ImmutableList.of(stale))).containsExactly(fresh);

        assertThat(store.findById(fresh.id())).contains(fresh);
    }

    @Test
    void staleDetailCannotRegressANewerSummaryOrCreateActivity()
    {
        Instant freshTime = Instant.parse("2026-08-22T00:00:00Z");
        PullRequest fresh = saveObserved(pullRequest(1, "owner/project", 1, "closed", freshTime, freshTime));
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
        PullRequest previous = saveObserved(pullRequest(1, "owner/project", 1));

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
        PullRequest fresh = saveObserved(pullRequest(1, "owner/project", 1, "open", null, freshTime).withOrigin(PullRequest.Origin.REVIEW_REQUESTED));
        store.updateEnrichment(fresh.id(), freshTime, PullRequestDetail.CiStatus.PENDING, 1, 0, 2, true, "clean", null, "feature",
                ImmutableList.of("bob"), PullRequest.Origin.REVIEW_REQUESTED, Optional.empty(), Optional.empty(), ImmutableMap.of("raw", "current"));
        PullRequest baseline = store.findById(fresh.id()).orElseThrow();

        store.updateEnrichment(fresh.id(), freshTime.minusSeconds(60), PullRequestDetail.CiStatus.FAILING, 0, 0, 0, false, "blocked", null, "old-feature",
                ImmutableList.of(), PullRequest.Origin.OTHER, Optional.empty(), Optional.of("APPROVED"), ImmutableMap.of("raw", "stale"));

        assertThat(store.findById(fresh.id())).contains(baseline);
    }

    @Test
    void mergeabilityGitHubHasNotComputedKeepsTheLastKnownAnswer()
    {
        FetchedPullRequest fetched = pullRequest(1, "owner/project", 1);
        PullRequest row = saveObserved(fetched);
        store.updateEnrichment(row.id(), row.updatedAt(), PullRequestDetail.CiStatus.PASSING, 1, 0, 0, true, "clean", null, "feature",
                ImmutableList.of(), PullRequest.Origin.AUTHORED, Optional.empty(), Optional.of("APPROVED"), ImmutableMap.of("summary", "clean"));

        store.updateEnrichment(row.id(), row.updatedAt(), PullRequestDetail.CiStatus.PASSING, 1, 0, 0, null, "unknown", null, "feature",
                ImmutableList.of(), PullRequest.Origin.AUTHORED, Optional.empty(), Optional.of("APPROVED"), ImmutableMap.of("summary", "unknown"));
        assertThat(store.findById(row.id()).orElseThrow()).extracting(PullRequest::mergeable, PullRequest::mergeableState).containsExactly(true, "clean");

        saveObserved(new FetchedPullRequest(fetched.remoteId(), fetched.repo(), fetched.number(), fetched.title(), fetched.author(), fetched.htmlUrl(),
                fetched.createdAt(), fetched.updatedAt(), fetched.origin(), fetched.labels(), fetched.labelColors(), false, "open", null, null,
                ImmutableList.of(), null, "unknown", "feature"));
        assertThat(store.findById(row.id()).orElseThrow()).extracting(PullRequest::mergeable, PullRequest::mergeableState).containsExactly(true, "clean");

        store.updateEnrichment(row.id(), row.updatedAt(), PullRequestDetail.CiStatus.PASSING, 1, 0, 0, false, "dirty", null, "feature",
                ImmutableList.of(), PullRequest.Origin.AUTHORED, Optional.empty(), Optional.of("APPROVED"), ImmutableMap.of("summary", "dirty"));
        assertThat(store.findById(row.id()).orElseThrow()).extracting(PullRequest::mergeable, PullRequest::mergeableState).containsExactly(false, "dirty");
    }

    @Test
    void newReviewRequestsAdvanceTheirTimestampAndSearchDoesNotInventRequests()
    {
        FetchedPullRequest fetched = pullRequest(1, "owner/project", 1);
        PullRequest row = saveObserved(fetched.withOrigin(PullRequest.Origin.REVIEW_REQUESTED));
        Instant firstRequestedAt = row.reviewRequestedAt();
        saveObserved(fetched.withOrigin(PullRequest.Origin.OTHER));
        Instant reviewedAt = fetched.updatedAt().plusSeconds(60);
        viewStateStore.markReviewed(row.id(), HandledAction.APPROVED, "head", reviewedAt);
        fetched = pullRequest(1, "owner/project", 1, "open", null, reviewedAt.plusSeconds(60));
        saveObserved(fetched.withOrigin(PullRequest.Origin.REVIEW_REQUESTED));
        Instant requestedAgainAt = store.findById(row.id()).orElseThrow().reviewRequestedAt();
        assertThat(requestedAgainAt).isAfter(reviewedAt);

        FetchedPullRequest searchRow = pullRequest(1, "owner/project", 1, "open", null, fetched.updatedAt(), null).withOrigin(PullRequest.Origin.OTHER);
        saveObserved(searchRow);
        saveObserved(searchRow);
        assertThat(store.findById(row.id()).orElseThrow().origin()).isEqualTo(PullRequest.Origin.REVIEW_REQUESTED);
        assertThat(store.findById(row.id()).orElseThrow().reviewRequestedAt()).isEqualTo(requestedAgainAt);
        saveRepositoryPage(ImmutableList.of(searchRow));
        saveRepositoryPage(ImmutableList.of(searchRow.withOrigin(PullRequest.Origin.REVIEW_REQUESTED)));
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
        saveRepositoryPage(rows.build());
        PullRequest unrelated = saveObserved(pullRequest(56, "owner/project", 56).withOrigin(PullRequest.Origin.OTHER));
        PullRequest reviewed = saveObserved(pullRequest(57, "owner/project", 57).withOrigin(PullRequest.Origin.OTHER));
        Instant reviewedAt = Instant.parse("2026-08-01T00:00:00Z");
        viewStateStore.markReviewed(reviewed.id(), HandledAction.APPROVED, "head", reviewedAt);
        PullRequest requested = saveObserved(pullRequest(58, "owner/project", 58).withOrigin(PullRequest.Origin.REVIEW_REQUESTED));
        saveObserved(pullRequest(58, "owner/project", 58).withOrigin(PullRequest.Origin.OTHER));

        assertThat(store.findAttentionCandidates()).hasSize(58).extracting(PullRequest::id).contains(unrelated.id());
        assertThat(store.findById(requested.id()).orElseThrow().reviewRequestedAt()).isNotNull();
        assertThat(store.findById(reviewed.id()).orElseThrow().reviewedAt()).isEqualTo(reviewedAt);
    }

    @Test
    void attentionIncludesOrdinaryWatchedRepositoryUpdatesWithoutADetailSnapshot()
    {
        Workspace workspace = workspace("updates", 100, RepoRef.of("owner", "project"));
        workspaceStore.createOrGet(workspace);
        PullRequest watched = saveRepositoryPage(ImmutableList.of(
                pullRequest(1, "owner/project", 1).withOrigin(PullRequest.Origin.OTHER))).getFirst();
        saveRepositoryPage(ImmutableList.of(pullRequest(2, "unwatched/project", 2).withOrigin(PullRequest.Origin.OTHER)));

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

        saveDashboardPage(ImmutableList.of(global, shared));
        saveRepositoryPage(ImmutableList.of(shared, workspaceOnly));
        saveRepositoryPage(ImmutableList.of(otherWorkspace));

        assertThat(store.findPage(0, 50).pullRequests())
                .extracting(PullRequest::id)
                .containsExactlyInAnyOrder(1L, 2L, 3L);
        assertThat(store.findPage(0, 50).openPullRequests()).isEqualTo(3);

        saveDashboardPage(ImmutableList.of(global));

        assertThat(store.findPage(0, 50).pullRequests())
                .extracting(PullRequest::id)
                .containsExactlyInAnyOrder(1L, 2L, 3L);

        workspaceStore.setWatched(workspace.id(), false);

        assertThat(store.findPage(0, 50).pullRequests())
                .extracting(PullRequest::id)
                .containsExactlyInAnyOrder(1L, 2L);
        assertThat(store.findPage(0, 50).openPullRequests()).isEqualTo(2);

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
        saveDashboardPage(pullRequests.build());

        var first = store.findPage(0, 50);
        var second = store.findPage(1, 50);

        assertThat(first.pullRequests()).hasSize(50);
        assertThat(first.totalPullRequests()).isEqualTo(55);
        assertThat(first.hasMore()).isTrue();
        assertThat(second.pullRequests()).hasSize(5);
        assertThat(second.hasMore()).isFalse();
    }

    @Test
    void storesDirectlyOpenedPullRequestInTheLocalDashboard()
    {
        FetchedPullRequest observed = pullRequest(91, "external/project", 12);

        PullRequest stored = saveObserved(observed);

        assertThat(store.findIdByRepoAndNumber("external/project", 12)).contains(stored.id());
        assertThat(store.findPage(0, 50).pullRequests()).containsExactly(stored);
    }

    @Test
    void updatesByRepositoryAndNumberWhenGitHubApisReturnDifferentIds()
    {
        // search/issues and the pulls endpoint report different ids for one
        // pull request; neither may decide the row identity.
        FetchedPullRequest searchResult = pullRequest(91, "external/project", 12);
        FetchedPullRequest pullEndpointResult = pullRequest(92, "external/project", 12);
        long first = saveObserved(searchResult).id();
        long second = saveObserved(pullEndpointResult).id();

        assertThat(second).isEqualTo(first);
        assertThat(store.findIdByRepoAndNumber("EXTERNAL/PROJECT", 12)).contains(first);
        assertThat(store.findPage(0, 50).pullRequests())
                .extracting(PullRequest::id)
                .containsExactly(first);
    }

    @Test
    void keepsOpenPullRequestsOmittedFromLaterDiscovery()
    {
        FetchedPullRequest first = pullRequest(101, "owner/project", 101);
        FetchedPullRequest second = pullRequest(102, "owner/project", 102);
        saveRepositoryPage(ImmutableList.of(first));

        saveRepositoryPage(ImmutableList.of(second));

        assertThat(store.findIdByRepoAndNumber("owner/project", 101)).isPresent();
        assertThat(store.findIdByRepoAndNumber("owner/project", 102)).isPresent();
    }

    @Test
    void findsThePullRequestsOnOneHeadBranch()
    {
        String branch = "bytequay/task/0f1e2d3c-0000-4000-8000-000000000001";
        saveRepositoryPage(ImmutableList.of(
                pullRequest(301, "Owner/Project", 301, branch),
                pullRequest(302, "Owner/Project", 302, branch),
                pullRequest(303, "Owner/Project", 303),
                pullRequest(304, "other/project", 304, branch)));

        assertThat(store.findByHeadRef("owner/project", branch))
                .extracting(PullRequest::number)
                .containsExactly(302, 301);
        assertThat(store.findByHeadRef("owner/project", "bytequay/task/none")).isEmpty();
    }

    /** Discovery stores the summary under its repository and shows it on the dashboard. */
    private PullRequest saveObserved(FetchedPullRequest pullRequest)
    {
        PullRequest row = saveListed(pullRequest);
        store.setDashboardVisible(row.id(), true);
        return store.findById(row.id()).orElseThrow();
    }

    private List<PullRequest> saveDashboardPage(List<FetchedPullRequest> pullRequests)
    {
        return pullRequests.stream().map(this::saveObserved).collect(toImmutableList());
    }

    /** A repository listing stores summaries without showing them on the dashboard. */
    private List<PullRequest> saveRepositoryPage(List<FetchedPullRequest> pullRequests)
    {
        return pullRequests.stream().map(this::saveListed).collect(toImmutableList());
    }

    private PullRequest saveListed(FetchedPullRequest pullRequest)
    {
        HostingInstance instance = identities.upsertInstance(new HostingServer(GITHUB, URI.create("https://github.com"), URI.create("https://api.github.com")));
        HostingAccount account = identities.upsertAccount(instance.id(), new RemoteHostingAccount("1", "alice"));
        String path = pullRequest.repo();
        HostingRepository repository = identities.upsertRepository(instance.id(), new RemoteHostingRepository(path.toLowerCase(Locale.ROOT), path,
                URI.create("https://github.com/" + path), URI.create("https://github.com/" + path + ".git"), Optional.empty(), Optional.of("main")));
        hosted.savePage(account.id(), repository.id(), CONNECTED_ACCOUNT, ImmutableList.of(pullRequest));
        return store.findByHostingRepository(repository.id(), pullRequest.number()).orElseThrow();
    }

    private static FetchedPullRequest pullRequest(long remoteId, String repository, int number)
    {
        return pullRequest(remoteId, repository, number, "feature");
    }

    private static FetchedPullRequest pullRequest(long remoteId, String repository, int number, String headRef)
    {
        return pullRequest(remoteId, repository, number, "open", null, Instant.parse("2026-08-21T00:00:00Z"), headRef);
    }

    private static FetchedPullRequest pullRequest(long remoteId, String repository, int number, String state, Instant closedAt, Instant updatedAt)
    {
        return pullRequest(remoteId, repository, number, state, closedAt, updatedAt, "feature");
    }

    private static FetchedPullRequest pullRequest(long remoteId, String repository, int number, String state, Instant closedAt, Instant updatedAt, String headRef)
    {
        return new FetchedPullRequest(
                remoteId,
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
