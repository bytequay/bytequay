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
package com.bytequay.app.review;

import com.bytequay.core.domain.PrTimelineComment;
import com.bytequay.core.domain.PrTimelinePayload;
import com.bytequay.core.domain.PullRequestDetail;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TestReviewOwnThreads
{
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void keepsTheThreadsTheUserStartedUnresolvedFirstThenMostRecentlyActive()
    {
        List<ReviewOwnThreads.Thread> threads = ReviewOwnThreads.of(detail(
                thread(10L, payload(false).resolved(true), comment("me", 100, "Resolved long ago"), comment("author", 900, "Done")),
                thread(20L, payload(false), comment("ME", 200, "Older open thread")),
                thread(30L, payload(false), comment("me", 300, "Newer open thread"), comment("author", 400, "Why?")),
                thread(40L, payload(false), comment("author", 500, "Started by the author"), comment("me", 600, "Agreed")),
                new PullRequestDetail.ActivityItem("me", "commented", NOW, "A top-level comment", 50L, PrTimelinePayload.empty())), "me");

        assertThat(threads).extracting(ReviewOwnThreads.Thread::id).containsExactly("30", "20", "10");
        assertThat(threads.getFirst().awaitingReply()).isTrue();
        assertThat(threads.getFirst().lastActor()).isEqualTo("author");
        assertThat(threads.getFirst().comments()).isEqualTo(2);
        // The user spoke last, or the thread is resolved: nobody waits for the user.
        assertThat(threads.get(1).awaitingReply()).isFalse();
        assertThat(threads.get(2).awaitingReply()).isFalse();
    }

    @Test
    void anOutdatedThreadKeepsItsOriginalLineAndABotReplyDoesNotWaitForTheUser()
    {
        PrTimelineComment bot = new PrTimelineComment(2L, "coderabbit", null, true, NOW.plusSeconds(2), "Looks addressed", null, 7L, 1L, ImmutableList.of());
        List<ReviewOwnThreads.Thread> threads = ReviewOwnThreads.of(detail(
                thread(10L, payload(true), comment("me", 1, "  Rename\n\n this   field.  " + "x".repeat(300)), bot)), "me");

        ReviewOwnThreads.Thread thread = threads.getFirst();
        assertThat(thread.line()).contains(7);
        assertThat(thread.outdated()).isTrue();
        assertThat(thread.awaitingReply()).isFalse();
        assertThat(thread.opening()).startsWith("Rename this field. xxx").hasSize(201).endsWith("…");
        assertThat(ReviewOwnThreads.of(detail(), "me")).isEmpty();
    }

    private static PrTimelinePayload.Builder payload(boolean outdated)
    {
        return PrTimelinePayload.builder().path("src/App.java").line(outdated ? null : 12).originalLine(7).side("RIGHT").outdated(outdated);
    }

    private static PullRequestDetail.ActivityItem thread(long id, PrTimelinePayload.Builder payload, PrTimelineComment... comments)
    {
        return new PullRequestDetail.ActivityItem(comments[0].actor(), "review_thread", NOW, null, id, payload.comments(ImmutableList.copyOf(comments)).build());
    }

    private static PrTimelineComment comment(String actor, int seconds, String body)
    {
        return new PrTimelineComment((long) seconds, actor, null, false, NOW.plusSeconds(seconds), body, null, 7L, null, ImmutableList.of());
    }

    private static PullRequestDetail detail(PullRequestDetail.ActivityItem... activity)
    {
        return new PullRequestDetail(12, "acme/widgets", 42, "Selected pull request", "https://github.com/acme/widgets/pull/42",
                "open", false, null, "unknown", "author", "", NOW, NOW, NOW,
                "main", "feature", "head", "", ImmutableList.of(), ImmutableList.of(), null,
                ImmutableList.of(), 3, 1, 2, 1, PullRequestDetail.CiStatus.PASSING, ImmutableList.copyOf(activity), ImmutableList.of(), ImmutableList.of(), false);
    }

    @Test
    void theOpeningIsOneLineOfTheFirstComment()
    {
        ReviewOwnThreads.Thread thread = ReviewOwnThreads.of(detail(thread(10L, payload(false), comment("me", 1, "Can this go?\nIt repeats the lookup."))), "me").getFirst();

        assertThat(thread.opening()).isEqualTo("Can this go? It repeats the lookup.");
        assertThat(thread.line()).isEqualTo(Optional.of(12));
    }
}
