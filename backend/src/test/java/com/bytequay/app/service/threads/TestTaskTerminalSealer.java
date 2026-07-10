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

import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageState;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.service.review.ReviewRoundService;
import com.bytequay.app.service.runs.AgentRunService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestTaskTerminalSealer
{
    private static final String TASK_ID = "t1.k1";

    private final StageStore stageStore = mock(StageStore.class);
    private final ReviewRoundService reviewRounds = mock(ReviewRoundService.class);
    private final AgentRunService agentRuns = mock(AgentRunService.class);
    private final TaskTerminalSealer sealer = new TaskTerminalSealer(stageStore, reviewRounds, agentRuns);

    @Test
    void closesTheOpenRoundAndEveryStillOpenStage()
    {
        StageInstance open = stage(StageState.OPEN);
        StageInstance closed = stage(StageState.CLOSED);
        when(agentRuns.liveRunsByTask(TASK_ID)).thenReturn(List.of());
        when(stageStore.findStagesByTask(TASK_ID)).thenReturn(List.of(open, closed));

        sealer.seal(TASK_ID, "pr_merged");

        verify(reviewRounds).closeOpenRounds(TASK_ID, "pr_merged");
        verify(stageStore).closeStage(open.id(), "pr_merged");
        verify(stageStore, never()).closeStage(closed.id(), "pr_merged");
    }

    @Test
    void cancelsAnyLiveRunLeftAfterClosingRounds()
    {
        AgentRun run = new AgentRun(
                "run-1", TASK_ID, AgentRun.KIND_REVIEW_ROUND, AgentRun.SOURCE_REMOTE,
                null, null, "stage-1", AgentRun.STATUS_RUNNING, 0, null, null, null,
                Instant.parse("2026-07-08T00:00:00Z"), null);
        when(agentRuns.liveRunsByTask(TASK_ID)).thenReturn(List.of(run));
        when(stageStore.findStagesByTask(TASK_ID)).thenReturn(List.of());

        sealer.seal(TASK_ID, "pr_merged");

        verify(agentRuns).transition("run-1", AgentRun.STATUS_CANCELLED, "pr_merged");
    }

    private static StageInstance stage(StageState state)
    {
        return new StageInstance(
                UUID.randomUUID(), TASK_ID, StageType.DEVELOPMENT_STAGE, state,
                Instant.parse("2026-07-08T00:00:00Z"), null, null, null);
    }
}
