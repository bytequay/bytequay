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

import com.bytequay.app.domain.ThreadGroup;
import com.bytequay.app.domain.ThreadGroupMembership;
import com.bytequay.app.repository.ThreadGroupStore;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Component
class SqliteThreadGroupStore
        implements ThreadGroupStore
{
    private final ThreadGroupJpaRepository groups;
    private final ThreadGroupMembershipJpaRepository memberships;

    SqliteThreadGroupStore(
            ThreadGroupJpaRepository groups,
            ThreadGroupMembershipJpaRepository memberships)
    {
        this.groups = requireNonNull(groups, "groups is null");
        this.memberships = requireNonNull(memberships, "memberships is null");
    }

    @Override
    @Transactional
    public void saveGroup(ThreadGroup group)
    {
        ThreadGroupEntity entity = groups.findById(group.id()).orElseGet(ThreadGroupEntity::new);
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
    public Optional<ThreadGroup> findGroupById(String id)
    {
        return groups.findById(id).map(SqliteThreadGroupStore::toGroup);
    }

    @Override
    public List<ThreadGroup> listGroups()
    {
        return groups.findAllByOrderBySortOrderAscCreatedAtMsAsc().stream()
                .map(SqliteThreadGroupStore::toGroup)
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
    public void addMember(String threadId, String groupId)
    {
        requireNonNull(threadId, "threadId is null");
        requireNonNull(groupId, "groupId is null");
        ThreadGroupMembershipEntity.MembershipKey key =
                new ThreadGroupMembershipEntity.MembershipKey(threadId, groupId);
        // Idempotent: skip when the pair already exists so retries
        // from a flaky bridge don't blow up on the PK constraint.
        if (memberships.existsById(key)) {
            return;
        }
        ThreadGroupMembershipEntity row = new ThreadGroupMembershipEntity();
        row.setId(key);
        row.setAddedAtMs(Instant.now().toEpochMilli());
        memberships.save(row);
    }

    @Override
    @Transactional
    public void removeMember(String threadId, String groupId)
    {
        requireNonNull(threadId, "threadId is null");
        requireNonNull(groupId, "groupId is null");
        ThreadGroupMembershipEntity.MembershipKey key =
                new ThreadGroupMembershipEntity.MembershipKey(threadId, groupId);
        if (memberships.existsById(key)) {
            memberships.deleteById(key);
        }
    }

    @Override
    public List<ThreadGroupMembership> listMembers(String groupId)
    {
        return memberships.findByIdGroupIdOrderByAddedAtMsAsc(groupId).stream()
                .map(SqliteThreadGroupStore::toMembership)
                .toList();
    }

    @Override
    public List<ThreadGroupMembership> listMemberships(String threadId)
    {
        return memberships.findByIdThreadIdOrderByAddedAtMsAsc(threadId).stream()
                .map(SqliteThreadGroupStore::toMembership)
                .toList();
    }

    @Override
    public List<ThreadGroupMembership> listAllMemberships()
    {
        return memberships.findAllByOrderByIdGroupIdAscAddedAtMsAsc().stream()
                .map(SqliteThreadGroupStore::toMembership)
                .toList();
    }

    @Override
    public long countMembers(String groupId)
    {
        return memberships.countByIdGroupId(groupId);
    }

    private static ThreadGroup toGroup(ThreadGroupEntity e)
    {
        return new ThreadGroup(
                e.getId(),
                e.getName(),
                e.getGlyph(),
                e.getColor(),
                e.getSortOrder(),
                Instant.ofEpochMilli(e.getCreatedAtMs()),
                Instant.ofEpochMilli(e.getUpdatedAtMs()));
    }

    private static ThreadGroupMembership toMembership(ThreadGroupMembershipEntity e)
    {
        return new ThreadGroupMembership(
                e.getId().getTaskId(),
                e.getId().getGroupId(),
                Instant.ofEpochMilli(e.getAddedAtMs()));
    }
}
