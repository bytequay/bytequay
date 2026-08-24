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

import com.bytequay.app.workspace.WorkspaceRemoteSourceService.WorkspaceRemote;
import com.bytequay.app.workspace.WorkspaceRemoteSourceService.WorkspaceRemote.Role;
import com.bytequay.core.domain.RemoteSource;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.domain.Workspace.CodeState;
import com.bytequay.core.domain.Workspace.MetadataState;
import com.bytequay.core.domain.Workspace.Topology;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestDefaultWorkspaceRemoteSourceService
{
    private static final Instant NOW = Instant.parse("2026-08-23T10:00:00Z");

    private InMemoryRemoteSourceStore store;
    private ManagedWorkspaceGit git;
    private WorkspaceRepositoryService repositoryService;
    private DefaultWorkspaceRemoteSourceService service;

    @BeforeEach
    void setUp()
    {
        store = new InMemoryRemoteSourceStore();
        git = mock(ManagedWorkspaceGit.class);
        repositoryService = mock(WorkspaceRepositoryService.class);
        WorkspaceStore workspaces = mock(WorkspaceStore.class);
        when(workspaces.findById("ws-1")).thenReturn(Optional.of(workspace(true)));
        when(workspaces.findById("ws-pending")).thenReturn(Optional.of(workspace(false)));
        service = new DefaultWorkspaceRemoteSourceService(workspaces, store, git, repositoryService);
    }

    @Test
    void listingIncludesRoutingRemotesWithRoles()
    {
        store.save(new RemoteSource("ws-1", "oss", "https://github.com/trino-oss/trino.git"));

        List<WorkspaceRemote> remotes = service.list("ws-1");

        assertThat(remotes).containsExactly(
                new WorkspaceRemote("origin", "https://github.com/contributor/trino.git", Role.ORIGIN),
                new WorkspaceRemote("upstream", "https://github.com/trinodb/trino.git", Role.UPSTREAM),
                new WorkspaceRemote("oss", "https://github.com/trino-oss/trino.git", Role.CUSTOM));
    }

    @Test
    void addProbesPersistsAndQueuesSync()
    {
        service.add("ws-1", "oss", "https://github.com/trino-oss/trino.git");

        verify(git).probeRemoteSource(any(), anyString());
        assertThat(store.findByWorkspace("ws-1"))
                .containsExactly(new RemoteSource("ws-1", "oss", "https://github.com/trino-oss/trino.git"));
        verify(repositoryService).requestSync("ws-1");
    }

    @Test
    void addSkipsTheProbeWhileTheCheckoutIsNotReady()
    {
        service.add("ws-pending", "oss", "https://github.com/trino-oss/trino.git");
        verify(git, never()).probeRemoteSource(any(), anyString());
        assertThat(store.findByWorkspace("ws-pending")).hasSize(1);
    }

    @Test
    void reservedAndInvalidNamesAreRejected()
    {
        for (String name : ImmutableList.of("origin", "upstream", "-oss", "a b", "")) {
            assertThatThrownBy(() -> service.add("ws-1", name, "https://github.com/x/y.git"))
                    .isInstanceOf(WorkspaceException.class);
        }
        assertThat(store.findByWorkspace("ws-1")).isEmpty();
    }

    @Test
    void unsafeUrlsAreRejected()
    {
        for (String url : ImmutableList.of(
                "http://github.com/x/y.git",
                "https://user:pass@github.com/x/y.git",
                "https://github.com/x/y.git?token=1",
                "git@github.com:x/y.git",
                "")) {
            assertThatThrownBy(() -> service.add("ws-1", "oss", url))
                    .isInstanceOf(WorkspaceException.class);
        }
    }

    @Test
    void duplicateNamesAreRejected()
    {
        service.add("ws-1", "oss", "https://github.com/trino-oss/trino.git");
        assertThatThrownBy(() -> service.add("ws-1", "oss", "https://github.com/other/trino.git"))
                .isInstanceOf(WorkspaceException.class);
    }

    @Test
    void removeDeletesAndQueuesSync()
    {
        store.save(new RemoteSource("ws-1", "oss", "https://github.com/trino-oss/trino.git"));

        service.remove("ws-1", "oss");

        assertThat(store.findByWorkspace("ws-1")).isEmpty();
        verify(repositoryService).requestSync("ws-1");
    }

    private static Workspace workspace(boolean ready)
    {
        return new Workspace(
                ready ? "ws-1" : "ws-pending",
                "contributor/trino",
                101,
                RepoRef.of("contributor", "trino"),
                Optional.of(202L),
                Optional.of(RepoRef.of("trinodb", "trino")),
                202,
                RepoRef.of("trinodb", "trino"),
                Topology.FORK,
                "master",
                "https://github.com/contributor/trino.git",
                Optional.of("master"),
                Optional.of("https://github.com/trinodb/trino.git"),
                "/managed/repository",
                ready ? CodeState.READY : CodeState.PENDING,
                MetadataState.READY,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                NOW,
                NOW);
    }
}
