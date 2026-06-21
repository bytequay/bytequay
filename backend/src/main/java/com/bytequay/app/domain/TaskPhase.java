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

/**
 * The dev task's PR-collaboration lifecycle — the realistic cycle from
 * "writing code" through "PR merged". Orthogonal to {@link TaskStatus},
 * which stays the agent <em>runtime</em> liveness axis (is the
 * subprocess alive / at a permission gate); a task can be {@code
 * phase=ADDRESSING_COMMENTS} while {@code status=RUNNING} or {@code
 * IDLE}.
 *
 * <p>The 12 forward phases are deliberately granular — each maps to a
 * distinct thing the agent or human is doing or waiting on. They are not
 * to be folded. {@link #NEEDS_ATTENTION} is a parked escape reachable
 * from any phase, not a forward step.
 *
 * <p>Pushes to the remote only ever originate from {@link #AWAITING_PUSH},
 * {@link #CI_FIXING} (via AWAITING_PUSH), and {@link #AWAITING_UPDATE_PUSH}
 * — every one human-approved (or auto, within the consecutive-auto cap).
 */
public enum TaskPhase
{
    /** A freshly-created task's first phase: the brain agent is producing a
     *  structured plan in the open {@code PlanStage}, and the user reviews
     *  it. No development work happens here — the {@code DevelopmentStage}
     *  cannot open until the plan is approved. Promotes to
     *  {@link #IMPLEMENTING} on approval. */
    PLANNING,

    /** Materialised from the thread's queue and waiting for a compute
     *  slot. The agent session pre-warms; the composer feeds the task's
     *  opening prompt. Promotes to {@link #IMPLEMENTING} when a slot
     *  opens. No human action required — grouped under IDLE. */
    QUEUED,

    /** Agent is writing code in the worktree. */
    IMPLEMENTING,

    /** Running the bundled checks (tests + checkstyle + repo rules) with
     *  a bounded auto-fix loop. */
    VALIDATING,

    /** A TaskPhase-hosted review pass (Lead + seats) is reviewing the
     *  local diff before anything is pushed. */
    INTERNAL_REVIEW,

    /** Validation + internal review passed; holding for the human to
     *  approve the first push. */
    AWAITING_PUSH,

    /** Branch pushed; waiting on remote CI to report. */
    PUSHED_AWAITING_CI,

    /** CI came back red (non-flaky); agent is fixing, then loops back to
     *  AWAITING_PUSH. */
    CI_FIXING,

    /** CI green on a draft PR; holding for the human to mark the PR ready
     *  for remote review. */
    AWAITING_READY,

    /** PR is ready; waiting on remote reviewers (external humans). */
    AWAITING_REMOTE_REVIEW,

    /** Remote review comments arrived; agent is addressing them. */
    ADDRESSING_COMMENTS,

    /** A TaskPhase-hosted RE_REVIEW pass (Loop D) is verifying the prior
     *  findings were addressed. */
    AGENT_RE_REVIEW,

    /** Comments addressed + re-review clean; holding for the human to
     *  approve the update push. */
    AWAITING_UPDATE_PUSH,

    /** Terminal: the PR merged (success) or was closed (cancelled). */
    COMPLETED,

    /** Parked escape (not a forward phase): a cap was hit, a human pushed
     *  to the branch, or the agent got stuck and needs the human. */
    NEEDS_ATTENTION
}
