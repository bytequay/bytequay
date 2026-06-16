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

import com.bytequay.app.beans.trace.MilestoneSummary;
import com.bytequay.app.beans.trace.NextPossible;
import com.bytequay.app.beans.trace.TaskTraceResponse;
import com.bytequay.app.domain.Actor;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskPhaseEvent;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.TaskStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TestTaskTraceService
{
    private final TaskStore taskStore = mock(TaskStore.class);
    private final TaskTraceService service = new TaskTraceService(taskStore);

    @Test
    void emptyForAnUnknownTask()
    {
        when(taskStore.findTaskById("nope")).thenReturn(Optional.empty());
        assertThat(service.trace("nope")).isEmpty();
    }

    @Test
    void countsLoopRevisitsAndFlagsTheActiveBucket()
    {
        // Implement → Validate → Review → (loop) Address → Re-review → Push → Wait CI.
        List<TaskPhaseEvent> log = events(
                null, TaskPhase.IMPLEMENTING,
                TaskPhase.IMPLEMENTING, TaskPhase.VALIDATING,
                TaskPhase.VALIDATING, TaskPhase.INTERNAL_REVIEW,
                TaskPhase.INTERNAL_REVIEW, TaskPhase.ADDRESSING_COMMENTS,
                TaskPhase.ADDRESSING_COMMENTS, TaskPhase.AGENT_RE_REVIEW,
                TaskPhase.AGENT_RE_REVIEW, TaskPhase.AWAITING_PUSH,
                TaskPhase.AWAITING_PUSH, TaskPhase.PUSHED_AWAITING_CI);
        stub(TaskPhase.PUSHED_AWAITING_CI, log);

        TaskTraceResponse trace = service.trace("t1.k1").orElseThrow();

        assertThat(trace.currentPhase()).isEqualTo("PUSHED_AWAITING_CI");
        assertThat(trace.currentMilestone()).isEqualTo("WAIT_ON_PR");
        assertThat(trace.events()).hasSize(7);

        // IMPLEMENT entered twice (initial + the Address loop); REVIEW twice
        // (Review + Re-review). Consecutive same-bucket phases don't recount.
        assertThat(visits(trace, "IMPLEMENT")).isEqualTo(2);
        assertThat(visits(trace, "REVIEW")).isEqualTo(2);
        assertThat(visits(trace, "VALIDATE")).isEqualTo(1);
        assertThat(visits(trace, "WAIT_ON_PR")).isEqualTo(1);
        assertThat(visits(trace, "MERGE")).isEqualTo(0);

        assertThat(active(trace)).isEqualTo("WAIT_ON_PR");
        // No bucket is skipped — every upstream bucket was entered.
        assertThat(trace.milestoneSummary()).noneMatch(MilestoneSummary::skipped);
    }

    @Test
    void flagsSkippedBucketsWhenADownstreamOneWasReached()
    {
        // A task that only ever logged Push → Wait CI: Implement / Validate /
        // Review were skipped over (downstream buckets reached, these not).
        List<TaskPhaseEvent> log = events(
                null, TaskPhase.AWAITING_PUSH,
                TaskPhase.AWAITING_PUSH, TaskPhase.PUSHED_AWAITING_CI);
        stub(TaskPhase.PUSHED_AWAITING_CI, log);

        TaskTraceResponse trace = service.trace("t1.k1").orElseThrow();

        assertThat(skipped(trace, "IMPLEMENT")).isTrue();
        assertThat(skipped(trace, "VALIDATE")).isTrue();
        assertThat(skipped(trace, "REVIEW")).isTrue();
        assertThat(skipped(trace, "PUSH")).isFalse();
        assertThat(skipped(trace, "WAIT_ON_PR")).isFalse();
        // MERGE has nothing downstream, so it's future, not skipped.
        assertThat(skipped(trace, "MERGE")).isFalse();
    }

    @Test
    void firstVisitVsRepeatLabelsAndNextPossible()
    {
        List<TaskPhaseEvent> log = events(
                null, TaskPhase.IMPLEMENTING,
                TaskPhase.IMPLEMENTING, TaskPhase.VALIDATING,
                TaskPhase.VALIDATING, TaskPhase.INTERNAL_REVIEW,
                TaskPhase.INTERNAL_REVIEW, TaskPhase.ADDRESSING_COMMENTS,
                TaskPhase.ADDRESSING_COMMENTS, TaskPhase.IMPLEMENTING);
        stub(TaskPhase.IMPLEMENTING, log);

        TaskTraceResponse trace = service.trace("t1.k1").orElseThrow();

        // First Implement reads "Implement"; the looped one reads "Address".
        assertThat(trace.events().get(0).label()).isEqualTo("Implement");
        assertThat(trace.events().get(4).label()).isEqualTo("Address");

        // nextPossible from IMPLEMENTING = its forward edge (Validate) plus
        // the universal external-merge escape (Merged), never NEEDS_ATTENTION.
        List<String> labels = trace.nextPossible().stream().map(NextPossible::label).toList();
        assertThat(labels).contains("Validate", "Merged");
        assertThat(labels).doesNotContain("Parked");
    }

    @Test
    void terminalPhaseHasNoNextPossible()
    {
        List<TaskPhaseEvent> log = events(
                null, TaskPhase.PUSHED_AWAITING_CI,
                TaskPhase.PUSHED_AWAITING_CI, TaskPhase.COMPLETED);
        stub(TaskPhase.COMPLETED, log);

        TaskTraceResponse trace = service.trace("t1.k1").orElseThrow();
        assertThat(trace.nextPossible()).isEmpty();
        assertThat(active(trace)).isEqualTo("MERGE");
    }

    // ── helpers ──────────────────────────────────────────────────────

    private void stub(TaskPhase phase, List<TaskPhaseEvent> log)
    {
        when(taskStore.findTaskById("t1.k1")).thenReturn(Optional.of(task(phase)));
        when(taskStore.listPhaseEvents("t1.k1")).thenReturn(log);
    }

    private static List<TaskPhaseEvent> events(TaskPhase... fromToPairs)
    {
        List<TaskPhaseEvent> out = new ArrayList<>();
        for (int i = 0; i < fromToPairs.length; i += 2) {
            out.add(new TaskPhaseEvent(
                    i / 2 + 1L, "t1.k1", fromToPairs[i], fromToPairs[i + 1],
                    Instant.ofEpochMilli(1_700_000_000_000L + i), "reason", Actor.AGENT));
        }
        return out;
    }

    private static int visits(TaskTraceResponse trace, String milestone)
    {
        return trace.milestoneSummary().stream()
                .filter(m -> m.milestone().equals(milestone)).findFirst().orElseThrow().visits();
    }

    private static boolean skipped(TaskTraceResponse trace, String milestone)
    {
        return trace.milestoneSummary().stream()
                .filter(m -> m.milestone().equals(milestone)).findFirst().orElseThrow().skipped();
    }

    private static String active(TaskTraceResponse trace)
    {
        return trace.milestoneSummary().stream()
                .filter(MilestoneSummary::active).map(MilestoneSummary::milestone)
                .findFirst().orElse(null);
    }

    private static Task task(TaskPhase phase)
    {
        Instant now = Instant.ofEpochMilli(1_700_000_000_000L);
        return new Task("t1.k1", "t1", 1L, TaskStatus.IN_REVIEW, "dev/x", "/wt", "main", "/clone",
                null, null, null, null, null, "DEVELOP", null, null, 0L, 0L, 0L, null,
                now, null, null, null, null, null, null, phase, null, 0, null);
    }
}
