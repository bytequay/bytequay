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
import com.google.common.collect.ImmutableMap;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.base.Preconditions.checkArgument;
import static java.util.Objects.requireNonNull;

/** Latest local reference metadata for one stable GitHub repository ID. */
public record RepositoryMetadataSnapshot(
        long repositoryId,
        RepoRef repository,
        String description,
        int stargazersCount,
        Map<String, Long> languages,
        List<RepositoryContributor> contributors,
        List<RepositoryPickerUser> pickerUsers,
        List<RepositoryLabel> labels,
        List<String> topics,
        Optional<Instant> overviewSyncedAt,
        Optional<Instant> contributorsSyncedAt,
        Optional<Instant> pickerUsersSyncedAt,
        Optional<Instant> labelsTopicsSyncedAt)
{
    public RepositoryMetadataSnapshot
    {
        checkArgument(repositoryId > 0, "repositoryId must be positive");
        requireNonNull(repository, "repository is null");
        checkArgument(stargazersCount >= 0, "stargazersCount is negative");
        languages = ImmutableMap.copyOf(requireNonNull(languages, "languages is null"));
        contributors = ImmutableList.copyOf(requireNonNull(contributors, "contributors is null"));
        pickerUsers = ImmutableList.copyOf(requireNonNull(pickerUsers, "pickerUsers is null"));
        labels = ImmutableList.copyOf(requireNonNull(labels, "labels is null"));
        topics = ImmutableList.copyOf(requireNonNull(topics, "topics is null"));
        requireNonNull(overviewSyncedAt, "overviewSyncedAt is null");
        requireNonNull(contributorsSyncedAt, "contributorsSyncedAt is null");
        requireNonNull(pickerUsersSyncedAt, "pickerUsersSyncedAt is null");
        requireNonNull(labelsTopicsSyncedAt, "labelsTopicsSyncedAt is null");
    }

    public static RepositoryMetadataSnapshot empty(long repositoryId, RepoRef repository)
    {
        return new RepositoryMetadataSnapshot(
                repositoryId, repository, null, 0, ImmutableMap.of(),
                ImmutableList.of(), ImmutableList.of(), ImmutableList.of(), ImmutableList.of(),
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty());
    }
}
