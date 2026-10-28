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
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.hosting.HostingAccount;
import com.bytequay.core.hosting.HostingFailure;
import com.bytequay.core.hosting.HostingInstance;
import com.bytequay.core.hosting.HostingProvider;
import com.bytequay.core.hosting.HostingPullRequestSnapshot;
import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.hosting.HostingServer;
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
import static com.bytequay.core.hosting.HostingFailure.Kind.REVISION_CONFLICT;
import static com.bytequay.core.hosting.HostingProvider.GITHUB;
import static com.bytequay.core.hosting.HostingProvider.GITLAB;
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
    private SqlitePrViewStateStore views;

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
        HostingPullRequestSnapshot saved = store.save(scope.account().id(), scope.repository().id(), 1, fetched, detail, Optional.of("\"metadata\""), NOW);
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

    @Test
    void isolatesSameRemoteIdsAcrossProvidersAndServers()
    {
        Scope github = scope(GITHUB, "https://github.com");
        Scope gitlab = scope(GITLAB, "https://gitlab.com");
        Scope company = scope(GITLAB, "https://git.company.com/engineering");
        HostingPullRequestSnapshot first = save(github, 1, "GitHub", "github-body");
        HostingPullRequestSnapshot second = save(gitlab, 1, "GitLab", "gitlab-body");
        HostingPullRequestSnapshot third = save(company, 1, "Company", "company-body");
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
        HostingPullRequestSnapshot alice = save(first, 1, "Alice metadata", "alice-body");
        assertThat(store.find(second.account().id(), second.repository().id(), 7)).isEmpty();
        HostingPullRequestSnapshot bob = save(second, 3, "Bob metadata", "bob-body");
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
        HostingPullRequestSnapshot original = save(scope, 1, "Before rename", "body");
        HostingRepository renamed = repository(scope.instance(), "NewGroup/Subgroup/NewProject");
        HostingPullRequestSnapshot updated = save(new Scope(scope.instance(), scope.account(), renamed), 1, "After rename", "new-body");
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
        ReviewDraft draft = drafts.save(ReviewDraft.empty(legacyMetadata.repo(), legacyMetadata.number(), NOW));
        HostingPullRequestSnapshot hosted = save(scope, 1, "Hosted title", "new-body");
        entities.flush();
        entities.clear();

        assertThat(hosted.request().id()).isNotEqualTo(legacy.getId());
        PullRequestEntity retained = requests.findById(legacy.getId()).orElseThrow();
        assertThat(retained.getHostingRepository()).isNull();
        assertThat(retained.getTitle()).isEqualTo("Legacy title");
        assertThat(views.findByPrIds(ImmutableSet.of(legacy.getId(), hosted.request().id()))).containsOnlyKeys(legacy.getId());
        assertThat(drafts.find(legacyMetadata.repo(), legacyMetadata.number())).contains(draft);
        assertThat(requests.count()).isEqualTo(2);
    }

    @Test
    void evictionRetainsIdentityLocalViewStateAndOtherAccounts()
    {
        Scope first = scope(GITLAB, "https://gitlab.com");
        Scope second = new Scope(first.instance(), identities.upsertAccount(first.instance().id(), new RemoteHostingAccount("2", "bob")), first.repository());
        HostingPullRequestSnapshot alice = save(first, 1, "Alice", "alice-body");
        HostingPullRequestSnapshot bob = save(second, 1, "Bob", "bob-body");
        views.markViewed(alice.request().id());
        store.evict(first.account().id(), first.repository().id(), 7);
        entities.flush();
        entities.clear();

        assertThat(store.find(first.account().id(), first.repository().id(), 7)).isEmpty();
        assertThat(store.find(second.account().id(), second.repository().id(), 7)).contains(bob);
        assertThat(views.findByPrIds(ImmutableSet.of(alice.request().id()))).containsKey(alice.request().id());
        HostingPullRequestSnapshot refreshed = save(first, 2, "Alice refreshed", "refreshed-body");
        assertThat(refreshed.request()).isEqualTo(alice.request());
        store.evict(first.account().id(), first.repository().id(), 7);
        store.evict(second.account().id(), second.repository().id(), 7);
        entities.flush();
        entities.clear();
        assertThat(requests.count()).isEqualTo(1);
        assertThat(snapshots.count()).isZero();
        PullRequestEntity retained = requests.findById(alice.request().id()).orElseThrow();
        assertThat(retained.getTitle()).isEmpty();
        assertThat(retained.getAuthor()).isNull();
        assertThat(retained.getLabels()).isEmpty();
        assertThat(retained.getLabelColors()).isNull();
        assertThat(retained.getRequestedReviewers()).isNull();
        assertThat(retained.getOrigin()).isEqualTo(PullRequest.Origin.OTHER.name());
        assertThat(requests.findByIdAndHostingRepositoryIsNull(alice.request().id())).isEmpty();
        assertThat(entities.createQuery("SELECT COUNT(d) FROM PrDetailEntity d WHERE d.prId = :id", Long.class)
                .setParameter("id", alice.request().id()).getSingleResult()).isZero();
        assertThat(views.findByPrIds(ImmutableSet.of(alice.request().id()))).containsKey(alice.request().id());
    }

    @Test
    void rejectsChangedRemoteIdentityEvenAfterSnapshotEviction()
    {
        Scope scope = scope(GITLAB, "https://gitlab.com");
        HostingPullRequestSnapshot original = save(scope, 1, "Original", "body");
        FetchedPullRequest reusedNumber = pullRequest(scope, 43, "Different request");

        assertThatThrownBy(() -> store.save(scope.account().id(), scope.repository().id(), 1, reusedNumber, detail(reusedNumber, "wrong"), Optional.empty(), NOW))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(INVALID_RESPONSE));
        assertThat(store.find(scope.account().id(), scope.repository().id(), 7)).contains(original);
        store.evict(scope.account().id(), scope.repository().id(), 7);
        assertThatThrownBy(() -> store.save(scope.account().id(), scope.repository().id(), 1, reusedNumber, detail(reusedNumber, "wrong"), Optional.empty(), NOW))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(INVALID_RESPONSE));
        assertThat(store.find(scope.account().id(), scope.repository().id(), 7)).isEmpty();
        assertThat(requests.findById(original.request().id()).orElseThrow().getRemoteId()).isEqualTo("42");
    }

    @Test
    void replacesGenerationAtomicallyAndRejectsStaleCredentials()
    {
        Scope scope = scope(GITLAB, "https://gitlab.com");
        save(scope, 1, "Old token", "old-body");
        HostingPullRequestSnapshot current = save(scope, 2, "New token", "new-body");
        entities.flush();
        entities.clear();

        assertThatThrownBy(() -> save(scope, 1, "Late old token", "stale-body"))
                .isInstanceOfSatisfying(HostingFailure.class, failure -> assertThat(failure.kind()).isEqualTo(REVISION_CONFLICT));
        assertThat(store.find(scope.account().id(), scope.repository().id(), 7)).contains(current);
        assertThat(current.credentialGeneration()).isEqualTo(2);
        assertThat(snapshots.count()).isEqualTo(1);
    }

    @Test
    void rejectsCrossInstanceAccessForEveryOperation()
    {
        Scope github = scope(GITHUB, "https://github.com");
        Scope gitlab = scope(GITLAB, "https://gitlab.com");
        HostingPullRequestSnapshot original = save(gitlab, 1, "GitLab", "body");

        assertThatIllegalArgumentException().isThrownBy(() -> store.find(github.account().id(), gitlab.repository().id(), 7));
        assertThatIllegalArgumentException().isThrownBy(() -> store.evict(github.account().id(), gitlab.repository().id(), 7));
        assertThatIllegalArgumentException().isThrownBy(() -> store.save(github.account().id(), gitlab.repository().id(), 1,
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
        assertThatIllegalArgumentException().isThrownBy(() -> store.evict(scope.account().id(), 999, 7));
        assertThatIllegalArgumentException().isThrownBy(() -> store.save(scope.account().id(), scope.repository().id(), 0,
                fetched, detail(fetched, "body"), Optional.empty(), NOW));
        assertThat(requests.count()).isZero();
        assertThat(snapshots.count()).isZero();
    }

    @Test
    void databaseEnforcesOneHostedIdentityPerRepositoryAndNumber()
    {
        Scope scope = scope(GITHUB, "https://github.com");
        HostingPullRequestSnapshot saved = save(scope, 1, "Title", "body");
        PullRequestEntity existing = requests.findById(saved.request().id()).orElseThrow();
        PullRequestEntity duplicate = PullRequestEntity.from(saved.pullRequest(), NOW);
        duplicate.setHostingRepository(existing.getHostingRepository());
        duplicate.setRemoteId(existing.getRemoteId());
        assertThatThrownBy(() -> requests.saveAndFlush(duplicate)).isInstanceOf(JpaSystemException.class)
                .hasMessageContaining("UNIQUE constraint failed: pull_requests.hosting_repository_id, pull_requests.number");
    }

    private HostingPullRequestSnapshot save(Scope scope, long generation, String title, String body)
    {
        FetchedPullRequest fetched = pullRequest(scope, 42, title);
        return store.save(scope.account().id(), scope.repository().id(), generation, fetched, detail(fetched, body), Optional.of("\"" + title + "\""), NOW);
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
        String url = scope.repository().webUrl() + (scope.instance().provider() == GITHUB ? "/pull/7" : "/-/merge_requests/7");
        return new FetchedPullRequest(remoteId, scope.repository().fullPath(), 7, title, "alice", url, NOW.minusSeconds(60), NOW,
                PullRequest.Origin.AUTHORED, ImmutableList.of("bug"), ImmutableMap.of("bug", "ff0000"), false, "open", null, null, ImmutableList.of(), null, null, "feature");
    }

    private static PrRawDetail detail(FetchedPullRequest fetched, String body)
    {
        return new PrRawDetail(fetched.title(), fetched.htmlUrl(), fetched.state(), fetched.author(), null, fetched.createdAt(), fetched.updatedAt(), null, body,
                fetched.labels(), fetched.labelColors(), ImmutableList.of("alice"), null, false, null, null, -1, -1, -1, -1, ImmutableList.of(), "abc", "feature", "main", "def");
    }

    private record Scope(HostingInstance instance, HostingAccount account, HostingRepository repository) {}
}
