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

import com.bytequay.app.domain.Actor;
import com.bytequay.app.domain.TaskMilestone;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskPhaseEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class TestTaskFlowLabels
{
    @Test
    void everyPhaseMapsToAMilestone()
    {
        for (TaskPhase phase : TaskPhase.values()) {
            assertThat(TaskMilestone.of(phase)).isNotNull();
        }
    }

    @Test
    void phasesThatShareABucketMapToTheSameMilestone()
    {
        // The loop phases collapse into their stage's bucket.
        assertThat(TaskMilestone.of(TaskPhase.ADDRESSING_COMMENTS)).isEqualTo(TaskMilestone.IMPLEMENT);
        assertThat(TaskMilestone.of(TaskPhase.CI_FIXING)).isEqualTo(TaskMilestone.IMPLEMENT);
        assertThat(TaskMilestone.of(TaskPhase.IMPLEMENTING)).isEqualTo(TaskMilestone.IMPLEMENT);
        assertThat(TaskMilestone.of(TaskPhase.AGENT_RE_REVIEW)).isEqualTo(TaskMilestone.REVIEW);
        assertThat(TaskMilestone.of(TaskPhase.AWAITING_UPDATE_PUSH)).isEqualTo(TaskMilestone.PUSH);
        assertThat(TaskMilestone.of(TaskPhase.AWAITING_READY)).isEqualTo(TaskMilestone.WAIT_ON_PR);
        assertThat(TaskMilestone.of(TaskPhase.AWAITING_REMOTE_REVIEW)).isEqualTo(TaskMilestone.WAIT_ON_PR);
    }

    @Test
    void specialPhasesMapToOffStepperMilestones()
    {
        assertThat(TaskMilestone.of(TaskPhase.QUEUED)).isEqualTo(TaskMilestone.QUEUED);
        assertThat(TaskMilestone.of(TaskPhase.NEEDS_ATTENTION)).isEqualTo(TaskMilestone.PARKED);
        assertThat(TaskMilestone.of(TaskPhase.COMPLETED)).isEqualTo(TaskMilestone.MERGE);
        // The off-stepper specials are not in the six canonical buckets.
        assertThat(TaskMilestone.CANONICAL).doesNotContain(TaskMilestone.QUEUED, TaskMilestone.PARKED);
        assertThat(TaskMilestone.CANONICAL).hasSize(6);
    }

    @Test
    void firstVisitVsRepeatChangesTheFriendlyLabel()
    {
        List<TaskPhaseEvent> log = new ArrayList<>();

        // First IMPLEMENTING → "Implement"; a later one → "Address".
        assertThat(TaskFlowLabels.friendlyLabel(toPhase(TaskPhase.IMPLEMENTING), log)).isEqualTo("Implement");
        log.add(toPhase(TaskPhase.IMPLEMENTING));
        assertThat(TaskFlowLabels.friendlyLabel(toPhase(TaskPhase.IMPLEMENTING), log)).isEqualTo("Address");

        // First INTERNAL_REVIEW → "Review"; a later one → "Re-review".
        List<TaskPhaseEvent> reviewLog = new ArrayList<>();
        assertThat(TaskFlowLabels.friendlyLabel(toPhase(TaskPhase.INTERNAL_REVIEW), reviewLog)).isEqualTo("Review");
        reviewLog.add(toPhase(TaskPhase.INTERNAL_REVIEW));
        assertThat(TaskFlowLabels.friendlyLabel(toPhase(TaskPhase.INTERNAL_REVIEW), reviewLog)).isEqualTo("Re-review");
    }

    @Test
    void pushVsPushUpdateAndOtherFixedLabels()
    {
        List<TaskPhaseEvent> none = List.of();
        assertThat(TaskFlowLabels.friendlyLabel(toPhase(TaskPhase.AWAITING_PUSH), none)).isEqualTo("Push");
        assertThat(TaskFlowLabels.friendlyLabel(toPhase(TaskPhase.AWAITING_UPDATE_PUSH), none)).isEqualTo("Push update");
        assertThat(TaskFlowLabels.friendlyLabel(toPhase(TaskPhase.CI_FIXING), none)).isEqualTo("Fix CI");
        assertThat(TaskFlowLabels.friendlyLabel(toPhase(TaskPhase.AWAITING_READY), none)).isEqualTo("Mark ready");
        assertThat(TaskFlowLabels.friendlyLabel(toPhase(TaskPhase.AWAITING_REMOTE_REVIEW), none)).isEqualTo("Remote review");
        assertThat(TaskFlowLabels.friendlyLabel(toPhase(TaskPhase.COMPLETED), none)).isEqualTo("Merged");
        assertThat(TaskFlowLabels.friendlyLabel(toPhase(TaskPhase.NEEDS_ATTENTION), none)).isEqualTo("Parked");
    }

    private static TaskPhaseEvent toPhase(TaskPhase to)
    {
        return new TaskPhaseEvent(1L, "t1.k1", null, to, Instant.ofEpochMilli(1_700_000_000_000L),
                "reason", Actor.AGENT);
    }
}
