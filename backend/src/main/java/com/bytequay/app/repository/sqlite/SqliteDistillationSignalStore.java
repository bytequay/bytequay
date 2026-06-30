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

import com.bytequay.app.domain.DistillationSignal;
import com.bytequay.app.repository.DistillationSignalStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Component
class SqliteDistillationSignalStore
        implements DistillationSignalStore
{
    private final DistillationSignalJpaRepository repository;

    SqliteDistillationSignalStore(DistillationSignalJpaRepository repository)
    {
        this.repository = repository;
    }

    @Override
    @Transactional
    public DistillationSignal save(DistillationSignal signal)
    {
        DistillationSignalEntity entity = new DistillationSignalEntity();
        entity.setId(signal.id());
        entity.setEventType(signal.eventType());
        entity.setSourceId(signal.sourceId());
        entity.setUserDecision(signal.userDecision());
        entity.setReason(signal.reason());
        entity.setContextSnapshotJson(signal.contextSnapshotJson());
        entity.setThreadId(signal.threadId());
        entity.setWorkspaceId(signal.workspaceId());
        entity.setCreatedAtMs(signal.createdAt().toEpochMilli());
        return toDomain(repository.save(entity));
    }

    @Override
    @Transactional(readOnly = true)
    public List<DistillationSignal> findByEventType(String eventType)
    {
        return repository.findByEventTypeOrderByCreatedAtMsAsc(eventType).stream()
                .map(SqliteDistillationSignalStore::toDomain)
                .toList();
    }

    private static DistillationSignal toDomain(DistillationSignalEntity e)
    {
        return new DistillationSignal(
                e.getId(),
                e.getEventType(),
                e.getSourceId(),
                e.getUserDecision(),
                e.getReason(),
                e.getContextSnapshotJson(),
                e.getThreadId(),
                e.getWorkspaceId(),
                Instant.ofEpochMilli(e.getCreatedAtMs()));
    }
}
