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

import com.bytequay.app.repository.github.GitHubPullRequestWriteClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.RepositoryIssueDetail;
import com.bytequay.core.domain.RepositoryIssuePage;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.domain.Workspace.CodeState;
import com.bytequay.core.domain.Workspace.MetadataState;
import com.bytequay.core.domain.Workspace.Topology;
import com.bytequay.core.store.RepositoryIssueDetailStore;
import com.bytequay.core.store.RepositoryIssueStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestDefaultWorkspaceIssueService
{
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");
    private static final RepoRef DIRECT = RepoRef.of("owner", "project");
    private static final RepoRef UPSTREAM = RepoRef.of("upstream", "project");

    private final Map<String, Workspace> workspaces = new HashMap<>();
    private final WorkspaceStore workspaceStore = mock(WorkspaceStore.class);
    private final RepositoryIssueStore issueStore = mock(RepositoryIssueStore.class);
    private final RepositoryIssueDetailStore detailStore = mock(RepositoryIssueDetailStore.class);
    private final GitHubPullRequestWriteClient issueWrites = mock(GitHubPullRequestWriteClient.class);
    private final CredentialService credentials = mock(CredentialService.class);
    private DefaultWorkspaceIssueService service;

    @BeforeEach
    void setUp()
    {
        when(credentials.resolveGitHubToken()).thenReturn("token");
        when(workspaceStore.findById(org.mockito.ArgumentMatchers.anyString()))
                .thenAnswer(invocation -> Optional.ofNullable(workspaces.get(invocation.getArgument(0))));
        service = new DefaultWorkspaceIssueService(
                workspaceStore,
                issueStore,
                detailStore,
                issueWrites,
                credentials,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void readsIssueRowsOnlyFromTheLocalProjection()
    {
        Workspace direct = workspace("direct", 100, DIRECT, Topology.DIRECT);
        RepositoryIssuePage expected = new RepositoryIssuePage(
                100, DIRECT, ImmutableList.of(issue(1)), 2, 50, 101, false, Optional.of(NOW));
        workspaces.put("direct", direct);
        when(issueStore.findPage(100, DIRECT, 2, 50)).thenReturn(expected);
        when(issueStore.find(100, 7)).thenReturn(Optional.of(issue(7)));

        assertThat(service.list("direct", 2, 50)).isSameAs(expected);
        assertThat(service.find("direct", 7)).contains(issue(7));
        verify(issueStore).findPage(100, DIRECT, 2, 50);
        verify(issueStore).find(100, 7);
        verifyNoInteractions(credentials, issueWrites);
    }

    @Test
    void readsIssueDetailOnlyFromTheLocalProjection()
    {
        workspaces.put("direct", workspace("direct", 100, DIRECT, Topology.DIRECT));
        RepositoryIssueDetail detail = detail(100, 7, "Task", event(1, "assigned", NOW));
        when(detailStore.find(100, 7)).thenReturn(Optional.of(detail));

        assertThat(service.detail("direct", 7)).isSameAs(detail);
        assertThat(service.detail("direct", 8)).isEqualTo(RepositoryIssueDetail.empty(100, 8));
        verifyNoInteractions(credentials, issueWrites);
    }

    @Test
    void postsIssueCommentAndAppendsItToTheLocalDetail()
    {
        workspaces.put("fork", workspace("fork", 300, UPSTREAM, Topology.FORK));
        PullRequestRef ref = PullRequestRef.of("upstream", "project", 9);
        PrTimelineEvent assigned = event(2, "assigned", NOW.minusSeconds(10));
        PrTimelineEvent created = event(3, "commented", NOW);
        when(detailStore.find(300, 9)).thenReturn(Optional.of(detail(300, 9, "Task", assigned)));
        when(issueWrites.createIssueComment("token", ref, "Looks good")).thenReturn(created);

        assertThat(service.comment("fork", 9, "  Looks good  ")).isEqualTo(created);
        verify(detailStore).save(detail(300, 9, "Task", assigned, created));
    }

    @Test
    void updatesIssueTypeInGitHubAndTheLocalDetail()
    {
        workspaces.put("fork", workspace("fork", 300, UPSTREAM, Topology.FORK));
        PullRequestRef ref = PullRequestRef.of("upstream", "project", 9);
        RepositoryIssueDetail stored = detail(300, 9, "Task");
        when(detailStore.find(300, 9)).thenReturn(Optional.of(stored));
        when(issueWrites.updateIssueType("token", ref, "Bug")).thenReturn("Bug");

        assertThat(service.updateIssueType("fork", 9, " Bug "))
                .isEqualTo(new WorkspaceIssueService.IssueTypeOptions("Bug", ImmutableList.of("Task", "Bug")));
        verify(detailStore).save(stored.withType("Bug"));
    }

    @Test
    void updatesIssueAssigneesAndLabelsAndRefreshesTheDatabaseProjection()
    {
        workspaces.put("fork", workspace("fork", 300, UPSTREAM, Topology.FORK));
        PullRequestRef ref = PullRequestRef.of("upstream", "project", 9);
        GitHubPullRequestWriteClient.IssueMetadata updated =
                new GitHubPullRequestWriteClient.IssueMetadata(
                        ImmutableList.of("alice"), ImmutableList.of("bug"), NOW);
        when(issueWrites.updateIssueMetadata(
                "token", ref, ImmutableList.of("alice"), ImmutableList.of("bug")))
                .thenReturn(updated);

        assertThat(service.updateMetadata(
                "fork", 9, ImmutableList.of(" alice "), ImmutableList.of("bug")))
                .isEqualTo(new WorkspaceIssueService.IssueMetadata(
                        ImmutableList.of("alice"), ImmutableList.of("bug")));
        verify(issueStore).updateMetadata(
                300, 9, ImmutableList.of("alice"), ImmutableList.of("bug"), NOW);
    }

    private static RepositoryIssueDetail detail(
            long repositoryId,
            int issueNumber,
            String currentType,
            PrTimelineEvent... events)
    {
        return new RepositoryIssueDetail(
                repositoryId,
                issueNumber,
                ImmutableList.copyOf(events),
                currentType,
                ImmutableList.of("Task", "Bug"),
                Optional.of(NOW));
    }

    private static PrTimelineEvent event(long id, String type, Instant timestamp)
    {
        return new PrTimelineEvent(id, type, "alice", timestamp, null, Optional.empty());
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
