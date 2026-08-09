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
package com.bytequay.app.developmentflow.execution.agentturn;

import com.bytequay.app.developmentflow.execution.CapacityManager;
import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.ExecutionContext;
import com.bytequay.app.developmentflow.execution.ExecutionPorts;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;

import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.API;
import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.REVIEW;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.OwnerKind.REVIEW_ASSIGNMENT_TURN;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestReviewAssignmentTurnOperationHandler
{
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");

    private FakeStore store;
    private FakeProvider provider;
    private ReviewAssignmentTurnOperationHandler handler;

    @BeforeEach
    void setUp()
            throws Exception
    {
        store = new FakeStore(turn());
        provider = new FakeProvider();
        handler = new ReviewAssignmentTurnOperationHandler(
                store, provider, JSON, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void runsOnlyTheExactReadOnlyReviewTurn()
            throws Exception
    {
        DispatchTicket.DispatchResult result = handler.execute(
                context(envelope(ImmutableSet.of(REVIEW, API)), false));

        assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.SUCCEEDED);
        assertThat(store.starts).isOne();
        assertThat(provider.starts).isOne();
        assertThat(provider.request.access())
                .isEqualTo(AgentTurnProviderSession.Access.READ_ONLY);
        assertThat(provider.request.toolEndpoint().profile()).isEqualTo(
                AgentTurnProviderSession.ToolProfile.REVIEW_ASSIGNMENT_READ_ONLY);
        assertThat(provider.request.toolEndpoint().ownerId())
                .isEqualTo("review-turn-1");
        assertThat(provider.request.maxCostUsdMilli()).isEqualTo(500L);
    }

    @Test
    void wrongCapacityAndPreLaunchCancellationNeverOpenTheProvider()
            throws Exception
    {
        DispatchTicket.DispatchResult wrong = handler.execute(
                context(envelope(ImmutableSet.of(REVIEW)), false));
        assertThat(wrong.outcome()).isEqualTo(DispatchTicket.Outcome.FAILED);
        assertThat(wrong.error()).contains("requires exactly REVIEW and API");
        assertThat(provider.opens).isZero();

        DispatchTicket.DispatchResult canceled = handler.execute(
                context(envelope(ImmutableSet.of(REVIEW, API)), true));
        assertThat(canceled.outcome()).isEqualTo(DispatchTicket.Outcome.CANCELED);
        assertThat(provider.opens).isZero();
    }

    @Test
    void providerCannotReportSuccessPastTheFrozenTurnCap()
            throws Exception
    {
        provider.costUsdMilli = 501;

        DispatchTicket.DispatchResult result = handler.execute(
                context(envelope(ImmutableSet.of(REVIEW, API)), false));

        assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.FAILED);
        assertThat(result.error()).contains("exceeded the frozen review Turn cost cap");
    }

    @Test
    void waitingRoundRetriesAndReconciliationNeverReplaysTheProvider()
    {
        store.start = ReviewAssignmentTurnOperationHandler.StartDisposition.ROUND_WAITING;
        assertThatThrownBy(() -> handler.execute(
                context(envelope(ImmutableSet.of(REVIEW, API)), false)))
                .isInstanceOf(ExecutionPorts.RetryableExecutionException.class);
        assertThat(provider.opens).isZero();

        DispatchTicket.DispatchResult reconciled = handler.reconcile(
                context(envelope(ImmutableSet.of(REVIEW, API)), false));
        assertThat(reconciled.outcome())
                .isEqualTo(DispatchTicket.Outcome.INDETERMINATE);
        assertThat(provider.opens).isZero();
    }

    @Test
    void reviewTurnCannotOmitItsApprovalGate()
            throws Exception
    {
        store = new FakeStore(turn(false));
        handler = new ReviewAssignmentTurnOperationHandler(
                store, provider, JSON, Clock.fixed(NOW, ZoneOffset.UTC));

        DispatchTicket.DispatchResult result = handler.execute(
                context(envelope(ImmutableSet.of(REVIEW, API)), false));

        assertThat(result.outcome()).isEqualTo(DispatchTicket.Outcome.FAILED);
        assertThat(result.error()).contains("exact ReviewAssignmentTurn");
        assertThat(provider.opens).isZero();
    }

    private static ExecutionContext context(
            DispatchTicket.DispatchEnvelope envelope, boolean canceled)
    {
        ExecutionContext context = mock(ExecutionContext.class);
        when(context.envelope()).thenReturn(envelope);
        when(context.isCancellationRequested()).thenReturn(canceled);
        return context;
    }

    private static DispatchTicket.DispatchEnvelope envelope(
            Set<CapacityManager.CapacityLane> lanes)
    {
        DispatchTicket.OperationFence fence = new DispatchTicket.OperationFence(
                1L, null, null, "review-operation-1", 1,
                null, "head-1", null);
        CapacityManager.CapacityRequest capacity =
                new CapacityManager.CapacityRequest(
                        "review-operation-1", CapacityManager.WorkflowSource.V2,
                        lanes, new CapacityManager.CapacityScope(
                                "workspace-1", "trunk-1", "task-1", 1L),
                        false, false, false);
        return new DispatchTicket.DispatchEnvelope(
                ReviewAssignmentTurnOperationHandler.OPERATION_KIND,
                DispatchTicket.AsyncFamily.AGENT_TURN,
                new DispatchTicket.OwnerReference(
                        REVIEW_ASSIGNMENT_TURN, "review-turn-1",
                        ReviewAssignmentTurnOperationHandler.CALLBACK_ROUTE),
                fence, capacity);
    }

    private static ReviewAssignmentTurnOperationHandler.ExactTurn turn()
            throws Exception
    {
        return turn(true);
    }

    private static ReviewAssignmentTurnOperationHandler.ExactTurn turn(
            boolean approvalGate)
            throws Exception
    {
        AgentTurnProviderSession.OwnerToolEndpoint endpoint =
                new AgentTurnProviderSession.OwnerToolEndpoint(
                        "bytequay",
                        "http://127.0.0.1:53123/api/v2/review-assignment-turns/"
                                + "review-turn-1/operations/review-operation-1/mcp",
                        REVIEW_ASSIGNMENT_TURN, "review-turn-1",
                        "review-operation-1",
                        AgentTurnProviderSession.ToolProfile.REVIEW_ASSIGNMENT_READ_ONLY,
                        approvalGate ? "mcp__bytequay__approval_prompt" : null);
        String input = JSON.writeValueAsString(
                new AgentTurnOperationHandler.LaunchInput(
                        1, AgentTurnProviderSession.Transport.API, "openai",
                        "account-1", "gpt-5.6", "high", "/tmp/task-1",
                        "review role", "review the exact commit", endpoint));
        return new ReviewAssignmentTurnOperationHandler.ExactTurn(
                "review-turn-1", "review-assignment-1", "review-round-1",
                "review-1", "investigate", "review-assignment-1", null,
                "REQUESTED", "review-operation-1",
                1, "head-1", input, 500, "RUNNING", "ACTIVE", "workspace-1",
                "trunk-1", "task-1", 1L, "ACTIVE", "head-1");
    }

    private static final class FakeStore
            implements ReviewAssignmentTurnOperationHandler.Store
    {
        private final ReviewAssignmentTurnOperationHandler.ExactTurn turn;
        private ReviewAssignmentTurnOperationHandler.StartDisposition start =
                ReviewAssignmentTurnOperationHandler.StartDisposition.STARTED;
        private int starts;

        private FakeStore(ReviewAssignmentTurnOperationHandler.ExactTurn turn)
        {
            this.turn = turn;
        }

        @Override
        public Optional<ReviewAssignmentTurnOperationHandler.ExactTurn> find(
                String turnId)
        {
            return turn.turnId().equals(turnId)
                    ? Optional.of(turn) : Optional.empty();
        }

        @Override
        public ReviewAssignmentTurnOperationHandler.StartDisposition tryStart(
                String turnId, String operationId, Instant startedAt)
        {
            starts++;
            return start;
        }

        @Override
        public Optional<ReviewAssignmentTurnOperationHandler.McpOwner> findMcpOwner(
                String turnId, String operationId, Instant now)
        {
            return Optional.empty();
        }
    }

    private static final class FakeProvider
            implements AgentTurnProviderSession
    {
        private Request request;
        private int opens;
        private int starts;
        private long costUsdMilli = 1;

        @Override
        public Session open(Request request, Observer observer)
        {
            this.request = request;
            opens++;
            return new Session()
            {
                @Override
                public Result startAndAwait(WriterFence writerFence)
                {
                    starts++;
                    return new Result(
                            Completion.SUCCEEDED, "review-provider-session-1",
                            "review complete", 2, 3, costUsdMilli, 123L, null);
                }

                @Override
                public void cancel() {}

                @Override
                public void close() {}
            };
        }
    }
}
