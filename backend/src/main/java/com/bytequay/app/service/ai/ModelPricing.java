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
package com.bytequay.app.service.ai;

import java.util.Map;

/**
 * Per-model token pricing shared by the logic-loop thread agent and the
 * review orchestrator. Costs are tracked in milli-USD (USD × 1000) so an
 * integer column can hold a $0.50 budget without floating-point drift.
 *
 * <p>Unknown model ids fall back to Sonnet-class pricing so a freshly
 * released model never meters at zero cost and silently bypasses a budget
 * cap.
 */
public final class ModelPricing
{
    private ModelPricing() {}

    /** USD-per-million-tokens for a model's input and output sides. */
    public record ModelPrice(double usdPerMillionIn, double usdPerMillionOut)
    {
        public long computeCostMilli(long tokensIn, long tokensOut)
        {
            double costUsd = tokensIn * usdPerMillionIn / 1_000_000.0
                    + tokensOut * usdPerMillionOut / 1_000_000.0;
            return Math.round(costUsd * 1000.0);
        }
    }

    /** Sonnet-class pricing for unrecognised model ids — never zero. */
    private static final ModelPrice FALLBACK = new ModelPrice(3.0, 15.0);

    private static final Map<String, ModelPrice> MODEL_PRICES = Map.ofEntries(
            Map.entry("claude-opus-4-8", new ModelPrice(15.0, 75.0)),
            Map.entry("claude-opus-4-7", new ModelPrice(15.0, 75.0)),
            Map.entry("claude-sonnet-4-6", new ModelPrice(3.0, 15.0)),
            Map.entry("claude-haiku-4-5", new ModelPrice(0.8, 4.0)),
            Map.entry("gpt-5", new ModelPrice(10.0, 40.0)),
            Map.entry("gpt-5-mini", new ModelPrice(1.25, 5.0)),
            Map.entry("gpt-4o", new ModelPrice(2.5, 10.0)),
            Map.entry("gpt-4o-mini", new ModelPrice(0.15, 0.6)),
            Map.entry("deepseek-chat", new ModelPrice(0.27, 1.1)),
            Map.entry("deepseek-reasoner", new ModelPrice(0.55, 2.19)));

    /**
     * Cost in milli-USD for a call on {@code modelId}. Unrecognised ids
     * use Sonnet-class pricing so cost is never silently zero.
     */
    public static long estimateCostMilli(String modelId, long tokensIn, long tokensOut)
    {
        return MODEL_PRICES.getOrDefault(modelId, FALLBACK).computeCostMilli(tokensIn, tokensOut);
    }
}
