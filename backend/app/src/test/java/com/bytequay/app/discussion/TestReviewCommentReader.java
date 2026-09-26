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
package com.bytequay.app.discussion;

import com.bytequay.core.discussion.ReviewCommentAnchor;
import com.bytequay.core.domain.PrRawDetail;
import com.bytequay.core.domain.PrReviewState;
import com.bytequay.core.domain.PrTimelineComment;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PrTimelinePayload;
import com.bytequay.core.domain.StoredPrDetail;
import com.bytequay.core.store.PrDetailStore;
import com.bytequay.core.store.PullRequestStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.stream.Collectors.joining;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestReviewCommentReader
{
    private static final Instant NOW = Instant.parse("2026-09-21T00:00:00Z");
    private static final String REPO = "owner/repo";
    private static final String URL = "https://github.com/owner/repo/pull/12";
    private static final ObjectMapper JSON = new ObjectMapper();

    private final PullRequestStore pullRequests = mock(PullRequestStore.class);
    private final PrDetailStore details = mock(PrDetailStore.class);
    private final ReviewCommentReader reader = new ReviewCommentReader(pullRequests, details);

    @Test
    void readsTheThreeKindsOldestFirstWithTheirSnapshots()
            throws Exception
    {
        cache(ImmutableList.of(review(3L, "APPROVED", "sha-review")), ImmutableList.of(
                issueComment(30L, "carol", NOW.plusSeconds(30), "Could this land today?", URL + "#issuecomment-30"),
                reviewed(3L, "alice", NOW.plusSeconds(20), "Please split this."),
                thread(PrTimelinePayload.builder().path("src/A.java").line(null).originalLine(7).outdated(true).diffHunk("@@ -1 +1 @@\n-a\n+b"),
                        comment(10L, "bob", NOW.plusSeconds(10), "Rename it.", 3L))));

        List<ReviewCommentItem> items = reader.read(REPO, 12);

        assertThat(items).extracting(item -> item.anchor().key()).containsExactly("review_comment:10", "review:3", "issue_comment:30");
        assertThat(items).extracting(ReviewCommentItem::author).containsExactly("bob", "alice", "carol");
        assertThat(items.getFirst().createdAt()).contains(NOW.plusSeconds(10));
        assertThat(snapshot(items.get(0))).isEqualTo(JSON.readTree("""
                {"kind":"review_comment","author":"bob","path":"src/A.java","line":7,"outdated":true,"commit":"sha-review","hunk":"@@ -1 +1 @@\\n-a\\n+b",
                 "url":"%s#r10","body":"Rename it."}""".formatted(URL)));
        assertThat(snapshot(items.get(1))).isEqualTo(JSON.readTree("""
                {"kind":"review","author":"alice","state":"CHANGES_REQUESTED","commit":"sha-review","url":"%s#review-3","body":"Please split this."}""".formatted(URL)));
        assertThat(snapshot(items.get(2))).isEqualTo(JSON.readTree("""
                {"kind":"issue_comment","author":"carol","url":"%s#issuecomment-30","body":"Could this land today?"}""".formatted(URL)));
        assertThat(reader.find(REPO, 12, "review:3")).map(ReviewCommentAnchor::snapshot).contains(items.get(1).anchor().snapshot());
        assertThat(reader.find(REPO, 12, "review:30")).isEmpty();
    }

    @Test
    void aReviewResolvesFromItsTimelineEventWhenTheReviewsSectionIsUnavailable()
            throws Exception
    {
        when(pullRequests.findIdByRepoAndNumber(REPO, 12)).thenReturn(Optional.of(12L));
        when(details.find(12L)).thenReturn(Optional.of(new StoredPrDetail(raw(), ImmutableList.of(), ImmutableList.of(reviewed(3L, "alice", NOW, "Please split this.")),
                ImmutableList.of(), ImmutableList.of(StoredPrDetail.REVIEWS))));

        assertThat(reader.find(REPO, 12, "review:3")).hasValueSatisfying(anchor -> assertThat(anchor.snapshot()).doesNotContain("commit"));
    }

    @Test
    void skipsWhatCannotBeKeyed()
    {
        cache(ImmutableList.of(), ImmutableList.of(
                reviewed(null, "alice", NOW, "No id."),
                reviewed(4L, "alice", NOW, "  "),
                issueComment(null, "carol", NOW, "No id either.", null),
                thread(PrTimelinePayload.builder(), comment(null, "bob", NOW, "Pending.", null), comment(11L, "bob", NOW, "Kept.", null))));

        assertThat(reader.read(REPO, 12)).extracting(item -> item.anchor().key()).containsExactly("review_comment:11");
    }

    @Test
    void cutsLongTextAndMarksTheBody()
            throws Exception
    {
        String hunk = IntStream.range(0, 60).mapToObj(line -> "+line " + line).collect(joining("\n"));
        cache(ImmutableList.of(), ImmutableList.of(
                thread(PrTimelinePayload.builder().path("p".repeat(1_500)).diffHunk(hunk), comment(10L, "bob", NOW, "b".repeat(5_000), null)),
                issueComment(30L, "carol", NOW, "c".repeat(5_000), null)));

        List<ReviewCommentItem> items = reader.read(REPO, 12);

        JsonNode comment = snapshot(items.get(0));
        assertThat(comment.path("body").asText()).isEqualTo("b".repeat(4_000) + " [truncated; full text: " + URL + "#r10]");
        assertThat(comment.path("path").asText()).hasSize(1_000);
        assertThat(comment.path("hunk").asText()).startsWith("+line 20\n").endsWith("+line 59");
        assertThat(snapshot(items.get(1)).path("body").asText()).isEqualTo("c".repeat(4_000) + " [truncated]");
    }

    @Test
    void carriesTheLastThreeEarlierCommentsOfTheThreadAndTheirReviewsCommit()
            throws Exception
    {
        List<PrTimelineComment> thread = IntStream.rangeClosed(1, 5)
                .mapToObj(index -> comment((long) index, "user" + index, NOW.plusSeconds(index), "Comment " + index + (index == 2 ? "x".repeat(1_200) : ""), index == 5 ? 8L : null))
                .collect(toImmutableList());
        cache(ImmutableList.of(review(8L, "COMMENTED", "sha-8")), ImmutableList.of(thread(PrTimelinePayload.builder(), thread.toArray(PrTimelineComment[]::new))));

        JsonNode last = snapshot(reader.read(REPO, 12).getLast());

        assertThat(ImmutableList.copyOf(last.path("thread"))).extracting(JsonNode::toString).containsExactly(
                "{\"author\":\"user2\",\"body\":\"Comment 2" + "x".repeat(991) + " [truncated]\"}",
                "{\"author\":\"user3\",\"body\":\"Comment 3\"}",
                "{\"author\":\"user4\",\"body\":\"Comment 4\"}");
        assertThat(last.path("commit").asText()).isEqualTo("sha-8");
        assertThat(snapshot(reader.read(REPO, 12).getFirst()).has("thread")).isFalse();
    }

    @Test
    void refusesAskingAboutACommentOverItsBudgetAndLeavesItOutOfTheList()
    {
        // Each control character serializes as six, so the thread context of the fourth comment carries it over budget.
        String escaped = "\u0001".repeat(3_000);
        cache(ImmutableList.of(), ImmutableList.of(thread(PrTimelinePayload.builder(), comment(10L, "bob", NOW, escaped, null),
                comment(11L, "bob", NOW, escaped, null), comment(12L, "bob", NOW, escaped, null), comment(13L, "bob", NOW, escaped, null))));

        assertThat(reader.read(REPO, 12)).extracting(item -> item.anchor().key()).containsExactly("review_comment:10", "review_comment:11", "review_comment:12");
        assertThatThrownBy(() -> reader.find(REPO, 12, "review_comment:13"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("This comment is too long to ask about");
    }

    @Test
    void anUncachedPullRequestHasNoComments()
    {
        when(pullRequests.findIdByRepoAndNumber(REPO, 12)).thenReturn(Optional.of(12L));
        when(details.find(12L)).thenReturn(Optional.empty());

        assertThat(reader.read(REPO, 12)).isEmpty();
        assertThat(reader.read(REPO, 13)).isEmpty();
    }

    private void cache(List<PrReviewState> reviews, List<PrTimelineEvent> timeline)
    {
        when(pullRequests.findIdByRepoAndNumber(REPO, 12)).thenReturn(Optional.of(12L));
        when(details.find(12L)).thenReturn(Optional.of(new StoredPrDetail(raw(), reviews, timeline, ImmutableList.of(), ImmutableList.of())));
    }

    private static JsonNode snapshot(ReviewCommentItem item)
            throws Exception
    {
        return JSON.readTree(item.anchor().snapshot());
    }

    private static PrRawDetail raw()
    {
        return new PrRawDetail(null, null, null, null, null, null, null, null, "Body", ImmutableList.of(), null, ImmutableList.of(), null, false, null,
                "unknown", 0, 0, 0, 0, ImmutableList.of(), "b".repeat(40), "branch", null);
    }

    private static PrReviewState review(Long id, String state, String commit)
    {
        return new PrReviewState(id, "alice", null, false, state, NOW, "", URL + "#review-" + id, commit);
    }

    private static PrTimelineEvent reviewed(Long id, String actor, Instant at, String body)
    {
        return new PrTimelineEvent(id, "reviewed", actor, at, body, PrTimelinePayload.builder().state("CHANGES_REQUESTED").htmlUrl(URL + "#review-" + id).build(),
                Optional.empty());
    }

    private static PrTimelineEvent issueComment(Long id, String actor, Instant at, String body, String url)
    {
        return new PrTimelineEvent(id, "commented", actor, at, body, PrTimelinePayload.builder().htmlUrl(url).build(), Optional.empty());
    }

    private static PrTimelineEvent thread(PrTimelinePayload.Builder payload, PrTimelineComment... comments)
    {
        return new PrTimelineEvent(comments[0].githubId(), "review_thread", comments[0].actor(), comments[0].timestamp(), null,
                payload.comments(ImmutableList.copyOf(comments)).build(), Optional.empty());
    }

    private static PrTimelineComment comment(Long id, String actor, Instant at, String body, Long reviewId)
    {
        return new PrTimelineComment(id, actor, null, false, at, body, URL + "#r" + id, reviewId, null, ImmutableList.of());
    }
}
