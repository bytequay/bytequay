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
import com.bytequay.agent.core.TaskBranches;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.impl.FileAgentResourceService;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.app.git.command.GitCommandRunner;
import com.bytequay.app.git.command.GitCommandRunner.Result;
import com.bytequay.app.repository.github.GitHubPullRequestIdentity;
import com.bytequay.app.repository.github.GitHubPullRequestReadClient;
import com.bytequay.app.review.IdeRuntimeService;
import com.bytequay.app.service.credentials.CredentialService;
import com.bytequay.app.service.pr.PullRequestService;
import com.bytequay.app.workspace.ManagedWorktreeService;
import com.bytequay.app.workspace.WorkspacePaths;
import com.bytequay.core.domain.PullRequestDetail;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.PullRequestTask;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.PullRequestTaskStore;
import com.bytequay.core.store.WorkspaceStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.MockMakers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static com.bytequay.app.git.GitTestSupport.configureUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.withSettings;

@Execution(ExecutionMode.CONCURRENT)
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
    private final PullRequestTaskStore assignments = mock(PullRequestTaskStore.class);
    private final PullRequestService cachedPullRequests = mock(PullRequestService.class);
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
        configureUser(repository, "ByteQuay Test", "test@example.invalid");
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
        worktreeService = new ManagedWorktreeService(paths, workspaces, git, mock(IdeRuntimeService.class));
        TaskId taskId = TaskId.random();
        worktree = worktreeService.provision(new ProvisionWorktreeCommand(
                taskId, WORKSPACE_ID, RepositoryIdentity.valueOf(REPOSITORY.fullName()), baseSha, TaskBranches.generate(taskId, "user", "Development test")));
        task = AgentTask.created(worktree.taskId(), true, "Development test", TitleSource.USER, AgentLaneId.CODEX_CLI,
                worktree, AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), NOW);
        inspector = new DevelopmentGitInspector(worktreeService, workspaces, git, credentials, pullRequests, assignments, cachedPullRequests);
    }

    @Test
    void capturesLocalFactsWithoutReadingGitHubOrRemote()
    {
        int before = git.branchReads;
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
        assertThat(git.branchReads - before).isEqualTo(2);
    }

    @Test
    void nameOnlyChangeKeepsCodeFingerprintAndLaterGitAndCleanupUseTheNewBranch()
            throws IOException
    {
        DevelopmentBaseline initial = inspector.capture(task);
        String branch = "user/bytequay/rename-without-changing-code";
        AtomicReference<AgentTask> saved = new AtomicReference<>(task);
        DevelopmentBranchRenameJournal journal = journal();

        TaskWorktree renamed = inspector.renameBranch(initial, branch, intent -> journal.write(task.id(), intent),
                updated -> saved.set(saved.get().withBranch(updated.generatedBranch(), NOW)));
        journal.clear(task.id());
        DevelopmentBaseline nextTurn = inspector.capture(saved.get());

        assertThat(nextTurn.local().fingerprint()).isEqualTo(initial.local().fingerprint());
        assertThat(nextTurn.local().headSha()).isEqualTo(initial.local().headSha());
        assertThat(nextTurn.local().branch()).isEqualTo(branch);
        assertThat(worktreeService.currentBranch(renamed)).contains(branch);
        String head = commit("Work after rename");
        inspector.pushDevelopmentHead(renamed, nextTurn.route(), head, Optional.empty());
        assertThat(run(bareRemote, "rev-parse", "refs/heads/" + branch)).isEqualTo(head);

        worktreeService.cleanup(renamed);
        assertThat(renamed.canonicalWorktree()).doesNotExist();
        assertThat(run(repository, "rev-parse", "refs/heads/" + branch)).isEqualTo(head);
        worktreeService.delete(renamed);
        assertThat(git.run(repository, "show-ref", "--verify", "--quiet", "refs/heads/" + branch).exitCode()).isEqualTo(1);
        assertThat(git.run(repository, "show-ref", "--verify", "--quiet", "refs/heads/" + worktree.generatedBranch()).exitCode()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"before-git", "after-git", "after-save", "write-failure", "save-then-throw"})
    void recoversOnlyTheRecordedRenameAcrossCrashWindows(String phase)
    {
        DevelopmentBaseline baseline = inspector.capture(task);
        String branch = "user/bytequay/recover-the-recorded-name";
        DevelopmentBranchRename intent = new DevelopmentBranchRename(worktree.generatedBranch(), branch, baseSha);
        DevelopmentBranchRenameJournal journal = journal();
        AtomicReference<AgentTask> saved = new AtomicReference<>(task);
        if (phase.equals("before-git")) {
            journal.write(task.id(), intent);
        }
        else {
            assertThatThrownBy(() -> inspector.renameBranch(baseline, branch, rename -> journal.write(task.id(), rename), renamed -> {
                if (phase.equals("write-failure")) {
                    throw new IllegalStateException("Database write failed");
                }
                if (phase.equals("after-save") || phase.equals("save-then-throw")) {
                    saved.set(saved.get().withBranch(renamed.generatedBranch(), NOW));
                }
                if (phase.equals("save-then-throw")) {
                    throw new IllegalStateException("Database commit result is uncertain");
                }
                // An abrupt process exit never reaches the remaining persistence or cleanup.
                throw new AssertionError("Simulated process exit");
            })).isInstanceOf(phase.equals("write-failure") || phase.equals("save-then-throw") ? IllegalStateException.class : AssertionError.class);
        }

        DevelopmentBranchRenameJournal reopened = journal();
        TaskWorktree recovered = inspector.recoverRename(saved.get().taskWorktree().orElseThrow(), reopened.read(task.id()).orElseThrow(),
                renamed -> saved.set(saved.get().withBranch(renamed.generatedBranch(), NOW)));
        reopened.clear(task.id());

        String expected = phase.equals("before-git") ? worktree.generatedBranch() : branch;
        assertThat(saved.get().taskWorktree().orElseThrow().generatedBranch()).isEqualTo(expected);
        assertThat(worktreeService.currentBranch(recovered)).contains(expected);
        assertThat(inspector.capture(saved.get()).local().headSha()).isEqualTo(baseSha);
        assertThat(reopened.read(task.id())).isEmpty();
        AgentTask abandoned = saved.get().fail("Abandoned after recovery", NOW, NOW.plusSeconds(60));
        worktreeService.cleanup(abandoned.taskWorktree().orElseThrow());
        assertThat(recovered.canonicalWorktree()).doesNotExist();
        assertThat(run(repository, "rev-parse", "refs/heads/" + expected)).isEqualTo(baseSha);
    }

    @ParameterizedTest
    @ValueSource(strings = {"unrelated", "head", "old-ref", "target-ref", "detached"})
    void recoveryRefusesUnrelatedGitChanges(String change)
    {
        String branch = "user/bytequay/recover-the-recorded-name";
        DevelopmentBranchRename intent = new DevelopmentBranchRename(worktree.generatedBranch(), branch, baseSha);
        DevelopmentBranchRenameJournal journal = journal();
        journal.write(task.id(), intent);
        switch (change) {
            case "unrelated" -> run(worktree.canonicalWorktree(), "checkout", "-b", "user/bytequay/unrelated");
            case "head" -> run(worktree.canonicalWorktree(), "commit", "--allow-empty", "-m", "Unrelated work");
            case "old-ref" -> {
                run(worktree.canonicalWorktree(), "branch", "-m", branch);
                run(worktree.canonicalWorktree(), "branch", worktree.generatedBranch(), baseSha);
            }
            case "target-ref" -> run(worktree.canonicalWorktree(), "branch", branch, baseSha);
            case "detached" -> run(worktree.canonicalWorktree(), "checkout", "--detach", baseSha);
            default -> throw new AssertionError(change);
        }
        String before = run(worktree.canonicalWorktree(), "rev-parse", "HEAD");

        assertThatThrownBy(() -> inspector.recoverRename(worktree, journal.read(task.id()).orElseThrow(), ignored -> {
            throw new AssertionError("Must not adopt an unrelated branch");
        })).isInstanceOf(RuntimeException.class);

        assertThat(run(worktree.canonicalWorktree(), "rev-parse", "HEAD")).isEqualTo(before);
        assertThat(journal.read(task.id())).contains(intent);
    }

    private DevelopmentBranchRenameJournal journal()
    {
        FileAgentResourceService resources = new FileAgentResourceService(temporaryDirectory.resolve("runtime"));
        resources.allocate(task.id());
        return new DevelopmentBranchRenameJournal(resources, new ObjectMapper());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void assignedPullRequestKeepsItsRemoteHeadAndBaseWhileUsingTheGeneratedLocalBranch(boolean fork)
            throws IOException
    {
        GitHubPullRequestIdentity assigned = assignPullRequest(fork);
        run(repository, "push", bareRemote.toString(), baseSha + ":refs/heads/" + assigned.headBranch());

        DevelopmentBaseline baseline = inspector.capture(task);

        assertThat(baseline.route().assignedPullRequest()).contains(assigned.pullRequest());
        assertThat(baseline.route().headBranch()).isEqualTo("existing-pr");
        assertThat(baseline.route().baseBranch()).isEqualTo("release");
        assertThat(baseline.local().branch()).isEqualTo(worktree.generatedBranch());
        verifyNoInteractions(credentials, pullRequests);
        assertThat(git.remoteReads).isZero();
        when(pullRequests.fetchPullRequestIdentity("test-token", assigned.pullRequest())).thenReturn(assigned);
        DevelopmentPublicationFacts facts = inspector.inspectPublication(worktree, baseline.route());
        assertThat(facts.matchingPullRequests()).containsExactly(assigned);
        assertThat(facts.remoteHeadSha()).contains(baseSha);
        String head = commit("update assigned PR");

        inspector.pushDevelopmentHead(worktree, baseline.route(), head, facts.remoteHeadSha());

        assertThat(run(bareRemote, "rev-parse", "refs/heads/" + assigned.headBranch())).isEqualTo(head);
        assertThat(git.run(bareRemote, "show-ref", "--verify", "--quiet", "refs/heads/" + worktree.generatedBranch()).exitCode()).isEqualTo(1);
        assertThat(worktreeService.currentBranch(worktree)).contains(worktree.generatedBranch());
        verify(pullRequests, never()).fetchPullRequestIdentities(anyString(), any(), any(), anyString());
    }

    @ParameterizedTest
    @ValueSource(strings = {"closed", "head", "base", "fork", "number"})
    void assignedPullRequestPublicationRefusesChangedTargets(String change)
    {
        GitHubPullRequestIdentity assigned = assignPullRequest(false);
        DevelopmentPublicationRoute route = inspector.capture(task).route();
        GitHubPullRequestIdentity changed = new GitHubPullRequestIdentity(
                change.equals("number") ? PullRequestRef.of("alice", "project", 13) : assigned.pullRequest(), assigned.url(),
                change.equals("fork") ? BASE_REPOSITORY : assigned.headRepository(), change.equals("head") ? "other-head" : assigned.headBranch(), assigned.headSha(),
                assigned.baseRepository(), change.equals("base") ? "other-base" : assigned.baseBranch(), assigned.baseSha(),
                change.equals("closed") ? GitHubPullRequestIdentity.State.CLOSED : assigned.state(), assigned.draft(), assigned.author());
        when(pullRequests.fetchPullRequestIdentity("test-token", assigned.pullRequest())).thenReturn(changed);

        assertThatThrownBy(() -> inspector.inspectPublication(worktree, route)).hasMessageContaining("Assigned pull request destination changed");

        assertThat(git.remoteReads).isZero();
        verify(pullRequests, never()).fetchPullRequestIdentities(anyString(), any(), any(), anyString());
    }

    @Test
    void assignedPullRequestCannotBeReplacedWithGeneratedBranchPublication()
    {
        assignPullRequest(false);
        DevelopmentPublicationRoute route = inspector.capture(task).route();
        when(assignments.findByTaskId(task.id().toString())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> inspector.inspectPublication(worktree, route)).hasMessageContaining("routing changed");

        verifyNoInteractions(credentials, pullRequests);
    }

    @ParameterizedTest
    @ValueSource(strings = {"details", "head", "base"})
    void assignedPullRequestCaptureRequiresItsCachedDestination(String missing)
    {
        GitHubPullRequestIdentity assigned = assignPullRequest(false);
        PullRequestDetail cached = cachedPullRequests.findPullRequestDetail(assigned.pullRequest().repoFullName(), assigned.pullRequest().number()).orElseThrow();
        switch (missing) {
            case "details" -> when(cachedPullRequests.findPullRequestDetail(assigned.pullRequest().repoFullName(), assigned.pullRequest().number()))
                    .thenReturn(Optional.empty());
            case "head" -> when(cached.headRef()).thenReturn(null);
            case "base" -> when(cached.baseRef()).thenReturn(null);
            default -> throw new AssertionError(missing);
        }

        String expected = missing.equals("details") ? "details are unavailable" : missing + " is unavailable";
        assertThatThrownBy(() -> inspector.capture(task)).hasMessageContaining("Assigned pull request " + expected);

        verifyNoInteractions(credentials, pullRequests);
        assertThat(git.remoteReads).isZero();
    }

    @Test
    void renamesUnpublishedWorkWithoutChangingFilesAndKeepsDeliveryAndCleanupWorking()
            throws IOException
    {
        DevelopmentBaseline baseline = inspector.capture(task);
        commit("Convert parquet time millis");
        String head = run(worktree.canonicalWorktree(), "rev-parse", "HEAD");
        Files.writeString(worktree.canonicalWorktree().resolve("README.md"), "uncommitted work\n");
        String branch = "user/bytequay/convert-parquet-time-millis-to-micro";
        List<TaskWorktree> recorded = new ArrayList<>();

        TaskWorktree renamed = inspector.renameBranch(baseline, branch, ignored -> {}, recorded::add);

        assertThat(recorded).containsExactly(renamed);
        assertThat(renamed.generatedBranch()).isEqualTo(branch);
        assertThat(worktreeService.inspect(renamed).head()).isEqualTo(head);
        assertThat(worktreeService.inspect(renamed).uncommitted()).isTrue();
        assertThat(Files.readString(renamed.canonicalWorktree().resolve("README.md"))).isEqualTo("uncommitted work\n");
        DevelopmentBaseline updated = inspector.capture(task.withBranch(branch, NOW));
        assertThat(updated.route().headBranch()).isEqualTo(branch);
        assertThat(updated.worktree()).isEqualTo(renamed);
        inspector.pushDevelopmentHead(renamed, updated.route(), head, Optional.empty());
        assertThat(run(bareRemote, "rev-parse", "refs/heads/" + branch)).isEqualTo(head);
        assertThat(git.run(repository, "show-ref", "--verify", "--quiet", "refs/heads/" + worktree.generatedBranch()).exitCode()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"remote", "pull-request", "tracking"})
    void keepsPreviouslyPublishedBranches(String kind)
    {
        DevelopmentBaseline baseline = inspector.capture(task);
        if (kind.equals("remote")) {
            inspector.pushDevelopmentHead(worktree, baseline.route(), baseSha, Optional.empty());
        }
        if (kind.equals("pull-request")) {
            when(pullRequests.fetchPullRequestIdentities("test-token", REPOSITORY, REPOSITORY, worktree.generatedBranch()))
                    .thenReturn(ImmutableList.of(new GitHubPullRequestIdentity(PullRequestRef.of("alice", "project", 12),
                            "https://github.com/alice/project/pull/12", REPOSITORY, worktree.generatedBranch(), baseSha,
                            REPOSITORY, "main", baseSha, GitHubPullRequestIdentity.State.CLOSED, true, "alice")));
        }
        if (kind.equals("tracking")) {
            run(repository, "config", "branch." + worktree.generatedBranch() + ".remote", "origin");
            run(repository, "config", "branch." + worktree.generatedBranch() + ".merge", "refs/heads/" + worktree.generatedBranch());
        }
        List<TaskWorktree> recorded = new ArrayList<>();
        assertThat(inspector.renameBranch(baseline, "user/bytequay/convert-parquet-time-millis-to-micro", ignored -> {}, recorded::add)).isEqualTo(worktree);
        assertThat(recorded).isEmpty();
        assertThat(worktreeService.currentBranch(worktree)).contains(worktree.generatedBranch());
    }

    @ParameterizedTest
    @ValueSource(strings = {"local", "remote", "pull-request", "unverifiable", "namespace"})
    void renameFailuresPreserveTheOriginalBranch(String kind)
    {
        DevelopmentBaseline baseline = inspector.capture(task);
        String branch = (kind.equals("namespace") ? "other" : "user") + "/bytequay/convert-parquet-time-millis-to-micro";
        if (kind.equals("local")) {
            run(repository, "branch", branch, baseSha);
        }
        if (kind.equals("remote")) {
            run(repository, "push", bareRemote.toString(), baseSha + ":refs/heads/" + branch);
        }
        if (kind.equals("pull-request")) {
            when(pullRequests.fetchPullRequestIdentities("test-token", REPOSITORY, REPOSITORY, branch))
                    .thenReturn(ImmutableList.of(new GitHubPullRequestIdentity(PullRequestRef.of("alice", "project", 12),
                            "https://github.com/alice/project/pull/12", REPOSITORY, branch, baseSha,
                            REPOSITORY, "main", baseSha, GitHubPullRequestIdentity.State.CLOSED, true, "alice")));
        }
        if (kind.equals("unverifiable")) {
            git.remoteResult = Optional.of(new Result(128, "offline", false));
        }
        assertThatThrownBy(() -> inspector.renameBranch(baseline, branch, ignored -> {}, renamed -> {
            throw new AssertionError("Must not record an unsafe rename");
        })).isInstanceOf(RuntimeException.class);
        assertThat(worktreeService.inspect(worktree).head()).isEqualTo(baseSha);
        assertThat(worktreeService.currentBranch(worktree)).contains(worktree.generatedBranch());
        if (kind.equals("local")) {
            assertThat(run(repository, "rev-parse", branch)).isEqualTo(baseSha);
        }
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

    @Test
    void publishesRewrittenCommitMessagesAndRejectsAStaleLease()
            throws IOException
    {
        DevelopmentPublicationRoute route = inspector.capture(task).route();
        String original = commit("development change");
        String ref = "refs/heads/" + worktree.generatedBranch();
        run(worktree.canonicalWorktree(), "push", "--", bareRemote.toString(), original + ":" + ref);
        run(worktree.canonicalWorktree(), "commit", "--amend", "-m", "Rewrite commit message");
        String rewritten = run(worktree.canonicalWorktree(), "rev-parse", "HEAD");

        assertThat(run(worktree.canonicalWorktree(), "rev-parse", rewritten + "^{tree}"))
                .isEqualTo(run(worktree.canonicalWorktree(), "rev-parse", original + "^{tree}"));
        assertThat(inspector.isAncestor(worktree, original, rewritten)).isFalse();

        inspector.pushDevelopmentHead(worktree, route, rewritten, Optional.of(original));

        assertThat(inspector.inspectPublication(worktree, route).remoteHeadSha()).contains(rewritten);
        run(worktree.canonicalWorktree(), "commit", "--amend", "-m", "Revise commit message again");
        String nextRewrite = run(worktree.canonicalWorktree(), "rev-parse", "HEAD");
        Path other = ciRepository();
        run(other, "reset", "--hard", rewritten);
        String advanced = publishCi(other, "later remote change");

        assertThatThrownBy(() -> inspector.pushDevelopmentHead(worktree, route, nextRewrite, Optional.of(rewritten)))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("Could not push the development branch");
        assertThat(inspector.inspectPublication(worktree, route).remoteHeadSha()).contains(advanced);
        assertThat(run(worktree.canonicalWorktree(), "rev-parse", "HEAD")).isEqualTo(nextRewrite);
    }

    @Test
    void synchronizesToARewrittenCiCommitFromAnotherCheckoutAndPreservesIgnoredBuildOutput()
            throws IOException
    {
        DevelopmentBaseline baseline = inspector.capture(task);
        String source = commit("source development");
        String repaired = commitIn(ciCheckout(), "rewritten CI repair");
        Files.createDirectory(worktree.canonicalWorktree().resolve("build"));
        Files.writeString(worktree.canonicalWorktree().resolve("build/output"), "local build output");
        Path hook = git.gitPaths(worktree.canonicalWorktree(), ImmutableList.of("hooks/post-checkout")).getFirst();
        Files.createDirectories(hook.getParent());
        Path hookOutput = temporaryDirectory.resolve("unexpected-hook");
        Files.writeString(hook, "#!/bin/sh\ntouch '" + hookOutput + "'\n");
        assertThat(hook.toFile().setExecutable(true)).isTrue();

        DevelopmentLocalFacts synchronizedFacts = inspector.synchronizeSource(baseline, source, repaired);

        assertThat(synchronizedFacts.headSha()).isEqualTo(repaired);
        assertThat(synchronizedFacts.uncommittedFileCount()).isZero();
        assertThat(inspector.isAncestor(worktree, source, repaired)).isFalse();
        assertThat(run(worktree.canonicalWorktree(), "symbolic-ref", "--short", "HEAD")).isEqualTo(worktree.generatedBranch());
        assertThat(Files.readString(worktree.canonicalWorktree().resolve("README.md"))).isEqualTo("rewritten CI repair\n");
        assertThat(Files.readString(worktree.canonicalWorktree().resolve("build/output"))).isEqualTo("local build output");
        assertThat(hookOutput).doesNotExist();
        assertThat(git.fetches).isEmpty();
        assertThat(git.transitions).containsExactly(ImmutableList.of("-c", "core.hooksPath=/dev/null", "checkout",
                "--no-overwrite-ignore", "--no-recurse-submodules", "-B", worktree.generatedBranch(), repaired));
        verifyNoInteractions(credentials, pullRequests);
    }

    @Test
    void ignoredCollisionRefusesSynchronizationWithoutChangingHeadOrFiles()
            throws IOException
    {
        DevelopmentBaseline baseline = inspector.capture(task);
        String source = commit("source development");
        Path ci = ciCheckout();
        Files.createDirectory(ci.resolve("build"));
        Files.writeString(ci.resolve("build/output"), "repaired output");
        run(ci, "add", "--force", "build/output");
        String repaired = commitIn(ci, "CI change");
        Files.createDirectory(worktree.canonicalWorktree().resolve("build"));
        Files.writeString(worktree.canonicalWorktree().resolve("build/output"), "local output");

        assertThatThrownBy(() -> inspector.synchronizeSource(baseline, source, repaired)).hasMessageContaining("worktree update");

        assertThat(run(worktree.canonicalWorktree(), "rev-parse", "HEAD")).isEqualTo(source);
        assertThat(Files.readString(worktree.canonicalWorktree().resolve("README.md"))).isEqualTo("source development\n");
        assertThat(Files.readString(worktree.canonicalWorktree().resolve("build/output"))).isEqualTo("local output");
        assertThat(git.transitions).hasSize(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"staged", "unstaged", "untracked", "moved", "branch", "operation"})
    void changedSourceRefusesSynchronization(String change)
            throws IOException
    {
        DevelopmentBaseline baseline = inspector.capture(task);
        String source = commit("source development");
        String repaired = commitIn(ciCheckout(), "CI change");
        switch (change) {
            case "staged" -> {
                Files.writeString(worktree.canonicalWorktree().resolve("README.md"), "local edit");
                run(worktree.canonicalWorktree(), "add", "README.md");
            }
            case "unstaged" -> Files.writeString(worktree.canonicalWorktree().resolve("README.md"), "local edit");
            case "untracked" -> Files.writeString(worktree.canonicalWorktree().resolve("new.txt"), "local edit");
            case "moved" -> run(worktree.canonicalWorktree(), "commit", "--allow-empty", "-m", "Later user work");
            case "branch" -> run(worktree.canonicalWorktree(), "checkout", "-b", "another-branch");
            case "operation" -> Files.writeString(git.gitPaths(worktree.canonicalWorktree(), ImmutableList.of("MERGE_HEAD")).getFirst(), baseSha);
            default -> throw new AssertionError(change);
        }
        String before = run(worktree.canonicalWorktree(), "rev-parse", "HEAD");
        String status = run(worktree.canonicalWorktree(), "status", "--porcelain=v1", "--untracked-files=all");

        String expectedFailure = switch (change) {
            case "staged", "unstaged", "untracked" -> "Source has staged, unstaged, or untracked changes";
            case "moved" -> "Source HEAD changed";
            case "branch" -> "another branch";
            case "operation" -> "Source has an open Git operation";
            default -> throw new AssertionError(change);
        };
        assertThatThrownBy(() -> inspector.synchronizeSource(baseline, source, repaired)).hasMessageContaining(expectedFailure);

        assertThat(git.transitions).isEmpty();
        assertThat(run(worktree.canonicalWorktree(), "rev-parse", "HEAD")).isEqualTo(before);
        assertThat(run(worktree.canonicalWorktree(), "status", "--porcelain=v1", "--untracked-files=all")).isEqualTo(status);
    }

    @Test
    void revisionMissingFromTheRepositoryIsRefusedWithoutFetching()
            throws IOException
    {
        DevelopmentBaseline baseline = inspector.capture(task);
        String source = commit("source development");
        String remoteOnly = publishCi(ciRepository(), "CI change");

        assertThatThrownBy(() -> inspector.synchronizeSource(baseline, source, remoteOnly)).hasMessageContaining("cat-file");

        assertThat(git.fetches).isEmpty();
        assertThat(git.transitions).isEmpty();
        assertThat(run(worktree.canonicalWorktree(), "rev-parse", "HEAD")).isEqualTo(source);
    }

    @Test
    void verifiesTheSourceAfterTheNativeTransitionWithoutRepairingAChangedResult()
            throws IOException
    {
        DevelopmentBaseline baseline = inspector.capture(task);
        String source = commit("source development");
        String repaired = commitIn(ciCheckout(), "CI change");
        git.afterTransition = () -> run(worktree.canonicalWorktree(), "commit", "--allow-empty", "-m", "After synchronization");

        assertThatThrownBy(() -> inspector.synchronizeSource(baseline, source, repaired)).hasMessageContaining("Source HEAD changed");

        assertThat(git.transitions).hasSize(1);
        assertThat(run(worktree.canonicalWorktree(), "log", "-1", "--format=%s")).isEqualTo("After synchronization");
    }

    /** A linked checkout of the task's repository, as a CI repair task has. */
    private Path ciCheckout()
    {
        Path ci = temporaryDirectory.resolve("ci-checkout");
        run(repository, "worktree", "add", "--quiet", "-b", "ci-repair", ci.toString(), baseSha);
        return ci;
    }

    private String commitIn(Path checkout, String contents)
            throws IOException
    {
        Files.writeString(checkout.resolve("README.md"), contents + "\n");
        run(checkout, "add", "README.md");
        run(checkout, "commit", "-m", "CI repair");
        return run(checkout, "rev-parse", "HEAD");
    }

    private Path ciRepository()
    {
        run(repository, "push", "--", bareRemote.toString(), baseSha + ":refs/heads/main");
        Path ci = temporaryDirectory.resolve("ci");
        run(repository, "clone", "--branch", "main", "--config", "user.name=CI Test", "--config", "user.email=ci@example.invalid", bareRemote.toString(), ci.toString());
        run(ci, "checkout", "-b", worktree.generatedBranch());
        return ci;
    }

    private GitHubPullRequestIdentity assignPullRequest(boolean fork)
    {
        RepoRef baseRepository = fork ? BASE_REPOSITORY : REPOSITORY;
        if (fork) {
            when(workspaces.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace(true, "main")));
            run(repository, "remote", "add", "upstream", UPSTREAM);
        }
        run(repository, "update-ref", "refs/remotes/" + (fork ? "upstream" : "origin") + "/release", baseSha);
        PullRequestRef ref = PullRequestRef.of(baseRepository.owner(), baseRepository.repo(), 12);
        when(assignments.findByTaskId(task.id().toString())).thenReturn(Optional.of(new PullRequestTask(
                task.id().toString(), WORKSPACE_ID, baseRepository.fullName(), 12, "Assigned pull request")));
        PullRequestDetail cached = mock(PullRequestDetail.class, withSettings().mockMaker(MockMakers.INLINE));
        when(cached.headRef()).thenReturn("existing-pr");
        when(cached.baseRef()).thenReturn("release");
        when(cachedPullRequests.findPullRequestDetail(baseRepository.fullName(), 12)).thenReturn(Optional.of(cached));
        return new GitHubPullRequestIdentity(ref, "https://github.com/" + baseRepository.fullName() + "/pull/12", REPOSITORY,
                "existing-pr", baseSha, baseRepository, "release", baseSha, GitHubPullRequestIdentity.State.OPEN, false, "alice");
    }

    private String publishCi(Path ci, String contents)
            throws IOException
    {
        Files.writeString(ci.resolve("README.md"), contents + "\n");
        run(ci, "add", "README.md");
        run(ci, "commit", "-m", "CI repair");
        String sha = run(ci, "rev-parse", "HEAD");
        run(ci, "push", "--", bareRemote.toString(), sha + ":refs/heads/" + worktree.generatedBranch());
        return sha;
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
        private int branchReads;
        private final List<List<String>> fetches = new ArrayList<>();
        private final List<List<String>> transitions = new ArrayList<>();
        private Optional<Result> fetchResult = Optional.empty();
        private Runnable afterFetch = () -> {};
        private Runnable afterTransition = () -> {};

        @Override
        public Result run(Path directory, Duration timeout, Map<String, String> environment, List<String> arguments)
        {
            if (arguments.equals(ImmutableList.of("symbolic-ref", "--quiet", "--short", "HEAD"))) {
                branchReads++;
            }
            if (!arguments.isEmpty() && arguments.getFirst().equals("ls-remote")) {
                remoteReads++;
                if (remoteResult.isPresent()) {
                    return remoteResult.orElseThrow();
                }
                return super.run(directory, timeout, environment, arguments.stream()
                        .map(this::localRemote)
                        .collect(ImmutableList.toImmutableList()));
            }
            if (!arguments.isEmpty() && arguments.getFirst().equals("fetch")) {
                fetches.add(ImmutableList.copyOf(arguments));
                Result result = fetchResult.orElseGet(() -> super.run(directory, timeout, environment, arguments.stream()
                        .map(this::localRemote).collect(ImmutableList.toImmutableList())));
                afterFetch.run();
                return result;
            }
            if (!arguments.isEmpty() && arguments.getFirst().equals("push")) {
                return super.run(directory, timeout, environment, arguments.stream()
                        .map(this::localRemote).collect(ImmutableList.toImmutableList()));
            }
            if (arguments.contains("--no-overwrite-ignore")) {
                transitions.add(ImmutableList.copyOf(arguments));
                Result result = super.run(directory, timeout, environment, arguments);
                afterTransition.run();
                return result;
            }
            return super.run(directory, timeout, environment, arguments);
        }

        private String localRemote(String value)
        {
            return switch (value) {
                case ORIGIN -> bareRemote.toString();
                case UPSTREAM -> temporaryDirectory.resolve("upstream.git").toString();
                default -> value;
            };
        }
    }
}
