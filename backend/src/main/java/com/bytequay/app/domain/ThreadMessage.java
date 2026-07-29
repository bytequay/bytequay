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
 * <p>{@code scope} is authoritative. {@code taskId} and {@code stageId}
 * are identifiers required or forbidden by that declared scope; their
 * nullability must never be used to decide what kind of row this is.
 */
public record ThreadMessage(
        String id,
        String threadId,
        String taskId,
        /** Positive physical seq for LEGACY rows; the ThreadTurn compatibility
         * projection uses a negative durable Trunk version. */
        long seq,
        String role,
        String type,
        String contentJson,
        Long durationMs,
        Long tokensIn,
        Long tokensOut,
        Long costUsdMilli,
        Instant ts,
        /** Owning stage when stage-scoped; null for task- or trunk-level rows. */
        String stageId,
        /** Explicit TRUNK | TASK | STAGE discriminator (see {@link ThreadScope}). */
        ThreadScope scope)
{
    public ThreadMessage
    {
        if (scope == null) {
            throw new IllegalArgumentException("message scope is null");
        }
        switch (scope) {
            case TRUNK -> {
                if (taskId != null || stageId != null) {
                    throw new IllegalArgumentException("TRUNK message forbids taskId and stageId");
                }
            }
            case TASK -> {
                if (taskId == null || stageId != null) {
                    throw new IllegalArgumentException("TASK message requires taskId and forbids stageId");
                }
            }
            case STAGE -> {
                if (taskId == null || stageId == null) {
                    throw new IllegalArgumentException("STAGE message requires taskId and stageId");
                }
            }
        }
    }
}
