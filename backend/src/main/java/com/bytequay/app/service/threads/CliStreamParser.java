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
package com.bytequay.app.service.threads;

import com.bytequay.app.domain.StreamEvent;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Converts one line of a CLI agent's streaming-JSON stdout into zero or
 * more {@link StreamEvent}s. Each supported CLI (Claude Code's
 * {@code stream-json}, Codex's {@code --json}) ships its own
 * implementation; {@link AbstractCliThreadAgent} holds one and feeds it
 * every stdout line so the provider-agnostic lifecycle never has to know
 * the wire shape.
 *
 * <p>Implementations are tolerant: an unrecognized or malformed line
 * returns an empty list rather than throwing, so a new upstream event
 * type can't crash a running turn.
 */
public interface CliStreamParser
{
    /**
     * Parse one stdout line. {@code now} is the wall-clock stamp to put
     * on emitted events — the source formats carry no timestamps, so
     * events anchor against parse time.
     */
    List<StreamEvent> parse(String line, Instant now);

    /**
     * Whether the {@code tokensIn}/{@code tokensOut} this parser puts on a
     * {@link StreamEvent.TurnDone} are the session's <em>cumulative</em> total
     * (true) or the turn's own usage (false). Codex's {@code turn.completed}
     * reports the running session total on every turn, so summing them
     * quadratically over-counts; the agent converts those to per-turn deltas.
     * Anthropic reports per-turn usage, so it sums directly.
     */
    default boolean reportsCumulativeUsage()
    {
        return false;
    }

    /**
     * Authoritative provider result observed while parsing the current turn.
     * Claude includes this separately from the assistant envelopes used for
     * streaming; other providers derive their result from those envelopes.
     */
    default Optional<String> terminalResult()
    {
        return Optional.empty();
    }
}
