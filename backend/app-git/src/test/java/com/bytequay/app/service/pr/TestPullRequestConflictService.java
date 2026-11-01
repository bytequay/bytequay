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
package com.bytequay.app.service.pr;

import com.bytequay.app.git.command.GitCommandRunner;
import com.bytequay.app.git.command.GitCommandRunner.Result;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.hosting.HostingConnectionService;
import com.bytequay.app.service.hosting.HostingIntegrations;
import com.bytequay.app.service.hosting.HostingReadSession;
import com.bytequay.app.workspace.WorkspaceHostingRead;
import com.bytequay.app.workspace.WorkspaceHostingService;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.domain.PullRequestIdentity;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.hosting.HostingAccess;
import com.bytequay.core.hosting.HostingInstance;
import com.bytequay.core.hosting.HostingRepository;
import com.bytequay.core.hosting.HostingTarget;
import com.bytequay.core.hosting.PullRequestContentReader;
import com.bytequay.core.hosting.PullRequestWriter;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockMakers;
import org.springframework.web.server.ResponseStatusException;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;

import static com.bytequay.app.git.GitTestSupport.configureUser;
import static com.bytequay.app.service.hosting.HostingIntegrationFixtures.integrations;
import static com.bytequay.app.service.hosting.HostingIntegrationFixtures.pullRequests;
import static com.bytequay.core.hosting.HostingProvider.GITHUB;
import static com.bytequay.core.hosting.HostingProvider.GITLAB;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

class TestPullRequestConflictService
{
    private static final RepoRef REPO = RepoRef.of("owner", "repo");
    private static final PullRequestRef PR = PullRequestRef.of("owner", "repo", 7);
    private static final String FILE = "目录/file with spaces\nand newline.txt";
    private static final String URL = "https://github.com/owner/repo.git";
    private static final HostingReadSession SESSION = new HostingReadSession(10, "desktop-scope",
            new HostingAccess(new HostingInstance(1, GITHUB, URI.create("https://github.com"), URI.create("https://api.github.com")), "test-token"));
    private static final HostingRepository HOSTED = new HostingRepository(1, 1, "100", "owner/repo", URI.create("https://github.com/owner/repo"), URI.create(URL),
            Optional.empty(), Optional.of("main"));
    private static final HostingTarget TARGET = new HostingTarget(SESSION.access(), HOSTED);
    private static final PullRequestRoute DESKTOP = PullRequestRoute.of(null, null);
    private final GitCommandRunner commands = new GitCommandRunner();
    private final GitCommandRunner git = spy(commands);
    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final WorkspaceRepositoryService repositories = mock(WorkspaceRepositoryService.class);
    private final WorkspaceHostingService hosting = mock(WorkspaceHostingService.class);
    private final PullRequestContentReader contents = mock(PullRequestContentReader.class);
    private final PullRequestContentReader gitlabContents = mock(PullRequestContentReader.class);
    private final HostingIntegrations integrations = integrations(pullRequests(GITHUB, contents, mock(PullRequestWriter.class)),
            pullRequests(GITLAB, gitlabContents, mock(PullRequestWriter.class)));
    private final CredentialService credentials = mock(CredentialService.class);
    private final HostingConnectionService connections = mock(HostingConnectionService.class);
    private final PullRequestConflictService service = new PullRequestConflictService(workspaces, repositories, hosting, connections, integrations, credentials, git);
    private String url = URL;

    @TempDir
    private Path repository;

    @BeforeEach
    void setUp()
            throws IOException
    {
        command("init", "--quiet", "-b", "main");
        configureUser(repository, "ByteQuay Test", "test@example.com");
        Workspace workspace = mock(Workspace.class, withSettings().mockMaker(MockMakers.INLINE));
        when(workspace.id()).thenReturn("workspace");
        when(workspace.monitoredRepository()).thenReturn(REPO);
        when(workspace.selectedRepository()).thenReturn(RepoRef.of("viewer", "fork"));
        when(credentials.captureHostingRead()).thenReturn(SESSION);
        when(connections.findRepository(eq(SESSION), anyString())).thenReturn(Optional.of(HOSTED));
        when(workspaces.findByHostingRepositoryId(1)).thenReturn(Optional.of(workspace));
        when(workspaces.isWatched("workspace")).thenReturn(true);
        when(repositories.requireLocalRepository("workspace")).thenReturn(new WorkspaceRepositoryContext("workspace", repository, 1,
                RepoRef.of("viewer", "fork"), OptionalLong.of(2), REPO, "upstream", "origin", "main", ImmutableList.of()));
        when(hosting.captureRead("workspace")).thenReturn(new WorkspaceHostingRead(SESSION, HOSTED, "viewer"));
        when(credentials.gitAuthenticationEnvironment("workspace", ImmutableList.of(URL))).thenReturn(ImmutableMap.of());
        doAnswer(invocation -> {
            List<String> arguments = new ArrayList<>(invocation.<List<String>>getArgument(3));
            assertThat(arguments).containsExactly("fetch", "--no-tags", "--no-recurse-submodules", "--no-write-fetch-head", "--no-auto-maintenance", "--", url,
                    arguments.get(7), arguments.get(8));
            arguments.set(6, repository.toString());
            return commands.run(repository, Duration.ofMinutes(2), ImmutableMap.of(), arguments);
        }).when(git).run(eq(repository), eq(Duration.ofMinutes(2)), anyMap(), anyList());
        doAnswer(invocation -> commands.run(repository, "ls-remote", "--exit-code", "--refs", "--", repository.toString(), "refs/heads/main"))
                .when(git).run(eq(repository), eq(Duration.ofMinutes(2)), anyMap(),
                        eq(ImmutableList.of("ls-remote", "--exit-code", "--refs", "--", URL, "refs/heads/main")));
        Files.createDirectories(repository.resolve("目录"));
        Files.writeString(repository.resolve(FILE), "base\n");
        command("add", ".");
        command("commit", "--quiet", "-m", "Base");
    }

    @ParameterizedTest
    @ValueSource(strings = {"content", "delete", "binary", "rename", "clean"})
    void comparesRemoteCommitsWithoutChangingTheCheckout(String kind)
            throws IOException
    {
        String previousBase = command("rev-parse", "HEAD").strip();
        command("checkout", "--quiet", "-b", "feature");
        if (kind.equals("rename")) {
            command("mv", FILE, "renamed.txt");
        }
        else {
            Files.writeString(repository.resolve(FILE), kind.equals("binary") ? "feature\0data" : "feature\n");
        }
        command("add", "-A");
        command("commit", "--quiet", "-m", "Feature");
        String head = command("rev-parse", "HEAD").strip();
        command("checkout", "--quiet", "main");
        if (kind.equals("delete") || kind.equals("rename")) {
            command("rm", FILE);
        }
        else if (kind.equals("clean")) {
            Files.writeString(repository.resolve("target-only.txt"), "target\n");
        }
        else {
            Files.writeString(repository.resolve(FILE), kind.equals("binary") ? "target\0data" : "target\n");
        }
        command("add", "-A");
        command("commit", "--quiet", "-m", "Target");
        String base = command("rev-parse", "HEAD").strip();
        when(contents.identity(TARGET, 7)).thenReturn(identity(previousBase, head));
        Files.writeString(repository.resolve("staged.txt"), "staged\n");
        command("add", "staged.txt");
        Files.writeString(repository.resolve("staged.txt"), "unstaged\n");
        Files.writeString(repository.resolve("untracked.txt"), "untracked\n");
        Files.writeString(repository.resolve(".git/FETCH_HEAD"), "preserve fetch head\n");
        String status = command("status", "--porcelain=v1", "-z");
        String refs = command("show-ref");
        byte[] index = Files.readAllBytes(repository.resolve(".git/index"));

        PullRequestConflicts result = service.read(DESKTOP, "OWNER/REPO", 7);

        assertThat(result.baseRef()).isEqualTo("main");
        assertThat(result.files()).containsExactlyElementsOf(kind.equals("clean") ? ImmutableList.of() : ImmutableList.of(kind.equals("rename") ? "renamed.txt" : FILE));
        assertThat(command("rev-parse", "HEAD").strip()).isEqualTo(base);
        assertThat(command("show-ref")).isEqualTo(refs);
        assertThat(Files.readAllBytes(repository.resolve(".git/index"))).isEqualTo(index);
        assertThat(command("status", "--porcelain=v1", "-z")).isEqualTo(status);
        assertThat(Files.readString(repository.resolve("staged.txt"))).isEqualTo("unstaged\n");
        assertThat(Files.readString(repository.resolve(".git/FETCH_HEAD"))).isEqualTo("preserve fetch head\n");
        verify(credentials).gitAuthenticationEnvironment("workspace", ImmutableList.of(URL));
    }

    @Test
    void doesNotFetchOrProvisionAnUnwatchedRepository()
    {
        when(workspaces.isWatched("workspace")).thenReturn(false);

        assertThatThrownBy(() -> service.read(DESKTOP, "owner/repo", 7)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Watch repository to list conflicted files");
        when(workspaces.findByHostingRepositoryId(1)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.read(DESKTOP, "owner/repo", 7)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Watch repository to list conflicted files");
        verifyNoInteractions(repositories, hosting, contents);
        verify(credentials, never()).gitAuthenticationEnvironment(anyString(), anyList());
    }

    @Test
    void comparesAGitLabMergeRequestThroughItsWorkspace()
    {
        comparesAGitLabMergeRequest(PullRequestRoute.of("gitlab", null));
    }

    @Test
    void comparesAGitLabMergeRequestOpenedThroughAnAccountInItsWatchedWorkspace()
    {
        comparesAGitLabMergeRequest(PullRequestRoute.account(11));
    }

    private void comparesAGitLabMergeRequest(PullRequestRoute route)
    {
        String sha = command("rev-parse", "HEAD").strip();
        HostingReadSession session = new HostingReadSession(11, "gitlab-account",
                new HostingAccess(new HostingInstance(2, GITLAB, URI.create("https://gitlab.example.com"), URI.create("https://gitlab.example.com/api/v4")), "gitlab-token"));
        url = "https://gitlab.example.com/group/sub/project.git";
        HostingRepository project = new HostingRepository(21, 2, "200", "group/sub/project", URI.create("https://gitlab.example.com/group/sub/project"), URI.create(url),
                Optional.empty(), Optional.of("main"));
        RepoRef path = RepoRef.of("group/sub", "project");
        Workspace workspace = mock(Workspace.class, withSettings().mockMaker(MockMakers.INLINE));
        when(workspace.id()).thenReturn("gitlab");
        when(workspace.monitoredRepository()).thenReturn(path);
        when(workspace.selectedRepository()).thenReturn(path);
        when(workspaces.findById("gitlab")).thenReturn(Optional.of(workspace));
        when(connections.readSession(11)).thenReturn(session);
        when(connections.findRepository(session, "group/sub/project")).thenReturn(Optional.of(project));
        when(workspaces.findByHostingRepositoryId(21)).thenReturn(Optional.of(workspace));
        when(workspaces.isWatched("gitlab")).thenReturn(true);
        when(repositories.requireLocalRepository("gitlab")).thenReturn(new WorkspaceRepositoryContext("gitlab", repository, 21, path, OptionalLong.empty(), path,
                "origin", "origin", "main", ImmutableList.of()));
        when(hosting.captureRead("gitlab")).thenReturn(new WorkspaceHostingRead(session, project, "viewer"));
        when(gitlabContents.identity(new HostingTarget(session.access(), project), 7)).thenReturn(new PullRequestIdentity(PullRequestRef.of("group/sub", "project", 7),
                "https://gitlab.example.com/group/sub/project/-/merge_requests/7", path, "feature", sha, path, "main", sha, PullRequestIdentity.State.OPEN, false, "contributor"));
        when(credentials.gitAuthenticationEnvironment("gitlab", ImmutableList.of(url))).thenReturn(ImmutableMap.of());
        doAnswer(invocation -> commands.run(repository, "ls-remote", "--exit-code", "--refs", "--", repository.toString(), "refs/heads/main"))
                .when(git).run(eq(repository), eq(Duration.ofMinutes(2)), anyMap(), eq(ImmutableList.of("ls-remote", "--exit-code", "--refs", "--", url, "refs/heads/main")));

        PullRequestConflicts result = service.read(route, "group/sub/project", 7);

        assertThat(result.files()).isEmpty();
        verify(credentials).gitAuthenticationEnvironment("gitlab", ImmutableList.of(url));
        verifyNoInteractions(contents);
    }

    @Test
    void reportsFetchFailureInsteadOfAnEmptyConflictList()
    {
        String sha = command("rev-parse", "HEAD").strip();
        when(contents.identity(TARGET, 7)).thenReturn(identity(sha, sha));
        doReturn(Result.failed("authentication failed")).when(git).run(eq(repository), eq(Duration.ofMinutes(2)), anyMap(),
                eq(ImmutableList.of("fetch", "--no-tags", "--no-recurse-submodules", "--no-write-fetch-head", "--no-auto-maintenance", "--", URL, sha, sha)));

        assertThatThrownBy(() -> service.read(DESKTOP, "owner/repo", 7)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("Could not fetch");
    }

    @Test
    void rejectsMissingOrInvalidTargetBranchInsteadOfUsingTheOldBase()
    {
        String sha = command("rev-parse", "HEAD").strip();
        when(contents.identity(TARGET, 7)).thenReturn(identity(sha, sha));
        for (Result result : ImmutableList.of(Result.failed("authentication failed"), new Result(2, "", false),
                new Result(0, sha + "\trefs/heads/main\n", true), new Result(0, "invalid\trefs/heads/main\n", false),
                new Result(0, sha + "\trefs/heads/other\n", false), new Result(0, sha + "\trefs/heads/main\n" + sha + "\trefs/heads/other\n", false))) {
            doReturn(result).when(git).run(eq(repository), eq(Duration.ofMinutes(2)), anyMap(),
                    eq(ImmutableList.of("ls-remote", "--exit-code", "--refs", "--", URL, "refs/heads/main")));

            assertThatThrownBy(() -> service.read(DESKTOP, "owner/repo", 7)).isInstanceOf(ResponseStatusException.class)
                    .hasMessageContaining("Could not read the current target branch");
        }
    }

    @Test
    void rejectsShallowHistoryRatherThanGuessingTheMergeBase()
            throws IOException
    {
        String sha = command("rev-parse", "HEAD").strip();
        when(contents.identity(TARGET, 7)).thenReturn(identity(sha, sha));
        Files.writeString(repository.resolve(".git/shallow"), sha + "\n");

        assertThatThrownBy(() -> service.read(DESKTOP, "owner/repo", 7)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("Complete repository history");
    }

    @Test
    void rejectsIncompleteAndUnrecognizedGitOutput()
    {
        String tree = "a".repeat(40);
        for (Result result : ImmutableList.of(new Result(1, tree + "\0file\0", true), new Result(1, tree + "\0file", false),
                new Result(1, tree + "\0", false), new Result(0, tree + "\0file\0", false), new Result(128, "unknown option", false))) {
            assertThatThrownBy(() -> PullRequestConflictService.conflictPaths(result)).isInstanceOf(ResponseStatusException.class);
        }
    }

    private static PullRequestIdentity identity(String base, String head)
    {
        return new PullRequestIdentity(PR, "https://github.com/owner/repo/pull/7", RepoRef.of("contributor", "fork"), "feature", head, REPO, "main", base,
                PullRequestIdentity.State.OPEN, false, "contributor");
    }

    private String command(String... arguments)
    {
        Result result = commands.run(repository, arguments);
        assertThat(result.successful()).as(result.output()).isTrue();
        return result.output();
    }
}
