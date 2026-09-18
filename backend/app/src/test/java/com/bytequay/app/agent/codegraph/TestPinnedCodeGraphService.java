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
import com.bytequay.agent.core.CodeSymbol;
import com.bytequay.agent.core.CodeSymbolResult;
import com.bytequay.agent.core.RepositoryIdentity;
import com.bytequay.agent.core.TaskId;
import com.bytequay.agent.core.TaskResources;
import com.bytequay.agent.core.TaskWorktree;
import com.bytequay.app.git.GitCommandRunner;
import com.bytequay.app.git.GitCommandRunner.Result;
import com.google.common.collect.ImmutableList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.InOrder;

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

import static com.google.common.base.Preconditions.checkState;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TestPinnedCodeGraphService
{
    private static final Optional<Path> BUNDLE = locateBundle();
    private static final Duration LONG = Duration.ofMinutes(5);

    // Trimmed from the pinned CLI's real output.
    private static final String QUERY_OUTPUT = """
            [
              {
                "node": {
                  "id": "method:527b9bbf19ca484e2a8b5e2449e7e0a0", "kind": "method", "name": "rendersWidget", "qualifiedName": "demo::TestWidget::rendersWidget",
                  "filePath": "src/test/java/demo/TestWidget.java", "language": "java", "startLine": 7, "endLine": 11, "startColumn": 4, "endColumn": 5,
                  "signature": "void ()", "visibility": null, "isExported": false, "updatedAt": 1789452770367
                },
                "score": 91.10317229733234
              }
            ]
            """;
    private static final String FILE_OUTPUT = """
            [{"id":"file:src/test/java/demo/TestWidget.java","kind":"file","name":"TestWidget.java","qualifiedName":"src/test/java/demo/TestWidget.java",\
            "filePath":"src/test/java/demo/TestWidget.java","language":"java","startLine":1,"endLine":21,"startColumn":0,"endColumn":0,"visibility":null},\
            {"id":"class:4a846482d935f6e9700c3a6accb9939b","kind":"class","name":"TestWidget","filePath":"src/test/java/demo/TestWidget.java",\
            "language":"java","startLine":5,"endLine":20,"startColumn":0,"endColumn":1,"signature":null}]
            """;

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
                new CodeGraphCli(BUNDLE, git, ImmutableList.of()), git, baselines, PinnedCodeGraphService.DEFAULT_RESULT_LIMIT_BYTES, LONG, Duration.ofMillis(1), LONG, LONG, Runnable::run);
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
        CodeGraphCli cli = new CodeGraphCli(Optional.of(temp.resolve("no-bundle")), git, ImmutableList.of());
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
        PinnedCodeGraphService service = new PinnedCodeGraphService(new CodeGraphCli(Optional.empty(), git, ImmutableList.of()), git, baselines);
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

    @Test
    void symbolReadsRunThePatchedBundle(@TempDir Path temp)
            throws IOException
    {
        assumeBundle();
        Fixture fixture = Fixture.create(temp, git);
        List<Runnable> queued = new ArrayList<>();
        PinnedCodeGraphService service = new PinnedCodeGraphService(
                new CodeGraphCli(BUNDLE, git, ImmutableList.of()), git, baselines, PinnedCodeGraphService.DEFAULT_RESULT_LIMIT_BYTES, LONG, LONG, LONG, Duration.ofSeconds(5), queued::add);
        assertThat(service.searchSymbols(fixture.worktree, fixture.resources, "alphaOne", 5)).isEqualTo(CodeSymbolResult.UPDATING);
        queued.forEach(Runnable::run);

        CodeSymbol alphaOne = new CodeSymbol("alphaOne", "method", Optional.of("Alpha::alphaOne"), "src/Alpha.java", 3, 4, 6, Optional.of("int ()"));
        assertThat(service.searchSymbols(fixture.worktree, fixture.resources, "alphaOne", 5).symbols()).containsExactly(alphaOne);
        assertThat(service.fileSymbols(fixture.worktree, fixture.resources, "src/Alpha.java").symbols())
                .extracting(CodeSymbol::kind, CodeSymbol::name)
                .containsExactly(tuple("file", "Alpha.java"), tuple("class", "Alpha"), tuple("method", "alphaOne"));
        assertThat(queued).hasSize(1);
    }

    @Test
    void symbolReadsSyncThenMapTheCliJson(@TempDir Path temp)
            throws IOException
    {
        Fixture fixture = Fixture.create(temp, git);
        fixture.markIndexCurrent();
        CodeGraphCli cli = fakeCli();
        when(cli.run(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            List<String> arguments = invocation.getArgument(4);
            return new Result(0, switch (arguments.get(0)) {
                case "query" -> QUERY_OUTPUT;
                case "node" -> FILE_OUTPUT;
                default -> "";
            }, false);
        });
        List<Runnable> queued = new ArrayList<>();
        PinnedCodeGraphService service = readService(cli, Duration.ofSeconds(5), queued);

        CodeSymbol method = new CodeSymbol("rendersWidget", "method", Optional.of("demo::TestWidget::rendersWidget"), "src/test/java/demo/TestWidget.java", 7, 4, 11, Optional.of("void ()"));
        assertThat(service.searchSymbols(fixture.worktree, fixture.resources, "-rendersWidget", 50)).isEqualTo(CodeSymbolResult.ready(ImmutableList.of(method)));
        assertThat(service.fileSymbols(fixture.worktree, fixture.resources, "src/test/java/demo/TestWidget.java")).isEqualTo(CodeSymbolResult.ready(ImmutableList.of(
                new CodeSymbol("TestWidget.java", "file", Optional.of("src/test/java/demo/TestWidget.java"), "src/test/java/demo/TestWidget.java", 1, 0, 21, Optional.empty()),
                new CodeSymbol("TestWidget", "class", Optional.empty(), "src/test/java/demo/TestWidget.java", 5, 0, 20, Optional.empty()))));

        InOrder order = inOrder(cli);
        order.verify(cli).run(any(), any(), any(), any(), eq(ImmutableList.of("sync", fixture.root.toString(), "--quiet")));
        order.verify(cli).run(any(), any(), any(), any(), eq(ImmutableList.of("query", "--json", "--limit", "50", "--", "-rendersWidget")));
        order.verify(cli).run(any(), any(), any(), any(), eq(ImmutableList.of("sync", fixture.root.toString(), "--quiet")));
        order.verify(cli).run(any(), any(), any(), any(), eq(ImmutableList.of("node", "--file=src/test/java/demo/TestWidget.java", "--symbols-only", "--json")));
        assertThat(queued).isEmpty();
    }

    @Test
    void symbolReadIsBusyWhileAnotherOperationHoldsTheIndex(@TempDir Path temp)
            throws Exception
    {
        Fixture fixture = Fixture.create(temp, git);
        CodeGraphCli cli = fakeCli();
        CountDownLatch building = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        when(cli.run(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            building.countDown();
            assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
            return new Result(1, "stopped", false);
        });
        PinnedCodeGraphService service = readService(cli, Duration.ofMillis(50), new ArrayList<>());
        CompletableFuture<Void> prepare = CompletableFuture.runAsync(() -> service.prepare(fixture.worktree, fixture.resources));
        try {
            assertThat(building.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(service.searchSymbols(fixture.worktree, fixture.resources, "alphaOne", 10)).isEqualTo(CodeSymbolResult.BUSY);
            assertThat(service.fileSymbols(fixture.worktree, fixture.resources, "src/Alpha.java")).isEqualTo(CodeSymbolResult.BUSY);
        }
        finally {
            release.countDown();
        }
        prepare.join();
    }

    @Test
    void symbolReadQueuesPreparationInsteadOfRebuildingOrDiscarding(@TempDir Path temp)
            throws IOException
    {
        Fixture fixture = Fixture.create(temp, git);
        CodeGraphCli cli = fakeCli();
        List<Runnable> queued = new ArrayList<>();
        PinnedCodeGraphService service = readService(cli, Duration.ofSeconds(5), queued);

        // No index yet.
        assertThat(service.searchSymbols(fixture.worktree, fixture.resources, "alphaOne", 10)).isEqualTo(CodeSymbolResult.UPDATING);
        assertThat(queued).hasSize(1);

        // HEAD moved since the index was synchronized.
        fixture.markIndexCurrent();
        Path database = fixture.resources.runtimeDirectory().resolve("codegraph").resolve("codegraph.db");
        fixture.git("-c", "user.name=t", "-c", "user.email=t@t", "commit", "-q", "--allow-empty", "-m", "Empty");
        assertThat(service.fileSymbols(fixture.worktree, fixture.resources, "src/Alpha.java")).isEqualTo(CodeSymbolResult.UPDATING);

        assertThat(queued).hasSize(2);
        verify(cli, never()).run(any(), any(), any(), any(), any());
        assertThat(database).hasContent("index");

        // The queued work is the ordinary prepare, which brings the index up to the new HEAD.
        when(cli.run(any(), any(), any(), any(), any())).thenAnswer(invocation -> {
            List<String> arguments = invocation.getArgument(4);
            return new Result(0, arguments.get(0).equals("query") ? QUERY_OUTPUT : "", false);
        });
        queued.getLast().run();
        assertThat(fixture.resources.runtimeDirectory().resolve("codegraph.head")).hasContent(fixture.git("rev-parse", "HEAD") + "\n");

        // Same HEAD with more than five percent of the files uncommitted: a rebuild would leave them uncommitted,
        // so the read syncs and queries in place instead of queuing a prepare after every read.
        fixture.write("src/Alpha.java", "public class Alpha {}\n");
        for (int read = 0; read < 2; read++) {
            assertThat(service.searchSymbols(fixture.worktree, fixture.resources, "alphaOne", 10).status()).isEqualTo(CodeSymbolResult.Status.READY);
        }
        assertThat(queued).hasSize(2);
        verify(cli, times(3)).run(any(), any(), any(), any(), eq(ImmutableList.of("sync", fixture.root.toString(), "--quiet")));
        verify(cli, times(2)).run(any(), any(), any(), any(), eq(ImmutableList.of("query", "--json", "--limit", "10", "--", "alphaOne")));
        verify(cli, never()).run(any(), any(), any(), any(), eq(ImmutableList.of("index", fixture.root.toString(), "--quiet")));
        verify(cli, never()).run(any(), any(), any(), any(), eq(ImmutableList.of("init", fixture.root.toString(), "--yes")));
        assertThat(database).hasContent("index");
    }

    @Test
    void failedOrUnreadableSymbolReadsAreUnavailable(@TempDir Path temp)
            throws IOException
    {
        Fixture fixture = Fixture.create(temp, git);
        fixture.markIndexCurrent();
        Path database = fixture.resources.runtimeDirectory().resolve("codegraph").resolve("codegraph.db");
        List<Runnable> queued = new ArrayList<>();
        for (Result queryResult : ImmutableList.of(
                new Result(0, "[{\"node\": {\"name\": \"cut", false),
                new Result(0, QUERY_OUTPUT, true),
                new Result(0, "Search failed: not json", false),
                new Result(0, "{\"node\": {}}", false),
                new Result(0, "[{\"node\": {\"name\": \"x\", \"kind\": \"method\", \"filePath\": \"A.java\", \"startLine\": \"7\"}}]", false),
                new Result(1, QUERY_OUTPUT, false))) {
            CodeGraphCli cli = fakeCli();
            when(cli.run(any(), any(), any(), any(), eq(ImmutableList.of("sync", fixture.root.toString(), "--quiet")))).thenReturn(new Result(0, "", false));
            when(cli.run(any(), any(), any(), any(), eq(ImmutableList.of("query", "--json", "--limit", "10", "--", "alphaOne")))).thenReturn(queryResult);
            assertThat(readService(cli, Duration.ofSeconds(5), queued).searchSymbols(fixture.worktree, fixture.resources, "alphaOne", 10))
                    .as(queryResult.output())
                    .isEqualTo(CodeSymbolResult.UNAVAILABLE);
        }

        CodeGraphCli failingSync = fakeCli();
        when(failingSync.run(any(), any(), any(), any(), any())).thenReturn(new Result(1, "sync failed", false));
        PinnedCodeGraphService unsynchronized = readService(failingSync, Duration.ofSeconds(5), queued);
        assertThat(unsynchronized.fileSymbols(fixture.worktree, fixture.resources, "src/Alpha.java")).isEqualTo(CodeSymbolResult.UNAVAILABLE);
        verify(failingSync, never()).run(any(), any(), any(), any(), eq(ImmutableList.of("node", "--file=src/Alpha.java", "--symbols-only", "--json")));
        assertThat(database).hasContent("index");
        assertThat(queued).isEmpty();

        CodeGraphCli missing = mock(CodeGraphCli.class);
        assertThat(readService(missing, Duration.ofSeconds(5), queued).searchSymbols(fixture.worktree, fixture.resources, "alphaOne", 10)).isEqualTo(CodeSymbolResult.UNAVAILABLE);
        verify(missing, never()).run(any(), any(), any(), any(), any());
    }

    @Test
    void rejectsInvalidSymbolReads(@TempDir Path temp)
            throws IOException
    {
        Fixture fixture = Fixture.create(temp, git);
        PinnedCodeGraphService service = readService(fakeCli(), Duration.ofSeconds(5), new ArrayList<>());
        assertThatThrownBy(() -> service.searchSymbols(fixture.worktree, fixture.resources, " ", 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.searchSymbols(fixture.worktree, fixture.resources, "x".repeat(PinnedCodeGraphService.MAXIMUM_QUERY_LENGTH + 1), 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.searchSymbols(fixture.worktree, fixture.resources, "alphaOne", 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.searchSymbols(fixture.worktree, fixture.resources, "alphaOne", PinnedCodeGraphService.MAXIMUM_SYMBOL_LIMIT + 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> service.fileSymbols(fixture.worktree, fixture.resources, "")).isInstanceOf(IllegalArgumentException.class);
    }

    private static CodeGraphCli fakeCli()
    {
        CodeGraphCli cli = mock(CodeGraphCli.class);
        when(cli.available()).thenReturn(true);
        return cli;
    }

    private PinnedCodeGraphService readService(CodeGraphCli cli, Duration readWait, List<Runnable> queued)
    {
        return new PinnedCodeGraphService(cli, git, baselines, PinnedCodeGraphService.DEFAULT_RESULT_LIMIT_BYTES, LONG, LONG, LONG, readWait, queued::add);
    }

    private PinnedCodeGraphService service(int resultLimitBytes, Duration timeout)
    {
        return new PinnedCodeGraphService(new CodeGraphCli(BUNDLE, git, ImmutableList.of()), git, baselines, resultLimitBytes, timeout, timeout, timeout, timeout, Runnable::run);
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

        /** Leaves a placeholder index that records the current HEAD, as a finished prepare would. */
        void markIndexCurrent()
                throws IOException
        {
            Path index = Files.createDirectories(resources.runtimeDirectory().resolve("codegraph"));
            Files.writeString(index.resolve("codegraph.db"), "index");
            Files.writeString(resources.runtimeDirectory().resolve("codegraph.head"), git("rev-parse", "HEAD") + "\n");
        }

        private static String run(GitCommandRunner git, Path directory, String... arguments)
        {
            Result result = git.run(directory, arguments);
            checkState(result.successful(), "git %s failed: %s", String.join(" ", arguments), result.output());
            return result.output().strip();
        }
    }
}
