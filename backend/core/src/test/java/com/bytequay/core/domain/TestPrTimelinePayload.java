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
package com.bytequay.core.domain;

import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestPrTimelinePayload
{
    @Test
    void copyRetainsEveryFieldAndChangesOnlyTheSelectedField()
    {
        PrReaction reaction = new PrReaction("HEART", 2, true);
        PrTimelineComment comment = new PrTimelineComment(
                1L, "reviewer", "avatar", false, Instant.EPOCH, "comment", "comment-url", 2L, 3L, ImmutableList.of(reaction));
        PrTimelinePayload original = new PrTimelinePayload(
                "avatar", true, "APPROVED", "url", "reviewer", "assignee", "label", "color", "old", "new",
                "sha", "message", "source", "source-url", 42, "milestone", "file.java", "hunk", 10, 11, 12, 13,
                "RIGHT", true, true, ImmutableList.of(reaction), ImmutableList.of(comment));

        assertThat(PrTimelinePayload.builder(original).build()).isEqualTo(original);
        PrTimelinePayload changed = PrTimelinePayload.builder(original).resolved(false).build();
        assertThat(changed.resolved()).isFalse();
        assertThat(changed).usingRecursiveComparison().ignoringFields("resolved").isEqualTo(original);
        assertThat(original.resolved()).isTrue();
    }

    @Test
    void collectionsDefaultToEmptyButExplicitNullIsRejected()
    {
        assertThat(PrTimelinePayload.empty().reactions()).isEmpty();
        assertThat(PrTimelinePayload.empty().comments()).isEmpty();
        assertThatThrownBy(() -> PrTimelinePayload.builder().reactions(null).build())
                .isInstanceOf(NullPointerException.class).hasMessage("reactions is null");
        assertThatThrownBy(() -> PrTimelinePayload.builder().comments(null).build())
                .isInstanceOf(NullPointerException.class).hasMessage("comments is null");
    }

    @Test
    void collectionsAreSnapshottedOnEachBuildAndSettersReplaceTheirContents()
    {
        PrReaction first = new PrReaction("HEART", 2, true);
        PrReaction second = new PrReaction("HOORAY", 1, false);
        List<PrReaction> reactions = new ArrayList<>();
        reactions.add(first);
        PrTimelinePayload.Builder builder = PrTimelinePayload.builder().reactions(reactions);
        PrTimelinePayload original = builder.build();
        reactions.add(second);

        assertThat(original.reactions()).containsExactly(first);
        assertThat(original.reactions()).isInstanceOf(ImmutableList.class);
        assertThat(builder.build().reactions()).containsExactly(first, second);
        assertThat(builder.reactions(ImmutableList.of(second)).build().reactions()).containsExactly(second);
    }
}
