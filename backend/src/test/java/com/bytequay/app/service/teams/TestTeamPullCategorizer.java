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
package com.bytequay.app.service.teams;

import com.bytequay.app.domain.HandledAction;
import com.bytequay.app.domain.MyPrColumn;
import com.bytequay.app.domain.PullRequest;
import com.bytequay.app.domain.PullRequestDetail.CiStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Pins the server-side kanban bucketing — the mirror of the frontend's
 * {@code prBuckets.ts categorizeMyPr}. The two must agree or the same PR
 * lands in different columns depending on where the page is built.
 */
class TestTeamPullCategorizer
{
    private static final Instant NOW = Instant.parse("2026-06-17T12:00:00Z");

    @Test
    void onlyAuthoredPrsAreOnTheBoard()
    {
        PullRequest pr = pr();
        when(pr.origin()).thenReturn(PullRequest.Origin.REVIEW_REQUESTED);
        assertThat(TeamPullCategorizer.categorize(pr, NOW)).isNull();
    }

    @Test
    void aUserHandledActionGoesToHandled()
    {
        for (HandledAction action : new HandledAction[] {
                HandledAction.MERGED, HandledAction.DISMISSED, HandledAction.MANUAL}) {
            PullRequest pr = pr();
            when(pr.handledAction()).thenReturn(action);
            assertThat(TeamPullCategorizer.categorize(pr, NOW))
                    .as("handled=%s", action)
                    .isEqualTo(MyPrColumn.HANDLED);
        }
    }

    @Test
    void recentlyMergedWithinTheWindowIsRecentlyMerged()
    {
        PullRequest pr = pr();
        when(pr.mergedAt()).thenReturn(NOW.minus(2, ChronoUnit.DAYS));
        assertThat(TeamPullCategorizer.categorize(pr, NOW)).isEqualTo(MyPrColumn.RECENTLY_MERGED);
    }

    @Test
    void recentlyClosedUnmergedIsRecentlyMerged()
    {
        PullRequest pr = pr();
        when(pr.state()).thenReturn("closed");
        when(pr.closedAt()).thenReturn(NOW.minus(1, ChronoUnit.DAYS));
        assertThat(TeamPullCategorizer.categorize(pr, NOW)).isEqualTo(MyPrColumn.RECENTLY_MERGED);
    }

    @Test
    void anOldClosedPrDropsOffTheBoard()
    {
        PullRequest pr = pr();
        when(pr.state()).thenReturn("closed");
        when(pr.closedAt()).thenReturn(NOW.minus(30, ChronoUnit.DAYS));
        assertThat(TeamPullCategorizer.categorize(pr, NOW)).isNull();
    }

    @Test
    void aDraftIsDrafting()
    {
        PullRequest pr = pr();
        when(pr.draft()).thenReturn(true);
        assertThat(TeamPullCategorizer.categorize(pr, NOW)).isEqualTo(MyPrColumn.DRAFTING);
    }

    @Test
    void approvedPassingAndMergeableIsReadyToMerge()
    {
        PullRequest pr = pr();
        when(pr.reviewerVerdicts()).thenReturn(Map.of("alice", "APPROVED"));
        when(pr.ciStatus()).thenReturn(CiStatus.PASSING);
        when(pr.mergeable()).thenReturn(true);
        assertThat(TeamPullCategorizer.categorize(pr, NOW)).isEqualTo(MyPrColumn.READY_TO_MERGE);
    }

    @Test
    void changesRequestedWinsOverAnApprovalAndNeedsChanges()
    {
        PullRequest pr = pr();
        when(pr.reviewerVerdicts()).thenReturn(Map.of("alice", "APPROVED", "bob", "CHANGES_REQUESTED"));
        when(pr.ciStatus()).thenReturn(CiStatus.PASSING);
        when(pr.mergeable()).thenReturn(true);
        assertThat(TeamPullCategorizer.categorize(pr, NOW)).isEqualTo(MyPrColumn.NEEDS_CHANGES);
    }

    @Test
    void failingCiNeedsChanges()
    {
        PullRequest pr = pr();
        when(pr.ciStatus()).thenReturn(CiStatus.FAILING);
        assertThat(TeamPullCategorizer.categorize(pr, NOW)).isEqualTo(MyPrColumn.NEEDS_CHANGES);
    }

    @Test
    void otherwiseWaitingOnReview()
    {
        PullRequest pr = pr();
        when(pr.ciStatus()).thenReturn(CiStatus.PENDING);
        assertThat(TeamPullCategorizer.categorize(pr, NOW)).isEqualTo(MyPrColumn.WAITING_ON_REVIEW);
    }

    /** A mocked PR with the neutral defaults categorize() reads: an
     *  authored, open, non-draft PR with no verdicts/CI/merge signal. */
    private static PullRequest pr()
    {
        PullRequest pr = mock(PullRequest.class);
        when(pr.origin()).thenReturn(PullRequest.Origin.AUTHORED);
        when(pr.state()).thenReturn("open");
        when(pr.reviewerVerdicts()).thenReturn(Map.of());
        return pr;
    }
}
