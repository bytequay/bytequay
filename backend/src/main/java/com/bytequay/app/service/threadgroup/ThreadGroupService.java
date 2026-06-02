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
package com.bytequay.app.service.threadgroup;

import com.bytequay.app.beans.threadgroup.NewGroupBody;
import com.bytequay.app.beans.threadgroup.PatchGroupBody;
import com.bytequay.app.domain.ThreadGroup;
import com.bytequay.app.domain.ThreadGroupMembership;

import java.util.List;

/**
 * Backend contract for the Groups rail and threads-group page: list /
 * create / rename / delete groups, plus add/remove members.
 *
 * <p>Group ↔ thread membership is many-to-many — one thread can sit in
 * several groups. The frontend pulls the full membership snapshot once
 * via {@link #memberships()} and joins it in memory.
 */
public interface ThreadGroupService
{
    List<ThreadGroup> list();

    /** Single flat list of (threadId, groupId, addedAt) — the frontend
     *  derives thread→groups and group→threads indexes in memory. */
    List<ThreadGroupMembership> memberships();

    /**
     * Create a group with an initial set of members. {@code
     * initialTaskIds} must contain at least one existing thread and no
     * more than the configured per-group member cap; the whole create
     * is transactional in the underlying thread service.
     */
    ThreadGroup create(NewGroupBody body);

    ThreadGroup update(String id, PatchGroupBody body);

    void delete(String id);

    /** Add a thread to a group. Rejects when the group is already at
     *  the configured cap. Idempotent on existing members. */
    void addMember(String groupId, String threadId);

    /** Remove a thread from a group. Rejects when the thread is the
     *  group's only remaining member — callers must delete the group
     *  itself instead. */
    void removeMember(String groupId, String threadId);
}
