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
package com.bytequay.app.service.epic;

import com.bytequay.agent.core.store.AgentUnitOfWork;
import com.bytequay.app.jira.JiraDtos;
import com.bytequay.app.jira.JiraService;
import com.bytequay.app.repository.github.GitHubRepoClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.goal.WorkItemService;
import com.bytequay.core.domain.Epic;
import com.bytequay.core.domain.EpicChild;
import com.bytequay.core.domain.EpicProposal;
import com.bytequay.core.domain.GoalItem;
import com.bytequay.core.domain.GoalItemKind;
import com.bytequay.core.domain.IssueEpic;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.WorkItemLink;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.IssueDevelopmentStore;
import com.bytequay.core.store.RepositoryIssueStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestEpicService
{
    private static final Instant NOW = Instant.EPOCH;
    private static final String EPIC = "ISSUE:github.com:1";
    private static final String JIRA_EPIC = "JIRA:5:cloud:10";
    private static final RepoRef REPOSITORY = RepoRef.of("org", "repo");

    private final InMemoryEpicStore records = new InMemoryEpicStore();
    private final EpicSources sources = mock(EpicSources.class);
    private final WorkItemService work = mock(WorkItemService.class);
    private final IssueDevelopmentStore markers = mock(IssueDevelopmentStore.class);
    private final GitHubRepoClient github = mock(GitHubRepoClient.class);
    private final CredentialService credentials = mock(CredentialService.class);
    private final JiraService jira = mock(JiraService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<JiraService> tickets = mock(ObjectProvider.class);
    private final AgentUnitOfWork transactions = new AgentUnitOfWork()
    {
        @Override
        public <T> T execute(Work<T> work)
        {
            return work.run();
        }
    };
    private final RepositoryIssueStore issues = mock(RepositoryIssueStore.class);
    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final EpicBreakdowns breakdowns = mock(EpicBreakdowns.class);
    private final EpicService service = new EpicService(records, sources, work, markers, github, credentials, tickets, transactions, issues, workspaces, breakdowns,
            Clock.fixed(NOW, ZoneOffset.UTC));

    @BeforeEach
    void setUp()
    {
        when(markers.findEpic("github.com", 1)).thenReturn(Optional.of(new IssueEpic("github.com", 1, "org/repo", 7, true, NOW)));
        when(credentials.resolveGitHubToken()).thenReturn("pat");
        when(tickets.getObject()).thenReturn(jira);
    }

    @Test
    void subIssuesJoinOnlyOnSyncOnceAndOneRemovedUpstreamIsNoLongerAChild()
    {
        GitHubRepoClient.SubIssue parse = new GitHubRepoClient.SubIssue(81, 8, "Parse", false, Optional.of("https://github.com/org/repo/issues/8"), REPOSITORY);
        GitHubRepoClient.SubIssue docs = new GitHubRepoClient.SubIssue(82, 3, "Docs", true, Optional.empty(), RepoRef.of("Other", "Docs"));
        when(github.fetchSubIssues("pat", REPOSITORY, 7)).thenReturn(ImmutableList.of(parse, docs));
        assertThat(service.view("workspace", EPIC).offered()).as("nothing read yet").isFalse();

        EpicView read = service.refreshChildren("workspace", EPIC, Optional.empty());
        assertThat(read.children()).extracting(EpicChild::identity, EpicChild::key, EpicChild::label, EpicChild::status, EpicChild::closed).containsExactly(
                tuple("ISSUE:github.com:81", "org/repo#8", "#8", "Open", false),
                tuple("ISSUE:github.com:82", "other/docs#3", "Other/Docs#3", "Closed", true));
        assertThat(read.childrenReadAt()).contains(NOW);
        assertThat(read.offered()).isTrue();
        verify(work, never()).joinEpic(anyString(), anyString(), any(WorkItemLink.class));

        EpicView synced = service.sync("workspace", EPIC);
        verify(work).joinEpic("workspace", EPIC, new WorkItemLink("ISSUE:github.com:81", new GoalItem(GoalItemKind.ISSUE, "org/repo#8", "#8", "Parse", NOW)));
        verify(work).joinEpic("workspace", EPIC, new WorkItemLink("ISSUE:github.com:82", new GoalItem(GoalItemKind.ISSUE, "other/docs#3", "Other/Docs#3", "Docs", NOW)));
        assertThat(synced.synced()).containsExactly("ISSUE:github.com:81", "ISSUE:github.com:82");
        assertThat(synced.offered()).isFalse();
        assertThat(service.sync("workspace", EPIC)).as("syncing again changes nothing").isEqualTo(synced);

        GitHubRepoClient.SubIssue later = new GitHubRepoClient.SubIssue(83, 9, "Ship", false, Optional.empty(), REPOSITORY);
        when(github.fetchSubIssues("pat", REPOSITORY, 7)).thenReturn(ImmutableList.of(docs, later));
        EpicView changed = service.refreshChildren("workspace", EPIC, Optional.empty());
        assertThat(changed.former()).extracting(EpicChild::identity).containsExactly("ISSUE:github.com:81");
        assertThat(changed.synced()).as("its work stays").contains("ISSUE:github.com:81");
        assertThat(changed.offered()).as("a child found later offers syncing again").isTrue();

        assertThat(service.dismiss("workspace", EPIC).offered()).isFalse();
        assertThat(service.refreshChildren("workspace", EPIC, Optional.empty()).offered()).as("Not now lasts while the children stay the same").isFalse();
        when(github.fetchSubIssues("pat", REPOSITORY, 7)).thenReturn(ImmutableList.of(docs, later, new GitHubRepoClient.SubIssue(84, 10, "Tune", false, Optional.empty(),
                REPOSITORY)));
        assertThat(service.refreshChildren("workspace", EPIC, Optional.empty()).offered()).isTrue();
    }

    @Test
    void concludingRecordsTheOutcomeOnceAllItsWorkIsDoneAndOnlyConcludedEpicsLeaveTheDrawer()
    {
        doThrow(new ResponseStatusException(HttpStatus.CONFLICT, "Conclude the Epic once all its work is done.")).when(work).requireEpicDone("workspace", EPIC);
        assertThatThrownBy(() -> service.conclude("workspace", EPIC, "Shipped")).hasMessageContaining("all its work is done");
        assertThat(service.concluded("workspace")).isEmpty();

        doNothing().when(work).requireEpicDone("workspace", EPIC);
        assertThatThrownBy(() -> service.conclude("workspace", EPIC, " ")).isInstanceOf(IllegalArgumentException.class);
        EpicView concluded = service.conclude("workspace", EPIC, "  Metadata caching shipped  ");
        assertThat(concluded.concludedAt()).contains(NOW);
        assertThat(concluded.outcome()).isEqualTo("Metadata caching shipped");
        assertThat(service.conclude("workspace", EPIC, "Another wording")).as("concluding again changes nothing").isEqualTo(concluded);
        service.dismiss("workspace", JIRA_EPIC);
        assertThat(service.concluded("workspace")).containsExactly(EPIC);
    }

    @Test
    void proposingReadsTheEpicsIssueAndAnAcceptedProposalBecomesItsWorkOnce()
    {
        Workspace workspace = mock(Workspace.class, withSettings().mockMaker(MockMakers.INLINE));
        when(workspace.monitoredRepositoryId()).thenReturn(5L);
        when(workspaces.findById("workspace")).thenReturn(Optional.of(workspace));
        when(issues.find(5, 7)).thenReturn(Optional.of(new RepositoryIssue(1, 7, "Iceberg REST catalog", "Cache table metadata", "author", "",
                "https://github.com/org/repo/issues/7", ImmutableList.of(), ImmutableList.of(), 0, NOW, NOW)));
        service.propose("workspace", EPIC, Optional.empty());
        verify(breakdowns).start("workspace", EPIC, new EpicBreakdowns.Source("#7", "Iceberg REST catalog", "Cache table metadata"));

        EpicProposal metrics = new EpicProposal("proposal-1", "Expose cache metrics", "Hits and misses are reported", "Report cache hits and misses per catalog.",
                Optional.empty(), Optional.of("The Epic asks for cache visibility."), Optional.empty());
        records.save(Epic.of(EPIC, "workspace").withProposals(ImmutableList.of(metrics,
                new EpicProposal("proposal-2", "Tune the cache", "Reads are faster", "Tune it.", Optional.empty(), Optional.empty(), Optional.empty()))));
        EpicView accepted = service.acceptProposal("workspace", EPIC, "proposal-1");
        assertThat(accepted.proposals()).extracting(EpicProposal::id).containsExactly("proposal-2");
        assertThat(service.acceptProposal("workspace", EPIC, "proposal-1")).as("accepting again changes nothing").isEqualTo(accepted);
        verify(work, times(1)).createInEpic("workspace", "proposal-1", EPIC, "Expose cache metrics", WorkItem.Kind.DEVELOP, """
                ### Description
                Report cache hits and misses per catalog.

                ### Done when
                Hits and misses are reported

                ### Evidence
                The Epic asks for cache visibility.""", WorkItem.CompletionRule.MERGED_DELIVERY);
        assertThat(service.dismissProposal("workspace", EPIC, "proposal-2").proposals()).isEmpty();
    }

    @Test
    void aJiraEpicsChildTicketsAreReadThroughTheConnectionThatShowsIt()
    {
        JiraDtos.Detail detail = mock(JiraDtos.Detail.class, withSettings().mockMaker(MockMakers.INLINE));
        when(detail.children()).thenReturn(ImmutableList.of(
                new JiraDtos.RelatedIssue("11", "ENG-11", "Docs", "Story", new JiraDtos.Status("3", "In Progress", "indeterminate"), Optional.empty(), Optional.empty()),
                new JiraDtos.RelatedIssue("12", "ENG-12", "Ship", "Task", new JiraDtos.Status("4", "Done", "done"), Optional.empty(), Optional.empty())));
        when(detail.childrenTruncated()).thenReturn(true);
        when(jira.refreshDetail("connection", "cloud", "10", false)).thenReturn(detail);

        EpicView read = service.refreshChildren("workspace", JIRA_EPIC, Optional.of("connection"));
        assertThat(read.children()).extracting(EpicChild::identity, EpicChild::key, EpicChild::label, EpicChild::status, EpicChild::closed).containsExactly(
                tuple("JIRA:5:cloud:11", "connection:cloud:11", "ENG-11", "In Progress", false),
                tuple("JIRA:5:cloud:12", "connection:cloud:12", "ENG-12", "Done", true));
        assertThat(read.childrenTruncated()).isTrue();
        assertThatThrownBy(() -> service.refreshChildren("workspace", JIRA_EPIC, Optional.empty())).hasMessageContaining("connection is required");
    }

    @Test
    void markingALocalItemKeepsItsEpicRecordAndUnmarkingReleasesItsWork()
    {
        WorkItem item = localItem(Optional.empty());
        when(work.requireItem("workspace", item.id())).thenReturn(item);
        String epicId = EpicSources.localEpicId(item.id());
        service.setLocalEpic("workspace", item.id(), true);
        assertThat(records.find(epicId)).contains(Epic.of(epicId, "workspace"));

        Epic planned = records.find(epicId).orElseThrow().withProposals(ImmutableList.of(new EpicProposal("proposal-1", "Parse Hive fields", "Fields parse",
                "Cover slash characters", Optional.empty(), Optional.empty(), Optional.empty())));
        records.save(planned);
        service.setLocalEpic("workspace", item.id(), true);
        assertThat(records.find(epicId)).as("marking again retains planning work").contains(planned);
        service.setLocalEpic("workspace", item.id(), false);
        verify(work).releaseEpic("workspace", epicId);
        verifyNoInteractions(github, jira, credentials, tickets, markers, issues);
    }

    @Test
    void promotingALocalItemRequiresItsWorkspaceAndNoParentEpic()
    {
        WorkItem assigned = localItem(Optional.of(EPIC));
        when(work.requireItem("workspace", assigned.id())).thenReturn(assigned);
        assertThatThrownBy(() -> service.setLocalEpic("workspace", assigned.id(), true)).isInstanceOf(IllegalArgumentException.class);
        when(work.requireItem("other", assigned.id())).thenThrow(new IllegalArgumentException("Work belongs to workspace workspace"));
        assertThatThrownBy(() -> service.setLocalEpic("other", assigned.id(), true)).hasMessageContaining("belongs to workspace workspace");
        assertThat(records.find(EpicSources.localEpicId(assigned.id()))).isEmpty();
        verify(work, never()).releaseEpic(anyString(), anyString());
    }

    @Test
    void aNewLocalEpicIsCreatedAndMarkedInOneStep()
    {
        WorkItem item = localItem(Optional.empty());
        when(work.requireItem("workspace", item.id())).thenReturn(item);
        String epicId = EpicSources.localEpicId(item.id());
        service.createLocalEpic("workspace", item.id(), "Cache metadata", " Cache per catalog. ", " Hive keeps its own cache. ");
        verify(work).create("workspace", item.id(), Optional.empty(), "Cache metadata", WorkItem.Kind.DEVELOP, "Cache per catalog.\n\n### Context\n\nHive keeps its own cache.",
                WorkItem.CompletionRule.MERGED_DELIVERY);
        assertThat(records.find(epicId)).contains(Epic.of(epicId, "workspace"));

        service.createLocalEpic("workspace", item.id(), "Cache metadata", "", "Only context");
        verify(work).create("workspace", item.id(), Optional.empty(), "Cache metadata", WorkItem.Kind.DEVELOP, "### Context\n\nOnly context", WorkItem.CompletionRule.MERGED_DELIVERY);
        assertThatThrownBy(() -> service.createLocalEpic("workspace", "work-2", "Too long", "x".repeat(65_537), ""))
                .hasMessageContaining("exceed 65536 characters");
        verifyNoInteractions(github, jira, credentials, tickets, markers, issues);
    }

    @Test
    void localRefreshStaysLocalAndBreakdownReadsTheSavedTitleAndDescription()
    {
        WorkItem item = localItem(Optional.empty());
        when(work.requireItem("workspace", item.id())).thenReturn(item);
        String epicId = EpicSources.localEpicId(item.id());
        service.setLocalEpic("workspace", item.id(), true);

        EpicView before = service.view("workspace", epicId);
        assertThat(service.refreshChildren("workspace", epicId, Optional.empty())).isEqualTo(before);
        assertThat(before.childrenReadAt()).as("there is no remote child collection to refresh").isEmpty();
        service.propose("workspace", epicId, Optional.empty());
        verify(breakdowns).start("workspace", epicId, new EpicBreakdowns.Source("Local", item.title(), item.acceptanceCriteria()));
        verifyNoInteractions(github, jira, credentials, tickets, markers, issues);
    }

    private static WorkItem localItem(Optional<String> epicId)
    {
        return new WorkItem("work-1", "workspace", Optional.empty(), "Hive field names", WorkItem.Kind.DEVELOP, "Read fields containing slash characters",
                WorkItem.CompletionRule.MERGED_DELIVERY, WorkItem.State.PLANNED, 0, ImmutableList.of(), ImmutableList.of(), NOW, NOW,
                WorkItem.Priority.NORMAL, 0, ImmutableList.of(), epicId, 0, ImmutableList.of());
    }
}
