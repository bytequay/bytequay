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
package com.bytequay.app.developmentflow.execution.quality;

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.ExecutionContext;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.bytequay.app.developmentflow.execution.quality.QualityIssuePublishOperationHandler.Operation;
import com.bytequay.app.developmentflow.execution.quality.QualityIssuePublishOperationHandler.Status;
import com.bytequay.app.domain.IssueOrigin;
import com.bytequay.app.domain.RepoIssue;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestQualityIssuePublishOperationHandler
{
    private static final Instant NOW = Instant.parse("2026-07-29T04:00:00Z");

    private QualityIssuePublishOperationHandler.OperationStore store;
    private QualityIssuePublishOperationHandler.Gateway github;
    private QualityIssuePublishOperationHandler handler;
    private ExecutionContext context;

    @BeforeEach
    void setUp()
    {
        store = mock(QualityIssuePublishOperationHandler.OperationStore.class);
        github = mock(QualityIssuePublishOperationHandler.Gateway.class);
        handler = new QualityIssuePublishOperationHandler(
                store, github, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        context = context();
    }

    @Test
    void firstExecutionProbesBeforeCreatingAndPersistsEvidence()
            throws Exception
    {
        Operation requested = operation(Status.REQUESTED, null);
        Operation executing = operation(Status.EXECUTING, null);
        RepoIssue created = issue();
        Operation succeeded = operation(Status.SUCCEEDED, created);
        when(store.require("operation-1")).thenReturn(requested);
        when(github.findExisting(requested)).thenReturn(Optional.empty());
        when(store.markExecuting("operation-1", NOW)).thenReturn(executing);
        when(github.create(executing)).thenReturn(created);
        when(store.markSucceeded("operation-1", created, NOW))
                .thenReturn(succeeded);

        DispatchTicket.DispatchResult result = handler.execute(context);

        assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.SUCCEEDED);
        var order = inOrder(github, store);
        order.verify(github).findExisting(requested);
        order.verify(store).markExecuting("operation-1", NOW);
        order.verify(github).create(executing);
        order.verify(store).markSucceeded("operation-1", created, NOW);
    }

    @Test
    void restartReconciliationOnlyProbesAndNeverRepeatsCreate()
            throws Exception
    {
        Operation executing = operation(Status.EXECUTING, null);
        RepoIssue observed = issue();
        Operation succeeded = operation(Status.SUCCEEDED, observed);
        when(store.require("operation-1")).thenReturn(executing);
        when(github.findExisting(executing)).thenReturn(Optional.of(observed));
        when(store.markSucceeded("operation-1", observed, NOW))
                .thenReturn(succeeded);

        DispatchTicket.DispatchResult result = handler.reconcile(context);

        assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.SUCCEEDED);
        verify(github).findExisting(executing);
        verify(github, never()).create(executing);
    }

    @Test
    void unresolvedAmbiguityFailsClosedWithoutRepeatingCreate()
            throws Exception
    {
        Operation executing = operation(Status.EXECUTING, null);
        when(store.require("operation-1")).thenReturn(executing);
        when(github.findExisting(executing)).thenReturn(Optional.empty());
        when(store.markIndeterminate(
                "operation-1", "Remote issue marker was not observed", NOW))
                .thenReturn(operation(Status.INDETERMINATE, null));

        assertThatThrownBy(() -> handler.reconcile(context))
                .isInstanceOf(ExecutionPorts.IndeterminateExecutionException.class)
                .hasMessageContaining("will not be repeated");
        verify(github, never()).create(executing);
    }

    private static ExecutionContext context()
    {
        ExecutionContext context = mock(ExecutionContext.class);
        DispatchTicket.DispatchEnvelope envelope = mock(
                DispatchTicket.DispatchEnvelope.class);
        when(context.envelope()).thenReturn(envelope);
        when(envelope.operationKind()).thenReturn(
                QualityIssuePublishOperationHandler.OPERATION_KIND);
        when(envelope.family()).thenReturn(
                DispatchTicket.AsyncFamily.GITHUB_EFFECT);
        when(envelope.owner()).thenReturn(new DispatchTicket.OwnerReference(
                DispatchTicket.OwnerKind.TASK, "task-1",
                QualityIssuePublishOperationHandler.CALLBACK_ROUTE));
        when(envelope.fence()).thenReturn(new DispatchTicket.OperationFence(
                7L, null, null, "operation-1", 1,
                null, null, null));
        return context;
    }

    private static Operation operation(Status status, RepoIssue issue)
    {
        return new Operation(
                "publish-1", "operation-1", "notification-1", "task-1", 7,
                "workspace-1", "trunk-1", "bytequay", "bytequay",
                "Finding", "Body", "<!-- marker -->", "a".repeat(64),
                status, issue, null, null, null, "ticket-1");
    }

    private static RepoIssue issue()
    {
        return new RepoIssue(
                91, 17, "Finding", "bot", "open",
                "https://example.test/issues/17", NOW, List.of(), 0,
                IssueOrigin.QUALITY_SCAN);
    }
}
