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
package com.bytequay.app.repository;

import com.bytequay.app.domain.Skill;

import java.util.List;
import java.util.Optional;

public interface SkillStore
{
    /** Every row, sorted by name. The Settings → Skills UI consumes this
     *  directly and slices on scope client-side. */
    List<Skill> list();

    /** Lookup by primary key. */
    Optional<Skill> byId(long id);

    /** Lookup by unique name. Used by {@code load_skill} at runtime. */
    Optional<Skill> byName(String name);

    /** All enabled global rows. */
    List<Skill> findGlobal();

    /** All enabled rows targeting {@code repo} (scope = 'repo'). Default-marked
     *  rows come first so callers that want a single match can take the head. */
    List<Skill> findByRepo(String repo);

    /** Convenience for the review path: the highest-priority enabled rubric
     *  for {@code repo}, or empty when none exists. */
    Optional<Skill> findRubricForRepo(String repo);

    /** Create a row. Throws IllegalStateException on a unique-constraint
     *  collision (the name column is unique across the table). */
    Skill create(
            String scope,
            String repo,
            String threadId,
            String name,
            String description,
            String body,
            String kind,
            String usage,
            String roleTag,
            boolean isDefault,
            String source,
            String provenance);

    /** Update the editable fields on an existing row. */
    Skill update(
            long id,
            String scope,
            String repo,
            String threadId,
            String name,
            String description,
            String body,
            String kind,
            String usage,
            String roleTag,
            boolean isDefault);

    /** Hard-delete. No-op when the id doesn't exist. */
    void delete(long id);

    /** Flip the enable flag; raises IllegalStateException when the id is
     *  missing so the controller can map to 404. */
    Skill setEnabled(long id, boolean enabled);
}
