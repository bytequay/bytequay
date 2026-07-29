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
import com.bytequay.app.developmentflow.execution.quality.QualityIssuePublishOperationHandler.EffectResult;
import com.bytequay.app.developmentflow.execution.quality.QualityIssuePublishOperationHandler.Operation;
import com.bytequay.app.developmentflow.execution.quality.QualityIssuePublishOperationHandler.Status;
import com.bytequay.app.domain.IssueOrigin;
import com.bytequay.app.domain.Notification;
import com.bytequay.app.domain.NotificationKind;
import com.bytequay.app.domain.NotificationStatus;
import com.bytequay.app.domain.RepoIssue;
import com.bytequay.app.service.IssueOriginService;
import com.bytequay.app.service.tools.ParkedProposal;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestV2QualityIssuePublishRuntime
{
    private static final Instant NOW = Instant.parse("2026-07-29T05:00:00Z");

    private SqliteQualityIssuePublishStore store;
    private IssueOriginService origins;
    private TransactionTemplate transactions;
    private ObjectMapper json;
    private V2QualityIssuePublishRuntime runtime;

    @BeforeEach
    void setUp()
    {
        store = mock(SqliteQualityIssuePublishStore.class);
        origins = mock(IssueOriginService.class);
        transactions = mock(TransactionTemplate.class);
        json = new ObjectMapper();
        doAnswer(invocation -> {
            invocation.<Consumer<TransactionStatus>>getArgument(0)
                    .accept(mock(TransactionStatus.class));
            return null;
        }).when(transactions).executeWithoutResult(any());
        runtime = new V2QualityIssuePublishRuntime(
                store, origins, transactions, json,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void approveAndDiscardOnlyDelegateDatabaseCommands()
    {
        Notification notification = notification();
        ParkedProposal.CreateIssue proposal = proposal();
        Operation requested = operation(Status.REQUESTED, null);
        when(store.authorize(
                notification, proposal, "edited", NOW)).thenReturn(requested);

        assertThat(runtime.approve(notification, proposal, "edited").message())
                .isEqualTo("Issue publication queued.");
        assertThat(runtime.discard(notification, proposal).resolution())
                .isEqualTo("discarded");

        verify(store).authorize(notification, proposal, "edited", NOW);
        verify(store).discard(notification, NOW);
        verify(origins, never()).recordCreated(any(), any());
    }

    @Test
    void succeededDeliveryRecordsProvenanceOnceAndAcceptsExactReplay()
            throws Exception
    {
        RepoIssue issue = issue();
        Operation succeeded = operation(Status.SUCCEEDED, issue);
        DispatchTicket.DispatchResult result = succeededResult(issue);
        Operation delivered = operation(
                Status.DELIVERED, issue, result.evidenceJson(), NOW);
        when(store.require("operation-1"))
                .thenReturn(succeeded, delivered);

        DispatchTicket.DeliveryReceipt first = runtime.deliver(
                owner(), fence(), result);
        DispatchTicket.DeliveryReceipt replay = runtime.deliver(
                owner(), fence(), result);

        assertThat(first.acceptance()).isEqualTo(
                DispatchTicket.Acceptance.ACCEPTED);
        assertThat(replay.acceptance()).isEqualTo(
                DispatchTicket.Acceptance.ACCEPTED);
        ArgumentCaptor<RepoIssue> created =
                ArgumentCaptor.forClass(RepoIssue.class);
        verify(origins).recordCreated(
                created.capture(), eq(IssueOrigin.QUALITY_SCAN));
        assertThat(created.getValue().id()).isEqualTo(issue.id());
        assertThat(created.getValue().number()).isEqualTo(issue.number());
        assertThat(created.getValue().htmlUrl()).isEqualTo(issue.htmlUrl());
        verify(store, times(2)).finishDelivery(
                "operation-1", Status.DELIVERED, result.evidenceJson(),
                null, NOW);
    }

    @Test
    void mismatchedTerminalReplayIsRejectedByTheOwnerLedger()
            throws Exception
    {
        RepoIssue issue = issue();
        DispatchTicket.DispatchResult succeeded = succeededResult(issue);
        Operation delivered = operation(
                Status.DELIVERED, issue, succeeded.evidenceJson(), NOW);
        when(store.require("operation-1")).thenReturn(delivered);
        doThrow(new IllegalStateException(
                "Quality issue delivery was already completed differently"))
                .when(store).finishDelivery(
                        "operation-1", Status.FAILED, "{}", "changed", NOW);
        DispatchTicket.DispatchResult mismatch = new DispatchTicket.DispatchResult(
                fence(), DispatchTicket.Outcome.FAILED, null, "{}", "changed");

        assertThatThrownBy(() -> runtime.deliver(owner(), fence(), mismatch))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("completed differently");
        verify(origins, never()).recordCreated(any(), any());
    }

    @Test
    void failedAndCanceledResultsStillReachTypedTerminalDelivery()
    {
        Operation failed = operation(Status.FAILED, null);
        Operation requested = operation(Status.REQUESTED, null);
        when(store.require("operation-1"))
                .thenReturn(failed, requested);
        DispatchTicket.DispatchResult failedResult = new DispatchTicket.DispatchResult(
                fence(), DispatchTicket.Outcome.FAILED, null, "{}", "rejected");
        DispatchTicket.DispatchResult canceledResult =
                DispatchTicket.DispatchResult.canceled(fence());

        assertThat(runtime.deliver(owner(), fence(), failedResult).acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);
        assertThat(runtime.deliver(owner(), fence(), canceledResult).acceptance())
                .isEqualTo(DispatchTicket.Acceptance.ACCEPTED);

        verify(store).finishDelivery(
                "operation-1", Status.FAILED, "{}", "rejected", NOW);
        verify(store).finishDelivery(
                "operation-1", Status.CANCELED, "{}",
                "cancel requested before launch", NOW);
        verify(origins, never()).recordCreated(any(), any());
    }

    @Test
    void wrongOwnerOrRawFenceIsSupersededWithoutTouchingTheLedger()
    {
        DispatchTicket.OwnerReference wrongOwner =
                new DispatchTicket.OwnerReference(
                        DispatchTicket.OwnerKind.TASK, "task-1", "WRONG");
        DispatchTicket.DispatchResult matchingResult =
                new DispatchTicket.DispatchResult(
                        fence(), DispatchTicket.Outcome.FAILED,
                        null, "{}", "wrong owner");
        DispatchTicket.OperationFence wrongFence = new DispatchTicket.OperationFence(
                7L, null, null, "operation-2", 1,
                null, null, null);
        DispatchTicket.DispatchResult wrongResult = new DispatchTicket.DispatchResult(
                wrongFence, DispatchTicket.Outcome.FAILED,
                null, "{}", "wrong");

        assertThat(runtime.deliver(
                wrongOwner, fence(), matchingResult).acceptance())
                .isEqualTo(DispatchTicket.Acceptance.SUPERSEDED);
        assertThat(runtime.deliver(owner(), fence(), wrongResult).acceptance())
                .isEqualTo(DispatchTicket.Acceptance.SUPERSEDED);
        verify(store, never()).require(any());
    }

    private DispatchTicket.DispatchResult succeededResult(RepoIssue issue)
            throws Exception
    {
        EffectResult effect = new EffectResult(
                1, "operation-1", "notification-1", "task-1", 7,
                issue.id(), issue.number(), issue.htmlUrl(), issue.title());
        String value = json.writeValueAsString(effect);
        return new DispatchTicket.DispatchResult(
                fence(), DispatchTicket.Outcome.SUCCEEDED,
                value, value, null);
    }

    private static Notification notification()
    {
        return new Notification(
                "notification-1", NotificationKind.AWAITING_REVIEW,
                "trunk-1", "task-1", NotificationStatus.UNREAD,
                "{}", NOW, null);
    }

    private static ParkedProposal.CreateIssue proposal()
    {
        return new ParkedProposal.CreateIssue(
                "Finding", "Details",
                new ParkedProposal.RepoRef("acme", "widget"));
    }

    private static DispatchTicket.OwnerReference owner()
    {
        return new DispatchTicket.OwnerReference(
                DispatchTicket.OwnerKind.TASK, "task-1",
                QualityIssuePublishOperationHandler.CALLBACK_ROUTE);
    }

    private static DispatchTicket.OperationFence fence()
    {
        return new DispatchTicket.OperationFence(
                7L, null, null, "operation-1", 1,
                null, null, null);
    }

    private static Operation operation(Status status, RepoIssue issue)
    {
        return operation(status, issue, null, null);
    }

    private static Operation operation(
            Status status, RepoIssue issue, String resultJson, Instant deliveredAt)
    {
        return new Operation(
                "publish-1", "operation-1", "notification-1", "task-1", 7,
                "workspace-1", "trunk-1", "acme", "widget", "Finding",
                "Details", "<!-- marker -->", "a".repeat(64), status, issue,
                status == Status.FAILED ? "rejected" : null,
                resultJson, deliveredAt, "ticket-1");
    }

    private static RepoIssue issue()
    {
        return new RepoIssue(
                91, 17, "Finding", "bot", "open",
                "https://example.test/issues/17", NOW, List.of(), 0,
                IssueOrigin.QUALITY_SCAN);
    }
}
