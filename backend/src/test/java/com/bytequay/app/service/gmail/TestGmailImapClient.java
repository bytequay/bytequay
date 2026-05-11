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
package com.bytequay.app.service.gmail;

import com.bytequay.app.domain.EmailThreadMeta;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives {@link GmailImapClient#groupIntoThreads} without a live IMAP
 * server. Covers the scenarios that change the resulting metas:
 * single-message vs multi-message threads, mixed unread state, and
 * the newest-first ordering across threads.
 */
class TestGmailImapClient
{
    private static final Instant T0 = Instant.parse("2026-05-11T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-05-11T11:00:00Z");
    private static final Instant T2 = Instant.parse("2026-05-11T12:00:00Z");

    @Test
    void singleMessagePerThreadMapsOneToOne()
    {
        List<GmailImapClient.MessageRow> rows = List.of(
                new GmailImapClient.MessageRow(100L, 1L, "alice@example.com", "Hi", T0, false),
                new GmailImapClient.MessageRow(200L, 2L, "bob@example.com", "Hey", T1, true));
        List<EmailThreadMeta> threads = GmailImapClient.groupIntoThreads(rows, 50);
        assertEquals(2, threads.size());
        // Newest-first ordering — bob's t1 thread before alice's t0.
        assertEquals("200", threads.get(0).id());
        assertEquals("100", threads.get(1).id());
        assertEquals(1, threads.get(0).messageCount());
    }

    @Test
    void multiMessageThreadCollapsesToOneRowAtNewestMessage()
    {
        List<GmailImapClient.MessageRow> rows = List.of(
                new GmailImapClient.MessageRow(500L, 11L, "alice@example.com", "Re: deploy", T0, false),
                new GmailImapClient.MessageRow(500L, 12L, "bob@example.com", "Re: deploy", T2, false),
                new GmailImapClient.MessageRow(500L, 13L, "alice@example.com", "Re: deploy", T1, false));
        List<EmailThreadMeta> threads = GmailImapClient.groupIntoThreads(rows, 50);
        assertEquals(1, threads.size());
        EmailThreadMeta thread = threads.get(0);
        assertEquals(3, thread.messageCount());
        // Head row is the newest by receivedAt — bob at T2.
        assertEquals("12", thread.latestMessageId());
        assertEquals("bob@example.com", thread.from());
        assertEquals(T2, thread.receivedAt());
    }

    @Test
    void anyUnreadInGroupMakesTheThreadUnread()
    {
        List<GmailImapClient.MessageRow> rows = List.of(
                new GmailImapClient.MessageRow(7L, 21L, "x@y", "subj", T0, false),
                new GmailImapClient.MessageRow(7L, 22L, "x@y", "subj", T1, true),
                new GmailImapClient.MessageRow(7L, 23L, "x@y", "subj", T2, false));
        List<EmailThreadMeta> threads = GmailImapClient.groupIntoThreads(rows, 50);
        assertEquals(1, threads.size());
        assertTrue(threads.get(0).unread(),
                "thread with one unread message must report unread=true");
    }

    @Test
    void allReadGroupYieldsThreadRead()
    {
        List<GmailImapClient.MessageRow> rows = List.of(
                new GmailImapClient.MessageRow(8L, 31L, "x@y", "subj", T0, false),
                new GmailImapClient.MessageRow(8L, 32L, "x@y", "subj", T1, false));
        List<EmailThreadMeta> threads = GmailImapClient.groupIntoThreads(rows, 50);
        assertEquals(1, threads.size());
        assertEquals(false, threads.get(0).unread());
    }

    @Test
    void respectsLimitAfterGrouping()
    {
        // Five distinct threads at increasing timestamps. Limit 3 →
        // newest 3 returned in newest-first order.
        List<GmailImapClient.MessageRow> rows = List.of(
                new GmailImapClient.MessageRow(1L, 41L, "a", "s", T0, false),
                new GmailImapClient.MessageRow(2L, 42L, "b", "s", T1, false),
                new GmailImapClient.MessageRow(3L, 43L, "c", "s", T2, false),
                new GmailImapClient.MessageRow(4L, 44L, "d", "s", T2.plusSeconds(60), false),
                new GmailImapClient.MessageRow(5L, 45L, "e", "s", T2.plusSeconds(120), false));
        List<EmailThreadMeta> threads = GmailImapClient.groupIntoThreads(rows, 3);
        assertEquals(3, threads.size());
        assertEquals(List.of("5", "4", "3"), threads.stream().map(EmailThreadMeta::id).toList());
    }

    @Test
    void unsignedStringIfThreadIdIsNegative()
    {
        // X-GM-THRID is a 64-bit integer; values past 2^63 wrap to
        // negative when read into a signed Java long. Long.toUnsignedString
        // round-trips them as positive decimals matching what Gmail's
        // API path emits.
        List<GmailImapClient.MessageRow> rows = List.of(
                new GmailImapClient.MessageRow(-1L, 1L, "a", "s", T0, false));
        List<EmailThreadMeta> threads = GmailImapClient.groupIntoThreads(rows, 10);
        assertEquals("18446744073709551615", threads.get(0).id());
    }
}
