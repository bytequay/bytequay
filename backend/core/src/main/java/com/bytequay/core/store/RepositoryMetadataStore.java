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
package com.bytequay.core.store;

import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryContributor;
import com.bytequay.core.domain.RepositoryLabel;
import com.bytequay.core.domain.RepositoryMetadataSnapshot;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface RepositoryMetadataStore
{
    Optional<RepositoryMetadataSnapshot> find(long repositoryId);

    void upsertOverview(
            long repositoryId,
            RepoRef repository,
            String description,
            int stargazersCount,
            List<String> topics,
            Map<String, Long> languages,
            Instant syncedAt);

    void upsertContributors(
            long repositoryId,
            RepoRef repository,
            List<RepositoryContributor> contributors,
            Instant syncedAt);

    void upsertLabelsAndTopics(
            long repositoryId,
            RepoRef repository,
            List<RepositoryLabel> labels,
            List<String> topics,
            Instant syncedAt);
}
