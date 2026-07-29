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

import com.bytequay.app.domain.AgendaPhase;
import com.bytequay.app.domain.AgendaPhaseStatus;
import com.bytequay.app.domain.ReviewFinding;
import com.bytequay.app.domain.ReviewFindingSeverity;
import com.bytequay.app.domain.ReviewFindingStatus;
import com.bytequay.app.domain.ReviewMessage;
import com.bytequay.app.domain.ReviewPass;
import com.bytequay.app.domain.ReviewPhase;
import com.bytequay.app.repository.ReviewStore;
import com.bytequay.app.service.agents.ToolCall;
import com.bytequay.app.service.agents.ToolExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The Lead's orchestration verbs: agenda lifecycle, ordered reviewer
 * dispatch, and consensus recording.
 */
class TestLeadToolset
{
    private static final String PASS_ID = "pass-1";
    private static final String LEAD_ID = "lead-1";
    private static final String SEAT_1 = "seat-1";
    private static final String SEAT_2 = "seat-2";

    private final ObjectMapper mapper = new ObjectMapper();

    private ReviewStore reviewStore;
    private ReviewerSeat reviewerSeat;
    private ReviewCallContext reviewAdmission;
    private LeadToolset toolset;
    private LeadToolset.Session session;
    private ReviewPass pass;

    @BeforeEach
    void setUp()
    {
        reviewStore = mock(ReviewStore.class);
        reviewerSeat = mock(ReviewerSeat.class);
        reviewAdmission = mock(ReviewCallContext.class);
        toolset = new LeadToolset(
                reviewStore, mock(SeatToolset.class), reviewerSeat, reviewAdmission, mapper);

        pass = new ReviewPass(
                PASS_ID, "thread-1", "acme/widget", 42, "abc",
                ReviewPhase.CROSS_REVIEW, 0, 3, 500L, 0L, null,
                Instant.ofEpochMilli(0), null);
        when(reviewStore.findPassById(PASS_ID)).thenReturn(Optional.of(pass));

        PanelSeatConfig roster = new PanelSeatConfig(List.of(
                new PanelSeatConfig.Seat(LEAD_ID, "claude", null, "Lead", true),
                new PanelSeatConfig.Seat(SEAT_1, "openai", null, "GPT", false),
                new PanelSeatConfig.Seat(SEAT_2, "deepseek", null, "DeepSeek", false)));
        session = toolset.sessionFor(PASS_ID, roster, LEAD_ID);
    }

    @Test
    void setAgendaWritesOnceAndRejectsASecondCall()
            throws Exception
    {
        ToolExecutor executor = session.roundExecutor(ReviewPhase.KICKOFF, 0);
        ToolExecutor.ToolCallResult first = executor.execute(call("set_agenda",
                "{\"phases\":[{\"id\":\"p1\",\"title\":\"Independent\"},"
                        + "{\"id\":\"p2\",\"title\":\"Cross-review\"}]}"));
        assertTrue(!first.isError(), first.text());

        ArgumentCaptor<ReviewPass> captor = ArgumentCaptor.forClass(ReviewPass.class);
        verify(reviewStore).savePass(captor.capture());
        List<AgendaPhase> agenda = AgendaJsonCodec.parse(captor.getValue().agendaJson());
        assertEquals(2, agenda.size());
        assertEquals(new AgendaPhase("p1", "Independent", AgendaPhaseStatus.OPEN), agenda.get(0));

        // Second call sees the persisted agenda → 409.
        when(reviewStore.findPassById(PASS_ID))
                .thenReturn(Optional.of(captor.getValue()));
        ToolExecutor.ToolCallResult second = executor.execute(call("set_agenda",
                "{\"phases\":[{\"id\":\"px\",\"title\":\"Replacement\"}]}"));
        assertTrue(second.isError());
        var parsed = mapper.readTree(second.text());
        assertEquals("agenda_already_set", parsed.path("error").asText());
        assertEquals(409, parsed.path("status").asInt());
    }

    @Test
    void dispatchWritesTheMentionMessageRunsTheSeatAndReturnsItsReplyInline()
    {
        ReviewMessage reply = seatReply(SEAT_1, "I checked it; the bound is fine.");
        when(reviewerSeat.runDispatchedTurn(
                any(), any(), eq(SEAT_1), anyString(), any(), anyInt(), anyString()))
                .thenReturn(reply);

        ToolExecutor executor = session.roundExecutor(ReviewPhase.CROSS_REVIEW, 0);
        ToolExecutor.ToolCallResult result = executor.execute(call("dispatch_to_reviewer",
                "{\"participant_id\":\"" + SEAT_1 + "\",\"body\":\"@GPT check the loop bound\"}"));

        assertTrue(!result.isError(), result.text());
        assertEquals("I checked it; the bound is fine.", result.text());

        // The Lead's @-mention message persisted before the seat ran.
        ArgumentCaptor<ReviewMessage> captor = ArgumentCaptor.forClass(ReviewMessage.class);
        verify(reviewStore).saveMessage(captor.capture());
        ReviewMessage dispatched = captor.getValue();
        assertEquals(LEAD_ID, dispatched.participantId());
        assertEquals(List.of(SEAT_1), dispatched.mentions());
        assertEquals("@GPT check the loop bound", dispatched.body());

        // The seat got the persisted dispatch id to exclude from history.
        verify(reviewerSeat).runDispatchedTurn(
                any(), any(), eq(SEAT_1), eq("@GPT check the loop bound"),
                eq(ReviewPhase.CROSS_REVIEW), eq(0), eq(dispatched.id()));
    }

    @Test
    void dispatchWithoutTheMentionIsRejected()
            throws Exception
    {
        ToolExecutor executor = session.roundExecutor(ReviewPhase.CROSS_REVIEW, 0);
        ToolExecutor.ToolCallResult result = executor.execute(call("dispatch_to_reviewer",
                "{\"participant_id\":\"" + SEAT_1 + "\",\"body\":\"check the loop bound\"}"));
        assertTrue(result.isError());
        assertEquals("missing_reviewer_mention",
                mapper.readTree(result.text()).path("error").asText());
        verify(reviewerSeat, times(0)).runDispatchedTurn(
                any(), any(), anyString(), anyString(), any(), anyInt(), anyString());
    }

    @Test
    @SuppressWarnings("unchecked")
    void batchDispatchRunsThroughTheSerialCallContextInOrder()
            throws Exception
    {
        when(reviewerSeat.runDispatchedTurnAlreadyAdmitted(
                any(), any(), eq(SEAT_1), anyString(), any(), anyInt(), anyString(), eq(true)))
                .thenReturn(seatReply(SEAT_1, "GPT's take"));
        when(reviewerSeat.runDispatchedTurnAlreadyAdmitted(
                any(), any(), eq(SEAT_2), anyString(), any(), anyInt(), anyString(), eq(true)))
                .thenReturn(seatReply(SEAT_2, "DeepSeek's take"));
        // Shared admission runs the batch inline here.
        when(reviewAdmission.invokeAll(any())).thenAnswer(inv -> {
            List<ReviewCallContext.Work<ToolExecutor.ToolCallResult>> work =
                    inv.getArgument(0);
            return work.stream().map(item -> {
                try {
                    return item.work().call();
                }
                catch (Exception e) {
                    throw new IllegalStateException(e);
                }
            }).toList();
        });

        LeadToolset.RoundExecutor executor = session.roundExecutor(ReviewPhase.CROSS_REVIEW, 0);
        ToolCall first = call("dispatch_to_reviewer",
                "{\"participant_id\":\"" + SEAT_1 + "\",\"body\":\"@GPT compare notes\"}");
        ToolCall second = call("dispatch_to_reviewer",
                "{\"participant_id\":\"" + SEAT_2 + "\",\"body\":\"@DeepSeek compare notes\"}");
        executor.prefetch(List.of(first, second));

        verify(reviewAdmission).invokeAll(any());
        // Results come back per call, in dispatch order.
        assertEquals("GPT's take", executor.execute(first).text());
        assertEquals("DeepSeek's take", executor.execute(second).text());
    }

    @Test
    void markConsensusValidatesTheFindingBelongsToThePass()
            throws Exception
    {
        ReviewFinding foreign = new ReviewFinding(
                "finding-9", "other-pass", null, null,
                ReviewFindingSeverity.MAJOR, ReviewFindingStatus.REPORTED,
                "[GPT] something", null, null, Instant.ofEpochMilli(0));
        when(reviewStore.findFindingById("finding-9")).thenReturn(Optional.of(foreign));

        ToolExecutor executor = session.roundExecutor(ReviewPhase.CONSENSUS, 0);
        ToolExecutor.ToolCallResult result = executor.execute(call("mark_consensus",
                "{\"finding_id\":\"finding-9\",\"status\":\"agreed\"}"));
        assertTrue(result.isError());
        assertEquals("unknown_finding", mapper.readTree(result.text()).path("error").asText());

        // A finding of this pass flips.
        ReviewFinding mine = new ReviewFinding(
                "finding-1", PASS_ID, "src/A.java", 3,
                ReviewFindingSeverity.MAJOR, ReviewFindingStatus.REPORTED,
                "[GPT] off-by-one", null, null, Instant.ofEpochMilli(0));
        when(reviewStore.findFindingById("finding-1")).thenReturn(Optional.of(mine));
        ToolExecutor.ToolCallResult ok = executor.execute(call("mark_consensus",
                "{\"finding_id\":\"finding-1\",\"status\":\"agreed\"}"));
        assertTrue(!ok.isError(), ok.text());
        ArgumentCaptor<ReviewFinding> captor = ArgumentCaptor.forClass(ReviewFinding.class);
        verify(reviewStore).saveFinding(captor.capture());
        assertEquals(ReviewFindingStatus.AGREED, captor.getValue().status());
    }

    @Test
    void debateLedgerCapsPerFindingDispatchSpend()
            throws Exception
    {
        ReviewMessage costly = new ReviewMessage(
                "m-1", PASS_ID, SEAT_1, ReviewPhase.DEBATE, 1,
                "expensive take", List.of(), List.of(), /* cost */ 100L,
                Instant.ofEpochMilli(0));
        when(reviewerSeat.runDispatchedTurn(
                any(), any(), eq(SEAT_1), anyString(), any(), anyInt(), anyString()))
                .thenReturn(costly);

        ToolExecutor executor = session.roundExecutor(ReviewPhase.DEBATE, 1);
        String args = "{\"participant_id\":\"" + SEAT_1 + "\",\"body\":\"@GPT defend it\","
                + "\"finding_id\":\"finding-1\"}";
        assertTrue(!executor.execute(call("dispatch_to_reviewer", args)).isError());

        // The first dispatch spent the whole per-finding ceiling; the
        // next one is refused with the structured error, not run.
        ToolExecutor.ToolCallResult second = executor.execute(call("dispatch_to_reviewer", args));
        assertTrue(second.isError());
        assertEquals("finding_debate_budget_exhausted",
                mapper.readTree(second.text()).path("error").asText());
    }

    private ReviewMessage seatReply(String seatId, String body)
    {
        return new ReviewMessage(
                messageIdFor(seatId), PASS_ID, seatId, ReviewPhase.CROSS_REVIEW, 0,
                body, List.of(), List.of(), 5L, Instant.ofEpochMilli(0));
    }

    private static String messageIdFor(String seed)
    {
        return "msg-" + seed;
    }

    private ToolCall call(String name, String argsJson)
    {
        try {
            return new ToolCall("call-" + System.nanoTime(), name, argsJson,
                    mapper.readTree(argsJson));
        }
        catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
