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
package com.bytequay.app.agent;

import com.bytequay.agent.core.AgentApprovalService;
import com.bytequay.agent.core.AgentCompletionService;
import com.bytequay.agent.core.AgentEventOutboxEntry;
import com.bytequay.agent.core.AgentEventOutboxPayload;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.AgentEventSource;
import com.bytequay.agent.core.AgentMetricsService;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.impl.AgentMaintenance;
import com.bytequay.agent.core.impl.AgentRuntimeRegistry;
import com.bytequay.agent.core.store.AgentEventOutboxStore;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.AgentUnitOfWork;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestAgentStartupRecovery
{
    @Test
    void drainsBeforeAndAfterMaintenanceThenOpensTheGate()
    {
        AgentEventOutboxStore outboxStore = mock(AgentEventOutboxStore.class);
        AgentEventService eventService = mock(AgentEventService.class);
        AgentTurnStore turnStore = mock(AgentTurnStore.class);
        AgentTaskStore taskStore = mock(AgentTaskStore.class);
        AgentMaintenance maintenance = maintenance(taskStore, turnStore);
        AgentServingGate servingGate = new AgentServingGate();
        AgentEventOutboxEntry before = event("before");
        AgentEventOutboxEntry after = event("after");
        when(outboxStore.listPending(100)).thenReturn(
                ImmutableList.of(before),
                ImmutableList.of(),
                ImmutableList.of(after),
                ImmutableList.of());
        new AgentStartupRecovery(outboxStore, eventService, maintenance, servingGate)
                .afterSingletonsInstantiated();

        InOrder order = inOrder(eventService, turnStore, taskStore);
        order.verify(eventService).drain(before.eventId());
        order.verify(turnStore).listRecoverable();
        order.verify(taskStore).listLive();
        order.verify(eventService).drain(after.eventId());
        assertThat(servingGate.isReady()).isTrue();
    }

    @Test
    void keepsTheGateClosedWhenOutboxRecoveryFails()
    {
        AgentEventOutboxStore outboxStore = mock(AgentEventOutboxStore.class);
        AgentEventService eventService = mock(AgentEventService.class);
        AgentTurnStore turnStore = mock(AgentTurnStore.class);
        AgentTaskStore taskStore = mock(AgentTaskStore.class);
        AgentMaintenance maintenance = maintenance(taskStore, turnStore);
        AgentServingGate servingGate = new AgentServingGate();
        AgentEventOutboxEntry event = event("broken");
        when(outboxStore.listPending(100)).thenReturn(ImmutableList.of(event));
        doThrow(new IllegalStateException("broken log")).when(eventService).drain(event.eventId());

        new AgentStartupRecovery(outboxStore, eventService, maintenance, servingGate)
                .afterSingletonsInstantiated();

        assertThat(servingGate.isReady()).isFalse();
        verifyNoInteractions(turnStore, taskStore);
    }

    @Test
    void keepsTheGateClosedWhenMaintenanceRecoveryFails()
    {
        AgentEventOutboxStore outboxStore = mock(AgentEventOutboxStore.class);
        AgentEventService eventService = mock(AgentEventService.class);
        AgentTurnStore turnStore = mock(AgentTurnStore.class);
        AgentTaskStore taskStore = mock(AgentTaskStore.class);
        AgentServingGate servingGate = new AgentServingGate();
        when(outboxStore.listPending(100)).thenReturn(ImmutableList.of());
        when(turnStore.listRecoverable()).thenThrow(new IllegalStateException("broken recovery"));

        new AgentStartupRecovery(outboxStore, eventService, maintenance(taskStore, turnStore), servingGate)
                .afterSingletonsInstantiated();

        assertThat(servingGate.isReady()).isFalse();
        verifyNoInteractions(eventService, taskStore);
    }

    private static AgentMaintenance maintenance(AgentTaskStore taskStore, AgentTurnStore turnStore)
    {
        return new AgentMaintenance(
                taskStore,
                turnStore,
                mock(AgentUnitOfWork.class),
                mock(AgentEventOutboxStore.class),
                mock(AgentApprovalService.class),
                mock(AgentMetricsService.class),
                mock(AgentCompletionService.class),
                new AgentRuntimeRegistry(),
                Clock.systemUTC());
    }

    private static AgentEventOutboxEntry event(String message)
    {
        return new AgentEventOutboxEntry(
                EventId.random(),
                TaskId.random(),
                Optional.empty(),
                AgentEventSource.root(),
                new AgentEventOutboxPayload.Inline(new AgentEventPayload.Error("startup", message, false)),
                Instant.parse("2026-09-01T00:00:00Z"),
                Optional.empty());
    }
}
