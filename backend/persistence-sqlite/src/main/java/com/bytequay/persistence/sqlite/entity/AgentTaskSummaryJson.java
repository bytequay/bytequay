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
package com.bytequay.persistence.sqlite.entity;

import com.bytequay.agent.core.AgentMetrics;
import com.bytequay.agent.core.ChildSummary;
import com.bytequay.agent.core.TaskSummary;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.ToolCounters;
import com.bytequay.agent.core.WorktreeSnapshot;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.google.common.collect.ImmutableList;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import static com.bytequay.persistence.sqlite.entity.JsonGuard.AGENT;

final class AgentTaskSummaryJson
{
    private AgentTaskSummaryJson() {}

    public static String encode(TaskSummary summary)
    {
        AgentMetrics metrics = summary.metrics();
        Optional<WorktreeSnapshot> worktree = summary.worktree();
        SummaryValue value = new SummaryValue(
                summary.finalResponse(),
                worktree.map(snapshot -> snapshot.worktree().canonicalWorktree().toString()).orElse(null),
                worktree.map(snapshot -> snapshot.worktree().physicalRepository().toString()).orElse(null),
                worktree.map(snapshot -> snapshot.worktree().generatedBranch()).orElse(null),
                worktree.map(WorktreeSnapshot::head).orElse(null),
                worktree.map(WorktreeSnapshot::dirty).orElse(false),
                worktree.map(WorktreeSnapshot::gitOperationInProgress).orElse(false),
                metrics.inputTokens(),
                metrics.cachedInputTokens(),
                metrics.outputTokens(),
                metrics.cost().map(BigDecimal::toPlainString).orElse(null),
                metrics.elapsed().toMillis(),
                metrics.counters(),
                summary.notes().orElse(null),
                summary.children());
        return AGENT.write(value, "task summary");
    }

    public static TaskSummary decode(String json, Optional<TaskWorktree> taskWorktree)
    {
        return AGENT.guard("Cannot deserialize task summary", mapper -> {
            SummaryValue value = mapper.readValue(json, SummaryValue.class);
            AgentMetrics metrics = new AgentMetrics(
                    value.inputTokens(),
                    value.cachedInputTokens(),
                    value.outputTokens(),
                    Optional.ofNullable(value.cost()).map(BigDecimal::new),
                    Duration.ofMillis(value.elapsedMillis()),
                    // A summary stored before tool counters existed has no field for them.
                    value.counters() == null ? ToolCounters.ZERO : value.counters());
            // Completion refuses uncommitted work, so a stored summary never records any.
            Optional<WorktreeSnapshot> worktree = Optional.ofNullable(value.head())
                    .flatMap(head -> taskWorktree.map(task -> new WorktreeSnapshot(task, head, value.dirty(), Boolean.TRUE.equals(value.gitOperationInProgress()), false)));
            return new TaskSummary(
                    value.finalResponse(),
                    worktree,
                    metrics,
                    Optional.ofNullable(value.notes()),
                    // A summary stored before subagents were indexed has no field for them.
                    value.children() == null ? ImmutableList.of() : value.children());
        });
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record SummaryValue(
            String finalResponse,
            String worktree,
            String repository,
            String branch,
            String head,
            boolean dirty,
            Boolean gitOperationInProgress,
            long inputTokens,
            long cachedInputTokens,
            long outputTokens,
            String cost,
            long elapsedMillis,
            ToolCounters counters,
            String notes,
            List<ChildSummary> children) {}
}
