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
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentProviderStatus;
import com.bytequay.agent.spi.AgentUnavailableException;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestStaticAgentDriverRegistry
{
    @Test
    void resolvesConfiguredDriversAndContainsProbeFailures()
    {
        DeterministicAgentDriver driver = new DeterministicAgentDriver();
        StaticAgentDriverRegistry registry = new StaticAgentDriverRegistry(ImmutableList.of(driver));

        assertThat(registry.require(DeterministicAgentDriver.LANE_ID)).isSameAs(driver);
        assertThat(registry.statuses()).singleElement().satisfies(status -> assertThat(status.health()).isEqualTo(AgentProviderStatus.Health.AVAILABLE));

        driver.failProbe(new IllegalStateException("probe broke"));
        assertThat(registry.statuses()).singleElement().satisfies(status -> {
            assertThat(status.health()).isEqualTo(AgentProviderStatus.Health.UNAVAILABLE);
            assertThat(status.diagnostic()).contains("probe broke");
        });
        assertThatThrownBy(() -> registry.require(new AgentLaneId("missing")))
                .isInstanceOf(AgentUnavailableException.class)
                .hasMessageContaining("missing");
    }

    @Test
    void rejectsDuplicateLaneIdentifiers()
    {
        assertThatThrownBy(() -> new StaticAgentDriverRegistry(
                ImmutableList.of(new DeterministicAgentDriver(), new DeterministicAgentDriver())))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
