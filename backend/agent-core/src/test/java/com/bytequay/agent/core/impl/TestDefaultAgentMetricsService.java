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
import java.util.OptionalLong;

import static com.bytequay.agent.core.AgentTaskFixtures.created;
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
        task = created(
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
        stores.put(task.withMetrics(new AgentMetrics(100, 10, 20, previousMetrics.cost(), Duration.ZERO), NOW));
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

        service.record(task.id(), turn.id(), sample("delta", ProviderUsageSample.Semantics.DELTA, 4, 1, 2, "0.10"));
        AgentMetrics result = service.record(task.id(), turn.id(), sample("final", ProviderUsageSample.Semantics.FINAL_FOR_INVOCATION, 6, 0, 3, "0.20"));

        assertThat(result).isEqualTo(metrics(10, 1, 5, "0.30"));
    }

    @Test
    void givesEachTurnWhatTheSessionsCostGrewBy()
    {
        AgentTurn first = turn("first", sessionCost("result-1", 17_539_675, 17_206_181, 121_018, "14.9625905"));
        service = new DefaultAgentMetricsService(stores, stores, logStore, clock);
        AgentTurn second = turn("second", sessionCost("result-2", 24_883_521, 24_722_478, 69_752, "30.6774495"));
        // Every result of one process carries the total on, so the latest one counts, while each result's tokens are its own.
        AgentTurn third = turn("third", sessionCost("result-3", 2_005_549, 1_994_258, 8_748, "32.0061485"), sessionCost("result-4", 1_000, 900, 10, "32.5061485"));

        assertThat(first.metrics()).isEqualTo(metrics(17_539_675, 17_206_181, 121_018, "14.9625905"));
        assertThat(second.metrics()).isEqualTo(metrics(24_883_521, 24_722_478, 69_752, "15.714859"));
        assertThat(third.metrics()).isEqualTo(metrics(2_006_549, 1_995_158, 8_758, "1.828699"));
        assertThat(service.taskTotals(task.id())).isEqualTo(metrics(44_429_745, 43_923_817, 199_528, "32.5061485"));
    }

    @Test
    void givesWhatAnUnreportedTurnSpentToTheNextTurnThatReports()
    {
        turn("first", sessionCost("result-1", 100, 90, 10, "14.9625905"));
        AgentTurn stopped = turn("stopped");
        AgentTurn next = turn("next", sessionCost("result-2", 200, 180, 20, "30.6774495"));

        assertThat(stopped.metrics().cost()).isEmpty();
        assertThat(next.metrics()).isEqualTo(metrics(200, 180, 20, "15.714859"));
        // The provider did not report every turn, so what the task cost in all is not said.
        assertThat(service.taskTotals(task.id()).cost()).isEmpty();
    }

    @Test
    void readsASessionCostThatDidNotMoveAsNoCostAndALowerOneAsUnknown()
    {
        // 24.4694136 is what SQLite hands back for a reported 24.469413599999996.
        turn("first", sessionCost("result-1", 100, 90, 10, "24.4694136"));
        AgentTurn unmoved = turn("unmoved", sessionCost("result-2", 0, 0, 0, "24.469413599999996"));
        AgentTurn restarted = turn("restarted", sessionCost("result-3", 300, 270, 30, "6.2092988"));
        AgentTurn caughtUp = turn("caught-up", sessionCost("result-4", 400, 360, 40, "30.4694136"));
        AgentTurn unpriced = turn("unpriced", new ProviderUsageSample(Optional.of("result-5"), ProviderUsageSample.Semantics.FINAL_WITH_SESSION_COST, 5, 4, 1, Optional.empty()));

        assertThat(unmoved.metrics()).isEqualTo(metrics(0, 0, 0, "0"));
        assertThat(restarted.metrics()).isEqualTo(new AgentMetrics(300, 270, 30, Optional.empty(), Duration.ZERO));
        assertThat(caughtUp.metrics()).isEqualTo(metrics(400, 360, 40, "6"));
        assertThat(unpriced.metrics().cost()).isEmpty();
    }

    @Test
    void countsASessionFromNothingAfterAnotherProviderRan()
    {
        // Stored before turns recorded their provider: it ran on the task's first one, like the turn after it.
        turn("unrecorded", sessionCost("result-1", 100, 90, 10, "3"));
        AgentTurn first = turn("first", AgentLaneId.CLAUDE_CLI, sessionCost("result-2", 100, 90, 10, "5"));
        turn("codex", AgentLaneId.CODEX_CLI);
        // A change of provider drops the task's session, so the one opened on the way back began its total at nothing.
        AgentTurn back = turn("back", AgentLaneId.CLAUDE_CLI, sessionCost("result-3", 200, 180, 20, "2"));
        AgentTurn again = turn("again", AgentLaneId.CLAUDE_CLI, sessionCost("result-4", 300, 270, 30, "6"));
        AgentTurn account = turn("account", new AgentLaneId("claude-account-work"), sessionCost("result-5", 400, 360, 40, "1.5"));

        assertThat(first.metrics()).isEqualTo(metrics(100, 90, 10, "2"));
        assertThat(back.metrics()).isEqualTo(metrics(200, 180, 20, "2"));
        assertThat(again.metrics()).isEqualTo(metrics(300, 270, 30, "4"));
        assertThat(account.metrics()).isEqualTo(metrics(400, 360, 40, "1.5"));
    }

    @Test
    void finalizesElapsedTimeAndPersistsTaskTotals()
    {
        AgentTurn turn = AgentTurn.accepted(TurnId.random(), task.id(), "request", turnSettings(), NOW)
                .markDeliveryStarted(NOW)
                .running(new ProviderTurnRef("provider-turn"), Optional.empty());
        stores.insert(turn);
        clock.advance(Duration.ofSeconds(5));

        AgentMetrics result = service.finishTurn(turn.id(), TurnState.COMPLETED, ToolCounters.ZERO);

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
                    new AgentEventPayload.ToolActivity("Read", ActivityState.COMPLETED, "read", Optional.empty(), Optional.empty())));
        }

        AgentMetrics result = service.finishTurn(turn.id(), TurnState.COMPLETED, service.countTools(task.id(), turn.id(), OptionalLong.empty()));

        assertThat(result.counters()).isEqualTo(new ToolCounters(12, 12, 0));
    }

    @Test
    void countsOnlyBackToTheTurnsFirstEvent()
    {
        TurnId turn = TurnId.random();
        for (int sequence = 1; sequence <= 30; sequence++) {
            // The turn's own events from sequence 21 on; an earlier stray event of the same id must be ignored once the
            // caller names where the turn began.
            logStore.append(event(sequence, sequence >= 21 || sequence == 3 ? turn : TurnId.random(),
                    new AgentEventPayload.ToolActivity("Read", ActivityState.COMPLETED, "read", Optional.empty(), Optional.empty())));
        }

        assertThat(service.countTools(task.id(), turn, OptionalLong.of(21))).isEqualTo(new ToolCounters(10, 10, 0));
        assertThat(service.countTools(task.id(), turn, OptionalLong.empty())).isEqualTo(new ToolCounters(11, 11, 0));
    }

    private static AgentTurnSettings turnSettings()
    {
        return new AgentTurnSettings(Optional.empty(), Optional.empty(), AgentPermissionPolicy.AUTO, Duration.ofMinutes(5), ImmutableList.of(), Optional.empty(), Optional.empty());
    }

    private static ProviderUsageSample cumulative(String eventId, long inputTokens, long cachedInputTokens, long outputTokens, String cost)
    {
        return sample(eventId, ProviderUsageSample.Semantics.CUMULATIVE, inputTokens, cachedInputTokens, outputTokens, cost);
    }

    private static ProviderUsageSample sessionCost(String eventId, long inputTokens, long cachedInputTokens, long outputTokens, String cost)
    {
        return sample(eventId, ProviderUsageSample.Semantics.FINAL_WITH_SESSION_COST, inputTokens, cachedInputTokens, outputTokens, cost);
    }

    /** A turn of the task that reported these samples and then ended. */
    private AgentTurn turn(String key, ProviderUsageSample... samples)
    {
        return turn(key, turnSettings(), samples);
    }

    private AgentTurn turn(String key, AgentLaneId lane, ProviderUsageSample... samples)
    {
        return turn(key, turnSettings().withLaneId(lane), samples);
    }

    private AgentTurn turn(String key, AgentTurnSettings settings, ProviderUsageSample... samples)
    {
        // The store lists a task's turns by when they were accepted, so each one gets its own moment.
        clock.advance(Duration.ofSeconds(1));
        Instant now = clock.instant();
        AgentTurn started = AgentTurn.accepted(TurnId.random(), task.id(), key, settings, now).markDeliveryStarted(now).running(new ProviderTurnRef(key), Optional.empty());
        stores.insert(started);
        for (ProviderUsageSample sample : samples) {
            service.record(task.id(), started.id(), sample);
        }
        AgentTurn reported = stores.require(started.id());
        return stores.update(reported.finish(TurnState.COMPLETED, reported.metrics(), Optional.empty(), now));
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

    private static ProviderUsageSample sample(String eventId, ProviderUsageSample.Semantics semantics, long inputTokens, long cachedInputTokens, long outputTokens, String cost)
    {
        return new ProviderUsageSample(Optional.of(eventId), semantics, inputTokens, cachedInputTokens, outputTokens, Optional.of(new BigDecimal(cost)));
    }

    private static AgentMetrics metrics(long inputTokens, long cachedInputTokens, long outputTokens, String cost)
    {
        return new AgentMetrics(inputTokens, cachedInputTokens, outputTokens, Optional.of(new BigDecimal(cost)), Duration.ZERO);
    }

    @Test
    void foldsToolCountersFromTheTurnsPersistedEventsOnly()
    {
        TurnId turn = TurnId.random();
        TurnId other = TurnId.random();
        List<AgentEventEnvelope> events = ImmutableList.of(
                event(1, turn, new AgentEventPayload.ToolActivity("Read", ActivityState.STARTED, "reading", Optional.empty(), Optional.empty())),
                event(2, turn, new AgentEventPayload.ToolActivity("Read", ActivityState.COMPLETED, "read", Optional.empty(), Optional.empty())),
                event(3, turn, new AgentEventPayload.ToolActivity("mcp__bytequay__code_query", ActivityState.COMPLETED, "query", Optional.of(1200L), Optional.empty())),
                event(4, turn, new AgentEventPayload.ToolActivity("bytequay/code_query", ActivityState.FAILED, "query", Optional.of(300L), Optional.empty())),
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
                event(6, other, new AgentEventPayload.ToolActivity("Grep", ActivityState.COMPLETED, "elsewhere", Optional.empty(), Optional.empty())));

        ToolCounters counters = DefaultAgentMetricsService.foldCounters(events, turn);

        assertThat(counters).isEqualTo(new ToolCounters(4, 4, 0));
    }

    @Test
    void countsOnlyTheTasksOwnContextCompactions()
    {
        TurnId turn = TurnId.random();
        AgentEventSource child = new AgentEventSource(Optional.of("child"), Optional.of("root"), Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
        List<AgentEventEnvelope> events = ImmutableList.of(
                event(1, turn, new AgentEventPayload.ToolActivity("Context compaction", ActivityState.STARTED, "", Optional.empty(), Optional.empty())),
                event(2, turn, new AgentEventPayload.ToolActivity("Context compaction", ActivityState.COMPLETED, "", Optional.empty(), Optional.empty())),
                new AgentEventEnvelope(AgentEventEnvelope.CURRENT_SCHEMA_VERSION, EventId.random(), 3, task.id(), Optional.of(turn), child, NOW,
                        new AgentEventPayload.ToolActivity("Context compaction", ActivityState.COMPLETED, "", Optional.empty(), Optional.empty())));

        assertThat(DefaultAgentMetricsService.foldCounters(events, turn).compactions()).isEqualTo(1);
    }

    private AgentEventEnvelope event(long sequence, TurnId turnId, AgentEventPayload payload)
    {
        return new AgentEventEnvelope(AgentEventEnvelope.CURRENT_SCHEMA_VERSION, EventId.random(), sequence, task.id(), Optional.of(turnId), AgentEventSource.root(), NOW, payload);
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
