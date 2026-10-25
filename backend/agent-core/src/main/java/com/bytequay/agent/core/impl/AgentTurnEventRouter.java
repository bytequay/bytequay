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
package com.bytequay.agent.core.impl;

import com.bytequay.agent.core.AgentConflictException;
import com.bytequay.agent.core.AgentEventService;
import com.bytequay.agent.core.AgentMetricsService;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.DevelopmentAutomation;
import com.bytequay.agent.core.DevelopmentTurnOrigin;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.ProviderAgentEventAdapter;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.UnsequencedAgentEvent;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.spi.ProviderAgentEvent;
import com.bytequay.agent.spi.ProviderTurnRef;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.function.Predicate;

import static com.bytequay.agent.core.AgentEventOutboxEntry.pendingInline;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Routes provider callbacks without letting a finished session update a replacement turn. */
final class AgentTurnEventRouter
{
    private final AgentTurnStore turnStore;
    private final AgentEventService eventService;
    private final AgentEventCommitter eventCommitter;
    private final AgentMetricsService metricsService;
    private final AgentRuntimeRegistry runtimeRegistry;
    private final Predicate<TurnId> launchedTurn;
    private final Clock clock;

    AgentTurnEventRouter(
            AgentTurnStore turnStore,
            AgentEventService eventService,
            AgentEventCommitter eventCommitter,
            AgentMetricsService metricsService,
            AgentRuntimeRegistry runtimeRegistry,
            Predicate<TurnId> launchedTurn,
            Clock clock)
    {
        this.turnStore = requireNonNull(turnStore, "turnStore is null");
        this.eventService = requireNonNull(eventService, "eventService is null");
        this.eventCommitter = requireNonNull(eventCommitter, "eventCommitter is null");
        this.metricsService = requireNonNull(metricsService, "metricsService is null");
        this.runtimeRegistry = requireNonNull(runtimeRegistry, "runtimeRegistry is null");
        this.launchedTurn = requireNonNull(launchedTurn, "launchedTurn is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    void route(TaskId taskId, TurnId boundTurnId, ProviderAgentEvent event)
    {
        requireNonNull(event, "event is null");
        if (event instanceof ProviderAgentEvent.Lifecycle lifecycle && stateCoupled(lifecycle.phase())) {
            return;
        }
        if (event instanceof ProviderAgentEvent.Usage usage) {
            synchronized (runtimeRegistry.taskLock(taskId)) {
                AgentTurn turn = requireActiveTurn(taskId);
                if (!turn.id().equals(boundTurnId)) {
                    throw new AgentConflictException("Usage belongs to a stopped or replaced turn: " + boundTurnId);
                }
                recordUsage(taskId, turn, usage);
            }
            return;
        }
        if (event instanceof ProviderAgentEvent.Plan plan
                && plan.source().parentProviderThreadId().isEmpty()
                && plan.source().logicalAgentPath().map(path -> path.equals("/") || path.equals("root")).orElse(true)) {
            synchronized (runtimeRegistry.taskLock(taskId)) {
                AgentTurn turn = requireActiveTurn(taskId);
                checkArgument(turn.id().equals(boundTurnId), "Plan belongs to another turn");
                checkArgument(turn.providerTurnReference().isEmpty() || plan.source().providerTurnId().isEmpty()
                                || plan.source().providerTurnId().equals(turn.providerTurnReference().map(ProviderTurnRef::value)),
                        "Plan belongs to another provider turn");
                ProviderAgentEventAdapter.CanonicalEvent update = ProviderAgentEventAdapter.adapt(plan);
                eventCommitter.commitIfAbsent(
                        pendingInline(EventId.random(), taskId, Optional.of(turn.id()), update.source(), update.payload(), Instant.now(clock)),
                        () -> turnStore.update(turn.withPlan(plan.items())));
            }
            return;
        }
        if (event instanceof ProviderAgentEvent.DevelopmentResult result) {
            synchronized (runtimeRegistry.taskLock(taskId)) {
                AgentTurn turn = requireActiveTurn(taskId);
                if (!turn.id().equals(boundTurnId) || turn.cancellationRequestedAt().isPresent()) {
                    throw new AgentConflictException("Development report belongs to a stopped or replaced turn");
                }
                recordDevelopmentResult(taskId, turn, result);
            }
            return;
        }
        // Streamed display events check the in-memory launch record, not the database, once per event.
        if (!launchedTurn.test(boundTurnId)) {
            throw new AgentConflictException("Task has no active turn: " + taskId);
        }
        eventService.append(new UnsequencedAgentEvent(taskId, Optional.of(boundTurnId), event));
    }

    private void recordDevelopmentResult(TaskId taskId, AgentTurn turn, ProviderAgentEvent.DevelopmentResult result)
    {
        checkArgument(turn.settings().flatMap(AgentTurnSettings::developmentAutomation).filter(DevelopmentAutomation::enabled).isPresent()
                        || turn.developmentOrigin().filter(origin -> origin.kind() == DevelopmentTurnOrigin.Kind.REFINE_SUMMARY).isPresent(),
                "Development reporting is not enabled for this turn");
        checkArgument(result.report().learningSummary().isEmpty()
                        || turn.developmentOrigin().filter(origin -> origin.kind() == DevelopmentTurnOrigin.Kind.REFINE_SUMMARY).isPresent(),
                "Learning summaries require a generated refinement turn");
        checkArgument(result.source().parentProviderThreadId().isEmpty()
                        && result.source().logicalAgentPath().map(path -> path.equals("/") || path.equals("root")).orElse(true),
                "Development report is not from the root agent");
        checkArgument(turn.providerTurnReference().isEmpty()
                        || result.source().providerTurnId().equals(turn.providerTurnReference().map(ProviderTurnRef::value)),
                "Development report belongs to another provider turn");
        String itemId = result.source().providerItemId().orElseThrow(() -> new IllegalArgumentException("Development report has no native tool identity"));
        EventId eventId = EventId.developmentResult(taskId, turn.id(), itemId);
        ProviderAgentEventAdapter.CanonicalEvent event = ProviderAgentEventAdapter.adapt(result);
        eventCommitter.commitIfAbsent(
                pendingInline(eventId, taskId, Optional.of(turn.id()), event.source(), event.payload(), Instant.now(clock)),
                () -> turnStore.update(turn.withDevelopmentResult(result.report())));
    }

    private void recordUsage(TaskId taskId, AgentTurn turn, ProviderAgentEvent.Usage usage)
    {
        EventId eventId = usage.providerEventId()
                .map(providerEventId -> EventId.providerUsage(taskId, turn.id(), providerEventId))
                // Without provider identity, distinct callbacks cannot be durably deduplicated.
                .orElseGet(EventId::random);
        ProviderAgentEventAdapter.CanonicalEvent event = ProviderAgentEventAdapter.adapt(usage);
        Instant occurredAt = Instant.now(clock);
        eventCommitter.commitIfAbsent(
                pendingInline(eventId, taskId, Optional.of(turn.id()), event.source(), event.payload(), occurredAt),
                () -> metricsService.record(taskId, turn.id(), usage.sample()));
    }

    private static boolean stateCoupled(ProviderAgentEvent.LifecyclePhase phase)
    {
        return switch (phase) {
            case SESSION_OPENED, TURN_STARTED, TURN_COMPLETED, INTERRUPTED -> true;
            // Subagent and backfill phases are display-only; they change no task or turn state.
            case RETRYING, RATE_LIMITED, AGENT_STARTED, AGENT_COMPLETED, AGENT_FAILED, BACKFILL_STARTED, BACKFILL_COMPLETED -> false;
        };
    }

    private AgentTurn requireActiveTurn(TaskId taskId)
    {
        return turnStore.findActive(taskId)
                .orElseThrow(() -> new AgentConflictException("Task has no active turn: " + taskId));
    }
}
