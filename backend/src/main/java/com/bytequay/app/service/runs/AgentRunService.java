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
import com.bytequay.app.domain.Thread;

import java.util.List;
import java.util.Optional;

/**
 * Read boundary for historical {@link AgentRun} rows. Execution and lifecycle
 * mutations are retired; typed V2 Turns and Operations own new work.
 */
public interface AgentRunService
{
    Optional<AgentRun> findById(String runId);

    /** Internal lookup for the inert investigation-review foreign-key row. */
    Optional<AgentRun> findReviewCompatibilityHeaderById(String runId);

    List<AgentRun> findByWorkspace(String workspaceId);

    List<AgentRun> findByThread(String threadId);

    List<AgentRun> findByReviewRound(String reviewRoundId);

    /** Every run for a task, newest-first. {@code kind} / {@code
     *  parentStageId} narrow when non-null. */
    List<AgentRun> findByTask(String taskId, String kind, String parentStageId);

    /** The task's live runs ({@code running} / {@code awaiting_gate}) —
     *  what a rail endpoint renders sub-rows from. */
    List<AgentRun> liveRunsByTask(String taskId);

    /**
     * Writes the one inert compatibility header still required by the
     * historical {@code review_round.agent_run_id} and verifier foreign keys.
     * The row is born terminal and fully detached, and is never exposed as a
     * Session or used to drive execution.
     */
    AgentRun createReviewCompatibilityHeader(
            String reviewRoundId,
            Integer budget);

    /**
     * Idempotent open: returns the task's existing live run of {@code
     * kind} if one is already open, else starts a fresh one — opening its
     * own backing {@code task_stage} row of {@code backingStageType} (
     * with no caller-stage link) so the run has an isolated message home
     * from its first turn. {@code parentStageId} is rail placement only,
     * independent of the backing stage's ownership.
     */
    AgentRun open(
            String taskId, String kind, String source, String parentStageId,
            StageType backingStageType, Integer budget);

    /** Same-transaction form for an enclosing task command. */
    AgentRun openInCommand(
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

    /** Same-transaction form for an enclosing task command. */
    AgentRun openInStageInCommand(
            String taskId, String kind, String source, String stageId, Integer budget);

    /** Open a run for an external review round that has no task/stage container. */
    AgentRun openDetached(String kind, String source, String reviewRoundId, Integer budget);

    /**
     * Open an artifact run owned by a task but outside its lifecycle stages.
     * Unlike {@link #open}, this never creates or reuses a {@code task_stage}.
     * Multiple runs of the same kind may belong to one review round (for
     * example the investigator and independent verifier), so it is not
     * deduplicated by task/kind.
     */
    AgentRun openTaskArtifact(
            String taskId, String kind, String source, String reviewRoundId, Integer budget);

    /**
     * Open the scheduler episode backing a public Session. Trunk turns always
     * get a fresh plan session; task/stage turns reuse the live session for
     * that stage episode and correlate every turn through agentRunId.
     */
    AgentRun openSchedulerSession(
            Thread thread, String taskId, String stageId, String kind, String launchInput);

    /** Same-transaction form for a scheduler enqueue inside an enclosing task command. */
    AgentRun openSchedulerSessionInCommand(
            Thread thread, String taskId, String stageId, String kind, String launchInput);

    /**
     * Attach an artifact run (notably a review round) to its public
     * workspace owner, and optionally its trunk, without creating a second
     * run record. PR-owned review runs intentionally have no trunk.
     */
    AgentRun attachOwnership(
            String runId, String workspaceId, String threadId,
            String provider, String model, String launchInput);

    /** Record one more iteration, optionally updating the fold-bar headline. */
    AgentRun recordIteration(String runId, String headlineOrNull);

    /** Spend one unit of budget (floor zero; a no-op if the run has none). */
    AgentRun spendBudget(String runId);

    AgentRun updateHeadline(String runId, String headline);

    AgentRun updateMetrics(String runId, String metricsJson);

    AgentRun updateAccounting(
            String runId, long costUsdMilli, long tokensIn, long tokensOut, int stepCursor);

    AgentRun pause(String runId, String reason);

    /** Same-transaction pause for an enclosing task command. */
    AgentRun pauseInCommand(String taskId, String runId, String reason);

    AgentRun resume(String runId);

    /** Creates a new queued Session carrying the prior launch request. */
    AgentRun restart(String runId);

    /** Same-transaction restart for an enclosing task command. */
    AgentRun restartInCommand(String taskId, String runId);

    /** Transition a live run to {@code status}; terminal runs are immutable.
     * A terminal status ({@code succeeded} / {@code failed} / {@code
     * cancelled}) also closes the run's backing stage. */
    AgentRun transition(String runId, String status, String reason);

    /** Same-transaction terminal projection for an enclosing task command. */
    AgentRun transitionInCommand(String taskId, String runId, String status, String reason);
}
