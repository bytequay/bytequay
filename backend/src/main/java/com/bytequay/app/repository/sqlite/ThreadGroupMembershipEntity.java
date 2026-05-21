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

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.util.Objects;

@Entity
@Table(name = "thread_group_members")
class ThreadGroupMembershipEntity
{
    @EmbeddedId
    private MembershipKey id;

    @Column(name = "added_at_ms", nullable = false)
    private long addedAtMs;

    MembershipKey getId() { return id; }
    void setId(MembershipKey id) { this.id = id; }

    long getAddedAtMs() { return addedAtMs; }
    void setAddedAtMs(long addedAtMs) { this.addedAtMs = addedAtMs; }

    @Embeddable
    static class MembershipKey
            implements Serializable
    {
        @Column(name = "thread_id", nullable = false)
        private String threadId;

        @Column(name = "group_id", nullable = false)
        private String groupId;

        MembershipKey() {}

        MembershipKey(String threadId, String groupId)
        {
            this.threadId = threadId;
            this.groupId = groupId;
        }

        String getTaskId() { return threadId; }
        void setTaskId(String threadId) { this.threadId = threadId; }

        String getGroupId() { return groupId; }
        void setGroupId(String groupId) { this.groupId = groupId; }

        @Override
        public boolean equals(Object o)
        {
            if (this == o) {
                return true;
            }
            if (!(o instanceof MembershipKey other)) {
                return false;
            }
            return Objects.equals(threadId, other.threadId)
                    && Objects.equals(groupId, other.groupId);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(threadId, groupId);
        }
    }
}
