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

import com.bytequay.core.domain.CommitEnrichment;
import com.bytequay.core.domain.CommitEnrichmentSnapshot;
import com.bytequay.core.domain.CommitRefreshStatus;
import com.bytequay.core.store.CommitHistoryStore;
import com.bytequay.persistence.sqlite.entity.CommitEnrichmentEntity;
import com.bytequay.persistence.sqlite.entity.CommitRefreshStatusEntity;
import com.bytequay.persistence.sqlite.repository.CommitEnrichmentJpaRepository;
import com.bytequay.persistence.sqlite.repository.CommitRefreshStatusJpaRepository;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.collect.ImmutableMap.toImmutableMap;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteCommitHistoryStore
        implements CommitHistoryStore
{
    private final CommitEnrichmentJpaRepository enrichment;
    private final CommitRefreshStatusJpaRepository refreshStatus;

    public SqliteCommitHistoryStore(
            CommitEnrichmentJpaRepository enrichment,
            CommitRefreshStatusJpaRepository refreshStatus)
    {
        this.enrichment = requireNonNull(enrichment, "enrichment is null");
        this.refreshStatus = requireNonNull(refreshStatus, "refreshStatus is null");
    }

    @Override
    public CommitEnrichmentSnapshot findEnrichment(long repositoryId, Collection<String> shas)
    {
        requireNonNull(shas, "shas is null");
        List<CommitEnrichmentEntity.Key> keys = shas.stream()
                .map(sha -> new CommitEnrichmentEntity.Key(repositoryId, sha))
                .collect(ImmutableList.toImmutableList());
        return new CommitEnrichmentSnapshot(enrichment.findAllById(keys).stream()
                .map(SqliteCommitHistoryStore::toDomain)
                .collect(ImmutableList.toImmutableList()));
    }

    @Override
    @Transactional
    public void upsertEnrichment(Collection<CommitEnrichment> values)
    {
        requireNonNull(values, "enrichment is null");
        List<CommitEnrichmentEntity.Key> keys = values.stream()
                .map(value -> new CommitEnrichmentEntity.Key(value.repositoryId(), value.sha()))
                .collect(ImmutableList.toImmutableList());
        Map<CommitEnrichmentEntity.Key, CommitEnrichmentEntity> existing = enrichment.findAllById(keys).stream()
                .collect(toImmutableMap(CommitEnrichmentEntity::getId, entity -> entity));
        List<CommitEnrichmentEntity> updated = values.stream()
                .map(value -> merge(existing.get(new CommitEnrichmentEntity.Key(value.repositoryId(), value.sha())), value))
                .collect(ImmutableList.toImmutableList());
        enrichment.saveAll(updated);
    }

    @Override
    public CommitRefreshStatus findRefreshStatus(String workspaceId)
    {
        requireNonNull(workspaceId, "workspaceId is null");
        return refreshStatus.findById(workspaceId)
                .map(SqliteCommitHistoryStore::toDomain)
                .orElseGet(() -> CommitRefreshStatus.idle(workspaceId));
    }

    @Override
    @Transactional
    public void saveRefreshStatus(CommitRefreshStatus status)
    {
        requireNonNull(status, "status is null");
        CommitRefreshStatusEntity entity = refreshStatus.findById(status.workspaceId())
                .orElseGet(CommitRefreshStatusEntity::new);
        entity.setWorkspaceId(status.workspaceId());
        entity.setState(status.state());
        entity.setLastAttemptAt(status.lastAttemptAt().orElse(null));
        entity.setLastSucceededAt(status.lastSucceededAt().orElse(entity.getLastSucceededAt()));
        entity.setError(status.error().orElse(null));
        refreshStatus.save(entity);
    }

    private static CommitEnrichmentEntity merge(CommitEnrichmentEntity entity, CommitEnrichment value)
    {
        if (entity == null) {
            entity = new CommitEnrichmentEntity();
            entity.setId(new CommitEnrichmentEntity.Key(value.repositoryId(), value.sha()));
        }
        entity.setAvatarKey(value.avatarKey().orElse(entity.getAvatarKey()));
        entity.setPullRequestNumber(value.pullRequestNumber().orElse(entity.getPullRequestNumber()));
        entity.setPullRequestTitle(value.pullRequestTitle().orElse(entity.getPullRequestTitle()));
        entity.setPullRequestUrl(value.pullRequestUrl().orElse(entity.getPullRequestUrl()));
        entity.setAvatarCheckedAt(value.avatarCheckedAt().orElse(entity.getAvatarCheckedAt()));
        entity.setAssociationCheckedAt(value.associationCheckedAt().orElse(entity.getAssociationCheckedAt()));
        entity.setLastSeenAt(value.lastSeenAt());
        return entity;
    }

    private static CommitEnrichment toDomain(CommitEnrichmentEntity entity)
    {
        return new CommitEnrichment(
                entity.getId().getRepositoryId(),
                entity.getId().getSha(),
                Optional.ofNullable(entity.getAvatarKey()),
                Optional.ofNullable(entity.getPullRequestNumber()),
                Optional.ofNullable(entity.getPullRequestTitle()),
                Optional.ofNullable(entity.getPullRequestUrl()),
                Optional.ofNullable(entity.getAvatarCheckedAt()),
                Optional.ofNullable(entity.getAssociationCheckedAt()),
                entity.getLastSeenAt());
    }

    private static CommitRefreshStatus toDomain(CommitRefreshStatusEntity entity)
    {
        return new CommitRefreshStatus(
                entity.getWorkspaceId(),
                entity.getState(),
                Optional.ofNullable(entity.getLastAttemptAt()),
                Optional.ofNullable(entity.getLastSucceededAt()),
                Optional.ofNullable(entity.getError()));
    }
}
