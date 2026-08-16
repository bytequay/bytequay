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
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnNotFoundException;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.persistence.sqlite.entity.AgentTurnEntity;
import com.bytequay.persistence.sqlite.repository.AgentTurnJpaRepository;
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
public class SqliteAgentTurnStore
        implements AgentTurnStore
{
    private final AgentTurnJpaRepository repository;

    public SqliteAgentTurnStore(AgentTurnJpaRepository repository)
    {
        requireNonNull(repository, "repository is null");
        this.repository = repository;
    }

    @Override
    @Transactional
    public void insert(AgentTurn turn)
    {
        requireNonNull(turn, "turn is null");
        if (repository.existsById(turn.id().toString())) {
            throw new AgentConflictException("Turn already exists: " + turn.id());
        }
        try {
            repository.saveAndFlush(AgentTurnEntity.fromDomain(turn));
        }
        catch (DataAccessException e) {
            throw mapConstraint("Turn conflicts with an existing request: " + turn.id(), e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentTurn> find(TurnId turnId)
    {
        requireNonNull(turnId, "turnId is null");
        return repository.findById(turnId.toString()).map(AgentTurnEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentTurn> findByIdempotencyKey(TaskId taskId, String idempotencyKey)
    {
        requireNonNull(taskId, "taskId is null");
        checkNotBlank(idempotencyKey, "idempotencyKey");
        return repository.findByTaskIdAndIdempotencyKey(taskId.toString(), idempotencyKey)
                .map(AgentTurnEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentTurn> findActive(TaskId taskId)
    {
        requireNonNull(taskId, "taskId is null");
        return repository.findByActiveTaskId(taskId.toString()).map(AgentTurnEntity::toDomain);
    }

    @Override
    @Transactional
    public AgentTurn update(AgentTurn turn)
    {
        requireNonNull(turn, "turn is null");
        AgentTurnEntity entity = repository.findById(turn.id().toString())
                .orElseThrow(() -> new AgentTurnNotFoundException(turn.id()));
        if (entity.getVersion() != turn.version()) {
            throw new AgentConflictException("Turn version changed: " + turn.id());
        }
        entity.updateFrom(turn);
        try {
            repository.saveAndFlush(entity);
            return entity.toDomain();
        }
        catch (OptimisticLockingFailureException e) {
            throw new AgentConflictException("Turn update conflicts with durable state: " + turn.id(), e);
        }
        catch (DataAccessException e) {
            RuntimeException mapped = mapConstraint("Turn update conflicts with durable state: " + turn.id(), e);
            if (mapped instanceof AgentConflictException) {
                throw mapped;
            }
            throw new AgentStorageException("Cannot update turn " + turn.id(), e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentTurn> list(TaskId taskId)
    {
        requireNonNull(taskId, "taskId is null");
        return repository.findByTaskIdOrderByCreatedAtAsc(taskId.toString()).stream()
                .map(AgentTurnEntity::toDomain)
                .collect(toImmutableList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentTurn> listRecoverable()
    {
        return repository.findByActiveTaskIdIsNotNullOrderByCreatedAtAsc().stream()
                .map(AgentTurnEntity::toDomain)
                .collect(toImmutableList());
    }
}
