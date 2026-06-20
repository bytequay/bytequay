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
package com.bytequay.app.domain;

import java.time.Instant;
import java.util.UUID;

/**
 * One loop iteration of a monitor stage. In the async-turn model an
 * iteration is exactly one monitor-enqueued turn: it opens when a driver
 * enqueues the turn (binding {@code turnId}) and closes when that turn
 * finishes. The summary is filled later by {@code record_iteration_summary}
 * or by a synthetic placeholder.
 *
 * @param turnId the monitor turn this iteration tracks
 * @param trigger red_ci | new_comments | budget_exhausted_resume
 * @param endedReason push_completed | failed | needs_attention (null while open)
 * @param summaryRequestTurnId the follow-up turn that solicits the summary, or null
 */
public record TaskStageIteration(
        UUID id,
        UUID stageId,
        String taskId,
        String turnId,
        int iterationNumber,
        String trigger,
        Instant startedAt,
        Instant endedAt,
        String endedReason,
        String summaryText,
        Instant summarizedAt,
        String summaryRequestTurnId)
{
    /** A fresh, open iteration for a just-enqueued monitor turn. */
    public static TaskStageIteration opened(
            UUID id, UUID stageId, String taskId, String turnId, int iterationNumber,
            String trigger, Instant startedAt)
    {
        return new TaskStageIteration(id, stageId, taskId, turnId, iterationNumber, trigger,
                startedAt, null, null, null, null, null);
    }

    public TaskStageIteration withEnded(Instant endedAt, String endedReason)
    {
        return new TaskStageIteration(id, stageId, taskId, turnId, iterationNumber, trigger,
                startedAt, endedAt, endedReason, summaryText, summarizedAt, summaryRequestTurnId);
    }

    public TaskStageIteration withSummary(String summaryText, Instant summarizedAt)
    {
        return new TaskStageIteration(id, stageId, taskId, turnId, iterationNumber, trigger,
                startedAt, endedAt, endedReason, summaryText, summarizedAt, summaryRequestTurnId);
    }

    public TaskStageIteration withSummaryRequestTurnId(String summaryRequestTurnId)
    {
        return new TaskStageIteration(id, stageId, taskId, turnId, iterationNumber, trigger,
                startedAt, endedAt, endedReason, summaryText, summarizedAt, summaryRequestTurnId);
    }
}
