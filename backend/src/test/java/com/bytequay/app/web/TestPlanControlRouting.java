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

import com.bytequay.app.developmentflow.compatibility.V2ControlRouteStore;
import com.bytequay.app.developmentflow.stage.V2PlanControlService;
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

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestPlanControlRouting
{
    private final PlanStageService legacy = mock(PlanStageService.class);
    private final TaskStore tasks = mock(TaskStore.class);
    private final V2ControlRouteStore routes = mock(V2ControlRouteStore.class);
    private final V2PlanControlService controls = mock(V2PlanControlService.class);
    private final StageController controller = controller();

    @Test
    void routesNewWorkflowPlanControlsOnlyToTheirTypedOwners()
    {
        UUID planStageId = UUID.randomUUID();
        when(routes.taskForStage(planStageId.toString()))
                .thenReturn(Optional.of("task-new"));
        when(tasks.isV2Task("task-new")).thenReturn(true);
        when(controls.approve(planStageId.toString()))
                .thenReturn(new V2PlanControlService.Approval(
                        "task-new", "local-new"));
        when(controls.replan("task-new"))
                .thenReturn(new V2PlanControlService.Replan(null, true));

        assertThat(controller.approvePlan(planStageId.toString()))
                .isEqualTo(new PlanStageService.ApproveResult(
                        "local-new", "/tasks/task-new/stages/local-new"));
        assertThat(controller.replan("task-new"))
                .isEqualTo(new PlanStageService.ReplanResult(null, true));
        controller.resolveFollowup(
                planStageId.toString(), "followup-new",
                new StageController.FollowupPatch("addressed"));

        verify(controls).approve(planStageId.toString());
        verify(controls).replan("task-new");
        verify(controls).resolveFollowup(
                "task-new", planStageId.toString(), "followup-new", "addressed");
        verifyNoInteractions(legacy);
    }

    @Test
    void leavesLegacyPlanControlsOnTheExistingService()
    {
        UUID planStageId = UUID.randomUUID();
        UUID followupId = UUID.randomUUID();
        when(routes.taskForStage(planStageId.toString()))
                .thenReturn(Optional.empty());
        when(tasks.isV2Task("task-legacy")).thenReturn(false);
        when(legacy.approveByStage(planStageId))
                .thenReturn(new PlanStageService.ApproveResult(
                        "local-legacy", "/legacy"));
        when(legacy.replan("task-legacy"))
                .thenReturn(new PlanStageService.ReplanResult(
                        "plan-legacy", false));

        assertThat(controller.approvePlan(planStageId.toString()))
                .isEqualTo(new PlanStageService.ApproveResult(
                        "local-legacy", "/legacy"));
        assertThat(controller.replan("task-legacy"))
                .isEqualTo(new PlanStageService.ReplanResult(
                        "plan-legacy", false));
        controller.resolveFollowup(
                planStageId.toString(), followupId.toString(),
                new StageController.FollowupPatch("dismissed"));

        verify(legacy).approveByStage(planStageId);
        verify(legacy).replan("task-legacy");
        verify(legacy).resolveFollowup(followupId, "dismissed");
        verifyNoInteractions(controls);
    }

    private StageController controller()
    {
        StageController result = new StageController(
                mock(StageService.class),
                mock(StageDetailService.class),
                mock(StageSteeringService.class),
                mock(StageRuntimeService.class),
                legacy,
                mock(StageStore.class),
                tasks,
                mock(ThreadStore.class),
                mock(WorkModelResolver.class));
        result.setV2PlanControls(routes, controls);
        return result;
    }
}
