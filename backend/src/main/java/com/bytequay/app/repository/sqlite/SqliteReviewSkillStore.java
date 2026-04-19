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
package com.bytequay.app.repository.sqlite;

import com.bytequay.app.domain.ReviewSkill;
import com.bytequay.app.repository.ReviewSkillStore;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteReviewSkillStore
        implements ReviewSkillStore
{
    private final ReviewSkillJpaRepository repo;

    public SqliteReviewSkillStore(ReviewSkillJpaRepository repo)
    {
        this.repo = requireNonNull(repo, "repo is null");
    }

    @Override
    public List<ReviewSkill> list()
    {
        return repo.findAllByOrderBySkillNameAsc().stream()
                .map(SqliteReviewSkillStore::toDomain)
                .collect(toImmutableList());
    }

    @Override
    public Optional<ReviewSkill> byId(long id)
    {
        return repo.findById(id).map(SqliteReviewSkillStore::toDomain);
    }

    @Override
    public Optional<ReviewSkill> findByRepo(String repo)
    {
        if (repo == null || repo.isBlank()) {
            return Optional.empty();
        }
        return this.repo.findByRepo(repo).map(SqliteReviewSkillStore::toDomain);
    }

    @Override
    @Transactional
    public ReviewSkill create(
            String skillName,
            String repo,
            String llmProvider,
            String description,
            String context)
    {
        ReviewSkillEntity e = new ReviewSkillEntity();
        e.setSkillName(skillName);
        e.setRepo(repo);
        e.setLlmProvider(blankToNull(llmProvider));
        e.setDescription(description);
        e.setContext(context);
        try {
            return toDomain(this.repo.save(e));
        }
        catch (DataIntegrityViolationException ex) {
            // Translates the SQLite UNIQUE-constraint failure into a
            // domain-level signal so the service layer can map it to a
            // user-facing 409 error.
            throw new IllegalStateException(
                    "skill name '" + skillName + "' or repo '" + repo + "' already exists", ex);
        }
    }

    @Override
    @Transactional
    public ReviewSkill update(
            long id,
            String skillName,
            String repo,
            String llmProvider,
            String description,
            String context)
    {
        ReviewSkillEntity e = this.repo.findById(id)
                .orElseThrow(() -> new IllegalStateException("skill " + id + " not found"));
        e.setSkillName(skillName);
        e.setRepo(repo);
        e.setLlmProvider(blankToNull(llmProvider));
        e.setDescription(description);
        e.setContext(context);
        try {
            return toDomain(this.repo.save(e));
        }
        catch (DataIntegrityViolationException ex) {
            throw new IllegalStateException(
                    "skill name '" + skillName + "' or repo '" + repo + "' already exists", ex);
        }
    }

    @Override
    @Transactional
    public void delete(long id)
    {
        this.repo.deleteById(id);
    }

    private static String blankToNull(String s)
    {
        return s == null || s.isBlank() ? null : s.strip();
    }

    private static ReviewSkill toDomain(ReviewSkillEntity e)
    {
        return new ReviewSkill(
                e.getId(),
                e.getSkillName(),
                e.getRepo(),
                e.getLlmProvider(),
                e.getDescription(),
                e.getContext(),
                e.getCreatedAt(),
                e.getUpdatedAt());
    }
}
