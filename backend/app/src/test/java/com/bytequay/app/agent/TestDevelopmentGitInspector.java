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
package com.bytequay.app.agent;

import com.bytequay.agent.core.AgentTask;
import com.bytequay.agent.core.AgentTaskDefaults;
import com.bytequay.agent.core.InvalidAgentRequestException;
import com.bytequay.agent.core.ProvisionWorktreeCommand;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.git.GitCommandRunner.Result;
import com.bytequay.app.repository.github.GitHubPullRequestIdentity;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.workspace.ManagedWorktreeService;
import com.bytequay.app.workspace.WorkspacePaths;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.WorkspaceStore;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class TestDevelopmentGitInspector
{
    private static final String WORKSPACE_ID = "development-test";
    private static final String ORIGIN = "https://github.com/alice/project.git";
    private static final String UPSTREAM = "https://github.com/acme/project.git";
    private static final RepoRef REPOSITORY = RepoRef.of("alice", "project");
    private static final RepoRef BASE_REPOSITORY = RepoRef.of("acme", "project");
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @TempDir
    private Path temporaryDirectory;

    private final WorkspaceStore workspaces = mock(WorkspaceStore.class);
    private final CredentialService credentials = mock(CredentialService.class);
    private final GitHubPullRequestReadClient pullRequests = mock(GitHubPullRequestReadClient.class);
    private TestGitRunner git;
    private ManagedWorktreeService worktreeService;
    private DevelopmentGitInspector inspector;
    private Path repository;
    private Path bareRemote;
    private TaskWorktree worktree;
    private AgentTask task;
    private String baseSha;

    @BeforeEach
    void setUp()
            throws IOException
    {
        WorkspacePaths paths = new WorkspacePaths(temporaryDirectory.resolve("managed").toString());
        repository = paths.repository(WORKSPACE_ID);
        bareRemote = temporaryDirectory.resolve("remote.git");
        Files.createDirectories(repository);
        Files.writeString(paths.workspace(WORKSPACE_ID).resolve(".bytequay-workspace"), WORKSPACE_ID + "\n1\n");
        git = new TestGitRunner();
        run(repository, "init", "--initial-branch=main");
        run(repository, "config", "user.name", "ByteQuay Test");
        run(repository, "config", "user.email", "test@example.invalid");
        run(repository, "remote", "add", "origin", ORIGIN);
        Files.writeString(repository.resolve("README.md"), "initial\n");
        Files.writeString(repository.resolve(".gitignore"), "build/\n");
        run(repository, "add", ".");
        run(repository, "commit", "-m", "Initial");
        baseSha = run(repository, "rev-parse", "HEAD");
        run(repository, "update-ref", "refs/remotes/origin/main", baseSha);
        run(repository, "init", "--bare", bareRemote.toString());
        when(workspaces.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace(false, "main")));
        when(credentials.gitAuthenticationEnvironment(eq(WORKSPACE_ID), anyCollection())).thenReturn(ImmutableMap.of());
        when(credentials.resolveGitHubToken(WORKSPACE_ID)).thenReturn("test-token");
        worktreeService = new ManagedWorktreeService(paths, workspaces, git);
        worktree = worktreeService.provision(new ProvisionWorktreeCommand(
                TaskId.random(), WORKSPACE_ID, RepositoryIdentity.valueOf(REPOSITORY.fullName()), baseSha));
        task = AgentTask.created(worktree.taskId(), true, "Development test", TitleSource.USER, AgentLaneId.CODEX_CLI,
                worktree, AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), NOW);
        inspector = new DevelopmentGitInspector(worktreeService, workspaces, git, credentials, pullRequests);
    }

    @Test
    void capturesLocalFactsWithoutReadingGitHubOrRemote()
    {
        DevelopmentBaseline baseline = inspector.capture(task);

        assertThat(baseline.worktree()).isEqualTo(worktree);
        assertThat(baseline.local().headSha()).isEqualTo(baseSha);
        assertThat(baseline.local().uncommittedFileCount()).isZero();
        assertThat(baseline.local().commitCount()).isZero();
        assertThat(baseline.local().diffFromBase()).isFalse();
        assertThat(baseline.route().headRepository()).isEqualTo(REPOSITORY);
        assertThat(baseline.route().baseRepository()).isEqualTo(REPOSITORY);
        assertThat(baseline.route().pushUrl()).isEqualTo(ORIGIN);
        verifyNoInteractions(credentials, pullRequests);
        assertThat(git.remoteReads).isZero();
    }

    @Test
    void countsUniqueChangedFilesAndIgnoresBuildOutputs()
            throws IOException
    {
        DevelopmentBaseline baseline = inspector.capture(task);
        Files.writeString(worktree.canonicalWorktree().resolve("README.md"), "staged\n");
        run(worktree.canonicalWorktree(), "add", "README.md");
        Files.writeString(worktree.canonicalWorktree().resolve("README.md"), "unstaged\n");
        Files.writeString(worktree.canonicalWorktree().resolve("new\nfile.txt"), "untracked\n");
        Files.createDirectory(worktree.canonicalWorktree().resolve("build"));
        Files.writeString(worktree.canonicalWorktree().resolve("build/output"), "ignored\n");

        DevelopmentLocalFacts facts = inspector.inspectLocal(worktree, baseline.route());

        assertThat(facts.stagedFiles()).isEqualTo(1);
        assertThat(facts.unstagedFiles()).isEqualTo(1);
        assertThat(facts.untrackedFiles()).isEqualTo(1);
        assertThat(facts.uncommittedFileCount()).isEqualTo(2);
        assertThat(facts.fingerprint()).isNotEqualTo(baseline.local().fingerprint());
        run(worktree.canonicalWorktree(), "reset", "--hard", baseSha);
        Files.delete(worktree.canonicalWorktree().resolve("new\nfile.txt"));
        assertThat(worktreeService.inspect(worktree).dirty()).isTrue();
        assertThat(inspector.inspectLocal(worktree, baseline.route()).fingerprint()).isEqualTo(baseline.local().fingerprint());
    }

    @Test
    void fingerprintsSameCountTrackedAndUntrackedEdits()
            throws IOException
    {
        DevelopmentPublicationRoute route = inspector.capture(task).route();
        Path tracked = worktree.canonicalWorktree().resolve("README.md");
        Path untracked = worktree.canonicalWorktree().resolve("new.txt");
        Files.writeString(tracked, "first\n");
        Files.writeString(untracked, "first\n");
        DevelopmentLocalFacts first = inspector.inspectLocal(worktree, route);
        Files.writeString(tracked, "other\n");
        DevelopmentLocalFacts trackedChanged = inspector.inspectLocal(worktree, route);
        Files.writeString(untracked, "other\n");
        DevelopmentLocalFacts untrackedChanged = inspector.inspectLocal(worktree, route);

        assertThat(first.uncommittedFileCount()).isEqualTo(2);
        assertThat(trackedChanged.uncommittedFileCount()).isEqualTo(2);
        assertThat(untrackedChanged.uncommittedFileCount()).isEqualTo(2);
        assertThat(first.fingerprint()).isNotEqualTo(trackedChanged.fingerprint());
        assertThat(trackedChanged.fingerprint()).isNotEqualTo(untrackedChanged.fingerprint());
    }

    @Test
    void fingerprintsUntrackedSymlinkWithoutReadingItsTarget()
            throws IOException
    {
        DevelopmentPublicationRoute route = inspector.capture(task).route();
        Path outside = temporaryDirectory.resolve("outside.txt");
        Files.writeString(outside, "first");
        Files.createSymbolicLink(worktree.canonicalWorktree().resolve("link"), outside);
        String first = inspector.inspectLocal(worktree, route).fingerprint();
        Files.writeString(outside, "changed external data");

        assertThat(inspector.inspectLocal(worktree, route).fingerprint()).isEqualTo(first);
    }

    @Test
    void countsARenameWithAnEmbeddedNewlineOnce()
    {
        DevelopmentPublicationRoute route = inspector.capture(task).route();
        run(worktree.canonicalWorktree(), "mv", "README.md", "renamed\nfile.md");

        DevelopmentLocalFacts facts = inspector.inspectLocal(worktree, route);

        assertThat(facts.stagedFiles()).isEqualTo(1);
        assertThat(facts.uncommittedFileCount()).isEqualTo(1);
    }

    @Test
    void comparesCommittedContentAgainstFrozenBaseAndVerifiesAncestry()
            throws IOException
    {
        DevelopmentPublicationRoute route = inspector.capture(task).route();
        run(worktree.canonicalWorktree(), "commit", "--allow-empty", "-m", "Empty commit");
        assertThat(inspector.inspectLocal(worktree, route).commitCount()).isEqualTo(1);
        assertThat(inspector.inspectLocal(worktree, route).diffFromBase()).isFalse();
        String head = commit("completed change");
        run(repository, "update-ref", "refs/remotes/origin/main", head);

        DevelopmentLocalFacts facts = inspector.inspectLocal(worktree, route);

        assertThat(facts.commitCount()).isEqualTo(2);
        assertThat(facts.diffFromBase()).isTrue();
        assertThat(inspector.isAncestor(worktree, baseSha, head)).isTrue();
        assertThat(inspector.isAncestor(worktree, head, baseSha)).isFalse();
    }

    @Test
    void upstreamAdvancementDoesNotBecomeADevelopmentDiff()
            throws IOException
    {
        Files.writeString(repository.resolve("README.md"), "upstream change\n");
        run(repository, "add", "README.md");
        run(repository, "commit", "-m", "Advance upstream");
        String upstream = run(repository, "rev-parse", "HEAD");
        run(repository, "update-ref", "refs/remotes/origin/main", upstream);

        DevelopmentBaseline baseline = inspector.capture(task);

        assertThat(baseline.route().baseSha()).isEqualTo(upstream);
        assertThat(baseline.local().commitCount()).isZero();
        assertThat(baseline.local().diffFromBase()).isFalse();
        commit("upstream change");
        DevelopmentLocalFacts changed = inspector.inspectLocal(worktree, baseline.route());
        assertThat(changed.commitCount()).isEqualTo(1);
        assertThat(changed.diffFromBase()).isTrue();
    }

    @Test
    void freezesForkRoutingAndRetainsWrongBasePullRequestsForTheCoordinator()
    {
        when(workspaces.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace(true, "main")));
        run(repository, "remote", "add", "upstream", UPSTREAM);
        run(repository, "update-ref", "refs/remotes/upstream/main", baseSha);
        DevelopmentPublicationRoute route = inspector.capture(task).route();
        GitHubPullRequestIdentity wrongBase = new GitHubPullRequestIdentity(
                PullRequestRef.of("acme", "project", 12), "https://github.com/acme/project/pull/12", REPOSITORY,
                worktree.generatedBranch(), baseSha, BASE_REPOSITORY, "release", baseSha, GitHubPullRequestIdentity.State.OPEN, true, "alice");
        when(pullRequests.fetchPullRequestIdentities("test-token", BASE_REPOSITORY, REPOSITORY, worktree.generatedBranch()))
                .thenReturn(ImmutableList.of(wrongBase));

        DevelopmentPublicationFacts facts = inspector.inspectPublication(worktree, route);

        assertThat(route.sourceRemote()).isEqualTo("upstream");
        assertThat(route.baseRepository()).isEqualTo(BASE_REPOSITORY);
        assertThat(route.headRepository()).isEqualTo(REPOSITORY);
        assertThat(facts.remoteHeadSha()).isEmpty();
        assertThat(facts.matchingPullRequests()).containsExactly(wrongBase);
    }

    @Test
    void distinguishesRemoteAbsenceFromUnknownOrAmbiguousReads()
    {
        DevelopmentPublicationRoute route = inspector.capture(task).route();
        assertThat(inspector.inspectPublication(worktree, route).remoteHeadSha()).isEmpty();
        git.remoteResult = Optional.of(new Result(128, "authentication failed", false));
        assertThatThrownBy(() -> inspector.inspectPublication(worktree, route)).hasMessageContaining("Cannot verify");
        git.remoteResult = Optional.of(new Result(0, baseSha + "\trefs/heads/another\n", false));
        assertThatThrownBy(() -> inspector.inspectPublication(worktree, route)).hasMessageContaining("ambiguous");
        git.remoteResult = Optional.of(new Result(2, "", true));
        assertThatThrownBy(() -> inspector.inspectPublication(worktree, route)).hasMessageContaining("truncated");
    }

    @Test
    void rejectsChangedWorkspaceBranchAndRemoteRouting()
    {
        DevelopmentPublicationRoute route = inspector.capture(task).route();
        when(workspaces.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace(false, "other-base")));
        assertThatThrownBy(() -> inspector.inspectLocal(worktree, route)).hasMessageContaining("routing changed");
        when(workspaces.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace(false, "main")));
        run(repository, "config", "url.https://github.com/other/.pushInsteadOf", "https://github.com/alice/");
        assertThatThrownBy(() -> inspector.inspectLocal(worktree, route)).hasMessageContaining("remote routing changed");
        run(repository, "config", "--remove-section", "url.https://github.com/other/");
        run(worktree.canonicalWorktree(), "checkout", "-b", "unexpected-branch");
        assertThatThrownBy(() -> inspector.inspectLocal(worktree, route)).isInstanceOf(InvalidAgentRequestException.class).hasMessageContaining("another branch");
    }

    @Test
    void reportsAnOpenGitOperation()
            throws IOException
    {
        DevelopmentPublicationRoute route = inspector.capture(task).route();
        Path marker = git.gitPaths(worktree.canonicalWorktree(), ImmutableList.of("MERGE_HEAD")).getFirst();
        Files.writeString(marker, baseSha);

        assertThat(inspector.inspectLocal(worktree, route).gitOperationInProgress()).isTrue();
    }

    @Test
    void exactLeaseGuardsAbsentAndExistingRemoteAndPushesTheFrozenObject()
            throws IOException
    {
        DevelopmentPublicationRoute route = inspector.capture(task).route();
        String first = commit("first");
        String later = commit("later");
        String ref = "refs/heads/" + worktree.generatedBranch();

        run(worktree.canonicalWorktree(), "push", "--force-with-lease=" + ref + ":", "--", bareRemote.toString(), first + ":" + ref);
        assertThat(inspector.inspectPublication(worktree, route).remoteHeadSha()).contains(first);
        Result absentAgain = git.run(worktree.canonicalWorktree(), "push", "--force-with-lease=" + ref + ":", "--", bareRemote.toString(), later + ":" + ref);
        assertThat(absentAgain.successful()).isFalse();
        run(worktree.canonicalWorktree(), "push", "--force-with-lease=" + ref + ":" + first, "--", bareRemote.toString(), later + ":" + ref);
        Result stale = git.run(worktree.canonicalWorktree(), "push", "--force-with-lease=" + ref + ":" + first, "--", bareRemote.toString(), first + ":" + ref);
        assertThat(stale.successful()).isFalse();
        assertThat(inspector.inspectPublication(worktree, route).remoteHeadSha()).contains(later);
    }

    private String commit(String contents)
            throws IOException
    {
        Files.writeString(worktree.canonicalWorktree().resolve("README.md"), contents + "\n");
        run(worktree.canonicalWorktree(), "add", "README.md");
        run(worktree.canonicalWorktree(), "commit", "-m", "Change content");
        return run(worktree.canonicalWorktree(), "rev-parse", "HEAD");
    }

    private Workspace workspace(boolean fork, String baseBranch)
    {
        return new Workspace(WORKSPACE_ID, "Development test", 1, REPOSITORY,
                fork ? Optional.of(2L) : Optional.empty(), fork ? Optional.of(BASE_REPOSITORY) : Optional.empty(),
                fork ? 2 : 1, fork ? BASE_REPOSITORY : REPOSITORY, fork ? Workspace.Topology.FORK : Workspace.Topology.DIRECT,
                baseBranch, ORIGIN, fork ? Optional.of(baseBranch) : Optional.empty(), fork ? Optional.of(UPSTREAM) : Optional.empty(),
                repository.toString(), Optional.of(NOW), Optional.of(NOW), 0, NOW, NOW);
    }

    private String run(Path directory, String... arguments)
    {
        Result result = git.run(directory, arguments);
        assertThat(result.successful()).describedAs("git %s: %s", String.join(" ", arguments), result.output()).isTrue();
        return result.output().strip();
    }

    private final class TestGitRunner
            extends GitCommandRunner
    {
        private Optional<Result> remoteResult = Optional.empty();
        private int remoteReads;

        @Override
        public Result run(Path directory, Duration timeout, Map<String, String> environment, List<String> arguments)
        {
            if (!arguments.isEmpty() && arguments.getFirst().equals("ls-remote")) {
                remoteReads++;
                if (remoteResult.isPresent()) {
                    return remoteResult.orElseThrow();
                }
                return super.run(directory, timeout, environment, arguments.stream()
                        .map(value -> value.equals(ORIGIN) ? bareRemote.toString() : value)
                        .collect(ImmutableList.toImmutableList()));
            }
            return super.run(directory, timeout, environment, arguments);
        }
    }
}
