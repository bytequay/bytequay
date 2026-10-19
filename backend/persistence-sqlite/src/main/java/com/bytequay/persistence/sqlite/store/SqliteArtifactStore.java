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
import com.bytequay.core.domain.ArtifactItem;
import com.bytequay.core.domain.ArtifactPage;
import com.bytequay.core.store.ArtifactStore;
import com.bytequay.persistence.sqlite.entity.ArtifactEntity;
import com.bytequay.persistence.sqlite.entity.ArtifactItemEntity;
import com.bytequay.persistence.sqlite.entity.ArtifactPageEntity;
import com.bytequay.persistence.sqlite.repository.ArtifactItemJpaRepository;
import com.bytequay.persistence.sqlite.repository.ArtifactJpaRepository;
import com.bytequay.persistence.sqlite.repository.ArtifactPageJpaRepository;
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
    private final ArtifactItemJpaRepository items;
    private final ArtifactPageJpaRepository pages;

    public SqliteArtifactStore(ArtifactJpaRepository artifacts, ArtifactItemJpaRepository items, ArtifactPageJpaRepository pages)
    {
        this.artifacts = requireNonNull(artifacts, "artifacts is null");
        this.items = requireNonNull(items, "items is null");
        this.pages = requireNonNull(pages, "pages is null");
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
        // Sorted here: ISO-8601 text with varying fraction digits does not sort by time in SQL.
        return artifacts.records(workspaceId).stream()
                .sorted(Comparator.comparing(Artifact::updatedAt).reversed())
                .collect(toImmutableList());
    }

    @Override
    @Transactional(readOnly = true)
    public List<Artifact> working()
    {
        return artifacts.working();
    }

    @Override
    @Transactional
    public void save(Artifact artifact)
    {
        ArtifactEntity entity = artifacts.findById(artifact.id()).orElseGet(() -> new ArtifactEntity(artifact));
        entity.update(artifact);
        artifacts.save(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ArtifactItem> items(String workspaceId, String artifactId)
    {
        return items.items(workspaceId, artifactId);
    }

    @Override
    @Transactional
    public void add(String workspaceId, ArtifactItem item)
    {
        items.save(new ArtifactItemEntity(workspaceId, item, items.countByArtifactId(item.artifactId())));
    }

    @Override
    @Transactional
    public void update(ArtifactItem item)
    {
        ArtifactItemEntity entity = items.findById(item.id()).orElseThrow(() -> new IllegalArgumentException("Unknown artifact item"));
        entity.update(item);
        items.save(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ArtifactPage> pages(String workspaceId, String artifactId)
    {
        return pages.pages(workspaceId, artifactId);
    }

    @Override
    @Transactional
    public void savePage(String workspaceId, ArtifactPage page, String html)
    {
        pages.save(new ArtifactPageEntity(workspaceId, page, html, pages.countByArtifactId(page.artifactId())));
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<String> pageHtml(String workspaceId, String artifactId, String pageId)
    {
        return pages.html(workspaceId, artifactId, pageId);
    }

    @Override
    @Transactional
    public void deleteWorkspace(String workspaceId)
    {
        items.deleteWorkspace(workspaceId);
        pages.deleteWorkspace(workspaceId);
        artifacts.deleteWorkspace(workspaceId);
    }
}
