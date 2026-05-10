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
 */
public record EmailThreadMeta(
        String id,
        String latestMessageId,
        String from,
        String subject,
        String snippet,
        Instant receivedAt,
        boolean unread,
        int messageCount)
{
}
