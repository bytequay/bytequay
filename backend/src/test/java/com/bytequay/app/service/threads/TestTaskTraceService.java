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
import com.bytequay.app.domain.PullRequestDetail;
import com.bytequay.app.domain.PullRequestDetail.CiStatus;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskPhaseEvent;
import com.bytequay.app.domain.TaskStatus;
import com.bytequay.app.repository.TaskStore;
import com.bytequay.app.service.pr.PullRequestService;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestTaskTraceService
{
    private final TaskStore taskStore = mock(TaskStore.class);
    private final PullRequestService pullRequests = mock(PullRequestService.class);
    private final TaskTraceService service = new TaskTraceService(taskStore, pullRequests);

    @Test
    void emptyForAnUnknownTask()
    {
        when(taskStore.findTaskById("nope")).thenReturn(Optional.empty());
        assertThat(service.trace("nope")).isEmpty();
    }

    @Test
    void countsLoopRevisitsAndFlagsTheActiveBucket()
    {
        // Implement → Validate → Review → (rework loop) Implement → Validate
        // → Review → Push → Wait CI.
        List<TaskPhaseEvent> log = events(
                null, TaskPhase.IMPLEMENTING,
                TaskPhase.IMPLEMENTING, TaskPhase.VALIDATING,
                TaskPhase.VALIDATING, TaskPhase.INTERNAL_REVIEW,
                TaskPhase.INTERNAL_REVIEW, TaskPhase.IMPLEMENTING,
                TaskPhase.IMPLEMENTING, TaskPhase.VALIDATING,
                TaskPhase.VALIDATING, TaskPhase.INTERNAL_REVIEW,
                TaskPhase.INTERNAL_REVIEW, TaskPhase.AWAITING_PUSH,
                TaskPhase.AWAITING_PUSH, TaskPhase.PUSHED_AWAITING_CI);
        stub(TaskPhase.PUSHED_AWAITING_CI, log);

        TaskTraceResponse trace = service.trace("t1.k1").orElseThrow();

        assertThat(trace.currentPhase()).isEqualTo("PUSHED_AWAITING_CI");
        assertThat(trace.currentMilestone()).isEqualTo("WAIT_ON_PR");
        assertThat(trace.events()).hasSize(8);

        // IMPLEMENT, VALIDATE and REVIEW each entered twice (the rework
        // loop redoes all three). Consecutive same-bucket phases don't
        // recount.
        assertThat(visits(trace, "IMPLEMENT")).isEqualTo(2);
        assertThat(visits(trace, "REVIEW")).isEqualTo(2);
        assertThat(visits(trace, "VALIDATE")).isEqualTo(2);
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
                TaskPhase.INTERNAL_REVIEW, TaskPhase.IMPLEMENTING);
        stub(TaskPhase.IMPLEMENTING, log);

        TaskTraceResponse trace = service.trace("t1.k1").orElseThrow();

        // First Implement reads "Implement"; the reworked one reads "Address".
        assertThat(trace.events().get(0).label()).isEqualTo("Implement");
        assertThat(trace.events().get(3).label()).isEqualTo("Address");

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

    @Test
    void surfacesTheLinkedPrInAWaitState()
    {
        when(taskStore.findTaskById("t1.k1")).thenReturn(
                Optional.of(task(TaskPhase.PUSHED_AWAITING_CI, "trinodb/trino#29897")));
        when(taskStore.listPhaseEvents("t1.k1")).thenReturn(events(
                null, TaskPhase.AWAITING_PUSH,
                TaskPhase.AWAITING_PUSH, TaskPhase.PUSHED_AWAITING_CI));
        PullRequestDetail pr = mock(PullRequestDetail.class);
        when(pr.number()).thenReturn(29897);
        when(pr.ciStatus()).thenReturn(CiStatus.PENDING);
        when(pr.draft()).thenReturn(true);
        when(pr.approvalCount()).thenReturn(0);
        when(pr.changesRequestedCount()).thenReturn(0);
        when(pr.pendingReviewerCount()).thenReturn(1);
        when(pr.requestedReviewers()).thenReturn(List.of("alice"));
        when(pullRequests.getPullRequestDetail("trinodb/trino", 29897)).thenReturn(pr);

        var active = service.trace("t1.k1").orElseThrow().linkedActivePr();
        assertThat(active).isNotNull();
        assertThat(active.prNumber()).isEqualTo(29897);
        assertThat(active.ciStatus()).isEqualTo("PENDING");
        assertThat(active.draft()).isTrue();
        assertThat(active.pendingReviewerCount()).isEqualTo(1);
        assertThat(active.requestedReviewers()).containsExactly("alice");
    }

    @Test
    void doesNotFetchTheLinkedPrOutsideAWaitState()
    {
        when(taskStore.findTaskById("t1.k1")).thenReturn(
                Optional.of(task(TaskPhase.IMPLEMENTING, "trinodb/trino#29897")));
        when(taskStore.listPhaseEvents("t1.k1")).thenReturn(events(null, TaskPhase.IMPLEMENTING));

        assertThat(service.trace("t1.k1").orElseThrow().linkedActivePr()).isNull();
        verifyNoInteractions(pullRequests);
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
        return task(phase, null);
    }

    private static Task task(TaskPhase phase, String linkedPrRef)
    {
        Instant now = Instant.ofEpochMilli(1_700_000_000_000L);
        return new Task("t1.k1", "t1", 1L, TaskStatus.IN_REVIEW, "dev/x", "/wt", "main", "/clone",
                null, null, null, null, null, "DEVELOP", null, null, 0L, 0L, 0L, null,
                now, null, null, null, null, null, null, phase, null, 0, linkedPrRef);
    }
}
