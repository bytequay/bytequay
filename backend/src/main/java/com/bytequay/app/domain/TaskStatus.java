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

import com.bytequay.app.service.concepts.Concept;
import com.bytequay.app.service.concepts.ConceptKind;

/**
 * Execution state of a {@link Task} (the unit of work that owns a
 * branch + worktree + PR). Distinct from {@link ThreadStatus} in
 * intent: the conversation may stay live while individual tasks roll
 * through pending → running → completed; tasks that park at
 * {@code AWAITING} are the "AI finished, waiting for the human to
 * decide" gate described in the workspace/thread/task design doc.
 *
 * <p>Values mirror {@link ThreadStatus} for now because the existing
 * status machinery maps cleanly onto a task; the two enums diverge
 * once the thread-level lifecycle (active/idle/archived) lands.
 */
public enum TaskStatus
{
    /** Created but no agent turn has started yet. */
    PENDING,

    /** Agent process is alive and producing events. */
    RUNNING,

    /** Paused waiting for the human at a per-tool permission gate
     *  (e.g. approve a destructive command). Short-lived. */
    AWAITING,

    /** Agent is idle (no live process) but the task hasn't terminated. */
    IDLE,

    /** Parked: the agent finished with a proposed diff + reply and is
     *  holding at the publish gate. Default landing state for headless
     *  auto-fix work — surfaces a notification, never silent publish. */
    @Concept(
            name = "awaiting_review",
            aka = {"parked-for-review", "review-gate"},
            kind = ConceptKind.STATE,
            definition = "A task whose agent finished a unit of work and is holding at "
                    + "the publish gate with a proposed diff + reply. The default landing "
                    + "state for headless work — a notification surfaces; nothing is "
                    + "pushed to GitHub until the user explicitly approves.",
            examples = {
                    "Next → parks the foreground task at AWAITING_REVIEW before cutting "
                            + "the sibling.",
                    "request_review on the active task transitions it to AWAITING_REVIEW."
            },
            relatedTools = {"request_review", "ship", "next"},
            relatedConcepts = {"task", "needs_attention"})
    AWAITING_REVIEW,

    /** Parked: the agent is stuck on a conflict, rejected push, or a
     *  judgment-call comment and needs the human to weigh in. The
     *  user's reply takes over the lease into an interactive session. */
    @Concept(
            name = "needs_attention",
            aka = {"blocked", "stuck"},
            kind = ConceptKind.STATE,
            definition = "A task whose agent is stuck on a conflict, a rejected push, or "
                    + "a judgment-call comment and needs the human to weigh in. The user's "
                    + "reply takes over the agent's lease into an interactive session.",
            examples = {
                    "An unattended turn whose tool request falls outside its autonomy "
                            + "envelope escalates to NEEDS_ATTENTION.",
                    "A merge conflict on push transitions the task to NEEDS_ATTENTION."
            },
            relatedConcepts = {"task", "awaiting_review"})
    NEEDS_ATTENTION,

    /** Shipped: the task's branch is pushed and a PR is open, but the PR
     *  hasn't merged yet. A non-terminal "published, in review" state —
     *  the task only advances to {@link #COMPLETED} once its PR merges.
     *  Distinct from {@link #AWAITING_REVIEW} (which is "agent finished,
     *  waiting at the local publish gate"); IN_REVIEW means the work is
     *  already on the remote under review. */
    IN_REVIEW,

    /** Task finished cleanly — for a shipped task, this means its PR merged. */
    COMPLETED,

    /** Task failed (budget, timeout, exception, killed, crashed). */
    ERRORED,

    /** Deliberately stopped by the user before finishing: the agent is
     *  interrupted, the task is closed, and its worktree + branch are
     *  reaped. Terminal — distinct from {@link #ERRORED} (a failure) and
     *  {@link #COMPLETED} (done). */
    CANCELED,
}
