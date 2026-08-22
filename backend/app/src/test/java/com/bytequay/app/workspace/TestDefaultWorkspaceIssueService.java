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
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.RepositoryIssueSnapshot;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.domain.Workspace.CodeState;
import com.bytequay.core.domain.Workspace.MetadataState;
import com.bytequay.core.domain.Workspace.Topology;
import com.bytequay.core.store.RepositoryIssueStore;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestDefaultWorkspaceIssueService
{
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");
    private static final RepoRef DIRECT = RepoRef.of("owner", "project");
    private static final RepoRef UPSTREAM = RepoRef.of("upstream", "project");

    private final Map<String, Workspace> workspaces = new HashMap<>();
    private final Map<Long, RepositoryIssueSnapshot> snapshots = new HashMap<>();
    private final WorkspaceStore workspaceStore = mock(WorkspaceStore.class);
    private final RepositoryIssueStore issueStore = mock(RepositoryIssueStore.class);
    private final GitHubRepoClient gitHub = mock(GitHubRepoClient.class);
    private final CredentialService credentials = mock(CredentialService.class);
    private DefaultWorkspaceIssueService service;

    @BeforeEach
    void setUp()
    {
        when(credentials.resolveGitHubToken()).thenReturn("token");
        when(workspaceStore.findById(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(workspaces.get(invocation.getArgument(0))));
        when(issueStore.find(org.mockito.ArgumentMatchers.anyLong()))
                .thenAnswer(invocation -> Optional.ofNullable(snapshots.get(invocation.getArgument(0))));
        doAnswer(invocation -> {
            long repositoryId = invocation.getArgument(0);
            RepoRef repository = invocation.getArgument(1);
            List<RepositoryIssue> issues = invocation.getArgument(2);
            Instant syncedAt = invocation.getArgument(3);
            snapshots.put(repositoryId, new RepositoryIssueSnapshot(
                    repositoryId, repository, issues, Optional.of(syncedAt)));
            return null;
        }).when(issueStore).replace(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyList(),
                org.mockito.ArgumentMatchers.any());
        service = new DefaultWorkspaceIssueService(
                workspaceStore,
                issueStore,
                gitHub,
                credentials,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void directAndForkSyncUseTheirMonitoredRepository()
    {
        workspaces.put("direct", workspace("direct", 100, DIRECT, Topology.DIRECT));
        workspaces.put("fork", workspace("fork", 300, UPSTREAM, Topology.FORK));
        when(gitHub.fetchOpenIssues("token", DIRECT)).thenReturn(ImmutableList.of(issue(1)));
        when(gitHub.fetchOpenIssues("token", UPSTREAM)).thenReturn(ImmutableList.of(issue(2)));

        assertThat(service.synchronize("direct").issues()).extracting(RepositoryIssue::id).containsExactly(1L);
        assertThat(service.synchronize("fork").issues()).extracting(RepositoryIssue::id).containsExactly(2L);
        verify(gitHub).fetchOpenIssues("token", DIRECT);
        verify(gitHub).fetchOpenIssues("token", UPSTREAM);
    }

    @Test
    void providerFailureKeepsPreviousSnapshotReadable()
    {
        workspaces.put("direct", workspace("direct", 100, DIRECT, Topology.DIRECT));
        RepositoryIssueSnapshot previous = new RepositoryIssueSnapshot(
                100, DIRECT, ImmutableList.of(issue(1)), Optional.of(NOW.minusSeconds(60)));
        snapshots.put(100L, previous);
        when(gitHub.fetchOpenIssues("token", DIRECT)).thenThrow(new IllegalStateException("provider down"));

        assertThatThrownBy(() -> service.synchronize("direct"))
                .isInstanceOfSatisfying(WorkspaceException.class,
                        failure -> assertThat(failure.code()).isEqualTo("ISSUE_SYNC_FAILED"));
        assertThat(service.list("direct")).isEqualTo(previous);
    }

    private static RepositoryIssue issue(long id)
    {
        return new RepositoryIssue(
                id, (int) id, "Issue " + id, "body", "alice", "avatar",
                "https://github.com/owner/project/issues/" + id,
                ImmutableList.of("bug"), ImmutableList.of("bob"), 1, NOW, NOW);
    }

    private static Workspace workspace(String id, long monitoredId, RepoRef monitored, Topology topology)
    {
        RepoRef selected = topology == Topology.DIRECT ? monitored : RepoRef.of(id, "project");
        return new Workspace(
                id,
                id,
                topology == Topology.DIRECT ? monitoredId : monitoredId + 1,
                selected,
                topology == Topology.FORK ? Optional.of("upstream-workspace") : Optional.empty(),
                topology == Topology.FORK ? Optional.of(monitoredId) : Optional.empty(),
                topology == Topology.FORK ? Optional.of(monitored) : Optional.empty(),
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
                CodeState.READY,
                MetadataState.READY,
                Optional.of(NOW),
                Optional.of(NOW),
                Optional.empty(),
                Optional.empty(),
                0,
                NOW,
                NOW);
    }
}
