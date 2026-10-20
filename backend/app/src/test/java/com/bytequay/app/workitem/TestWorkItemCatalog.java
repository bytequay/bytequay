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
package com.bytequay.app.workitem;

import com.bytequay.app.jira.JiraService;
import com.bytequay.core.domain.GitHubIssueSource;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.RepositoryIssuePage;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.WorkItemContextLink;
import com.bytequay.core.domain.WorkItemLink;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.RepositoryIssueStore;
import com.bytequay.core.store.WorkItemStore;
import com.bytequay.core.store.WorkspaceStore;
import com.bytequay.integration.jira.JiraDtos;
import com.bytequay.integration.jira.JiraIssue;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestWorkItemCatalog
{
    @Test
    void readsAllThreeSourcesWithoutCreatingOrPublishingAndKeepsOneIdentityAfterExposure()
    {
        Instant now = Instant.EPOCH;
        WorkspaceStore workspaces = mock(WorkspaceStore.class);
        Workspace workspace = mock(Workspace.class, withSettings().mockMaker(MockMakers.INLINE));
        when(workspaces.findById("workspace")).thenReturn(Optional.of(workspace));
        when(workspace.monitoredRepositoryId()).thenReturn(1L);
        when(workspace.monitoredRepository()).thenReturn(RepoRef.of("org", "repo"));
        RepositoryIssue issue = new RepositoryIssue(10, 1, "Remote request", "Remote context", "alice", "", "https://github.com/org/repo/issues/1",
                ImmutableList.of(), ImmutableList.of(), 0, now, now);
        RepositoryIssueStore issues = mock(RepositoryIssueStore.class);
        when(issues.findPage(1, RepoRef.of("org", "repo"), 0, 100))
                .thenReturn(new RepositoryIssuePage(1, RepoRef.of("org", "repo"), ImmutableList.of(issue), 0, 100, 1, false, Optional.of(now)));
        JiraService jira = mock(JiraService.class);
        when(jira.cachedIssues()).thenReturn(ImmutableList.of(ticket("workspace"), ticket("other")));
        WorkItemStore store = mock(WorkItemStore.class);
        WorkItemService work = mock(WorkItemService.class);
        WorkItem local = new WorkItem("local", "workspace", Optional.empty(), "Private request", WorkItem.Kind.DEVELOP, "Private context",
                WorkItem.CompletionRule.MERGED_DELIVERY, WorkItem.State.PLANNED, 0, ImmutableList.of(), ImmutableList.of(), now, now,
                WorkItem.Priority.NORMAL, 0, ImmutableList.of(), Optional.empty(), 0, ImmutableList.of());
        WorkItemView view = new WorkItemView(local.id(), local.workspaceId(), local.goalId(), local.title(), local.kind(), local.acceptanceCriteria(),
                local.completionRule(), local.state(), local.revision(), ImmutableList.of(), local.history(), now, now, local.priority(), 0, ImmutableList.of(),
                Optional.empty(), 0, false, ImmutableList.of());
        when(store.list("workspace")).thenReturn(ImmutableList.of(local));
        when(work.list("workspace")).thenReturn(ImmutableList.of(view));
        WorkItemCatalog catalog = new WorkItemCatalog(workspaces, issues, jira, store, work);
        assertThat(catalog.list("workspace")).extracting(UnifiedWorkItem::source)
                .containsExactlyInAnyOrder(UnifiedWorkItem.Source.LOCAL, UnifiedWorkItem.Source.GITHUB, UnifiedWorkItem.Source.JIRA);
        assertThat(catalog.read("workspace", "local").description()).isEqualTo("Private context");
        assertThatThrownBy(() -> catalog.read("workspace", "other")).hasMessageContaining("not found");
        verify(store, never()).insert(any());

        WorkItemLink link = GoalReferenceReader.issueLink(new GitHubIssueSource("github.com", 10, "org/repo", 1, issue.htmlUrl(), issue.title(), issue.body(), now), now);
        WorkItem published = new WorkItem(local.id(), local.workspaceId(), local.goalId(), local.title(), local.kind(), local.acceptanceCriteria(),
                local.completionRule(), local.state(), 1, ImmutableList.of(link), local.history(), now, now, local.priority(), 0,
                ImmutableList.of(), Optional.empty(), 0, ImmutableList.of());
        when(store.list("workspace")).thenReturn(ImmutableList.of(published));
        assertThat(catalog.list("workspace")).hasSize(2);
        UnifiedWorkItem exposed = catalog.read("workspace", "local");
        assertThat(exposed.source()).isEqualTo(UnifiedWorkItem.Source.GITHUB);
        assertThat(exposed.work().orElseThrow().acceptanceCriteria()).isEqualTo("Private context");
        assertThat(exposed.description()).isEqualTo("Remote context");
    }

    @Test
    void contextLinksAcceptAllWorkspaceSourcesWithoutClaimingTheirOwnership()
    {
        ContextFixture fixture = new ContextFixture();
        fixture.catalog.linkContext("workspace", "owner", 0, "target");
        verify(fixture.work).changeContextLinks("workspace", "owner", 0,
                ImmutableList.of(new WorkItemContextLink("target", "target")), "Linked context: target");
        fixture.catalog.linkContext("workspace", "owner", 0, "ISSUE:github.com:10");
        verify(fixture.work).changeContextLinks("workspace", "owner", 0,
                ImmutableList.of(new WorkItemContextLink("ISSUE:github.com:10", "Remote request")), "Linked context: Remote request");
        String jiraId = fixture.catalog.list("workspace").stream().filter(item -> item.source() == UnifiedWorkItem.Source.JIRA).findFirst().orElseThrow().id();
        fixture.catalog.linkContext("workspace", "owner", 0, jiraId);
        verify(fixture.work).changeContextLinks("workspace", "owner", 0,
                ImmutableList.of(new WorkItemContextLink(jiraId, "Jira request")), "Linked context: Jira request");
        verify(fixture.store, never()).insert(any());
        verify(fixture.store, never()).update(any(), eq(0L));
    }

    @Test
    void contextLinksRejectSelfAndForeignTargetsIncludingAliases()
    {
        ContextFixture fixture = new ContextFixture();
        assertThatThrownBy(() -> fixture.catalog.linkContext("workspace", "owner", 0, "owner")).hasMessageContaining("itself");
        when(fixture.store.findByIdentity("ISSUE:github.com:10")).thenReturn(Optional.of(local("owner", ImmutableList.of())));
        assertThatThrownBy(() -> fixture.catalog.linkContext("workspace", "owner", 0, "ISSUE:github.com:10")).hasMessageContaining("itself");
        assertThatThrownBy(() -> fixture.catalog.linkContext("workspace", "owner", 0, "foreign")).hasMessageContaining("not found");
        assertThatThrownBy(() -> fixture.catalog.linkContext("workspace", "owner", -1, "target")).hasMessageContaining("revision");
        verify(fixture.work, never()).changeContextLinks(anyString(), anyString(), eq(0L), any(), anyString());

        fixture.owner(ImmutableList.of(new WorkItemContextLink("ISSUE:github.com:10", "Before publication")));
        WorkItemContextView formerTarget = fixture.catalog.contextLinks("workspace", "owner").getFirst();
        assertThat(formerTarget.available()).isFalse();
        assertThat(formerTarget.status()).isEqualTo("Same work item");
        fixture.catalog.unlinkContext("workspace", "owner", 0, "owner");
        verify(fixture.work).changeContextLinks("workspace", "owner", 0, ImmutableList.of(), "Unlinked context: Before publication");
    }

    @Test
    void remoteContextSurvivesImportAndCanBeUnlinkedUsingEitherIdentity()
    {
        ContextFixture fixture = new ContextFixture();
        WorkItemContextLink saved = new WorkItemContextLink("ISSUE:github.com:10", "Saved title");
        fixture.owner(ImmutableList.of(saved));
        WorkItem target = local("target", ImmutableList.of(new WorkItemContextLink("owner", "owner")));
        when(fixture.store.list("workspace")).thenReturn(ImmutableList.of(local("owner", ImmutableList.of(saved)), target));
        when(fixture.store.findByIdentity(saved.targetId())).thenReturn(Optional.of(target));
        assertThat(fixture.catalog.read("workspace", saved.targetId()).id()).isEqualTo("target");
        WorkItemContextView reference = fixture.catalog.contextLinks("workspace", "owner").getFirst();
        assertThat(reference.targetId()).isEqualTo(saved.targetId());
        assertThat(reference.id()).isEqualTo("target");
        assertThat(reference.title()).isEqualTo("target");
        assertThat(reference.available()).isTrue();
        fixture.catalog.linkContext("workspace", "owner", 0, "target");
        verify(fixture.work).changeContextLinks("workspace", "owner", 0, ImmutableList.of(saved), "");
        fixture.catalog.unlinkContext("workspace", "owner", 0, "target");
        fixture.catalog.unlinkContext("workspace", "owner", 0, saved.targetId());
        verify(fixture.work, org.mockito.Mockito.times(2)).changeContextLinks("workspace", "owner", 0, ImmutableList.of(), "Unlinked context: Saved title");
    }

    @Test
    void unavailableContextRetainsItsTitleAndRemainsRemovable()
    {
        ContextFixture fixture = new ContextFixture();
        fixture.owner(ImmutableList.of(new WorkItemContextLink("ISSUE:github.com:999", "Closed issue")));
        assertThat(fixture.catalog.contextLinks("workspace", "owner"))
                .containsExactly(new WorkItemContextView("ISSUE:github.com:999", "ISSUE:github.com:999", UnifiedWorkItem.Source.GITHUB,
                        "Closed issue", "Unavailable", false, Optional.empty()));
        fixture.catalog.unlinkContext("workspace", "owner", 0, "ISSUE:github.com:999");
        verify(fixture.work).changeContextLinks("workspace", "owner", 0, ImmutableList.of(), "Unlinked context: Closed issue");
    }

    @Test
    void foreignOwnershipDoesNotExposePrivateContextOrHideAnIndependentlyVisibleIssue()
    {
        ContextFixture fixture = new ContextFixture();
        WorkItem foreign = new WorkItem("private", "other-workspace", Optional.empty(), "Private title", WorkItem.Kind.DEVELOP, "Private body",
                WorkItem.CompletionRule.MERGED_DELIVERY, WorkItem.State.PLANNED, 0, ImmutableList.of(), ImmutableList.of(), Instant.EPOCH, Instant.EPOCH,
                WorkItem.Priority.NORMAL, 0, ImmutableList.of(), Optional.empty(), 0, ImmutableList.of());
        when(fixture.store.findByIdentity("ISSUE:github.com:10")).thenReturn(Optional.of(foreign));
        fixture.owner(ImmutableList.of(new WorkItemContextLink("ISSUE:github.com:10", "Saved title")));
        UnifiedWorkItem target = fixture.catalog.read("workspace", "ISSUE:github.com:10");
        assertThat(target.id()).isEqualTo("ISSUE:github.com:10");
        assertThat(target.title()).isEqualTo("Remote request");
        assertThat(target.work()).isEmpty();
        assertThat(fixture.catalog.contextLinks("workspace", "owner").getFirst().title()).isEqualTo("Remote request");
        assertThatThrownBy(() -> fixture.catalog.read("workspace", "private")).hasMessageContaining("not found");
    }

    private static WorkItem local(String id, List<WorkItemContextLink> links)
    {
        return new WorkItem(id, "workspace", Optional.empty(), id, WorkItem.Kind.DEVELOP, "Context", WorkItem.CompletionRule.MERGED_DELIVERY,
                WorkItem.State.PLANNED, 0, ImmutableList.of(), ImmutableList.of(), Instant.EPOCH, Instant.EPOCH, WorkItem.Priority.NORMAL, 0,
                ImmutableList.of(), Optional.empty(), 0, links);
    }

    private static final class ContextFixture
    {
        private final WorkItemStore store = mock(WorkItemStore.class);
        private final WorkItemService work = mock(WorkItemService.class);
        private final WorkItemCatalog catalog;

        private ContextFixture()
        {
            WorkspaceStore workspaces = mock(WorkspaceStore.class);
            Workspace workspace = mock(Workspace.class, withSettings().mockMaker(MockMakers.INLINE));
            when(workspaces.findById("workspace")).thenReturn(Optional.of(workspace));
            when(workspace.monitoredRepositoryId()).thenReturn(1L);
            when(workspace.monitoredRepository()).thenReturn(RepoRef.of("org", "repo"));
            RepositoryIssueStore issues = mock(RepositoryIssueStore.class);
            RepositoryIssue issue = new RepositoryIssue(10, 1, "Remote request", "Remote context", "alice", "", "https://github.com/org/repo/issues/1",
                    ImmutableList.of(), ImmutableList.of(), 0, Instant.EPOCH, Instant.EPOCH);
            when(issues.findPage(1, RepoRef.of("org", "repo"), 0, 100)).thenReturn(new RepositoryIssuePage(1, RepoRef.of("org", "repo"),
                    ImmutableList.of(issue), 0, 100, 1, false, Optional.of(Instant.EPOCH)));
            JiraService jira = mock(JiraService.class);
            when(jira.cachedIssues()).thenReturn(ImmutableList.of(ticket("workspace"), ticket("other")));
            catalog = new WorkItemCatalog(workspaces, issues, jira, store, work);
            owner(ImmutableList.of());
        }

        private void owner(List<WorkItemContextLink> links)
        {
            WorkItem owner = local("owner", links);
            when(work.requireItem("workspace", "owner")).thenReturn(owner);
            when(store.list("workspace")).thenReturn(ImmutableList.of(owner, local("target", ImmutableList.of())));
        }
    }

    private static JiraIssue ticket(String workspaceId)
    {
        return new JiraIssue("connection", "cloud", workspaceId, "ENG-1", "https://example.atlassian.net/browse/ENG-1", "Jira request", "Jira context", "Task",
                new JiraDtos.Status("1", "Open", "new"), Optional.empty(), ImmutableList.of(), Optional.empty(), Optional.empty(), false, false,
                Instant.EPOCH, Optional.empty(), Optional.empty(), ImmutableList.of(), false, Optional.empty(), Optional.of(new JiraDtos.WorkspaceLink(workspaceId, "Workspace")));
    }
}
