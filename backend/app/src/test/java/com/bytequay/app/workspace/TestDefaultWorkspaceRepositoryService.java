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

import com.bytequay.app.repository.github.GitHubAccountClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.core.cherrypick.CherryPickExecutionStore;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.UserRepo;
import com.bytequay.core.domain.WatchedRepo;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.domain.Workspace.CodeState;
import com.bytequay.core.domain.Workspace.MetadataState;
import com.bytequay.core.domain.Workspace.Topology;
import com.bytequay.core.store.WatchedRepoStore;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestDefaultWorkspaceRepositoryService
{
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @Test
    void persistsDirectAndForkRoutingFromExactGitHubIdentity()
    {
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        when(gitHub.fetchRepository("token", "owner", "project"))
                .thenReturn(Optional.of(direct(100, "owner")));
        when(gitHub.fetchRepository("token", "contributor", "project"))
                .thenReturn(Optional.of(fork(200, 300)));
        DefaultWorkspaceRepositoryService service = service(store, gitHub, mock(WatchedRepoStore.class));

        Workspace direct = service.watch(command("owner"));
        Workspace fork = service.watch(command("contributor"));

        assertThat(direct.topology()).isEqualTo(Topology.DIRECT);
        assertThat(direct.monitoredRepositoryId()).isEqualTo(100);
        assertThat(direct.upstreamRepositoryId()).isEmpty();
        assertThat(fork.topology()).isEqualTo(Topology.FORK);
        assertThat(fork.upstreamRepositoryId()).contains(300L);
        assertThat(fork.monitoredRepository()).isEqualTo(RepoRef.of("upstream", "project"));
        assertThat(fork.defaultBranch()).isEqualTo("main");
    }

    @Test
    void concurrentRepeatReturnsOnePersistedWorkspace()
            throws Exception
    {
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        when(gitHub.fetchRepository("token", "owner", "project"))
                .thenReturn(Optional.of(direct(100, "owner")));
        DefaultWorkspaceRepositoryService service = service(store, gitHub, mock(WatchedRepoStore.class));
        List<Callable<Workspace>> watches = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            watches.add(() -> service.watch(command("owner")));
        }

        try (var executor = Executors.newFixedThreadPool(8)) {
            List<Workspace> results = executor.invokeAll(watches).stream()
                    .map(future -> {
                        try {
                            return future.get();
                        }
                        catch (Exception failure) {
                            throw new AssertionError(failure);
                        }
                    })
                    .collect(toImmutableList());
            assertThat(results).extracting(Workspace::id).containsOnly(results.getFirst().id());
        }
        assertThat(store.findAll()).hasSize(1);
    }

    @Test
    void migratesLegacyRowsOnlyAfterWorkspaceExists()
    {
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        WatchedRepoStore legacy = mock(WatchedRepoStore.class);
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        when(legacy.findAll()).thenReturn(List.of(new WatchedRepo(9, "owner", "project", 4)));
        when(gitHub.fetchRepository("token", "owner", "project"))
                .thenReturn(Optional.of(direct(100, "owner")));
        DefaultWorkspaceRepositoryService service = service(store, gitHub, legacy);

        List<WorkspaceSummary> migrated = service.listWorkspaces();

        assertThat(migrated).singleElement().satisfies(workspace -> {
            assertThat(workspace.selectedRepositoryId()).isEqualTo(100);
            assertThat(workspace.displayOrder()).isEqualTo(4);
        });
        verify(legacy).remove("owner", "project");
    }

    @Test
    void mapsMissingAndNotReadyWorkspacesToStableErrors()
    {
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        when(gitHub.fetchRepository("token", "owner", "project"))
                .thenReturn(Optional.of(direct(100, "owner")));
        DefaultWorkspaceRepositoryService service = service(store, gitHub, mock(WatchedRepoStore.class));
        Workspace pending = service.watch(command("owner"));

        assertThatThrownBy(() -> service.getWorkspace("missing"))
                .isInstanceOfSatisfying(WorkspaceException.class,
                        failure -> assertThat(failure.code()).isEqualTo("WORKSPACE_NOT_FOUND"));
        assertThatThrownBy(() -> service.requireReadyRepository(pending.id()))
                .isInstanceOfSatisfying(WorkspaceException.class,
                        failure -> assertThat(failure.code()).isEqualTo("WORKSPACE_CODE_NOT_READY"));
    }

    @Test
    void syncAlwaysQueuesCodeAndMetadata()
    {
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        when(gitHub.fetchRepository("token", "owner", "project"))
                .thenReturn(Optional.of(direct(100, "owner")));
        DefaultWorkspaceRepositoryService service = service(store, gitHub, mock(WatchedRepoStore.class));
        Workspace ready = store.save(service.watch(command("owner"))
                .withStates(CodeState.READY, MetadataState.READY, NOW));

        Workspace requested = service.requestSync(ready.id());

        assertThat(requested.codeState()).isEqualTo(CodeState.PENDING);
        assertThat(requested.metadataState()).isEqualTo(MetadataState.PENDING);
    }

    private static DefaultWorkspaceRepositoryService service(
            WorkspaceStore store,
            GitHubAccountClient gitHub,
            WatchedRepoStore legacy)
    {
        CredentialService credentials = mock(CredentialService.class);
        when(credentials.resolveGitHubToken()).thenReturn("token");
        CherryPickExecutionStore executions = mock(CherryPickExecutionStore.class);
        when(executions.listRecent(CherryPickExecutionStore.MAX_LIST_LIMIT)).thenReturn(List.of());
        return new DefaultWorkspaceRepositoryService(
                store,
                legacy,
                executions,
                gitHub,
                credentials,
                Clock.fixed(NOW, ZoneOffset.UTC),
                "/tmp/bytequay-workspaces");
    }

    private static WatchRepositoryCommand command(String owner)
    {
        return new WatchRepositoryCommand(owner, "project", Optional.empty());
    }

    private static UserRepo direct(long id, String owner)
    {
        return new UserRepo(
                id,
                owner,
                "project",
                owner + "/project",
                Optional.of("project"),
                false,
                "main",
                "https://github.com/" + owner + "/project.git",
                Optional.empty());
    }

    private static UserRepo fork(long id, long upstreamId)
    {
        return new UserRepo(
                id,
                "contributor",
                "project",
                "contributor/project",
                Optional.of("fork"),
                true,
                "feature-default",
                "https://github.com/contributor/project.git",
                Optional.of(new UserRepo.Upstream(
                        upstreamId,
                        "upstream",
                        "project",
                        "main",
                        "https://github.com/upstream/project.git")));
    }

    private static final class InMemoryWorkspaceStore
            implements WorkspaceStore
    {
        private final Map<String, Workspace> byId = new LinkedHashMap<>();

        @Override
        public synchronized List<Workspace> findAll()
        {
            return ImmutableList.copyOf(byId.values());
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
        }
    }
}
