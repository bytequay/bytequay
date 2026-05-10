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
 * One row of {@code slack_inbox_state}. Keyed by
 * {@code (workspaceId, channelId, ts)} where {@code ts} is the
 * thread-root ts: for a non-threaded mention/DM that's the message
 * itself; for a threaded mention that's {@code thread_ts}. Multiple
 * messages in the same thread therefore share one inbox row, which is
 * exactly the unit the four-state machine operates on.
 *
 * <p>An item is "archived" iff {@link #archivedAt} is non-null,
 * regardless of {@link #state}. The pair carries the four-state
 * machine semantics on top.
 */
public record SlackInboxStateRow(
        String workspaceId,
        String channelId,
        String ts,
        SlackInboxItemState state,
        Instant archivedAt,
        Instant bumpedAt,
        Instant respondedAt,
        Instant expandedAt)
{
    public boolean isArchived()
    {
        return archivedAt != null;
    }
}
