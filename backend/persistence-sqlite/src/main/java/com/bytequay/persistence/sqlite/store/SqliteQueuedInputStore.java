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
import com.bytequay.agent.core.QueuedAgentInput;
import com.bytequay.agent.core.QueuedInputId;
import com.bytequay.agent.core.QueuedInputNotFoundException;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.store.QueuedInputStore;
import com.bytequay.persistence.sqlite.entity.AgentQueuedInputEntity;
import com.bytequay.persistence.sqlite.repository.AgentQueuedInputJpaRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;
import static com.bytequay.persistence.sqlite.store.SqliteStoreExceptions.mapConstraint;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteQueuedInputStore
        implements QueuedInputStore
{
    private final AgentQueuedInputJpaRepository repository;

    public SqliteQueuedInputStore(AgentQueuedInputJpaRepository repository)
    {
        this.repository = requireNonNull(repository, "repository is null");
    }

    @Override
    @Transactional
    public void insert(QueuedAgentInput input)
    {
        requireNonNull(input, "input is null");
        if (repository.existsById(input.id().toString())) {
            throw new AgentConflictException("Queued input already exists: " + input.id());
        }
        try {
            repository.saveAndFlush(AgentQueuedInputEntity.fromDomain(input));
        }
        catch (DataAccessException e) {
            throw mapConstraint("Queued input conflicts with an existing request: " + input.id(), e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<QueuedAgentInput> find(QueuedInputId inputId)
    {
        requireNonNull(inputId, "inputId is null");
        return repository.findById(inputId.toString()).map(AgentQueuedInputEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<QueuedAgentInput> findQueuedByIdempotencyKey(TaskId taskId, String idempotencyKey)
    {
        requireNonNull(taskId, "taskId is null");
        checkNotBlank(idempotencyKey, "idempotencyKey");
        return repository.findByTaskIdAndIdempotencyKey(taskId.toString(), idempotencyKey)
                .map(AgentQueuedInputEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<QueuedAgentInput> listQueued(TaskId taskId)
    {
        requireNonNull(taskId, "taskId is null");
        return repository.findByTaskIdOrderByPositionAsc(taskId.toString()).stream()
                .map(AgentQueuedInputEntity::toDomain)
                .collect(toImmutableList());
    }

    @Override
    @Transactional
    public QueuedAgentInput update(QueuedAgentInput input)
    {
        requireNonNull(input, "input is null");
        AgentQueuedInputEntity entity = repository.findById(input.id().toString())
                .orElseThrow(() -> new QueuedInputNotFoundException(input.id()));
        if (entity.getVersion() != input.version()) {
            throw new AgentConflictException("Queued input version changed: " + input.id());
        }
        entity.updateFrom(input);
        try {
            repository.saveAndFlush(entity);
            return entity.toDomain();
        }
        catch (OptimisticLockingFailureException e) {
            throw new AgentConflictException("Queued input version changed: " + input.id(), e);
        }
        catch (DataAccessException e) {
            throw new AgentStorageException("Cannot update queued input " + input.id(), e);
        }
    }
}
