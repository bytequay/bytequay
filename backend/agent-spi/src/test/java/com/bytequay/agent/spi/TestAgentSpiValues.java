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
package com.bytequay.agent.spi;

import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestAgentSpiValues
{
    @Test
    void laneAndSessionReferencesAreStableAndValidated()
    {
        AgentSessionRef reference = new AgentSessionRef(AgentLaneId.CODEX_CLI, 1, "thread-123");

        assertThat(reference.laneId()).isEqualTo(new AgentLaneId("codex-cli"));
        assertThat(reference.providerValue()).isEqualTo("thread-123");
        assertThatThrownBy(() -> new AgentLaneId("Codex CLI"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AgentSessionRef(AgentLaneId.CODEX_CLI, 0, "thread-123"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void capabilitiesCopyCallerOwnedCollections()
    {
        Set<String> mediaTypes = Sets.newHashSet("text/plain");
        Set<AgentPermissionPolicy> permissionPolicies = Sets.newHashSet(AgentPermissionPolicy.AUTO);
        AgentCapabilities capabilities = new AgentCapabilities(
                true,
                true,
                true,
                true,
                true,
                false,
                true,
                true,
                true,
                CostProvenance.UNAVAILABLE,
                permissionPolicies,
                mediaTypes);

        mediaTypes.add("image/png");
        permissionPolicies.add(AgentPermissionPolicy.CI_AUTOFIX);

        assertThat(capabilities.permissionPolicies()).containsExactly(AgentPermissionPolicy.AUTO);
        assertThat(capabilities.attachmentMediaTypes()).isInstanceOf(ImmutableSet.class);
        assertThat(capabilities.attachmentMediaTypes()).containsExactly("text/plain");
        assertThatThrownBy(() -> capabilities.attachmentMediaTypes().add("image/png"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void missingCostIsUnavailableRatherThanZero()
    {
        ProviderUsageSample sample = new ProviderUsageSample(
                Optional.of("usage-1"),
                ProviderUsageSample.Semantics.CUMULATIVE,
                10,
                2,
                4,
                Optional.empty(),
                CostProvenance.UNAVAILABLE,
                Instant.parse("2026-08-16T00:00:00Z"));

        assertThat(sample.cost()).isEmpty();
        assertThatThrownBy(() -> new ProviderUsageSample(
                Optional.empty(),
                ProviderUsageSample.Semantics.DELTA,
                0,
                0,
                0,
                Optional.of(BigDecimal.ZERO),
                CostProvenance.UNAVAILABLE,
                Instant.parse("2026-08-16T00:00:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("disagree");
    }

    @Test
    void providerEventsRejectEscapingFilePaths()
    {
        assertThatThrownBy(() -> new ProviderAgentEvent.FileChange(
                Optional.empty(),
                Optional.empty(),
                "../secret",
                ProviderAgentEvent.ChangeKind.MODIFIED,
                Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsafe");
    }

    @Test
    void failedProviderResultsRequireAReason()
    {
        assertThatThrownBy(() -> new AgentTurnResult(
                AgentTurnResult.State.FAILED,
                Optional.empty(),
                Optional.empty(),
                Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requires");
    }
}
