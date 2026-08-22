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
package com.bytequay.persistence.sqlite.store;

import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.RepositoryContributor;
import com.bytequay.core.domain.RepositoryLabel;
import com.bytequay.core.domain.RepositoryMetadataSnapshot;
import com.bytequay.core.store.RepositoryMetadataStore;
import com.bytequay.persistence.sqlite.entity.RepositoryMetadataEntity;
import com.bytequay.persistence.sqlite.repository.RepositoryMetadataJpaRepository;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Repository
public class SqliteRepositoryMetadataStore
        implements RepositoryMetadataStore
{
    private final RepositoryMetadataJpaRepository repository;

    public SqliteRepositoryMetadataStore(RepositoryMetadataJpaRepository repository)
    {
        this.repository = requireNonNull(repository, "repository is null");
    }

    @Override
    public Optional<RepositoryMetadataSnapshot> find(long repositoryId)
    {
        return repository.findById(repositoryId).map(SqliteRepositoryMetadataStore::toDomain);
    }

    @Override
    @Transactional
    public void upsertContributors(
            long repositoryId,
            RepoRef repositoryRef,
            List<RepositoryContributor> contributors,
            Instant syncedAt)
    {
        requireNonNull(contributors, "contributors is null");
        RepositoryMetadataEntity entity = entity(repositoryId, repositoryRef);
        Map<Long, RepositoryContributor> byId = new LinkedHashMap<>();
        entity.getContributors().forEach(value -> byId.put(value.id(), value));
        contributors.forEach(value -> byId.put(value.id(), value));
        entity.setContributors(ImmutableList.copyOf(byId.values()));
        entity.setContributorsSyncedAt(requireNonNull(syncedAt, "syncedAt is null"));
        repository.save(entity);
    }

    @Override
    @Transactional
    public void upsertLabelsAndTopics(
            long repositoryId,
            RepoRef repositoryRef,
            List<RepositoryLabel> labels,
            List<String> topics,
            Instant syncedAt)
    {
        requireNonNull(labels, "labels is null");
        requireNonNull(topics, "topics is null");
        RepositoryMetadataEntity entity = entity(repositoryId, repositoryRef);
        Map<Long, RepositoryLabel> byId = new LinkedHashMap<>();
        entity.getLabels().forEach(value -> byId.put(value.id(), value));
        labels.forEach(value -> byId.put(value.id(), value));
        entity.setLabels(ImmutableList.copyOf(byId.values()));
        entity.setTopics(ImmutableList.copyOf(topics));
        entity.setLabelsTopicsSyncedAt(requireNonNull(syncedAt, "syncedAt is null"));
        repository.save(entity);
    }

    private RepositoryMetadataEntity entity(long repositoryId, RepoRef repositoryRef)
    {
        requireNonNull(repositoryRef, "repositoryRef is null");
        RepositoryMetadataEntity entity = repository.findById(repositoryId)
                .orElseGet(RepositoryMetadataEntity::new);
        entity.setRepositoryId(repositoryId);
        entity.setOwner(repositoryRef.owner());
        entity.setRepo(repositoryRef.repo());
        if (entity.getContributors() == null) {
            entity.setContributors(ImmutableList.of());
        }
        if (entity.getLabels() == null) {
            entity.setLabels(ImmutableList.of());
        }
        if (entity.getTopics() == null) {
            entity.setTopics(ImmutableList.of());
        }
        return entity;
    }

    private static RepositoryMetadataSnapshot toDomain(RepositoryMetadataEntity entity)
    {
        return new RepositoryMetadataSnapshot(
                entity.getRepositoryId(),
                RepoRef.of(entity.getOwner(), entity.getRepo()),
                entity.getContributors(),
                entity.getLabels(),
                entity.getTopics(),
                Optional.ofNullable(entity.getContributorsSyncedAt()),
                Optional.ofNullable(entity.getLabelsTopicsSyncedAt()));
    }
}
