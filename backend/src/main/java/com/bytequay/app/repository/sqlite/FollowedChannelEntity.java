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
@Table(name = "followed_channels")
class FollowedChannelEntity
{
    @EmbeddedId
    private FollowedChannelKey id;

    @Column(name = "channel_name", nullable = false)
    private String channelName;

    @Column(name = "is_private", nullable = false)
    private boolean isPrivate;

    @Column(name = "selected_at", nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant selectedAt;

    FollowedChannelKey getId() { return id; }
    void setId(FollowedChannelKey id) { this.id = id; }

    String getChannelName() { return channelName; }
    void setChannelName(String channelName) { this.channelName = channelName; }

    boolean isPrivate() { return isPrivate; }
    void setPrivate(boolean aPrivate) { this.isPrivate = aPrivate; }

    Instant getSelectedAt() { return selectedAt; }
    void setSelectedAt(Instant selectedAt) { this.selectedAt = selectedAt; }

    @Embeddable
    static class FollowedChannelKey
            implements Serializable
    {
        @Column(name = "workspace_id", nullable = false)
        private String workspaceId;

        @Column(name = "channel_id", nullable = false)
        private String channelId;

        FollowedChannelKey() {}

        FollowedChannelKey(String workspaceId, String channelId)
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
            if (!(o instanceof FollowedChannelKey other)) {
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
