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
import jakarta.persistence.Convert;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import java.io.Serializable;
import java.time.Instant;
import java.util.Objects;

@Entity
@Table(name = "slack_dm_conversations")
class SlackDmConversationEntity
{
    @EmbeddedId
    private SlackDmConversationKey id;

    @Column(name = "is_group", nullable = false)
    private boolean isGroup;

    /** Comma-separated list of peer user ids; one entry for IMs, N for MPIMs. */
    @Column(name = "peer_user_ids", nullable = false)
    private String peerUserIds;

    @Column(name = "latest_ts")
    private String latestTs;

    @Column(name = "last_seen_at", nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant lastSeenAt;

    SlackDmConversationKey getId() { return id; }
    void setId(SlackDmConversationKey id) { this.id = id; }

    boolean isGroup() { return isGroup; }
    void setGroup(boolean group) { this.isGroup = group; }

    String getPeerUserIds() { return peerUserIds; }
    void setPeerUserIds(String peerUserIds) { this.peerUserIds = peerUserIds; }

    String getLatestTs() { return latestTs; }
    void setLatestTs(String latestTs) { this.latestTs = latestTs; }

    Instant getLastSeenAt() { return lastSeenAt; }
    void setLastSeenAt(Instant lastSeenAt) { this.lastSeenAt = lastSeenAt; }

    @Embeddable
    static class SlackDmConversationKey
            implements Serializable
    {
        @Column(name = "workspace_id", nullable = false)
        private String workspaceId;

        @Column(name = "conversation_id", nullable = false)
        private String conversationId;

        SlackDmConversationKey() {}

        SlackDmConversationKey(String workspaceId, String conversationId)
        {
            this.workspaceId = workspaceId;
            this.conversationId = conversationId;
        }

        String getWorkspaceId() { return workspaceId; }
        void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }

        String getConversationId() { return conversationId; }
        void setConversationId(String conversationId) { this.conversationId = conversationId; }

        @Override
        public boolean equals(Object o)
        {
            if (!(o instanceof SlackDmConversationKey other)) {
                return false;
            }
            return Objects.equals(workspaceId, other.workspaceId)
                    && Objects.equals(conversationId, other.conversationId);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(workspaceId, conversationId);
        }
    }
}
