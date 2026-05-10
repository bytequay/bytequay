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
@Table(name = "slack_messages")
class SlackMessageEntity
{
    @EmbeddedId
    private SlackMessageKey id;

    @Column(name = "user_id")
    private String userId;

    @Column(name = "text")
    private String text;

    @Column(name = "thread_ts")
    private String threadTs;

    @Column(name = "has_at_you", nullable = false)
    private boolean hasAtYou;

    @Column(name = "inbox_kind", nullable = false)
    private String inboxKind;

    @Column(name = "raw_json", nullable = false)
    private String rawJson;

    @Column(name = "fetched_at", nullable = false)
    @Convert(converter = InstantToTextConverter.class)
    private Instant fetchedAt;

    SlackMessageKey getId() { return id; }
    void setId(SlackMessageKey id) { this.id = id; }

    String getUserId() { return userId; }
    void setUserId(String userId) { this.userId = userId; }

    String getText() { return text; }
    void setText(String text) { this.text = text; }

    String getThreadTs() { return threadTs; }
    void setThreadTs(String threadTs) { this.threadTs = threadTs; }

    boolean isHasAtYou() { return hasAtYou; }
    void setHasAtYou(boolean hasAtYou) { this.hasAtYou = hasAtYou; }

    String getInboxKind() { return inboxKind; }
    void setInboxKind(String inboxKind) { this.inboxKind = inboxKind; }

    String getRawJson() { return rawJson; }
    void setRawJson(String rawJson) { this.rawJson = rawJson; }

    Instant getFetchedAt() { return fetchedAt; }
    void setFetchedAt(Instant fetchedAt) { this.fetchedAt = fetchedAt; }

    @Embeddable
    static class SlackMessageKey
            implements Serializable
    {
        @Column(name = "workspace_id", nullable = false)
        private String workspaceId;

        @Column(name = "channel_id", nullable = false)
        private String channelId;

        @Column(name = "ts", nullable = false)
        private String ts;

        SlackMessageKey() {}

        SlackMessageKey(String workspaceId, String channelId, String ts)
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
            if (!(o instanceof SlackMessageKey other)) {
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
