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
import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkState;
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
        Optional<AgentTurnSettings> settings,
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
        requireNonNull(settings, "settings is null");
        checkOptionalText(terminalReason, "terminalReason");
        requireNonNull(metrics, "metrics is null");
        checkArgument(version >= 0, "version is negative");
        checkArgument(providerTurnReference.isEmpty() || startedAt.isPresent(), "a provider reference requires delivery start time");
        checkArgument(state != TurnState.RUNNING || providerTurnReference.isPresent(), "a running turn requires a provider reference");
        checkArgument(state != TurnState.ACCEPTED || providerTurnReference.isEmpty(), "an accepted turn cannot have a provider reference");
        checkArgument(state.active() != endedAt.isPresent(), "endedAt and turn state disagree");
        checkArgument((state != TurnState.COMPLETED && state.terminal()) == terminalReason.isPresent(), "terminalReason and turn state disagree");
        checkArgument(startedAt.isEmpty() || !startedAt.orElseThrow().isBefore(createdAt), "startedAt is before createdAt");
        checkArgument(endedAt.isEmpty() || !endedAt.orElseThrow().isBefore(startedAt.orElse(createdAt)), "endedAt is before turn start");
        checkArgument(cancellationRequestedAt.isEmpty()
                        || !cancellationRequestedAt.orElseThrow().isBefore(createdAt),
                "cancellationRequestedAt is before createdAt");
    }

    public static AgentTurn accepted(
            TurnId id,
            TaskId taskId,
            String idempotencyKey,
            AgentTurnSettings settings,
            Instant now)
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
                Optional.of(requireNonNull(settings, "settings is null")),
                Optional.empty(),
                TurnMetrics.empty());
    }

    public AgentTurn running(ProviderTurnRef reference, Optional<String> providerModel)
    {
        requireState(TurnState.ACCEPTED);
        requireNonNull(reference, "reference is null");
        checkOptionalText(providerModel, "providerModel");
        checkState(startedAt.isPresent(), "provider delivery has not started");
        checkState(settings.isPresent(), "accepted turn has no settings snapshot");
        checkArgument(
                providerModel.isEmpty() || settings.orElseThrow().model().equals(providerModel),
                "provider model does not match the accepted turn settings");
        return copy(
                TurnState.RUNNING,
                version,
                startedAt,
                Optional.empty(),
                cancellationRequestedAt,
                Optional.of(reference),
                providerModel,
                Optional.empty(),
                metrics);
    }

    public AgentTurn markDeliveryStarted(Instant now)
    {
        requireState(TurnState.ACCEPTED);
        requireNonNull(now, "now is null");
        checkState(startedAt.isEmpty(), "provider delivery already started");
        checkArgument(!now.isBefore(createdAt), "delivery start is before turn acceptance");
        return copy(state, version, Optional.of(now), endedAt, cancellationRequestedAt,
                providerTurnReference, model, terminalReason, metrics);
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
        checkArgument(!now.isBefore(startedAt.orElse(createdAt)), "cancellation time is before turn start");
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
        checkArgument(terminalState.terminal(), "terminalState is not terminal");
        if (state.terminal()) {
            checkState(state == terminalState, "turn is already terminal");
            return this;
        }
        checkState(state != TurnState.ACCEPTED || terminalState != TurnState.COMPLETED, "an accepted turn cannot complete before running");
        checkArgument((terminalState != TurnState.COMPLETED) == reason.isPresent(), "reason and terminalState disagree");
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
                settings,
                updatedTerminalReason,
                updatedMetrics);
    }

    private void requireState(TurnState expected)
    {
        checkState(state == expected, "expected turn state " + expected + " but was " + state);
    }
}
