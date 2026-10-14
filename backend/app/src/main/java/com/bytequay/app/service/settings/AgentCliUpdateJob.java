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
package com.bytequay.app.service.settings;

import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentProviderStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.List;

import static java.util.Objects.requireNonNull;

/**
 * Startup maintenance for the CLI providers: seeds the probe-free model
 * catalogs, applies each detected CLI's update policy, and then re-probes the
 * catalogs so they reflect the installed version. Runs off the startup thread;
 * a failed update leaves readiness to the driver's version and authentication checks.
 */
@Component
public class AgentCliUpdateJob
{
    private static final Logger log = LoggerFactory.getLogger(AgentCliUpdateJob.class);

    private final AgentDriverRegistry drivers;
    private final ModelSettingsService models;

    public AgentCliUpdateJob(AgentDriverRegistry drivers, ModelSettingsService models)
    {
        this.drivers = requireNonNull(drivers, "drivers is null");
        this.models = requireNonNull(models, "models is null");
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady()
    {
        models.seedCatalogs();
        Thread.ofVirtual().name("bytequay-cli-update").start(this::runUpdates);
    }

    void runUpdates()
    {
        List<AgentProviderStatus> statuses = drivers.statuses();
        models.refreshMissingCatalogs(statuses);
        for (AgentProviderStatus status : statuses) {
            if (!status.available() && status.version().isEmpty()) {
                continue;
            }
            String lane = status.laneId().value();
            if (lane.startsWith("deepseek-key-") || lane.startsWith(AgentLaneId.CLAUDE_ACCOUNT_PREFIX)) {
                // Update each installed binary through its default lane, never once per account or key.
                continue;
            }
            try {
                drivers.require(status.laneId()).update().ifPresent(output -> log.info("{} CLI update: {}", lane, output));
            }
            catch (RuntimeException failure) {
                log.warn("{} CLI update failed: {}", lane, failure.getMessage());
            }
        }
        models.refreshCatalogs();
    }
}
