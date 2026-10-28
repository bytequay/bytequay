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

import com.bytequay.app.git.command.GitCommandRunner;
import com.bytequay.app.repository.github.GitHubAccountClient;
import com.bytequay.app.service.InMemoryAppSettingsStore;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.github.GhCliService;
import com.bytequay.app.service.hosting.HostingConnectionService;
import com.bytequay.app.service.hosting.HostingReadSession;
import com.bytequay.core.domain.RemoteSource;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.UserRepo;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.domain.Workspace.Topology;
import com.bytequay.core.hosting.HostingAccess;
import com.bytequay.core.hosting.HostingInstance;
import com.bytequay.core.hosting.HostingProvider;
import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.hosting.HostingServer;
import com.bytequay.core.store.CredentialStore;
import com.bytequay.core.store.WorkspaceCredentialStore;
import com.bytequay.core.store.WorkspaceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static com.bytequay.app.git.GitTestSupport.configureUser;
import static com.bytequay.app.git.GitTestSupport.git;
import static com.google.common.collect.ImmutableList.toImmutableList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@Execution(ExecutionMode.CONCURRENT)
class TestWorkspaceProvisioningService
{
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");
    private static final String ORIGIN_URL = "https://github.test/contributor/project.git";
    private static final String UPSTREAM_URL = "https://github.test/upstream/project.git";
    private static final String CUSTOM_URL = "https://github.test/other/project.git";

    @TempDir
    private Path temporary;

    @Test
    void provisionsDirectAndForkRouting()
            throws IOException
    {
        Remote origin = remote("origin");
        Remote upstream = remote("upstream");
        Path commandLog = temporary.resolve("git-commands.log");
        GitCommandRunner git = routedGit(ImmutableMap.of(ORIGIN_URL, origin.bare(), UPSTREAM_URL, upstream.bare()), commandLog);
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        Workspace direct = store.createOrGet(workspace("direct", false));
        Workspace fork = store.createOrGet(workspace("fork", true));
        WorkspaceProvisioningService service = service(store, git);

        WorkspaceCodeSyncResult directResult = service.synchronizeCode(direct.id());
        WorkspaceCodeSyncResult forkResult = service.synchronizeCode(fork.id());

        assertThat(directResult.successful()).isTrue();
        assertThat(git(directPath(direct), "remote").lines()).containsExactly("origin");
        assertThat(forkResult.successful()).isTrue();
        assertThat(git(directPath(fork), "remote").lines()).containsExactly("origin", "upstream");
        assertThat(git(directPath(fork), "remote", "get-url", "upstream")).isEqualTo(UPSTREAM_URL);

        Workspace linked = store.findById(fork.id()).orElseThrow();
        store.save(linked.withRepositoryRouting(
                linked.selectedRepository(),
                Optional.empty(),
                Optional.empty(),
                linked.selectedRepositoryId(),
                linked.selectedRepository(),
                linked.defaultBranch(),
                linked.originCloneUrl(),
                Optional.empty(),
                Optional.empty(),
                NOW));

        assertThat(service.synchronizeCode(fork.id()).successful()).isTrue();
        assertThat(git(directPath(fork), "remote").lines()).containsExactly("origin");
    }

    @Test
    void defaultsPushToForkEvenWhenTheBranchTracksUpstream()
            throws IOException
    {
        Remote origin = remote("origin");
        Remote upstream = remote("upstream");
        GitCommandRunner routed = routedGit(ImmutableMap.of(ORIGIN_URL, origin.bare(), UPSTREAM_URL, upstream.bare()), temporary.resolve("git-commands.log"));
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        Workspace workspace = store.createOrGet(workspace("fork", true));
        assertThat(service(store, routed).synchronizeCode(workspace.id()).successful()).isTrue();
        Path repository = directPath(workspace);
        git(repository, "checkout", "-b", "contribution", "--track", "upstream/main");

        GitCommandRunner.Result pushed = routed.run(repository, "-c", "push.default=current", "push");

        assertThat(pushed.successful()).describedAs(pushed.output()).isTrue();
        assertThat(git(origin.bare(), "rev-parse", "refs/heads/contribution"))
                .isEqualTo(git(upstream.bare(), "rev-parse", "refs/heads/main"));
        assertThat(new GitCommandRunner().run(upstream.bare(), "show-ref", "--verify", "refs/heads/contribution").successful()).isFalse();
    }

    @Test
    void verifiesPublishedBranchWithoutChangingEitherRepository()
            throws IOException
    {
        Remote origin = remote("origin");
        GitCommandRunner routed = routedGit(ImmutableMap.of(ORIGIN_URL, origin.bare()), temporary.resolve("remote-read.log"));
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        Workspace workspace = store.createOrGet(workspace("remote-read", false));
        assertThat(service(store, routed).synchronizeCode(workspace.id()).successful()).isTrue();
        var managed = new ManagedWorkspaceGit(new WorkspacePaths(temporary.resolve("workspaces").toString()), routed,
                mock(CredentialService.class), new InMemoryRemoteSourceStore());
        assertThat(managed.remoteBranchSha(workspace, "main")).contains(git(origin.bare(), "rev-parse", "refs/heads/main"));
        assertThat(managed.remoteBranchSha(workspace, "absent")).isEmpty();
        git(directPath(workspace), "remote", "set-url", "origin", CUSTOM_URL);
        assertThatThrownBy(() -> managed.remoteBranchSha(workspace, "main")).hasMessageContaining("origin remote");
    }

    @Test
    void concurrentProvisioningClonesOnceAndRetryReusesCheckout()
            throws Exception
    {
        Remote origin = remote("origin");
        Path commandLog = temporary.resolve("git-commands.log");
        GitCommandRunner git = routedGit(ImmutableMap.of(ORIGIN_URL, origin.bare()), commandLog);
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        Workspace workspace = store.createOrGet(workspace("concurrent", false));
        WorkspaceProvisioningService service = service(store, git);
        ImmutableList.Builder<Callable<WorkspaceCodeSyncResult>> calls = ImmutableList.builder();
        for (int index = 0; index < 8; index++) {
            calls.add(() -> service.synchronizeCode(workspace.id()));
        }

        try (var executor = Executors.newFixedThreadPool(8)) {
            assertThat(executor.invokeAll(calls.build()).stream()
                    .map(future -> {
                        try {
                            return future.get().successful();
                        }
                        catch (Exception failure) {
                            throw new AssertionError(failure);
                        }
                    })
                    .collect(toImmutableList()))
                    .containsOnly(true);
        }

        assertThat(Files.readAllLines(commandLog, StandardCharsets.UTF_8).stream()
                .filter(line -> line.contains(" clone ")))
                .hasSize(1);
        assertThat(service.synchronizeCode(workspace.id()).successful()).isTrue();
        assertThat(Files.readAllLines(commandLog, StandardCharsets.UTF_8).stream()
                .filter(line -> line.contains(" clone ")))
                .hasSize(1);
        assertThat(store.findAll()).singleElement().extracting(Workspace::id).isEqualTo(workspace.id());
    }

    @Test
    void rejectsUnknownDirectoryAndRemoteMismatchWithoutDeletingEither()
            throws IOException
    {
        Remote origin = remote("origin");
        GitCommandRunner git = routedGit(ImmutableMap.of(ORIGIN_URL, origin.bare()), temporary.resolve("git-commands.log"));
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        Workspace unknown = store.createOrGet(workspace("unknown", false));
        Path unexpected = directPath(unknown).resolve("keep.txt");
        Files.createDirectories(unexpected.getParent());
        Files.writeString(unexpected, "keep", StandardCharsets.UTF_8);
        WorkspaceProvisioningService service = service(store, git);

        assertThat(service.synchronizeCode(unknown.id()).successful()).isFalse();
        assertThat(unexpected).hasContent("keep");

        InMemoryWorkspaceStore mismatchStore = new InMemoryWorkspaceStore();
        Workspace mismatch = mismatchStore.createOrGet(workspace("mismatch", false));
        WorkspaceProvisioningService mismatchService = service(mismatchStore, git);
        assertThat(mismatchService.synchronizeCode(mismatch.id()).successful()).isTrue();
        git(directPath(mismatch), "remote", "set-url", "origin", "https://github.test/other/project.git");
        mismatchStore.save(mismatchStore.findById(mismatch.id()).orElseThrow()
                .withCodeSyncAt(Optional.empty(), NOW));

        assertThat(mismatchService.synchronizeCode(mismatch.id()).successful()).isFalse();
        assertThat(git(directPath(mismatch), "remote", "get-url", "origin"))
                .isEqualTo("https://github.test/other/project.git");
        assertThat(directPath(mismatch)).isDirectory();
    }

    @Test
    void fetchPreservesBranchChangesAndLinkedWorktree()
            throws IOException
    {
        Remote origin = remote("origin");
        GitCommandRunner git = routedGit(ImmutableMap.of(ORIGIN_URL, origin.bare()), temporary.resolve("git-commands.log"));
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        Workspace workspace = store.createOrGet(workspace("preserve", false));
        WorkspaceProvisioningService service = service(store, git);
        assertThat(service.synchronizeCode(workspace.id()).successful()).isTrue();
        Path repository = directPath(workspace);
        git(repository, "checkout", "-b", "local-work");
        Path localFile = Files.writeString(repository.resolve("local.txt"), "local", StandardCharsets.UTF_8);
        Path linkedWorktree = temporary.resolve("linked-worktree");
        git(repository, "worktree", "add", "-b", "linked-work", linkedWorktree.toString(), "main");
        Files.writeString(origin.source().resolve("README.md"), "updated", StandardCharsets.UTF_8);
        git(origin.source(), "add", "README.md");
        git(origin.source(), "commit", "-m", "remote update");
        git(origin.source(), "push", origin.bare().toUri().toString(), "main");
        String expectedRemoteHead = git(origin.source(), "rev-parse", "HEAD");

        WorkspaceCodeSyncResult result = service.synchronizeCode(workspace.id());

        assertThat(result.successful()).isTrue();
        assertThat(git(repository, "branch", "--show-current")).isEqualTo("local-work");
        assertThat(localFile).hasContent("local");
        assertThat(linkedWorktree).isDirectory();
        assertThat(git(repository, "status", "--porcelain")).contains("?? local.txt");
        assertThat(git(repository, "rev-parse", "refs/remotes/origin/main"))
                .isEqualTo(expectedRemoteHead);
    }

    @Test
    void fetchingUpstreamPreservesLocalMainAndExistingTaskWorktree()
            throws IOException
    {
        Remote origin = remote("origin");
        Remote upstream = remote("upstream");
        GitCommandRunner git = routedGit(ImmutableMap.of(ORIGIN_URL, origin.bare(), UPSTREAM_URL, upstream.bare()), temporary.resolve("git-commands.log"));
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        Workspace workspace = store.createOrGet(workspace("preserve-fork", true));
        WorkspaceProvisioningService service = service(store, git);
        assertThat(service.synchronizeCode(workspace.id()).successful()).isTrue();
        Path repository = directPath(workspace);
        String localMainHead = git(repository, "rev-parse", "refs/heads/main");
        Path localFile = Files.writeString(repository.resolve("local.txt"), "local work", StandardCharsets.UTF_8);
        Path taskWorktree = temporary.resolve("task-worktree");
        String taskBranch = "bytequay/task/existing-task";
        git(repository, "worktree", "add", "-b", taskBranch, taskWorktree.toString(), "upstream/main");
        String taskHead = git(taskWorktree, "rev-parse", "HEAD");
        Path taskFile = Files.writeString(taskWorktree.resolve("README.md"), "task changes", StandardCharsets.UTF_8);
        Files.writeString(upstream.source().resolve("README.md"), "upstream update", StandardCharsets.UTF_8);
        git(upstream.source(), "add", "README.md");
        git(upstream.source(), "commit", "-m", "upstream update");
        git(upstream.source(), "push", upstream.bare().toUri().toString(), "main");
        String upstreamHead = git(upstream.source(), "rev-parse", "HEAD");
        assertThat(upstreamHead).isNotEqualTo(taskHead);

        WorkspaceCodeSyncResult result = service.synchronizeCode(workspace.id());

        assertThat(result.successful()).isTrue();
        assertThat(git(repository, "rev-parse", "refs/remotes/upstream/main")).isEqualTo(upstreamHead);
        assertThat(git(repository, "branch", "--show-current")).isEqualTo("main");
        assertThat(git(repository, "rev-parse", "refs/heads/main")).isEqualTo(localMainHead);
        assertThat(localFile).hasContent("local work");
        assertThat(git(repository, "status", "--porcelain")).contains("?? local.txt");
        assertThat(git(repository, "rev-parse", "refs/heads/" + taskBranch)).isEqualTo(taskHead);
        assertThat(git(taskWorktree, "branch", "--show-current")).isEqualTo(taskBranch);
        assertThat(git(taskWorktree, "rev-parse", "HEAD")).isEqualTo(taskHead);
        assertThat(taskFile).hasContent("task changes");
        assertThat(git(taskWorktree, "status", "--porcelain")).contains("M README.md");
    }

    @Test
    void repoLineReplacesHandAddedRemotesAndABrokenOneDoesNotFailTheWorkspace()
            throws IOException
    {
        Remote origin = remote("origin");
        GitCommandRunner git = routedGit(ImmutableMap.of(ORIGIN_URL, origin.bare(), CUSTOM_URL, temporary.resolve("missing-custom.git")), temporary.resolve("git-commands.log"));
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        InMemoryRemoteSourceStore remoteSources = new InMemoryRemoteSourceStore();
        WorkspaceRelationships relationships = new WorkspaceRelationships(store, new InMemoryAppSettingsStore(), new ObjectMapper());
        Workspace workspace = store.createOrGet(workspace("broken-custom", false));
        store.createOrGet(new Workspace("oss", "oss", 900, RepoRef.of("other", "project"), Optional.empty(), Optional.empty(), 900, RepoRef.of("other", "project"),
                Topology.DIRECT, "main", CUSTOM_URL, Optional.empty(), Optional.empty(), "/managed/oss", Optional.empty(), Optional.empty(), 0, NOW, NOW));
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        when(gitHub.fetchRepository("token", 100L))
                .thenReturn(Optional.of(direct("contributor", ORIGIN_URL)));
        WorkspaceProvisioningService service = service(store, git, gitHub, remoteSources, relationships);
        assertThat(service.synchronizeCode(workspace.id()).successful()).isTrue();
        remoteSources.save(new RemoteSource(workspace.id(), "hand-added", UPSTREAM_URL));
        relationships.save(workspace.id(), new WorkspaceRelationships.Relationship("oss", WorkspaceRelationships.Kind.FORK, "", false));

        WorkspaceCodeSyncResult result = service.synchronizeCode(workspace.id());

        assertThat(result.successful()).isTrue();
        assertThat(result.failedRemoteSources()).containsExactly("backport-900");
        assertThat(remoteSources.findByWorkspace(workspace.id())).extracting(RemoteSource::name).containsExactly("backport-900");
        assertThat(git(directPath(workspace), "remote").lines()).containsExactly("backport-900", "origin");

        relationships.remove(workspace.id(), "oss");
        assertThat(service.synchronizeCode(workspace.id()).failedRemoteSources()).isEmpty();
        assertThat(git(directPath(workspace), "remote").lines()).containsExactly("origin");
    }

    @Test
    void reconcilesRepositoryRenameByStableId()
            throws IOException
    {
        String renamedUrl = "https://github.test/new-owner/project.git";
        Remote origin = remote("origin");
        GitCommandRunner git = routedGit(ImmutableMap.of(ORIGIN_URL, origin.bare(), renamedUrl, origin.bare()), temporary.resolve("git-commands.log"));
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        Workspace workspace = store.createOrGet(workspace("rename", false));
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        when(gitHub.fetchRepository("token", 100L))
                .thenReturn(Optional.of(direct("contributor", ORIGIN_URL)))
                .thenReturn(Optional.of(direct("new-owner", renamedUrl)));
        WorkspaceProvisioningService service = service(store, git, gitHub);
        service.synchronizeCode(workspace.id());

        WorkspaceCodeSyncResult result = service.synchronizeCode(workspace.id());

        assertThat(result.successful()).isTrue();
        Workspace renamed = store.findById(workspace.id()).orElseThrow();
        assertThat(renamed.selectedRepository()).isEqualTo(RepoRef.of("new-owner", "project"));
        assertThat(git(directPath(workspace), "remote", "get-url", "origin")).isEqualTo(renamedUrl);
    }

    @ParameterizedTest
    @CsvSource({"GITHUB,https://github.com", "GITLAB,https://gitlab.com", "GITLAB,https://git.example.test:8443/gitlab"})
    void selectedHostingAccountClonesFetchesAndFollowsRepositoryRenames(HostingProvider provider, String base)
            throws IOException
    {
        Remote origin = remote("hosted-origin");
        String originalPath = provider == HostingProvider.GITHUB ? "group/project" : "group/subgroup/project";
        String renamedPath = provider == HostingProvider.GITHUB ? "renamed/project" : "renamed/" + originalPath;
        String originalUrl = base + "/" + originalPath + ".git";
        String renamedUrl = base + "/" + renamedPath + ".git";
        GitCommandRunner routed = routedGit(ImmutableMap.of(originalUrl, origin.bare(), renamedUrl, origin.bare()), temporary.resolve("hosted-git.log"));
        GitCommandRunner authenticated = new GitCommandRunner()
        {
            @Override
            public Result run(Path repository, Duration timeout, Map<String, String> environment, List<String> arguments)
            {
                if (ImmutableList.of("clone", "fetch").contains(arguments.getFirst())) {
                    assertThat(environment).containsEntry("GIT_CONFIG_KEY_0", "http." + base + "/.extraHeader");
                    assertThat(environment.get("GIT_CONFIG_VALUE_0")).isNotBlank();
                    assertThat(environment).containsEntry("GIT_CONFIG_KEY_1", "http.followRedirects").containsEntry("GIT_CONFIG_VALUE_1", "false");
                }
                return routed.run(repository, timeout, environment, arguments);
            }
        };
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        HostingInstance instance = new HostingInstance(1, provider, URI.create(base), URI.create(base + "/api"));
        HostingServer server = new HostingServer(provider, instance.webBaseUrl(), instance.apiBaseUrl());
        HostingReadSession session = new HostingReadSession(7, "hosting-account", new HostingAccess(instance, "selected-token"));
        HostingRepository original = new HostingRepository(9, 1, "100", originalPath, URI.create(base + "/" + originalPath), URI.create(originalUrl),
                Optional.empty(), Optional.of("main"));
        HostingRepository renamed = new HostingRepository(9, 1, "100", renamedPath, URI.create(base + "/" + renamedPath), URI.create(renamedUrl),
                Optional.empty(), Optional.of("main"));
        RepoRef ref = WorkspaceHostingService.repositoryRef(original);
        Workspace workspace = store.createOrGet(new Workspace("hosted", "Hosted", 100, ref, Optional.empty(), Optional.empty(), 100, ref, Topology.DIRECT,
                "main", originalUrl, Optional.empty(), Optional.empty(), temporary.resolve("workspaces/hosted/repository").toString(),
                Optional.empty(), Optional.empty(), 0, NOW, NOW, Optional.of(server)));
        store.bindHosting(workspace.id(), 7, 9, Optional.empty());
        HostingConnectionService hosting = mock(HostingConnectionService.class);
        when(hosting.readSession(7)).thenReturn(session);
        when(hosting.resolveRepositoryById(session, "100")).thenReturn(original, renamed);
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        CredentialStore global = mock(CredentialStore.class);
        CredentialService credentials = new CredentialService(global, mock(WorkspaceCredentialStore.class), mock(GhCliService.class), gitHub, hosting, store);
        WorkspacePaths paths = new WorkspacePaths(temporary.resolve("workspaces").toString());
        InMemoryRemoteSourceStore remoteSources = new InMemoryRemoteSourceStore();
        WorkspaceProvisioningService service = new WorkspaceProvisioningService(store, new ManagedWorkspaceGit(paths, authenticated, credentials, remoteSources),
                gitHub, credentials, new WorkspaceRelationships(store, new InMemoryAppSettingsStore(), new ObjectMapper()), remoteSources, hosting,
                Clock.fixed(NOW, ZoneOffset.UTC));

        assertThat(service.synchronizeCode(workspace.id()).successful()).isTrue();
        assertThat(service.synchronizeCode(workspace.id()).successful()).isTrue();

        Workspace current = store.findById(workspace.id()).orElseThrow();
        assertThat(current.selectedRepository().fullName()).isEqualTo(renamedPath);
        assertThat(current.hostingServer()).contains(server);
        assertThat(git(directPath(current), "remote", "get-url", "origin")).isEqualTo(renamedUrl);
        assertThat(git(directPath(current), "rev-parse", "origin/main")).isNotBlank();
        verifyNoInteractions(gitHub, global);
    }

    @Test
    void rejectsWorkspacePathsOutsideManagedRoot()
    {
        WorkspacePaths paths = new WorkspacePaths(temporary.resolve("workspaces").toString());

        assertThatThrownBy(() -> paths.workspace("../outside"))
                .isInstanceOfSatisfying(WorkspaceException.class, failure -> assertThat(failure.code()).isEqualTo("REPOSITORY_ROUTING_INVALID"));
    }

    private WorkspaceProvisioningService service(WorkspaceStore store, GitCommandRunner git)
    {
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        when(gitHub.fetchRepository("token", 100L)).thenReturn(Optional.of(direct("contributor", ORIGIN_URL)));
        when(gitHub.fetchRepository("token", 200L)).thenReturn(Optional.of(fork()));
        when(gitHub.fetchRepository("token", 300L)).thenReturn(Optional.of(upstream()));
        return service(store, git, gitHub);
    }

    private WorkspaceProvisioningService service(WorkspaceStore store, GitCommandRunner git, GitHubAccountClient gitHub)
    {
        return service(store, git, gitHub, new InMemoryRemoteSourceStore());
    }

    private WorkspaceProvisioningService service(WorkspaceStore store, GitCommandRunner git, GitHubAccountClient gitHub, InMemoryRemoteSourceStore remoteSources)
    {
        return service(store, git, gitHub, remoteSources, new WorkspaceRelationships(store, new InMemoryAppSettingsStore(), new ObjectMapper()));
    }

    private WorkspaceProvisioningService service(WorkspaceStore store, GitCommandRunner git, GitHubAccountClient gitHub, InMemoryRemoteSourceStore remoteSources,
            WorkspaceRelationships relationships)
    {
        CredentialService credentials = mock(CredentialService.class);
        when(credentials.resolveGitHubToken()).thenReturn("token");
        when(credentials.resolveGitHubToken(anyString())).thenReturn("token");
        when(credentials.gitAuthenticationEnvironment(anyString(), anyCollection())).thenReturn(ImmutableMap.of("GIT_CONFIG_COUNT", "0"));
        WorkspacePaths paths = new WorkspacePaths(temporary.resolve("workspaces").toString());
        return new WorkspaceProvisioningService(
                store,
                new ManagedWorkspaceGit(paths, git, credentials, remoteSources),
                gitHub,
                credentials,
                relationships,
                remoteSources,
                mock(HostingConnectionService.class),
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private Workspace workspace(String id, boolean fork)
    {
        WorkspacePaths paths = new WorkspacePaths(temporary.resolve("workspaces").toString());
        return new Workspace(
                id,
                id,
                fork ? 200 : 100,
                RepoRef.of("contributor", "project"),
                fork ? Optional.of(300L) : Optional.empty(),
                fork ? Optional.of(RepoRef.of("upstream", "project")) : Optional.empty(),
                fork ? 300 : 100,
                fork ? RepoRef.of("upstream", "project") : RepoRef.of("contributor", "project"),
                fork ? Topology.FORK : Topology.DIRECT,
                "main",
                ORIGIN_URL,
                fork ? Optional.of("main") : Optional.empty(),
                fork ? Optional.of(UPSTREAM_URL) : Optional.empty(),
                paths.workspace(id).resolve("repository").toString(),
                Optional.empty(),
                Optional.empty(),
                0,
                NOW,
                NOW);
    }

    private static UserRepo direct(String owner, String cloneUrl)
    {
        return new UserRepo(100, owner, "project", owner + "/project", Optional.empty(), false, "private", true, "main", cloneUrl, Optional.empty());
    }

    private static UserRepo fork()
    {
        return new UserRepo(
                200,
                "contributor",
                "project",
                "contributor/project",
                Optional.empty(),
                true,
                "public",
                true,
                "main",
                ORIGIN_URL,
                Optional.of(new UserRepo.Upstream(300, "upstream", "project", "main", UPSTREAM_URL)));
    }

    private static UserRepo upstream()
    {
        return new UserRepo(300, "upstream", "project", "upstream/project", Optional.empty(), false, "public", false, "main", UPSTREAM_URL, Optional.empty());
    }

    private Remote remote(String name)
            throws IOException
    {
        Path source = temporary.resolve(name + "-source");
        Files.createDirectories(source);
        git(source, "init", "--initial-branch=main");
        configureUser(source, "ByteQuay Test", "test@bytequay.local");
        Files.writeString(source.resolve("README.md"), name, StandardCharsets.UTF_8);
        git(source, "add", "README.md");
        git(source, "commit", "-m", "initial");
        Path bare = temporary.resolve(name + ".git");
        git(temporary, "clone", "--bare", source.toString(), bare.toString());
        return new Remote(source, bare);
    }

    private GitCommandRunner routedGit(Map<String, Path> routes, Path commandLog)
            throws IOException
    {
        Path executable = temporary.resolve("git-" + routes.hashCode() + ".sh");
        StringBuilder script = new StringBuilder("#!/bin/sh\n")
                .append("printf '%s\\n' \"$*\" >> ")
                .append(shellQuote(commandLog.toString()))
                // Transport routing in this fixture must not alter the URLs production validates.
                .append("\ncase \"$3 $4\" in 'remote get-url') exec git \"$@\" ;; esac\nexec git");
        routes.forEach((url, remote) -> script.append(" -c ")
                .append(shellQuote("url." + remote.toUri() + ".insteadOf=" + url)));
        script.append(" \"$@\"\n");
        Files.writeString(executable, script, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
        return new GitCommandRunner(executable.toString());
    }

    private static Path directPath(Workspace workspace)
    {
        return Path.of(workspace.localRepositoryPath());
    }

    private static String shellQuote(String value)
    {
        return "'" + value.replace("'", "'\\''") + "'";
    }

    private record Remote(Path source, Path bare) {}
}
