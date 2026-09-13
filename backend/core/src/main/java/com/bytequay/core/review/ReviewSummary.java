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
package com.bytequay.core.review;

import com.google.common.collect.ImmutableList;

import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** The agent's structured explanation of the run, kept at its latest accepted revision. */
public record ReviewSummary(
        int revision,
        String purpose,
        List<CommitExplanation> commits,
        String combinedResult,
        Optional<String> evidence,
        List<String> criticalFindingIds,
        List<String> limitations)
{
    public ReviewSummary
    {
        checkArgument(revision > 0, "revision is not positive");
        requireNonNull(purpose, "purpose is null");
        commits = ImmutableList.copyOf(commits);
        requireNonNull(combinedResult, "combinedResult is null");
        requireNonNull(evidence, "evidence is null");
        criticalFindingIds = ImmutableList.copyOf(criticalFindingIds);
        limitations = ImmutableList.copyOf(limitations);
    }

    public record CommitExplanation(String sha, String explanation)
    {
        public CommitExplanation
        {
            requireNonNull(sha, "sha is null");
            requireNonNull(explanation, "explanation is null");
        }
    }
}
