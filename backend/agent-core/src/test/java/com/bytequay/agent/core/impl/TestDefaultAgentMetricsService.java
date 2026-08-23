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

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskMetrics;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnMetrics;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.agent.spi.CostProvenance;
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
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;

class TestDefaultAgentMetricsService
{
    private static final Instant NOW = Instant.parse("2026-08-16T00:00:00Z");

    private InMemoryTaskStore taskStore;
    private InMemoryTurnStore turnStore;
    private MutableClock clock;
    private DefaultAgentMetricsService service;
    private AgentTask task;

    @BeforeEach
    void setUp()
    {
        taskStore = new InMemoryTaskStore();
        turnStore = new InMemoryTurnStore();
        clock = new MutableClock(NOW);
        service = new DefaultAgentMetricsService(taskStore, turnStore, clock);
        task = AgentTask.created(
                TaskId.random(),
                "Task",
                AgentLaneId.CODEX_CLI,
                new RepositoryIdentity("bytequay", "sample"),
                Path.of("/tmp/worktree"),
                AgentPermissionPolicy.AUTO,
                NOW);
        taskStore.insert(task);
    }

    @Test
    void derivesCumulativeUsageFromTaskBaselineAndDiscardsDuplicates()
    {
        TurnMetrics previousMetrics = metrics(100, 10, 20, "2.00");
        AgentTurn previous = AgentTurn.accepted(TurnId.random(), task.id(), "previous", NOW)
                .running(new ProviderTurnRef("previous"), Optional.empty(), NOW)
                .finish(TurnState.COMPLETED, previousMetrics, Optional.empty(), NOW.plusSeconds(1));
        turnStore.insert(previous);
        taskStore.put(task.withMetrics(
                new TaskMetrics(100, 10, 20, previousMetrics.cost(), previousMetrics.costProvenance(), Duration.ZERO),
                NOW));
        AgentTurn current = AgentTurn.accepted(TurnId.random(), task.id(), "current", NOW.plusSeconds(2));
        turnStore.insert(current);
        service.beginTurn(task.id(), current.id());

        TurnMetrics first = service.record(task.id(), current.id(), cumulative("usage-1", 130, 12, 25, "2.50"));
        TurnMetrics duplicate = service.record(task.id(), current.id(), cumulative("usage-1", 130, 12, 25, "2.50"));
        TurnMetrics latest = service.record(task.id(), current.id(), cumulative("usage-2", 150, 15, 30, "2.75"));

        assertThat(first).isEqualTo(metrics(30, 2, 5, "0.50"));
        assertThat(duplicate).isEqualTo(first);
        assertThat(latest).isEqualTo(metrics(50, 5, 10, "0.75"));
        assertThat(service.taskTotals(task.id())).isEqualTo(new TaskMetrics(150, 15, 30, Optional.of(new BigDecimal("2.75")), CostProvenance.PROVIDER_REPORTED, Duration.ZERO));
    }

    @Test
    void addsDeltaAndFinalInvocationUsage()
    {
        AgentTurn turn = AgentTurn.accepted(TurnId.random(), task.id(), "request", NOW);
        turnStore.insert(turn);
        service.beginTurn(task.id(), turn.id());

        service.record(task.id(), turn.id(), sample(
                "delta",
                ProviderUsageSample.Semantics.DELTA,
                4,
                1,
                2,
                "0.10"));
        TurnMetrics result = service.record(task.id(), turn.id(), sample(
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
        AgentTurn turn = AgentTurn.accepted(TurnId.random(), task.id(), "request", NOW)
                .running(new ProviderTurnRef("provider-turn"), Optional.empty(), NOW);
        turnStore.insert(turn);
        service.beginTurn(task.id(), turn.id());
        clock.advance(Duration.ofSeconds(5));

        TurnMetrics result = service.finishTurn(turn.id(), TurnState.COMPLETED);

        assertThat(result.elapsed()).isEqualTo(Duration.ofSeconds(5));
        assertThat(taskStore.find(task.id()).orElseThrow().metrics().elapsed()).isEqualTo(Duration.ofSeconds(5));
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
                Optional.of(new BigDecimal(cost)),
                CostProvenance.PROVIDER_REPORTED,
                NOW);
    }

    private static TurnMetrics metrics(long inputTokens, long cachedInputTokens, long outputTokens, String cost)
    {
        return new TurnMetrics(
                inputTokens,
                cachedInputTokens,
                outputTokens,
                Optional.of(new BigDecimal(cost)),
                CostProvenance.PROVIDER_REPORTED,
                Duration.ZERO);
    }

    private static final class InMemoryTaskStore
            implements AgentTaskStore
    {
        private final Map<TaskId, AgentTask> tasks = new HashMap<>();

        @Override
        public void insert(AgentTask task)
        {
            tasks.put(task.id(), task);
        }

        @Override
        public Optional<AgentTask> find(TaskId taskId)
        {
            return Optional.ofNullable(tasks.get(taskId));
        }

        @Override
        public AgentTask update(AgentTask task)
        {
            AgentTask stored = task.withVersion(task.version() + 1);
            tasks.put(task.id(), stored);
            return stored;
        }

        @Override
        public List<AgentTask> listRecent(int limit)
        {
            return ImmutableList.copyOf(tasks.values());
        }

        @Override
        public List<AgentTask> listLive()
        {
            return ImmutableList.copyOf(tasks.values());
        }

        @Override
        public List<AgentTask> listCleanupDue(Instant now, int limit)
        {
            return ImmutableList.of();
        }

        private void put(AgentTask task)
        {
            tasks.put(task.id(), task);
        }
    }

    private static final class InMemoryTurnStore
            implements AgentTurnStore
    {
        private final Map<TurnId, AgentTurn> turns = new HashMap<>();

        @Override
        public void insert(AgentTurn turn)
        {
            turns.put(turn.id(), turn);
        }

        @Override
        public Optional<AgentTurn> find(TurnId turnId)
        {
            return Optional.ofNullable(turns.get(turnId));
        }

        @Override
        public Optional<AgentTurn> findByIdempotencyKey(TaskId taskId, String idempotencyKey)
        {
            return turns.values().stream()
                    .filter(turn -> turn.taskId().equals(taskId))
                    .filter(turn -> turn.idempotencyKey().equals(idempotencyKey))
                    .findFirst();
        }

        @Override
        public Optional<AgentTurn> findActive(TaskId taskId)
        {
            return turns.values().stream()
                    .filter(turn -> turn.taskId().equals(taskId))
                    .filter(turn -> turn.state().active())
                    .findFirst();
        }

        @Override
        public AgentTurn update(AgentTurn turn)
        {
            AgentTurn stored = turn.withVersion(turn.version() + 1);
            turns.put(turn.id(), stored);
            return stored;
        }

        @Override
        public List<AgentTurn> list(TaskId taskId)
        {
            return turns.values().stream()
                    .filter(turn -> turn.taskId().equals(taskId))
                    .sorted(Comparator.comparing(AgentTurn::createdAt))
                    .collect(toImmutableList());
        }

        @Override
        public List<AgentTurn> listRecoverable()
        {
            return turns.values().stream()
                    .filter(turn -> turn.state().active())
                    .collect(toImmutableList());
        }
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
