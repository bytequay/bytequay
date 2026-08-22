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
import com.bytequay.core.domain.RepositoryIssue;
import com.bytequay.core.domain.RepositoryIssueSnapshot;
import com.bytequay.core.store.RepositoryIssueStore;
import com.bytequay.persistence.sqlite.entity.RepositoryIssueSnapshotEntity;
import com.bytequay.persistence.sqlite.repository.RepositoryIssueJpaRepository;
import com.google.common.collect.ImmutableList;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Objects.requireNonNull;

@Repository
public class SqliteRepositoryIssueStore
        implements RepositoryIssueStore
{
    private final RepositoryIssueJpaRepository repository;

    public SqliteRepositoryIssueStore(RepositoryIssueJpaRepository repository)
    {
        this.repository = requireNonNull(repository, "repository is null");
    }

    @Override
    public Optional<RepositoryIssueSnapshot> find(long repositoryId)
    {
        return repository.findById(repositoryId).map(entity -> new RepositoryIssueSnapshot(
                entity.getRepositoryId(),
                RepoRef.of(entity.getOwner(), entity.getRepo()),
                entity.getIssues(),
                Optional.of(entity.getSyncedAt())));
    }

    @Override
    @Transactional
    public void replace(
            long repositoryId,
            RepoRef repositoryRef,
            List<RepositoryIssue> issues,
            Instant syncedAt)
    {
        requireNonNull(repositoryRef, "repositoryRef is null");
        RepositoryIssueSnapshotEntity entity = repository.findById(repositoryId)
                .orElseGet(RepositoryIssueSnapshotEntity::new);
        entity.setRepositoryId(repositoryId);
        entity.setOwner(repositoryRef.owner());
        entity.setRepo(repositoryRef.repo());
        entity.setIssues(ImmutableList.copyOf(requireNonNull(issues, "issues is null")));
        entity.setSyncedAt(requireNonNull(syncedAt, "syncedAt is null"));
        repository.save(entity);
    }
}
