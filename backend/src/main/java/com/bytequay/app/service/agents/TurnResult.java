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
 * Outcome of one {@link TurnRunner} turn.
 *
 * @param finalText      the last round's assistant text (may be empty
 *                       when the loop ended on the iteration bound or
 *                       was cut short).
 * @param tokensIn       prompt tokens summed across all rounds.
 * @param tokensOut      completion tokens summed across all rounds.
 * @param costMilliUsd   estimated turn cost from per-model pricing.
 * @param rounds         provider round-trips performed.
 * @param end            why the loop stopped.
 */
public record TurnResult(
        String finalText,
        long tokensIn,
        long tokensOut,
        long costMilliUsd,
        int rounds,
        End end)
{
    public enum End
    {
        /** The model produced a final-text round with no tool calls. */
        COMPLETED,
        /** {@link TurnHooks#interrupted()} fired at a round boundary. */
        INTERRUPTED,
        /** {@link TurnHooks#abortTurn} fired — typically a budget cap. */
        ABORTED,
        /** The tool-iteration bound was hit while the model was still
         *  calling tools; the runner forced one final tools-off round so
         *  the model answers from what it gathered rather than leaving
         *  the turn empty. {@code finalText} is that wrap-up summary. */
        MAX_STEPS,
    }
}
