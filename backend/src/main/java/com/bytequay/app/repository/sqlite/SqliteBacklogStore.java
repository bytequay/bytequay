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
        entity.setStartedAtMs(epochOrNull(item.startedAt()));
        entity.setLinkedTaskId(item.linkedTaskId());
        entity.setWorkspaceId(item.workspaceId());
        entity.setPriority(item.priority());
        entity.setSource(item.source());
        entity.setStatus(item.status());
        entity.setCreatedBy(item.createdBy());
        entity.setInProgressAtMs(epochOrNull(item.inProgressAt()));
        entity.setResolvedAtMs(epochOrNull(item.resolvedAt()));
        entity.setRejectedAtMs(epochOrNull(item.rejectedAt()));
        entity.setRejectionReason(item.rejectionReason());
        entity.setRelatedBacklogIds(item.relatedBacklogIds());
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
    public List<BacklogItem> findByWorkspace(String workspaceId)
    {
        return repository.findByWorkspaceIdOrderByCreatedAtMsDesc(workspaceId).stream()
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
                e.getWorkspaceId(),
                e.getTitle(),
                e.getBody(),
                e.getTags(),
                e.getPriority(),
                e.getSource(),
                e.getStatus(),
                e.getCreatedBy(),
                Instant.ofEpochMilli(e.getCreatedAtMs()),
                instantOrNull(e.getInProgressAtMs()),
                instantOrNull(e.getStartedAtMs()),
                instantOrNull(e.getResolvedAtMs()),
                instantOrNull(e.getRejectedAtMs()),
                e.getRejectionReason(),
                e.getLinkedTaskId(),
                e.getRelatedBacklogIds());
    }

    private static Long epochOrNull(Instant instant)
    {
        return instant == null ? null : instant.toEpochMilli();
    }

    private static Instant instantOrNull(Long epochMs)
    {
        return epochMs == null ? null : Instant.ofEpochMilli(epochMs);
    }
}
