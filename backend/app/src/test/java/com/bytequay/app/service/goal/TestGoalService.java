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
package com.bytequay.app.service.goal;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskNotFoundException;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.app.jira.JiraDtos;
import com.bytequay.app.jira.JiraIssue;
import com.bytequay.app.jira.JiraService;
import com.bytequay.core.domain.GoalItem;
import com.bytequay.core.domain.GoalItemKind;
import com.bytequay.core.domain.GoalSource;
import com.bytequay.core.domain.IssueTask;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestPage;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.RepositoryIssuePage;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.IssueDevelopmentStore;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.RepositoryIssueStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestGoalService
{
    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");
    private static final RepoRef REPO = RepoRef.of("trinodb", "trino");
    private static final String WORKSPACE = "workspace-1";
    private static final TaskId TASK = TaskId.random();

    private final InMemoryGoalStore goals = new InMemoryGoalStore();
    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final PullRequestStore pullRequests = mock(PullRequestStore.class);
    private final RepositoryIssueStore issues = mock(RepositoryIssueStore.class);
    private final IssueDevelopmentStore development = mock(IssueDevelopmentStore.class);
    private final JiraService jira = mock(JiraService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<JiraService> jiraProvider = mock(ObjectProvider.class);
    private final AgentTaskService tasks = mock(AgentTaskService.class);
    private final GoalService service = new GoalServiceImpl(goals, workspaces, pullRequests, issues, development, jiraProvider, tasks, new TickingClock());

    @BeforeEach
    void setUp()
    {
        Workspace workspace = mock(Workspace.class, withSettings().mockMaker(MockMakers.INLINE));
        when(workspace.id()).thenReturn(WORKSPACE);
        when(workspace.monitoredRepository()).thenReturn(REPO);
        when(workspace.monitoredRepositoryId()).thenReturn(7L);
        when(workspaces.findById(WORKSPACE)).thenReturn(Optional.of(workspace));
        when(jiraProvider.getObject()).thenReturn(jira);

        PullRequest merged = pullRequest(30702, "Retry Iceberg commits", "merged", false);
        PullRequest draft = pullRequest(31264, "Keep deletion vectors in checkpoints", "open", true);
        when(pullRequests.findIdByRepoAndNumber("trinodb/trino", 30702)).thenReturn(Optional.of(1L));
        when(pullRequests.findIdByRepoAndNumber("trinodb/trino", 31264)).thenReturn(Optional.of(2L));
        when(pullRequests.findById(1L)).thenReturn(Optional.of(merged));
        when(pullRequests.findById(2L)).thenReturn(Optional.of(draft));
        when(pullRequests.findRepositoryPage(eq("trinodb/trino"), anyInt(), anyInt(), anyString()))
                .thenReturn(new PullRequestPage(ImmutableList.of(draft), 0, 8, 1, 1, false));

        RepositoryIssue issue = new RepositoryIssue(90, 31190, "Delta Lake checkpoint drops deletion vectors", "", "alice", "",
                "https://github.com/trinodb/trino/issues/31190", ImmutableList.of(), ImmutableList.of(), 0, NOW, NOW);
        when(issues.find(7L, 31190)).thenReturn(Optional.of(issue));
        when(issues.findPage(eq(7L), eq(REPO), anyInt(), anyInt()))
                .thenReturn(new RepositoryIssuePage(7L, REPO, ImmutableList.of(issue), 0, 500, 1, false, Optional.of(NOW)));
        when(development.findTask("github.com", 90)).thenReturn(Optional.of(mock(IssueTask.class, withSettings().mockMaker(MockMakers.INLINE))));

        JiraIssue ticket = ticket("TRINO-412", "Validate the fix on staging", new JiraDtos.Status("3", "Blocked", "indeterminate"));
        when(jira.issue("connection", "cloud", "10412")).thenReturn(ticket);
        when(jira.cachedIssues()).thenReturn(ImmutableList.of(ticket));

        AgentTask running = mock(AgentTask.class, withSettings().mockMaker(MockMakers.INLINE));
        when(running.id()).thenReturn(TASK);
        when(running.title()).thenReturn("Fix Delta Lake checkpoints");
        when(running.state()).thenReturn(TaskState.OPEN);
        when(running.worktreeMutationClaim()).thenReturn(Optional.of(new WorktreeMutationClaim(WorktreeMutationClaim.Kind.TURN, "turn-1", NOW)));
        when(running.updatedAt()).thenReturn(NOW);
        when(tasks.getInteractive(eq(WORKSPACE), any(TaskId.class))).thenAnswer(invocation -> {
            if (invocation.getArgument(1).equals(TASK)) {
                return running;
            }
            throw new AgentTaskNotFoundException(invocation.getArgument(1));
        });
        when(tasks.list(WORKSPACE, 100)).thenReturn(ImmutableList.of(running));
    }

    @Test
    void pinsTheFirstGoalAndMovesThePinWhenAskedOrWhenThePinnedGoalGoes()
    {
        GoalView first = service.create(WORKSPACE, "  Iceberg & Delta Lake correctness  ", "Land the fixes before 482");
        GoalView second = service.create(WORKSPACE, "Parquet performance", "");
        assertThat(first.title()).isEqualTo("Iceberg & Delta Lake correctness");
        assertThat(first.pinned()).isTrue();
        assertThat(second.pinned()).isFalse();

        service.update(WORKSPACE, second.id(), Optional.of("Parquet projection performance"), Optional.empty(), true);
        assertThat(service.list(WORKSPACE)).extracting(GoalView::title, GoalView::pinned)
                .containsExactly(tuple("Iceberg & Delta Lake correctness", false), tuple("Parquet projection performance", true));

        service.delete(WORKSPACE, second.id());
        assertThat(service.list(WORKSPACE)).extracting(GoalView::id, GoalView::pinned).containsExactly(tuple(first.id(), true));
        assertThatThrownBy(() -> service.create(WORKSPACE, " ", "")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.delete("workspace-2", first.id())).isInstanceOf(RuntimeException.class);
    }

    @Test
    void addsStoredItemsUnderTheirCurrentNamesAndReadsEachFromItsOwnStore()
    {
        String goal = service.create(WORKSPACE, "Delta Lake correctness", "").id();
        service.addItem(WORKSPACE, goal, GoalItemKind.PULL_REQUEST, "TrinoDB/Trino#30702");
        service.addItem(WORKSPACE, goal, GoalItemKind.PULL_REQUEST, "trinodb/trino#31264");
        service.addItem(WORKSPACE, goal, GoalItemKind.ISSUE, "trinodb/trino#31190");
        service.addItem(WORKSPACE, goal, GoalItemKind.JIRA, "connection:cloud:10412");
        GoalView view = service.addItem(WORKSPACE, goal, GoalItemKind.TASK, TASK.toString());

        assertThat(view.items()).extracting(GoalItemView::key, GoalItemView::label, GoalItemView::state, GoalItemView::status, GoalItemView::blocked).containsExactly(
                tuple("trinodb/trino#30702", "#30702", GoalItemState.FINISHED, "Merged", false),
                tuple("trinodb/trino#31264", "#31264", GoalItemState.WORKING, "Draft", false),
                tuple("trinodb/trino#31190", "#31190", GoalItemState.WORKING, "Open", false),
                tuple("connection:cloud:10412", "TRINO-412", GoalItemState.WORKING, "Blocked", true),
                tuple(TASK.toString(), "Task", GoalItemState.WORKING, "Running", false));
        assertThat(view.items().get(0).activity()).contains(new GoalActivity("#30702 merged", NOW));
        assertThat(view.items()).allMatch(item -> item.addedAt().isPresent());

        // The same item twice, in another spelling, is still one item.
        assertThat(service.addItem(WORKSPACE, goal, GoalItemKind.PULL_REQUEST, "trinodb/trino#30702").items()).hasSize(5);
        assertThat(service.removeItem(WORKSPACE, goal, GoalItemKind.PULL_REQUEST, "TRINODB/trino#30702").items()).extracting(GoalItemView::key)
                .containsExactly("trinodb/trino#31264", "trinodb/trino#31190", "connection:cloud:10412", TASK.toString());
        assertThatThrownBy(() -> service.addItem(WORKSPACE, goal, GoalItemKind.PULL_REQUEST, "trinodb/trino#1"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("has not stored this item");
        assertThatThrownBy(() -> service.addItem(WORKSPACE, goal, GoalItemKind.ISSUE, "not-a-key")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void readsAnItemThatLeftItsStoreFromWhatWasSavedAndAnIssueGoneFromTheSyncedOpenIssuesAsClosed()
    {
        String goal = service.create(WORKSPACE, "Delta Lake correctness", "").id();
        service.addItem(WORKSPACE, goal, GoalItemKind.ISSUE, "trinodb/trino#31190");
        service.addItem(WORKSPACE, goal, GoalItemKind.TASK, TASK.toString());
        when(issues.find(7L, 31190)).thenReturn(Optional.empty());
        when(tasks.getInteractive(WORKSPACE, TASK)).thenThrow(new AgentTaskNotFoundException(TASK));

        assertThat(service.list(WORKSPACE).get(0).items()).extracting(GoalItemView::title, GoalItemView::state, GoalItemView::status).containsExactly(
                tuple("Delta Lake checkpoint drops deletion vectors", GoalItemState.FINISHED, "Closed"),
                tuple("Fix Delta Lake checkpoints", GoalItemState.IDLE, "Deleted"));

        // Before the first issue sync nothing says the issue closed.
        when(issues.findPage(eq(7L), eq(REPO), anyInt(), anyInt())).thenReturn(new RepositoryIssuePage(7L, REPO, ImmutableList.of(), 0, 1, 0, false, Optional.empty()));
        assertThat(service.list(WORKSPACE).get(0).items().get(0).state()).isEqualTo(GoalItemState.IDLE);
    }

    @Test
    void aMarkedIssueOrTicketSeedsOneGoalThatNeverListsItselfAndUnmarkingRemovesIt()
    {
        GoalSource source = new GoalSource(GoalItemKind.ISSUE, GoalItem.numberedKey("trinodb/trino", 31000));
        service.seed(WORKSPACE, source, "Iceberg correctness for 482");
        service.seed(WORKSPACE, source, "Iceberg correctness for 482");
        List<GoalView> seeded = service.list(WORKSPACE);
        assertThat(seeded).extracting(GoalView::title, GoalView::pinned, GoalView::source)
                .containsExactly(tuple("Iceberg correctness for 482", true, Optional.of(source)));
        assertThat(seeded.get(0).items()).isEmpty();

        GoalView manual = service.create(WORKSPACE, "Parquet performance", "");
        service.unseed(source);
        assertThat(service.list(WORKSPACE)).extracting(GoalView::id, GoalView::pinned).containsExactly(tuple(manual.id(), true));
    }

    @Test
    void goalChangesOnlyReadGitHubAndJiraDataThatIsAlreadyStored()
    {
        String goal = service.create(WORKSPACE, "Delta Lake correctness", "").id();
        for (GoalItemView candidate : service.candidates(WORKSPACE, "")) {
            service.addItem(WORKSPACE, goal, candidate.kind(), candidate.key());
        }
        assertThat(service.list(WORKSPACE).get(0).items()).extracting(GoalItemView::label).containsExactly("#31264", "#31190", "TRINO-412", "Task");
        when(pullRequests.findRepositoryPage("trinodb/trino", 0, 8, "trino-4")).thenReturn(new PullRequestPage(ImmutableList.of(), 0, 8, 0, 0, false));
        assertThat(service.candidates(WORKSPACE, " TRINO-4 ")).extracting(GoalItemView::label).containsExactly("TRINO-412");
        for (GoalItemView item : service.list(WORKSPACE).get(0).items()) {
            service.removeItem(WORKSPACE, goal, item.kind(), item.key());
        }
        service.update(WORKSPACE, goal, Optional.of("Renamed"), Optional.of("Described"), true);
        service.delete(WORKSPACE, goal);

        // No GitHub client is wired at all; every Jira, pull request, issue and task call is a local read.
        assertReadsOnly(jira, ImmutableSet.of("issue", "cachedIssues"));
        assertReadsOnly(pullRequests, ImmutableSet.of("findIdByRepoAndNumber", "findById", "findRepositoryPage"));
        assertReadsOnly(issues, ImmutableSet.of("find", "findPage"));
        assertReadsOnly(development, ImmutableSet.of("findTask"));
        assertReadsOnly(tasks, ImmutableSet.of("getInteractive", "list"));
    }

    private static void assertReadsOnly(Object collaborator, Set<String> reads)
    {
        assertThat(mockingDetails(collaborator).getInvocations()).extracting(invocation -> invocation.getMethod().getName()).isNotEmpty().allMatch(reads::contains);
    }

    private static PullRequest pullRequest(int number, String title, String state, boolean draft)
    {
        PullRequest pull = mock(PullRequest.class, withSettings().mockMaker(MockMakers.INLINE));
        when(pull.repo()).thenReturn("trinodb/trino");
        when(pull.number()).thenReturn(number);
        when(pull.title()).thenReturn(title);
        when(pull.state()).thenReturn(state);
        when(pull.draft()).thenReturn(draft);
        when(pull.htmlUrl()).thenReturn("https://github.com/trinodb/trino/pull/" + number);
        when(pull.updatedAt()).thenReturn(NOW);
        when(pull.mergedAt()).thenReturn("merged".equals(state) ? NOW : null);
        return pull;
    }

    private static JiraIssue ticket(String key, String title, JiraDtos.Status status)
    {
        return new JiraIssue("connection", "cloud", "10412", key, "https://example.atlassian.net/browse/" + key, title, "", "Task", status, Optional.empty(),
                ImmutableList.of(), Optional.empty(), Optional.empty(), false, false, NOW, Optional.empty(), Optional.empty(), ImmutableList.of(), false,
                Optional.empty(), Optional.empty());
    }

    /** One second later on every read, so goals created in one test keep their order. */
    private static final class TickingClock
            extends Clock
    {
        private Instant now = NOW;

        @Override
        public ZoneId getZone()
        {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone)
        {
            return this;
        }

        @Override
        public synchronized Instant instant()
        {
            now = now.plusSeconds(1);
            return now;
        }
    }
}
