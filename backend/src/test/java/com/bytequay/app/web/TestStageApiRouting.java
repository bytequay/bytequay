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
package com.bytequay.app.web;

import com.bytequay.app.beans.stage.StageDetailData;
import com.bytequay.app.beans.stage.StageDetailDto;
import com.bytequay.app.developmentflow.compatibility.V2ControlRouteStore;
import com.bytequay.app.developmentflow.compatibility.V2StageApiService;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.stage.PlanStageService;
import com.bytequay.app.service.stage.StageDetailServiceImpl;
import com.bytequay.app.service.stage.StageServiceImpl;
import com.bytequay.app.service.stage.StageSteeringServiceImpl;
import com.bytequay.app.service.workmodel.ReasoningEffortService;
import com.bytequay.app.service.workmodel.WorkModelResolver;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestStageApiRouting
{
    private final StageDetailServiceImpl legacyDetail = mock(StageDetailServiceImpl.class);
    private final StageSteeringServiceImpl steering = mock(StageSteeringServiceImpl.class);
    private final StageServiceImpl stages = mock(StageServiceImpl.class);
    private final V2ControlRouteStore routes = mock(V2ControlRouteStore.class);
    private final V2StageApiService v2 = mock(V2StageApiService.class);
    private final StageStore stageStore = mock(StageStore.class);
    private final TaskStore taskStore = mock(TaskStore.class);
    private final ReasoningEffortService reasoningEfforts =
            mock(ReasoningEffortService.class);
    private final StageController controller = controller();

    @Test
    void singleStageRouteDelegatesToTheVersionAwareStageProjection()
    {
        UUID stageId = UUID.randomUUID();
        StageDetailDto detail = mock(StageDetailDto.class);
        when(stages.getStageDetail(stageId)).thenReturn(detail);

        assertThat(controller.stage(stageId.toString())).isSameAs(detail);

        verify(stages).getStageDetail(stageId);
        verifyNoInteractions(legacyDetail, v2);
    }

    @Test
    void v2DetailStreamAndInterruptNeverReachLegacyStageRuntime()
    {
        UUID stageId = UUID.randomUUID();
        StageDetailData detail = mock(StageDetailData.class);
        when(routes.taskForStage(stageId.toString()))
                .thenReturn(Optional.of("task-v2"));
        when(v2.detail("task-v2", stageId.toString())).thenReturn(detail);
        when(v2.subscribe(eq("task-v2"), eq(stageId.toString()), any()))
                .thenReturn(() -> {});

        assertThat(controller.stageDetail(stageId.toString())).isSameAs(detail);
        controller.stream(stageId.toString());
        controller.interrupt(stageId.toString());

        verify(v2).detail("task-v2", stageId.toString());
        verify(v2).subscribe(eq("task-v2"), eq(stageId.toString()), any());
        verify(v2).interrupt("task-v2", stageId.toString());
        verifyNoInteractions(legacyDetail);
    }

    @Test
    void legacyDetailRemainsReadableButRuntimeEndpointsAreRejected()
    {
        UUID stageId = UUID.randomUUID();
        StageDetailData detail = mock(StageDetailData.class);
        when(routes.taskForStage(stageId.toString())).thenReturn(Optional.empty());
        when(legacyDetail.getDetail(stageId)).thenReturn(detail);

        assertThat(controller.stageDetail(stageId.toString())).isSameAs(detail);
        assertThatThrownBy(() -> controller.stream(stageId.toString()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("read-only");
        assertThatThrownBy(() -> controller.interrupt(stageId.toString()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("read-only");

        verify(legacyDetail).getDetail(stageId);
        verifyNoInteractions(v2);
    }

    @Test
    void stageWorkModelMutationUsesOnlyTheTypedV2Owner()
    {
        UUID legacyId = UUID.randomUUID();
        UUID v2Id = UUID.randomUUID();
        StageInstance legacyStage = mock(StageInstance.class);
        Task legacyTask = mock(Task.class);
        Task v2Task = mock(Task.class);
        when(legacyTask.id()).thenReturn("task-legacy");
        when(v2Task.id()).thenReturn("task-v2");
        when(v2Task.threadId()).thenReturn("trunk-v2");
        when(legacyStage.taskId()).thenReturn("task-legacy");
        when(stageStore.findStageById(legacyId)).thenReturn(Optional.of(legacyStage));
        when(taskStore.findTaskById("task-legacy")).thenReturn(Optional.of(legacyTask));
        when(taskStore.findTaskById("task-v2")).thenReturn(Optional.of(v2Task));
        when(routes.taskForStage(v2Id.toString()))
                .thenReturn(Optional.of("task-v2"));
        WorkModel effective = new WorkModel(
                WorkModelKind.CLI, "codex", "gpt-5", null, "high");
        when(reasoningEfforts.resolveStageEngine(
                "trunk-v2", "task-v2", v2Id.toString()))
                .thenReturn(effective);

        assertThatThrownBy(() -> controller.setWorkModel(legacyId.toString(), null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("read-only");
        assertThat(controller.setWorkModel(
                v2Id.toString(),
                new StageController.WorkModelBody(effective)).effective())
                .isEqualTo(effective);

        verify(reasoningEfforts).setStage("task-v2", v2Id.toString(), "high");
        verify(stageStore, never())
                .updateWorkModel(any(), any());
    }

    @Test
    void steeringDefaultsToAppendAndAcceptsExplicitReplacement()
    {
        UUID stageId = UUID.randomUUID();
        when(steering.steer(
                stageId, "append", List.of(), StageSteeringServiceImpl.Mode.APPEND,
                null))
                .thenReturn(new StageSteeringServiceImpl.SteerResult("append-turn"));
        when(steering.steer(
                stageId, "replace", List.of(),
                StageSteeringServiceImpl.Mode.CANCEL_AND_REPLACE,
                "predecessor-turn"))
                .thenReturn(new StageSteeringServiceImpl.SteerResult("replace-turn"));

        assertThat(controller.steer(
                stageId.toString(),
                new StageController.SteerRequest(
                        "append", List.of(), null, null))
                .turnId()).isEqualTo("append-turn");
        assertThat(controller.steer(
                stageId.toString(),
                new StageController.SteerRequest(
                        "replace", List.of(),
                        StageSteeringServiceImpl.Mode.CANCEL_AND_REPLACE,
                        "predecessor-turn"))
                .turnId()).isEqualTo("replace-turn");

        verify(steering).steer(
                stageId, "append", List.of(), StageSteeringServiceImpl.Mode.APPEND,
                null);
        verify(steering).steer(
                stageId, "replace", List.of(),
                StageSteeringServiceImpl.Mode.CANCEL_AND_REPLACE,
                "predecessor-turn");
    }

    private StageController controller()
    {
        StageController result = new StageController(
                stages, legacyDetail, steering,
                mock(PlanStageService.class), stageStore,
                taskStore, mock(ThreadStore.class),
                mock(WorkModelResolver.class));
        result.setV2Stages(routes, v2);
        result.setReasoningEfforts(reasoningEfforts);
        return result;
    }
}
