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

import com.bytequay.core.memory.Memory;
import com.bytequay.core.memory.MemoryStore;
import com.bytequay.persistence.sqlite.entity.MemoryEntity;
import com.bytequay.persistence.sqlite.repository.MemoryJpaRepository;
import com.google.common.collect.ImmutableSet;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteMemoryStore
        implements MemoryStore
{
    private final MemoryJpaRepository memories;

    public SqliteMemoryStore(MemoryJpaRepository memories)
    {
        this.memories = requireNonNull(memories, "memories is null");
    }

    @Override
    @Transactional(readOnly = true)
    public List<Memory> list(String workspaceId)
    {
        return memories.findByWorkspaceIdOrderByCreatedAtDescIdDesc(workspaceId).stream().map(MemoryEntity::toDomain).collect(toImmutableList());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Memory> find(String memoryId)
    {
        return memories.findById(memoryId).map(MemoryEntity::toDomain);
    }

    @Override
    @Transactional
    public void save(Memory memory)
    {
        // Updates the loaded row; only a memory with no row yet is persisted as new.
        MemoryEntity entity = memories.findById(memory.id()).orElseGet(() -> new MemoryEntity(memory));
        entity.update(memory);
        memories.saveAndFlush(entity);
    }

    @Override
    @Transactional
    public void delete(String memoryId)
    {
        memories.deleteById(memoryId);
    }

    @Override
    @Transactional
    public void countDelivered(Collection<String> memoryIds)
    {
        if (!memoryIds.isEmpty()) {
            memories.countDelivered(ImmutableSet.copyOf(memoryIds));
        }
    }

    @Override
    @Transactional
    public void countCited(Collection<String> memoryIds)
    {
        if (!memoryIds.isEmpty()) {
            memories.countCited(ImmutableSet.copyOf(memoryIds));
        }
    }
}
