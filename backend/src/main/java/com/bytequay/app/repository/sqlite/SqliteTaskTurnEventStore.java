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

import com.bytequay.app.domain.TaskTurnEvent;
import com.bytequay.app.domain.TaskTurnEventType;
import com.bytequay.app.repository.TaskTurnEventStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static com.bytequay.app.repository.sqlite.SqlitePageRequests.firstPage;
import static java.util.Objects.requireNonNull;

@Component
class SqliteTaskTurnEventStore
        implements TaskTurnEventStore
{
    private final TaskTurnEventJpaRepository events;

    SqliteTaskTurnEventStore(TaskTurnEventJpaRepository events)
    {
        this.events = requireNonNull(events, "events is null");
    }

    @Override
    @Transactional
    public void appendEvent(TaskTurnEvent event)
    {
        requireNonNull(event, "event is null");
        TaskTurnEventEntity entity = new TaskTurnEventEntity();
        entity.setId(event.id());
        entity.setTurnId(event.turnId());
        entity.setTaskId(event.taskId());
        entity.setEvent(event.event().name());
        entity.setCreatedAtMs(event.createdAt().toEpochMilli());
        entity.setMessage(event.message());
        events.save(entity);
    }

    @Override
    public List<TaskTurnEvent> listEventsByTaskId(String taskId, int limit)
    {
        requireNonNull(taskId, "taskId is null");
        return events.findByTaskIdOrderByCreatedAtMsDescIdDesc(taskId, firstPage(limit))
                .stream()
                .map(SqliteTaskTurnEventStore::toEvent)
                .toList();
    }

    private static TaskTurnEvent toEvent(TaskTurnEventEntity e)
    {
        return new TaskTurnEvent(
                e.getId(),
                e.getTurnId(),
                e.getTaskId(),
                TaskTurnEventType.valueOf(e.getEvent()),
                Instant.ofEpochMilli(e.getCreatedAtMs()),
                e.getMessage());
    }
}
