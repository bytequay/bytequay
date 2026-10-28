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
import com.bytequay.core.domain.PrActivitySummary;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.hosting.HostingProvider;
import com.bytequay.core.hosting.HostingServer;
import com.bytequay.core.hosting.RemoteHostingRepository;
import com.bytequay.persistence.sqlite.entity.HostingRepositoryEntity;
import com.bytequay.persistence.sqlite.entity.PullRequestEntity;
import com.bytequay.persistence.sqlite.repository.PullRequestJpaRepository;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.net.URI;
import java.time.Instant;
import java.util.Optional;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestLegacyPullRequestIsolation
        extends AbstractTestTransactionalSqliteStore
{
    private static final String REPO = "owner/project";
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @Autowired
    private SqlitePullRequestStore legacy;

    @Autowired
    private SqlitePrDetailStore details;

    @Autowired
    private SqliteHostingIdentityStore identities;

    @Autowired
    private SqliteWorkspaceStore workspaces;

    @Autowired
    private PullRequestJpaRepository rows;

    @Autowired
    private EntityManager entities;

    @ParameterizedTest
    @EnumSource(HostingProvider.class)
    void legacyQueriesAndSyncStaySeparateFromHostedCoordinates(HostingProvider provider)
    {
        PullRequestEntity hosted = hosted(provider, 7);
        PullRequestEntity merged = hosted(provider, 8);
        merged.setState("merged");
        merged.setMergedAt(NOW);
        rows.saveAndFlush(merged);
        PullRequest stored = legacy.saveObserved(fetched(7, "Legacy title"));
        legacy.saveDashboardSyncPage(ImmutableList.of(fetched(7, "Legacy title")));
        legacy.saveRepositorySyncPage(REPO, ImmutableList.of(fetched(7, "Legacy title")));
        entities.flush();
        entities.clear();

        assertThat(stored.id()).isNotEqualTo(hosted.getId());
        assertThat(legacy.findIdByRepoAndNumber("OWNER/PROJECT", 7)).contains(stored.id());
        assertThat(legacy.findById(hosted.getId())).hasValueSatisfying(row -> assertThat(row.title()).isEqualTo("Hosted title"));
        assertThat(legacy.findPage(0, 50).pullRequests()).extracting(PullRequest::id).containsExactly(stored.id());
        assertThat(legacy.findPage(0, 50).totalPullRequests()).isEqualTo(1);
        assertThat(legacy.findPage(0, 50).openPullRequests()).isEqualTo(1);
        assertThat(legacy.findAttentionCandidates()).extracting(PullRequest::id).containsExactly(stored.id());
        assertThat(legacy.findRepositoryPage(REPO, 0, 50).pullRequests()).extracting(PullRequest::id).containsExactly(stored.id());
        assertThat(legacy.findRepositoryPage(REPO, 0, 50).openPullRequests()).isEqualTo(1);
        assertThat(legacy.findRepositoryPage(REPO, 0, 50, "#7").pullRequests()).extracting(PullRequest::id).containsExactly(stored.id());
        assertThat(legacy.findRepositoryPage(REPO, 0, 50, "#7").openPullRequests()).isEqualTo(1);
        assertThat(legacy.findMergedRepositoryPullRequests(REPO, "", 50)).isEmpty();
        assertThat(legacy.findByHeadRef(REPO, "feature")).extracting(PullRequest::id).containsExactly(stored.id());
        assertThat(rows.findById(hosted.getId()).orElseThrow().getTitle()).isEqualTo("Hosted title");
        assertThat(rows.findByHostingRepositoryIdAndNumber(hosted.getHostingRepository().getId(), 7)).map(PullRequestEntity::getId).contains(hosted.getId());
    }

    @Test
    void localIdReadsAndUpdatesReachOnlyTheSelectedHostedRow()
    {
        PullRequestEntity hosted = hosted(HostingProvider.GITLAB, 7);
        PullRequest pathOnly = legacy.saveObserved(fetched(7, "Legacy title"));
        PrActivitySummary activity = new PrActivitySummary("commented", Optional.of("alice"), NOW, Optional.empty(), Optional.empty(), Optional.empty(),
                Optional.of("Private discussion"), Optional.empty(), OptionalInt.empty());
        hosted.setCardActivity(activity);
        hosted.setDiscussionActivityAt(NOW);
        hosted.setAttentionActivityAt(NOW);
        rows.saveAndFlush(hosted);
        long id = hosted.getId();

        assertThat(legacy.findActivities(ImmutableSet.of(id))).containsExactlyEntriesOf(ImmutableMap.of(id, activity));
        assertThat(legacy.findDiscussionActivityAt(id)).contains(NOW);
        assertThat(legacy.findDiscussionActivities(ImmutableSet.of(id))).containsExactlyEntriesOf(ImmutableMap.of(id, NOW));
        assertThat(legacy.findAttentionActivities(ImmutableSet.of(id))).containsExactlyEntriesOf(ImmutableMap.of(id, NOW));
        legacy.setMonitored(id, true);
        assertThat(legacy.findById(id).orElseThrow().monitoredAt()).isNotNull();
        legacy.updateTitle(id, "Updated title");
        assertThat(legacy.findById(id).orElseThrow().title()).isEqualTo("Updated title");
        legacy.updateDetails(id, raw("Updated detail"));
        legacy.updateEnrichment(id, NOW.plusSeconds(1), PullRequestDetail.CiStatus.FAILING, 1, 2, 3, true, "clean", NOW, "other",
                ImmutableList.of("reviewer"), PullRequest.Origin.REVIEW_REQUESTED, Optional.of(NOW.plusSeconds(1)), Optional.of("APPROVED"), ImmutableMap.of("checks", "changed"));
        legacy.observeActivity(id, Optional.empty(), NOW.plusSeconds(1));
        legacy.observeAttentionActivity(id, NOW.plusSeconds(1));
        entities.flush();
        entities.clear();

        PullRequestEntity retained = rows.findById(id).orElseThrow();
        PullRequest updated = legacy.findById(id).orElseThrow();
        assertThat(updated.title()).isEqualTo("Updated detail");
        assertThat(updated.state()).isEqualTo("merged");
        assertThat(updated.monitoredAt()).isNull();
        assertThat(updated.ciStatus()).isEqualTo(PullRequestDetail.CiStatus.FAILING);
        assertThat(updated.additions()).isEqualTo(1);
        assertThat(updated.deletions()).isEqualTo(2);
        assertThat(updated.commentCount()).isEqualTo(3);
        assertThat(updated.headRef()).isEqualTo("other");
        assertThat(updated.requestedReviewers()).containsExactly("reviewer");
        assertThat(retained.getDiscussionActivityAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(retained.getAttentionActivityAt()).isEqualTo(NOW.plusSeconds(1));
        assertThat(retained.getCardActivity()).isEqualTo(activity);
        assertThat(legacy.findById(pathOnly.id())).contains(pathOnly);
        assertThat(legacy.findIdByRepoAndNumber(REPO, 7)).contains(pathOnly.id());
    }

    @Test
    void legacyDetailPathAccessDoesNotSelectAHostedRequest()
    {
        PullRequestEntity hosted = hosted(HostingProvider.GITLAB, 7);
        details.saveMetadata(hosted.getId(), new StoredPrDetail(raw("Hosted title"), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), false));
        PrTimelineEvent event = new PrTimelineEvent(null, "local", "alice", NOW, "Local action", Optional.of("local-event"));

        assertThat(details.findSyncedAt(REPO, 7)).isEmpty();
        assertThatThrownBy(() -> details.recordLocalTimelineEvent(PullRequestRef.of("owner", "project", 7), event)).isInstanceOf(IllegalArgumentException.class);
        PullRequest stored = legacy.saveObserved(fetched(7, "Legacy title"));
        details.saveMetadata(stored.id(), new StoredPrDetail(raw("Legacy title"), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), false));
        details.recordLocalTimelineEvent(PullRequestRef.of("owner", "project", 7), event);

        assertThat(details.findSyncedAt(REPO, 7)).isPresent();
        assertThat(details.find(hosted.getId()).orElseThrow().timeline()).isEmpty();
        assertThat(details.find(stored.id()).orElseThrow().timeline()).containsExactly(event);
    }

    @Test
    void deletingALegacyWorkspacePreservesHostedRowsAtTheSamePath()
    {
        PullRequestEntity hosted = hosted(HostingProvider.GITLAB, 7);
        hosted.setDashboardVisible(false);
        rows.saveAndFlush(hosted);
        PullRequest stored = legacy.saveRepositorySyncPage(REPO, ImmutableList.of(fetched(7, "Legacy title"))).getFirst();
        RepoRef repo = RepoRef.of("owner", "project");
        Workspace workspace = workspaces.createOrGet(new Workspace("workspace", "Project", 100, repo, Optional.empty(), Optional.empty(), 100, repo,
                Workspace.Topology.DIRECT, "main", "https://github.com/owner/project.git", Optional.empty(), Optional.empty(), "/retained/workspace",
                Optional.empty(), Optional.empty(), 0, NOW, NOW));

        workspaces.delete(workspace.id());
        entities.flush();
        entities.clear();

        assertThat(rows.findById(stored.id())).isEmpty();
        assertThat(rows.findById(hosted.getId())).hasValueSatisfying(row -> assertThat(row.getWorkspaceVisible()).isTrue());
    }

    private PullRequestEntity hosted(HostingProvider provider, int number)
    {
        URI base = URI.create(provider == HostingProvider.GITHUB ? "https://github.com" : "https://gitlab.company.test");
        URI api = URI.create(provider == HostingProvider.GITHUB ? "https://api.github.com" : base + "/api/v4");
        long instanceId = identities.upsertInstance(new HostingServer(provider, base, api)).id();
        long repositoryId = identities.upsertRepository(instanceId, new RemoteHostingRepository("100", REPO, URI.create(base + "/" + REPO),
                URI.create(base + "/" + REPO + ".git"), Optional.empty(), Optional.of("main"))).id();
        PullRequestEntity row = PullRequestEntity.from(fetched(number, "Hosted title"), NOW);
        row.setHostingRepository(entities.getReference(HostingRepositoryEntity.class, repositoryId));
        row.setRemoteId(Integer.toString(number));
        // Visibility must never bypass the hosting scope, even when another writer sets these flags.
        row.setDashboardVisible(true);
        row.setWorkspaceVisible(true);
        return rows.saveAndFlush(row);
    }

    private static FetchedPullRequest fetched(int number, String title)
    {
        return new FetchedPullRequest((long) number, REPO, number, title, "alice", "https://github.com/" + REPO + "/pull/" + number, NOW, NOW,
                PullRequest.Origin.AUTHORED, ImmutableList.of(), ImmutableMap.of(), false, "open", null, null, ImmutableList.of(), null, null, "feature");
    }

    private static PrRawDetail raw(String title)
    {
        return new PrRawDetail(title, "https://github.com/owner/project/pull/7", "closed", "alice", null, NOW, NOW.plusSeconds(1), NOW, "Description",
                ImmutableList.of(), ImmutableMap.of(), ImmutableList.of(), null, false, null, null, 0, 0, 0, 0, ImmutableList.of(), "a".repeat(40), "feature", "main");
    }
}
