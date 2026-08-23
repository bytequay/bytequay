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

import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.repository.github.GitHubAccountClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.UserRepo;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.domain.Workspace.CodeState;
import com.bytequay.core.domain.Workspace.MetadataState;
import com.bytequay.core.domain.Workspace.Topology;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
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

class TestDefaultWorkspaceProvisioningService
{
    private static final Instant NOW = Instant.parse("2026-08-21T00:00:00Z");
    private static final String ORIGIN_URL = "https://github.test/contributor/project.git";
    private static final String UPSTREAM_URL = "https://github.test/upstream/project.git";

    @TempDir
    private Path temporary;

    @Test
    void provisionsDirectAndForkRouting()
            throws IOException
    {
        Remote origin = remote("origin");
        Remote upstream = remote("upstream");
        Path commandLog = temporary.resolve("git-commands.log");
        GitCommandRunner git = routedGit(
                ImmutableMap.of(ORIGIN_URL, origin.bare(), UPSTREAM_URL, upstream.bare()),
                commandLog);
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        Workspace direct = store.createOrGet(workspace("direct", false));
        Workspace fork = store.createOrGet(workspace("fork", true));
        DefaultWorkspaceProvisioningService service = service(store, git);

        WorkspaceProvisioningResult directResult = service.provision(direct.id());
        WorkspaceProvisioningResult forkResult = service.provision(fork.id());

        assertThat(directResult.codeState()).isEqualTo(CodeState.READY);
        assertThat(git(directPath(direct), "remote").lines()).containsExactly("origin");
        assertThat(forkResult.codeState()).isEqualTo(CodeState.READY);
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

        assertThat(service.synchronizeCode(fork.id()).codeState()).isEqualTo(CodeState.READY);
        assertThat(git(directPath(fork), "remote").lines()).containsExactly("origin");
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
        DefaultWorkspaceProvisioningService service = service(store, git);
        ImmutableList.Builder<Callable<WorkspaceProvisioningResult>> calls = ImmutableList.builder();
        for (int index = 0; index < 8; index++) {
            calls.add(() -> service.provision(workspace.id()));
        }

        try (var executor = Executors.newFixedThreadPool(8)) {
            assertThat(executor.invokeAll(calls.build()).stream()
                    .map(future -> {
                        try {
                            return future.get().codeState();
                        }
                        catch (Exception failure) {
                            throw new AssertionError(failure);
                        }
                    })
                    .collect(toImmutableList()))
                    .containsOnly(CodeState.READY);
        }

        assertThat(Files.readAllLines(commandLog, StandardCharsets.UTF_8).stream()
                .filter(line -> line.contains(" clone ")))
                .hasSize(1);
        Workspace cloned = store.findById(workspace.id()).orElseThrow();
        store.save(cloned.withCodeState(
                CodeState.CLONING,
                cloned.lastCodeSyncAt(),
                Optional.empty(),
                NOW));
        assertThat(service.provision(workspace.id()).codeState()).isEqualTo(CodeState.READY);
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
        GitCommandRunner git = routedGit(
                ImmutableMap.of(ORIGIN_URL, origin.bare()),
                temporary.resolve("git-commands.log"));
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        Workspace unknown = store.createOrGet(workspace("unknown", false));
        Path unexpected = directPath(unknown).resolve("keep.txt");
        Files.createDirectories(unexpected.getParent());
        Files.writeString(unexpected, "keep", StandardCharsets.UTF_8);
        DefaultWorkspaceProvisioningService service = service(store, git);

        assertThat(service.provision(unknown.id()).codeState()).isEqualTo(CodeState.FAILED);
        assertThat(unexpected).hasContent("keep");

        InMemoryWorkspaceStore mismatchStore = new InMemoryWorkspaceStore();
        Workspace mismatch = mismatchStore.createOrGet(workspace("mismatch", false));
        DefaultWorkspaceProvisioningService mismatchService = service(mismatchStore, git);
        assertThat(mismatchService.provision(mismatch.id()).codeState()).isEqualTo(CodeState.READY);
        git(directPath(mismatch), "remote", "set-url", "origin", "https://github.test/other/project.git");
        mismatchStore.save(mismatchStore.findById(mismatch.id()).orElseThrow().withCodeState(
                CodeState.PENDING,
                Optional.empty(),
                Optional.empty(),
                NOW));

        assertThat(mismatchService.provision(mismatch.id()).codeState()).isEqualTo(CodeState.FAILED);
        assertThat(git(directPath(mismatch), "remote", "get-url", "origin"))
                .isEqualTo("https://github.test/other/project.git");
        assertThat(directPath(mismatch)).isDirectory();
    }

    @Test
    void fetchPreservesBranchChangesAndLinkedWorktree()
            throws IOException
    {
        Remote origin = remote("origin");
        GitCommandRunner git = routedGit(
                ImmutableMap.of(ORIGIN_URL, origin.bare()),
                temporary.resolve("git-commands.log"));
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        Workspace workspace = store.createOrGet(workspace("preserve", false));
        DefaultWorkspaceProvisioningService service = service(store, git);
        assertThat(service.provision(workspace.id()).codeState()).isEqualTo(CodeState.READY);
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

        assertThat(result.codeState()).isEqualTo(CodeState.READY);
        assertThat(git(repository, "branch", "--show-current")).isEqualTo("local-work");
        assertThat(localFile).hasContent("local");
        assertThat(linkedWorktree).isDirectory();
        assertThat(git(repository, "status", "--porcelain")).contains("?? local.txt");
        assertThat(git(repository, "rev-parse", "refs/remotes/origin/main"))
                .isEqualTo(expectedRemoteHead);
    }

    @Test
    void reconcilesRepositoryRenameByStableId()
            throws IOException
    {
        String renamedUrl = "https://github.test/new-owner/project.git";
        Remote origin = remote("origin");
        GitCommandRunner git = routedGit(
                ImmutableMap.of(ORIGIN_URL, origin.bare(), renamedUrl, origin.bare()),
                temporary.resolve("git-commands.log"));
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        Workspace workspace = store.createOrGet(workspace("rename", false));
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        when(gitHub.fetchRepository("token", 100L))
                .thenReturn(Optional.of(direct("contributor", ORIGIN_URL)))
                .thenReturn(Optional.of(direct("new-owner", renamedUrl)));
        DefaultWorkspaceProvisioningService service = service(store, git, gitHub);
        service.provision(workspace.id());

        WorkspaceCodeSyncResult result = service.synchronizeCode(workspace.id());

        assertThat(result.codeState()).isEqualTo(CodeState.READY);
        Workspace renamed = store.findById(workspace.id()).orElseThrow();
        assertThat(renamed.selectedRepository()).isEqualTo(RepoRef.of("new-owner", "project"));
        assertThat(git(directPath(workspace), "remote", "get-url", "origin")).isEqualTo(renamedUrl);
    }

    @Test
    void startupRecoverySchedulesInterruptedCloneAndFetch()
    {
        InMemoryWorkspaceStore store = new InMemoryWorkspaceStore();
        Workspace pending = store.createOrGet(workspace("pending", false));
        Workspace syncing = store.createOrGet(workspace("syncing", true));
        store.save(syncing.withCodeState(
                CodeState.SYNCING,
                Optional.empty(),
                Optional.empty(),
                NOW));
        WorkspaceProvisioningService provisioning = mock(WorkspaceProvisioningService.class);
        WorkspaceMetadataService metadata = mock(WorkspaceMetadataService.class);
        WorkspaceProvisioningRecovery recovery = new WorkspaceProvisioningRecovery(
                store,
                provisioning,
                metadata,
                Runnable::run);

        recovery.recover();

        verify(provisioning).provision(pending.id());
        verify(provisioning).synchronizeCode(syncing.id());
        verify(metadata).synchronize(pending.id());
        verify(metadata).synchronize(syncing.id());
    }

    @Test
    void rejectsWorkspacePathsOutsideManagedRoot()
    {
        WorkspacePaths paths = new WorkspacePaths(temporary.resolve("workspaces").toString());

        assertThatThrownBy(() -> paths.workspace("../outside"))
                .isInstanceOfSatisfying(WorkspaceException.class,
                        failure -> assertThat(failure.code()).isEqualTo("REPOSITORY_ROUTING_INVALID"));
    }

    private DefaultWorkspaceProvisioningService service(
            WorkspaceStore store,
            GitCommandRunner git)
    {
        GitHubAccountClient gitHub = mock(GitHubAccountClient.class);
        when(gitHub.fetchRepository("token", 100L)).thenReturn(Optional.of(direct("contributor", ORIGIN_URL)));
        when(gitHub.fetchRepository("token", 200L)).thenReturn(Optional.of(fork()));
        when(gitHub.fetchRepository("token", 300L)).thenReturn(Optional.of(upstream()));
        return service(store, git, gitHub);
    }

    private DefaultWorkspaceProvisioningService service(
            WorkspaceStore store,
            GitCommandRunner git,
            GitHubAccountClient gitHub)
    {
        CredentialService credentials = mock(CredentialService.class);
        when(credentials.resolveGitHubToken()).thenReturn("token");
        WorkspacePaths paths = new WorkspacePaths(temporary.resolve("workspaces").toString());
        return new DefaultWorkspaceProvisioningService(
                store,
                new ManagedWorkspaceGit(paths, git, credentials),
                gitHub,
                credentials,
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
                CodeState.PENDING,
                MetadataState.PENDING,
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                Optional.empty(),
                0,
                NOW,
                NOW);
    }

    private static UserRepo direct(String owner, String cloneUrl)
    {
        return new UserRepo(
                100,
                owner,
                "project",
                owner + "/project",
                Optional.empty(),
                false,
                "private",
                true,
                "main",
                cloneUrl,
                Optional.empty());
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
                Optional.of(new UserRepo.Upstream(
                        300,
                        "upstream",
                        "project",
                        "main",
                        UPSTREAM_URL)));
    }

    private static UserRepo upstream()
    {
        return new UserRepo(
                300,
                "upstream",
                "project",
                "upstream/project",
                Optional.empty(),
                false,
                "public",
                false,
                "main",
                UPSTREAM_URL,
                Optional.empty());
    }

    private Remote remote(String name)
            throws IOException
    {
        Path source = temporary.resolve(name + "-source");
        Files.createDirectories(source);
        git(source, "init", "--initial-branch=main");
        git(source, "config", "user.name", "ByteQuay Test");
        git(source, "config", "user.email", "test@bytequay.local");
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
                .append("\nexec git");
        routes.forEach((url, remote) -> script.append(" -c ")
                .append(shellQuote("url." + remote.toUri() + ".insteadOf=" + url)));
        script.append(" \"$@\"\n");
        Files.writeString(executable, script, StandardCharsets.UTF_8);
        Files.setPosixFilePermissions(executable, PosixFilePermissions.fromString("rwx------"));
        return new GitCommandRunner(executable.toString());
    }

    private static String git(Path directory, String... arguments)
    {
        GitCommandRunner.Result result = new GitCommandRunner().run(directory, arguments);
        assertThat(result.successful()).describedAs(result.output()).isTrue();
        return result.output().strip();
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
