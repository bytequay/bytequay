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

import com.bytequay.agent.spi.ProviderTurnRef;

import java.time.Instant;
import java.util.Optional;

import static com.bytequay.agent.spi.AgentChecks.checkNotBlank;
import static com.bytequay.agent.spi.AgentChecks.checkOptionalText;
import static java.util.Objects.requireNonNull;

public record AgentTurn(
        TurnId id,
        TaskId taskId,
        String idempotencyKey,
        TurnState state,
        long version,
        Instant createdAt,
        Optional<Instant> startedAt,
        Optional<Instant> endedAt,
        Optional<Instant> cancellationRequestedAt,
        Optional<ProviderTurnRef> providerTurnReference,
        Optional<String> model,
        Optional<String> terminalReason,
        TurnMetrics metrics)
{
    public AgentTurn
    {
        requireNonNull(id, "id is null");
        requireNonNull(taskId, "taskId is null");
        checkNotBlank(idempotencyKey, "idempotencyKey");
        requireNonNull(state, "state is null");
        requireNonNull(createdAt, "createdAt is null");
        requireNonNull(startedAt, "startedAt is null");
        requireNonNull(endedAt, "endedAt is null");
        requireNonNull(cancellationRequestedAt, "cancellationRequestedAt is null");
        requireNonNull(providerTurnReference, "providerTurnReference is null");
        checkOptionalText(model, "model");
        checkOptionalText(terminalReason, "terminalReason");
        requireNonNull(metrics, "metrics is null");
        if (version < 0) {
            throw new IllegalArgumentException("version is negative");
        }
        if (state == TurnState.RUNNING && (startedAt.isEmpty() || providerTurnReference.isEmpty())) {
            throw new IllegalArgumentException("a running turn requires start time and provider reference");
        }
        if (state.active() == endedAt.isPresent()) {
            throw new IllegalArgumentException("endedAt and turn state disagree");
        }
        if ((state != TurnState.COMPLETED && state.terminal()) != terminalReason.isPresent()) {
            throw new IllegalArgumentException("terminalReason and turn state disagree");
        }
        if (startedAt.isPresent() && startedAt.orElseThrow().isBefore(createdAt)) {
            throw new IllegalArgumentException("startedAt is before createdAt");
        }
        if (endedAt.isPresent() && endedAt.orElseThrow().isBefore(startedAt.orElse(createdAt))) {
            throw new IllegalArgumentException("endedAt is before turn start");
        }
        if (cancellationRequestedAt.isPresent() && cancellationRequestedAt.orElseThrow().isBefore(createdAt)) {
            throw new IllegalArgumentException("cancellationRequestedAt is before createdAt");
        }
    }

    public static AgentTurn accepted(TurnId id, TaskId taskId, String idempotencyKey, Instant now)
    {
        return new AgentTurn(
                id,
                taskId,
                idempotencyKey,
                TurnState.ACCEPTED,
                0,
                now,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                TurnMetrics.empty());
    }

    public AgentTurn running(ProviderTurnRef reference, Optional<String> providerModel, Instant now)
    {
        requireState(TurnState.ACCEPTED);
        requireNonNull(reference, "reference is null");
        checkOptionalText(providerModel, "providerModel");
        return copy(
                TurnState.RUNNING,
                version,
                Optional.of(now),
                Optional.empty(),
                cancellationRequestedAt,
                Optional.of(reference),
                providerModel,
                Optional.empty(),
                metrics);
    }

    public AgentTurn requestCancellation(Instant now)
    {
        requireNonNull(now, "now is null");
        if (!state.active()) {
            return this;
        }
        if (cancellationRequestedAt.isPresent()) {
            return this;
        }
        if (now.isBefore(startedAt.orElse(createdAt))) {
            throw new IllegalArgumentException("cancellation time is before turn start");
        }
        return copy(state, version, startedAt, endedAt, Optional.of(now), providerTurnReference,
                model, terminalReason, metrics);
    }

    public AgentTurn finish(
            TurnState terminalState,
            TurnMetrics finalMetrics,
            Optional<String> reason,
            Instant now)
    {
        requireNonNull(terminalState, "terminalState is null");
        requireNonNull(finalMetrics, "finalMetrics is null");
        checkOptionalText(reason, "reason");
        requireNonNull(now, "now is null");
        if (!terminalState.terminal()) {
            throw new IllegalArgumentException("terminalState is not terminal");
        }
        if (state.terminal()) {
            if (state == terminalState) {
                return this;
            }
            throw new IllegalStateException("turn is already terminal");
        }
        if (state == TurnState.ACCEPTED && terminalState == TurnState.COMPLETED) {
            throw new IllegalStateException("an accepted turn cannot complete before running");
        }
        if ((terminalState != TurnState.COMPLETED) != reason.isPresent()) {
            throw new IllegalArgumentException("reason and terminalState disagree");
        }
        return copy(terminalState, version, startedAt, Optional.of(now), cancellationRequestedAt,
                providerTurnReference, model, reason, finalMetrics);
    }

    public AgentTurn withMetrics(TurnMetrics updatedMetrics)
    {
        requireNonNull(updatedMetrics, "updatedMetrics is null");
        return copy(state, version, startedAt, endedAt, cancellationRequestedAt,
                providerTurnReference, model, terminalReason, updatedMetrics);
    }

    public AgentTurn withVersion(long updatedVersion)
    {
        return copy(state, updatedVersion, startedAt, endedAt, cancellationRequestedAt,
                providerTurnReference, model, terminalReason, metrics);
    }

    private AgentTurn copy(
            TurnState updatedState,
            long updatedVersion,
            Optional<Instant> updatedStartedAt,
            Optional<Instant> updatedEndedAt,
            Optional<Instant> updatedCancellationRequestedAt,
            Optional<ProviderTurnRef> updatedProviderTurnReference,
            Optional<String> updatedModel,
            Optional<String> updatedTerminalReason,
            TurnMetrics updatedMetrics)
    {
        return new AgentTurn(
                id,
                taskId,
                idempotencyKey,
                updatedState,
                updatedVersion,
                createdAt,
                updatedStartedAt,
                updatedEndedAt,
                updatedCancellationRequestedAt,
                updatedProviderTurnReference,
                updatedModel,
                updatedTerminalReason,
                updatedMetrics);
    }

    private void requireState(TurnState expected)
    {
        if (state != expected) {
            throw new IllegalStateException("expected turn state " + expected + " but was " + state);
        }
    }
}
