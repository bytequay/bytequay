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

import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.repository.StageStore;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.threads.PlanKickoffRequested;
import com.bytequay.app.service.threads.TaskCreatedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.context.ApplicationEventPublisher;

import java.util.List;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure ordering contract retained for the read-only legacy compatibility
 * boundary. Typed V2 Task and Stage owners cover lifecycle mutation; the
 * retired TaskPhaseMachine is tested separately as fail-closed.
 */
class TestStageLifecycle
{
    @Test
    void taskCreationOpensPlanStageBeforePublishingPlanningKickoff()
    {
        StageStore stages = mock(StageStore.class);
        StageStateMachine stageMachine = mock(StageStateMachine.class);
        TaskStore tasks = mock(TaskStore.class);
        ApplicationEventPublisher publisher = mock(ApplicationEventPublisher.class);
        StageInstance opened = mock(StageInstance.class);
        when(stages.findStagesByTask("task-order")).thenReturn(List.of());
        when(stageMachine.ensurePhaseOpen("task-order", StageType.PLAN_STAGE, null))
                .thenReturn(opened);

        ObjectNode trunkPlan = new ObjectMapper().createObjectNode()
                .put("status", "finalized");
        new StageLifecycle(stages, stageMachine, tasks, publisher).onTaskCreated(
                new TaskCreatedEvent("task-order", "fix it", trunkPlan, true));

        InOrder order = inOrder(stageMachine, publisher);
        order.verify(stageMachine).ensurePhaseOpen(
                "task-order", StageType.PLAN_STAGE, null);
        order.verify(publisher).publishEvent(
                new PlanKickoffRequested("task-order", "fix it", trunkPlan));
        verify(opened).id();
    }
}
