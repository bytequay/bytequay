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
package com.bytequay.app.agent;

import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskService;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.AgentUnitOfWork;
import com.bytequay.app.jira.JiraService;
import com.bytequay.app.workitem.WorkItemService;
import com.bytequay.app.workspace.WorkspaceIssueService;
import com.bytequay.core.domain.JiraIssueSource;
import com.bytequay.core.domain.JiraIssueTask;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.IssueDevelopmentStore;
import com.bytequay.core.store.JiraIssueTaskStore;
import com.bytequay.core.store.RepositoryIssueStore;
import com.bytequay.core.store.WorkspaceStore;
import com.bytequay.integration.jira.JiraDtos;
import com.bytequay.integration.jira.JiraIssue;
import com.bytequay.integration.jira.JiraIssueLocks;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.MockMakers;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestJiraDevelopment
{
    private static final Instant NOW = Instant.parse("2026-09-16T00:00:00Z");
    private static final JiraIssueSource SOURCE = new JiraIssueSource("connection", "cloud", "10001", "APP-7",
            "https://company.atlassian.net/browse/APP-7", "Keep selection", "Accepted description", NOW);
    private static final JiraSourceInput INPUT = new JiraSourceInput("connection", "cloud", "10001", "APP-7", SOURCE.title(), SOURCE.body(), NOW, Optional.empty());
    private final JiraIssueTaskStore sources = mock(JiraIssueTaskStore.class);
    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final AgentTaskService tasks = mock(AgentTaskService.class);
    private final AgentTaskStore taskStore = mock(AgentTaskStore.class);
    private final AgentTurnStore turns = mock(AgentTurnStore.class);
    private final AgentUnitOfWork unitOfWork = mock(AgentUnitOfWork.class);
    private final JiraService jira = mock(JiraService.class);
    private final TaskId taskId = TaskId.random();
    private final AgentTask task = mock(AgentTask.class, withSettings().mockMaker(MockMakers.INLINE));
    private final AtomicReference<JiraIssueTask> linked = new AtomicReference<>();
    private final IssueDevelopmentService service = new IssueDevelopmentService(mock(IssueDevelopmentStore.class), mock(RepositoryIssueStore.class), workspaces,
            tasks, taskStore, unitOfWork, mock(WorkspaceIssueService.class), Clock.fixed(NOW, ZoneOffset.UTC), turns,
            mock(WorkItemService.class), sources, jira);

    @BeforeEach
    void setUp()
    {
        when(workspaces.findById(anyString())).thenReturn(Optional.of(mock(Workspace.class, withSettings().mockMaker(MockMakers.INLINE))));
        when(sources.findBySource("cloud", "10001")).thenAnswer(ignored -> Optional.ofNullable(linked.get()));
        when(sources.findByTaskId(taskId.toString())).thenAnswer(ignored -> Optional.ofNullable(linked.get()));
        doAnswer(invocation -> {
            linked.set(invocation.getArgument(0));
            return null;
        }).when(sources).insert(any());
        when(unitOfWork.execute(any())).thenAnswer(invocation -> invocation.<AgentUnitOfWork.Work<?>>getArgument(0).run());
        when(task.id()).thenReturn(taskId);
        when(task.workspaceId()).thenReturn(Optional.of("workspace"));
        when(tasks.getInteractive("workspace", taskId)).thenReturn(task);
        when(jira.issue("connection", "cloud", "10001")).thenReturn(issue(SOURCE.title(), false, "indeterminate", NOW));
        when(jira.refreshIssue("connection", "cloud", "10001")).thenReturn(issue(SOURCE.title(), false, "indeterminate", NOW));
    }

    @Test
    void capturesAuthoritativeSnapshotWithoutTreatingMetadataChangesAsNewInstructions()
    {
        when(jira.refreshIssue("connection", "cloud", "10001")).thenReturn(issue(SOURCE.title(), false, "indeterminate", NOW.plusSeconds(20)));
        assertThat(service.start("workspace", taskId, INPUT, "Implement selection", accepted -> {
            assertThat(accepted.updatedAt()).isEqualTo(NOW.plusSeconds(20));
            assertThat(accepted.body()).isEqualTo(SOURCE.body());
            service.persistTask(task, accepted, "Implement selection");
            assertThat(linked.get().taskId()).isEqualTo(taskId.toString());
            return task;
        })).isSameAs(task);
        assertThat(linked.get().instructions()).isEqualTo("Implement selection");
        verify(taskStore).insert(task);
    }

    @Test
    void reusesAcceptedSourceWithoutCacheOrConnectionOnRetryAndProjectsOriginalTurn()
    {
        linked.set(new JiraIssueTask(taskId.toString(), "workspace", SOURCE, "Implement selection", NOW));
        AgentTurn first = mock(AgentTurn.class, withSettings().mockMaker(MockMakers.INLINE));
        TurnId initial = TurnId.random();
        when(first.id()).thenReturn(initial);
        when(turns.list(taskId)).thenReturn(ImmutableList.of(first));
        assertThat(service.start("workspace", taskId, INPUT, "Implement selection", accepted -> {
            assertThat(accepted).isEqualTo(SOURCE);
            return task;
        })).isSameAs(task);
        assertThat(service.jiraSource(taskId.toString()).orElseThrow().initialTurnId()).contains(initial.toString());
        assertThatThrownBy(() -> service.start("workspace", taskId, INPUT, "Different instructions", ignored -> task))
                .isInstanceOf(AgentConflictException.class).hasMessageContaining("request changed");
        assertThatThrownBy(() -> service.requireAttachedSources(taskId, Optional.empty(), Optional.empty())).isInstanceOf(AgentConflictException.class);
        verifyNoInteractions(jira);
    }

    @Test
    void anotherAttemptReplacesOnlyAnEndedTaskAndTakesTheTicket()
    {
        service.persistTask(task, SOURCE, "Implement");
        TaskId next = TaskId.random();
        JiraSourceInput another = new JiraSourceInput("connection", "cloud", "10001", "APP-7", SOURCE.title(), SOURCE.body(), NOW, Optional.of(taskId.toString()));
        assertThatThrownBy(() -> service.start("workspace", next, another, "Try again", ignored -> task))
                .isInstanceOf(AgentConflictException.class).hasMessageContaining("task is gone");
        when(taskStore.find(taskId)).thenReturn(Optional.of(task));
        when(task.state()).thenReturn(TaskState.OPEN);
        assertThatThrownBy(() -> service.start("workspace", next, another, "Try again", ignored -> {
            throw new AssertionError("an open task is still the attempt");
        })).isInstanceOf(AgentConflictException.class).hasMessageContaining("has not ended");
        assertThat(service.start("workspace", next, INPUT, "Try again", ignored -> {
            throw new AssertionError("without Start another attempt the ticket's task opens");
        })).isSameAs(task);

        when(task.state()).thenReturn(TaskState.FAILED);
        when(task.developmentContinuationUnfinished()).thenReturn(true);
        assertThatThrownBy(() -> service.start("workspace", next, another, "Try again", ignored -> task))
                .isInstanceOf(AgentConflictException.class).hasMessageContaining("has not ended");
        when(task.developmentContinuationUnfinished()).thenReturn(false);
        assertThatThrownBy(() -> service.start("another-workspace", next, another, "Try again", ignored -> task))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Task belongs to another workspace");
        AgentTask attempt = mock(AgentTask.class, withSettings().mockMaker(MockMakers.INLINE));
        when(attempt.id()).thenReturn(next);
        when(attempt.workspaceId()).thenReturn(Optional.of("workspace"));
        assertThat(service.start("workspace", next, another, "Try again", accepted -> {
            service.persistTask(attempt, accepted, "Try again");
            return attempt;
        })).isSameAs(attempt);
        verify(sources).retire(taskId.toString());
        assertThat(linked.get().taskId()).as("the new attempt is the ticket's task").isEqualTo(next.toString());
    }

    @Test
    void rejectsChangedDescriptionBeforeProvisioning()
    {
        when(jira.refreshIssue("connection", "cloud", "10001")).thenReturn(issue("Changed scope", false, "indeterminate", NOW.plusSeconds(1)));
        assertThatThrownBy(() -> service.start("workspace", taskId, INPUT, "Implement", ignored -> { throw new AssertionError("must not create"); }))
                .isInstanceOf(AgentConflictException.class).hasMessageContaining("Refresh");
        verifyNoInteractions(taskStore);
    }

    @Test
    void rejectsEpicParentAndDoneBeforeAnyFreshReadOrProvisioning()
    {
        when(jira.isEpic("cloud", "10001")).thenReturn(true);
        assertThatThrownBy(() -> service.start("workspace", taskId, INPUT, "Implement", ignored -> task)).hasMessageContaining("Epic");
        when(jira.isEpic("cloud", "10001")).thenReturn(false);
        when(jira.issue("connection", "cloud", "10001")).thenReturn(issue(SOURCE.title(), true, "indeterminate", NOW));
        assertThatThrownBy(() -> service.start("workspace", taskId, INPUT, "Implement", ignored -> task)).hasMessageContaining("children");
        when(jira.issue("connection", "cloud", "10001")).thenReturn(issue(SOURCE.title(), false, "done", NOW));
        assertThatThrownBy(() -> service.start("workspace", taskId, INPUT, "Implement", ignored -> task)).hasMessageContaining("done");
        verify(jira, never()).refreshIssue(anyString(), anyString(), anyString());
        verifyNoInteractions(taskStore);
    }

    @Test
    void requiresTheAssignedWorkspaceBeforeRefreshingOrCreating()
    {
        assertThatThrownBy(() -> service.start("other-workspace", taskId, INPUT, "Implement", ignored -> task))
                .isInstanceOf(AgentConflictException.class).hasMessageContaining("another workspace");
        JiraIssue unassigned = issue(SOURCE.title(), false, "indeterminate", NOW).withMetadata(false, Optional.empty(), Optional.empty());
        when(jira.issue("connection", "cloud", "10001")).thenReturn(unassigned);
        assertThatThrownBy(() -> service.start("workspace", taskId, INPUT, "Implement", ignored -> task))
                .isInstanceOf(AgentConflictException.class).hasMessageContaining("Assign a ByteQuay workspace");
        verify(jira, never()).refreshIssue(anyString(), anyString(), anyString());
        verifyNoInteractions(taskStore);
    }

    @Test
    void concurrentStartsAcrossWorkspacesReuseOneTaskAndEpicWaitsForAdmission()
            throws Exception
    {
        CountDownLatch admitted = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        AtomicBoolean epicChanged = new AtomicBoolean();
        when(jira.isEpic("cloud", "10001")).thenAnswer(ignored -> epicChanged.get());
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> service.start("workspace", taskId, INPUT, "Implement", accepted -> {
                admitted.countDown();
                try {
                    assertThat(finish.await(5, TimeUnit.SECONDS)).isTrue();
                }
                catch (InterruptedException failure) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(failure);
                }
                service.persistTask(task, accepted, "Implement");
                return task;
            }));
            assertThat(admitted.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> service.start("other-workspace", TaskId.random(), INPUT, "Implement", ignored -> { throw new AssertionError("duplicate task"); }));
            var epic = executor.submit(() -> JiraIssueLocks.withLock("cloud", "10001", () -> {
                epicChanged.set(true);
                return true;
            }));
            assertThat(epicChanged).isFalse();
            finish.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isSameAs(task);
            assertThat(second.get(5, TimeUnit.SECONDS)).isSameAs(task);
            assertThat(epic.get(5, TimeUnit.SECONDS)).isTrue();
            verify(taskStore).insert(task);
        }
        finally {
            finish.countDown();
        }
    }

    private static JiraIssue issue(String title, boolean children, String category, Instant updatedAt)
    {
        return new JiraIssue("connection", "cloud", "10001", "APP-7", SOURCE.url(), title, SOURCE.body(), "Story",
                new JiraDtos.Status("3", "In progress", category), Optional.empty(), ImmutableList.of("bug"), Optional.empty(), Optional.empty(), children, false,
                updatedAt, Optional.empty(), Optional.empty(), ImmutableList.of(), false, Optional.empty(), Optional.of(new JiraDtos.WorkspaceLink("workspace", "Workspace")));
    }
}
