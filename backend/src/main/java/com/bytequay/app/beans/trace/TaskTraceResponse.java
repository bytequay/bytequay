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

import java.util.List;

/**
 * Read-model for a task's lifecycle flow display: the chronological
 * phase-event log, a six-bucket milestone summary computed by grouping
 * those events, and the next-possible transitions from the current
 * phase. Backs {@code GET /api/tasks/{taskId}/trace}.
 *
 * @param currentPhase     null only for a task with no phase yet
 * @param currentMilestone milestone the current phase rolls up to, or null
 */
public record TaskTraceResponse(
        String taskId,
        String currentPhase,
        String currentMilestone,
        List<TraceEvent> events,
        List<MilestoneSummary> milestoneSummary,
        List<NextPossible> nextPossible)
{
}
