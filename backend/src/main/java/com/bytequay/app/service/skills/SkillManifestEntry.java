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
 * Catalog projection used by ByteQuay's selector and settings UI. The body is
 * fetched only after ByteQuay selects an entry for a bounded turn context.
 *
 * @param id          primary key used by settings and cache invalidation
 * @param name        unique skill name
 * @param description the trigger ByteQuay's selector matches on
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
