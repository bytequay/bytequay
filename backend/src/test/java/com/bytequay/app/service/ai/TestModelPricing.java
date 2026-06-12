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

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestModelPricing
{
    @Test
    void knownModelPricesInputAndOutputTokens()
    {
        // claude-opus-4-7 is $15/M in, $75/M out. 1M each → $90.00 → 90000 milli-USD.
        assertThat(ModelPricing.estimateCostMilli("claude-opus-4-7", 1_000_000, 1_000_000))
                .isEqualTo(90_000L);
        // gpt-4o-mini is $0.15/M in, $0.60/M out. 2M in + 1M out → $0.30 + $0.60 = $0.90.
        assertThat(ModelPricing.estimateCostMilli("gpt-4o-mini", 2_000_000, 1_000_000))
                .isEqualTo(900L);
    }

    @Test
    void unknownModelFallsBackToSonnetClassPricingNeverZero()
    {
        // Fallback is $3/M in, $15/M out (same as claude-sonnet-4-6).
        long fallback = ModelPricing.estimateCostMilli("made-up-model-v9", 1_000_000, 1_000_000);
        long sonnet = ModelPricing.estimateCostMilli("claude-sonnet-4-6", 1_000_000, 1_000_000);
        assertThat(fallback).isEqualTo(sonnet).isEqualTo(18_000L);
    }

    @Test
    void smallUsageRoundsToNearestMilliUsd()
    {
        // Tiny call: 100 in + 50 out on gpt-4o-mini rounds to 0 milli-USD.
        assertThat(ModelPricing.estimateCostMilli("gpt-4o-mini", 100, 50)).isZero();
    }
}
