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
package com.bytequay.app.service.inspector;

import java.time.Instant;

/**
 * Header strip the inspector renders above the section nav. Carries
 * the model that would receive the prompt, the provider shape, the
 * total token estimate, and a flag for whether the prefix-cache
 * predicate is expected to hit (so the user can spot a stale cache
 * mid-debug).
 *
 * @param model           e.g. {@code claude-sonnet-4-6}
 * @param providerShape   {@code ANTHROPIC} or {@code OPENAI}
 * @param assembledAt     wall-clock when the assembler ran
 * @param totalTokens     sum of every section's tokenCount
 * @param cacheHitPredicted heuristic: tools + system blocks > some
 *                          threshold and we're not the first turn
 */
public record ContextMeta(
        String model,
        String providerShape,
        Instant assembledAt,
        int totalTokens,
        boolean cacheHitPredicted) {}
