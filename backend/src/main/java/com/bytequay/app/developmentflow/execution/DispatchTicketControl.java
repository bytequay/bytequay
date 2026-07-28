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

import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** Persists ticket cancellation even while V2 execution is globally paused. */
public final class DispatchTicketControl
{
    private final ExecutionPorts.DispatchTicketStore tickets;
    private final ObjectProvider<ExecutionDispatcher> dispatcher;
    private final Clock clock;

    public DispatchTicketControl(
            ExecutionPorts.DispatchTicketStore tickets,
            ObjectProvider<ExecutionDispatcher> dispatcher)
    {
        this(tickets, dispatcher, Clock.systemUTC());
    }

    DispatchTicketControl(
            ExecutionPorts.DispatchTicketStore tickets,
            ObjectProvider<ExecutionDispatcher> dispatcher,
            Clock clock)
    {
        this.tickets = requireNonNull(tickets, "tickets is null");
        this.dispatcher = requireNonNull(dispatcher, "dispatcher is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    public boolean requestCancel(String ticketId)
    {
        requireNonNull(ticketId, "ticketId is null");
        ExecutionDispatcher current = dispatcher.getIfAvailable();
        if (current != null) {
            return current.requestCancel(ticketId);
        }
        while (true) {
            Optional<DispatchTicket> found = tickets.findById(ticketId);
            if (found.isEmpty() || found.orElseThrow().state().isTerminal()) {
                return false;
            }
            DispatchTicket ticket = found.orElseThrow();
            DispatchTicket canceled = ticket.requestCancel(clock.instant());
            if (canceled == ticket) {
                return true;
            }
            if (tickets.compareAndSet(ticketId, ticket.version(), canceled)) {
                return true;
            }
        }
    }

    /** Re-arms a domain-deferred ticket even while V2 dispatch is paused. */
    public boolean resumeDeferred(String ticketId)
    {
        requireNonNull(ticketId, "ticketId is null");
        ExecutionDispatcher current = dispatcher.getIfAvailable();
        if (current != null) {
            return current.resumeDeferred(ticketId);
        }
        while (true) {
            Optional<DispatchTicket> found = tickets.findById(ticketId);
            if (found.isEmpty()) {
                return false;
            }
            DispatchTicket ticket = found.orElseThrow();
            if (ticket.state() != DispatchTicket.State.RECONCILE_WAIT
                    || ticket.nextAttemptAt() != null
                    || ticket.cancelRequestedAt() != null) {
                return false;
            }
            DispatchTicket resumed = ticket.resumeReconciliation(clock.instant());
            if (tickets.compareAndSet(ticketId, ticket.version(), resumed)) {
                return true;
            }
        }
    }
}
