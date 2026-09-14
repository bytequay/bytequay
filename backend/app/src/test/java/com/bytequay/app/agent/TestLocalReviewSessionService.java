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
import com.bytequay.agent.core.AgentTurnService;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.agent.core.TitleSource;
import com.bytequay.agent.core.WorktreeService;
import com.bytequay.agent.core.WorktreeSnapshot;
import com.bytequay.agent.spi.AgentLaneId;
import com.bytequay.agent.spi.AgentPermissionPolicy;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.git.GitCommandRunner.Result;
import com.bytequay.app.git.ReviewExperiments;
import com.bytequay.app.repository.github.GitHubPullRequestIdentity;
import com.bytequay.core.domain.PullRequestRef;
import com.bytequay.core.domain.RepoRef;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestLocalReviewSessionService
{
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");
    private static final RepoRef REPOSITORY = RepoRef.of("owner", "repo");
    private final TaskId taskId = TaskId.random();
    private final AgentTurnService turns = mock(AgentTurnService.class);
    private final DevelopmentGitInspector inspector = mock(DevelopmentGitInspector.class);
    private final GitCommandRunner git = spy(new GitCommandRunner());
    private final WorktreeService worktrees = mock(WorktreeService.class);
    private final AtomicReference<AgentTask> storedTask = new AtomicReference<>();
    private final LocalReviewSessionService service = new LocalReviewSessionService(turns, inspector, new ReviewExperiments(git, worktrees));

    @TempDir
    private Path directory;

    private Path repository;
    private TaskWorktree worktree;
    private DevelopmentPublicationRoute route;
    private String head;

    @BeforeEach
    void setUp()
            throws IOException
    {
        repository = Files.createDirectories(directory.resolve("repository"));
        run(repository, "init", "--initial-branch=main");
        run(repository, "config", "user.name", "ByteQuay Test");
        run(repository, "config", "user.email", "test@example.invalid");
        run(repository, "config", "core.hooksPath", "/dev/null");
        Files.writeString(repository.resolve("Code.java"), "original source\n");
        Files.writeString(repository.resolve(".gitignore"), "build/\n");
        run(repository, "add", ".");
        run(repository, "commit", "-m", "Intended development");
        head = run(repository, "rev-parse", "HEAD");
        String branch = "bytequay/task/" + taskId;
        Path checkout = directory.resolve(taskId.toString());
        run(repository, "worktree", "add", "-b", branch, checkout.toString(), head);
        worktree = new TaskWorktree(taskId, "workspace", RepositoryIdentity.valueOf(REPOSITORY.fullName()), checkout, branch, head);
        route = new DevelopmentPublicationRoute(REPOSITORY, REPOSITORY, "https://github.com/owner/repo.git", "origin", "main", head, branch);
        storedTask.set(AgentTask.created(taskId, true, "Develop", TitleSource.USER, AgentLaneId.CODEX_CLI, worktree,
                AgentTaskDefaults.forPermission(AgentPermissionPolicy.AUTO), NOW));
        when(worktrees.inspect(worktree)).thenAnswer(invocation -> new WorktreeSnapshot(worktree, local().headSha(), local().uncommittedFileCount() > 0, false));
        when(inspector.synchronizeSource(any(), any(), any(), any())).thenAnswer(invocation -> {
            assertThat(run(worktree.canonicalWorktree(), "status", "--porcelain=v1", "--untracked-files=all")).isEmpty();
            assertThat(run(worktree.canonicalWorktree(), "rev-parse", "HEAD")).isEqualTo(invocation.getArgument(1));
            run(worktree.canonicalWorktree(), "merge", "--ff-only", invocation.getArgument(2));
            return local();
        });
        when(inspector.capture(any())).thenAnswer(invocation -> new DevelopmentBaseline(worktree, route, local()));
        when(inspector.inspectLocal(worktree, route)).thenAnswer(invocation -> local());
        when(inspector.inspectPublication(worktree, route)).thenReturn(published(head));
        when(turns.updateLocalReview(eq(taskId), anyLong(), any())).thenAnswer(invocation -> {
            Function<AgentTask, Optional<String>> action = invocation.getArgument(2);
            AgentTask current = storedTask.get();
            AgentTask updated = current.withLocalReviewHead(action.apply(current), NOW).withVersion(current.version() + 1);
            storedTask.set(updated);
            return updated;
        });
    }

    @Test
    void startsFromTheCleanPublishedCommitAndAllowsIgnoredBuildOutput()
            throws IOException
    {
        Files.createDirectories(worktree.canonicalWorktree().resolve("build"));
        Files.writeString(worktree.canonicalWorktree().resolve("build/output"), "build result");

        AgentTask started = service.start(taskId, storedTask.get().version());

        assertThat(started.localReviewHead()).contains(head);
        assertThat(run(worktree.canonicalWorktree(), "rev-parse", "HEAD")).isEqualTo(head);
        verify(inspector).inspectPublication(worktree, route);
    }

    @Test
    void refusesUncommittedDevelopmentWithoutChangingTheFiles()
            throws IOException
    {
        Files.writeString(worktree.canonicalWorktree().resolve("Code.java"), "unfinished feature");
        Files.writeString(worktree.canonicalWorktree().resolve("NewTest.java"), "unfinished test");

        assertThatThrownBy(() -> service.start(taskId, storedTask.get().version())).hasMessageContaining("clean committed revision");

        assertThat(storedTask.get().localReviewHead()).isEmpty();
        assertThat(worktree.canonicalWorktree().resolve("Code.java")).hasContent("unfinished feature");
        assertThat(worktree.canonicalWorktree().resolve("NewTest.java")).hasContent("unfinished test");
        assertThat(run(repository, "stash", "list")).isEmpty();
    }

    @Test
    void refusesUnpublishedAndOutdatedPullRequests()
    {
        when(inspector.inspectPublication(worktree, route)).thenReturn(new DevelopmentPublicationFacts(Optional.empty(), ImmutableList.of()));
        assertThatThrownBy(() -> service.start(taskId, storedTask.get().version())).hasMessageContaining("draft pull request");
        when(inspector.inspectPublication(worktree, route)).thenReturn(published("a".repeat(40)));
        assertThatThrownBy(() -> service.start(taskId, storedTask.get().version())).hasMessageContaining("draft pull request");
        assertThat(storedTask.get().localReviewHead()).isEmpty();
    }

    @Test
    void refusesAHeadThatChangesDuringEntry()
    {
        when(inspector.inspectLocal(worktree, route)).thenReturn(facts("a".repeat(40), 0, false));

        assertThatThrownBy(() -> service.start(taskId, storedTask.get().version())).hasMessageContaining("Development changed");
        assertThat(storedTask.get().localReviewHead()).isEmpty();
    }

    @Test
    void finishingDiscardsOnlyExperimentsAndRetainsThePublishedCommitIgnoredOutputAndUnrelatedStash()
            throws IOException
    {
        Files.writeString(repository.resolve("Code.java"), "unrelated user's work");
        run(repository, "stash", "push", "--message", "Unrelated user stash");
        String userStash = run(repository, "rev-parse", "stash@{0}");
        service.start(taskId, storedTask.get().version());
        Files.writeString(worktree.canonicalWorktree().resolve("Code.java"), "staged experiment");
        run(worktree.canonicalWorktree(), "add", "Code.java");
        Files.writeString(worktree.canonicalWorktree().resolve("Code.java"), "unstaged experiment");
        Files.writeString(worktree.canonicalWorktree().resolve("TestExperiment.java"), "private test");
        Files.createDirectories(worktree.canonicalWorktree().resolve("build"));
        Files.writeString(worktree.canonicalWorktree().resolve("build/output"), "ignored result");

        AgentTask finished = service.finish(taskId, storedTask.get().version());

        assertThat(finished.localReviewHead()).isEmpty();
        assertThat(run(worktree.canonicalWorktree(), "status", "--porcelain=v1", "--untracked-files=all")).isEmpty();
        assertThat(run(worktree.canonicalWorktree(), "rev-parse", "HEAD")).isEqualTo(head);
        assertThat(worktree.canonicalWorktree().resolve("Code.java")).hasContent("original source\n");
        assertThat(worktree.canonicalWorktree().resolve("TestExperiment.java")).doesNotExist();
        assertThat(worktree.canonicalWorktree().resolve("build/output")).hasContent("ignored result");
        assertThat(run(repository, "stash", "list", "--format=%H")).isEqualTo(userStash);
        assertThat(run(repository, "stash", "show", "-p", "stash@{0}")).contains("unrelated user's work");
    }

    @Test
    void refusesChangedHistoryAndOpenGitOperationsWithoutStashing()
            throws IOException
    {
        service.start(taskId, storedTask.get().version());
        Files.writeString(worktree.canonicalWorktree().resolve("TestExperiment.java"), "private test");
        when(worktrees.inspect(worktree)).thenReturn(new WorktreeSnapshot(worktree, "a".repeat(40), true, false));
        assertThatThrownBy(() -> service.finish(taskId, storedTask.get().version())).hasMessageContaining("HEAD changed");
        when(worktrees.inspect(worktree)).thenReturn(new WorktreeSnapshot(worktree, head, true, true));
        assertThatThrownBy(() -> service.finish(taskId, storedTask.get().version())).hasMessageContaining("open Git operation");
        assertThat(storedTask.get().localReviewHead()).contains(head);
        assertThat(worktree.canonicalWorktree().resolve("TestExperiment.java")).hasContent("private test");
        assertThat(run(repository, "stash", "list")).isEmpty();
    }

    @Test
    void failedCleanupKeepsPrivateModeUntilTheExplicitRetryFinishes()
            throws IOException
    {
        service.start(taskId, storedTask.get().version());
        Files.writeString(worktree.canonicalWorktree().resolve("Code.java"), "private source edit");
        Files.writeString(worktree.canonicalWorktree().resolve("TestExperiment.java"), "private test");
        doReturn(Result.failed("simulated cleanup failure")).when(git).run(worktree.canonicalWorktree(), "clean", "-fd");

        assertThatThrownBy(() -> service.finish(taskId, storedTask.get().version())).hasMessageContaining("simulated cleanup failure");

        assertThat(storedTask.get().localReviewHead()).contains(head);
        assertThat(run(repository, "stash", "list")).isEmpty();
        assertThat(worktree.canonicalWorktree().resolve("TestExperiment.java")).hasContent("private test");
        assertThat(run(worktree.canonicalWorktree(), "rev-parse", "HEAD")).isEqualTo(head);
        doCallRealMethod().when(git).run(worktree.canonicalWorktree(), "clean", "-fd");

        assertThat(service.finish(taskId, storedTask.get().version()).localReviewHead()).isEmpty();
        assertThat(run(repository, "stash", "list")).isEmpty();
    }

    @Test
    void synchronizesPublishedHeadAndKeepsExperimentsPrivate()
            throws IOException
    {
        service.start(taskId, storedTask.get().version());
        Files.writeString(worktree.canonicalWorktree().resolve("TestExperiment.java"), "private verification");
        Files.writeString(repository.resolve("Remote.java"), "remote feature");
        run(repository, "add", "Remote.java");
        run(repository, "commit", "-m", "Remote change");
        String remoteHead = run(repository, "rev-parse", "HEAD");
        when(inspector.inspectPublication(worktree, route)).thenReturn(published(remoteHead));

        LocalReviewSessionService.SyncResult result = service.synchronize(taskId, storedTask.get().version());

        assertThat(result.warning()).isEmpty();
        assertThat(result.task().localReviewHead()).contains(remoteHead);
        assertThat(worktree.canonicalWorktree().resolve("Remote.java")).hasContent("remote feature");
        assertThat(worktree.canonicalWorktree().resolve("TestExperiment.java")).hasContent("private verification");
        assertThat(run(worktree.canonicalWorktree(), "status", "--porcelain=v1")).contains("?? TestExperiment.java");
        assertThat(run(repository, "stash", "list")).isEmpty();
        assertThat(service.finish(taskId, result.task().version()).localReviewHead()).isEmpty();
        assertThat(run(worktree.canonicalWorktree(), "rev-parse", "HEAD")).isEqualTo(remoteHead);
    }

    @Test
    void conflictingRestoreDiscardsExperimentsAndFinishesAtTheSyncedBaseline()
            throws IOException
    {
        service.start(taskId, storedTask.get().version());
        Files.writeString(worktree.canonicalWorktree().resolve("Code.java"), "private experiment\n");
        Files.writeString(repository.resolve("Code.java"), "updated remote source\n");
        run(repository, "add", "Code.java");
        run(repository, "commit", "-m", "Remote source change");
        String remoteHead = run(repository, "rev-parse", "HEAD");
        when(inspector.inspectPublication(worktree, route)).thenReturn(published(remoteHead));

        LocalReviewSessionService.SyncResult result = service.synchronize(taskId, storedTask.get().version());

        assertThat(result.warning()).hasValueSatisfying(message -> assertThat(message).contains("discarded"));
        assertThat(result.task().localReviewHead()).contains(remoteHead);
        assertThat(run(worktree.canonicalWorktree(), "rev-parse", "HEAD")).isEqualTo(remoteHead);
        assertThat(run(repository, "stash", "list")).isEmpty();
        assertThat(run(worktree.canonicalWorktree(), "status", "--porcelain=v1")).isEmpty();
        assertThat(worktree.canonicalWorktree().resolve("Code.java")).hasContent("updated remote source\n");
        assertThat(service.synchronize(taskId, result.task().version()).warning()).isEmpty();
    }

    private DevelopmentLocalFacts local()
    {
        String status = run(worktree.canonicalWorktree(), "status", "--porcelain=v1", "--untracked-files=all");
        return facts(run(worktree.canonicalWorktree(), "rev-parse", "HEAD"), (int) status.lines().count(), false);
    }

    private DevelopmentLocalFacts facts(String revision, int changedFiles, boolean gitOperation)
    {
        return new DevelopmentLocalFacts(worktree.generatedBranch(), revision, "tree", "fingerprint", 0, changedFiles, 0, changedFiles, 1, true, gitOperation);
    }

    private DevelopmentPublicationFacts published(String revision)
    {
        return new DevelopmentPublicationFacts(Optional.of(revision), ImmutableList.of(new GitHubPullRequestIdentity(
                PullRequestRef.of("owner", "repo", 1), "https://github.com/owner/repo/pull/1", REPOSITORY, worktree.generatedBranch(), revision,
                REPOSITORY, "main", head, GitHubPullRequestIdentity.State.OPEN, true, "owner")));
    }

    private String run(Path path, String... arguments)
    {
        Result result = git.run(path, arguments);
        assertThat(result.successful()).as("git %s: %s", String.join(" ", arguments), result.output()).isTrue();
        return result.output().strip();
    }
}
