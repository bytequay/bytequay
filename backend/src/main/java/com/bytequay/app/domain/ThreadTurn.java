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
 * <p>{@link #scope} is authoritative. IDs are validated payload for that
 * declared scope; callers must never infer scope from their nullability.
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
    public ThreadTurn
    {
        if (scope == null) {
            throw new IllegalArgumentException("thread turn scope is null");
        }
        boolean hasTask = taskId != null && !taskId.isBlank();
        boolean hasStage = stageId != null && !stageId.isBlank();
        switch (scope) {
            case TRUNK -> {
                if (hasTask || hasStage) {
                    throw new IllegalArgumentException("TRUNK turn cannot carry taskId or stageId");
                }
            }
            case TASK -> {
                if (!hasTask || hasStage) {
                    throw new IllegalArgumentException("TASK turn requires taskId and forbids stageId");
                }
            }
            case STAGE -> {
                if (!hasTask || !hasStage) {
                    throw new IllegalArgumentException("STAGE turn requires taskId and stageId");
                }
            }
        }
    }

    /** Read a task identity only after proving this is task-owned work. */
    public String requireTaskId()
    {
        if (scope == ThreadScope.TRUNK) {
            throw new IllegalStateException("TRUNK turn has no taskId");
        }
        return taskId;
    }

    /** Read a stage identity only after proving this is stage-scoped work. */
    public String requireStageId()
    {
        if (scope != ThreadScope.STAGE) {
            throw new IllegalStateException(scope + " turn has no stageId");
        }
        return stageId;
    }

    /** Runtime identity: one trunk agent per thread, one dev agent per Task. */
    public String runtimeAgentKey()
    {
        return scope == ThreadScope.TRUNK ? threadId : requireTaskId();
    }

    /** Convenience constructor for a turn with no AgentRun correlation. */
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
}
