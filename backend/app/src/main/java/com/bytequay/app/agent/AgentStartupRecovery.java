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

import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.impl.AgentMaintenance;
import com.bytequay.agent.core.store.AgentEventOutboxStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;

import static java.util.Objects.requireNonNull;

@Component
public final class AgentStartupRecovery
        implements SmartInitializingSingleton
{
    private static final Logger log = LoggerFactory.getLogger(AgentStartupRecovery.class);
    private static final int OUTBOX_BATCH_SIZE = 100;

    private final AgentEventOutboxStore outboxStore;
    private final AgentEventService eventService;
    private final AgentMaintenance maintenance;
    private final AgentServingGate servingGate;

    public AgentStartupRecovery(
            AgentEventOutboxStore outboxStore,
            AgentEventService eventService,
            AgentMaintenance maintenance,
            AgentServingGate servingGate)
    {
        this.outboxStore = requireNonNull(outboxStore, "outboxStore is null");
        this.eventService = requireNonNull(eventService, "eventService is null");
        this.maintenance = requireNonNull(maintenance, "maintenance is null");
        this.servingGate = requireNonNull(servingGate, "servingGate is null");
    }

    @Override
    public void afterSingletonsInstantiated()
    {
        try {
            drainPendingEvents();
            maintenance.recover();
            drainPendingEvents();
            servingGate.open();
        }
        catch (RuntimeException failure) {
            log.error("Agent startup recovery failed; agent operations remain unavailable", failure);
        }
    }

    private void drainPendingEvents()
    {
        while (true) {
            var pending = outboxStore.listPending(OUTBOX_BATCH_SIZE);
            if (pending.isEmpty()) {
                return;
            }
            pending.forEach(entry -> eventService.drain(entry.eventId()));
        }
    }
}
