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
@Table(name = "slack_channel_watermarks")
class SlackChannelWatermarkEntity
{
    @EmbeddedId
    private SlackChannelWatermarkKey id;

    @Column(name = "last_ts", nullable = false)
    private String lastTs;

    @Column(name = "last_polled_at", nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant lastPolledAt;

    SlackChannelWatermarkKey getId() { return id; }
    void setId(SlackChannelWatermarkKey id) { this.id = id; }

    String getLastTs() { return lastTs; }
    void setLastTs(String lastTs) { this.lastTs = lastTs; }

    Instant getLastPolledAt() { return lastPolledAt; }
    void setLastPolledAt(Instant lastPolledAt) { this.lastPolledAt = lastPolledAt; }

    @Embeddable
    static class SlackChannelWatermarkKey
            implements Serializable
    {
        @Column(name = "workspace_id", nullable = false)
        private String workspaceId;

        @Column(name = "channel_id", nullable = false)
        private String channelId;

        SlackChannelWatermarkKey() {}

        SlackChannelWatermarkKey(String workspaceId, String channelId)
        {
            this.workspaceId = workspaceId;
            this.channelId = channelId;
        }

        String getWorkspaceId() { return workspaceId; }
        void setWorkspaceId(String workspaceId) { this.workspaceId = workspaceId; }

        String getChannelId() { return channelId; }
        void setChannelId(String channelId) { this.channelId = channelId; }

        @Override
        public boolean equals(Object o)
        {
            if (!(o instanceof SlackChannelWatermarkKey other)) {
                return false;
            }
            return Objects.equals(workspaceId, other.workspaceId)
                    && Objects.equals(channelId, other.channelId);
        }

        @Override
        public int hashCode()
        {
            return Objects.hash(workspaceId, channelId);
        }
    }
}
