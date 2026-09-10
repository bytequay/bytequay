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

import com.bytequay.agent.core.CodeGraphArm;
import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

/** The observational CodeGraph comparison: per repository, what tasks in each arm used per finished turn. */
public record CodeGraphComparison(List<RepositoryComparison> repositories)
{
    public CodeGraphComparison
    {
        repositories = ImmutableList.copyOf(requireNonNull(repositories, "repositories is null"));
    }

    /**
     * One repository's arms. Grouped by {@code owner/name} rather than by
     * workspace, because two workspaces can watch the same repository and one
     * workspace can change which repository it watches; either way the arms
     * being compared are the repository's, not the workspace's.
     */
    public record RepositoryComparison(String repository, List<ArmComparison> arms)
    {
        public RepositoryComparison
        {
            requireNonNull(repository, "repository is null");
            arms = ImmutableList.copyOf(requireNonNull(arms, "arms is null"));
        }
    }

    /** Medians are per finished turn and absent until the arm has one; the graph columns exist only for the ON arm. */
    public record ArmComparison(
            CodeGraphArm arm,
            long taskCount,
            long turnCount,
            double turnsPerTask,
            boolean smallSample,
            Optional<Double> inputTokens,
            Optional<Double> outputTokens,
            Optional<Double> toolCalls,
            Optional<Double> exploreActions,
            Optional<Double> minutes,
            Optional<Double> graphCalls,
            Optional<Double> graphSeconds)
    {
        public ArmComparison
        {
            requireNonNull(arm, "arm is null");
            requireNonNull(inputTokens, "inputTokens is null");
            requireNonNull(outputTokens, "outputTokens is null");
            requireNonNull(toolCalls, "toolCalls is null");
            requireNonNull(exploreActions, "exploreActions is null");
            requireNonNull(minutes, "minutes is null");
            requireNonNull(graphCalls, "graphCalls is null");
            requireNonNull(graphSeconds, "graphSeconds is null");
        }
    }
}
