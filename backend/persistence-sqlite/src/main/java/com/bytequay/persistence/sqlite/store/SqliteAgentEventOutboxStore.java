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
import com.bytequay.agent.core.AgentEventOutboxEntry;
import com.bytequay.agent.core.AgentStorageException;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.store.AgentEventOutboxStore;
import com.bytequay.persistence.sqlite.entity.AgentEventOutboxEntity;
import com.bytequay.persistence.sqlite.repository.AgentEventOutboxJpaRepository;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static com.bytequay.persistence.sqlite.store.SqliteStoreExceptions.mapConstraint;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteAgentEventOutboxStore
        implements AgentEventOutboxStore
{
    private final AgentEventOutboxJpaRepository repository;

    public SqliteAgentEventOutboxStore(AgentEventOutboxJpaRepository repository)
    {
        this.repository = requireNonNull(repository, "repository is null");
    }

    @Override
    @Transactional
    public void insert(AgentEventOutboxEntry entry)
    {
        requireNonNull(entry, "entry is null");
        try {
            repository.saveAndFlush(AgentEventOutboxEntity.fromDomain(entry));
        }
        catch (DataAccessException e) {
            throw mapConstraint("Event already exists in the outbox: " + entry.eventId(), e);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<AgentEventOutboxEntry> find(EventId eventId)
    {
        requireNonNull(eventId, "eventId is null");
        return repository.findByEventId(eventId.toString()).map(AgentEventOutboxEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public List<AgentEventOutboxEntry> listPending(int limit)
    {
        checkArgument(limit > 0, "limit must be positive");
        return repository.findByEventSequenceIsNullOrderByIdAsc(PageRequest.of(0, limit)).stream()
                .map(AgentEventOutboxEntity::toDomain)
                .collect(toImmutableList());
    }

    @Override
    @Transactional(readOnly = true)
    public boolean hasPending(TaskId taskId)
    {
        requireNonNull(taskId, "taskId is null");
        return repository.existsByTaskIdAndEventSequenceIsNull(taskId.toString());
    }

    @Override
    @Transactional
    public void markAppended(EventId eventId, long sequence)
    {
        requireNonNull(eventId, "eventId is null");
        checkArgument(sequence > 0, "sequence must be positive");
        AgentEventOutboxEntity entity = repository.findByEventId(eventId.toString())
                .orElseThrow(() -> new AgentStorageException("Event is absent from the outbox: " + eventId));
        Long existing = entity.getEventSequence();
        if (existing != null) {
            if (existing != sequence) {
                throw new AgentConflictException("Event already has a different sequence: " + eventId);
            }
            return;
        }
        entity.markAppended(sequence);
        repository.saveAndFlush(entity);
    }
}
