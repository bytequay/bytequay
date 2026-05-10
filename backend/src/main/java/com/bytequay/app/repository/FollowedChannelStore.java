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

import com.bytequay.app.domain.FollowedChannel;

import java.util.List;

/**
 * Local store for Slack channel-follow choices. One row per
 * (workspace_id, channel_id); the picker overwrites the whole set
 * idempotently via {@link #replace}.
 */
public interface FollowedChannelStore
{
    /** All followed channels for a given workspace, in insertion order. */
    List<FollowedChannel> findByWorkspace(String workspaceId);

    /**
     * Replaces the followed-channel set for {@code workspaceId} with
     * {@code channels}. Removes rows not present in the new set,
     * upserts the rest. Channels passed in carry the names + privacy
     * flag we'll show on the sidebar without re-fetching from Slack.
     */
    void replace(String workspaceId, List<FollowedChannel> channels);
}
