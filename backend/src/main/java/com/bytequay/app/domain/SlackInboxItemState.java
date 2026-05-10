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

import java.util.Locale;

/**
 * The user-visible four-state machine for an inbox item, per the Slack
 * design doc. {@code archived_at} on the row is orthogonal — when set,
 * the item is hidden from the inbox view regardless of state.
 *
 * <ul>
 *   <li>{@link #UNREAD} — initial state when the polling loop creates
 *       a row from a fresh MENTION or DM message.</li>
 *   <li>{@link #EXPANDED} — user opened it. Persisted so a refresh
 *       doesn't snap an opened item back to bold.</li>
 *   <li>{@link #RESPONDED} — user replied via the inline reply box.
 *       Auto-archives 4h after {@code responded_at}.</li>
 *   <li>{@link #BUMPED} — was archived but a fresh ping pulled it
 *       back. {@code archived_at} clears, {@code bumped_at} fills,
 *       {@code state} flips to BUMPED.</li>
 * </ul>
 */
public enum SlackInboxItemState
{
    UNREAD, EXPANDED, RESPONDED, BUMPED;

    /** Lowercase form persisted in {@code slack_inbox_state.state}. */
    public String toDb()
    {
        return name().toLowerCase(Locale.ROOT);
    }

    public static SlackInboxItemState fromDb(String s)
    {
        return SlackInboxItemState.valueOf(s.toUpperCase(Locale.ROOT));
    }
}
