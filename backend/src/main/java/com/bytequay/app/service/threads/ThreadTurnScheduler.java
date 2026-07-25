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
package com.bytequay.app.service.threads;

import com.bytequay.app.domain.Thread;
import com.bytequay.app.domain.TurnInitiator;
import com.bytequay.app.domain.TurnLiveness;

/**
 * Boundary ThreadService uses to queue agent work.
 */
public interface ThreadTurnScheduler
{
    /** Queue a user (attended) turn and return its durable turn id. */
    String enqueueTurn(Thread thread, String input);

    /** Queue a turn stamped with an explicit initiator — automated
     *  triggers use this to mark the turn unattended so the approval
     *  gate escalates rather than waits for a human. */
    String enqueueTurn(Thread thread, String input, TurnInitiator initiator);

    /** Queue a trunk-scope turn — forces {@code task_id = null} on the
     *  persisted row so the trunk planning agent picks it up regardless
     *  of any foreground Task on the thread. */
    String enqueueTrunkTurn(Thread thread, String input);

    /** Queue a trunk-scope turn with an explicit attended/unattended
     *  initiator. Background monitors must use this overload so tool
     *  approval policy does not mistake them for a live user turn. */
    default String enqueueTrunkTurn(Thread thread, String input, TurnInitiator initiator)
    {
        return enqueueTrunkTurn(thread, input);
    }

    /** Replay a persisted trunk launch while correlating the new turn to a
     *  caller-created Session. */
    default String enqueueTrunkTurn(Thread thread, String input, String agentRunId)
    {
        return enqueueTrunkTurn(thread, input);
    }

    /** Queue a task-scope (attended) turn bound to an explicit {@code
     *  taskId}, bypassing the active-task projection the no-id overloads
     *  derive. The task-altitude composer uses this so a turn lands on
     *  its task's conversation slice even when that task isn't in the
     *  narrow "active" set — parked at {@code AWAITING_REVIEW}, {@code
     *  NEEDS_ATTENTION}, or with a {@code COMPLETED} dev-lifecycle phase.
     *  In those states the thread has no active task, which used to
     *  mislabel the turn as a {@code task_id = null} (trunk) row and leak
     *  the task conversation into the trunk slice. A null {@code taskId}
     *  falls back to a trunk turn, matching the no-id behaviour.
     *
     *  <p>Defaulted to the active-task-derived {@link #enqueueTurn(Thread,
     *  String)} so simple implementors (test fakes) need no change; the
     *  production scheduler overrides it to honour {@code taskId}. */
    default String enqueueTaskTurn(Thread thread, String input, String taskId)
    {
        return enqueueTurn(thread, input);
    }

    /** As {@link #enqueueTaskTurn(Thread, String, String)} but with an
     *  explicit initiator. Used to steer the dev agent at the review gate:
     *  a task parked at {@code AWAITING_REVIEW} is no longer in the
     *  thread's active set, so the active-task-derived overloads
     *  would misroute the turn to the trunk planner — binding the taskId
     *  keeps it on the dev agent. */
    default String enqueueTaskTurn(Thread thread, String input, String taskId, TurnInitiator initiator)
    {
        return enqueueTaskTurn(thread, input, taskId);
    }

    /** As {@link #enqueueTaskTurn(Thread, String, String, TurnInitiator)} but
     *  with the stage pinned explicitly rather than derived from the active-
     *  stage projection. Automation/iteration turns (shipped-CI fix, comment
     *  addressing) use this so the turn is stage-scoped — its detail lands in
     *  {@code stage_messages}, never the thread slice — even when the active-
     *  stage lookup would momentarily miss. Defaulted to the no-stage overload
     *  so test fakes need no change; the production scheduler overrides it. */
    default String enqueueTaskTurn(
            Thread thread, String input, String taskId, String stageId, TurnInitiator initiator)
    {
        return enqueueTaskTurn(thread, input, taskId, initiator);
    }

    /** As the explicit-stage overload, plus an AgentRun correlation id. The
     *  turn still executes on {@code stageId}; {@code agentRunId} identifies
     *  the episode whose completion handler should advance. */
    default String enqueueTaskTurn(
            Thread thread, String input, String taskId, String stageId,
            TurnInitiator initiator, String agentRunId)
    {
        return enqueueTaskTurn(thread, input, taskId, stageId, initiator);
    }

    /** As the run-correlated overload, with the turn's explicit liveness
     *  classification — every automated task-owned enqueue site states
     *  whether the turn is the task's own runtime ({@code CODE}) or
     *  brain/planning/review narration ({@code NARRATION}); it is never
     *  inferred from the shared thread. */
    default String enqueueTaskTurn(
            Thread thread, String input, String taskId, String stageId,
            TurnInitiator initiator, String agentRunId, TurnLiveness liveness)
    {
        return enqueueTaskTurn(thread, input, taskId, stageId, initiator, agentRunId);
    }

    /** Idempotent keyed enqueue: at most one turn ever exists per
     *  {@code kickKey} — a repeat (listener and sweep racing, or a retry
     *  after a crash between claim and launch) returns the existing
     *  turn's id instead of inserting a duplicate. */
    default String enqueueTaskTurnOnce(
            String kickKey, Thread thread, String input, String taskId, String stageId,
            TurnInitiator initiator, String agentRunId, TurnLiveness liveness)
    {
        return enqueueTaskTurn(thread, input, taskId, stageId, initiator, agentRunId, liveness);
    }

    /** Cancel queued turns for one thread and return the number cancelled. */
    int cancelQueuedTurns(String threadId);

    /** Cancel queued turns and interrupt running turns belonging to one Session. */
    default int cancelSessionTurns(String agentRunId)
    {
        return 0;
    }

    /** Cancel every queued/running turn attributed to one exact task. This is
     *  task-scoped (siblings on the same thread are left alone). */
    default int cancelTaskTurns(String taskId)
    {
        return 0;
    }
}
