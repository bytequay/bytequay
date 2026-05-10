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

import com.bytequay.app.domain.SlackInboxKind;
import com.bytequay.app.domain.SlackMessage;

import java.util.List;
import java.util.Optional;

/**
 * Local store for messages ingested from Slack. Slice 4 only writes
 * here (via the polling loop and bootstrap). Slice 5+ adds the read
 * paths for the inbox and channel-feed views.
 *
 * <p>The polling layer dedups against the (workspaceId, channelId, ts)
 * primary key — replaying the last fetched message on every tick is
 * expected and must be cheap.
 */
public interface SlackMessageStore
{
    /**
     * Inserts the messages that aren't already stored under their
     * {@code (workspaceId, channelId, ts)} key. Existing rows are left
     * alone — Slack messages are immutable from our perspective (edits
     * would arrive as a separate event we don't subscribe to in v1).
     */
    void insertIfAbsent(List<SlackMessage> messages);

    /** Lookup by the (workspace, channel, ts) primary key. Used by the
     *  inbox-state path to look up a thread-root parent. */
    Optional<SlackMessage> find(String workspaceId, String channelId, String ts);

    /** All messages for a single channel, newest first. Used by the channel-feed view. */
    List<SlackMessage> findByChannel(String workspaceId, String channelId);

    /** All messages in one thread (parent + replies) for a channel,
     *  oldest first. Used by the inbox MENTION expanded view. */
    List<SlackMessage> findByThread(String workspaceId, String channelId, String threadTs);

    /** All messages of one inbox kind across all channels, newest first. Used by the inbox view. */
    List<SlackMessage> findByInboxKind(String workspaceId, SlackInboxKind kind);
}
