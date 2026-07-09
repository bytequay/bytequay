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
package com.bytequay.app.service.threads;

import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.ThreadFlow;
import com.bytequay.app.domain.ThreadKind;
import com.bytequay.app.domain.ThreadStatus;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.WorktreeLeaseStore;
import com.bytequay.app.service.CredentialService;
import com.bytequay.app.service.workmodel.WorkModelResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Confirms the Phase-4 wiring fix: {@link ThreadRegistry#buildStage} now
 * resolves through the stage → task → thread cascade instead of always
 * calling {@code resolveForThread}, and a stage's session is built once
 * per stage key — so a mid-stage resolver change doesn't retroactively
 * affect the session already running, only a fresh stage key.
 */
class TestThreadRegistryWorkModel
{
    private static final String THREAD_ID = "thread-1";
    private static final String TASK_ID = "task-1";
    private static final Instant NOW = Instant.parse("2026-05-15T12:00:00Z");

    private final ThreadStore threadStore = mock(ThreadStore.class);
    private final TaskStore taskStore = mock(TaskStore.class);
    private final WorkModelResolver workModelResolver = mock(WorkModelResolver.class);
    private final WorktreeLeaseService leaseService =
            new WorktreeLeaseService(mock(WorktreeLeaseStore.class));

    @Test
    void getOrCreateWithAStageIdResolvesViaTheStageCascade()
    {
        when(threadStore.listMessages(anyString())).thenReturn(List.of());
        WorkModel stagePick = new WorkModel(WorkModelKind.API, "anthropic", "claude-opus-4-7", null);
        when(workModelResolver.resolveForStage(THREAD_ID, TASK_ID, "stage-1"))
                .thenReturn(new WorkModelResolver.Resolved(stagePick,
                        new WorkModelResolver.Provenance(WorkModelResolver.Source.STAGE, "stage-1", "stage-1")));
        ThreadRegistry registry = newRegistry();
        Task task = task(TASK_ID);

        registry.getOrCreate(logicLoopThread(), task, "stage-1");

        verify(workModelResolver).resolveForStage(THREAD_ID, TASK_ID, "stage-1");
        verify(workModelResolver, times(0)).resolveForThread(THREAD_ID);
    }

    @Test
    void getOrCreateWithNoStageIdFallsBackToTheTaskCascade()
    {
        when(threadStore.listMessages(anyString())).thenReturn(List.of());
        WorkModel taskPick = new WorkModel(WorkModelKind.API, "anthropic", "claude-opus-4-7", null);
        when(workModelResolver.resolveForTask(THREAD_ID, TASK_ID))
                .thenReturn(new WorkModelResolver.Resolved(taskPick,
                        new WorkModelResolver.Provenance(WorkModelResolver.Source.TASK, TASK_ID, TASK_ID)));
        ThreadRegistry registry = newRegistry();
        Task task = task(TASK_ID);

        registry.getOrCreate(logicLoopThread(), task, null);

        verify(workModelResolver).resolveForTask(THREAD_ID, TASK_ID);
    }

    @Test
    void aSecondGetOrCreateForTheSameStageReusesTheSessionWithoutReResolving()
    {
        when(threadStore.listMessages(anyString())).thenReturn(List.of());
        WorkModel firstPick = new WorkModel(WorkModelKind.API, "anthropic", "claude-opus-4-7", null);
        when(workModelResolver.resolveForStage(THREAD_ID, TASK_ID, "stage-1"))
                .thenReturn(new WorkModelResolver.Resolved(firstPick,
                        new WorkModelResolver.Provenance(WorkModelResolver.Source.STAGE, "stage-1", "stage-1")));
        ThreadRegistry registry = newRegistry();
        Task task = task(TASK_ID);

        ThreadAgent first = registry.getOrCreate(logicLoopThread(), task, "stage-1");
        // Simulate the picker being changed mid-stage: the resolver would
        // now return something different, but the already-built session
        // must not pick it up.
        WorkModel changedPick = new WorkModel(WorkModelKind.API, "anthropic", "claude-sonnet-4-6", null);
        when(workModelResolver.resolveForStage(THREAD_ID, TASK_ID, "stage-1"))
                .thenReturn(new WorkModelResolver.Resolved(changedPick,
                        new WorkModelResolver.Provenance(WorkModelResolver.Source.STAGE, "stage-1", "stage-1")));

        ThreadAgent second = registry.getOrCreate(logicLoopThread(), task, "stage-1");

        assertThat(second).isSameAs(first);
        // Resolved exactly once — at the first build, not on the cache hit.
        verify(workModelResolver, times(1)).resolveForStage(THREAD_ID, TASK_ID, "stage-1");
    }

    @Test
    void aFreshStageKeyPicksUpTheChangedOverride()
    {
        when(threadStore.listMessages(anyString())).thenReturn(List.of());
        WorkModel firstPick = new WorkModel(WorkModelKind.API, "anthropic", "claude-opus-4-7", null);
        when(workModelResolver.resolveForStage(THREAD_ID, TASK_ID, "stage-1"))
                .thenReturn(new WorkModelResolver.Resolved(firstPick,
                        new WorkModelResolver.Provenance(WorkModelResolver.Source.STAGE, "stage-1", "stage-1")));
        WorkModel secondPick = new WorkModel(WorkModelKind.API, "anthropic", "claude-sonnet-4-6", null);
        when(workModelResolver.resolveForStage(THREAD_ID, TASK_ID, "stage-2"))
                .thenReturn(new WorkModelResolver.Resolved(secondPick,
                        new WorkModelResolver.Provenance(WorkModelResolver.Source.STAGE, "stage-2", "stage-2")));
        ThreadRegistry registry = newRegistry();
        Task task = task(TASK_ID);

        registry.getOrCreate(logicLoopThread(), task, "stage-1");
        registry.getOrCreate(logicLoopThread(), task, "stage-2");

        verify(workModelResolver).resolveForStage(THREAD_ID, TASK_ID, "stage-1");
        verify(workModelResolver).resolveForStage(THREAD_ID, TASK_ID, "stage-2");
    }

    @Test
    void codexTrunkStartsWithPlanningReasoningEffort()
    {
        when(threadStore.listMessages(anyString())).thenReturn(List.of());
        ThreadRegistry registry = newRegistry();
        Thread trunk = cliThread("codex", "gpt-5", /* sessionId */ null);

        CodexCliThreadAgent agent = (CodexCliThreadAgent) registry.getOrCreateTrunk(trunk);

        assertThat(agent.buildCommand("plan the next task").command())
                .containsSubsequence("-c", "model_reasoning_effort=\"high\"");
    }

    @Test
    void claudeBrainStartsWithPlanningReasoningEffort()
    {
        when(threadStore.listMessages(anyString())).thenReturn(List.of());
        when(taskStore.findTaskById(TASK_ID)).thenReturn(Optional.of(task(TASK_ID)));
        WorkModel brainPick = new WorkModel(WorkModelKind.CLI, "claude-code", "claude-sonnet-4-6", null);
        when(workModelResolver.resolveForThread("brain-1"))
                .thenReturn(new WorkModelResolver.Resolved(brainPick,
                        new WorkModelResolver.Provenance(WorkModelResolver.Source.THREAD, "brain-1", "brain-1")));
        ThreadRegistry registry = newRegistry();

        ClaudeCodeCliThreadAgent agent = (ClaudeCodeCliThreadAgent)
                registry.getOrCreate(brainThread(), null, null);

        assertThat(agent.buildCommand("review this iteration").command())
                .containsSubsequence("--effort", "high");
    }

    private ThreadRegistry newRegistry()
    {
        ObjectMapper mapper = new ObjectMapper();
        return new ThreadRegistry(
                threadStore,
                taskStore,
                new StreamJsonParser(mapper),
                mapper,
                mock(McpPermissionGate.class),
                sameThreadExecutor(),
                mock(CheckpointTrigger.class),
                () -> "",
                leaseService,
                thread -> System.getProperty("java.io.tmpdir"),
                null,
                null,
                workModelResolver,
                mock(CredentialService.class),
                null,
                null,
                null,
                null);
    }

    private static Thread logicLoopThread()
    {
        return new Thread(
                THREAD_ID, ThreadKind.LOGIC_LOOP, "anthropic", /* agentSessionId */ null,
                "Work-model wiring test", ThreadStatus.IDLE, "claude-sonnet-4-6",
                0L, 0L, 0L, NOW, NOW, null, null,
                ThreadFlow.BUILD, "ws-default", null, null);
    }

    private static Thread cliThread(String provider, String model, String sessionId)
    {
        return new Thread(
                THREAD_ID, ThreadKind.CLI_AGENT, provider, sessionId,
                "Work-model wiring test", ThreadStatus.IDLE, model,
                0L, 0L, 0L, NOW, NOW, null, null,
                ThreadFlow.BUILD, "ws-default", null, null);
    }

    private static Thread brainThread()
    {
        return new Thread(
                "brain-1", ThreadKind.BRAIN_AGENT, "claude-code", /* agentSessionId */ null,
                "Brain wiring test", ThreadStatus.IDLE, "claude-sonnet-4-6",
                0L, 0L, 0L, NOW, NOW, null, null,
                ThreadFlow.BUILD, "ws-default", null, null, 1, TASK_ID);
    }

    private static Task task(String id)
    {
        return new Task(
                id, THREAD_ID, /* seq */ 1L, TaskStatus.RUNNING,
                /* branchName */ "auto/" + id, /* worktreePath */ null,
                /* baseBranch */ "main", /* workingDir */ "/tmp/repo",
                /* processPid */ null, /* logPath */ null,
                /* prNumber */ null, /* prState */ null, /* ciState */ null,
                /* taskType */ "DEVELOP",
                /* linkedPrNumber */ null, /* linkedIssueNumber */ null,
                0L, 0L, 0L,
                /* agentSessionId */ null,
                NOW, null, null, null, null, null);
    }

    private static ExecutorService sameThreadExecutor()
    {
        return new AbstractExecutorService()
        {
            private volatile boolean shutdown;

            @Override public void shutdown() { shutdown = true; }

            @Override
            public List<Runnable> shutdownNow()
            {
                shutdown = true;
                return List.of();
            }

            @Override public boolean isShutdown() { return shutdown; }
            @Override public boolean isTerminated() { return shutdown; }
            @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return shutdown; }
            @Override public void execute(Runnable command) { command.run(); }
        };
    }
}
