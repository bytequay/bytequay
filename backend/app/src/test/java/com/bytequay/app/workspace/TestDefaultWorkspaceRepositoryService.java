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

import com.bytequay.app.repository.RepositoryEnrichmentService;
import com.bytequay.app.repository.github.GitHubAccountClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.credentials.CredentialService.GitHubCredential;
import com.bytequay.app.service.pr.PullRequestRefreshService;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.UserProfile;
import com.bytequay.core.domain.UserRepo;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.domain.Workspace.Topology;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

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
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestDefaultWorkspaceRepositoryService
{
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");

    @Test
    void watchingOwnedRepositoriesKeepsOriginalDirectAndLinksForkToUpstream()
    {
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        when(gitHub.fetchRepository("token", 100L))
                .thenReturn(Optional.of(direct(100, "owner")));
        when(gitHub.fetchRepository("token", 200L))
                .thenReturn(Optional.of(fork(200, 300)));
        when(gitHub.fetchRepository("token", 300L)).thenReturn(Optional.of(direct(300, "upstream")));
        DefaultWorkspaceRepositoryService service = service(store, gitHub);

        WatchRepositoryPreview directPreview = service.previewWatch(100, Optional.empty());
        Workspace direct = service.watch(new WatchRepositoryCommand(
                100, directPreview.topology(), directPreview.fingerprint(), Optional.empty(), false));
        Workspace fork = watch(service, 200, Optional.of(Topology.FORK));

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
        assertThat(fork.upstreamRepositoryId()).contains(300L);
        assertThat(fork.monitoredRepository()).isEqualTo(RepoRef.of("upstream", "project"));
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
        WatchRepositoryPreview preview = service.previewWatch(100, Optional.empty());
        WatchRepositoryCommand command = new WatchRepositoryCommand(100, preview.topology(), preview.fingerprint(), Optional.empty(), false);
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
    }

    @Test
    void unwatchRetainsWorktreeAndRewatchRestoresTheSameWorkspace(@TempDir Path temporaryDirectory)
            throws IOException
    {
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        when(gitHub.fetchRepository("token", 100L)).thenReturn(Optional.of(direct(100, "owner")));
        WorkspacePaths paths = new WorkspacePaths(temporaryDirectory.toString());
        WorkspaceProvisioningService provisioning = mock(WorkspaceProvisioningService.class);
        DefaultWorkspaceRepositoryService service = service(store, gitHub, provisioning, paths);
        Workspace workspace = watch(service, 100);
        Path taskWorktree = paths.worktrees(workspace.id()).resolve("task-1");
        Files.createDirectories(taskWorktree);
        Files.writeString(taskWorktree.resolve("unfinished.txt"), "local work");

        service.unwatch(workspace.id());
        service.unwatch(workspace.id());

        assertThat(service.listWorkspaces()).isEmpty();
        assertThat(service.listRetiredWorkspaces()).containsExactly(WorkspaceSummary.of(workspace));
        assertThat(service.getWorkspace(workspace.id())).isEqualTo(WorkspaceSummary.of(workspace));
        assertThat(store.findAllRetained()).containsExactly(workspace);
        assertThat(service.previewWatch(100, Optional.empty()).existingWorkspaceId()).isEmpty();
        assertThat(Files.readString(taskWorktree.resolve("unfinished.txt"))).isEqualTo("local work");
        reset(provisioning);
        Workspace restored = watch(service, 100);
        assertThat(restored).isEqualTo(workspace);
        assertThat(service.listWorkspaces()).extracting(WorkspaceSummary::id).containsExactly(workspace.id());
        assertThat(service.listRetiredWorkspaces()).isEmpty();
        assertThat(Files.readString(taskWorktree.resolve("unfinished.txt"))).isEqualTo("local work");
        verify(provisioning).synchronizeCode(workspace.id());
    }

    @Test
    void retiredListExcludesWatchedAndDeletedWorkspacesWithoutSchedulingRefreshes()
    {
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        when(gitHub.fetchRepository("token", 100L)).thenReturn(Optional.of(direct(100, "owner")));
        when(gitHub.fetchRepository("token", 200L)).thenReturn(Optional.of(direct(200, "owner")));
        CredentialService credentials = mock(CredentialService.class);
        when(credentials.resolveGitHubCredential()).thenReturn(credential("owner"));
        WorkspaceProvisioningService provisioning = mock(WorkspaceProvisioningService.class);
        WorkspaceMetadataService metadata = mock(WorkspaceMetadataService.class);
        PullRequestRefreshService refresh = mock(PullRequestRefreshService.class);
        RepositoryEnrichmentService enrichment = enrichment();
        List<Runnable> queued = new ArrayList<>();
        DefaultWorkspaceRepositoryService service = new DefaultWorkspaceRepositoryService(store, enrichment, gitHub, credentials,
                Clock.fixed(NOW, ZoneOffset.UTC), new WorkspacePaths("/tmp/bytequay-workspaces"), provisioning, metadata, refresh, queued::add);
        Workspace retired = watch(service, 100);
        Workspace watched = watch(service, 200);
        service.unwatch(retired.id());
        queued.clear();
        reset(enrichment, gitHub, credentials, provisioning, metadata, refresh);

        assertThat(service.listRetiredWorkspaces()).containsExactly(WorkspaceSummary.of(retired));
        assertThat(service.listWorkspaces()).containsExactly(WorkspaceSummary.of(watched));
        assertThat(service.getWorkspace(retired.id())).isEqualTo(WorkspaceSummary.of(retired));
        assertThat(store.isWatched(retired.id())).isFalse();
        assertThat(queued).isEmpty();
        verifyNoInteractions(enrichment, gitHub, credentials, provisioning, metadata, refresh);

        store.delete(retired.id());
        assertThat(service.listRetiredWorkspaces()).isEmpty();
        assertThat(service.listWorkspaces()).containsExactly(WorkspaceSummary.of(watched));
    }

    @Test
    void rewatchRequiresTheCurrentRoutingPreview()
    {
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        when(gitHub.fetchRepository("token", 100L)).thenReturn(Optional.of(direct(100, "owner")));
        DefaultWorkspaceRepositoryService service = service(store, gitHub);
        Workspace workspace = watch(service, 100);
        service.unwatch(workspace.id());
        WatchRepositoryPreview preview = service.previewWatch(100, Optional.empty());
        when(gitHub.fetchRepository("token", 100L)).thenReturn(Optional.of(new UserRepo(
                100, "owner", "project", "owner/project", Optional.empty(), false, "public", true,
                "next-main", "https://github.com/owner/project.git", Optional.empty())));

        assertThatThrownBy(() -> service.watch(new WatchRepositoryCommand(100, preview.topology(), preview.fingerprint(), Optional.empty(), false)))
                .isInstanceOfSatisfying(WorkspaceException.class, failure -> assertThat(failure.code()).isEqualTo("WATCH_PREVIEW_CHANGED"));
        assertThat(store.isWatched(workspace.id())).isFalse();
        assertThat(watch(service, 100).id()).isEqualTo(workspace.id());
        assertThat(store.isWatched(workspace.id())).isTrue();
    }

    @Test
    void queuedRepositoryRefreshesSkipUnwatchedAndDeletedWorkspaces()
    {
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        when(gitHub.fetchRepository("token", 100L)).thenReturn(Optional.of(direct(100, "owner")));
        CredentialService credentials = mock(CredentialService.class);
        when(credentials.resolveGitHubCredential()).thenReturn(credential("owner"));
        WorkspaceProvisioningService provisioning = mock(WorkspaceProvisioningService.class);
        WorkspaceMetadataService metadata = mock(WorkspaceMetadataService.class);
        PullRequestRefreshService refresh = mock(PullRequestRefreshService.class);
        List<Runnable> queued = new ArrayList<>();
        DefaultWorkspaceRepositoryService service = new DefaultWorkspaceRepositoryService(store, enrichment(), gitHub, credentials,
                Clock.fixed(NOW, ZoneOffset.UTC), new WorkspacePaths("/tmp/bytequay-workspaces"), provisioning, metadata, refresh, queued::add);
        Workspace workspace = watch(service, 100);
        service.unwatch(workspace.id());

        queued.forEach(Runnable::run);
        service.recoverIncompleteWork();

        verifyNoInteractions(provisioning, metadata, refresh);
        assertThat(store.findById(workspace.id())).contains(workspace);

        queued.clear();
        watch(service, 100);
        store.delete(workspace.id());
        queued.forEach(Runnable::run);

        verifyNoInteractions(provisioning, metadata, refresh);
        assertThat(store.findById(workspace.id())).isEmpty();
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
            return WorkspaceCodeSyncResult.success(synced, ImmutableSet.of());
        });

        assertThat(service.requireReadyRepository(unsynced.id()).repository())
                .isEqualTo(Path.of("/tmp/bytequay-workspaces", unsynced.id(), "repository"));
        verify(provisioning).synchronizeCode(unsynced.id());
    }

    @Test
    void localRepositoryResolutionNeverStartsSynchronization()
    {
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        when(gitHub.fetchRepository("token", 100L)).thenReturn(Optional.of(direct(100, "owner")));
        WorkspaceProvisioningService provisioning = mock(WorkspaceProvisioningService.class);
        DefaultWorkspaceRepositoryService service = service(store, gitHub, provisioning);
        Workspace unsynced = watch(service, 100);
        reset(provisioning, gitHub);

        assertThatThrownBy(() -> service.requireLocalRepository(unsynced.id())).isInstanceOf(WorkspaceException.class);
        Workspace synced = store.save(unsynced.withCodeSyncAt(Optional.of(NOW), NOW));
        assertThat(service.requireLocalRepository(synced.id()).repository())
                .isEqualTo(Path.of("/tmp/bytequay-workspaces", synced.id(), "repository"));
        verifyNoInteractions(provisioning, gitHub);
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

        assertThat(service.requireReadyRepository(synced.id()).repository())
                .isEqualTo(Path.of("/tmp/bytequay-workspaces", synced.id(), "repository"));
        assertThat(service.requireReadableRepository(synced.id()).repository())
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
        when(credentials.resolveGitHubCredential()).thenReturn(credential("owner"));
        WorkspaceProvisioningService provisioning = mock(WorkspaceProvisioningService.class);
        WorkspaceMetadataService metadata = mock(WorkspaceMetadataService.class);
        DefaultWorkspaceRepositoryService service = new DefaultWorkspaceRepositoryService(
                store,
                enrichment(),
                gitHub,
                credentials,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new WorkspacePaths("/tmp/bytequay-workspaces"),
                provisioning,
                metadata,
                mock(PullRequestRefreshService.class),
                Runnable::run);
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
        when(gitHub.fetchRepository("token", 100L)).thenReturn(Optional.of(direct(100, "owner")));
        when(gitHub.fetchRepository("token", 200L)).thenReturn(Optional.of(direct(200, "owner")));
        when(gitHub.fetchRepository("token", 300L)).thenReturn(Optional.of(direct(300, "owner")));
        CredentialService credentials = mock(CredentialService.class);
        when(credentials.resolveGitHubToken()).thenReturn("token");
        when(credentials.resolveGitHubCredential()).thenReturn(credential("owner"));
        WorkspaceProvisioningService provisioning = mock(WorkspaceProvisioningService.class);
        WorkspaceMetadataService metadata = mock(WorkspaceMetadataService.class);
        DefaultWorkspaceRepositoryService service = new DefaultWorkspaceRepositoryService(
                store,
                enrichment(),
                gitHub,
                credentials,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new WorkspacePaths("/tmp/bytequay-workspaces"),
                provisioning,
                metadata,
                mock(PullRequestRefreshService.class),
                Runnable::run);
        Workspace pending = watch(service, 100);
        Workspace syncing = store.save(watch(service, 200)
                .withCodeSyncAt(Optional.of(NOW.minusSeconds(301)), NOW));
        Workspace fresh = store.save(watch(service, 300)
                .withCodeSyncAt(Optional.of(NOW), NOW)
                .withMetadataSyncAt(Optional.of(NOW), NOW));
        reset(provisioning, metadata);

        service.recoverIncompleteWork();

        verify(provisioning).synchronizeCode(pending.id());
        verify(provisioning).synchronizeCode(syncing.id());
        verify(metadata).synchronize(pending.id());
        verify(metadata).synchronize(syncing.id());
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
        when(credentials.resolveGitHubCredential()).thenReturn(credential("owner"));
        WorkspaceProvisioningService provisioning = mock(WorkspaceProvisioningService.class);
        PullRequestRefreshService pullRequestRefresh = mock(PullRequestRefreshService.class);
        AtomicReference<Optional<Instant>> observed = new AtomicReference<>();
        when(provisioning.synchronizeCode(anyString())).thenAnswer(invocation -> {
            Workspace saved = store.findById(invocation.getArgument(0)).orElseThrow();
            observed.set(saved.lastCodeSyncAt());
            return WorkspaceCodeSyncResult.success(saved, ImmutableSet.of());
        });
        DefaultWorkspaceRepositoryService service = new DefaultWorkspaceRepositoryService(
                store,
                enrichment(),
                gitHub,
                credentials,
                Clock.fixed(NOW, ZoneOffset.UTC),
                new WorkspacePaths("/tmp/bytequay-workspaces"),
                provisioning,
                mock(WorkspaceMetadataService.class),
                pullRequestRefresh,
                Runnable::run);

        Workspace watched = watch(service, 100);

        assertThat(observed).hasValue(Optional.empty());
        verify(pullRequestRefresh).refreshWorkspace(watched.id());
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
        CredentialService credentials = mock(CredentialService.class);
        when(credentials.resolveGitHubToken()).thenReturn("token");
        when(credentials.resolveGitHubCredential()).thenReturn(credential("owner"));
        return new DefaultWorkspaceRepositoryService(
                store,
                enrichment(),
                gitHub,
                credentials,
                Clock.fixed(NOW, ZoneOffset.UTC),
                paths,
                provisioning,
                mock(WorkspaceMetadataService.class),
                mock(PullRequestRefreshService.class),
                Runnable::run);
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

        when(gitHub.fetchRepository("token", 300L)).thenReturn(Optional.of(direct(300, "upstream")));
        WatchRepositoryPreview preview = service.previewWatch(200, Optional.of(Topology.FORK));

        assertThat(preview.topology()).isEqualTo(Topology.FORK);
        assertThat(preview.upstreamRepository()).contains(RepoRef.of("upstream", "project"));
        assertThat(preview.monitoredRepository()).isEqualTo(RepoRef.of("upstream", "project"));
        assertThat(preview.developmentBase()).isEqualTo("upstream/main");
        assertThat(preview.pushRemote()).isEqualTo("origin");
        assertThat(preview.pushWritable()).isTrue();
        assertThat(preview.pullRequestBase()).isEqualTo(RepoRef.of("upstream", "project"));
        assertThatThrownBy(() -> service.watch(new WatchRepositoryCommand(
                200, preview.topology(), preview.fingerprint(), Optional.empty(), false)))
                .isInstanceOfSatisfying(WorkspaceException.class,
                        failure -> assertThat(failure.code()).isEqualTo("WATCH_PREVIEW_CHANGED"));
        assertThat(store.findAll()).isEmpty();
    }

    @Test
    void forkPreviewUsesUpstreamVisibilityAndKeepsTheRequestedAndPushRepositories()
    {
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        UserRepo upstream = new UserRepo(300, "upstream", "project", "upstream/project", Optional.empty(), false, "internal", false,
                "master", "https://github.com/upstream/project.git", Optional.empty());
        UserRepo personalFork = new UserRepo(200, "owner", "project", "owner/project", Optional.empty(), true, "private", true,
                "fork-default", "https://github.com/owner/project.git", Optional.of(new UserRepo.Upstream(
                        300, "upstream", "project", "master", "https://github.com/upstream/project.git")));
        when(gitHub.fetchRepository("token", 200L)).thenReturn(Optional.of(personalFork));
        when(gitHub.fetchRepository("token", 300L)).thenReturn(Optional.of(upstream));

        WatchRepositoryPreview preview = service(store, gitHub).previewWatch(200, Optional.of(Topology.FORK));

        assertThat(preview.visibility()).isEqualTo("internal");
        assertThat(preview.selectedRepositoryId()).isEqualTo(200);
        assertThat(preview.selectedRepository()).isEqualTo(RepoRef.of("owner", "project"));
        assertThat(preview.monitoredRepositoryId()).isEqualTo(300);
        assertThat(preview.monitoredRepository()).isEqualTo(RepoRef.of("upstream", "project"));
        assertThat(preview.defaultBranch()).isEqualTo("master");
        assertThat(preview.pushRepository()).isEqualTo(RepoRef.of("owner", "project"));
        assertThat(preview.pushWritable()).isTrue();
    }

    @Test
    void linksAndUnlinksAnyReadableRepository()
    {
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        when(gitHub.fetchRepository("token", 100L)).thenReturn(Optional.of(direct(100, "owner")));
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

        assertThatThrownBy(() -> service.previewWatch(100, Optional.empty()))
                .isInstanceOfSatisfying(WorkspaceException.class,
                        failure -> assertThat(failure.code()).isEqualTo("REPOSITORY_ROUTING_INVALID"));
        assertThat(store.findAll()).isEmpty();
    }

    @Test
    void watchingUpstreamOrForkReusesPersonalForkEvenWithUpstreamWriteAccess()
    {
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        UserRepo upstream = direct(300, "upstream");
        UserRepo personalFork = fork(200, 300);
        when(gitHub.fetchRepository("token", 300L)).thenReturn(Optional.of(upstream));
        when(gitHub.fetchRepository("token", 200L)).thenReturn(Optional.of(personalFork));
        when(gitHub.findOwnedFork("token", upstream, "owner")).thenReturn(Optional.of(personalFork));
        DefaultWorkspaceRepositoryService service = service(store, gitHub);

        WatchRepositoryPreview preview = service.previewWatch(300, Optional.empty());
        assertThat(preview.pushRepository()).isEqualTo(RepoRef.of("owner", "project"));
        assertThat(preview.forkRequired()).isFalse();
        assertThat(preview.monitoredRepositoryId()).isEqualTo(300);
        Workspace workspace = watch(service, 300);
        assertThat(workspace.selectedRepositoryId()).isEqualTo(200);
        assertThat(workspace.upstreamRepositoryId()).contains(300L);
        assertThat(workspace.name()).isEqualTo("upstream/project");
        assertThat(watch(service, 200, Optional.of(Topology.FORK)).id()).isEqualTo(workspace.id());
        assertThat(service.previewWatch(300, Optional.empty()).existingWorkspaceId()).contains(workspace.id());
        assertThat(store.findAll()).hasSize(1);
        verify(gitHub, never()).createFork(anyString(), any());
    }

    @Test
    void watchingLegacyUpstreamPreservesItsWorkspaceAndCreatesTheForkWorkspace()
    {
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        Workspace original = new Workspace("original-workspace", "upstream/project", 300, RepoRef.of("upstream", "project"),
                Optional.empty(), Optional.empty(), 300, RepoRef.of("upstream", "project"), Topology.DIRECT,
                "main", "https://github.com/upstream/project.git", Optional.empty(), Optional.empty(),
                "/tmp/bytequay-workspaces/original-workspace/repository", Optional.of(NOW), Optional.of(NOW), 0, NOW, NOW);
        store.save(original);
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        UserRepo upstream = direct(300, "upstream");
        when(gitHub.fetchRepository("token", 300L)).thenReturn(Optional.of(upstream));
        when(gitHub.findOwnedFork("token", upstream, "owner")).thenReturn(Optional.of(fork(200, 300)));
        DefaultWorkspaceRepositoryService service = service(store, gitHub);

        assertThat(service.previewWatch(300, Optional.empty()).existingWorkspaceId()).isEmpty();
        Workspace forkWorkspace = watch(service, 300);

        assertThat(forkWorkspace.selectedRepositoryId()).isEqualTo(200);
        assertThat(forkWorkspace.id()).isNotEqualTo(original.id());
        assertThat(store.findById(original.id())).contains(original);
        assertThat(store.findAll()).hasSize(2);
    }

    @Test
    void watchingSomeoneElsesForkUsesPersonalForkAndCanonicalUpstream()
    {
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        UserRepo upstream = direct(300, "upstream");
        UserRepo otherFork = new UserRepo(400, "someone", "project", "someone/project", Optional.empty(), true, "public", true,
                "other-branch", "https://github.com/someone/project.git", fork(200, 300).upstream());
        when(gitHub.fetchRepository("token", 400L)).thenReturn(Optional.of(otherFork));
        when(gitHub.fetchRepository("token", 300L)).thenReturn(Optional.of(upstream));
        when(gitHub.findOwnedFork("token", upstream, "owner")).thenReturn(Optional.of(fork(200, 300)));
        DefaultWorkspaceRepositoryService service = service(store, gitHub);

        Workspace workspace = watch(service, 400);

        assertThat(workspace.selectedRepository()).isEqualTo(RepoRef.of("owner", "project"));
        assertThat(workspace.monitoredRepository()).isEqualTo(RepoRef.of("upstream", "project"));
        verify(gitHub, never()).createFork(anyString(), any());
    }

    @Test
    void watchingAForkOfAnOwnedOriginalClonesTheSelectedRepositoryDirectly()
    {
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        UserRepo original = direct(300, "owner");
        UserRepo otherFork = new UserRepo(400, "someone", "project", "someone/project", Optional.empty(), true, "public", false,
                "main", "https://github.com/someone/project.git", Optional.of(new UserRepo.Upstream(
                        original.id(), original.owner(), original.name(), original.defaultBranch(), original.cloneUrl())));
        when(gitHub.fetchRepository("token", 400L)).thenReturn(Optional.of(otherFork));
        when(gitHub.fetchRepository("token", 300L)).thenReturn(Optional.of(original));
        DefaultWorkspaceRepositoryService service = service(store, gitHub);

        WatchRepositoryPreview preview = service.previewWatch(400, Optional.empty());
        assertThat(preview.forkRequired()).isFalse();
        assertThat(preview.topology()).isEqualTo(Topology.DIRECT);
        assertThat(preview.pushRepository()).isEqualTo(RepoRef.of("someone", "project"));
        Workspace workspace = watch(service, 400);
        assertThat(workspace.selectedRepositoryId()).isEqualTo(400);
        assertThat(workspace.upstreamRepositoryId()).isEmpty();
        verify(gitHub, never()).findOwnedFork(anyString(), any(), anyString());
        verify(gitHub, never()).createFork(anyString(), any());
    }

    @Test
    void confirmedWatchRelinksAnExistingPersonalForkWithoutChangingItsIdentity()
    {
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        UserRepo upstream = direct(300, "upstream");
        when(gitHub.fetchRepository("token", 300L)).thenReturn(Optional.of(upstream));
        when(gitHub.fetchRepository("token", 200L)).thenReturn(Optional.of(fork(200, 300)));
        when(gitHub.findOwnedFork("token", upstream, "owner")).thenReturn(Optional.of(fork(200, 300)));
        DefaultWorkspaceRepositoryService service = service(store, gitHub);
        Workspace workspace = watch(service, 200, Optional.of(Topology.FORK));
        service.setUpstream(workspace.id(), Optional.empty());

        assertThat(service.previewWatch(300, Optional.empty()).existingWorkspaceId()).isEmpty();
        Workspace relinked = watch(service, 300);

        assertThat(relinked.id()).isEqualTo(workspace.id());
        assertThat(relinked.selectedRepositoryId()).isEqualTo(workspace.selectedRepositoryId());
        assertThat(relinked.localRepositoryPath()).isEqualTo(workspace.localRepositoryPath());
        assertThat(relinked.upstreamRepositoryId()).contains(300L);
        assertThat(store.findAll()).hasSize(1);
    }

    @Test
    void creatingMissingForkRequiresConfirmedCurrentPreviewAndExplicitConsent()
    {
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        UserRepo upstream = direct(300, "upstream");
        UserRepo personalFork = fork(200, 300);
        when(gitHub.fetchRepository("token", 300L)).thenReturn(Optional.of(upstream));
        when(gitHub.createFork("token", upstream)).thenReturn(personalFork);
        DefaultWorkspaceRepositoryService service = service(store, gitHub);
        WatchRepositoryPreview preview = service.previewWatch(300, Optional.empty());

        assertThat(preview.forkRequired()).isTrue();
        assertThat(preview.pushRepository()).isEqualTo(RepoRef.of("owner", "project"));
        verify(gitHub, never()).createFork(anyString(), any());
        assertThatThrownBy(() -> service.watch(new WatchRepositoryCommand(300, preview.topology(), preview.fingerprint(), Optional.empty(), false)))
                .isInstanceOfSatisfying(WorkspaceException.class, failure -> assertThat(failure.code()).isEqualTo("FORK_CONFIRMATION_REQUIRED"));
        assertThatThrownBy(() -> service.watch(new WatchRepositoryCommand(300, preview.topology(), "stale", Optional.empty(), true)))
                .isInstanceOfSatisfying(WorkspaceException.class, failure -> assertThat(failure.code()).isEqualTo("WATCH_PREVIEW_CHANGED"));
        verify(gitHub, never()).createFork(anyString(), any());
        assertThat(store.findAll()).isEmpty();

        Workspace workspace = service.watch(new WatchRepositoryCommand(300, preview.topology(), preview.fingerprint(), Optional.empty(), true));
        assertThat(workspace.selectedRepositoryId()).isEqualTo(200);
        assertThat(workspace.monitoredRepositoryId()).isEqualTo(300);
        assertThat(workspace.upstreamDefaultBranch()).contains("main");
        verify(gitHub).createFork("token", upstream);
        when(gitHub.findOwnedFork("token", upstream, "owner")).thenReturn(Optional.of(personalFork));
        assertThat(service.watch(new WatchRepositoryCommand(300, preview.topology(), preview.fingerprint(), Optional.empty(), true)).id()).isEqualTo(workspace.id());
        verify(gitHub).createFork("token", upstream);
    }

    @Test
    void forkCreationFailureDoesNotSaveWorkspaceOrFallBackToUpstreamPushes()
    {
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        UserRepo upstream = direct(300, "upstream");
        when(gitHub.fetchRepository("token", 300L)).thenReturn(Optional.of(upstream));
        when(gitHub.createFork("token", upstream)).thenThrow(new IllegalStateException("Fork creation unavailable"));
        DefaultWorkspaceRepositoryService service = service(store, gitHub);
        WatchRepositoryPreview preview = service.previewWatch(300, Optional.empty());

        assertThatThrownBy(() -> service.watch(new WatchRepositoryCommand(300, preview.topology(), preview.fingerprint(), Optional.empty(), true)))
                .hasMessage("Fork creation unavailable");
        assertThat(store.findAll()).isEmpty();
    }

    @Test
    void anExistingForkThatAppearsAfterPreviewRequiresReviewInsteadOfCreation()
    {
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        UserRepo upstream = direct(300, "upstream");
        when(gitHub.fetchRepository("token", 300L)).thenReturn(Optional.of(upstream));
        DefaultWorkspaceRepositoryService service = service(store, gitHub);
        WatchRepositoryPreview preview = service.previewWatch(300, Optional.empty());
        when(gitHub.findOwnedFork("token", upstream, "owner")).thenReturn(Optional.of(fork(200, 300)));

        assertThatThrownBy(() -> service.watch(new WatchRepositoryCommand(300, preview.topology(), preview.fingerprint(), Optional.empty(), true)))
                .isInstanceOfSatisfying(WorkspaceException.class, failure -> assertThat(failure.code()).isEqualTo("WATCH_PREVIEW_CHANGED"));
        verify(gitHub, never()).createFork(anyString(), any());
        assertThat(store.findAll()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({"private, upstream, DIRECT", "internal, upstream, DIRECT", "public, upstream, FORK", "public, owner, DIRECT", "private, owner, DIRECT"})
    void recommendsWorkflowFromVisibilityAndOwnership(String visibility, String owner, Topology expected)
    {
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        UserRepo repository = new UserRepo(300, owner, "project", owner + "/project", Optional.empty(), false, visibility, true,
                "main", "https://github.com/" + owner + "/project.git", Optional.empty());
        when(gitHub.fetchRepository("token", 300L)).thenReturn(Optional.of(repository));
        DefaultWorkspaceRepositoryService service = service(store, gitHub);

        WatchRepositoryPreview preview = service.previewWatch(300, Optional.empty());

        assertThat(preview.topology()).isEqualTo(expected);
        assertThat(preview.recommendedTopology()).isEqualTo(expected);
        assertThat(preview.forkModeAvailable()).isEqualTo(!owner.equals("owner"));
        assertThat(preview.forkRequired()).isEqualTo(expected == Topology.FORK);
        verify(gitHub, never()).createFork(anyString(), any());
        if (expected == Topology.DIRECT) {
            Workspace workspace = service.watch(new WatchRepositoryCommand(300, preview.topology(), preview.fingerprint(), Optional.empty(), false));
            assertThat(workspace.selectedRepositoryId()).isEqualTo(300);
            assertThat(workspace.monitoredRepositoryId()).isEqualTo(300);
            assertThat(workspace.upstreamRepositoryId()).isEmpty();
            assertThat(workspace.topology()).isEqualTo(Topology.DIRECT);
            assertThat(workspace.originCloneUrl()).isEqualTo(repository.cloneUrl());
            assertThat(preview.developmentBase()).isEqualTo("origin/main");
            assertThat(preview.pullRequestBase()).isEqualTo(preview.selectedRepository());
            verify(gitHub, never()).findOwnedFork(anyString(), any(), anyString());
            assertThat(watch(service, 300).id()).isEqualTo(workspace.id());
        }
    }

    @Test
    void directCloneCanFollowAReadOnlyPublicRepositoryWithoutLookingForForks()
    {
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        UserRepo repository = new UserRepo(300, "upstream", "project", "upstream/project", Optional.empty(), false, "public", false,
                "main", "https://github.com/upstream/project.git", Optional.empty());
        when(gitHub.fetchRepository("token", 300L)).thenReturn(Optional.of(repository));
        DefaultWorkspaceRepositoryService service = service(store, gitHub);

        WatchRepositoryPreview preview = service.previewWatch(300, Optional.of(Topology.DIRECT));
        Workspace workspace = watch(service, 300, Optional.of(Topology.DIRECT));

        assertThat(preview.recommendedTopology()).isEqualTo(Topology.FORK);
        assertThat(preview.pushWritable()).isFalse();
        assertThat(preview.pushRepository()).isEqualTo(RepoRef.of("upstream", "project"));
        assertThat(preview.forkRequired()).isFalse();
        assertThat(workspace.monitoredRepository()).isEqualTo(workspace.selectedRepository());
        verify(gitHub, never()).findOwnedFork(anyString(), any(), anyString());
        verify(gitHub, never()).createFork(anyString(), any());
    }

    @Test
    void privateRepositoriesCanExplicitlyUseForkModeButCannotReuseDirectConsent()
    {
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        UserRepo repository = new UserRepo(300, "upstream", "project", "upstream/project", Optional.empty(), false, "private", true,
                "main", "https://github.com/upstream/project.git", Optional.empty());
        when(gitHub.fetchRepository("token", 300L)).thenReturn(Optional.of(repository));
        when(gitHub.createFork("token", repository)).thenReturn(fork(200, 300));
        DefaultWorkspaceRepositoryService service = service(store, gitHub);
        WatchRepositoryPreview direct = service.previewWatch(300, Optional.empty());
        WatchRepositoryPreview fork = service.previewWatch(300, Optional.of(Topology.FORK));

        assertThat(fork.recommendedTopology()).isEqualTo(Topology.DIRECT);
        assertThat(fork.forkRequired()).isTrue();
        assertThat(fork.fingerprint()).isNotEqualTo(direct.fingerprint());
        assertThatThrownBy(() -> service.watch(new WatchRepositoryCommand(300, Topology.FORK, direct.fingerprint(), Optional.empty(), true)))
                .isInstanceOfSatisfying(WorkspaceException.class, failure -> assertThat(failure.code()).isEqualTo("WATCH_PREVIEW_CHANGED"));
        assertThatThrownBy(() -> service.watch(new WatchRepositoryCommand(300, Topology.DIRECT, fork.fingerprint(), Optional.empty(), false)))
                .isInstanceOfSatisfying(WorkspaceException.class, failure -> assertThat(failure.code()).isEqualTo("WATCH_PREVIEW_CHANGED"));
        verify(gitHub, never()).createFork(anyString(), any());
        Workspace workspace = service.watch(new WatchRepositoryCommand(300, Topology.FORK, fork.fingerprint(), Optional.empty(), true));
        assertThat(workspace.monitoredRepositoryId()).isEqualTo(300);
        assertThat(workspace.selectedRepositoryId()).isEqualTo(200);
        assertThat(workspace.topology()).isEqualTo(Topology.FORK);
    }

    @Test
    void ownedForkDefaultsToDirectAndCanSwitchMonitoringAfterConfirmation()
    {
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        when(gitHub.fetchRepository("token", 200L)).thenReturn(Optional.of(fork(200, 300)));
        when(gitHub.fetchRepository("token", 300L)).thenReturn(Optional.of(direct(300, "upstream")));
        DefaultWorkspaceRepositoryService service = service(store, gitHub);

        Workspace direct = watch(service, 200);
        assertThat(direct.topology()).isEqualTo(Topology.DIRECT);
        assertThat(direct.monitoredRepositoryId()).isEqualTo(200);
        assertThat(direct.upstreamRepositoryId()).isEmpty();
        verify(gitHub, never()).fetchRepository("token", 300L);
        assertThat(service.previewWatch(200, Optional.of(Topology.FORK)).existingWorkspaceId()).isEmpty();
        Workspace fork = watch(service, 200, Optional.of(Topology.FORK));
        assertThat(fork.id()).isEqualTo(direct.id());
        assertThat(fork.topology()).isEqualTo(Topology.FORK);
        assertThat(fork.monitoredRepositoryId()).isEqualTo(300);
        Workspace unlinked = watch(service, 200);
        assertThat(unlinked.id()).isEqualTo(direct.id());
        assertThat(unlinked.topology()).isEqualTo(Topology.DIRECT);
        assertThat(unlinked.monitoredRepositoryId()).isEqualTo(200);
        verify(gitHub, never()).createFork(anyString(), any());
    }

    private static GitHubCredential credential(String login)
    {
        return new GitHubCredential("token", new UserProfile(login, login, "", "", 0, 0, 0, null, null, null, null));
    }

    private static Workspace watch(DefaultWorkspaceRepositoryService service, long repositoryId)
    {
        return watch(service, repositoryId, Optional.empty());
    }

    private static Workspace watch(DefaultWorkspaceRepositoryService service, long repositoryId, Optional<Topology> topology)
    {
        WatchRepositoryPreview preview = service.previewWatch(repositoryId, topology);
        return service.watch(new WatchRepositoryCommand(repositoryId, preview.topology(), preview.fingerprint(), Optional.empty(), false));
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
                "owner",
                "project",
                "owner/project",
                Optional.of("fork"),
                true,
                "public",
                viewerCanPush,
                "feature-default",
                "https://github.com/owner/project.git",
                Optional.of(new UserRepo.Upstream(
                        upstreamId,
                        "upstream",
                        "project",
                        "main",
                        "https://github.com/upstream/project.git")));
    }
}
