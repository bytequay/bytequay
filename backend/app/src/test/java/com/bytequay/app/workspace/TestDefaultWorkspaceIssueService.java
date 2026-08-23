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

import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.app.repository.github.GitHubPullRequestWriteClient;
import com.bytequay.app.repository.github.GitHubRepoClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.RepositoryIssuePage;
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
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class TestDefaultWorkspaceIssueService
{
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");
    private static final RepoRef DIRECT = RepoRef.of("owner", "project");
    private static final RepoRef UPSTREAM = RepoRef.of("upstream", "project");

    private final Map<String, Workspace> workspaces = new HashMap<>();
    private final WorkspaceStore workspaceStore = mock(WorkspaceStore.class);
    private final RepositoryIssueStore issueStore = mock(RepositoryIssueStore.class);
    private final GitHubRepoClient gitHub = mock(GitHubRepoClient.class);
    private final GitHubPullRequestReadClient issueReads = mock(GitHubPullRequestReadClient.class);
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
                gitHub,
                issueReads,
                issueWrites,
                credentials,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void directAndForkSyncUseTheirMonitoredRepository()
    {
        Workspace direct = workspace("direct", 100, DIRECT, Topology.DIRECT);
        Workspace fork = workspace("fork", 300, UPSTREAM, Topology.FORK);
        workspaces.put("direct", direct);
        workspaces.put("fork", fork);
        when(gitHub.fetchOpenIssuesPage("token", DIRECT, 1))
                .thenReturn(new GitHubRepoClient.IssuePage(ImmutableList.of(issue(1)), false));
        when(gitHub.fetchOpenIssuesPage("token", UPSTREAM, 1))
                .thenReturn(new GitHubRepoClient.IssuePage(ImmutableList.of(issue(2)), false));

        service.synchronize("direct");
        service.synchronize("fork");
        verify(gitHub).fetchOpenIssuesPage("token", DIRECT, 1);
        verify(gitHub).fetchOpenIssuesPage("token", UPSTREAM, 1);
        verify(issueStore).saveSyncPage(
                org.mockito.ArgumentMatchers.eq(100L),
                org.mockito.ArgumentMatchers.eq(ImmutableList.of(issue(1))),
                anyString());
        verify(issueStore).saveSyncPage(
                org.mockito.ArgumentMatchers.eq(300L),
                org.mockito.ArgumentMatchers.eq(ImmutableList.of(issue(2))),
                anyString());
    }

    @Test
    void writesEachGitHubPageBeforeRequestingTheNextOne()
    {
        workspaces.put("direct", workspace("direct", 100, DIRECT, Topology.DIRECT));
        when(gitHub.fetchOpenIssuesPage("token", DIRECT, 1))
                .thenReturn(new GitHubRepoClient.IssuePage(ImmutableList.of(issue(1)), true));
        when(gitHub.fetchOpenIssuesPage("token", DIRECT, 2))
                .thenReturn(new GitHubRepoClient.IssuePage(ImmutableList.of(issue(2)), false));

        service.synchronize("direct");

        var order = inOrder(gitHub, issueStore);
        order.verify(gitHub).fetchOpenIssuesPage("token", DIRECT, 1);
        order.verify(issueStore).saveSyncPage(
                org.mockito.ArgumentMatchers.eq(100L),
                org.mockito.ArgumentMatchers.eq(ImmutableList.of(issue(1))),
                anyString());
        order.verify(gitHub).fetchOpenIssuesPage("token", DIRECT, 2);
        order.verify(issueStore).saveSyncPage(
                org.mockito.ArgumentMatchers.eq(100L),
                org.mockito.ArgumentMatchers.eq(ImmutableList.of(issue(2))),
                anyString());
        order.verify(issueStore).completeSync(
                org.mockito.ArgumentMatchers.eq(100L),
                org.mockito.ArgumentMatchers.eq(DIRECT),
                anyString(),
                org.mockito.ArgumentMatchers.eq(NOW));
    }

    @Test
    void readsOnlyTheRequestedDatabasePage()
    {
        Workspace direct = workspace("direct", 100, DIRECT, Topology.DIRECT);
        RepositoryIssuePage expected = new RepositoryIssuePage(
                100, DIRECT, ImmutableList.of(issue(1)), 2, 50, 101, false, Optional.of(NOW));
        workspaces.put("direct", direct);
        when(issueStore.findPage(100, DIRECT, 2, 50)).thenReturn(expected);

        assertThat(service.list("direct", 2, 50)).isSameAs(expected);
        verify(issueStore).findPage(100, DIRECT, 2, 50);
    }

    @Test
    void findsOneIssueOnlyInTheLocalProjection()
    {
        Workspace direct = workspace("direct", 100, DIRECT, Topology.DIRECT);
        workspaces.put("direct", direct);
        when(issueStore.find(100, 7)).thenReturn(Optional.of(issue(7)));

        assertThat(service.find("direct", 7)).contains(issue(7));
        verify(issueStore).find(100, 7);
        verifyNoMoreInteractions(credentials, gitHub, issueReads, issueWrites);
    }

    @Test
    void providerFailureDoesNotFinalizeOrHideThePreviousDatabasePage()
    {
        Workspace direct = workspace("direct", 100, DIRECT, Topology.DIRECT);
        RepositoryIssuePage previous = new RepositoryIssuePage(
                100, DIRECT, ImmutableList.of(issue(1)), 0, 50, 1, false,
                Optional.of(NOW.minusSeconds(60)));
        workspaces.put("direct", direct);
        when(issueStore.findPage(100, DIRECT, 0, 50)).thenReturn(previous);
        when(gitHub.fetchOpenIssuesPage("token", DIRECT, 1))
                .thenThrow(new IllegalStateException("provider down"));

        assertThatThrownBy(() -> service.synchronize("direct"))
                .isInstanceOfSatisfying(WorkspaceException.class,
                        failure -> assertThat(failure.code()).isEqualTo("ISSUE_SYNC_FAILED"));
        assertThat(service.list("direct", 0, 50)).isSameAs(previous);
        verify(issueStore, never()).completeSync(
                org.mockito.ArgumentMatchers.anyLong(),
                org.mockito.ArgumentMatchers.any(),
                anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void combinesIssueEventsAndCommentsWithoutTimelineCommentDuplicates()
    {
        workspaces.put("direct", workspace("direct", 100, DIRECT, Topology.DIRECT));
        PullRequestRef ref = PullRequestRef.of("owner", "project", 7);
        PrTimelineEvent assigned = event(1, "assigned", NOW.minusSeconds(20));
        PrTimelineEvent duplicateComment = event(2, "commented", NOW.minusSeconds(10));
        PrTimelineEvent comment = event(2, "commented", NOW.minusSeconds(10));
        when(issueReads.fetchPrTimeline("token", ref, null)).thenReturn(ImmutableList.of(assigned, duplicateComment));
        when(issueReads.fetchPrIssueComments("token", ref, null)).thenReturn(ImmutableList.of(comment));

        assertThat(service.timeline("direct", 7)).containsExactly(assigned, comment);
    }

    @Test
    void postsIssueCommentToTheMonitoredRepository()
    {
        workspaces.put("fork", workspace("fork", 300, UPSTREAM, Topology.FORK));
        PullRequestRef ref = PullRequestRef.of("upstream", "project", 9);
        PrTimelineEvent created = event(3, "commented", NOW);
        when(issueWrites.createIssueComment("token", ref, "Looks good")).thenReturn(created);

        assertThat(service.comment("fork", 9, "  Looks good  ")).isEqualTo(created);
        verify(issueWrites).createIssueComment("token", ref, "Looks good");
        verifyNoMoreInteractions(issueWrites);
    }

    @Test
    void loadsAndUpdatesIssueTypeOnTheMonitoredRepository()
    {
        workspaces.put("fork", workspace("fork", 300, UPSTREAM, Topology.FORK));
        PullRequestRef ref = PullRequestRef.of("upstream", "project", 9);
        when(gitHub.fetchIssueType("token", UPSTREAM, 9)).thenReturn("Task");
        when(gitHub.fetchIssueTypes("token", UPSTREAM)).thenReturn(ImmutableList.of("Task", "Bug"));
        when(issueWrites.updateIssueType("token", ref, "Bug")).thenReturn("Bug");

        assertThat(service.issueType("fork", 9))
                .isEqualTo(new WorkspaceIssueService.IssueTypeOptions("Task", ImmutableList.of("Task", "Bug")));
        assertThat(service.updateIssueType("fork", 9, " Bug "))
                .isEqualTo(new WorkspaceIssueService.IssueTypeOptions("Bug", ImmutableList.of("Task", "Bug")));
        verify(issueWrites).updateIssueType("token", ref, "Bug");
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
