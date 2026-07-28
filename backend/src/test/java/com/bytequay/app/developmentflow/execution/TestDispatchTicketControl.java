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
package com.bytequay.app.developmentflow.execution;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static com.bytequay.app.developmentflow.execution.CapacityManager.CapacityLane.VALIDATION;
import static com.bytequay.app.developmentflow.execution.CapacityManager.WorkflowSource.V2;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.OwnerKind.STAGE;
import static com.bytequay.app.developmentflow.execution.DispatchTicket.State.RESULT_PENDING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestDispatchTicketControl
{
    private static final Instant NOW = Instant.parse("2026-07-28T00:00:00Z");

    @Test
    void persistsQueuedCancellationWithoutAStartedDispatcher()
    {
        AtomicReference<DispatchTicket> stored = new AtomicReference<>(ticket());
        ExecutionPorts.DispatchTicketStore tickets = mock(
                ExecutionPorts.DispatchTicketStore.class);
        when(tickets.findById("ticket")).thenAnswer(ignored ->
                Optional.of(stored.get()));
        when(tickets.compareAndSet(eq("ticket"), anyLong(), any()))
                .thenAnswer(invocation -> {
                    long version = invocation.getArgument(1);
                    DispatchTicket replacement = invocation.getArgument(2);
                    if (stored.get().version() != version) {
                        return false;
                    }
                    stored.set(replacement);
                    return true;
                });
        @SuppressWarnings("unchecked")
        ObjectProvider<ExecutionDispatcher> dispatcher = mock(ObjectProvider.class);
        when(dispatcher.getIfAvailable()).thenReturn(null);
        DispatchTicketControl control = new DispatchTicketControl(
                tickets, dispatcher, Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(control.requestCancel("ticket")).isTrue();
        assertThat(stored.get().state()).isEqualTo(RESULT_PENDING);
        assertThat(stored.get().cancelRequestedAt()).isEqualTo(NOW);
        assertThat(stored.get().pendingResult().outcome())
                .isEqualTo(DispatchTicket.Outcome.CANCELED);
    }

    private static DispatchTicket ticket()
    {
        DispatchTicket.OperationFence fence = new DispatchTicket.OperationFence(
                1L, "stage", 1L, "operation", 1,
                "fingerprint", "head", "base");
        CapacityManager.CapacityRequest capacity = new CapacityManager.CapacityRequest(
                "operation", V2, Set.of(VALIDATION),
                new CapacityManager.CapacityScope(
                        "workspace", "trunk", "task", 1L),
                false, true, false);
        return DispatchTicket.requested(
                "ticket",
                new DispatchTicket.DispatchEnvelope(
                        "VALIDATE", DispatchTicket.AsyncFamily.VALIDATION,
                        new DispatchTicket.OwnerReference(
                                STAGE, "stage", "validation-result"),
                        fence, capacity),
                NOW.minusSeconds(1));
    }
}
