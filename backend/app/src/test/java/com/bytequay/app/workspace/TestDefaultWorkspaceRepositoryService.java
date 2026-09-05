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

import com.bytequay.agent.core.store.AgentTaskStore;
import com.bytequay.app.agent.codegraph.FakeCodeGraphService;
import com.bytequay.app.repository.RepositoryEnrichmentService;
import com.bytequay.app.repository.github.GitHubAccountClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.pr.PullRequestRefreshService;
import com.bytequay.core.cherrypick.CherryPickExecutionStore;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.UserRepo;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.domain.Workspace.Topology;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Lock;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
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
        DefaultWorkspaceRepositoryService service = service(store, gitHub);

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
        DefaultWorkspaceRepositoryService service = service(store, gitHub);
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
    void mapsMissingAndNotReadyWorkspacesToStableErrors()
    {
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        when(gitHub.fetchRepository("token", 100L))
                .thenReturn(Optional.of(direct(100, "owner")));
        DefaultWorkspaceRepositoryService service = service(store, gitHub);
        Workspace pending = watch(service, 100);

        assertThatThrownBy(() -> service.getWorkspace("missing"))
                .isInstanceOfSatisfying(WorkspaceException.class,
                        failure -> assertThat(failure.code()).isEqualTo("WORKSPACE_NOT_FOUND"));
        assertThatThrownBy(() -> service.requireReadyRepository(pending.id()))
                .isInstanceOfSatisfying(WorkspaceException.class,
                        failure -> assertThat(failure.code()).isEqualTo("WORKSPACE_CODE_NOT_READY"));
        assertThatThrownBy(() -> service.requireReadableRepository(pending.id()))
                .isInstanceOfSatisfying(WorkspaceException.class,
                        failure -> assertThat(failure.code()).isEqualTo("WORKSPACE_CODE_NOT_READY"));
    }

    @Test
    void unwatchPreservesWorkspaceWithManagedTaskWorktreeDirectory(@TempDir Path temporaryDirectory)
            throws IOException
    {
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        when(gitHub.fetchRepository("token", 100L)).thenReturn(Optional.of(direct(100, "owner")));
        WorkspacePaths paths = new WorkspacePaths(temporaryDirectory.toString());
        DefaultWorkspaceRepositoryService service = service(
                store,
                gitHub,
                mock(WorkspaceProvisioningService.class),
                paths);
        Workspace workspace = watch(service, 100);
        Path taskWorktree = paths.worktrees(workspace.id()).resolve("task-1");
        Files.createDirectories(taskWorktree);

        assertThatThrownBy(() -> service.unwatch(workspace.id()))
                .isInstanceOfSatisfying(WorkspaceException.class,
                        failure -> assertThat(failure.code()).isEqualTo("WORKSPACE_BUSY"));
        assertThat(store.findById(workspace.id())).contains(workspace);

        Files.delete(taskWorktree);
        service.unwatch(workspace.id());
        assertThat(store.findById(workspace.id())).isEmpty();
    }

    @Test
    void unwatchPreservesWorkspaceReferencedByPersistedTask(@TempDir Path temporaryDirectory)
    {
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        when(gitHub.fetchRepository("token", 100L)).thenReturn(Optional.of(direct(100, "owner")));
        AgentTaskStore agentTasks = mock(AgentTaskStore.class);
        WorkspacePaths paths = new WorkspacePaths(temporaryDirectory.toString());
        DefaultWorkspaceRepositoryService service = service(
                store,
                gitHub,
                mock(WorkspaceProvisioningService.class),
                paths,
                agentTasks);
        Workspace workspace = watch(service, 100);
        when(agentTasks.hasWorktreeReference(workspace.id())).thenReturn(true);

        assertThatThrownBy(() -> service.unwatch(workspace.id()))
                .isInstanceOfSatisfying(WorkspaceException.class,
                        failure -> assertThat(failure.code()).isEqualTo("WORKSPACE_BUSY"));
        assertThat(store.findById(workspace.id())).contains(workspace);
    }

    @Test
    void unwatchWaitsForWorkspaceMutationAndRechecksWorktrees(@TempDir Path temporaryDirectory)
            throws Exception
    {
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        when(gitHub.fetchRepository("token", 100L)).thenReturn(Optional.of(direct(100, "owner")));
        WorkspacePaths paths = new WorkspacePaths(temporaryDirectory.toString());
        DefaultWorkspaceRepositoryService service = service(
                store,
                gitHub,
                mock(WorkspaceProvisioningService.class),
                paths);
        Workspace workspace = watch(service, 100);
        CountDownLatch unwatchStarted = new CountDownLatch(1);
        Lock mutation = WorkspaceOperationLocks.get(workspace.id());

        try (var executor = Executors.newSingleThreadExecutor()) {
            mutation.lock();
            var unwatch = executor.submit(() -> {
                unwatchStarted.countDown();
                service.unwatch(workspace.id());
            });
            try {
                assertThat(unwatchStarted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThat(unwatch.isDone()).isFalse();
                Files.createDirectories(paths.worktrees(workspace.id()).resolve("task-1"));
            }
            finally {
                mutation.unlock();
            }

            assertThatThrownBy(() -> unwatch.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(ExecutionException.class)
                    .hasCauseInstanceOf(WorkspaceException.class)
                    .satisfies(failure -> assertThat(((WorkspaceException) failure.getCause()).code())
                            .isEqualTo("WORKSPACE_BUSY"));
        }
        assertThat(store.findById(workspace.id())).contains(workspace);
    }

    @Test
    void synchronizesMissingCodeBeforeReturningRepository()
    {
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        when(gitHub.fetchRepository("token", 100L)).thenReturn(Optional.of(direct(100, "owner")));
        WorkspaceProvisioningService provisioning = mock(WorkspaceProvisioningService.class);
        DefaultWorkspaceRepositoryService service = service(store, gitHub, provisioning);
        Workspace unsynced = watch(service, 100);
        reset(provisioning);
        when(provisioning.synchronizeCode(unsynced.id())).thenAnswer(ignored -> {
            Workspace synced = store.save(unsynced.withCodeSyncAt(Optional.of(NOW), NOW));
            return WorkspaceCodeSyncResult.success(synced);
        });

        assertThat(service.requireReadyRepository(unsynced.id()).repository())
                .isEqualTo(Path.of("/tmp/bytequay-workspaces", unsynced.id(), "repository"));
        assertThat(service.requireReadableRepository(unsynced.id()).repository())
                .isEqualTo(Path.of("/tmp/bytequay-workspaces", unsynced.id(), "repository"));
        verify(provisioning).synchronizeCode(unsynced.id());
    }

    @Test
    void usesTheLastSuccessfulCodeSyncForReadyAndReadableRepositories()
    {
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        when(gitHub.fetchRepository("token", 100L))
                .thenReturn(Optional.of(direct(100, "owner")));
        DefaultWorkspaceRepositoryService service = service(store, gitHub);
        Workspace synced = store.save(watch(service, 100)
                .withCodeSyncAt(Optional.of(NOW), NOW));

        assertThat(service.requireReadableRepository(synced.id()).repository())
                .isEqualTo(Path.of("/tmp/bytequay-workspaces", synced.id(), "repository"));
        assertThat(service.requireReadyRepository(synced.id()).repository())
                .isEqualTo(Path.of("/tmp/bytequay-workspaces", synced.id(), "repository"));
    }

    @Test
    void staleSyncQueuesCodeAndMetadata()
    {
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        when(gitHub.fetchRepository("token", 100L))
                .thenReturn(Optional.of(direct(100, "owner")));
        CredentialService credentials = mock(CredentialService.class);
        when(credentials.resolveGitHubToken()).thenReturn("token");
        WorkspaceProvisioningService provisioning = mock(WorkspaceProvisioningService.class);
        WorkspaceMetadataService metadata = mock(WorkspaceMetadataService.class);
        DefaultWorkspaceRepositoryService service = new DefaultWorkspaceRepositoryService(
                store,
                mock(AgentTaskStore.class),
                mock(CherryPickExecutionStore.class),
                new InMemoryRemoteSourceStore(),
                enrichment(),
                new com.bytequay.app.service.InMemoryWorkspaceCredentialStore(),
                gitHub,
                credentials,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new WorkspacePaths("/tmp/bytequay-workspaces"),
                provisioning,
                metadata,
                mock(PullRequestRefreshService.class),
                Runnable::run,
                new FakeCodeGraphService());
        Instant staleCode = NOW.minusSeconds(301);
        Instant staleMetadata = NOW.minusSeconds(8 * 24 * 60 * 60);
        Workspace ready = store.save(watch(service, 100)
                .withCodeSyncAt(Optional.of(staleCode), NOW)
                .withMetadataSyncAt(Optional.of(staleMetadata), NOW));
        reset(provisioning, metadata);

        Workspace requested = service.requestSync(ready.id());

        assertThat(requested.lastCodeSyncAt()).contains(staleCode);
        assertThat(requested.lastMetadataSyncAt()).contains(staleMetadata);
        verify(provisioning).synchronizeCode(ready.id());
        verify(metadata).synchronize(ready.id());
    }

    @Test
    void startupRecoveryUsesTheSameFreshnessAndSchedulingPath()
    {
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        when(gitHub.fetchRepository("token", 100L)).thenReturn(Optional.of(direct(100, "pending")));
        when(gitHub.fetchRepository("token", 200L)).thenReturn(Optional.of(direct(200, "syncing")));
        when(gitHub.fetchRepository("token", 300L)).thenReturn(Optional.of(direct(300, "fresh")));
        CredentialService credentials = mock(CredentialService.class);
        when(credentials.resolveGitHubToken()).thenReturn("token");
        WorkspaceProvisioningService provisioning = mock(WorkspaceProvisioningService.class);
        WorkspaceMetadataService metadata = mock(WorkspaceMetadataService.class);
        DefaultWorkspaceRepositoryService service = new DefaultWorkspaceRepositoryService(
                store,
                mock(AgentTaskStore.class),
                mock(CherryPickExecutionStore.class),
                new InMemoryRemoteSourceStore(),
                enrichment(),
                new com.bytequay.app.service.InMemoryWorkspaceCredentialStore(),
                gitHub,
                credentials,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new WorkspacePaths("/tmp/bytequay-workspaces"),
                provisioning,
                metadata,
                mock(PullRequestRefreshService.class),
                Runnable::run,
                new FakeCodeGraphService());
        Workspace pending = watch(service, 100);
        Workspace syncing = store.save(watch(service, 200)
                .withCodeSyncAt(Optional.of(NOW.minusSeconds(301)), NOW));
        Workspace fresh = store.save(watch(service, 300)
                .withCodeSyncAt(Optional.of(NOW), NOW)
                .withMetadataSyncAt(Optional.of(NOW), NOW));
        reset(provisioning, metadata);

        service.recoverIncompleteWork();

        verify(provisioning).provision(pending.id());
        verify(provisioning).synchronizeCode(syncing.id());
        verify(metadata).synchronize(pending.id());
        verify(metadata).synchronize(syncing.id());
        verify(provisioning, never()).provision(fresh.id());
        verify(provisioning, never()).synchronizeCode(fresh.id());
        verify(metadata).synchronize(fresh.id());
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
        PullRequestRefreshService pullRequestRefresh = mock(PullRequestRefreshService.class);
        AtomicReference<Optional<Instant>> observed = new AtomicReference<>();
        when(provisioning.provision(anyString())).thenAnswer(invocation -> {
            Workspace saved = store.findById(invocation.getArgument(0)).orElseThrow();
            observed.set(saved.lastCodeSyncAt());
            return WorkspaceProvisioningResult.success(saved);
        });
        DefaultWorkspaceRepositoryService service = new DefaultWorkspaceRepositoryService(
                store,
                mock(AgentTaskStore.class),
                executions,
                new InMemoryRemoteSourceStore(),
                enrichment(),
                new com.bytequay.app.service.InMemoryWorkspaceCredentialStore(),
                gitHub,
                credentials,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new WorkspacePaths("/tmp/bytequay-workspaces"),
                provisioning,
                mock(WorkspaceMetadataService.class),
                pullRequestRefresh,
                Runnable::run,
                new FakeCodeGraphService());

        watch(service, 100);

        assertThat(observed).hasValue(Optional.empty());
        verify(pullRequestRefresh).refreshRepository(RepoRef.of("owner", "project"));
    }

    private static DefaultWorkspaceRepositoryService service(
            WorkspaceStore store,
            GitHubAccountClient gitHub)
    {
        return service(store, gitHub, mock(WorkspaceProvisioningService.class));
    }

    private static DefaultWorkspaceRepositoryService service(
            WorkspaceStore store,
            GitHubAccountClient gitHub,
            WorkspaceProvisioningService provisioning)
    {
        return service(
                store,
                gitHub,
                provisioning,
                new WorkspacePaths("/tmp/bytequay-workspaces"));
    }

    private static DefaultWorkspaceRepositoryService service(
            WorkspaceStore store,
            GitHubAccountClient gitHub,
            WorkspaceProvisioningService provisioning,
            WorkspacePaths paths)
    {
        return service(store, gitHub, provisioning, paths, mock(AgentTaskStore.class));
    }

    private static DefaultWorkspaceRepositoryService service(
            WorkspaceStore store,
            GitHubAccountClient gitHub,
            WorkspaceProvisioningService provisioning,
            WorkspacePaths paths,
            AgentTaskStore agentTasks)
    {
        CredentialService credentials = mock(CredentialService.class);
        when(credentials.resolveGitHubToken()).thenReturn("token");
        CherryPickExecutionStore executions = mock(CherryPickExecutionStore.class);
        when(executions.listRecent(CherryPickExecutionStore.MAX_LIST_LIMIT)).thenReturn(ImmutableList.of());
        return new DefaultWorkspaceRepositoryService(
                store,
                agentTasks,
                executions,
                new InMemoryRemoteSourceStore(),
                enrichment(),
                new com.bytequay.app.service.InMemoryWorkspaceCredentialStore(),
                gitHub,
                credentials,
                Clock.fixed(NOW, ZoneOffset.UTC),
                paths,
                provisioning,
                mock(WorkspaceMetadataService.class),
                mock(PullRequestRefreshService.class),
                Runnable::run,
                new FakeCodeGraphService());
    }

    private static RepositoryEnrichmentService enrichment()
    {
        RepositoryEnrichmentService enrichment = mock(RepositoryEnrichmentService.class);
        when(enrichment.enrichRemoteSources(anyString())).thenReturn(ImmutableList.of());
        return enrichment;
    }

    @Test
    void previewShowsExactRoutingAndRejectsPermissionChanges()
    {
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        when(gitHub.fetchRepository("token", 200L))
                .thenReturn(Optional.of(fork(200, 300, true)))
                .thenReturn(Optional.of(fork(200, 300, false)));
        DefaultWorkspaceRepositoryService service = service(store, gitHub);

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
    void linksAndUnlinksAnyReadableRepository()
    {
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        when(gitHub.fetchRepository("token", 100L)).thenReturn(Optional.of(direct(100, "contributor")));
        when(gitHub.fetchRepository("token", 300L)).thenReturn(Optional.of(direct(300, "upstream")));
        DefaultWorkspaceRepositoryService service = service(store, gitHub);
        Workspace selected = store.save(watch(service, 100)
                .withCodeSyncAt(Optional.of(NOW), NOW)
                .withMetadataSyncAt(Optional.of(NOW), NOW));

        Workspace linked = service.setUpstream(selected.id(), Optional.of(300L));

        assertThat(store.findAll()).hasSize(1);
        assertThat(linked.upstreamRepositoryId()).contains(300L);
        assertThat(linked.upstreamRepository()).contains(RepoRef.of("upstream", "project"));
        assertThat(linked.monitoredRepository()).isEqualTo(RepoRef.of("upstream", "project"));
        assertThat(linked.upstreamDefaultBranch()).contains("main");

        store.save(linked.withCodeSyncAt(Optional.of(NOW), NOW)
                .withMetadataSyncAt(Optional.of(NOW), NOW));
        Workspace unlinked = service.setUpstream(selected.id(), Optional.empty());

        assertThat(unlinked.upstreamRepository()).isEmpty();
        assertThat(unlinked.monitoredRepository()).isEqualTo(selected.selectedRepository());
    }

    @Test
    void rejectsSelectedAndUnreadableUpstreamRepositories()
    {
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        when(gitHub.fetchRepository("token", 100L)).thenReturn(Optional.of(direct(100, "owner")));
        when(gitHub.fetchRepository("token", 999L)).thenReturn(Optional.empty());
        DefaultWorkspaceRepositoryService service = service(store, gitHub);
        Workspace selected = store.save(watch(service, 100)
                .withCodeSyncAt(Optional.of(NOW), NOW)
                .withMetadataSyncAt(Optional.of(NOW), NOW));

        assertThatThrownBy(() -> service.setUpstream(selected.id(), Optional.of(100L)))
                .isInstanceOfSatisfying(WorkspaceException.class,
                        failure -> assertThat(failure.code()).isEqualTo("WORKSPACE_UPSTREAM_INVALID"));
        assertThatThrownBy(() -> service.setUpstream(selected.id(), Optional.of(999L)))
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
        DefaultWorkspaceRepositoryService service = service(store, gitHub);
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
        DefaultWorkspaceRepositoryService service = service(store, gitHub);

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
}
