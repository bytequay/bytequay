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
package com.bytequay.app.workspace;

import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.collect.ImmutableList.toImmutableList;

final class InMemoryWorkspaceStore
        implements WorkspaceStore
{
    private final Map<String, Workspace> byId = new LinkedHashMap<>();
    private final Set<String> unwatched = new HashSet<>();

    @Override
    public synchronized List<Workspace> findAll()
    {
        return byId.values().stream().filter(workspace -> !unwatched.contains(workspace.id())).collect(toImmutableList());
    }

    @Override
    public synchronized List<Workspace> findAllRetained()
    {
        return ImmutableList.copyOf(byId.values());
    }

    @Override
    public synchronized void setWatched(String workspaceId, boolean watched)
    {
        checkArgument(byId.containsKey(workspaceId), "Workspace not found: %s", workspaceId);
        if (watched) {
            unwatched.remove(workspaceId);
        }
        else {
            unwatched.add(workspaceId);
        }
    }

    @Override
    public synchronized boolean isWatched(String workspaceId)
    {
        return byId.containsKey(workspaceId) && !unwatched.contains(workspaceId);
    }

    @Override
    public boolean hasActiveWorkflows(String workspaceId)
    {
        return false;
    }

    @Override
    public synchronized Optional<Workspace> findById(String workspaceId)
    {
        return Optional.ofNullable(byId.get(workspaceId));
    }

    @Override
    public synchronized Optional<Workspace> findBySelectedRepositoryId(long selectedRepositoryId)
    {
        return byId.values().stream()
                .filter(workspace -> workspace.selectedRepositoryId() == selectedRepositoryId)
                .findFirst();
    }

    @Override
    public synchronized Workspace createOrGet(Workspace workspace)
    {
        Optional<Workspace> existing = findBySelectedRepositoryId(workspace.selectedRepositoryId());
        if (existing.isPresent()) {
            return existing.orElseThrow();
        }
        byId.put(workspace.id(), workspace);
        return workspace;
    }

    @Override
    public synchronized Workspace save(Workspace workspace)
    {
        byId.put(workspace.id(), workspace);
        return workspace;
    }

    @Override
    public synchronized void delete(String workspaceId)
    {
        byId.remove(workspaceId);
        unwatched.remove(workspaceId);
    }
}
