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
package com.bytequay.agent.core;

import java.time.Instant;
import java.util.Optional;

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;
import static com.bytequay.agent.spi.AgentChecks.checkOptionalText;
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
import static java.util.Objects.requireNonNull;

/**
 * The durable record of one steer: input added to a turn already running.
 *
 * <p>Steering has no idempotency key on the provider side, so the outcome of a send that got no
 * answer is {@link State#UNKNOWN} and stays that way. Nothing resends it.
 */
public record AgentInputDelivery(
        InputDeliveryId id,
        TaskId taskId,
        TurnId turnId,
        String idempotencyKey,
        State state,
        Instant createdAt,
        Instant updatedAt,
        Optional<String> failure,
        long version)
{
    public AgentInputDelivery
    {
        requireNonNull(id, "id is null");
        requireNonNull(taskId, "taskId is null");
        requireNonNull(turnId, "turnId is null");
        checkNotBlank(idempotencyKey, "idempotencyKey");
        requireNonNull(state, "state is null");
        requireNonNull(createdAt, "createdAt is null");
        requireNonNull(updatedAt, "updatedAt is null");
        checkOptionalText(failure, "failure");
        checkArgument(version >= 0, "version is negative");
        checkArgument(!updatedAt.isBefore(createdAt), "updatedAt is before createdAt");
        checkArgument((state == State.FAILED || state == State.UNKNOWN) == failure.isPresent(), "failure and state disagree");
    }

    public static AgentInputDelivery accepted(InputDeliveryId id, TaskId taskId, TurnId turnId, String idempotencyKey, Instant now)
    {
        return new AgentInputDelivery(id, taskId, turnId, idempotencyKey, State.ACCEPTED, now, now, Optional.empty(), 0);
    }

    public AgentInputDelivery sending(Instant now)
    {
        requireState(State.ACCEPTED);
        return copy(State.SENDING, Optional.empty(), now);
    }

    public AgentInputDelivery delivered(Instant now)
    {
        requireState(State.SENDING);
        return copy(State.DELIVERED, Optional.empty(), now);
    }

    /** The provider definitely did not take the input. */
    public AgentInputDelivery failed(String reason, Instant now)
    {
        checkNotBlank(reason, "reason");
        checkState(state == State.ACCEPTED || state == State.SENDING, "delivery is already settled: " + state);
        return copy(State.FAILED, Optional.of(reason), now);
    }

    /** The input was sent and nothing says whether it arrived; it is never sent again. */
    public AgentInputDelivery unknown(String reason, Instant now)
    {
        checkNotBlank(reason, "reason");
        requireState(State.SENDING);
        return copy(State.UNKNOWN, Optional.of(reason), now);
    }

    public AgentInputDelivery withVersion(long updatedVersion)
    {
        return new AgentInputDelivery(id, taskId, turnId, idempotencyKey, state, createdAt, updatedAt, failure, updatedVersion);
    }

    private AgentInputDelivery copy(State updatedState, Optional<String> updatedFailure, Instant now)
    {
        requireNonNull(now, "now is null");
        return new AgentInputDelivery(id, taskId, turnId, idempotencyKey, updatedState, createdAt, now, updatedFailure, version);
    }

    private void requireState(State expected)
    {
        checkState(state == expected, "expected delivery state " + expected + " but was " + state);
    }

    public enum State
    {
        ACCEPTED,
        SENDING,
        DELIVERED,
        FAILED,
        UNKNOWN;

        public boolean settled()
        {
            return this == DELIVERED || this == FAILED || this == UNKNOWN;
        }
    }
}
