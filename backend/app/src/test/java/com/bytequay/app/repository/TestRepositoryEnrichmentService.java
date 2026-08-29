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
package com.bytequay.app.repository;

import com.bytequay.core.domain.CommitEnrichment;
import com.bytequay.core.domain.CommitEnrichmentSnapshot;
import com.bytequay.core.domain.RemoteSource;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.domain.Workspace.Topology;
import com.bytequay.core.store.CommitHistoryStore;
import com.bytequay.core.store.RemoteSourceStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestRepositoryEnrichmentService
{
    private static final Instant NOW = Instant.parse("2026-08-29T00:00:00Z");

    private CommitHistoryStore commitHistory;
    private RemoteSourceStore remoteSources;
    private WorkspaceStore workspaces;
    private RepositoryEnrichmentService service;

    @BeforeEach
    void setUp()
    {
        commitHistory = mock(CommitHistoryStore.class);
        remoteSources = mock(RemoteSourceStore.class);
        workspaces = mock(WorkspaceStore.class);
        service = new RepositoryEnrichmentService(commitHistory, remoteSources, workspaces);
    }

    @Test
    void delegatesCommitEnrichmentReadsAndWrites()
    {
        CommitEnrichment value = enrichment(166_515_022L, "abc123");
        CommitEnrichmentSnapshot snapshot = new CommitEnrichmentSnapshot(ImmutableList.of(value));
        when(commitHistory.findEnrichment(166_515_022L, ImmutableList.of("abc123")))
                .thenReturn(snapshot);

        assertThat(service.findCommits(166_515_022L, ImmutableList.of("abc123")))
                .isSameAs(snapshot);
        service.upsertCommits(ImmutableList.of(value));

        verify(commitHistory).findEnrichment(166_515_022L, ImmutableList.of("abc123"));
        verify(commitHistory).upsertEnrichment(ImmutableList.of(value));
    }

    @Test
    void reusesWatchedSelectedAndUpstreamIdentitiesAndPersistsThem()
    {
        Workspace selected = directWorkspace("selected", 101, RepoRef.of("trinodb", "trino"));
        Workspace linked = linkedWorkspace(
                "linked",
                202,
                RepoRef.of("contributor", "iceberg"),
                303,
                RepoRef.of("apache", "iceberg"));
        RemoteSource selectedRemote = new RemoteSource(
                "target", "oss", "https://github.com/TRINODB/trino.git");
        RemoteSource upstreamRemote = new RemoteSource(
                "target", "iceberg", "https://github.com/apache/iceberg");
        when(workspaces.findAll()).thenReturn(ImmutableList.of(selected, linked));
        when(remoteSources.findByWorkspace("target"))
                .thenReturn(ImmutableList.of(selectedRemote, upstreamRemote));

        assertThat(service.enrichRemoteSources("target")).containsExactly(
                selectedRemote.withRepositoryIdentity(101, RepoRef.of("trinodb", "trino")),
                upstreamRemote.withRepositoryIdentity(303, RepoRef.of("apache", "iceberg")));

        verify(remoteSources).save(
                selectedRemote.withRepositoryIdentity(101, RepoRef.of("trinodb", "trino")));
        verify(remoteSources).save(
                upstreamRemote.withRepositoryIdentity(303, RepoRef.of("apache", "iceberg")));
    }

    @Test
    void leavesUnmatchedAndNonGitHubRemotesWithoutProviderIdentity()
    {
        RemoteSource unmatched = new RemoteSource(
                "target", "unknown", "https://github.com/unknown/project.git");
        RemoteSource nonGitHub = new RemoteSource(
                "target", "internal", "https://git.example.com/team/project.git");
        when(workspaces.findAll()).thenReturn(ImmutableList.of(
                directWorkspace("selected", 101, RepoRef.of("trinodb", "trino"))));
        when(remoteSources.findByWorkspace("target"))
                .thenReturn(ImmutableList.of(unmatched, nonGitHub));

        assertThat(service.enrichRemoteSources("target")).containsExactly(unmatched, nonGitHub);
        verify(remoteSources, never()).save(any());
    }

    @Test
    void keepsRememberedStableIdentityAndOnlyRefreshesItsCoordinates()
    {
        RemoteSource remembered = new RemoteSource(
                "target", "remembered", "https://github.com/current/project.git")
                .withRepositoryIdentity(101, RepoRef.of("previous", "project"));
        RemoteSource renamed = new RemoteSource(
                "target", "renamed", "https://github.com/old/name.git")
                .withRepositoryIdentity(202, RepoRef.of("old", "name"));
        RepoRef current = RepoRef.of("current", "project");
        when(workspaces.findAll()).thenReturn(ImmutableList.of(directWorkspace("current", 202, current)));
        when(remoteSources.findByWorkspace("target")).thenReturn(ImmutableList.of(remembered, renamed));

        assertThat(service.enrichRemoteSources("target")).containsExactly(
                remembered,
                renamed.withRepositoryIdentity(202, current));

        verify(remoteSources).save(renamed.withRepositoryIdentity(202, current));
    }

    private static CommitEnrichment enrichment(long repositoryId, String sha)
    {
        return new CommitEnrichment(
                repositoryId,
                sha,
                Optional.of("author"),
                Optional.of("a".repeat(64)),
                Optional.of("committer"),
                Optional.of("b".repeat(64)),
                Optional.of(42),
                Optional.of("Example pull request"),
                Optional.of("https://github.com/example/project/pull/42"),
                Optional.of(NOW),
                Optional.of(NOW),
                NOW);
    }

    private static Workspace directWorkspace(String id, long repositoryId, RepoRef repository)
    {
        return new Workspace(
                id,
                repository.toString(),
                repositoryId,
                repository,
                Optional.empty(),
                Optional.empty(),
                repositoryId,
                repository,
                Topology.DIRECT,
                "main",
                cloneUrl(repository),
                Optional.empty(),
                Optional.empty(),
                "/managed/" + id,
                Optional.of(NOW),
                Optional.of(NOW),
                0,
                NOW,
                NOW);
    }

    private static Workspace linkedWorkspace(
            String id,
            long selectedRepositoryId,
            RepoRef selectedRepository,
            long upstreamRepositoryId,
            RepoRef upstreamRepository)
    {
        return new Workspace(
                id,
                selectedRepository.toString(),
                selectedRepositoryId,
                selectedRepository,
                Optional.of(upstreamRepositoryId),
                Optional.of(upstreamRepository),
                upstreamRepositoryId,
                upstreamRepository,
                Topology.FORK,
                "main",
                cloneUrl(selectedRepository),
                Optional.of("main"),
                Optional.of(cloneUrl(upstreamRepository)),
                "/managed/" + id,
                Optional.of(NOW),
                Optional.of(NOW),
                0,
                NOW,
                NOW);
    }

    private static String cloneUrl(RepoRef repository)
    {
        return "https://github.com/" + repository.owner() + "/" + repository.repo() + ".git";
    }
}
