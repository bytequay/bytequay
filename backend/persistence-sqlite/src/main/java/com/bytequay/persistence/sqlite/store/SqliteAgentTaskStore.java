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
package com.bytequay.persistence.sqlite.store;

import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentStorageException;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskNotFoundException;
import com.bytequay.agent.core.CleanupState;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskState;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.persistence.sqlite.entity.AgentTaskEntity;
import com.bytequay.persistence.sqlite.repository.AgentTaskJpaRepository;
import com.google.common.collect.ImmutableList;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.bytequay.persistence.sqlite.store.SqliteStoreExceptions.mapConstraint;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteAgentTaskStore
        implements AgentTaskStore
{
    private static final List<TaskState> TERMINAL_STATES = ImmutableList.of(TaskState.COMPLETED, TaskState.FAILED);

    private final AgentTaskJpaRepository repository;

    public SqliteAgentTaskStore(AgentTaskJpaRepository repository)
    {
        this.repository = requireNonNull(repository, "repository is null");
    }

    @Override
    @Transactional
    public void insert(AgentTask task)
    {
        requireNonNull(task, "task is null");
        if (repository.existsById(task.id().toString())) {
            throw new AgentConflictException("Task already exists: " + task.id());
        }
        try {
            repository.saveAndFlush(AgentTaskEntity.fromDomain(task));
        }
        catch (DataAccessException e) {
            throw mapConstraint("Task already exists: " + task.id(), e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentTask> find(TaskId taskId)
    {
        requireNonNull(taskId, "taskId is null");
        return repository.findById(taskId.toString()).map(AgentTaskEntity::toDomain);
    }

    @Override
    @Transactional
    public AgentTask update(AgentTask task)
    {
        requireNonNull(task, "task is null");
        AgentTaskEntity entity = repository.findById(task.id().toString())
                .orElseThrow(() -> new AgentTaskNotFoundException(task.id()));
        if (entity.getVersion() != task.version()) {
            throw new AgentConflictException("Task version changed: " + task.id());
        }
        entity.updateFrom(task);
        try {
            repository.saveAndFlush(entity);
            return entity.toDomain();
        }
        catch (OptimisticLockingFailureException e) {
            throw new AgentConflictException("Task version changed: " + task.id(), e);
        }
        catch (DataAccessException e) {
            throw new AgentStorageException("Cannot update task " + task.id(), e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentTask> listRecent(int limit)
    {
        checkLimit(limit);
        return repository.findAllByOrderByUpdatedAtDesc(PageRequest.of(0, limit)).stream()
                .map(AgentTaskEntity::toDomain)
                .collect(toImmutableList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentTask> listLive()
    {
        return repository.findByStateNotInOrderByCreatedAtAsc(TERMINAL_STATES).stream()
                .map(AgentTaskEntity::toDomain)
                .collect(toImmutableList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentTask> listCleanupDue(Instant now, int limit)
    {
        requireNonNull(now, "now is null");
        checkLimit(limit);
        return repository.findCleanupDue(
                        TERMINAL_STATES,
                        CleanupState.DELETED,
                        CleanupState.RETRY_PENDING,
                        now,
                        PageRequest.of(0, limit)).stream()
                .map(AgentTaskEntity::toDomain)
                .collect(toImmutableList());
    }

    private static void checkLimit(int limit)
    {
        checkArgument(limit > 0, "limit must be positive");
    }
}
