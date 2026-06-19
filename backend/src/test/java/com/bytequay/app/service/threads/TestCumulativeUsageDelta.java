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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit coverage for {@link CumulativeUsageDelta} — the fix for the inflated
 * token metrics on Codex threads, where {@code turn.completed} reports the
 * session running total each turn and summing them quadratically over-counted.
 */
class TestCumulativeUsageDelta
{
    @Test
    void cumulativeProviderDeltasSumToTheLastTotalNotTheSumOfTotals()
    {
        CumulativeUsageDelta tracker = new CumulativeUsageDelta(/* cumulative */ true, 0L);

        // Codex reports the running session total each turn: 100, 250, 400.
        // Naively summing those gives 750 (the bug); the true total is 400.
        long total = tracker.delta(100L) + tracker.delta(250L) + tracker.delta(400L);

        assertThat(total).isEqualTo(400L);
    }

    @Test
    void cumulativeProviderResumesFromTheSeededBaseline()
    {
        // A rebuilt agent re-seeds from the persisted total (250) and the
        // session keeps reporting its growing cumulative — only the new tokens
        // since the baseline count.
        CumulativeUsageDelta tracker = new CumulativeUsageDelta(true, 250L);

        assertThat(tracker.delta(270L)).isEqualTo(20L);
        assertThat(tracker.delta(330L)).isEqualTo(60L);
    }

    @Test
    void cumulativeProviderClampsASessionResetToZero()
    {
        CumulativeUsageDelta tracker = new CumulativeUsageDelta(true, 0L);
        assertThat(tracker.delta(100L)).isEqualTo(100L);
        // A lower value (e.g. a fresh session, or a transient mis-report) must
        // not subtract — clamp to 0.
        assertThat(tracker.delta(30L)).isEqualTo(0L);
        assertThat(tracker.delta(140L)).isEqualTo(110L);
    }

    @Test
    void perTurnProviderPassesEachTurnsUsageThrough()
    {
        // Anthropic reports the turn's own usage; summing is already correct.
        CumulativeUsageDelta tracker = new CumulativeUsageDelta(/* cumulative */ false, 0L);

        long total = tracker.delta(100L) + tracker.delta(250L) + tracker.delta(400L);

        assertThat(total).isEqualTo(750L);
    }
}
