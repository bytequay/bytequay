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
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.hosting.HostingAccount;
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingInstance;
import com.bytequay.core.hosting.HostingProvider;
import com.bytequay.core.hosting.HostingPullRequestSnapshot;
import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.hosting.HostingServer;
import com.bytequay.core.hosting.PullRequestListPage;
import com.bytequay.core.hosting.RemoteHostingAccount;
import com.bytequay.core.hosting.RemoteHostingRepository;
import com.bytequay.core.review.ReviewDraft;
import com.bytequay.persistence.sqlite.entity.PullRequestEntity;
import com.bytequay.persistence.sqlite.repository.HostingPullRequestSnapshotJpaRepository;
import com.bytequay.persistence.sqlite.repository.PullRequestJpaRepository;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.orm.jpa.JpaSystemException;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;

import static com.bytequay.core.hosting.HostingFailure.Kind.INVALID_RESPONSE;
import static com.bytequay.core.hosting.HostingProvider.GITHUB;
import static com.bytequay.core.hosting.HostingProvider.GITLAB;
import static com.bytequay.core.hosting.HostingPullRequestSnapshot.CONNECTED_ACCOUNT;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestSqliteHostingPullRequestStore
        extends AbstractTestTransactionalSqliteStore
{
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @Autowired
    private SqliteHostingPullRequestStore store;

    @Autowired
    private SqliteHostingIdentityStore identities;

    @Autowired
    private SqlitePullRequestStore desktop;

    @Autowired
    private SqlitePrViewStateStore views;

    @Autowired
    private SqlitePrDetailStore details;

    @Autowired
    private SqliteReviewDraftStore drafts;

    @Autowired
    private PullRequestJpaRepository requests;

    @Autowired
    private HostingPullRequestSnapshotJpaRepository snapshots;

    @Autowired
    private EntityManager entities;

    @ParameterizedTest
    @EnumSource(HostingProvider.class)
    void roundTripsTypedMetadataAndUnknownCounts(HostingProvider provider)
    {
        Scope scope = scope(provider, provider == GITHUB ? "https://github.com" : "https://gitlab.com");
        FetchedPullRequest fetched = pullRequest(scope, 42, "Request title");
        PrRawDetail detail = detail(fetched, "Private description");
        HostingPullRequestSnapshot saved = store.save(scope.account().id(), scope.repository().id(), CONNECTED_ACCOUNT, fetched, detail, Optional.of("\"metadata\""), NOW);
        entities.flush();
        entities.clear();

        assertThat(store.find(scope.account().id(), scope.repository().id(), 7)).contains(saved);
        assertThat(saved.pullRequest()).isEqualTo(fetched);
        assertThat(saved.detail()).isEqualTo(detail);
        assertThat(saved.detail().requestedReviewerCount()).isEqualTo(PrRawDetail.UNKNOWN_COUNT);
        assertThat(saved.detail().changedFiles()).isEqualTo(PrRawDetail.UNKNOWN_COUNT);
        assertThat(saved.request().remoteId()).isEqualTo("42");
        PullRequestEntity retained = requests.findById(saved.request().id()).orElseThrow();
        assertThat(retained.getRepo()).isEqualTo("Team/Project");
        assertThat(retained.getDashboardVisible()).isFalse();
        assertThat(retained.getWorkspaceVisible()).isFalse();
        assertThat(requests.count()).isEqualTo(1);
        assertThat(snapshots.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(HostingProvider.class)
    void pagesSummariesByLastUpdateWithoutCreatingDetailSnapshots(HostingProvider provider)
    {
        Scope scope = scope(provider, provider == GITHUB ? "https://github.com" : "https://gitlab.com");
        FetchedPullRequest newest = pullRequest(scope, 42, 7, "Newest", NOW);
        FetchedPullRequest middle = pullRequest(scope, 43, 8, "Middle", NOW.minusSeconds(10));
        FetchedPullRequest oldest = pullRequest(scope, 44, 9, "Oldest", NOW.minusSeconds(20));
        store.savePage(scope.account().id(), scope.repository().id(), CONNECTED_ACCOUNT, ImmutableList.of(oldest, newest));
        store.savePage(scope.account().id(), scope.repository().id(), CONNECTED_ACCOUNT, ImmutableList.of(middle));
        entities.flush();
        entities.clear();

        PullRequestListPage first = store.findPage(scope.account().id(), scope.repository().id(), CONNECTED_ACCOUNT, 1, 2);
        PullRequestListPage last = store.findPage(scope.account().id(), scope.repository().id(), CONNECTED_ACCOUNT, 2, 2);
        assertThat(first.items()).containsExactly(newest, middle);
        assertThat(first.hasNext()).isTrue();
        assertThat(last.items()).containsExactly(oldest);
        assertThat(last.hasNext()).isFalse();
        assertThat(store.find(scope.account().id(), scope.repository().id(), 7)).isEmpty();
        assertThat(requests.count()).isEqualTo(3);
        assertThat(snapshots.count()).isEqualTo(3);

        HostingPullRequestSnapshot fetched = store.save(scope.account().id(), scope.repository().id(), CONNECTED_ACCOUNT,
                newest, detail(newest, "Full description"), Optional.of("\"detail\""), NOW);
        entities.flush();
        entities.clear();
        assertThat(store.find(scope.account().id(), scope.repository().id(), 7)).contains(fetched);
        assertThat(requests.count()).isEqualTo(3);
        assertThat(snapshots.count()).isEqualTo(3);
    }

    @Test
    void retainsApprovalSnapshotsWithoutRemoteReviewIdsWhenTheirSourceIsUnavailable()
    {
        Scope scope = scope(GITLAB, "https://gitlab.com");
        HostingPullRequestSnapshot snapshot = save(scope, "Request", "Body");
        long id = snapshot.request().id();
        var approvals = ImmutableList.of(
                new PrReviewState(null, "alice", null, false, "APPROVED", null, null, null, null),
                new PrReviewState(null, "bob", null, false, "APPROVED", null, null, null, null));
        StoredPrDetail complete = new StoredPrDetail(snapshot.detail(), approvals, ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), true);
        details.saveDiscussion(id, complete, NOW);
        entities.flush();
        entities.clear();
        details.saveDiscussion(id, new StoredPrDetail(snapshot.detail(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                ImmutableList.of(StoredPrDetail.REVIEWS), true), NOW);
        entities.flush();
        entities.clear();

        assertThat(details.find(id).orElseThrow().reviews()).containsExactlyInAnyOrderElementsOf(approvals);
        assertThat(details.find(id).orElseThrow().reviewsComplete()).isFalse();
        details.saveDiscussion(id, complete, NOW);
        entities.flush();
        entities.clear();
        assertThat(details.find(id).orElseThrow().reviews()).containsExactlyInAnyOrderElementsOf(approvals);
    }

    @Test
    void hydratesAnIdentityOnlyRowFromItsCachedSummaryWithoutLosingLocalState()
    {
        Scope scope = scope(GITLAB, "https://gitlab.com");
        HostingPullRequestSnapshot snapshot = save(scope, "Cached title", "Body");
        long id = snapshot.request().id();
        PullRequestEntity row = requests.findById(id).orElseThrow();
        row.setTitle("");
        row.setAuthor(null);
        row.setCreatedAt(null);
        row.setUpdatedAt(null);
        row.setState(null);
        row.setOrigin(PullRequest.Origin.OTHER.name());
        row.setLabels(ImmutableList.of());
        row.setMonitoredAt(NOW);
        row.setCiStatus(PullRequestDetail.CiStatus.FAILING);
        row.setAdditions(5);
        views.markViewed(id);
        entities.flush();
        entities.clear();

        assertThat(desktop.findByHostingRepository(scope.repository().id(), 7)).isEmpty();
        FetchedPullRequest cached = store.findPage(scope.account().id(), scope.repository().id(), CONNECTED_ACCOUNT, 1, 100).items().getFirst();
        PullRequest hydrated = desktop.saveHosted(scope.repository().id(), cached);
        entities.flush();
        entities.clear();

        assertThat(hydrated.id()).isEqualTo(id);
        assertThat(hydrated.title()).isEqualTo("Cached title");
        assertThat(hydrated.author()).isEqualTo(cached.author());
        assertThat(hydrated.updatedAt()).isEqualTo(cached.updatedAt());
        assertThat(hydrated.viewedAt()).isNotNull();
        assertThat(hydrated.monitoredAt()).isEqualTo(NOW);
        assertThat(hydrated.ciStatus()).isEqualTo(PullRequestDetail.CiStatus.FAILING);
        assertThat(hydrated.additions()).isEqualTo(5);
        assertThat(desktop.findById(id)).contains(hydrated);
        assertThat(desktop.findByHostingRepository(scope.repository().id(), 7)).contains(hydrated);
        assertThat(requests.count()).isEqualTo(1);
        assertThat(store.find(scope.account().id(), scope.repository().id(), 7)).contains(snapshot);
    }

    @Test
    void listRefreshPreservesMetadataUntilAnotherCredentialSourceReplacesIt()
    {
        Scope scope = scope(GITLAB, "https://git.company.com/gitlab");
        HostingPullRequestSnapshot original = save(scope, "Original", "Full description");
        FetchedPullRequest summary = pullRequest(scope, 42, "Updated summary");
        store.savePage(scope.account().id(), scope.repository().id(), CONNECTED_ACCOUNT, ImmutableList.of(summary));
        entities.flush();
        entities.clear();

        HostingPullRequestSnapshot updated = store.find(scope.account().id(), scope.repository().id(), 7).orElseThrow();
        assertThat(updated.request()).isEqualTo(original.request());
        assertThat(updated.pullRequest()).isEqualTo(summary);
        assertThat(updated.detail()).isEqualTo(original.detail());
        assertThat(updated.etag()).isEqualTo(original.etag());
        assertThat(updated.validatedAt()).isEqualTo(original.validatedAt());

        store.savePage(scope.account().id(), scope.repository().id(), "workspace-token", ImmutableList.of(summary));
        entities.flush();
        entities.clear();
        assertThat(store.find(scope.account().id(), scope.repository().id(), 7)).isEmpty();
        assertThat(store.findPage(scope.account().id(), scope.repository().id(), CONNECTED_ACCOUNT, 1, 100).items()).isEmpty();
        assertThat(store.findPage(scope.account().id(), scope.repository().id(), "workspace-token", 1, 100).items()).containsExactly(summary);
        assertThat(requests.count()).isEqualTo(1);
        assertThat(snapshots.count()).isEqualTo(1);
    }

    @Test
    void listPagesAreScopedToAccountRepositoryAndCredentialSource()
    {
        Scope first = scope(GITLAB, "https://gitlab.com");
        Scope second = new Scope(first.instance(), identities.upsertAccount(first.instance().id(), new RemoteHostingAccount("2", "bob")), first.repository());
        Scope otherHost = scope(GITLAB, "https://git.company.com");
        Scope github = scope(GITHUB, "https://github.com");
        for (Scope scope : ImmutableList.of(first, second, otherHost, github)) {
            store.savePage(scope.account().id(), scope.repository().id(), CONNECTED_ACCOUNT,
                    ImmutableList.of(pullRequest(scope, 42, scope.account().login() + " on " + scope.instance().webBaseUrl())));
        }
        entities.flush();
        entities.clear();

        for (Scope scope : ImmutableList.of(first, second, otherHost, github)) {
            assertThat(store.findPage(scope.account().id(), scope.repository().id(), CONNECTED_ACCOUNT, 1, 100).items())
                    .containsExactly(pullRequest(scope, 42, scope.account().login() + " on " + scope.instance().webBaseUrl()));
            assertThat(store.findPage(scope.account().id(), scope.repository().id(), "other-token", 1, 100).items()).isEmpty();
        }
        assertThat(requests.count()).isEqualTo(3);
        assertThat(snapshots.count()).isEqualTo(4);
        assertThatIllegalArgumentException().isThrownBy(() -> store.findPage(first.account().id(), otherHost.repository().id(), CONNECTED_ACCOUNT, 1, 100));
        assertThatIllegalArgumentException().isThrownBy(() -> store.savePage(first.account().id(), otherHost.repository().id(), CONNECTED_ACCOUNT, ImmutableList.of()));
    }

    @Test
    void isolatesSameRemoteIdsAcrossProvidersAndServers()
    {
        Scope github = scope(GITHUB, "https://github.com");
        Scope gitlab = scope(GITLAB, "https://gitlab.com");
        Scope company = scope(GITLAB, "https://git.company.com/engineering");
        HostingPullRequestSnapshot first = save(github, "GitHub", "github-body");
        HostingPullRequestSnapshot second = save(gitlab, "GitLab", "gitlab-body");
        HostingPullRequestSnapshot third = save(company, "Company", "company-body");
        entities.flush();
        entities.clear();

        assertThat(ImmutableSet.of(first.request().id(), second.request().id(), third.request().id())).hasSize(3);
        assertThat(store.find(github.account().id(), github.repository().id(), 7)).contains(first);
        assertThat(store.find(gitlab.account().id(), gitlab.repository().id(), 7)).contains(second);
        assertThat(store.find(company.account().id(), company.repository().id(), 7)).contains(third);
        assertThat(requests.count()).isEqualTo(3);
    }

    @Test
    void sharesRequestIdentityButIsolatesAccountSnapshotsAndValidators()
    {
        Scope first = scope(GITLAB, "https://gitlab.com");
        Scope second = new Scope(first.instance(), identities.upsertAccount(first.instance().id(), new RemoteHostingAccount("2", "bob")), first.repository());
        HostingPullRequestSnapshot alice = save(first, "Alice metadata", "alice-body");
        assertThat(store.find(second.account().id(), second.repository().id(), 7)).isEmpty();
        HostingPullRequestSnapshot bob = save(second, "Bob metadata", "bob-body");
        entities.flush();
        entities.clear();

        assertThat(bob.request()).isEqualTo(alice.request());
        assertThat(store.find(first.account().id(), first.repository().id(), 7)).contains(alice);
        assertThat(store.find(second.account().id(), second.repository().id(), 7)).contains(bob);
        assertThat(alice.etag()).contains("\"Alice metadata\"");
        assertThat(bob.etag()).contains("\"Bob metadata\"");
        assertThat(requests.count()).isEqualTo(1);
        assertThat(snapshots.count()).isEqualTo(2);
    }

    @Test
    void keepsIdentityAcrossNestedProjectRenames()
    {
        Scope scope = scope(GITLAB, "https://git.company.com/gitlab");
        HostingPullRequestSnapshot original = save(scope, "Before rename", "body");
        HostingRepository renamed = repository(scope.instance(), "NewGroup/Subgroup/NewProject");
        HostingPullRequestSnapshot updated = save(new Scope(scope.instance(), scope.account(), renamed), "After rename", "new-body");
        entities.flush();
        entities.clear();

        assertThat(updated.request()).isEqualTo(original.request());
        assertThat(updated.pullRequest().repo()).isEqualTo("NewGroup/Subgroup/NewProject");
        assertThat(requests.findById(updated.request().id()).orElseThrow().getRepo()).isEqualTo("NewGroup/Subgroup/NewProject");
        assertThat(store.find(scope.account().id(), scope.repository().id(), 7)).contains(updated);
        assertThat(snapshots.count()).isEqualTo(1);
    }

    @Test
    void neverAttachesToLegacyRowsOrTransfersTheirLocalState()
    {
        Scope scope = scope(GITHUB, "https://github.com");
        FetchedPullRequest legacyMetadata = pullRequest(scope, 99, "Legacy title");
        PullRequestEntity legacy = requests.saveAndFlush(PullRequestEntity.from(legacyMetadata, NOW));
        views.markViewed(legacy.getId());
        ReviewDraft draft = drafts.save(ReviewDraft.empty("saved-draft", legacyMetadata.repo(), legacyMetadata.number(), NOW));
        HostingPullRequestSnapshot hosted = save(scope, "Hosted title", "new-body");
        entities.flush();
        entities.clear();

        assertThat(hosted.request().id()).isNotEqualTo(legacy.getId());
        PullRequestEntity retained = requests.findById(legacy.getId()).orElseThrow();
        assertThat(retained.getHostingRepository()).isNull();
        assertThat(retained.getTitle()).isEqualTo("Legacy title");
        assertThat(views.findByPrIds(ImmutableSet.of(legacy.getId(), hosted.request().id()))).containsOnlyKeys(legacy.getId());
        assertThat(drafts.find(draft.id())).contains(draft);
        assertThat(requests.count()).isEqualTo(2);
    }

    @Test
    void evictionRetainsIdentityLocalViewStateAndOtherAccounts()
    {
        Scope first = scope(GITLAB, "https://gitlab.com");
        Scope second = new Scope(first.instance(), identities.upsertAccount(first.instance().id(), new RemoteHostingAccount("2", "bob")), first.repository());
        HostingPullRequestSnapshot alice = save(first, "Alice", "alice-body");
        HostingPullRequestSnapshot bob = save(second, "Bob", "bob-body");
        views.markViewed(alice.request().id());
        store.evict(first.account().id(), first.repository().id(), 7, CONNECTED_ACCOUNT);
        entities.flush();
        entities.clear();

        assertThat(store.find(first.account().id(), first.repository().id(), 7)).isEmpty();
        assertThat(store.find(second.account().id(), second.repository().id(), 7)).contains(bob);
        assertThat(views.findByPrIds(ImmutableSet.of(alice.request().id()))).containsKey(alice.request().id());
        HostingPullRequestSnapshot refreshed = save(first, "Alice refreshed", "refreshed-body");
        assertThat(refreshed.request()).isEqualTo(alice.request());
        store.evict(first.account().id(), first.repository().id(), 7, CONNECTED_ACCOUNT);
        store.evict(second.account().id(), second.repository().id(), 7, CONNECTED_ACCOUNT);
        entities.flush();
        entities.clear();
        assertThat(requests.count()).isEqualTo(1);
        assertThat(snapshots.count()).isZero();
        PullRequestEntity retained = requests.findById(alice.request().id()).orElseThrow();
        assertThat(retained.getTitle()).isEqualTo("Alice refreshed");
        assertThat(retained.getAuthor()).isEqualTo("alice");
        assertThat(retained.getLabels()).containsExactly("bug");
        assertThat(retained.getLabelColors()).containsEntry("bug", "ff0000");
        assertThat(retained.getRequestedReviewers()).isEmpty();
        assertThat(retained.getOrigin()).isEqualTo(PullRequest.Origin.AUTHORED.name());
        assertThat(requests.findByIdAndHostingRepositoryIsNull(alice.request().id())).isEmpty();
        assertThat(entities.createQuery("SELECT COUNT(d) FROM PrDetailEntity d WHERE d.prId = :id", Long.class)
                .setParameter("id", alice.request().id()).getSingleResult()).isZero();
        assertThat(views.findByPrIds(ImmutableSet.of(alice.request().id()))).containsKey(alice.request().id());
    }

    @Test
    void rejectsChangedRemoteIdentityEvenAfterSnapshotEviction()
    {
        Scope scope = scope(GITLAB, "https://gitlab.com");
        HostingPullRequestSnapshot original = save(scope, "Original", "body");
        FetchedPullRequest reusedNumber = pullRequest(scope, 43, "Different request");

        assertThatThrownBy(() -> store.save(scope.account().id(), scope.repository().id(), CONNECTED_ACCOUNT,
                reusedNumber, detail(reusedNumber, "wrong"), Optional.empty(), NOW))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(INVALID_RESPONSE));
        assertThat(store.find(scope.account().id(), scope.repository().id(), 7)).contains(original);
        store.evict(scope.account().id(), scope.repository().id(), 7, CONNECTED_ACCOUNT);
        assertThatThrownBy(() -> store.save(scope.account().id(), scope.repository().id(), CONNECTED_ACCOUNT,
                reusedNumber, detail(reusedNumber, "wrong"), Optional.empty(), NOW))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(INVALID_RESPONSE));
        assertThat(store.find(scope.account().id(), scope.repository().id(), 7)).isEmpty();
        assertThat(requests.findById(original.request().id()).orElseThrow().getRemoteId()).isEqualTo("42");
    }

    @Test
    void repositoryInvalidationClearsAllScopesAndAccountsWithoutDeletingIdentity()
    {
        Scope first = scope(GITHUB, "https://github.com");
        Scope second = new Scope(first.instance(), identities.upsertAccount(first.instance().id(), new RemoteHostingAccount("2", "bob")), first.repository());
        Scope otherHost = scope(GITLAB, "https://gitlab.com");
        HostingPullRequestSnapshot original = save(first, "Connected", "connected-body");
        FetchedPullRequest desktop = pullRequest(second, 42, "Desktop");
        store.save(second.account().id(), second.repository().id(), "github-global", desktop, detail(desktop, "desktop-body"), Optional.empty(), NOW);
        HostingPullRequestSnapshot unrelated = save(otherHost, "Other host", "other-body");
        FetchedPullRequest otherNumber = pullRequest(first, 43, 8, "Other number");
        HostingPullRequestSnapshot next = store.save(first.account().id(), first.repository().id(), CONNECTED_ACCOUNT,
                otherNumber, detail(otherNumber, "next-body"), Optional.empty(), NOW);
        views.markViewed(original.request().id());

        store.evictRepository(first.repository().id(), 7);
        entities.flush();
        entities.clear();

        assertThat(store.find(first.account().id(), first.repository().id(), 7)).isEmpty();
        assertThat(store.find(second.account().id(), second.repository().id(), 7)).isEmpty();
        assertThat(store.find(first.account().id(), first.repository().id(), 8)).contains(next);
        assertThat(store.find(otherHost.account().id(), otherHost.repository().id(), 7)).contains(unrelated);
        assertThat(requests.findById(original.request().id())).isPresent();
        assertThat(views.findByPrIds(ImmutableSet.of(original.request().id()))).containsKey(original.request().id());
        assertThat(snapshots.count()).isEqualTo(2);
    }

    @Test
    void credentialSourcesReplaceTheAccountSnapshotAndUseSeparateValidators()
    {
        Scope scope = scope(GITHUB, "https://github.com");
        HostingPullRequestSnapshot connected = save(scope, "Connected account", "connected-body");
        String desktopScope = "github-global";
        FetchedPullRequest desktop = pullRequest(scope, 42, "Desktop account");
        HostingPullRequestSnapshot projected = store.save(scope.account().id(), scope.repository().id(), desktopScope,
                desktop, detail(desktop, "desktop-body"), Optional.of("\"desktop\""), NOW);
        entities.flush();
        entities.clear();

        assertThat(projected.request()).isEqualTo(connected.request());
        assertThat(store.find(scope.account().id(), scope.repository().id(), 7)).contains(projected);
        assertThat(projected.credentialScope()).isEqualTo(desktopScope);
        assertThat(projected.etag()).contains("\"desktop\"");
        store.evict(scope.account().id(), scope.repository().id(), 7, CONNECTED_ACCOUNT);
        assertThat(store.find(scope.account().id(), scope.repository().id(), 7)).contains(projected);

        HostingPullRequestSnapshot replaced = save(scope, "Connected again", "new-body");
        store.evict(scope.account().id(), scope.repository().id(), 7, desktopScope);
        assertThat(store.find(scope.account().id(), scope.repository().id(), 7)).contains(replaced);
        assertThat(replaced.credentialScope()).isEqualTo(CONNECTED_ACCOUNT);
        assertThat(snapshots.count()).isEqualTo(1);
    }

    @Test
    void snapshotsWrittenBeforeScopesDefaultToConnectedAccount()
    {
        Scope scope = scope(GITHUB, "https://github.com");
        HostingPullRequestSnapshot saved = save(scope, "Existing metadata", "body");
        entities.createQuery("UPDATE HostingPullRequestSnapshotEntity SET credentialScope = NULL").executeUpdate();
        entities.clear();

        assertThat(store.find(scope.account().id(), scope.repository().id(), 7)).contains(saved);
        store.evict(scope.account().id(), scope.repository().id(), 7, CONNECTED_ACCOUNT);
        assertThat(store.find(scope.account().id(), scope.repository().id(), 7)).isEmpty();
    }

    @Test
    void rejectsCrossInstanceAccessForEveryOperation()
    {
        Scope github = scope(GITHUB, "https://github.com");
        Scope gitlab = scope(GITLAB, "https://gitlab.com");
        HostingPullRequestSnapshot original = save(gitlab, "GitLab", "body");

        assertThatIllegalArgumentException().isThrownBy(() -> store.find(github.account().id(), gitlab.repository().id(), 7));
        assertThatIllegalArgumentException().isThrownBy(() -> store.evict(github.account().id(), gitlab.repository().id(), 7, CONNECTED_ACCOUNT));
        assertThatIllegalArgumentException().isThrownBy(() -> store.save(github.account().id(), gitlab.repository().id(), CONNECTED_ACCOUNT,
                original.pullRequest(), original.detail(), original.etag(), NOW));
        assertThat(store.find(gitlab.account().id(), gitlab.repository().id(), 7)).contains(original);
        assertThat(requests.count()).isEqualTo(1);
    }

    @Test
    void rejectsInvalidOrMissingTargetsWithoutCreatingRows()
    {
        Scope scope = scope(GITHUB, "https://github.com");
        FetchedPullRequest fetched = pullRequest(scope, 42, "Title");
        assertThatIllegalArgumentException().isThrownBy(() -> store.find(0, scope.repository().id(), 7));
        assertThatIllegalArgumentException().isThrownBy(() -> store.find(scope.account().id(), 0, 7));
        assertThatIllegalArgumentException().isThrownBy(() -> store.find(scope.account().id(), scope.repository().id(), 0));
        assertThatIllegalArgumentException().isThrownBy(() -> store.find(999, scope.repository().id(), 7));
        assertThatIllegalArgumentException().isThrownBy(() -> store.evict(scope.account().id(), 999, 7, CONNECTED_ACCOUNT));
        assertThatIllegalArgumentException().isThrownBy(() -> store.save(scope.account().id(), scope.repository().id(), "",
                fetched, detail(fetched, "body"), Optional.empty(), NOW));
        assertThat(requests.count()).isZero();
        assertThat(snapshots.count()).isZero();
    }

    @Test
    void sharedRowsUseTheExistingDesktopProjectionWithoutCrossServerCollisions()
    {
        Scope publicServer = scope(GITLAB, "https://gitlab.com");
        Scope privateServer = scope(GITLAB, "https://git.example.com");
        HostingPullRequestSnapshot publicRequest = save(publicServer, "Public", "Public body");
        HostingPullRequestSnapshot privateRequest = save(privateServer, "Private", "Private body");
        PullRequest legacy = desktop.saveObserved(pullRequest(scope(GITHUB, "https://github.com"), 42, "GitHub"));
        desktop.setMonitored(publicRequest.request().id(), true);
        views.markViewed(publicRequest.request().id());
        entities.flush();
        entities.clear();

        PullRequest publicRow = desktop.findByHostingRepository(publicServer.repository().id(), 7).orElseThrow();
        PullRequest privateRow = desktop.findByHostingRepository(privateServer.repository().id(), 7).orElseThrow();
        assertThat(publicRow.title()).isEqualTo("Public");
        assertThat(publicRow.author()).isEqualTo("alice");
        assertThat(publicRow.monitoredAt()).isNotNull();
        assertThat(publicRow.viewedAt()).isNotNull();
        assertThat(privateRow.title()).isEqualTo("Private");
        assertThat(privateRow.monitoredAt()).isNull();
        assertThat(privateRow.viewedAt()).isNull();
        assertThat(desktop.findById(publicRow.id())).contains(publicRow);
        assertThat(desktop.findHostingIdentity(publicRow.id())).contains(publicRequest.request());
        assertThat(desktop.findHostingIdentity(privateRow.id())).contains(privateRequest.request());
        assertThat(desktop.findHostingIdentity(legacy.id())).isEmpty();
        assertThat(desktop.findIdByRepoAndNumber(publicRow.repo(), 7)).contains(legacy.id());
        assertThat(desktop.findByHostingHeadRef(publicServer.repository().id(), "feature")).extracting(PullRequest::id).containsExactly(publicRow.id());
    }

    @Test
    void hostedRefreshPreservesLocalEnrichmentAndAdvancesTheExistingActivityClock()
    {
        Scope scope = scope(GITLAB, "https://gitlab.com");
        HostingPullRequestSnapshot original = save(scope, "Original", "Body");
        long id = original.request().id();
        desktop.setMonitored(id, true);
        views.markViewed(id);
        PullRequestEntity row = requests.findById(id).orElseThrow();
        row.setCiStatus(PullRequestDetail.CiStatus.FAILING);
        row.setAdditions(9);
        row.setActivityAt(NOW.minusSeconds(10));
        FetchedPullRequest updated = pullRequest(scope, 42, 7, "Updated", NOW.plusSeconds(5));
        store.save(scope.account().id(), scope.repository().id(), CONNECTED_ACCOUNT, updated, detail(updated, "Body"), Optional.empty(), NOW.plusSeconds(10));
        entities.flush();
        entities.clear();

        PullRequest projected = desktop.findById(id).orElseThrow();
        assertThat(projected.title()).isEqualTo("Updated");
        assertThat(projected.ciStatus()).isEqualTo(PullRequestDetail.CiStatus.FAILING);
        assertThat(projected.additions()).isEqualTo(9);
        assertThat(projected.monitoredAt()).isNotNull();
        assertThat(projected.viewedAt()).isNotNull();
        assertThat(projected.activityAt()).isEqualTo(NOW.plusSeconds(10));

        FetchedPullRequest merged = new FetchedPullRequest(updated.githubId(), updated.repo(), updated.number(), updated.title(), updated.author(), updated.htmlUrl(),
                updated.createdAt(), NOW.plusSeconds(20), updated.origin(), updated.labels(), updated.labelColors(), false, "merged", NOW.plusSeconds(20), NOW.plusSeconds(20),
                updated.requestedReviewers(), updated.mergeable(), updated.mergeableState(), updated.headRef());
        store.savePage(scope.account().id(), scope.repository().id(), CONNECTED_ACCOUNT, ImmutableList.of(merged));
        assertThat(desktop.findById(id).orElseThrow().monitoredAt()).isNull();
        assertThat(desktop.findById(id).orElseThrow().viewedAt()).isNotNull();
    }

    @Test
    void databaseEnforcesOneHostedIdentityPerRepositoryAndNumber()
    {
        Scope scope = scope(GITHUB, "https://github.com");
        HostingPullRequestSnapshot saved = save(scope, "Title", "body");
        PullRequestEntity existing = requests.findById(saved.request().id()).orElseThrow();
        PullRequestEntity duplicate = PullRequestEntity.from(saved.pullRequest(), NOW);
        duplicate.setHostingRepository(existing.getHostingRepository());
        duplicate.setRemoteId(existing.getRemoteId());
        assertThatThrownBy(() -> requests.saveAndFlush(duplicate)).isInstanceOf(JpaSystemException.class)
                .hasMessageContaining("UNIQUE constraint failed: pull_requests.hosting_repository_id, pull_requests.number");
    }

    private HostingPullRequestSnapshot save(Scope scope, String title, String body)
    {
        FetchedPullRequest fetched = pullRequest(scope, 42, title);
        return store.save(scope.account().id(), scope.repository().id(), CONNECTED_ACCOUNT, fetched, detail(fetched, body), Optional.of("\"" + title + "\""), NOW);
    }

    private Scope scope(HostingProvider provider, String base)
    {
        String api = provider == GITHUB ? (base.equals("https://github.com") ? "https://api.github.com" : base + "/api/v3") : base + "/api/v4";
        HostingInstance instance = identities.upsertInstance(new HostingServer(provider, URI.create(base), URI.create(api)));
        HostingAccount account = identities.upsertAccount(instance.id(), new RemoteHostingAccount("1", "alice"));
        return new Scope(instance, account, repository(instance, "Team/Project"));
    }

    private HostingRepository repository(HostingInstance instance, String path)
    {
        return identities.upsertRepository(instance.id(), new RemoteHostingRepository("99", path, URI.create(instance.webBaseUrl() + "/" + path),
                URI.create(instance.webBaseUrl() + "/" + path + ".git"), Optional.empty(), Optional.of("main")));
    }

    private static FetchedPullRequest pullRequest(Scope scope, long remoteId, String title)
    {
        return pullRequest(scope, remoteId, 7, title);
    }

    private static FetchedPullRequest pullRequest(Scope scope, long remoteId, int number, String title)
    {
        return pullRequest(scope, remoteId, number, title, NOW);
    }

    private static FetchedPullRequest pullRequest(Scope scope, long remoteId, int number, String title, Instant updatedAt)
    {
        String url = scope.repository().webUrl() + (scope.instance().provider() == GITHUB ? "/pull/" : "/-/merge_requests/") + number;
        return new FetchedPullRequest(remoteId, scope.repository().fullPath(), number, title, "alice", url, NOW.minusSeconds(60), updatedAt,
                PullRequest.Origin.AUTHORED, ImmutableList.of("bug"), ImmutableMap.of("bug", "ff0000"), false, "open", null, null, ImmutableList.of(), null, null, "feature");
    }

    private static PrRawDetail detail(FetchedPullRequest fetched, String body)
    {
        return new PrRawDetail(fetched.title(), fetched.htmlUrl(), fetched.state(), fetched.author(), null, fetched.createdAt(), fetched.updatedAt(), null, body,
                fetched.labels(), fetched.labelColors(), ImmutableList.of("alice"), null, false, null, null, -1, -1, -1, -1, ImmutableList.of(), "abc", "feature", "main", "def");
    }

    private record Scope(HostingInstance instance, HostingAccount account, HostingRepository repository) {}
}
