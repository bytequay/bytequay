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
package com.bytequay.app.repository.sqlite;

import com.bytequay.app.domain.TaskGroup;
import com.bytequay.app.repository.TaskGroupStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Component
class SqliteTaskGroupStore
        implements TaskGroupStore
{
    private final TaskGroupJpaRepository groups;

    SqliteTaskGroupStore(TaskGroupJpaRepository groups)
    {
        this.groups = requireNonNull(groups, "groups is null");
    }

    @Override
    @Transactional
    public void saveGroup(TaskGroup group)
    {
        TaskGroupEntity entity = groups.findById(group.id()).orElseGet(TaskGroupEntity::new);
        entity.setId(group.id());
        entity.setName(group.name());
        entity.setGlyph(group.glyph());
        entity.setColor(group.color());
        entity.setSortOrder(group.sortOrder());
        entity.setCreatedAtMs(group.createdAt().toEpochMilli());
        entity.setUpdatedAtMs(group.updatedAt().toEpochMilli());
        groups.save(entity);
    }

    @Override
    public Optional<TaskGroup> findGroupById(String id)
    {
        return groups.findById(id).map(SqliteTaskGroupStore::toGroup);
    }

    @Override
    public List<TaskGroup> listGroups()
    {
        return groups.findAllByOrderBySortOrderAscCreatedAtMsAsc().stream()
                .map(SqliteTaskGroupStore::toGroup)
                .toList();
    }

    @Override
    @Transactional
    public void deleteGroup(String id)
    {
        groups.deleteById(id);
    }

    private static TaskGroup toGroup(TaskGroupEntity e)
    {
        return new TaskGroup(
                e.getId(),
                e.getName(),
                e.getGlyph(),
                e.getColor(),
                e.getSortOrder(),
                Instant.ofEpochMilli(e.getCreatedAtMs()),
                Instant.ofEpochMilli(e.getUpdatedAtMs()));
    }
}
