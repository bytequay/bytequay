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
package com.bytequay.app.artifact;

import com.bytequay.core.domain.Artifact;
import com.bytequay.core.domain.ArtifactItem;
import com.bytequay.core.domain.ArtifactPage;
import com.bytequay.core.store.ArtifactStore;
import com.google.common.collect.ImmutableList;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;

/** The artifact store held in memory, for tests that read back what a turn wrote. */
final class MemoryArtifactStore
        implements ArtifactStore
{
    private final Map<String, Artifact> artifacts = new LinkedHashMap<>();
    private final List<ArtifactItem> items = new ArrayList<>();
    private final List<ArtifactPage> pages = new ArrayList<>();
    private final Map<String, String> html = new LinkedHashMap<>();

    @Override
    public synchronized Optional<Artifact> find(String workspaceId, String id)
    {
        return Optional.ofNullable(artifacts.get(id)).filter(artifact -> artifact.workspaceId().equals(workspaceId));
    }

    @Override
    public synchronized List<Artifact> list(String workspaceId)
    {
        return artifacts.values().stream().filter(artifact -> artifact.workspaceId().equals(workspaceId))
                .sorted(Comparator.comparing(Artifact::updatedAt).reversed()).collect(toImmutableList());
    }

    @Override
    public synchronized List<Artifact> working()
    {
        return artifacts.values().stream().filter(Artifact::working).collect(toImmutableList());
    }

    @Override
    public synchronized void save(Artifact artifact)
    {
        artifacts.put(artifact.id(), artifact);
    }

    @Override
    public synchronized List<ArtifactItem> items(String workspaceId, String artifactId)
    {
        return find(workspaceId, artifactId).isEmpty() ? ImmutableList.of() : items.stream().filter(item -> item.artifactId().equals(artifactId)).collect(toImmutableList());
    }

    @Override
    public synchronized void add(String workspaceId, ArtifactItem item)
    {
        items.add(item);
    }

    @Override
    public synchronized void update(ArtifactItem item)
    {
        items.replaceAll(existing -> existing.id().equals(item.id()) ? item : existing);
    }

    @Override
    public synchronized List<ArtifactPage> pages(String workspaceId, String artifactId)
    {
        return find(workspaceId, artifactId).isEmpty() ? ImmutableList.of() : pages.stream().filter(page -> page.artifactId().equals(artifactId)).collect(toImmutableList());
    }

    @Override
    public synchronized void savePage(String workspaceId, ArtifactPage page, String text)
    {
        pages.add(page);
        html.put(page.id(), text);
    }

    @Override
    public synchronized Optional<String> pageHtml(String workspaceId, String artifactId, String pageId)
    {
        return find(workspaceId, artifactId).flatMap(artifact -> Optional.ofNullable(html.get(pageId)));
    }

    @Override
    public synchronized void deleteWorkspace(String workspaceId)
    {
        List<String> gone = artifacts.values().stream().filter(artifact -> artifact.workspaceId().equals(workspaceId)).map(Artifact::id).collect(toImmutableList());
        artifacts.keySet().removeAll(gone);
        items.removeIf(item -> gone.contains(item.artifactId()));
        pages.removeIf(page -> gone.contains(page.artifactId()));
    }
}
