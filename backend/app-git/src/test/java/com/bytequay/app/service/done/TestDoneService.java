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
package com.bytequay.app.service.done;

import com.bytequay.agent.core.AgentMetrics;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskSummary;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.app.repository.github.GitHubAccountClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.credentials.CredentialService.GitHubCredential;
import com.bytequay.app.workspace.TaskPullRequestOwnership;
import com.bytequay.core.domain.DoneActivity;
import com.bytequay.core.domain.DoneItem;
import com.bytequay.core.domain.GoalItem;
import com.bytequay.core.domain.GoalItemKind;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.UserProfile;
import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.domain.WorkItemLink;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.WorkItemStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static com.bytequay.agent.core.AgentTaskFixtures.created;
import static com.bytequay.core.hosting.HostingServer.GITHUB_COM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestDoneService
{
    private static final LocalDate DAY = LocalDate.parse("2026-11-02");

    private final GitHubAccountClient accounts = mock(GitHubAccountClient.class);
    private final CredentialService credentials = mock(CredentialService.class);
    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final AgentTaskStore tasks = mock(AgentTaskStore.class);
    private final WorkItemStore workItems = mock(WorkItemStore.class);
    private final TaskPullRequestOwnership ownership = mock(TaskPullRequestOwnership.class);

    @Test
    void finishedTasksAndWorkJoinGitHubUnlessAMergeAlreadySaysSo()
    {
        Workspace workspace = mock(Workspace.class, withSettings().mockMaker(MockMakers.INLINE));
        when(workspace.id()).thenReturn("ws1");
        when(workspace.server()).thenReturn(GITHUB_COM);
        when(workspace.monitoredRepository()).thenReturn(RepoRef.of("Acme", "App"));
        when(workspaces.findAll()).thenReturn(ImmutableList.of(workspace));
        when(credentials.resolveGitHubCredential()).thenReturn(new GitHubCredential("pat", new UserProfile("me", null, null, null, 0, 0, 0, null, null, null, null)));
        when(accounts.fetchDone(eq("pat"), eq("me"), any(), any())).thenReturn(new DoneActivity(ImmutableList.of(
                DoneItem.github(DoneItem.Kind.MERGED, "Fix the parser", "acme/app", 1, at("10:00")),
                DoneItem.github(DoneItem.Kind.APPROVED, "Elsewhere", "other/repo", 5, at("09:00"))), true));

        AgentTask delivered = completed("Fix the parser", at("11:00"));
        AgentTask finished = completed("Investigate flaky test", at("12:00"));
        AgentTask tomorrow = completed("Later", at("12:00").plusSeconds(86_400));
        AgentTask open = created(TaskId.random(), Optional.of("ws1"), true, "Still open", AgentLaneId.CODEX_CLI, new RepositoryIdentity("acme", "app"),
                Path.of("/tmp/task"), AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), at("08:00"));
        when(tasks.listByWorkspace("ws1")).thenReturn(ImmutableList.of(delivered, finished, tomorrow, open));
        PullRequest merged = mock(PullRequest.class, withSettings().mockMaker(MockMakers.INLINE));
        when(merged.repo()).thenReturn("acme/app");
        when(merged.number()).thenReturn(1);
        when(ownership.linkedPullRequest(any())).thenReturn(Optional.empty());
        when(ownership.linkedPullRequest(delivered)).thenReturn(Optional.of(merged));
        WorkItemLink delivery = new WorkItemLink("pr", new GoalItem(GoalItemKind.PULL_REQUEST, "Acme/App#1", "#1", "Fix", at("08:00")));
        when(workItems.list("ws1")).thenReturn(ImmutableList.of(
                work("w1", WorkItem.State.DONE, ImmutableList.of(), at("13:00")),
                work("w2", WorkItem.State.DONE, ImmutableList.of(delivery), at("13:00")),
                work("w3", WorkItem.State.WORKING, ImmutableList.of(), at("13:00"))));

        DoneService done = new DoneService(accounts, credentials, workspaces, tasks, workItems, ownership, Clock.fixed(at("14:00"), ZoneOffset.UTC));
        DoneActivity activity = done.done(DAY, DAY);

        assertThat(activity.complete()).isTrue();
        assertThat(activity.items()).extracting(DoneItem::kind, DoneItem::title, DoneItem::workspaceId).containsExactly(
                tuple(DoneItem.Kind.WORK_DONE, "Work w1", Optional.of("ws1")),
                tuple(DoneItem.Kind.TASK_DONE, "Investigate flaky test", Optional.of("ws1")),
                tuple(DoneItem.Kind.MERGED, "Fix the parser", Optional.of("ws1")),
                tuple(DoneItem.Kind.APPROVED, "Elsewhere", Optional.empty()));

        // Within five minutes the same span answers from the last read.
        done.done(DAY, DAY);
        verify(accounts, times(1)).fetchDone(any(), any(), any(), any());
    }

    private static AgentTask completed(String title, Instant at)
    {
        AgentTask task = created(TaskId.random(), Optional.of("ws1"), true, title, AgentLaneId.CODEX_CLI, new RepositoryIdentity("acme", "app"), Path.of("/tmp/task"),
                AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), at("08:00"));
        return task.complete(new TaskSummary("Done", Optional.empty(), AgentMetrics.empty(), Optional.empty(), ImmutableList.of()), at, at.plusSeconds(3600));
    }

    private static WorkItem work(String id, WorkItem.State state, List<WorkItemLink> links, Instant at)
    {
        return new WorkItem(id, "ws1", "Work " + id, WorkItem.Kind.DEVELOP, "", WorkItem.CompletionRule.MERGED_DELIVERY, state, 0,
                links, ImmutableList.of(), at("08:00"), at, WorkItem.Priority.NORMAL, ImmutableList.of(), Optional.empty(), 0, ImmutableList.of());
    }

    // The span follows the wall clock, so the times are local and the test fails on a machine away from UTC if that regresses.
    private static Instant at(String time)
    {
        return LocalDateTime.parse(DAY + "T" + time).atZone(ZoneId.systemDefault()).toInstant();
    }
}
