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

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Latest locally persisted issue list for one monitored repository ID. */
public record RepositoryIssueSnapshot(
        long repositoryId,
        RepoRef repository,
        List<RepositoryIssue> issues,
        Optional<Instant> syncedAt)
{
    public RepositoryIssueSnapshot
    {
        checkArgument(repositoryId > 0, "repositoryId must be positive");
        requireNonNull(repository, "repository is null");
        issues = List.copyOf(requireNonNull(issues, "issues is null"));
        requireNonNull(syncedAt, "syncedAt is null");
    }

    public static RepositoryIssueSnapshot empty(long repositoryId, RepoRef repository)
    {
        return new RepositoryIssueSnapshot(repositoryId, repository, List.of(), Optional.empty());
    }
}
