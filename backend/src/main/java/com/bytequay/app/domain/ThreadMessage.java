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
 * One row in {@code thread_messages} — a single event in a thread's
 * conversation log (user message, assistant text, tool call, tool
 * result, thinking, permission request, error). Mirrors a single
 * {@code StreamEvent} that came out of either a CLI's stream-json
 * stdout or a logic-loop's synthesizer.
 *
 * <p>{@code role} and {@code type} are kept as free-form strings
 * rather than enums because the set grows as we add stream-event
 * shapes and we'd rather not require a migration each time. The
 * small allowed set is documented in the SQL migration.
 *
 * <p>{@code contentJson}'s shape varies by {@code type} — a
 * {@code text} row carries {@code {"text": "..."}}, a
 * {@code tool_call} row carries {@code {"toolName": "...", "input": {...}}},
 * etc. The renderer dispatches on {@code type} to know how to read it.
 *
 * <p>{@code durationMs} / {@code tokensIn} / {@code tokensOut} /
 * {@code costUsdMilli} are populated for the rows where they apply
 * (tool calls, turn completions) and {@code null} elsewhere.
 *
 * <p>{@code taskId} is the focused Task at the time this row was
 * written. {@code null} marks a trunk planning row — talk that
 * happens at the Thread level with no task focused. The Task slice
 * for a thread is {@code WHERE task_id = :task}; the trunk is
 * {@code WHERE task_id IS NULL}.
 */
public record ThreadMessage(
        String id,
        String threadId,
        String taskId,
        long seq,
        String role,
        String type,
        String contentJson,
        Long durationMs,
        Long tokensIn,
        Long tokensOut,
        Long costUsdMilli,
        Instant ts)
{
}
