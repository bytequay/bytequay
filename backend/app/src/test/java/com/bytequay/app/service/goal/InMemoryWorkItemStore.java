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
package com.bytequay.app.service.goal;

import com.bytequay.core.domain.WorkItem;
import com.bytequay.core.store.WorkItemStore;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;

final class InMemoryWorkItemStore
        implements WorkItemStore
{
    private final Map<String, WorkItem> items = new LinkedHashMap<>();

    @Override
    public List<WorkItem> list(String workspaceId)
    {
        return items.values().stream().filter(item -> item.workspaceId().equals(workspaceId)).collect(toImmutableList());
    }

    @Override
    public Optional<WorkItem> find(String id)
    {
        return Optional.ofNullable(items.get(id));
    }

    @Override
    public Optional<WorkItem> findByIdentity(String identity)
    {
        return items.values().stream().filter(item -> item.links().stream().anyMatch(link -> link.identity().equals(identity))).findFirst();
    }

    @Override
    public void insert(WorkItem item)
    {
        items.put(item.id(), item);
    }

    @Override
    public boolean update(WorkItem item, long expectedRevision)
    {
        if (items.get(item.id()).revision() != expectedRevision) {
            return false;
        }
        items.put(item.id(), item);
        return true;
    }

    @Override
    public void delete(WorkItem item)
    {
        items.remove(item.id());
    }
}
