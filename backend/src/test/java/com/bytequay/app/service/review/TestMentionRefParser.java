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
package com.bytequay.app.service.review;

import com.bytequay.app.domain.ReviewParticipant;
import com.bytequay.app.domain.ReviewParticipantKind;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestMentionRefParser
{
    private static ReviewParticipant seat(String id, ReviewParticipantKind kind, String label)
    {
        return new ReviewParticipant(id, "pass-1", kind, null, label, null, null, Instant.EPOCH);
    }

    @Test
    void parsesMentionsCaseInsensitivelyOnWordBoundariesAndDedupes()
    {
        MentionRefParser.Parsed p = MentionRefParser.parse(
                "Hey @Claude and @claude, @GPT-5 — what about @panel? Email a@b.com and @@x aren't mentions.");
        assertThat(p.mentionLabels()).containsExactly("claude", "gpt-5", "panel");
    }

    @Test
    void parsesMsgAndFindingRefsButIgnoresReservedCheckpointRefs()
    {
        MentionRefParser.Parsed p = MentionRefParser.parse(
                "See #msg-abc123 and #finding-42, but #cp-9 is reserved and #notearef is plain.");
        assertThat(p.refs()).containsExactly(
                new MentionRefParser.Ref("msg", "abc123"),
                new MentionRefParser.Ref("finding", "42"));
    }

    @Test
    void emptyOrNullTextYieldsNothing()
    {
        assertThat(MentionRefParser.parse(null).mentionLabels()).isEmpty();
        assertThat(MentionRefParser.parse("").refs()).isEmpty();
        assertThat(MentionRefParser.parse("no addressing here").mentionLabels()).isEmpty();
    }

    @Test
    void resolvesLabelsOntoSeatIdsIncludingPanelAndYou()
    {
        ReviewParticipant claude = seat("p-claude", ReviewParticipantKind.REVIEWER, "Claude (Anthropic)");
        ReviewParticipant gpt = seat("p-gpt", ReviewParticipantKind.REVIEWER, "GPT-5");
        ReviewParticipant human = seat("p-you", ReviewParticipantKind.HUMAN, "You");
        List<ReviewParticipant> seats = List.of(claude, gpt, human);

        // @claude → the Claude seat (first-token match); @gpt → GPT-5
        // (starts-with match).
        assertThat(MentionRefParser.resolveMentions(List.of("claude"), seats))
                .containsExactly("p-claude");
        assertThat(MentionRefParser.resolveMentions(List.of("gpt"), seats))
                .containsExactly("p-gpt");
        // @you → the human seat only.
        assertThat(MentionRefParser.resolveMentions(List.of("you"), seats))
                .containsExactly("p-you");
        // @panel → every reviewer seat, not the human.
        assertThat(MentionRefParser.resolveMentions(List.of("panel"), seats))
                .containsExactly("p-claude", "p-gpt");
        // Unknown labels resolve to nothing.
        assertThat(MentionRefParser.resolveMentions(List.of("nobody"), seats)).isEmpty();
    }

    @Test
    void encodesRefsForStorage()
    {
        assertThat(MentionRefParser.encodeRef(new MentionRefParser.Ref("finding", "42")))
                .isEqualTo("finding:42");
    }
}
