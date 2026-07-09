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
package com.bytequay.app.service.runs;

import com.bytequay.app.domain.AgentRun;
import com.bytequay.app.domain.StageType;

import java.util.List;
import java.util.Optional;

/**
 * Owns the {@link AgentRun} status machine — the containment-without-
 * lifecycle-position entity (plan-rail-runs.md R6). Every run gets its
 * own backing {@code task_stage} row so its turns land in {@code
 * stage_messages} through the mechanism that already exists for stages
 * (see {@link #open}); this is an implementation detail callers never
 * touch directly.
 */
public interface AgentRunService
{
    Optional<AgentRun> findById(String runId);

    /** Every run for a task, newest-first. {@code kind} / {@code
     *  parentStageId} narrow when non-null. */
    List<AgentRun> findByTask(String taskId, String kind, String parentStageId);

    /** The task's live runs ({@code running} / {@code awaiting_gate}) —
     *  what a rail endpoint renders sub-rows from. */
    List<AgentRun> liveRunsByTask(String taskId);

    /**
     * Idempotent open: returns the task's existing live run of {@code
     * kind} if one is already open, else starts a fresh one — opening its
     * own backing {@code task_stage} row of {@code backingStageType} (
     * {@code callerStageId = parentStageId}, mirroring the review-panel
     * sub-stage pattern) so the run has an isolated message home from
     * its first turn, independent of any phase-driven stage.
     */
    AgentRun open(
            String taskId, String kind, String source, String parentStageId,
            StageType backingStageType, Integer budget);

    /**
     * Idempotent open for an episode that executes inside an existing stage.
     * The run's {@code stageId} and {@code parentStageId} both point at
     * {@code stageId}; terminal run transitions must not close that owning
     * stage.
     */
    AgentRun openInStage(
            String taskId, String kind, String source, String stageId, Integer budget);

    /** Record one more iteration, optionally updating the fold-bar headline. */
    AgentRun recordIteration(String runId, String headlineOrNull);

    /** Spend one unit of budget (floor zero; a no-op if the run has none). */
    AgentRun spendBudget(String runId);

    AgentRun updateHeadline(String runId, String headline);

    /** Transition to {@code status}; a terminal status ({@code succeeded}
     *  / {@code failed} / {@code cancelled}) also closes the run's
     *  backing stage. */
    AgentRun transition(String runId, String status, String reason);
}
