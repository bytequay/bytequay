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
package com.bytequay.app.context;

import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.core.context.ContextDiscussionSupplement;
import com.bytequay.core.context.ContextLimits;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PrTimelineComment;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PrTimelinePayload;
import com.bytequay.core.domain.PullRequest;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewAnchor;
import com.bytequay.core.review.ReviewFinding;
import com.bytequay.core.review.ReviewMessage;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.store.PrDetailStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.IntStream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestContextDiscussionReader
{
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private final AgentReviewStore reviews = mock(AgentReviewStore.class);
    private final PullRequestService pullRequests = mock(PullRequestService.class);
    private final PrDetailStore details = mock(PrDetailStore.class);
    private final AgentReview review = new AgentReview("review", "workspace", "example/repository", 42, OptionalLong.of(1), "task",
            AgentReview.State.OPEN, false, Optional.empty(), Optional.empty(), 0, NOW, NOW, Optional.empty());
    private final ReviewRun run = new ReviewRun("run", "review", 1, ReviewRun.Scope.FULL, "main", "a".repeat(40), "a".repeat(40), "a".repeat(40),
            Optional.empty(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ReviewRun.State.RUNNING,
            Optional.empty(), Optional.empty(), Optional.empty(), ImmutableList.of(), false, ImmutableMap.of(), ImmutableMap.of(),
            Optional.empty(), Optional.empty(), 0, NOW, NOW, Optional.empty());
    private final ContextDiscussionReader reader = new ContextDiscussionReader(reviews, pullRequests, details, Clock.fixed(NOW, ZoneOffset.UTC));

    @Test
    void capturesTargetMessagesAndCachedRepliesWithExactEvidence()
    {
        ReviewMessage question = message("question", "run", "finding", "Explain this");
        when(reviews.listMessages("review")).thenReturn(ImmutableList.of(question,
                message("prior", "run", "finding", "Prior relevant explanation"),
                message("other-run", "old", "finding", "Wrong run"),
                message("other-target", "run", "other", "Wrong target")));
        ReviewFinding finding = new ReviewFinding("finding", "review", "run", 1, "Title", "Body", Optional.empty(),
                Optional.of(new ReviewAnchor("src/Relevant.java", "RIGHT", 1, 1, ImmutableList.of("line"))), ImmutableList.of(), 1, ReviewFinding.State.OPEN,
                Optional.empty(), "a".repeat(40), false, 0, NOW, NOW);
        when(reviews.findFinding("finding")).thenReturn(Optional.of(finding));
        PrTimelineComment reply = new PrTimelineComment(12L, "alice", null, false, NOW, "Cached reply", "https://github.com/example/repository/pull/42#discussion_r12",
                null, null, ImmutableList.of());
        cache(ImmutableList.of(new PrTimelineEvent(1L, "review_thread", "bob", NOW, "Cached root",
                PrTimelinePayload.builder().path("src/Relevant.java").comments(ImmutableList.of(reply)).build(), Optional.empty()),
                new PrTimelineEvent(2L, "review_thread", "bob", NOW.plusSeconds(1), "Other file",
                        PrTimelinePayload.builder().path("src/Other.java").build(), Optional.empty())));

        ContextDiscussionReader.Capture captured = reader.capture(review, run, question, "turn");

        assertThat(captured.supplement().packet().text()).contains("Prior relevant explanation", "Cached root", "Cached reply", "observedHead=", "excerptHash=")
                .doesNotContain("Wrong run", "Wrong target", "Other file");
        assertThat(captured.evidence()).hasSize(3);
        assertThat(captured.supplement().evidenceRefs()).containsExactlyInAnyOrderElementsOf(captured.evidence().keySet());
        captured.evidence().values().forEach(evidence -> assertThat(captured.supplement().packet().text()).contains(evidence.ref(), evidence.excerpt(), evidence.provenance()));
        assertThat(captured.supplement().capturedAt()).isEqualTo(NOW);
        assertThat(captured.supplement().gaps()).anyMatch(gap -> gap.contains("stale, edited or unrelated"));
    }

    @Test
    void aChangedFindingCannotSupplyTheOriginalPathFilter()
    {
        ReviewFinding changed = new ReviewFinding("finding", "review", "run", 1, "Title", "Body", Optional.empty(),
                Optional.of(new ReviewAnchor("src/New.java", "RIGHT", 1, 1, ImmutableList.of("line"))), ImmutableList.of(), 2, ReviewFinding.State.OPEN,
                Optional.empty(), "b".repeat(40), false, 0, NOW, NOW);
        when(reviews.findFinding("finding")).thenReturn(Optional.of(changed));
        cache(ImmutableList.of(new PrTimelineEvent(1L, "review_thread", "alice", NOW, "Older path comment",
                PrTimelinePayload.builder().path("src/Old.java").build(), Optional.empty())));

        ContextDiscussionReader.Capture captured = reader.capture(review, run, message("question", "run", "finding", "Explain"), "turn");

        assertThat(captured.supplement().packet().text()).contains("Older path comment");
        assertThat(captured.supplement().gaps()).anyMatch(gap -> gap.contains("original finding revision is unavailable"));
    }

    @Test
    void missingCacheIsAnExplicitGapWithoutHydration()
    {
        ContextDiscussionReader.Capture captured = reader.capture(review, run, message("question", "run", "finding", "Explain"), "turn");

        assertThat(captured.evidence()).isEmpty();
        assertThat(captured.supplement().gaps()).anyMatch(gap -> gap.contains("no cached discussion detail"));
        verifyNoInteractions(details);
    }

    @Test
    void boundsLargeMultibyteCommentsAndRetainsTheCapturedText()
    {
        cache(IntStream.range(0, 150).mapToObj(index -> new PrTimelineEvent((long) index, "commented", "alice", NOW.plusSeconds(index),
                "界".repeat(5000), Optional.empty())).collect(toImmutableList()));
        when(reviews.listMessages("review")).thenReturn(IntStream.range(0, 10)
                .mapToObj(index -> message("private-" + index, "run", "finding", "界".repeat(5000))).collect(toImmutableList()));

        ContextDiscussionReader.Capture captured = reader.capture(review, run, message("question", "run", "finding", "Explain"), "turn");

        assertThat(captured.evidence()).hasSizeBetween(1, 6);
        assertThat(captured.supplement().packet().utf8Bytes()).isLessThanOrEqualTo(ContextDiscussionSupplement.MAXIMUM_BYTES);
        captured.evidence().values().forEach(evidence -> {
            assertThat(ContextLimits.bytes(evidence.excerpt())).isLessThanOrEqualTo(768);
            assertThat(evidence.provenance()).contains("excerptTruncated=true");
        });
        assertThat(captured.supplement().gaps()).anyMatch(gap -> gap.contains("three most recent private"));
        assertThat(captured.supplement().gaps()).anyMatch(gap -> gap.contains("100 cached events"));
    }

    @Test
    void rejectsAnUnrelatedQuestionBeforeReadingSources()
    {
        assertThatThrownBy(() -> reader.capture(review, run, message("question", "old-run", "finding", "Explain"), "turn"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("another review or run");
        verifyNoInteractions(reviews, pullRequests, details);
    }

    private void cache(List<PrTimelineEvent> events)
    {
        PullRequest row = new PullRequest(1, "example/repository", 42, "Title", "author", "url", NOW, NOW,
                PullRequest.Origin.OTHER, ImmutableList.of(), ImmutableMap.of(), false, null, null, null, ImmutableList.of(), null,
                0, 0, 0, "OPEN", null, null, null, null, null, "branch", null, null, null, null);
        when(pullRequests.findStored("example/repository", 42)).thenReturn(Optional.of(row));
        PrRawDetail raw = new PrRawDetail(null, null, null, null, null, null, null, null, "Body", ImmutableList.of(), null, ImmutableList.of(), null, false, null, "unknown", 0, 0,
                0, 0, ImmutableList.of(), "b".repeat(40), "branch", null);
        when(details.find(1)).thenReturn(Optional.of(new StoredPrDetail(raw, ImmutableList.of(), events, ImmutableList.of(), ImmutableList.of())));
        when(details.findSyncedAt("example/repository", 42)).thenReturn(Optional.of(NOW));
    }

    private static ReviewMessage message(String id, String runId, String findingId, String body)
    {
        return new ReviewMessage(id, "review", Optional.of(findingId), Optional.empty(), ReviewMessage.Author.USER,
                body, 1, runId, Optional.empty(), Optional.empty(), 0, NOW);
    }
}
