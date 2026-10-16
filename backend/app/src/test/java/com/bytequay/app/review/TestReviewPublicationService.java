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

import com.bytequay.app.repository.github.GitHubRateLimitException;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.core.domain.InlineReviewComment;
import com.bytequay.core.domain.PrTimelineComment;
import com.bytequay.core.domain.PrTimelineEvent;
import com.bytequay.core.domain.PrTimelinePayload;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.PullRequestDetail.CiStatus;
import com.bytequay.core.review.AgentReview;
import com.bytequay.core.review.AgentReviewStore;
import com.bytequay.core.review.ReviewAnchor;
import com.bytequay.core.review.ReviewCandidateValidation;
import com.bytequay.core.review.ReviewConflictException;
import com.bytequay.core.review.ReviewCoverage;
import com.bytequay.core.review.ReviewDraft;
import com.bytequay.core.review.ReviewDraftItem;
import com.bytequay.core.review.ReviewDraftItem.Source;
import com.bytequay.core.review.ReviewDraftStore;
import com.bytequay.core.review.ReviewEvidence;
import com.bytequay.core.review.ReviewFinding;
import com.bytequay.core.review.ReviewInvestigation;
import com.bytequay.core.review.ReviewMessage;
import com.bytequay.core.review.ReviewPublication;
import com.bytequay.core.review.ReviewRun;
import com.bytequay.core.review.ReviewStep;
import com.bytequay.core.review.ReviewValidation;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.stream.IntStream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
    private ReviewPublicationService service;

    @BeforeEach
    void setUp()
    {
        drafts = new InMemoryReviewDraftStore();
        reviews = new InMemoryAgentReviewStore();
        pullRequests = mock(PullRequestService.class);
        service = new ReviewPublicationService(drafts, reviews, pullRequests, Clock.fixed(NOW, ZoneOffset.UTC));
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

        assertThat(service.draft(REPO, NUMBER).items()).isEmpty();
        verifyNoInteractions(pullRequests);
    }

    @Test
    void aFindingAboutTheWholePullRequestJoinsTheReviewText()
    {
        ReviewFinding lines = finding(FINDING_ID, 1, "src/Service.java", 118, 131, "Guard the null case");
        ReviewFinding whole = new ReviewFinding("f-whole", REVIEW_ID, RUN_ID, 2, "Nit: commit subject", "Commit message: name the behavior, not the file.", Optional.empty(),
                Optional.empty(), ImmutableList.of(), 1, ReviewFinding.State.OPEN, Optional.empty(), REVIEWED_HEAD, false, 0, NOW, NOW,
                ImmutableList.of(), ReviewFinding.Origin.AUTOMATIC);
        reviews.put(lines);
        reviews.put(whole);

        ReviewDraft first = service.addFinding(REPO, NUMBER, 0, REVIEW_ID, "f-whole");
        assertThat(first.items()).isEmpty();
        assertThat(first.body()).isEqualTo("Nit: commit subject\n\nCommit message: name the behavior, not the file.");
        assertThatThrownBy(() -> service.addFinding(REPO, NUMBER, first.version(), REVIEW_ID, "f-whole"))
                .isInstanceOf(ReviewConflictException.class).hasMessageContaining("already in your review");

        // It follows text the user already wrote, and a finding with lines still becomes an inline comment.
        ReviewDraft written = service.saveReview(REPO, NUMBER, first.version(), "Thanks for the fix.", "COMMENT");
        ReviewDraft second = service.addFinding(REPO, NUMBER, written.version(), REVIEW_ID, "f-whole");
        assertThat(second.body()).isEqualTo("Thanks for the fix.\n\nNit: commit subject\n\nCommit message: name the behavior, not the file.");
        assertThat(service.addFinding(REPO, NUMBER, second.version(), REVIEW_ID, FINDING_ID).items()).hasSize(1);
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
        assertThat(item.body()).isEqualTo("Finding 1\n\nGuard the null case");
        assertThat(item.included()).isTrue();
        assertThat(item.publication()).isEmpty();
        assertThat(reviews.findFinding(FINDING_ID)).contains(open);
        verifyNoInteractions(pullRequests);
    }

    @Test
    void anUnselectedSupportedFindingCanBeExplicitlyAddedAtItsValidatedRevision()
    {
        ReviewFinding finding = finding(FINDING_ID, 1, "src/Service.java", 118, 131, "Guard the null case");
        reviews.put(finding);
        reviews.setRun(acceptedReport(finding, true, false));

        ReviewDraft draft = service.addFinding(REPO, NUMBER, 0, REVIEW_ID, FINDING_ID);

        assertThat(draft.items()).singleElement().satisfies(item -> {
            assertThat(item.findingId()).contains(FINDING_ID);
            assertThat(item.findingRevision()).hasValue(1);
        });
        ReviewFinding revised = finding.revised(finding.title(), "New claim after acceptance", finding.severity(), finding.anchor(), finding.evidence(), REVIEWED_HEAD, NOW);
        reviews.put(revised);
        ReviewDraft cleared = service.removeItem(REPO, NUMBER, draft.version(), lastItemId(draft));
        assertThatThrownBy(() -> service.addFinding(REPO, NUMBER, cleared.version(), REVIEW_ID, FINDING_ID))
                .isInstanceOf(ReviewConflictException.class).hasMessageContaining("accepted review of its source revision");
        verifyNoInteractions(pullRequests);
    }

    @Test
    void anAcceptedOriginDoesNotMakeAnUnselectedUnsupportedFindingPromotable()
    {
        ReviewFinding finding = finding(FINDING_ID, 1, "src/Service.java", 118, 131, "Unsupported claim");
        reviews.put(finding);
        reviews.setRun(acceptedReport(finding, false, false));

        assertThatThrownBy(() -> service.addFinding(REPO, NUMBER, 0, REVIEW_ID, FINDING_ID))
                .isInstanceOf(ReviewConflictException.class).hasMessageContaining("accepted review of its source revision");

        assertThat(service.draft(REPO, NUMBER).items()).isEmpty();
        verifyNoInteractions(pullRequests);
    }

    @Test
    void anAcceptedExplanationRefreshCannotAuthorizeAFinding()
    {
        ReviewFinding original = finding(FINDING_ID, 1, "src/Service.java", 118, 131, "A discussion claim");
        reviews.put(new ReviewFinding(original.id(), original.reviewId(), original.runId(), original.ordinal(), original.title(), original.body(),
                original.severity(), original.anchor(), original.evidence(), original.revision(), original.state(), original.stateReason(), original.validatedHeadSha(),
                original.outdated(), original.version(), original.createdAt(), original.updatedAt(), original.memories(), ReviewFinding.Origin.DISCUSSION));
        reviews.setRun(run(ReviewRun.State.ACCEPTED).withStep(ReviewStep.explanation(), NOW));

        assertThatThrownBy(() -> service.addFinding(REPO, NUMBER, 0, REVIEW_ID, FINDING_ID))
                .isInstanceOf(ReviewConflictException.class).hasMessageContaining("accepted review of its source commit");

        assertThat(service.draft(REPO, NUMBER).items()).isEmpty();
        verifyNoInteractions(pullRequests);
    }

    @Test
    void acceptedSelectionKeepsDiscussionRevisionsPromotableWithoutRewritingUserDrafts()
    {
        ReviewFinding original = finding(FINDING_ID, 1, "src/Service.java", 118, 131, "Original finding");
        reviews.put(original);
        reviews.setRun(acceptedReport(original, true, true));
        ReviewFinding revised = original.revised(original.title(), "Clarified after discussion", original.severity(), original.anchor(), original.evidence(), REVIEWED_HEAD, NOW);
        reviews.put(revised);

        ReviewDraft promoted = service.addFinding(REPO, NUMBER, 0, REVIEW_ID, FINDING_ID);
        assertThat(promoted.items().getFirst().findingRevision()).hasValue(2);
        assertThat(promoted.items().getFirst().body()).isEqualTo("Finding 1\n\nClarified after discussion");
        ReviewDraft edited = service.editItem(REPO, NUMBER, promoted.version(), lastItemId(promoted), "My own wording", true);
        reviews.put(revised.revised(revised.title(), "Another clarification", revised.severity(), revised.anchor(), revised.evidence(), REVIEWED_HEAD, NOW));

        assertThat(service.draft(REPO, NUMBER)).isEqualTo(edited);
        assertThat(edited.items().getFirst().body()).isEqualTo("My own wording");
        assertThat(edited.items().getFirst().findingRevision()).hasValue(2);
        verifyNoInteractions(pullRequests);
    }

    @Test
    void supersededValidationSupportDoesNotAuthorizePromotion()
    {
        ReviewFinding finding = finding(FINDING_ID, 1, "src/Service.java", 118, 131, "Superseded claim");
        reviews.put(finding);
        ReviewRun earlierSupport = acceptedReport(finding, true, false);
        ReviewInvestigation report = earlierSupport.latestSealedInvestigation().orElseThrow().investigation().orElseThrow();
        ReviewStep collection = earlierSupport.currentStep().orElseThrow().next(ReviewStep.Kind.COLLECT_EVIDENCE).withInvestigation(report);
        ReviewValidation rejection = validation(3, finding, false, false);
        reviews.setRun(earlierSupport.withStep(collection, NOW).withStep(collection.next(ReviewStep.Kind.VALIDATE).withValidation(rejection), NOW));

        assertThatThrownBy(() -> service.addFinding(REPO, NUMBER, 0, REVIEW_ID, FINDING_ID))
                .isInstanceOf(ReviewConflictException.class).hasMessageContaining("accepted review of its source revision");

        assertThat(service.draft(REPO, NUMBER).items()).isEmpty();
        verifyNoInteractions(pullRequests);
    }

    @Test
    void acceptedReassessmentCanSupportAFindingFromAFailedOriginRun()
    {
        ReviewFinding original = finding(FINDING_ID, 1, "src/Service.java", 118, 131, "Reassessed claim");
        ReviewFinding carried = new ReviewFinding(original.id(), original.reviewId(), "failed-run", original.ordinal(), original.title(), original.body(),
                original.severity(), original.anchor(), original.evidence(), original.revision(), original.state(), original.stateReason(), original.validatedHeadSha(),
                false, original.version(), original.createdAt(), original.updatedAt(), original.memories(), original.origin());
        reviews.put(carried);
        ReviewRun failed = new ReviewRun("failed-run", REVIEW_ID, 1, ReviewRun.Scope.FULL, "main", CURRENT_HEAD, CURRENT_HEAD, REVIEWED_HEAD, Optional.empty(),
                Optional.empty(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ReviewRun.State.FAILED, Optional.empty(), Optional.empty(), Optional.empty(),
                ImmutableList.of(), false, Map.of(), Map.of(), Optional.empty(), 0, NOW, NOW, Optional.of(NOW));
        reviews.setRun(acceptedReport(carried, true, false));
        reviews.runs.addFirst(failed);

        ReviewDraft draft = service.addFinding(REPO, NUMBER, 0, REVIEW_ID, FINDING_ID);

        assertThat(draft.items().getFirst().findingId()).contains(FINDING_ID);
        assertThat(draft.items().getFirst().findingRevision()).hasValue(carried.revision());
        verifyNoInteractions(pullRequests);
    }

    @Test
    void aProvisionalFindingCannotBeAddedButAnExistingSnapshotCanBeSent()
    {
        reviews.put(finding(FINDING_ID, 1, "src/Service.java", 118, 131, "Guard the null case"));
        ReviewDraft draft = service.addFinding(REPO, NUMBER, 0, REVIEW_ID, FINDING_ID);
        reviews.setRun(run(ReviewRun.State.RUNNING));

        assertThatThrownBy(() -> service.addFinding(REPO, NUMBER, draft.version(), REVIEW_ID, FINDING_ID))
                .isInstanceOf(ReviewConflictException.class).hasMessageContaining("accepted review");
        verifyNoInteractions(pullRequests);

        when(pullRequests.createReviewComment(eq(REPO), eq(NUMBER), any())).thenReturn(posted());
        assertThat(service.publishItem(REPO, NUMBER, draft.version(), lastItemId(draft)).publishedItemIds()).containsExactly(lastItemId(draft));
    }

    @Test
    void reassessmentReopensAMappedFindingForTheAcceptedRun()
    {
        ReviewFinding pending = finding(FINDING_ID, 1, "src/Service.java", 118, 131, "Guard the null case")
                .pendingReassessment(NOW);
        reviews.put(pending);
        reviews.put(pending.revised("Null guard", "Guard the null case", Optional.of("medium"), pending.anchor(),
                ImmutableList.of(new ReviewEvidence("E1", "Frozen source", "reviewed head", "return value;", "Confirmed the null path")), REVIEWED_HEAD, NOW));
        ReviewDraft draft = service.addFinding(REPO, NUMBER, 0, REVIEW_ID, FINDING_ID);

        assertThat(draft.items().getFirst().findingRevision()).hasValue(2);
        verifyNoInteractions(pullRequests);
    }

    @Test
    void aFindingCannotBeAddedToAnotherPullRequestDraft()
    {
        reviews.put(finding(FINDING_ID, 1, "src/Service.java", 118, 131, "Guard the null case"));

        assertThatThrownBy(() -> service.addFinding("octo/other", NUMBER, 0, REVIEW_ID, FINDING_ID))
                .isInstanceOf(ReviewConflictException.class).hasMessageContaining("different pull request");
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
    void savedCommentsStayUnchangedAndEligibleAfterTheirFindingsMoveOn()
    {
        ReviewFinding resolved = finding("f-resolved", 1, "src/A.java", 10, 10, "Resolved later");
        ReviewFinding withdrawn = finding("f-withdrawn", 2, "src/B.java", 20, 20, "Withdrawn later");
        ReviewFinding outdated = finding("f-outdated", 3, "src/C.java", 30, 30, "Outdated later");
        ReviewFinding revised = finding("f-revised", 4, "src/D.java", 40, 40, "Revised later");
        ReviewFinding removed = finding("f-removed", 5, "src/E.java", 50, 50, "Removed later");
        ImmutableList.of(resolved, withdrawn, outdated, revised, removed).forEach(reviews::put);

        ReviewDraft draft = service.addManual(REPO, NUMBER, 0, manual("src/Main.java", 12, "Still fine"));
        draft = service.addFinding(REPO, NUMBER, draft.version(), REVIEW_ID, "f-resolved");
        draft = service.addFinding(REPO, NUMBER, draft.version(), REVIEW_ID, "f-withdrawn");
        draft = service.addFinding(REPO, NUMBER, draft.version(), REVIEW_ID, "f-outdated");
        draft = service.addFinding(REPO, NUMBER, draft.version(), REVIEW_ID, "f-revised");
        draft = service.addFinding(REPO, NUMBER, draft.version(), REVIEW_ID, "f-removed");

        reviews.put(resolved.withState(ReviewFinding.State.RESOLVED, Optional.of("fixed it"), NOW));
        reviews.put(withdrawn.withState(ReviewFinding.State.WITHDRAWN, Optional.of("misread the diff"), NOW));
        reviews.put(outdated.pendingReassessment(NOW));
        reviews.put(revised.revised("Revised title", "Rewritten body", Optional.empty(), revised.anchor(), ImmutableList.of(), REVIEWED_HEAD, NOW));
        reviews.findings.remove(removed.id());

        assertThat(service.draft(REPO, NUMBER)).isEqualTo(draft);
        verifyNoInteractions(pullRequests);

        when(pullRequests.getPullRequestDetail(REPO, NUMBER)).thenReturn(detail());
        when(pullRequests.submitReview(eq(REPO), eq(NUMBER), eq(PR_ID), eq("COMMENT"), eq(""), any())).thenReturn(posted());
        service.submit(REPO, NUMBER, draft.version(), "", "COMMENT");

        ArgumentCaptor<List<InlineReviewComment>> sent = ArgumentCaptor.captor();
        verify(pullRequests).submitReview(eq(REPO), eq(NUMBER), eq(PR_ID), eq("COMMENT"), eq(""), sent.capture());
        assertThat(sent.getValue()).extracting(InlineReviewComment::body)
                .containsExactly("Still fine", "Finding 1\n\nResolved later", "Finding 2\n\nWithdrawn later", "Finding 3\n\nOutdated later",
                        "Finding 4\n\nRevised later", "Finding 5\n\nRemoved later");
    }

    @Test
    void anUpdatedBaseAndNewerPullRequestHeadDoNotRetargetTheDraft()
    {
        ReviewFinding original = finding(FINDING_ID, 1, "src/Service.java", 118, 131, "Guard the null case");
        reviews.put(original.pendingReassessment(NOW));
        when(pullRequests.findHeadSha(REPO, NUMBER)).thenReturn(Optional.of(CURRENT_HEAD));
        ReviewDraft draft = service.addFinding(REPO, NUMBER, 0, REVIEW_ID, FINDING_ID);
        when(pullRequests.createReviewComment(eq(REPO), eq(NUMBER), any())).thenReturn(posted());

        service.publishItem(REPO, NUMBER, draft.version(), lastItemId(draft));

        ArgumentCaptor<InlineReviewComment> sent = ArgumentCaptor.captor();
        verify(pullRequests).createReviewComment(eq(REPO), eq(NUMBER), sent.capture());
        assertThat(sent.getValue().commitId()).isEqualTo(REVIEWED_HEAD);
        assertThat(sent.getValue().line()).isEqualTo(131);
        verifyNoMoreInteractions(pullRequests);
    }

    @Test
    void submitGroupsEachOriginalCommitAndPlacesTheCurrentHeadVerdictLast()
    {
        reviews.put(finding(FINDING_ID, 1, "src/Service.java", 118, 131, "Guard the null case"));
        ReviewDraft draft = service.addManual(REPO, NUMBER, 0, manual("src/Main.java", 12, "Rename this").withCommitId(CURRENT_HEAD));
        draft = service.addFinding(REPO, NUMBER, draft.version(), REVIEW_ID, FINDING_ID);
        when(pullRequests.findHeadSha(REPO, NUMBER)).thenReturn(Optional.of(CURRENT_HEAD));
        when(pullRequests.getPullRequestDetail(REPO, NUMBER)).thenReturn(detail());
        when(pullRequests.submitReview(eq(REPO), eq(NUMBER), eq(PR_ID), any(), any(), any())).thenReturn(posted());

        service.submit(REPO, NUMBER, draft.version(), "Please fix these", "REQUEST_CHANGES");

        ArgumentCaptor<String> events = ArgumentCaptor.captor();
        ArgumentCaptor<String> bodies = ArgumentCaptor.captor();
        ArgumentCaptor<List<InlineReviewComment>> comments = ArgumentCaptor.captor();
        verify(pullRequests, times(2)).submitReview(eq(REPO), eq(NUMBER), eq(PR_ID), events.capture(), bodies.capture(), comments.capture());
        assertThat(events.getAllValues()).containsExactly("COMMENT", "REQUEST_CHANGES");
        assertThat(bodies.getAllValues()).containsExactly("", "Please fix these");
        assertThat(comments.getAllValues().getFirst()).singleElement().satisfies(item -> assertThat(item.commitId()).isEqualTo(REVIEWED_HEAD));
        assertThat(comments.getAllValues().getLast()).singleElement().satisfies(item -> assertThat(item.commitId()).isEqualTo(CURRENT_HEAD));
    }

    @Test
    void publishItemSendsTheExactReviewedLocation()
    {
        reviews.put(finding(FINDING_ID, 1, "src/Service.java", 118, 131, "Guard the null case"));
        ReviewDraft draft = service.addFinding(REPO, NUMBER, 0, REVIEW_ID, FINDING_ID);
        when(pullRequests.findHeadSha(REPO, NUMBER)).thenReturn(Optional.of(REVIEWED_HEAD));
        when(pullRequests.createReviewComment(eq(REPO), eq(NUMBER), any(InlineReviewComment.class))).thenReturn(posted());

        service.publishItem(REPO, NUMBER, draft.version(), lastItemId(draft));

        ArgumentCaptor<InlineReviewComment> sent = ArgumentCaptor.captor();
        verify(pullRequests).createReviewComment(eq(REPO), eq(NUMBER), sent.capture());
        InlineReviewComment comment = sent.getValue();
        assertThat(comment.path()).isEqualTo("src/Service.java");
        assertThat(comment.body()).isEqualTo("Finding 1\n\nGuard the null case");
        assertThat(comment.side()).isEqualTo("RIGHT");
        assertThat(comment.startSide()).isEqualTo("RIGHT");
        assertThat(comment.line()).isEqualTo(131);
        assertThat(comment.startLine()).isEqualTo(118);
        assertThat(comment.commitId()).isEqualTo(REVIEWED_HEAD);
    }

    @Test
    void aLaterFindingRevisionAndMovedAnchorDoNotRewriteTheUserDraft()
    {
        ReviewFinding original = finding(FINDING_ID, 1, "src/Service.java", 118, 131, "Guard the null case");
        reviews.put(original);
        ReviewDraft draft = service.addFinding(REPO, NUMBER, 0, REVIEW_ID, FINDING_ID);
        reviews.put(original.revised("New title", "Different wording", Optional.empty(),
                Optional.of(new ReviewAnchor("src/Service.java", "RIGHT", 119, 132, ImmutableList.of())), ImmutableList.of(), CURRENT_HEAD, NOW));
        when(pullRequests.createReviewComment(eq(REPO), eq(NUMBER), any())).thenReturn(posted());

        service.publishItem(REPO, NUMBER, draft.version(), lastItemId(draft));

        ArgumentCaptor<InlineReviewComment> sent = ArgumentCaptor.captor();
        verify(pullRequests).createReviewComment(eq(REPO), eq(NUMBER), sent.capture());
        assertThat(sent.getValue().commitId()).isEqualTo(REVIEWED_HEAD);
        assertThat(sent.getValue().body()).isEqualTo("Finding 1\n\nGuard the null case");
        assertThat(sent.getValue().line()).isEqualTo(131);
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
        assertThat(draft.items().getFirst().body()).isEqualTo("Finding 1\n\n" + body);
        verifyNoInteractions(pullRequests);
        when(pullRequests.findHeadSha(REPO, NUMBER)).thenReturn(Optional.of(REVIEWED_HEAD));
        when(pullRequests.getPullRequestDetail(REPO, NUMBER)).thenReturn(detail());
        when(pullRequests.submitReview(eq(REPO), eq(NUMBER), eq(PR_ID), eq("COMMENT"), eq("Suggested fix"), any())).thenReturn(posted());

        service.submit(REPO, NUMBER, draft.version(), "Suggested fix", "COMMENT");

        ArgumentCaptor<List<InlineReviewComment>> sent = ArgumentCaptor.captor();
        verify(pullRequests).submitReview(eq(REPO), eq(NUMBER), eq(PR_ID), eq("COMMENT"), eq("Suggested fix"), sent.capture());
        assertThat(sent.getValue()).singleElement().satisfies(comment -> {
            assertThat(comment.body()).isEqualTo("Finding 1\n\n" + body);
            assertThat(comment.path()).isEqualTo("src/Service.java");
            assertThat(comment.startLine()).isEqualTo(118);
            assertThat(comment.line()).isEqualTo(119);
            assertThat(comment.side()).isEqualTo("RIGHT");
            assertThat(comment.startSide()).isEqualTo("RIGHT");
        });
    }

    @Test
    void repliesRetainTheirThreadWhenEditedAndJoinTheFinalReviewBatch()
    {
        ReviewDraftItem reply = new ReviewDraftItem("reply", Source.MANUAL, Optional.empty(), OptionalInt.empty(), "old.java", "RIGHT", 1,
                OptionalInt.empty(), "Reply", true, Optional.empty(), Optional.empty(), OptionalLong.of(61));
        ReviewDraft draft = service.addManual(REPO, NUMBER, 0, reply);
        String replyId = lastItemId(draft);
        draft = service.editItem(REPO, NUMBER, draft.version(), replyId, "Edited reply", true);
        draft = service.addManual(REPO, NUMBER, draft.version(), manual("A.java", 3, "Older comment").withCommitId(REVIEWED_HEAD));
        draft = service.addManual(REPO, NUMBER, draft.version(), manual("B.java", 4, "Head comment").withCommitId(CURRENT_HEAD));
        assertThat(draft.items().getFirst().replyToCommentId()).hasValue(61);
        assertThat(draft.items().getFirst().commitId()).isEmpty();
        when(pullRequests.findHeadSha(REPO, NUMBER)).thenReturn(Optional.of(CURRENT_HEAD));
        when(pullRequests.getPullRequestDetail(REPO, NUMBER)).thenReturn(detail());
        when(pullRequests.submitReview(eq(REPO), eq(NUMBER), eq(PR_ID), any(), any(), any())).thenReturn(posted());

        service.submit(REPO, NUMBER, draft.version(), "", "APPROVE");

        ArgumentCaptor<List<InlineReviewComment>> sent = ArgumentCaptor.captor();
        verify(pullRequests).submitReview(eq(REPO), eq(NUMBER), eq(PR_ID), eq("COMMENT"), eq(""), sent.capture());
        assertThat(sent.getValue()).extracting(InlineReviewComment::body).containsExactly("Older comment");
        verify(pullRequests).submitReview(eq(REPO), eq(NUMBER), eq(PR_ID), eq("APPROVE"), eq(""), sent.capture());
        assertThat(sent.getValue()).extracting(InlineReviewComment::body).containsExactly("Head comment", "Edited reply");
        assertThat(sent.getValue().getLast().replyToCommentId()).hasValue(61);
    }

    @Test
    void submitSendsOnlyTheIncludedUnpublishedItems()
    {
        ReviewDraft draft = service.addManual(REPO, NUMBER, 0, manual("src/A.java", 10, "Rename this"));
        draft = service.addManual(REPO, NUMBER, draft.version(), manual("src/B.java", 20, "Not this one"));
        String excluded = lastItemId(draft);
        draft = service.addManual(REPO, NUMBER, draft.version(), manual("src/C.java", 30, "Extract a helper"));
        draft = service.editItem(REPO, NUMBER, draft.version(), excluded, "Not this one", false);
        when(pullRequests.findHeadSha(REPO, NUMBER)).thenReturn(Optional.of(REVIEWED_HEAD));
        when(pullRequests.getPullRequestDetail(REPO, NUMBER)).thenReturn(detail());
        when(pullRequests.submitReview(eq(REPO), eq(NUMBER), eq(PR_ID), eq("REQUEST_CHANGES"), eq("Please fix these"), any())).thenReturn(posted());

        service.submit(REPO, NUMBER, draft.version(), "Please fix these", "REQUEST_CHANGES");

        ArgumentCaptor<List<InlineReviewComment>> sent = ArgumentCaptor.captor();
        verify(pullRequests).submitReview(eq(REPO), eq(NUMBER), eq(PR_ID), eq("REQUEST_CHANGES"), eq("Please fix these"), sent.capture());
        verify(pullRequests).findHeadSha(REPO, NUMBER);
        verify(pullRequests).getPullRequestDetail(REPO, NUMBER);
        verifyNoMoreInteractions(pullRequests);
        assertThat(sent.getValue()).extracting(InlineReviewComment::body).containsExactly("Rename this", "Extract a helper");
    }

    @Test
    void submitSendsTheEditedSnapshotAfterTheFindingIsResolved()
    {
        ReviewFinding open = finding(FINDING_ID, 1, "src/Service.java", 118, 131, "Guard the null case");
        reviews.put(open);
        ReviewDraft draft = service.addFinding(REPO, NUMBER, 0, REVIEW_ID, FINDING_ID);
        draft = service.editItem(REPO, NUMBER, draft.version(), lastItemId(draft), "My own review comment", true);
        reviews.put(open.withState(ReviewFinding.State.RESOLVED, Optional.of("fixed it"), NOW));

        when(pullRequests.getPullRequestDetail(REPO, NUMBER)).thenReturn(detail());
        when(pullRequests.submitReview(eq(REPO), eq(NUMBER), eq(PR_ID), eq("COMMENT"), eq("Please fix these"), any())).thenReturn(posted());

        ReviewPublicationService.Publication result = service.submit(REPO, NUMBER, draft.version(), "Please fix these", "COMMENT");

        ArgumentCaptor<List<InlineReviewComment>> sent = ArgumentCaptor.captor();
        verify(pullRequests).submitReview(eq(REPO), eq(NUMBER), eq(PR_ID), eq("COMMENT"), eq("Please fix these"), sent.capture());
        assertThat(sent.getValue()).containsExactly(new InlineReviewComment("src/Service.java", "My own review comment", 131, "RIGHT", 118, "RIGHT", REVIEWED_HEAD,
                OptionalLong.empty()));
        assertThat(result.publishedItemIds()).containsExactly(lastItemId(draft));
        assertThat(reviews.findFinding(FINDING_ID)).contains(open.withState(ReviewFinding.State.RESOLVED, Optional.of("fixed it"), NOW));
    }

    @Test
    void publishItemSendsTheEditedSnapshotAfterTheFindingIsWithdrawn()
    {
        ReviewFinding open = finding(FINDING_ID, 1, "src/Service.java", 118, 131, "Guard the null case");
        reviews.put(open);
        ReviewDraft draft = service.addFinding(REPO, NUMBER, 0, REVIEW_ID, FINDING_ID);
        draft = service.editItem(REPO, NUMBER, draft.version(), lastItemId(draft), "My own review comment", true);
        reviews.put(open.withState(ReviewFinding.State.WITHDRAWN, Optional.of("misread the diff"), NOW));
        when(pullRequests.createReviewComment(eq(REPO), eq(NUMBER), any())).thenReturn(posted());

        ReviewPublicationService.Publication result = service.publishItem(REPO, NUMBER, draft.version(), lastItemId(draft));

        verify(pullRequests).createReviewComment(REPO, NUMBER, new InlineReviewComment("src/Service.java", "My own review comment", 131, "RIGHT", 118, "RIGHT", REVIEWED_HEAD,
                OptionalLong.empty()));
        assertThat(result.publishedItemIds()).containsExactly(lastItemId(draft));
    }

    @Test
    void aPublishedItemIsNeverSentAgainByEitherRoute()
    {
        ReviewDraft draft = service.addManual(REPO, NUMBER, 0, manual("src/A.java", 10, "Rename this"));
        String itemId = lastItemId(draft);
        when(pullRequests.findHeadSha(REPO, NUMBER)).thenReturn(Optional.of(CURRENT_HEAD));
        when(pullRequests.createReviewComment(eq(REPO), eq(NUMBER), any(InlineReviewComment.class))).thenReturn(posted());
        when(pullRequests.getPullRequestDetail(REPO, NUMBER)).thenReturn(detail());
        when(pullRequests.submitReview(eq(REPO), eq(NUMBER), eq(PR_ID), eq("COMMENT"), eq("Anything else?"), any())).thenReturn(posted());

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
        assertThat(after.items().getFirst().published()).isFalse();
        assertThat(after.items().getFirst().body()).isEqualTo("Rename this");
        // A send whose outcome is unknown is never retried from here; the refusal is what keeps a duplicate comment off GitHub.
        assertThatThrownBy(() -> service.publishItem(REPO, NUMBER, after.version(), itemId)).hasMessageContaining("unknown outcome");
        when(pullRequests.getPullRequestDetail(REPO, NUMBER)).thenReturn(detail());
        assertThatThrownBy(() -> service.submit(REPO, NUMBER, after.version(), "Review", "COMMENT")).hasMessageContaining("unknown outcome");
        assertThatThrownBy(() -> service.editItem(REPO, NUMBER, after.version(), itemId, "Changed", true)).hasMessageContaining("unknown outcome");
        assertThatThrownBy(() -> service.removeItem(REPO, NUMBER, after.version(), itemId)).hasMessageContaining("unknown outcome");
        verify(pullRequests, times(1)).createReviewComment(eq(REPO), eq(NUMBER), any(InlineReviewComment.class));
    }

    @Test
    void confirmedMissingCommentReturnsToPendingWithoutChangingThePayloadOrPosting()
    {
        ReviewPublication uncertain = new ReviewPublication("failed-send", ReviewPublication.State.UNCERTAIN, OptionalLong.empty(), OptionalLong.empty(), Optional.empty(), NOW);
        ReviewDraftItem original = manual("src/A.java", 10, "My edited comment").withPublication(Optional.of(uncertain));
        ReviewDraft saved = drafts.save(ReviewDraft.empty(REPO, NUMBER, NOW).withReview("Saved review", "REQUEST_CHANGES", NOW)
                .withItems(ImmutableList.of(original), NOW));

        assertThatThrownBy(() -> service.confirmNotPosted(REPO, NUMBER, saved.version() - 1, original.id())).hasMessageContaining("changed in another window");
        assertThat(service.draft(REPO, NUMBER).items()).containsExactly(original);

        ReviewDraft pending = service.confirmNotPosted(REPO, NUMBER, saved.version(), original.id());

        assertThat(pending.items()).containsExactly(original.withPublication(Optional.empty()));
        assertThat(pending.body()).isEqualTo(saved.body());
        assertThat(pending.event()).isEqualTo(saved.event());
        assertThat(pending.version()).isEqualTo(saved.version() + 1);
        verifyNoInteractions(pullRequests);

        when(pullRequests.createReviewComment(eq(REPO), eq(NUMBER), any())).thenReturn(posted());
        ReviewDraft posted = service.publishItem(REPO, NUMBER, pending.version(), original.id()).draft();
        assertThat(posted.items().getFirst().published()).isTrue();
        assertThat(posted.items().getFirst().publication().orElseThrow().attemptId()).isNotEqualTo(uncertain.attemptId());
        verify(pullRequests).createReviewComment(REPO, NUMBER, new InlineReviewComment(
                original.path(), original.body(), original.line(), original.side(), null, null, REVIEWED_HEAD, OptionalLong.empty()));
    }

    @Test
    void confirmedMissingCannotResetPendingOrSuccessfulComments()
    {
        ReviewDraft saved = service.addManual(REPO, NUMBER, 0, manual("src/A.java", 10, "Comment"));
        for (Optional<ReviewPublication> publication : ImmutableList.of(Optional.<ReviewPublication>empty(),
                Optional.of(new ReviewPublication("posted", ReviewPublication.State.PUBLISHED, OptionalLong.of(1), OptionalLong.of(2), Optional.empty(), NOW)))) {
            ReviewDraft current = drafts.save(saved.withItems(ImmutableList.of(saved.items().getFirst().withPublication(publication)), NOW));
            assertThatThrownBy(() -> service.confirmNotPosted(REPO, NUMBER, current.version(), lastItemId(current)))
                    .isInstanceOf(ReviewConflictException.class).hasMessageContaining("Only a comment with unconfirmed delivery");
            assertThat(service.draft(REPO, NUMBER)).isEqualTo(current);
            saved = current;
        }
        verifyNoInteractions(pullRequests);
    }

    @Test
    void aReservationNoSendOwnsReadsAsUncertainAfterARestart()
    {
        ReviewPublication reserved = new ReviewPublication(
                "before-restart", ReviewPublication.State.RESERVED, OptionalLong.empty(), OptionalLong.empty(), Optional.empty(), Instant.EPOCH);
        ReviewDraftItem item = manual("src/A.java", 10, "Was it sent?").withPublication(Optional.of(reserved));
        drafts.save(ReviewDraft.empty(REPO, NUMBER, NOW).withItems(ImmutableList.of(item), NOW));

        ReviewDraft restarted = new ReviewPublicationService(drafts, reviews, pullRequests, Clock.fixed(NOW, ZoneOffset.UTC)).draft(REPO, NUMBER);
        when(pullRequests.getPullRequestDetail(REPO, NUMBER)).thenReturn(detail());

        assertThat(restarted.items().getFirst().publication().orElseThrow().state()).isEqualTo(ReviewPublication.State.UNCERTAIN);
        assertThat(restarted.items().getFirst().publication().orElseThrow().attemptId()).isEqualTo("before-restart");
        assertThatThrownBy(() -> service.submit(REPO, NUMBER, restarted.version(), "Review", "COMMENT")).hasMessageContaining("unknown outcome");
        ReviewDraft pending = service.confirmNotPosted(REPO, NUMBER, restarted.version(), item.id());
        assertThat(pending.items().getFirst().publication()).isEmpty();
        verify(pullRequests, never()).submitReview(any(), anyInt(), anyLong(), any(), any(), any());
    }

    @Test
    void aSendWhoseResultCannotBeSavedReadsAsUncertainInsteadOfSending()
    {
        ReviewDraft draft = service.addManual(REPO, NUMBER, 0, manual("src/A.java", 10, "Rename this"));
        IllegalStateException locked = new IllegalStateException("database is locked");
        when(pullRequests.createReviewComment(eq(REPO), eq(NUMBER), any())).thenAnswer(invocation -> {
            drafts.failNextSave(locked);
            return posted();
        });

        assertThatThrownBy(() -> service.publishItem(REPO, NUMBER, draft.version(), lastItemId(draft))).isSameAs(locked);

        ReviewDraft after = service.draft(REPO, NUMBER);
        assertThat(after.items().getFirst().publication().orElseThrow().state()).isEqualTo(ReviewPublication.State.UNCERTAIN);
        assertThatThrownBy(() -> service.publishItem(REPO, NUMBER, after.version(), lastItemId(after))).hasMessageContaining("unknown outcome");
        verify(pullRequests, times(1)).createReviewComment(eq(REPO), eq(NUMBER), any());
    }

    @Test
    void aSendGitHubRefusedReturnsToThePendingReview()
    {
        ReviewDraft draft = service.addManual(REPO, NUMBER, 0, manual("src/A.java", 10, "Rename this"));
        ResponseStatusException invalid = new ResponseStatusException(HttpStatus.UNPROCESSABLE_ENTITY, "Line could not be resolved");
        when(pullRequests.createReviewComment(eq(REPO), eq(NUMBER), any())).thenThrow(invalid);

        assertThatThrownBy(() -> service.publishItem(REPO, NUMBER, draft.version(), lastItemId(draft))).isSameAs(invalid);

        ReviewDraft after = service.draft(REPO, NUMBER);
        assertThat(after.items().getFirst().publication()).isEmpty();
        when(pullRequests.getPullRequestDetail(REPO, NUMBER)).thenReturn(detail());
        when(pullRequests.submitReview(eq(REPO), eq(NUMBER), eq(PR_ID), any(), any(), any())).thenThrow(new GitHubRateLimitException("secondary", NOW.getEpochSecond() + 60));
        assertThatThrownBy(() -> service.submit(REPO, NUMBER, after.version(), "Review", "COMMENT")).isInstanceOf(GitHubRateLimitException.class);
        assertThat(service.draft(REPO, NUMBER).items().getFirst().publication()).isEmpty();
    }

    @Test
    void aFailedPullRequestLookupReservesAndSendsNothing()
    {
        ReviewDraft draft = service.addManual(REPO, NUMBER, 0, manual("src/A.java", 10, "Rename this"));
        IllegalStateException unreachable = new IllegalStateException("github is unreachable");
        when(pullRequests.getPullRequestDetail(REPO, NUMBER)).thenThrow(unreachable);

        assertThatThrownBy(() -> service.submit(REPO, NUMBER, draft.version(), "Review", "COMMENT")).isSameAs(unreachable);

        assertThat(service.draft(REPO, NUMBER)).isEqualTo(draft);
        verify(pullRequests, never()).submitReview(any(), anyInt(), anyLong(), any(), any(), any());
    }

    @Test
    void postNowRecordsTheLinkGitHubReturned()
    {
        ReviewDraft draft = service.addManual(REPO, NUMBER, 0, manual("src/A.java", 10, "Rename this"));
        PrTimelineEvent created = thread("src/A.java", comment(9001L, 77L, "Rename this", null));
        when(pullRequests.createReviewComment(eq(REPO), eq(NUMBER), any())).thenReturn(new PullRequestService.Posted(detail(), OptionalLong.empty(), ImmutableList.of(created)));

        ReviewPublicationService.Publication result = service.publishItem(REPO, NUMBER, draft.version(), lastItemId(draft));

        ReviewPublication publication = result.draft().items().getFirst().publication().orElseThrow();
        assertThat(publication.remoteCommentId()).hasValue(9001L);
        assertThat(publication.remoteReviewId()).hasValue(77L);
        assertThat(publication.url()).contains("https://github.com/octo/widgets/pull/7#discussion_r9001");
        assertThat(result.uncertainReason()).isEmpty();
    }

    @Test
    void submitMatchesEachCommentByFileAndTextWithinItsReview()
    {
        ReviewDraft draft = service.addManual(REPO, NUMBER, 0, manual("src/A.java", 10, "Rename this"));
        draft = service.addManual(REPO, NUMBER, draft.version(), new ReviewDraftItem("new", Source.MANUAL, Optional.empty(), OptionalInt.empty(), "src/B.java", "RIGHT", 20,
                OptionalInt.of(18), "Split this", true, Optional.empty(), Optional.of(REVIEWED_HEAD), OptionalLong.empty()));
        // GitHub reports no start line for this range and an older review carries the same text; neither may block the match.
        PrTimelineEvent range = thread("src/B.java", comment(9002L, 501L, "Split this", null));
        PrTimelineEvent older = thread("src/A.java", comment(8000L, 400L, "Rename this", null));
        PrTimelineEvent current = thread("src/A.java", comment(9001L, 501L, "Rename this", null));
        when(pullRequests.findHeadSha(REPO, NUMBER)).thenReturn(Optional.of(REVIEWED_HEAD));
        when(pullRequests.getPullRequestDetail(REPO, NUMBER)).thenReturn(detail());
        when(pullRequests.submitReview(eq(REPO), eq(NUMBER), eq(PR_ID), any(), any(), any()))
                .thenReturn(new PullRequestService.Posted(detail(), OptionalLong.of(501L), ImmutableList.of(older, range, current)));

        ReviewPublicationService.Publication result = service.submit(REPO, NUMBER, draft.version(), "", "COMMENT");

        assertThat(result.draft().items()).extracting(item -> item.publication().orElseThrow().remoteCommentId()).containsExactly(OptionalLong.of(9001L), OptionalLong.of(9002L));
        assertThat(result.draft().items()).extracting(item -> item.publication().orElseThrow().remoteReviewId()).containsOnly(OptionalLong.of(501L));
        assertThat(result.uncertainReason()).isEmpty();
    }

    @Test
    void aSuccessfulSubmitResetsTheReviewAndMarksTheItemsPublished()
    {
        ReviewDraft draft = service.addManual(REPO, NUMBER, 0, manual("src/A.java", 10, "Rename this"));
        String itemId = lastItemId(draft);
        draft = service.saveReview(REPO, NUMBER, draft.version(), "Please fix these", "REQUEST_CHANGES");
        when(pullRequests.findHeadSha(REPO, NUMBER)).thenReturn(Optional.of(CURRENT_HEAD));
        when(pullRequests.getPullRequestDetail(REPO, NUMBER)).thenReturn(detail());
        when(pullRequests.submitReview(eq(REPO), eq(NUMBER), eq(PR_ID), eq("APPROVE"), eq("Looks good"), any())).thenReturn(posted());

        ReviewPublicationService.Publication published = service.submit(REPO, NUMBER, draft.version(), "Looks good", "APPROVE");

        assertThat(published.publishedItemIds()).containsExactly(itemId);
        assertThat(published.draft().body()).isEmpty();
        assertThat(published.draft().event()).isEqualTo(ReviewDraft.DEFAULT_EVENT);
        ReviewDraftItem item = published.draft().items().getFirst();
        assertThat(item.published()).isTrue();
        assertThat(item.publication().orElseThrow().state()).isEqualTo(ReviewPublication.State.PUBLISHED);
        assertThat(service.draft(REPO, NUMBER)).isEqualTo(published.draft());
    }

    @Test
    void partialGroupedFailureKeepsSuccessfulItemsAndReleasesOnlyUnsentItems()
    {
        String middle = "c".repeat(40);
        ReviewDraft draft = service.addManual(REPO, NUMBER, 0, manual("src/A.java", 1, "First"));
        draft = service.addManual(REPO, NUMBER, draft.version(), manual("src/B.java", 2, "Second").withCommitId(middle));
        draft = service.addManual(REPO, NUMBER, draft.version(), manual("src/C.java", 3, "Third").withCommitId(CURRENT_HEAD));
        when(pullRequests.findHeadSha(REPO, NUMBER)).thenReturn(Optional.of(CURRENT_HEAD));
        when(pullRequests.getPullRequestDetail(REPO, NUMBER)).thenReturn(detail());
        when(pullRequests.submitReview(eq(REPO), eq(NUMBER), eq(PR_ID), any(), any(), any()))
                .thenReturn(posted()).thenThrow(new IllegalStateException("Reply lost"));
        long version = draft.version();

        assertThatThrownBy(() -> service.submit(REPO, NUMBER, version, "Final summary", "APPROVE")).hasMessage("Reply lost");

        ReviewDraft after = service.draft(REPO, NUMBER);
        assertThat(after.items().get(0).publication()).hasValueSatisfying(value -> assertThat(value.state()).isEqualTo(ReviewPublication.State.PUBLISHED));
        assertThat(after.items().get(1).publication()).hasValueSatisfying(value -> assertThat(value.state()).isEqualTo(ReviewPublication.State.UNCERTAIN));
        assertThat(after.items()).extracting(ReviewDraftItem::published).containsExactly(true, false, false);
        assertThat(after.items().get(2).publication()).isEmpty();
        assertThat(after.body()).isEqualTo("Final summary");
        assertThatThrownBy(() -> service.submit(REPO, NUMBER, after.version(), after.body(), after.event())).hasMessageContaining("unknown outcome");
        verify(pullRequests, times(2)).submitReview(eq(REPO), eq(NUMBER), eq(PR_ID), eq("COMMENT"), eq(""), any());

        ReviewDraft ready = service.confirmNotPosted(REPO, NUMBER, after.version(), after.items().get(1).id());
        when(pullRequests.submitReview(eq(REPO), eq(NUMBER), eq(PR_ID), any(), any(), any())).thenReturn(posted());
        ReviewPublicationService.Publication retried = service.submit(REPO, NUMBER, ready.version(), ready.body(), ready.event());
        assertThat(retried.publishedItemIds()).containsExactly(after.items().get(1).id(), after.items().get(2).id());
        ArgumentCaptor<List<InlineReviewComment>> comments = ArgumentCaptor.captor();
        verify(pullRequests, times(4)).submitReview(eq(REPO), eq(NUMBER), eq(PR_ID), any(), any(), comments.capture());
        assertThat(comments.getAllValues().stream().flatMap(List::stream).map(InlineReviewComment::body))
                .containsExactly("First", "Second", "Second", "Third");
    }

    @Test
    void reservedItemsCannotBeChangedAndNewerReviewTextSurvivesSubmission()
    {
        ReviewDraft draft = service.addManual(REPO, NUMBER, 0, manual("src/A.java", 1, "First"));
        when(pullRequests.getPullRequestDetail(REPO, NUMBER)).thenReturn(detail());
        when(pullRequests.submitReview(eq(REPO), eq(NUMBER), eq(PR_ID), any(), any(), any())).thenAnswer(invocation -> {
            ReviewDraft sending = service.draft(REPO, NUMBER);
            String itemId = lastItemId(sending);
            assertThatThrownBy(() -> service.editItem(REPO, NUMBER, sending.version(), itemId, "Changed", true)).hasMessageContaining("being sent");
            assertThatThrownBy(() -> service.removeItem(REPO, NUMBER, sending.version(), itemId)).hasMessageContaining("being sent");
            assertThatThrownBy(() -> service.confirmNotPosted(REPO, NUMBER, sending.version(), itemId)).hasMessageContaining("Only a comment with unconfirmed delivery");
            service.saveReview(REPO, NUMBER, sending.version(), "Next review notes", "REQUEST_CHANGES");
            return posted();
        });

        service.submit(REPO, NUMBER, draft.version(), "Submitted notes", "COMMENT");

        ReviewDraft after = service.draft(REPO, NUMBER);
        assertThat(after.items().getFirst().body()).isEqualTo("First");
        assertThat(after.body()).isEqualTo("Next review notes");
        assertThat(after.event()).isEqualTo("REQUEST_CHANGES");
    }

    @Test
    void aLegacyManualDraftIsPinnedWhenPreviewedAndNotRetargetedAfterAPush()
    {
        ReviewDraftItem old = new ReviewDraftItem("old", Source.MANUAL, Optional.empty(), OptionalInt.empty(), "src/A.java", "RIGHT", 1,
                OptionalInt.empty(), "User wording", true, Optional.empty(), Optional.empty(), OptionalLong.empty());
        drafts.save(ReviewDraft.empty(REPO, NUMBER, NOW).withItems(ImmutableList.of(old), NOW));
        when(pullRequests.findHeadSha(REPO, NUMBER)).thenReturn(Optional.of(REVIEWED_HEAD));
        ReviewDraft preview = service.draft(REPO, NUMBER);
        assertThat(preview.items().getFirst().commitId()).contains(REVIEWED_HEAD);
        when(pullRequests.findHeadSha(REPO, NUMBER)).thenReturn(Optional.of(CURRENT_HEAD));
        when(pullRequests.getPullRequestDetail(REPO, NUMBER)).thenReturn(detail());
        when(pullRequests.submitReview(eq(REPO), eq(NUMBER), eq(PR_ID), any(), any(), any())).thenReturn(posted());

        service.submit(REPO, NUMBER, preview.version(), "", "COMMENT");

        ArgumentCaptor<List<InlineReviewComment>> sent = ArgumentCaptor.captor();
        verify(pullRequests).submitReview(eq(REPO), eq(NUMBER), eq(PR_ID), eq("COMMENT"), eq(""), sent.capture());
        assertThat(sent.getValue()).singleElement().satisfies(item -> assertThat(item.commitId()).isEqualTo(REVIEWED_HEAD));
    }

    @Test
    void anEarlierAcceptedRunDoesNotCertifyANewSameHeadFinding()
    {
        ReviewFinding original = finding(FINDING_ID, 1, "src/A.java", 1, 1, "Provisional claim");
        ReviewFinding provisional = new ReviewFinding(original.id(), original.reviewId(), "running-run", original.ordinal(), original.title(), original.body(),
                original.severity(), original.anchor(), original.evidence(), original.revision(), original.state(), original.stateReason(),
                original.validatedHeadSha(), false, original.version(), original.createdAt(), original.updatedAt(), ImmutableList.of(), ReviewFinding.Origin.AUTOMATIC);
        reviews.put(provisional);

        assertThatThrownBy(() -> service.addFinding(REPO, NUMBER, 0, REVIEW_ID, FINDING_ID)).hasMessageContaining("accepted review of its source revision");
        verifyNoInteractions(pullRequests);
    }

    private static ReviewRun acceptedReport(ReviewFinding finding, boolean supported, boolean selected)
    {
        ReviewInvestigation investigation = new ReviewInvestigation("Check the guard", ImmutableList.of("src/Service.java"), ImmutableList.of(),
                ImmutableList.of(ReviewTestData.discoveryCandidate(1, "The guard may be missing")), ImmutableList.of(), ImmutableList.of(),
                Optional.empty(), ReviewTestData.perspectives(), Optional.empty());
        ReviewStep sealed = ReviewStep.initial().withInvestigation(investigation);
        return run(ReviewRun.State.ACCEPTED).withStep(sealed, NOW)
                .withStep(sealed.next(ReviewStep.Kind.VALIDATE).withValidation(validation(1, finding, supported, selected)), NOW);
    }

    private static ReviewValidation validation(int investigationStep, ReviewFinding finding, boolean supported, boolean selected)
    {
        ReviewCandidateValidation assessment = new ReviewCandidateValidation(1,
                supported ? ReviewCandidateValidation.Outcome.SUPPORTED : ReviewCandidateValidation.Outcome.REJECTED,
                supported ? "The changed path misses the guard" : "The caller guarantees the guard", ImmutableList.of(), "Traced all relevant callers",
                supported ? Optional.of(finding.id()) : Optional.empty(), supported ? Optional.of(finding.revision()) : Optional.empty(), Optional.empty());
        return new ReviewValidation(investigationStep, ImmutableList.of(assessment), new ReviewCoverage(true, ImmutableList.of(), ImmutableList.of(), NOW),
                ImmutableList.of(), selected ? ImmutableList.of(finding.id()) : ImmutableList.of(), selected ? "Recommend this finding" : "Not selected", ImmutableList.of());
    }

    private static ReviewDraftItem manual(String path, int line, String body)
    {
        return new ReviewDraftItem("new", Source.MANUAL, Optional.empty(), OptionalInt.empty(), path, "RIGHT", line, OptionalInt.empty(), body,
                true, Optional.empty(), Optional.of(REVIEWED_HEAD), OptionalLong.empty());
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
                Optional.of(new ReviewAnchor(path, "RIGHT", startLine, endLine, ImmutableList.of("  return value;"))),
                ImmutableList.of(),
                1,
                ReviewFinding.State.OPEN,
                Optional.empty(),
                REVIEWED_HEAD,
                false,
                0,
                NOW,
                NOW, ImmutableList.of(), ReviewFinding.Origin.AUTOMATIC);
    }

    private static ReviewRun run(ReviewRun.State state)
    {
        return new ReviewRun(RUN_ID, REVIEW_ID, 1, ReviewRun.Scope.FULL, "main", CURRENT_HEAD, CURRENT_HEAD, REVIEWED_HEAD, Optional.empty(),
                Optional.empty(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), state, Optional.empty(), Optional.empty(), Optional.empty(),
                ImmutableList.of(), false, Map.of(), Map.of(), Optional.empty(), 0, NOW, NOW,
                state.terminal() ? Optional.of(NOW) : Optional.empty());
    }

    private static String lastItemId(ReviewDraft draft)
    {
        return draft.items().getLast().id();
    }

    private static PrTimelineEvent thread(String path, PrTimelineComment comment)
    {
        return new PrTimelineEvent(comment.githubId(), "review_thread", "me", NOW, null, PrTimelinePayload.builder().path(path).comments(ImmutableList.of(comment)).build(),
                Optional.empty());
    }

    private static PrTimelineComment comment(long githubId, long reviewId, String body, Long inReplyToId)
    {
        return new PrTimelineComment(githubId, "me", null, false, NOW, body, "https://github.com/octo/widgets/pull/7#discussion_r" + githubId, reviewId, inReplyToId,
                ImmutableList.of());
    }

    /** A send GitHub accepted without identifying any comment. */
    private static PullRequestService.Posted posted()
    {
        return new PullRequestService.Posted(detail(), OptionalLong.empty(), ImmutableList.of());
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
        private Optional<RuntimeException> failNext = Optional.empty();

        void failNextSave(RuntimeException failure)
        {
            failNext = Optional.of(failure);
        }

        @Override
        public Optional<ReviewDraft> find(String repo, int number)
        {
            return Optional.ofNullable(stored.get(repo.toLowerCase(Locale.ROOT) + "#" + number));
        }

        @Override
        public ReviewDraft save(ReviewDraft draft)
        {
            Optional<RuntimeException> failure = failNext;
            failNext = Optional.empty();
            failure.ifPresent(value -> {
                throw value;
            });
            long current = Optional.ofNullable(stored.get(draft.id())).map(ReviewDraft::version).orElse(0L);
            if (current != draft.version()) {
                throw new ReviewConflictException("Review draft version changed: " + draft.id());
            }
            ReviewDraft saved = draft.withVersion(draft.version() + 1);
            stored.put(saved.id(), saved);
            return saved;
        }
    }

    /** The publication service reads review acceptance and finding state without changing either. */
    private static final class InMemoryAgentReviewStore
            implements AgentReviewStore
    {
        private final Map<String, ReviewFinding> findings = new HashMap<>();
        private final List<ReviewRun> runs = new ArrayList<>(ImmutableList.of(run(ReviewRun.State.ACCEPTED)));
        private final AgentReview review = new AgentReview(
                REVIEW_ID,
                "workspace-1",
                REPO,
                OptionalInt.of(NUMBER),
                OptionalLong.of(PR_ID),
                "task-1",
                Optional.empty(),
                AgentReview.State.OPEN,
                false,
                Optional.of(RUN_ID),
                Optional.of(REVIEWED_HEAD),
                Optional.of(REVIEWED_HEAD),
                0,
                NOW,
                NOW,
                Optional.empty(),
                Optional.empty());

        void put(ReviewFinding finding)
        {
            findings.put(finding.id(), finding);
        }

        void setRun(ReviewRun run)
        {
            runs.clear();
            runs.add(run);
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
        public Optional<AgentReview> findReview(String reviewId) { return REVIEW_ID.equals(reviewId) ? Optional.of(review) : Optional.empty(); }

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
        public List<ReviewRun> listRuns(String reviewId)
        {
            if (!REVIEW_ID.equals(reviewId)) {
                return ImmutableList.of();
            }
            return runs.stream().map(run -> {
                if (run.state() != ReviewRun.State.ACCEPTED || !run.steps().isEmpty()) {
                    return run;
                }
                List<ReviewFinding> selected = findings.values().stream().filter(finding -> finding.runId().equals(run.id())).collect(toImmutableList());
                ReviewValidation validation = new ReviewValidation(1, IntStream.range(0, selected.size())
                        .mapToObj(index -> new ReviewCandidateValidation(index + 1, ReviewCandidateValidation.Outcome.SUPPORTED, "Confirmed against source",
                                ImmutableList.of(), "No counterexample", Optional.of(selected.get(index).id()), Optional.of(selected.get(index).revision()), Optional.empty()))
                        .collect(toImmutableList()), new ReviewCoverage(true, ImmutableList.of(), ImmutableList.of(), NOW), ImmutableList.of(),
                        selected.stream().map(ReviewFinding::id).collect(toImmutableList()), "Select the supported findings", ImmutableList.of());
                return run.withStep(ReviewStep.initial(), NOW).withStep(ReviewStep.initial().next(ReviewStep.Kind.VALIDATE).withValidation(validation), NOW);
            }).collect(toImmutableList());
        }

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
        public Optional<ReviewMessage> findMessage(String messageId) { throw new UnsupportedOperationException(); }

        @Override
        public void deleteMessage(String messageId) { throw new UnsupportedOperationException(); }

        @Override
        public List<ReviewMessage> listMessages(String reviewId) { throw new UnsupportedOperationException(); }
    }
}
