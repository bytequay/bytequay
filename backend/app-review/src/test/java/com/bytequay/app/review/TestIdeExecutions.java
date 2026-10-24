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
package com.bytequay.app.review;

import com.bytequay.agent.cli.process.ManagedProcess;
import com.bytequay.agent.cli.process.ProcessExit;
import com.bytequay.agent.cli.process.ProcessIdentity;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.LocalRun;
import com.bytequay.core.review.LocalRun.Scope;
import com.bytequay.core.review.LocalRun.Status;
import com.bytequay.core.review.LocalRunStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockMakers;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestIdeExecutions
{
    @TempDir
    Path directory;
    private final IdeRuntimeService runtime = mock(IdeRuntimeService.class);
    private final ReviewCodeService code = mock(ReviewCodeService.class);
    private final AgentTurnService turns = mock(AgentTurnService.class);
    private final LocalRunStore store = mock(LocalRunStore.class);
    private final ManagedProcess process = mock(ManagedProcess.class);
    private final CompletableFuture<ProcessExit> exit = new CompletableFuture<>();
    private final Map<String, LocalRun> rows = new HashMap<>();
    private final Map<String, String> output = new HashMap<>();
    private final TaskId taskId = TaskId.random();
    private final TaskCodeHost host = new TaskCodeHost("workspace", taskId.toString());
    private IdeExecutions executions;

    @BeforeEach
    void setUp()
    {
        AgentTask task = mock(AgentTask.class, withSettings().mockMaker(MockMakers.INLINE));
        when(task.id()).thenReturn(taskId);
        TaskWorktree worktree = new TaskWorktree(taskId, "workspace", RepositoryIdentity.valueOf("acme/project"), directory.resolve(taskId.toString()),
                "bytequay/task/" + taskId, "a".repeat(40));
        when(code.mutableTask(host)).thenReturn(task);
        when(code.ideWorktree(host)).thenReturn(worktree);
        when(code.workingCopySource(worktree.canonicalWorktree())).thenReturn(new ReviewCodeService.WorkingCopySource("a".repeat(40), ImmutableList.of("main.go"), "fingerprint"));
        when(process.launchId()).thenReturn("ide-launch");
        when(process.exit()).thenReturn(exit);
        when(process.observedDescendants()).thenReturn(Optional.of(ImmutableList.of()));
        when(runtime.trackExecution(taskId.toString(), "session")).thenReturn(new IdeRuntimeService.Execution(process, ImmutableList.of()));
        when(store.insert(any())).thenAnswer(call -> {
            LocalRun run = call.getArgument(0);
            rows.put(run.id(), run);
            return run;
        });
        when(store.update(any())).thenAnswer(call -> {
            LocalRun run = call.getArgument(0);
            rows.put(run.id(), run);
            return run;
        });
        when(store.update(any(), anyString())).thenAnswer(call -> {
            LocalRun run = call.getArgument(0);
            rows.put(run.id(), run);
            output.put(run.id(), call.getArgument(1));
            return run;
        });
        when(store.find(anyString())).thenAnswer(call -> Optional.ofNullable(rows.get(call.getArgument(0))));
        executions = new IdeExecutions(runtime, code, turns, mock(AgentReviewService.class), mock(AgentReviewStore.class), store, new ReviewLocks(),
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC), Runnable::run);
    }

    @Test
    void savesEvidenceButHoldsTheClaimUntilNewProcessesExit()
    {
        LocalRun run = start(Scope.TESTS);
        verify(turns).claimLocalRun(taskId, run.id());
        assertThat(run.launchId()).contains("ide-launch");
        assertThat(run.sourceFingerprint()).contains("fingerprint");
        ProcessHandle handle = ProcessHandle.current();
        when(process.observedDescendants()).thenReturn(Optional.of(ImmutableList.of(new ProcessIdentity(handle.pid(), handle.info().startInstant().orElseThrow()))));
        IdeExecutions.Output chunk = new IdeExecutions.Output(1, "expected 7, got 6\n", false);
        executions.output(taskId.toString(), "session", run.id(), chunk);
        assertThat(executions.output(run, 9)).contains(new LocalRunService.OutputChunk("7, got 6\n", chunk.text().length(), 0, false));
        executions.output(taskId.toString(), "session", run.id(), chunk);
        assertThatThrownBy(() -> executions.output(taskId.toString(), "other-session", run.id(), chunk)).hasMessageContaining("no longer active");
        assertThatThrownBy(() -> executions.output(taskId.toString(), "session", run.id(), new IdeExecutions.Output(1, "changed", false)))
                .hasMessageContaining("retry changed");
        LocalRun.TestClass result = new LocalRun.TestClass("main_test.go", OptionalLong.empty(), ImmutableList.of(
                new LocalRun.TestMethod("TestTwice", LocalRun.MethodStatus.FAILED, OptionalLong.of(15), Optional.of("expected 7, got 6"), Optional.empty())));
        IdeExecutions.Result finished = new IdeExecutions.Result(false, false, false, OptionalInt.empty(), ImmutableList.of(result));
        LocalRun recorded = executions.finish(taskId.toString(), "session", run.id(), finished);
        assertThat(recorded.status()).isEqualTo(Status.FAILED_ASSERTION);
        assertThat(recorded.treeUnverified()).isTrue();
        assertThat(recorded.counts()).contains(new LocalRun.Counts(1, 1, 0, 0));
        assertThat(output.get(run.id())).isEqualTo(chunk.text());
        verify(turns, never()).releaseLocalRun(any(), anyString());
        when(process.observedDescendants()).thenReturn(Optional.of(ImmutableList.of()));
        executions.reconcile();
        verify(turns).releaseLocalRun(taskId, run.id());
        assertThat(rows.get(run.id()).treeUnverified()).isFalse();
        assertThat(executions.finish(taskId.toString(), "session", run.id(), finished)).isEqualTo(rows.get(run.id()));
        verify(runtime, never()).stop(anyString(), anyString());
    }

    @Test
    void incompleteObservationRetainsTheClaimUntilExplicitRecovery()
    {
        LocalRun run = start(Scope.DEBUG);
        when(process.observedDescendants()).thenReturn(Optional.empty());
        LocalRun finished = executions.finish(taskId.toString(), "session", run.id(), new IdeExecutions.Result(false, false, false, OptionalInt.empty(), ImmutableList.of()));
        assertThat(finished.status()).isEqualTo(Status.COMPLETED);
        assertThat(finished.counts()).isEmpty();
        assertThat(finished.treeUnverified()).isTrue();
        executions.reconcile();
        verify(turns, never()).releaseLocalRun(any(), anyString());
        doAnswer(call -> {
            exit.complete(new ProcessExit(0, ProcessExit.Reason.CANCELLED, ProcessExit.TreeTermination.VERIFIED_STOPPED, Duration.ZERO, false, false));
            return null;
        }).when(runtime).stop(taskId.toString(), "session");
        assertThat(executions.stop(finished).treeUnverified()).isFalse();
        verify(turns).releaseLocalRun(taskId, run.id());
        assertThat(new IdeExecutions.Result(false, false, true, OptionalInt.empty(), ImmutableList.of()).status(Scope.TESTS)).isEqualTo(Status.RESULTS_UNAVAILABLE);
    }

    @Test
    void stopRequiresTheOriginalTaskAndEditorSession()
    {
        LocalRun run = start(Scope.RUN);
        assertThatThrownBy(() -> executions.stop(taskId.toString(), "other-session", run.id())).hasMessageContaining("no longer active");
        assertThatThrownBy(() -> executions.stop(TaskId.random().toString(), "session", run.id())).hasMessageContaining("no longer active");
        verify(runtime, never()).stop(anyString(), anyString());
        executions.stop(taskId.toString(), "session", run.id());
        verify(runtime).stop(taskId.toString(), "session");
    }

    @Test
    void failureBeforeDispatchReleasesOnlyItsOwnClaim()
    {
        when(runtime.trackExecution(taskId.toString(), "session")).thenThrow(new IllegalStateException("Runtime ownership unavailable"));
        assertThatThrownBy(() -> start(Scope.RUN)).hasMessageContaining("ownership unavailable");
        verify(turns).releaseLocalRun(eq(taskId), anyString());
        verify(store, never()).insert(any());
        verify(runtime, never()).stop(anyString(), anyString());
        assertThatThrownBy(() -> new IdeExecutions.Start("session", Scope.RUN, "Run", Optional.of("../other"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void runtimeCrashRecordsInterruptedEvidenceAndRetainsAnUnverifiedClaim()
    {
        LocalRun run = start(Scope.TESTS);
        executions.output(taskId.toString(), "session", run.id(), new IdeExecutions.Output(1, "before crash", false));
        exit.completeExceptionally(new IllegalStateException("Runtime crashed without process verification"));
        executions.reconcile();
        assertThat(rows.get(run.id()).status()).isEqualTo(Status.INTERRUPTED);
        assertThat(rows.get(run.id()).treeUnverified()).isTrue();
        assertThat(output.get(run.id())).isEqualTo("before crash");
        verify(turns, never()).releaseLocalRun(any(), anyString());
    }

    private LocalRun start(Scope scope)
    {
        return executions.start(host, new IdeExecutions.Start("session", scope, "Native execution", Optional.of("main.go")));
    }
}
