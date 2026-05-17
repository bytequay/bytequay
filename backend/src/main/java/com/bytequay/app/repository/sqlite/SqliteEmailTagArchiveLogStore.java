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

import com.bytequay.app.domain.EmailTagArchiveEntry;
import com.bytequay.app.repository.EmailTagArchiveLogStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static java.util.Objects.requireNonNull;

@Component
class SqliteEmailTagArchiveLogStore
        implements EmailTagArchiveLogStore
{
    private final EmailTagArchiveLogJpaRepository repo;

    SqliteEmailTagArchiveLogStore(EmailTagArchiveLogJpaRepository repo)
    {
        this.repo = requireNonNull(repo, "repo is null");
    }

    @Override
    @Transactional
    public void save(EmailTagArchiveEntry entry)
    {
        EmailTagArchiveLogEntity.EmailTagArchiveLogKey key =
                new EmailTagArchiveLogEntity.EmailTagArchiveLogKey(entry.accountEmail(), entry.gmailThreadId());
        EmailTagArchiveLogEntity entity = repo.findById(key).orElseGet(EmailTagArchiveLogEntity::new);
        entity.setId(key);
        entity.setTagId(entry.tagId());
        entity.setSubject(entry.subject());
        entity.setFromAddr(entry.fromAddr());
        entity.setSnippet(entry.snippet());
        entity.setReceivedAtMs(entry.receivedAt().toEpochMilli());
        entity.setArchivedAtMs(entry.archivedAt().toEpochMilli());
        repo.save(entity);
    }

    @Override
    @Transactional
    public void delete(String accountEmail, String gmailThreadId)
    {
        repo.deleteById(new EmailTagArchiveLogEntity.EmailTagArchiveLogKey(accountEmail, gmailThreadId));
    }

    @Override
    public List<EmailTagArchiveEntry> listByAccount(String accountEmail)
    {
        return repo.findByIdAccountEmailOrderByArchivedAtMsDesc(accountEmail).stream()
                .map(SqliteEmailTagArchiveLogStore::toDomain)
                .toList();
    }

    private static EmailTagArchiveEntry toDomain(EmailTagArchiveLogEntity e)
    {
        return new EmailTagArchiveEntry(
                e.getId().getAccountEmail(),
                e.getId().getGmailThreadId(),
                e.getTagId(),
                e.getSubject(),
                e.getFromAddr(),
                e.getSnippet(),
                Instant.ofEpochMilli(e.getReceivedAtMs()),
                Instant.ofEpochMilli(e.getArchivedAtMs()));
    }
}
