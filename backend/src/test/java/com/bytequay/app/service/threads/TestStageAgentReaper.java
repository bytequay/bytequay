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

import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageState;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.service.stage.StageClosedEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executor;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The reaper stops and evicts only the closed stage's per-stage agent,
 * keying eviction by stage id and resolving the owning thread from the
 * task. A close with no live agent for the stage is a no-op.
 */
class TestStageAgentReaper
{
    private static final String STAGE_ID = "00000000-0000-0000-0000-000000000101";

    private final ThreadRegistry registry = mock(ThreadRegistry.class);
    private final StageStore stageStore = mock(StageStore.class);
    private final Executor direct = Runnable::run;
    private final StageAgentReaper reaper = new StageAgentReaper(registry, stageStore, direct);

    @Test
    void stopsAndEvictsTheClosedStageAgent()
    {
        ThreadAgent agent = mock(ThreadAgent.class);
        when(registry.findStages(List.of("stage-1"))).thenReturn(List.of(agent));

        reaper.onStageClosed(new StageClosedEvent("task-1", "stage-1"));

        verify(agent).stop();
        verify(registry).evictStage(null, "stage-1");
    }

    @Test
    void evictsByStageIdEvenWhenNoLiveAgentExists()
    {
        when(registry.findStages(List.of("stage-1"))).thenReturn(List.of());

        reaper.onStageClosed(new StageClosedEvent("task-1", "stage-1"));

        verify(registry).evictStage(null, "stage-1");
    }

    @Test
    void ignoresAClosedEventWithNoStageId()
    {
        reaper.onStageClosed(new StageClosedEvent("task-1", null));

        verify(registry, never()).evictStage(any(), any());
    }

    @Test
    void sweepReapsClosedAndMissingStagesButKeepsOpenStages()
    {
        String missingId = "00000000-0000-0000-0000-000000000102";
        String openId = "00000000-0000-0000-0000-000000000103";
        ThreadAgent closedAgent = mock(ThreadAgent.class);
        ThreadAgent missingAgent = mock(ThreadAgent.class);
        when(registry.cachedStageIds()).thenReturn(Set.of(STAGE_ID, missingId, openId));
        when(stageStore.findStageById(UUID.fromString(STAGE_ID)))
                .thenReturn(Optional.of(stage(STAGE_ID, StageState.CLOSED)));
        when(stageStore.findStageById(UUID.fromString(missingId))).thenReturn(Optional.empty());
        when(stageStore.findStageById(UUID.fromString(openId)))
                .thenReturn(Optional.of(stage(openId, StageState.OPEN)));
        when(registry.findStages(List.of(STAGE_ID))).thenReturn(List.of(closedAgent));
        when(registry.findStages(List.of(missingId))).thenReturn(List.of(missingAgent));

        reaper.reconcileClosedStages();

        verify(closedAgent).stop();
        verify(missingAgent).stop();
        verify(registry).evictStage(null, STAGE_ID);
        verify(registry).evictStage(null, missingId);
        verify(registry, never()).evictStage(null, openId);
    }

    private static StageInstance stage(String id, StageState state)
    {
        return new StageInstance(
                UUID.fromString(id), "task-1", StageType.DEVELOPMENT_STAGE,
                state, Instant.parse("2026-06-17T12:00:00Z"), null, null);
    }
}
