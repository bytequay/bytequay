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
import com.bytequay.agent.core.AgentMetricsService;
import com.bytequay.agent.core.AgentStorageException;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskNotFoundException;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.AgentTurnNotFoundException;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskMetrics;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnMetrics;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.UsageBaseline;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.spi.CostProvenance;
import com.bytequay.agent.spi.ProviderUsageSample;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

public final class DefaultAgentMetricsService
        implements AgentMetricsService
{
    private final AgentTaskStore taskStore;
    private final AgentTurnStore turnStore;
    private final Clock clock;

    public DefaultAgentMetricsService(AgentTaskStore taskStore, AgentTurnStore turnStore, Clock clock)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.turnStore = requireNonNull(turnStore, "turnStore is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    public UsageBaseline beginTurn(TaskId taskId, TurnId turnId)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(turnId, "turnId is null");
        requireTask(taskId);
        AgentTurn turn = requireTurn(turnId);
        requireOwnership(taskId, turn);
        if (!turn.state().active()) {
            throw new AgentConflictException("Cannot begin metrics for terminal turn " + turnId);
        }
        return new UsageBaseline(taskId, turnId, baselineTotals(taskId, turnId), Instant.now(clock));
    }

    @Override
    public TurnMetrics record(TaskId taskId, TurnId turnId, ProviderUsageSample sample)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(turnId, "turnId is null");
        requireNonNull(sample, "sample is null");
        AgentTurn turn = requireTurn(turnId);
        requireOwnership(taskId, turn);
        if (!turn.state().active()) {
            throw new AgentConflictException("Cannot record usage for terminal turn " + turnId);
        }
        TurnMetrics updated = switch (sample.semantics()) {
            case CUMULATIVE -> cumulativeMetrics(baselineTotals(taskId, turnId), turn.metrics(), sample);
            case DELTA, FINAL_FOR_INVOCATION -> addMetrics(turn.metrics(), sample);
        };
        if (updated.equals(turn.metrics())) {
            return updated;
        }
        AgentTurn stored = turnStore.update(turn.withMetrics(updated));
        persistTaskTotals(taskId);
        return stored.metrics();
    }

    @Override
    public TurnMetrics finishTurn(TurnId turnId, TurnState terminalState)
    {
        requireNonNull(turnId, "turnId is null");
        requireNonNull(terminalState, "terminalState is null");
        if (!terminalState.terminal()) {
            throw new AgentConflictException("Turn state is not terminal: " + terminalState);
        }
        AgentTurn turn = requireTurn(turnId);
        Instant startedAt = turn.startedAt().orElse(turn.createdAt());
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
        return stored.metrics();
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

    private TaskMetrics baselineTotals(TaskId taskId, TurnId turnId)
    {
        return calculateTaskTotals(turnStore.list(taskId).stream()
                .filter(turn -> !turn.id().equals(turnId))
                .collect(toImmutableList()));
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
}
