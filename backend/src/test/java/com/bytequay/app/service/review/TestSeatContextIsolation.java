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

import com.bytequay.app.domain.ReviewMessage;
import com.bytequay.app.domain.ReviewParticipant;
import com.bytequay.app.domain.ReviewParticipantKind;
import com.bytequay.app.domain.ReviewPass;
import com.bytequay.app.domain.ReviewPhase;
import com.bytequay.app.repository.ReviewStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * THE SAFETY PROPERTY: no reviewer's assembled context may contain
 * another reviewer's messages unless the Lead explicitly #ref-quoted
 * them in a message @-mentioning that reviewer. Per-reviewer context
 * isolation is load-bearing (it prevents anchoring and bounds each
 * seat's prefix), so this class runs on every build — if it fails,
 * the property is broken, full stop.
 */
class TestSeatContextIsolation
{
    private static final String PASS_ID = "pass-1";
    private static final int REVIEWERS = 5;
    private static final int MESSAGES_PER_REVIEWER = 10;

    private ReviewStore reviewStore;
    private SeatContextAssembler assembler;
    private List<ReviewParticipant> reviewers;
    private ReviewParticipant lead;
    private List<ReviewMessage> transcript;
    private String refQuotedBody;

    @BeforeEach
    void setUp()
    {
        reviewStore = mock(ReviewStore.class);
        assembler = new SeatContextAssembler(reviewStore);
        transcript = new ArrayList<>();

        lead = participant(ReviewParticipantKind.LEAD, "Lead");
        reviewers = new ArrayList<>();
        for (int i = 1; i <= REVIEWERS; i++) {
            reviewers.add(participant(ReviewParticipantKind.REVIEWER, "reviewer-" + i));
        }

        // Every reviewer has written 10 messages of its own.
        for (int r = 0; r < REVIEWERS; r++) {
            for (int n = 0; n < MESSAGES_PER_REVIEWER; n++) {
                transcript.add(message(
                        reviewers.get(r).id(),
                        "msg-from-reviewer-" + (r + 1) + "-" + n,
                        List.of(), List.of()));
            }
        }

        // The Lead has written 20 messages: half @reviewer-1, half
        // @reviewer-3, none @reviewer-2.
        for (int n = 0; n < 10; n++) {
            transcript.add(message(
                    lead.id(),
                    "lead-to-reviewer-1-" + n + " @reviewer-1",
                    List.of(reviewers.get(0).id()), List.of()));
            transcript.add(message(
                    lead.id(),
                    "lead-to-reviewer-3-" + n + " @reviewer-3",
                    List.of(reviewers.get(2).id()), List.of()));
        }

        when(reviewStore.listMessagesForPass(PASS_ID)).thenReturn(transcript);
        when(reviewStore.listParticipantsForPass(PASS_ID)).thenAnswer(inv -> {
            List<ReviewParticipant> all = new ArrayList<>(reviewers);
            all.add(lead);
            return all;
        });
        when(reviewStore.findMessageById(anyString())).thenAnswer(inv -> {
            String id = inv.getArgument(0);
            return transcript.stream().filter(m -> m.id().equals(id)).findFirst();
        });
    }

    @Test
    void seatContextNeverLeaksOtherReviewers()
    {
        String reviewer2 = reviewers.get(1).id();
        List<SeatContextAssembler.SeatMessage> ctx =
                assembler.assemble(pass(), reviewer2, /* excludeMessageId */ null);
        String joined = joinedText(ctx);

        // Zero messages from reviewers #1, #3, #4, #5.
        for (int r : new int[] {1, 3, 4, 5}) {
            if (r == 2) {
                continue;
            }
            assertFalse(joined.contains("msg-from-reviewer-" + r + "-"),
                    "reviewer-2's context leaked reviewer-" + r + "'s messages");
        }
        // Zero lead messages not addressed to reviewer-2.
        assertFalse(joined.contains("lead-to-reviewer-1-"),
                "reviewer-2's context leaked lead chatter addressed to reviewer-1");
        assertFalse(joined.contains("lead-to-reviewer-3-"),
                "reviewer-2's context leaked lead chatter addressed to reviewer-3");
        // Reviewer-2's own messages are present.
        assertTrue(joined.contains("msg-from-reviewer-2-0"));
        assertTrue(joined.contains("msg-from-reviewer-2-" + (MESSAGES_PER_REVIEWER - 1)));
    }

    @Test
    void leadCanQuoteAnotherReviewerForMeViaRef()
    {
        // The Lead quotes one of reviewer-4's messages FOR reviewer-2:
        // an @reviewer-2 message carrying a #ref to the quoted row.
        ReviewMessage quoted = transcript.stream()
                .filter(m -> m.body().startsWith("msg-from-reviewer-4-7"))
                .findFirst()
                .orElseThrow();
        refQuotedBody = quoted.body();
        transcript.add(message(
                lead.id(),
                "@reviewer-2 please respond to #msg — do you agree?",
                List.of(reviewers.get(1).id()),
                List.of("msg:" + quoted.id())));

        String reviewer2 = reviewers.get(1).id();
        List<SeatContextAssembler.SeatMessage> ctx =
                assembler.assemble(pass(), reviewer2, /* excludeMessageId */ null);
        String joined = joinedText(ctx);

        // The explicitly quoted body IS included…
        assertTrue(joined.contains(refQuotedBody),
                "the Lead's #ref-quoted body should reach reviewer-2");
        // …and that is the ONLY reviewer-4 content that leaks.
        long reviewer4Mentions = joined.lines()
                .filter(l -> l.contains("msg-from-reviewer-4-"))
                .count();
        assertTrue(reviewer4Mentions <= 2,
                "only the quoted reviewer-4 body may appear, got " + reviewer4Mentions);
        assertFalse(joined.contains("msg-from-reviewer-4-3"),
                "unquoted reviewer-4 messages must stay invisible");
    }

    @Test
    void mentionedLeadDirectivesAreAddressedToTheSeat()
    {
        transcript.add(message(
                lead.id(),
                "@reviewer-2 take an independent pass over the diff",
                List.of(reviewers.get(1).id()), List.of()));

        List<SeatContextAssembler.SeatMessage> ctx =
                assembler.assemble(pass(), reviewers.get(1).id(), null);
        String joined = joinedText(ctx);
        assertTrue(joined.contains("take an independent pass over the diff"));
    }

    @Test
    void excludedMessageIsLeftOutOfTheHistory()
    {
        ReviewMessage directive = message(
                lead.id(),
                "@reviewer-2 the directive that rides as the new turn",
                List.of(reviewers.get(1).id()), List.of());
        transcript.add(directive);

        List<SeatContextAssembler.SeatMessage> ctx =
                assembler.assemble(pass(), reviewers.get(1).id(), directive.id());
        assertFalse(joinedText(ctx).contains("the directive that rides as the new turn"),
                "the excluded (current-directive) message must not duplicate into history");
    }

    // ── Fixtures ─────────────────────────────────────────────────────

    private static ReviewPass pass()
    {
        return new ReviewPass(
                PASS_ID, "thread-1", "acme/widget", 42, "abc",
                ReviewPhase.CROSS_REVIEW, 0, 3, 500L, 0L, null,
                Instant.ofEpochMilli(0), null);
    }

    private static ReviewParticipant participant(ReviewParticipantKind kind, String label)
    {
        return new ReviewParticipant(
                UUID.randomUUID().toString(), PASS_ID, kind,
                /* credentialId */ null, label, /* model */ null, /* color */ null,
                Instant.ofEpochMilli(0));
    }

    private static ReviewMessage message(
            String participantId, String body, List<String> mentions, List<String> refs)
    {
        return new ReviewMessage(
                UUID.randomUUID().toString(), PASS_ID, participantId,
                ReviewPhase.CROSS_REVIEW, 0, body, mentions, refs,
                /* costUsdMilli */ 0L, Instant.ofEpochMilli(transcriptTs++));
    }

    private static long transcriptTs;

    private static String joinedText(List<SeatContextAssembler.SeatMessage> ctx)
    {
        StringBuilder sb = new StringBuilder();
        for (SeatContextAssembler.SeatMessage m : ctx) {
            sb.append(m.text()).append('\n');
        }
        return sb.toString();
    }
}
