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
package com.bytequay.app.memory;

import com.bytequay.core.memory.Memory;
import com.bytequay.core.memory.MemoryStore;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;

public final class InMemoryMemoryStore
        implements MemoryStore
{
    private final Map<String, Memory> memories = new LinkedHashMap<>();

    @Override
    public synchronized List<Memory> list(String workspaceId)
    {
        return memories.values().stream().filter(memory -> memory.workspaceId().equals(workspaceId))
                .sorted(Comparator.comparing(Memory::createdAt).thenComparing(Memory::number).reversed())
                .collect(toImmutableList());
    }

    @Override
    public synchronized Optional<Memory> find(String memoryId)
    {
        return Optional.ofNullable(memories.get(memoryId));
    }

    @Override
    public synchronized void save(Memory memory)
    {
        memories.put(memory.id(), memory);
    }

    @Override
    public synchronized void delete(String memoryId)
    {
        memories.remove(memoryId);
    }

    @Override
    public synchronized void countDelivered(Collection<String> memoryIds)
    {
        memoryIds.stream().distinct().filter(memories::containsKey).forEach(id -> memories.computeIfPresent(id, (ignored, memory) -> new Memory(memory.id(),
                memory.workspaceId(), memory.kind(), memory.title(), memory.text(), memory.scope(), memory.sources(), memory.origin(), memory.version(), memory.previous(),
                memory.delivered() + 1, memory.cited(), memory.reports(), memory.createdAt(), memory.updatedAt())));
    }

    @Override
    public synchronized void countCited(Collection<String> memoryIds)
    {
        memoryIds.stream().distinct().filter(memories::containsKey).forEach(id -> memories.computeIfPresent(id, (ignored, memory) -> new Memory(memory.id(),
                memory.workspaceId(), memory.kind(), memory.title(), memory.text(), memory.scope(), memory.sources(), memory.origin(), memory.version(), memory.previous(),
                memory.delivered(), memory.cited() + 1, memory.reports(), memory.createdAt(), memory.updatedAt())));
    }
}
