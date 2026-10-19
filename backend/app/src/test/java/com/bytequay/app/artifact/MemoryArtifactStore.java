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
import com.bytequay.core.store.ArtifactStore;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.google.common.collect.ImmutableList.toImmutableList;

/** The artifact store held in memory, for tests that read back what a write saved. */
final class MemoryArtifactStore
        implements ArtifactStore
{
    private final Map<String, Artifact> records = new LinkedHashMap<>();
    private final Map<String, String> pages = new LinkedHashMap<>();

    @Override
    public synchronized Optional<Artifact> find(String workspaceId, String id)
    {
        return Optional.ofNullable(records.get(id)).filter(artifact -> artifact.workspaceId().equals(workspaceId));
    }

    @Override
    public synchronized List<Artifact> list(String workspaceId)
    {
        return records.values().stream().filter(artifact -> artifact.workspaceId().equals(workspaceId))
                .sorted(Comparator.comparing(Artifact::updatedAt).reversed()).collect(toImmutableList());
    }

    @Override
    public synchronized List<Artifact> writing()
    {
        return records.values().stream().filter(artifact -> artifact.state() == Artifact.State.WRITING).collect(toImmutableList());
    }

    @Override
    public synchronized Optional<String> page(String workspaceId, String id)
    {
        return find(workspaceId, id).map(artifact -> pages.get(id));
    }

    @Override
    public synchronized void save(Artifact artifact)
    {
        records.put(artifact.id(), artifact);
    }

    @Override
    public synchronized void save(Artifact artifact, String page)
    {
        records.put(artifact.id(), artifact);
        pages.put(artifact.id(), page);
    }

    @Override
    public synchronized void deleteWorkspace(String workspaceId)
    {
        records.values().removeIf(artifact -> {
            boolean gone = artifact.workspaceId().equals(workspaceId);
            if (gone) {
                pages.remove(artifact.id());
            }
            return gone;
        });
    }
}
