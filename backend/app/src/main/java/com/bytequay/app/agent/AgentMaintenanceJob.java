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

import com.bytequay.agent.core.CleanupState;
import com.bytequay.agent.core.impl.AgentMaintenance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import static java.util.Objects.requireNonNull;

@Component
public final class AgentMaintenanceJob
{
    private static final Logger log = LoggerFactory.getLogger(AgentMaintenanceJob.class);
    private static final int CLEANUP_BATCH_SIZE = 100;

    private final AgentMaintenance maintenance;
    private final AgentServingGate servingGate;

    public AgentMaintenanceJob(AgentMaintenance maintenance, AgentServingGate servingGate)
    {
        this.maintenance = requireNonNull(maintenance, "maintenance is null");
        this.servingGate = requireNonNull(servingGate, "servingGate is null");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void cleanupAfterStartup()
    {
        cleanupDue();
    }

    @Scheduled(fixedDelayString = "${bytequay.agent.cleanup-interval:PT1H}")
    public void cleanupDue()
    {
        if (!servingGate.isReady()) {
            return;
        }
        maintenance.cleanupDue(CLEANUP_BATCH_SIZE).stream()
                .filter(result -> result.state() == CleanupState.RETRY_PENDING)
                .forEach(result -> log.warn(
                        "Agent runtime cleanup remains pending for task {}: {}",
                        result.taskId(),
                        result.failure().orElse("unknown failure")));
    }
}
