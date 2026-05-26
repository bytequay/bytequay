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
package com.bytequay.app.service.skills;

/**
 * Projection of one skill row as it appears in the {@code list_skills}
 * manifest the model browses at runtime. Carries only the fields the
 * model needs to decide whether to load the body — the body itself
 * lands in history as the {@code load_skill} tool result.
 *
 * <p>The order of fields here is part of the manifest's wire shape and
 * therefore part of the cached prefix once the manifest lands in a
 * tool result. Keep it stable.
 *
 * @param id          primary key — used as the cache key in the
 *                    runtime so subsequent {@code load_skill} calls
 *                    can cross-reference the row the manifest pointed
 *                    at, even if the user renames it
 * @param name        unique skill name — the model passes this to
 *                    {@code load_skill}
 * @param description the "loads when …" trigger the model matches on
 * @param scope       one of 'global' / 'repo' / 'thread'
 * @param repo        owner/name when {@code scope='repo'}; null otherwise
 * @param roleTag     role this skill targets, or null
 * @param kind        one of 'library' / 'persona' / 'rubric'
 */
public record SkillManifestEntry(
        long id,
        String name,
        String description,
        String scope,
        String repo,
        String roleTag,
        String kind) {}
