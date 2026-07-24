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

import com.bytequay.app.domain.Actor;
import com.bytequay.app.domain.ReviewRound;
import com.bytequay.app.domain.StageInstance;
import com.bytequay.app.domain.StageState;
import com.bytequay.app.domain.StageType;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskPhaseEvent;
import com.bytequay.app.domain.TaskStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

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

    @Test
    void pausedReviewDoesNotReadAsCompleted()
    {
        assertThat(StageServiceImpl.buildBrainReviewPhase(round(null, 0)
                        .withStatus(ReviewRound.STATUS_PAUSED)))
                .satisfies(phase -> {
                    assertThat(phase.status()).isEqualTo("future");
                    assertThat(phase.meta()).isEqualTo("review failed");
                });
    }

    @Test
    void validationRemainsDoneAfterALaterBrainFailure()
    {
        Instant now = Instant.parse("2026-07-23T00:00:00Z");
        StageInstance dev = new StageInstance(
                UUID.randomUUID(), "t1", StageType.DEVELOPMENT_STAGE,
                StageState.OPEN, now, null, null);
        List<TaskPhaseEvent> events = List.of(new TaskPhaseEvent(
                1L, "t1", TaskPhase.VALIDATING, TaskPhase.INTERNAL_REVIEW,
                now, "validation_passed", Actor.AGENT));

        assertThat(StageServiceImpl.buildDevPhases(
                TaskPhase.NEEDS_ATTENTION, dev, List.of(), List.of(), events).get(1).status())
                .isEqualTo("done");
    }

    @Test
    void parkedReasonIsExposedAsTheTaskStatusLabel()
    {
        Instant now = Instant.parse("2026-07-23T00:00:00Z");
        Task task = new Task(
                "t1", "thread-1", 1L, TaskStatus.NEEDS_ATTENTION,
                "feature/x", null, "main", null,
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, now, null, "brain_review_turn_failed",
                null, null, null);

        assertThat(StageServiceImpl.statusLabel(task)).isEqualTo("brain review turn failed");
    }

    @Test
    void legacyParkedTaskFallsBackToItsLatestPhaseReason()
    {
        Instant now = Instant.parse("2026-07-23T00:00:00Z");
        Task task = new Task(
                "t1", "thread-1", 1L, TaskStatus.NEEDS_ATTENTION,
                "feature/x", null, "main", null,
                null, null, null, null, null, "DEVELOP", null, null,
                0L, 0L, 0L, null, now, null, null,
                null, null, null);
        List<TaskPhaseEvent> events = List.of(new TaskPhaseEvent(
                1L, "t1", TaskPhase.INTERNAL_REVIEW, TaskPhase.NEEDS_ATTENTION,
                now, "brain_review_verdict_missing", Actor.AGENT));

        assertThat(StageServiceImpl.statusLabel(task, events))
                .isEqualTo("brain review verdict missing");
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
