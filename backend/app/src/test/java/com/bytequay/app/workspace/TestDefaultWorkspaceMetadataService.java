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

import com.bytequay.app.repository.github.GitHubRepoClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryContributor;
import com.bytequay.core.domain.RepositoryLabel;
import com.bytequay.core.domain.RepositoryMetadataSnapshot;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.domain.Workspace.Topology;
import com.bytequay.core.store.RepositoryMetadataStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
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
    private final CredentialService credentials = mock(CredentialService.class);
    private DefaultWorkspaceMetadataService service;

    @BeforeEach
    void setUp()
    {
        when(credentials.resolveGitHubToken()).thenReturn("token");
        when(workspaceStore.findById(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(workspaces.get(invocation.getArgument(0))));
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
            List<RepositoryContributor> contributors = invocation.getArgument(2);
            Instant syncedAt = invocation.getArgument(3);
            RepositoryMetadataSnapshot previous = snapshots.getOrDefault(
                    id, RepositoryMetadataSnapshot.empty(id, repository));
            snapshots.put(id, new RepositoryMetadataSnapshot(
                    id, repository, contributors, previous.labels(), previous.topics(),
                    Optional.of(syncedAt), previous.labelsTopicsSyncedAt()));
            return null;
        }).when(metadataStore).upsertContributors(
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
                    id, repository, previous.contributors(), labels, topics,
                    previous.contributorsSyncedAt(), Optional.of(syncedAt)));
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
                credentials,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void directUsesSelectedRepositoryAndRefreshesBothProjections()
    {
        workspaces.put("direct", workspace("direct", 100, DIRECT, Topology.DIRECT));
        when(gitHub.fetchContributors("token", DIRECT))
                .thenReturn(ImmutableList.of(new RepositoryContributor(1, "alice", "avatar", false)));
        when(gitHub.fetchLabels("token", DIRECT))
                .thenReturn(ImmutableList.of(new RepositoryLabel(2, "bug", "ff0000")));
        when(gitHub.fetchTopics("token", DIRECT)).thenReturn(ImmutableList.of("java"));

        WorkspaceMetadataSyncResult result = service.synchronize("direct");

        assertThat(result.successful()).isTrue();
        assertThat(result.contributorCount()).isEqualTo(1);
        assertThat(workspaces.get("direct").lastCodeSyncAt()).contains(NOW);
        verify(workspaceStore).save(org.mockito.ArgumentMatchers.any());
        verify(gitHub).fetchContributors("token", DIRECT);
        verify(gitHub).fetchLabels("token", DIRECT);
        verify(gitHub).fetchTopics("token", DIRECT);
    }

    @Test
    void forksSharingUpstreamReuseFreshStableIdCache()
    {
        workspaces.put("fork-one", workspace("fork-one", 300, UPSTREAM, Topology.FORK));
        workspaces.put("fork-two", workspace("fork-two", 300, UPSTREAM, Topology.FORK));
        when(gitHub.fetchContributors("token", UPSTREAM)).thenReturn(ImmutableList.of());
        when(gitHub.fetchLabels("token", UPSTREAM)).thenReturn(ImmutableList.of());
        when(gitHub.fetchTopics("token", UPSTREAM)).thenReturn(ImmutableList.of());

        service.synchronize("fork-one");
        service.synchronize("fork-two");

        verify(gitHub, times(1)).fetchContributors("token", UPSTREAM);
        verify(gitHub, times(1)).fetchLabels("token", UPSTREAM);
        verify(gitHub, times(1)).fetchTopics("token", UPSTREAM);
        assertThat(workspaces.get("fork-two").lastMetadataSyncAt()).contains(NOW);
    }

    @Test
    void freshWorkspaceTimestampUsesTheCachedSnapshot()
    {
        Workspace fresh = workspace("direct", 100, DIRECT, Topology.DIRECT)
                .withMetadataSyncAt(Optional.of(NOW), NOW);
        workspaces.put("direct", fresh);

        WorkspaceMetadataSyncResult result = service.synchronize("direct");

        assertThat(result.successful()).isTrue();
        verify(workspaceStore, never()).save(org.mockito.ArgumentMatchers.any());
        verify(gitHub, never()).fetchContributors("token", DIRECT);
        verify(gitHub, never()).fetchLabels("token", DIRECT);
        verify(gitHub, never()).fetchTopics("token", DIRECT);
    }

    @Test
    void failedRefreshRetainsSnapshotAndReadyCode()
    {
        workspaces.put("direct", workspace("direct", 100, DIRECT, Topology.DIRECT));
        RepositoryMetadataSnapshot previous = new RepositoryMetadataSnapshot(
                100,
                DIRECT,
                ImmutableList.of(new RepositoryContributor(1, "alice", "avatar", false)),
                ImmutableList.of(new RepositoryLabel(2, "bug", "ff0000")),
                ImmutableList.of("java"),
                Optional.of(NOW.minusSeconds(8 * 24 * 60 * 60)),
                Optional.of(NOW.minusSeconds(31L * 24 * 60 * 60)));
        snapshots.put(100L, previous);
        when(gitHub.fetchContributors("token", DIRECT)).thenReturn(ImmutableList.of());
        when(gitHub.fetchLabels("token", DIRECT)).thenThrow(new IllegalStateException("provider down"));

        WorkspaceMetadataSyncResult result = service.synchronize("direct");

        assertThat(result.successful()).isFalse();
        assertThat(service.getSnapshot("direct")).isEqualTo(previous);
        assertThat(workspaces.get("direct").lastCodeSyncAt()).contains(NOW);
        assertThat(workspaces.get("direct").lastMetadataSyncAt()).isEmpty();
        verify(metadataStore, never()).upsertContributors(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any());
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
