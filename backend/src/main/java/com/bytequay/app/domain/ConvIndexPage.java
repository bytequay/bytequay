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

import java.util.List;

/**
 * One window of the conversation index, paired with the messages
 * that share the same loaded range.
 *
 * <p>The frontend reads {@code totalUserMessages} and {@code entries}
 * for the index panel header ("Conversation · N of M") and rows;
 * {@code messages} carries the corresponding window of
 * {@code thread_messages} for the agent terminal. They're fetched in
 * one round-trip so the index and the rendered transcript can't
 * drift — a deliberate constraint from the conversation-index
 * design doc.
 *
 * <p>Two flavours:
 * <ul>
 *   <li><b>Initial window</b>: the page returns the tail of the
 *       conversation. {@code messages} is the rendered window;
 *       {@code totalUserMessages} reflects the whole thread;
 *       {@code nextCursor} is the earliest loaded canonical seq, or
 *       {@code null} when nothing older exists.</li>
 *   <li><b>Backfill window</b>: the page returns an older slice
 *       triggered by "↑ load earlier". {@code totalUserMessages}
 *       is still the thread-wide count (so the header math works);
 *       {@code nextCursor} is again the earliest canonical seq of this batch,
 *       or {@code null} when the start of the thread is reached.</li>
 * </ul>
 *
 * @param entries User prompts in the loaded window, oldest-first.
 * @param messages The {@code thread_messages} rows that match the same
 * window. Oldest-first; intended to be prepended to the agent
 * terminal's loaded set on backfill, or used as the initial render on
 * initial loads.
 * @param loadedFromSeq Earliest canonical seq in this window, or {@code null}
 * when the window is empty.
 * @param nextCursor Cursor to pass back for the next load-earlier request, or
 * {@code null} when no older rows remain. It equals {@code loadedFromSeq};
 * ordering is source-aware and must not be inferred numerically.
 */
public record ConvIndexPage(
        String threadId,
        long totalUserMessages,
        List<ConvIndexEntry> entries,
        List<ThreadMessage> messages,
        Long loadedFromSeq,
        Long nextCursor)
{
}
