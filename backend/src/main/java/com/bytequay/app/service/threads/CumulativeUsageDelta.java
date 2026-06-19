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

import java.util.concurrent.atomic.AtomicLong;

/**
 * Turns a provider's reported per-turn token usage into a per-turn delta the
 * running counters can sum.
 *
 * <p>A <em>cumulative</em> provider (Codex) reports the session running total
 * on every {@code turn.completed}, so the delta is the rise since the previous
 * turn — summing the raw values would quadratically over-count (turn1 +
 * turn1&2 + turn1&2&3 + …). A <em>per-turn</em> provider (Anthropic) already
 * reports the turn's own usage, so its value passes through unchanged.
 *
 * <p>For a cumulative provider the tracker is seeded with the session's prior
 * cumulative (the thread/task's persisted tokens when resuming an existing
 * session, else 0) so the first delta after a resume counts only new tokens.
 * Deltas are clamped at 0 so a session reset (cumulative dropping below the
 * last seen value) can't subtract.
 */
final class CumulativeUsageDelta
{
    private final boolean cumulative;
    private final AtomicLong lastSeen;

    CumulativeUsageDelta(boolean cumulative, long baseline)
    {
        this.cumulative = cumulative;
        this.lastSeen = new AtomicLong(Math.max(0L, baseline));
    }

    /** The per-turn delta for this turn's {@code reported} usage. */
    long delta(long reported)
    {
        if (!cumulative) {
            return Math.max(0L, reported);
        }
        long previous = lastSeen.getAndSet(reported);
        return Math.max(0L, reported - previous);
    }
}
