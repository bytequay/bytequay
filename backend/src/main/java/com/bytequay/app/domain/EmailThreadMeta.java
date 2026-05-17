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
package com.bytequay.app.domain;

import java.time.Instant;

/**
 * Lightweight projection of a Gmail conversation thread — the data
 * the inbox list view shows for one thread card. Mirrors what
 * Gmail's web UI collapses into a single row: latest message's
 * sender / subject / snippet / time, plus a {@code messageCount}
 * for the {@code (N)} badge when the thread has more than one
 * message.
 *
 * <p>{@code unread} is true if <em>any</em> message in the thread
 * carries the UNREAD label — same semantics as Gmail's bold-or-not
 * row state.
 *
 * <p>{@code matchedTagId} and {@code view} are filled in by the
 * tag-classification pass in {@code EmailTagService}; the IMAP layer
 * leaves them as {@code null} / {@link View#INBOX}.
 */
public record EmailThreadMeta(
        String id,
        String latestMessageId,
        String from,
        String subject,
        String snippet,
        Instant receivedAt,
        boolean unread,
        int messageCount,
        String matchedTagId,
        View view)
{
    /** Classification of a thread after tag rules have been applied. */
    public enum View
    {
        /** No tag matched, or the matched tag is informational only — render in the main Inbox view. */
        INBOX,
        /** Matched a FOCUS tag — render in Inbox and clickable under the tag in the left nav. */
        FOCUS,
        /** Matched an ARCHIVE tag — hidden from Inbox; archived on Gmail and recorded in the archive log. */
        ARCHIVE,
        /** Matched an IGNORE tag — hidden from the app entirely; no Gmail-side change. */
        IGNORE
    }

    /** Convenience for the IMAP layer and tests where classification isn't relevant yet. */
    public EmailThreadMeta(
            String id,
            String latestMessageId,
            String from,
            String subject,
            String snippet,
            Instant receivedAt,
            boolean unread,
            int messageCount)
    {
        this(id, latestMessageId, from, subject, snippet, receivedAt, unread, messageCount, null, View.INBOX);
    }

    /** Returns a copy with the given classification set. */
    public EmailThreadMeta withClassification(String matchedTagId, View view)
    {
        return new EmailThreadMeta(id, latestMessageId, from, subject, snippet,
                receivedAt, unread, messageCount, matchedTagId, view);
    }
}
