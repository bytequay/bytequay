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
 * Single live event in an agent session — the unit the frontend
 * renders one row at a time. The {@link com.bytequay.app.service.tasks.AgentSession}
 * implementation for each {@link TaskKind} synthesizes the same shapes:
 *
 * <ul>
 *   <li>{@code CLI_AGENT} parses Claude Code's
 *       {@code --output-format stream-json} stdout into these.</li>
 *   <li>{@code LOGIC_LOOP} synthesizes them as the in-JVM loop runs
 *       its own tools.</li>
 * </ul>
 *
 * <p>Each variant is also persisted as a {@link TaskMessage} row, so
 * a refresh replays the conversation by reading rows in {@code seq}
 * order. Live subscribers and the persistence layer share the same
 * stream — there is no separate "history-only" shape.
 *
 * <p>Sealed so a {@code switch} on the receiver is exhaustive and
 * any new variant forces every renderer to opt in.
 */
public sealed interface StreamEvent
        permits StreamEvent.SessionStarted,
                StreamEvent.UserMessage,
                StreamEvent.AssistantText,
                StreamEvent.ThinkingStarted,
                StreamEvent.ThinkingDone,
                StreamEvent.ToolCallStarted,
                StreamEvent.ToolCallDone,
                StreamEvent.PermissionRequested,
                StreamEvent.PermissionDecided,
                StreamEvent.PermissionAutoAllowed,
                StreamEvent.TurnDone,
                StreamEvent.ErrorOccurred,
                StreamEvent.SessionEnded
{
    /** Wall-clock at the moment the event was emitted by the source. */
    Instant timestamp();

    /** First event of every session — anchors the conversation. */
    record SessionStarted(
            Instant timestamp,
            String sessionId,
            String cwd,
            String model)
            implements StreamEvent {}

    /** User input dispatched into the loop. Echoed back so the
     *  renderer can show what was sent without a round-trip. */
    record UserMessage(
            Instant timestamp,
            String text)
            implements StreamEvent {}

    /** Assistant prose. Streamed in chunks for {@code CLI_AGENT};
     *  collapsed into one event per response for {@code LOGIC_LOOP}
     *  unless we wire up partials there too. */
    record AssistantText(
            Instant timestamp,
            String text)
            implements StreamEvent {}

    /** Begins a thinking block. {@code summary} is the model's own
     *  short label when available (Claude provides one); blank for
     *  providers that don't surface it. */
    record ThinkingStarted(
            Instant timestamp,
            String summary)
            implements StreamEvent {}

    /** Closes a thinking block. The renderer collapses the block
     *  on this event. */
    record ThinkingDone(
            Instant timestamp)
            implements StreamEvent {}

    /** Tool invocation began. {@code callId} pairs this with the
     *  matching {@link ToolCallDone} and any
     *  {@link PermissionRequested} / {@link PermissionDecided}. */
    record ToolCallStarted(
            Instant timestamp,
            String callId,
            String toolName,
            String inputJson)
            implements StreamEvent {}

    /** Tool finished. {@code outputJson} is the raw result; the
     *  renderer truncates / pretty-prints. {@code isError} flags
     *  tool-level failures (file not found, command exit nonzero,
     *  etc.) — it does NOT mean the session crashed. */
    record ToolCallDone(
            Instant timestamp,
            String callId,
            String outputJson,
            boolean isError)
            implements StreamEvent {}

    /** Loop is blocked waiting for the user to allow / deny a tool
     *  call. The frontend pops a banner and calls
     *  {@code AgentSession.decide(callId, ...)}. */
    record PermissionRequested(
            Instant timestamp,
            String callId,
            String toolName,
            String summary)
            implements StreamEvent {}

    /** User answered a {@link PermissionRequested}. Emitted by the
     *  session itself (not the source loop) so that subscribers see
     *  a complete record without polling. */
    record PermissionDecided(
            Instant timestamp,
            String callId,
            PermissionDecision decision)
            implements StreamEvent {}

    /** A tool call was automatically allowed by a previously-granted
     *  pre-approval budget ("Allow next 5" / "Always for this tool"),
     *  so no {@link PermissionRequested} was shown. Emitted by the
     *  MCP gate after draining a slot, so the conversation pane can
     *  surface "auto-approved · N left for &lt;tool&gt;" next to the
     *  tool call. {@code remaining} is the budget left after this
     *  consumption: {@code -1} for an ALWAYS grant, {@code 0} when
     *  this was the last slot, otherwise the positive remainder. */
    record PermissionAutoAllowed(
            Instant timestamp,
            String callId,
            String toolName,
            int remaining)
            implements StreamEvent {}

    /** End of one assistant turn — model has finished and is waiting
     *  for the next user input. Carries the per-turn cost / token
     *  accounting we accumulate into {@link AgentMetrics}. */
    record TurnDone(
            Instant timestamp,
            long durationMs,
            long costUsdMilli,
            long tokensIn,
            long tokensOut)
            implements StreamEvent {}

    /** Recoverable failure (rate limit, transient API error, malformed
     *  stream chunk). The session may continue; the frontend shows
     *  it in the conversation log. Named {@code ErrorOccurred} to
     *  avoid collision with {@code java.lang.Error}. */
    record ErrorOccurred(
            Instant timestamp,
            String message,
            boolean recoverable)
            implements StreamEvent {}

    /** Terminal event — no further events will follow. {@code exitCode}
     *  comes from the CLI process for {@code CLI_AGENT}; synthesized
     *  (0 = normal, nonzero = abort) for {@code LOGIC_LOOP}.
     *  {@code errorMessage} is null on a clean exit. */
    record SessionEnded(
            Instant timestamp,
            int exitCode,
            String errorMessage)
            implements StreamEvent {}
}
