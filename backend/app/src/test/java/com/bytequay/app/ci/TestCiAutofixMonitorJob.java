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
package com.bytequay.app.ci;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.app.agent.AgentServingGate;
import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.ci.CiAutofixRun.Authorization;
import com.bytequay.core.ci.CiAutofixRun.Scope;
import com.bytequay.core.ci.CiAutofixRunId;
import com.bytequay.core.ci.CiAutofixStore;
import com.bytequay.core.domain.PullRequestRef;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static com.bytequay.app.agent.AgentServingGates.readyAgentServingGate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestCiAutofixMonitorJob
{
    private static final Instant MONITOR_NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(MONITOR_NOW, ZoneOffset.UTC);

    @Test
    void advancesABoundedBatchAndContinuesAfterOneRunFails()
    {
        CiAutofixStore store = mock(CiAutofixStore.class);
        AgentTaskStore taskStore = mock(AgentTaskStore.class);
        CiAutofixService service = mock(CiAutofixService.class);
        CiAutofixRun first = run();
        CiAutofixRun second = run();
        when(store.listNonFinished(2)).thenReturn(ImmutableList.of(first, second));
        doThrow(new IllegalStateException("provider unavailable")).when(service).advance(first.id());

        new CiAutofixMonitorJob(store, taskStore, service, readyAgentServingGate(), CLOCK, 2).monitor();

        verify(service).advance(first.id());
        verify(service).advance(second.id());
    }

    @Test
    void skipsAnOverlappingMonitorPass()
            throws Exception
    {
        CiAutofixStore store = mock(CiAutofixStore.class);
        AgentTaskStore taskStore = mock(AgentTaskStore.class);
        CiAutofixService service = mock(CiAutofixService.class);
        CiAutofixRun run = run();
        when(store.listNonFinished(1)).thenReturn(ImmutableList.of(run));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(invocation -> {
            entered.countDown();
            assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
            return run;
        }).when(service).advance(run.id());
        CiAutofixMonitorJob job = new CiAutofixMonitorJob(store, taskStore, service, readyAgentServingGate(), CLOCK, 1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var firstPass = executor.submit(job::monitor);
            assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
            job.monitor();
            release.countDown();
            firstPass.get();
        }

        verify(store, times(1)).listNonFinished(1);
        verify(service, times(1)).advance(run.id());
    }

    @Test
    void retriesTaskCompletionForFinishedRunsWithLiveLeases()
    {
        CiAutofixStore store = mock(CiAutofixStore.class);
        AgentTaskStore taskStore = mock(AgentTaskStore.class);
        CiAutofixService service = mock(CiAutofixService.class);
        TaskId taskId = TaskId.random();
        CiAutofixRun finished = run()
                .assignAgentTask(taskId.toString(), Instant.parse("2026-08-18T00:00:01Z"))
                .finish(
                        CiAutofixRun.FinishReason.CANCELLED,
                        "cancelled",
                        Instant.parse("2026-08-18T00:00:02Z"));
        AgentTask task = AgentTask.created(
                taskId,
                Optional.empty(),
                false,
                "CI Autofix",
                AgentLaneId.CLAUDE_CLI,
                new RepositoryIdentity("bytequay", "sample"),
                Path.of("/tmp/bytequay-ci-monitor"),
                AgentPermissionPolicy.CI_AUTOFIX,
                Instant.parse("2026-08-18T00:00:00Z"));
        when(taskStore.listLive()).thenReturn(ImmutableList.of(task));
        when(store.findByAgentTaskId(taskId.toString())).thenReturn(Optional.of(finished));

        new CiAutofixMonitorJob(store, taskStore, service, readyAgentServingGate(), CLOCK, 1).monitor();

        verify(service).advance(finished.id());
    }

    @Test
    void backsOffRecentlyFailedFinishedRunCompletion()
    {
        CiAutofixStore store = mock(CiAutofixStore.class);
        AgentTaskStore taskStore = mock(AgentTaskStore.class);
        CiAutofixService service = mock(CiAutofixService.class);
        TaskId taskId = TaskId.random();
        CiAutofixRun finished = run()
                .assignAgentTask(taskId.toString(), Instant.parse("2026-08-18T00:00:01Z"))
                .finish(CiAutofixRun.FinishReason.CANCELLED, "cancelled", Instant.parse("2026-08-18T00:00:02Z"));
        AgentTask recentlyUpdated = AgentTask.created(
                taskId,
                Optional.empty(),
                false,
                "CI Autofix",
                AgentLaneId.CLAUDE_CLI,
                new RepositoryIdentity("bytequay", "sample"),
                Path.of("/tmp/bytequay-ci-live-backoff"),
                AgentPermissionPolicy.CI_AUTOFIX,
                MONITOR_NOW);
        when(taskStore.listLive()).thenReturn(ImmutableList.of(recentlyUpdated));
        when(store.findByAgentTaskId(taskId.toString())).thenReturn(Optional.of(finished));

        new CiAutofixMonitorJob(store, taskStore, service, readyAgentServingGate(), CLOCK, 1).monitor();

        verifyNoInteractions(service);
    }

    @Test
    void retriesWorktreeCleanupForFinishedRunsWithTerminalTasks()
    {
        CiAutofixStore store = mock(CiAutofixStore.class);
        AgentTaskStore taskStore = mock(AgentTaskStore.class);
        CiAutofixService service = mock(CiAutofixService.class);
        TaskId unfinishedTaskId = TaskId.random();
        TaskId taskId = TaskId.random();
        CiAutofixRun finished = run()
                .assignAgentTask(taskId.toString(), Instant.parse("2026-08-18T00:00:01Z"))
                .finish(
                        CiAutofixRun.FinishReason.GREEN,
                        "green",
                        Instant.parse("2026-08-18T00:00:02Z"));
        Instant now = Instant.parse("2026-08-18T00:00:00Z");
        AgentTask task = AgentTask.created(
                        taskId,
                        Optional.empty(),
                        false,
                        "CI Autofix",
                        AgentLaneId.CODEX_CLI,
                        new RepositoryIdentity("bytequay", "sample"),
                        Path.of("/tmp/bytequay-ci-cleanup-monitor"),
                        AgentPermissionPolicy.CI_AUTOFIX,
                        now)
                .fail("finished", now.plusSeconds(1), now.plus(Duration.ofDays(7)));
        AgentTask unfinishedTask = AgentTask.created(
                        unfinishedTaskId,
                        Optional.empty(),
                        false,
                        "CI Autofix",
                        AgentLaneId.CODEX_CLI,
                        new RepositoryIdentity("bytequay", "sample"),
                        Path.of("/tmp/bytequay-ci-blocked-cleanup-monitor"),
                        AgentPermissionPolicy.CI_AUTOFIX,
                        now.minusSeconds(1))
                .fail("finished", now, now.plus(Duration.ofDays(7)));
        CiAutofixRun unfinished = run()
                .assignAgentTask(unfinishedTaskId.toString(), Instant.parse("2026-08-18T00:00:01Z"));
        when(taskStore.listPendingWorktreeCleanup(
                AgentPermissionPolicy.CI_AUTOFIX,
                MONITOR_NOW.minus(Duration.ofMinutes(1))))
                .thenReturn(ImmutableList.of(unfinishedTask, task));
        when(store.findByAgentTaskId(unfinishedTaskId.toString())).thenReturn(Optional.of(unfinished));
        when(store.findByAgentTaskId(taskId.toString())).thenReturn(Optional.of(finished));

        new CiAutofixMonitorJob(store, taskStore, service, readyAgentServingGate(), CLOCK, 1).monitor();

        verify(service).advance(finished.id());
    }

    @Test
    void doesNotAdvanceWhileAgentRecoveryIsIncomplete()
    {
        CiAutofixStore store = mock(CiAutofixStore.class);
        AgentTaskStore taskStore = mock(AgentTaskStore.class);
        CiAutofixService service = mock(CiAutofixService.class);

        new CiAutofixMonitorJob(store, taskStore, service, new AgentServingGate(), CLOCK, 1).monitor();

        verifyNoInteractions(store, taskStore, service);
    }

    private static CiAutofixRun run()
    {
        CiAutofixRunId id = CiAutofixRunId.random();
        Instant now = Instant.parse("2026-08-18T00:00:00Z");
        return CiAutofixRun.create(
                id,
                id.toString(),
                new Scope(
                        PullRequestRef.of("bytequay", "sample", 1),
                        "workspace-1",
                        Optional.empty(),
                        "author",
                        "feature",
                        "main",
                        "codex"),
                "a".repeat(40),
                "a".repeat(40),
                "b".repeat(40),
                new Authorization(true, true, true, 3, now, now.plus(Duration.ofHours(8))),
                now);
    }
}
