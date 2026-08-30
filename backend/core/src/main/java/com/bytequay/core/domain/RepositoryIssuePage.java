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
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** One database-backed page of open issues for a monitored repository. */
public record RepositoryIssuePage(
        long repositoryId,
        RepoRef repository,
        List<RepositoryIssue> issues,
        int page,
        int pageSize,
        long totalIssues,
        boolean hasMore,
        Optional<Instant> syncedAt)
{
    public RepositoryIssuePage
    {
        checkArgument(repositoryId > 0, "repositoryId must be positive");
        requireNonNull(repository, "repository is null");
        issues = ImmutableList.copyOf(requireNonNull(issues, "issues is null"));
        checkArgument(page >= 0, "page is negative");
        checkArgument(pageSize > 0, "pageSize must be positive");
        checkArgument(totalIssues >= 0, "totalIssues is negative");
        requireNonNull(syncedAt, "syncedAt is null");
    }
}
