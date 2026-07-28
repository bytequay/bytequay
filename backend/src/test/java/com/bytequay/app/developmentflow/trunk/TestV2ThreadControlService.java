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
package com.bytequay.app.developmentflow.trunk;

import com.bytequay.app.developmentflow.CommandResult;
import com.bytequay.app.developmentflow.execution.ExecutionDispatcher;
import com.bytequay.app.developmentflow.execution.agentturn.AgentTurnProviderSession;
import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.domain.WorkModel;
import com.bytequay.app.domain.WorkModelKind;
import com.bytequay.app.service.skills.RoleRegistry;
import com.bytequay.app.service.workmodel.SessionAudience;
import com.bytequay.app.service.workmodel.ThreadEngineOverrides;
import com.bytequay.app.service.workspaces.SessionKnowledgeProvider;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestV2ThreadControlService
{
    @Test
    void sendsTrunkInputThroughDurablePlanningRefresh()
    {
        PlanningBaseTurnRuntime planning = mock(PlanningBaseTurnRuntime.class);
        ThreadEngineOverrides engines = mock(ThreadEngineOverrides.class);
        RoleRegistry roles = mock(RoleRegistry.class);
        SessionKnowledgeProvider knowledge = mock(SessionKnowledgeProvider.class);
        Thread thread = mock(Thread.class);
        WorkModel engine = mock(WorkModel.class);

        when(thread.id()).thenReturn("trunk-1");
        when(thread.workspaceId()).thenReturn("workspace-1");
        when(thread.title()).thenReturn("Trunk one");
        when(engines.forAudience("trunk-1", SessionAudience.PLAN))
                .thenReturn(Optional.of(engine));
        when(engine.kind()).thenReturn(WorkModelKind.CLI);
        when(engine.agentOrProvider()).thenReturn("claude");
        when(engine.model()).thenReturn("model-1");
        when(engine.reasoningEffort()).thenReturn("high");
        when(roles.trunkTemplate()).thenReturn("system");
        when(knowledge.renderForThread(
                "workspace-1", "trunk-1", SessionAudience.PLAN, "Trunk one"))
                .thenReturn("");
        when(planning.request(any()))
                .thenReturn(new PlanningBaseTurnRuntime.Receipt(
                        "turn-1", "planning-1", "operation-1", "ticket-1",
                        CommandResult.Disposition.APPLIED));

        V2ThreadControlService service = new V2ThreadControlService(
                planning, mock(ThreadTurnProjection.class),
                mock(ExecutionDispatcher.class), engines, roles, knowledge);

        assertThat(service.send(thread, "plan the next task", TurnInitiator.user()))
                .isEqualTo("turn-1");

        ArgumentCaptor<PlanningBaseTurnRuntime.Request> request =
                ArgumentCaptor.forClass(PlanningBaseTurnRuntime.Request.class);
        verify(planning).request(request.capture());
        assertThat(request.getValue().trunkId()).isEqualTo("trunk-1");
        assertThat(request.getValue().workspaceId()).isEqualTo("workspace-1");
        assertThat(request.getValue().purpose()).isEqualTo("TRUNK_CONVERSATION");
        assertThat(request.getValue().transport())
                .isEqualTo(AgentTurnProviderSession.Transport.CLI);
        assertThat(request.getValue().userMessage()).isEqualTo("plan the next task");
        assertThat(request.getValue().prompt()).isEqualTo("plan the next task");
    }

    @Test
    void interruptPersistsPreLaunchSuppressionBeforeCancelingTickets()
    {
        PlanningBaseTurnRuntime planning = mock(PlanningBaseTurnRuntime.class);
        ThreadTurnProjection projection = mock(ThreadTurnProjection.class);
        ExecutionDispatcher dispatcher = mock(ExecutionDispatcher.class);
        when(projection.cancelableTicketIds("trunk-1"))
                .thenReturn(List.of("ticket-1"));
        V2ThreadControlService service = new V2ThreadControlService(
                planning, projection, dispatcher,
                mock(ThreadEngineOverrides.class), mock(RoleRegistry.class),
                mock(SessionKnowledgeProvider.class));

        service.interrupt("trunk-1");

        InOrder order = inOrder(planning, dispatcher);
        order.verify(planning).suppressPending(
                "trunk-1", "User canceled before provider launch");
        order.verify(dispatcher).requestCancel("ticket-1");
    }
}
