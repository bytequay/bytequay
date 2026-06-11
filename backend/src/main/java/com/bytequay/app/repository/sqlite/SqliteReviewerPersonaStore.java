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

import com.bytequay.app.domain.ReviewerPersona;
import com.bytequay.app.domain.ReviewerPersonaRole;
import com.bytequay.app.repository.ReviewerPersonaStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Component
class SqliteReviewerPersonaStore
        implements ReviewerPersonaStore
{
    private final ReviewerPersonaJpaRepository repo;

    SqliteReviewerPersonaStore(ReviewerPersonaJpaRepository repo)
    {
        this.repo = requireNonNull(repo, "repo is null");
    }

    @Override
    @Transactional
    public void save(ReviewerPersona persona)
    {
        ReviewerPersonaEntity entity = repo.findById(persona.id())
                .orElseGet(ReviewerPersonaEntity::new);
        entity.setId(persona.id());
        entity.setName(persona.name());
        entity.setSystemPrompt(persona.systemPrompt());
        entity.setRole(persona.role().name());
        entity.setIsActive(persona.active() ? 1 : 0);
        entity.setCreatedAtMs(persona.createdAt().toEpochMilli());
        entity.setUpdatedAtMs(persona.updatedAt().toEpochMilli());
        repo.save(entity);
    }

    @Override
    public Optional<ReviewerPersona> findById(String id)
    {
        return repo.findById(id).map(SqliteReviewerPersonaStore::toDomain);
    }

    @Override
    public List<ReviewerPersona> listActive()
    {
        return repo.findByIsActiveOrderByNameAsc(1).stream()
                .map(SqliteReviewerPersonaStore::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public void softDelete(String id)
    {
        repo.findById(id).ifPresent(entity -> {
            entity.setIsActive(0);
            entity.setUpdatedAtMs(Instant.now().toEpochMilli());
            repo.save(entity);
        });
    }

    private static ReviewerPersona toDomain(ReviewerPersonaEntity e)
    {
        return new ReviewerPersona(
                e.getId(),
                e.getName(),
                e.getSystemPrompt(),
                ReviewerPersonaRole.valueOf(e.getRole()),
                e.getIsActive() == 1,
                Instant.ofEpochMilli(e.getCreatedAtMs()),
                Instant.ofEpochMilli(e.getUpdatedAtMs()));
    }
}
