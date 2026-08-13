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
package com.bytequay.app.flow.upstream;

import com.bytequay.app.flow.upstream.UpstreamSyncRecords.RepairPlacementPolicy;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.RunState;
import com.bytequay.app.flow.upstream.UpstreamSyncRecords.UpstreamSyncRun;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

final class TestUpstreamSyncRecords
{
    @Test
    void aRunWithoutACapAlwaysHasARepairTurn()
    {
        // Zero is the no-cap sentinel, not an exhausted budget: the default
        // start has no cap and must never park over spent turns.
        assertThat(run(0, 0).repairTurnsRemaining()).isTrue();
        assertThat(run(50, 1).repairTurnsRemaining()).isTrue();
        assertThat(run(50, 0).repairTurnsRemaining()).isFalse();
    }

    private static UpstreamSyncRun run(int budget, int remaining)
    {
        return new UpstreamSyncRun(
                "run", "request", "task",
                RepairPlacementPolicy.ATTRIBUTED_FIXUP, RunState.PICKING,
                budget, remaining, 0, null, null, null, null, 0, 0, 0);
    }
}
