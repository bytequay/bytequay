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
package com.bytequay.app.beans.trace;

/**
 * One node of the expanded sequential timeline — a single
 * {@code task_phase_event} row plus its derived milestone and friendly
 * label.
 *
 * @param n              1-based position in the chronological sequence
 * @param fromPhase      null on the task's first transition
 * @param fromMilestone  null on the task's first transition
 * @param transitionedAt ISO-8601 instant
 * @param label          friendly node name (first-visit aware)
 */
public record TraceEvent(
        int n,
        String fromPhase,
        String toPhase,
        String fromMilestone,
        String toMilestone,
        String actor,
        String reason,
        String transitionedAt,
        String label)
{
}
