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

import com.bytequay.app.jira.JiraDtos;
import com.bytequay.app.jira.JiraIssue;
import com.bytequay.app.jira.JiraService;
import com.bytequay.app.service.goal.GoalReferenceReader;
import com.bytequay.app.service.goal.WorkItemService;
import com.bytequay.app.service.goal.WorkItemView;
import com.bytequay.core.domain.GitHubIssueSource;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.RepositoryIssuePage;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.WorkItemLink;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.RepositoryIssueStore;
import com.bytequay.core.store.WorkItemStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
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
                WorkItem.Priority.NORMAL, 0, ImmutableList.of(), Optional.empty(), 0);
        WorkItemView view = new WorkItemView(local.id(), local.workspaceId(), local.goalId(), local.title(), local.kind(), local.acceptanceCriteria(),
                local.completionRule(), local.state(), local.revision(), ImmutableList.of(), local.history(), now, now, local.priority(), 0, ImmutableList.of(),
                Optional.empty(), 0);
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
                local.completionRule(), local.state(), 1, ImmutableList.of(link), local.history(), now, now, local.priority(), 0, ImmutableList.of(), Optional.empty(), 0);
        when(store.list("workspace")).thenReturn(ImmutableList.of(published));
        assertThat(catalog.list("workspace")).hasSize(2);
        UnifiedWorkItem exposed = catalog.read("workspace", "local");
        assertThat(exposed.source()).isEqualTo(UnifiedWorkItem.Source.GITHUB);
        assertThat(exposed.work().orElseThrow().acceptanceCriteria()).isEqualTo("Private context");
        assertThat(exposed.description()).isEqualTo("Remote context");
    }

    private static JiraIssue ticket(String workspaceId)
    {
        return new JiraIssue("connection", "cloud", workspaceId, "ENG-1", "https://example.atlassian.net/browse/ENG-1", "Jira request", "Jira context", "Task",
                new JiraDtos.Status("1", "Open", "new"), Optional.empty(), ImmutableList.of(), Optional.empty(), Optional.empty(), false, false,
                Instant.EPOCH, Optional.empty(), Optional.empty(), ImmutableList.of(), false, Optional.empty(), Optional.of(new JiraDtos.WorkspaceLink(workspaceId, "Workspace")));
    }
}
