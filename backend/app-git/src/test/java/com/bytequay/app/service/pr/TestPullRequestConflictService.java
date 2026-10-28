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
import com.bytequay.app.repository.github.GitHubPullRequestIdentity;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.workspace.WorkspaceRepositoryContext;
import com.bytequay.app.workspace.WorkspaceRepositoryService;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalLong;

import static com.bytequay.app.git.GitTestSupport.configureUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
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
    private final GitCommandRunner commands = new GitCommandRunner();
    private final GitCommandRunner git = spy(commands);
    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final WorkspaceRepositoryService repositories = mock(WorkspaceRepositoryService.class);
    private final GitHubPullRequestReadClient github = mock(GitHubPullRequestReadClient.class);
    private final CredentialService credentials = mock(CredentialService.class);
    private final PullRequestConflictService service = new PullRequestConflictService(workspaces, repositories, github, credentials, git);

    @TempDir
    private Path repository;

    @BeforeEach
    void setUp()
            throws IOException
    {
        command("init", "--quiet", "-b", "main");
        configureUser(repository, "ByteQuay Test", "test@example.com");
        Workspace workspace = mock(Workspace.class, withSettings().mockMaker(MockMakers.INLINE));
        when(workspace.isGitHub()).thenReturn(true);
        when(workspace.id()).thenReturn("workspace");
        when(workspace.monitoredRepository()).thenReturn(REPO);
        when(workspace.selectedRepository()).thenReturn(RepoRef.of("viewer", "fork"));
        when(workspaces.findAll()).thenReturn(ImmutableList.of(workspace));
        when(repositories.requireLocalRepository("workspace")).thenReturn(new WorkspaceRepositoryContext("workspace", repository, 1,
                RepoRef.of("viewer", "fork"), OptionalLong.of(2), REPO, "upstream", "origin", "main", ImmutableList.of()));
        when(credentials.resolveGitHubToken("workspace")).thenReturn("test-token");
        when(credentials.gitAuthenticationEnvironment("workspace", ImmutableList.of(URL))).thenReturn(ImmutableMap.of());
        doAnswer(invocation -> {
            List<String> arguments = new ArrayList<>(invocation.<List<String>>getArgument(3));
            assertThat(arguments).containsExactly("fetch", "--no-tags", "--no-recurse-submodules", "--no-write-fetch-head", "--no-auto-maintenance", "--", URL,
                    arguments.get(7), arguments.get(8));
            arguments.set(6, repository.toString());
            return commands.run(repository, Duration.ofMinutes(2), ImmutableMap.of(), arguments);
        }).when(git).run(eq(repository), eq(Duration.ofMinutes(2)), anyMap(), anyList());
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
        when(github.fetchPullRequestIdentity("test-token", PR)).thenReturn(identity(base, head));
        Files.writeString(repository.resolve("staged.txt"), "staged\n");
        command("add", "staged.txt");
        Files.writeString(repository.resolve("staged.txt"), "unstaged\n");
        Files.writeString(repository.resolve("untracked.txt"), "untracked\n");
        Files.writeString(repository.resolve(".git/FETCH_HEAD"), "preserve fetch head\n");
        String status = command("status", "--porcelain=v1", "-z");
        String refs = command("show-ref");
        byte[] index = Files.readAllBytes(repository.resolve(".git/index"));

        PullRequestConflicts result = service.read("OWNER/REPO", 7);

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
        when(workspaces.findAll()).thenReturn(ImmutableList.of());

        assertThatThrownBy(() -> service.read("owner/repo", 7)).isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("Watch repository to list conflicted files");
        verifyNoInteractions(repositories, github, credentials);
    }

    @Test
    void reportsFetchFailureInsteadOfAnEmptyConflictList()
    {
        String sha = command("rev-parse", "HEAD").strip();
        when(github.fetchPullRequestIdentity("test-token", PR)).thenReturn(identity(sha, sha));
        doReturn(Result.failed("authentication failed")).when(git).run(eq(repository), eq(Duration.ofMinutes(2)), anyMap(), anyList());

        assertThatThrownBy(() -> service.read("owner/repo", 7)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("Could not fetch");
    }

    @Test
    void rejectsShallowHistoryRatherThanGuessingTheMergeBase()
            throws IOException
    {
        String sha = command("rev-parse", "HEAD").strip();
        when(github.fetchPullRequestIdentity("test-token", PR)).thenReturn(identity(sha, sha));
        Files.writeString(repository.resolve(".git/shallow"), sha + "\n");

        assertThatThrownBy(() -> service.read("owner/repo", 7)).isInstanceOf(ResponseStatusException.class).hasMessageContaining("Complete repository history");
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

    private static GitHubPullRequestIdentity identity(String base, String head)
    {
        return new GitHubPullRequestIdentity(PR, "https://github.com/owner/repo/pull/7", RepoRef.of("contributor", "fork"), "feature", head, REPO, "main", base,
                GitHubPullRequestIdentity.State.OPEN, false, "contributor");
    }

    private String command(String... arguments)
    {
        Result result = commands.run(repository, arguments);
        assertThat(result.successful()).as(result.output()).isTrue();
        return result.output();
    }
}
