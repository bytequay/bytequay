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

import com.bytequay.agent.core.QueuedAgentInput;
import com.bytequay.agent.core.QueuedInputId;
import com.bytequay.agent.core.QueuedInputNotFoundException;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.store.QueuedInputStore;
import com.bytequay.persistence.sqlite.entity.AgentQueuedInputEntity;
import com.bytequay.persistence.sqlite.repository.AgentQueuedInputJpaRepository;
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
        StoreWrites.insert(repository, input.id().toString(), "Queued input", () -> AgentQueuedInputEntity.fromDomain(input), AGENT);
    }

    @Override
    public Optional<QueuedAgentInput> find(QueuedInputId inputId)
    {
        requireNonNull(inputId, "inputId is null");
        return repository.findById(inputId.toString()).map(AgentQueuedInputEntity::toDomain);
    }

    @Override
    public Optional<QueuedAgentInput> findQueuedByIdempotencyKey(TaskId taskId, String idempotencyKey)
    {
        requireNonNull(taskId, "taskId is null");
        checkNotBlank(idempotencyKey, "idempotencyKey");
        return repository.findByTaskIdAndIdempotencyKey(taskId.toString(), idempotencyKey)
                .map(AgentQueuedInputEntity::toDomain);
    }

    @Override
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
        return StoreWrites.update(
                repository, input.id().toString(), "Queued input", input.version(), AgentQueuedInputEntity::getVersion, entity -> entity.updateFrom(input),
                () -> new QueuedInputNotFoundException(input.id()), AGENT)
                .toDomain();
    }
}
