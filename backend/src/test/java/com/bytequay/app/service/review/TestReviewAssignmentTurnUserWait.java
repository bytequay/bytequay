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

import com.bytequay.app.developmentflow.execution.DispatchTicket;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnOperationHandler;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession;
import com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.Admission;
import com.bytequay.app.service.review.ReviewAssignmentTurnRuntime.ContinuationCandidate;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestReviewAssignmentTurnUserWait
{
    private static final Instant NOW = Instant.parse("2026-07-29T00:00:00Z");
    private static final ObjectMapper JSON = new ObjectMapper();

    @Test
    void continuationPreservesTheSeatAndUsesOneStableSuccessorAcrossRestart()
            throws Exception
    {
        ReviewAssignmentTurnRuntime.Store store =
                mock(ReviewAssignmentTurnRuntime.Store.class);
        AtomicReference<Admission> admitted = new AtomicReference<>();
        when(store.userWaitSuccessor("QUESTION", "question-1"))
                .thenAnswer(invocation -> Optional.ofNullable(admitted.get())
                        .map(Admission::turnId));
        ContinuationCandidate candidate = new ContinuationCandidate(
                "review-turn-1", "review-operation-1", "round-1",
                "assignment-1", "head-1", ReviewAssignmentTurnRuntime.INVESTIGATE,
                "assignment-1", null, 2, 500,
                launch("review-turn-1", "review-operation-1"));
        when(store.userWaitCandidate(
                "review-turn-1", "review-operation-1",
                "QUESTION", "question-1")).thenReturn(Optional.of(candidate));
        when(store.continueUserWait(
                any(), anyString(), anyString(), any(), any()))
                .thenAnswer(invocation -> {
                    Admission successor = invocation.getArgument(3);
                    admitted.set(successor);
                    return successor.turnId();
                });

        String successor = runtime(store).continueUserWait(
                "review-turn-1", "review-operation-1",
                "QUESTION", "question-1", "Use the safer option");
        String restarted = runtime(store).continueUserWait(
                "review-turn-1", "review-operation-1",
                "QUESTION", "question-1", "Use the safer option");

        Admission continuation = admitted.get();
        assertThat(restarted).isEqualTo(successor);
        assertThat(continuation.assignmentId()).isEqualTo("assignment-1");
        assertThat(continuation.purpose())
                .isEqualTo(ReviewAssignmentTurnRuntime.INVESTIGATE);
        assertThat(continuation.subjectKey()).isEqualTo("assignment-1");
        assertThat(continuation.startCommit()).isEqualTo("head-1");
        assertThat(continuation.attempt()).isEqualTo(3);
        assertThat(continuation.costCapUsdMilli()).isEqualTo(500);
        AgentTurnOperationHandler.LaunchInput launch = JSON.readValue(
                continuation.launchInput(), AgentTurnOperationHandler.LaunchInput.class);
        assertThat(launch.prompt()).contains(
                "review the exact commit", "Use the safer option");
        assertThat(launch.toolEndpoint().ownerKind())
                .isEqualTo(DispatchTicket.OwnerKind.REVIEW_ASSIGNMENT_TURN);
        assertThat(launch.toolEndpoint().ownerId()).isEqualTo(successor);
        assertThat(launch.toolEndpoint().operationId())
                .isEqualTo(continuation.operationId());
    }

    @Test
    void staleReviewFenceCannotAdmitAnotherSeat()
    {
        ReviewAssignmentTurnRuntime.Store store =
                mock(ReviewAssignmentTurnRuntime.Store.class);
        when(store.userWaitSuccessor(anyString(), anyString()))
                .thenReturn(Optional.empty());
        when(store.userWaitCandidate(
                anyString(), anyString(), anyString(), anyString()))
                .thenReturn(Optional.empty());

        assertThat(runtime(store).continueUserWait(
                "stale-turn", "stale-operation", "PERMISSION",
                "permission-1", "Allow once")).isNull();
        verify(store, never()).continueUserWait(
                any(), anyString(), anyString(), any(), any());
    }

    private static ReviewAssignmentTurnRuntime runtime(
            ReviewAssignmentTurnRuntime.Store store)
    {
        return new ReviewAssignmentTurnRuntime(
                store, mock(ReviewProviderEndpoints.class),
                mock(ReviewAssignmentTurnRuntime.TicketControl.class), JSON,
                Clock.fixed(NOW, ZoneOffset.UTC), 53123, () -> "unused");
    }

    private static String launch(String turnId, String operationId)
            throws Exception
    {
        AgentTurnProviderSession.OwnerToolEndpoint endpoint =
                new AgentTurnProviderSession.OwnerToolEndpoint(
                        "bytequay",
                        "http://127.0.0.1:53123/api/v2/review-assignment-turns/"
                                + turnId + "/operations/" + operationId + "/mcp",
                        DispatchTicket.OwnerKind.REVIEW_ASSIGNMENT_TURN,
                        turnId, operationId,
                        AgentTurnProviderSession.ToolProfile
                                .REVIEW_ASSIGNMENT_READ_ONLY,
                        "mcp__bytequay__approval_prompt");
        return JSON.writeValueAsString(
                new AgentTurnOperationHandler.LaunchInput(
                        1, AgentTurnProviderSession.Transport.API,
                        "openai", "account-1", "gpt-5.6", "high",
                        "/tmp/task-1", "review role",
                        "review the exact commit", endpoint));
    }
}
