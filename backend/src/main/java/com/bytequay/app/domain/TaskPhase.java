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
 * phase=AWAITING_REMOTE_REVIEW} while {@code status=RUNNING} or {@code
 * IDLE}.
 *
 * <p>The forward phases are deliberately granular — each maps to a
 * distinct thing the agent or human is doing or waiting on. They are not
 * to be folded. {@link #NEEDS_ATTENTION} is a parked escape reachable
 * from any phase, not a forward step.
 *
 * <p>Pushes to the remote only ever originate from {@link #AWAITING_PUSH}
 * — every one human-approved (or auto, within the consecutive-auto cap).
 * A red CI check on {@link #PUSHED_AWAITING_CI} no longer moves the phase
 * at all — it opens a {@code ci_fix} {@code AgentRun} that fixes and
 * re-pushes beside whatever phase the task is already on (see {@code
 * CiFixRunExecutor}). Likewise, remote review comments no longer move the
 * phase off {@link #AWAITING_REMOTE_REVIEW} — a {@code review_round}
 * {@code AgentRun} triages, fixes, and drafts replies beside it, and the
 * round's own gate approval pushes straight to {@link #PUSHED_AWAITING_CI}
 * (see {@code ReviewRoundService}).
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
     *  approve the first push. Also the wait state the local addressing
     *  loop returns to once a round of local PR comments is addressed —
     *  it's already the de facto "local PR ready for review" state. */
    AWAITING_PUSH,

    /** New local PR review comments arrived (pre-push); agent is
     *  addressing them directly (no gate — the unpushed branch is the
     *  safety buffer), then returns to {@link #AWAITING_PUSH}. The local
     *  twin of a remote {@code review_round}. */
    ADDRESSING_LOCAL_COMMENTS,

    /** Branch pushed; waiting on remote CI to report. Also the phase a task
     *  holds at while a {@code ci_fix} {@code AgentRun} fixes a red check —
     *  the run works beside this phase rather than moving it. */
    PUSHED_AWAITING_CI,

    /** CI green on a draft PR; holding for the human to mark the PR ready
     *  for remote review. */
    AWAITING_READY,

    /** PR is ready; waiting on remote reviewers (external humans). Also the
     *  phase a task holds at while a {@code review_round} {@code AgentRun}
     *  triages and addresses a batch of reviewer comments — the run works
     *  beside this phase rather than moving it. */
    AWAITING_REMOTE_REVIEW,

    /** Terminal: the PR merged (success) or was closed (cancelled). */
    COMPLETED,

    /** Parked escape (not a forward phase): a cap was hit, a human pushed
     *  to the branch, or the agent got stuck and needs the human. */
    NEEDS_ATTENTION
}
