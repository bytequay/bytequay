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

import com.bytequay.app.domain.ReviewSkill;

import java.util.List;
import java.util.Optional;

public interface ReviewSkillStore
{
    /** All skills, sorted by skill_name. */
    List<ReviewSkill> list();

    /** Lookup by primary key. */
    Optional<ReviewSkill> byId(long id);

    /** Lookup by full {@code owner/name} repo string — used at review-time
     *  to apply the matching skill. Returns empty when no skill targets
     *  the repo. */
    Optional<ReviewSkill> findByRepo(String repo);

    /** Insert a new skill. Throws IllegalStateException when skill_name or
     *  repo already exists. */
    ReviewSkill create(
            String skillName,
            String repo,
            String llmProvider,
            String description,
            String context);

    /** Updates the four mutable fields. skill_name and repo must remain
     *  unique across the table. */
    ReviewSkill update(
            long id,
            String skillName,
            String repo,
            String llmProvider,
            String description,
            String context);

    /** Hard-delete. No-op when the id doesn't exist. */
    void delete(long id);
}
