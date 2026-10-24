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
package com.bytequay.app.git.command;

import com.bytequay.app.git.GitTestSupport;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.bytequay.app.git.GitTestSupport.configureUser;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TestGitCommandRunner
{
    private static final GitCommandRunner GIT = new GitCommandRunner();

    @TempDir
    private Path temporaryDirectory;

    private Path repository;

    @BeforeEach
    void setUp()
            throws IOException
    {
        Path created = Files.createDirectories(temporaryDirectory.resolve("repo"));
        git(temporaryDirectory, "init", "--quiet", "-b", "main", created.toString());
        repository = created.toRealPath();
    }

    @Test
    void resolvesEveryMarkerInOneCallAndKeepsTheRequestedOrder()
            throws IOException
    {
        List<String> markers = ImmutableList.of("CHERRY_PICK_HEAD", "rebase-merge", "MERGE_HEAD");

        List<Path> paths = GIT.gitPaths(repository, markers);

        assertThat(paths).hasSameSizeAs(markers);
        for (int marker = 0; marker < markers.size(); marker++) {
            assertThat(paths.get(marker))
                    .isAbsolute()
                    .isEqualTo(repository.resolve(".git").resolve(markers.get(marker)));
        }

        // The caller decides what an unborn marker means, so a resolved path is
        // reported whether or not the operation is open.
        assertThat(paths).noneMatch(Files::exists);
        Files.writeString(paths.get(2), "0".repeat(40) + "\n", StandardCharsets.UTF_8);
        assertThat(GIT.gitPaths(repository, markers)).map(Files::exists)
                .containsExactly(false, false, true);
    }

    @Test
    void failsOnlyOnLeftoverMarkersNotOnWhitespaceTheAuthorWrote()
            throws IOException
    {
        configureUser(repository, "ByteQuay Test", "bytequay@example.com");
        GitTestSupport.write(repository, "file.txt", "one\n");
        GitTestSupport.commit(repository, "Base");
        String before = GitTestSupport.head(repository);

        GitTestSupport.write(repository, "file.txt", "one\ntrailing space here   \n\n");
        GitTestSupport.commit(repository, "Faithful copy of untidy upstream content");
        assertThat(GIT.withoutConflictMarkers(repository, before, GitTestSupport.head(repository)))
                .as("upstream whitespace must not fail a faithful pick")
                .isTrue();

        String beforeMarkers = GitTestSupport.head(repository);
        GitTestSupport.write(repository, "file.txt", "<<<<<<< HEAD\nours\n=======\ntheirs\n>>>>>>> other\n");
        GitTestSupport.commit(repository, "Left a resolution unfinished");
        assertThat(GIT.withoutConflictMarkers(repository, beforeMarkers, GitTestSupport.head(repository))).isFalse();
    }

    @Test
    void defaultExecutableKeepsThePathGitUnlessItIsTheMacosLauncher()
            throws IOException
    {
        Path directory = Files.createDirectories(temporaryDirectory.resolve("bin"));
        Path installed = Files.writeString(directory.resolve("git"), "#!/bin/sh\n");
        assertThat(installed.toFile().setExecutable(true)).isTrue();
        assertThat(GitCommandRunner.defaultExecutable(directory + ":/usr/bin")).isEqualTo("git");

        String developerGit = GitCommandRunner.defaultExecutable("/usr/bin");
        assertThat(developerGit).endsWith("/usr/bin/git").isNotEqualTo("/usr/bin/git");
        assertThat(new GitCommandRunner(developerGit).run(repository, "--version").output()).startsWith("git version");
    }

    @Test
    void reportsNoPathsOutsideARepository()
            throws IOException
    {
        Path plain = Files.createDirectories(temporaryDirectory.resolve("plain"));

        assertThat(GIT.gitPaths(plain, ImmutableList.of("MERGE_HEAD"))).isEmpty();
    }

    @Test
    void stdoutRunKeepsWarningsOutOfTheOutputAndReportsThemOnFailure()
    {
        String bothStreams = "alias.both=!printf 'out\\0'; echo 'warning: noise' 1>&2";
        assertThat(GIT.run(repository, "-c", bothStreams, "both").output()).contains("warning: noise").contains("out\0");

        GitCommandRunner.Result success = GIT.runStdout(repository, "-c", bothStreams, "both");
        assertThat(success.successful()).isTrue();
        assertThat(success.output()).isEqualTo("out\0");

        GitCommandRunner.Result failure = GIT.runStdout(repository, "-c", bothStreams + "; exit 3", "both");
        assertThat(failure.exitCode()).isEqualTo(3);
        assertThat(failure.output()).isEqualTo("warning: noise\n");
    }

    @Test
    void localBoundedReadsUseConfiguredExecutableSafetyEnvironmentAndOutputCap()
            throws IOException
    {
        Path executable = temporaryDirectory.resolve("local-git");
        Files.writeString(executable, """
                #!/bin/sh
                printf '%s %s %s %s\\n' "$GIT_NO_LAZY_FETCH" "$GIT_NO_REPLACE_OBJECTS" "$GIT_TERMINAL_PROMPT" "$LC_ALL"
                printf '%s\\n' "$@"
                """);
        assertThat(executable.toFile().setExecutable(true)).isTrue();
        GitCommandRunner runner = new GitCommandRunner(executable.toString());

        GitCommandRunner.Result read = runner.runLocalBounded(repository, Duration.ofSeconds(2), 4096, ImmutableList.of("cat-file", "blob", "a".repeat(40)));
        assertThat(read.successful()).isTrue();
        assertThat(read.truncated()).isFalse();
        assertThat(read.output()).isEqualTo("1 1 0 C\n-C\n" + repository + "\ncat-file\nblob\n" + "a".repeat(40) + "\n");

        GitCommandRunner.Result bounded = runner.runLocalBounded(repository, Duration.ofSeconds(2), 8, ImmutableList.of("cat-file"));
        assertThat(bounded.successful()).isTrue();
        assertThat(bounded.truncated()).isTrue();
        assertThat(bounded.output()).isEqualTo("1 1 0 C\n");
    }

    @Test
    void requiresCompleteSuccessfulOutputAndPreservesItsBytes()
    {
        assertThat(new GitCommandRunner.Result(0, " path \0\n", false).requireOutput(IllegalStateException::new)).isEqualTo(" path \0\n");
        assertThatThrownBy(() -> new GitCommandRunner.Result(0, "partial", true).requireOutput(IllegalStateException::new))
                .isInstanceOf(IllegalStateException.class).hasMessage("Git output was truncated");
        assertThatThrownBy(() -> new GitCommandRunner.Result(2, " failure\n", false).requireOutput(IllegalArgumentException::new))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("failure");
        assertThatThrownBy(() -> new GitCommandRunner.Result(2, "", false).requireOutput(IllegalStateException::new))
                .hasMessage("Git exited with status 2");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void stopsHooksAndTheirChildrenBeforeReturningFromTimeoutOrInterruption(boolean interrupt)
            throws Exception
    {
        configureUser(repository, "ByteQuay Test", "bytequay@example.com");
        git(repository, "-c", "core.hooksPath=/dev/null", "commit", "--allow-empty", "-m", "Base");
        Path hook = repository.resolve(".git/hooks/post-checkout");
        Files.writeString(hook, """
                #!/bin/bash
                trap '' TERM
                set -m
                printf '%s\\n' "$$" > hook.pid
                sleep 30 &
                printf '%s\\n' "$!" > child.pid
                wait
                printf 'changed' > late-write
                """);
        assertThat(hook.toFile().setExecutable(true)).isTrue();
        CompletableFuture<GitCommandRunner.Result> result = new CompletableFuture<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread caller = Thread.ofPlatform().start(() -> {
            try {
                result.complete(GIT.run(repository, Duration.ofSeconds(interrupt ? 30 : 2),
                        ImmutableList.of("-c", "core.hooksPath=" + hook.getParent(), "checkout", "-b", "hook-cleanup")));
                interrupted.set(Thread.currentThread().isInterrupted());
            }
            catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        });
        try {
            long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            Path childPid = repository.resolve("child.pid");
            while ((!Files.exists(childPid) || Files.size(childPid) == 0) && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertThat(childPid).exists();
            long child = Long.parseLong(Files.readString(childPid).strip());
            long hookPid = Long.parseLong(Files.readString(repository.resolve("hook.pid")).strip());
            if (interrupt) {
                caller.interrupt();
            }
            assertThat(result.get(10, TimeUnit.SECONDS).output()).isEqualTo(interrupt ? "Git was interrupted" : "Git timed out");
            caller.join(Duration.ofSeconds(5));
            assertThat(caller.isAlive()).isFalse();
            assertThat(interrupted.get()).isEqualTo(interrupt);
            assertThat(ProcessHandle.of(hookPid).map(ProcessHandle::isAlive).orElse(false)).as("Git hook is stopped").isFalse();
            assertThat(ProcessHandle.of(child).map(ProcessHandle::isAlive).orElse(false)).as("Hook child is stopped").isFalse();
            assertThat(repository.resolve("late-write")).doesNotExist();
        }
        finally {
            caller.interrupt();
            for (String file : ImmutableList.of("hook.pid", "child.pid")) {
                Path pid = repository.resolve(file);
                if (Files.exists(pid) && !Files.readString(pid).isBlank()) {
                    ProcessHandle.of(Long.parseLong(Files.readString(pid).strip())).ifPresent(ProcessHandle::destroyForcibly);
                }
            }
            caller.join(Duration.ofSeconds(5));
        }
    }

    @Test
    void stopsHelpersThatOutliveTheCommand()
            throws Exception
    {
        Path pid = repository.resolve("helper.pid");
        try {
            GitCommandRunner.Result result = GIT.runCommand(repository, ImmutableList.of("/bin/sh", "-c", """
                    trap '' TERM
                    (sleep 30; printf changed > late-write) &
                    printf '%s\\n' "$!" > helper.pid
                    """), Duration.ofSeconds(10), 1024);

            assertThat(result.successful()).as(result.output()).isTrue();
            long helper = Long.parseLong(Files.readString(pid).strip());
            // A killed orphan can remain visible briefly while the OS reaps it.
            ProcessHandle.of(helper).map(ProcessHandle::onExit).orElseGet(() -> CompletableFuture.completedFuture(null)).get(2, TimeUnit.SECONDS);
            assertThat(ProcessHandle.of(helper).map(ProcessHandle::isAlive).orElse(false)).isFalse();
            assertThat(repository.resolve("late-write")).doesNotExist();
        }
        finally {
            if (Files.exists(pid)) {
                ProcessHandle.of(Long.parseLong(Files.readString(pid).strip())).ifPresent(process -> {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                });
            }
        }
    }

    private static void git(Path directory, String... arguments)
    {
        GitCommandRunner.Result result = GIT.run(directory, arguments);
        assertThat(result.exitCode())
                .as("git %s: %s", String.join(" ", arguments), result.output())
                .isZero();
    }
}
