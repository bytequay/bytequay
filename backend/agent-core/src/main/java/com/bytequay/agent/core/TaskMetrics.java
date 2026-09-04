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
package com.bytequay.agent.core;

import com.bytequay.agent.spi.CostProvenance;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

public record TaskMetrics(
        long inputTokens,
        long cachedInputTokens,
        long outputTokens,
        Optional<BigDecimal> cost,
        CostProvenance costProvenance,
        Duration elapsed)
{
    public TaskMetrics
    {
        requireNonNull(cost, "cost is null");
        requireNonNull(costProvenance, "costProvenance is null");
        requireNonNull(elapsed, "elapsed is null");
        checkArgument(inputTokens >= 0 && cachedInputTokens >= 0 && outputTokens >= 0, "token counts must not be negative");
        checkArgument(cost.stream().noneMatch(value -> value.signum() < 0), "cost must not be negative");
        checkArgument(cost.isEmpty() == (costProvenance == CostProvenance.UNAVAILABLE), "cost and costProvenance disagree");
        checkArgument(!elapsed.isNegative(), "elapsed is negative");
        cost = cost.map(BigDecimal::stripTrailingZeros);
    }

    public static TaskMetrics empty()
    {
        return new TaskMetrics(0, 0, 0, Optional.empty(), CostProvenance.UNAVAILABLE, Duration.ZERO);
    }
}
