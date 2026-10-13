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
package com.bytequay.app.agent;

import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.core.QueueAgentInputCommand;
import com.bytequay.agent.core.QueuedInputId;
import com.bytequay.agent.core.StartAgentTurnCommand;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.google.common.collect.ImmutableList;

import java.util.Optional;

import static java.util.Objects.requireNonNull;

/**
 * How a saved message from outside the task's own composer, such as a review question or a local discussion reply, reaches
 * the task's conversation: a new turn, or the queue behind the running one. Its idempotency key makes a repeated send the
 * same input, and is how the caller learns what accepted a send whose outcome it did not see.
 */
public final class TaskInput
{
    private TaskInput() {}

    public static Accepted send(AgentTurnService turns, TaskId taskId, long expectedVersion, String message, String key, boolean queue, Optional<DevelopmentAutomation> automation)
    {
        if (queue) {
            return new Accepted(Optional.empty(), Optional.of(turns.enqueue(
                    new QueueAgentInputCommand(taskId, expectedVersion, message, ImmutableList.of(), key, automation)).id()));
        }
        return new Accepted(Optional.of(turns.start(
                new StartAgentTurnCommand(taskId, expectedVersion, message, ImmutableList.of(), key).withDevelopmentAutomation(automation)).id()), Optional.empty());
    }

    /** The queue row or turn a key's input became, if anything accepted it. */
    public static Optional<Accepted> accepted(AgentTurnService turns, TaskId taskId, String key)
    {
        return turns.findQueuedByIdempotencyKey(taskId, key).map(input -> new Accepted(Optional.empty(), Optional.of(input.id())))
                .or(() -> turns.findTurnByIdempotencyKey(taskId, key).map(turn -> new Accepted(Optional.of(turn.id()), Optional.empty())));
    }

    public record Accepted(Optional<TurnId> turnId, Optional<QueuedInputId> queuedInputId)
    {
        public Accepted
        {
            requireNonNull(turnId, "turnId is null");
            requireNonNull(queuedInputId, "queuedInputId is null");
        }
    }
}
