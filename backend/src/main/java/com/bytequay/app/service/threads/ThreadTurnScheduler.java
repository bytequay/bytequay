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
    /** Queue a trunk-scope turn. Runtime scope is selected by this method,
     *  never inferred from nullable task/stage ids. */
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

    /** Queue an attended task-scope turn. {@code taskId} is required and
     *  the resulting turn intentionally carries no stage. */
    String enqueueTaskTurn(Thread thread, String input, String taskId);

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

    /** Task-scope overload with run correlation and explicit liveness. */
    default String enqueueTaskTurn(
            Thread thread, String input, String taskId, TurnInitiator initiator,
            String agentRunId, TurnLiveness liveness)
    {
        return enqueueTaskTurn(thread, input, taskId, initiator);
    }

    /** Idempotent keyed enqueue for a task-scoped turn. */
    default String enqueueTaskTurnOnce(
            String kickKey, Thread thread, String input, String taskId,
            TurnInitiator initiator, String agentRunId, TurnLiveness liveness)
    {
        return enqueueTaskTurn(thread, input, taskId, initiator, agentRunId, liveness);
    }

    /** Queue a stage-scope turn. Both owning task and stage are required. */
    String enqueueStageTurn(
            Thread thread, String input, String taskId, String stageId, TurnInitiator initiator);

    /** As the explicit-stage overload, plus an AgentRun correlation id. */
    default String enqueueStageTurn(
            Thread thread, String input, String taskId, String stageId,
            TurnInitiator initiator, String agentRunId)
    {
        return enqueueStageTurn(thread, input, taskId, stageId, initiator);
    }

    /** As the run-correlated overload, with the turn's explicit liveness
     *  classification — every automated task-owned enqueue site states
     *  whether the turn is the task's own runtime ({@code CODE}) or
     *  brain/planning/review narration ({@code NARRATION}); it is never
     *  inferred from the shared thread. */
    default String enqueueStageTurn(
            Thread thread, String input, String taskId, String stageId,
            TurnInitiator initiator, String agentRunId, TurnLiveness liveness)
    {
        return enqueueStageTurn(thread, input, taskId, stageId, initiator, agentRunId);
    }

    /** Idempotent keyed enqueue: at most one turn ever exists per
     *  {@code kickKey} — a repeat (listener and sweep racing, or a retry
     *  after a crash between claim and launch) returns the existing
     *  turn's id instead of inserting a duplicate. */
    default String enqueueStageTurnOnce(
            String kickKey, Thread thread, String input, String taskId, String stageId,
            TurnInitiator initiator, String agentRunId, TurnLiveness liveness)
    {
        return enqueueStageTurn(thread, input, taskId, stageId, initiator, agentRunId, liveness);
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
