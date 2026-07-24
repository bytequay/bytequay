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

import com.bytequay.app.domain.ThreadResourceLane;
import com.bytequay.app.domain.ThreadScope;
import com.bytequay.app.domain.ThreadTurn;
import com.bytequay.app.domain.ThreadTurnStatus;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.repository.ThreadTurnStore;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static com.bytequay.app.repository.sqlite.SqlitePageRequests.firstPage;
import static java.util.Objects.requireNonNull;

@Component
class SqliteThreadTurnStore
        implements ThreadTurnStore
{
    private final ThreadTurnJpaRepository turns;

    SqliteThreadTurnStore(ThreadTurnJpaRepository turns)
    {
        this.turns = requireNonNull(turns, "turns is null");
    }

    @Override
    @Transactional
    public void saveTurn(ThreadTurn turn)
    {
        ThreadTurnEntity entity = turns.findById(turn.id()).orElseGet(ThreadTurnEntity::new);
        entity.setId(turn.id());
        entity.setThreadId(turn.threadId());
        entity.setTaskId(turn.taskId());
        entity.setLane(turn.lane().name());
        entity.setStatus(turn.status().name());
        entity.setInput(turn.input());
        entity.setCreatedAtMs(turn.createdAt().toEpochMilli());
        entity.setUpdatedAtMs(turn.updatedAt().toEpochMilli());
        entity.setStartedAtMs(Timestamps.epochMilli(turn.startedAt()));
        entity.setFinishedAtMs(Timestamps.epochMilli(turn.finishedAt()));
        entity.setErrorMessage(turn.errorMessage());
        TurnInitiator initiator = turn.initiator() == null ? TurnInitiator.user() : turn.initiator();
        entity.setInitiatorAttended(initiator.attended());
        entity.setInitiatorSource(initiator.source());
        entity.setStageId(turn.stageId());
        entity.setScope(turn.scope() == null ? null : turn.scope().name());
        entity.setAgentRunId(turn.agentRunId());
        turns.save(entity);
    }

    @Override
    public Optional<ThreadTurn> findTurnById(String id)
    {
        return turns.findById(id).map(SqliteThreadTurnStore::toTurn);
    }

    @Override
    public List<ThreadTurn> listTurnsByStatus(ThreadTurnStatus status, int limit)
    {
        requireNonNull(status, "status is null");
        return turns.findByStatusOrderByCreatedAtMsAscIdAsc(status.name(), firstPage(limit))
                .stream()
                .map(SqliteThreadTurnStore::toTurn)
                .toList();
    }

    @Override
    public List<ThreadTurn> listTurnsByStatusAfter(ThreadTurnStatus status, Instant createdAfter, String idAfter, int limit)
    {
        requireNonNull(status, "status is null");
        requireNonNull(createdAfter, "createdAfter is null");
        requireNonNull(idAfter, "idAfter is null");
        return turns.findByStatusAfterCursor(
                        status.name(),
                        createdAfter.toEpochMilli(),
                        idAfter,
                        firstPage(limit))
                .stream()
                .map(SqliteThreadTurnStore::toTurn)
                .toList();
    }

    @Override
    public List<ThreadTurn> listTurnsByStatuses(Collection<ThreadTurnStatus> statuses, int limit)
    {
        requireNonNull(statuses, "statuses is null");
        PageRequest page = firstPage(limit);
        if (statuses.isEmpty()) {
            return List.of();
        }
        return turns.findByStatusInOrderByCreatedAtMsAscIdAsc(
                        statuses.stream()
                                .map(ThreadTurnStatus::name)
                                .toList(),
                        page)
                .stream()
                .map(SqliteThreadTurnStore::toTurn)
                .toList();
    }

    @Override
    public List<ThreadTurn> listTurnsByTaskIdAndStatus(String threadId, ThreadTurnStatus status, int limit)
    {
        requireNonNull(threadId, "threadId is null");
        requireNonNull(status, "status is null");
        return turns.findByThreadIdAndStatusOrderByCreatedAtMsDescIdDesc(threadId, status.name(), firstPage(limit))
                .stream()
                .map(SqliteThreadTurnStore::toTurn)
                .toList();
    }

    @Override
    public List<ThreadTurn> listTurnsByTaskId(String threadId, int limit)
    {
        requireNonNull(threadId, "threadId is null");
        return turns.findByThreadIdOrderByCreatedAtMsDescIdDesc(threadId, firstPage(limit))
                .stream()
                .map(SqliteThreadTurnStore::toTurn)
                .toList();
    }

    @Override
    public List<ThreadTurn> listTurnsByAgentRunId(String agentRunId, int limit)
    {
        requireNonNull(agentRunId, "agentRunId is null");
        return turns.findByAgentRunIdOrderByCreatedAtMsDescIdDesc(
                        agentRunId, firstPage(limit))
                .stream()
                .map(SqliteThreadTurnStore::toTurn)
                .toList();
    }

    @Override
    public List<ThreadTurn> listTurnsByExactTaskIdAndStatus(
            String taskId, ThreadTurnStatus status, int limit)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(status, "status is null");
        return turns.findByTaskIdAndStatusOrderByCreatedAtMsDescIdDesc(
                        taskId, status.name(), firstPage(limit))
                .stream()
                .map(SqliteThreadTurnStore::toTurn)
                .toList();
    }

    private static ThreadTurn toTurn(ThreadTurnEntity e)
    {
        return new ThreadTurn(
                e.getId(),
                e.getThreadId(),
                e.getTaskId(),
                ThreadResourceLane.valueOf(e.getLane()),
                ThreadTurnStatus.valueOf(e.getStatus()),
                e.getInput(),
                Instant.ofEpochMilli(e.getCreatedAtMs()),
                Instant.ofEpochMilli(e.getUpdatedAtMs()),
                Timestamps.instant(e.getStartedAtMs()),
                Timestamps.instant(e.getFinishedAtMs()),
                e.getErrorMessage(),
                new TurnInitiator(e.isInitiatorAttended(), e.getInitiatorSource()),
                e.getStageId(),
                e.getScope() == null
                        ? ThreadScope.of(e.getTaskId(), e.getStageId())
                        : ThreadScope.valueOf(e.getScope()),
                e.getAgentRunId());
    }
}
