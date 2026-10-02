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
package com.bytequay.core.cherrypick;

import com.bytequay.core.domain.PullRequestRef;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import java.time.Instant;
import java.util.List;

import static com.bytequay.core.domain.Checks.checkSha;
import static com.bytequay.core.domain.Checks.checkText;
import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Frozen merge evidence and the oldest-first landed sequence; later hops resolve their own PR afresh. */
public record MergedPullRequestRange(
        PullRequestRef pullRequest,
        long repositoryId,
        String baseBranch,
        Instant mergedAt,
        String mergeSha,
        List<String> originalCommits,
        String sourceBaseSha,
        List<String> commits)
{
    public MergedPullRequestRange
    {
        requireNonNull(pullRequest, "pullRequest is null");
        checkArgument(repositoryId > 0, "repositoryId must be positive");
        checkText(baseBranch, "baseBranch");
        requireNonNull(mergedAt, "mergedAt is null");
        checkSha(mergeSha, "mergeSha");
        checkSha(sourceBaseSha, "sourceBaseSha");
        originalCommits = ImmutableList.copyOf(originalCommits);
        commits = ImmutableList.copyOf(commits);
        checkArgument(!originalCommits.isEmpty() && !commits.isEmpty(), "Commit sequences must not be empty");
        originalCommits.forEach(sha -> checkSha(sha, "original commit"));
        commits.forEach(sha -> checkSha(sha, "landed commit"));
        checkArgument(ImmutableSet.copyOf(originalCommits).size() == originalCommits.size(), "Duplicate original commits");
        checkArgument(ImmutableSet.copyOf(commits).size() == commits.size(), "Duplicate landed commits");
        checkArgument(commits.getLast().equals(mergeSha), "The landed range must end at the verified merge SHA");
        checkArgument(!commits.contains(sourceBaseSha), "The exclusive base cannot be a landed commit");
    }
}
