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
package com.bytequay.app.domain;

import java.time.Instant;

/**
 * A per-repo review "skill" — additional system-prompt context that the
 * AI reviewer applies when running against a matching repo, optionally
 * locked to a specific LLM provider.
 *
 * @param skillName   user-facing label, unique across all skills
 * @param repo        full {@code owner/name} the skill applies to, unique
 * @param llmProvider provider id (e.g. "claude") that this skill is locked
 *                    to, or null when the skill applies to any provider
 * @param description short blurb shown in the settings list
 * @param context     extra system-prompt content appended to the review
 *                    prompt when this skill matches
 */
public record ReviewSkill(
        long id,
        String skillName,
        String repo,
        String llmProvider,
        String description,
        String context,
        /** When false the row stays in the vault but no
         *  consumer applies it. Today the review prompt only
         *  reads enabled skills; future runtime lanes follow
         *  the same flag. */
        boolean enabled,
        Instant createdAt,
        Instant updatedAt) {}
