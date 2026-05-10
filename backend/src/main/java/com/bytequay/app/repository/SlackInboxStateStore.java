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

import com.bytequay.app.domain.SlackInboxItemState;
import com.bytequay.app.domain.SlackInboxStateRow;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Read/write surface for the per-thread inbox-state machine. The
 * inbox view, the reply path, the polling-loop hook, and the auto-
 * archive sweeper all go through here.
 *
 * <p>An item is "in the inbox" iff its {@code archivedAt} is null —
 * archived items linger in the table to enable the asymmetric
 * resurface rule (a fresh mention pulls them back) but don't render in
 * the inbox stream.
 */
public interface SlackInboxStateStore
{
    Optional<SlackInboxStateRow> find(String workspaceId, String channelId, String ts);

    /** All non-archived rows for a workspace, ordered by recency desc.
     *  The inbox view layers thread context on top of this. */
    List<SlackInboxStateRow> findActive(String workspaceId);

    /** Inserts a fresh UNREAD row iff one doesn't already exist for the key. */
    void createIfAbsent(String workspaceId, String channelId, String ts);

    void markExpanded(String workspaceId, String channelId, String ts, Instant when);

    void markResponded(String workspaceId, String channelId, String ts, Instant when);

    /** Manual or auto-archive — flags the row as archived, {@code state} stays whatever it was. */
    void markArchived(String workspaceId, String channelId, String ts, Instant when);

    /** Pulls an archived row back into the inbox per the asymmetric-resurface rule. */
    void resurrect(String workspaceId, String channelId, String ts, Instant when);

    /** All RESPONDED-and-not-yet-archived rows whose responded_at is older than {@code threshold}.
     *  The archive sweeper consumes this list to set archived_at on each. */
    List<SlackInboxStateRow> findRespondedBefore(String workspaceId, Instant threshold);

    /** Override state without touching timestamps — used by the polling
     *  hook when a new reply lands on an EXPANDED item (no semantic
     *  change yet) but we still want to refresh row touch metadata. */
    void updateState(String workspaceId, String channelId, String ts, SlackInboxItemState state);
}
