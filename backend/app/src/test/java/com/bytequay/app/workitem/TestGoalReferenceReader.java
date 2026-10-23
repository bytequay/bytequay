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

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskNotFoundException;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.WorktreeMutationClaim;
import com.bytequay.app.agent.TaskPullRequestOwnership;
import com.bytequay.app.jira.JiraService;
import com.bytequay.core.domain.GoalItem;
import com.bytequay.core.domain.GoalItemKind;
import com.bytequay.core.domain.IssueTask;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.PullRequestPage;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.RepositoryIssuePage;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.IssueDevelopmentStore;
import com.bytequay.core.store.JiraIssueTaskStore;
import com.bytequay.core.store.PullRequestStore;
import com.bytequay.core.store.PullRequestTaskStore;
import com.bytequay.core.store.RepositoryIssueStore;
import com.bytequay.core.store.WorkspaceStore;
import com.bytequay.integration.jira.JiraDtos;
import com.bytequay.integration.jira.JiraIssue;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestGoalReferenceReader
{
    private static final Instant NOW = Instant.parse("2026-09-28T10:00:00Z");
    private static final RepoRef REPO = RepoRef.of("owner", "repository");
    private static final String WORKSPACE = "workspace-1";
    private static final TaskId TASK = TaskId.random();

    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final PullRequestStore pullRequests = mock(PullRequestStore.class);
    private final RepositoryIssueStore issues = mock(RepositoryIssueStore.class);
    private final IssueDevelopmentStore development = mock(IssueDevelopmentStore.class);
    private final JiraService jira = mock(JiraService.class);
    @SuppressWarnings("unchecked")
    private final ObjectProvider<JiraService> jiraProvider = mock(ObjectProvider.class);
    private final AgentTaskService tasks = mock(AgentTaskService.class);
    private final GoalReferenceReader reader = new GoalReferenceReader(workspaces, pullRequests, issues, development, jiraProvider, tasks, new TickingClock(),
            mock(JiraIssueTaskStore.class), mock(PullRequestTaskStore.class), mock(TaskPullRequestOwnership.class));

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
        when(pullRequests.findIdByRepoAndNumber("owner/repository", 30702)).thenReturn(Optional.of(1L));
        when(pullRequests.findIdByRepoAndNumber("owner/repository", 31264)).thenReturn(Optional.of(2L));
        when(pullRequests.findById(1L)).thenReturn(Optional.of(merged));
        when(pullRequests.findById(2L)).thenReturn(Optional.of(draft));
        when(pullRequests.findRepositoryPage(eq("owner/repository"), anyInt(), anyInt(), anyString()))
                .thenReturn(new PullRequestPage(ImmutableList.of(draft), 0, 8, 1, 1, false));

        RepositoryIssue issue = new RepositoryIssue(90, 31190, "Delta Lake checkpoint drops deletion vectors", "", "alice", "",
                "https://github.com/owner/repository/issues/31190", ImmutableList.of(), ImmutableList.of(), 0, NOW, NOW);
        when(issues.find(7L, 31190)).thenReturn(Optional.of(issue));
        when(issues.findPage(eq(7L), eq(REPO), anyInt(), anyInt()))
                .thenReturn(new RepositoryIssuePage(7L, REPO, ImmutableList.of(issue), 0, 500, 1, false, Optional.of(NOW)));
        when(development.findTask("github.com", 90)).thenReturn(Optional.of(mock(IssueTask.class, withSettings().mockMaker(MockMakers.INLINE))));

        JiraIssue ticket = ticket("ENG-412", "Validate the fix on staging", new JiraDtos.Status("3", "Blocked", "indeterminate"));
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
    void absentOpenIssueCacheIsUnknownEvenAfterASuccessfulSync()
    {
        GoalItem saved = new GoalItem(GoalItemKind.ISSUE, "owner/repository#31190", "#31190", "Saved issue", NOW);
        when(issues.find(7L, 31190)).thenReturn(Optional.empty());
        assertThat(reader.view(WORKSPACE, saved).state()).isEqualTo(GoalItemState.UNKNOWN);
        assertThat(reader.view(WORKSPACE, saved).title()).isEqualTo("Saved issue");
    }

    @Test
    void failedTasksAndUnmergedClosuresAreEndedActivityRatherThanDelivery()
    {
        AgentTask task = tasks.getInteractive(WORKSPACE, TASK);
        when(task.state()).thenReturn(TaskState.FAILED);
        GoalItem saved = new GoalItem(GoalItemKind.TASK, TASK.toString(), "Task", task.title(), NOW);
        assertThat(reader.view(WORKSPACE, saved).state()).isEqualTo(GoalItemState.ENDED);
        assertThat(reader.view(WORKSPACE, saved).status()).isEqualTo("Failed");
        when(task.state()).thenReturn(TaskState.COMPLETED);
        assertThat(reader.view(WORKSPACE, saved).state()).isEqualTo(GoalItemState.ENDED);
        PullRequest closed = pullRequest(30702, "Abandoned", "closed", false);
        when(pullRequests.findById(1L)).thenReturn(Optional.of(closed));
        GoalItem pull = new GoalItem(GoalItemKind.PULL_REQUEST, "owner/repository#30702", "#30702", "Saved PR", NOW);
        assertThat(reader.view(WORKSPACE, pull).state()).isEqualTo(GoalItemState.ENDED);
        PullRequest merged = pullRequest(30702, "Merged", "merged", false);
        when(pullRequests.findById(1L)).thenReturn(Optional.of(merged));
        assertThat(reader.view(WORKSPACE, pull).state()).isEqualTo(GoalItemState.MERGED);
    }

    @Test
    void aTicketKeepsJirasOwnPriorityUnmappedBesideItsBlockedStatus()
    {
        GoalItemView view = reader.view(WORKSPACE, new GoalItem(GoalItemKind.JIRA, "connection:cloud:10412", "ENG-412", "Saved ticket", NOW));
        assertThat(view.priority()).contains("Highest");
        assertThat(view.blocked()).isTrue();
    }

    private static PullRequest pullRequest(int number, String title, String state, boolean draft)
    {
        PullRequest pull = mock(PullRequest.class, withSettings().mockMaker(MockMakers.INLINE));
        when(pull.repo()).thenReturn("owner/repository");
        when(pull.number()).thenReturn(number);
        when(pull.title()).thenReturn(title);
        when(pull.state()).thenReturn(state);
        when(pull.draft()).thenReturn(draft);
        when(pull.htmlUrl()).thenReturn("https://github.com/owner/repository/pull/" + number);
        when(pull.updatedAt()).thenReturn(NOW);
        when(pull.mergedAt()).thenReturn("merged".equals(state) ? NOW : null);
        return pull;
    }

    private static JiraIssue ticket(String key, String title, JiraDtos.Status status)
    {
        return new JiraIssue("connection", "cloud", "10412", key, "https://example.atlassian.net/browse/" + key, title, "", "Task", status, Optional.empty(),
                ImmutableList.of(), Optional.of(new JiraDtos.Choice("2", "Highest")), Optional.empty(), false, false, NOW, Optional.empty(), Optional.empty(),
                ImmutableList.of(), false, Optional.empty(), Optional.empty());
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
