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
package com.bytequay.app.service.slack;

import com.bytequay.app.domain.SlackInboxKind;

/**
 * Classifies an incoming Slack message for the inbox view. Pure
 * function so the polling loop, the bootstrap, and Slice 5's tests can
 * all share the same logic.
 *
 * <p>Rules:
 * <ul>
 *   <li>Anything in a DM or group-DM conversation is {@link SlackInboxKind#DM}.</li>
 *   <li>Otherwise, if the message text contains {@code <@USER_ID>} for the
 *       authenticated user it's {@link SlackInboxKind#MENTION}.</li>
 *   <li>Otherwise it's {@link SlackInboxKind#CHANNEL}.</li>
 * </ul>
 *
 * <p>Subteam ({@code <!subteam^...>}), {@code @channel}, and
 * {@code @here} mentions are intentionally out of scope per
 * {@code scopes.md}'s v1 minimal-mention list.
 */
public final class SlackInboxCategorizer
{
    private SlackInboxCategorizer() {}

    /** True iff the conversation id is one Slack uses for IMs/MPIMs. */
    public static boolean isDmConversation(String channelId)
    {
        if (channelId == null || channelId.isEmpty()) {
            return false;
        }
        char prefix = channelId.charAt(0);
        // Slack's published id-prefix vocabulary: D = DM, G = legacy
        // private-or-mpim (we differentiate elsewhere via is_mpim), C =
        // public channel. Treating G as non-DM here is safe because the
        // followed-channel path is the only thing that ingests Gxxx
        // ids — DM ingestion comes from users.conversations(types=im,mpim).
        return prefix == 'D';
    }

    public static SlackInboxKind categorize(String channelId, String text, String authedUserId, boolean isDmContext)
    {
        if (isDmContext || isDmConversation(channelId)) {
            return SlackInboxKind.DM;
        }
        if (containsUserMention(text, authedUserId)) {
            return SlackInboxKind.MENTION;
        }
        return SlackInboxKind.CHANNEL;
    }

    /** Public so the message store can also stamp {@code has_at_you} the same way. */
    public static boolean containsUserMention(String text, String authedUserId)
    {
        if (text == null || authedUserId == null || authedUserId.isEmpty()) {
            return false;
        }
        // Slack renders `@you` in a message as `<@U123>` (or `<@U123|fallback>`
        // when a display-name fallback is present). Match either shape
        // without a regex — substring scans are enough and avoid the
        // class-loader overhead of Pattern compilation on the hot path.
        String needle = "<@" + authedUserId;
        int idx = text.indexOf(needle);
        if (idx < 0) {
            return false;
        }
        int after = idx + needle.length();
        if (after >= text.length()) {
            return false;
        }
        char terminator = text.charAt(after);
        return terminator == '>' || terminator == '|';
    }
}
