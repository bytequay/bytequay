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
@Table(name = "slack_inbox_state")
class SlackInboxStateEntity
{
    @EmbeddedId
    private SlackInboxStateKey id;

    @Column(name = "state", nullable = false)
    private String state;

    @Column(name = "archived_at")
    @Convert(converter = InstantToTextConverter.class)
    private Instant archivedAt;

    @Column(name = "bumped_at")
    @Convert(converter = InstantToTextConverter.class)
    private Instant bumpedAt;

    @Column(name = "responded_at")
    @Convert(converter = InstantToTextConverter.class)
    private Instant respondedAt;

    @Column(name = "expanded_at")
    @Convert(converter = InstantToTextConverter.class)
    private Instant expandedAt;

    SlackInboxStateKey getId() { return id; }
    void setId(SlackInboxStateKey id) { this.id = id; }

    String getState() { return state; }
    void setState(String state) { this.state = state; }

    Instant getArchivedAt() { return archivedAt; }
    void setArchivedAt(Instant archivedAt) { this.archivedAt = archivedAt; }

    Instant getBumpedAt() { return bumpedAt; }
    void setBumpedAt(Instant bumpedAt) { this.bumpedAt = bumpedAt; }

    Instant getRespondedAt() { return respondedAt; }
    void setRespondedAt(Instant respondedAt) { this.respondedAt = respondedAt; }

    Instant getExpandedAt() { return expandedAt; }
    void setExpandedAt(Instant expandedAt) { this.expandedAt = expandedAt; }

    @Embeddable
    static class SlackInboxStateKey
            implements Serializable
    {
        @Column(name = "workspace_id", nullable = false)
        private String workspaceId;

        @Column(name = "channel_id", nullable = false)
        private String channelId;

        @Column(name = "ts", nullable = false)
        private String ts;

        SlackInboxStateKey() {}

        SlackInboxStateKey(String workspaceId, String channelId, String ts)
        {
            this.workspaceId = workspaceId;
            this.channelId = channelId;
            this.ts = ts;
        }

        String getWorkspaceId() { return workspaceId; }
        void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }

        String getChannelId() { return channelId; }
        void setChannelId(String channelId) { this.channelId = channelId; }

        String getTs() { return ts; }
        void setTs(String ts) { this.ts = ts; }

        @Override
        public boolean equals(Object o)
        {
            if (!(o instanceof SlackInboxStateKey other)) {
                return false;
            }
            return Objects.equals(workspaceId, other.workspaceId)
                    && Objects.equals(channelId, other.channelId)
                    && Objects.equals(ts, other.ts);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(workspaceId, channelId, ts);
        }
    }
}
