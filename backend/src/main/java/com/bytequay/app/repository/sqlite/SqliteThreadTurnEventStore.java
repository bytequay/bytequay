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

import com.bytequay.app.domain.ThreadTurnEvent;
import com.bytequay.app.domain.ThreadTurnEventType;
import com.bytequay.app.repository.ThreadTurnEventStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static com.bytequay.app.repository.sqlite.SqlitePageRequests.firstPage;
import static java.util.Objects.requireNonNull;

@Component
class SqliteThreadTurnEventStore
        implements ThreadTurnEventStore
{
    private final ThreadTurnEventJpaRepository events;

    SqliteThreadTurnEventStore(ThreadTurnEventJpaRepository events)
    {
        this.events = requireNonNull(events, "events is null");
    }

    @Override
    @Transactional
    public void appendEvent(ThreadTurnEvent event)
    {
        requireNonNull(event, "event is null");
        ThreadTurnEventEntity entity = new ThreadTurnEventEntity();
        entity.setId(event.id());
        entity.setTurnId(event.turnId());
        entity.setTaskId(event.threadId());
        entity.setEvent(event.event().name());
        entity.setCreatedAtMs(event.createdAt().toEpochMilli());
        entity.setMessage(event.message());
        events.save(entity);
    }

    @Override
    public List<ThreadTurnEvent> listEventsByTaskId(String threadId, int limit)
    {
        requireNonNull(threadId, "threadId is null");
        return events.findByThreadIdOrderByCreatedAtMsDescIdDesc(threadId, firstPage(limit))
                .stream()
                .map(SqliteThreadTurnEventStore::toEvent)
                .toList();
    }

    private static ThreadTurnEvent toEvent(ThreadTurnEventEntity e)
    {
        return new ThreadTurnEvent(
                e.getId(),
                e.getTurnId(),
                e.getTaskId(),
                ThreadTurnEventType.valueOf(e.getEvent()),
                Instant.ofEpochMilli(e.getCreatedAtMs()),
                e.getMessage());
    }
}
