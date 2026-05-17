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

import com.bytequay.app.domain.EmailTag;
import com.bytequay.app.repository.EmailTagStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Component
class SqliteEmailTagStore
        implements EmailTagStore
{
    private final EmailTagJpaRepository repo;

    SqliteEmailTagStore(EmailTagJpaRepository repo)
    {
        this.repo = requireNonNull(repo, "repo is null");
    }

    @Override
    @Transactional
    public void save(EmailTag tag)
    {
        EmailTagEntity entity = repo.findById(tag.id()).orElseGet(EmailTagEntity::new);
        entity.setId(tag.id());
        entity.setAccountEmail(tag.accountEmail());
        entity.setName(tag.name());
        entity.setSubjectContains(tag.subjectContains());
        entity.setAction(tag.action().name());
        entity.setCreatedAtMs(tag.createdAt().toEpochMilli());
        entity.setUpdatedAtMs(tag.updatedAt().toEpochMilli());
        repo.save(entity);
    }

    @Override
    public Optional<EmailTag> findById(String id)
    {
        return repo.findById(id).map(SqliteEmailTagStore::toDomain);
    }

    @Override
    public List<EmailTag> listByAccount(String accountEmail)
    {
        return repo.findByAccountEmail(accountEmail).stream()
                .map(SqliteEmailTagStore::toDomain)
                .toList();
    }

    @Override
    @Transactional
    public void deleteById(String id)
    {
        repo.deleteById(id);
    }

    private static EmailTag toDomain(EmailTagEntity e)
    {
        return new EmailTag(
                e.getId(),
                e.getAccountEmail(),
                e.getName(),
                e.getSubjectContains(),
                EmailTag.Action.valueOf(e.getAction()),
                Instant.ofEpochMilli(e.getCreatedAtMs()),
                Instant.ofEpochMilli(e.getUpdatedAtMs()));
    }
}
