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

import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnNotFoundException;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.persistence.sqlite.entity.AgentTurnEntity;
import com.bytequay.persistence.sqlite.repository.AgentTurnJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;
import static com.bytequay.persistence.sqlite.store.StoreWrites.AGENT;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
@Transactional(readOnly = true)
public class SqliteAgentTurnStore
        implements AgentTurnStore
{
    private final AgentTurnJpaRepository repository;

    public SqliteAgentTurnStore(AgentTurnJpaRepository repository)
    {
        this.repository = requireNonNull(repository, "repository is null");
    }

    @Override
    @Transactional
    public void insert(AgentTurn turn)
    {
        requireNonNull(turn, "turn is null");
        StoreWrites.insert(repository, turn.id().toString(), "Turn", () -> AgentTurnEntity.fromDomain(turn), AGENT);
    }

    @Override
    public Optional<AgentTurn> find(TurnId turnId)
    {
        requireNonNull(turnId, "turnId is null");
        return repository.findById(turnId.toString()).map(AgentTurnEntity::toDomain);
    }

    @Override
    public Optional<AgentTurn> findByIdempotencyKey(TaskId taskId, String idempotencyKey)
    {
        requireNonNull(taskId, "taskId is null");
        checkNotBlank(idempotencyKey, "idempotencyKey");
        return repository.findByTaskIdAndIdempotencyKey(taskId.toString(), idempotencyKey)
                .map(AgentTurnEntity::toDomain);
    }

    @Override
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
        return StoreWrites.update(
                repository, turn.id().toString(), "Turn", turn.version(), AgentTurnEntity::getVersion, entity -> entity.updateFrom(turn),
                () -> new AgentTurnNotFoundException(turn.id()), AGENT)
                .toDomain();
    }

    @Override
    public List<AgentTurn> list(TaskId taskId)
    {
        requireNonNull(taskId, "taskId is null");
        return repository.findByTaskIdOrderByCreatedAtAsc(taskId.toString()).stream()
                .map(AgentTurnEntity::toDomain)
                .collect(toImmutableList());
    }

    @Override
    public List<AgentTurn> listRecoverable()
    {
        return repository.findByActiveTaskIdIsNotNullOrderByCreatedAtAsc().stream()
                .map(AgentTurnEntity::toDomain)
                .collect(toImmutableList());
    }
}
