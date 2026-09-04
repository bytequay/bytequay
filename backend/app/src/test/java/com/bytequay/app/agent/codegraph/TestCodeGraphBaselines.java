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
package com.bytequay.app.agent.codegraph;

import com.bytequay.agent.core.CodeGraphException;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskResources;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.git.GitCommandRunner.Result;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static com.google.common.base.Preconditions.checkState;
import static java.util.Collections.synchronizedList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TestCodeGraphBaselines
{
    private static final Optional<Path> BUNDLE = TestPinnedCodeGraphService.locateBundle();
    private static final Duration LONG = Duration.ofMinutes(5);
    private static final RepositoryIdentity REPOSITORY = new RepositoryIdentity("owner", "name");
    private static final String NOWHERE = "0000000000000000000000000000000000000000";

    private final GitCommandRunner git = new GitCommandRunner();

    @Test
    void refreshBuildsFromNothingThenIsANoOp(@TempDir Path temp)
            throws IOException
    {
        assumeBundle();
        Repository repository = Repository.create(temp, git);
        RecordingCli cli = new RecordingCli(git);
        PinnedCodeGraphService service = service(cli, temp);

        service.refreshBaseline(REPOSITORY, repository.root, repository.head());
        Path base = service.baselineDirectory(REPOSITORY);
        assertThat(base.resolve("codegraph").resolve("codegraph.db")).isRegularFile();
        assertThat(base.resolve("HEAD")).hasContent(repository.head() + "\n");
        assertThat(base.resolve("checkout")).doesNotExist();
        assertThat(base.resolve("codegraph.next")).doesNotExist();
        assertThat(repository.git("worktree", "list")).hasLineCount(1);
        assertThat(cli.commands).containsExactly("codegraph.next:init");

        service.refreshBaseline(REPOSITORY, repository.root, repository.head());
        assertThat(cli.commands).containsExactly("codegraph.next:init");
    }

    @Test
    void refreshSyncsASmallDeltaAndRebuildsALargeOne(@TempDir Path temp)
            throws IOException
    {
        assumeBundle();
        Repository repository = Repository.create(temp, git);
        RecordingCli cli = new RecordingCli(git);
        PinnedCodeGraphService service = service(cli, temp);
        service.refreshBaseline(REPOSITORY, repository.root, repository.head());

        repository.commitFiles("One of twenty-five", "Delta");
        service.refreshBaseline(REPOSITORY, repository.root, repository.head());
        assertThat(cli.commands).containsExactly("codegraph.next:init", "codegraph.next:sync");
        assertThat(service.baselineDirectory(REPOSITORY).resolve("HEAD")).hasContent(repository.head() + "\n");

        repository.commitFiles("Three of twenty-eight", "Echo", "Foxtrot", "Golf");
        service.refreshBaseline(REPOSITORY, repository.root, repository.head());
        assertThat(cli.commands).containsExactly("codegraph.next:init", "codegraph.next:sync", "codegraph.next:init");
        assertThat(repository.git("worktree", "list")).hasLineCount(1);
    }

    @Test
    void failedRefreshKeepsThePreviousBaseline(@TempDir Path temp)
            throws IOException
    {
        assumeBundle();
        Repository repository = Repository.create(temp, git);
        PinnedCodeGraphService service = service(new RecordingCli(git), temp);
        service.refreshBaseline(REPOSITORY, repository.root, repository.head());

        assertThatThrownBy(() -> service.refreshBaseline(REPOSITORY, repository.root, NOWHERE))
                .isInstanceOf(CodeGraphException.class)
                .hasMessageContaining("Cannot check out");
        Path base = service.baselineDirectory(REPOSITORY);
        assertThat(base.resolve("codegraph").resolve("codegraph.db")).isRegularFile();
        assertThat(base.resolve("HEAD")).hasContent(repository.head() + "\n");
        assertThat(base.resolve("checkout")).doesNotExist();
        assertThat(repository.git("worktree", "list")).hasLineCount(1);
    }

    @Test
    void prepareClonesTheBaselineAndOnlySyncs(@TempDir Path temp)
            throws IOException
    {
        assumeBundle();
        Repository repository = Repository.create(temp, git);
        RecordingCli cli = new RecordingCli(git);
        PinnedCodeGraphService service = service(cli, temp);
        service.refreshBaseline(REPOSITORY, repository.root, repository.head());
        Task task = repository.task(temp, repository.head());

        service.prepare(task.worktree, task.resources);
        assertThat(cli.commands).containsExactly("codegraph.next:init", "codegraph:sync");
        assertThat(task.resources.runtimeDirectory().resolve("codegraph.head")).hasContent(repository.head() + "\n");

        task.write("src/Zulu.java", "public class Zulu\n{\n    public static int zuluNine()\n    {\n        return 9;\n    }\n}\n");
        assertThat(service.explore(task.worktree, task.resources, "zuluNine")).contains("Zulu.java");
        assertThat(cli.commands).containsExactly("codegraph.next:init", "codegraph:sync", "codegraph:sync", "codegraph:explore");
        assertThat(service.baselineDirectory(REPOSITORY).resolve("codegraph").resolve("codegraph.db")).isRegularFile();
    }

    @Test
    void prepareWithoutABaselineBuildsTheTaskIndexDirectly(@TempDir Path temp)
            throws IOException
    {
        assumeBundle();
        Repository repository = Repository.create(temp, git);
        RecordingCli cli = new RecordingCli(git);
        PinnedCodeGraphService service = service(cli, temp);
        Task task = repository.task(temp, repository.head());

        service.prepare(task.worktree, task.resources);
        assertThat(cli.commands).containsExactly("codegraph:init");
        assertThat(service.baselineDirectory(REPOSITORY)).doesNotExist();
    }

    @Test
    void prepareWaitsForARunningRefresh(@TempDir Path temp)
            throws Exception
    {
        assumeBundle();
        Repository repository = Repository.create(temp, git);
        CountDownLatch refreshStarted = new CountDownLatch(1);
        RecordingCli cli = new RecordingCli(git, command -> {
            if (command.equals("codegraph.next:init")) {
                refreshStarted.countDown();
            }
        });
        PinnedCodeGraphService service = service(cli, temp);
        Task task = repository.task(temp, repository.head());

        CompletableFuture<Void> refresh = CompletableFuture.runAsync(() -> service.refreshBaseline(REPOSITORY, repository.root, repository.head()));
        assertThat(refreshStarted.await(30, TimeUnit.SECONDS)).isTrue();
        service.prepare(task.worktree, task.resources);
        refresh.join();
        assertThat(cli.commands).containsExactly("codegraph.next:init", "codegraph:sync");
    }

    @Test
    void exploreWaitsForPrepareOrReportsItIsStillPreparing(@TempDir Path temp)
            throws Exception
    {
        assumeBundle();
        Repository repository = Repository.create(temp, git);
        CountDownLatch buildStarted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        RecordingCli cli = new RecordingCli(git, command -> {
            if (command.equals("codegraph:init")) {
                buildStarted.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        });
        Task task = repository.task(temp, repository.head());

        PinnedCodeGraphService impatient = new PinnedCodeGraphService(
                cli, git, temp.resolve("baselines"), PinnedCodeGraphService.DEFAULT_RESULT_LIMIT_BYTES, LONG, LONG, Duration.ofMillis(50));
        CompletableFuture<Void> prepare = CompletableFuture.runAsync(() -> impatient.prepare(task.worktree, task.resources));
        assertThat(buildStarted.await(30, TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> impatient.explore(task.worktree, task.resources, "alphaOne"))
                .isInstanceOf(CodeGraphException.class)
                .hasMessage(PinnedCodeGraphService.STILL_PREPARING);
        release.countDown();
        prepare.join();
        assertThat(impatient.explore(task.worktree, task.resources, "alphaOne")).contains("Alpha0.java");
    }

    @Test
    void deleteBaselineIsIdempotent(@TempDir Path temp)
            throws IOException
    {
        assumeBundle();
        Repository repository = Repository.create(temp, git);
        PinnedCodeGraphService service = service(new RecordingCli(git), temp);
        service.refreshBaseline(REPOSITORY, repository.root, repository.head());
        service.deleteBaseline(REPOSITORY);
        assertThat(service.baselineDirectory(REPOSITORY)).doesNotExist();
        service.deleteBaseline(REPOSITORY);
    }

    private PinnedCodeGraphService service(CodeGraphCli cli, Path temp)
    {
        return new PinnedCodeGraphService(cli, git, temp.resolve("baselines"), PinnedCodeGraphService.DEFAULT_RESULT_LIMIT_BYTES, LONG, LONG, LONG);
    }

    private static void assumeBundle()
    {
        assumeTrue(BUNDLE.isPresent(), "vendored CodeGraph bundle missing; run scripts/codegraph-bundle.mjs");
    }

    /** Records every CLI command as {@code <index directory name>:<command>} and lets a test hook observe it. */
    private static class RecordingCli
            extends CodeGraphCli
    {
        final List<String> commands = synchronizedList(new ArrayList<>());
        private final Consumer<String> hook;

        RecordingCli(GitCommandRunner git)
        {
            this(git, ignored -> {});
        }

        RecordingCli(GitCommandRunner git, Consumer<String> hook)
        {
            super(BUNDLE, git);
            this.hook = hook;
        }

        @Override
        Result run(Path workingDirectory, Path indexDirectory, Path scratchHome, Duration timeout, List<String> arguments)
        {
            String command = indexDirectory.getFileName() + ":" + arguments.get(0);
            commands.add(command);
            hook.accept(command);
            return super.run(workingDirectory, indexDirectory, scratchHome, timeout, arguments);
        }
    }

    /** A managed repository with twenty-five tracked Java files, so one changed file stays under the five percent rebuild line. */
    private record Repository(Path root, GitCommandRunner git)
    {
        static Repository create(Path temp, GitCommandRunner git)
                throws IOException
        {
            Path root = temp.resolve("repository").toAbsolutePath().normalize();
            Files.createDirectories(root.resolve("src"));
            for (int i = 0; i < 25; i++) {
                Files.writeString(
                        root.resolve("src/Alpha" + i + ".java"),
                        "public class Alpha" + i + "\n{\n    public static int alphaOne()\n    {\n        return " + i + ";\n    }\n}\n");
            }
            run(git, root, "init", "-q", "-b", "main");
            run(git, root, "add", "-A");
            run(git, root, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "Alpha");
            return new Repository(root, git);
        }

        String head()
        {
            return git("rev-parse", "HEAD");
        }

        void commitFiles(String subject, String... names)
                throws IOException
        {
            for (String name : names) {
                Files.writeString(
                        root.resolve("src/" + name + ".java"),
                        "public class " + name + "\n{\n    public static int " + name.toLowerCase() + "Two()\n    {\n        return 2;\n    }\n}\n");
            }
            git("add", "-A");
            git("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", subject);
        }

        Task task(Path temp, String commit)
                throws IOException
        {
            TaskId taskId = TaskId.random();
            Path worktree = temp.resolve("tasks").resolve(taskId.toString()).toAbsolutePath().normalize();
            Files.createDirectories(worktree.getParent());
            git("worktree", "add", "-q", "--detach", worktree.toString(), commit);
            Path runtime = temp.resolve("runtime-" + taskId).toAbsolutePath().normalize();
            Files.createDirectories(runtime);
            return new Task(
                    new TaskWorktree(taskId, "workspace", REPOSITORY, worktree, "bytequay/task/" + taskId, commit),
                    new TaskResources(runtime, runtime.resolve("events.jsonl"), runtime.resolve("blobs"), runtime.resolve("provider").resolve("scratch")));
        }

        String git(String... arguments)
        {
            return run(git, root, arguments);
        }

        private static String run(GitCommandRunner git, Path directory, String... arguments)
        {
            Result result = git.run(directory, arguments);
            checkState(result.successful(), "git %s failed: %s", String.join(" ", arguments), result.output());
            return result.output().strip();
        }
    }

    private record Task(TaskWorktree worktree, TaskResources resources)
    {
        void write(String relative, String content)
                throws IOException
        {
            Files.writeString(worktree.canonicalWorktree().resolve(relative), content);
        }
    }
}
