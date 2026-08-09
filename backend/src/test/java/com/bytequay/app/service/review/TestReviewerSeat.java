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

import com.bytequay.app.domain.CredentialType;
import com.bytequay.app.domain.ReviewMessage;
import com.bytequay.app.domain.ReviewParticipant;
import com.bytequay.app.domain.ReviewParticipantKind;
import com.bytequay.app.domain.ReviewPass;
import com.bytequay.app.domain.ReviewPhase;
import com.bytequay.app.repository.AppSettingsStore;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.ReviewStore;
import com.bytequay.app.service.CredentialService;
import com.bytequay.app.service.agents.TurnResult;
import com.bytequay.app.service.agents.TurnRunner;
import com.bytequay.app.service.agents.TurnSpec;
import com.bytequay.app.service.credentials.PatResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * ReviewerSeat composition: budget gate up front, persisted seat
 * message + metered spend after the turn.
 */
class TestReviewerSeat
{
    private static final String PASS_ID = "pass-1";
    private static final String SEAT_ID = "seat-1";

    private final ObjectMapper mapper = new ObjectMapper();

    private ReviewStore reviewStore;
    private TurnRunner runner;
    private ReviewerSeat seat;
    private ReviewParticipant participant;
    private ReviewPass pass;

    @BeforeEach
    void setUp()
    {
        reviewStore = mock(ReviewStore.class);
        runner = mock(TurnRunner.class);

        pass = new ReviewPass(
                PASS_ID, "thread-1", "acme/widget", 42, "abc",
                ReviewPhase.INDEPENDENT, 0, 3, 500L, 0L, null,
                Instant.ofEpochMilli(0), null);
        participant = new ReviewParticipant(
                SEAT_ID, PASS_ID, ReviewParticipantKind.REVIEWER, "claude",
                "Claude", null, null, Instant.ofEpochMilli(0),
                /* cap */ 100L, /* spent */ 0L);

        when(reviewStore.findPassById(PASS_ID)).thenReturn(Optional.of(pass));
        when(reviewStore.findParticipantById(SEAT_ID)).thenReturn(Optional.of(participant));
        when(reviewStore.listMessagesForPass(PASS_ID)).thenReturn(List.of());
        when(reviewStore.listParticipantsForPass(PASS_ID)).thenReturn(List.of(participant));

        CredentialService credentials = mock(CredentialService.class);
        when(credentials.getSecret(CredentialType.AI, "anthropic"))
                .thenReturn(Optional.of("test-key"));
        AppSettingsStore appSettings = mock(AppSettingsStore.class);
        when(appSettings.get(any())).thenReturn(Optional.empty());

        ReviewDiffCache diffCache = mock(ReviewDiffCache.class);
        when(diffCache.diffFor(any())).thenReturn("diff --git a/x b/x\n+hi\n");
        ReviewCallContext admission = mock(ReviewCallContext.class);
        when(admission.invoke(any(), any(), any(), any())).thenAnswer(invocation ->
                invocation.<Callable<TurnResult>>getArgument(3).call());

        SeatToolset toolset = new SeatToolset(
                reviewStore, diffCache, mock(PullRequestRepository.class),
                mock(PatResolver.class), mapper);
        seat = new ReviewerSeat(
                runner,
                new SeatContextAssembler(reviewStore),
                toolset,
                new ReviewProviderEndpoints(credentials, appSettings),
                new ReviewBudgetMeter(reviewStore),
                diffCache,
                reviewStore,
                mapper,
                new CliReviewRunner(mapper),
                admission);
    }

    @Test
    void persistsTheSeatReplyAndChargesBothBudgets()
    {
        when(runner.runTurn(any(), any(), any())).thenReturn(new TurnResult(
                "Checked the loop bound; looks correct.", 100, 50, 7L, 1,
                TurnResult.End.COMPLETED));

        ReviewMessage out = seat.runDispatchedTurn(
                pass, roster(), SEAT_ID, "take an independent pass",
                ReviewPhase.INDEPENDENT, 0, null);

        assertEquals(SEAT_ID, out.participantId());
        assertEquals("Checked the loop bound; looks correct.", out.body());
        assertEquals(7L, out.costUsdMilli());
        verify(reviewStore).saveMessage(out);

        // Seat slice advanced…
        ArgumentCaptor<ReviewParticipant> seatCaptor =
                ArgumentCaptor.forClass(ReviewParticipant.class);
        verify(reviewStore).saveParticipant(seatCaptor.capture());
        assertEquals(7L, seatCaptor.getValue().budgetMilliUsdSpent());
        // …and the pass total too.
        ArgumentCaptor<ReviewPass> passCaptor = ArgumentCaptor.forClass(ReviewPass.class);
        verify(reviewStore).savePass(passCaptor.capture());
        assertEquals(7L, passCaptor.getValue().costUsdMilli());

        ArgumentCaptor<TurnSpec> spec = ArgumentCaptor.forClass(TurnSpec.class);
        verify(runner).runTurn(spec.capture(), any(), any());
        assertTrue(spec.getValue().system().contains("Caveman is mandatory"));
        assertTrue(spec.getValue().system().contains("report_finding"));
    }

    @Test
    void exhaustedSeatSliceRejectsTheDispatchBeforeAnyModelCall()
    {
        when(reviewStore.findParticipantById(SEAT_ID)).thenReturn(Optional.of(
                participant.withBudgetSpent(100L)));

        assertThrows(ReviewerSeat.SeatBudgetExhaustedException.class, () ->
                seat.runDispatchedTurn(pass, roster(), SEAT_ID, "go",
                        ReviewPhase.DEBATE, 1, null));
        verify(runner, never()).runTurn(any(), any(), any());
        verify(reviewStore, never()).saveMessage(any());
    }

    @Test
    void abortedTurnPersistsTheBudgetStopNote()
    {
        when(runner.runTurn(any(), any(), any())).thenReturn(new TurnResult(
                "", 200, 80, 60L, 2, TurnResult.End.ABORTED));

        ReviewMessage out = seat.runDispatchedTurn(
                pass, roster(), SEAT_ID, "dig deeper",
                ReviewPhase.CROSS_REVIEW, 0, null);

        assertTrue(out.body().contains("budget slice"),
                "aborted turns should say why they stopped, got: " + out.body());
        assertEquals(60L, out.costUsdMilli());
    }

    private PanelSeatConfig roster()
    {
        return new PanelSeatConfig(List.of(
                new PanelSeatConfig.Seat(SEAT_ID, "claude", null, "Claude", false)));
    }
}
