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
import com.bytequay.app.domain.StoredPrDetail;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestPrAttention
{
    private static final Instant NOW = Instant.parse("2026-04-26T10:00:00Z");

    @Test
    void unseenThirdPartyMentionPromotesThePullRequest()
    {
        StoredPrDetail detail = detail(
                List.of(comment("bob", "hey @Alice", NOW.minusSeconds(1))), List.of(), raw(true, "clean"));

        assertThat(PrAttention.promoteReason(pr(PullRequest.Origin.REVIEW_REQUESTED), detail, "alice",
                NOW.minusSeconds(2), NOW)).isEqualTo(AttentionReason.MENTIONED);
        assertThat(PrAttention.promoteReason(pr(PullRequest.Origin.REVIEW_REQUESTED), detail, "alice",
                NOW, NOW)).isNull();
    }

    @Test
    void failingCiTakesPriority()
    {
        StoredPrDetail detail = detail(
                List.of(),
                List.of(new PrCheckRunState(1L, "ci", "completed", "failure")),
                raw(false, "dirty"));

        assertThat(PrAttention.promoteReason(pr(PullRequest.Origin.AUTHORED), detail, "alice", NOW, NOW))
                .isEqualTo(AttentionReason.CI_FAILING);
    }

    @Test
    void authoredPullRequestsFallBackToMine()
    {
        assertThat(PrAttention.promoteReason(
                pr(PullRequest.Origin.AUTHORED), detail(List.of(), List.of(), raw(true, "clean")),
                "alice", NOW, NOW)).isEqualTo(AttentionReason.MINE);
    }

    @Test
    void countsTimelineComments()
    {
        StoredPrDetail detail = detail(
                List.of(comment("bob", "one", NOW), comment("carol", "two", NOW)), List.of(), raw(true, "clean"));
        assertThat(PrAttention.countComments(detail)).isEqualTo(2);
    }

    private static StoredPrDetail detail(
            List<PrTimelineEvent> timeline, List<PrCheckRunState> checks, PrRawDetail raw)
    {
        return new StoredPrDetail(raw, List.of(), timeline, checks);
    }

    private static PrTimelineEvent comment(String actor, String body, Instant timestamp)
    {
        return new PrTimelineEvent(null, "commented", actor, timestamp, body);
    }

    private static PrRawDetail raw(boolean mergeable, String mergeableState)
    {
        return new PrRawDetail(
                null, List.of(), false, mergeable, mergeableState, 0, 0, 0, 0, List.of(),
                "head", null);
    }

    private static PullRequest pr(PullRequest.Origin origin)
    {
        return new PullRequest(
                1, "owner/repo", 7, "title", "alice", "url", null, NOW, origin,
                List.of(), null, false, null, null, null, List.of(), null,
                0, 0, 0, null, "open", null, null, null, null, null, null, null);
    }
}
