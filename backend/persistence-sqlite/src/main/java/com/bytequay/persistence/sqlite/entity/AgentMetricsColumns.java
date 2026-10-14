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
package com.bytequay.persistence.sqlite.entity;

import com.bytequay.agent.core.AgentMetrics;
import com.bytequay.agent.core.ToolCounters;
import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;

/** The same metrics columns belong to both a task and each of its turns. */
@Embeddable
public record AgentMetricsColumns(
        @Column(nullable = false) long inputTokens,
        @Column(nullable = false) long cachedInputTokens,
        @Column(nullable = false) long outputTokens,
        BigDecimal cost,
        @Column(nullable = false) long elapsedMillis,
        // Nullable so rows written before these counters read as zero.
        @Column(name = "tool_calls") Long toolCalls,
        @Column(name = "explore_actions") Long exploreActions,
        @Column(name = "context_compactions") Long contextCompactions)
{
    AgentMetricsColumns(AgentMetrics metrics)
    {
        this(metrics.inputTokens(), metrics.cachedInputTokens(), metrics.outputTokens(), metrics.cost().orElse(null), metrics.elapsed().toMillis(),
                metrics.counters().toolCalls(), metrics.counters().exploreActions(), metrics.counters().compactions());
    }

    AgentMetrics toDomain()
    {
        return new AgentMetrics(inputTokens, cachedInputTokens, outputTokens, Optional.ofNullable(cost), Duration.ofMillis(elapsedMillis),
                new ToolCounters(zeroIfNull(toolCalls), zeroIfNull(exploreActions), zeroIfNull(contextCompactions)));
    }

    private static long zeroIfNull(Long value)
    {
        return value == null ? 0 : value;
    }
}
