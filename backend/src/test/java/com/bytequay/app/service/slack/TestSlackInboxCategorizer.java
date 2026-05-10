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
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TestSlackInboxCategorizer
{
    private static final String ME = "U123";

    @Test
    void testDmChannelIdAlwaysClassifiesAsDm()
    {
        // Slack DM ids start with D — even without any text or mention,
        // the channel kind alone is enough.
        SlackInboxKind kind = SlackInboxCategorizer.categorize("D9999", "no mention here", ME, false);
        assertThat(kind).isEqualTo(SlackInboxKind.DM);
    }

    @Test
    void testIsDmContextOverridesNonDmChannelId()
    {
        // The polling loop sets isDm=true when ingesting via the DM
        // path; the categorizer must trust that even if Slack ever
        // returns a non-D-prefixed id for a DM-shaped conversation.
        SlackInboxKind kind = SlackInboxCategorizer.categorize("C1234", "hey there", ME, true);
        assertThat(kind).isEqualTo(SlackInboxKind.DM);
    }

    @Test
    void testMentionInChannelClassifiesAsMention()
    {
        SlackInboxKind kind = SlackInboxCategorizer.categorize(
                "C1234", "Hey <@U123> can you take a look?", ME, false);
        assertThat(kind).isEqualTo(SlackInboxKind.MENTION);
    }

    @Test
    void testMentionWithDisplayFallbackClassifiesAsMention()
    {
        // Slack sometimes renders <@U123|jack> with a display fallback.
        SlackInboxKind kind = SlackInboxCategorizer.categorize(
                "C1234", "ping <@U123|jack> please", ME, false);
        assertThat(kind).isEqualTo(SlackInboxKind.MENTION);
    }

    @Test
    void testMentionOfDifferentUserStaysAsChannel()
    {
        SlackInboxKind kind = SlackInboxCategorizer.categorize(
                "C1234", "Hey <@U999> nice work", ME, false);
        assertThat(kind).isEqualTo(SlackInboxKind.CHANNEL);
    }

    @Test
    void testAtChannelDoesNotCountAsMention()
    {
        // <!channel>/<@channel>/<!here> are out of scope for v1's inbox —
        // they'd flood the inbox in busy channels.
        SlackInboxKind kind = SlackInboxCategorizer.categorize(
                "C1234", "<!channel> deploying now", ME, false);
        assertThat(kind).isEqualTo(SlackInboxKind.CHANNEL);
    }

    @Test
    void testNullTextDoesNotMatchMention()
    {
        // file-share / message-changed payloads can carry text on a
        // sub-object — null at the top level means "not a mention" for
        // categorization purposes.
        assertThat(SlackInboxCategorizer.containsUserMention(null, ME)).isFalse();
        assertThat(SlackInboxCategorizer.categorize("C1234", null, ME, false))
                .isEqualTo(SlackInboxKind.CHANNEL);
    }

    @Test
    void testEmptyAuthedUserDoesNotMatch()
    {
        // Defensive — a missing authed_user id from the OAuth response
        // shouldn't blow up the categorizer.
        assertThat(SlackInboxCategorizer.containsUserMention("hey <@U123>", "")).isFalse();
        assertThat(SlackInboxCategorizer.containsUserMention("hey <@U123>", null)).isFalse();
    }

    @Test
    void testIsDmConversation()
    {
        assertThat(SlackInboxCategorizer.isDmConversation("D123")).isTrue();
        assertThat(SlackInboxCategorizer.isDmConversation("C123")).isFalse();
        assertThat(SlackInboxCategorizer.isDmConversation("G123")).isFalse();
        assertThat(SlackInboxCategorizer.isDmConversation("")).isFalse();
        assertThat(SlackInboxCategorizer.isDmConversation(null)).isFalse();
    }
}
