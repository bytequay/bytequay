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

import com.bytequay.agent.spi.AgentTurnFailureKind;
import com.bytequay.agent.spi.DevelopmentResultReport;
import com.bytequay.agent.spi.ProviderAgentEvent.PlanItem;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.List;
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
        boolean knownUnsent,
        Optional<String> model,
        Optional<AgentTurnSettings> settings,
        Optional<String> terminalReason,
        Optional<AgentTurnFailureKind> failureKind,
        AgentMetrics metrics,
        Optional<DevelopmentResultReport> developmentResult,
        Optional<DevelopmentTurnOrigin> developmentOrigin,
        Optional<List<PlanItem>> plan,
        List<String> guidelines,
        List<TurnMemory> memories)
{
    public AgentTurn(
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
            boolean knownUnsent,
            Optional<String> model,
            Optional<AgentTurnSettings> settings,
            Optional<String> terminalReason,
            Optional<AgentTurnFailureKind> failureKind,
            AgentMetrics metrics)
    {
        this(id, taskId, idempotencyKey, state, version, createdAt, startedAt, endedAt, cancellationRequestedAt,
                providerTurnReference, knownUnsent, model, settings, terminalReason, failureKind, metrics, Optional.empty(), Optional.empty(), Optional.empty(),
                ImmutableList.of(), ImmutableList.of());
    }

    public AgentTurn
    {
        requireNonNull(developmentResult, "developmentResult is null");
        requireNonNull(developmentOrigin, "developmentOrigin is null");
        plan = requireNonNull(plan, "plan is null").map(ImmutableList::copyOf);
        guidelines = ImmutableList.copyOf(guidelines);
        memories = ImmutableList.copyOf(memories);
        checkArgument(developmentOrigin.stream().noneMatch(origin -> origin.parentUserTurnId().equals(id)), "generated turn cannot be its own parent");
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
        requireNonNull(failureKind, "failureKind is null");
        requireNonNull(metrics, "metrics is null");
        checkArgument(version >= 0, "version is negative");
        checkArgument(providerTurnReference.isEmpty() || startedAt.isPresent(), "a provider reference requires delivery start time");
        checkArgument(state != TurnState.RUNNING || providerTurnReference.isPresent(), "a running turn requires a provider reference");
        checkArgument(state != TurnState.ACCEPTED || providerTurnReference.isEmpty(), "an accepted turn cannot have a provider reference");
        checkArgument(!knownUnsent || (state == TurnState.FAILED && providerTurnReference.isEmpty()), "known-unsent delivery requires an unacknowledged failed turn");
        checkArgument(state.active() != endedAt.isPresent(), "endedAt and turn state disagree");
        checkArgument((state != TurnState.COMPLETED && state.terminal()) == terminalReason.isPresent(), "terminalReason and turn state disagree");
        checkArgument(failureKind.isEmpty() || state == TurnState.FAILED, "a typed failure requires a failed turn");
        checkArgument(startedAt.isEmpty() || !startedAt.orElseThrow().isBefore(createdAt), "startedAt is before createdAt");
        checkArgument(endedAt.isEmpty() || !endedAt.orElseThrow().isBefore(startedAt.orElse(createdAt)), "endedAt is before turn start");
        checkArgument(cancellationRequestedAt.isEmpty()
                        || !cancellationRequestedAt.orElseThrow().isBefore(createdAt),
                "cancellationRequestedAt is before createdAt");
    }

    public static AgentTurn accepted(TurnId id, TaskId taskId, String idempotencyKey, AgentTurnSettings settings, Instant now)
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
                false,
                Optional.empty(),
                Optional.of(requireNonNull(settings, "settings is null")),
                Optional.empty(),
                Optional.empty(),
                AgentMetrics.empty());
    }

    public AgentTurn running(ProviderTurnRef reference, Optional<String> providerModel)
    {
        requireState(TurnState.ACCEPTED);
        requireNonNull(reference, "reference is null");
        checkOptionalText(providerModel, "providerModel");
        checkState(startedAt.isPresent(), "provider delivery has not started");
        checkState(settings.isPresent(), "accepted turn has no settings snapshot");
        checkArgument(providerModel.isEmpty() || settings.orElseThrow().model().equals(providerModel), "provider model does not match the accepted turn settings");
        return copy(
                TurnState.RUNNING,
                version,
                startedAt,
                Optional.empty(),
                cancellationRequestedAt,
                Optional.of(reference),
                knownUnsent,
                providerModel,
                Optional.empty(),
                failureKind,
                metrics);
    }

    public AgentTurn markDeliveryStarted(Instant now)
    {
        requireState(TurnState.ACCEPTED);
        requireNonNull(now, "now is null");
        checkState(startedAt.isEmpty(), "provider delivery already started");
        checkArgument(!now.isBefore(createdAt), "delivery start is before turn acceptance");
        return copy(state, version, Optional.of(now), endedAt, cancellationRequestedAt, providerTurnReference, knownUnsent, model, terminalReason, failureKind, metrics);
    }

    /** The repository guideline files and memories that go with this turn's provider input, recorded when its delivery starts. */
    public AgentTurn withReference(List<String> guidelinePaths, List<TurnMemory> deliveredMemories)
    {
        requireState(TurnState.ACCEPTED);
        return new AgentTurn(id, taskId, idempotencyKey, state, version, createdAt, startedAt, endedAt, cancellationRequestedAt,
                providerTurnReference, knownUnsent, model, settings, terminalReason, failureKind, metrics, developmentResult, developmentOrigin, plan, guidelinePaths,
                deliveredMemories);
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
        return copy(state, version, startedAt, endedAt, Optional.of(now), providerTurnReference, knownUnsent, model, terminalReason, failureKind, metrics);
    }

    public AgentTurn finish(TurnState terminalState, AgentMetrics finalMetrics, Optional<String> reason, Instant now)
    {
        return finish(terminalState, finalMetrics, reason, Optional.empty(), now, false);
    }

    public AgentTurn finish(TurnState terminalState, AgentMetrics finalMetrics, Optional<String> reason, Optional<AgentTurnFailureKind> updatedFailureKind, Instant now)
    {
        return finish(terminalState, finalMetrics, reason, updatedFailureKind, now, false);
    }

    /** Finishes an accepted request that provably failed before the provider call. */
    public AgentTurn finishKnownUnsent(AgentMetrics finalMetrics, String reason, Instant now)
    {
        checkNotBlank(reason, "reason");
        return finish(TurnState.FAILED, finalMetrics, Optional.of(reason), Optional.empty(), now, true);
    }

    private AgentTurn finish(
            TurnState terminalState,
            AgentMetrics finalMetrics,
            Optional<String> reason,
            Optional<AgentTurnFailureKind> updatedFailureKind,
            Instant now,
            boolean updatedKnownUnsent)
    {
        requireNonNull(terminalState, "terminalState is null");
        requireNonNull(finalMetrics, "finalMetrics is null");
        checkOptionalText(reason, "reason");
        requireNonNull(updatedFailureKind, "updatedFailureKind is null");
        requireNonNull(now, "now is null");
        checkArgument(terminalState.terminal(), "terminalState is not terminal");
        if (state.terminal()) {
            checkState(state == terminalState, "turn is already terminal");
            return this;
        }
        checkState(state != TurnState.ACCEPTED || terminalState != TurnState.COMPLETED, "an accepted turn cannot complete before running");
        checkArgument((terminalState != TurnState.COMPLETED) == reason.isPresent(), "reason and terminalState disagree");
        checkArgument(updatedFailureKind.isEmpty() || terminalState == TurnState.FAILED, "a typed failure requires a failed turn");
        return copy(terminalState, version, startedAt, Optional.of(now), cancellationRequestedAt,
                providerTurnReference, updatedKnownUnsent, model, reason, updatedFailureKind, finalMetrics);
    }

    public AgentTurn withDevelopmentOrigin(DevelopmentTurnOrigin origin)
    {
        requireNonNull(origin, "origin is null");
        requireState(TurnState.ACCEPTED);
        checkState(developmentOrigin.isEmpty(), "generated turn already has an origin");
        return new AgentTurn(id, taskId, idempotencyKey, state, version, createdAt, startedAt, endedAt, cancellationRequestedAt,
                providerTurnReference, knownUnsent, model, settings, terminalReason, failureKind, metrics, developmentResult, Optional.of(origin), plan, guidelines, memories);
    }

    public AgentTurn withDevelopmentResult(DevelopmentResultReport report)
    {
        requireNonNull(report, "report is null");
        checkState(state.active(), "cannot report development readiness after the turn ended");
        return new AgentTurn(id, taskId, idempotencyKey, state, version, createdAt, startedAt, endedAt, cancellationRequestedAt,
                providerTurnReference, knownUnsent, model, settings, terminalReason, failureKind, metrics, Optional.of(report), developmentOrigin, plan, guidelines, memories);
    }

    public AgentTurn withPlan(List<PlanItem> items)
    {
        checkState(state.active(), "cannot update the plan after the turn ended");
        return new AgentTurn(id, taskId, idempotencyKey, state, version, createdAt, startedAt, endedAt, cancellationRequestedAt,
                providerTurnReference, knownUnsent, model, settings, terminalReason, failureKind, metrics, developmentResult, developmentOrigin, Optional.of(items), guidelines,
                memories);
    }

    public AgentTurn withoutLearningSummary()
    {
        checkState(state.terminal(), "cannot discard a summary while its turn is active");
        if (developmentResult.flatMap(DevelopmentResultReport::learningSummary).isEmpty()) {
            return this;
        }
        DevelopmentResultReport report = developmentResult.orElseThrow();
        return new AgentTurn(id, taskId, idempotencyKey, state, version, createdAt, startedAt, endedAt, cancellationRequestedAt,
                providerTurnReference, knownUnsent, model, settings, terminalReason, failureKind, metrics,
                Optional.of(new DevelopmentResultReport(report.readyForDelivery(), report.message())), developmentOrigin, plan, guidelines, memories);
    }

    public AgentTurn withMetrics(AgentMetrics updatedMetrics)
    {
        requireNonNull(updatedMetrics, "updatedMetrics is null");
        return copy(state, version, startedAt, endedAt, cancellationRequestedAt, providerTurnReference, knownUnsent, model, terminalReason, failureKind, updatedMetrics);
    }

    public AgentTurn withVersion(long updatedVersion)
    {
        return copy(state, updatedVersion, startedAt, endedAt, cancellationRequestedAt, providerTurnReference, knownUnsent, model, terminalReason, failureKind, metrics);
    }

    private AgentTurn copy(
            TurnState updatedState,
            long updatedVersion,
            Optional<Instant> updatedStartedAt,
            Optional<Instant> updatedEndedAt,
            Optional<Instant> updatedCancellationRequestedAt,
            Optional<ProviderTurnRef> updatedProviderTurnReference,
            boolean updatedKnownUnsent,
            Optional<String> updatedModel,
            Optional<String> updatedTerminalReason,
            Optional<AgentTurnFailureKind> updatedFailureKind,
            AgentMetrics updatedMetrics)
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
                updatedKnownUnsent,
                updatedModel,
                settings,
                updatedTerminalReason,
                updatedFailureKind,
                updatedMetrics,
                developmentResult,
                developmentOrigin,
                plan,
                guidelines,
                memories);
    }

    private void requireState(TurnState expected)
    {
        checkState(state == expected, "expected turn state " + expected + " but was " + state);
    }
}
