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
package com.bytequay.core.memory;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface MemoryStore
{
    /** The workspace's memories, newest first. */
    List<Memory> list(String workspaceId);

    Optional<Memory> find(String memoryId);

    /** Inserts the memory or replaces the stored one with the same id. */
    void save(Memory memory);

    void delete(String memoryId);

    /** Adds one delivery to each memory that still exists, without rewriting it. */
    void countDelivered(Collection<String> memoryIds);

    /** Adds one citation to each memory that still exists, without rewriting it. */
    void countCited(Collection<String> memoryIds);
}
