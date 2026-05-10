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
 * How the inbox view should treat a {@link SlackMessage}. Computed by
 * {@code SlackInboxCategorizer} at write time so the inbox query is a
 * cheap WHERE filter rather than a per-row re-categorisation.
 */
public enum SlackInboxKind
{
    /** {@code <@me>} (or a follow-on user-id mention) appears in the message text. */
    MENTION,
    /** Posted in a DM or group-DM conversation. */
    DM,
    /** Posted in a followed channel; doesn't mention the user. */
    CHANNEL;

    /** Lowercase form persisted in {@code slack_messages.inbox_kind}. */
    public String toDb()
    {
        return name().toLowerCase(Locale.ROOT);
    }
}
