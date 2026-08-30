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

import com.bytequay.core.domain.RemoteSource;
import com.bytequay.core.store.RemoteSourceStore;
import com.bytequay.persistence.sqlite.entity.RemoteSourceEntity;
import com.bytequay.persistence.sqlite.repository.RemoteSourceJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteRemoteSourceStore
        implements RemoteSourceStore
{
    private final RemoteSourceJpaRepository repository;

    public SqliteRemoteSourceStore(RemoteSourceJpaRepository repository)
    {
        this.repository = requireNonNull(repository, "repository is null");
    }

    @Override
    @Transactional(readOnly = true)
    public List<RemoteSource> findByWorkspace(String workspaceId)
    {
        return repository.findByWorkspaceIdOrderByNameAsc(workspaceId).stream()
                .map(RemoteSourceEntity::toDomain)
                .collect(toImmutableList());
    }

    @Override
    @Transactional
    public void save(RemoteSource remoteSource)
    {
        requireNonNull(remoteSource, "remoteSource is null");
        repository.findById(RemoteSourceEntity.idOf(remoteSource.workspaceId(), remoteSource.name()))
                .ifPresentOrElse(
                        existing -> existing.update(remoteSource),
                        () -> repository.save(RemoteSourceEntity.fromDomain(remoteSource)));
    }

    @Override
    @Transactional
    public void delete(String workspaceId, String name)
    {
        repository.deleteById(RemoteSourceEntity.idOf(workspaceId, name));
    }

    @Override
    @Transactional
    public void deleteByWorkspace(String workspaceId)
    {
        repository.deleteByWorkspaceId(workspaceId);
    }
}
