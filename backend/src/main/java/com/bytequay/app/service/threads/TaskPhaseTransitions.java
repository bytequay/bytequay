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

import com.bytequay.app.domain.TaskPhase;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The dev-task phase graph: allowed forward edges per {@link TaskPhase},
 * plus the universal escapes. {@code NEEDS_ATTENTION} and {@code
 * COMPLETED} are reachable from every non-terminal phase (a cap can park
 * a task, and a merge / close can finish it, at any point); {@code
 * COMPLETED} is terminal.
 *
 * <p>This is the deterministic spine the {@link TaskPhaseMachine} guards
 * every transition against — illegal edges throw rather than silently
 * corrupt the lifecycle.
 */
final class TaskPhaseTransitions
{
    private TaskPhaseTransitions() {}

    private static final Map<TaskPhase, Set<TaskPhase>> FORWARD = forwardEdges();

    private static Map<TaskPhase, Set<TaskPhase>> forwardEdges()
    {
        Map<TaskPhase, Set<TaskPhase>> m = new EnumMap<>(TaskPhase.class);
        // A planning task promotes to IMPLEMENTING when the user approves
        // the plan (which opens the DevelopmentStage); QUEUED is the only
        // other forward target for a task whose plan-approval routes it
        // through the scheduler before a compute slot frees.
        m.put(TaskPhase.PLANNING, EnumSet.of(TaskPhase.IMPLEMENTING, TaskPhase.QUEUED));
        // A queued task promotes to IMPLEMENTING when the scheduler frees
        // a slot; that's its only forward edge (besides the universal
        // NEEDS_ATTENTION / COMPLETED escapes a cancel can take).
        m.put(TaskPhase.QUEUED, EnumSet.of(TaskPhase.IMPLEMENTING));
        m.put(TaskPhase.IMPLEMENTING, EnumSet.of(TaskPhase.VALIDATING));
        m.put(TaskPhase.VALIDATING, EnumSet.of(TaskPhase.INTERNAL_REVIEW));
        // Internal review either surfaces findings to address, or is clean
        // and the task holds for the first push.
        m.put(TaskPhase.INTERNAL_REVIEW,
                EnumSet.of(TaskPhase.ADDRESSING_COMMENTS, TaskPhase.AWAITING_PUSH));
        m.put(TaskPhase.AWAITING_PUSH, EnumSet.of(TaskPhase.PUSHED_AWAITING_CI));
        // CI green on a draft holds for "mark ready"; CI green on a ready
        // PR goes straight to remote review; CI red kicks off a fix loop.
        m.put(TaskPhase.PUSHED_AWAITING_CI,
                EnumSet.of(TaskPhase.AWAITING_READY, TaskPhase.AWAITING_REMOTE_REVIEW,
                        TaskPhase.CI_FIXING));
        m.put(TaskPhase.CI_FIXING, EnumSet.of(TaskPhase.AWAITING_PUSH));
        m.put(TaskPhase.AWAITING_READY, EnumSet.of(TaskPhase.AWAITING_REMOTE_REVIEW));
        m.put(TaskPhase.AWAITING_REMOTE_REVIEW, EnumSet.of(TaskPhase.ADDRESSING_COMMENTS));
        m.put(TaskPhase.ADDRESSING_COMMENTS, EnumSet.of(TaskPhase.AGENT_RE_REVIEW));
        // Re-review either finds more to fix, or is clean — and the clean
        // exit depends on whether the work was already pushed (update push)
        // or is still the first internal pass (first push).
        m.put(TaskPhase.AGENT_RE_REVIEW,
                EnumSet.of(TaskPhase.ADDRESSING_COMMENTS, TaskPhase.AWAITING_UPDATE_PUSH,
                        TaskPhase.AWAITING_PUSH));
        m.put(TaskPhase.AWAITING_UPDATE_PUSH, EnumSet.of(TaskPhase.PUSHED_AWAITING_CI));
        // Human-recovered parked task restarts the cycle.
        m.put(TaskPhase.NEEDS_ATTENTION, EnumSet.of(TaskPhase.IMPLEMENTING));
        m.put(TaskPhase.COMPLETED, EnumSet.noneOf(TaskPhase.class));
        return m;
    }

    /** True when {@code from → to} is a legal edge: an explicit forward
     *  edge, or the universal escape to NEEDS_ATTENTION / COMPLETED from
     *  any non-terminal phase. COMPLETED is terminal. */
    static boolean isLegal(TaskPhase from, TaskPhase to)
    {
        if (from == to) {
            return false;
        }
        if (from == TaskPhase.COMPLETED) {
            return false;
        }
        // Universal escapes from any non-terminal phase: park (NEEDS_ATTENTION),
        // finish (COMPLETED), or restart planning (PLANNING, via an explicit
        // user re-plan that reopens a PlanStage).
        if (to == TaskPhase.NEEDS_ATTENTION || to == TaskPhase.COMPLETED
                || to == TaskPhase.PLANNING) {
            return true;
        }
        return FORWARD.getOrDefault(from, Set.of()).contains(to);
    }

    /**
     * Phases a non-terminal {@code from} could transition to next, for the
     * next-possible line: its explicit forward edges, plus the universal
     * "PR merged externally" escape to {@link TaskPhase#COMPLETED} appended
     * last. {@link TaskPhase#NEEDS_ATTENTION} is omitted — it's a parked
     * fallback, not a predicted next step. Empty for a terminal phase.
     */
    static List<TaskPhase> nextPhases(TaskPhase from)
    {
        if (from == TaskPhase.COMPLETED) {
            return List.of();
        }
        LinkedHashSet<TaskPhase> next = new LinkedHashSet<>(FORWARD.getOrDefault(from, Set.of()));
        next.add(TaskPhase.COMPLETED);
        return List.copyOf(next);
    }
}
