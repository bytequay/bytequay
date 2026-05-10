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
package com.bytequay.app.domain;

import java.time.Instant;

/**
 * A Slack channel the user has chosen to follow in full inside ByteQuay's
 * Slack tab. Persisted in the {@code followed_channels} table; selection
 * is per-workspace (Slack {@code team_id}). Slice 4+ uses this list to
 * decide which channels to subscribe to and cache locally.
 */
public record FollowedChannel(
        String workspaceId,
        String channelId,
        String channelName,
        boolean isPrivate,
        Instant selectedAt) {}
