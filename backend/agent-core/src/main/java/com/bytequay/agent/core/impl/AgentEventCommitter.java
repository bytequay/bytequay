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
package com.bytequay.agent.core.impl;

import com.bytequay.agent.core.AgentEventOutboxEntry;
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.store.AgentEventOutboxStore;
import com.bytequay.agent.core.store.AgentUnitOfWork;

import static java.util.Objects.requireNonNull;

/**
 * Commits a state change together with the event that announces it, then publishes that event.
 *
 * <p>The outbox row rides in the same unit of work as the change, so a crash between the two
 * cannot lose the event; draining it right after is what makes the change visible.
 */
final class AgentEventCommitter
{
    private final AgentUnitOfWork unitOfWork;
    private final AgentEventOutboxStore outboxStore;
    private final AgentEventService eventService;

    AgentEventCommitter(AgentUnitOfWork unitOfWork, AgentEventOutboxStore outboxStore, AgentEventService eventService)
    {
        this.unitOfWork = requireNonNull(unitOfWork, "unitOfWork is null");
        this.outboxStore = requireNonNull(outboxStore, "outboxStore is null");
        this.eventService = requireNonNull(eventService, "eventService is null");
    }

    /** Runs {@code work} and inserts {@code event} in one unit of work, then drains the event. */
    <T> T commit(AgentEventOutboxEntry event, AgentUnitOfWork.Work<T> work)
    {
        requireNonNull(event, "event is null");
        requireNonNull(work, "work is null");
        T result = unitOfWork.execute(() -> {
            T value = work.run();
            outboxStore.insert(event);
            return value;
        });
        eventService.drain(event.eventId());
        return result;
    }

    /** Publishes an event that announces no state change of its own. */
    void publish(AgentEventOutboxEntry event)
    {
        commit(event, () -> null);
    }
}
