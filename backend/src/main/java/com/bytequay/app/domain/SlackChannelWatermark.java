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
 * High-water mark for a single Slack channel or DM conversation. The
 * polling loop hands this to {@code conversations.history?oldest=<ts>}
 * so each tick fetches only what's new.
 *
 * <p>Absence of a row means the bootstrap path hasn't run yet — the
 * caller treats that as "fetch the last 24h, then write the watermark
 * to the latest ts seen."
 */
public record SlackChannelWatermark(
        String workspaceId,
        String channelId,
        String lastTs,
        Instant lastPolledAt) {}
