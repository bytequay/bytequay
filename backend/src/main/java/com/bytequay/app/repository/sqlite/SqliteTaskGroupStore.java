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
import com.bytequay.app.domain.TaskGroupMembership;
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
    private final TaskGroupMembershipJpaRepository memberships;

    SqliteTaskGroupStore(
            TaskGroupJpaRepository groups,
            TaskGroupMembershipJpaRepository memberships)
    {
        this.groups = requireNonNull(groups, "groups is null");
        this.memberships = requireNonNull(memberships, "memberships is null");
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
        memberships.deleteByGroupId(id);
        groups.deleteById(id);
    }

    @Override
    @Transactional
    public void addMember(String taskId, String groupId)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(groupId, "groupId is null");
        TaskGroupMembershipEntity.MembershipKey key =
                new TaskGroupMembershipEntity.MembershipKey(taskId, groupId);
        // Idempotent: skip when the pair already exists so retries
        // from a flaky bridge don't blow up on the PK constraint.
        if (memberships.existsById(key)) {
            return;
        }
        TaskGroupMembershipEntity row = new TaskGroupMembershipEntity();
        row.setId(key);
        row.setAddedAtMs(Instant.now().toEpochMilli());
        memberships.save(row);
    }

    @Override
    @Transactional
    public void removeMember(String taskId, String groupId)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(groupId, "groupId is null");
        TaskGroupMembershipEntity.MembershipKey key =
                new TaskGroupMembershipEntity.MembershipKey(taskId, groupId);
        if (memberships.existsById(key)) {
            memberships.deleteById(key);
        }
    }

    @Override
    public List<TaskGroupMembership> listMembers(String groupId)
    {
        return memberships.findByIdGroupIdOrderByAddedAtMsAsc(groupId).stream()
                .map(SqliteTaskGroupStore::toMembership)
                .toList();
    }

    @Override
    public List<TaskGroupMembership> listMemberships(String taskId)
    {
        return memberships.findByIdTaskIdOrderByAddedAtMsAsc(taskId).stream()
                .map(SqliteTaskGroupStore::toMembership)
                .toList();
    }

    @Override
    public List<TaskGroupMembership> listAllMemberships()
    {
        return memberships.findAllByOrderByIdGroupIdAscAddedAtMsAsc().stream()
                .map(SqliteTaskGroupStore::toMembership)
                .toList();
    }

    @Override
    public long countMembers(String groupId)
    {
        return memberships.countByIdGroupId(groupId);
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

    private static TaskGroupMembership toMembership(TaskGroupMembershipEntity e)
    {
        return new TaskGroupMembership(
                e.getId().getTaskId(),
                e.getId().getGroupId(),
                Instant.ofEpochMilli(e.getAddedAtMs()));
    }
}
