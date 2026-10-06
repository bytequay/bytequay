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

import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentEventSource;
import com.bytequay.agent.core.AgentMetrics;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnSettings;
import com.bytequay.agent.core.EventId;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.ToolCounters;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.ProviderAgentEvent.ActivityState;
import com.bytequay.agent.spi.ProviderAgentEvent.CommandAction;
import com.bytequay.agent.spi.ProviderAgentEvent.CommandActionKind;
import com.bytequay.agent.spi.ProviderTurnRef;
import com.bytequay.agent.spi.ProviderUsageSample;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class TestDefaultAgentMetricsService
{
    private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");

    private InMemoryAgentStores stores;
    private final InMemoryTaskLogStore logStore = new InMemoryTaskLogStore();
    private MutableClock clock;
    private DefaultAgentMetricsService service;
    private AgentTask task;

    @BeforeEach
    void setUp()
    {
        stores = new InMemoryAgentStores();
        clock = new MutableClock(NOW);
        service = new DefaultAgentMetricsService(stores, stores, logStore, clock);
        task = AgentTask.created(
                TaskId.random(),
                Optional.empty(),
                false,
                "Task",
                AgentLaneId.CODEX_CLI,
                new RepositoryIdentity("bytequay", "sample"),
                Path.of("/tmp/worktree"),
                AgentPermissionPolicy.AUTO,
                NOW);
        stores.insert(task);
    }

    @Test
    void derivesCumulativeUsageFromDurableBaselineAfterServiceRestart()
    {
        AgentMetrics previousMetrics = metrics(100, 10, 20, "2.00");
        AgentTurn previous = AgentTurn.accepted(TurnId.random(), task.id(), "previous", turnSettings(), NOW)
                .markDeliveryStarted(NOW)
                .running(new ProviderTurnRef("previous"), Optional.empty())
                .finish(TurnState.COMPLETED, previousMetrics, Optional.empty(), NOW.plusSeconds(1));
        stores.insert(previous);
        stores.put(task.withMetrics(
                new AgentMetrics(100, 10, 20, previousMetrics.cost(), Duration.ZERO),
                NOW));
        AgentTurn current = AgentTurn.accepted(TurnId.random(), task.id(), "current", turnSettings(), NOW.plusSeconds(2));
        stores.insert(current);

        AgentMetrics first = service.record(task.id(), current.id(), cumulative("usage-1", 130, 12, 25, "2.50"));
        service = new DefaultAgentMetricsService(stores, stores, logStore, clock);
        AgentMetrics duplicate = service.record(task.id(), current.id(), cumulative("usage-1", 130, 12, 25, "2.50"));
        AgentMetrics latest = service.record(task.id(), current.id(), cumulative("usage-2", 150, 15, 30, "2.75"));

        assertThat(first).isEqualTo(metrics(30, 2, 5, "0.50"));
        assertThat(duplicate).isEqualTo(first);
        assertThat(latest).isEqualTo(metrics(50, 5, 10, "0.75"));
        assertThat(service.taskTotals(task.id())).isEqualTo(new AgentMetrics(150, 15, 30, Optional.of(new BigDecimal("2.75")), Duration.ZERO));
    }

    @Test
    void keepsResumedInvocationUsageWithoutSubtractingEarlierProcessCounters()
    {
        AgentMetrics previousMetrics = new AgentMetrics(59_857, 24_960, 234, Optional.empty(), Duration.ZERO);
        AgentTurn previous = AgentTurn.accepted(TurnId.random(), task.id(), "previous", turnSettings(), NOW)
                .markDeliveryStarted(NOW)
                .running(new ProviderTurnRef("previous"), Optional.empty())
                .finish(TurnState.COMPLETED, previousMetrics, Optional.empty(), NOW.plusSeconds(1));
        stores.insert(previous);
        AgentTurn current = AgentTurn.accepted(TurnId.random(), task.id(), "resumed", turnSettings(), NOW.plusSeconds(2));
        stores.insert(current);

        AgentMetrics first = service.record(task.id(), current.id(), invocationUsage("usage-1", 20_759, 20_224, 73));
        service.record(task.id(), current.id(), invocationUsage("usage-2", 42_682, 40_832, 179));
        AgentMetrics latest = service.record(task.id(), current.id(), invocationUsage("usage-3", 64_844, 62_592, 252));
        service = new DefaultAgentMetricsService(stores, stores, logStore, clock);
        AgentMetrics duplicate = service.record(task.id(), current.id(), invocationUsage("usage-3", 64_844, 62_592, 252));
        AgentMetrics older = service.record(task.id(), current.id(), invocationUsage("usage-2", 42_682, 40_832, 179));

        assertThat(first).isEqualTo(new AgentMetrics(20_759, 20_224, 73, Optional.empty(), Duration.ZERO));
        assertThat(latest).isEqualTo(new AgentMetrics(64_844, 62_592, 252, Optional.empty(), Duration.ZERO));
        assertThat(duplicate).isEqualTo(latest);
        assertThat(older).isEqualTo(latest);
        assertThat(service.taskTotals(task.id())).isEqualTo(new AgentMetrics(124_701, 87_552, 486, Optional.empty(), Duration.ZERO));
    }

    @Test
    void replacesInvocationCostWithoutAddingRepeatedTotals()
    {
        AgentTurn turn = AgentTurn.accepted(TurnId.random(), task.id(), "request", turnSettings(), NOW);
        stores.insert(turn);
        service.record(task.id(), turn.id(), sample("first", ProviderUsageSample.Semantics.CUMULATIVE_FOR_INVOCATION, 100, 90, 10, "1.00"));

        AgentMetrics latest = service.record(task.id(), turn.id(), sample("latest", ProviderUsageSample.Semantics.CUMULATIVE_FOR_INVOCATION, 150, 120, 30, "1.75"));

        assertThat(latest).isEqualTo(metrics(150, 120, 30, "1.75"));
    }

    @Test
    void addsDeltaAndFinalInvocationUsage()
    {
        AgentTurn turn = AgentTurn.accepted(TurnId.random(), task.id(), "request", turnSettings(), NOW);
        stores.insert(turn);

        service.record(task.id(), turn.id(), sample(
                "delta",
                ProviderUsageSample.Semantics.DELTA,
                4,
                1,
                2,
                "0.10"));
        AgentMetrics result = service.record(task.id(), turn.id(), sample(
                "final",
                ProviderUsageSample.Semantics.FINAL_FOR_INVOCATION,
                6,
                0,
                3,
                "0.20"));

        assertThat(result).isEqualTo(metrics(10, 1, 5, "0.30"));
    }

    @Test
    void finalizesElapsedTimeAndPersistsTaskTotals()
    {
        AgentTurn turn = AgentTurn.accepted(TurnId.random(), task.id(), "request", turnSettings(), NOW)
                .markDeliveryStarted(NOW)
                .running(new ProviderTurnRef("provider-turn"), Optional.empty());
        stores.insert(turn);
        clock.advance(Duration.ofSeconds(5));

        AgentMetrics result = service.finishTurn(turn.id(), TurnState.COMPLETED);

        assertThat(result.elapsed()).isEqualTo(Duration.ofSeconds(5));
        assertThat(stores.find(task.id()).orElseThrow().metrics().elapsed()).isEqualTo(Duration.ofSeconds(5));
    }

    @Test
    void foldsPersistedCountersAcrossPagesWithoutCountingOtherTurns()
    {
        AgentTurn turn = AgentTurn.accepted(TurnId.random(), task.id(), "request", turnSettings(), NOW);
        stores.insert(turn);
        TurnId otherTurn = TurnId.random();
        for (int sequence = 1; sequence <= 24; sequence++) {
            logStore.append(event(sequence, sequence % 2 == 0 ? turn.id() : otherTurn,
                    new AgentEventPayload.ToolActivity("Read", ActivityState.COMPLETED, "read", Optional.empty())));
        }

        AgentMetrics result = service.finishTurn(turn.id(), TurnState.COMPLETED);

        assertThat(result.counters()).isEqualTo(new ToolCounters(12, 12));
    }

    private static AgentTurnSettings turnSettings()
    {
        return new AgentTurnSettings(
                Optional.empty(),
                Optional.empty(),
                AgentPermissionPolicy.AUTO,
                Duration.ofMinutes(5),
                ImmutableList.of());
    }

    private static ProviderUsageSample cumulative(
            String eventId,
            long inputTokens,
            long cachedInputTokens,
            long outputTokens,
            String cost)
    {
        return sample(
                eventId,
                ProviderUsageSample.Semantics.CUMULATIVE,
                inputTokens,
                cachedInputTokens,
                outputTokens,
                cost);
    }

    private static ProviderUsageSample invocationUsage(String eventId, long inputTokens, long cachedInputTokens, long outputTokens)
    {
        return new ProviderUsageSample(
                Optional.of(eventId),
                ProviderUsageSample.Semantics.CUMULATIVE_FOR_INVOCATION,
                inputTokens,
                cachedInputTokens,
                outputTokens,
                Optional.empty());
    }

    private static ProviderUsageSample sample(
            String eventId,
            ProviderUsageSample.Semantics semantics,
            long inputTokens,
            long cachedInputTokens,
            long outputTokens,
            String cost)
    {
        return new ProviderUsageSample(
                Optional.of(eventId),
                semantics,
                inputTokens,
                cachedInputTokens,
                outputTokens,
                Optional.of(new BigDecimal(cost)));
    }

    private static AgentMetrics metrics(long inputTokens, long cachedInputTokens, long outputTokens, String cost)
    {
        return new AgentMetrics(
                inputTokens,
                cachedInputTokens,
                outputTokens,
                Optional.of(new BigDecimal(cost)),
                Duration.ZERO);
    }

    @Test
    void foldsToolCountersFromTheTurnsPersistedEventsOnly()
    {
        TurnId turn = TurnId.random();
        TurnId other = TurnId.random();
        List<AgentEventEnvelope> events = ImmutableList.of(
                event(1, turn, new AgentEventPayload.ToolActivity("Read", ActivityState.STARTED, "reading", Optional.empty())),
                event(2, turn, new AgentEventPayload.ToolActivity("Read", ActivityState.COMPLETED, "read", Optional.empty())),
                event(3, turn, new AgentEventPayload.ToolActivity("mcp__bytequay__code_query", ActivityState.COMPLETED, "query", Optional.of(1200L))),
                event(4, turn, new AgentEventPayload.ToolActivity("bytequay/code_query", ActivityState.FAILED, "query", Optional.of(300L))),
                event(5, turn, new AgentEventPayload.CommandActivity(
                        ActivityState.COMPLETED,
                        ImmutableList.of("rg", "cleanup"),
                        Optional.empty(),
                        Optional.of(0),
                        false,
                        Optional.empty(),
                        Optional.of(40L),
                        ImmutableList.of(
                                new CommandAction(CommandActionKind.SEARCH, Optional.empty(), Optional.empty(), Optional.of("cleanup")),
                                new CommandAction(CommandActionKind.OTHER, Optional.empty(), Optional.empty(), Optional.empty())),
                        false,
                        Optional.empty())),
                event(6, other, new AgentEventPayload.ToolActivity("Grep", ActivityState.COMPLETED, "elsewhere", Optional.empty())));

        ToolCounters counters = DefaultAgentMetricsService.foldCounters(events, turn);

        assertThat(counters).isEqualTo(new ToolCounters(4, 4));
    }

    private AgentEventEnvelope event(long sequence, TurnId turnId, AgentEventPayload payload)
    {
        return new AgentEventEnvelope(
                AgentEventEnvelope.CURRENT_SCHEMA_VERSION,
                EventId.random(),
                sequence,
                task.id(),
                Optional.of(turnId),
                AgentEventSource.root(),
                NOW,
                payload);
    }

    private static final class MutableClock
            extends Clock
    {
        private Instant now;

        private MutableClock(Instant now)
        {
            this.now = now;
        }

        @Override
        public ZoneId getZone()
        {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone)
        {
            return this;
        }

        @Override
        public Instant instant()
        {
            return now;
        }

        private void advance(Duration duration)
        {
            now = now.plus(duration);
        }
    }
}
