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

import com.bytequay.agent.spi.AgentCapabilities;
import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.collect.Maps.uniqueIndex;
import static java.util.Objects.requireNonNull;

/**
 * Immutable registry of the provider drivers configured for one backend process.
 */
public final class StaticAgentDriverRegistry
        implements AgentDriverRegistry
{
    private final Map<AgentLaneId, AgentDriver> driversByLane;

    public StaticAgentDriverRegistry(List<AgentDriver> drivers)
    {
        requireNonNull(drivers, "drivers is null");
        this.driversByLane = uniqueIndex(drivers, AgentDriver::id);
    }

    @Override
    public AgentDriver require(AgentLaneId laneId)
    {
        requireNonNull(laneId, "laneId is null");
        AgentDriver driver = driversByLane.get(laneId);
        if (driver == null) {
            throw new AgentUnavailableException("Agent lane is not configured: " + laneId);
        }
        return driver;
    }

    @Override
    public List<AgentProviderStatus> statuses()
    {
        ImmutableList.Builder<AgentProviderStatus> statuses = ImmutableList.builderWithExpectedSize(driversByLane.size());
        driversByLane.values().forEach(driver -> statuses.add(probe(driver)));
        return statuses.build();
    }

    private static AgentProviderStatus probe(AgentDriver driver)
    {
        try {
            return driver.probe();
        }
        catch (RuntimeException e) {
            String message = e.getMessage();
            if (message == null || message.isBlank()) {
                message = e.getClass().getSimpleName();
            }
            return new AgentProviderStatus(
                    driver.id(),
                    AgentProviderStatus.Health.UNAVAILABLE,
                    Optional.empty(),
                    "Provider probe failed: " + message,
                    AgentCapabilities.none());
        }
    }
}
