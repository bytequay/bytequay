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

import com.bytequay.app.domain.DiffFile;
import com.bytequay.app.domain.HandledAction;
import com.bytequay.app.domain.MergePullRequestCommand;
import com.bytequay.app.domain.PrCheckRunState;
import com.bytequay.app.domain.PrCiSnapshot;
import com.bytequay.app.domain.PrRawDetail;
import com.bytequay.app.domain.PrReviewState;
import com.bytequay.app.domain.PrReviewThreadMessage;
import com.bytequay.app.domain.PrTimelineEvent;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.PullRequestRef;
import com.bytequay.app.domain.Reactions;
import com.bytequay.app.domain.RepoRef;
import com.bytequay.app.domain.StoredPrDetail;
import com.bytequay.app.domain.SuggestedReviewer;
import com.bytequay.app.domain.UpdatePullRequestCommand;
import com.bytequay.app.repository.AppSettingsStore;
import com.bytequay.app.repository.PrDetailStore;
import com.bytequay.app.repository.PrViewStateStore;
import com.bytequay.app.repository.PullRequestRepository;
import com.bytequay.app.repository.PullRequestStore;
import com.bytequay.app.service.CredentialService;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Executor;

import static com.bytequay.app.domain.PullRequestDetail.CiStatus.FAILING;
import static com.bytequay.app.domain.PullRequestDetail.CiStatus.NONE;
import static com.bytequay.app.domain.PullRequestDetail.CiStatus.PASSING;
import static com.bytequay.app.domain.PullRequestDetail.CiStatus.PENDING;
import static com.bytequay.app.repository.AppSettingsStore.Key.PR_SORT_ORDER;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.HttpStatus.BAD_REQUEST;

@ExtendWith(MockitoExtension.class)
class TestPullRequestService
{
    @Mock
    private PullRequestRepository gitHub;

    @Mock
    private PullRequestStore store;

    @Mock
    private PrDetailStore detailStore;

    @Mock
    private PrViewStateStore viewStateStore;

    @Mock
    private AppSettingsStore settingsStore;

    // Consumed by @InjectMocks via reflection — Error Prone can't see the use.
    @SuppressWarnings("UnusedVariable")
    @Mock
    private CredentialService credentialService;

    @Mock
    private GitHubResponseCache responseCache;

    @Mock
    private PullRequestDetailInvalidator detailInvalidator;

    @SuppressWarnings("UnusedVariable")
    @Mock
    private Executor executor;

    @InjectMocks
    private PullRequestService pullRequestService;

    // ── listPullRequests ───────────────────────────────────────────────────────

    @Test
    void testListPullRequestsReadsFromStore()
    {
        when(store.findAll()).thenReturn(ImmutableList.of());
        when(settingsStore.get(PR_SORT_ORDER)).thenReturn(Optional.of("updated-desc"));

        pullRequestService.listPullRequests();

        verify(store).findAll();
        verify(settingsStore).get(PR_SORT_ORDER);
    }

    @Test
    void testListPullRequestsDefaultsToSmartWhenNoSetting()
    {
        when(store.findAll()).thenReturn(ImmutableList.of());
        when(settingsStore.get(PR_SORT_ORDER)).thenReturn(Optional.empty());

        pullRequestService.listPullRequests();

        verify(store).findAll();
    }

    // ── getPullRequestDetail input validation ──────────────────────────────────

    @Test
    void testGetPullRequestDetailNoSlashThrows400()
    {
        assertThatThrownBy(() -> pullRequestService.getPullRequestDetail("pat", "just-a-name", 1))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(BAD_REQUEST.value()));
    }

    @Test
    void testGetPullRequestDetailBlankOwnerThrows400()
    {
        assertThatThrownBy(() -> pullRequestService.getPullRequestDetail("pat", "/repo", 1))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(BAD_REQUEST.value()));
    }

    @Test
    void testGetPullRequestDetailBlankRepoThrows400()
    {
        assertThatThrownBy(() -> pullRequestService.getPullRequestDetail("pat", "owner/", 1))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value()).isEqualTo(BAD_REQUEST.value()));
    }

    @Test
    void testGetPullRequestDetailReturnsFromCacheWhenAvailable()
    {
        PrRawDetail raw = new PrRawDetail(
                "body", ImmutableList.of("bug"), false, true, "clean", 10, 3, 2, 1,
                ImmutableList.of("alice"), "sha",
                "feat/foo", "owner/my-repo", "main", "owner/my-repo");
        StoredPrDetail stored = new StoredPrDetail(
                raw,
                ImmutableList.of(new PrReviewState("alice", "APPROVED")),
                ImmutableList.of(new PullRequestDetail.ChangedFile("file.txt", 5, 2, "modified")),
                ImmutableList.of(),
                ImmutableList.of(new PrCheckRunState(null, null, "completed", "success", null, null, null)),
                ImmutableList.of(),
                ImmutableList.of());
        when(store.findIdByRepoAndNumber("owner/my-repo", 42)).thenReturn(Optional.of(7L));
        when(detailStore.find(7L)).thenReturn(Optional.of(stored));
        when(responseCache.getViewerCanWrite(eq("pat"), eq(RepoRef.of("owner", "my-repo")), any()))
                .thenReturn(true);

        PullRequestDetail result = pullRequestService.getPullRequestDetail("pat", "owner/my-repo", 42);

        assertThat(result.repo()).isEqualTo("owner/my-repo");
        assertThat(result.number()).isEqualTo(42);
        assertThat(result.body()).isEqualTo("body");
        assertThat(result.approvalCount()).isEqualTo(1);
        assertThat(result.changesRequestedCount()).isZero();
        assertThat(result.ciStatus()).isEqualTo(PASSING);
        assertThat(result.files()).hasSize(1);
        assertThat(result.viewerCanWrite()).isTrue();
        verify(responseCache).getViewerCanWrite(eq("pat"), eq(RepoRef.of("owner", "my-repo")), any());
    }

    @Test
    void testGetPullRequestCiSnapshotReadsViewerPermissionFromCache()
    {
        PrRawDetail raw = new PrRawDetail(
                "body", ImmutableList.of(), false, true, "clean", 10, 0, 0, 0,
                ImmutableList.of(), "sha",
                "feat/foo", "owner/repo", "main", "owner/repo");
        when(gitHub.fetchPrDetail(eq("pat"), any(PullRequestRef.class))).thenReturn(raw);
        when(gitHub.fetchPrCheckRuns("pat", "owner", "repo", "sha")).thenReturn(ImmutableList.of());
        when(responseCache.getViewerCanWrite(eq("pat"), eq(RepoRef.of("owner", "repo")), any()))
                .thenReturn(true);

        PrCiSnapshot result = pullRequestService.getPullRequestCiSnapshot("pat", "owner/repo", 7);

        assertThat(result.viewerCanWrite()).isTrue();
        verify(responseCache).getViewerCanWrite(eq("pat"), eq(RepoRef.of("owner", "repo")), any());
    }

    // ── countApprovals ─────────────────────────────────────────────────────────

    @Test
    void testCountApprovalsEmpty()
    {
        assertThat(PullRequestService.countApprovals(ImmutableList.of())).isZero();
    }

    @Test
    void testCountApprovalsSingleApproval()
    {
        assertThat(PullRequestService.countApprovals(ImmutableList.of(
                new PrReviewState("alice", "APPROVED")))).isEqualTo(1);
    }

    @Test
    void testCountApprovalsTwoApprovals()
    {
        assertThat(PullRequestService.countApprovals(ImmutableList.of(
                new PrReviewState("alice", "APPROVED"),
                new PrReviewState("bob", "APPROVED")))).isEqualTo(2);
    }

    @Test
    void testCountApprovalsIgnoresOtherStates()
    {
        assertThat(PullRequestService.countApprovals(ImmutableList.of(
                new PrReviewState("alice", "APPROVED"),
                new PrReviewState("bob", "CHANGES_REQUESTED"),
                new PrReviewState("carol", "COMMENTED")))).isEqualTo(1);
    }

    // ── countChangesRequested ──────────────────────────────────────────────────

    @Test
    void testCountChangesRequestedEmpty()
    {
        assertThat(PullRequestService.countChangesRequested(ImmutableList.of())).isZero();
    }

    @Test
    void testCountChangesRequestedSingle()
    {
        assertThat(PullRequestService.countChangesRequested(ImmutableList.of(
                new PrReviewState("alice", "CHANGES_REQUESTED")))).isEqualTo(1);
    }

    @Test
    void testCountChangesRequestedTwoDistinct()
    {
        assertThat(PullRequestService.countChangesRequested(ImmutableList.of(
                new PrReviewState("alice", "CHANGES_REQUESTED"),
                new PrReviewState("bob", "CHANGES_REQUESTED")))).isEqualTo(2);
    }

    @Test
    void testCountChangesRequestedIgnoresApproved()
    {
        assertThat(PullRequestService.countChangesRequested(ImmutableList.of(
                new PrReviewState("alice", "APPROVED"),
                new PrReviewState("bob", "CHANGES_REQUESTED")))).isEqualTo(1);
    }

    // ── aggregateCiStatus ──────────────────────────────────────────────────────

    @Test
    void testAggregateCiStatusEmptyReturnsNone()
    {
        assertThat(PullRequestService.aggregateCiStatus(ImmutableList.of())).isEqualTo(NONE);
    }

    @Test
    void testAggregateCiStatusAllSuccessReturnsPassing()
    {
        assertThat(PullRequestService.aggregateCiStatus(ImmutableList.of(
                new PrCheckRunState(null, null, "completed", "success", null, null, null),
                new PrCheckRunState(null, null, "completed", "success", null, null, null)))).isEqualTo(PASSING);
    }

    @Test
    void testAggregateCiStatusAnyFailureReturnsFailing()
    {
        assertThat(PullRequestService.aggregateCiStatus(ImmutableList.of(
                new PrCheckRunState(null, null, "completed", "success", null, null, null),
                new PrCheckRunState(null, null, "completed", "failure", null, null, null)))).isEqualTo(FAILING);
    }

    @Test
    void testAggregateCiStatusCancelledReturnsFailing()
    {
        assertThat(PullRequestService.aggregateCiStatus(ImmutableList.of(
                new PrCheckRunState(null, null, "completed", "cancelled", null, null, null)))).isEqualTo(FAILING);
    }

    @Test
    void testAggregateCiStatusInProgressReturnsPending()
    {
        assertThat(PullRequestService.aggregateCiStatus(ImmutableList.of(
                new PrCheckRunState(null, null, "in_progress", null, null, null, null)))).isEqualTo(PENDING);
    }

    @Test
    void testAggregateCiStatusQueuedReturnsPending()
    {
        assertThat(PullRequestService.aggregateCiStatus(ImmutableList.of(
                new PrCheckRunState(null, null, "queued", null, null, null, null)))).isEqualTo(PENDING);
    }

    @Test
    void testAggregateCiStatusFailureTakesPriorityOverPending()
    {
        assertThat(PullRequestService.aggregateCiStatus(ImmutableList.of(
                new PrCheckRunState(null, null, "in_progress", null, null, null, null),
                new PrCheckRunState(null, null, "completed", "failure", null, null, null)))).isEqualTo(FAILING);
    }

    // ── toActivityItems ────────────────────────────────────────────────────────

    @Test
    void testToActivityItemsEmpty()
    {
        assertThat(PullRequestService.toActivityItems(ImmutableList.of())).isEmpty();
    }

    @Test
    void testToActivityItemsUninterestingEventsFiltered()
    {
        PrTimelineEvent event = new PrTimelineEvent(null, "labeled", "alice", null, Instant.now(), null, null, null, null, null, null, Reactions.EMPTY);
        assertThat(PullRequestService.toActivityItems(ImmutableList.of(event))).isEmpty();
    }

    @Test
    void testToActivityItemsInterestingEventsKept()
    {
        Instant now = Instant.now();
        PrTimelineEvent reviewed = new PrTimelineEvent(null, "reviewed", "alice", "APPROVED", now, null, null, null, null, null, null, Reactions.EMPTY);
        PrTimelineEvent commented = new PrTimelineEvent(null, "commented", "bob", null, now, null, null, null, null, null, null, Reactions.EMPTY);
        List<PullRequestDetail.ActivityItem> items =
                PullRequestService.toActivityItems(ImmutableList.of(reviewed, commented));
        assertThat(items).hasSize(2);
    }

    @Test
    void testToActivityItemsResultIsReversedMostRecentFirst()
    {
        Instant now = Instant.now();
        PrTimelineEvent first = new PrTimelineEvent(null, "commented", "first", null, now, null, null, null, null, null, null, Reactions.EMPTY);
        PrTimelineEvent second = new PrTimelineEvent(null, "commented", "second", null, now.plusSeconds(60), null, null, null, null, null, null, Reactions.EMPTY);
        List<PullRequestDetail.ActivityItem> items =
                PullRequestService.toActivityItems(ImmutableList.of(first, second));
        assertThat(items.get(0).actor()).isEqualTo("second");
        assertThat(items.get(1).actor()).isEqualTo("first");
    }

    @Test
    void testToActivityItemsKeepsAllEventsNewestFirst()
    {
        // No cap — long-lived PRs can have 300+ events and the user
        // expects to see them all. Verify we keep every event and
        // sort newest-first.
        Instant base = Instant.now();
        List<PrTimelineEvent> events = Lists.newArrayList();
        for (int i = 0; i < 500; i++) {
            events.add(new PrTimelineEvent(null, "commented", "user" + i, null,
                    base.plusSeconds(i), null, null, null, null, null, null, Reactions.EMPTY));
        }
        List<PullRequestDetail.ActivityItem> items = PullRequestService.toActivityItems(events);
        assertThat(items).hasSize(500);
        assertThat(items.get(0).actor()).isEqualTo("user499");
        assertThat(items.get(499).actor()).isEqualTo("user0");
    }

    @Test
    void testToActivityItemsMapsEventTypeDirectly()
    {
        PrTimelineEvent event = new PrTimelineEvent(null, "merged", "alice", null, Instant.now(), null, null, null, null, null, null, Reactions.EMPTY);
        List<PullRequestDetail.ActivityItem> items =
                PullRequestService.toActivityItems(ImmutableList.of(event));
        assertThat(items).hasSize(1);
        assertThat(items.get(0).eventType()).isEqualTo("merged");
        assertThat(items.get(0).actor()).isEqualTo("alice");
    }

    // ── deriveHandledActionFromReviews ─────────────────────────────────────────

    @Test
    void testDeriveHandledActionReturnsNullForNullLogin()
    {
        List<PrReviewState> reviews = ImmutableList.of(new PrReviewState("alice", "APPROVED"));
        assertThat(PullRequestService.deriveHandledActionFromReviews(reviews, null)).isNull();
    }

    @Test
    void testDeriveHandledActionReturnsNullForEmptyReviews()
    {
        assertThat(PullRequestService.deriveHandledActionFromReviews(ImmutableList.of(), "alice")).isNull();
    }

    @Test
    void testDeriveHandledActionReturnsNullWhenUserHasNoReview()
    {
        List<PrReviewState> reviews = ImmutableList.of(
                new PrReviewState("bob", "APPROVED"),
                new PrReviewState("carol", "CHANGES_REQUESTED"));
        assertThat(PullRequestService.deriveHandledActionFromReviews(reviews, "alice")).isNull();
    }

    @Test
    void testDeriveHandledActionReturnsApprovedWhenUserApproved()
    {
        List<PrReviewState> reviews = ImmutableList.of(
                new PrReviewState("bob", "COMMENTED"),
                new PrReviewState("alice", "APPROVED"));
        assertThat(PullRequestService.deriveHandledActionFromReviews(reviews, "alice"))
                .isEqualTo(HandledAction.APPROVED);
    }

    @Test
    void testDeriveHandledActionReturnsChangesRequested()
    {
        List<PrReviewState> reviews = ImmutableList.of(new PrReviewState("alice", "CHANGES_REQUESTED"));
        assertThat(PullRequestService.deriveHandledActionFromReviews(reviews, "alice"))
                .isEqualTo(HandledAction.CHANGES_REQUESTED);
    }

    @Test
    void testDeriveHandledActionMatchesLoginCaseInsensitively()
    {
        List<PrReviewState> reviews = ImmutableList.of(new PrReviewState("Alice", "APPROVED"));
        assertThat(PullRequestService.deriveHandledActionFromReviews(reviews, "alice"))
                .isEqualTo(HandledAction.APPROVED);
    }

    @Test
    void testDeriveHandledActionLaterReviewWins()
    {
        // User first asked for changes, then later approved.
        List<PrReviewState> reviews = ImmutableList.of(
                new PrReviewState("alice", "CHANGES_REQUESTED"),
                new PrReviewState("alice", "APPROVED"));
        assertThat(PullRequestService.deriveHandledActionFromReviews(reviews, "alice"))
                .isEqualTo(HandledAction.APPROVED);
    }

    @Test
    void testDeriveHandledActionDismissalResetsState()
    {
        // Approved → dismissed → (nothing). User has no effective stance.
        List<PrReviewState> reviews = ImmutableList.of(
                new PrReviewState("alice", "APPROVED"),
                new PrReviewState("alice", "DISMISSED"));
        assertThat(PullRequestService.deriveHandledActionFromReviews(reviews, "alice")).isNull();
    }

    @Test
    void testDeriveHandledActionIgnoresCommentedReviews()
    {
        // Drive-by COMMENTED shouldn't auto-mark as handled.
        List<PrReviewState> reviews = ImmutableList.of(new PrReviewState("alice", "COMMENTED"));
        assertThat(PullRequestService.deriveHandledActionFromReviews(reviews, "alice")).isNull();
    }

    @Test
    void testDeriveHandledActionSkipsOtherUsersEntries()
    {
        List<PrReviewState> reviews = ImmutableList.of(
                new PrReviewState("bob", "CHANGES_REQUESTED"),
                new PrReviewState("alice", "APPROVED"),
                new PrReviewState("carol", "APPROVED"));
        assertThat(PullRequestService.deriveHandledActionFromReviews(reviews, "alice"))
                .isEqualTo(HandledAction.APPROVED);
    }

    // ── commentOnPullRequest ───────────────────────────────────────────────────

    @Test
    void testCommentOnPullRequestPostsCommentOnly()
    {
        pullRequestService.commentOnPullRequest("pat", "owner/repo", 7, 99L, "LGTM", false);

        verify(gitHub).createIssueComment(eq("pat"), any(PullRequestRef.class), eq("LGTM"));
        verify(gitHub, never()).updatePullRequest(anyString(), any(), any());
        verify(viewStateStore, never()).markReviewed(anyLong(), any());
        verifyNoInteractions(responseCache);
        verifyNoInteractions(detailInvalidator);
    }

    @Test
    void testCommentOnPullRequestClosesWithoutCommentWhenBodyBlank()
    {
        pullRequestService.commentOnPullRequest("pat", "owner/repo", 7, 99L, "   ", true);

        verify(gitHub, never()).createIssueComment(anyString(), any(), anyString());
        verify(gitHub).updatePullRequest(eq("pat"), any(PullRequestRef.class), any(UpdatePullRequestCommand.class));
        verify(viewStateStore).markReviewed(99L, HandledAction.DISMISSED);
        verify(detailInvalidator).invalidate("owner/repo", 7);
    }

    @Test
    void testCommentOnPullRequestPostsCommentThenCloses()
    {
        pullRequestService.commentOnPullRequest("pat", "owner/repo", 7, 99L, "not needed anymore", true);

        verify(gitHub).createIssueComment(eq("pat"), any(PullRequestRef.class), eq("not needed anymore"));
        verify(gitHub).updatePullRequest(eq("pat"), any(PullRequestRef.class), any(UpdatePullRequestCommand.class));
        verify(viewStateStore).markReviewed(99L, HandledAction.DISMISSED);
        verify(detailInvalidator).invalidate("owner/repo", 7);
    }

    @Test
    void testCommentOnPullRequestNoOpWhenBlankBodyAndNoClose()
    {
        pullRequestService.commentOnPullRequest("pat", "owner/repo", 7, 99L, "", false);

        verifyNoInteractions(gitHub);
        verify(viewStateStore, never()).markReviewed(anyLong(), any());
        verifyNoInteractions(responseCache);
        verifyNoInteractions(detailInvalidator);
    }

    // ── structural mutation invalidation ──────────────────────────────────────

    @Test
    void testSetPullRequestDraftInvalidatesDetail()
    {
        pullRequestService.setPullRequestDraft("pat", "owner/repo", 7, true);

        verify(gitHub).setPullRequestDraft(eq("pat"), eq(PullRequestRef.of("owner", "repo", 7)), eq(true));
        verify(detailInvalidator).invalidate("owner/repo", 7);
    }

    @Test
    void testAddRequestedReviewerInvalidatesDetail()
    {
        pullRequestService.addRequestedReviewer("pat", "owner/repo", 7, " alice ");

        verify(gitHub).requestReviewers(eq("pat"), eq(PullRequestRef.of("owner", "repo", 7)), any());
        verify(detailInvalidator).invalidate("owner/repo", 7);
    }

    @Test
    void testRemoveRequestedReviewerInvalidatesDetail()
    {
        pullRequestService.removeRequestedReviewer("pat", "owner/repo", 7, "alice");

        verify(gitHub).removeRequestedReviewers(eq("pat"), eq(PullRequestRef.of("owner", "repo", 7)), any());
        verify(detailInvalidator).invalidate("owner/repo", 7);
    }

    @Test
    void testCreateInlineReviewCommentInvalidatesDetail()
    {
        pullRequestService.createInlineReviewComment(
                "pat",
                "owner/repo",
                7,
                "please fix",
                "src/Main.java",
                12,
                "RIGHT",
                "abc123",
                null,
                null);

        verify(gitHub).createInlineReviewComment(
                "pat",
                PullRequestRef.of("owner", "repo", 7),
                "please fix",
                "src/Main.java",
                12,
                "RIGHT",
                "abc123",
                null,
                null);
        verify(detailInvalidator).invalidate("owner/repo", 7);
    }

    @Test
    void testUpdatePullRequestBodyInvalidatesDetail()
    {
        pullRequestService.updatePullRequestBody("pat", "owner/repo", 7, "new body");

        verify(gitHub).updatePullRequest(eq("pat"), eq(PullRequestRef.of("owner", "repo", 7)), any(UpdatePullRequestCommand.class));
        verify(detailInvalidator).invalidate("owner/repo", 7);
    }

    // ── approvePullRequest / mergePullRequest / markHandled / reopen ───────────

    @Test
    void testApprovePullRequestMarksReviewedApproved()
    {
        pullRequestService.approvePullRequest("pat", "owner/repo", 7, 99L);

        verify(gitHub).createReview(eq("pat"), any(PullRequestRef.class), any());
        verify(viewStateStore).markReviewed(99L, HandledAction.APPROVED);
        verify(detailInvalidator).invalidate("owner/repo", 7);
    }

    @Test
    void testMergePullRequestUsesRebaseAndMarksMerged()
    {
        // The service currently defaults to rebase. If this ever changes, this
        // test is the early-warning: it locks the strategy in so regressions
        // don't silently switch back to a merge-commit.
        MergeResultArgMatcher matcher = new MergeResultArgMatcher();
        when(gitHub.mergePullRequest(eq("pat"), any(PullRequestRef.class), any(MergePullRequestCommand.class)))
                .thenAnswer(invocation -> {
                    MergePullRequestCommand cmd = invocation.getArgument(2);
                    matcher.capture(cmd);
                    return null;
                });

        pullRequestService.mergePullRequest("pat", "owner/repo", 7, 99L, null);

        assertThat(matcher.captured).isNotNull();
        assertThat(matcher.captured.mergeMethod()).isEqualTo("rebase");
        verify(viewStateStore).markReviewed(99L, HandledAction.MERGED);
        verify(detailInvalidator).invalidate("owner/repo", 7);
    }

    @Test
    void testMergePullRequestPropagatesSquashStrategy()
    {
        MergeResultArgMatcher matcher = new MergeResultArgMatcher();
        when(gitHub.mergePullRequest(eq("pat"), any(PullRequestRef.class), any(MergePullRequestCommand.class)))
                .thenAnswer(invocation -> {
                    matcher.capture(invocation.getArgument(2));
                    return null;
                });

        pullRequestService.mergePullRequest("pat", "owner/repo", 7, 99L, "squash");

        assertThat(matcher.captured.mergeMethod()).isEqualTo("squash");
    }

    @Test
    void testMergePullRequestPropagatesMergeCommitStrategy()
    {
        MergeResultArgMatcher matcher = new MergeResultArgMatcher();
        when(gitHub.mergePullRequest(eq("pat"), any(PullRequestRef.class), any(MergePullRequestCommand.class)))
                .thenAnswer(invocation -> {
                    matcher.capture(invocation.getArgument(2));
                    return null;
                });

        pullRequestService.mergePullRequest("pat", "owner/repo", 7, 99L, "merge");

        assertThat(matcher.captured.mergeMethod()).isEqualTo("merge");
    }

    @Test
    void testMergePullRequestUnknownStrategyFallsBackToRebase()
    {
        MergeResultArgMatcher matcher = new MergeResultArgMatcher();
        when(gitHub.mergePullRequest(eq("pat"), any(PullRequestRef.class), any(MergePullRequestCommand.class)))
                .thenAnswer(invocation -> {
                    matcher.capture(invocation.getArgument(2));
                    return null;
                });

        pullRequestService.mergePullRequest("pat", "owner/repo", 7, 99L, "garbage");

        assertThat(matcher.captured.mergeMethod()).isEqualTo("rebase");
    }

    @Test
    void testMarkHandledUpdatesViewStateOnlyNoGitHubCall()
    {
        pullRequestService.markHandled(99L, HandledAction.MANUAL);

        verify(viewStateStore).markReviewed(99L, HandledAction.MANUAL);
        verifyNoInteractions(gitHub);
    }

    @Test
    void testReopenDelegatesToViewState()
    {
        pullRequestService.reopen(99L);

        verify(viewStateStore).reopen(99L);
        verifyNoInteractions(gitHub);
    }

    /** Simple capturing helper for the rebase-default test. */
    private static final class MergeResultArgMatcher
    {
        MergePullRequestCommand captured;

        void capture(MergePullRequestCommand cmd)
        {
            this.captured = cmd;
        }
    }

    // ── markViewed ─────────────────────────────────────────────────────────────

    @Test
    void testMarkViewedDelegatesToViewStateStore()
    {
        pullRequestService.markViewed(42L);
        verify(viewStateStore).markViewed(42L);
    }

    // ── getPullRequestDiffFiles / getPullRequestCommits ────────────────────────

    @Test
    void testGetPullRequestDiffFilesDelegatesToClient()
    {
        when(gitHub.fetchPrDiffFiles(eq("pat"), any(PullRequestRef.class)))
                .thenReturn(ImmutableList.of());

        List<?> result = pullRequestService.getPullRequestDiffFiles("pat", "owner/repo", 7);

        assertThat(result).isEmpty();
        verify(gitHub).fetchPrDiffFiles(eq("pat"), any(PullRequestRef.class));
    }

    @Test
    void testGetPullRequestCommitsDelegatesToClient()
    {
        when(gitHub.fetchPrCommits(eq("pat"), any(PullRequestRef.class)))
                .thenReturn(ImmutableList.of());

        List<?> result = pullRequestService.getPullRequestCommits("pat", "owner/repo", 7);

        assertThat(result).isEmpty();
        verify(gitHub).fetchPrCommits(eq("pat"), any(PullRequestRef.class));
    }

    @Test
    void testGetCommitDiffFilesDelegatesThroughResponseCache()
    {
        List<DiffFile> files = ImmutableList.of(new DiffFile("README.md", "modified", 1, 0, "@@"));
        when(responseCache.getCommitDiffFiles(eq("pat"), eq(PullRequestRef.of("owner", "repo", 7)), eq("sha"), any()))
                .thenReturn(files);

        List<DiffFile> result = pullRequestService.getCommitDiffFiles("pat", "owner/repo", 7, "sha");

        assertThat(result).isSameAs(files);
        verify(responseCache).getCommitDiffFiles(eq("pat"), eq(PullRequestRef.of("owner", "repo", 7)), eq("sha"), any());
        verify(gitHub, never()).fetchCommitDiffFiles(anyString(), any(), anyString());
    }

    @Test
    void testGetFileBlobLinesDelegatesThroughResponseCache()
    {
        List<String> lines = ImmutableList.of("hello");
        when(responseCache.getFileBlobLines(eq("pat"), eq(RepoRef.of("owner", "repo")), eq("README.md"), eq("sha"), any()))
                .thenReturn(lines);

        List<String> result = pullRequestService.getFileBlobLines("pat", "owner/repo", "README.md", "sha");

        assertThat(result).isSameAs(lines);
        verify(responseCache).getFileBlobLines(eq("pat"), eq(RepoRef.of("owner", "repo")), eq("README.md"), eq("sha"), any());
        verify(gitHub, never()).fetchFileBlobLines(anyString(), any(), anyString(), anyString());
    }

    @Test
    void testGetSuggestedReviewersDelegatesThroughResponseCache()
    {
        List<SuggestedReviewer> reviewers = ImmutableList.of(new SuggestedReviewer("alice", null, "Alice", false, true));
        when(responseCache.getSuggestedReviewers(eq("pat"), eq(PullRequestRef.of("owner", "repo", 7)), any()))
                .thenReturn(reviewers);

        List<SuggestedReviewer> result = pullRequestService.getSuggestedReviewers("pat", "owner/repo", 7);

        assertThat(result).isSameAs(reviewers);
        verify(responseCache).getSuggestedReviewers(eq("pat"), eq(PullRequestRef.of("owner", "repo", 7)), any());
        verify(gitHub, never()).fetchSuggestedReviewers(anyString(), any());
    }

    @Test
    void testGetPullRequestDiffFilesRejectsInvalidRepo()
    {
        assertThatThrownBy(() -> pullRequestService.getPullRequestDiffFiles("pat", "no-slash", 7))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void testExtractClosingReferencesPicksUpAllKeywordVariants()
    {
        String body = "Closes #1 and fixes #2.\nResolved #3 yesterday — also closed #4.\n"
                + "Fix: #5 plus FIXES #6.";
        assertThat(PullRequestService.extractClosingReferences(body, "trinodb", "trino"))
                .containsExactlyInAnyOrder(1, 2, 3, 4, 6);
    }

    @Test
    void testExtractClosingReferencesIgnoresBareHashRefs()
    {
        String body = "Reverts #1\nDiscussed in #99\nSee also #100";
        assertThat(PullRequestService.extractClosingReferences(body, "trinodb", "trino")).isEmpty();
    }

    @Test
    void testExtractClosingReferencesHandlesNullAndBlank()
    {
        assertThat(PullRequestService.extractClosingReferences(null, "trinodb", "trino")).isEmpty();
        assertThat(PullRequestService.extractClosingReferences("", "trinodb", "trino")).isEmpty();
        assertThat(PullRequestService.extractClosingReferences("   ", "trinodb", "trino")).isEmpty();
    }

    @Test
    void testExtractClosingReferencesAcceptsSameRepoUrlForm()
    {
        String body = "Fixes https://github.com/trinodb/trino/issues/1234\n"
                + "Also closes http://github.com/trinodb/trino/issues/5678.";
        assertThat(PullRequestService.extractClosingReferences(body, "trinodb", "trino"))
                .containsExactlyInAnyOrder(1234, 5678);
    }

    @Test
    void testExtractClosingReferencesSkipsCrossRepoUrlForm()
    {
        // Cross-repo: URL points at owner/repo that isn't the PR's. Skip
        // it — fetching that number against the PR's repo would silently
        // return the wrong issue or 404.
        String body = "Fixes https://github.com/other/repo/issues/9";
        assertThat(PullRequestService.extractClosingReferences(body, "trinodb", "trino")).isEmpty();
    }

    @Test
    void testExtractClosingReferencesUrlAndHashFormsCoexist()
    {
        String body = "Closes #1, fixes https://github.com/trinodb/trino/issues/2, "
                + "resolves #3.";
        assertThat(PullRequestService.extractClosingReferences(body, "trinodb", "trino"))
                .containsExactlyInAnyOrder(1, 2, 3);
    }

    // ── Conversation mutation freshness ────────────────────────────────────────
    // Conversation mutations intentionally stay optimistic: they write to
    // GitHub, but do not invalidate the PR-detail caches. This keeps a
    // stale SQLite snapshot from overwriting local optimistic UI.

    @Test
    void testReplyToReviewThreadAppendsReplyToCachedDetail()
    {
        PrReviewThreadMessage reply = new PrReviewThreadMessage(
                999L,
                4357983764L,
                null,
                "bob",
                "thanks",
                "src/Main.java",
                12,
                "RIGHT",
                "@@",
                "abc123",
                Instant.parse("2026-05-08T01:00:00Z"),
                Reactions.EMPTY,
                false,
                null,
                null,
                null,
                null,
                "MEMBER",
                null,
                null);
        when(gitHub.replyToReviewComment(
                "pat",
                PullRequestRef.of("trinodb", "trino", 7),
                4357983764L,
                "thanks")).thenReturn(reply);
        when(store.findIdByRepoAndNumber("trinodb/trino", 7)).thenReturn(Optional.of(123L));
        when(detailStore.find(123L)).thenReturn(Optional.of(new StoredPrDetail(
                null,
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of())));

        pullRequestService.replyToReviewThread("pat", "trinodb/trino", 7, 4357983764L, "thanks");

        verify(detailInvalidator, never()).invalidate(anyString(), anyInt());

        ArgumentCaptor<StoredPrDetail> captor = ArgumentCaptor.forClass(StoredPrDetail.class);
        verify(detailStore).save(eq(123L), captor.capture());
        assertThat(captor.getValue().reviewComments())
                .singleElement()
                .satisfies(m -> {
                    assertThat(m.githubId()).isEqualTo(999L);
                    assertThat(m.body()).isEqualTo("thanks");
                    assertThat(m.inReplyTo()).isEqualTo(4357983764L);
                });
    }

    @Test
    void testReplyToReviewThreadSkipsCachePatchWhenPrUnknown()
    {
        when(gitHub.replyToReviewComment(any(), any(), anyLong(), anyString()))
                .thenReturn(new PrReviewThreadMessage(
                        999L, 4357983764L, null, "bob", "thanks",
                        "src/Main.java", 12, "RIGHT", "@@", "abc123",
                        Instant.parse("2026-05-08T01:00:00Z"), Reactions.EMPTY,
                        false, null, null, null, null, "MEMBER", null, null));
        when(store.findIdByRepoAndNumber("trinodb/trino", 7)).thenReturn(Optional.empty());

        pullRequestService.replyToReviewThread("pat", "trinodb/trino", 7, 4357983764L, "thanks");

        verify(detailStore, never()).save(anyLong(), any());
    }

    @Test
    void testEditIssueCommentDoesNotInvalidateDetail()
    {
        pullRequestService.editIssueComment("pat", "trinodb/trino", 4357983764L, "updated");

        verify(gitHub).editIssueComment("pat", "trinodb", "trino", 4357983764L, "updated");
        verify(detailInvalidator, never()).invalidate(anyString(), anyInt());
    }

    @Test
    void testEditReviewCommentDoesNotInvalidateDetail()
    {
        pullRequestService.editReviewComment("pat", "trinodb/trino", 4357983764L, "updated");

        verify(gitHub).editReviewComment("pat", "trinodb", "trino", 4357983764L, "updated");
        verify(detailInvalidator, never()).invalidate(anyString(), anyInt());
    }

    @Test
    void testSetReviewThreadResolvedPatchesCachedDetail()
    {
        PrReviewThreadMessage root = new PrReviewThreadMessage(
                4357983764L,
                null,
                null,
                "alice",
                "please fix",
                "src/Main.java",
                12,
                "RIGHT",
                "@@",
                "abc123",
                Instant.parse("2026-05-08T00:00:00Z"),
                Reactions.EMPTY,
                false,
                null,
                null,
                null,
                null,
                "MEMBER",
                "thread-node-id",
                false);
        when(detailStore.find(123L)).thenReturn(Optional.of(new StoredPrDetail(
                null,
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(root),
                ImmutableList.of())));

        pullRequestService.setReviewThreadResolved("pat", 123L, 4357983764L, true);

        verify(gitHub).resolveReviewThread("pat", "thread-node-id");
        verify(detailInvalidator, never()).invalidate(anyString(), anyInt());

        ArgumentCaptor<StoredPrDetail> captor = ArgumentCaptor.forClass(StoredPrDetail.class);
        verify(detailStore).save(eq(123L), captor.capture());
        PrReviewThreadMessage patched = captor.getValue().reviewComments().get(0);
        assertThat(patched.resolved()).isTrue();
        assertThat(patched.body()).isEqualTo("please fix");
        assertThat(patched.graphqlNodeId()).isEqualTo("thread-node-id");
    }

    @Test
    void testSetReviewThreadUnresolvedFlipsFlagBackToFalse()
    {
        PrReviewThreadMessage root = new PrReviewThreadMessage(
                4357983764L,
                null,
                null,
                "alice",
                "please fix",
                "src/Main.java",
                12,
                "RIGHT",
                "@@",
                "abc123",
                Instant.parse("2026-05-08T00:00:00Z"),
                Reactions.EMPTY,
                false,
                null,
                null,
                null,
                null,
                "MEMBER",
                "thread-node-id",
                true);
        when(detailStore.find(123L)).thenReturn(Optional.of(new StoredPrDetail(
                null,
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(root),
                ImmutableList.of())));

        pullRequestService.setReviewThreadResolved("pat", 123L, 4357983764L, false);

        verify(gitHub).unresolveReviewThread("pat", "thread-node-id");

        ArgumentCaptor<StoredPrDetail> captor = ArgumentCaptor.forClass(StoredPrDetail.class);
        verify(detailStore).save(eq(123L), captor.capture());
        assertThat(captor.getValue().reviewComments().get(0).resolved()).isFalse();
    }

    @Test
    void testSetReviewThreadResolvedDoesNotPatchWhenGitHubFails()
    {
        PrReviewThreadMessage root = new PrReviewThreadMessage(
                4357983764L,
                null,
                null,
                "alice",
                "please fix",
                "src/Main.java",
                12,
                "RIGHT",
                "@@",
                "abc123",
                Instant.parse("2026-05-08T00:00:00Z"),
                Reactions.EMPTY,
                false,
                null,
                null,
                null,
                null,
                "MEMBER",
                "thread-node-id",
                false);
        when(detailStore.find(123L)).thenReturn(Optional.of(new StoredPrDetail(
                null,
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(),
                ImmutableList.of(root),
                ImmutableList.of())));
        doThrow(new RuntimeException("GitHub down"))
                .when(gitHub).resolveReviewThread("pat", "thread-node-id");

        assertThatThrownBy(() ->
                pullRequestService.setReviewThreadResolved("pat", 123L, 4357983764L, true))
                .isInstanceOf(RuntimeException.class);

        verify(detailStore, never()).save(anyLong(), any());
    }

    // ── Reaction endpoints ─────────────────────────────────────────────────────
    // Regression guard. addReviewCommentReaction / addIssueCommentReaction
    // used to call parseRef(repo, 0), but PullRequestRef requires
    // number > 0 — so every reaction click returned 400 "number must be
    // positive, got: 0". Now they use parseRepoRef. These tests pin
    // both the happy path and the content-allowlist + repo-format
    // validation so the bug can't slip back.

    @Test
    void testAddReviewCommentReactionWithValidRepoForwardsToGitHub()
    {
        pullRequestService.addReviewCommentReaction("pat", "trinodb/trino", 4357983764L, "+1");

        verify(gitHub).addReviewCommentReaction("pat", "trinodb", "trino", 4357983764L, "+1");
        verify(responseCache, never()).invalidatePullRequest(any());
        verify(detailInvalidator, never()).invalidate(anyString(), anyInt());
    }

    @Test
    void testAddIssueCommentReactionWithValidRepoForwardsToGitHub()
    {
        pullRequestService.addIssueCommentReaction("pat", "trinodb/trino", 4357983764L, "heart");

        verify(gitHub).addIssueCommentReaction("pat", "trinodb", "trino", 4357983764L, "heart");
        verify(responseCache, never()).invalidatePullRequest(any());
        verify(detailInvalidator, never()).invalidate(anyString(), anyInt());
    }

    @Test
    void testAddReviewCommentReactionRejectsInvalidContent()
    {
        assertThatThrownBy(() ->
                pullRequestService.addReviewCommentReaction("pat", "trinodb/trino", 1L, "fire"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                        .isEqualTo(BAD_REQUEST.value()));
    }

    @Test
    void testAddReviewCommentReactionRejectsBadRepoShape()
    {
        assertThatThrownBy(() ->
                pullRequestService.addReviewCommentReaction("pat", "no-slash", 1L, "+1"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                        .isEqualTo(BAD_REQUEST.value()));
    }

    @Test
    void testAddIssueCommentReactionRejectsInvalidContent()
    {
        assertThatThrownBy(() ->
                pullRequestService.addIssueCommentReaction("pat", "trinodb/trino", 1L, "thumbs"))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode().value())
                        .isEqualTo(BAD_REQUEST.value()));
    }
}
