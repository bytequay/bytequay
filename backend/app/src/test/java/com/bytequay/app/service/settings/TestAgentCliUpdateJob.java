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

import com.bytequay.agent.spi.AgentCapabilities;
import com.bytequay.agent.spi.AgentDriver;
import com.bytequay.agent.spi.AgentDriverRegistry;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentProviderStatus.Health;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestAgentCliUpdateJob
{
    private static final AgentLaneId CLAUDE = new AgentLaneId("claude");
    private static final AgentLaneId CODEX = new AgentLaneId("codex");

    @Test
    void updatesAvailableProvidersAndRefreshesCatalogsAfterwards()
    {
        AgentDriverRegistry drivers = mock(AgentDriverRegistry.class);
        when(drivers.statuses()).thenReturn(ImmutableList.of(
                status(CLAUDE, Health.AVAILABLE),
                status(CODEX, Health.AVAILABLE)));
        AgentDriver claude = mock(AgentDriver.class);
        when(claude.update()).thenReturn(Optional.of("Updated to 2.2.0"));
        when(drivers.require(CLAUDE)).thenReturn(claude);
        AgentDriver codex = mock(AgentDriver.class);
        when(codex.update()).thenReturn(Optional.of("Updated to 0.152.0"));
        when(drivers.require(CODEX)).thenReturn(codex);
        ModelSettingsService models = mock(ModelSettingsService.class);

        new AgentCliUpdateJob(drivers, models).runUpdates();

        var order = inOrder(claude, codex, models);
        order.verify(claude).update();
        order.verify(codex).update();
        order.verify(models).refreshCatalogs();
    }

    @Test
    void updateFailureStillRefreshesCatalogs()
    {
        AgentDriverRegistry drivers = mock(AgentDriverRegistry.class);
        when(drivers.statuses()).thenReturn(ImmutableList.of(
                status(CLAUDE, Health.AVAILABLE),
                status(CODEX, Health.AVAILABLE)));
        AgentDriver claude = mock(AgentDriver.class);
        when(claude.update()).thenThrow(new AgentUnavailableException("update failed"));
        when(drivers.require(CLAUDE)).thenReturn(claude);
        AgentDriver codex = mock(AgentDriver.class);
        when(drivers.require(CODEX)).thenReturn(codex);
        ModelSettingsService models = mock(ModelSettingsService.class);

        new AgentCliUpdateJob(drivers, models).runUpdates();

        verify(codex).update();
        verify(models).refreshCatalogs();
    }

    private static AgentProviderStatus status(AgentLaneId lane, Health health)
    {
        return new AgentProviderStatus(
                lane, health, Optional.of("1.0.0"), "ready", AgentCapabilities.none());
    }
}
