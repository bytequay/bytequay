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
import com.bytequay.app.domain.ReviewPass;
import com.bytequay.app.domain.ReviewPhase;
import com.bytequay.app.repository.AppSettingsStore;
import com.bytequay.app.repository.ReviewStore;
import com.bytequay.app.service.CredentialService;
import com.bytequay.app.service.agents.TurnResult;
import com.bytequay.app.service.agents.TurnRunner;
import com.bytequay.app.service.agents.TurnSpec;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LeadOrchestrator composition: one round persists the Lead's closing
 * text on the transcript and charges the pass budget.
 */
class TestLeadOrchestrator
{
    private static final String PASS_ID = "pass-1";
    private static final String LEAD_ID = "lead-1";

    private final ObjectMapper mapper = new ObjectMapper();

    private ReviewStore reviewStore;
    private TurnRunner runner;
    private LeadOrchestrator orchestrator;
    private LeadToolset toolset;
    private ReviewPass pass;
    private PanelSeatConfig roster;

    @BeforeEach
    void setUp()
    {
        reviewStore = mock(ReviewStore.class);
        runner = mock(TurnRunner.class);

        pass = new ReviewPass(
                PASS_ID, "thread-1", "acme/widget", 42, "abc",
                ReviewPhase.CONSENSUS, 0, 3, 500L, 0L, null,
                Instant.ofEpochMilli(0), null);
        when(reviewStore.findPassById(PASS_ID)).thenReturn(Optional.of(pass));
        when(reviewStore.listMessagesForPass(PASS_ID)).thenReturn(List.of());
        when(reviewStore.listParticipantsForPass(PASS_ID)).thenReturn(List.of());
        when(reviewStore.listFindingsForPass(PASS_ID)).thenReturn(List.of());

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

        toolset = new LeadToolset(
                reviewStore, mock(SeatToolset.class), mock(ReviewerSeat.class),
                admission, mapper);
        orchestrator = new LeadOrchestrator(
                runner,
                new LeadContextAssembler(reviewStore, diffCache),
                toolset,
                new ReviewProviderEndpoints(credentials, appSettings),
                new ReviewBudgetMeter(reviewStore),
                reviewStore,
                admission,
                mapper);
        roster = new PanelSeatConfig(List.of(
                new PanelSeatConfig.Seat(LEAD_ID, "claude", null, "Lead", true)));
    }

    @Test
    void persistsTheLeadClosingTextAndChargesThePass()
    {
        when(runner.runTurn(any(), any(), any())).thenReturn(new TurnResult(
                "Asked both reviewers; consensus recorded.", 300, 60, 9L, 2,
                TurnResult.End.COMPLETED));

        orchestrator.runRound(pass, toolset.sessionFor(PASS_ID, roster, LEAD_ID),
                roster, ReviewPhase.CONSENSUS, 0, "Work the consensus phase.");

        ArgumentCaptor<ReviewMessage> message = ArgumentCaptor.forClass(ReviewMessage.class);
        verify(reviewStore).saveMessage(message.capture());
        assertEquals(LEAD_ID, message.getValue().participantId());
        assertEquals("Asked both reviewers; consensus recorded.", message.getValue().body());
        assertEquals(9L, message.getValue().costUsdMilli());

        ArgumentCaptor<ReviewPass> saved = ArgumentCaptor.forClass(ReviewPass.class);
        verify(reviewStore).savePass(saved.capture());
        assertEquals(9L, saved.getValue().costUsdMilli());

        ArgumentCaptor<TurnSpec> spec = ArgumentCaptor.forClass(TurnSpec.class);
        verify(runner).runTurn(spec.capture(), any(), any());
        assertTrue(spec.getValue().system().contains("Caveman is mandatory"));
        assertTrue(spec.getValue().system().contains("dispatch_to_reviewer"));
    }
}
