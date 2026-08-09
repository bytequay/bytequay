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
package com.bytequay.app.service.stage;

import com.bytequay.app.developmentflow.compatibility.V2ControlRouteStore;
import com.bytequay.app.developmentflow.stage.V2StageSteeringControl;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.sqlite.SqliteStageStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestStageSteeringService
{
    private SqliteStageStore stages;
    private TaskStore tasks;
    private V2ControlRouteStore routes;
    private V2StageSteeringControl typed;
    private StageSteeringServiceImpl service;

    @BeforeEach
    void setUp()
    {
        stages = mock(SqliteStageStore.class);
        tasks = mock(TaskStore.class);
        routes = mock(V2ControlRouteStore.class);
        typed = mock(V2StageSteeringControl.class);
        service = new StageSteeringServiceImpl(stages, tasks);
        service.setV2Routes(routes);
        service.setV2Steering(typed);
    }

    @Test
    void v2StageRoutesOnlyToItsTypedControl()
    {
        UUID stageId = UUID.randomUUID();
        when(routes.taskForStage(stageId.toString())).thenReturn(Optional.of("task-v2"));
        when(typed.steer(
                "task-v2", stageId.toString(), "change course", List.of(),
                V2StageSteeringControl.Mode.APPEND, null))
                .thenReturn("stage-turn-v2");

        StageSteeringServiceImpl.SteerResult result = service.steer(
                stageId, " change course ", List.of());

        assertThat(result.turnId()).isEqualTo("stage-turn-v2");
        verify(stages, never()).findStageById(any());
    }

    @Test
    void replacementModeReachesTheExactTypedControl()
    {
        UUID stageId = UUID.randomUUID();
        when(routes.taskForStage(stageId.toString())).thenReturn(Optional.of("task-v2"));
        when(typed.steer(
                "task-v2", stageId.toString(), "replace it", List.of(),
                V2StageSteeringControl.Mode.CANCEL_AND_REPLACE,
                "predecessor-turn"))
                .thenReturn("replacement-turn");

        assertThat(service.steer(
                stageId, "replace it", List.of(),
                StageSteeringServiceImpl.Mode.CANCEL_AND_REPLACE,
                "predecessor-turn").turnId())
                .isEqualTo("replacement-turn");
    }

    @Test
    void historicalStageCannotCreateAClassicTurn()
    {
        UUID stageId = UUID.randomUUID();
        StageInstance stage = mock(StageInstance.class);
        when(routes.taskForStage(stageId.toString())).thenReturn(Optional.empty());
        when(stages.findStageById(stageId)).thenReturn(Optional.of(stage));
        when(stage.taskId()).thenReturn("legacy-task");
        when(tasks.isV2Task("legacy-task")).thenReturn(false);

        assertThatThrownBy(() -> service.steer(stageId, "change course", List.of()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("read-only");
        verify(typed, never()).steer(
                any(), any(), any(), any(), any(), any());
    }

    @Test
    void legacyReplacementIsRejectedBeforeAnyLookup()
    {
        UUID stageId = UUID.randomUUID();
        when(routes.taskForStage(stageId.toString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.steer(
                stageId, "replace it", List.of(),
                StageSteeringServiceImpl.Mode.CANCEL_AND_REPLACE))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("only for V2");
        verify(stages, never()).findStageById(any());
    }
}
