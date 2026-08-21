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

/** Latest local reference metadata for one stable GitHub repository ID. */
public record RepositoryMetadataSnapshot(
        long repositoryId,
        RepoRef repository,
        List<RepositoryContributor> contributors,
        List<RepositoryLabel> labels,
        List<String> topics,
        Optional<Instant> contributorsSyncedAt,
        Optional<Instant> labelsTopicsSyncedAt)
{
    public RepositoryMetadataSnapshot
    {
        checkArgument(repositoryId > 0, "repositoryId must be positive");
        requireNonNull(repository, "repository is null");
        contributors = List.copyOf(requireNonNull(contributors, "contributors is null"));
        labels = List.copyOf(requireNonNull(labels, "labels is null"));
        topics = List.copyOf(requireNonNull(topics, "topics is null"));
        requireNonNull(contributorsSyncedAt, "contributorsSyncedAt is null");
        requireNonNull(labelsTopicsSyncedAt, "labelsTopicsSyncedAt is null");
    }

    public static RepositoryMetadataSnapshot empty(long repositoryId, RepoRef repository)
    {
        return new RepositoryMetadataSnapshot(
                repositoryId, repository, List.of(), List.of(), List.of(),
                Optional.empty(), Optional.empty());
    }
}
