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
package com.bytequay.app.service.agents;

/**
 * Observation + control hooks a {@link TurnRunner} caller can plug
 * into the loop. The runner itself owns no persistence and no event
 * stream — these hooks are where a caller persists tool rows, emits
 * stream events, meters spend, or pulls the plug. All methods default
 * to no-ops so callers override only what they need.
 */
public interface TurnHooks
{
    TurnHooks NONE = new TurnHooks() {};

    /** A streamed text fragment arrived. {@code blockIndex} is the
     *  provider's content-block index (always 0 on OpenAI-compatible
     *  transports). */
    default void onTextDelta(int blockIndex, String chunk)
    {
    }

    /** The provider reported cumulative usage for the current round. */
    default void onUsage(long tokensIn, long tokensOut)
    {
    }

    /** Fires immediately before the executor runs a tool call. */
    default void onToolCallStarted(String callId, String toolName, String inputJson)
    {
    }

    /** Fires immediately after the executor returned a tool result. */
    default void onToolCallDone(String callId, String resultText, boolean isError)
    {
    }

    /** One provider round-trip finished (whether or not it carried
     *  tool calls). Token counts are this round's, not the running
     *  total. */
    default void onRoundCompleted(long tokensIn, long tokensOut, long elapsedNanos)
    {
    }

    /** Polled at the top of each round and between stream frames.
     *  {@code true} stops the loop: mid-stream it ends the read, at a
     *  round boundary it ends the turn with
     *  {@link TurnResult.End#INTERRUPTED}. */
    default boolean interrupted()
    {
        return false;
    }

    /** Polled after each completed round with the turn's running cost
     *  estimate. {@code true} stops the loop with
     *  {@link TurnResult.End#ABORTED} — the budget-cap hook for
     *  callers that meter spend. */
    default boolean abortTurn(long costSoFarMilliUsd)
    {
        return false;
    }
}
