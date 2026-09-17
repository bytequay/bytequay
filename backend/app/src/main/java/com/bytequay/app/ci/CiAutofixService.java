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
package com.bytequay.app.ci;

import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.spi.CiDescriptionProposal;
import com.bytequay.app.repository.github.GitHubPullRequestIdentity;
import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.ci.CiAutofixRunId;

import java.time.Instant;
import java.util.List;

public interface CiAutofixService
{
    /** Stores one exact description repair from this run's active turn; does not write to GitHub. */
    void proposeDescription(TaskId taskId, TurnId turnId, CiDescriptionProposal proposal);

    /** Records why the active repair turn considers one failed job worth retrying without changes. */
    void requestRerun(TaskId taskId, TurnId turnId, long jobId, String reason);

    /**
     * Starts CI Autofix after direct user consent.
     *
     * <p>Verifies the authenticated user is the PR author, the PR is open and
     * its head has a verified workspace remote, and the commit/push and
     * failed-job-rerun permissions are explicit. Creates one durable run and
     * one agent task in a fresh managed worktree. Does not write to GitHub.
     *
     * @param command PR, workspace, lane, limits, consent, and idempotency key
     * @return the created or existing run
     */
    CiAutofixRun start(StartCiAutofixCommand command);

    /** Starts only under the live source continuation, retaining its original authorization deadline. */
    CiAutofixRun startDevelopment(StartCiAutofixCommand command, TaskId parentTaskId, TurnId sourceTurnId,
            GitHubPullRequestIdentity expectedPr, Instant activatedAt);

    /**
     * Stops children after the caller has revoked the source continuation in agent core.
     * Never replays journals or provisions missing tasks; source revocation also excludes a preparing, not-yet-inserted child.
     */
    void stopDevelopment(TaskId parentTaskId);

    /**
     * Explicitly discards local children after source authority was revoked in agent core and all child writers are verified stopped.
     * Retains CI run history and all remote results.
     */
    void deleteDevelopment(TaskId parentTaskId);

    /** Whether the existing paused run currently permits an answer without renewing authority. */
    boolean canMessage(CiAutofixRun run);

    /**
     * Returns one durable run and its attempts.
     *
     * @param runId run to read
     * @return current run projection
     */
    CiAutofixRun get(CiAutofixRunId runId);

    /**
     * Lists recent runs in reverse update order.
     *
     * @param limit positive maximum result count
     * @return recent runs
     */
    List<CiAutofixRun> list(int limit);

    /**
     * Sends one user instruction to a paused run's existing agent task.
     *
     * <p>Rechecks author, authorization, remote head, task readiness, and
     * worktree lease before starting a turn. The message cannot expand the
     * stored GitHub-write scope.
     *
     * @param command run ID, bounded message, and idempotency key
     * @return run linked to the accepted turn
     */
    CiAutofixRun sendMessage(SendCiAutofixMessageCommand command);

    /**
     * Cancels a run and any active agent turn.
     *
     * <p>Cancellation is idempotent. It never resets local history or writes
     * to GitHub.
     *
     * @param runId run to cancel
     * @return finished run
     */
    CiAutofixRun cancel(CiAutofixRunId runId);

    /**
     * Reconciles one run and performs at most one next action.
     *
     * <p>Uses only durable program facts: provider turn state, inspected Git,
     * exact push and rerun journals, and GitHub checks for the expected head.
     * Agent prose is retained for display but cannot select a branch. Calls
     * for one run are serialized in-process and every store update remains
     * optimistic for restart and cross-process safety.
     *
     * @param runId run selected by the monitor
     * @return run after reconciliation
     */
    CiAutofixRun advance(CiAutofixRunId runId);
}
