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

import com.bytequay.app.domain.ThreadSignal;
import com.bytequay.app.repository.ThreadSignalStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
class SqliteThreadSignalStore
        implements ThreadSignalStore
{
    private final ThreadSignalJpaRepository repository;

    SqliteThreadSignalStore(ThreadSignalJpaRepository repository)
    {
        this.repository = repository;
    }

    @Override
    @Transactional
    public ThreadSignal save(ThreadSignal signal)
    {
        ThreadSignalEntity entity = new ThreadSignalEntity();
        entity.setId(signal.id());
        entity.setThreadId(signal.threadId());
        entity.setTaskId(signal.taskId());
        entity.setSourceKind(signal.sourceKind());
        entity.setIconKind(signal.iconKind());
        entity.setTitle(signal.title());
        entity.setBody(signal.body());
        entity.setSourceUrl(signal.sourceUrl());
        entity.setCreatedAtMs(signal.createdAt().toEpochMilli());
        entity.setReadAtMs(signal.readAt() == null ? null : signal.readAt().toEpochMilli());
        return toDomain(repository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public List<ThreadSignal> findByThread(String threadId)
    {
        return repository.findByThreadIdOrderByCreatedAtMsDesc(threadId).stream()
                .map(SqliteThreadSignalStore::toDomain)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ThreadSignal> findById(String id)
    {
        return repository.findById(id).map(SqliteThreadSignalStore::toDomain);
    }

    private static ThreadSignal toDomain(ThreadSignalEntity e)
    {
        return new ThreadSignal(
                e.getId(),
                e.getThreadId(),
                e.getTaskId(),
                e.getSourceKind(),
                e.getIconKind(),
                e.getTitle(),
                e.getBody(),
                e.getSourceUrl(),
                Instant.ofEpochMilli(e.getCreatedAtMs()),
                e.getReadAtMs() == null ? null : Instant.ofEpochMilli(e.getReadAtMs()));
    }
}
