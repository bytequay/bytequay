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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static com.bytequay.agent.spi.AgentChecks.checkOptionalText;
import static java.util.Objects.requireNonNull;

public record ProviderUsageSample(
        Optional<String> providerEventId,
        Semantics semantics,
        long inputTokens,
        long cachedInputTokens,
        long outputTokens,
        Optional<BigDecimal> cost,
        CostProvenance costProvenance,
        Instant observedAt)
{
    public ProviderUsageSample
    {
        checkOptionalText(providerEventId, "providerEventId");
        requireNonNull(semantics, "semantics is null");
        requireNonNull(cost, "cost is null");
        requireNonNull(costProvenance, "costProvenance is null");
        requireNonNull(observedAt, "observedAt is null");
        if (inputTokens < 0 || cachedInputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("token counts must not be negative");
        }
        if (cost.stream().anyMatch(value -> value.signum() < 0)) {
            throw new IllegalArgumentException("cost must not be negative");
        }
        if (cost.isEmpty() != (costProvenance == CostProvenance.UNAVAILABLE)) {
            throw new IllegalArgumentException("cost and costProvenance disagree");
        }
    }

    public enum Semantics
    {
        CUMULATIVE,
        DELTA,
        FINAL_FOR_INVOCATION
    }
}
