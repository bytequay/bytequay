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
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.service.stage.PlanStageService;
import com.bytequay.app.service.stage.StageDetailService;
import com.bytequay.app.service.stage.StageRuntimeService;
import com.bytequay.app.service.stage.StageService;
import com.bytequay.app.service.stage.StageSteeringService;
import com.bytequay.app.service.workmodel.WorkModelResolver;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestStageApiRouting
{
    private final StageDetailService legacyDetail = mock(StageDetailService.class);
    private final StageRuntimeService legacyRuntime = mock(StageRuntimeService.class);
    private final StageSteeringService steering = mock(StageSteeringService.class);
    private final StageService stages = mock(StageService.class);
    private final V2ControlRouteStore routes = mock(V2ControlRouteStore.class);
    private final V2StageApiService v2 = mock(V2StageApiService.class);
    private final StageController controller = controller();

    @Test
    void singleStageRouteDelegatesToTheVersionAwareStageProjection()
    {
        UUID stageId = UUID.randomUUID();
        StageDetailDto detail = mock(StageDetailDto.class);
        when(stages.getStageDetail(stageId)).thenReturn(detail);

        assertThat(controller.stage(stageId.toString())).isSameAs(detail);

        verify(stages).getStageDetail(stageId);
        verifyNoInteractions(legacyDetail, legacyRuntime, v2);
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
        verifyNoInteractions(legacyDetail, legacyRuntime);
    }

    @Test
    void legacyDetailStreamAndInterruptKeepTheirExistingOwners()
    {
        UUID stageId = UUID.randomUUID();
        StageDetailData detail = mock(StageDetailData.class);
        when(routes.taskForStage(stageId.toString())).thenReturn(Optional.empty());
        when(legacyDetail.getDetail(stageId)).thenReturn(detail);
        when(legacyRuntime.subscribe(eq(stageId), any())).thenReturn(() -> {});

        assertThat(controller.stageDetail(stageId.toString())).isSameAs(detail);
        controller.stream(stageId.toString());
        controller.interrupt(stageId.toString());

        verify(legacyDetail).getDetail(stageId);
        verify(legacyRuntime).subscribe(eq(stageId), any());
        verify(legacyRuntime).interrupt(stageId);
        verifyNoInteractions(v2);
    }

    @Test
    void steeringDefaultsToAppendAndAcceptsExplicitReplacement()
    {
        UUID stageId = UUID.randomUUID();
        when(steering.steer(
                stageId, "append", List.of(), StageSteeringService.Mode.APPEND))
                .thenReturn(new StageSteeringService.SteerResult("append-turn"));
        when(steering.steer(
                stageId, "replace", List.of(),
                StageSteeringService.Mode.CANCEL_AND_REPLACE))
                .thenReturn(new StageSteeringService.SteerResult("replace-turn"));

        assertThat(controller.steer(
                stageId.toString(),
                new StageController.SteerRequest("append", List.of(), null))
                .turnId()).isEqualTo("append-turn");
        assertThat(controller.steer(
                stageId.toString(),
                new StageController.SteerRequest(
                        "replace", List.of(),
                        StageSteeringService.Mode.CANCEL_AND_REPLACE))
                .turnId()).isEqualTo("replace-turn");

        verify(steering).steer(
                stageId, "append", List.of(), StageSteeringService.Mode.APPEND);
        verify(steering).steer(
                stageId, "replace", List.of(),
                StageSteeringService.Mode.CANCEL_AND_REPLACE);
    }

    private StageController controller()
    {
        StageController result = new StageController(
                stages, legacyDetail, steering, legacyRuntime,
                mock(PlanStageService.class), mock(StageStore.class),
                mock(TaskStore.class), mock(ThreadStore.class),
                mock(WorkModelResolver.class));
        result.setV2Stages(routes, v2);
        return result;
    }
}
