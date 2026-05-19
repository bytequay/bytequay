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
 * {@code task_messages} for the agent terminal. They're fetched in
 * one round-trip so the index and the rendered transcript can't
 * drift — a deliberate constraint from the conversation-index
 * design doc.
 *
 * <p>Two flavours:
 * <ul>
 *   <li><b>Initial window</b>: the page returns the tail of the
 *       conversation. {@code messages} is the rendered window;
 *       {@code totalUserMessages} reflects the whole task;
 *       {@code nextCursor} is the smallest loaded seq, or
 *       {@code null} when nothing older exists.</li>
 *   <li><b>Backfill window</b>: the page returns an older slice
 *       triggered by "↑ load earlier". {@code totalUserMessages}
 *       is still the task-wide count (so the header math works);
 *       {@code nextCursor} is again the smallest seq of this batch,
 *       or {@code null} when the start of the task is reached.</li>
 * </ul>
 */
public record ConvIndexPage(
        String taskId,
        long totalUserMessages,
        /** User prompts in the loaded window, oldest-first. */
        List<ConvIndexEntry> entries,
        /** The {@code task_messages} rows that match the same window.
         *  Oldest-first; intended to be prepended to the agent
         *  terminal's loaded set on backfill, or used as the initial
         *  render on initial loads. */
        List<TaskMessage> messages,
        /** Smallest seq in this window, or {@code null} when the
         *  window is empty (a brand-new task with no messages yet). */
        Long loadedFromSeq,
        /** Smallest seq strictly less than {@code loadedFromSeq} for
         *  the next "↑ load earlier" cursor, or {@code null} when no
         *  older rows remain. The frontend uses this to gate the
         *  load-more affordance. */
        Long nextCursor)
{
}
