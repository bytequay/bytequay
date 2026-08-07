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
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Component
public class ThreadSignalStore
{
    private final ThreadSignalJpaRepository repository;

    ThreadSignalStore(ThreadSignalJpaRepository repository)
    {
        this.repository = repository;
    }

    @Transactional
    /** Insert or update a signal; returns the persisted row. */
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

    @Transactional(readOnly = true)
    /** Signals on a thread, newest-first. */
    public List<ThreadSignal> findByThread(String threadId)
    {
        return repository.findByThreadIdOrderByCreatedAtMsDesc(threadId).stream()
                .map(ThreadSignalStore::toDomain)
                .toList();
    }

    @Transactional(readOnly = true)
    /** One signal by id. */
    public Optional<ThreadSignal> findById(String id)
    {
        return repository.findById(id).map(ThreadSignalStore::toDomain);
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
