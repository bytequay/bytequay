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

import com.bytequay.core.ci.CiAutofixRun;
import com.bytequay.core.ci.CiAutofixRunId;

import java.util.List;

public interface CiAutofixService
{
    /**
     * Starts CI Autofix after direct user consent.
     *
     * <p>Verifies the authenticated user is the PR author, the PR is open and
     * same-repository, the selected worktree matches the PR head, and the
     * commit/push and failed-job-rerun permissions are explicit. Creates one
     * durable run and one existing agent task. Does not write to GitHub.
     *
     * @param command PR, worktree, lane, limits, consent, and idempotency key
     * @return the created or existing run
     */
    CiAutofixRun start(StartCiAutofixCommand command);

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
     * <p>The first implementation observes the current agent turn. Later
     * checkpoints add Git inspection, push, rerun, and CI monitoring without
     * changing this entry point.
     *
     * @param runId run selected by the monitor
     * @return run after reconciliation
     */
    CiAutofixRun advance(CiAutofixRunId runId);
}
