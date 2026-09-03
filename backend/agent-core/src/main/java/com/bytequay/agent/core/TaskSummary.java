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

import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Optional;

import static com.bytequay.agent.spi.AgentChecks.checkOptionalText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

public record TaskSummary(
        String finalResponse,
        Optional<WorktreeSnapshot> worktree,
        List<TestFact> tests,
        TaskMetrics metrics,
        List<String> learnings,
        Optional<String> notes,
        List<ChildSummary> children)
{
    /** A summary of a task that delegated nothing, which is what every task was before subagents. */
    public TaskSummary(
            String finalResponse,
            Optional<WorktreeSnapshot> worktree,
            List<TestFact> tests,
            TaskMetrics metrics,
            List<String> learnings,
            Optional<String> notes)
    {
        this(finalResponse, worktree, tests, metrics, learnings, notes, ImmutableList.of());
    }

    public TaskSummary
    {
        children = ImmutableList.copyOf(children);
        requireNonNull(finalResponse, "finalResponse is null");
        requireNonNull(worktree, "worktree is null");
        tests = ImmutableList.copyOf(tests);
        requireNonNull(metrics, "metrics is null");
        learnings = ImmutableList.copyOf(learnings);
        checkOptionalText(notes, "notes");
        checkArgument(learnings.stream().noneMatch(String::isBlank), "learnings contains a blank value");
    }
}
