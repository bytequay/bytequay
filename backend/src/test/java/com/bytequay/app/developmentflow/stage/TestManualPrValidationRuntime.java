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
package com.bytequay.app.developmentflow.stage;

import com.bytequay.app.developmentflow.execution.CapacityManager;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.ExecutionContext;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.developmentflow.stage.persistence.SqliteManualPrValidationStore;
import com.bytequay.app.developmentflow.stage.persistence.SqliteManualPrValidationStore.Operation;
import com.bytequay.app.developmentflow.stage.persistence.SqliteManualPrValidationStore.RequestContext;
import com.bytequay.app.developmentflow.stage.persistence.SqliteManualPrValidationStore.Status;
import com.bytequay.app.service.checks.CodeFingerprints;
import com.bytequay.app.service.checks.RepoTestValidationCheck;
import com.bytequay.app.service.checks.ValidationFailure;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;

import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.VALIDATION;
import static com.bytequay.app.developmentflow.execution.CapacityManager.WorkflowSource.V2;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.OwnerKind.TASK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestManualPrValidationRuntime
{
    private static final Instant NOW = Instant.parse("2026-07-29T00:00:00Z");

    @Test
    void commandPersistsTheAuthoritativeTaskSubjectWithoutRunningGitInline()
    {
        SqliteManualPrValidationStore store = mock(SqliteManualPrValidationStore.class);
        TaskCommandExecutor commands = mock(TaskCommandExecutor.class);
        RequestContext context = new RequestContext(
                "pr-1", "task-1", 2, "workspace-1", "trunk-1", "/tmp/worktree",
                "fingerprint-1", "head-1", "base-1");
        Operation completed = new Operation(
                "operation-1", "command-1", "pr-1", "task-1", 2,
                Status.COMPLETED, "{}", null);
        when(store.requireRequestContext("pr-1")).thenReturn(context);
        when(store.request("command-1", context, NOW)).thenReturn(completed);
        when(store.requireOperation("operation-1")).thenReturn(completed);
        doAnswer(invocation -> invocation.getArgument(1, Supplier.class).get())
                .when(commands).execute(any(), any());

        ManualPrValidationRuntime runtime = new ManualPrValidationRuntime(
                store, commands, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(runtime.runAndWait("command-1", "pr-1")).isEqualTo(completed);
        verify(store).request("command-1", context, NOW);
    }

    @Test
    void boundedWaitReturnsTheSameNonterminalOperationForIdempotentPolling()
    {
        SqliteManualPrValidationStore store = mock(SqliteManualPrValidationStore.class);
        TaskCommandExecutor commands = mock(TaskCommandExecutor.class);
        RequestContext context = new RequestContext(
                "pr-1", "task-1", 2, "workspace-1", "trunk-1", "/tmp/worktree",
                "fingerprint-1", "head-1", "base-1");
        Operation requested = new Operation(
                "operation-1", "command-1", "pr-1", "task-1", 2,
                Status.REQUESTED, null, null);
        when(store.requireRequestContext("pr-1")).thenReturn(context);
        when(store.request("command-1", context, NOW)).thenReturn(requested);
        when(store.requireOperation("operation-1")).thenReturn(requested);
        doAnswer(invocation -> invocation.getArgument(1, Supplier.class).get())
                .when(commands).execute(any(), any());

        ManualPrValidationRuntime runtime = new ManualPrValidationRuntime(
                store, commands, Clock.fixed(NOW, ZoneOffset.UTC), Duration.ZERO);

        assertThat(runtime.runAndWait("command-1", "pr-1")).isEqualTo(requested);
    }

    @Test
    void cancellationInterruptsAndCancelsAnInFlightTestRun()
            throws Exception
    {
        SqliteManualPrValidationStore store = mock(SqliteManualPrValidationStore.class);
        RepoTestValidationCheck tests = mock(RepoTestValidationCheck.class);
        CodeFingerprints fingerprints = mock(CodeFingerprints.class);
        GitRunner git = mock(GitRunner.class);
        ExecutionContext execution = mock(ExecutionContext.class);
        Path worktree = Path.of("/tmp/worktree");
        when(execution.envelope()).thenReturn(envelope());
        when(execution.isCancellationRequested()).thenReturn(false, false, true);
        when(store.requireExecutionContext("operation-1")).thenReturn(
                new SqliteManualPrValidationStore.ExecutionContext(
                        "operation-1", "pr-1", "task-1", 2,
                        worktree.toString(), "fingerprint-1", "head-1",
                        "base-1", true));
        when(fingerprints.fingerprint(worktree)).thenReturn("fingerprint-1");
        when(git.headSha(worktree)).thenReturn("head-1");
        when(tests.runWithoutRecording(worktree)).thenReturn(Optional.of(
                new RepoTestValidationCheck.TestRun(
                        "maven", true, 10, List.of(), 1, 11)));

        ManualPrValidationOperationHandler handler =
                new ManualPrValidationOperationHandler(
                        store, tests, fingerprints, git, new ObjectMapper());

        assertThatThrownBy(() -> handler.execute(execution))
                .isInstanceOf(ExecutionPorts.OperationCanceledException.class);
        verify(execution).onCancellation(any());
        verify(tests).runWithoutRecording(worktree);
    }

    @Test
    void acceptedResultCarriesTypedProjectionWithoutRecordingDuringExecution()
            throws Exception
    {
        SqliteManualPrValidationStore store = mock(SqliteManualPrValidationStore.class);
        RepoTestValidationCheck tests = mock(RepoTestValidationCheck.class);
        CodeFingerprints fingerprints = mock(CodeFingerprints.class);
        GitRunner git = mock(GitRunner.class);
        ExecutionContext execution = mock(ExecutionContext.class);
        Path worktree = Path.of("/tmp/worktree");
        var context = new SqliteManualPrValidationStore.ExecutionContext(
                "operation-1", "pr-1", "task-1", 2,
                worktree.toString(), "fingerprint-1", "head-1", "base-1", true);
        ValidationFailure failure = new ValidationFailure("test", "failed");
        when(execution.envelope()).thenReturn(envelope());
        when(store.requireExecutionContext("operation-1")).thenReturn(context);
        when(fingerprints.fingerprint(worktree)).thenReturn("fingerprint-1");
        when(git.headSha(worktree)).thenReturn("head-1");
        when(tests.runWithoutRecording(worktree)).thenReturn(Optional.of(
                new RepoTestValidationCheck.TestRun(
                        "maven", false, 10, List.of(failure), 1, 11)));
        ObjectMapper json = new ObjectMapper();
        ManualPrValidationOperationHandler handler =
                new ManualPrValidationOperationHandler(
                        store, tests, fingerprints, git, json);

        DispatchTicket.DispatchResult result = handler.execute(execution);
        Operation requested = new Operation(
                "operation-1", "command-1", "pr-1", "task-1", 2,
                Status.REQUESTED, null, null);
        Operation completed = new Operation(
                "operation-1", "command-1", "pr-1", "task-1", 2,
                Status.COMPLETED, result.evidenceJson(), null);
        when(store.requireOperation("operation-1")).thenReturn(requested);
        when(store.finish(
                "operation-1", Status.COMPLETED.name(),
                result.evidenceJson(), null, NOW)).thenReturn(completed);
        ManualPrValidationResultDeliveryPort delivery =
                new ManualPrValidationResultDeliveryPort(
                        store, json, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(delivery.deliver(
                envelope().owner(), envelope().fence(), result).acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
        assertThat(json.readTree(result.payloadJson())
                .path("testRun").path("ecosystem").asText()).isEqualTo("maven");
        verify(tests, never()).run(any(), any());
        verify(store).finish(
                "operation-1", Status.COMPLETED.name(),
                result.evidenceJson(), null, NOW);
    }

    private static DispatchTicket.DispatchEnvelope envelope()
    {
        DispatchTicket.OperationFence fence = new DispatchTicket.OperationFence(
                2L, null, null, "operation-1", 1,
                "fingerprint-1", "head-1", "base-1");
        return new DispatchTicket.DispatchEnvelope(
                ManualPrValidationOperationHandler.OPERATION_KIND,
                DispatchTicket.AsyncFamily.VALIDATION,
                new DispatchTicket.OwnerReference(
                        TASK, "task-1",
                        ManualPrValidationOperationHandler.CALLBACK_ROUTE),
                fence,
                new CapacityManager.CapacityRequest(
                        "operation-1", V2, Set.of(VALIDATION),
                        new CapacityManager.CapacityScope(
                                "workspace-1", "trunk-1", "task-1", 2L),
                        false, true, false));
    }
}
