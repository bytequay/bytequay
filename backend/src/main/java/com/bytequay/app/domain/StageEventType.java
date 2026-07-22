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
 * The event kinds recorded in {@code task_stage_event} for measurement and
 * audit. Lifecycle events, notifications, and pull-request milestones
 * are written by their respective services; the remaining values provide
 * stable vocabulary for other stage transitions.
 */
public enum StageEventType
{
    OPENED,
    CLOSED,
    PAUSED,
    RESUMED,
    LOOP_ITERATION_STARTED,
    NOTIFY_FIRED,
    NOTIFY_SKIPPED,
    OPERATION_STARTED,
    OPERATION_COMPLETED,
    OPERATION_FAILED,
    BUDGET_EXHAUSTED,
    BUDGET_EXHAUSTED_DECISION,

    /** The Development agent began one of the two durable PR-preparation
     *  phases ({@code starting} or {@code creating-draft}). The ordinary git,
     *  template, and diff tool calls remain in the Development transcript;
     *  this compact milestone is also projected into Brain and the PR timeline. */
    PULL_REQUEST_PROGRESS,

    /** A task's local branch was published as a new remote pull request.
     *  Payload carries the branch flow, GitHub URL, and local diff totals so
     *  Development and Brain can render the same milestone. */
    PULL_REQUEST_CREATED,

    /** A {@code PlanResult} was recorded on a PlanStage (the payload is the
     *  full plan JSON). Multiple per stage form the revision chain. */
    PLAN_RECORDED,

    /** The user approved the latest plan; the PlanStage closes and the
     *  DevelopmentStage opens. Payload carries the approved revision id. */
    PLAN_APPROVED,

    /** The dev agent flagged a plan-adequacy concern during development.
     *  Non-blocking; payload is the note + source stage + timestamp. */
    PLAN_FOLLOWUP_NOTED,

    /** A planning turn finished without producing a plan (the brain agent
     *  errored or never recorded one). Payload carries the failure reason so
     *  the plan card / feed can surface it instead of a silent empty draft. */
    PLAN_FAILED,

    /** A CLOSED stage was woken back up for a new burst of work of the same
     *  kind, reusing its id (and whatever agent session is cached under it)
     *  instead of opening a second stage. */
    REOPENED,

    /** The brain's mandatory one-round self-review of its own plan finished
     *  (plan-rail-runs.md R20) — payload carries the verdict + whether it
     *  revised the plan. Written on the PlanStage; at most one per task, so
     *  its presence also gates against re-triggering the self-review turn. */
    PLAN_SELF_REVIEWED
}
