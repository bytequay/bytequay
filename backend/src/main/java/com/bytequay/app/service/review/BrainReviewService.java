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

import com.bytequay.app.domain.PR;
import com.bytequay.app.domain.ReviewRound;
import com.bytequay.app.domain.Task;

/**
 * Brain-driven adversarial review at the plan and code lock points
 * (plan-rail-runs.md R20-R24) — the brain critiques its own plan once
 * (R20) and reviews Development's diff at two code lock points (R21),
 * looping review-fix-review with a human-escalating budget (R23) via the
 * {@link ReviewRound} machinery (R22). {@code ci_fix}, {@code
 * branch_guard}, and Cleanup are excluded — nothing here ever hooks them.
 */
public interface BrainReviewService
{
    /**
     * The R21(a) dev-end lock point: called wherever the PR used to flip
     * straight to {@code local-open}. Starts (or continues) the brain's
     * review of the dev diff and returns the PR in its <em>current</em>
     * state — still {@code local-drafted} while a review is in flight,
     * already {@code local-open} once the brain approved (or escalated)
     * and the flip actually happened. Callers that used to call {@code
     * PRService.requestUserReview} directly call this instead; no
     * other change is needed at the call site.
     */
    PR reviewBeforeLocalOpen(String prId, String actor);

    /** Start a fresh adversarial round after Development addressed an
     *  explicitly submitted local user-review batch. The PR is already
     *  {@code local-open}; approval returns the task to the same push gate. */
    void reviewAfterLocalComments(String prId);

    /** Reopens a Brain review episode parked by an operational failure.
     *  Returns true when the coordinator owns the resumed runtime. */
    boolean resumeParkedReview(String taskId);

    /** True when task Resume must be handed back to this coordinator instead
     *  of generically waking a stage agent. Covers plan self-review plus local
     *  and external code-review episodes. */
    boolean ownsParkedResume(String taskId);

    /** Atomically parks any coordinator-owned review runtime for an explicit
     *  task Pause. Returns true when a live code-review round or pending plan
     *  self-review was owned; this operation does not record a failed review. */
    boolean pauseActiveReview(String taskId, String reason);

    /**
     * The R21(b) round lock point: called once an external round's
     * addressing turn finishes, instead of arming {@code awaiting_gate}
     * directly. Starts (or continues) a brain verification pass over the
     * round's fixes; arms the gate once it concludes (approved or
     * escalated) — the gate always eventually arms, just tagged with the
     * brain's verdict when it didn't approve.
     */
    void reviewBeforeRoundGate(ReviewRound round, Task task);

    /**
     * Persists the brain's verdict from {@code record_review_verdict}.
     * {@code scope} is {@code plan} (R20, resolved directly off the
     * calling turn's PlanStage id) or {@code dev}/{@code round} (R21,
     * resolved via the live {@link ReviewRound} whose backing run owns
     * the calling turn's stage id).
     */
    default void recordVerdict(String taskId, String stageId, String scope, String verdict)
    {
        recordVerdict(taskId, stageId, null, scope, verdict);
    }

    void recordVerdict(String taskId, String stageId, String agentRunId, String scope, String verdict);

    /** True only after the Brain spent its review budget and deliberately
     *  handed unresolved Brain findings to the human promotion gate. */
    default boolean isBudgetExhaustedEscalation(String taskId)
    {
        return false;
    }
}
