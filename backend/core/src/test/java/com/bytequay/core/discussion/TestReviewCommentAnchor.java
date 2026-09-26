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
package com.bytequay.core.discussion;

import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestReviewCommentAnchor
{
    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");
    private static final String SNAPSHOT = "{\"kind\":\"review\",\"body\":\"Rename it.\"}";

    @Test
    void keysNameTheCommentKindBecauseGitHubIdsAreUniqueOnlyWithinOne()
    {
        assertThat(new ReviewCommentAnchor("owner/repo", 12, "review:1", SNAPSHOT).kind()).isEqualTo("review");
        assertThat(new ReviewCommentAnchor("owner/repo", 12, "review_comment:2", SNAPSHOT).kind()).isEqualTo("review_comment");
        assertThat(new ReviewCommentAnchor("owner/repo", 12, "issue_comment:3", SNAPSHOT).kind()).isEqualTo("issue_comment");
        assertThatThrownBy(() -> new ReviewCommentAnchor("owner/repo", 12, "comment:1", SNAPSHOT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid comment key");
        assertThatThrownBy(() -> new ReviewCommentAnchor("owner/repo", 12, "review:x", SNAPSHOT))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Invalid comment key");
        assertThatThrownBy(() -> new ReviewCommentAnchor("owner/repo", 12, "review:1", "x".repeat(ReviewCommentAnchor.MAX_SNAPSHOT_CHARACTERS + 1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Comment snapshot exceeds its budget");
    }

    @Test
    void anchorsEveryCommentThreadOnTheCommentTurnByTheKeysHash()
    {
        DiscussionAnchor anchor = new ReviewCommentAnchor("owner/repo", 12, "review_comment:2", SNAPSHOT).anchor();

        assertThat(anchor.turnId()).isEqualTo(ReviewCommentAnchor.TURN);
        assertThat(anchor.quote()).isEqualTo("review_comment:2");
        assertThat(anchor.occurrence()).isEqualTo(1);
        assertThat(anchor.quoteHash()).isEqualTo(ReviewCommentAnchor.hash("review_comment:2")).matches("[0-9a-f]{64}");
        assertThat(ReviewCommentAnchor.hash("review:2")).isNotEqualTo(anchor.quoteHash());
    }

    @Test
    void aCommentThreadHasItsCommentsAnchorAndNoSeedWhileAPassageHasASeed()
    {
        ReviewCommentAnchor comment = new ReviewCommentAnchor("owner/repo", 12, "review:1", SNAPSHOT);
        DiscussionAnchor passage = new DiscussionAnchor("turn-1", "quote", 1, "a".repeat(64));

        assertThat(discussion(comment.anchor(), Optional.of(comment), Optional.empty()).comment()).contains(comment);
        assertThatThrownBy(() -> discussion(passage, Optional.of(comment), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Comment anchor mismatch");
        assertThatThrownBy(() -> discussion(comment.anchor(), Optional.of(comment), Optional.of("--- conversation so far ---")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("comment thread renders it per run");
        assertThatThrownBy(() -> discussion(passage, Optional.empty(), Optional.empty()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("passage discussion stores its seed");
    }

    private static TaskDiscussion discussion(DiscussionAnchor anchor, Optional<ReviewCommentAnchor> comment, Optional<String> seed)
    {
        return new TaskDiscussion("d1", "task-1", anchor, comment, seed, "claude", Optional.empty(), Optional.empty(), ImmutableList.of(), false, NOW, NOW, 0);
    }
}
