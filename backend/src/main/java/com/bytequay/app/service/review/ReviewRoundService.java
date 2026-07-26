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
package com.bytequay.app.service.review;

import com.bytequay.app.domain.ReviewRound;
import com.bytequay.app.domain.Task;

import java.util.List;
import java.util.Optional;

/**
 * Owns the {@code ReviewRound} lifecycle (plan-rail-runs.md R11-R13): groups
 * a batch of newly-arrived remote review comments, launches the batch's
 * {@code review_round} {@link com.bytequay.app.domain.AgentRun}, and gates
 * posting the round's drafted replies + pushing its commits behind an
 * explicit user approval.
 */
public interface ReviewRoundService
{
    /**
     * Called from {@code TaskLifecycleDriver}'s reconcile sweep once a
     * task's linked PR comments are ingested and its phase is confirmed at
     * {@code AWAITING_REMOTE_REVIEW}. Batches any unrounded remote comments
     * once the debounce window has elapsed, and opens the batch's {@code
     * review_round} run. A no-op when there's nothing new, or when the
     * oldest unrounded comment hasn't debounced yet.
     */
    void reconcile(Task task);

    Optional<ReviewRound> findById(String roundId);

    /** A task's rounds, newest-first. */
    List<ReviewRound> findByTask(String taskId);

    /**
     * Called from a task's terminal teardown (remote merge/close observed,
     * or an in-app cancel) so no round history remains actionable. Cancels
     * backing runs and closes every non-closed round, including paused and
     * posted rows. A no-op if all of the task's rounds are already closed.
     */
    void closeOpenRounds(String taskId, String reason);

    /** Same-transaction terminal projection for an enclosing task command. */
    void closeOpenRoundsInCommand(String taskId, String reason);

    /**
     * The gate: post every drafted reply in the round + push whatever the
     * round's agent committed to the task's worktree, in one go, then flip
     * the round to {@code posted} and the task's phase to {@code
     * PUSHED_AWAITING_CI}. Throws if the round isn't awaiting its gate.
     */
    ReviewRound approve(String roundId);

    /**
     * Recompute {@code fixed}/{@code replied}/{@code open} from the round's
     * actual comments (a resolved comment with a drafted reply counts as
     * replied, resolved with none as fixed, unresolved as still open) and
     * persist it. {@code pushedBack} always stays 0 — nothing in the agent
     * tool surface produces that outcome yet. Called whenever a round
     * comment's resolved/replied state changes; a no-op for an unknown
     * round id.
     */
    void recomputeStats(String roundId);
}
