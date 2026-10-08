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
package com.bytequay.agent.cli.codex;

import com.bytequay.agent.spi.ProviderQuotaWindow;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class TestCodexQuota
{
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void prefersBucketsAndUsesActualDurationRatherThanPrimaryPosition()
            throws Exception
    {
        var quota = CodexQuota.parse(mapper.readTree("""
                {"rateLimits":{"primary":{"usedPercent":99,"windowDurationMins":300}},
                 "rateLimitsByLimitId":{"codex":{"planType":"pro","primary":{"usedPercent":49,"windowDurationMins":10080,"resetsAt":1791954533},"secondary":null}}}
                """));
        assertThat(quota.plan()).contains("pro");
        assertThat(quota.windows()).hasSize(1);
        ProviderQuotaWindow window = quota.windows().getFirst();
        assertThat(window.label()).isEqualTo("Weekly");
        assertThat(window.usedPercent()).isEqualTo(49);
        assertThat(Duration.between(window.startsAt().orElseThrow(), window.resetsAt().orElseThrow())).isEqualTo(Duration.ofDays(7));
    }

    @Test
    void preservesZeroAndOverageButDoesNotTurnMissingValuesIntoZero()
            throws Exception
    {
        var quota = CodexQuota.parse(mapper.readTree("""
                {"rateLimits":{"primary":{"usedPercent":0,"windowDurationMins":300},"secondary":{"usedPercent":120}}}
                """));
        assertThat(quota.windows()).extracting(ProviderQuotaWindow::usedPercent).containsExactly(0.0, 120.0);
        assertThat(quota.windows()).allSatisfy(window -> assertThat(window.startsAt()).isEmpty());
        assertThat(CodexQuota.parse(mapper.readTree("{\"rateLimits\":{\"primary\":{}}}")).windows()).isEmpty();
    }
}
