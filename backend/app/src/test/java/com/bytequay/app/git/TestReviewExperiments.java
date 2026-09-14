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
package com.bytequay.app.git;

import com.bytequay.agent.core.ProvisionWorktreeCommand;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.app.git.GitCommandRunner.Result;
import com.bytequay.app.workspace.ManagedWorktreeService;
import com.bytequay.app.workspace.WorkspacePaths;
import com.bytequay.core.domain.RepoRef;
import com.bytequay.core.domain.Workspace;
import com.bytequay.core.store.WorkspaceStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

class TestReviewExperiments
{
    private static final String WORKSPACE_ID = "review-experiments";
    private static final String ORIGIN = "https://github.com/owner/repo.git";
    private static final RepoRef REPOSITORY = RepoRef.of("owner", "repo");
    private static final Instant NOW = Instant.parse("2026-09-01T00:00:00Z");

    @TempDir
    private Path directory;

    private final GitCommandRunner git = spy(new GitCommandRunner());
    private Path repository;
    private TaskWorktree worktree;
    private ManagedWorktreeService worktrees;
    private ReviewExperiments experiments;
    private String base;
    private String target;

    @BeforeEach
    void setUp()
            throws IOException
    {
        WorkspacePaths paths = new WorkspacePaths(directory.resolve("managed").toString());
        repository = paths.repository(WORKSPACE_ID);
        Files.createDirectories(repository);
        Files.writeString(paths.workspace(WORKSPACE_ID).resolve(".bytequay-workspace"), WORKSPACE_ID + "\n1\n");
        run(repository, "init", "--initial-branch=main");
        run(repository, "config", "user.name", "ByteQuay Test");
        run(repository, "config", "user.email", "test@example.invalid");
        run(repository, "config", "core.hooksPath", "/dev/null");
        run(repository, "remote", "add", "origin", ORIGIN);
        Files.writeString(repository.resolve("Code.java"), "original source\n");
        Files.writeString(repository.resolve("Other.java"), "other source\n");
        Files.writeString(repository.resolve(".gitignore"), "build/\n");
        run(repository, "add", ".");
        run(repository, "commit", "-m", "Reviewed revision");
        base = run(repository, "rev-parse", "HEAD");
        Files.writeString(repository.resolve("Remote.java"), "remote improvement\n");
        run(repository, "add", ".");
        run(repository, "commit", "-m", "Updated PR revision");
        target = run(repository, "rev-parse", "HEAD");
        Workspace workspace = new Workspace(WORKSPACE_ID, "Review experiments", 1, REPOSITORY, Optional.empty(), Optional.empty(),
                1, REPOSITORY, Workspace.Topology.DIRECT, "main", ORIGIN, Optional.empty(), Optional.empty(), repository.toString(),
                Optional.of(NOW), Optional.of(NOW), 0, NOW, NOW);
        WorkspaceStore store = mock(WorkspaceStore.class);
        when(store.findById(WORKSPACE_ID)).thenReturn(Optional.of(workspace));
        worktrees = new ManagedWorktreeService(paths, store, git);
        TaskId taskId = TaskId.random();
        worktree = worktrees.provision(new ProvisionWorktreeCommand(taskId, WORKSPACE_ID, RepositoryIdentity.valueOf(REPOSITORY.fullName()),
                base, "bytequay/task/" + taskId));
        experiments = new ReviewExperiments(git, worktrees);
    }

    @Test
    void cleanUpdateMovesOnlyToTheRequestedRevision()
    {
        assertThat(experiments.synchronize(worktree, base, target, this::moveToTarget)).isEmpty();
        assertThat(head()).isEqualTo(target);
        assertThat(worktree.canonicalWorktree().resolve("Remote.java")).hasContent("remote improvement\n");
        assertThat(run(repository, "stash", "list")).isEmpty();
        assertThat(run(worktree.canonicalWorktree(), "rev-list", "--count", base + "..HEAD")).isEqualTo("1");
    }

    @Test
    void dirtyUpdatePreservesStagedUnstagedAndUntrackedExperimentsIgnoredOutputAndUnrelatedStashes()
            throws IOException
    {
        Files.writeString(repository.resolve("Code.java"), "unrelated user's changes");
        run(repository, "stash", "push", "--message", "User stash from another worktree");
        String unrelated = run(repository, "rev-parse", "stash@{0}");
        Files.writeString(worktree.canonicalWorktree().resolve("Code.java"), "staged experiment");
        run(worktree.canonicalWorktree(), "add", "Code.java");
        Files.writeString(worktree.canonicalWorktree().resolve("Other.java"), "unstaged experiment");
        Files.writeString(worktree.canonicalWorktree().resolve("TestExperiment.java"), "private test");
        Files.createDirectories(worktree.canonicalWorktree().resolve("build"));
        Files.writeString(worktree.canonicalWorktree().resolve("build/output"), "ignored build output");

        assertThat(experiments.synchronize(worktree, base, target, this::moveToTarget)).isEmpty();

        assertThat(head()).isEqualTo(target);
        assertThat(worktree.canonicalWorktree().resolve("Code.java")).hasContent("staged experiment");
        assertThat(worktree.canonicalWorktree().resolve("Other.java")).hasContent("unstaged experiment");
        assertThat(worktree.canonicalWorktree().resolve("TestExperiment.java")).hasContent("private test");
        assertThat(worktree.canonicalWorktree().resolve("build/output")).hasContent("ignored build output");
        assertThat(run(worktree.canonicalWorktree(), "diff", "--cached", "--name-only")).isEqualTo("Code.java");
        assertThat(run(worktree.canonicalWorktree(), "diff", "--name-only")).isEqualTo("Other.java");
        assertThat(run(repository, "stash", "list", "--format=%H")).isEqualTo(unrelated);
        assertThat(run(repository, "stash", "show", "-p", "stash@{0}")).contains("unrelated user's changes");
    }

    @Test
    void alreadyUpdatedCheckoutKeepsExperimentsWithoutCallingTheMover()
            throws IOException
    {
        moveToTarget();
        Files.writeString(worktree.canonicalWorktree().resolve("TestExperiment.java"), "ongoing test");
        AtomicInteger moves = new AtomicInteger();

        assertThat(experiments.synchronize(worktree, base, target, moves::incrementAndGet)).isEmpty();

        assertThat(moves).hasValue(0);
        assertThat(worktree.canonicalWorktree().resolve("TestExperiment.java")).hasContent("ongoing test");
        assertThat(run(repository, "stash", "list")).isEmpty();
    }

    @Test
    void conflictingExperimentsAreDiscardedAtTheTargetWithoutPreventingTheNextUpdate()
            throws IOException
    {
        makeConflictingTarget();
        Files.writeString(worktree.canonicalWorktree().resolve("TestExperiment.java"), "private test");
        Files.createDirectories(worktree.canonicalWorktree().resolve("build"));
        Files.writeString(worktree.canonicalWorktree().resolve("build/output"), "ignored result");

        assertThat(experiments.synchronize(worktree, base, target, () -> {
            assertThat(run(repository, "stash", "list", "--format=%H").lines().count()).isEqualTo(1);
            moveToTarget();
        })).hasValueSatisfying(message -> assertThat(message).contains("discarded"));

        assertThat(head()).isEqualTo(target);
        assertThat(worktrees.inspect(worktree).uncommitted()).isFalse();
        assertThat(run(worktree.canonicalWorktree(), "ls-files", "--unmerged")).isEmpty();
        assertThat(worktree.canonicalWorktree().resolve("Code.java")).hasContent("remote code\n");
        assertThat(worktree.canonicalWorktree().resolve("TestExperiment.java")).doesNotExist();
        assertThat(worktree.canonicalWorktree().resolve("build/output")).hasContent("ignored result");
        assertThat(run(repository, "stash", "list")).isEmpty();
        String previous = target;
        Files.writeString(repository.resolve("Later.java"), "another remote improvement");
        run(repository, "add", ".");
        run(repository, "commit", "-m", "Another PR update");
        target = run(repository, "rev-parse", "HEAD");

        assertThat(experiments.synchronize(worktree, previous, target, this::moveToTarget)).isEmpty();
        assertThat(head()).isEqualTo(target);
        assertThat(run(repository, "stash", "list")).isEmpty();
    }

    @Test
    void nextExplicitUpdateRestoresAnInterruptedTemporaryStashBeforeUpdating()
            throws IOException
    {
        Files.writeString(worktree.canonicalWorktree().resolve("Code.java"), "staged experiment");
        run(worktree.canonicalWorktree(), "add", "Code.java");
        Files.writeString(worktree.canonicalWorktree().resolve("TestExperiment.java"), "private test");
        saveInterruptedExperiment();

        assertThat(experiments.synchronize(worktree, base, target, () -> {
            assertThat(run(repository, "stash", "list", "--format=%H").lines().count()).isEqualTo(1);
            moveToTarget();
        })).isEmpty();

        assertThat(head()).isEqualTo(target);
        assertThat(worktree.canonicalWorktree().resolve("Code.java")).hasContent("staged experiment");
        assertThat(worktree.canonicalWorktree().resolve("TestExperiment.java")).hasContent("private test");
        assertThat(run(worktree.canonicalWorktree(), "diff", "--cached", "--name-only")).isEqualTo("Code.java");
        assertThat(run(repository, "stash", "list")).isEmpty();
    }

    @Test
    void nextExplicitUpdateDiscardsInterruptedIndexConflictsEvenWhenTheTemporaryStashWasRemoved()
            throws IOException
    {
        makeConflictingTarget();
        saveInterruptedExperiment();
        moveToTarget();
        assertThat(git.run(worktree.canonicalWorktree(), "stash", "apply", "--index", "stash@{0}").successful()).isFalse();
        run(repository, "stash", "drop", "stash@{0}");
        assertThat(run(worktree.canonicalWorktree(), "ls-files", "--unmerged")).isNotEmpty();
        AtomicInteger moves = new AtomicInteger();

        assertThat(experiments.synchronize(worktree, base, target, moves::incrementAndGet)).hasValueSatisfying(message -> assertThat(message).contains("discarded"));

        assertThat(moves).hasValue(0);
        assertThat(head()).isEqualTo(target);
        assertThat(worktrees.inspect(worktree).uncommitted()).isFalse();
        assertThat(worktree.canonicalWorktree().resolve("Code.java")).hasContent("remote code\n");
        assertThat(run(repository, "stash", "list")).isEmpty();
    }

    @Test
    void refusesChangedHeadOrOpenGitOperationBeforeSavingOrMoving()
            throws IOException
    {
        Files.writeString(worktree.canonicalWorktree().resolve("TestExperiment.java"), "private test");
        AtomicInteger moves = new AtomicInteger();
        assertThatThrownBy(() -> experiments.synchronize(worktree, "a".repeat(40), target, moves::incrementAndGet)).hasMessageContaining("HEAD changed");
        Path marker = Path.of(run(worktree.canonicalWorktree(), "rev-parse", "--path-format=absolute", "--git-path", "MERGE_HEAD"));
        Files.writeString(marker, target);
        assertThatThrownBy(() -> experiments.synchronize(worktree, base, target, moves::incrementAndGet)).hasMessageContaining("open Git operation");
        assertThat(moves).hasValue(0);
        assertThat(run(repository, "stash", "list")).isEmpty();
        assertThat(worktree.canonicalWorktree().resolve("TestExperiment.java")).hasContent("private test");
    }

    @Test
    void failedMoveRestoresExperimentsAtTheOldRevision()
            throws IOException
    {
        Files.writeString(worktree.canonicalWorktree().resolve("Code.java"), "staged experiment");
        run(worktree.canonicalWorktree(), "add", "Code.java");
        Files.writeString(worktree.canonicalWorktree().resolve("TestExperiment.java"), "private test");

        assertThatThrownBy(() -> experiments.synchronize(worktree, base, target, () -> {
            assertThat(worktrees.inspect(worktree).uncommitted()).isFalse();
            throw new IllegalStateException("Remote move failed");
        })).hasMessageContaining("Remote move failed");

        assertThat(head()).isEqualTo(base);
        assertThat(worktree.canonicalWorktree().resolve("Code.java")).hasContent("staged experiment");
        assertThat(worktree.canonicalWorktree().resolve("TestExperiment.java")).hasContent("private test");
        assertThat(run(worktree.canonicalWorktree(), "diff", "--cached", "--name-only")).isEqualTo("Code.java");
        assertThat(run(repository, "stash", "list")).isEmpty();
    }

    @Test
    void failedMoveWithAnUnexpectedHeadPreservesTheBackupWithoutApplyingToUnknownCode()
            throws IOException
    {
        Files.writeString(repository.resolve("Later.java"), "later remote revision");
        run(repository, "add", ".");
        run(repository, "commit", "-m", "Unexpected revision");
        String unexpected = run(repository, "rev-parse", "HEAD");
        Files.writeString(worktree.canonicalWorktree().resolve("TestExperiment.java"), "private test");

        assertThatThrownBy(() -> experiments.synchronize(worktree, base, target, () -> {
            run(worktree.canonicalWorktree(), "reset", "--hard", unexpected);
            throw new IllegalStateException("Move outcome uncertain");
        })).hasMessageContaining("experiments remain saved");

        assertThat(head()).isEqualTo(unexpected);
        assertThat(worktree.canonicalWorktree().resolve("TestExperiment.java")).doesNotExist();
        assertThat(run(repository, "stash", "show", "--include-untracked", "-p", "stash@{0}")).contains("private test");
    }

    @Test
    void explicitDiscardClearsCurrentAndInterruptedExperimentsButPreservesUnrelatedStashesAndIgnoredOutput()
            throws IOException
    {
        Files.writeString(repository.resolve("Other.java"), "unrelated experiment");
        run(repository, "stash", "push", "--message", "User work");
        String unrelated = run(repository, "rev-parse", "stash@{0}");
        makeConflictingTarget();
        saveInterruptedExperiment();
        moveToTarget();
        assertThat(git.run(worktree.canonicalWorktree(), "stash", "apply", "--index", "stash@{0}").successful()).isFalse();
        assertThat(run(worktree.canonicalWorktree(), "ls-files", "--unmerged")).isNotEmpty();
        Files.writeString(worktree.canonicalWorktree().resolve("AnotherTest.java"), "another private test");
        Files.createDirectories(worktree.canonicalWorktree().resolve("build"));
        Files.writeString(worktree.canonicalWorktree().resolve("build/output"), "ignored result");

        experiments.discard(worktree, target);

        assertThat(head()).isEqualTo(target);
        assertThat(worktrees.inspect(worktree).uncommitted()).isFalse();
        assertThat(worktree.canonicalWorktree().resolve("Code.java")).hasContent("remote code\n");
        assertThat(worktree.canonicalWorktree().resolve("AnotherTest.java")).doesNotExist();
        assertThat(worktree.canonicalWorktree().resolve("build/output")).hasContent("ignored result");
        assertThat(run(repository, "stash", "list", "--format=%H")).isEqualTo(unrelated);
    }

    @Test
    void failedTemporaryStashDropThrowsAndTheNextExplicitUpdateDiscardsConflictingExperiments()
            throws IOException
    {
        Files.writeString(worktree.canonicalWorktree().resolve("TestExperiment.java"), "private test");
        doReturn(Result.failed("simulated drop failure")).when(git).run(worktree.canonicalWorktree(), "stash", "drop", "stash@{0}");

        assertThatThrownBy(() -> experiments.synchronize(worktree, base, target, this::moveToTarget)).hasMessageContaining("simulated drop failure");

        assertThat(head()).isEqualTo(target);
        assertThat(worktree.canonicalWorktree().resolve("TestExperiment.java")).hasContent("private test");
        assertThat(run(repository, "stash", "list", "--format=%H").lines().count()).isEqualTo(1);
        doCallRealMethod().when(git).run(worktree.canonicalWorktree(), "stash", "drop", "stash@{0}");
        AtomicInteger moves = new AtomicInteger();

        assertThat(experiments.synchronize(worktree, base, target, moves::incrementAndGet)).hasValueSatisfying(message -> assertThat(message).contains("discarded"));

        assertThat(moves).hasValue(0);
        assertThat(head()).isEqualTo(target);
        assertThat(worktrees.inspect(worktree).uncommitted()).isFalse();
        assertThat(run(repository, "stash", "list")).isEmpty();
        assertThat(worktree.canonicalWorktree().resolve("TestExperiment.java")).doesNotExist();
    }

    @Test
    void failedDiscardDoesNotCreateAnotherStashOrDeleteTheSavedExperiment()
            throws IOException
    {
        Files.writeString(worktree.canonicalWorktree().resolve("TestExperiment.java"), "saved test");
        saveInterruptedExperiment();
        String saved = run(repository, "rev-parse", "stash@{0}");
        Files.writeString(worktree.canonicalWorktree().resolve("AnotherTest.java"), "current test");
        doReturn(Result.failed("simulated clean failure")).when(git).run(worktree.canonicalWorktree(), "clean", "-fd");

        assertThatThrownBy(() -> experiments.discard(worktree, base)).hasMessageContaining("simulated clean failure");

        assertThat(head()).isEqualTo(base);
        assertThat(worktree.canonicalWorktree().resolve("AnotherTest.java")).hasContent("current test");
        assertThat(run(repository, "stash", "list", "--format=%H")).isEqualTo(saved);
    }

    private void saveInterruptedExperiment()
    {
        run(worktree.canonicalWorktree(), "stash", "push", "--include-untracked", "--message", "bytequay-review-experiment:" + worktree.taskId() + ":sync:interrupted");
    }

    private void makeConflictingTarget()
            throws IOException
    {
        Files.writeString(repository.resolve("Code.java"), "remote code\n");
        run(repository, "add", ".");
        run(repository, "commit", "-m", "Change reviewed code");
        target = run(repository, "rev-parse", "HEAD");
        Files.writeString(worktree.canonicalWorktree().resolve("Code.java"), "experiment code\n");
    }

    private String head()
    {
        return run(worktree.canonicalWorktree(), "rev-parse", "HEAD");
    }

    private void moveToTarget()
    {
        run(worktree.canonicalWorktree(), "reset", "--hard", target);
    }

    private String run(Path path, String... arguments)
    {
        Result result = git.run(path, arguments);
        assertThat(result.successful()).as("git %s: %s", String.join(" ", arguments), result.output()).isTrue();
        return result.output().strip();
    }
}
