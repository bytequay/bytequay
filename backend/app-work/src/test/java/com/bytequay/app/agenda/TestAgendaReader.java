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
package com.bytequay.app.agenda;

import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.spi.ReviewTool;
import com.bytequay.app.agent.IssueDevelopmentService;
import com.bytequay.app.jira.JiraService;
import com.bytequay.app.localreview.LocalReviewService;
import com.bytequay.app.notices.AppNotice;
import com.bytequay.app.notices.AppNoticeService;
import com.bytequay.app.repository.github.GitHubAccountClient;
import com.bytequay.app.service.InMemoryAppSettingsStore;
import com.bytequay.app.service.attention.AttentionMarkService;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.team.TeamService;
import com.bytequay.app.workitem.WorkItemService;
import com.bytequay.app.workitem.WorkItemView;
import com.bytequay.app.workspace.TaskPullRequestOwnership;
import com.bytequay.app.workspace.WorkspaceHostingService;
import com.bytequay.core.domain.AgendaCalendar;
import com.bytequay.core.domain.AgendaTarget;
import com.bytequay.core.domain.Epic;
import com.bytequay.core.domain.IssueEpic;
import com.bytequay.core.domain.PrActivitySummary;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.EpicStore;
import com.bytequay.core.store.IssueDevelopmentStore;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.core.store.RepositoryIssueStore;
import com.bytequay.core.store.RepositoryMetadataStore;
import com.bytequay.core.store.WorkItemStore;
import com.bytequay.core.store.WorkspaceStore;
import com.bytequay.integration.jira.JiraActivityReader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestAgendaReader
{
    private static final Instant SINCE = Instant.parse("2026-10-15T09:02:00Z");
    private static final Instant NOW = Instant.parse("2026-10-16T09:02:00Z");
    private static final String LONG_COMMENT = "Please split the retry policy into its own PR. " + "It mixes two changes. ".repeat(30) + "SECRET-TAIL";

    private final PullRequestService pullRequests = mock(PullRequestService.class);
    private final PrDetailStore details = mock(PrDetailStore.class);
    private final CredentialService credentials = mock(CredentialService.class);
    private final GitHubAccountClient github = mock(GitHubAccountClient.class);
    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final WorkItemStore items = mock(WorkItemStore.class);
    private final WorkItemService work = mock(WorkItemService.class);
    private final EpicStore epics = mock(EpicStore.class);
    private final IssueDevelopmentStore markers = mock(IssueDevelopmentStore.class);
    private final AttentionMarkService marks = mock(AttentionMarkService.class);
    private final AppNoticeService notices = mock(AppNoticeService.class);
    private final LocalReviewService localReviews = mock(LocalReviewService.class);
    private final ObjectMapper mapper = AgendaFixtures.mapper();

    @Test
    void pagesCarryShortFactsAndNeverTheTextBehindThem()
            throws Exception
    {
        PullRequest mine = pullRequest(31402, "me", PullRequest.Origin.AUTHORED, ImmutableList.of(), "CHANGES_REQUESTED", 30, 4);
        PullRequest asked = pullRequest(31377, "marlo-t", PullRequest.Origin.REVIEW_REQUESTED, ImmutableList.of("me"), null, 120, 14);
        PullRequest unrelated = pullRequest(31111, "someone", PullRequest.Origin.OTHER, ImmutableList.of(), null, 5, 5);
        when(credentials.cachedLogin()).thenReturn(Optional.of("me"));
        when(pullRequests.listAttentionCandidates()).thenReturn(ImmutableList.of(mine, asked, unrelated));
        when(pullRequests.findCachedLatestActivities(any())).thenReturn(ImmutableMap.of(mine.id(), new PrActivitySummary("commented", Optional.of("elric-t"),
                NOW.minusSeconds(3600), Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(LONG_COMMENT), Optional.empty(), OptionalInt.empty())));
        when(details.find(mine.id())).thenReturn(Optional.of(detail(ImmutableList.of(
                new PrTimelineEvent(1L, "commented", "elric-t", NOW.minusSeconds(3600), LONG_COMMENT, Optional.empty()),
                new PrTimelineEvent(2L, "commented", "codecov[bot]", NOW.minusSeconds(3500), "SECRET-BOT-REPORT", Optional.empty()),
                new PrTimelineEvent(3L, "committed", "me", NOW.minusSeconds(3400), "SECRET-COMMIT-MESSAGE", Optional.empty())))));

        AgendaSnapshot snapshot = reader().read(LocalDate.parse("2026-10-16"), SINCE, false, ImmutableList.of(), AgendaCalendar.of("off"));
        ReviewTool tool = new AgendaPages(snapshot, mapper).tool();
        StringBuilder everything = new StringBuilder();
        for (String kind : ImmutableList.of("overview", "changes", "mine", "tasks", "asks", "epics", "history")) {
            everything.append(tool.call().apply("{\"kind\":\"" + kind + "\"}"));
        }

        assertThat(everything.toString()).doesNotContain("SECRET-DESCRIPTION", "SECRET-BOT-REPORT", "SECRET-COMMIT-MESSAGE", "SECRET-TAIL", "pr:owner/repository#31111");
        JsonNode change = mapper.readTree(tool.call().apply("{\"kind\":\"changes\"}")).path("entries").path(0);
        assertThat(change.path("ref").asText()).isEqualTo("pr:owner/repository#31402");
        assertThat(ImmutableSet.copyOf(change.path("facts").elements()).stream().map(JsonNode::asText)).containsExactly("1 new reply", "1 new commit");
        assertThat(change.path("last").path("by").asText()).isEqualTo("elric-t");
        assertThat(change.path("last").path("text").asText()).startsWith("Please split the retry policy").hasSize(160).endsWith("…");
        JsonNode ask = mapper.readTree(tool.call().apply("{\"kind\":\"asks\"}")).path("entries").path(0);
        assertThat(ask.path("why").asText()).isEqualTo("review_requested");
        assertThat(ask.path("lines").asInt()).isEqualTo(134);
        JsonNode own = mapper.readTree(tool.call().apply("{\"kind\":\"mine\"}")).path("entries").path(0);
        assertThat(own.path("review").path("decision").asText()).isEqualTo("changes_requested");
        assertThat(snapshot.overview().counts().mine()).isEqualTo(1);
    }

    @Test
    void readsWhosePullRequestItIsByTheLoginAndWhetherItsMergeIsBlocked()
    {
        // GitHub's search finds a pull request Copilot opened for the user among theirs; the login says whose it is, as the pull request list does.
        PullRequest copilot = pullRequest(31500, "copilot-swe-agent", PullRequest.Origin.AUTHORED, ImmutableList.of(), "APPROVED", 5, 5);
        PullRequest blocked = pullRequest(31402, "me", PullRequest.Origin.AUTHORED, ImmutableList.of(), "APPROVED", 30, 4, "blocked");
        when(credentials.cachedLogin()).thenReturn(Optional.of("me"));
        when(pullRequests.listAttentionCandidates()).thenReturn(ImmutableList.of(copilot, blocked));

        AgendaSnapshot snapshot = reader().read(LocalDate.parse("2026-10-16"), SINCE, false, ImmutableList.of(), AgendaCalendar.of("off"));

        assertThat(snapshot.mine()).extracting(AgendaSnapshot.Mine::ref).containsExactly("pr:owner/repository#31402");
        // Approved is not ready to merge while GitHub blocks the merge.
        assertThat(snapshot.mine().getFirst().mergeBlocked()).isTrue();
    }

    @Test
    void leavesSnoozedPullRequestsOutUntilTheSnoozeEnds()
    {
        PullRequest asked = pullRequest(31377, "marlo-t", PullRequest.Origin.REVIEW_REQUESTED, ImmutableList.of("me"), null, 120, 14);
        PullRequest snoozed = pullRequest(31378, "marlo-t", PullRequest.Origin.REVIEW_REQUESTED, ImmutableList.of("me"), null, 10, 1);
        when(credentials.cachedLogin()).thenReturn(Optional.of("me"));
        when(pullRequests.listAttentionCandidates()).thenReturn(ImmutableList.of(asked, snoozed));
        when(marks.snoozedKeys()).thenReturn(ImmutableSet.of("owner/repository#31378"));

        AgendaSnapshot snapshot = reader().read(LocalDate.parse("2026-10-16"), SINCE, false, ImmutableList.of(), AgendaCalendar.of("off"));

        assertThat(snapshot.asks()).extracting(AgendaSnapshot.Ask::ref).containsExactly("pr:owner/repository#31377");
        assertThat(snapshot.refs()).doesNotContainKey("pr:owner/repository#31378");
    }

    @Test
    void readsCirclesWithoutAskingGitHubWhoTheUserIs()
    {
        // The assistant reads this inside a turn; on a cold cache, naming the account would be a request to GitHub.
        reader().read(LocalDate.parse("2026-10-16"), SINCE, false, ImmutableList.of(), AgendaCalendar.of("off"));

        verify(credentials, never()).resolveGitHubCredential();
        verifyNoInteractions(github);
    }

    @Test
    void leavesFindingsOutForTasksTheReviewToolsAreClosedTo()
    {
        // A completed task keeps its finished notice; reading its findings through the closed tools would only fail on every refresh.
        when(notices.current()).thenReturn(ImmutableList.of(new AppNotice("finished:turn", AppNotice.Kind.TURN_FINISHED, "00000000-0000-0000-0000-000000000001", "workspace",
                Optional.empty(), Optional.empty(), "Finished task", Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(), "The response is ready.",
                "owner/repository", "lane", Optional.empty(), NOW)));

        AgendaSnapshot snapshot = reader().read(LocalDate.parse("2026-10-16"), SINCE, false, ImmutableList.of(), AgendaCalendar.of("off"));

        assertThat(snapshot.tasks()).singleElement().satisfies(task -> assertThat(task.findings()).isEmpty());
        verify(localReviews, never()).findingsForAgent(any());
    }

    @Test
    void localEpicsOpenTheirWorkItemAndDoNotHideRemoteEpics()
    {
        Workspace workspace = mock(Workspace.class, withSettings().mockMaker(MockMakers.INLINE));
        when(workspace.isGitHub()).thenReturn(true);
        when(workspace.issueHost()).thenReturn("github.com");
        when(workspace.id()).thenReturn("workspace");
        when(workspace.monitoredRepository()).thenReturn(RepoRef.of("org", "repo"));
        when(workspace.createdAt()).thenReturn(SINCE);
        when(workspaces.findAll()).thenReturn(ImmutableList.of(workspace));
        WorkItem parent = new WorkItem("local-parent", "workspace", "Private connector work", WorkItem.Kind.DEVELOP, "Keep context local",
                WorkItem.CompletionRule.MERGED_DELIVERY, WorkItem.State.PLANNED, 0, ImmutableList.of(), ImmutableList.of(), NOW, NOW,
                WorkItem.Priority.NORMAL, ImmutableList.of(), Optional.empty(), 0, ImmutableList.of());
        when(items.find(parent.id())).thenReturn(Optional.of(parent));
        when(epics.find("LOCAL:local-parent")).thenReturn(Optional.of(Epic.of("LOCAL:local-parent", "workspace")));
        when(markers.findEpic("github.com", 41)).thenReturn(Optional.of(new IssueEpic("github.com", 41, "org/repo", 7, true, NOW)));
        when(work.list("workspace")).thenReturn(ImmutableList.of(work("local-child", "LOCAL:local-parent"), work("issue-child", "ISSUE:github.com:41")));

        AgendaSnapshot snapshot = reader().read(LocalDate.parse("2026-10-16"), SINCE, false, ImmutableList.of(), AgendaCalendar.of("off"));

        assertThat(snapshot.epics()).extracting(AgendaSnapshot.EpicEntry::title).containsExactly("Private connector work", "#7");
        assertThat(snapshot.epics().getFirst().ready()).extracting(AgendaSnapshot.Ready::ref).containsExactly(AgendaRefs.work("local-child"));
        assertThat(snapshot.refs().get(AgendaRefs.epic("LOCAL:local-parent")).target()).isEqualTo(AgendaTarget.of(AgendaTarget.Kind.WORK, "workspace", parent.id()));
        assertThat(snapshot.refs().get(AgendaRefs.epic("ISSUE:github.com:41")).target()).isEqualTo(AgendaTarget.issue("org/repo", 7, "workspace"));
        verifyNoInteractions(github);
    }

    private AgendaReader reader()
    {
        @SuppressWarnings("unchecked")
        ObjectProvider<JiraService> jira = mock(ObjectProvider.class);
        return new AgendaReader(pullRequests, details, credentials, mock(WorkspaceHostingService.class), workspaces, notices, mock(AgentTaskStore.class),
                mock(TaskPullRequestOwnership.class), localReviews, items, work, epics,
                markers, mock(RepositoryIssueStore.class), mock(IssueDevelopmentService.class), jira, mock(JiraActivityReader.class),
                new TeamService(new InMemoryAppSettingsStore(), mapper, credentials, github, mock(RepositoryMetadataStore.class)), marks, mock(AgendaDone.class));
    }

    private static WorkItemView work(String id, String epicId)
    {
        return new WorkItemView(id, "workspace", id, WorkItem.Kind.DEVELOP, "", WorkItem.CompletionRule.MERGED_DELIVERY,
                WorkItem.State.PLANNED, 0, ImmutableList.of(), ImmutableList.of(), NOW, NOW, WorkItem.Priority.NORMAL, ImmutableList.of(), Optional.of(epicId), 0, false,
                ImmutableList.of());
    }

    private static PullRequest pullRequest(int number, String author, PullRequest.Origin origin, List<String> requested, String decision, int additions, int deletions)
    {
        return pullRequest(number, author, origin, requested, decision, additions, deletions, "clean");
    }

    private static PullRequest pullRequest(int number, String author, PullRequest.Origin origin, List<String> requested, String decision, int additions, int deletions,
            String mergeableState)
    {
        return new PullRequest(number, "owner/repository", number, "PR " + number, author, "https://github.com/owner/repository/pull/" + number, NOW.minusSeconds(864000),
                NOW.minusSeconds(3400), origin, ImmutableList.of(), ImmutableMap.of(), false, null, null, null, requested, PullRequestDetail.CiStatus.PASSING,
                additions, deletions, 3, "open", null, null, true, mergeableState, NOW.minusSeconds(3400), "branch", null, NOW.minusSeconds(3400),
                requested.isEmpty() ? null : NOW.minusSeconds(259200), decision);
    }

    private static StoredPrDetail detail(List<PrTimelineEvent> timeline)
    {
        PrRawDetail raw = new PrRawDetail("PR 31402", "https://github.com/owner/repository/pull/31402", "open", "me", "", NOW.minusSeconds(864000), NOW, null,
                "SECRET-DESCRIPTION of the whole change", ImmutableList.of(), ImmutableMap.of(), ImmutableList.of(), null, false, true, "clean", 1, 1, 1, 0,
                ImmutableList.of(), "abc123", "head", "main");
        return new StoredPrDetail(raw, ImmutableList.of(), timeline, ImmutableList.of(), ImmutableList.of(), false);
    }
}
