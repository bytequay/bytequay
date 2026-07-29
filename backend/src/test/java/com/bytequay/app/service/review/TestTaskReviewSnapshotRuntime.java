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
package com.bytequay.app.service.review;

import com.bytequay.app.developmentflow.execution.CapacityManager;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.ExecutionContext;
import com.bytequay.app.developmentflow.execution.ExecutionPorts.OperationCanceledException;
import com.bytequay.app.developmentflow.execution.ExecutionPorts.ResultDeliveryPort;
import com.bytequay.app.domain.InvestigationReviewData.AgentReviewRow;
import com.bytequay.app.service.checks.CodeFingerprints;
import com.bytequay.app.service.local.GitRunner;
import com.bytequay.app.service.review.TaskReviewSnapshotOperationHandler.SnapshotResult;
import com.bytequay.app.service.review.TaskReviewSnapshotRuntime.ExecutionSubject;
import com.bytequay.app.service.review.TaskReviewSnapshotRuntime.Status;
import com.bytequay.app.service.threads.TaskCommandExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.LOCAL_GIT;
import static com.bytequay.app.developmentflow.execution.CapacityManager.WorkflowSource.V2;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.OwnerKind.TASK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestTaskReviewSnapshotRuntime
{
    private static final Instant NOW = Instant.parse("2026-07-29T00:00:00Z");

    @Test
    void handlerCapturesTheExactDiffAndRechecksTheCodeSubject()
            throws Exception
    {
        TaskReviewSnapshotRuntime operations = mock(
                TaskReviewSnapshotRuntime.class);
        CodeFingerprints fingerprints = mock(CodeFingerprints.class);
        GitRunner git = mock(GitRunner.class);
        ExecutionContext execution = mock(ExecutionContext.class);
        ObjectMapper json = new ObjectMapper();
        Path worktree = Path.of("/tmp/task-review-worktree");
        ExecutionSubject subject = subject(true, Status.REQUESTED, null);
        when(execution.envelope()).thenReturn(envelope());
        when(operations.requireExecutionSubject("operation-1"))
                .thenReturn(subject);
        when(fingerprints.fingerprint(worktree)).thenReturn("fingerprint-1");
        when(git.headSha(worktree)).thenReturn("head-1");
        when(git.diff(worktree, "base-1", "head-1", 240_000))
                .thenReturn("diff --git a/A.java b/A.java\n+changed\n");
        when(git.rangeFiles(worktree, "base-1", "head-1"))
                .thenReturn(List.of(new GitRunner.CommitFileChange(
                        "A.java", "M", 0, 0)));
        when(git.fileAtRef(worktree, "head-1", "A.java"))
                .thenReturn("complete frozen body\n");

        TaskReviewSnapshotOperationHandler handler =
                new TaskReviewSnapshotOperationHandler(
                        operations, fingerprints, git, json,
                        Clock.fixed(NOW, ZoneOffset.UTC));
        DispatchTicket.DispatchResult dispatched = handler.execute(execution);
        SnapshotResult result = json.readValue(
                dispatched.payloadJson(), SnapshotResult.class);

        assertThat(dispatched.outcome())
                .isEqualTo(DispatchTicket.Outcome.SUCCEEDED);
        assertThat(dispatched.payloadJson()).isEqualTo(dispatched.evidenceJson());
        assertThat(result.subjectCurrent()).isTrue();
        assertThat(result.diff()).contains("+changed");
        assertThat(result.files())
                .extracting(file -> file.filename())
                .containsExactly("A.java");
        assertThat(result.fileContents())
                .containsEntry("A.java", "complete frozen body\n");
        verify(fingerprints, times(2)).fingerprint(worktree);
        verify(git, times(2)).headSha(worktree);
    }

    @Test
    void staleOwnerReturnsNoDiffAndNeverReadsThePatch()
            throws Exception
    {
        TaskReviewSnapshotRuntime operations = mock(
                TaskReviewSnapshotRuntime.class);
        CodeFingerprints fingerprints = mock(CodeFingerprints.class);
        GitRunner git = mock(GitRunner.class);
        ExecutionContext execution = mock(ExecutionContext.class);
        ObjectMapper json = new ObjectMapper();
        Path worktree = Path.of("/tmp/task-review-worktree");
        when(execution.envelope()).thenReturn(envelope());
        when(operations.requireExecutionSubject("operation-1"))
                .thenReturn(subject(false, Status.REQUESTED, null));

        TaskReviewSnapshotOperationHandler handler =
                new TaskReviewSnapshotOperationHandler(
                        operations, fingerprints, git, json,
                        Clock.fixed(NOW, ZoneOffset.UTC));
        SnapshotResult result = json.readValue(
                handler.execute(execution).payloadJson(), SnapshotResult.class);

        assertThat(result.subjectCurrent()).isFalse();
        assertThat(result.diff()).isEmpty();
        verify(fingerprints, never()).fingerprint(worktree);
        verify(git, never()).headSha(worktree);
        verify(git, never()).diff(
                worktree, "base-1", "head-1", 240_000);
    }

    @Test
    void deliveryHandsAnExactCurrentSnapshotToTheReviewOwner()
            throws Exception
    {
        TaskReviewSnapshotRuntime operations = mock(
                TaskReviewSnapshotRuntime.class);
        InvestigationReviewService reviews = mock(
                InvestigationReviewService.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<InvestigationReviewService> provider = mock(
                ObjectProvider.class);
        ObjectMapper json = new ObjectMapper();
        ExecutionSubject subject = subject(true, Status.REQUESTED, null);
        SnapshotResult result = new SnapshotResult(
                1, "operation-1", "review-1", "pr-1", "task-1",
                null, null, "main", "Title", "Description", 2,
                "/tmp/task-review-worktree", "fingerprint-1", "head-1",
                "base-1", true, "diff", List.of(), Map.of(),
                "fingerprint-1", "head-1", NOW.toEpochMilli(),
                NOW.toEpochMilli());
        String evidence = json.writeValueAsString(result);
        DispatchTicket.DispatchResult dispatched = new DispatchTicket.DispatchResult(
                envelope().fence(), DispatchTicket.Outcome.SUCCEEDED,
                evidence, evidence, null);
        when(operations.requireExecutionSubject("operation-1"))
                .thenReturn(subject);
        when(provider.getObject()).thenReturn(reviews);

        TaskReviewSnapshotResultDeliveryPort delivery =
                new TaskReviewSnapshotResultDeliveryPort(
                        operations,
                        provider,
                        new TaskCommandExecutor(new NoopTransactions()),
                        json);

        assertThat(delivery.deliver(
                envelope().owner(), envelope().fence(), dispatched).acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
        verify(reviews).acceptTaskReviewSnapshot(subject, result);
        verify(operations, never()).finishTerminal(
                any(), any(), any(), any());
    }

    @Test
    void interruptedTaskCaptureMapsRequestedCancellationToCanceled()
            throws Exception
    {
        TaskReviewSnapshotRuntime operations = mock(
                TaskReviewSnapshotRuntime.class);
        CodeFingerprints fingerprints = mock(CodeFingerprints.class);
        GitRunner git = mock(GitRunner.class);
        ExecutionContext execution = mock(ExecutionContext.class);
        Path worktree = Path.of("/tmp/task-review-worktree");
        when(execution.envelope()).thenReturn(envelope());
        when(execution.isCancellationRequested()).thenReturn(false, true);
        when(operations.requireExecutionSubject("operation-1"))
                .thenReturn(subject(true, Status.REQUESTED, null));
        when(fingerprints.fingerprint(worktree)).thenReturn("fingerprint-1");
        when(git.headSha(worktree)).thenReturn("head-1");
        when(git.diff(worktree, "base-1", "head-1", 240_000))
                .thenThrow(new InterruptedException("stopped"));
        TaskReviewSnapshotOperationHandler handler =
                new TaskReviewSnapshotOperationHandler(
                        operations, fingerprints, git, new ObjectMapper(),
                        Clock.fixed(NOW, ZoneOffset.UTC));

        try {
            assertThatThrownBy(() -> handler.execute(execution))
                    .isInstanceOf(OperationCanceledException.class);
        }
        finally {
            Thread.interrupted();
        }
    }

    @Test
    void terminalSnapshotDeliveriesAcceptOnlyExactReplays()
            throws Exception
    {
        assertExactInitialTaskReplay();
        assertExactLaterTaskReplay();
        assertExactReviewSessionReplay();
    }

    @Test
    void standaloneContinuationKeepsItsEstablishedQuickOrFullScope()
    {
        AgentReviewRow quick = new AgentReviewRow(
                "review-1", "repo-1", "pr-1", "base-1", "head-1",
                "ACTIVE", null, null, null);
        AgentReviewRow full = new AgentReviewRow(
                "review-2", "repo-1", "pr-1", "base-1", "head-1",
                "ACTIVE", "workspace-1", null, null);

        assertThat(InvestigationReviewService.standaloneSnapshotScope(quick))
                .isEqualTo(ReviewSessionSnapshotRuntime.Scope.QUICK);
        assertThat(InvestigationReviewService.standaloneSnapshotScope(full))
                .isEqualTo(ReviewSessionSnapshotRuntime.Scope.FULL);
    }

    private static void assertExactInitialTaskReplay()
            throws Exception
    {
        TaskReviewSnapshotRuntime operations = mock(
                TaskReviewSnapshotRuntime.class);
        when(operations.requireExecutionSubject("operation-1"))
                .thenReturn(subject(true, Status.COMPLETED, "receipt"));
        TaskReviewSnapshotResultDeliveryPort delivery =
                new TaskReviewSnapshotResultDeliveryPort(
                        operations, mock(ObjectProvider.class),
                        mock(TaskCommandExecutor.class), new ObjectMapper());

        assertReplay(delivery, envelope());
    }

    private static void assertExactLaterTaskReplay()
            throws Exception
    {
        TaskReviewRoundSnapshotRuntime operations = mock(
                TaskReviewRoundSnapshotRuntime.class);
        when(operations.requireExecutionSubject("operation-1")).thenReturn(
                new TaskReviewRoundSnapshotRuntime.ExecutionSubject(
                        "operation-1", "review-1", "command-1", "pr-1",
                        null, null, "main", "Title", "Description",
                        "task-1", 2, "/tmp/task-review-worktree",
                        "fingerprint-1", "head-1", "base-1", "{}",
                        TaskReviewRoundSnapshotRuntime.Status.COMPLETED,
                        "receipt", null, "round-1", true));
        TaskReviewRoundSnapshotResultDeliveryPort delivery =
                new TaskReviewRoundSnapshotResultDeliveryPort(
                        operations, mock(ObjectProvider.class),
                        mock(TaskCommandExecutor.class), new ObjectMapper());

        assertReplay(delivery, roundEnvelope());
    }

    private static void assertExactReviewSessionReplay()
            throws Exception
    {
        ReviewSessionSnapshotRuntime operations = mock(
                ReviewSessionSnapshotRuntime.class);
        when(operations.requireExecutionSubject("operation-1")).thenReturn(
                new ReviewSessionSnapshotRuntime.ExecutionSubject(
                        "operation-1", "review-1", "command-1", "pr-1",
                        "owner/repo", 7, "main", "Title", "Description",
                        "workspace-1", "/tmp/repo",
                        ReviewSessionSnapshotRuntime.Scope.FULL, "{}", "base-1",
                        "head-1", ReviewSessionSnapshotRuntime.Status.COMPLETED,
                        "receipt", null, "round-1", true));
        ReviewSessionSnapshotResultDeliveryPort delivery =
                new ReviewSessionSnapshotResultDeliveryPort(
                        operations, mock(ObjectProvider.class), new ObjectMapper());

        assertReplay(delivery, reviewSessionEnvelope());
    }

    private static void assertReplay(
            ResultDeliveryPort delivery,
            DispatchTicket.DispatchEnvelope envelope)
            throws Exception
    {
        DispatchTicket.DispatchResult exact = new DispatchTicket.DispatchResult(
                envelope.fence(), DispatchTicket.Outcome.SUCCEEDED,
                "receipt", "receipt", null);
        assertThat(delivery.deliver(
                envelope.owner(), envelope.fence(), exact).acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);

        DispatchTicket.DispatchResult changed = new DispatchTicket.DispatchResult(
                envelope.fence(), DispatchTicket.Outcome.SUCCEEDED,
                "changed", "changed", null);
        assertThatThrownBy(() -> delivery.deliver(
                envelope.owner(), envelope.fence(), changed))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("terminal replay differs");
    }

    private static ExecutionSubject subject(
            boolean current, Status status, String resultJson)
    {
        return new ExecutionSubject(
                "operation-1", "review-1", "pr-1", "task-1",
                null, null, "main", "Title", "Description", 2,
                "/tmp/task-review-worktree", "fingerprint-1", "head-1",
                "base-1", "{}", status, resultJson, null, current);
    }

    private static DispatchTicket.DispatchEnvelope envelope()
    {
        return new DispatchTicket.DispatchEnvelope(
                TaskReviewSnapshotOperationHandler.OPERATION_KIND,
                DispatchTicket.AsyncFamily.LOCAL_GIT,
                new DispatchTicket.OwnerReference(
                        TASK, "task-1",
                        TaskReviewSnapshotOperationHandler.CALLBACK_ROUTE),
                new DispatchTicket.OperationFence(
                        2L, null, null, "operation-1", 1,
                        "fingerprint-1", "head-1", "base-1"),
                new CapacityManager.CapacityRequest(
                        "operation-1", V2, Set.of(LOCAL_GIT),
                        new CapacityManager.CapacityScope(
                                "workspace-1", "trunk-1", "task-1", 2L),
                        false, true, true));
    }

    private static DispatchTicket.DispatchEnvelope roundEnvelope()
    {
        DispatchTicket.DispatchEnvelope initial = envelope();
        return new DispatchTicket.DispatchEnvelope(
                TaskReviewRoundSnapshotOperationHandler.OPERATION_KIND,
                initial.family(),
                new DispatchTicket.OwnerReference(
                        TASK, "task-1",
                        TaskReviewRoundSnapshotOperationHandler.CALLBACK_ROUTE),
                initial.fence(), initial.capacityRequest());
    }

    private static DispatchTicket.DispatchEnvelope reviewSessionEnvelope()
    {
        return new DispatchTicket.DispatchEnvelope(
                ReviewSessionSnapshotOperationHandler.OPERATION_KIND,
                DispatchTicket.AsyncFamily.LOCAL_GIT,
                new DispatchTicket.OwnerReference(
                        DispatchTicket.OwnerKind.REVIEW_SESSION, "review-1",
                        ReviewSessionSnapshotOperationHandler.CALLBACK_ROUTE),
                new DispatchTicket.OperationFence(
                        null, null, null, "operation-1", 1,
                        null, "head-1", "base-1"),
                new CapacityManager.CapacityRequest(
                        "operation-1", V2,
                        Set.of(LOCAL_GIT,
                                CapacityManager.CapacityLane.GITHUB),
                        new CapacityManager.CapacityScope(
                                "workspace-1", null, null, null),
                        false, false, false));
    }

    private static final class NoopTransactions
            extends AbstractPlatformTransactionManager
    {
        @Override
        protected Object doGetTransaction()
        {
            return new Object();
        }

        @Override
        protected void doBegin(
                Object transaction, TransactionDefinition definition) {}

        @Override
        protected void doCommit(DefaultTransactionStatus status) {}

        @Override
        protected void doRollback(DefaultTransactionStatus status) {}
    }
}
