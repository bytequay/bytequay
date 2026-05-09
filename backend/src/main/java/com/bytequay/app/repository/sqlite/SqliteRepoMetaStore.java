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
package com.bytequay.app.repository.sqlite;

import com.bytequay.app.domain.RepoMeta;
import com.bytequay.app.domain.StoredRepoMeta;
import com.bytequay.app.repository.RepoMetaStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Repository
public class SqliteRepoMetaStore
        implements RepoMetaStore
{
    private final RepoMetaJpaRepository repo;

    public SqliteRepoMetaStore(RepoMetaJpaRepository repo)
    {
        this.repo = requireNonNull(repo, "repo is null");
    }

    @Override
    public Optional<StoredRepoMeta> find(String owner, String repo)
    {
        return this.repo.findById(new RepoMetaEntity.RepoMetaKey(owner, repo))
                .map(SqliteRepoMetaStore::toStored);
    }

    @Override
    @Transactional
    public void save(String owner, String repo, RepoMeta meta, Instant syncedAt)
    {
        RepoMetaEntity.RepoMetaKey key = new RepoMetaEntity.RepoMetaKey(owner, repo);
        RepoMetaEntity entity = this.repo.findById(key).orElseGet(RepoMetaEntity::new);
        entity.setId(key);
        entity.setFullName(meta.fullName());
        entity.setHtmlUrl(meta.htmlUrl());
        entity.setDescription(meta.description());
        entity.setDefaultBranch(meta.defaultBranch());
        entity.setLicense(meta.license());
        entity.setStargazersCount(meta.stargazersCount());
        entity.setForksCount(meta.forksCount());
        entity.setWatchersCount(meta.watchersCount());
        entity.setOpenIssuesCount(meta.openIssuesCount());
        entity.setSizeKb(meta.sizeKb());
        entity.setCreatedAt(meta.createdAt());
        entity.setPushedAt(meta.pushedAt());
        // Defensive copies — store implementations should not retain
        // references to caller-mutable collections.
        List<String> topics = meta.topics() == null
                ? ImmutableList.of()
                : ImmutableList.copyOf(meta.topics());
        Map<String, Long> languages = meta.languages() == null
                ? ImmutableMap.of()
                : ImmutableMap.copyOf(meta.languages());
        entity.setTopics(topics);
        entity.setLanguages(languages);
        entity.setOwnerAvatarUrl(meta.ownerAvatarUrl());
        entity.setParentOwner(meta.parentOwner());
        entity.setParentRepo(meta.parentName());
        entity.setParentDefaultBranch(meta.parentDefaultBranch());
        entity.setSyncedAt(syncedAt);
        this.repo.save(entity);
    }

    private static StoredRepoMeta toStored(RepoMetaEntity e)
    {
        RepoMeta meta = new RepoMeta(
                e.getFullName(),
                e.getHtmlUrl(),
                e.getDescription(),
                e.getDefaultBranch(),
                e.getLicense(),
                e.getStargazersCount(),
                e.getForksCount(),
                e.getWatchersCount(),
                e.getOpenIssuesCount(),
                e.getSizeKb(),
                e.getCreatedAt(),
                e.getPushedAt(),
                e.getTopics() == null ? ImmutableList.of() : ImmutableList.copyOf(e.getTopics()),
                e.getLanguages() == null ? ImmutableMap.of() : ImmutableMap.copyOf(e.getLanguages()),
                e.getOwnerAvatarUrl(),
                e.getParentOwner(),
                e.getParentRepo(),
                e.getParentDefaultBranch());
        return new StoredRepoMeta(meta, e.getSyncedAt());
    }
}
