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

    /** Queue a task-scope (attended) turn bound to an explicit {@code
     *  taskId}, bypassing the active-task projection the no-id overloads
     *  derive. The task-altitude composer uses this so a turn lands on
     *  its task's conversation slice even when that task isn't in the
     *  narrow "active" set — parked at {@code AWAITING_REVIEW}, {@code
     *  NEEDS_ATTENTION}, or with a {@code COMPLETED} dev-lifecycle phase.
     *  In those states {@code Thread.activeTask()} is null, which used to
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
     *  a task parked at {@code AWAITING_REVIEW} has a null
     *  {@link Thread#activeTask()}, so the active-task-derived overloads
     *  would misroute the turn to the trunk planner — binding the taskId
     *  keeps it on the dev agent. */
    default String enqueueTaskTurn(Thread thread, String input, String taskId, TurnInitiator initiator)
    {
        return enqueueTaskTurn(thread, input, taskId);
    }

    /** Cancel queued turns for one thread and return the number cancelled. */
    int cancelQueuedTurns(String threadId);
}
