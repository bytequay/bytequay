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
import com.bytequay.app.repository.github.GitHubRepoClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.goal.WorkItemService;
import com.bytequay.core.domain.GitHubIssueSource;
import com.bytequay.core.domain.IssueEpic;
import com.bytequay.core.domain.IssueTask;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.RepositoryIssuePage;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.IssueDevelopmentStore;
import com.bytequay.core.store.RepositoryIssueStore;
import com.bytequay.core.store.WorkspaceStore;
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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestIssueDevelopmentService
{
    private static final Instant NOW = Instant.parse("2026-09-16T00:00:00Z");
    private static final RepoRef REPO = RepoRef.of("upstream", "repo");
    private static final GitHubIssueSource SOURCE = new GitHubIssueSource("github.com", 70, REPO.fullName(), 7,
            "https://github.com/upstream/repo/issues/7", "Fix selection", "Keep selection when switching tabs", NOW);
    private static final IssueSourceInput INPUT = new IssueSourceInput(70, 7, NOW, Optional.empty());
    private final IssueDevelopmentStore sources = mock(IssueDevelopmentStore.class);
    private final RepositoryIssueStore issues = mock(RepositoryIssueStore.class);
    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final AgentTaskService tasks = mock(AgentTaskService.class);
    private final AgentTaskStore taskStore = mock(AgentTaskStore.class);
    private final AgentUnitOfWork unitOfWork = mock(AgentUnitOfWork.class);
    private final AgentTurnStore turns = mock(AgentTurnStore.class);
    private final GitHubRepoClient github = mock(GitHubRepoClient.class);
    private final CredentialService credentials = mock(CredentialService.class);
    private final AtomicReference<IssueTask> linked = new AtomicReference<>();
    private final AtomicReference<IssueEpic> epic = new AtomicReference<>();
    private final AgentTask task = mock(AgentTask.class, withSettings().mockMaker(MockMakers.INLINE));
    private final TaskId taskId = TaskId.random();
    private final IssueDevelopmentService service = new IssueDevelopmentService(sources, issues, workspaces, tasks, taskStore,
            unitOfWork, github, credentials, Clock.fixed(NOW, ZoneOffset.UTC), turns, mock(WorkItemService.class));

    @BeforeEach
    void setUp()
    {
        Workspace workspace = mock(Workspace.class, withSettings().mockMaker(MockMakers.INLINE));
        when(workspace.monitoredRepository()).thenReturn(REPO);
        when(workspace.monitoredRepositoryId()).thenReturn(2L);
        when(workspaces.findById(anyString())).thenReturn(Optional.of(workspace));
        when(issues.find(2, 7)).thenReturn(Optional.of(new RepositoryIssue(70, 7, SOURCE.title(), SOURCE.body(), "alice", "", SOURCE.url(),
                ImmutableList.of(), ImmutableList.of("alice"), 0, NOW, NOW)));
        when(sources.findEpic("github.com", 70)).thenAnswer(ignored -> Optional.ofNullable(epic.get()));
        when(sources.findTask("github.com", 70)).thenAnswer(ignored -> Optional.ofNullable(linked.get()));
        when(sources.findByTaskId(taskId.toString())).thenAnswer(ignored -> Optional.ofNullable(linked.get()));
        doAnswer(invocation -> {
            epic.set(invocation.getArgument(0));
            return null;
        }).when(sources).saveEpic(any());
        doAnswer(invocation -> {
            linked.set(invocation.getArgument(0));
            return null;
        }).when(sources).insertTask(any());
        when(unitOfWork.execute(any())).thenAnswer(invocation -> invocation.<AgentUnitOfWork.Work<?>>getArgument(0).run());
        when(credentials.resolveGitHubToken()).thenReturn("token");
        when(github.fetchOpenIssueSource("token", REPO, 7)).thenReturn(SOURCE);
        when(task.id()).thenReturn(taskId);
        when(task.workspaceId()).thenReturn(Optional.of("workspace"));
        when(tasks.getInteractive("workspace", taskId)).thenReturn(task);
    }

    @Test
    void markingAnEpicOnlyChangesItsLocalClassification()
    {
        assertThat(service.setEpic("workspace", 7, true).isEpic()).isTrue();
        assertThat(service.setEpic("workspace", 7, false).isEpic()).isFalse();
        verifyNoInteractions(github, tasks, taskStore);
    }

    @Test
    void sourceProvenanceUsesOldestRootTurnAndNeverInventsInstructions()
    {
        service.persistTask(task, SOURCE, "Use the existing selection model");
        AgentTurn first = mock(AgentTurn.class, withSettings().mockMaker(MockMakers.INLINE));
        AgentTurn followup = mock(AgentTurn.class, withSettings().mockMaker(MockMakers.INLINE));
        TurnId firstId = TurnId.random();
        when(first.id()).thenReturn(firstId);
        when(turns.list(taskId)).thenReturn(ImmutableList.of(first, followup));
        IssueDevelopmentService.Source source = service.source(taskId.toString()).orElseThrow();
        assertThat(source.initialTurnId()).contains(firstId.toString());
        assertThat(source.instructions()).isEqualTo("Use the existing selection model");
        linked.set(new IssueTask(taskId.toString(), "workspace", SOURCE, Optional.empty(), NOW));
        assertThat(service.source(taskId.toString()).orElseThrow().instructions()).isEmpty();
    }

    @Test
    void issueCreationCannotReuseAnUnrelatedTaskOrDropItsSourceOnRetry()
    {
        when(taskStore.find(taskId)).thenReturn(Optional.of(task));
        assertThatThrownBy(() -> service.start("workspace", taskId, INPUT, "Implement", ignored -> task))
                .isInstanceOf(AgentConflictException.class).hasMessageContaining("without this issue source");
        service.persistTask(task, SOURCE, "Implement");
        assertThatThrownBy(() -> service.requireUnattachedCreate(taskId)).isInstanceOf(AgentConflictException.class);
    }

    @Test
    void epicRefusesStartBeforeRemoteReadOrTaskCreation()
    {
        service.setEpic("other-workspace", 7, true);
        assertThatThrownBy(() -> service.start("workspace", taskId, INPUT, "Implement", ignored -> {
            throw new AssertionError("must not create");
        })).isInstanceOf(AgentConflictException.class).hasMessageContaining("Epic");
        verifyNoInteractions(github, taskStore, tasks);
        verify(credentials, never()).resolveGitHubToken();
    }

    @Test
    void changedDescriptionRefreshesCacheAndRequiresAcceptanceBeforeCreation()
    {
        GitHubIssueSource changed = new GitHubIssueSource("github.com", 70, REPO.fullName(), 7, SOURCE.url(), SOURCE.title(), "New scope", NOW.plusSeconds(1));
        when(github.fetchOpenIssueSource("token", REPO, 7)).thenReturn(changed);
        assertThatThrownBy(() -> service.start("workspace", taskId, INPUT, "Implement", ignored -> {
            throw new AssertionError("must not create");
        })).isInstanceOf(AgentConflictException.class).hasMessageStartingWith("Issue changed.");
        verify(issues).updateContent(2, 7, changed.title(), changed.body(), changed.updatedAt());
        verifyNoInteractions(taskStore);
    }

    @Test
    void commentOnlyUpdateAcceptsFreshSnapshotAndSuppliesSourceContext()
    {
        GitHubIssueSource comment = new GitHubIssueSource("github.com", 70, REPO.fullName(), 7, SOURCE.url(), SOURCE.title(), SOURCE.body(), NOW.plusSeconds(1));
        when(github.fetchOpenIssueSource("token", REPO, 7)).thenReturn(comment);
        assertThat(service.start("workspace", taskId, INPUT, "Please add regression coverage", source -> {
            service.persistTask(task, source, "Please add regression coverage");
            return task;
        })).isSameAs(task);
        assertThat(linked.get().source()).isEqualTo(comment);
        assertThat(linked.get().instructions()).contains("Please add regression coverage");
        verify(taskStore).insert(task);
    }

    @Test
    void commentOnlyUpdateDoesNotInvalidateRetryAfterProviderPreflightFailure()
    {
        GitHubIssueSource comment = new GitHubIssueSource("github.com", 70, REPO.fullName(), 7, SOURCE.url(), SOURCE.title(), SOURCE.body(), NOW.plusSeconds(1));
        when(github.fetchOpenIssueSource("token", REPO, 7)).thenReturn(comment);
        assertThatThrownBy(() -> service.start("workspace", taskId, INPUT, "Implement", ignored -> {
            throw new IllegalStateException("provider not ready");
        })).hasMessage("provider not ready");
        verify(issues, never()).updateContent(anyLong(), anyInt(), anyString(), anyString(), any());
        assertThat(service.start("workspace", taskId, INPUT, "Implement", ignored -> task)).isSameAs(task);
    }

    @Test
    void changedWorkspaceRepositoryRefusesPreparedTaskAndSourceWrite()
    {
        Workspace changed = mock(Workspace.class, withSettings().mockMaker(MockMakers.INLINE));
        when(changed.monitoredRepository()).thenReturn(RepoRef.of("different", "upstream"));
        when(workspaces.findById("workspace")).thenReturn(Optional.of(changed));
        assertThatThrownBy(() -> service.persistTask(task, SOURCE, "Implement"))
                .isInstanceOf(AgentConflictException.class).hasMessageContaining("Workspace issue repository changed");
        verifyNoInteractions(taskStore);
        verify(sources, never()).insertTask(any());
    }

    @Test
    void duplicateAcrossWorkspacesReturnsExistingTaskWithoutNewAdmission()
    {
        service.persistTask(task, SOURCE, "Implement");
        assertThat(service.start("another-workspace", TaskId.random(), INPUT, "Different draft", ignored -> {
            throw new AssertionError("must reuse existing task");
        })).isSameAs(task);
        verifyNoInteractions(github);
        verify(tasks).getInteractive("workspace", taskId);
    }

    @Test
    void anotherAttemptReplacesOnlyAnEndedTaskAndTakesTheIssue()
    {
        service.persistTask(task, SOURCE, "Implement");
        TaskId next = TaskId.random();
        IssueSourceInput another = new IssueSourceInput(70, 7, NOW, Optional.of(taskId.toString()));
        when(taskStore.find(taskId)).thenReturn(Optional.of(task));
        when(task.state()).thenReturn(TaskState.OPEN);
        assertThatThrownBy(() -> service.start("workspace", next, another, "Try again", ignored -> {
            throw new AssertionError("an open task is still the attempt");
        })).isInstanceOf(AgentConflictException.class).hasMessageContaining("has not ended");
        assertThat(service.start("workspace", next, INPUT, "Try again", ignored -> {
            throw new AssertionError("without Start another attempt the issue's task opens");
        })).isSameAs(task);

        when(task.state()).thenReturn(TaskState.FAILED);
        AgentTask attempt = mock(AgentTask.class, withSettings().mockMaker(MockMakers.INLINE));
        when(attempt.id()).thenReturn(next);
        when(attempt.workspaceId()).thenReturn(Optional.of("workspace"));
        assertThat(service.start("workspace", next, another, "Try again", accepted -> {
            service.persistTask(attempt, accepted, "Try again");
            return attempt;
        })).isSameAs(attempt);
        verify(sources).retireTask(taskId.toString());
        assertThat(linked.get().taskId()).as("the new attempt is the issue's task").isEqualTo(next.toString());
    }

    @Test
    void retryUsesAcceptedSourceAfterCacheEvictionAndRejectsChangedInstructions()
    {
        service.persistTask(task, SOURCE, "Implement");
        when(issues.find(2, 7)).thenReturn(Optional.empty());
        assertThat(service.start("workspace", taskId, INPUT, "Implement", accepted -> {
            assertThat(accepted).isEqualTo(SOURCE);
            return task;
        })).isSameAs(task);
        assertThatThrownBy(() -> service.start("workspace", taskId, INPUT, "Changed", ignored -> task))
                .isInstanceOf(AgentConflictException.class).hasMessageContaining("request changed");
        verifyNoInteractions(github);
        verify(issues, never()).find(anyLong(), anyInt());
    }

    @Test
    void attentionReturnsTheWholeCachedRepositoryWithoutCredentialsOrRemoteReads()
    {
        RepositoryIssue cached = issues.find(2, 7).orElseThrow();
        RepositoryIssuePage page = new RepositoryIssuePage(2, REPO, ImmutableList.of(cached), 0, Integer.MAX_VALUE, 1, false, Optional.of(NOW));
        when(issues.findPage(2, REPO, 0, Integer.MAX_VALUE)).thenReturn(page);
        assertThat(service.attention("workspace")).isSameAs(page);
        verifyNoInteractions(credentials, github);
    }

    @Test
    void concurrentStartsAcrossWorkspacesCreateOnce()
            throws Exception
    {
        AtomicInteger creations = new AtomicInteger();
        CountDownLatch creating = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> service.start("workspace", taskId, INPUT, "Implement", source -> {
                creations.incrementAndGet();
                creating.countDown();
                await(finish);
                service.persistTask(task, source, "Implement");
                return task;
            }));
            assertThat(creating.await(5, TimeUnit.SECONDS)).isTrue();
            var second = executor.submit(() -> service.start("another-workspace", TaskId.random(), INPUT, "Implement", ignored -> {
                creations.incrementAndGet();
                return task;
            }));
            finish.countDown();
            assertThat(first.get(5, TimeUnit.SECONDS)).isSameAs(task);
            assertThat(second.get(5, TimeUnit.SECONDS)).isSameAs(task);
        }
        assertThat(creations.get()).isEqualTo(1);
        verify(github).fetchOpenIssueSource("token", REPO, 7);
    }

    @Test
    void epicUpdateAndStartShareCanonicalLock()
            throws Exception
    {
        CountDownLatch creating = new CountDownLatch(1);
        CountDownLatch finish = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> service.start("workspace", taskId, INPUT, "Implement", source -> {
                creating.countDown();
                await(finish);
                assertThat(epic.get()).isNull();
                service.persistTask(task, source, "Implement");
                return task;
            }));
            assertThat(creating.await(5, TimeUnit.SECONDS)).isTrue();
            var mark = executor.submit(() -> service.setEpic("another-workspace", 7, true));
            finish.countDown();
            first.get(5, TimeUnit.SECONDS);
            assertThat(mark.get(5, TimeUnit.SECONDS).isEpic()).isTrue();
        }
        assertThat(service.start("workspace", taskId, INPUT, "Implement", ignored -> {
            throw new AssertionError("Epic must not admit a new turn");
        })).isSameAs(task);
    }

    private static void await(CountDownLatch latch)
    {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for test operation");
            }
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }
}
