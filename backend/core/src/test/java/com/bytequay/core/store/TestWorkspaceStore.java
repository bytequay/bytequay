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
package com.bytequay.core.store;

import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.domain.Workspace.Topology;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;

class TestWorkspaceStore
{
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @Test
    void findsTheWorkspaceOnAHostingRepositoryPreferringAWatchedOne()
    {
        WorkspaceStore workspaces = mock(WorkspaceStore.class, CALLS_REAL_METHODS);
        Workspace unwatched = workspace("unwatched");
        Workspace watched = workspace("watched");
        Workspace other = workspace("other");
        doReturn(ImmutableList.of(unwatched, other, watched)).when(workspaces).findAllRetained();
        doReturn(Optional.of(20L)).when(workspaces).findHostingRepositoryId("unwatched");
        doReturn(Optional.of(20L)).when(workspaces).findHostingRepositoryId("watched");
        doReturn(Optional.of(30L)).when(workspaces).findHostingRepositoryId("other");
        doReturn(false).when(workspaces).isWatched("unwatched");
        doReturn(true).when(workspaces).isWatched("watched");
        doReturn(false).when(workspaces).isWatched("other");

        assertThat(workspaces.findByHostingRepositoryId(20)).contains(watched);
        assertThat(workspaces.findByHostingRepositoryId(30)).contains(other);
        assertThat(workspaces.findByHostingRepositoryId(40)).isEmpty();
    }

    private static Workspace workspace(String id)
    {
        RepoRef repository = RepoRef.of("owner", id);
        return new Workspace(id, id, 100, repository, Optional.empty(), Optional.empty(), 100, repository, Topology.DIRECT, "main",
                "https://example.com/owner/" + id + ".git", Optional.empty(), Optional.empty(), "/tmp/" + id, Optional.empty(), Optional.empty(), 0, NOW, NOW);
    }
}
