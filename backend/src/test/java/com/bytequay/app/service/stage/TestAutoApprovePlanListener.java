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

import com.bytequay.app.repository.TaskStore;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestAutoApprovePlanListener
{
    private static final String TASK = "ws.t1.k1";
    private static final UUID PLAN_STAGE = UUID.randomUUID();

    @Test
    void approvesThePlanWhenAutoApproveIsOn()
    {
        TaskStore taskStore = mock(TaskStore.class);
        PlanStageService planStageService = mock(PlanStageService.class);
        when(taskStore.isAutoApprove(TASK)).thenReturn(true);

        new AutoApprovePlanListener(taskStore, planStageService)
                .onPlanFinalized(new PlanFinalizedEvent(TASK, PLAN_STAGE));

        verify(planStageService).approveByStage(PLAN_STAGE);
    }

    @Test
    void leavesThePlanForManualApprovalWhenAutoApproveIsOff()
    {
        TaskStore taskStore = mock(TaskStore.class);
        PlanStageService planStageService = mock(PlanStageService.class);
        when(taskStore.isAutoApprove(TASK)).thenReturn(false);

        new AutoApprovePlanListener(taskStore, planStageService)
                .onPlanFinalized(new PlanFinalizedEvent(TASK, PLAN_STAGE));

        verify(planStageService, never()).approveByStage(any());
    }
}
