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

import java.time.Instant;

/**
 * One queued or running user input for a thread.
 *
 * <p>A thread can be idle while its next turn is waiting for scheduler
 * capacity. Keeping that state here avoids overloading
 * {@link ThreadStatus} with queue details.
 *
 * <p>{@code taskId} is the focused Task when the turn was enqueued;
 * {@code null} marks a trunk planning turn (no task focused, no
 * worktree). The agent scheduler routes the turn to the matching
 * Task's session, or to the Thread trunk session if {@code taskId}
 * is {@code null}.
 *
 * <p>{@code initiator} records who set the turn in motion — a human
 * (attended) or an automated trigger (unattended). The tool-approval
 * gate reads it to decide whether a permission prompt makes sense.
 */
public record ThreadTurn(
        String id,
        String threadId,
        String taskId,
        ThreadResourceLane lane,
        ThreadTurnStatus status,
        String input,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant finishedAt,
        String errorMessage,
        TurnInitiator initiator,
        /** Owning stage when the turn is stage-scoped; null for task- or
         *  trunk-level turns. */
        String stageId,
        /** Explicit TRUNK | TASK | STAGE discriminator (see {@link ThreadScope}). */
        ThreadScope scope,
        /** Optional AgentRun episode this turn belongs to; stageId remains
         *  the execution/session stage. */
        String agentRunId)
{
    /** Constructor for stage-scoped rows that predate run correlation. */
    public ThreadTurn(
            String id,
            String threadId,
            String taskId,
            ThreadResourceLane lane,
            ThreadTurnStatus status,
            String input,
            Instant createdAt,
            Instant updatedAt,
            Instant startedAt,
            Instant finishedAt,
            String errorMessage,
            TurnInitiator initiator,
            String stageId,
            ThreadScope scope)
    {
        this(id, threadId, taskId, lane, status, input, createdAt, updatedAt,
                startedAt, finishedAt, errorMessage, initiator, stageId, scope,
                /* agentRunId */ null);
    }

    /** Legacy constructor for callers (and rows) that predate the explicit
     *  scope/stage_id fields — derives the scope from the ids and leaves the
     *  stage null. New enqueue paths use the full constructor. */
    public ThreadTurn(
            String id,
            String threadId,
            String taskId,
            ThreadResourceLane lane,
            ThreadTurnStatus status,
            String input,
            Instant createdAt,
            Instant updatedAt,
            Instant startedAt,
            Instant finishedAt,
            String errorMessage,
            TurnInitiator initiator)
    {
        this(id, threadId, taskId, lane, status, input, createdAt, updatedAt,
                startedAt, finishedAt, errorMessage, initiator,
                /* stageId */ null, ThreadScope.of(taskId, null),
                /* agentRunId */ null);
    }
}
