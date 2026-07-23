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
package com.bytequay.app.service.stage;

import com.bytequay.app.domain.ReviewRound;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The brain-review DevPhase label the task page renders. A recorded APPROVED
 * verdict must only read "brain approved" once the reviewer's own findings are
 * cleared; with open roots it reads "brain unresolved · N" so the frontend's
 * budget-exhausted human-override ship path engages.
 */
class TestBrainReviewPhase
{
    @Test
    void approvedWithNoOpenFindingsReadsApproved()
    {
        assertThat(StageServiceImpl.buildBrainReviewPhase(round(ReviewRound.VERDICT_APPROVED, 0)).meta())
                .isEqualTo("brain approved");
    }

    @Test
    void approvedButWithOpenFindingsReadsUnresolved()
    {
        assertThat(StageServiceImpl.buildBrainReviewPhase(round(ReviewRound.VERDICT_APPROVED, 3)).meta())
                .isEqualTo("brain unresolved · 3");
    }

    @Test
    void changesRequestedReadsUnresolved()
    {
        assertThat(StageServiceImpl.buildBrainReviewPhase(round(ReviewRound.VERDICT_CHANGES_REQUESTED, 2)).meta())
                .isEqualTo("brain unresolved · 2");
    }

    private static ReviewRound round(String verdict, int open)
    {
        return new ReviewRound(
                "r1", "t1", 1, List.of("@octocat"),
                ReviewRound.STATUS_CLOSED,
                new ReviewRound.ReviewRoundStats(0, 0, 0, open),
                "run-1", Instant.parse("2026-07-23T00:00:00Z"), null, null,
                ReviewRound.ORIGIN_BRAIN, verdict, 1, ReviewRound.DEFAULT_BRAIN_BUDGET);
    }
}
