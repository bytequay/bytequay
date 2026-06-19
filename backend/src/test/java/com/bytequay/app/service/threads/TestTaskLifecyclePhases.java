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
package com.bytequay.app.service.threads;

import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.PullRequestDetail.CiStatus;
import com.bytequay.app.domain.PullRequestDetail.ReviewMessage;
import com.bytequay.app.domain.PullRequestDetail.ReviewThread;
import com.bytequay.app.domain.TaskPhase;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestTaskLifecyclePhases
{
    private static final Instant T = Instant.parse("2026-06-15T12:00:00Z");

    @Test
    void mergedPrCompletesTheTask()
    {
        assertThat(TaskLifecyclePhases.observedPhaseFor(
                pr(CiStatus.PASSING, false, "closed", /* merged */ T, null)))
                .contains(TaskPhase.COMPLETED);
    }

    @Test
    void closedUnmergedPrCompletesTheTask()
    {
        assertThat(TaskLifecyclePhases.observedPhaseFor(
                pr(CiStatus.PASSING, false, "closed", null, null)))
                .contains(TaskPhase.COMPLETED);
    }

    @Test
    void failingCiGoesToCiFixing()
    {
        assertThat(TaskLifecyclePhases.observedPhaseFor(
                pr(CiStatus.FAILING, false, "open", null, null)))
                .contains(TaskPhase.CI_FIXING);
    }

    @Test
    void pendingOrUnknownCiWaitsOnCi()
    {
        assertThat(TaskLifecyclePhases.observedPhaseFor(
                pr(CiStatus.PENDING, false, "open", null, null)))
                .contains(TaskPhase.PUSHED_AWAITING_CI);
        assertThat(TaskLifecyclePhases.observedPhaseFor(
                pr(null, false, "open", null, null)))
                .contains(TaskPhase.PUSHED_AWAITING_CI);
    }

    @Test
    void greenDraftAwaitsReady()
    {
        assertThat(TaskLifecyclePhases.observedPhaseFor(
                pr(CiStatus.PASSING, true, "open", null, null)))
                .contains(TaskPhase.AWAITING_READY);
    }

    @Test
    void greenReadyWithChangesRequestedAddressesComments()
    {
        assertThat(TaskLifecyclePhases.observedPhaseFor(
                pr(CiStatus.PASSING, false, "open", null, Map.of("bob", "CHANGES_REQUESTED"))))
                .contains(TaskPhase.ADDRESSING_COMMENTS);
    }

    @Test
    void greenReadyWithoutChangesAwaitsRemoteReview()
    {
        assertThat(TaskLifecyclePhases.observedPhaseFor(
                pr(CiStatus.PASSING, false, "open", null, Map.of("bob", "APPROVED"))))
                .contains(TaskPhase.AWAITING_REMOTE_REVIEW);
        // No CI gate (NONE) behaves like green.
        assertThat(TaskLifecyclePhases.observedPhaseFor(
                pr(CiStatus.NONE, false, "open", null, null)))
                .contains(TaskPhase.AWAITING_REMOTE_REVIEW);
    }

    @Test
    void fromDetail_failingCi_goesToCiFixing()
    {
        assertThat(TaskLifecyclePhases.observedPhaseFromDetail(detail(CiStatus.FAILING, false)))
                .contains(TaskPhase.CI_FIXING);
    }

    @Test
    void fromDetail_pendingOrUnknownCi_waitsOnCi()
    {
        assertThat(TaskLifecyclePhases.observedPhaseFromDetail(detail(CiStatus.PENDING, false)))
                .contains(TaskPhase.PUSHED_AWAITING_CI);
        assertThat(TaskLifecyclePhases.observedPhaseFromDetail(detail(null, false)))
                .contains(TaskPhase.PUSHED_AWAITING_CI);
    }

    @Test
    void fromDetail_greenDraft_awaitsReady()
    {
        assertThat(TaskLifecyclePhases.observedPhaseFromDetail(detail(CiStatus.PASSING, true)))
                .contains(TaskPhase.AWAITING_READY);
    }

    @Test
    void fromDetail_greenReady_awaitsRemoteReview()
    {
        assertThat(TaskLifecyclePhases.observedPhaseFromDetail(detail(CiStatus.PASSING, false)))
                .contains(TaskPhase.AWAITING_REMOTE_REVIEW);
        assertThat(TaskLifecyclePhases.observedPhaseFromDetail(detail(CiStatus.NONE, false)))
                .contains(TaskPhase.AWAITING_REMOTE_REVIEW);
    }

    @Test
    void fromDetail_mergedOrClosed_completesTheTask()
    {
        // Merged wins regardless of CI / draft — the PR landed.
        PullRequestDetail merged = mock(PullRequestDetail.class);
        when(merged.merged()).thenReturn(true);
        assertThat(TaskLifecyclePhases.observedPhaseFromDetail(merged)).contains(TaskPhase.COMPLETED);

        // Closed without merging is also terminal (cancelled).
        PullRequestDetail closed = mock(PullRequestDetail.class);
        when(closed.state()).thenReturn("closed");
        assertThat(TaskLifecyclePhases.observedPhaseFromDetail(closed)).contains(TaskPhase.COMPLETED);
    }

    @Test
    void unresolvedReviewCommentsNewerThanTheMarkerAreUnaddressed()
    {
        Instant older = Instant.parse("2026-06-01T09:00:00Z");
        Instant newer = Instant.parse("2026-06-10T09:00:00Z");
        PullRequestDetail d = detailWithThreads(List.of(
                unresolvedThread(older), unresolvedThread(newer)));

        // Marker between the two: only the newer comment counts, and the
        // returned instant is that newest unaddressed comment.
        assertThat(TaskLifecyclePhases.newestUnaddressedReviewComment(d, older))
                .contains(newer);
        // No marker yet → everything is new; newest wins.
        assertThat(TaskLifecyclePhases.newestUnaddressedReviewComment(d, null))
                .contains(newer);
        // Marker at/after the newest → nothing new to address.
        assertThat(TaskLifecyclePhases.newestUnaddressedReviewComment(d, newer))
                .isEmpty();
    }

    @Test
    void resolvedReviewThreadsAreNeverUnaddressed()
    {
        Instant at = Instant.parse("2026-06-10T09:00:00Z");
        ReviewThread resolved = new ReviewThread(1L, "Foo.java", 10, "RIGHT", null,
                List.of(new ReviewMessage(1L, "reviewer", "fixed?", at, null, null, "COLLABORATOR")),
                /* resolved */ true, false, null, null, null, null);
        PullRequestDetail d = detailWithThreads(List.of(resolved));

        assertThat(TaskLifecyclePhases.newestUnaddressedReviewComment(d, null)).isEmpty();
    }

    @Test
    void noReviewThreadsMeansNothingToAddress()
    {
        assertThat(TaskLifecyclePhases.newestUnaddressedReviewComment(
                detailWithThreads(List.of()), null)).isEmpty();
        assertThat(TaskLifecyclePhases.newestUnaddressedReviewComment(null, null)).isEmpty();
    }

    private static PullRequestDetail detailWithThreads(List<ReviewThread> threads)
    {
        PullRequestDetail d = mock(PullRequestDetail.class);
        when(d.reviewThreads()).thenReturn(threads);
        return d;
    }

    private static ReviewThread unresolvedThread(Instant at)
    {
        return new ReviewThread(1L, "Foo.java", 10, "RIGHT", null,
                List.of(new ReviewMessage(1L, "reviewer", "please fix", at, null, null, "COLLABORATOR")),
                /* resolved */ false, /* outdated */ false, null, null, null, null);
    }

    private static PullRequestDetail detail(CiStatus ci, boolean draft)
    {
        PullRequestDetail d = mock(PullRequestDetail.class);
        when(d.ciStatus()).thenReturn(ci);
        when(d.draft()).thenReturn(draft);
        return d;
    }

    private static PullRequest pr(CiStatus ci, boolean draft, String state, Instant mergedAt,
            Map<String, String> verdicts)
    {
        return new PullRequest(
                1L, "owner/repo", 42, "Title", "alice",
                "https://github.com/owner/repo/pull/42", T, T,
                PullRequest.Origin.AUTHORED, List.of(), null, draft, null, null, null, List.of(),
                ci, 0, 0, 0, null,
                state, null, mergedAt, null, null, null, verdicts,
                null, null, "dev/task-branch");
    }
}
