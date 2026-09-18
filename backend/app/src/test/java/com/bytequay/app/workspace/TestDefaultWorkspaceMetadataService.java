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
package com.bytequay.app.workspace;

import com.bytequay.app.git.AvatarCache;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.app.repository.github.GitHubRepoClient;
import com.bytequay.app.repository.github.GitHubRepoClient.RepositoryOverview;
import com.bytequay.app.repository.github.GitHubRepositoryUser;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryContributor;
import com.bytequay.core.domain.RepositoryLabel;
import com.bytequay.core.domain.RepositoryMetadataSnapshot;
import com.bytequay.core.domain.RepositoryPickerUser;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.domain.Workspace.Topology;
import com.bytequay.core.store.RepositoryMetadataStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class TestDefaultWorkspaceMetadataService
{
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");
    private static final RepoRef DIRECT = RepoRef.of("owner", "project");
    private static final RepoRef UPSTREAM = RepoRef.of("upstream", "project");

    private final Map<String, Workspace> workspaces = new HashMap<>();
    private final Map<Long, RepositoryMetadataSnapshot> snapshots = new HashMap<>();
    private final WorkspaceStore workspaceStore = mock(WorkspaceStore.class);
    private final RepositoryMetadataStore metadataStore = mock(RepositoryMetadataStore.class);
    private final GitHubRepoClient gitHub = mock(GitHubRepoClient.class);
    private final GitHubPullRequestReadClient pullRequests = mock(GitHubPullRequestReadClient.class);
    private final AvatarCache avatarCache = mock(AvatarCache.class);
    private final CredentialService credentials = mock(CredentialService.class);
    private DefaultWorkspaceMetadataService service;

    @BeforeEach
    void setUp()
    {
        when(credentials.resolveGitHubToken()).thenReturn("token");
        when(workspaceStore.findById(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(workspaces.get(invocation.getArgument(0))));
        when(workspaceStore.findAllRetained()).thenAnswer(invocation -> ImmutableList.copyOf(workspaces.values()));
        when(workspaceStore.save(org.mockito.ArgumentMatchers.any()))
                .thenAnswer(invocation -> {
                    Workspace workspace = invocation.getArgument(0);
                    workspaces.put(workspace.id(), workspace);
                    return workspace;
                });
        when(metadataStore.find(org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(invocation -> Optional.ofNullable(snapshots.get(invocation.getArgument(0))));
        doAnswer(invocation -> {
            long id = invocation.getArgument(0);
            RepoRef repository = invocation.getArgument(1);
            String description = invocation.getArgument(2);
            int stargazersCount = invocation.getArgument(3);
            List<String> topics = invocation.getArgument(4);
            Map<String, Long> languages = invocation.getArgument(5);
            Instant syncedAt = invocation.getArgument(6);
            RepositoryMetadataSnapshot previous = snapshots.getOrDefault(
                    id, RepositoryMetadataSnapshot.empty(id, repository));
            snapshots.put(id, new RepositoryMetadataSnapshot(
                    id, repository, description, stargazersCount, languages,
                    previous.contributors(), previous.pickerUsers(), previous.labels(), topics, Optional.of(syncedAt),
                    previous.contributorsSyncedAt(), previous.pickerUsersSyncedAt(), previous.labelsTopicsSyncedAt()));
            return null;
        }).when(metadataStore).upsertOverview(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyMap(),
                org.mockito.ArgumentMatchers.any());
        doAnswer(invocation -> {
            long id = invocation.getArgument(0);
            RepoRef repository = invocation.getArgument(1);
            List<RepositoryContributor> contributors = invocation.getArgument(2);
            Instant syncedAt = invocation.getArgument(3);
            RepositoryMetadataSnapshot previous = snapshots.getOrDefault(
                    id, RepositoryMetadataSnapshot.empty(id, repository));
            snapshots.put(id, new RepositoryMetadataSnapshot(
                    id, repository, previous.description(), previous.stargazersCount(), previous.languages(),
                    contributors, previous.pickerUsers(), previous.labels(), previous.topics(), previous.overviewSyncedAt(),
                    Optional.of(syncedAt), previous.pickerUsersSyncedAt(), previous.labelsTopicsSyncedAt()));
            return null;
        }).when(metadataStore).upsertContributors(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any());
        doAnswer(invocation -> {
            long id = invocation.getArgument(0);
            RepoRef repository = invocation.getArgument(1);
            List<RepositoryPickerUser> pickerUsers = invocation.getArgument(2);
            Instant syncedAt = invocation.getArgument(3);
            RepositoryMetadataSnapshot previous = snapshots.getOrDefault(
                    id, RepositoryMetadataSnapshot.empty(id, repository));
            snapshots.put(id, new RepositoryMetadataSnapshot(
                    id, repository, previous.description(), previous.stargazersCount(), previous.languages(),
                    previous.contributors(), pickerUsers, previous.labels(), previous.topics(), previous.overviewSyncedAt(),
                    previous.contributorsSyncedAt(), Optional.of(syncedAt), previous.labelsTopicsSyncedAt()));
            return null;
        }).when(metadataStore).replacePickerUsers(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any());
        doAnswer(invocation -> {
            long id = invocation.getArgument(0);
            RepoRef repository = invocation.getArgument(1);
            List<RepositoryLabel> labels = invocation.getArgument(2);
            List<String> topics = invocation.getArgument(3);
            Instant syncedAt = invocation.getArgument(4);
            RepositoryMetadataSnapshot previous = snapshots.getOrDefault(
                    id, RepositoryMetadataSnapshot.empty(id, repository));
            snapshots.put(id, new RepositoryMetadataSnapshot(
                    id, repository, previous.description(), previous.stargazersCount(), previous.languages(),
                    previous.contributors(), previous.pickerUsers(), labels, topics, previous.overviewSyncedAt(),
                    previous.contributorsSyncedAt(), previous.pickerUsersSyncedAt(), Optional.of(syncedAt)));
            return null;
        }).when(metadataStore).upsertLabelsAndTopics(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any());
        service = new DefaultWorkspaceMetadataService(
                workspaceStore,
                metadataStore,
                gitHub,
                pullRequests,
                avatarCache,
                credentials,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Runnable::run);
    }

    @Test
    void directUsesSelectedRepositoryAndRefreshesMetadata()
    {
        workspaces.put("direct", workspace("direct", 100, DIRECT, Topology.DIRECT));
        when(gitHub.fetchContributors("token", DIRECT))
                .thenReturn(ImmutableList.of(new RepositoryContributor(1, "alice", "avatar", false)));
        when(gitHub.fetchLabels("token", DIRECT))
                .thenReturn(ImmutableList.of(new RepositoryLabel(2, "bug", "ff0000")));
        when(gitHub.fetchOverview("token", DIRECT)).thenReturn(overview());
        when(pullRequests.fetchAssignableRepositoryUsers("token", DIRECT))
                .thenReturn(ImmutableList.of(new GitHubRepositoryUser(
                        3, "alice", "https://avatars.example/alice", false)));
        when(avatarCache.cache("https://avatars.example/alice")).thenReturn(Optional.of("avatar-key"));

        service.synchronize("direct");

        assertThat(service.getSnapshot("direct").contributors()).hasSize(1);
        assertThat(service.getSnapshot("direct").pickerUsers())
                .containsExactly(new RepositoryPickerUser(3, "alice", Optional.of("avatar-key"), false));
        assertThat(workspaces.get("direct").lastCodeSyncAt()).contains(NOW);
        verify(workspaceStore).save(org.mockito.ArgumentMatchers.any());
        verify(gitHub).fetchContributors("token", DIRECT);
        verify(gitHub).fetchLabels("token", DIRECT);
        verify(gitHub).fetchOverview("token", DIRECT);
        verify(pullRequests).fetchAssignableRepositoryUsers("token", DIRECT);
        verify(avatarCache).cache("https://avatars.example/alice");
    }

    @Test
    void forksSharingUpstreamReuseFreshStableIdCache()
    {
        workspaces.put("fork-one", workspace("fork-one", 300, UPSTREAM, Topology.FORK));
        workspaces.put("fork-two", workspace("fork-two", 300, UPSTREAM, Topology.FORK));
        when(gitHub.fetchContributors("token", UPSTREAM)).thenReturn(ImmutableList.of());
        when(gitHub.fetchLabels("token", UPSTREAM)).thenReturn(ImmutableList.of());
        when(gitHub.fetchOverview("token", UPSTREAM)).thenReturn(overview());

        service.synchronize("fork-one");
        service.synchronize("fork-two");

        verify(gitHub, times(1)).fetchContributors("token", UPSTREAM);
        verify(gitHub, times(1)).fetchLabels("token", UPSTREAM);
        verify(gitHub, times(1)).fetchOverview("token", UPSTREAM);
        assertThat(workspaces.get("fork-two").lastMetadataSyncAt()).contains(NOW);
    }

    @Test
    void freshWorkspaceTimestampUsesTheCachedSnapshot()
    {
        Workspace fresh = workspace("direct", 100, DIRECT, Topology.DIRECT)
                .withMetadataSyncAt(Optional.of(NOW), NOW);
        workspaces.put("direct", fresh);
        snapshots.put(100L, new RepositoryMetadataSnapshot(
                100,
                DIRECT,
                "Description",
                7,
                ImmutableMap.of("Java", 100L),
                ImmutableList.of(),
                ImmutableList.of(new RepositoryPickerUser(1, "alice", Optional.of("avatar-key"), false)),
                ImmutableList.of(),
                ImmutableList.of(),
                Optional.of(NOW),
                Optional.of(NOW),
                Optional.of(NOW),
                Optional.of(NOW)));

        service.synchronize("direct");

        verify(workspaceStore, never()).save(org.mockito.ArgumentMatchers.any());
        verify(gitHub, never()).fetchContributors("token", DIRECT);
        verify(gitHub, never()).fetchLabels("token", DIRECT);
        verify(gitHub, never()).fetchOverview("token", DIRECT);
        verify(pullRequests, never()).fetchAssignableRepositoryUsers("token", DIRECT);
    }

    @Test
    void freshForkWorkspaceStillRefreshesItsExpiredUpstreamOverview()
    {
        Workspace fork = workspace("fork", 300, UPSTREAM, Topology.FORK).withMetadataSyncAt(Optional.of(NOW.minusSeconds(60)), NOW);
        workspaces.put(fork.id(), fork);
        snapshots.put(300L, new RepositoryMetadataSnapshot(
                300, UPSTREAM, "Old upstream description", 1, ImmutableMap.of(),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                Optional.of(NOW.minusSeconds(3601)), Optional.of(NOW), Optional.of(NOW), Optional.of(NOW)));
        when(gitHub.fetchOverview("token", UPSTREAM)).thenReturn(overview());

        service.synchronize(fork.id());

        assertThat(service.getSnapshot(fork.id()).description()).isEqualTo("Description");
        assertThat(service.getSnapshot(fork.id()).overviewSyncedAt()).contains(NOW);
        assertThat(service.getSnapshot(fork.id()).repositoryId()).isEqualTo(300);
        assertThat(workspaces.get(fork.id()).lastMetadataSyncAt()).contains(NOW);
        verify(gitHub).fetchOverview("token", UPSTREAM);
        verifyNoMoreInteractions(gitHub);
        verifyNoInteractions(pullRequests, avatarCache);
    }

    @Test
    void missingUpstreamContributorsAreFetchedWithoutUsingTheForkCache()
    {
        Workspace fork = workspace("fork", 300, UPSTREAM, Topology.FORK).withMetadataSyncAt(Optional.of(NOW), NOW);
        workspaces.put(fork.id(), fork);
        RepositoryContributor forkContributor = new RepositoryContributor(1, "fork-user", "fork-avatar", false);
        RepositoryContributor upstreamContributor = new RepositoryContributor(2, "upstream-user", "upstream-avatar", false);
        RepositoryMetadataSnapshot forkSnapshot = new RepositoryMetadataSnapshot(
                fork.selectedRepositoryId(), fork.selectedRepository(), "Fork description", 0, ImmutableMap.of(),
                ImmutableList.of(forkContributor), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                Optional.of(NOW), Optional.of(NOW), Optional.of(NOW), Optional.of(NOW));
        snapshots.put(fork.selectedRepositoryId(), forkSnapshot);
        snapshots.put(300L, new RepositoryMetadataSnapshot(
                300, UPSTREAM, "Upstream description", 7, ImmutableMap.of(),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                Optional.of(NOW), Optional.empty(), Optional.of(NOW), Optional.of(NOW)));
        when(gitHub.fetchContributors("token", UPSTREAM)).thenReturn(ImmutableList.of(upstreamContributor));

        service.synchronize(fork.id());

        RepositoryMetadataSnapshot upstreamSnapshot = service.getSnapshot(fork.id());
        assertThat(upstreamSnapshot.repository()).isEqualTo(UPSTREAM);
        assertThat(upstreamSnapshot.repositoryId()).isEqualTo(300);
        assertThat(upstreamSnapshot.contributors()).containsExactly(upstreamContributor);
        assertThat(upstreamSnapshot.contributorsSyncedAt()).contains(NOW);
        assertThat(upstreamSnapshot.description()).isEqualTo("Upstream description");
        assertThat(snapshots.get(fork.selectedRepositoryId())).isEqualTo(forkSnapshot);
        verify(gitHub).fetchContributors("token", UPSTREAM);
        verifyNoMoreInteractions(gitHub);
        verifyNoInteractions(pullRequests, avatarCache);
    }

    @Test
    void fillsANewPickerProjectionEvenWhenTheWorkspaceTimestampIsFresh()
    {
        Workspace fresh = workspace("direct", 100, DIRECT, Topology.DIRECT)
                .withMetadataSyncAt(Optional.of(NOW), NOW);
        workspaces.put("direct", fresh);
        snapshots.put(100L, new RepositoryMetadataSnapshot(
                100,
                DIRECT,
                "Description",
                7,
                ImmutableMap.of("Java", 100L),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                Optional.of(NOW),
                Optional.of(NOW),
                Optional.empty(),
                Optional.of(NOW)));
        when(pullRequests.fetchAssignableRepositoryUsers("token", DIRECT))
                .thenReturn(ImmutableList.of(new GitHubRepositoryUser(
                        3, "alice", "https://avatars.example/alice", false)));
        when(avatarCache.cache("https://avatars.example/alice")).thenReturn(Optional.of("avatar-key"));

        service.synchronize("direct");

        assertThat(service.getSnapshot("direct").pickerUsers())
                .containsExactly(new RepositoryPickerUser(3, "alice", Optional.of("avatar-key"), false));
        verify(pullRequests).fetchAssignableRepositoryUsers("token", DIRECT);
        verify(gitHub, never()).fetchContributors("token", DIRECT);
        verify(gitHub, never()).fetchLabels("token", DIRECT);
        verify(gitHub, never()).fetchOverview("token", DIRECT);
    }

    @Test
    void weeklyRefreshKeepsThePreviousAvatarWhenRedownloadFails()
    {
        Workspace fresh = workspace("direct", 100, DIRECT, Topology.DIRECT)
                .withMetadataSyncAt(Optional.of(NOW), NOW);
        workspaces.put("direct", fresh);
        snapshots.put(100L, new RepositoryMetadataSnapshot(
                100,
                DIRECT,
                "Description",
                7,
                ImmutableMap.of("Java", 100L),
                ImmutableList.of(),
                ImmutableList.of(new RepositoryPickerUser(3, "alice", Optional.of("old-key"), false)),
                ImmutableList.of(),
                ImmutableList.of(),
                Optional.of(NOW),
                Optional.of(NOW),
                Optional.of(NOW.minusSeconds(8 * 24 * 60 * 60)),
                Optional.of(NOW)));
        when(pullRequests.fetchAssignableRepositoryUsers("token", DIRECT))
                .thenReturn(ImmutableList.of(new GitHubRepositoryUser(
                        3, "alice", "https://avatars.example/alice", false)));
        when(avatarCache.cache("https://avatars.example/alice")).thenReturn(Optional.empty());

        service.synchronize("direct");

        assertThat(service.getSnapshot("direct").pickerUsers())
                .containsExactly(new RepositoryPickerUser(3, "alice", Optional.of("old-key"), false));
        verify(pullRequests).fetchAssignableRepositoryUsers("token", DIRECT);
        verify(gitHub, never()).fetchContributors("token", DIRECT);
        verify(gitHub, never()).fetchLabels("token", DIRECT);
        verify(gitHub, never()).fetchOverview("token", DIRECT);
    }

    @Test
    void failedRefreshRetainsSnapshotAndReadyCode()
    {
        workspaces.put("direct", workspace("direct", 100, DIRECT, Topology.DIRECT));
        RepositoryMetadataSnapshot previous = new RepositoryMetadataSnapshot(
                100,
                DIRECT,
                "Description",
                7,
                ImmutableMap.of("Java", 100L),
                ImmutableList.of(new RepositoryContributor(1, "alice", "avatar", false)),
                ImmutableList.of(new RepositoryPickerUser(1, "alice", Optional.of("avatar-key"), false)),
                ImmutableList.of(new RepositoryLabel(2, "bug", "ff0000")),
                ImmutableList.of("java"),
                Optional.of(NOW),
                Optional.of(NOW.minusSeconds(8 * 24 * 60 * 60)),
                Optional.of(NOW.minusSeconds(8 * 24 * 60 * 60)),
                Optional.of(NOW.minusSeconds(31L * 24 * 60 * 60)));
        snapshots.put(100L, previous);
        when(gitHub.fetchContributors("token", DIRECT)).thenReturn(ImmutableList.of());
        when(gitHub.fetchLabels("token", DIRECT)).thenThrow(new IllegalStateException("provider down"));

        service.synchronize("direct");

        assertThat(service.getSnapshot("direct")).isEqualTo(previous);
        assertThat(workspaces.get("direct").lastCodeSyncAt()).contains(NOW);
        assertThat(workspaces.get("direct").lastMetadataSyncAt()).isEmpty();
        verify(metadataStore, never()).upsertContributors(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any());
    }

    private static RepositoryOverview overview()
    {
        return new RepositoryOverview(
                "Description", 7, ImmutableList.of("java"), ImmutableMap.of("Java", 100L));
    }

    private static Workspace workspace(String id, long monitoredId, RepoRef monitored, Topology topology)
    {
        RepoRef selected = topology == Topology.DIRECT ? monitored : RepoRef.of(id, "project");
        Optional<Long> upstreamId = topology == Topology.FORK ? Optional.of(monitoredId) : Optional.empty();
        Optional<RepoRef> upstream = topology == Topology.FORK ? Optional.of(monitored) : Optional.empty();
        return new Workspace(
                id,
                id,
                topology == Topology.DIRECT ? monitoredId : monitoredId + 1,
                selected,
                upstreamId,
                upstream,
                monitoredId,
                monitored,
                topology,
                "main",
                "https://github.com/" + selected.fullName() + ".git",
                topology == Topology.FORK ? Optional.of("main") : Optional.empty(),
                topology == Topology.FORK
                        ? Optional.of("https://github.com/" + monitored.fullName() + ".git")
                        : Optional.empty(),
                "/tmp/" + id,
                Optional.of(NOW),
                Optional.empty(),
                0,
                NOW,
                NOW);
    }
}
