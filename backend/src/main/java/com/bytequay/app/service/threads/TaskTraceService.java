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
import com.bytequay.app.beans.trace.TraceEvent;
import com.bytequay.app.domain.Task;
import com.bytequay.app.domain.TaskMilestone;
import com.bytequay.app.domain.TaskPhase;
import com.bytequay.app.domain.TaskPhaseEvent;
import com.bytequay.app.repository.TaskStore;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * Builds the read-model for a task's lifecycle flow display from its
 * {@code task_phase_event} log: the chronological node list, the
 * six-bucket milestone summary (visit counts, active and skip flags),
 * and the next-possible transitions. Pure derivation — no mutation.
 */
@Service
public class TaskTraceService
{
    private final TaskStore taskStore;

    public TaskTraceService(TaskStore taskStore)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
    }

    public Optional<TaskTraceResponse> trace(String taskId)
    {
        Optional<Task> taskOpt = taskStore.findTaskById(taskId);
        if (taskOpt.isEmpty()) {
            return Optional.empty();
        }
        Task task = taskOpt.get();
        List<TaskPhaseEvent> events = taskStore.listPhaseEvents(taskId);
        TaskPhase currentPhase = task.phase();
        TaskMilestone currentMilestone = currentPhase == null ? null : TaskMilestone.of(currentPhase);

        return Optional.of(new TaskTraceResponse(
                taskId,
                currentPhase == null ? null : currentPhase.name(),
                currentMilestone == null ? null : currentMilestone.name(),
                buildEvents(events),
                buildMilestoneSummary(events, currentMilestone),
                buildNextPossible(currentPhase)));
    }

    private static List<TraceEvent> buildEvents(List<TaskPhaseEvent> events)
    {
        List<TraceEvent> out = new ArrayList<>(events.size());
        for (int i = 0; i < events.size(); i++) {
            TaskPhaseEvent e = events.get(i);
            TaskMilestone toM = TaskMilestone.of(e.toPhase());
            TaskMilestone fromM = e.fromPhase() == null ? null : TaskMilestone.of(e.fromPhase());
            out.add(new TraceEvent(
                    i + 1,
                    e.fromPhase() == null ? null : e.fromPhase().name(),
                    e.toPhase().name(),
                    fromM == null ? null : fromM.name(),
                    toM.name(),
                    e.actor() == null ? null : e.actor().name(),
                    e.reason(),
                    e.transitionedAt().toString(),
                    TaskFlowLabels.friendlyLabel(e, events.subList(0, i))));
        }
        return out;
    }

    /**
     * One entry per canonical bucket, in fixed order. {@code visits}
     * counts distinct entries (a transition into the bucket from a
     * <em>different</em> milestone) so a loop reads as {@code ×N};
     * consecutive phases inside the same bucket don't double-count.
     */
    private static List<MilestoneSummary> buildMilestoneSummary(
            List<TaskPhaseEvent> events, TaskMilestone currentMilestone)
    {
        Map<TaskMilestone, Integer> visits = new EnumMap<>(TaskMilestone.class);
        for (TaskPhaseEvent e : events) {
            TaskMilestone toM = TaskMilestone.of(e.toPhase());
            TaskMilestone fromM = e.fromPhase() == null ? null : TaskMilestone.of(e.fromPhase());
            if (fromM != toM) {
                visits.merge(toM, 1, Integer::sum);
            }
        }

        List<TaskMilestone> canon = TaskMilestone.CANONICAL;
        List<MilestoneSummary> out = new ArrayList<>(canon.size());
        for (int pos = 0; pos < canon.size(); pos++) {
            TaskMilestone m = canon.get(pos);
            int v = visits.getOrDefault(m, 0);
            boolean active = m == currentMilestone;
            // Skipped: never entered, yet a downstream canonical bucket was —
            // a skip-by-omission the dashed connector renders.
            boolean skipped = v == 0 && canon.subList(pos + 1, canon.size()).stream()
                    .anyMatch(d -> visits.getOrDefault(d, 0) > 0);
            out.add(new MilestoneSummary(m.name(), m.label(), v, active, skipped, pos + 1));
        }
        return out;
    }

    private static List<NextPossible> buildNextPossible(TaskPhase currentPhase)
    {
        if (currentPhase == null) {
            return List.of();
        }
        List<NextPossible> out = new ArrayList<>();
        for (TaskPhase to : TaskPhaseTransitions.nextPhases(currentPhase)) {
            out.add(new NextPossible(to.name(), TaskFlowLabels.nodeLabel(to), TaskFlowLabels.conditionFor(to)));
        }
        return out;
    }
}
