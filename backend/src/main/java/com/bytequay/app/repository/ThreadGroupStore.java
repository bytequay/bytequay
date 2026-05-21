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
package com.bytequay.app.repository;

import com.bytequay.app.domain.ThreadGroup;
import com.bytequay.app.domain.ThreadGroupMembership;

import java.util.List;
import java.util.Optional;

/**
 * Persistence boundary for the {@code thread_groups} table and its
 * {@code thread_group_members} join table that backs the rail's Groups
 * section.
 */
public interface ThreadGroupStore
{
    // ── group rows ────────────────────────────────────────────────────

    /** Insert or update by primary key. */
    void saveGroup(ThreadGroup group);

    Optional<ThreadGroup> findGroupById(String id);

    /** All groups, sorted by {@code sortOrder} then {@code createdAt}. */
    List<ThreadGroup> listGroups();

    /** Drop a group and cascade its membership rows. Tasks themselves
     *  are NOT deleted — they simply leave the group. */
    void deleteGroup(String id);

    // ── group ↔ thread membership ───────────────────────────────────────

    /** Insert a (threadId, groupId) row. Idempotent — re-adding an
     *  existing pair is a no-op. Cap enforcement (4 per group) lives
     *  in the service layer so the caller can return a typed error. */
    void addMember(String threadId, String groupId);

    /** Remove a (threadId, groupId) row. No-op when the pair doesn't
     *  exist. The non-empty-group invariant is enforced in the
     *  service layer so this method stays composable. */
    void removeMember(String threadId, String groupId);

    /** Members of one group, oldest-added first. */
    List<ThreadGroupMembership> listMembers(String groupId);

    /** Groups containing one thread, oldest-added first. */
    List<ThreadGroupMembership> listMemberships(String threadId);

    /** Full membership snapshot, used to feed the frontend's
     *  thread↔group index in a single round-trip. */
    List<ThreadGroupMembership> listAllMemberships();

    /** Member count for invariant checks (cap-at-4 and non-empty). */
    long countMembers(String groupId);
}
