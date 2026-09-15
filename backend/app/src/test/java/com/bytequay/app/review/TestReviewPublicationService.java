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

import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.core.domain.InlineReviewComment;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.PullRequestDetail.CiStatus;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewAnchor;
import com.bytequay.core.review.ReviewConflictException;
import com.bytequay.core.review.ReviewDraft;
import com.bytequay.core.review.ReviewDraftItem;
import com.bytequay.core.review.ReviewDraftItem.Source;
import com.bytequay.core.review.ReviewDraftStore;
import com.bytequay.core.review.ReviewFinding;
import com.bytequay.core.review.ReviewMessage;
import com.bytequay.core.review.ReviewPublication;
import com.bytequay.core.review.ReviewRun;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

/**
 * The pending review is local until the user sends it. Every test that exercises a private action
 * asserts the GitHub-facing service was never touched at all, because that boundary is the whole
 * safety property of this service.
 */
class TestReviewPublicationService
{
    private static final Instant NOW = Instant.parse("2026-09-06T10:15:00Z");
    private static final String REPO = "octo/widgets";
    private static final int NUMBER = 7;
    private static final long PR_ID = 4242L;
    private static final String REVIEW_ID = "review-1";
    private static final String RUN_ID = "run-1";
    private static final String FINDING_ID = "finding-1";
    private static final String REVIEWED_HEAD = "a".repeat(40);
    private static final String CURRENT_HEAD = "b".repeat(40);

    private InMemoryReviewDraftStore drafts;
    private InMemoryAgentReviewStore reviews;
    private PullRequestService pullRequests;
    private DefaultReviewPublicationService service;

    @BeforeEach
    void setUp()
    {
        drafts = new InMemoryReviewDraftStore();
        reviews = new InMemoryAgentReviewStore();
        pullRequests = mock(PullRequestService.class);
        service = new DefaultReviewPublicationService(drafts, reviews, pullRequests, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void aPullRequestWithoutADraftGetsAnEmptyCommentReview()
    {
        ReviewDraft draft = service.draft(REPO, NUMBER);

        assertThat(draft.repo()).isEqualTo(REPO);
        assertThat(draft.number()).isEqualTo(NUMBER);
        assertThat(draft.items()).isEmpty();
        assertThat(draft.body()).isEmpty();
        assertThat(draft.version()).isZero();
        assertThat(draft.event()).isEqualTo("COMMENT").isNotEqualTo("APPROVE");
        assertThat(ReviewDraft.DEFAULT_EVENT).isEqualTo("COMMENT");
        verifyNoInteractions(pullRequests);
    }

    @Test
    void everyLocalEditBumpsTheVersionAndNeverReachesGitHub()
    {
        reviews.put(finding(FINDING_ID, 1, "src/Service.java", 118, 131, "Guard the null case"));

        ReviewDraft afterManual = service.addManual(REPO, NUMBER, 0, manual("src/Main.java", 12, "Rename this"));
        ReviewDraft afterFinding = service.addFinding(REPO, NUMBER, afterManual.version(), REVIEW_ID, FINDING_ID);
        ReviewDraft afterEdit = service.editItem(REPO, NUMBER, afterFinding.version(), lastItemId(afterFinding), "Reworded", true);
        ReviewDraft afterSave = service.saveReview(REPO, NUMBER, afterEdit.version(), "Overall notes", "REQUEST_CHANGES");
        ReviewDraft afterRemove = service.removeItem(REPO, NUMBER, afterSave.version(), lastItemId(afterSave));

        assertThat(ImmutableList.of(afterManual.version(), afterFinding.version(), afterEdit.version(), afterSave.version(), afterRemove.version()))
                .containsExactly(1L, 2L, 3L, 4L, 5L);
        assertThat(afterRemove.items()).hasSize(1);
        verifyNoInteractions(pullRequests);
    }

    @Test
    void addFindingRefusesAFindingThatCannotBePublished()
    {
        ReviewFinding open = finding(FINDING_ID, 1, "src/Service.java", 118, 131, "Guard the null case");

        reviews.put(open.withState(ReviewFinding.State.RESOLVED, Optional.of("fixed it"), NOW));
        assertThatThrownBy(() -> service.addFinding(REPO, NUMBER, 0, REVIEW_ID, FINDING_ID))
                .isInstanceOf(ReviewConflictException.class)
                .hasMessageContaining("You resolved this finding locally");

        reviews.put(open.withState(ReviewFinding.State.WITHDRAWN, Optional.of("misread the diff"), NOW));
        assertThatThrownBy(() -> service.addFinding(REPO, NUMBER, 0, REVIEW_ID, FINDING_ID))
                .isInstanceOf(ReviewConflictException.class)
                .hasMessageContaining("The agent withdrew this finding");

        reviews.put(open.withMapping(CURRENT_HEAD, true, NOW));
        assertThatThrownBy(() -> service.addFinding(REPO, NUMBER, 0, REVIEW_ID, FINDING_ID))
                .isInstanceOf(ReviewConflictException.class)
                .hasMessageContaining("New commits moved these lines");

        assertThat(service.draft(REPO, NUMBER).items()).isEmpty();
        verifyNoInteractions(pullRequests);
    }

    @Test
    void addFindingSnapshotsTheFindingWithoutTouchingIt()
    {
        ReviewFinding open = finding(FINDING_ID, 1, "src/Service.java", 118, 131, "Guard the null case");
        reviews.put(open);

        ReviewDraft draft = service.addFinding(REPO, NUMBER, 0, REVIEW_ID, FINDING_ID);

        ReviewDraftItem item = draft.items().getFirst();
        assertThat(item.source()).isEqualTo(Source.AGENT_FINDING);
        assertThat(item.findingId()).contains(FINDING_ID);
        assertThat(item.findingRevision()).hasValue(1);
        assertThat(item.path()).isEqualTo("src/Service.java");
        assertThat(item.side()).isEqualTo("RIGHT");
        assertThat(item.line()).isEqualTo(131);
        assertThat(item.startLine()).hasValue(118);
        assertThat(item.body()).isEqualTo("Guard the null case");
        assertThat(item.included()).isTrue();
        assertThat(item.publication()).isEmpty();
        assertThat(reviews.findFinding(FINDING_ID)).contains(open);
        verifyNoInteractions(pullRequests);
    }

    @Test
    void editingAPromotedItemLeavesTheFindingAlone()
    {
        ReviewFinding open = finding(FINDING_ID, 1, "src/Service.java", 118, 131, "Guard the null case");
        reviews.put(open);
        ReviewDraft draft = service.addFinding(REPO, NUMBER, 0, REVIEW_ID, FINDING_ID);

        ReviewDraft edited = service.editItem(REPO, NUMBER, draft.version(), lastItemId(draft), "  Guard it, and say why  ", true);

        assertThat(edited.items().getFirst().body()).isEqualTo("Guard it, and say why");
        assertThat(reviews.findFinding(FINDING_ID)).contains(open);
        verifyNoInteractions(pullRequests);
    }

    @Test
    void ineligibleExplainsEveryItemWhoseFindingMovedOn()
    {
        ReviewFinding resolved = finding("f-resolved", 1, "src/A.java", 10, 10, "Resolved later");
        ReviewFinding withdrawn = finding("f-withdrawn", 2, "src/B.java", 20, 20, "Withdrawn later");
        ReviewFinding outdated = finding("f-outdated", 3, "src/C.java", 30, 30, "Outdated later");
        ReviewFinding revised = finding("f-revised", 4, "src/D.java", 40, 40, "Revised later");
        ImmutableList.of(resolved, withdrawn, outdated, revised).forEach(reviews::put);

        ReviewDraft draft = service.addManual(REPO, NUMBER, 0, manual("src/Main.java", 12, "Still fine"));
        String manualItem = lastItemId(draft);
        draft = service.addFinding(REPO, NUMBER, draft.version(), REVIEW_ID, "f-resolved");
        String resolvedItem = lastItemId(draft);
        draft = service.addFinding(REPO, NUMBER, draft.version(), REVIEW_ID, "f-withdrawn");
        String withdrawnItem = lastItemId(draft);
        draft = service.addFinding(REPO, NUMBER, draft.version(), REVIEW_ID, "f-outdated");
        String outdatedItem = lastItemId(draft);
        draft = service.addFinding(REPO, NUMBER, draft.version(), REVIEW_ID, "f-revised");
        String revisedItem = lastItemId(draft);

        reviews.put(resolved.withState(ReviewFinding.State.RESOLVED, Optional.of("fixed it"), NOW));
        reviews.put(withdrawn.withState(ReviewFinding.State.WITHDRAWN, Optional.of("misread the diff"), NOW));
        reviews.put(outdated.withMapping(CURRENT_HEAD, true, NOW));
        reviews.put(revised.revised("Revised title", "Rewritten body", Optional.empty(), ImmutableList.of(), NOW));

        Map<String, String> reasons = service.ineligible(REPO, NUMBER).stream()
                .collect(toImmutableMap(ReviewPublicationService.Ineligible::itemId, ReviewPublicationService.Ineligible::reason));

        assertThat(reasons).hasSize(4).doesNotContainKey(manualItem);
        assertThat(reasons.get(resolvedItem)).contains("You resolved this finding locally");
        assertThat(reasons.get(withdrawnItem)).contains("The agent withdrew this finding");
        assertThat(reasons.get(outdatedItem)).contains("New commits moved these lines");
        assertThat(reasons.get(revisedItem)).contains("The agent revised this finding after you added it");
        verifyNoInteractions(pullRequests);
    }

    @Test
    void publishItemSendsOneCommentPinnedToTheCurrentHead()
    {
        reviews.put(finding(FINDING_ID, 1, "src/Service.java", 118, 131, "Guard the null case"));
        ReviewDraft draft = service.addFinding(REPO, NUMBER, 0, REVIEW_ID, FINDING_ID);
        when(pullRequests.findHeadSha(REPO, NUMBER)).thenReturn(Optional.of(CURRENT_HEAD));
        when(pullRequests.createReviewComment(eq(REPO), eq(NUMBER), any(InlineReviewComment.class))).thenReturn(detail());

        service.publishItem(REPO, NUMBER, draft.version(), lastItemId(draft));

        ArgumentCaptor<InlineReviewComment> sent = ArgumentCaptor.captor();
        verify(pullRequests).createReviewComment(eq(REPO), eq(NUMBER), sent.capture());
        verify(pullRequests).findHeadSha(REPO, NUMBER);
        verifyNoMoreInteractions(pullRequests);
        InlineReviewComment comment = sent.getValue();
        assertThat(comment.path()).isEqualTo("src/Service.java");
        assertThat(comment.body()).isEqualTo("Guard the null case");
        assertThat(comment.side()).isEqualTo("RIGHT");
        assertThat(comment.startSide()).isEqualTo("RIGHT");
        assertThat(comment.line()).isEqualTo(131);
        assertThat(comment.startLine()).isEqualTo(118);
        // The comment is pinned to the pull request's head now, not to the head the finding was validated against.
        assertThat(comment.commitId()).isEqualTo(CURRENT_HEAD).isNotEqualTo(REVIEWED_HEAD);
    }

    @Test
    void submitPreservesSuggestionFenceAndTargetRange()
    {
        String body = """
                Guard the empty value.

                ```suggestion
                    return input.orElseThrow();
                ```""";
        reviews.put(finding(FINDING_ID, 1, "src/Service.java", 118, 119, body));
        ReviewDraft draft = service.addFinding(REPO, NUMBER, 0, REVIEW_ID, FINDING_ID);
        assertThat(draft.items().getFirst().body()).isEqualTo(body);
        verifyNoInteractions(pullRequests);
        when(pullRequests.findHeadSha(REPO, NUMBER)).thenReturn(Optional.of(CURRENT_HEAD));
        when(pullRequests.getPullRequestDetail(REPO, NUMBER)).thenReturn(detail());
        when(pullRequests.submitReview(eq(REPO), eq(NUMBER), eq(PR_ID), eq("COMMENT"), eq("Suggested fix"), any())).thenReturn(detail());

        service.submit(REPO, NUMBER, draft.version(), "Suggested fix", "COMMENT");

        ArgumentCaptor<List<InlineReviewComment>> sent = ArgumentCaptor.captor();
        verify(pullRequests).submitReview(eq(REPO), eq(NUMBER), eq(PR_ID), eq("COMMENT"), eq("Suggested fix"), sent.capture());
        assertThat(sent.getValue()).singleElement().satisfies(comment -> {
            assertThat(comment.body()).isEqualTo(body);
            assertThat(comment.path()).isEqualTo("src/Service.java");
            assertThat(comment.startLine()).isEqualTo(118);
            assertThat(comment.line()).isEqualTo(119);
            assertThat(comment.side()).isEqualTo("RIGHT");
            assertThat(comment.startSide()).isEqualTo("RIGHT");
        });
    }

    @Test
    void submitSendsOnlyTheIncludedUnpublishedItems()
    {
        ReviewDraft draft = service.addManual(REPO, NUMBER, 0, manual("src/A.java", 10, "Rename this"));
        draft = service.addManual(REPO, NUMBER, draft.version(), manual("src/B.java", 20, "Not this one"));
        String excluded = lastItemId(draft);
        draft = service.addManual(REPO, NUMBER, draft.version(), manual("src/C.java", 30, "Extract a helper"));
        draft = service.editItem(REPO, NUMBER, draft.version(), excluded, "Not this one", false);
        when(pullRequests.findHeadSha(REPO, NUMBER)).thenReturn(Optional.of(CURRENT_HEAD));
        when(pullRequests.getPullRequestDetail(REPO, NUMBER)).thenReturn(detail());
        when(pullRequests.submitReview(eq(REPO), eq(NUMBER), eq(PR_ID), eq("REQUEST_CHANGES"), eq("Please fix these"), any())).thenReturn(detail());

        service.submit(REPO, NUMBER, draft.version(), "Please fix these", "REQUEST_CHANGES");

        ArgumentCaptor<List<InlineReviewComment>> sent = ArgumentCaptor.captor();
        verify(pullRequests).submitReview(eq(REPO), eq(NUMBER), eq(PR_ID), eq("REQUEST_CHANGES"), eq("Please fix these"), sent.capture());
        verify(pullRequests).findHeadSha(REPO, NUMBER);
        verify(pullRequests).getPullRequestDetail(REPO, NUMBER);
        verifyNoMoreInteractions(pullRequests);
        assertThat(sent.getValue()).extracting(InlineReviewComment::body).containsExactly("Rename this", "Extract a helper");
    }

    @Test
    void submitRefusesAResolvedFindingBeforeAnythingIsSent()
    {
        ReviewFinding open = finding(FINDING_ID, 1, "src/Service.java", 118, 131, "Guard the null case");
        reviews.put(open);
        ReviewDraft draft = service.addFinding(REPO, NUMBER, 0, REVIEW_ID, FINDING_ID);
        reviews.put(open.withState(ReviewFinding.State.RESOLVED, Optional.of("fixed it"), NOW));

        assertThatThrownBy(() -> service.submit(REPO, NUMBER, draft.version(), "Please fix these", "COMMENT"))
                .isInstanceOf(ReviewConflictException.class)
                .hasMessageContaining("You resolved this finding locally");

        verifyNoInteractions(pullRequests);
    }

    @Test
    void aPublishedItemIsNeverSentAgainByEitherRoute()
    {
        ReviewDraft draft = service.addManual(REPO, NUMBER, 0, manual("src/A.java", 10, "Rename this"));
        String itemId = lastItemId(draft);
        when(pullRequests.findHeadSha(REPO, NUMBER)).thenReturn(Optional.of(CURRENT_HEAD));
        when(pullRequests.createReviewComment(eq(REPO), eq(NUMBER), any(InlineReviewComment.class))).thenReturn(detail());
        when(pullRequests.getPullRequestDetail(REPO, NUMBER)).thenReturn(detail());
        when(pullRequests.submitReview(eq(REPO), eq(NUMBER), eq(PR_ID), eq("COMMENT"), eq("Anything else?"), any())).thenReturn(detail());

        long version = service.publishItem(REPO, NUMBER, draft.version(), itemId).draft().version();

        assertThatThrownBy(() -> service.publishItem(REPO, NUMBER, version, itemId))
                .isInstanceOf(ReviewConflictException.class)
                .hasMessageContaining("already posted to GitHub");

        service.submit(REPO, NUMBER, version, "Anything else?", "COMMENT");

        ArgumentCaptor<List<InlineReviewComment>> sent = ArgumentCaptor.captor();
        verify(pullRequests).submitReview(eq(REPO), eq(NUMBER), eq(PR_ID), eq("COMMENT"), eq("Anything else?"), sent.capture());
        assertThat(sent.getValue()).isEmpty();
        verify(pullRequests, times(1)).createReviewComment(eq(REPO), eq(NUMBER), any(InlineReviewComment.class));
    }

    @Test
    void aFailedSendLeavesTheItemUncertainAndBlocksAResend()
    {
        ReviewDraft draft = service.addManual(REPO, NUMBER, 0, manual("src/A.java", 10, "Rename this"));
        String itemId = lastItemId(draft);
        when(pullRequests.findHeadSha(REPO, NUMBER)).thenReturn(Optional.of(CURRENT_HEAD));
        when(pullRequests.createReviewComment(eq(REPO), eq(NUMBER), any(InlineReviewComment.class))).thenThrow(new IllegalStateException("github is unreachable"));

        assertThatThrownBy(() -> service.publishItem(REPO, NUMBER, draft.version(), itemId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("github is unreachable");

        ReviewDraft after = service.draft(REPO, NUMBER);
        assertThat(after.items().getFirst().publication().orElseThrow().state()).isEqualTo(ReviewPublication.State.UNCERTAIN);
        // A send whose outcome is unknown is never retried from here; the refusal is what keeps a duplicate comment off GitHub.
        assertThatThrownBy(() -> service.publishItem(REPO, NUMBER, after.version(), itemId)).isInstanceOf(ReviewConflictException.class);
        verify(pullRequests, times(1)).createReviewComment(eq(REPO), eq(NUMBER), any(InlineReviewComment.class));
    }

    @Test
    void aSuccessfulSubmitResetsTheReviewAndMarksTheItemsPublished()
    {
        ReviewDraft draft = service.addManual(REPO, NUMBER, 0, manual("src/A.java", 10, "Rename this"));
        String itemId = lastItemId(draft);
        draft = service.saveReview(REPO, NUMBER, draft.version(), "Please fix these", "REQUEST_CHANGES");
        when(pullRequests.findHeadSha(REPO, NUMBER)).thenReturn(Optional.of(CURRENT_HEAD));
        when(pullRequests.getPullRequestDetail(REPO, NUMBER)).thenReturn(detail());
        when(pullRequests.submitReview(eq(REPO), eq(NUMBER), eq(PR_ID), eq("APPROVE"), eq("Looks good"), any())).thenReturn(detail());

        ReviewPublicationService.Publication published = service.submit(REPO, NUMBER, draft.version(), "Looks good", "APPROVE");

        assertThat(published.publishedItemIds()).containsExactly(itemId);
        assertThat(published.draft().body()).isEmpty();
        assertThat(published.draft().event()).isEqualTo(ReviewDraft.DEFAULT_EVENT);
        ReviewDraftItem item = published.draft().items().getFirst();
        assertThat(item.published()).isTrue();
        assertThat(item.publication().orElseThrow().state()).isEqualTo(ReviewPublication.State.PUBLISHED);
        assertThat(service.draft(REPO, NUMBER)).isEqualTo(published.draft());
    }

    private static ReviewDraftItem manual(String path, int line, String body)
    {
        return new ReviewDraftItem("new", Source.MANUAL, Optional.empty(), OptionalInt.empty(), path, "RIGHT", line, OptionalInt.empty(), body, true, Optional.empty());
    }

    private static ReviewFinding finding(String id, int ordinal, String path, int startLine, int endLine, String body)
    {
        return new ReviewFinding(
                id,
                REVIEW_ID,
                RUN_ID,
                ordinal,
                "Finding " + ordinal,
                body,
                Optional.of("major"),
                new ReviewAnchor(path, "RIGHT", startLine, endLine, ImmutableList.of("  return value;")),
                ImmutableList.of(),
                1,
                ReviewFinding.State.OPEN,
                Optional.empty(),
                REVIEWED_HEAD,
                false,
                0,
                NOW,
                NOW);
    }

    private static String lastItemId(ReviewDraft draft)
    {
        return draft.items().getLast().id();
    }

    /** The cached pull request the GitHub-facing service hands back; only its id and empty activity matter here. */
    private static PullRequestDetail detail()
    {
        return new PullRequestDetail(
                PR_ID,
                REPO,
                NUMBER,
                "Add the widget cache",
                "https://github.com/octo/widgets/pull/7",
                "open",
                false,
                Boolean.TRUE,
                "clean",
                "octocat",
                "",
                NOW,
                NOW,
                null,
                "main",
                "widget-cache",
                "",
                ImmutableList.of(),
                ImmutableList.of(),
                "",
                ImmutableList.of(),
                12,
                3,
                2,
                0,
                CiStatus.PASSING,
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                true);
    }

    private static final class InMemoryReviewDraftStore
            implements ReviewDraftStore
    {
        private final Map<String, ReviewDraft> stored = new HashMap<>();

        @Override
        public Optional<ReviewDraft> find(String repo, int number)
        {
            return Optional.ofNullable(stored.get(repo.toLowerCase(Locale.ROOT) + "#" + number));
        }

        @Override
        public ReviewDraft save(ReviewDraft draft)
        {
            long current = Optional.ofNullable(stored.get(draft.id())).map(ReviewDraft::version).orElse(0L);
            if (current != draft.version()) {
                throw new ReviewConflictException("Review draft version changed: " + draft.id());
            }
            ReviewDraft saved = draft.withVersion(draft.version() + 1);
            stored.put(saved.id(), saved);
            return saved;
        }
    }

    /** The publication service only ever reads findings, so nothing else is backed by real state. */
    private static final class InMemoryAgentReviewStore
            implements AgentReviewStore
    {
        private final Map<String, ReviewFinding> findings = new HashMap<>();

        void put(ReviewFinding finding)
        {
            findings.put(finding.id(), finding);
        }

        @Override
        public Optional<ReviewFinding> findFinding(String findingId)
        {
            return Optional.ofNullable(findings.get(findingId));
        }

        @Override
        public AgentReview insertReview(AgentReview review) { throw new UnsupportedOperationException(); }

        @Override
        public AgentReview updateReview(AgentReview review) { throw new UnsupportedOperationException(); }

        @Override
        public Optional<AgentReview> findReview(String reviewId) { throw new UnsupportedOperationException(); }

        @Override
        public Optional<AgentReview> findOpenReview(String workspaceId, String repo, int number) { throw new UnsupportedOperationException(); }

        @Override
        public Optional<AgentReview> findReviewByTask(String taskId) { throw new UnsupportedOperationException(); }

        @Override
        public List<AgentReview> listOpenReviews() { throw new UnsupportedOperationException(); }

        @Override
        public List<AgentReview> listReviews(String workspaceId) { throw new UnsupportedOperationException(); }

        @Override
        public ReviewRun insertRun(ReviewRun run) { throw new UnsupportedOperationException(); }

        @Override
        public ReviewRun updateRun(ReviewRun run) { throw new UnsupportedOperationException(); }

        @Override
        public void acceptRun(AgentReview review, ReviewRun run) { throw new UnsupportedOperationException(); }

        @Override
        public ReviewFinding saveFinding(ReviewFinding finding, boolean insert, ReviewRun run) { throw new UnsupportedOperationException(); }

        @Override
        public ReviewMessage insertMessage(ReviewMessage message, ReviewRun run) { throw new UnsupportedOperationException(); }

        @Override
        public Optional<ReviewRun> findRun(String runId) { throw new UnsupportedOperationException(); }

        @Override
        public List<ReviewRun> listRuns(String reviewId) { throw new UnsupportedOperationException(); }

        @Override
        public List<ReviewRun> listRecentRuns(String workspaceId, String repo, int limit) { throw new UnsupportedOperationException(); }

        @Override
        public ReviewFinding insertFinding(ReviewFinding finding) { throw new UnsupportedOperationException(); }

        @Override
        public ReviewFinding updateFinding(ReviewFinding finding) { throw new UnsupportedOperationException(); }

        @Override
        public List<ReviewFinding> listFindings(String reviewId) { throw new UnsupportedOperationException(); }

        @Override
        public ReviewMessage insertMessage(ReviewMessage message) { throw new UnsupportedOperationException(); }

        @Override
        public ReviewMessage updateMessage(ReviewMessage message) { throw new UnsupportedOperationException(); }

        @Override
        public List<ReviewMessage> listMessages(String reviewId) { throw new UnsupportedOperationException(); }
    }
}
