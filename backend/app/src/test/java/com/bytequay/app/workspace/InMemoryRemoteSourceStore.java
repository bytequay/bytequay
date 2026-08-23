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

import com.bytequay.core.domain.RemoteSource;
import com.bytequay.core.store.RemoteSourceStore;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class InMemoryRemoteSourceStore
        implements RemoteSourceStore
{
    private final Map<String, RemoteSource> rows = new ConcurrentHashMap<>();

    @Override
    public List<RemoteSource> findByWorkspace(String workspaceId)
    {
        return rows.values().stream()
                .filter(source -> source.workspaceId().equals(workspaceId))
                .sorted(Comparator.comparing(RemoteSource::name))
                .toList();
    }

    @Override
    public void save(RemoteSource remoteSource)
    {
        rows.put(remoteSource.workspaceId() + ":" + remoteSource.name(), remoteSource);
    }

    @Override
    public void delete(String workspaceId, String name)
    {
        rows.remove(workspaceId + ":" + name);
    }

    @Override
    public void deleteByWorkspace(String workspaceId)
    {
        rows.values().removeIf(source -> source.workspaceId().equals(workspaceId));
    }
}
