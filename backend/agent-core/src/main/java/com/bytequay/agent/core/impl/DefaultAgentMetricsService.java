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
import com.bytequay.agent.core.AgentEventEnvelope;
import com.bytequay.agent.core.AgentEventPayload;
import com.bytequay.agent.core.AgentHistoryPage;
import com.bytequay.agent.core.AgentMetrics;
import com.bytequay.agent.core.AgentMetricsService;
import com.bytequay.agent.core.AgentStorageException;
import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.ToolCounters;
import com.bytequay.agent.core.TurnId;
import com.bytequay.agent.core.TurnState;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.agent.core.store.TaskLogStore;
import com.bytequay.agent.spi.ProviderAgentEvent;
import com.bytequay.agent.spi.ProviderAgentEvent.ActivityState;
import com.bytequay.agent.spi.ProviderAgentEvent.CommandActionKind;
import com.bytequay.agent.spi.ProviderUsageSample;
import com.google.common.collect.ImmutableSet;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

public final class DefaultAgentMetricsService
        implements AgentMetricsService
{
    private static final Set<String> EXPLORE_TOOLS = ImmutableSet.of("Read", "Grep", "Glob", "LS", "code_query", "bytequay/code_query", "mcp__bytequay__code_query");
    private static final Set<CommandActionKind> EXPLORE_ACTIONS =
            ImmutableSet.of(CommandActionKind.READ, CommandActionKind.LIST_FILES, CommandActionKind.SEARCH);

    private final AgentTaskStore taskStore;
    private final AgentTurnStore turnStore;
    private final TaskLogStore logStore;
    private final Clock clock;

    public DefaultAgentMetricsService(AgentTaskStore taskStore, AgentTurnStore turnStore, TaskLogStore logStore, Clock clock)
    {
        this.taskStore = requireNonNull(taskStore, "taskStore is null");
        this.turnStore = requireNonNull(turnStore, "turnStore is null");
        this.logStore = requireNonNull(logStore, "logStore is null");
        this.clock = requireNonNull(clock, "clock is null");
    }

    @Override
    public AgentMetrics record(TaskId taskId, TurnId turnId, ProviderUsageSample sample)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(turnId, "turnId is null");
        requireNonNull(sample, "sample is null");
        AgentTurn turn = turnStore.require(turnId);
        requireOwnership(taskId, turn);
        if (!turn.state().active()) {
            throw new AgentConflictException("Cannot record usage for terminal turn " + turnId);
        }
        AgentMetrics updated = switch (sample.semantics()) {
            case CUMULATIVE -> cumulativeMetrics(baselineTotals(taskId, turnId), turn.metrics(), sample);
            case CUMULATIVE_FOR_INVOCATION -> cumulativeMetrics(AgentMetrics.empty(), turn.metrics(), sample);
            case DELTA, FINAL_FOR_INVOCATION -> addMetrics(turn.metrics(), sample);
        };
        if (updated.equals(turn.metrics())) {
            return updated;
        }
        // Task totals fold at turn end: writing them here would bump the task's optimistic version
        // under every request the user makes while a turn streams.
        return turnStore.update(turn.withMetrics(updated)).metrics();
    }

    @Override
    public ToolCounters countTools(TaskId taskId, TurnId turnId, OptionalLong firstSequence)
    {
        requireNonNull(taskId, "taskId is null");
        requireNonNull(turnId, "turnId is null");
        requireNonNull(firstSequence, "firstSequence is null");
        // Folded from the persisted log rather than counted live, so replay and recovery agree with it. Pages are
        // byte-bounded and a turn's events end the log, so a long task costs no more than its latest turn.
        ToolCounters counters = ToolCounters.ZERO;
        OptionalLong before = OptionalLong.empty();
        while (true) {
            AgentHistoryPage page = logStore.readHistory(taskId, before, OptionalLong.empty());
            counters = counters.plus(foldCounters(page.events().stream()
                    .filter(event -> firstSequence.isEmpty() || event.sequence() >= firstSequence.getAsLong())
                    .collect(toImmutableList()), turnId));
            if (page.events().isEmpty() || page.beforeCursor().isEmpty()
                    || (firstSequence.isPresent() && page.events().getFirst().sequence() <= firstSequence.getAsLong())) {
                return counters;
            }
            before = page.beforeCursor();
        }
    }

    @Override
    public AgentMetrics finishTurn(TurnId turnId, TurnState terminalState, ToolCounters counters)
    {
        requireNonNull(turnId, "turnId is null");
        requireNonNull(terminalState, "terminalState is null");
        requireNonNull(counters, "counters is null");
        if (!terminalState.terminal()) {
            throw new AgentConflictException("Turn state is not terminal: " + terminalState);
        }
        AgentTurn turn = turnStore.require(turnId);
        Instant startedAt = turn.startedAt().orElse(turn.createdAt());
        Duration elapsed = Duration.between(startedAt, Instant.now(clock));
        if (elapsed.isNegative()) {
            throw new AgentStorageException("Turn elapsed time is negative");
        }
        AgentMetrics current = turn.metrics();
        AgentMetrics finished = new AgentMetrics(current.inputTokens(), current.cachedInputTokens(), current.outputTokens(), current.cost(), elapsed, counters);
        AgentTurn stored = turnStore.update(turn.withMetrics(finished));
        persistTaskTotals(turn.taskId());
        return stored.metrics();
    }

    @Override
    public AgentMetrics taskTotals(TaskId taskId)
    {
        requireNonNull(taskId, "taskId is null");
        taskStore.require(taskId);
        return calculateTaskTotals(turnStore.list(taskId));
    }

    private void persistTaskTotals(TaskId taskId)
    {
        AgentTask task = taskStore.require(taskId);
        AgentMetrics totals = calculateTaskTotals(turnStore.list(taskId));
        taskStore.update(task.withMetrics(totals, Instant.now(clock)));
    }

    private AgentMetrics baselineTotals(TaskId taskId, TurnId turnId)
    {
        return calculateTaskTotals(turnStore.list(taskId).stream()
                .filter(turn -> !turn.id().equals(turnId))
                .collect(toImmutableList()));
    }

    private static AgentMetrics cumulativeMetrics(
            AgentMetrics baseline,
            AgentMetrics current,
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

        return new AgentMetrics(inputTokens, cachedInputTokens, outputTokens, cumulativeCost(baseline, sample), current.elapsed());
    }

    private static Optional<BigDecimal> cumulativeCost(AgentMetrics baseline, ProviderUsageSample sample)
    {
        if (sample.cost().isEmpty()) {
            return Optional.empty();
        }
        if (baseline.cost().isPresent()) {
            BigDecimal difference = sample.cost().orElseThrow().subtract(baseline.cost().orElseThrow());
            return difference.signum() < 0 ? Optional.empty() : Optional.of(difference);
        }
        if (baseline.inputTokens() == 0 && baseline.cachedInputTokens() == 0 && baseline.outputTokens() == 0) {
            return sample.cost();
        }
        return Optional.empty();
    }

    private static AgentMetrics addMetrics(AgentMetrics current, ProviderUsageSample sample)
    {
        Optional<BigDecimal> cost = addCost(current, sample);
        try {
            return new AgentMetrics(
                    Math.addExact(current.inputTokens(), sample.inputTokens()),
                    Math.addExact(current.cachedInputTokens(), sample.cachedInputTokens()),
                    Math.addExact(current.outputTokens(), sample.outputTokens()),
                    cost,
                    current.elapsed());
        }
        catch (ArithmeticException e) {
            throw new AgentStorageException("Provider token counters overflowed", e);
        }
    }

    private static Optional<BigDecimal> addCost(AgentMetrics current, ProviderUsageSample sample)
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
        return Optional.of(current.cost().orElseThrow().add(sample.cost().orElseThrow()));
    }

    /** Counts a turn's finished tool and command activity; child-agent events carry the owning turn and count with it. */
    static ToolCounters foldCounters(List<AgentEventEnvelope> events, TurnId turnId)
    {
        long toolCalls = 0;
        long exploreActions = 0;
        long compactions = 0;
        for (AgentEventEnvelope event : events) {
            if (!event.turnId().map(turnId::equals).orElse(false)) {
                continue;
            }
            if (event.payload() instanceof AgentEventPayload.ToolActivity tool && terminal(tool.state())) {
                toolCalls++;
                if (EXPLORE_TOOLS.contains(tool.name())) {
                    exploreActions++;
                }
                // A subagent compacting its own context leaves the task's conversation as it was.
                if (tool.name().equals(ProviderAgentEvent.Tool.CONTEXT_COMPACTION) && event.source().parentProviderThreadId().isEmpty()) {
                    compactions++;
                }
            }
            else if (event.payload() instanceof AgentEventPayload.CommandActivity command && terminal(command.state())) {
                toolCalls++;
                exploreActions += command.actions().stream().filter(action -> EXPLORE_ACTIONS.contains(action.kind())).count();
            }
        }
        return new ToolCounters(toolCalls, exploreActions, compactions);
    }

    private static boolean terminal(ActivityState state)
    {
        return state == ActivityState.COMPLETED || state == ActivityState.FAILED;
    }

    private static AgentMetrics calculateTaskTotals(List<AgentTurn> turns)
    {
        long inputTokens = 0;
        long cachedInputTokens = 0;
        long outputTokens = 0;
        Duration elapsed = Duration.ZERO;
        ToolCounters counters = ToolCounters.ZERO;
        Optional<BigDecimal> cost = Optional.of(BigDecimal.ZERO);
        try {
            for (AgentTurn turn : turns) {
                AgentMetrics metrics = turn.metrics();
                inputTokens = Math.addExact(inputTokens, metrics.inputTokens());
                cachedInputTokens = Math.addExact(cachedInputTokens, metrics.cachedInputTokens());
                outputTokens = Math.addExact(outputTokens, metrics.outputTokens());
                elapsed = elapsed.plus(metrics.elapsed());
                counters = counters.plus(metrics.counters());
                if (metrics.cost().isEmpty()) {
                    cost = Optional.empty();
                }
                else if (cost.isPresent()) {
                    cost = Optional.of(cost.orElseThrow().add(metrics.cost().orElseThrow()));
                }
            }
        }
        catch (ArithmeticException e) {
            throw new AgentStorageException("Task usage totals overflowed", e);
        }
        if (turns.isEmpty() || cost.isEmpty()) {
            return new AgentMetrics(inputTokens, cachedInputTokens, outputTokens, Optional.empty(), elapsed, counters);
        }
        return new AgentMetrics(inputTokens, cachedInputTokens, outputTokens, cost, elapsed, counters);
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
