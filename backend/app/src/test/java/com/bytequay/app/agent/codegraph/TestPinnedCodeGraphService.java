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
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import static com.google.common.base.Preconditions.checkState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class TestPinnedCodeGraphService
{
    private static final Optional<Path> BUNDLE = locateBundle();
    private static final Duration LONG = Duration.ofMinutes(5);

    private final GitCommandRunner git = new GitCommandRunner();
    @TempDir
    private Path baselines;

    @Test
    void exploreBuildsTheIndexAndFollowsEveryKindOfChange(@TempDir Path temp)
            throws IOException
    {
        assumeBundle();
        Fixture fixture = Fixture.create(temp, git);
        PinnedCodeGraphService service = service(PinnedCodeGraphService.DEFAULT_RESULT_LIMIT_BYTES, LONG);

        String first = service.explore(fixture.worktree, fixture.resources, "alphaOne");
        assertThat(first).contains("alphaOne").contains("Alpha.java");
        assertThat(fixture.resources.runtimeDirectory().resolve("codegraph").resolve("codegraph.db")).isRegularFile();
        assertThat(fixture.resources.runtimeDirectory().resolve("codegraph.head")).hasContent(fixture.git("rev-parse", "HEAD") + "\n");
        assertThat(fixture.git("status", "--porcelain")).isEmpty();
        assertThat(Files.list(fixture.root).map(path -> path.getFileName().toString())).doesNotContain(".codegraph");

        // Nothing changed: the unscoped sync path, not a rebuild.
        assertThat(service.explore(fixture.worktree, fixture.resources, "alphaOne")).contains("Alpha.java");

        fixture.write("src/Beta.java", "public class Beta\n{\n    public static int betaTwo()\n    {\n        return 2;\n    }\n}\n");
        assertThat(service.explore(fixture.worktree, fixture.resources, "betaTwo")).contains("Beta.java");

        Files.delete(fixture.root.resolve("src/Alpha.java"));
        fixture.git("add", "-A");
        fixture.git("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "Replace alpha with beta");
        assertThat(service.explore(fixture.worktree, fixture.resources, "alphaOne")).doesNotContain("Alpha.java");
        assertThat(fixture.resources.runtimeDirectory().resolve("codegraph.head")).hasContent(fixture.git("rev-parse", "HEAD") + "\n");
    }

    @Test
    void corruptIndexIsDiscardedThenRebuilt(@TempDir Path temp)
            throws IOException
    {
        assumeBundle();
        Fixture fixture = Fixture.create(temp, git);
        PinnedCodeGraphService service = service(PinnedCodeGraphService.DEFAULT_RESULT_LIMIT_BYTES, LONG);
        service.explore(fixture.worktree, fixture.resources, "alphaOne");
        Path database = fixture.resources.runtimeDirectory().resolve("codegraph").resolve("codegraph.db");
        Files.writeString(database, "not a database");

        assertThatThrownBy(() -> service.explore(fixture.worktree, fixture.resources, "alphaOne")).isInstanceOf(CodeGraphException.class);
        assertThat(fixture.resources.runtimeDirectory().resolve("codegraph")).doesNotExist();
        assertThat(service.explore(fixture.worktree, fixture.resources, "alphaOne")).contains("Alpha.java");
    }

    @Test
    void twoTasksExploreConcurrently(@TempDir Path temp)
            throws IOException
    {
        assumeBundle();
        Fixture one = Fixture.create(temp.resolve("one"), git);
        Fixture two = Fixture.create(temp.resolve("two"), git);
        two.write("src/Gamma.java", "public class Gamma\n{\n    public static int gammaThree()\n    {\n        return 3;\n    }\n}\n");
        PinnedCodeGraphService service = service(PinnedCodeGraphService.DEFAULT_RESULT_LIMIT_BYTES, LONG);

        CompletableFuture<String> first = CompletableFuture.supplyAsync(() -> service.explore(one.worktree, one.resources, "alphaOne"));
        CompletableFuture<String> second = CompletableFuture.supplyAsync(() -> service.explore(two.worktree, two.resources, "gammaThree"));
        assertThat(first.join()).contains("Alpha.java").doesNotContain("Gamma.java");
        assertThat(second.join()).contains("Gamma.java");
    }

    @Test
    void truncatesLongResults(@TempDir Path temp)
            throws IOException
    {
        assumeBundle();
        Fixture fixture = Fixture.create(temp, git);
        String result = service(200, LONG).explore(fixture.worktree, fixture.resources, "alphaOne");
        assertThat(result).endsWith(PinnedCodeGraphService.TRUNCATION_MARKER);
        assertThat(result.length()).isLessThan(200 + PinnedCodeGraphService.TRUNCATION_MARKER.length() + 1);
    }

    @Test
    void queryTimeoutIsARecoverableFailure(@TempDir Path temp)
            throws IOException
    {
        assumeBundle();
        Fixture fixture = Fixture.create(temp, git);
        PinnedCodeGraphService service = new PinnedCodeGraphService(
                new CodeGraphCli(BUNDLE, git), git, baselines, PinnedCodeGraphService.DEFAULT_RESULT_LIMIT_BYTES, LONG, Duration.ofMillis(1), LONG);
        assertThatThrownBy(() -> service.explore(fixture.worktree, fixture.resources, "alphaOne"))
                .isInstanceOf(CodeGraphException.class)
                .hasMessageContaining("timed out");
    }

    @Test
    void deleteIndexRemovesEveryDerivedFile(@TempDir Path temp)
            throws IOException
    {
        assumeBundle();
        Fixture fixture = Fixture.create(temp, git);
        PinnedCodeGraphService service = service(PinnedCodeGraphService.DEFAULT_RESULT_LIMIT_BYTES, LONG);
        service.explore(fixture.worktree, fixture.resources, "alphaOne");
        service.deleteIndex(fixture.resources);
        assertThat(fixture.resources.runtimeDirectory().resolve("codegraph")).doesNotExist();
        assertThat(fixture.resources.runtimeDirectory().resolve("codegraph.head")).doesNotExist();
        assertThat(fixture.resources.runtimeDirectory().resolve("codegraph-home")).doesNotExist();
        service.deleteIndex(fixture.resources);
    }

    @Test
    void missingBundleIsUnavailableNotAnError(@TempDir Path temp)
            throws IOException
    {
        Fixture fixture = Fixture.create(temp, git);
        CodeGraphCli cli = new CodeGraphCli(Optional.of(temp.resolve("no-bundle")), git);
        assertThat(cli.available()).isFalse();
        PinnedCodeGraphService service = new PinnedCodeGraphService(cli, git, baselines);
        assertThatThrownBy(() -> service.explore(fixture.worktree, fixture.resources, "alphaOne"))
                .isInstanceOf(CodeGraphException.class)
                .hasMessage("CodeGraph is not installed");
        service.deleteIndex(fixture.resources);
    }

    @Test
    void rejectsBlankAndOversizedQueries(@TempDir Path temp)
            throws IOException
    {
        Fixture fixture = Fixture.create(temp, git);
        PinnedCodeGraphService service = new PinnedCodeGraphService(new CodeGraphCli(Optional.empty(), git), git, baselines);
        assertThatThrownBy(() -> service.explore(fixture.worktree, fixture.resources, " ")).isInstanceOf(IllegalArgumentException.class);
        String oversized = "x".repeat(PinnedCodeGraphService.MAXIMUM_QUERY_LENGTH + 1);
        assertThatThrownBy(() -> service.explore(fixture.worktree, fixture.resources, oversized)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rebuildsAboveFivePercentOfTrackedFilesOrWhenGitCannotCompare()
    {
        assertThat(PinnedCodeGraphService.rebuildNeeded(0, 1000)).isFalse();
        assertThat(PinnedCodeGraphService.rebuildNeeded(50, 1000)).isFalse();
        assertThat(PinnedCodeGraphService.rebuildNeeded(51, 1000)).isTrue();
        assertThat(PinnedCodeGraphService.rebuildNeeded(-1, 1000)).isTrue();
        assertThat(PinnedCodeGraphService.rebuildNeeded(1, 0)).isTrue();
    }

    private PinnedCodeGraphService service(int resultLimitBytes, Duration timeout)
    {
        return new PinnedCodeGraphService(new CodeGraphCli(BUNDLE, git), git, baselines, resultLimitBytes, timeout, timeout, timeout);
    }

    private static void assumeBundle()
    {
        assumeTrue(BUNDLE.isPresent(), "vendored CodeGraph bundle missing; run scripts/codegraph-bundle.mjs");
    }

    static Optional<Path> locateBundle()
    {
        String configured = System.getenv("BYTEQUAY_CODEGRAPH_HOME");
        Path home = configured == null || configured.isBlank()
                ? Path.of("..", "..", "frontend", "vendor", "codegraph")
                : Path.of(configured);
        home = home.toAbsolutePath().normalize();
        return Files.isRegularFile(home.resolve("BYTEQUAY_BUNDLE")) ? Optional.of(home) : Optional.empty();
    }

    private record Fixture(Path root, TaskWorktree worktree, TaskResources resources, GitCommandRunner git)
    {
        static Fixture create(Path base, GitCommandRunner git)
                throws IOException
        {
            TaskId taskId = TaskId.random();
            Path root = base.resolve(taskId.toString()).toAbsolutePath().normalize();
            Files.createDirectories(root.resolve("src"));
            Files.writeString(root.resolve("src/Alpha.java"), "public class Alpha\n{\n    public static int alphaOne()\n    {\n        return 1;\n    }\n}\n");
            run(git, root, "init", "-q");
            run(git, root, "add", "-A");
            run(git, root, "-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "-m", "Alpha");
            String head = run(git, root, "rev-parse", "HEAD");
            Path runtime = base.resolve("runtime-" + taskId).toAbsolutePath().normalize();
            Files.createDirectories(runtime);
            TaskWorktree worktree = new TaskWorktree(taskId, "workspace", new RepositoryIdentity("owner", "name"), root, "bytequay/task/" + taskId, head);
            TaskResources resources = new TaskResources(runtime, runtime.resolve("events.jsonl"), runtime.resolve("blobs"), runtime.resolve("provider").resolve("scratch"));
            return new Fixture(root, worktree, resources, git);
        }

        String git(String... arguments)
        {
            return run(git, root, arguments);
        }

        void write(String relative, String content)
                throws IOException
        {
            Files.writeString(root.resolve(relative), content);
        }

        private static String run(GitCommandRunner git, Path directory, String... arguments)
        {
            Result result = git.run(directory, arguments);
            checkState(result.successful(), "git %s failed: %s", String.join(" ", arguments), result.output());
            return result.output().strip();
        }
    }
}
