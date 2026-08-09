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
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.repository.ThreadStore;
import com.bytequay.app.repository.sqlite.SqliteStageStore;
import com.bytequay.app.service.stage.StageDetailServiceImpl;
import com.bytequay.app.service.stage.StageServiceImpl;
import com.bytequay.app.service.stage.StageSteeringServiceImpl;
import com.bytequay.app.service.workmodel.WorkModelResolver;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestPlanControlRouting
{
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
                .isEqualTo(new StageController.ApprovePlanResult(
                        "local-new", "/tasks/task-new/stages/local-new"));
        assertThat(controller.replan("task-new"))
                .isEqualTo(new StageController.ReplanResult(null, true));
        controller.resolveFollowup(
                planStageId.toString(), "followup-new",
                new StageController.FollowupPatch("addressed"));

        verify(controls).approve(planStageId.toString());
        verify(controls).replan("task-new");
        verify(controls).resolveFollowup(
                "task-new", planStageId.toString(), "followup-new", "addressed");
    }

    @Test
    void rejectsLegacyPlanMutationsWithoutCallingEitherOwner()
    {
        UUID planStageId = UUID.randomUUID();
        UUID followupId = UUID.randomUUID();
        when(routes.taskForStage(planStageId.toString()))
                .thenReturn(Optional.empty());
        when(tasks.isV2Task("task-legacy")).thenReturn(false);

        assertThatThrownBy(() -> controller.approvePlan(planStageId.toString()))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("read-only");
        assertThatThrownBy(() -> controller.replan("task-legacy"))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("read-only");
        assertThatThrownBy(() -> controller.resolveFollowup(
                planStageId.toString(), followupId.toString(),
                new StageController.FollowupPatch("dismissed")))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("read-only");

        verifyNoInteractions(controls);
    }

    private StageController controller()
    {
        StageController result = new StageController(
                mock(StageServiceImpl.class),
                mock(StageDetailServiceImpl.class),
                mock(StageSteeringServiceImpl.class),
                mock(SqliteStageStore.class),
                tasks,
                mock(ThreadStore.class),
                mock(WorkModelResolver.class));
        result.setV2PlanControls(routes, controls);
        return result;
    }
}
