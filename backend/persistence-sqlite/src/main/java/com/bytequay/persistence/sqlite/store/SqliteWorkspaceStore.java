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

import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.WorkspaceStore;
import com.bytequay.persistence.sqlite.entity.WorkspaceEntity;
import com.bytequay.persistence.sqlite.repository.WorkspaceJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteWorkspaceStore
        implements WorkspaceStore
{
    private final WorkspaceJpaRepository repository;

    public SqliteWorkspaceStore(WorkspaceJpaRepository repository)
    {
        this.repository = requireNonNull(repository, "repository is null");
    }

    @Override
    @Transactional(readOnly = true)
    public List<Workspace> findAll()
    {
        return repository.findAllByOrderByDisplayOrderAscCreatedAtAsc().stream()
                .map(WorkspaceEntity::toDomain)
                .collect(toImmutableList());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Workspace> findById(String workspaceId)
    {
        return repository.findById(workspaceId).map(WorkspaceEntity::toDomain);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Workspace> findBySelectedRepositoryId(long selectedRepositoryId)
    {
        return repository.findBySelectedRepositoryId(selectedRepositoryId)
                .map(WorkspaceEntity::toDomain);
    }

    @Override
    public Workspace createOrGet(Workspace workspace)
    {
        requireNonNull(workspace, "workspace is null");
        Optional<Workspace> existing = findBySelectedRepositoryId(workspace.selectedRepositoryId());
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }
        return repository.saveAndFlush(WorkspaceEntity.fromDomain(workspace)).toDomain();
    }

    @Override
    @Transactional
    public Workspace save(Workspace workspace)
    {
        WorkspaceEntity entity = repository.findById(workspace.id())
                .orElseThrow(() -> new IllegalArgumentException("Workspace not found: " + workspace.id()));
        entity.updateFrom(workspace);
        return repository.saveAndFlush(entity).toDomain();
    }

    @Override
    @Transactional
    public void delete(String workspaceId)
    {
        repository.deleteById(workspaceId);
    }
}
