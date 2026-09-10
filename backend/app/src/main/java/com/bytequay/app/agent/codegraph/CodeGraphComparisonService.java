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
package com.bytequay.app.agent.codegraph;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTurn;
import com.bytequay.agent.core.CodeGraphArm;
import com.bytequay.agent.core.TurnMetrics;
import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.agent.core.store.AgentTurnStore;
import com.bytequay.app.agent.codegraph.CodeGraphComparison.ArmComparison;
import com.bytequay.app.agent.codegraph.CodeGraphComparison.RepositoryComparison;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.ToDoubleFunction;
import java.util.stream.Stream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toList;

/**
 * Builds the Section 12 comparison from every arm-stamped task and its finished turns. Read-only,
 * computed on request, and observational: different tasks, models, and prompts confound it.
 */
@Component
public class CodeGraphComparisonService
{
    static final int SMALL_SAMPLE_TASKS = 5;

    private final AgentTaskStore tasks;
    private final AgentTurnStore turns;

    public CodeGraphComparisonService(AgentTaskStore tasks, AgentTurnStore turns)
    {
        this.tasks = requireNonNull(tasks, "tasks is null");
        this.turns = requireNonNull(turns, "turns is null");
    }

    public CodeGraphComparison compare()
    {
        Map<String, List<AgentTask>> byRepository = tasks.listWithCodeGraphArm().stream()
                .filter(task -> task.workspaceId().isPresent())
                .collect(groupingBy(task -> task.repository().toString(), LinkedHashMap::new, toList()));
        return new CodeGraphComparison(byRepository.entrySet().stream()
                .map(entry -> new RepositoryComparison(
                        entry.getKey(),
                        Stream.of(CodeGraphArm.ON, CodeGraphArm.OFF)
                                .flatMap(arm -> arm(arm, entry.getValue().stream().filter(task -> task.codegraphArm().orElseThrow() == arm).toList()).stream())
                                .collect(toImmutableList())))
                .collect(toImmutableList()));
    }

    private Optional<ArmComparison> arm(CodeGraphArm arm, List<AgentTask> armTasks)
    {
        if (armTasks.isEmpty()) {
            return Optional.empty();
        }
        // ponytail: one turn query per task; the table is a settings page over one desktop's tasks.
        List<TurnMetrics> finished = armTasks.stream()
                .flatMap(task -> turns.list(task.id()).stream())
                .filter(turn -> turn.state().terminal())
                .map(AgentTurn::metrics)
                .toList();
        boolean graph = arm == CodeGraphArm.ON;
        return Optional.of(new ArmComparison(
                arm,
                armTasks.size(),
                finished.size(),
                (double) finished.size() / armTasks.size(),
                armTasks.size() < SMALL_SAMPLE_TASKS,
                median(finished, TurnMetrics::inputTokens),
                median(finished, TurnMetrics::outputTokens),
                median(finished, metrics -> metrics.counters().toolCalls()),
                median(finished, metrics -> metrics.counters().exploreActions()),
                median(finished, metrics -> metrics.elapsed().toMillis() / 60_000.0),
                graph ? median(finished, metrics -> metrics.counters().codegraphCalls()) : Optional.empty(),
                graph ? median(finished, metrics -> metrics.counters().codegraphMillis() / 1000.0) : Optional.empty()));
    }

    static Optional<Double> median(List<TurnMetrics> turns, ToDoubleFunction<TurnMetrics> value)
    {
        if (turns.isEmpty()) {
            return Optional.empty();
        }
        double[] sorted = turns.stream().mapToDouble(value).sorted().toArray();
        int middle = sorted.length / 2;
        return Optional.of(sorted.length % 2 == 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2);
    }
}
