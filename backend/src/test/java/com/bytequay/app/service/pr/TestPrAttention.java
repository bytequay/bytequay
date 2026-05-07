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
package com.bytequay.app.service.pr;

import com.bytequay.app.domain.AttentionReason;
import com.bytequay.app.domain.PrCheckRunState;
import com.bytequay.app.domain.PrRawDetail;
import com.bytequay.app.domain.PrTimelineEvent;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.Reactions;
import com.bytequay.app.domain.StoredPrDetail;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestPrAttention
{
    private static final Instant NOW = Instant.parse("2026-04-26T10:00:00Z");
    private static final String ME = "alice";

    @Test
    void testMentionAfterViewedPromotes()
    {
        Instant viewedAt = NOW.minus(Duration.ofHours(2));
        PrTimelineEvent comment = commented("bob", NOW.minusSeconds(60), "hey @alice can you take a look?");
        StoredPrDetail detail = detailWith(ImmutableList.of(comment));

        AttentionReason reason = PrAttention.promoteReason(pr(), detail, ME, viewedAt, NOW);
        assertThat(reason).isEqualTo(AttentionReason.MENTIONED);
    }

    @Test
    void testMentionBeforeViewedIsIgnored()
    {
        Instant viewedAt = NOW.minus(Duration.ofMinutes(30));
        PrTimelineEvent stale = commented("bob", NOW.minus(Duration.ofHours(2)), "hey @alice");
        StoredPrDetail detail = detailWith(ImmutableList.of(stale));

        AttentionReason reason = PrAttention.promoteReason(pr(), detail, ME, viewedAt, NOW);
        assertThat(reason).isNull();
    }

    @Test
    void testNeverViewedAnyMentionPromotes()
    {
        PrTimelineEvent old = commented("bob", NOW.minus(Duration.ofDays(3)), "hi @alice");
        StoredPrDetail detail = detailWith(ImmutableList.of(old));

        AttentionReason reason = PrAttention.promoteReason(pr(), detail, ME, null, NOW);
        assertThat(reason).isEqualTo(AttentionReason.MENTIONED);
    }

    @Test
    void testSelfMentionDoesNotPromote()
    {
        PrTimelineEvent selfNote = commented("alice", NOW.minusSeconds(60), "@alice will follow up");
        StoredPrDetail detail = detailWith(ImmutableList.of(selfNote));

        AttentionReason reason = PrAttention.promoteReason(pr(), detail, ME, null, NOW);
        assertThat(reason).isNull();
    }

    @Test
    void testMentionMatchesAreCaseInsensitive()
    {
        PrTimelineEvent comment = commented("bob", NOW.minusSeconds(60), "thanks @ALICE");
        StoredPrDetail detail = detailWith(ImmutableList.of(comment));

        AttentionReason reason = PrAttention.promoteReason(pr(), detail, ME, null, NOW);
        assertThat(reason).isEqualTo(AttentionReason.MENTIONED);
    }

    @Test
    void testMentionMustBeWordBoundary()
    {
        // "@alicebot" is a different user — must not match "@alice".
        PrTimelineEvent decoy = commented("bob", NOW.minusSeconds(60), "I'll ping @alicebot for the rollout");
        StoredPrDetail detail = detailWith(ImmutableList.of(decoy));

        AttentionReason reason = PrAttention.promoteReason(pr(), detail, ME, null, NOW);
        assertThat(reason).isNull();
    }

    @Test
    void testCiFailingTakesPrecedenceOverMention()
    {
        PrTimelineEvent comment = commented("bob", NOW.minusSeconds(60), "hi @alice");
        StoredPrDetail detail = new StoredPrDetail(
                rawDetail(),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(comment),
                ImmutableList.of(new PrCheckRunState(null, "ci", "completed", "failure", null, null, null)),
                ImmutableList.of(),
                ImmutableList.of());

        AttentionReason reason = PrAttention.promoteReason(pr(), detail, ME, null, NOW);
        assertThat(reason).isEqualTo(AttentionReason.CI_FAILING);
    }

    @Test
    void testNullCurrentLoginNeverMentions()
    {
        PrTimelineEvent comment = commented("bob", NOW.minusSeconds(60), "hi @alice");
        StoredPrDetail detail = detailWith(ImmutableList.of(comment));

        AttentionReason reason = PrAttention.promoteReason(pr(), detail, null, null, NOW);
        assertThat(reason).isNull();
    }

    private static PullRequest pr()
    {
        return prWith(PullRequest.Origin.REVIEW_REQUESTED);
    }

    private static PullRequest prWith(PullRequest.Origin origin)
    {
        return new PullRequest(
                1L, "acme/widgets", 42, "Title", "carol",
                "https://x", null, NOW.minus(Duration.ofMinutes(5)),
                origin,
                ImmutableList.of(), null, false,
                null, null, null, ImmutableList.of(),
                null, 0, 0, 0, null,
                "open", null, null, null, null, null, null,
                null, null);
    }

    @Test
    void testMineFiresForAuthoredPrWithNothingElse()
    {
        StoredPrDetail detail = detailWith(ImmutableList.of());
        AttentionReason reason = PrAttention.promoteReason(prWith(PullRequest.Origin.AUTHORED), detail, ME, NOW, NOW);
        assertThat(reason).isEqualTo(AttentionReason.MINE);
    }

    @Test
    void testMineDoesNotFireForReviewRequestedPr()
    {
        StoredPrDetail detail = detailWith(ImmutableList.of());
        AttentionReason reason = PrAttention.promoteReason(prWith(PullRequest.Origin.REVIEW_REQUESTED), detail, ME, NOW, NOW);
        assertThat(reason).isNull();
    }

    @Test
    void testMergeConflictFiresOnAuthoredPr()
    {
        StoredPrDetail detail = new StoredPrDetail(
                new PrRawDetail(null, ImmutableList.of(), false, false, "dirty", 0, 0, 0, 0, ImmutableList.of(), "abc", null, null, null, null),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of());
        AttentionReason reason = PrAttention.promoteReason(prWith(PullRequest.Origin.AUTHORED), detail, ME, NOW, NOW);
        assertThat(reason).isEqualTo(AttentionReason.MERGE_CONFLICT);
    }

    @Test
    void testMergeConflictDoesNotFireOnReviewRequestedPr()
    {
        StoredPrDetail detail = new StoredPrDetail(
                new PrRawDetail(null, ImmutableList.of(), false, false, "dirty", 0, 0, 0, 0, ImmutableList.of(), "abc", null, null, null, null),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of());
        AttentionReason reason = PrAttention.promoteReason(prWith(PullRequest.Origin.REVIEW_REQUESTED), detail, ME, NOW, NOW);
        // Reviewer can't fix the conflict; falls through to MINE-or-null;
        // since this PR is REVIEW_REQUESTED, MINE doesn't fire either.
        assertThat(reason).isNull();
    }

    @Test
    void testNewCommentFiresOnAuthoredPrAfterViewedAt()
    {
        Instant viewedAt = NOW.minus(Duration.ofHours(1));
        PrTimelineEvent fresh = commented("bob", NOW.minus(Duration.ofMinutes(30)), "looking good");
        StoredPrDetail detail = detailWith(ImmutableList.of(fresh));
        AttentionReason reason = PrAttention.promoteReason(prWith(PullRequest.Origin.AUTHORED), detail, ME, viewedAt, NOW);
        assertThat(reason).isEqualTo(AttentionReason.NEW_COMMENT);
    }

    @Test
    void testNewCommentDoesNotFireForOwnComment()
    {
        Instant viewedAt = NOW.minus(Duration.ofHours(1));
        PrTimelineEvent self = commented(ME, NOW.minus(Duration.ofMinutes(10)), "ping");
        StoredPrDetail detail = detailWith(ImmutableList.of(self));
        AttentionReason reason = PrAttention.promoteReason(prWith(PullRequest.Origin.AUTHORED), detail, ME, viewedAt, NOW);
        // Falls through to MINE since no third-party activity.
        assertThat(reason).isEqualTo(AttentionReason.MINE);
    }

    @Test
    void testCiFailingTakesPrecedenceOverMergeConflictAndMine()
    {
        StoredPrDetail detail = new StoredPrDetail(
                new PrRawDetail(null, ImmutableList.of(), false, false, "dirty", 0, 0, 0, 0, ImmutableList.of(), "abc", null, null, null, null),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                ImmutableList.of(new PrCheckRunState(null, "ci", "completed", "failure", null, null, null)),
                ImmutableList.of(), ImmutableList.of());
        AttentionReason reason = PrAttention.promoteReason(prWith(PullRequest.Origin.AUTHORED), detail, ME, NOW, NOW);
        assertThat(reason).isEqualTo(AttentionReason.CI_FAILING);
    }

    private static PrTimelineEvent commented(String actor, Instant timestamp, String body)
    {
        return new PrTimelineEvent(null, "commented", actor, null, timestamp, body, null, null, null, null, null, Reactions.EMPTY);
    }

    private static StoredPrDetail detailWith(List<PrTimelineEvent> timeline)
    {
        return new StoredPrDetail(
                rawDetail(),
                ImmutableList.of(),
                ImmutableList.of(),
                timeline,
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of());
    }

    private static PrRawDetail rawDetail()
    {
        return new PrRawDetail(null, ImmutableList.of(), false, null, null, 0, 0, 0, 0, ImmutableList.of(), "abc", null, null, null, null);
    }
}
