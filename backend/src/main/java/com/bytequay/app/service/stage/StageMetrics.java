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
package com.bytequay.app.service.stage;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Typed view of the monitor-stage {@code metrics_json} blob. Only the
 * fields the budget machinery needs are modelled; unknown keys (e.g.
 * future {@code lastPolledAt}, {@code consecutiveMutexSkips}) are ignored
 * on read so the schema can grow without breaking deserialisation.
 *
 * @param autoPushBudget null for stages without a budget (review-monitor by default)
 * @param internalReviewEnabled true when every push is user-gated
 * @param budgetExhausted true once the budget hit zero, until the user resolves
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record StageMetrics(
        AutoPushBudget autoPushBudget,
        boolean internalReviewEnabled,
        boolean budgetExhausted)
{
    /** An empty metrics object — no budget, autonomous pushes, not exhausted. */
    public static StageMetrics empty()
    {
        return new StageMetrics(null, false, false);
    }

    public StageMetrics withBudget(AutoPushBudget budget)
    {
        return new StageMetrics(budget, internalReviewEnabled, budgetExhausted);
    }

    public StageMetrics withInternalReviewEnabled(boolean enabled)
    {
        return new StageMetrics(autoPushBudget, enabled, budgetExhausted);
    }

    public StageMetrics withBudgetExhausted(boolean exhausted)
    {
        return new StageMetrics(autoPushBudget, internalReviewEnabled, exhausted);
    }

    /**
     * Per-stage-instance autonomous-push allowance. Each new
     * {@code CiFixingStage} starts fresh; on exhaustion the stage falls
     * back to user-gated pushes.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AutoPushBudget(int limit, int used, int remaining)
    {
        public static AutoPushBudget fresh(int limit)
        {
            return new AutoPushBudget(limit, 0, limit);
        }

        public AutoPushBudget decremented()
        {
            int nextUsed = used + 1;
            return new AutoPushBudget(limit, nextUsed, Math.max(0, limit - nextUsed));
        }

        public AutoPushBudget extendedBy(int additional)
        {
            int nextLimit = limit + additional;
            return new AutoPushBudget(nextLimit, used, Math.max(0, nextLimit - used));
        }

        public boolean exhausted()
        {
            return remaining <= 0;
        }
    }
}
