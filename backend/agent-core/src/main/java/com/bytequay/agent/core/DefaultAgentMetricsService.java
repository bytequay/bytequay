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

import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.spi.CostProvenance;
import com.bytequay.agent.spi.ProviderUsageSample;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import static java.util.Objects.requireNonNull;

public final class DefaultAgentMetricsService
        implements AgentMetricsService
{
    private final AgentTaskStore taskStore;
    private final AgentTurnStore turnStore;
    private final Clock clock;
    private final ConcurrentMap<TurnId, TurnUsageState> usageStates = new ConcurrentHashMap<>();

    public DefaultAgentMetricsService(AgentTaskStore taskStore, AgentTurnStore turnStore, Clock clock)
    {
        requireNonNull(taskStore, "taskStore is null");
        requireNonNull(turnStore, "turnStore is null");
        requireNonNull(clock, "clock is null");
        this.taskStore = taskStore;
        this.turnStore = turnStore;
        this.clock = clock;
    }

    @Override
    public UsageBaseline beginTurn(TaskId taskId, TurnId turnId)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(turnId, "turnId is null");
        AgentTask task = requireTask(taskId);
        AgentTurn turn = requireTurn(turnId);
        requireOwnership(taskId, turn);
        if (!turn.state().active()) {
            throw new AgentConflictException("Cannot begin metrics for terminal turn " + turnId);
        }
        TurnUsageState state = usageStates.computeIfAbsent(
                turnId,
                ignored -> new TurnUsageState(new UsageBaseline(taskId, turnId, task.metrics(), Instant.now(clock))));
        return state.baseline();
    }

    @Override
    public TurnMetrics record(TaskId taskId, TurnId turnId, ProviderUsageSample sample)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(turnId, "turnId is null");
        requireNonNull(sample, "sample is null");
        TurnUsageState state = usageStates.computeIfAbsent(turnId, ignored -> {
            AgentTask task = requireTask(taskId);
            return new TurnUsageState(new UsageBaseline(taskId, turnId, task.metrics(), Instant.now(clock)));
        });
        synchronized (state) {
            AgentTurn turn = requireTurn(turnId);
            requireOwnership(taskId, turn);
            if (!turn.state().active()) {
                throw new AgentConflictException("Cannot record usage for terminal turn " + turnId);
            }
            if (sample.providerEventId().stream().anyMatch(state.seenProviderEventIds()::contains)) {
                persistTaskTotals(taskId);
                return turn.metrics();
            }

            TurnMetrics updated = switch (sample.semantics()) {
                case CUMULATIVE -> cumulativeMetrics(state.baseline().totals(), turn.metrics(), sample);
                case DELTA, FINAL_FOR_INVOCATION -> addMetrics(turn.metrics(), sample);
            };
            AgentTurn stored = turnStore.update(turn.withMetrics(updated));
            sample.providerEventId().ifPresent(state.seenProviderEventIds()::add);
            persistTaskTotals(taskId);
            return stored.metrics();
        }
    }

    @Override
    public TurnMetrics finishTurn(TurnId turnId, TurnState terminalState)
    {
        requireNonNull(turnId, "turnId is null");
        requireNonNull(terminalState, "terminalState is null");
        if (!terminalState.terminal()) {
            throw new AgentConflictException("Turn state is not terminal: " + terminalState);
        }
        TurnUsageState state = usageStates.computeIfAbsent(turnId, ignored -> {
            AgentTurn turn = requireTurn(turnId);
            AgentTask task = requireTask(turn.taskId());
            return new TurnUsageState(new UsageBaseline(
                    turn.taskId(),
                    turnId,
                    task.metrics(),
                    turn.startedAt().orElse(turn.createdAt())));
        });
        synchronized (state) {
            AgentTurn turn = requireTurn(turnId);
            Instant startedAt = turn.startedAt().orElse(state.baseline().capturedAt());
            Duration elapsed = Duration.between(startedAt, Instant.now(clock));
            if (elapsed.isNegative()) {
                throw new AgentStorageException("Turn elapsed time is negative");
            }
            TurnMetrics current = turn.metrics();
            TurnMetrics finished = new TurnMetrics(
                    current.inputTokens(),
                    current.cachedInputTokens(),
                    current.outputTokens(),
                    current.cost(),
                    current.costProvenance(),
                    elapsed);
            AgentTurn stored = turnStore.update(turn.withMetrics(finished));
            persistTaskTotals(turn.taskId());
            usageStates.remove(turnId, state);
            return stored.metrics();
        }
    }

    @Override
    public TaskMetrics taskTotals(TaskId taskId)
    {
        requireNonNull(taskId, "taskId is null");
        requireTask(taskId);
        return calculateTaskTotals(turnStore.list(taskId));
    }

    private void persistTaskTotals(TaskId taskId)
    {
        AgentTask task = requireTask(taskId);
        TaskMetrics totals = calculateTaskTotals(turnStore.list(taskId));
        taskStore.update(task.withMetrics(totals, Instant.now(clock)));
    }

    private static TurnMetrics cumulativeMetrics(
            TaskMetrics baseline,
            TurnMetrics current,
            ProviderUsageSample sample)
    {
        long inputTokens = subtractFloor(sample.inputTokens(), baseline.inputTokens());
        long cachedInputTokens = subtractFloor(sample.cachedInputTokens(), baseline.cachedInputTokens());
        long outputTokens = subtractFloor(sample.outputTokens(), baseline.outputTokens());
        if (inputTokens < current.inputTokens()
                || cachedInputTokens < current.cachedInputTokens()
                || outputTokens < current.outputTokens()) {
            return current;
        }

        Optional<BigDecimal> cost = cumulativeCost(baseline, sample);
        CostProvenance provenance = cost.isPresent()
                ? sample.costProvenance()
                : CostProvenance.UNAVAILABLE;
        return new TurnMetrics(
                inputTokens,
                cachedInputTokens,
                outputTokens,
                cost,
                provenance,
                current.elapsed());
    }

    private static Optional<BigDecimal> cumulativeCost(TaskMetrics baseline, ProviderUsageSample sample)
    {
        if (sample.cost().isEmpty()) {
            return Optional.empty();
        }
        if (baseline.cost().isPresent() && baseline.costProvenance() == sample.costProvenance()) {
            BigDecimal difference = sample.cost().orElseThrow().subtract(baseline.cost().orElseThrow());
            return difference.signum() < 0 ? Optional.empty() : Optional.of(difference);
        }
        if (baseline.inputTokens() == 0 && baseline.cachedInputTokens() == 0 && baseline.outputTokens() == 0) {
            return sample.cost();
        }
        return Optional.empty();
    }

    private static TurnMetrics addMetrics(TurnMetrics current, ProviderUsageSample sample)
    {
        Optional<BigDecimal> cost = addCost(current, sample);
        CostProvenance provenance = cost.isPresent()
                ? sample.costProvenance()
                : CostProvenance.UNAVAILABLE;
        try {
            return new TurnMetrics(
                    Math.addExact(current.inputTokens(), sample.inputTokens()),
                    Math.addExact(current.cachedInputTokens(), sample.cachedInputTokens()),
                    Math.addExact(current.outputTokens(), sample.outputTokens()),
                    cost,
                    provenance,
                    current.elapsed());
        }
        catch (ArithmeticException e) {
            throw new AgentStorageException("Provider token counters overflowed", e);
        }
    }

    private static Optional<BigDecimal> addCost(TurnMetrics current, ProviderUsageSample sample)
    {
        if (sample.cost().isEmpty()) {
            return Optional.empty();
        }
        if (current.cost().isEmpty()) {
            if (current.inputTokens() == 0 && current.cachedInputTokens() == 0 && current.outputTokens() == 0) {
                return sample.cost();
            }
            return Optional.empty();
        }
        if (current.costProvenance() != sample.costProvenance()) {
            return Optional.empty();
        }
        return Optional.of(current.cost().orElseThrow().add(sample.cost().orElseThrow()));
    }

    private static TaskMetrics calculateTaskTotals(List<AgentTurn> turns)
    {
        long inputTokens = 0;
        long cachedInputTokens = 0;
        long outputTokens = 0;
        Duration elapsed = Duration.ZERO;
        Optional<BigDecimal> cost = Optional.of(BigDecimal.ZERO);
        CostProvenance provenance = null;
        try {
            for (AgentTurn turn : turns) {
                TurnMetrics metrics = turn.metrics();
                inputTokens = Math.addExact(inputTokens, metrics.inputTokens());
                cachedInputTokens = Math.addExact(cachedInputTokens, metrics.cachedInputTokens());
                outputTokens = Math.addExact(outputTokens, metrics.outputTokens());
                elapsed = elapsed.plus(metrics.elapsed());
                if (metrics.cost().isEmpty()) {
                    cost = Optional.empty();
                }
                else if (cost.isPresent()) {
                    if (provenance != null && provenance != metrics.costProvenance()) {
                        cost = Optional.empty();
                    }
                    else {
                        provenance = metrics.costProvenance();
                        cost = Optional.of(cost.orElseThrow().add(metrics.cost().orElseThrow()));
                    }
                }
            }
        }
        catch (ArithmeticException e) {
            throw new AgentStorageException("Task usage totals overflowed", e);
        }
        if (turns.isEmpty() || cost.isEmpty()) {
            return new TaskMetrics(
                    inputTokens,
                    cachedInputTokens,
                    outputTokens,
                    Optional.empty(),
                    CostProvenance.UNAVAILABLE,
                    elapsed);
        }
        return new TaskMetrics(inputTokens, cachedInputTokens, outputTokens, cost, provenance, elapsed);
    }

    private AgentTask requireTask(TaskId taskId)
    {
        return taskStore.find(taskId).orElseThrow(() -> new AgentTaskNotFoundException(taskId));
    }

    private AgentTurn requireTurn(TurnId turnId)
    {
        return turnStore.find(turnId).orElseThrow(() -> new AgentTurnNotFoundException(turnId));
    }

    private static void requireOwnership(TaskId taskId, AgentTurn turn)
    {
        if (!turn.taskId().equals(taskId)) {
            throw new AgentConflictException("Turn does not belong to task " + taskId);
        }
    }

    private static long subtractFloor(long value, long baseline)
    {
        return value < baseline ? 0 : value - baseline;
    }

    private record TurnUsageState(UsageBaseline baseline, Set<String> seenProviderEventIds)
    {
        private TurnUsageState(UsageBaseline baseline)
        {
            this(baseline, new HashSet<>());
        }
    }
}
