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

import com.bytequay.app.repository.github.GitHubAccountClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.pr.PullRequestRefreshService;
import com.bytequay.core.domain.RemoteSource;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.domain.Workspace.Topology;
import com.bytequay.core.store.RemoteSourceStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestWorkspaceRepositoryIdentity
{
    private static final Instant NOW = Instant.parse("2026-08-29T00:00:00Z");

    private GitHubAccountClient gitHub;
    private RemoteSourceStore remoteSources;
    private WorkspaceStore workspaces;
    private WorkspaceRepositoryService service;

    @BeforeEach
    void setUp()
    {
        gitHub = mock(GitHubAccountClient.class);
        remoteSources = mock(RemoteSourceStore.class);
        workspaces = mock(WorkspaceStore.class);
        service = new WorkspaceRepositoryService(workspaces, remoteSources, gitHub, mock(CredentialService.class), Clock.fixed(NOW, ZoneOffset.UTC),
                new WorkspacePaths("/managed"), mock(WorkspaceProvisioningService.class), mock(WorkspaceMetadataService.class),
                mock(PullRequestRefreshService.class), Runnable::run);
        when(workspaces.findById("target")).thenReturn(Optional.of(directWorkspace("target", 404, RepoRef.of("target", "project"))));
    }

    @AfterEach
    void loadingRemoteIdentityNeverContactsGitHub()
    {
        verifyNoInteractions(gitHub);
    }

    @Test
    void reusesWatchedSelectedAndUpstreamIdentitiesAndPersistsThem()
    {
        Workspace selected = directWorkspace("selected", 101, RepoRef.of("trinodb", "trino"));
        Workspace linked = linkedWorkspace("linked", 202, RepoRef.of("contributor", "iceberg"), 303, RepoRef.of("apache", "iceberg"));
        RemoteSource selectedRemote = new RemoteSource("target", "oss", "https://github.com/TRINODB/trino.git");
        RemoteSource upstreamRemote = new RemoteSource("target", "iceberg", "https://github.com/apache/iceberg");
        when(workspaces.findAllRetained()).thenReturn(ImmutableList.of(selected, linked));
        when(remoteSources.findByWorkspace("target"))
                .thenReturn(ImmutableList.of(selectedRemote, upstreamRemote));

        assertThat(service.requireLocalRepository("target").extraRemotes()).containsExactly(
                selectedRemote.withRepositoryIdentity(101, RepoRef.of("trinodb", "trino")),
                upstreamRemote.withRepositoryIdentity(303, RepoRef.of("apache", "iceberg")));

        verify(remoteSources).save(selectedRemote.withRepositoryIdentity(101, RepoRef.of("trinodb", "trino")));
        verify(remoteSources).save(upstreamRemote.withRepositoryIdentity(303, RepoRef.of("apache", "iceberg")));
    }

    @Test
    void leavesUnmatchedAndNonGitHubRemotesWithoutProviderIdentity()
    {
        RemoteSource unmatched = new RemoteSource("target", "unknown", "https://github.com/unknown/project.git");
        RemoteSource nonGitHub = new RemoteSource("target", "internal", "https://git.example.com/team/project.git");
        when(workspaces.findAllRetained()).thenReturn(ImmutableList.of(directWorkspace("selected", 101, RepoRef.of("trinodb", "trino"))));
        when(remoteSources.findByWorkspace("target"))
                .thenReturn(ImmutableList.of(unmatched, nonGitHub));

        assertThat(service.requireLocalRepository("target").extraRemotes()).containsExactly(unmatched, nonGitHub);
        verify(remoteSources, never()).save(any());
    }

    @Test
    void keepsRememberedStableIdentityAndOnlyRefreshesItsCoordinates()
    {
        RemoteSource remembered = new RemoteSource("target", "remembered", "https://github.com/current/project.git")
                .withRepositoryIdentity(101, RepoRef.of("previous", "project"));
        RemoteSource renamed = new RemoteSource("target", "renamed", "https://github.com/old/name.git")
                .withRepositoryIdentity(202, RepoRef.of("old", "name"));
        RepoRef current = RepoRef.of("current", "project");
        when(workspaces.findAllRetained()).thenReturn(ImmutableList.of(directWorkspace("current", 202, current)));
        when(remoteSources.findByWorkspace("target")).thenReturn(ImmutableList.of(remembered, renamed));

        assertThat(service.requireLocalRepository("target").extraRemotes()).containsExactly(remembered, renamed.withRepositoryIdentity(202, current));

        verify(remoteSources).save(renamed.withRepositoryIdentity(202, current));
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
                "/managed/" + id + "/repository",
                Optional.of(NOW),
                Optional.of(NOW),
                0,
                NOW,
                NOW);
    }

    private static Workspace linkedWorkspace(String id, long selectedRepositoryId, RepoRef selectedRepository, long upstreamRepositoryId, RepoRef upstreamRepository)
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
                "/managed/" + id + "/repository",
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
