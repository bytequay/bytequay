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

import com.bytequay.core.domain.Artifact;
import com.bytequay.core.store.ArtifactStore;
import com.bytequay.persistence.sqlite.entity.ArtifactEntity;
import com.bytequay.persistence.sqlite.repository.ArtifactJpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Objects.requireNonNull;

@Repository
public class SqliteArtifactStore
        implements ArtifactStore
{
    private final ArtifactJpaRepository artifacts;

    public SqliteArtifactStore(ArtifactJpaRepository artifacts)
    {
        this.artifacts = requireNonNull(artifacts, "artifacts is null");
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Artifact> find(String workspaceId, String id)
    {
        return artifacts.findById(id).map(ArtifactEntity::toDomain).filter(artifact -> artifact.workspaceId().equals(workspaceId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<Artifact> list(String workspaceId)
    {
        return artifacts.records(workspaceId).stream()
                .sorted(Comparator.comparing(Artifact::updatedAt).reversed())
                .collect(toImmutableList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Artifact> writing()
    {
        return artifacts.inState(Artifact.State.WRITING);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> page(String workspaceId, String id)
    {
        return artifacts.page(id, workspaceId);
    }

    @Override
    @Transactional
    public void save(Artifact artifact)
    {
        save(artifact, Optional.empty());
    }

    @Override
    @Transactional
    public void save(Artifact artifact, String page)
    {
        save(artifact, Optional.of(page));
    }

    private void save(Artifact artifact, Optional<String> page)
    {
        ArtifactEntity entity = artifacts.findById(artifact.id()).orElseGet(() -> new ArtifactEntity(artifact));
        entity.update(artifact, page);
        artifacts.save(entity);
    }

    @Override
    @Transactional
    public void deleteWorkspace(String workspaceId)
    {
        artifacts.deleteWorkspace(workspaceId);
    }
}
