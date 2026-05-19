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

import com.bytequay.app.domain.TaskResourceLane;
import com.bytequay.app.domain.TaskTurn;
import com.bytequay.app.domain.TaskTurnStatus;
import com.bytequay.app.repository.TaskTurnStore;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Component
class SqliteTaskTurnStore
        implements TaskTurnStore
{
    private final TaskTurnJpaRepository turns;

    SqliteTaskTurnStore(TaskTurnJpaRepository turns)
    {
        this.turns = requireNonNull(turns, "turns is null");
    }

    @Override
    @Transactional
    public void saveTurn(TaskTurn turn)
    {
        TaskTurnEntity entity = turns.findById(turn.id()).orElseGet(TaskTurnEntity::new);
        entity.setId(turn.id());
        entity.setTaskId(turn.taskId());
        entity.setLane(turn.lane().name());
        entity.setStatus(turn.status().name());
        entity.setInput(turn.input());
        entity.setCreatedAtMs(turn.createdAt().toEpochMilli());
        entity.setUpdatedAtMs(turn.updatedAt().toEpochMilli());
        entity.setStartedAtMs(turn.startedAt() == null ? null : turn.startedAt().toEpochMilli());
        entity.setFinishedAtMs(turn.finishedAt() == null ? null : turn.finishedAt().toEpochMilli());
        entity.setErrorMessage(turn.errorMessage());
        turns.save(entity);
    }

    @Override
    public Optional<TaskTurn> findTurnById(String id)
    {
        return turns.findById(id).map(SqliteTaskTurnStore::toTurn);
    }

    @Override
    public List<TaskTurn> listTurnsByStatus(TaskTurnStatus status, int limit)
    {
        return turns.findByStatusOrderByCreatedAtMsAsc(status.name(), PageRequest.of(0, limit))
                .stream()
                .map(SqliteTaskTurnStore::toTurn)
                .toList();
    }

    @Override
    public List<TaskTurn> listTurnsByTaskId(String taskId, int limit)
    {
        requireNonNull(taskId, "taskId is null");
        return turns.findByTaskIdOrderByCreatedAtMsDesc(taskId, PageRequest.of(0, limit))
                .stream()
                .map(SqliteTaskTurnStore::toTurn)
                .toList();
    }

    private static TaskTurn toTurn(TaskTurnEntity e)
    {
        return new TaskTurn(
                e.getId(),
                e.getTaskId(),
                TaskResourceLane.valueOf(e.getLane()),
                TaskTurnStatus.valueOf(e.getStatus()),
                e.getInput(),
                Instant.ofEpochMilli(e.getCreatedAtMs()),
                Instant.ofEpochMilli(e.getUpdatedAtMs()),
                e.getStartedAtMs() == null ? null : Instant.ofEpochMilli(e.getStartedAtMs()),
                e.getFinishedAtMs() == null ? null : Instant.ofEpochMilli(e.getFinishedAtMs()),
                e.getErrorMessage());
    }
}
