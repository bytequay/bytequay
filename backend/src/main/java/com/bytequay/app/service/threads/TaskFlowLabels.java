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
import com.bytequay.app.domain.TaskPhaseEvent;

import java.util.List;

/**
 * Friendly per-event labels for the expanded sequential flow stepper.
 * The label depends on context: the <em>first</em> visit to a phase
 * reads differently from a later one ({@code Implement} vs {@code
 * Address}, {@code Review} vs {@code Re-review}, {@code Push} vs {@code
 * Push update}), which is what lets a loop read as a story rather than a
 * bare repeated phase name.
 */
public final class TaskFlowLabels
{
    private TaskFlowLabels() {}

    /**
     * Friendly label for the node an event lands on, given every event
     * that came before it (in chronological order) so first-vs-repeat
     * visits can be distinguished.
     */
    public static String friendlyLabel(TaskPhaseEvent event, List<TaskPhaseEvent> previousEvents)
    {
        return switch (event.toPhase()) {
            case IMPLEMENTING ->
                    firstVisit(previousEvents, TaskPhase.IMPLEMENTING) ? "Implement" : "Address";
            case CI_FIXING -> "Fix CI";
            case ADDRESSING_COMMENTS -> "Address";
            case VALIDATING -> "Validate";
            case INTERNAL_REVIEW ->
                    firstVisit(previousEvents, TaskPhase.INTERNAL_REVIEW) ? "Review" : "Re-review";
            case AGENT_RE_REVIEW -> "Re-review";
            case AWAITING_PUSH -> "Push";
            case AWAITING_UPDATE_PUSH -> "Push update";
            case PUSHED_AWAITING_CI -> "Wait CI";
            case AWAITING_READY -> "Mark ready";
            case AWAITING_REMOTE_REVIEW -> "Remote review";
            case COMPLETED -> "Merged";
            case NEEDS_ATTENTION -> "Parked";
            case QUEUED -> "Queued";
        };
    }

    /** True when {@code phase} was never the target of an earlier event. */
    private static boolean firstVisit(List<TaskPhaseEvent> previousEvents, TaskPhase phase)
    {
        return previousEvents.stream().noneMatch(e -> e.toPhase() == phase);
    }
}
