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
package com.bytequay.agent.core;

import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;

final class InMemoryAgentStores
        implements AgentTaskStore, AgentTurnStore
{
    private final Map<TaskId, AgentTask> tasks = new HashMap<>();
    private final Map<TurnId, AgentTurn> turns = new HashMap<>();

    @Override
    public synchronized void insert(AgentTask task)
    {
        if (tasks.putIfAbsent(task.id(), task) != null) {
            throw new AgentConflictException("Task already exists: " + task.id());
        }
    }

    @Override
    public synchronized Optional<AgentTask> find(TaskId taskId)
    {
        return Optional.ofNullable(tasks.get(taskId));
    }

    @Override
    public synchronized AgentTask update(AgentTask task)
    {
        AgentTask current = tasks.get(task.id());
        if (current == null) {
            throw new AgentTaskNotFoundException(task.id());
        }
        if (current.version() != task.version()) {
            throw new AgentConflictException("Task version changed: " + task.id());
        }
        AgentTask stored = task.withVersion(task.version() + 1);
        tasks.put(task.id(), stored);
        return stored;
    }

    @Override
    public synchronized List<AgentTask> listRecent(int limit)
    {
        return tasks.values().stream()
                .sorted(Comparator.comparing(AgentTask::updatedAt).reversed())
                .limit(limit)
                .collect(toImmutableList());
    }

    @Override
    public synchronized List<AgentTask> listLive()
    {
        return tasks.values().stream()
                .filter(task -> !task.state().terminal())
                .sorted(Comparator.comparing(AgentTask::createdAt))
                .collect(toImmutableList());
    }

    @Override
    public synchronized List<AgentTask> listCleanupDue(Instant now, int limit)
    {
        return tasks.values().stream()
                .filter(task -> task.state().terminal())
                .filter(task -> task.cleanupState() != CleanupState.DELETED)
                .filter(task -> task.cleanupState() == CleanupState.RETRY_PENDING
                        || task.deleteAfter().stream().anyMatch(deadline -> !deadline.isAfter(now)))
                .sorted(Comparator.comparing(task -> task.deleteAfter().orElse(Instant.MAX)))
                .limit(limit)
                .collect(toImmutableList());
    }

    @Override
    public synchronized void insert(AgentTurn turn)
    {
        if (turns.containsKey(turn.id())) {
            throw new AgentConflictException("Turn already exists: " + turn.id());
        }
        if (turns.values().stream().anyMatch(existing -> existing.taskId().equals(turn.taskId())
                && existing.idempotencyKey().equals(turn.idempotencyKey()))) {
            throw new AgentConflictException("Turn idempotency key already exists");
        }
        if (turn.state().active() && findActive(turn.taskId()).isPresent()) {
            throw new AgentConflictException("Task already has an active turn");
        }
        turns.put(turn.id(), turn);
    }

    @Override
    public synchronized Optional<AgentTurn> find(TurnId turnId)
    {
        return Optional.ofNullable(turns.get(turnId));
    }

    @Override
    public synchronized Optional<AgentTurn> findByIdempotencyKey(TaskId taskId, String idempotencyKey)
    {
        return turns.values().stream()
                .filter(turn -> turn.taskId().equals(taskId))
                .filter(turn -> turn.idempotencyKey().equals(idempotencyKey))
                .findFirst();
    }

    @Override
    public synchronized Optional<AgentTurn> findActive(TaskId taskId)
    {
        return turns.values().stream()
                .filter(turn -> turn.taskId().equals(taskId))
                .filter(turn -> turn.state().active())
                .findFirst();
    }

    @Override
    public synchronized AgentTurn update(AgentTurn turn)
    {
        AgentTurn current = turns.get(turn.id());
        if (current == null) {
            throw new AgentTurnNotFoundException(turn.id());
        }
        if (current.version() != turn.version()) {
            throw new AgentConflictException("Turn version changed: " + turn.id());
        }
        if (turn.state().active() && turns.values().stream().anyMatch(existing -> !existing.id().equals(turn.id())
                && existing.taskId().equals(turn.taskId())
                && existing.state().active())) {
            throw new AgentConflictException("Task already has an active turn");
        }
        AgentTurn stored = turn.withVersion(turn.version() + 1);
        turns.put(turn.id(), stored);
        return stored;
    }

    @Override
    public synchronized List<AgentTurn> list(TaskId taskId)
    {
        return turns.values().stream()
                .filter(turn -> turn.taskId().equals(taskId))
                .sorted(Comparator.comparing(AgentTurn::createdAt))
                .collect(toImmutableList());
    }

    @Override
    public synchronized List<AgentTurn> listRecoverable()
    {
        return turns.values().stream()
                .filter(turn -> turn.state().active())
                .sorted(Comparator.comparing(AgentTurn::createdAt))
                .collect(toImmutableList());
    }

    synchronized List<AgentTask> tasks()
    {
        return ImmutableList.copyOf(tasks.values());
    }
}
