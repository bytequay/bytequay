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
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Component
public class DistillationSignalStore
{
    private final DistillationSignalJpaRepository repository;

    DistillationSignalStore(DistillationSignalJpaRepository repository)
    {
        this.repository = repository;
    }

    @Transactional
    /** Append one decision signal. */
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

    @Transactional(readOnly = true)
    /** Signals of one event type, oldest-first. Exists for the (future)
    *  memory read path + tests; v1 has no production reader. */
    public List<DistillationSignal> findByEventType(String eventType)
    {
        return repository.findByEventTypeOrderByCreatedAtMsAsc(eventType).stream()
                .map(DistillationSignalStore::toDomain)
                .toList();
    }

    @Transactional
    /** Delete signals attached to a thread. Returns the count removed. */
    public int deleteByThread(String threadId)
    {
        if (threadId == null || threadId.isBlank()) {
            return 0;
        }
        return (int) repository.deleteByThreadId(threadId);
    }

    @Transactional
    /** Delete signals attached to a workspace. Returns the count removed. */
    public int deleteByWorkspace(String workspaceId)
    {
        if (workspaceId == null || workspaceId.isBlank()) {
            return 0;
        }
        return (int) repository.deleteByWorkspaceId(workspaceId);
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
