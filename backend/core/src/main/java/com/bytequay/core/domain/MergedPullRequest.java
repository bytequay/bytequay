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
package com.bytequay.core.domain;

import com.google.common.collect.ImmutableList;

import java.time.Instant;
import java.util.List;

import static com.bytequay.core.domain.Checks.checkSha;
import static java.util.Objects.requireNonNull;

/**
 * Fresh merge evidence for a request, never cached commit-to-request grouping.
 *
 * @param repositoryId the provider's id of the base repository
 * @param mergeSha the commit that landed on the base branch: a merge or squash commit, or the request's own head when it fast-forwarded
 * @param commits the request's complete original commits, oldest first
 */
public record MergedPullRequest(PullRequestIdentity identity, long repositoryId, Instant mergedAt, String mergeSha, List<String> commits)
{
    public MergedPullRequest
    {
        requireNonNull(identity, "identity is null");
        requireNonNull(mergedAt, "mergedAt is null");
        checkSha(mergeSha, "mergeSha");
        commits = ImmutableList.copyOf(commits);
    }
}
