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

import com.bytequay.app.domain.BacklogItem;
import com.bytequay.app.repository.BacklogStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
class SqliteBacklogStore
        implements BacklogStore
{
    private final BacklogItemJpaRepository repository;

    SqliteBacklogStore(BacklogItemJpaRepository repository)
    {
        this.repository = repository;
    }

    @Override
    @Transactional
    public BacklogItem save(BacklogItem item)
    {
        BacklogItemEntity entity = new BacklogItemEntity();
        entity.setId(item.id());
        entity.setThreadId(item.threadId());
        entity.setTitle(item.title());
        entity.setBody(item.body());
        entity.setTags(item.tags());
        entity.setCreatedAtMs(item.createdAt().toEpochMilli());
        entity.setStartedAtMs(item.startedAt() == null ? null : item.startedAt().toEpochMilli());
        entity.setLinkedTaskId(item.linkedTaskId());
        return toDomain(repository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public List<BacklogItem> findByThread(String threadId)
    {
        return repository.findByThreadIdOrderByCreatedAtMsAsc(threadId).stream()
                .map(SqliteBacklogStore::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<BacklogItem> findById(String id)
    {
        return repository.findById(id).map(SqliteBacklogStore::toDomain);
    }

    @Override
    @Transactional
    public void delete(String id)
    {
        repository.deleteById(id);
    }

    private static BacklogItem toDomain(BacklogItemEntity e)
    {
        return new BacklogItem(
                e.getId(),
                e.getThreadId(),
                e.getTitle(),
                e.getBody(),
                e.getTags(),
                Instant.ofEpochMilli(e.getCreatedAtMs()),
                e.getStartedAtMs() == null ? null : Instant.ofEpochMilli(e.getStartedAtMs()),
                e.getLinkedTaskId());
    }
}
