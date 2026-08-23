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

import java.util.List;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** One database-backed page of pull requests. */
public record PullRequestPage(
        List<PullRequest> pullRequests,
        int page,
        int pageSize,
        long totalPullRequests,
        long openPullRequests,
        boolean hasMore)
{
    public PullRequestPage
    {
        pullRequests = ImmutableList.copyOf(requireNonNull(pullRequests, "pullRequests is null"));
        checkArgument(page >= 0, "page is negative");
        checkArgument(pageSize > 0, "pageSize must be positive");
        checkArgument(totalPullRequests >= 0, "totalPullRequests is negative");
        checkArgument(openPullRequests >= 0, "openPullRequests is negative");
    }
}
