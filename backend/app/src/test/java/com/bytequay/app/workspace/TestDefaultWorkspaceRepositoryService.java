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
import com.bytequay.app.scheduler.PullRequestSyncJob;
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
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestDefaultWorkspaceRepositoryService
{
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @Test
    void watchingPersistsSelectedRepositoryWithoutInferringAnUpstream()
    {
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        when(gitHub.fetchRepository("token", 100L))
                .thenReturn(Optional.of(direct(100, "owner")));
        when(gitHub.fetchRepository("token", 200L))
                .thenReturn(Optional.of(fork(200, 300)));
        DefaultWorkspaceRepositoryService service = service(store, gitHub, mock(WatchedRepoStore.class));

        WatchRepositoryPreview directPreview = service.previewWatch(100);
        Workspace direct = service.watch(new WatchRepositoryCommand(
                100, directPreview.fingerprint(), Optional.empty()));
        Workspace fork = watch(service, 200);

        assertThat(directPreview.topology()).isEqualTo(Topology.DIRECT);
        assertThat(directPreview.upstreamRepository()).isEmpty();
        assertThat(directPreview.monitoredRepository()).isEqualTo(RepoRef.of("owner", "project"));
        assertThat(directPreview.developmentBase()).isEqualTo("origin/main");
        assertThat(directPreview.pushRemote()).isEqualTo("origin");
        assertThat(directPreview.pushWritable()).isTrue();
        assertThat(directPreview.pullRequestBase()).isEqualTo(RepoRef.of("owner", "project"));
        assertThat(direct.topology()).isEqualTo(Topology.DIRECT);
        assertThat(direct.monitoredRepositoryId()).isEqualTo(100);
        assertThat(direct.upstreamRepositoryId()).isEmpty();
        assertThat(fork.topology()).isEqualTo(Topology.FORK);
        assertThat(fork.upstreamWorkspaceId()).isEmpty();
        assertThat(fork.upstreamRepositoryId()).isEmpty();
        assertThat(fork.monitoredRepository()).isEqualTo(RepoRef.of("contributor", "project"));
        assertThat(fork.defaultBranch()).isEqualTo("feature-default");
    }

    @Test
    void concurrentRepeatReturnsOnePersistedWorkspace()
            throws Exception
    {
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        when(gitHub.fetchRepository("token", 100L))
                .thenReturn(Optional.of(direct(100, "owner")));
        DefaultWorkspaceRepositoryService service = service(store, gitHub, mock(WatchedRepoStore.class));
        WatchRepositoryPreview preview = service.previewWatch(100);
        WatchRepositoryCommand command = new WatchRepositoryCommand(100, preview.fingerprint(), Optional.empty());
        List<Callable<Workspace>> watches = new ArrayList<>();
        for (int index = 0; index < 8; index++) {
            watches.add(() -> service.watch(command));
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
        when(legacy.findAll()).thenReturn(ImmutableList.of(new WatchedRepo(9, "owner", "project", 4)));
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
        when(gitHub.fetchRepository("token", 100L))
                .thenReturn(Optional.of(direct(100, "owner")));
        DefaultWorkspaceRepositoryService service = service(store, gitHub, mock(WatchedRepoStore.class));
        Workspace pending = watch(service, 100);

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
        when(gitHub.fetchRepository("token", 100L))
                .thenReturn(Optional.of(direct(100, "owner")));
        DefaultWorkspaceRepositoryService service = service(store, gitHub, mock(WatchedRepoStore.class));
        Workspace ready = store.save(watch(service, 100)
                .withStates(CodeState.READY, MetadataState.READY, NOW));

        Workspace requested = service.requestSync(ready.id());

        assertThat(requested.codeState()).isEqualTo(CodeState.SYNCING);
        assertThat(requested.metadataState()).isEqualTo(MetadataState.PENDING);
    }

    @Test
    void persistsPendingWorkspaceBeforeSchedulingProvisioning()
    {
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        when(gitHub.fetchRepository("token", 100L))
                .thenReturn(Optional.of(direct(100, "owner")));
        CredentialService credentials = mock(CredentialService.class);
        when(credentials.resolveGitHubToken()).thenReturn("token");
        CherryPickExecutionStore executions = mock(CherryPickExecutionStore.class);
        WorkspaceProvisioningService provisioning = mock(WorkspaceProvisioningService.class);
        PullRequestSyncJob pullRequestSync = mock(PullRequestSyncJob.class);
        AtomicReference<CodeState> observed = new AtomicReference<>();
        when(provisioning.provision(anyString())).thenAnswer(invocation -> {
            Workspace saved = store.findById(invocation.getArgument(0)).orElseThrow();
            observed.set(saved.codeState());
            return WorkspaceProvisioningResult.of(saved);
        });
        DefaultWorkspaceRepositoryService service = new DefaultWorkspaceRepositoryService(
                store,
                mock(WatchedRepoStore.class),
                executions,
                gitHub,
                credentials,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new WorkspacePaths("/tmp/bytequay-workspaces"),
                provisioning,
                mock(WorkspaceMetadataService.class),
                pullRequestSync,
                Runnable::run);

        watch(service, 100);

        assertThat(observed).hasValue(CodeState.PENDING);
        verify(pullRequestSync).requestImmediateSync();
    }

    private static DefaultWorkspaceRepositoryService service(
            WorkspaceStore store,
            GitHubAccountClient gitHub,
            WatchedRepoStore legacy)
    {
        CredentialService credentials = mock(CredentialService.class);
        when(credentials.resolveGitHubToken()).thenReturn("token");
        CherryPickExecutionStore executions = mock(CherryPickExecutionStore.class);
        when(executions.listRecent(CherryPickExecutionStore.MAX_LIST_LIMIT)).thenReturn(ImmutableList.of());
        return new DefaultWorkspaceRepositoryService(
                store,
                legacy,
                executions,
                gitHub,
                credentials,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new WorkspacePaths("/tmp/bytequay-workspaces"),
                mock(WorkspaceProvisioningService.class),
                mock(WorkspaceMetadataService.class),
                mock(PullRequestSyncJob.class),
                ignored -> {});
    }

    @Test
    void previewShowsExactRoutingAndRejectsPermissionChanges()
    {
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        when(gitHub.fetchRepository("token", 200L))
                .thenReturn(Optional.of(fork(200, 300, true)))
                .thenReturn(Optional.of(fork(200, 300, false)));
        DefaultWorkspaceRepositoryService service = service(store, gitHub, mock(WatchedRepoStore.class));

        WatchRepositoryPreview preview = service.previewWatch(200);

        assertThat(preview.topology()).isEqualTo(Topology.FORK);
        assertThat(preview.upstreamRepository()).contains(RepoRef.of("upstream", "project"));
        assertThat(preview.monitoredRepository()).isEqualTo(RepoRef.of("contributor", "project"));
        assertThat(preview.developmentBase()).isEqualTo("origin/feature-default");
        assertThat(preview.pushRemote()).isEqualTo("origin");
        assertThat(preview.pushWritable()).isTrue();
        assertThat(preview.pullRequestBase()).isEqualTo(RepoRef.of("contributor", "project"));
        assertThatThrownBy(() -> service.watch(new WatchRepositoryCommand(
                200, preview.fingerprint(), Optional.empty())))
                .isInstanceOfSatisfying(WorkspaceException.class,
                        failure -> assertThat(failure.code()).isEqualTo("WATCH_PREVIEW_CHANGED"));
        assertThat(store.findAll()).isEmpty();
    }

    @Test
    void linksAndUnlinksOnlyAnotherWatchedWorkspace()
    {
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        when(gitHub.fetchRepository("token", 100L)).thenReturn(Optional.of(direct(100, "contributor")));
        when(gitHub.fetchRepository("token", 300L)).thenReturn(Optional.of(direct(300, "upstream")));
        DefaultWorkspaceRepositoryService service = service(store, gitHub, mock(WatchedRepoStore.class));
        Workspace selected = store.save(watch(service, 100).withStates(CodeState.READY, MetadataState.READY, NOW));
        Workspace upstream = store.save(watch(service, 300).withStates(CodeState.READY, MetadataState.READY, NOW));

        Workspace linked = service.setUpstream(selected.id(), Optional.of(upstream.id()));

        assertThat(linked.upstreamWorkspaceId()).contains(upstream.id());
        assertThat(linked.upstreamRepository()).contains(upstream.selectedRepository());
        assertThat(linked.monitoredRepository()).isEqualTo(upstream.selectedRepository());
        assertThat(linked.upstreamDefaultBranch()).contains(upstream.defaultBranch());
        assertThatThrownBy(() -> service.unwatch(upstream.id()))
                .isInstanceOfSatisfying(WorkspaceException.class,
                        failure -> assertThat(failure.code()).isEqualTo("WORKSPACE_UPSTREAM_IN_USE"));

        store.save(linked.withStates(CodeState.READY, MetadataState.READY, NOW));
        Workspace unlinked = service.setUpstream(selected.id(), Optional.empty());

        assertThat(unlinked.upstreamWorkspaceId()).isEmpty();
        assertThat(unlinked.upstreamRepository()).isEmpty();
        assertThat(unlinked.monitoredRepository()).isEqualTo(selected.selectedRepository());
    }

    @Test
    void rejectsSelfAndUnwatchedUpstreamLinks()
    {
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        when(gitHub.fetchRepository("token", 100L)).thenReturn(Optional.of(direct(100, "owner")));
        DefaultWorkspaceRepositoryService service = service(store, gitHub, mock(WatchedRepoStore.class));
        Workspace selected = store.save(watch(service, 100).withStates(CodeState.READY, MetadataState.READY, NOW));

        assertThatThrownBy(() -> service.setUpstream(selected.id(), Optional.of(selected.id())))
                .isInstanceOfSatisfying(WorkspaceException.class,
                        failure -> assertThat(failure.code()).isEqualTo("WORKSPACE_UPSTREAM_INVALID"));
        assertThatThrownBy(() -> service.setUpstream(selected.id(), Optional.of("not-watched")))
                .isInstanceOfSatisfying(WorkspaceException.class,
                        failure -> assertThat(failure.code()).isEqualTo("WORKSPACE_UPSTREAM_INVALID"));
    }

    @Test
    void searchIncludesStableIdentityAndExistingWorkspace()
    {
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        UserRepo repository = direct(100, "owner");
        when(gitHub.fetchRepository("token", 100L)).thenReturn(Optional.of(repository));
        when(gitHub.fetchUserRepos("token")).thenReturn(ImmutableList.of(repository));
        DefaultWorkspaceRepositoryService service = service(store, gitHub, mock(WatchedRepoStore.class));
        Workspace watched = watch(service, 100);

        List<RepositorySearchResult> results = service.searchRepositories("", 30);

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.id()).isEqualTo(100);
            assertThat(result.visibility()).isEqualTo("public");
            assertThat(result.viewerCanPush()).isTrue();
            assertThat(result.existingWorkspaceId()).contains(watched.id());
        });
    }

    @Test
    void rejectsCredentialBearingCloneUrlsBeforePreviewOrPersistence()
    {
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        UserRepo unsafe = new UserRepo(
                100,
                "owner",
                "project",
                "owner/project",
                Optional.empty(),
                false,
                "private",
                true,
                "main",
                "https://secret@github.com/owner/project.git",
                Optional.empty());
        when(gitHub.fetchRepository("token", 100L)).thenReturn(Optional.of(unsafe));
        DefaultWorkspaceRepositoryService service = service(store, gitHub, mock(WatchedRepoStore.class));

        assertThatThrownBy(() -> service.previewWatch(100))
                .isInstanceOfSatisfying(WorkspaceException.class,
                        failure -> assertThat(failure.code()).isEqualTo("REPOSITORY_ROUTING_INVALID"));
        assertThat(store.findAll()).isEmpty();
    }

    private static Workspace watch(DefaultWorkspaceRepositoryService service, long repositoryId)
    {
        WatchRepositoryPreview preview = service.previewWatch(repositoryId);
        return service.watch(new WatchRepositoryCommand(repositoryId, preview.fingerprint(), Optional.empty()));
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
                "public",
                true,
                "main",
                "https://github.com/" + owner + "/project.git",
                Optional.empty());
    }

    private static UserRepo fork(long id, long upstreamId)
    {
        return fork(id, upstreamId, true);
    }

    private static UserRepo fork(long id, long upstreamId, boolean viewerCanPush)
    {
        return new UserRepo(
                id,
                "contributor",
                "project",
                "contributor/project",
                Optional.of("fork"),
                true,
                "public",
                viewerCanPush,
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
