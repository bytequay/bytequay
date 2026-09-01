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

import com.bytequay.agent.cli.process.OwnedProcessRecovery;
import com.bytequay.agent.cli.process.ProcessSupervisor;
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
    private final ProcessSupervisor supervisor;
    private final AgentServingGate servingGate;

    public AgentStartupRecovery(
            AgentEventOutboxStore outboxStore,
            AgentEventService eventService,
            AgentMaintenance maintenance,
            ProcessSupervisor supervisor,
            AgentServingGate servingGate)
    {
        this.outboxStore = requireNonNull(outboxStore, "outboxStore is null");
        this.eventService = requireNonNull(eventService, "eventService is null");
        this.maintenance = requireNonNull(maintenance, "maintenance is null");
        this.supervisor = requireNonNull(supervisor, "supervisor is null");
        this.servingGate = requireNonNull(servingGate, "servingGate is null");
    }

    @Override
    public void afterSingletonsInstantiated()
    {
        try {
            drainPendingEvents();
            maintenance.recover();
            drainPendingEvents();
            if (!recoverOwnedProcesses()) {
                log.error("Agent operations remain unavailable: an owned provider process could not be "
                        + "verified stopped, so a worktree may still have a live writer");
                return;
            }
            servingGate.open();
        }
        catch (RuntimeException failure) {
            log.error("Agent startup recovery failed; agent operations remain unavailable", failure);
        }
    }

    /**
     * Stops provider process trees left by an earlier backend instance.
     *
     * <p>Until a durable owner row links a launch to one task claim, an unverifiable tree blocks
     * every agent operation rather than one task. Its launch marker is retained for inspection and
     * is never acknowledged, so the block survives a restart until the process is resolved.
     *
     * @return true when every recovered launch is proven unlaunched or verified stopped
     */
    private boolean recoverOwnedProcesses()
    {
        boolean quiescent = true;
        for (OwnedProcessRecovery recovery : supervisor.recoverOwnedProcesses()) {
            if (recovery.outcome() == OwnedProcessRecovery.Outcome.UNVERIFIABLE) {
                log.error("Owned provider process {} owned by {} is unverifiable: {}",
                        recovery.launchId(), recovery.owner().orElse(null), recovery.detail());
                quiescent = false;
                continue;
            }
            log.info("Owned provider process {} recovered as {}: {}",
                    recovery.launchId(), recovery.outcome(), recovery.detail());
            supervisor.acknowledge(recovery.launchId());
        }
        return quiescent;
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
