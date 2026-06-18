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

import com.bytequay.app.domain.PrCheckRunState;
import com.bytequay.app.domain.PrRawDetail;
import com.bytequay.app.domain.PrReviewState;
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.PullRequestDetail.CiStatus;
import com.bytequay.app.domain.StoredPrDetail;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestPullRequestDetailMapper
{
    @Test
    void aggregatesCiStatusOverTheLatestRunPerCheck()
    {
        assertThat(PullRequestDetailMapper.aggregateCiStatus(List.of()))
                .isEqualTo(CiStatus.NONE);
        assertThat(PullRequestDetailMapper.aggregateCiStatus(List.of(
                check("build", "completed", "success"))))
                .isEqualTo(CiStatus.PASSING);
        // Any distinct check failing fails the whole PR.
        assertThat(PullRequestDetailMapper.aggregateCiStatus(List.of(
                check("build", "completed", "success"),
                check("test", "completed", "failure"))))
                .isEqualTo(CiStatus.FAILING);
        // A still-running check (none failed) reads as pending.
        assertThat(PullRequestDetailMapper.aggregateCiStatus(List.of(
                check("build", "completed", "success"),
                check("test", "in_progress", null))))
                .isEqualTo(CiStatus.PENDING);
    }

    @Test
    void aReRunSuccessMasksTheEarlierFailureForTheSameCheck()
    {
        // Newest-first: the re-run (success) precedes the original failure,
        // so the deduped latest is the success → not FAILING.
        assertThat(PullRequestDetailMapper.aggregateCiStatus(List.of(
                check("build", "completed", "success"),
                check("build", "completed", "failure"))))
                .isEqualTo(CiStatus.PASSING);
    }

    @Test
    void countsApprovalsAndChangesRequestedFromReviews()
    {
        List<PrReviewState> reviews = List.of(
                new PrReviewState("alice", "APPROVED"),
                new PrReviewState("bob", "CHANGES_REQUESTED"),
                new PrReviewState("carol", "COMMENTED"));
        assertThat(PullRequestDetailMapper.countApprovals(reviews)).isEqualTo(1);
        assertThat(PullRequestDetailMapper.countChangesRequested(reviews)).isEqualTo(1);
    }

    @Test
    void mapsTerminalStateCountsCiAndViewerWriteEndToEnd()
    {
        StoredPrDetail stored = new StoredPrDetail(
                raw("closed", /* merged */ true),
                List.of(new PrReviewState("alice", "APPROVED"),
                        new PrReviewState("bob", "CHANGES_REQUESTED")),
                List.of(), List.of(),
                List.of(check("build", "completed", "success")),
                List.of(), List.of(), /* mergeQueueState */ null, /* mergeQueueEnabled */ false);

        PullRequestDetail detail = PullRequestDetailMapper.toPullRequestDetail(
                "owner/repo", 7, stored, /* viewerCanWrite */ true);

        assertThat(detail.state()).isEqualTo("closed");
        assertThat(detail.merged()).isTrue();
        assertThat(detail.approvalCount()).isEqualTo(1);
        assertThat(detail.changesRequestedCount()).isEqualTo(1);
        assertThat(detail.ciStatus()).isEqualTo(CiStatus.PASSING);
        assertThat(detail.viewerCanWrite()).isTrue();
        assertThat(detail.baseRef()).isEqualTo("main");
    }

    private static PrCheckRunState check(String name, String status, String conclusion)
    {
        return new PrCheckRunState(1L, name, status, conclusion, null, null, null);
    }

    private static PrRawDetail raw(String state, boolean merged)
    {
        return new PrRawDetail(
                "body", List.of(), /* draft */ false, /* mergeable */ true, "clean",
                10, 2, 3, /* requestedReviewerCount */ 0, List.of(),
                "headsha", "dev/x", "owner/repo", "main", "owner/repo",
                state, merged);
    }
}
